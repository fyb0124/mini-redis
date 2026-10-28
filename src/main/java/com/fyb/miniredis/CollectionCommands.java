package com.fyb.miniredis;

import java.util.ArrayList;
import java.util.List;

/**
 * List, hash and set commands. All mutations are applied inside
 * KeyValueStore.compute() calls, so concurrent pushes/HSETs on the same key
 * cannot lose updates.
 */
final class CollectionCommands {

    private CollectionCommands() {
    }

    static void register(CommandProcessor p) {
        KeyValueStore store = p.store;

        // ---------------- lists ----------------

        p.register("LPUSH", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'lpush' command");
            }
            List<String> values = slice(args, 2);
            long length = store.collections().listPush(args[1], true, values);
            p.log(args);
            return length;
        });

        p.register("RPUSH", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'rpush' command");
            }
            List<String> values = slice(args, 2);
            long length = store.collections().listPush(args[1], false, values);
            p.log(args);
            return length;
        });

        p.register("LPOP", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'lpop' command");
            }
            String value = store.collections().listPop(args[1], true);
            if (value != null) {
                p.log(args);
            }
            return value;
        });

        p.register("RPOP", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'rpop' command");
            }
            String value = store.collections().listPop(args[1], false);
            if (value != null) {
                p.log(args);
            }
            return value;
        });

        p.register("LLEN", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'llen' command");
            }
            return (long) store.collections().listLength(args[1]);
        });

        p.register("LRANGE", 4, args -> {
            if (args.length != 4) {
                return CommandProcessor.error("ERR wrong number of arguments for 'lrange' command");
            }
            int start = Integer.parseInt(args[2]);
            int stop = Integer.parseInt(args[3]);
            return new ArrayList<Object>(store.collections().listRange(args[1], start, stop));
        });

        p.register("LINDEX", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'lindex' command");
            }
            int index = Integer.parseInt(args[2]);
            return store.collections().listIndex(args[1], index);
        });

        // ---------------- hashes ----------------

        p.register("HSET", 4, args -> {
            if (args.length % 2 != 0) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hset' command");
            }
            String[] fieldValues = new String[args.length - 2];
            System.arraycopy(args, 2, fieldValues, 0, fieldValues.length);
            long added = store.collections().hashSet(args[1], fieldValues);
            p.log(args);
            return added;
        });

        p.register("HGET", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hget' command");
            }
            return store.collections().hashGet(args[1], args[2]);
        });

        p.register("HDEL", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hdel' command");
            }
            String[] fields = new String[args.length - 2];
            System.arraycopy(args, 2, fields, 0, fields.length);
            long removed = store.collections().hashDel(args[1], fields);
            if (removed > 0) {
                p.log(args);
            }
            return removed;
        });

        p.register("HGETALL", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hgetall' command");
            }
            return new ArrayList<Object>(store.collections().hashGetAll(args[1]));
        });

        p.register("HLEN", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hlen' command");
            }
            return (long) store.collections().hashLength(args[1]);
        });

        p.register("HEXISTS", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'hexists' command");
            }
            return store.collections().hashExists(args[1], args[2]) ? 1L : 0L;
        });

        // ---------------- sets ----------------

        p.register("SADD", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'sadd' command");
            }
            String[] members = new String[args.length - 2];
            System.arraycopy(args, 2, members, 0, members.length);
            long added = store.collections().setAdd(args[1], members);
            if (added > 0) {
                p.log(args);
            }
            return added;
        });

        p.register("SREM", 3, args -> {
            if (args.length < 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'srem' command");
            }
            String[] members = new String[args.length - 2];
            System.arraycopy(args, 2, members, 0, members.length);
            long removed = store.collections().setRemove(args[1], members);
            if (removed > 0) {
                p.log(args);
            }
            return removed;
        });

        p.register("SMEMBERS", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'smembers' command");
            }
            return new ArrayList<Object>(store.collections().setMembers(args[1]));
        });

        p.register("SCARD", 2, args -> {
            if (args.length != 2) {
                return CommandProcessor.error("ERR wrong number of arguments for 'scard' command");
            }
            return (long) store.collections().setSize(args[1]);
        });

        p.register("SISMEMBER", 3, args -> {
            if (args.length != 3) {
                return CommandProcessor.error("ERR wrong number of arguments for 'sismember' command");
            }
            return store.collections().setIsMember(args[1], args[2]) ? 1L : 0L;
        });
    }

    private static List<String> slice(String[] args, int from) {
        List<String> values = new ArrayList<>(args.length - from);
        for (int i = from; i < args.length; i++) {
            values.add(args[i]);
        }
        return values;
    }
}