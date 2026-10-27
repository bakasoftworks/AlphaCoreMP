package cc.starsector.client.net;

import cc.starsector.client.voice.VoiceSheet;
import cc.starsector.server.runtime.VoiceFrameCodec;

import java.io.IOException;
import java.io.InputStream;

/**
 * The client mirror of the server's
 * {@code VCFG} DOWN CTRL frame ({@code VoiceConfigFrameCodec}): the 22 B voice sheet every
 * client encodes/plays against, sent once at admit (last in the admit spool) and re-sent on every
 * operator {@code /voice} retune. WHY a hand-mirror rather than folding the server codec: its
 * decode returns {@code VoiceConfig}, whose {@code resolve()} pulls {@code ServerConfig}
 * (server-only) — so the client decodes into its own {@link VoiceSheet} and re-states every
 * refusal the server codec + the {@code VoiceConfig} canonical constructor carry, so a sheet the
 * server would refuse to boot with is refused here too (fail-closed).
 *
 * <p><b>Layout (22 B, big-endian; schema 2, schema 1 = the 17 B shape without
 * the jitter trio):</b> {@code magic u32 'VCFG'} · {@code schema u16} ·
 * {@code kbps u16} · {@code frameMs u8} · {@code complexity u8} · {@code flags u8} (bit0 fec,
 * bit1 echo, bit2 lowDelay; bits 3-7 refuse) · {@code maxTalkers u8} · {@code proxFullSu u16} ·
 * {@code proxCullSu u16} · {@code relayTickMs u8} (the EFFECTIVE tick, ≥ 1 on the wire) ·
 * {@code jitterMinFrames u8} · {@code jitterMaxMs u16} · {@code jitterShrinkMs u16}.
 *
 * <p>{@link #read} throws {@link IllegalArgumentException} on: length ≠ 22, bad magic, schema
 * mismatch, undefined flag bits, relayTickMs &lt; 1 (codec refusals); bitrate outside
 * 6..510 kbps or frameMs ∉ {10,20,40,60} or the derived frame cap not fitting one datagram
 * (via the folded {@code VoiceFrameCodec.fitsOneDatagram}), complexity outside 0..10,
 * maxTalkers outside 1..8, proxFullSu/proxCullSu outside 1..65535, proxFullSu ≥ proxCullSu,
 * relayTickMs &gt; 60, jitterMinFrames outside 1..4, jitterMaxMs outside 20..160 or below
 * jitterMinFrames × frameMs, jitterShrinkMs outside 200..20000 ({@code VoiceConfig} constructor
 * refusals). A refused VCFG is a SERVER bug, so the session's route counts + drops it rather
 * than dying (see the PRDS {@code VOICE_CONFIG} case).
 */
public final class VoiceConfigReader {

    /** VCFG magic = ASCII "VCFG". Mirrors {@code VoiceConfigFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x56434647;
    /** Registered schema version. Mirrors {@code VoiceConfigFrameCodec.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 2;
    /** magic(4) + schema(2) + kbps(2) + frameMs(1) + complexity(1) + flags(1) + maxTalkers(1)
     * + proxFullSu(2) + proxCullSu(2) + relayTickMs(1) + jitterMinFrames(1) + jitterMaxMs(2)
     * + jitterShrinkMs(2). */
    public static final int FRAME_BYTES = 22;

    public static final int FLAG_FEC = 0x01;
    public static final int FLAG_ECHO = 0x02;
    public static final int FLAG_LOW_DELAY = 0x04;
    public static final int FLAGS_DEFINED = FLAG_FEC | FLAG_ECHO | FLAG_LOW_DELAY;

    /** Domain bounds restated from {@code VoiceConfig} (server) — the constructor's laws. */
    public static final int MAX_COMPLEXITY = 10;
    public static final int MAX_PROX_SU = 0xFFFF;
    public static final int MAX_RELAY_TICK_MS = 60;
    public static final int MIN_JITTER_MIN_FRAMES = 1;
    public static final int MAX_JITTER_MIN_FRAMES = 4;
    public static final int MIN_JITTER_MAX_MS = 20;
    public static final int MAX_JITTER_MAX_MS = 160;
    public static final int MIN_JITTER_SHRINK_MS = 200;
    public static final int MAX_JITTER_SHRINK_MS = 20000;

    private VoiceConfigReader() {
    }

