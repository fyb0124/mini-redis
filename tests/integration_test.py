#!/usr/bin/env python3
"""Black-box integration test for mini-redis.

Starts the real server as a separate process, talks to it over raw TCP
sockets with hand-built RESP2 frames, and checks the replies. It shares no
code with the Java test suite, so it independently validates the wire
protocol and the server binary.

Usage:
    python tests/integration_test.py

Requirements:
    - Java 17 at E:\\jdk\\bin\\java.exe (override with JAVA_EXE)
    - dist/miniredis.jar (built automatically with build.cmd if missing)
"""

import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "dist" / "miniredis.jar"
JAVA = os.environ.get("JAVA_EXE", r"E:\jdk\bin\java.exe")

passed = 0
failed = 0
failures = []


# ----------------------------------------------------------------------
# Tiny RESP client
# ----------------------------------------------------------------------

class ErrorReply:
    def __init__(self, message):
        self.message = message

    def __repr__(self):
        return "-" + self.message


class Client:
    def __init__(self, port, timeout=10.0):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.buf = b""

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def send(self, *parts):
        frame = b"*%d\r\n" % len(parts)
        for part in parts:
            data = part.encode("utf-8") if isinstance(part, str) else part
            frame += b"$%d\r\n%s\r\n" % (len(data), data)
        self.sock.sendall(frame)

    def cmd(self, *parts):
        self.send(*parts)
        return self.read()

    def send_raw(self, data):
        self.sock.sendall(data)

    def _fill(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            raise EOFError("server closed the connection")
        self.buf += chunk

    def _read_line(self):
        while b"\r\n" not in self.buf:
            self._fill()
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _read_exact(self, n):
        while len(self.buf) < n:
            self._fill()
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def read(self):
        line = self._read_line()
        kind, rest = line[:1], line[1:]
        if kind == b"+":
            return rest.decode("utf-8")
        if kind == b"-":
            return ErrorReply(rest.decode("utf-8"))
        if kind == b":":
            return int(rest)
        if kind == b"$":
            length = int(rest)
            if length < 0:
                return None
            data = self._read_exact(length)
            assert self._read_exact(2) == b"\r\n"
            return data.decode("utf-8")
        if kind == b"*":
            count = int(rest)
            if count < 0:
                return None
            return [self.read() for _ in range(count)]
        raise AssertionError("unknown RESP type: %r" % line)


# ----------------------------------------------------------------------
# Assertions
# ----------------------------------------------------------------------

def check(condition, name, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print("[PASS] " + name)
    else:
        failed += 1
        failures.append(name + (" | " + detail if detail else ""))
        print("[FAIL] " + name + (" | " + detail if detail else ""))


def eq(name, actual, expected):
    check(actual == expected, name, "expected %r, got %r" % (expected, actual))


def is_error(name, reply, prefix):
    check(isinstance(reply, ErrorReply) and reply.message.startswith(prefix),
          name, "expected error %r, got %r" % (prefix, reply))


def between(name, value, low, high):
    check(isinstance(value, int) and low <= value <= high, name,
          "expected %d..%d, got %r" % (low, high, value))


# ----------------------------------------------------------------------
# Server lifecycle
# ----------------------------------------------------------------------

def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def wait_until_ready(port, process, timeout=15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("server exited early with code %s" % process.returncode)
        try:
            probe = socket.create_connection(("127.0.0.1", port), timeout=0.5)
            probe.close()
            return
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("server did not start listening on port %d" % port)


def start_server(port, extra_args, log_path):
    args = [
        JAVA, "-jar", str(JAR),
        "--port=%d" % port,
        "--appendonly=no",
        "--threads=8",
    ] + extra_args
    log = open(log_path, "wb")
    process = subprocess.Popen(args, cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT)
    try:
        wait_until_ready(port, process)
    except Exception:
        process.kill()
        log.close()
        raise
    return process, log


def stop_server(process, log):
    try:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    finally:
        log.close()


# ----------------------------------------------------------------------
# Test cases
# ----------------------------------------------------------------------

def test_basic(port):
    print("\n--- connection / string commands ---")
    c = Client(port)
    try:
        eq("PING", c.cmd("PING"), "PONG")
        eq("PING message", c.cmd("PING", "hi"), "hi")
        eq("ECHO", c.cmd("ECHO", "hello"), "hello")
        eq("ECHO UTF-8", c.cmd("ECHO", "你好 Redis"), "你好 Redis")

        eq("SET", c.cmd("SET", "it:key", "v1"), "OK")
        eq("GET", c.cmd("GET", "it:key"), "v1")
        eq("GET missing is nil", c.cmd("GET", "it:none"), None)
        eq("TYPE string", c.cmd("TYPE", "it:key"), "string")
        eq("TYPE missing", c.cmd("TYPE", "it:none"), "none")

        eq("SET NX existing", c.cmd("SET", "it:key", "v2", "NX"), None)
        eq("SET NX kept value", c.cmd("GET", "it:key"), "v1")
        eq("SET XX existing", c.cmd("SET", "it:key", "v2", "XX"), "OK")
        eq("SET XX kept new value", c.cmd("GET", "it:key"), "v2")
        eq("SET XX missing", c.cmd("SET", "it:xx", "v", "XX"), None)

        eq("MSET", c.cmd("MSET", "it:a", "1", "it:b", "2"), "OK")
        eq("MGET", c.cmd("MGET", "it:a", "it:b", "it:none"), ["1", "2", None])
        eq("EXISTS", c.cmd("EXISTS", "it:a", "it:b", "it:none"), 2)
        eq("DEL", c.cmd("DEL", "it:a", "it:b"), 2)
        eq("DEL removed keys", c.cmd("EXISTS", "it:a"), 0)

        eq("APPEND new", c.cmd("APPEND", "it:app", "abc"), 3)
        eq("APPEND existing", c.cmd("APPEND", "it:app", "def"), 6)
        eq("APPEND value", c.cmd("GET", "it:app"), "abcdef")
        eq("STRLEN", c.cmd("STRLEN", "it:app"), 6)

        eq("INCR", c.cmd("INCR", "it:n"), 1)
        eq("INCR again", c.cmd("INCR", "it:n"), 2)
        eq("DECR", c.cmd("DECR", "it:n"), 1)
        eq("INCRBY", c.cmd("INCRBY", "it:n", "10"), 11)
        eq("DECRBY", c.cmd("DECRBY", "it:n", "5"), 6)

        is_error("unknown command", c.cmd("NOPE"), "ERR unknown command")
        is_error("wrong arity", c.cmd("GET"), "ERR wrong number")
        is_error("INCR non-integer", c.cmd("INCR", "it:app"), "ERR value is not an integer")
    finally:
        c.close()


def test_expiration(port):
    print("\n--- expiration ---")
    c = Client(port)
    try:
        eq("SET ttl", c.cmd("SET", "it:ttl", "v"), "OK")
        eq("TTL no expire", c.cmd("TTL", "it:ttl"), -1)
        eq("PTTL no expire", c.cmd("PTTL", "it:ttl"), -1)
        eq("EXPIRE", c.cmd("EXPIRE", "it:ttl", "100"), 1)
        between("TTL after EXPIRE", c.cmd("TTL", "it:ttl"), 90, 100)
        between("PTTL after EXPIRE", c.cmd("PTTL", "it:ttl"), 90000, 100000)
        eq("PERSIST", c.cmd("PERSIST", "it:ttl"), 1)
        eq("TTL after PERSIST", c.cmd("TTL", "it:ttl"), -1)
        eq("PERSIST again", c.cmd("PERSIST", "it:ttl"), 0)
        eq("EXPIRE missing", c.cmd("EXPIRE", "it:none", "10"), 0)

        eq("PEXPIRE", c.cmd("PEXPIRE", "it:ttl", "60"), 1)
        time.sleep(0.2)
        eq("expired key is gone", c.cmd("GET", "it:ttl"), None)
        eq("TTL expired", c.cmd("TTL", "it:ttl"), -2)
        eq("PTTL expired", c.cmd("PTTL", "it:ttl"), -2)

        eq("SET EX", c.cmd("SET", "it:ttl2", "v", "EX", "50"), "OK")
        between("TTL of SET EX", c.cmd("TTL", "it:ttl2"), 45, 50)
        deadline = int(time.time() * 1000) + 40000
        eq("PEXPIREAT", c.cmd("PEXPIREAT", "it:ttl2", str(deadline)), 1)
        between("TTL after PEXPIREAT", c.cmd("TTL", "it:ttl2"), 35, 40)
        is_error("SET EX 0 rejected", c.cmd("SET", "it:bad", "v", "EX", "0"),
                 "ERR invalid expire time")
    finally:
        c.close()


def test_lists(port):
    print("\n--- lists ---")
    c = Client(port)
    try:
        eq("LPUSH", c.cmd("LPUSH", "it:list", "a"), 1)
        eq("LPUSH head", c.cmd("LPUSH", "it:list", "b"), 2)
        eq("RPUSH", c.cmd("RPUSH", "it:list", "c"), 3)
        eq("LRANGE", c.cmd("LRANGE", "it:list", "0", "-1"), ["b", "a", "c"])
        eq("LLEN", c.cmd("LLEN", "it:list"), 3)
        eq("LINDEX 0", c.cmd("LINDEX", "it:list", "0"), "b")
        eq("LINDEX -1", c.cmd("LINDEX", "it:list", "-1"), "c")
        eq("LPOP", c.cmd("LPOP", "it:list"), "b")
        eq("RPOP", c.cmd("RPOP", "it:list"), "c")
        eq("LRANGE after pops", c.cmd("LRANGE", "it:list", "0", "-1"), ["a"])
        eq("LPOP last", c.cmd("LPOP", "it:list"), "a")
        eq("empty list removed", c.cmd("EXISTS", "it:list"), 0)
        eq("LPOP missing", c.cmd("LPOP", "it:list"), None)
    finally:
        c.close()


def test_hashes_sets(port):
    print("\n--- hashes / sets ---")
    c = Client(port)
    try:
        eq("HSET", c.cmd("HSET", "it:hash", "f1", "v1"), 1)
        eq("HSET more", c.cmd("HSET", "it:hash", "f2", "v2", "f3", "v3"), 2)
        eq("HSET update", c.cmd("HSET", "it:hash", "f1", "v1b"), 0)
        eq("HGET", c.cmd("HGET", "it:hash", "f1"), "v1b")
        eq("HEXISTS", c.cmd("HEXISTS", "it:hash", "f2"), 1)
        eq("HLEN", c.cmd("HLEN", "it:hash"), 3)
        eq("HGETALL", sorted(c.cmd("HGETALL", "it:hash")),
           sorted(["f1", "v1b", "f2", "v2", "f3", "v3"]))
        eq("HDEL", c.cmd("HDEL", "it:hash", "f2", "f3"), 2)
        eq("HLEN after HDEL", c.cmd("HLEN", "it:hash"), 1)

        eq("SADD", c.cmd("SADD", "it:set", "a", "b", "c"), 3)
        eq("SADD duplicate", c.cmd("SADD", "it:set", "a"), 0)
        eq("SCARD", c.cmd("SCARD", "it:set"), 3)
        eq("SISMEMBER yes", c.cmd("SISMEMBER", "it:set", "a"), 1)
        eq("SISMEMBER no", c.cmd("SISMEMBER", "it:set", "z"), 0)
        eq("SMEMBERS", sorted(c.cmd("SMEMBERS", "it:set")), ["a", "b", "c"])
        eq("SREM", c.cmd("SREM", "it:set", "a", "z"), 1)
        eq("SCARD after SREM", c.cmd("SCARD", "it:set"), 2)

        c.cmd("SET", "it:str", "x")
        is_error("LPUSH wrong type", c.cmd("LPUSH", "it:str", "y"), "WRONGTYPE")
        is_error("HGET wrong type", c.cmd("HGET", "it:str", "f"), "WRONGTYPE")
        is_error("SADD wrong type", c.cmd("SADD", "it:str", "m"), "WRONGTYPE")
    finally:
        c.close()


def test_keys_scan(port):
    print("\n--- KEYS / SCAN ---")
    c = Client(port)
    try:
        for i in range(1, 13):
            c.cmd("SET", "it:user:%d" % i, "v%d" % i)
        eq("KEYS star", len(c.cmd("KEYS", "it:user:*")), 12)
        eq("KEYS question mark", len(c.cmd("KEYS", "it:user:1?")), 3)
        eq("KEYS class", sorted(c.cmd("KEYS", "it:user:[2-4]")),
           ["it:user:2", "it:user:3", "it:user:4"])
        eq("KEYS no match", c.cmd("KEYS", "nope:*"), [])

        seen = set()
        cursor = 0
        rounds = 0
        while True:
            reply = c.cmd("SCAN", str(cursor), "MATCH", "it:user:*", "COUNT", "5")
            cursor = int(reply[0])
            seen.update(reply[1])
            rounds += 1
            check(len(reply[1]) <= 5, "SCAN page respects COUNT")
            if cursor == 0 or rounds > 50:
                break
        eq("SCAN visited all keys", len(seen), 12)
        check(rounds > 1, "SCAN took multiple pages", "rounds=%d" % rounds)

        single = c.cmd("SCAN", "0", "MATCH", "it:user:12", "COUNT", "100")
        eq("SCAN exact match", single, ["0", ["it:user:12"]])

        before = c.cmd("DBSIZE")
        c.cmd("SET", "it:dbsize", "x")
        eq("DBSIZE increments", c.cmd("DBSIZE"), before + 1)
        eq("FLUSHDB", c.cmd("FLUSHDB"), "OK")
        eq("DBSIZE after FLUSHDB", c.cmd("DBSIZE"), 0)
    finally:
        c.close()


def test_raw_protocol(port):
    print("\n--- raw protocol ---")
    c = Client(port)
    try:
        c.send_raw(b"PING\r\n")
        eq("inline PING", c.read(), "PONG")
        c.send_raw(b'ECHO "hello world"\r\n')
        eq("inline quoted ECHO", c.read(), "hello world")
        c.send_raw(b"SET it:inline 'a value'\r\n")
        eq("inline SET", c.read(), "OK")
        eq("inline value stored", c.cmd("GET", "it:inline"), "a value")

        c.send_raw(b"*1\r\n$4\r\nPI")
        time.sleep(0.05)
        c.send_raw(b"NG\r\n")
        eq("fragmented command", c.read(), "PONG")

        c.send_raw(b"*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n")
        eq("pipeline reply 1", c.read(), "PONG")
        eq("pipeline reply 2", c.read(), "hi")

        bad = Client(port)
        try:
            bad.send_raw(b"*1\r\n+PING\r\n")
            reply = bad.read()
            check(isinstance(reply, ErrorReply)
                  and reply.message.startswith("ERR Protocol error"),
                  "malformed request rejected", repr(reply))
            bad.sock.settimeout(2)
            check(bad.sock.recv(1) == b"", "server closed bad connection")
        finally:
            bad.close()

        q = Client(port)
        try:
            eq("QUIT", q.cmd("QUIT"), "OK")
            q.sock.settimeout(2)
            check(q.sock.recv(1) == b"", "server closed after QUIT")
        finally:
            q.close()
    finally:
        c.close()


def test_aof_restart():
    print("\n--- AOF restart (black box) ---")
    port = free_port()
    with tempfile.TemporaryDirectory(prefix="miniredis-it-") as tmp:
        aof = os.path.join(tmp, "it.aof")
        log_path = os.path.join(tmp, "server.log")
        args = ["--appendonly=yes", "--appendfsync=always", "--aof=" + aof]
        process, log = start_server(port, args, log_path)
        try:
            c = Client(port)
            try:
                eq("AOF SET", c.cmd("SET", "it:aof:key", "persisted"), "OK")
                eq("AOF RPUSH", c.cmd("RPUSH", "it:aof:list", "x", "y"), 2)
                eq("AOF HSET", c.cmd("HSET", "it:aof:hash", "f", "v"), 1)
                eq("AOF counter", c.cmd("INCRBY", "it:aof:n", "7"), 7)
            finally:
                c.close()
        finally:
            stop_server(process, log)
        check(os.path.getsize(aof) > 0, "AOF file written", aof)

        process2, log2 = start_server(port, args, log_path)
        try:
            c2 = Client(port)
            try:
                eq("replayed string", c2.cmd("GET", "it:aof:key"), "persisted")
                eq("replayed list", c2.cmd("LRANGE", "it:aof:list", "0", "-1"), ["x", "y"])
                eq("replayed hash", c2.cmd("HGET", "it:aof:hash", "f"), "v")
                eq("replayed counter", c2.cmd("GET", "it:aof:n"), "7")
            finally:
                c2.close()
        finally:
            stop_server(process2, log2)


# ----------------------------------------------------------------------

def main():
    if not Path(JAVA).is_file():
        print("Java not found at %s" % JAVA)
        return 2
    if not JAR.is_file():
        print("dist/miniredis.jar missing, running build.cmd ...")
        subprocess.run(["cmd", "/c", "build.cmd"], cwd=str(ROOT), check=True)

    port = free_port()
    log_path = os.path.join(tempfile.gettempdir(), "miniredis-integration.log")
    process, log = start_server(port, [], log_path)
    print("server started on port %d (pid %d)" % (port, process.pid))
    try:
        test_basic(port)
        test_expiration(port)
        test_lists(port)
        test_hashes_sets(port)
        test_keys_scan(port)
        test_raw_protocol(port)
    finally:
        stop_server(process, log)
    test_aof_restart()

    print("\n==========================================")
    print("TOTAL: %d assertions passed, %d failed" % (passed, failed))
    if failed:
        for failure in failures:
            print("  - " + failure)
        print("RESULT: FAILED")
        return 1
    print("RESULT: ALL INTEGRATION TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())