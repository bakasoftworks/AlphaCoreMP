package cc.starsector.client.voice;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.concentus.OpusPacketInfo;

import cc.starsector.server.runtime.VoiceFrameCodec;

/**
 * The real {@link TalkerStream.FrameDecoder}: vendored Concentus {@code OpusDecoder(48000, 1)}
 * behind the decode guard.
 *
 * <p>WHY the guard runs BEFORE decode: a peer-authored TOC byte with frame-count code 3 packs
 * 120 ms into one packet (a 36x-real-time decode DoS shape); refusing any packet whose
 * {@code OpusPacketInfo.getNumSamples} is not exactly {@code frameMs x 48} keeps decode cost
 * bounded to one frame per frame. {@code decode} is then called with {@code frameSize} exactly
 * (Concentus throws rather than over-writes when the packet holds more). Concentus'
 * {@code OpusException} is CHECKED — the stream catches it per frame; this class never logs the
 * bytes.
 */
public final class OpusFrameDecoder implements TalkerStream.FrameDecoder {
    private final OpusDecoder decoder;
    private final int frameMs;

    public OpusFrameDecoder(int frameMs) throws OpusException {
        this.frameMs = frameMs;
        this.decoder = new OpusDecoder(48000, 1);
    }

    /** True iff the packet's TOC declares exactly {@code frameMs} of 48 kHz audio (D6). */
    public static boolean guardFrame(byte[] opus, int len, int frameMs) {
        if (opus == null || len < 1 || len > opus.length || len > VoiceFrameCodec.MAX_OPUS_BYTES) {
            return false;
        }
        int samples;
        try {
            samples = OpusPacketInfo.getNumSamples(opus, 0, len, 48000);
        } catch (RuntimeException | AssertionError e) {
            // Concentus OpusAssert = AssertionError — a refusal, never a throw.
            return false;
        }
        return samples == frameMs * 48;
    }

    @Override
    public int decode(byte[] opus, int len, short[] pcmOut, int frameSize) throws OpusException {
        if (!guardFrame(opus, len, frameMs)) {
            return -1;
        }
        return decoder.decode(opus, 0, len, pcmOut, 0, frameSize, false);
    }

    /** The packet's LBRR copy of the PREVIOUS frame ({@code decodeFEC=true}); the same
     *  packet is decoded normally afterwards — the libopus-documented sequence. It is
     *  gated on {@link #hasLbrr} — {@code decode_fec=true} on a packet WITHOUT an LBRR copy is
     *  plain PLC inside Concentus, so
     *  without the gate the stream's {@code fecRec} counted concealment as recovery. */
    @Override
    public int decodeFec(byte[] opus, int len, short[] pcmOut, int frameSize) throws OpusException {
        if (!guardFrame(opus, len, frameMs) || !hasLbrr(opus, len)) {
            return -1;
        }
        return decoder.decode(opus, 0, len, pcmOut, 0, frameSize, true);
    }

    /**
     * true iff a SILK/hybrid, frame-count-code-0 packet (the one shape our encoder emits —
     * one Opus frame per packet) carries the SILK header's LBRR flag. The header's VAD flags and
     * LBRR flag are range-coded at p = 1/2 (RFC 6716 section 4.2.3, {@code ec_dec_bit_logp(1)}), and at
     * p = 1/2 the coder's decisions ARE the raw MSB-first bits of payload byte 0: N VAD bits
     * (N = 20 ms SILK sub-frames: 1 for 10/20 ms, 2 for 40 ms, 3 for 60 ms) then the LBRR bit —
     * bit 6/5/4. CELT-only (config ≥ 16), multi-frame codes, or a header-less packet ⇒ false
     * (no recovery attempted — the PLC path keeps the count honest). Pinned by the selftest
     * against real Concentus encodes (FEC off ⇒ never set; FEC + loss ⇒ set on voiced frames).
     */
    public static boolean hasLbrr(byte[] opus, int len) {
        if (opus == null || len < 2 || len > opus.length) {
            return false;
        }
        int toc = opus[0] & 0xFF;
        if ((toc & 3) != 0) {
            return false;
        }
        int config = toc >> 3;
        if (config >= 16) {
            return false;
        }
        int subFrames;
        if (config >= 12) {
            subFrames = 1; // hybrid: 10 / 20 ms only
        } else {
            int d = config & 3; // 10, 20, 40, 60 ms
            subFrames = d <= 1 ? 1 : (d == 2 ? 2 : 3);
        }
        int lbrrBit = 7 - subFrames;
        return (((opus[1] & 0xFF) >> lbrrBit) & 1) != 0;
    }

    @Override
    public int decodePlc(short[] pcmOut, int frameSize) throws OpusException {
        return decoder.decode(null, 0, 0, pcmOut, 0, frameSize, false);
    }

    @Override
    public void reset() {
        decoder.resetState();
    }
}
