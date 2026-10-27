package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * Allowlist-only big-endian wire primitives for the client mod.
 *
 * <p>Starsector 0.98a-RC8 runs mod-jar/script code under a sandbox classloader
 * ({@code com.fs.starfarer.loading.scripts.B}) that throws
 * {@code SecurityException: File access and reflection are not allowed to scripts.}
 * when sandboxed code loads most {@code java.io.*}, any {@code java.nio.file.File*},
 * {@code java.lang.reflect.*} (bar a few), {@code javax.script.*}, or
 * {@code java.util.prefs.*} class. {@code java.io.DataInputStream},
 * {@code DataOutputStream} and {@code ByteArrayOutputStream} are <em>not</em> on its
 * allowlist, so the mod cannot use them to (de)serialize the server wire frames.
 * {@code java.io.InputStream}, {@code ByteArrayInputStream}, {@code BufferedInputStream},
 * {@code OutputStream}, {@code IOException} (and {@code FileReader}/{@code BufferedReader})
 * <em>are</em> allowlisted, as are all {@code java.net.*} sockets.
 *
 * <p>These helpers reproduce {@code DataInputStream}/{@code DataOutputStream} big-endian
 * semantics over plain {@code InputStream}/{@code byte[]} using only allowlisted types,
 * so the wire bytes stay byte-identical to the server codec — the game-free verifiers
 * ({@code verify-client-mod-*.ps1}) assert exactly that. The sandbox boundary itself
 * (the classloader, its allow/block lists, and the proof there is no off-switch) is
 * RE-recorded.
 */
final class WireBytes {

    private WireBytes() {
    }

    /** Read one byte; return -1 at end of stream (a clean frame boundary). */
    static int readOrEof(InputStream in) throws IOException {
        return in.read();
    }

    /** Read one unsigned byte; throw on a premature end of stream. */
    static int readUnsignedByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("unexpected end of stream");
        }
        return b;
    }

    /** Read one signed byte — {@code DataInputStream.readByte} semantics. */
    static byte readSignedByte(InputStream in) throws IOException {
        return (byte) readUnsignedByte(in);
    }

    static int readUnsignedShort(InputStream in) throws IOException {
        return (readUnsignedByte(in) << 8) | readUnsignedByte(in);
    }

    /** Read a sign-extended big-endian short — {@code DataInputStream.readShort} semantics. */
    static short readShort(InputStream in) throws IOException {
        return (short) readUnsignedShort(in);
    }

    static int readInt(InputStream in) throws IOException {
        return (readUnsignedByte(in) << 24)
                | (readUnsignedByte(in) << 16)
                | (readUnsignedByte(in) << 8)
                | readUnsignedByte(in);
    }

    static long readLong(InputStream in) throws IOException {
        long hi = readInt(in) & 0xffffffffL;
        long lo = readInt(in) & 0xffffffffL;
        return (hi << 32) | lo;
    }

    /**
     * Read a big-endian IEEE-754 float — {@code DataInputStream.readFloat} semantics
     * (folds the {@code Float.intBitsToFloat(readInt(..))} idiom the
     * snapshot reader hand-rolled at every float field).
     */
    static float readFloat(InputStream in) throws IOException {
        return Float.intBitsToFloat(readInt(in));
    }

    /** Fill {@code buf} fully; throw on a premature end of stream. */
    static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("unexpected end of stream");
            }
            off += n;
        }
    }

    /** Big-endian int at {@code off} — the {@code byte[]} twin of {@link #readInt(InputStream)}. */
    static int getInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24)
                | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8)
                | (b[off + 3] & 0xff);
    }

    /** Big-endian long at {@code off} — the {@code byte[]} twin of {@link #readLong(InputStream)}. */
    static long getLong(byte[] b, int off) {
        long hi = getInt(b, off) & 0xffffffffL;
        long lo = getInt(b, off + 4) & 0xffffffffL;
        return (hi << 32) | lo;
    }

    static int putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
        return off + 2;
    }

    static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    static int putLong(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 56);
        b[off + 1] = (byte) (v >>> 48);
        b[off + 2] = (byte) (v >>> 40);
        b[off + 3] = (byte) (v >>> 32);
        b[off + 4] = (byte) (v >>> 24);
        b[off + 5] = (byte) (v >>> 16);
        b[off + 6] = (byte) (v >>> 8);
        b[off + 7] = (byte) v;
        return off + 8;
    }

    static int putByte(byte[] b, int off, int v) {
        b[off] = (byte) v;
        return off + 1;
    }
}
