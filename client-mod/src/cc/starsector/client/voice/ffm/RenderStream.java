package cc.starsector.client.voice.ffm;

/**
 * The feeder-side seam of {@link WasapiRenderStream}:
 * exactly what {@link FfmVoiceBackend} touches on an OPEN render stream, so the feeder's laws
 * (ring lead paid after the copy, newest-wins, the underrun count, 3 × {@code WAIT_TIMEOUT} =
 * event starved, stream lost → the JDK line) run against a scripted fake on the
 * {@code selftest25} leg with NO WASAPI. Production has ONE implementation
 * ({@link WasapiRenderStream}); the fakes never ride the jar.
 *
 * <p>CONTRACT = the real stream's: every method runs on the feeder thread that opened the stream;
 * a lost device surfaces as {@link Com.HrException} from the {@code Throwable} methods; the
 * accessors are engine-returned values fixed at open.
 */
interface RenderStream {

    /** Engine mix rate in Hz (equals the mixer's ask, or the stream did not open). */
    int mixRate();

    int mixChannels();

    /** float32 mix format (else int16). */
    boolean isFloat();

    /** Engine-returned shared-buffer size in frames; sizes the feeder's scratch + the free clamp. */
    int bufferFrames();

    /** {@code GetStreamLatency} in 100 ns units, −1 when the engine refused the call (log only). */
    long streamLatency100ns();

    boolean mmcssOk();

    /** Default engine period in frames (the wake cadence). */
    int periodFrames();

    void start() throws Throwable;

    /** Blocks until the engine signals a period (or {@link WasapiRenderStream#WAIT_MS}); the WAIT_* code. */
    int waitWake() throws Throwable;

    /** Frames the engine still holds (clamped to {@link #bufferFrames}); a lost device throws. */
    int padding() throws Throwable;

    /**
     * Hand {@code frames} interleaved int16 LE frames of {@code srcChannels} (1 or 2) from
     * {@code pcm} to the engine; the caller already clamped {@code frames} to
     * {@code bufferFrames − padding}.
     */
    void render(byte[] pcm, int frames, int srcChannels) throws Throwable;

    /** Stop + release everything; idempotent, never throws. */
    void close();
}
