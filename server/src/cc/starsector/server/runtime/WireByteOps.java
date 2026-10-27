package cc.starsector.server.runtime;

/**
 * Shared allowlist-clean big-endian byte ops over a fixed {@code byte[]} — the fold
 * of the private byte-op blocks previously copied into five codecs
 * ({@link FireDischargeDeclarationCodec}, {@link PilotIntentWireCodec},
 * {@link PilotWorldViewWireCodec}, {@link SeatNameFrameCodec}, {@link SeatStateFrameCodec};
 * {@code AuthoritativeWorldSnapshotCodec} stays on {@code Data*Streams} and is OUT of the fold).
 *
 * <p>Reproduces {@code DataOutput}/{@code DataInput} big-endian semantics — floats via the same
 * {@link Float#floatToIntBits}/{@link Float#intBitsToFloat} the streams use — so encodings are
 * byte-identical to the prior per-codec copies. Asserted, together with the compact-vs-verbose
 * {@code putLong} equality D6 requires, by
 * {@code ServerContractTests.wireByteOpsMatchDataStreamBytesAndCompactPutLong}. {@link #putLong}
 * is the compact two-{@code putInt} form (the Seat codecs'); the three older codecs' verbose
 * 8-shift form was byte-equal and is deleted.
 *
 * <p><b>No {@code Data*Stream}/{@code ByteArrayOutputStream} here, ever:</b>
 * {@link PilotIntentWireCodec} and {@link PilotWorldViewWireCodec} load in-game under Starsector's
 * script classloader {@code B}, whose STATIC name blocklist denies those classes — and this class
 * rides the client mod jar beside them ({@code client-mod/build.ps1} {@code $pilotClasses}; if it
 * is missing there the mod dies in-game with {@code NoClassDefFoundError}). The client mod's OWN
 * readers keep their separate hand-mirrored ops ({@code WireBytes} + reader-local getters) — that
 * client-side fold is deliberately deferred to the next natural client-reader touch.
 *
 *
 * <p>Every {@code put*} returns the advanced offset ({@code off + width}); {@code get*} takes the
 * offset and returns the value — exactly the signatures the five codecs already used, so the fold
 * left call sites textually unchanged (static imports).
 */
public final class WireByteOps {

    private WireByteOps() {}

    /** Write the low 8 bits of {@code v}; returns {@code off + 1}. */
    public static int putByte(byte[] b, int off, int v) {
        b[off] = (byte) v;
        return off + 1;
    }

    /** Write the low 16 bits of {@code v} big-endian; returns {@code off + 2}. */
    public static int putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
        return off + 2;
    }

    /** Write {@code v} big-endian; returns {@code off + 4}. */
    public static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    /** Write {@code v} big-endian; returns {@code off + 8}. */
    public static int putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) (v >>> 32));
        putInt(b, off + 4, (int) v);
        return off + 8;
    }

    /** Write {@code v} as {@link Float#floatToIntBits} big-endian; returns {@code off + 4}. */
    public static int putFloat(byte[] b, int off, float v) {
        return putInt(b, off, Float.floatToIntBits(v));
    }

    /** Read 2 bytes big-endian as an unsigned 16-bit value. */
    public static int getUnsignedShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Read 4 bytes big-endian. */
    public static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Read 8 bytes big-endian. */
    public static long getLong(byte[] b, int off) {
        long hi = getInt(b, off) & 0xFFFFFFFFL;
        long lo = getInt(b, off + 4) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    /** Read 4 bytes big-endian as {@link Float#intBitsToFloat}. */
    public static float getFloat(byte[] b, int off) {
        return Float.intBitsToFloat(getInt(b, off));
    }
}
