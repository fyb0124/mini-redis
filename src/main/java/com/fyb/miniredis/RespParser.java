package com.fyb.miniredis;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP2 request reader. It understands both the multibulk form
 * (*N\r\n$len\r\n...\r\n) and the inline form (PING hello\r\n). Bytes are read
 * one at a time from a BufferedInputStream, so TCP fragmentation (half a
 * command in one packet, the rest in the next) and coalescing (multiple
 * commands in one packet) are handled naturally: readCommand() returns exactly
 * one command and the next call continues from the buffer.
 */
public final class RespParser {

    private static final int MAX_LINE = 64 * 1024;
    private static final int MAX_BULK = 64 * 1024 * 1024;
    private static final int MAX_MULTIBULK = 1024 * 1024;

    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    /**
     * Reads one command. Returns null on a clean EOF before any byte of the
     * next command. Throws ProtocolException for malformed input.
     */
    public String[] readCommand() throws IOException {
        while (true) {
            int first = in.read();
            if (first == -1) {
                return null;
            }
            if (first == '*') {
                String line = readLine();
                int count = parseCount(line, "invalid multibulk length");
                if (count <= 0) {
                    continue; // empty array: ignore and read the next command
                }
                if (count > MAX_MULTIBULK) {
                    throw new ProtocolException("invalid multibulk length");
                }
                String[] args = new String[count];
                for (int i = 0; i < count; i++) {
                    int type = in.read();
                    if (type == -1) {
                        throw new ProtocolException("unexpected end of stream");
                    }
                    if (type != '$') {
                        throw new ProtocolException("expected '$', got '" + (char) type + "'");
                    }
                    int len = parseCount(readLine(), "invalid bulk length");
                    if (len < 0 || len > MAX_BULK) {
                        throw new ProtocolException("invalid bulk length");
                    }
                    byte[] data = readN(len);
                    expectCrlf();
                    args[i] = new String(data, StandardCharsets.UTF_8);
                }
                return args;
            }
            String line = readLine(first);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return tokenizeInline(trimmed);
        }
    }

    private static int parseCount(String line, String error) throws ProtocolException {
        try {
            return Integer.parseInt(line.trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException(error);
        }
    }

    private String readLine() throws IOException {
        return readLine(-1);
    }

    /** Reads through '\n', strips a trailing '\r'. */
    private String readLine(int first) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        if (first != -1 && first != '\r') {
            buf.write(first);
        }
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buf.size() == 0) {
                    throw new EOFException("unexpected end of stream");
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > MAX_LINE) {
                throw new ProtocolException("too big inline request");
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private byte[] readN(int n) throws IOException {
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(data, off, n - off);
            if (r == -1) {
                throw new ProtocolException("unexpected end of stream");
            }
            off += r;
        }
        return data;
    }

    private void expectCrlf() throws IOException {
        int a = in.read();
        int b = in.read();
        if (a == '\r' && b == '\n') {
            return;
        }
        // 严格按 RESP 规范要求 CRLF：只发 LF 的报文视为协议错误。
        // 注意这里不能"容忍"LF——已经多读了一个字节 b，容忍会把下一条命令的首字节吞掉。
        throw new ProtocolException("expected CRLF after bulk string");
    }

    /**
     * Splits an inline command on whitespace, honouring single and double
     * quotes. Double quotes support \n, \r, \t, \b, \a and \" escapes.
     */
    static String[] tokenizeInline(String line) throws ProtocolException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inDouble) {
                if (c == '\\' && i + 1 < line.length()) {
                    char n = line.charAt(++i);
                    switch (n) {
                        case 'n' -> cur.append('\n');
                        case 'r' -> cur.append('\r');
                        case 't' -> cur.append('\t');
                        case 'b' -> cur.append('\b');
                        case 'a' -> cur.append('\u0007');
                        default -> cur.append(n);
                    }
                } else if (c == '"') {
                    inDouble = false;
                } else {
                    cur.append(c);
                }
            } else if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    cur.append(c);
                }
            } else if (c == ' ' || c == '\t') {
                if (hasToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else if (c == '"') {
                inDouble = true;
                hasToken = true;
            } else if (c == '\'') {
                inSingle = true;
                hasToken = true;
            } else {
                cur.append(c);
                hasToken = true;
            }
        }
        if (inSingle || inDouble) {
            throw new ProtocolException("unbalanced quotes in request");
        }
        if (hasToken) {
            out.add(cur.toString());
        }
        return out.toArray(new String[0]);
    }
}