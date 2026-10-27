package cc.starsector.client.voice;

/**
 * Send-nanos-by-seq table for the own-voice echo round trip (build brief D8:
 * "stamp voice rtt from send-nanos by seq" — the Rig 2 meter, live only when the sheet's
 * {@code echo} flag is set). The sender notes each frame's seq + nanos; the playback thread looks
 * the seq up when the own seat's frame comes back and publishes {@code VoiceMeters.echoRttNanos}.
 *
 * <p>The seq that comes back in VCDN is the ENVELOPE voice seq (u16) the datagram session stamps
 * on send, so whoever owns that counter should call {@link #noteSent} — the capture thread's
 * local counter only aligns while the session's counter started at 0 and advanced solely on
 * sent frames. 256-slot ring keyed by {@code seq & 0xFF}; a slot whose stored seq differs is a
 * miss (-1). Tiny synchronized methods: ~50 calls/s per side.
 */
public final class EchoClock {
    private static final int SLOTS = 256;
    private final long[] nanos = new long[SLOTS];
    private final int[] seqs = new int[SLOTS];

    public EchoClock() {
        java.util.Arrays.fill(seqs, -1);
    }

    public synchronized void noteSent(int seq, long sendNanos) {
        int s = seq & 0xFFFF;
        seqs[s & 0xFF] = s;
        nanos[s & 0xFF] = sendNanos;
    }

    /** Round trip for {@code seq} or -1 when the seq was never noted (or was overwritten). */
    public synchronized long rttNanos(int seq, long nowNanos) {
        int s = seq & 0xFFFF;
        if (seqs[s & 0xFF] != s) {
            return -1L;
        }
        seqs[s & 0xFF] = -1;
        return nowNanos - nanos[s & 0xFF];
    }
}
