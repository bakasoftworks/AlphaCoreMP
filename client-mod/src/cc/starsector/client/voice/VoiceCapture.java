package cc.starsector.client.voice;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusSignal;

import cc.starsector.server.runtime.VoiceFrameCodec;

/**
 * The microphone half: thread {@code cc-voice-capture}.
 * Opens a {@code TargetDataLine} at the first {@link CaptureFormatLadder} format
 * the mixer accepts (48 kHz mono 16-bit LE first; anything else rides {@link PcmResampler} to
 * 48 k mono) with a line buffer of ~4 frames, on the mixer whose name contains settings
 * {@code mic.device} (else the default), reads EXACTLY one frame's worth per read — always, even
 * with PTT off, so the line is drained and a press never plays stale buffered audio — and while
 * {@link VoicePtt#mode()} is not 0 applies {@code mic.gain} (int math, clamped), encodes with
 * vendored Concentus and hands the VCUP to the {@link VoiceUplink}.
 *
 * <p>WHY the encoder can never emit an out-of-domain frame: the server relay drops over-cap frames
 * and KILLS the session on a malformed VCUP, so the encoder's {@code max_data_bytes} is the
 * sheet's {@code maxOpusBytes()} (Opus hard-caps its packet to that), the result is re-checked
 * (1..cap) before the send, flags are only {@code FLAG_PROXIMITY | FLAG_BURST_START | FLAG_ECHO}
 * (the last = the client's effective echo choice), burstId is u8 from the
 * PTT law. The encoder is rebuilt when the config generation OR the effective complexity (the
 * client's {@code voice.complexity} override, else the sheet's) changes (retune, D10); at every
 * burst start the encoder state is RESET to mirror the decoder's burst-start reset
 * ({@code TalkerStream#push}) — an encoder carrying the previous burst's overlap/LPC state into a
 * decoder that just reset is the onset click;
 * an unsupported capture format ⇒ {@code VoiceMeters.CAPTURE_UNSUPPORTED} + ONE log line and the
 * thread exits (playback unaffected); a line that stops delivering (device removed) ⇒
 * {@code CAPTURE_LOST} the same way after {@value #DEAD_LINE_READS} frame-paced empty reads,
 * never a hot spin. Encode never runs on the render thread (Concentus allocates
 * 70-150 KB per encoded frame).
 */
public final class VoiceCapture implements Runnable {
    private static final int MAX_FRAME_SAMPLES = 60 * 48;
    /** Consecutive empty reads (one frame-period apart) before the line is declared LOST (F4). */
    static final int DEAD_LINE_READS = 50;

    /** STAGE STAMPS ({@code -Dcc.probe.voiceStageStamps=true}; probe-only, default
     *  off): every VCUP carries {@link VoiceFrameCodec#FLAG_STAMP} + the capture hand-off's QPC
     *  microseconds (taken right after the resampler hands the PCM frame over), and this thread
     *  prints a {@code voice capture stamps} row every 5 s with capture→send min/avg/max. WHY: the
     *  m2e constant (capture→sink − wait) is bimodal 9.4 / ≈ 14 ms; the relay and the listener read
     *  the same stamp (loopback = one QPC clock) so the 4.7 ms lands on ONE stage. */
    static final boolean STAGE_STAMPS = Boolean.getBoolean("cc.probe.voiceStageStamps");
    private static final long STAMP_ROW_NANOS = 5_000_000_000L;

    private final VoiceUplink uplink;
    private final Supplier<VoiceSheet> sheet;
    private final IntSupplier configGeneration;
    private final VoicePtt ptt;
    private final VoiceMeters meters;
    /** Live settings (L5): device read once at open, {@code micGain} re-read per frame. */
    private final Supplier<VoiceClientSettings> settings;
    private final Consumer<String> log;
    private final Thread thread;
    private volatile boolean running = true;
    /** The open source ({@link LinePcmSource} or the ffm dragon via the factory). */
    private volatile PcmSource line;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong droppedOutOfDomain = new AtomicLong();
    private final AtomicLong encodeErrors = new AtomicLong();
    private final AtomicLong uplinkRefused = new AtomicLong();

