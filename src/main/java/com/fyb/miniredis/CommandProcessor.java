package com.fyb.miniredis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Command registry and dispatcher. Generic/connection commands live here;
 * string and collection commands are registered by StringCommands and
 * CollectionCommands to keep each file small and focused.
 */
public final class CommandProcessor {

    public static final String VERSION = "1.0.0";

    public interface Handler {
        Object execute(String[] args);
    }

    final KeyValueStore store;
    final AofLogger aof;
    final Config config;

    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, Integer> minArgs = new HashMap<>();
    private final AtomicLong commandsProcessed = new AtomicLong();
    private final long startTime = System.currentTimeMillis();

    private boolean replay;
    private LongSupplier connectionCount = () -> 0;

    public CommandProcessor(KeyValueStore store, AofLogger aof, Config config) {
        this.store = store;
        this.aof = aof;
        this.config = config;
        registerGenericCommands();
        StringCommands.register(this);
        CollectionCommands.register(this);
    }

    void setConnectionCountSupplier(LongSupplier supplier) {
        this.connectionCount = supplier;
    }

    void register(String name, int minimumArgs, Handler handler) {
        handlers.put(name, handler);
        minArgs.put(name, minimumArgs);
    }

    public Object execute(String[] args, boolean replayMode) {
        boolean previous = this.replay;
        this.replay = replayMode;
        try {
            if (args.length == 0) {
                return error("ERR empty command");
            }
            String name = args[0].toUpperCase(Locale.ROOT);
            Handler handler = handlers.get(name);
            if (handler == null) {
                return error("ERR unknown command '" + args[0]
                        + "', with args beginning with: " + quoteArgs(args));
            }
            int minimum = minArgs.get(name);
            if (args.length < minimum) {
                return error("ERR wrong number of arguments for '" + name.toLowerCase(Locale.ROOT)
                        + "' command");
            }
            if (!replayMode) {
                commandsProcessed.incrementAndGet();
            }
            return handler.execute(args);
        } catch (KeyValueStore.WrongTypeException e) {
            return error("WRONGTYPE Operation against a key holding the wrong kind of value");
        } catch (NumberFormatException e) {
            return error("ERR value is not an integer or out of range");
        } catch (ArithmeticException e) {
            return error("ERR increment or decrement would overflow");
        } catch (RuntimeException e) {
            System.err.println("[command] internal error: " + e);
            e.printStackTrace();
            return error("ERR internal error: " + e.getMessage());
        } finally {
            this.replay = previous;
        }
    }

    /** Appends to the AOF only for real (non-replay) executions. */
    void log(String... command) {
        if (!replay && aof != null) {
            aof.append(command);
        }
    }

    boolean isReplay() {
        return replay;
    }

    static RespWriter.ErrorReply error(String message) {
        return new RespWriter.ErrorReply(message);
    }

    static RespWriter.SimpleString ok() {
        return new RespWriter.SimpleString("OK");
    }

