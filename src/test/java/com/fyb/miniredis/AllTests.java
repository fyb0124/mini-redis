package com.fyb.miniredis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Dependency-free test suite. It starts real servers on real TCP ports and
 * talks to them through a real socket using RESP, so it exercises the wire
 * protocol, the parser, the store and the AOF code together.
 *
 * Run with: java -cp build/classes;build/test-classes com.fyb.miniredis.AllTests
 */
public final class AllTests {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== mini-redis test suite ===");
        Path tempDir = Files.createTempDirectory("miniredis-tests");
        File aofFile = tempDir.resolve("appendonly-test.aof").toFile();

        Config mainConfig = new Config();
        mainConfig.port = 6411;
        mainConfig.appendonly = false;
        mainConfig.maxmemory = 100_000;
        MiniRedisServer main = new MiniRedisServer(mainConfig);
        main.start();

        Config lruConfig = new Config();
        lruConfig.port = 6412;
        lruConfig.appendonly = false;
        lruConfig.maxmemory = 5;
        MiniRedisServer lruServer = new MiniRedisServer(lruConfig);

        MiniRedisServer aofFirst = null;
        MiniRedisServer aofSecond = null;

        try (Client c = new Client(6411)) {
            testConnectionAndStrings(c);
            testExpiration(c, main);
            testLists(c);
            testHashes(c);
            testSets(c);
            testKeysAndScan(c);
            testGenericCommands(c);
            testRawProtocol(6411);
            testLru(lruServer);
            testConcurrency();
            testAof(aofFile);
            testFlush(c);
        } finally {
            main.stop();
            if (lruServer.isRunning()) {
                lruServer.stop();
            }
            if (aofFirst != null) {
                aofFirst.stop();
            }
            if (aofSecond != null) {
                aofSecond.stop();
            }
            deleteRecursively(tempDir);
        }

        System.out.println();
        System.out.println("==========================================");
        System.out.println("TOTAL: " + passed + " assertions passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("Failures:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: ALL TESTS PASSED");
    }

    // ==================================================================
    // Test groups
    // ==================================================================

