package com.fyb.miniredis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * List / hash / set operations, split out of KeyValueStore so each file stays
 * focused. All methods mutate the shared ConcurrentHashMap through compute(),
 * which keeps read-modify-write sequences atomic per key.
 */
public final class CollectionStore {

    private final KeyValueStore store;

    CollectionStore(KeyValueStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    public int listPush(String key, boolean left, List<String> values) {
        final int[] length = {0};
        final boolean[] expired = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e == null) {
                e = new KeyValueStore.Entry(Value.list(), 0);
            } else if (e.value.type != Value.Type.LIST) {
                throw new KeyValueStore.WrongTypeException();
            }
            for (String v : values) {
                if (left) {
                    e.value.list.addFirst(v);
                } else {
                    e.value.list.addLast(v);
                }
            }
            length[0] = e.value.list.size();
            return e;
        });
        if (expired[0]) {
            store.forgetExpired(key);
        }
        store.touchAndEvict(key);
        return length[0];
    }

    public String listPop(String key, boolean left) {
        final String[] popped = {null};
        final boolean[] removed = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                removed[0] = true;
            }
            if (e == null) {
                return null;
            }
            if (e.value.type != Value.Type.LIST) {
                throw new KeyValueStore.WrongTypeException();
            }
            String v = left ? e.value.list.pollFirst() : e.value.list.pollLast();
            if (v == null) {
                removed[0] = true;
                return null;
            }
            popped[0] = v;
            if (e.value.list.isEmpty()) {
                removed[0] = true;
                return null;
            }
            return e;
        });
        if (removed[0]) {
            store.lru.remove(key);
        } else if (popped[0] != null) {
            store.lru.touch(key);
        }
        return popped[0];
    }

    public int listLength(String key) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return 0;
        }
        if (e.value.type != Value.Type.LIST) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.list.size();
    }

    public List<String> listRange(String key, int start, int stop) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return Collections.emptyList();
        }
        if (e.value.type != Value.Type.LIST) {
            throw new KeyValueStore.WrongTypeException();
        }
        List<String> all = new ArrayList<>(e.value.list);
        int size = all.size();
        if (start < 0) {
            start = Math.max(size + start, 0);
        }
        if (stop < 0) {
            stop = size + stop;
        }
        if (start > stop || start >= size || stop < 0) {
            return Collections.emptyList();
        }
        if (stop >= size) {
            stop = size - 1;
        }
        return new ArrayList<>(all.subList(start, stop + 1));
    }

    public String listIndex(String key, int index) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return null;
        }
        if (e.value.type != Value.Type.LIST) {
            throw new KeyValueStore.WrongTypeException();
        }
        ArrayDeque<String> list = e.value.list;
        int size = list.size();
        if (index < 0) {
            index = size + index;
        }
        if (index < 0 || index >= size) {
            return null;
        }
        int i = 0;
        for (String v : list) {
            if (i++ == index) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Hashes
    // ------------------------------------------------------------------

    public int hashSet(String key, String[] fieldValues) {
        final int[] added = {0};
        final boolean[] expired = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e == null) {
                e = new KeyValueStore.Entry(Value.hash(), 0);
            } else if (e.value.type != Value.Type.HASH) {
                throw new KeyValueStore.WrongTypeException();
            }
            for (int i = 0; i + 1 < fieldValues.length; i += 2) {
                if (!e.value.hash.containsKey(fieldValues[i])) {
                    added[0]++;
                }
                e.value.hash.put(fieldValues[i], fieldValues[i + 1]);
            }
            return e;
        });
        if (expired[0]) {
            store.forgetExpired(key);
        }
        store.touchAndEvict(key);
        return added[0];
    }

    public String hashGet(String key, String field) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return null;
        }
        if (e.value.type != Value.Type.HASH) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.hash.get(field);
    }

    public int hashDel(String key, String[] fields) {
        final int[] removedFields = {0};
        final boolean[] removeKey = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                removeKey[0] = true;
            }
            if (e == null) {
                return null;
            }
            if (e.value.type != Value.Type.HASH) {
                throw new KeyValueStore.WrongTypeException();
            }
            for (String f : fields) {
                if (e.value.hash.remove(f) != null) {
                    removedFields[0]++;
                }
            }
            if (e.value.hash.isEmpty()) {
                removeKey[0] = true;
                return null;
            }
            return e;
        });
        if (removeKey[0]) {
            store.lru.remove(key);
        } else if (removedFields[0] > 0) {
            store.lru.touch(key);
        }
        return removedFields[0];
    }

    public List<String> hashGetAll(String key) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return Collections.emptyList();
        }
        if (e.value.type != Value.Type.HASH) {
            throw new KeyValueStore.WrongTypeException();
        }
        List<String> out = new ArrayList<>(e.value.hash.size() * 2);
        for (Map.Entry<String, String> en : e.value.hash.entrySet()) {
            out.add(en.getKey());
            out.add(en.getValue());
        }
        return out;
    }

    public int hashLength(String key) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return 0;
        }
        if (e.value.type != Value.Type.HASH) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.hash.size();
    }

    public boolean hashExists(String key, String field) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return false;
        }
        if (e.value.type != Value.Type.HASH) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.hash.containsKey(field);
    }

    // ------------------------------------------------------------------
    // Sets
    // ------------------------------------------------------------------

    public int setAdd(String key, String[] members) {
        final int[] added = {0};
        final boolean[] expired = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                expired[0] = true;
            }
            if (e == null) {
                e = new KeyValueStore.Entry(Value.set(), 0);
            } else if (e.value.type != Value.Type.SET) {
                throw new KeyValueStore.WrongTypeException();
            }
            for (String m : members) {
                if (e.value.set.add(m)) {
                    added[0]++;
                }
            }
            return e;
        });
        if (expired[0]) {
            store.forgetExpired(key);
        }
        store.touchAndEvict(key);
        return added[0];
    }

    public int setRemove(String key, String[] members) {
        final int[] removed = {0};
        final boolean[] removeKey = {false};
        store.map.compute(key, (k, e) -> {
            if (e != null && KeyValueStore.isExpired(e, System.currentTimeMillis())) {
                e = null;
                removeKey[0] = true;
            }
            if (e == null) {
                return null;
            }
            if (e.value.type != Value.Type.SET) {
                throw new KeyValueStore.WrongTypeException();
            }
            for (String m : members) {
                if (e.value.set.remove(m)) {
                    removed[0]++;
                }
            }
            if (e.value.set.isEmpty()) {
                removeKey[0] = true;
                return null;
            }
            return e;
        });
        if (removeKey[0]) {
            store.lru.remove(key);
        } else if (removed[0] > 0) {
            store.lru.touch(key);
        }
        return removed[0];
    }

    public List<String> setMembers(String key) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return Collections.emptyList();
        }
        if (e.value.type != Value.Type.SET) {
            throw new KeyValueStore.WrongTypeException();
        }
        return new ArrayList<>(e.value.set);
    }

    public int setSize(String key) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return 0;
        }
        if (e.value.type != Value.Type.SET) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.set.size();
    }

    public boolean setIsMember(String key, String member) {
        KeyValueStore.Entry e = store.getEntry(key);
        if (e == null) {
            return false;
        }
        if (e.value.type != Value.Type.SET) {
            throw new KeyValueStore.WrongTypeException();
        }
        return e.value.set.contains(member);
    }
}