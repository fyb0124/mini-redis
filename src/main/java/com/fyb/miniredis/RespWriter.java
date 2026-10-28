package com.fyb.miniredis;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RESP2 reply encoder. Command handlers return plain Java values and this
 * class maps them to the wire format:
 *
 *   null                -> $-1\r\n          (nil bulk)
 *   SimpleString        -> +OK\r\n
 *   ErrorReply          -> -ERR ...\r\n
 *   Long / Integer      -> :42\r\n
 *   String / byte[]     -> $len\r\n...\r\n
 *   List<?>             -> *N\r\n...
 */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NIL_BULK = {'$', '-', '1', '\r', '\n'};

    public static final class SimpleString {
        public final String value;

        public SimpleString(String value) {
            this.value = value;
        }
    }

    public static final class ErrorReply {
        public final String message;

        public ErrorReply(String message) {
            this.message = message;
        }
    }

    private RespWriter() {
    }

    public static byte[] encode(Object reply) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            write(reply, bos);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    public static void write(Object reply, OutputStream out) throws IOException {
        if (reply == null) {
            out.write(NIL_BULK);
        } else if (reply instanceof SimpleString s) {
            out.write('+');
            out.write(s.value.getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
        } else if (reply instanceof ErrorReply e) {
            out.write('-');
            out.write(sanitize(e.message).getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
        } else if (reply instanceof Long l) {
            out.write((':'));
            out.write(Long.toString(l).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
        } else if (reply instanceof Integer i) {
            out.write((':'));
            out.write(Integer.toString(i).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
        } else if (reply instanceof String s) {
            writeBulk(out, s.getBytes(StandardCharsets.UTF_8));
        } else if (reply instanceof byte[] b) {
            writeBulk(out, b);
        } else if (reply instanceof List<?> list) {
            out.write('*');
            out.write(Integer.toString(list.size()).getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
            for (Object item : list) {
                write(item, out);
            }
        } else if (reply instanceof Double d) {
            writeBulk(out, formatDouble(d).getBytes(StandardCharsets.US_ASCII));
        } else {
            throw new IllegalArgumentException("Unsupported reply type: " + reply.getClass());
        }
    }

    private static void writeBulk(OutputStream out, byte[] data) throws IOException {
        out.write('$');
        out.write(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(data);
        out.write(CRLF);
    }

    private static String sanitize(String message) {
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d) + ".0";
        }
        return Double.toString(d);
    }
}