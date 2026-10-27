package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * One open WASAPI <b>shared-mode, event-driven</b> render stream on the default endpoint
 * (the render half of the voice prototype, minus its
 * measurement harness).
 *
 * <p>WHY plain {@code IAudioClient} and not {@code IAudioClient3}: the voice-latency design
 * makes small periods OPTIONAL — the prize is leaving
 * the JDK's DirectSound line (53 ms p50 at the shipped depth), and at the engine's DEFAULT
 * period {@code IAudioClient3::InitializeSharedAudioStream} buys nothing over
 * {@code IAudioClient::Initialize(SHARED, EVENTCALLBACK, 0, 0, mix, NULL)} — the OBS path, Win7+.
 * Requesting the default period also never perturbs the endpoint-global engine period the game's
 * OpenAL rides.
 *
 * <p>THREADING: every method runs on the feeder thread that called {@link #open} — that thread
 * owns COM ({@code CoInitializeEx} before, {@code CoUninitialize} after) and the confined
 * {@link Arena}; MMCSS "Pro Audio" is a per-thread characteristic set here and reverted in
 * {@link #close}.
 *
 * <p>NATIVE-SEAM LAW: every value handed to the native side is a compile-time constant or is
 * clamped from what the engine itself returned ({@code GetBufferSize}/{@code GetCurrentPadding});
 * only int16 PCM sample VALUES (converted to the engine's float32 / int16 mix format) cross.
 * Vtable slots follow the Windows SDK headers (mmdeviceapi.h, audioclient.h).
 */
final class WasapiRenderStream implements RenderStream {

    static final String CLSID_MMDeviceEnumerator = "BCDE0395-E52F-467C-8E3D-C4579291692E";
    static final String IID_IMMDeviceEnumerator = "A95664D2-9614-4F35-A746-DE8DB63617E6";
    static final String IID_IAudioClient = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2";
    static final String IID_IAudioRenderClient = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2";
    static final String SUBTYPE_IEEE_FLOAT = "00000003-0000-0010-8000-00AA00389B71";
    static final String SUBTYPE_PCM = "00000001-0000-0010-8000-00AA00389B71";
    static final int COINIT_APARTMENTTHREADED = 0x2;
    static final int CLSCTX_ALL = 0x17;
    static final int E_RENDER = 0;
    static final int E_CONSOLE = 0;
    static final int AUDCLNT_SHAREMODE_SHARED = 0;
    static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    static final int WAVE_FORMAT_PCM = 1;
    static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;
    static final int WAIT_OBJECT_0 = 0;
    static final int WAIT_TIMEOUT = 0x102;
    /** Per-period wait cap; a hit means the engine stalled (counted, never fatal). */
    static final int WAIT_MS = 2000;

    // ---- vtable slots (mmdeviceapi.h / audioclient.h) ---------------------------------------
    static final int ENUM_GetDefaultAudioEndpoint = 4;
    static final int DEV_Activate = 3;
    static final int AC_Initialize = 3;
    static final int AC_GetBufferSize = 4;
    static final int AC_GetStreamLatency = 5;
    static final int AC_GetCurrentPadding = 6;
    static final int AC_GetMixFormat = 8;
    static final int AC_GetDevicePeriod = 9;
    static final int AC_Start = 10;
    static final int AC_Stop = 11;
    static final int AC_SetEventHandle = 13;
    static final int AC_GetService = 14;
    static final int RC_GetBuffer = 3;
    static final int RC_ReleaseBuffer = 4;

    // ---- downcall signatures, built once ----------------------------------------------------
    static final MethodHandle M_this_int_int_pp = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle M_this_p_int_p_pp = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle M_this_p = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle M_this = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle M_this_p_p = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle M_init = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    static final MethodHandle M_this_int_p = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    static final MethodHandle M_this_int_int = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    private final Com com;
    private final Arena arena;
    private MemorySegment enumerator;
    private MemorySegment device;
    private MemorySegment client;
    private MemorySegment render;
    private MemorySegment fmtPtr;
    private MemorySegment event = MemorySegment.NULL;
    private MemorySegment mmcss = MemorySegment.NULL;
    private MemorySegment fpPad;
    private MemorySegment fpGet;
    private MemorySegment fpRel;
    private MemorySegment pPad;
    private MemorySegment pData;
    private boolean started;

    /** Engine mix format, read from the endpoint (the ONLY format ever handed back to it). */
    private int mixRate;
    private int mixChannels;
    private int blockAlign;
    private boolean isFloat;
    /** Engine-returned sizes: the shared buffer in frames, the default period in 100 ns units. */
    private int bufferFrames;
    private long defaultPeriod100ns;
    private long streamLatency100ns;
    private boolean mmcssOk;

    private WasapiRenderStream(Com com, Arena arena) {
        this.com = com;
        this.arena = arena;
    }

    // ---- RenderStream accessors (the feeder reads the seam, never the fields) ------
    @Override
    public int mixRate() {
        return mixRate;
    }

    @Override
    public int mixChannels() {
        return mixChannels;
    }

    @Override
    public boolean isFloat() {
        return isFloat;
    }

    @Override
    public int bufferFrames() {
        return bufferFrames;
    }

    @Override
    public long streamLatency100ns() {
        return streamLatency100ns;
    }

    @Override
    public boolean mmcssOk() {
        return mmcssOk;
    }

    /**
     * Activate the default render endpoint and initialise a shared event-driven stream at the
     * engine's default period. Throws {@link Com.HrException} (a negative HRESULT, numbers only)
     * or {@link IllegalStateException} (mix format the sink cannot feed: rate != {@code wantRate},
     * or neither float32 nor int16) — the caller falls back to the JDK line on either.
     */
    static WasapiRenderStream open(Com com, Arena arena, int wantRate) throws Throwable {
        WasapiRenderStream s = new WasapiRenderStream(com, arena);
        try {
            s.activate(wantRate);
            return s;
        } catch (Throwable t) {
            s.close();
            throw t;
        }
    }

    private void activate(int wantRate) throws Throwable {
        MemorySegment slot = com.ptrSlot();
        Com.check("CoCreateInstance(MMDeviceEnumerator)", (int) com.coCreateInstance.invokeExact(
                com.guid(CLSID_MMDeviceEnumerator), MemorySegment.NULL, CLSCTX_ALL, com.guid(IID_IMMDeviceEnumerator), slot));
        enumerator = Com.deref(slot);
        slot = com.ptrSlot();
        Com.check("GetDefaultAudioEndpoint", (int) M_this_int_int_pp.invokeExact(
                Com.fp(enumerator, ENUM_GetDefaultAudioEndpoint), enumerator, E_RENDER, E_CONSOLE, slot));
        device = Com.deref(slot);
        slot = com.ptrSlot();
        Com.check("Activate(IAudioClient)", (int) M_this_p_int_p_pp.invokeExact(
                Com.fp(device, DEV_Activate), device, com.guid(IID_IAudioClient), CLSCTX_ALL, MemorySegment.NULL, slot));
        client = Com.deref(slot);

        slot = com.ptrSlot();
        Com.check("GetMixFormat", (int) M_this_p.invokeExact(Com.fp(client, AC_GetMixFormat), client, slot));
        fmtPtr = Com.deref(slot);
        readFormat(fmtPtr);
        if (mixRate != wantRate) {
            throw new IllegalStateException("mix rate " + mixRate + " != " + wantRate);
        }
        if (mixChannels < 1 || mixChannels > 32) {
            throw new IllegalStateException("mix channels " + mixChannels);
        }

        MemorySegment pDef = arena.allocate(JAVA_LONG);
        MemorySegment pMin = arena.allocate(JAVA_LONG);
        Com.check("GetDevicePeriod", (int) M_this_p_p.invokeExact(Com.fp(client, AC_GetDevicePeriod), client, pDef, pMin));
        defaultPeriod100ns = pDef.get(JAVA_LONG, 0);

        // hnsBufferDuration 0 + hnsPeriodicity 0 = the engine's own default buffer at its default
        // period (the OBS shape); EVENTCALLBACK = one wake per period on `event`.
        Com.check("Initialize(SHARED, EVENTCALLBACK)", (int) M_init.invokeExact(Com.fp(client, AC_Initialize), client,
                AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 0L, 0L, fmtPtr, MemorySegment.NULL));

        MemorySegment pBuf = arena.allocate(JAVA_INT);
        Com.check("GetBufferSize", (int) M_this_p.invokeExact(Com.fp(client, AC_GetBufferSize), client, pBuf));
        bufferFrames = pBuf.get(JAVA_INT, 0);
        if (bufferFrames <= 0 || bufferFrames > 1 << 20) {
            throw new IllegalStateException("buffer frames " + bufferFrames);
        }
        MemorySegment pLat = arena.allocate(JAVA_LONG);
        int hrLat = (int) M_this_p.invokeExact(Com.fp(client, AC_GetStreamLatency), client, pLat);
        streamLatency100ns = hrLat >= 0 ? pLat.get(JAVA_LONG, 0) : -1L;

        slot = com.ptrSlot();
        Com.check("GetService(IAudioRenderClient)", (int) M_this_p_p.invokeExact(
                Com.fp(client, AC_GetService), client, com.guid(IID_IAudioRenderClient), slot));
        render = Com.deref(slot);

        event = (MemorySegment) com.createEventW.invokeExact(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
        if (event.address() == 0) {
            throw new IllegalStateException("CreateEventW NULL");
        }
        Com.check("SetEventHandle", (int) M_this_p.invokeExact(Com.fp(client, AC_SetEventHandle), client, event));

        if (com.avSetMmThreadCharacteristicsW != null) {
            MemorySegment idx = arena.allocate(JAVA_INT);
            mmcss = (MemorySegment) com.avSetMmThreadCharacteristicsW.invokeExact(com.wstr("Pro Audio"), idx);
            mmcssOk = mmcss.address() != 0;
        }

        fpPad = Com.fp(client, AC_GetCurrentPadding);
        fpGet = Com.fp(render, RC_GetBuffer);
        fpRel = Com.fp(render, RC_ReleaseBuffer);
        pPad = arena.allocate(JAVA_INT);
        pData = com.ptrSlot();
    }

    /** WAVEFORMATEX (18 B) [+ EXTENSIBLE: Samples u16, dwChannelMask u32, SubFormat GUID] from CoTaskMem. */
    private void readFormat(MemorySegment p) {
        MemorySegment h = p.reinterpret(18);
        int tag = h.get(JAVA_SHORT, 0) & 0xFFFF;
        mixChannels = h.get(JAVA_SHORT, 2) & 0xFFFF;
        mixRate = h.get(JAVA_INT, 4);
        blockAlign = h.get(JAVA_SHORT, 12) & 0xFFFF;
        int bits = h.get(JAVA_SHORT, 14) & 0xFFFF;
        int cb = h.get(JAVA_SHORT, 16) & 0xFFFF;
        boolean pcm16 = false;
        if (tag == WAVE_FORMAT_EXTENSIBLE && cb >= 22) {
            MemorySegment ext = p.reinterpret(18 + cb);
            MemorySegment guid = ext.asSlice(24, 16);
            isFloat = Com.guidEquals(guid, com.guid(SUBTYPE_IEEE_FLOAT));
            pcm16 = Com.guidEquals(guid, com.guid(SUBTYPE_PCM));
        } else if (tag == WAVE_FORMAT_IEEE_FLOAT) {
            isFloat = true;
        } else if (tag == WAVE_FORMAT_PCM) {
            pcm16 = true;
        }
        if (isFloat && bits != 32) {
            throw new IllegalStateException("float bits " + bits);
        }
        if (!isFloat && !(pcm16 && bits == 16)) {
            throw new IllegalStateException("mix format tag " + tag + " bits " + bits);
        }
        if (blockAlign != mixChannels * (isFloat ? 4 : 2)) {
            throw new IllegalStateException("blockAlign " + blockAlign);
        }
    }

    /** Default engine period in frames (the wake cadence). */
    @Override
    public int periodFrames() {
        return (int) Math.max(1L, Math.min(bufferFrames, defaultPeriod100ns * mixRate / 10_000_000L));
    }

    @Override
    public void start() throws Throwable {
        Com.check("Start", (int) M_this.invokeExact(Com.fp(client, AC_Start), client));
        started = true;
    }

    /** Blocks until the engine signals a period (or {@link #WAIT_MS}); returns the WAIT_* code. */
    @Override
    public int waitWake() throws Throwable {
        return (int) com.waitForSingleObject.invokeExact(event, WAIT_MS);
    }

    /** Frames the engine still holds; a negative HRESULT (device lost) throws. */
    @Override
    public int padding() throws Throwable {
        Com.check("GetCurrentPadding", (int) M_this_p.invokeExact(fpPad, client, pPad));
        int pad = pPad.get(JAVA_INT, 0);
        return (pad < 0 || pad > bufferFrames) ? bufferFrames : pad;
    }

    /**
     * GetBuffer(frames) → convert {@code frames} interleaved int16 LE frames of {@code srcChannels}
     * (1 or 2) from {@code pcm} into the engine's mix format → ReleaseBuffer. Stereo lands on
     * mix channels 0/1 (the rest silent); a mono endpoint gets (L+R)/2. {@code frames} is already
     * clamped to {@code bufferFrames − padding} by the caller; the copy length is bounded by it
     * times the engine's own block align.
     */
    @Override
    public void render(byte[] pcm, int frames, int srcChannels) throws Throwable {
        if (frames <= 0) {
            return;
        }
        Com.check("GetBuffer", (int) M_this_int_p.invokeExact(fpGet, render, frames, pData));
        MemorySegment buf = Com.deref(pData).reinterpret((long) frames * blockAlign);
        int srcBpf = srcChannels * 2;
        int mc = mixChannels;
        for (int i = 0; i < frames; i++) {
            int o = i * srcBpf;
            int l = (short) ((pcm[o] & 0xFF) | (pcm[o + 1] << 8));
            int r = srcChannels == 2 ? (short) ((pcm[o + 2] & 0xFF) | (pcm[o + 3] << 8)) : l;
            long base = (long) i * blockAlign;
            if (isFloat) {
                if (mc == 1) {
                    buf.set(JAVA_FLOAT, base, (l + r) * (0.5f / 32768f));
                } else {
                    buf.set(JAVA_FLOAT, base, l * (1f / 32768f));
                    buf.set(JAVA_FLOAT, base + 4L, r * (1f / 32768f));
                    for (int c = 2; c < mc; c++) {
                        buf.set(JAVA_FLOAT, base + 4L * c, 0f);
                    }
                }
            } else {
                if (mc == 1) {
                    buf.set(JAVA_SHORT, base, (short) ((l + r) >> 1));
                } else {
                    buf.set(JAVA_SHORT, base, (short) l);
                    buf.set(JAVA_SHORT, base + 2L, (short) r);
                    for (int c = 2; c < mc; c++) {
                        buf.set(JAVA_SHORT, base + 2L * c, (short) 0);
                    }
                }
            }
        }
        Com.check("ReleaseBuffer", (int) M_this_int_int.invokeExact(fpRel, render, frames, 0));
    }

    /** Stop → Release(render, client, device, enumerator) → CloseHandle(event) → CoTaskMemFree(fmt) → MMCSS revert. */
    @Override
    public void close() {
        try {
            if (started && client != null) {
                int ignored = (int) M_this.invokeExact(Com.fp(client, AC_Stop), client);
            }
        } catch (Throwable t) {
            // Best effort at teardown.
        }
        started = false;
        Com.release(render);
        Com.release(client);
        Com.release(device);
        Com.release(enumerator);
        render = null;
        client = null;
        device = null;
        enumerator = null;
        try {
            if (event.address() != 0) {
                int ignored = (int) com.closeHandle.invokeExact(event);
            }
            event = MemorySegment.NULL;
            if (fmtPtr != null) {
                com.coTaskMemFree.invokeExact(fmtPtr);
                fmtPtr = null;
            }
            if (mmcss.address() != 0 && com.avRevertMmThreadCharacteristics != null) {
                int ignored = (int) com.avRevertMmThreadCharacteristics.invokeExact(mmcss);
            }
            mmcss = MemorySegment.NULL;
        } catch (Throwable t) {
            // Best effort at teardown.
        }
    }
}