    /** Echo RTT is stamped by the uplink owner at the ENVELOPE seq ({@code EchoClock.noteSent}
     * from the session's {@code sendVoice}) — the capture has no seq the VCDN could echo back. */
    public VoiceCapture(VoiceUplink uplink, Supplier<VoiceSheet> sheet, IntSupplier configGeneration,
            VoicePtt ptt, VoiceMeters meters, Supplier<VoiceClientSettings> settings,
            Consumer<String> log) {
        this.uplink = uplink;
        this.sheet = sheet;
        this.configGeneration = configGeneration;
        this.ptt = ptt;
        this.meters = meters;
        this.settings = settings == null ? () -> VoiceClientSettings.DEFAULTS : settings;
        this.log = log;
        this.thread = new Thread(this, "cc-voice-capture");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Stops the thread (closing the source unblocks a pending read; any-thread safe) and joins it. */
    public void close() {
        running = false;
        PcmSource l = line;
        if (l != null) {
            try {
                l.close();
            } catch (RuntimeException e) {
                // best effort
            }
        }
        thread.interrupt();
        try {
            thread.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        meters.pttMode.set(0);
        meters.captureState.set(VoiceMeters.CAPTURE_OFF);
    }

    public long sent() {
        return sent.get();
    }

    public long droppedOutOfDomain() {
        return droppedOutOfDomain.get();
    }

    public long encodeErrors() {
        return encodeErrors.get();
    }

    public long uplinkRefused() {
        return uplinkRefused.get();
    }

    @Override
    public void run() {
        VoiceSheet s = sheet.get();
        if (s == null) {
            return;
        }
        VoiceClientSettings s0 = settings.get();
        if (s0 == null) {
            s0 = VoiceClientSettings.DEFAULTS;
        }
        PcmSource l;
        PcmResampler rs;
        String fmtText;
        try {
            // The first ladder format the mixer accepts; non-48 k-mono lines are converted.
            // The factory puts the WASAPI dragon in front of the ladder on the kit's 25 JRE.
            CaptureFormatLadder.Opened o;
            if (VoiceProbe.toneMode() != VoiceProbe.TONE_OFF) {
                // The mouth-to-ear injector replaces the mic at the pipeline
                // entry; gain → encode → uplink below never learn the difference.
                o = new CaptureFormatLadder.Opened(ProbeToneSource.fromSysprops(ptt),
                        new javax.sound.sampled.AudioFormat(ProbeToneSource.RATE, 16, 1, true, false));
                say("voice source: probe tone injector (" + VoiceProbe.describe() + ")");
            } else {
                o = VoiceSourceFactory.open(s0.micDevice, 4 * s.samplesPerFrame(), this::say);
            }
            l = o.source;
            rs = o.resampler();
            fmtText = CaptureFormatLadder.describe(o.format)
                    + (rs.identity() ? "" : " -> 48 kHz mono (resampled)");
        } catch (Exception e) {
            meters.captureState.set(VoiceMeters.CAPTURE_UNSUPPORTED);
            say("voice capture disabled: no 16-bit PCM capture format opens on the mixer ("
                    + e.getClass().getSimpleName() + ")");
            return;
        }
        if (!running) {
            // close() raced the ladder open: never flip LIVE after teardown.
            try {
                l.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            return;
        }
        line = l;
        meters.captureState.set(VoiceMeters.CAPTURE_LIVE);
        say("voice capture open: " + fmtText + ", line buffer " + l.getBufferSize()
                + " B, mic.gain " + s0.micGain + ", device '" + s0.micDevice + "'");

        byte[] raw = new byte[rs.maxInputFor(MAX_FRAME_SAMPLES) * rs.bytesPerFrame];
        short[] pcm = new short[MAX_FRAME_SAMPLES];
        byte[] out = new byte[VoiceFrameCodec.MAX_OPUS_BYTES];
        OpusEncoder enc = null;
        int encGen = Integer.MIN_VALUE;
        int encComplexity = Integer.MIN_VALUE;
        float gainF = s0.micGain;
        int gainQ8 = Math.round(gainF * 256f);
        int emptyReads = 0;
        int stampN = 0; // stage-stamp capture→send accumulators (this thread)
        long stampMin = 0L;
        long stampSum = 0L;
        long stampMax = 0L;
        long stampRowNanos = 0L;
        try {
            while (running) {
                VoiceSheet cur = sheet.get();
                if (cur == null) {
                    break;
                }
                // L5: a /settings gain pick applies on the next frame (recompute only on change).
                VoiceClientSettings live = settings.get();
                if (live != null && live.micGain != gainF) {
                    gainF = live.micGain;
                    gainQ8 = Math.round(gainF * 256f);
                }
                int frameSize = cur.samplesPerFrame();
                int inFrames = rs.inputNeeded(frameSize);
                int bytes = inFrames * rs.bytesPerFrame;
                int off = 0;
                while (off < bytes && running) {
                    int n = l.read(raw, off, bytes - off);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                }
                if (off < bytes) {
                    if (!running) {
                        break;
                    }
                    // A removed device (USB unplug / Bluetooth drop) makes read()
                    // return 0 WITHOUT blocking — never hot-spin a core on it: pace by one frame,
                    // and after ~1 s of empties give the line up (LOST; a mic.on flip restarts).
                    if (++emptyReads >= DEAD_LINE_READS) {
                        meters.captureState.set(VoiceMeters.CAPTURE_LOST);
                        say("voice capture stopped: the line delivers no audio (device removed?)");
                        return;
                    }
                    try {
                        Thread.sleep(cur.frameMs);
                    } catch (InterruptedException e) {
                        break; // close() interrupts after clearing running
                    }
                    continue;
                }
                emptyReads = 0;
                // Always convert (PTT off included) so the phase carries across a press boundary.
                rs.convert(raw, inFrames, pcm, frameSize);
                long capNanos = STAGE_STAMPS ? System.nanoTime() : 0L; // The capture hand-off
                int mode = ptt.mode();
                meters.pttMode.set(mode);
                if (mode == VoicePtt.MODE_OFF) {
                    meters.silentCaptureFrames.set(0);
                    continue;
                }
                int gen = configGeneration.getAsInt();
                VoiceClientSettings liveS = live != null ? live : VoiceClientSettings.DEFAULTS;
                int complexity = liveS.effectiveComplexity(cur);
                if (enc == null || gen != encGen || complexity != encComplexity) {
                    try {
                        enc = buildEncoder(cur, complexity);
                        encGen = gen;
                        encComplexity = complexity;
                    } catch (Exception e) {
                        encodeErrors.incrementAndGet();
                        say("voice capture disabled: encoder init failed ("
                                + e.getClass().getSimpleName() + ")");
                        meters.captureState.set(VoiceMeters.CAPTURE_UNSUPPORTED);
                        return;
                    }
                }
                boolean silent = true;
                for (int i = 0; i < frameSize; i++) {
                    int v = pcm[i];
                    if (v != 0) {
                        silent = false;
                    }
                    v = (v * gainQ8) >> 8;
                    pcm[i] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                }
                if (silent) {
                    meters.silentCaptureFrames.incrementAndGet();
                } else {
                    meters.silentCaptureFrames.set(0);
                }
                meters.ownLevel.set(silent ? 0 : TalkerStream.rms(pcm, frameSize));
                int cap = Math.min(cur.maxOpusBytes(), VoiceFrameCodec.MAX_OPUS_BYTES);
                // Consume the burst start BEFORE encode and reset the encoder with
                // it (the decoder resets on the same flag). A frame lost after this point still
                // opens a new burstId on the wire, which resets the decoder too.
                boolean burst = ptt.takeBurstStart();
                if (burst) {
                    enc.resetState();
                }
                int len;
                try {
                    len = enc.encode(pcm, 0, frameSize, out, 0, cap);
                } catch (Exception | AssertionError e) {
                    // Concentus OpusAssert = AssertionError, unconditionally
                    // — without this arm the capture thread died silently on one bad frame.
                    encodeErrors.incrementAndGet();
                    continue;
                }
                if (len < 1 || len > cap) {
                    droppedOutOfDomain.incrementAndGet();
                    continue;
                }
                int flags = (mode == VoicePtt.MODE_PROXIMITY ? VoiceFrameCodec.FLAG_PROXIMITY : 0)
                        | (burst ? VoiceFrameCodec.FLAG_BURST_START : 0)
                        | (liveS.effectiveEcho(cur) ? VoiceFrameCodec.FLAG_ECHO : 0)
                        | (STAGE_STAMPS ? VoiceFrameCodec.FLAG_STAMP : 0);
                byte[] vcup;
                try {
                    vcup = VoiceFrameCodec.encodeUp(flags, ptt.burstId(), Arrays.copyOf(out, len),
                            (int) (capNanos / 1000L));
                } catch (IllegalArgumentException e) {
                    droppedOutOfDomain.incrementAndGet();
                    continue;
                }
                if (uplink.sendVoice(vcup)) {
                    sent.incrementAndGet();
                } else {
                    uplinkRefused.incrementAndGet();
                }
                if (STAGE_STAMPS) {
                    // Capture→send (encode + uplink hand-off) on this thread's clock.
                    long now = System.nanoTime();
                    long d = now - capNanos;
                    if (stampN++ == 0) {
                        stampMin = d;
                        stampSum = d;
                        stampMax = d;
                    } else {
                        stampSum += d;
                        stampMin = Math.min(stampMin, d);
                        stampMax = Math.max(stampMax, d);
                    }
                    if (stampRowNanos == 0L) {
                        stampRowNanos = now;
                    } else if (now - stampRowNanos >= STAMP_ROW_NANOS) {
                        say("voice capture stamps n=" + stampN + " cap>send="
                                + TalkerStream.waitText(stampN, stampSum, stampMin, stampMax));
                        stampN = 0;
                        stampRowNanos = now;
                    }
                }
            }
        } catch (Throwable e) {
            // log-and-stop on ANY throwable (the last line; a death here
            // at least prints its class instead of a silent thread exit).
            say("voice capture stopped: " + e.getClass().getSimpleName());
        } finally {
            line = null;
            try {
                l.close();
            } catch (RuntimeException e) {
                // best effort
            }
            meters.pttMode.set(0);
            if (meters.captureState.get() == VoiceMeters.CAPTURE_LIVE) {
                meters.captureState.set(VoiceMeters.CAPTURE_OFF);
            }
        }
    }

    /** The loss the encoder plans LBRR for when the sheet's fec is on — 20 % lowers the
     *  LBRR bitrate floor to 1.05× (libopus: (125 − min(loss, 25)) %) so the copy is actually
     *  written at the sheet's SILK share; 0 = FEC off (no copy, no bitrate diverted). */
    static final int FEC_EXPECTED_LOSS_PERCENT = 20;

    public static OpusEncoder buildEncoder(VoiceSheet s) throws Exception {
        return buildEncoder(s, s.complexity);
    }

    /** {@code complexity} = the EFFECTIVE Opus complexity 0..10 (client override or sheet). */
    public static OpusEncoder buildEncoder(VoiceSheet s, int complexity) throws Exception {
        OpusEncoder e = new OpusEncoder(48000, 1, s.lowDelay
                ? OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY
                : OpusApplication.OPUS_APPLICATION_VOIP);
        e.setBitrate(s.kbps * 1000);
        e.setComplexity(complexity < 0 ? 0 : (complexity > 10 ? 10 : complexity));
        e.setUseVBR(true);
        e.setUseConstrainedVBR(true);
        e.setUseInbandFEC(s.fec);
        // Concentus/libopus write the LBRR copy only when the
        // expected loss is > 0 (SilkChannelEncoder setup_LBRR: useInBandFEC && PacketLoss_perc
        // > 0; OpusEncoder also needs it to stay in SILK/hybrid) — without this line the
        // sheet's fec=1 encoded NO redundancy and the decoder's decodeFec was pure PLC.
        e.setPacketLossPercent(s.fec ? FEC_EXPECTED_LOSS_PERCENT : 0);
        e.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        return e;
    }

    private void say(String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
