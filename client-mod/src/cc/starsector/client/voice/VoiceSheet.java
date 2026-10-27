package cc.starsector.client.voice;

import cc.starsector.server.runtime.VoiceFrameCodec;

/**
 * The client's decoded copy of the server's VCFG voice sheet.
 * WHY a client record instead of folding the server's {@code VoiceConfig}: that record's
 * {@code resolve()} pulls {@code ServerConfig}, which is server-only. Every field is exactly what
 * rode the 22 B VCFG frame (schema 2; mirrored by {@code cc.starsector.client.net.VoiceConfigReader});
 * the sheet is immutable so the capture/playback threads can read a published reference without
 * locks. Domain is enforced by the reader (fail-closed), never here.
 *
 * <p>The jitter trio (W12 (v1)): {@code jitterMinFrames} / {@code jitterMaxMs} /
 * {@code jitterShrinkMs} = the {@link TalkerStream} floor / ceiling / relax window the server
 * dictates (ops-GUI Voice tab; a retune re-targets live streams without a line reopen).
 */
public final class VoiceSheet {
    public final int kbps;
    public final int frameMs;
    public final int complexity;
    public final boolean fec;
    public final boolean echo;
    public final boolean lowDelay;
    public final int maxTalkers;
    public final float proxFullSu;
    public final float proxCullSu;
    public final int relayTickMs;
    public final int jitterMinFrames;
    public final int jitterMaxMs;
    public final int jitterShrinkMs;

    public VoiceSheet(int kbps, int frameMs, int complexity, boolean fec, boolean echo,
            boolean lowDelay, int maxTalkers, float proxFullSu, float proxCullSu, int relayTickMs,
            int jitterMinFrames, int jitterMaxMs, int jitterShrinkMs) {
        this.kbps = kbps;
        this.frameMs = frameMs;
        this.complexity = complexity;
        this.fec = fec;
        this.echo = echo;
        this.lowDelay = lowDelay;
        this.maxTalkers = maxTalkers;
        this.proxFullSu = proxFullSu;
        this.proxCullSu = proxCullSu;
        this.relayTickMs = relayTickMs;
        this.jitterMinFrames = jitterMinFrames;
        this.jitterMaxMs = jitterMaxMs;
        this.jitterShrinkMs = jitterShrinkMs;
    }

    /** The pre-(v1) ten-field shape: jitter trio at the {@link TalkerStream} defaults (selftests). */
    public VoiceSheet(int kbps, int frameMs, int complexity, boolean fec, boolean echo,
            boolean lowDelay, int maxTalkers, float proxFullSu, float proxCullSu, int relayTickMs) {
        this(kbps, frameMs, complexity, fec, echo, lowDelay, maxTalkers, proxFullSu, proxCullSu,
                relayTickMs, TalkerStream.TARGET_MIN, TalkerStream.TARGET_MAX_MS,
                TalkerStream.SHRINK_AFTER_CLEAN_MS);
    }

    /** PCM samples per mono frame at 48 kHz = the exact Opus frameSize the codec must use (D6). */
    public int samplesPerFrame() {
        return frameMs * 48;
    }

    /** Largest Opus payload the server relay admits for this sheet (mirrors {@code VoiceRelay}). */
    public int maxOpusBytes() {
        return VoiceFrameCodec.configMaxOpusBytes(kbps, frameMs);
    }

    /** True when {@code other} dictates the same jitter trio (a retune that keeps the trio
     *  leaves live streams alone). */
    public boolean sameJitter(VoiceSheet other) {
        return other != null && other.jitterMinFrames == jitterMinFrames
                && other.jitterMaxMs == jitterMaxMs && other.jitterShrinkMs == jitterShrinkMs;
    }

    @Override
    public String toString() {
        return "VoiceSheet{kbps=" + kbps + ",frameMs=" + frameMs + ",cx=" + complexity + ",fec=" + fec
                + ",echo=" + echo + ",lowDelay=" + lowDelay + ",maxTalkers=" + maxTalkers
                + ",proxFull=" + proxFullSu + ",proxCull=" + proxCullSu + ",tick=" + relayTickMs
                + ",jitter=" + jitterMinFrames + "f/" + jitterMaxMs + "ms/" + jitterShrinkMs + "ms}";
    }
}
