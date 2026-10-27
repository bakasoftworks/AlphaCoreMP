package cc.starsector.server.runtime;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Per-datagram SNAP deflate:
 * a compressed SNAP payload is {@code u32 magic "CAWZ" + u32 rawLen + raw-DEFLATE bytes} of ONE
 * AWSS chunk frame; an incompressible frame is emitted as the plain AWSS frame unchanged
 * (raw-fallback — a wrapped payload is only ever emitted when it is strictly smaller, so no
 * payload widens and the 1200-B budget is never re-litigated). The format is
 * self-describing per payload by its leading magic — the in-repo magic-discriminated frame
 * convention — so the 32-B datagram envelope, {@code DatagramFrameCodecContract}, and both
 * golden families are untouched, and a knob-OFF serve is byte-identical to today.
 *
 * <p>Raw DEFLATE ({@code nowrap=true}, level {@value #LEVEL} — the bench GO:
 * 3.39×/2.77× wire-effective, level hard-wired, no level knob): integrity already rides the
 * envelope CRC32 + AWSS frame CRC32, so the zlib header/Adler32 is dead weight. Compressed
 * bytes are NEVER golden-pinned (deflate output is a zlib-release function, only the
 * format is contractual); the 8 fixed header bytes are deterministic and ARE pinned.
 *
 * <p>Consumed by all three lanes from this ONE class (bundled into the mod jar by the
 * {@code client-mod/build.ps1} pilot-class list): the shared {@link DownEmissionComposer}
 * wraps above the fan; the game-free sibling client and the in-game mod session unwrap at
 * their single decode sites. Script-sandbox discipline: byte-array {@code java.util.zip} API
 * only ({@code CRC32} from the same package is long since proven in-game; {@code java.io}
 * stays off this class beyond the allowlisted {@link IOException}; the knob resolver lives
 * server-side on the composer so this class references NOTHING outside those packages).
 *
 * <p>Callers own the {@link Deflater}/{@link Inflater} lifecycle (one per serve loop / session,
 * {@code reset()} here per payload, {@code end()} in the owner's close) and drive each instance
 * from one thread at a time.
 */
public final class SnapDeflateCodec {

    /** Leading payload magic {@code "CAWZ"} — compressed AWSS, deflate. */
    public static final int MAGIC = 0x4341575A;
    /** Wrapper header: u32 magic + u32 rawLen (big-endian, the wire's byte order). */
    public static final int HEADER_BYTES = 8;
    /** Hard-wired deflate level (bench: 9 buys +0.7% for ~2× CPU, 1 gives back ~13%). */
    public static final int LEVEL = 6;
    /**
     * Inflate bomb guard: base L1 never emits {@code rawLen} above the 1164-B payload cap
     * (chunks are priced on their RAW frame bytes), but the DEFERRED compressed-fit packing
     * rider would — the guard anticipates it at 8192 so a future server-side
     * rider never forces a client re-kit. A declared rawLen outside (0, this] fails closed.
     */
    public static final int MAX_RAW_BYTES = 8192;

    private SnapDeflateCodec() {
    }

    /** A fresh raw-DEFLATE deflater at the hard-wired level — one per serve loop, end() owed. */
    public static Deflater newDeflater() {
        return new Deflater(LEVEL, true);
    }

    /** A fresh raw-DEFLATE inflater — one per session, end() owed. */
    public static Inflater newInflater() {
        return new Inflater(true);
    }

    /**
     * Server half: wrap one raw AWSS chunk frame as a CAWZ payload, or return {@code rawFrame}
     * UNCHANGED (the same array) when wrapping would not strictly shrink it (raw-fallback).
     * The deflater must be a {@link #newDeflater()} raw-DEFLATE instance; it is reset here.
     */
    public static byte[] wrapOrRaw(Deflater deflater, byte[] rawFrame) {
        deflater.reset();
        deflater.setInput(rawFrame);
        deflater.finish();
        // Output budget = one byte short of break-even: filling it means no strict win.
        byte[] out = new byte[HEADER_BYTES + Math.max(0, rawFrame.length - HEADER_BYTES)];
        int off = HEADER_BYTES;
        while (!deflater.finished()) {
            int produced = deflater.deflate(out, off, out.length - off);
            off += produced;
            if (produced == 0 && !deflater.finished()) {
                return rawFrame; // budget exhausted — the frame is incompressible at a win
            }
        }
        if (off >= rawFrame.length) {
            return rawFrame;
        }
        out[0] = (byte) (MAGIC >>> 24);
        out[1] = (byte) (MAGIC >>> 16);
        out[2] = (byte) (MAGIC >>> 8);
        out[3] = (byte) MAGIC;
        out[4] = (byte) (rawFrame.length >>> 24);
        out[5] = (byte) (rawFrame.length >>> 16);
        out[6] = (byte) (rawFrame.length >>> 8);
        out[7] = (byte) rawFrame.length;
        return Arrays.copyOf(out, off);
    }

    /** True when {@code payload} leads with the CAWZ wrapper magic. */
    public static boolean isWrapped(byte[] payload) {
        return payload != null && payload.length >= HEADER_BYTES && leadingMagic(payload) == MAGIC;
    }

    /**
     * Client half (both lanes' single decode site): a CAWZ payload inflates back to the exact
     * declared raw frame; any other payload (the raw-fallback, every CTRL kind) returns
     * UNCHANGED. Loud fail-closed on a wrapped payload that lies — over-guard rawLen, corrupt
     * stream, length mismatch, or trailing bytes: it arrived CRC-verified post-admission, so a
     * bad wrapper is a protocol break, never spoof noise.
     */
    public static byte[] unwrapIfWrapped(Inflater inflater, byte[] payload) throws IOException {
        if (!isWrapped(payload)) {
            return payload;
        }
        int rawLen = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        if (rawLen <= 0 || rawLen > MAX_RAW_BYTES) {
            throw new IOException("CAWZ rawLen " + rawLen + " outside (0, " + MAX_RAW_BYTES
                    + "] — inflate bomb guard, fail closed");
        }
        inflater.reset();
        inflater.setInput(payload, HEADER_BYTES, payload.length - HEADER_BYTES);
        byte[] raw = new byte[rawLen];
        int produced = 0;
        try {
            while (produced < rawLen && !inflater.finished()) {
                int n = inflater.inflate(raw, produced, rawLen - produced);
                if (n == 0) {
                    break; // needsInput/needsDictionary — truncated or corrupt, caught below
                }
                produced += n;
            }
            if (produced == rawLen && !inflater.finished()) {
                // The end-of-stream marker may trail the last output byte; a probe that
                // produces ANYTHING means the stream is bigger than it declared.
                if (inflater.inflate(new byte[1], 0, 1) > 0) {
                    throw new IOException(
                            "CAWZ stream exceeds its declared rawLen=" + rawLen + " — fail closed");
                }
            }
        } catch (DataFormatException corrupt) {
            throw new IOException("CAWZ payload corrupt: " + corrupt.getMessage(), corrupt);
        }
        if (produced != rawLen || !inflater.finished()) {
            throw new IOException("CAWZ inflate mismatch: declared rawLen=" + rawLen
                    + " produced=" + produced + " finished=" + inflater.finished()
                    + " — truncated or corrupt, fail closed");
        }
        if (inflater.getRemaining() != 0) {
            throw new IOException("CAWZ payload carries " + inflater.getRemaining()
                    + " trailing bytes after the deflate stream — fail closed");
        }
        return raw;
    }

    private static int leadingMagic(byte[] payload) {
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }
}
