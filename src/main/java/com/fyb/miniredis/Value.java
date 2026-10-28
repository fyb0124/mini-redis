package com.fyb.miniredis;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * A single stored value. Only one of the four concrete representations is
 * non-null; the others are null. This mirrors Redis' object types without
 * introducing a class hierarchy.
 */
public final class Value {

    public enum Type {
        STRING, LIST, HASH, SET
    }

    public final Type type;
    public String str;
    public final ArrayDeque<String> list;
    public final LinkedHashMap<String, String> hash;
    public final LinkedHashSet<String> set;

    private Value(Type type, String str, ArrayDeque<String> list,
                  LinkedHashMap<String, String> hash, LinkedHashSet<String> set) {
        this.type = type;
        this.str = str;
        this.list = list;
        this.hash = hash;
        this.set = set;
    }

    public static Value string(String s) {
        return new Value(Type.STRING, s, null, null, null);
    }

    public static Value list() {
        return new Value(Type.LIST, null, new ArrayDeque<>(), null, null);
    }

    public static Value hash() {
        return new Value(Type.HASH, null, null, new LinkedHashMap<>(), null);
    }

    public static Value set() {
        return new Value(Type.SET, null, null, null, new LinkedHashSet<>());
    }

    public String typeName() {
        return switch (type) {
            case STRING -> "string";
            case LIST -> "list";
            case HASH -> "hash";
            case SET -> "set";
        };
    }
}