    private static void testConnectionAndStrings(Client c) throws IOException {
        section("connection / strings");
        eq("PING -> PONG", c.cmd("PING"), "PONG");
        eq("PING with message", c.cmd("PING", "hello"), "hello");
        eq("ECHO", c.cmd("ECHO", "hello world"), "hello world");
        eq("ECHO UTF-8", c.cmd("ECHO", "你好，Redis"), "你好，Redis");
        eq("unknown command is an error", typeof(c.cmd("NOPE")), "ERR");
        eq("wrong arity is an error", typeof(c.cmd("GET")), "ERR");

        eq("SET -> OK", c.cmd("SET", "s:key", "v1"), "OK");
        eq("GET", c.cmd("GET", "s:key"), "v1");
        eq("GET missing -> nil", c.cmd("GET", "s:missing"), null);
        eq("TYPE string", c.cmd("TYPE", "s:key"), "string");
        eq("TYPE missing", c.cmd("TYPE", "s:missing"), "none");

        eq("SET NX on existing -> nil", c.cmd("SET", "s:key", "v2", "NX"), null);
        eq("SET NX did not overwrite", c.cmd("GET", "s:key"), "v1");
        eq("SET NX on missing", c.cmd("SET", "s:nx", "first", "NX"), "OK");
        eq("SET XX on existing", c.cmd("SET", "s:nx", "second", "XX"), "OK");
        eq("SET XX value", c.cmd("GET", "s:nx"), "second");
        eq("SET XX on missing -> nil", c.cmd("SET", "s:xxmissing", "x", "XX"), null);
        eq("SET XX did not create key", c.cmd("EXISTS", "s:xxmissing"), 0L);
        eq("SET NX+XX is a syntax error", typeof(c.cmd("SET", "s:key", "v", "NX", "XX")), "ERR");

        eq("SET EX", c.cmd("SET", "s:ex", "v", "EX", "100"), "OK");
        between("TTL of SET EX 100", (Long) c.cmd("TTL", "s:ex"), 90, 100);
        eq("SET EX 0 is rejected", typeof(c.cmd("SET", "s:ex0", "v", "EX", "0")), "ERR");
        eq("SET with bad option is rejected", typeof(c.cmd("SET", "s:ex1", "v", "BOGUS")), "ERR");

        eq("APPEND to new key returns length", c.cmd("APPEND", "s:app", "Hello"), 5L);
        eq("APPEND to existing key", c.cmd("APPEND", "s:app", " World"), 11L);
        eq("APPEND result", c.cmd("GET", "s:app"), "Hello World");
        eq("STRLEN", c.cmd("STRLEN", "s:app"), 11L);
        eq("STRLEN missing", c.cmd("STRLEN", "s:noapp"), 0L);

        eq("INCR new key", c.cmd("INCR", "s:cnt"), 1L);
        eq("INCR existing", c.cmd("INCR", "s:cnt"), 2L);
        eq("DECR", c.cmd("DECR", "s:cnt"), 1L);
        eq("INCRBY", c.cmd("INCRBY", "s:cnt", "10"), 11L);
        eq("DECRBY", c.cmd("DECRBY", "s:cnt", "5"), 6L);
        eq("counter is a string", c.cmd("GET", "s:cnt"), "6");
        eq("INCR on non-integer", typeof(c.cmd("INCR", "s:app")), "ERR");

        eq("MSET", c.cmd("MSET", "s:a", "1", "s:b", "2", "s:c", "3"), "OK");
        eq("MGET", c.cmd("MGET", "s:a", "s:b", "s:nope", "s:c"),
                java.util.Arrays.asList("1", "2", null, "3"));
        eq("MSET odd arguments rejected", typeof(c.cmd("MSET", "s:a", "1", "s:b")), "ERR");
        eq("EXISTS multiple", c.cmd("EXISTS", "s:a", "s:b", "s:nope"), 2L);
        eq("EXISTS counts duplicates", c.cmd("EXISTS", "s:a", "s:a"), 2L);
        eq("DEL multiple", c.cmd("DEL", "s:a", "s:b", "s:nope"), 2L);
        eq("DEL result", c.cmd("EXISTS", "s:a"), 0L);

        eq("GETSET missing -> nil", c.cmd("GETSET", "s:gs", "old"), null);
        eq("GETSET existing returns old", c.cmd("GETSET", "s:gs", "new"), "old");
        eq("GETSET stored new", c.cmd("GET", "s:gs"), "new");
        eq("SETNX new -> 1", c.cmd("SETNX", "s:sn", "v1"), 1L);
        eq("SETNX existing -> 0", c.cmd("SETNX", "s:sn", "v2"), 0L);
        eq("SETNX kept old", c.cmd("GET", "s:sn"), "v1");
        eq("GETDEL", c.cmd("GETDEL", "s:gs"), "new");
        eq("GETDEL removed key", c.cmd("EXISTS", "s:gs"), 0L);
        eq("GETDEL missing -> nil", c.cmd("GETDEL", "s:gs"), null);
    }

