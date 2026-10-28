package com.fyb.miniredis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe keyspace with lazy + active expiration and LRU eviction.
 * String and expiration commands live here; list/hash/set operations are in
 * CollectionStore.
 *
 * Concurrency strategy:
 *  - the key -> Entry map is a ConcurrentHashMap, so plain reads are lock free;
 *  - every command that must be atomic (INCR, SET NX, EXPIRE, pushes, ...)
 *    uses ConcurrentHashMap.compute(), which locks a single bin;
 *  - LRU order is guarded by its own monitor in LruCache.
 *
 * maxmemory is counted in keys, as required by the project specification.
 */
public final class KeyValueStore {

    public static final class Entry {
        public final Value value;
        public volatile long expireAt; // epoch millis, 0 == no expiration

        public Entry(Value value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    public static final class WrongTypeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public WrongTypeException() {
            super("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
    }

    public static final class ScanResult {
        public final long cursor;
        public final List<String> keys;

        public ScanResult(long cursor, List<String> keys) {
            this.cursor = cursor;
            this.keys = keys;
        }
    }

    final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    final LruCache lru;
    final AtomicLong expiredCount = new AtomicLong();

    private final long maxKeys;
    private final AtomicLong evictedCount = new AtomicLong();
    private final CollectionStore collections = new CollectionStore(this);

    public KeyValueStore(long maxKeys) {
        this.maxKeys = Math.max(1, maxKeys);
        this.lru = new LruCache(this.maxKeys);
    }

    public CollectionStore collections() {
        return collections;
    }

    static boolean isExpired(Entry e, long now) {
        return e.expireAt > 0 && e.expireAt <= now;
    }

    void forgetExpired(String key) {
        lru.remove(key);
        expiredCount.incrementAndGet();
    }

    void touchAndEvict(String key) {
        lru.touch(key);
        evictIfNeeded();
    }

    void evictIfNeeded() {
        while (map.size() > maxKeys) {
            String victim = lru.pollEldest();
            if (victim == null) {
                break;
            }
            if (map.remove(victim) != null) {
                evictedCount.incrementAndGet();
            }
        }
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ------------------------------------------------------------------
    // Generic key operations
    // ------------------------------------------------------------------

    /**
     * Returns a live entry and refreshes its LRU position. Expired entries are
     * removed lazily. Returns null when the key does not exist.
     */
    public Entry getEntry(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (isExpired(e, System.currentTimeMillis())) {
            if (map.remove(key, e)) {
                forgetExpired(key);
            }
            return null;
        }
        lru.touch(key);
        return e;
    }

    public boolean exists(String key) {
        return getEntry(key) != null;
    }

    public long size() {
        long now = System.currentTimeMillis();
        long n = 0;
        for (Entry e : map.values()) {
            if (!isExpired(e, now)) {
                n++;
            }
        }
        return n;
    }

    public long expiredCount() {
        return expiredCount.get();
    }

    public long evictedCount() {
        return evictedCount.get();
    }

    public int expireCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Entry e : map.values()) {
            if (e.expireAt > 0 && !isExpired(e, now)) {
                n++;
            }
        }
        return n;
    }

    public String type(String key) {
        Entry e = getEntry(key);
        return e == null ? "none" : e.value.typeName();
    }

    /** Live keys, sorted for deterministic KEYS/SCAN output. */
    public List<String> keys() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>(map.size());
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            if (isExpired(en.getValue(), now)) {
                if (map.remove(en.getKey(), en.getValue())) {
                    forgetExpired(en.getKey());
                }
            } else {
                out.add(en.getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    public List<String> keysMatching(String pattern) {
        List<String> out = new ArrayList<>();
        for (String k : keys()) {
            if (Glob.match(pattern, k)) {
                out.add(k);
            }
        }
        return out;
    }

    public ScanResult scan(long cursor, String pattern, int count) {
        List<String> all = keysMatching(pattern);
        if (cursor < 0) {
            cursor = 0;
        }
        if (cursor >= all.size()) {
            return new ScanResult(0, Collections.emptyList());
        }
        int from = (int) cursor;
        int to = (int) Math.min(cursor + count, all.size());
        List<String> page = new ArrayList<>(all.subList(from, to));
        long next = to >= all.size() ? 0 : to;
        return new ScanResult(next, page);
    }

    /** Unconditional write (SET / MSET); clears any previous TTL. */
    public void put(String key, Value value, long expireAt) {
        map.put(key, new Entry(value, expireAt));
        touchAndEvict(key);
    }

    /**
     * Atomic conditional SET. nx and xx are mutually exclusive.
     * Returns true when the value was written.
     */
    public boolean set(String key, String value, long expireAt, boolean nx, boolean xx) {
        final boolean[] written = {false};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e != null && isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (nx && e != null) {
                written[0] = false;
                return e;
            }
            if (xx && e == null) {
                written[0] = false;
                return null;
            }
            written[0] = true;
            return new Entry(Value.string(value), expireAt);
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        if (written[0]) {
            touchAndEvict(key);
        }
        return written[0];
    }

    /** Deletes a live key; an already expired key counts as absent. */
    public boolean delete(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return false;
        }
        if (isExpired(e, System.currentTimeMillis())) {
            if (map.remove(key, e)) {
                forgetExpired(key);
            }
            return false;
        }
        boolean removed = map.remove(key, e);
        if (removed) {
            lru.remove(key);
        }
        return removed;
    }

    public void flush() {
        map.clear();
        lru.clear();
    }

    public int purgeExpired(int limit) {
        long now = System.currentTimeMillis();
        int removed = 0;
        int scanned = 0;
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext() && scanned < limit) {
            Map.Entry<String, Entry> en = it.next();
            scanned++;
            if (isExpired(en.getValue(), now)) {
                if (map.remove(en.getKey(), en.getValue())) {
                    forgetExpired(en.getKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // String commands
    // ------------------------------------------------------------------

    public String getString(String key) {
        Entry e = getEntry(key);
        if (e == null) {
            return null;
        }
        if (e.value.type != Value.Type.STRING) {
            throw new WrongTypeException();
        }
        return e.value.str;
    }

    public int stringLength(String key) {
        String s = getString(key);
        return s == null ? 0 : utf8Length(s);
    }

    public int append(String key, String suffix) {
        final int[] len = {0};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e != null && isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e == null) {
                len[0] = utf8Length(suffix);
                return new Entry(Value.string(suffix), 0);
            }
            if (e.value.type != Value.Type.STRING) {
                throw new WrongTypeException();
            }
            e.value.str = e.value.str + suffix;
            len[0] = utf8Length(e.value.str);
            return e;
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        touchAndEvict(key);
        return len[0];
    }

    public long incr(String key, long delta) {
        final long[] result = {0};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e != null && isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e == null) {
                result[0] = delta;
                return new Entry(Value.string(Long.toString(delta)), 0);
            }
            if (e.value.type != Value.Type.STRING) {
                throw new WrongTypeException();
            }
            long current = Long.parseLong(e.value.str);
            long next = Math.addExact(current, delta);
            e.value.str = Long.toString(next);
            result[0] = next;
            return e;
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        touchAndEvict(key);
        return result[0];
    }

    /** Returns old value (or null) and replaces it, clearing the TTL. */
    public String getSet(String key, String value) {
        final String[] old = {null};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e != null && isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e != null) {
                if (e.value.type != Value.Type.STRING) {
                    throw new WrongTypeException();
                }
                old[0] = e.value.str;
            }
            return new Entry(Value.string(value), 0);
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        touchAndEvict(key);
        return old[0];
    }

    public String getDel(String key) {
        Entry e = getEntry(key);
        if (e == null) {
            return null;
        }
        if (e.value.type != Value.Type.STRING) {
            throw new WrongTypeException();
        }
        String v = e.value.str;
        map.remove(key, e);
        lru.remove(key);
        return v;
    }

    // ------------------------------------------------------------------
    // Expiration
    // ------------------------------------------------------------------

    /** Sets an absolute expiration. A past timestamp deletes the key. */
    public boolean expireAt(String key, long absMillis) {
        long now = System.currentTimeMillis();
        if (absMillis <= now) {
            return delete(key);
        }
        final boolean[] changed = {false};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e == null || isExpired(e, System.currentTimeMillis())) {
                expired[0] = e != null;
                return null;
            }
            e.expireAt = absMillis;
            changed[0] = true;
            return e;
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        return changed[0];
    }

    public boolean persist(String key) {
        final boolean[] changed = {false};
        final boolean[] expired = {false};
        map.compute(key, (k, e) -> {
            if (e == null || isExpired(e, System.currentTimeMillis())) {
                expired[0] = e != null;
                return null;
            }
            if (e.expireAt != 0) {
                e.expireAt = 0;
                changed[0] = true;
            }
            return e;
        });
        if (expired[0]) {
            forgetExpired(key);
        }
        if (changed[0]) {
            lru.touch(key);
        }
        return changed[0];
    }

    /** -2 missing, -1 no TTL, otherwise remaining milliseconds. */
    public long ttlMillis(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return -2;
        }
        long now = System.currentTimeMillis();
        if (isExpired(e, now)) {
            if (map.remove(key, e)) {
                forgetExpired(key);
            }
            return -2;
        }
        lru.touch(key);
        if (e.expireAt == 0) {
            return -1;
        }
        return e.expireAt - now;
    }

    // Only exposed for tests / diagnostics.
    Set<String> rawKeys() {
        return map.keySet();
    }
}