    private static String quoteArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(args.length, 4);
        for (int i = 1; i < limit; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('\'').append(args[i]).append('\'');
        }
        return sb.toString();
    }

    public long commandsProcessed() {
        return commandsProcessed.get();
    }

    public List<String> commandNames() {
        List<String> names = new ArrayList<>(handlers.size());
        for (String name : handlers.keySet()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        Collections.sort(names);
        return names;
    }

    // ------------------------------------------------------------------
    // Connection / generic commands
    // ------------------------------------------------------------------

    private void registerGenericCommands() {
        register("PING", 1, args -> {
            if (args.length == 1) {
                return new RespWriter.SimpleString("PONG");
            }
            if (args.length == 2) {
                return args[1];
            }
            return error("ERR wrong number of arguments for 'ping' command");
        });

        register("ECHO", 2, args -> {
            if (args.length != 2) {
                return error("ERR wrong number of arguments for 'echo' command");
            }
            return args[1];
        });

        register("SELECT", 2, args -> {
            if (args.length != 2) {
                return error("ERR wrong number of arguments for 'select' command");
            }
            try {
                int index = Integer.parseInt(args[1]);
                if (index == 0) {
                    return ok();
                }
            } catch (NumberFormatException ignored) {
                // fall through to the standard error
            }
            return error("ERR DB index is out of range");
        });

        register("DBSIZE", 1, args -> {
            if (args.length != 1) {
                return error("ERR wrong number of arguments for 'dbsize' command");
            }
            return store.size();
        });

        register("FLUSHDB", 1, args -> {
            if (args.length != 1) {
                return error("ERR wrong number of arguments for 'flushdb' command");
            }
            store.flush();
            log("FLUSHDB");
            return ok();
        });

        register("FLUSHALL", 1, args -> {
            if (args.length != 1) {
                return error("ERR wrong number of arguments for 'flushall' command");
            }
            store.flush();
            log("FLUSHALL");
            return ok();
        });

        register("TYPE", 2, args -> {
            if (args.length != 2) {
                return error("ERR wrong number of arguments for 'type' command");
            }
            return new RespWriter.SimpleString(store.type(args[1]));
        });

        register("KEYS", 2, args -> {
            if (args.length != 2) {
                return error("ERR wrong number of arguments for 'keys' command");
            }
            List<String> matched = store.keysMatching(args[1]);
            return new ArrayList<Object>(matched);
        });

        register("SCAN", 2, args -> scan(args));

        register("COMMAND", 1, args -> {
            if (args.length == 1) {
                List<Object> names = new ArrayList<>(commandNames());
                return names;
            }
            String sub = args[1].toUpperCase(Locale.ROOT);
            if (sub.equals("COUNT") && args.length == 2) {
                return (long) handlers.size();
            }
            if (sub.equals("DOCS") && args.length == 2) {
                return error("ERR COMMAND DOCS is not supported by mini-redis");
            }
            return error("ERR unknown subcommand '" + args[1] + "'. Try COMMAND COUNT.");
        });

        register("INFO", 1, args -> info());

        register("QUIT", 1, args -> {
            if (args.length != 1) {
                return error("ERR wrong number of arguments for 'quit' command");
            }
            return ok();
        });
    }

    private Object scan(String[] args) {
        long cursor;
        try {
            cursor = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            return error("ERR invalid cursor");
        }
        if (cursor < 0) {
            return error("ERR invalid cursor");
        }
        String pattern = "*";
        int count = 10;
        for (int i = 2; i < args.length; i++) {
            String opt = args[i].toUpperCase(Locale.ROOT);
            if (opt.equals("MATCH")) {
                if (i + 1 >= args.length) {
                    return error("ERR syntax error");
                }
                pattern = args[++i];
            } else if (opt.equals("COUNT")) {
                if (i + 1 >= args.length) {
                    return error("ERR syntax error");
                }
                try {
                    count = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    return error("ERR value is not an integer or out of range");
                }
                if (count < 1) {
                    return error("ERR syntax error");
                }
                if (count > 1000) {
                    count = 1000;
                }
            } else {
                return error("ERR syntax error");
            }
        }
        KeyValueStore.ScanResult result = store.scan(cursor, pattern, count);
        List<Object> reply = new ArrayList<>(2);
        reply.add(Long.toString(result.cursor));
        reply.add(new ArrayList<Object>(result.keys));
        return reply;
    }

    private Object info() {
        long keys = store.size();
        long expires = store.expireCount();
        long uptime = Math.max(0, (System.currentTimeMillis() - startTime) / 1000);
        String text = "# Server\r\n"
                + "miniredis_version:" + VERSION + "\r\n"
                + "java_version:" + System.getProperty("java.version") + "\r\n"
                + "uptime_in_seconds:" + uptime + "\r\n"
                + "# Clients\r\n"
                + "connected_clients:" + connectionCount.getAsLong() + "\r\n"
                + "# Stats\r\n"
                + "total_commands_processed:" + commandsProcessed.get() + "\r\n"
                + "expired_keys:" + store.expiredCount() + "\r\n"
                + "evicted_keys:" + store.evictedCount() + "\r\n"
                + "# Keyspace\r\n"
                + "db0:keys=" + keys + ",expires=" + expires + "\r\n";
        return text;
    }
}