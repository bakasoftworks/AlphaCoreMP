package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * One open WASAPI <b>shared-mode, event-driven</b> CAPTURE stream on the default microphone
 * endpoint (the capture mirror of {@link WasapiRenderStream},
 * API shape checked against a loopback rig).
 *
 * <p>WHY: the shipped capture is the JDK {@code DirectAudioDevice} {@code TargetDataLine} — an
 * 80 ms line read-polled at {@code bufferMs/4} = 20 ms, i.e. a poll cliff + line depth
 * on every frame. WASAPI hands each ~10 ms engine packet over on an event with a hardware QPC
 * stamp, so the mic path becomes wake → drain → ring with no poll at all — and self-timestamping:
 * {@code nanoTime() − pu64QPCPosition} IS the acquisition latency, no stimulus needed.
 *
 * <p>FORMAT LAW: NO {@code AUTOCONVERTPCM} (it inserts a matrixer + SRC) — the engine's
 * mix format is read and fed back verbatim; float32 / int16 × N channels are folded to mono int16
 * IN JAVA at the mix RATE (any rate is accepted — the 17-side {@code PcmResampler} runs identity at
 * 48 k and its rate path otherwise). Endpoint role = {@code eCommunications}, the voice-chat mic
 * convention.
 *
 * <p>THREADING: every method runs on the reader thread that called {@link #open} — that thread owns
 * COM and the confined {@link Arena}; MMCSS "Audio" (OBS's class for ≥ 10 ms capture) is set here
 * and reverted in {@link #close}.
 *
 * <p>NATIVE-SEAM LAW: every value handed to the native side is a compile-time constant or is
 * clamped from what the engine itself returned; {@code GetBuffer}'s frame count is bounded by the
 * engine's own {@code bufferFrames} before the one native read, and {@code ReleaseBuffer} echoes the
 * engine's count. Only inert int16 PCM sample values flow OUT.
 *
 * <p>LOOPBACK VARIANT ({@link #openLoopback}; the mouth-to-ear
 * meter's ear): the SAME class opened on the default RENDER endpoint ({@code eRender}/{@code eConsole})
 * with {@code AUDCLNT_STREAMFLAGS_LOOPBACK} instead of {@code EVENTCALLBACK}, so every packet is the
 * engine's own post-mix output with its QPC stamp. WHY polled: event-driven loopback is the classic
 * WASAPI quirk (the event may never fire on a loopback client — the documented workaround is a
 * dummy render event), so a loopback {@link #waitWake} is a 2 ms sleep and the packet stamps make
 * the poll cadence irrelevant to the measurement. No event handle is ever created for it; the mic
 * path ({@link #open}) is byte-for-byte unchanged.
 */
final class WasapiCaptureStream implements CaptureStream {

    static final String IID_IAudioCaptureClient = "C8ADBD64-E71E-48A0-A4DE-185C395CD317";
    static final int E_CAPTURE = 1;
    static final int E_COMMUNICATIONS = 2;
    /** audioclient.h: capture the render endpoint's post-mix stream (shared mode only, no event). */
    static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    /** Loopback poll cadence (ms): the engine period is ~10 ms; the 1 ms Windows timer makes this ~2 ms. */
    static final long LOOPBACK_POLL_MS = 2L;
    // ---- IAudioCaptureClient vtable (audioclient.h; IUnknown = 0..2) --------------------------
    static final int CC_GetBuffer = 3;
    static final int CC_ReleaseBuffer = 4;
    static final int CC_GetNextPacketSize = 5;
    static final int FLAG_DISCONTINUITY = 0x1;
    static final int FLAG_SILENT = 0x2;
    static final int FLAG_TIMESTAMP_ERROR = 0x4;
    /** S_ code (not an error): no packet queued. */
    static final int AUDCLNT_S_BUFFER_EMPTY = 0x08890001;

    /** GetBuffer(this, BYTE**, UINT32* frames, DWORD* flags, UINT64* devPos, UINT64* qpcPos). */
    static final MethodHandle M_cc_get = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** ReleaseBuffer(this, UINT32 frames) — ONE int, unlike render's (frames, flags). */
    static final MethodHandle M_this_int = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    private final Com com;
    private final Arena arena;
    /** {@code true} = the render-endpoint loopback variant (polled, no event); {@code false} = the mic. */
    private final boolean loopback;
    private MemorySegment enumerator;
    private MemorySegment device;
    private MemorySegment client;
    private MemorySegment capture;
    private MemorySegment fmtPtr;
    private MemorySegment event = MemorySegment.NULL;
    private MemorySegment mmcss = MemorySegment.NULL;
    private MemorySegment fpNext;
    private MemorySegment fpGet;
    private MemorySegment fpRel;
    private MemorySegment pNext;
    private MemorySegment pData;
    private MemorySegment pFrames;
    private MemorySegment pFlags;
    private MemorySegment pPos;
    private MemorySegment pQpc;
    private boolean started;

    /** Engine mix format, read from the endpoint (the ONLY format ever handed back to it). */
    private int mixRate;
    private int mixChannels;
    private int blockAlign;
    private boolean isFloat;
    /** Engine-returned sizes: the shared buffer in frames, the default period in 100 ns units. */
    private int bufferFrames;
    private long defaultPeriod100ns;
    private boolean mmcssOk;
    /** Last packet's flags + QPC position (100 ns units of the QPC clock = HotSpot nanoTime / 100). */
    private int lastFlags;
    private long lastQpc100ns;
    /** {@code System.nanoTime()} taken right after the {@code GetBuffer} downcall returned — the stamp point (before the fold). */
    private long lastStampNs;

    private WasapiCaptureStream(Com com, Arena arena, boolean loopback) {
        this.com = com;
        this.arena = arena;
        this.loopback = loopback;
    }

    /** {@code true} for a stream opened by {@link #openLoopback}. */
    boolean isLoopback() {
        return loopback;
    }

    // ---- CaptureStream accessors (the reader reads the seam, never the fields) -----
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
    public boolean mmcssOk() {
        return mmcssOk;
    }

    @Override
    public int lastFlags() {
        return lastFlags;
    }

    @Override
    public long lastQpc100ns() {
        return lastQpc100ns;
    }

    @Override
    public long lastStampNs() {
        return lastStampNs;
    }

    /**
     * Activate the default capture endpoint and initialise a shared event-driven stream at the
     * engine's default period. Throws {@link Com.HrException} (negative HRESULT, numbers only — a
     * box with no capture endpoint lands here) or {@link IllegalStateException} (a mix format
     * that is neither float32 nor int16); the factory falls back to the JDK line on either.
     */
    static WasapiCaptureStream open(Com com, Arena arena) throws Throwable {
        return open(com, arena, false);
    }

    /**
     * The LOOPBACK variant (class javadoc): the default RENDER endpoint's post-mix stream, polled.
     * Same failure contract as {@link #open} — {@link Com.HrException} (a box with no render
     * endpoint, or an engine that refuses the flag) or {@link IllegalStateException} (mix format).
     */
    static WasapiCaptureStream openLoopback(Com com, Arena arena) throws Throwable {
        return open(com, arena, true);
    }

    private static WasapiCaptureStream open(Com com, Arena arena, boolean loopback) throws Throwable {
        WasapiCaptureStream s = new WasapiCaptureStream(com, arena, loopback);
        try {
            s.activate();
            return s;
        } catch (Throwable t) {
            s.close();
            throw t;
        }
    }

    private void activate() throws Throwable {
        MemorySegment slot = com.ptrSlot();
        Com.check("CoCreateInstance(MMDeviceEnumerator)", (int) com.coCreateInstance.invokeExact(
                com.guid(WasapiRenderStream.CLSID_MMDeviceEnumerator), MemorySegment.NULL, WasapiRenderStream.CLSCTX_ALL,
                com.guid(WasapiRenderStream.IID_IMMDeviceEnumerator), slot));
        enumerator = Com.deref(slot);
        slot = com.ptrSlot();
        if (loopback) {
            Com.check("GetDefaultAudioEndpoint(eRender)", (int) WasapiRenderStream.M_this_int_int_pp.invokeExact(
                    Com.fp(enumerator, WasapiRenderStream.ENUM_GetDefaultAudioEndpoint), enumerator,
                    WasapiRenderStream.E_RENDER, WasapiRenderStream.E_CONSOLE, slot));
        } else {
            Com.check("GetDefaultAudioEndpoint(eCapture)", (int) WasapiRenderStream.M_this_int_int_pp.invokeExact(
                    Com.fp(enumerator, WasapiRenderStream.ENUM_GetDefaultAudioEndpoint), enumerator, E_CAPTURE, E_COMMUNICATIONS, slot));
        }
        device = Com.deref(slot);
        slot = com.ptrSlot();
        Com.check("Activate(IAudioClient)", (int) WasapiRenderStream.M_this_p_int_p_pp.invokeExact(
                Com.fp(device, WasapiRenderStream.DEV_Activate), device, com.guid(WasapiRenderStream.IID_IAudioClient),
                WasapiRenderStream.CLSCTX_ALL, MemorySegment.NULL, slot));
        client = Com.deref(slot);

        slot = com.ptrSlot();
        Com.check("GetMixFormat", (int) WasapiRenderStream.M_this_p.invokeExact(Com.fp(client, WasapiRenderStream.AC_GetMixFormat), client, slot));
        fmtPtr = Com.deref(slot);
        readFormat(fmtPtr);
        if (mixRate < 8000 || mixRate > 192000) {
            throw new IllegalStateException("mix rate " + mixRate);
        }
        if (mixChannels < 1 || mixChannels > 32) {
            throw new IllegalStateException("mix channels " + mixChannels);
        }

        MemorySegment pDef = arena.allocate(JAVA_LONG);
        MemorySegment pMin = arena.allocate(JAVA_LONG);
        Com.check("GetDevicePeriod", (int) WasapiRenderStream.M_this_p_p.invokeExact(Com.fp(client, WasapiRenderStream.AC_GetDevicePeriod), client, pDef, pMin));
        defaultPeriod100ns = pDef.get(JAVA_LONG, 0);

        // hnsBufferDuration 0 + hnsPeriodicity 0 = the engine's own default buffer at its default
        // period; EVENTCALLBACK = one wake per period on `event` (the OBS capture shape). The
        // loopback variant asks for LOOPBACK alone (polled — class javadoc).
        if (loopback) {
            Com.check("Initialize(SHARED, LOOPBACK)", (int) WasapiRenderStream.M_init.invokeExact(
                    Com.fp(client, WasapiRenderStream.AC_Initialize), client, WasapiRenderStream.AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_LOOPBACK, 0L, 0L, fmtPtr, MemorySegment.NULL));
        } else {
            Com.check("Initialize(SHARED, EVENTCALLBACK)", (int) WasapiRenderStream.M_init.invokeExact(
                    Com.fp(client, WasapiRenderStream.AC_Initialize), client, WasapiRenderStream.AUDCLNT_SHAREMODE_SHARED,
                    WasapiRenderStream.AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 0L, 0L, fmtPtr, MemorySegment.NULL));
        }

        MemorySegment pBuf = arena.allocate(JAVA_INT);
        Com.check("GetBufferSize", (int) WasapiRenderStream.M_this_p.invokeExact(Com.fp(client, WasapiRenderStream.AC_GetBufferSize), client, pBuf));
        bufferFrames = pBuf.get(JAVA_INT, 0);
        if (bufferFrames <= 0 || bufferFrames > 1 << 20) {
            throw new IllegalStateException("buffer frames " + bufferFrames);
        }

        slot = com.ptrSlot();
        Com.check("GetService(IAudioCaptureClient)", (int) WasapiRenderStream.M_this_p_p.invokeExact(
                Com.fp(client, WasapiRenderStream.AC_GetService), client, com.guid(IID_IAudioCaptureClient), slot));
        capture = Com.deref(slot);

        if (!loopback) {
            event = (MemorySegment) com.createEventW.invokeExact(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
            if (event.address() == 0) {
                throw new IllegalStateException("CreateEventW NULL");
            }
            Com.check("SetEventHandle", (int) WasapiRenderStream.M_this_p.invokeExact(Com.fp(client, WasapiRenderStream.AC_SetEventHandle), client, event));
        }

        if (com.avSetMmThreadCharacteristicsW != null) {
            MemorySegment idx = arena.allocate(JAVA_INT);
            mmcss = (MemorySegment) com.avSetMmThreadCharacteristicsW.invokeExact(com.wstr("Audio"), idx);
            mmcssOk = mmcss.address() != 0;
        }

        fpNext = Com.fp(capture, CC_GetNextPacketSize);
        fpGet = Com.fp(capture, CC_GetBuffer);
        fpRel = Com.fp(capture, CC_ReleaseBuffer);
        pNext = arena.allocate(JAVA_INT);
        pData = com.ptrSlot();
        pFrames = arena.allocate(JAVA_INT);
        pFlags = arena.allocate(JAVA_INT);
        pPos = arena.allocate(JAVA_LONG);
        pQpc = arena.allocate(JAVA_LONG);
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
        if (tag == WasapiRenderStream.WAVE_FORMAT_EXTENSIBLE && cb >= 22) {
            MemorySegment ext = p.reinterpret(18 + cb);
            MemorySegment guid = ext.asSlice(24, 16);
            isFloat = Com.guidEquals(guid, com.guid(WasapiRenderStream.SUBTYPE_IEEE_FLOAT));
            pcm16 = Com.guidEquals(guid, com.guid(WasapiRenderStream.SUBTYPE_PCM));
        } else if (tag == WasapiRenderStream.WAVE_FORMAT_IEEE_FLOAT) {
            isFloat = true;
        } else if (tag == WasapiRenderStream.WAVE_FORMAT_PCM) {
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
        Com.check("Start", (int) WasapiRenderStream.M_this.invokeExact(Com.fp(client, WasapiRenderStream.AC_Start), client));
        started = true;
    }

    /**
     * Blocks until the engine signals a period (or {@link WasapiRenderStream#WAIT_MS}); the WAIT_* code.
     * A loopback stream has no event: it sleeps {@value #LOOPBACK_POLL_MS} ms and reports
     * {@code WAIT_OBJECT_0} (the caller drains whatever queued; an interrupt is re-flagged, never lost).
     */
    @Override
    public int waitWake() throws Throwable {
        if (loopback) {
            try {
                Thread.sleep(LOOPBACK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return WasapiRenderStream.WAIT_OBJECT_0;
        }
        return (int) com.waitForSingleObject.invokeExact(event, WasapiRenderStream.WAIT_MS);
    }

    /** Frames in the next queued packet; 0 = drained for this wake. */
    @Override
    public int nextPacketFrames() throws Throwable {
        Com.check("GetNextPacketSize", (int) WasapiRenderStream.M_this_p.invokeExact(fpNext, capture, pNext));
        int n = pNext.get(JAVA_INT, 0);
        return n < 0 ? 0 : n;
    }

    /**
     * GetBuffer → fold the packet to mono int16 into {@code dst} (float32 / int16, N channels: ch0
     * alone, else (ch0+ch1)/2; a SILENT packet writes zeros) → ReleaseBuffer with the engine's own
     * count. Returns the frames written (0 = the engine had nothing after all). {@code dst} is
     * sized {@code bufferFrames} by the caller; a packet the engine claims is larger than its own
     * buffer is refused (stream lost) — the copy never runs past either bound.
     */
    @Override
    public int readPacket(short[] dst) throws Throwable {
        int hr = (int) M_cc_get.invokeExact(fpGet, capture, pData, pFrames, pFlags, pPos, pQpc);
        lastStampNs = System.nanoTime();
        if (hr == AUDCLNT_S_BUFFER_EMPTY) {
            return 0;
        }
        Com.check("GetBuffer", hr);
        int frames = pFrames.get(JAVA_INT, 0);
        lastFlags = pFlags.get(JAVA_INT, 0);
        lastQpc100ns = pQpc.get(JAVA_LONG, 0);
        if (frames <= 0) {
            Com.check("ReleaseBuffer", (int) M_this_int.invokeExact(fpRel, capture, frames));
            return 0;
        }
        if (frames > bufferFrames || frames > dst.length) {
            throw new IllegalStateException("packet frames " + frames);
        }
        if ((lastFlags & FLAG_SILENT) != 0) {
            Arrays.fill(dst, 0, frames, (short) 0);
        } else {
            MemorySegment buf = Com.deref(pData).reinterpret((long) frames * blockAlign);
            int mc = mixChannels;
            for (int i = 0; i < frames; i++) {
                long base = (long) i * blockAlign;
                int v;
                if (isFloat) {
                    float f = buf.get(JAVA_FLOAT, base);
                    if (mc > 1) {
                        f = (f + buf.get(JAVA_FLOAT, base + 4L)) * 0.5f;
                    }
                    v = Math.round(f * 32767f);
                } else {
                    v = buf.get(JAVA_SHORT, base);
                    if (mc > 1) {
                        v = (v + buf.get(JAVA_SHORT, base + 2L)) >> 1;
                    }
                }
                dst[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
            }
        }
        Com.check("ReleaseBuffer", (int) M_this_int.invokeExact(fpRel, capture, frames));
        return frames;
    }

    /** Stop → Release(capture, client, device, enumerator) → CloseHandle(event) → CoTaskMemFree(fmt) → MMCSS revert. */
    @Override
    public void close() {
        try {
            if (started && client != null) {
                int ignored = (int) WasapiRenderStream.M_this.invokeExact(Com.fp(client, WasapiRenderStream.AC_Stop), client);
            }
        } catch (Throwable t) {
            // Best effort at teardown.
        }
        started = false;
        Com.release(capture);
        Com.release(client);
        Com.release(device);
        Com.release(enumerator);
        capture = null;
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
