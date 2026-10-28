package com.fyb.miniredis;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Runtime configuration. Values come from (lowest to highest priority):
 * built-in defaults, miniredis.properties (or --config FILE), command line.
 *
 * Command line syntax: --port=6400 or --port 6400.
 */
public final class Config {

    public int port = 6399;
    public long maxmemory = 100_000L;
    public boolean appendonly = true;
    public String aofPath = "appendonly.aof";
    public String appendfsync = "everysec";
    public int threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 4);
    public int sweepIntervalMs = 100;

    public static Config load(String[] args) throws IOException {
        Config c = new Config();
        String configPath = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--config")) {
                configPath = requireNext(args, i, "--config");
                i++;
            } else if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            }
        }
        if (configPath == null && new File("miniredis.properties").isFile()) {
            configPath = "miniredis.properties";
        }
        if (configPath != null) {
            c.readProperties(configPath);
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--config")) {
                i++;
                continue;
            }
            if (a.startsWith("--config=")) {
                continue;
            }
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
            String key;
            String value;
            int eq = a.indexOf('=');
            if (eq > 0) {
                key = a.substring(2, eq);
                value = a.substring(eq + 1);
            } else {
                key = a.substring(2);
                value = requireNext(args, i, a);
                i++;
            }
            c.apply(key, value);
        }
        c.validate();
        return c;
    }

    private static String requireNext(String[] args, int i, String arg) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + arg);
        }
        return args[i + 1];
    }

    private void readProperties(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            p.load(in);
        }
        for (String name : p.stringPropertyNames()) {
            apply(name, p.getProperty(name));
        }
    }

    private void apply(String rawKey, String value) {
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value.trim();
        switch (key) {
            case "port" -> port = parseInt(key, v);
            case "maxmemory" -> maxmemory = parseLong(key, v);
            case "appendonly" -> appendonly = parseBoolean(key, v);
            case "aof", "aofpath", "appendfilename" -> aofPath = v;
            case "appendfsync" -> appendfsync = v.toLowerCase(Locale.ROOT);
            case "threads" -> threads = parseInt(key, v);
            case "sweepintervalms", "sweep-interval-ms" -> sweepIntervalMs = parseInt(key, v);
            default -> throw new IllegalArgumentException("Unknown configuration key: " + rawKey);
        }
    }

    private static int parseInt(String key, String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + v);
        }
    }

    private static long parseLong(String key, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + v);
        }
    }

    private static boolean parseBoolean(String key, String v) {
        String s = v.toLowerCase(Locale.ROOT);
        if (s.equals("yes") || s.equals("true") || s.equals("1") || s.equals("on")) {
            return true;
        }
        if (s.equals("no") || s.equals("false") || s.equals("0") || s.equals("off")) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean for " + key + ": " + v);
    }

    private void validate() {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (maxmemory < 1) {
            throw new IllegalArgumentException("maxmemory must be >= 1");
        }
        if (threads < 1 || threads > 4096) {
            throw new IllegalArgumentException("threads out of range: " + threads);
        }
        if (sweepIntervalMs < 10) {
            throw new IllegalArgumentException("sweepIntervalMs must be >= 10");
        }
        if (!appendfsync.equals("always") && !appendfsync.equals("everysec")
                && !appendfsync.equals("no")) {
            throw new IllegalArgumentException("appendfsync must be always|everysec|no");
        }
        if (aofPath == null || aofPath.isEmpty()) {
            throw new IllegalArgumentException("aof path must not be empty");
        }
    }

    public String describe() {
        return "port=" + port + ", maxmemory=" + maxmemory + ", appendonly=" + appendonly
                + ", aof=" + aofPath + ", appendfsync=" + appendfsync + ", threads=" + threads
                + ", sweepIntervalMs=" + sweepIntervalMs;
    }
}