    private static void testExpiration(Client c, MiniRedisServer server) throws Exception {
        section("expiration");
        eq("SET for TTL", c.cmd("SET", "s:ttl", "v"), "OK");
        eq("TTL without expire", c.cmd("TTL", "s:ttl"), -1L);
        eq("PTTL without expire", c.cmd("PTTL", "s:ttl"), -1L);
        eq("EXPIRE", c.cmd("EXPIRE", "s:ttl", "100"), 1L);
        between("TTL after EXPIRE 100", (Long) c.cmd("TTL", "s:ttl"), 90, 100);
        between("PTTL after EXPIRE 100", (Long) c.cmd("PTTL", "s:ttl"), 90_000, 100_000);
        eq("PERSIST", c.cmd("PERSIST", "s:ttl"), 1L);
        eq("TTL after PERSIST", c.cmd("TTL", "s:ttl"), -1L);
        eq("PERSIST without TTL", c.cmd("PERSIST", "s:ttl"), 0L);
        eq("EXPIRE missing key", c.cmd("EXPIRE", "s:nottl", "10"), 0L);

        eq("PEXPIRE 50ms", c.cmd("PEXPIRE", "s:ttl", "50"), 1L);
        Thread.sleep(150);
        eq("key expired (lazy) -> nil", c.cmd("GET", "s:ttl"), null);
        eq("TTL expired -> -2", c.cmd("TTL", "s:ttl"), -2L);
        eq("PTTL expired -> -2", c.cmd("PTTL", "s:ttl"), -2L);

        eq("SET EX for PEXPIREAT", c.cmd("SET", "s:ttl2", "v", "EX", "100"), "OK");
        long deadline = System.currentTimeMillis() + 50_000;
        eq("PEXPIREAT", c.cmd("PEXPIREAT", "s:ttl2", Long.toString(deadline)), 1L);
        between("TTL after PEXPIREAT", (Long) c.cmd("TTL", "s:ttl2"), 40, 50);

        eq("SET for active sweep", c.cmd("SET", "s:sweeper", "v"), "OK");
        eq("PEXPIRE sweeper", c.cmd("PEXPIRE", "s:sweeper", "40"), 1L);
        long expiredBefore = Long.parseLong(infoField(c.cmd("INFO"), "expired_keys"));
        Thread.sleep(450);
        boolean stillInRawMap = server.store().rawKeys().contains("s:sweeper");
        truth("background sweeper removed expired key", !stillInRawMap);
        long expiredAfter = Long.parseLong(infoField(c.cmd("INFO"), "expired_keys"));
        truth("expired_keys counter increased", expiredAfter > expiredBefore);
    }

    private static void testLists(Client c) throws IOException {
        section("lists");
        eq("LPUSH new list", c.cmd("LPUSH", "s:list", "a"), 1L);
        eq("LPUSH head", c.cmd("LPUSH", "s:list", "b"), 2L);
        eq("RPUSH tail", c.cmd("RPUSH", "s:list", "c"), 3L);
        eq("LRANGE full", c.cmd("LRANGE", "s:list", "0", "-1"), List.of("b", "a", "c"));
        eq("LLEN", c.cmd("LLEN", "s:list"), 3L);
        eq("LINDEX 0", c.cmd("LINDEX", "s:list", "0"), "b");
        eq("LINDEX -1", c.cmd("LINDEX", "s:list", "-1"), "c");
        eq("LINDEX out of range", c.cmd("LINDEX", "s:list", "99"), null);
        eq("LRANGE negative slice", c.cmd("LRANGE", "s:list", "-2", "-1"), List.of("a", "c"));
        eq("LRANGE out of range", c.cmd("LRANGE", "s:list", "5", "10"), List.of());
        eq("LPOP", c.cmd("LPOP", "s:list"), "b");
        eq("RPOP", c.cmd("RPOP", "s:list"), "c");
        eq("LRANGE after pops", c.cmd("LRANGE", "s:list", "0", "-1"), List.of("a"));
        eq("LPOP last element", c.cmd("LPOP", "s:list"), "a");
        eq("list key removed when empty", c.cmd("EXISTS", "s:list"), 0L);
        eq("LPOP missing -> nil", c.cmd("LPOP", "s:list"), null);
        eq("LLEN missing", c.cmd("LLEN", "s:list"), 0L);
        eq("RPUSH multiple", c.cmd("RPUSH", "s:list2", "x", "y", "z"), 3L);
        eq("LRANGE of s:list2", c.cmd("LRANGE", "s:list2", "0", "-1"), List.of("x", "y", "z"));
        c.cmd("SET", "s:strwrong", "x");
        eq("LPUSH on string is WRONGTYPE", typeof(c.cmd("LPUSH", "s:strwrong", "y")), "WRONGTYPE");
        eq("LRANGE on string is WRONGTYPE", typeof(c.cmd("LRANGE", "s:strwrong", "0", "1")), "WRONGTYPE");
        eq("INCR on list is WRONGTYPE", typeof(c.cmd("INCR", "s:list2")), "WRONGTYPE");
    }

