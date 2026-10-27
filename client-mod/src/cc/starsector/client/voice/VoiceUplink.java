package cc.starsector.client.voice;

/**
 * The capture side's one way out (build brief D5). WHY an interface: the encoder
 * thread ({@code cc-voice-capture}) must never see the datagram session's read loop; it hands one
 * finished VCUP payload to whoever owns the socket (the session's thread-safe {@code sendVoice}),
 * and the envelope voice seq is ONE monotonic counter owned by that sender so the server relay's
 * strictly-advancing check never trips.
 */
public interface VoiceUplink {
    /** Send one VCUP payload (already encoded by {@code VoiceFrameCodec.encodeUp}); false = not sent. */
    boolean sendVoice(byte[] vcup);
}
