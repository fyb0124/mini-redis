package com.fyb.miniredis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * String and expiration commands: SET/GET/MSET/MGET/DEL/EXISTS/APPEND/STRLEN,
 * INCR family, EXPIRE family. Registered into CommandProcessor.
 */
final class StringCommands {

    private StringCommands() {
    }

    static void register(CommandProcessor p) {
        KeyValueStore store = p.store;

        p.register("SET", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'set' command");
            }
            String key = args[1];
            String value = args[2];
            long expireAt = 0;
            boolean nx = false;
            boolean xx = false;
            for (int i = 3; i < args.length; i++) {
                String option = args[i].toUpperCase(Locale.ROOT);
                switch (option) {
                    case "EX", "PX" -> {
                        if (i + 1 >= args.length) {
                            return CommandProcessor.error("ERR syntax error");
                        }
                        long amount;
                        try {
                            amount = Long.parseLong(args[++i]);
                        } catch (NumberFormatException e) {
                            return CommandProcessor.error("ERR value is not an integer or out of range");
                        }
                        if (amount <= 0) {
                            return CommandProcessor.error("ERR invalid expire time in 'set' command");
                        }
                        long millis;
                        try {
                            millis = option.equals("EX") ? Math.multiplyExact(amount, 1000L) : amount;
                            expireAt = Math.addExact(System.currentTimeMillis(), millis);
                        } catch (ArithmeticException e) {
                            return CommandProcessor.error("ERR invalid expire time in 'set' command");
                        }
                    }
                    case "NX" -> nx = true;
                    case "XX" -> xx = true;
                    default -> {
                        return CommandProcessor.error("ERR syntax error");
                    }
                }
            }
            if (nx && xx) {
                return CommandProcessor.error("ERR syntax error");
            }
            boolean written = store.set(key, value, expireAt, nx, xx);
            if (!written) {
                return null;
            }
            p.log("SET", key, value);
            if (expireAt > 0) {
                p.log("PEXPIREAT", key, Long.toString(expireAt));
            }
            return CommandProcessor.ok();
        });

        p.register("GET", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'get' command");
            }
            return store.getString(args[1]);
        });

        p.register("MSET", 3, args -> {
            if (args.length % 2 == 0) {
                return CommandProcessor.error("ERR wrong number of arguments for 'mset' command");
            }
            for (int i = 1; i + 1 < args.length; i += 2) {
                store.put(args[i], Value.string(args[i + 1]), 0);
            }
            p.log(args);
            return CommandProcessor.ok();
        });

        p.register("MGET", 2, args -> {
            List<Object> values = new ArrayList<>(args.length - 1);
            for (int i = 1; i < args.length; i++) {
                KeyValueStore.Entry e = store.getEntry(args[i]);
                if (e == null || e.value.type != Value.Type.STRING) {
                    values.add(null);
                } else {
                    values.add(e.value.str);
                }
            }
            return values;
        });

        p.register("DEL", 2, args -> {
            long removed = 0;
            for (int i = 1; i < args.length; i++) {
                if (store.delete(args[i])) {
                    removed++;
                }
            }
            if (removed > 0) {
                p.log(args);
            }
            return removed;
        });

        p.register("EXISTS", 2, args -> {
            long count = 0;
            for (int i = 1; i < args.length; i++) {
                if (store.exists(args[i])) {
                    count++;
                }
            }
            return count;
        });

        p.register("APPEND", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'append' command");
            }
            int length = store.append(args[1], args[2]);
            p.log(args);
            return (long) length;
        });

        p.register("STRLEN", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'strlen' command");
            }
            return (long) store.stringLength(args[1]);
        });

        p.register("INCR", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'incr' command");
            }
            long value = store.incr(args[1], 1);
            p.log(args);
            return value;
        });

        p.register("DECR", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'decr' command");
            }
            long value = store.incr(args[1], -1);
            p.log(args);
            return value;
        });

        p.register("INCRBY", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'incrby' command");
            }
            long delta = Long.parseLong(args[2]);
            long value = store.incr(args[1], delta);
            p.log(args);
            return value;
        });

        p.register("DECRBY", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'decrby' command");
            }
            long delta = Math.negateExact(Long.parseLong(args[2]));
            long value = store.incr(args[1], delta);
            p.log(args);
            return value;
        });

        p.register("SETNX", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'setnx' command");
            }
            boolean written = store.set(args[1], args[2], 0, true, false);
            if (written) {
                p.log("SET", args[1], args[2]);
                return 1L;
            }
            return 0L;
        });

        p.register("GETSET", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'getset' command");
            }
            String old = store.getSet(args[1], args[2]);
            p.log("SET", args[1], args[2]);
            return old;
        });

        p.register("GETDEL", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'getdel' command");
            }
            String old = store.getDel(args[1]);
            if (old != null) {
                p.log("DEL", args[1]);
            }
            return old;
        });

        p.register("EXPIRE", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'expire' command");
            }
            long seconds = Long.parseLong(args[2]);
            long millis;
            long abs;
            try {
                millis = Math.multiplyExact(seconds, 1000L);
                abs = Math.addExact(System.currentTimeMillis(), millis);
            } catch (ArithmeticException e) {
                return CommandProcessor.error("ERR invalid expire time in 'expire' command");
            }
            boolean changed = store.expireAt(args[1], abs);
            if (changed) {
                p.log("PEXPIREAT", args[1], Long.toString(abs));
                return 1L;
            }
            return 0L;
        });

        p.register("PEXPIRE", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'pexpire' command");
            }
            long millis = Long.parseLong(args[2]);
            long abs;
            try {
                abs = Math.addExact(System.currentTimeMillis(), millis);
            } catch (ArithmeticException e) {
                return CommandProcessor.error("ERR invalid expire time in 'pexpire' command");
            }
            boolean changed = store.expireAt(args[1], abs);
            if (changed) {
                p.log("PEXPIREAT", args[1], Long.toString(abs));
                return 1L;
            }
            return 0L;
        });

        p.register("PEXPIREAT", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'pexpireat' command");
            }
            long abs = Long.parseLong(args[2]);
            boolean changed = store.expireAt(args[1], abs);
            if (changed) {
                p.log("PEXPIREAT", args[1], Long.toString(abs));
                return 1L;
            }
            return 0L;
        });

        p.register("TTL", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'ttl' command");
            }
            long ms = store.ttlMillis(args[1]);
            if (ms == -2 || ms == -1) {
                return ms;
            }
            return (ms + 999) / 1000;
        });

        p.register("PTTL", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'pttl' command");
            }
            return store.ttlMillis(args[1]);
        });

        p.register("PERSIST", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'persist' command");
            }
            boolean changed = store.persist(args[1]);
            if (changed) {
                p.log(args);
                return 1L;
            }
            return 0L;
        });
    }
}