    private static void testHashes(Client c) throws IOException {
        section("hashes");
        eq("HSET one field", c.cmd("HSET", "s:hash", "f1", "v1"), 1L);
        eq("HSET two new fields", c.cmd("HSET", "s:hash", "f2", "v2", "f3", "v3"), 2L);
        eq("HSET update returns 0", c.cmd("HSET", "s:hash", "f1", "v1b"), 0L);
        eq("HGET", c.cmd("HGET", "s:hash", "f1"), "v1b");
        eq("HGET missing field", c.cmd("HGET", "s:hash", "nope"), null);
        eq("HGET missing key", c.cmd("HGET", "s:nohash", "f"), null);
        eq("HEXISTS yes", c.cmd("HEXISTS", "s:hash", "f1"), 1L);
        eq("HEXISTS no", c.cmd("HEXISTS", "s:hash", "nope"), 0L);
        eq("HLEN", c.cmd("HLEN", "s:hash"), 3L);
        List<?> flat = (List<?>) c.cmd("HGETALL", "s:hash");
        eq("HGETALL length", flat.size(), 6);
        eq("HGETALL content", new HashSet<>(flat),
                new HashSet<>(List.of("f1", "v1b", "f2", "v2", "f3", "v3")));
        eq("HDEL multiple", c.cmd("HDEL", "s:hash", "f2", "f3", "nope"), 2L);
        eq("HLEN after HDEL", c.cmd("HLEN", "s:hash"), 1L);
        eq("HDEL last field removes key", c.cmd("HDEL", "s:hash", "f1"), 1L);
        eq("hash key gone", c.cmd("EXISTS", "s:hash"), 0L);
        c.cmd("SET", "s:hashwrong", "x");
        eq("HSET on string is WRONGTYPE", typeof(c.cmd("HSET", "s:hashwrong", "f", "v")), "WRONGTYPE");
    }

    private static void testSets(Client c) throws IOException {
        section("sets");
        eq("SADD three", c.cmd("SADD", "s:set", "a", "b", "c"), 3L);
        eq("SADD duplicate/new", c.cmd("SADD", "s:set", "a", "d"), 1L);
        eq("SCARD", c.cmd("SCARD", "s:set"), 4L);
        eq("SISMEMBER yes", c.cmd("SISMEMBER", "s:set", "a"), 1L);
        eq("SISMEMBER no", c.cmd("SISMEMBER", "s:set", "zz"), 0L);
        List<?> members = (List<?>) c.cmd("SMEMBERS", "s:set");
        eq("SMEMBERS size", members.size(), 4);
        eq("SMEMBERS content", new HashSet<>(members), Set.of("a", "b", "c", "d"));
        eq("SREM one", c.cmd("SREM", "s:set", "a", "nope"), 1L);
        eq("SCARD after SREM", c.cmd("SCARD", "s:set"), 3L);
        eq("SREM rest", c.cmd("SREM", "s:set", "b", "c", "d"), 3L);
        eq("set key gone", c.cmd("EXISTS", "s:set"), 0L);
        eq("SMEMBERS missing", c.cmd("SMEMBERS", "s:set"), List.of());
        c.cmd("SET", "s:setwrong", "x");
        eq("SADD on string is WRONGTYPE", typeof(c.cmd("SADD", "s:setwrong", "m")), "WRONGTYPE");
    }

    private static void testKeysAndScan(Client c) throws IOException {
        section("KEYS / SCAN");
        for (int i = 1; i <= 20; i++) {
            c.cmd("SET", "k:user:" + i, "v" + i);
        }
        eq("KEYS * counts every key", ((List<?>) c.cmd("KEYS", "k:user:*")).size(), 20);
        eq("KEYS ? wildcard", ((List<?>) c.cmd("KEYS", "k:user:1?")).size(), 10);
        eq("KEYS [123]", new HashSet<>((List<?>) c.cmd("KEYS", "k:user:[123]")),
                Set.of("k:user:1", "k:user:2", "k:user:3"));
        eq("KEYS single char", ((List<?>) c.cmd("KEYS", "k:user:?")).size(), 9);
        eq("KEYS range [1-5]", ((List<?>) c.cmd("KEYS", "k:user:[1-5]")).size(), 5);
        eq("KEYS negation [^1]", ((List<?>) c.cmd("KEYS", "k:user:[^1]")).size(), 8);
        eq("KEYS no match", c.cmd("KEYS", "nomatch:*"), List.of());

        Set<String> scanned = new HashSet<>();
        long cursor = 0;
        int rounds = 0;
        do {
            List<?> reply = (List<?>) c.cmd("SCAN", Long.toString(cursor), "MATCH", "k:user:*", "COUNT", "7");
            cursor = Long.parseLong((String) reply.get(0));
            for (Object k : (List<?>) reply.get(1)) {
                scanned.add((String) k);
            }
            rounds++;
            truth("SCAN round " + rounds + " returns at most COUNT", ((List<?>) reply.get(1)).size() <= 7);
        } while (cursor != 0 && rounds < 100);
        eq("SCAN visited all 20 keys", scanned.size(), 20);
        truth("SCAN needed multiple rounds", rounds > 1);
        eq("SCAN invalid cursor", typeof(c.cmd("SCAN", "abc")), "ERR");
        eq("SCAN bad option", typeof(c.cmd("SCAN", "0", "BOGUS", "x")), "ERR");

        eq("DBSIZE matches KEYS *", c.cmd("DBSIZE"), (long) ((List<?>) c.cmd("KEYS", "*")).size());
    }

