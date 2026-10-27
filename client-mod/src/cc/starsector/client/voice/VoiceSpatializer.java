package cc.starsector.client.voice;

/**
 * Pure gain/pan law for the voice mixer.
 *
 * <p>WHY client-side gain from own-BODY to talker anchor: the server culls proximity voice on
 * body-to-body distance, so the fade must reach exactly 0 at {@code proxCullSu} — matching the
 * cull means a talker leaving range fades out instead of popping. Pan is taken from the CAMERA-
 * relative x-offset (what the player sees), normalised by {@code proxFullSu} so a talker one
 * "full-gain radius" to the right is hard right. Constant-power pan (cos/sin quarter-circle) keeps
 * perceived loudness flat across the stereo field. Global talkers: unity gain, centred.
 *
 * <p>Everything here is static and allocation-free; the audio thread calls it per talker per
 * frame.
 */
public final class VoiceSpatializer {
    private VoiceSpatializer() {
    }

    /**
     * Proximity gain: 1 for {@code d <= proxFull}, linear to 0 at {@code proxCull}, 0 beyond.
     * {@code proxCull <= proxFull} degenerates to a step at {@code proxFull} (never a divide by
     * zero).
     */
    public static float proximityGain(float d, float proxFull, float proxCull) {
        if (!(d > proxFull)) {
            return 1f;
        }
        if (proxCull <= proxFull || d >= proxCull) {
            return 0f;
        }
        float g = 1f - (d - proxFull) / (proxCull - proxFull);
        return g < 0f ? 0f : (g > 1f ? 1f : g);
    }

    /** Pan position in -1..1 from the camera-relative x offset, normalised by proxFull (min 1). */
    public static float pan(float anchorX, float camX, float proxFull) {
        float p = (anchorX - camX) / Math.max(proxFull, 1f);
        if (p != p) {
            return 0f;
        }
        return p < -1f ? -1f : (p > 1f ? 1f : p);
    }

    /** Far-ear floor: |pan| never exceeds this, whatever the width knob says —
     *  at 0.7 the far ear keeps cos(0.85·π/2) ≈ 0.23 (−12.6 dB) and the near ear ≈ 0.97, so a
     *  talker dead to one side still reads in both ears. Decoupled from the 0.5 width default
     *  (the design reasoned from the default; a live test ran a persisted 1.0 ⇒ pan%=100). */
    public static final float PAN_LIMIT = 0.7f;

    /** Clamp a post-width pan into ±{@link #PAN_LIMIT} (NaN ⇒ centred). */
    public static float limitPan(float p) {
        if (p != p) {
            return 0f;
        }
        return p < -PAN_LIMIT ? -PAN_LIMIT : (p > PAN_LIMIT ? PAN_LIMIT : p);
    }

    /** Left gain for pan {@code p} in -1..1: cos((p+1)·π/4). */
    public static float panL(float p) {
        return (float) Math.cos((p + 1f) * Math.PI / 4.0);
    }

    /** Right gain for pan {@code p} in -1..1: sin((p+1)·π/4). */
    public static float panR(float p) {
        return (float) Math.sin((p + 1f) * Math.PI / 4.0);
    }
}
