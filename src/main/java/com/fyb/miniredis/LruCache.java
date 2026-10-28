package com.fyb.miniredis;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Approximate LRU order for keys. The map itself lives in KeyValueStore;
 * this class only maintains "least recently used first" ordering. All
 * operations are synchronized because LinkedHashMap in access-order mode is
 * not thread safe.
 *
 * Eviction is driven by KeyValueStore: it polls candidates from here and
 * removes them from the concurrent map. A key can therefore briefly exist in
 * the order map after it was deleted from the data map; pollEldest() simply
 * skips such stale entries.
 */
public final class LruCache {

    private final int maxKeys;
    private final LinkedHashMap<String, Boolean> order;

    public LruCache(long maxKeys) {
        this.maxKeys = (int) Math.min(maxKeys, Integer.MAX_VALUE);
        this.order = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized void touch(String key) {
        order.put(key, Boolean.TRUE);
    }

    public synchronized void remove(String key) {
        order.remove(key);
    }

    /** Removes and returns the least recently used key, or null when empty. */
    public synchronized String pollEldest() {
        Iterator<Map.Entry<String, Boolean>> it = order.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        String key = it.next().getKey();
        it.remove();
        return key;
    }

    public synchronized int size() {
        return order.size();
    }

    public synchronized void clear() {
        order.clear();
    }

    public int maxKeys() {
        return maxKeys;
    }
}