    private static void testGenericCommands(Client c) throws IOException {
        section("generic");
        eq("SELECT 0", c.cmd("SELECT", "0"), "OK");
        eq("SELECT 1 rejected", typeof(c.cmd("SELECT", "1")), "ERR");
        truth("COMMAND COUNT >= 50", ((Long) c.cmd("COMMAND", "COUNT")) >= 50);
        List<?> commands = (List<?>) c.cmd("COMMAND");
        truth("COMMAND lists commands", commands.size() >= 50 && commands.contains("get"));
        String info = (String) c.cmd("INFO");
        truth("INFO contains version", info.contains("miniredis_version:" + CommandProcessor.VERSION));
        truth("INFO contains keyspace", info.contains("db0:keys="));
        eq("TYPE list", c.cmd("TYPE", "s:list2"), "list");
        eq("FLUSHALL", c.cmd("FLUSHALL"), "OK");
        eq("DBSIZE after FLUSHALL", c.cmd("DBSIZE"), 0L);
    }

    private static void testFlush(Client c) throws IOException {
        section("FLUSHDB");
        c.cmd("FLUSHDB");
        c.cmd("SET", "flush:1", "x");
        c.cmd("SET", "flush:2", "y");
        eq("DBSIZE before FLUSHDB", c.cmd("DBSIZE"), 2L);
        eq("FLUSHDB", c.cmd("FLUSHDB"), "OK");
        eq("DBSIZE after FLUSHDB", c.cmd("DBSIZE"), 0L);
        eq("GET after FLUSHDB", c.cmd("GET", "flush:1"), null);
    }

