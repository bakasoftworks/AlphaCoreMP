package cc.starsector.server.runtime;

import java.util.Objects;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * W12 L2 — the {@code VCFG} DOWN CTRL frame: the server-dictated
 * voice sheet every client encodes/plays against. Sent once at admit (the reader thread's admit
 * spool, right after the variant pick) and re-sent to every live session on each operator
 * {@code /voice} retune — tuning never needs a handshake bump or a kit re-pack (the
 * server-settable-not-client-baked law). A NEW CTRL magic: the client's unknown-down-magic law is
 * why the handshake version bumps to v9 beside this leg.
 *
 * <p><b>Layout (22 B fixed, big-endian like every sibling codec; schema 2 —
 * schema 1 was the 17 B shape without the jitter trio):</b> {@code magic u32 'VCFG'} ·
 * {@code schema u16} ({@link #SCHEMA_VERSION}) · {@code kbps u16} · {@code frameMs u8} ·
 * {@code complexity u8} · {@code flags u8} (bit0 fec, bit1 echo, bit2 lowDelay; bits 3-7 refuse) ·
 * {@code maxTalkers u8} · {@code proxFullSu u16} · {@code proxCullSu u16} · {@code relayTickMs u8}
 * (the EFFECTIVE tick — the client sizes its jitter buffer on it, never on the raw 0 knob) ·
 * {@code jitterMinFrames u8} · {@code jitterMaxMs u16} · {@code jitterShrinkMs u16} (the (v1)
 * jitter-aggression trio the client's {@code TalkerStream} adopts, {@code VoiceConfig} javadoc).
 *
 * <p>{@link #decode} is fail-closed: exact length, magic, schema, undefined flag bits, and every
 * range refusal the {@link VoiceConfig} constructor carries ⇒ {@link IllegalArgumentException}.
 * The client twin at L3 hand-mirrors these bytes and carries
 * {@code Mirrors VoiceConfigFrameCodec.SCHEMA_VERSION} for the session-start parity checker.
 */
public final class VoiceConfigFrameCodec {

    /** VCFG magic = ASCII "VCFG". */
    public static final int MAGIC = 0x56434647;
    /** Schema version (2 = the jitter trio). Mirrored by the L3 client reader
     *  ({@code VoiceConfigReader.SCHEMA_VERSION}). */
    public static final int SCHEMA_VERSION = 2;
    /** magic(4) + schema(2) + kbps(2) + frameMs(1) + complexity(1) + flags(1) + maxTalkers(1)
     * + proxFullSu(2) + proxCullSu(2) + relayTickMs(1) + jitterMinFrames(1) + jitterMaxMs(2)
     * + jitterShrinkMs(2). */
    public static final int FRAME_BYTES = 22;

    public static final int FLAG_FEC = 0x01;
    public static final int FLAG_ECHO = 0x02;
    public static final int FLAG_LOW_DELAY = 0x04;
    public static final int FLAGS_DEFINED = FLAG_FEC | FLAG_ECHO | FLAG_LOW_DELAY;

    private VoiceConfigFrameCodec() {
    }

    /** Encode the sheet. Runs on our own validated record — every field already fits its width. */
    public static byte[] encode(VoiceConfig config) {
        Objects.requireNonNull(config, "config");
        byte[] out = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(out, off, MAGIC);
        off = putShort(out, off, SCHEMA_VERSION);
        off = putShort(out, off, config.kbps());
        out[off++] = (byte) config.frameMs();
        out[off++] = (byte) config.complexity();
        out[off++] = (byte) ((config.fec() ? FLAG_FEC : 0) | (config.echo() ? FLAG_ECHO : 0)
                | (config.lowDelay() ? FLAG_LOW_DELAY : 0));
        out[off++] = (byte) config.maxTalkers();
        off = putShort(out, off, config.proxFullSu());
        off = putShort(out, off, config.proxCullSu());
        out[off++] = (byte) config.effectiveRelayTickMs();
        out[off++] = (byte) config.jitterMinFrames();
        off = putShort(out, off, config.jitterMaxMs());
        off = putShort(out, off, config.jitterShrinkMs());
        return out;
    }

    /**
     * Decode one VCFG frame (the whole CTRL payload). Throws {@link IllegalArgumentException} on
     * any out-of-domain shape. {@code enabled} is not on the wire (it rides WELCOME matchFlags
     * bit 2) — the decoded record reads {@code enabled=true}.
     */
    public static VoiceConfig decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != FRAME_BYTES) {
            throw new IllegalArgumentException("VCFG must be exactly " + FRAME_BYTES + " B: "
                    + payload.length);
        }
        int off = 0;
        int magic = getInt(payload, off); off += 4;
        if (magic != MAGIC) {
            throw new IllegalArgumentException("VCFG bad magic 0x" + Integer.toHexString(magic));
        }
        int schema = getUnsignedShort(payload, off); off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("VCFG unsupported schema " + schema);
        }
        int kbps = getUnsignedShort(payload, off); off += 2;
        int frameMs = payload[off++] & 0xFF;
        int complexity = payload[off++] & 0xFF;
        int flags = payload[off++] & 0xFF;
        if ((flags & ~FLAGS_DEFINED) != 0) {
            throw new IllegalArgumentException("VCFG flags out of domain: 0x"
                    + Integer.toHexString(flags));
        }
        int maxTalkers = payload[off++] & 0xFF;
        int proxFull = getUnsignedShort(payload, off); off += 2;
        int proxCull = getUnsignedShort(payload, off); off += 2;
        int relayTickMs = payload[off++] & 0xFF;
        if (relayTickMs < 1) {
            throw new IllegalArgumentException("VCFG relayTickMs must be >= 1 on the wire");
        }
        int jitterMinFrames = payload[off++] & 0xFF;
        int jitterMaxMs = getUnsignedShort(payload, off); off += 2;
        int jitterShrinkMs = getUnsignedShort(payload, off); off += 2;
        return new VoiceConfig(true, kbps, frameMs, complexity, (flags & FLAG_FEC) != 0,
                (flags & FLAG_LOW_DELAY) != 0, (flags & FLAG_ECHO) != 0, maxTalkers, proxFull,
                proxCull, relayTickMs, jitterMinFrames, jitterMaxMs, jitterShrinkMs);
    }
}
