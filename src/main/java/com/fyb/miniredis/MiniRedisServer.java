package com.fyb.miniredis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP front end: ServerSocket + fixed thread pool (default CPU*4 threads).
 * One connection is owned by one worker thread for its whole lifetime, which
 * makes request handling sequential per connection (like Redis) while
 * different connections run in parallel.
 */
public final class MiniRedisServer {

    private static final AtomicInteger WORKER_IDS = new AtomicInteger();

    private final Config config;
    private final KeyValueStore store;
    private final AofLogger aof;
    private final CommandProcessor processor;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ThreadPoolExecutor pool;
    private Thread acceptThread;
    private Thread sweeperThread;
    private Thread shutdownHook;

    public MiniRedisServer(Config config) throws IOException {
        this.config = config;
        this.store = new KeyValueStore(config.maxmemory);
        this.aof = new AofLogger(new File(config.aofPath), config.appendonly, config.appendfsync);
        this.processor = new CommandProcessor(store, aof, config);
        this.processor.setConnectionCountSupplier(connectionCount::get);
        replayAof();
    }

    private void replayAof() {
        if (!config.appendonly) {
            return;
        }
        List<String[]> commands = AofLogger.load(new File(config.aofPath));
        if (commands.isEmpty()) {
            return;
        }
        int applied = 0;
        int failed = 0;
        for (String[] command : commands) {
            Object reply = processor.execute(command, true);
            if (reply instanceof RespWriter.ErrorReply) {
                failed++;
                System.err.println("[aof] replay failed for " + command[0] + ": "
                        + ((RespWriter.ErrorReply) reply).message);
            } else {
                applied++;
            }
        }
        System.out.println("[miniredis] AOF replay: " + applied + " applied, " + failed
                + " failed, " + commands.size() + " total commands");
    }

    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("server is already running");
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.port));
        running = true;

        int threads = Math.max(1, config.threads);
        pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "miniredis-worker-" + WORKER_IDS.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });

        acceptThread = new Thread(this::acceptLoop, "miniredis-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        sweeperThread = new Thread(this::sweepLoop, "miniredis-sweeper");
        sweeperThread.setDaemon(true);
        sweeperThread.start();

        shutdownHook = new Thread(this::stop, "miniredis-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("[miniredis] listening on 0.0.0.0:" + config.port
                + " (" + config.describe() + ")");
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                clients.add(socket);
                connectionCount.incrementAndGet();
                final Socket accepted = socket;
                pool.execute(() -> handle(accepted));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket);
            } catch (IOException e) {
                if (running) {
                    System.err.println("[miniredis] accept failed: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024)) {
            RespParser parser = new RespParser(in);
            while (running) {
                String[] command;
                try {
                    command = parser.readCommand();
                } catch (RespParser.ProtocolException pe) {
                    RespWriter.write(new RespWriter.ErrorReply(
                            "ERR Protocol error: " + pe.getMessage()), out);
                    out.flush();
                    break;
                } catch (IOException e) {
                    break;
                }
                if (command == null) {
                    break;
                }
                if (command.length == 0) {
                    continue;
                }
                Object reply = processor.execute(command, false);
                RespWriter.write(reply, out);
                out.flush();
                if (command[0].equalsIgnoreCase("QUIT")) {
                    break;
                }
            }
        } catch (IOException e) {
            // Connection reset by peer: normal, nothing to do.
        } finally {
            clients.remove(socket);
            connectionCount.decrementAndGet();
        }
    }

    private void sweepLoop() {
        while (running) {
            try {
                Thread.sleep(config.sweepIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                store.purgeExpired(200);
            } catch (RuntimeException e) {
                System.err.println("[miniredis] sweeper error: " + e);
            }
        }
    }

    /** Idempotent, safe to call from a shutdown hook or from tests. */
    public void stop() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        System.out.println("[miniredis] shutting down");
        closeQuietly(serverSocket);
        for (Socket s : clients) {
            closeQuietly(s);
        }
        clients.clear();
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        aof.close();
        if (sweeperThread != null) {
            sweeperThread.interrupt();
        }
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down
            }
        }
        stopped.countDown();
    }

    public void await() throws InterruptedException {
        stopped.await();
    }

    public int port() {
        return config.port;
    }

    public KeyValueStore store() {
        return store;
    }

    public CommandProcessor processor() {
        return processor;
    }

    public boolean isRunning() {
        return running;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    public static void main(String[] args) {
        Config config;
        try {
            config = Config.load(args);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("[miniredis] configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        MiniRedisServer server;
        try {
            server = new MiniRedisServer(config);
        } catch (IOException e) {
            System.err.println("[miniredis] startup failed: " + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            server.start();
            server.await();
        } catch (IOException e) {
            System.err.println("[miniredis] cannot bind port " + config.port + ": " + e.getMessage());
            server.stop();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop();
        }
    }
}