    private static void testRawProtocol(int port) throws Exception {
        section("raw protocol (inline / half packet / pipelining)");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write("*1\r\n$4\r\nPI".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(30);
            out.write("NG\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            eq("half packet command completes", readLine(in), "+PONG");

            out.write("ECHO \"hello world\"\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            eq("inline command", readLine(in), "$11");
            eq("inline bulk payload", readBulkBody(in, 11), "hello world");

            out.write(("*1\r\n$4\r\nPING\r\n"
                    + "*1\r\n$4\r\nPING\r\n"
                    + "*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            eq("pipeline reply 1", readLine(in), "+PONG");
            eq("pipeline reply 2", readLine(in), "+PONG");
            eq("pipeline reply 3 header", readLine(in), "$2");
            eq("pipeline reply 3 body", readBulkBody(in, 2), "hi");

            out.write("SET raw:quote 'single quoted value'\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            eq("inline single quotes", readLine(in), "+OK");
            try (Client probe = new Client(port)) {
                eq("inline single quote stored", probe.cmd("GET", "raw:quote"), "single quoted value");
            }

            out.write("*1\r\n+PING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String protocolError = readLine(in);
            truth("protocol error reported", protocolError != null
                    && protocolError.startsWith("-ERR Protocol error"));
        }
    }

    private static void testLru(MiniRedisServer lruServer) throws Exception {
        section("LRU eviction (maxmemory = 5 keys)");
        lruServer.start();
        try (Client c = new Client(6412)) {
            for (int i = 1; i <= 10; i++) {
                eq("insert lru:" + i, c.cmd("SET", "lru:" + i, "v"), "OK");
            }
            eq("DBSIZE capped at maxmemory", c.cmd("DBSIZE"), 5L);
            for (int i = 1; i <= 5; i++) {
                eq("oldest lru:" + i + " evicted", c.cmd("EXISTS", "lru:" + i), 0L);
            }
            for (int i = 6; i <= 10; i++) {
                eq("newest lru:" + i + " kept", c.cmd("EXISTS", "lru:" + i), 1L);
            }
            eq("touch lru:6", c.cmd("GET", "lru:6"), "v");
            eq("insert lru:11", c.cmd("SET", "lru:11", "v"), "OK");
            eq("recently accessed lru:6 survives", c.cmd("EXISTS", "lru:6"), 1L);
            eq("least recently used lru:7 evicted", c.cmd("EXISTS", "lru:7"), 0L);
            eq("new key lru:11 present", c.cmd("EXISTS", "lru:11"), 1L);
            eq("still capped at 5", c.cmd("DBSIZE"), 5L);
        } finally {
            lruServer.stop();
        }
    }

    private static void testConcurrency() throws Exception {
        section("concurrency: 16 threads x 20000 INCR = 320000");
        int threads = 16;
        int perThread = 20_000;
        Client setup = new Client(6411);
        setup.cmd("DEL", "k:counter");
        setup.close();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wrongReplies = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long begin = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try (Client c = new Client(6411, 60_000)) {
                    start.await();
                    int done = 0;
                    while (done < perThread) {
                        int batch = Math.min(500, perThread - done);
                        for (int i = 0; i < batch; i++) {
                            c.send("INCR", "k:counter");
                        }
                        for (int i = 0; i < batch; i++) {
                            Object reply = c.read();
                            if (!(reply instanceof Long)) {
                                wrongReplies.incrementAndGet();
                            }
                        }
                        done += batch;
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        double seconds = (System.nanoTime() - begin) / 1_000_000_000.0;
        try (Client c = new Client(6411)) {
            eq("concurrent INCR final value", c.cmd("GET", "k:counter"), Integer.toString(threads * perThread));
            eq("no non-integer replies", wrongReplies.get(), 0);
        }
        System.out.printf("  (concurrency phase: %.2f s, %.0f INCR ops/s)%n",
                seconds, threads * perThread / seconds);
    }

    private static void testAof(File aofFile) throws Exception {
        section("AOF persistence and replay");
        if (aofFile.exists() && !aofFile.delete()) {
            throw new IOException("cannot delete old AOF " + aofFile);
        }

        Config first = new Config();
        first.port = 6413;
        first.appendonly = true;
        first.aofPath = aofFile.getAbsolutePath();
        first.appendfsync = "always";
        MiniRedisServer a = new MiniRedisServer(first);
        a.start();
        try (Client c = new Client(6413)) {
            eq("AOF SET", c.cmd("SET", "aof:str", "hello"), "OK");
            eq("AOF RPUSH", c.cmd("RPUSH", "aof:list", "x", "y"), 2L);
            eq("AOF HSET", c.cmd("HSET", "aof:hash", "f", "v"), 1L);
            eq("AOF SADD", c.cmd("SADD", "aof:set", "m1", "m2"), 2L);
            eq("AOF SET with TTL", c.cmd("SET", "aof:ttl", "v"), "OK");
            long deadline = System.currentTimeMillis() + 120_000;
            eq("AOF PEXPIREAT", c.cmd("PEXPIREAT", "aof:ttl", Long.toString(deadline)), 1L);
            for (int i = 0; i < 5; i++) {
                c.cmd("INCR", "aof:counter");
            }
            eq("AOF INCRBY", c.cmd("INCRBY", "aof:counter", "10"), 15L);
            eq("AOF SET deleted later", c.cmd("SET", "aof:gone", "v"), "OK");
            eq("AOF DEL", c.cmd("DEL", "aof:gone"), 1L);
            eq("AOF DBSIZE before restart", c.cmd("DBSIZE"), 6L);
        } finally {
            a.stop();
        }
        truth("AOF file is non-empty", aofFile.length() > 0);

        Config second = new Config();
        second.port = 6414;
        second.appendonly = true;
        second.aofPath = aofFile.getAbsolutePath();
        second.appendfsync = "everysec";
        MiniRedisServer b = new MiniRedisServer(second);
        b.start();
        try (Client c = new Client(6414)) {
            eq("replayed string", c.cmd("GET", "aof:str"), "hello");
            eq("replayed list", c.cmd("LRANGE", "aof:list", "0", "-1"), List.of("x", "y"));
            eq("replayed hash", c.cmd("HGET", "aof:hash", "f"), "v");
            eq("replayed set", new HashSet<>((List<?>) c.cmd("SMEMBERS", "aof:set")), Set.of("m1", "m2"));
            eq("replayed counter", c.cmd("GET", "aof:counter"), "15");
            between("replayed TTL", (Long) c.cmd("PTTL", "aof:ttl"), 100_000, 120_000);
            eq("replayed delete", c.cmd("EXISTS", "aof:gone"), 0L);
            eq("replayed DBSIZE", c.cmd("DBSIZE"), 6L);
        } finally {
            b.stop();
        }
    }

    // ==================================================================
    // Assertions
    // ==================================================================

    private static void section(String name) {
        System.out.println();
        System.out.println("--- " + name + " ---");
    }

    private static void eq(String what, Object actual, Object expected) {
        if (Objects.equals(actual, expected)) {
            pass(what);
        } else {
            fail(what + " | expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void truth(String what, boolean condition) {
        if (condition) {
            pass(what);
        } else {
            fail(what);
        }
    }

    private static void between(String what, Long actual, long low, long high) {
        if (actual != null && actual >= low && actual <= high) {
            pass(what + " (" + actual + ")");
        } else {
            fail(what + " | expected " + low + ".." + high + " but was <" + actual + ">");
        }
    }

    private static String typeof(Object reply) {
        if (reply instanceof Err e) {
            int space = e.message.indexOf(' ');
            return space > 0 ? e.message.substring(0, space) : e.message;
        }
        return reply == null ? "nil" : reply.getClass().getSimpleName();
    }

    private static void pass(String what) {
        passed++;
        System.out.println("[PASS] " + what);
    }

    private static void fail(String what) {
        failed++;
        failures.add(what);
        System.out.println("[FAIL] " + what);
    }

    // ==================================================================
    // Minimal RESP client / helpers
    // ==================================================================

    static final class Err {
        final String message;

        Err(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "-" + message;
        }
    }

    static final class Client implements Closeable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final BufferedOutputStream out;

        Client(int port) throws IOException {
            this(port, 15_000);
        }

        Client(int port, int timeoutMs) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);
            in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
        }

        Object cmd(String... parts) throws IOException {
            send(parts);
            return read();
        }

        void send(String... parts) throws IOException {
            out.write(encode(parts));
            out.flush();
        }

        Object read() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new EOFException("server closed connection");
            }
            String line = readLine(in);
            switch (type) {
                case '+':
                    return line;
                case '-':
                    return new Err(line);
                case ':':
                    return Long.parseLong(line);
                case '$': {
                    int len = Integer.parseInt(line);
                    if (len < 0) {
                        return null;
                    }
                    String value = new String(readExactly(in, len), StandardCharsets.UTF_8);
                    expectCrlf(in);
                    return value;
                }
                case '*': {
                    int count = Integer.parseInt(line);
                    if (count < 0) {
                        return null;
                    }
                    List<Object> items = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        items.add(read());
                    }
                    return items;
                }
                default:
                    throw new IOException("Unexpected reply type: " + (char) type);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static byte[] encode(String[] parts) {
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

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32);
        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
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

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(data, off, n - off);
            if (r == -1) {
                throw new EOFException();
            }
            off += r;
        }
        return data;
    }

    private static String readBulkBody(InputStream in, int n) throws IOException {
        String s = new String(readExactly(in, n), StandardCharsets.UTF_8);
        expectCrlf(in);
        return s;
    }

    private static void expectCrlf(InputStream in) throws IOException {
        int a = in.read();
        int b = in.read();
        if (a != '\r' || b != '\n') {
            throw new IOException("expected CRLF");
        }
    }

    private static String infoField(Object info, String field) {
        if (!(info instanceof String text)) {
            return null;
        }
        for (String line : text.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).equals(field)) {
                return line.substring(idx + 1);
            }
        }
        return null;
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}