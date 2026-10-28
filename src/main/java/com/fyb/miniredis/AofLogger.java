package com.fyb.miniredis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only file in RESP format.
 *
 * Every successful write command is serialized as a RESP array of bulk
 * strings, exactly like Redis' AOF. On startup the file is parsed with the
 * normal RESP parser and replayed through CommandProcessor in replay mode
 * (which suppresses re-logging).
 *
 * appendfsync modes:
 *   always   - flush + FileDescriptor.sync() before the command returns
 *   everysec - flush on every write, fsync once per second from a daemon thread
 *   no       - flush on every write, never call fsync (OS decides)
 */
public final class AofLogger implements Closeable {

    private final File file;
    private final boolean enabled;
    private final String fsyncMode;
    private final Object lock = new Object();

    private FileOutputStream fileOut;
    private BufferedOutputStream out;
    private volatile boolean dirty;
    private volatile boolean closed;
    private Thread flusher;

    public AofLogger(File file, boolean enabled, String fsyncMode) throws IOException {
        this.file = file;
        this.enabled = enabled;
        this.fsyncMode = fsyncMode.toLowerCase(java.util.Locale.ROOT);
        if (!enabled) {
            return;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create AOF directory: " + parent);
        }
        this.fileOut = new FileOutputStream(file, true);
        this.out = new BufferedOutputStream(fileOut, 64 * 1024);
        if (this.fsyncMode.equals("everysec")) {
            flusher = new Thread(this::flushLoop, "miniredis-aof-flusher");
            flusher.setDaemon(true);
            flusher.start();
        }
    }

    public File file() {
        return file;
    }

    /** Appends one command to the AOF. No-op in replay mode / when disabled. */
    public void append(String[] command) {
        if (!enabled) {
            return;
        }
        byte[] data = encode(command);
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                out.write(data);
                out.flush();
                if (fsyncMode.equals("always")) {
                    fileOut.getFD().sync();
                } else {
                    dirty = true;
                }
            } catch (IOException e) {
                throw new RuntimeException("AOF write failed: " + e.getMessage(), e);
            }
        }
    }

    private void flushLoop() {
        while (!closed) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (!dirty) {
                continue;
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                try {
                    out.flush();
                    fileOut.getFD().sync();
                    dirty = false;
                } catch (IOException e) {
                    System.err.println("[aof] fsync failed: " + e.getMessage());
                }
            }
        }
    }

    public void flush() {
        if (!enabled) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                out.flush();
                if (!fsyncMode.equals("no")) {
                    fileOut.getFD().sync();
                }
                dirty = false;
            } catch (IOException e) {
                System.err.println("[aof] flush failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (!enabled) {
                return;
            }
            try {
                out.flush();
                if (!fsyncMode.equals("no")) {
                    fileOut.getFD().sync();
                }
            } catch (IOException e) {
                System.err.println("[aof] final flush failed: " + e.getMessage());
            }
            try {
                out.close();
            } catch (IOException e) {
                System.err.println("[aof] close failed: " + e.getMessage());
            }
        }
        if (flusher != null) {
            flusher.interrupt();
            try {
                flusher.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Serializes a command as a RESP array. */
    public static byte[] encode(String[] command) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        writeAscii(bos, "*" + command.length + "\r\n");
        for (String arg : command) {
            byte[] data = arg.getBytes(StandardCharsets.UTF_8);
            writeAscii(bos, "$" + data.length + "\r\n");
            bos.write(data, 0, data.length);
            writeAscii(bos, "\r\n");
        }
        return bos.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream bos, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        bos.write(b, 0, b.length);
    }

    /**
     * Parses an AOF file into commands. A truncated tail (possible after a
     * hard crash with appendfsync=no) is ignored with a warning.
     */
    public static List<String[]> load(File file) {
        List<String[]> commands = new ArrayList<>();
        if (!file.isFile() || file.length() == 0) {
            return commands;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            RespParser parser = new RespParser(in);
            while (true) {
                String[] cmd;
                try {
                    cmd = parser.readCommand();
                } catch (IOException e) {
                    System.err.println("[aof] truncated or corrupt tail ignored: " + e.getMessage());
                    break;
                }
                if (cmd == null) {
                    break;
                }
                if (cmd.length > 0) {
                    commands.add(cmd);
                }
            }
        } catch (IOException e) {
            System.err.println("[aof] cannot read " + file + ": " + e.getMessage());
        }
        return commands;
    }
}