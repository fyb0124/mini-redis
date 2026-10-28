package com.fyb.miniredis;

/**
 * Redis-style glob matcher used by KEYS and SCAN MATCH.
 *
 * Supported syntax:
 *   *        any sequence (including empty)
 *   ?        exactly one character
 *   [abc]    one character from the set
 *   [a-z]    one character from the range
 *   [^abc]   one character not in the set (also [!abc])
 *   \x       literal x
 *
 * The matcher uses the classic single-star backtracking algorithm, so it runs
 * in O(pattern * text) worst case and never recurses.
 */
public final class Glob {

    private Glob() {
    }

    public static boolean match(String pattern, String text) {
        int p = 0;
        int s = 0;
        int star = -1;
        int mark = 0;
        while (s < text.length()) {
            boolean ok = false;
            int nextP = p;
            if (p < pattern.length()) {
                char pc = pattern.charAt(p);
                if (pc == '*') {
                    star = p;
                    mark = s;
                    p++;
                    continue;
                } else if (pc == '?') {
                    ok = true;
                    nextP = p + 1;
                } else if (pc == '[') {
                    int end = classEnd(pattern, p);
                    if (end > 0) {
                        ok = classMatches(pattern, p, end, text.charAt(s));
                        nextP = end + 1;
                    } else {
                        ok = text.charAt(s) == '[';
                        nextP = p + 1;
                    }
                } else if (pc == '\\' && p + 1 < pattern.length()) {
                    ok = pattern.charAt(p + 1) == text.charAt(s);
                    nextP = p + 2;
                } else {
                    ok = pc == text.charAt(s);
                    nextP = p + 1;
                }
            }
            if (ok) {
                p = nextP;
                s++;
            } else if (star >= 0) {
                p = star + 1;
                s = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length()) {
            if (pattern.charAt(p) == '*') {
                p++;
            } else if (pattern.charAt(p) == '\\' && p + 1 < pattern.length()
                    && pattern.charAt(p + 1) == '*') {
                // an escaped '*' cannot match an empty remainder
                return false;
            } else {
                return false;
            }
        }
        return p == pattern.length();
    }

    /** Returns the index of the closing ']' or -1 when malformed. */
    private static int classEnd(String p, int start) {
        int i = start + 1;
        if (i < p.length() && (p.charAt(i) == '^' || p.charAt(i) == '!')) {
            i++;
        }
        if (i < p.length() && p.charAt(i) == ']') {
            i++; // a leading ']' is a literal member
        }
        while (i < p.length() && p.charAt(i) != ']') {
            i++;
        }
        return i < p.length() ? i : -1;
    }

    private static boolean classMatches(String p, int start, int end, char c) {
        int i = start + 1;
        boolean negate = false;
        if (i < end && (p.charAt(i) == '^' || p.charAt(i) == '!')) {
            negate = true;
            i++;
        }
        boolean found = false;
        boolean first = true;
        while (i < end) {
            char lo = p.charAt(i);
            if (first && lo == ']') {
                if (c == ']') {
                    found = true;
                }
                i++;
                first = false;
                continue;
            }
            first = false;
            if (lo == '\\' && i + 1 < end) {
                lo = p.charAt(++i);
            }
            if (i + 2 < end && p.charAt(i + 1) == '-') {
                char hi = p.charAt(i + 2);
                if (c >= lo && c <= hi) {
                    found = true;
                }
                i += 3;
            } else {
                if (c == lo) {
                    found = true;
                }
                i++;
            }
        }
        return negate != found;
    }
}