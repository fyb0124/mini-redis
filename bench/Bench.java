import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-socket load generator for mini-redis.
 *
 * Each worker owns one TCP connection and issues SET/GET sequentially
 * (no pipelining), timing every operation from just before write() to just
 * after the full reply has been read. Reported latency is therefore
 * client-observed end-to-end latency, including client encoding and syscalls.
 *
 * Usage: java Bench [host] [port] [threads] [opsPerThread]
 *        defaults: 127.0.0.1 6399 8 20000
 */
public final class Bench {

    private static final String VALUE = "0123456789abcdef";
    private static final int KEY_SPACE = 1000;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6399;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int ops = args.length > 3 ? Integer.parseInt(args[3]) : 20_000;

        System.out.println("=== mini-redis bench ===");
        System.out.println("target      : " + host + ":" + port);
        System.out.println("threads     : " + threads);
        System.out.println("ops/thread  : " + ops + " SET + " + ops + " GET");
        System.out.println("total ops   : " + (long) threads * ops * 2);
        System.out.println("java        : " + System.getProperty("java.version"));
        System.out.println("os          : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        System.out.println("processors  : " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        long[][] setLatencies = new long[threads][ops];
        long[][] getLatencies = new long[threads][ops];
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread worker = new Thread(() -> {
                try (Socket socket = new Socket(host, port)) {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(30_000);
                    InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
                    byte[][] setCommands = buildCommands("SET", id, true);
                    byte[][] getCommands = buildCommands("GET", id, false);

                    // warmup: 2000 mixed operations, not measured
                    for (int i = 0; i < 2000; i++) {
                        out.write(setCommands[i % KEY_SPACE]);
                        out.flush();
                        readReply(in);
                        out.write(getCommands[i % KEY_SPACE]);
                        out.flush();
                        readReply(in);
                    }

                    ready.countDown();
                    start.await();

                    for (int i = 0; i < ops; i++) {
                        int slot = i % KEY_SPACE;
                        long t0 = System.nanoTime();
                        out.write(setCommands[slot]);
                        out.flush();
                        readReply(in);
                        setLatencies[id][i] = System.nanoTime() - t0;

                        t0 = System.nanoTime();
                        out.write(getCommands[slot]);
                        out.flush();
                        readReply(in);
                        getLatencies[id][i] = System.nanoTime() - t0;
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "bench-worker-" + id);
            worker.setDaemon(true);
            worker.start();
        }

        ready.await();
        System.out.println("warmup complete, starting measurement...");
        long begin = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedNanos = System.nanoTime() - begin;

        if (error.get() != null) {
            System.err.println("bench failed: " + error.get());
            error.get().printStackTrace();
            System.exit(1);
        }

        long totalOps = (long) threads * ops * 2;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double qps = totalOps / seconds;

        long[] setAll = flatten(setLatencies);
        long[] getAll = flatten(getLatencies);
        Arrays.sort(setAll);
        Arrays.sort(getAll);
        long[] all = new long[setAll.length + getAll.length];
        System.arraycopy(setAll, 0, all, 0, setAll.length);
        System.arraycopy(getAll, 0, all, setAll.length, getAll.length);
        Arrays.sort(all);

        System.out.println();
        System.out.printf(Locale.ROOT, "elapsed        : %.3f s%n", seconds);
        System.out.printf(Locale.ROOT, "throughput     : %.0f ops/s (%.3f M ops/s)%n",
                qps, qps / 1_000_000.0);
        System.out.printf(Locale.ROOT, "SET throughput : %.0f ops/s%n", (long) threads * ops / seconds);
        System.out.printf(Locale.ROOT, "GET throughput : %.0f ops/s%n", (long) threads * ops / seconds);
        System.out.println();
        System.out.println("latency (client-observed, microseconds):");
        printLatency("SET", setAll);
        printLatency("GET", getAll);
        printLatency("ALL", all);
        System.out.println();
        System.out.println("(P50/P99 computed from " + all.length + " individual samples)");
    }

    private static void printLatency(String label, long[] sorted) {
        System.out.printf(Locale.ROOT,
                "  %-4s  p50=%8.1f us   p99=%8.1f us   p999=%8.1f us   max=%8.1f us%n",
                label,
                percentile(sorted, 0.50) / 1000.0,
                percentile(sorted, 0.99) / 1000.0,
                percentile(sorted, 0.999) / 1000.0,
                sorted[sorted.length - 1] / 1000.0);
    }

    private static long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    private static long[] flatten(long[][] arrays) {
        int total = 0;
        for (long[] a : arrays) {
            total += a.length;
        }
        long[] out = new long[total];
        int off = 0;
        for (long[] a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    private static byte[][] buildCommands(String verb, int threadId, boolean withValue) {
        byte[][] commands = new byte[KEY_SPACE][];
        for (int k = 0; k < KEY_SPACE; k++) {
            String key = "bench:" + threadId + ":" + k;
            if (withValue) {
                commands[k] = encode(verb, key, VALUE);
            } else {
                commands[k] = encode(verb, key);
            }
        }
        return commands;
    }

    private static byte[] encode(String... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        write(bos, "*" + parts.length + "\r\n");
        for (String part : parts) {
            byte[] data = part.getBytes(StandardCharsets.UTF_8);
            write(bos, "$" + data.length + "\r\n");
            bos.write(data, 0, data.length);
            write(bos, "\r\n");
        }
        return bos.toByteArray();
    }

    private static void write(ByteArrayOutputStream bos, String s) {
        byte[] data = s.getBytes(StandardCharsets.US_ASCII);
        bos.write(data, 0, data.length);
    }

    /** Reads one RESP reply and validates it; returns the payload for GET. */
    private static String readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("server closed the connection");
        }
        String line = readLine(in);
        switch (type) {
            case '+':
                return line;
            case '-':
                throw new IOException("server error: " + line);
            case ':':
                return line;
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] data = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(data, off, len - off);
                    if (r == -1) {
                        throw new EOFException();
                    }
                    off += r;
                }
                if (in.read() != '\r' || in.read() != '\n') {
                    throw new IOException("malformed bulk reply");
                }
                return new String(data, StandardCharsets.UTF_8);
            }
            default:
                throw new IOException("unexpected reply type: " + (char) type);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException();
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                bos.write(b);
            }
        }
        return bos.toString(StandardCharsets.UTF_8);
    }
}