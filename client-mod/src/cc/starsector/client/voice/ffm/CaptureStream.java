package cc.starsector.client.voice.ffm;

/**
 * The reader-side seam of {@link WasapiCaptureStream} (
 * the capture mirror of {@link RenderStream}): exactly what {@link FfmVoiceCapture} touches on an
 * OPEN capture stream, so the reader's laws (drain every queued packet per wake, SILENT /
 * DISCONTINUITY counts, the self-timestamp reservoir, 3 × {@code WAIT_TIMEOUT} = event starved,
 * stream lost → the JDK line into the SAME ring, or DEAD when the ring rate has no fallback) run
 * against a scripted fake on the {@code selftest25} leg with NO WASAPI. Production has ONE
 * implementation ({@link WasapiCaptureStream}); the fakes never ride the jar.
 *
 * <p>CONTRACT = the real stream's: every method runs on the reader thread that opened the stream;
 * a lost device surfaces as {@link Com.HrException}; {@link #readPacket} folds the packet to mono
 * int16 (a SILENT packet writes zeros) and refreshes the three {@code last*} values.
 */
interface CaptureStream {

    /** Engine mix rate in Hz — the ring's rate (any rate; the JDK fallback needs 48 k). */
    int mixRate();

    int mixChannels();

    /** float32 mix format (else int16). */
    boolean isFloat();

    /** Engine-returned shared-buffer size in frames; sizes the reader's scratch (one packet ≤ this). */
    int bufferFrames();

    boolean mmcssOk();

    /** Default engine period in frames (the wake cadence). */
    int periodFrames();

    void start() throws Throwable;

    /** Blocks until the engine signals a period (or {@link WasapiRenderStream#WAIT_MS}); the WAIT_* code. */
    int waitWake() throws Throwable;

    /** Frames in the next queued packet; 0 = drained for this wake. A lost device throws. */
    int nextPacketFrames() throws Throwable;

    /** Fold the next packet to mono int16 into {@code dst}; frames written (0 = nothing after all). */
    int readPacket(short[] dst) throws Throwable;

    /** Last packet's {@code AUDCLNT_BUFFERFLAGS_*} ({@link WasapiCaptureStream#FLAG_SILENT} etc.). */
    int lastFlags();

    /** Last packet's engine QPC position in 100 ns units (0 = none). */
    long lastQpc100ns();

    /** {@code System.nanoTime()} taken right after the last packet's {@code GetBuffer} returned. */
    long lastStampNs();

    /** Stop + release everything; idempotent, never throws. */
    void close();
}