    /**
     * Reconstruct the whole 22 B frame whose 4-byte {@link #MAGIC} the demux entry point has
     * already consumed — the {@code ChatReader.readAfterMagic} idiom. Returns the RAW bytes (not
     * yet validated): the route decodes them under its own count-and-drop law, because a
     * mid-frame EOF here is a stream break (an {@link IOException}, like every sibling) while an
     * out-of-domain field is a server-side sheet bug the session must survive.
     */
    public static byte[] readFrameAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return frame;
    }

    /**
     * Decode + validate one whole VCFG payload (magic included). Throws
     * {@link IllegalArgumentException} on every refusal listed in the class javadoc.
     */
    public static VoiceSheet read(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("VCFG payload null");
        }
        if (payload.length != FRAME_BYTES) {
            throw new IllegalArgumentException("VCFG must be exactly " + FRAME_BYTES + " B: "
                    + payload.length);
        }
        int off = 0;
        int magic = WireBytes.getInt(payload, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IllegalArgumentException("VCFG bad magic 0x" + Integer.toHexString(magic));
        }
        int schema = u16(payload, off);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("VCFG unsupported schema " + schema);
        }
        int kbps = u16(payload, off);
        off += 2;
        int frameMs = payload[off++] & 0xFF;
        int complexity = payload[off++] & 0xFF;
        int flags = payload[off++] & 0xFF;
        if ((flags & ~FLAGS_DEFINED) != 0) {
            throw new IllegalArgumentException("VCFG flags out of domain: 0x"
                    + Integer.toHexString(flags));
        }
        int maxTalkers = payload[off++] & 0xFF;
        int proxFull = u16(payload, off);
        off += 2;
        int proxCull = u16(payload, off);
        off += 2;
        int relayTickMs = payload[off++] & 0xFF;
        if (relayTickMs < 1) {
            throw new IllegalArgumentException("VCFG relayTickMs must be >= 1 on the wire");
        }
        int jitterMinFrames = payload[off++] & 0xFF;
        int jitterMaxMs = u16(payload, off);
        off += 2;
        int jitterShrinkMs = u16(payload, off);
        off += 2;
        // ---- VoiceConfig constructor laws (same order, same bounds) ----
        // Bitrate domain (6..510) + frameMs membership ({10,20,40,60}) throw inside
        // configMaxOpusBytes; the fit law refuses a cap over one datagram.
        if (!VoiceFrameCodec.fitsOneDatagram(kbps, frameMs)) {
            throw new IllegalArgumentException("VCFG bitrateKbps=" + kbps + " at frameMs="
                    + frameMs + " derives a frame cap of "
                    + VoiceFrameCodec.configMaxOpusBytes(kbps, frameMs) + " B > "
                    + VoiceFrameCodec.MAX_OPUS_BYTES);
        }
        if (complexity > MAX_COMPLEXITY) {
            throw new IllegalArgumentException("VCFG complexity must be 0.." + MAX_COMPLEXITY
                    + ": " + complexity);
        }
        if (maxTalkers < 1 || maxTalkers > VoiceFrameCodec.MAX_TALKERS_WIRE) {
            throw new IllegalArgumentException("VCFG maxTalkers must be 1.."
                    + VoiceFrameCodec.MAX_TALKERS_WIRE + ": " + maxTalkers);
        }
        if (proxFull < 1 || proxFull > MAX_PROX_SU) {
            throw new IllegalArgumentException("VCFG proxFullSu must be 1.." + MAX_PROX_SU
                    + ": " + proxFull);
        }
        if (proxCull < 1 || proxCull > MAX_PROX_SU) {
            throw new IllegalArgumentException("VCFG proxCullSu must be 1.." + MAX_PROX_SU
                    + ": " + proxCull);
        }
        if (proxFull >= proxCull) {
            throw new IllegalArgumentException("VCFG proxFullSu (" + proxFull
                    + ") must be < proxCullSu (" + proxCull + ")");
        }
        if (relayTickMs > MAX_RELAY_TICK_MS) {
            throw new IllegalArgumentException("VCFG relayTickMs must be 1.." + MAX_RELAY_TICK_MS
                    + ": " + relayTickMs);
        }
        if (jitterMinFrames < MIN_JITTER_MIN_FRAMES || jitterMinFrames > MAX_JITTER_MIN_FRAMES) {
            throw new IllegalArgumentException("VCFG jitterMinFrames must be "
                    + MIN_JITTER_MIN_FRAMES + ".." + MAX_JITTER_MIN_FRAMES + ": " + jitterMinFrames);
        }
        if (jitterMaxMs < MIN_JITTER_MAX_MS || jitterMaxMs > MAX_JITTER_MAX_MS) {
            throw new IllegalArgumentException("VCFG jitterMaxMs must be " + MIN_JITTER_MAX_MS
                    + ".." + MAX_JITTER_MAX_MS + ": " + jitterMaxMs);
        }
        if (jitterMaxMs < jitterMinFrames * frameMs) {
            throw new IllegalArgumentException("VCFG jitterMaxMs (" + jitterMaxMs
                    + ") must be >= jitterMinFrames x frameMs (" + (jitterMinFrames * frameMs) + ")");
        }
        if (jitterShrinkMs < MIN_JITTER_SHRINK_MS || jitterShrinkMs > MAX_JITTER_SHRINK_MS) {
            throw new IllegalArgumentException("VCFG jitterShrinkMs must be "
                    + MIN_JITTER_SHRINK_MS + ".." + MAX_JITTER_SHRINK_MS + ": " + jitterShrinkMs);
        }
        return new VoiceSheet(kbps, frameMs, complexity, (flags & FLAG_FEC) != 0,
                (flags & FLAG_ECHO) != 0, (flags & FLAG_LOW_DELAY) != 0, maxTalkers, proxFull,
                proxCull, relayTickMs, jitterMinFrames, jitterMaxMs, jitterShrinkMs);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
