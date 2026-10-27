package cc.starsector.client.voice;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import cc.starsector.server.runtime.VoiceFrameCodec;
import cc.starsector.server.runtime.VoiceFrameCodec.TalkerFrame;

/**
 * The speaker half: thread {@code cc-voice-audio}.
 * Per frame tick: drain the net-to-audio {@link TalkerFrameQueue} into per-seat
 * {@link TalkerStream}s (at most {@code MAX_TALKERS_WIRE} = 8 live seats, a 9th drops + counts),
 * pop every stream into a mono scratch, publish its RMS to {@link VoiceMeters}, apply the D8
 * gain/pan law into an int stereo accumulator, apply the volume knob, hard-limit to ±32767 with a
 * per-frame limiter gain when the peak exceeds, pack little-endian stereo and {@code sink.write}.
 * The sink's blocking write paces the thread; with no active stream a SILENCE frame is written so
 * the line stays primed (idle cost = zeros).
 *
 * <p>Own seat: frames are played only when the EFFECTIVE echo is on (the client's own
 * {@code voice.echo} override, else the sheet's flag); then the
 * {@link EchoClock} stamps {@code meters.echoRttNanos} (+ the stamp time and the buffered-ms half
 * for the HUD row). Own frames mix CENTRED at unit gain (never through the proximity
 * law); the pan of every other proximity talker is scaled by {@code voice.panWidth}
 * or zeroed under {@code voice.spatial=false}. A {@code frameMs} change in the
 * sheet (config generation bump) ⇒ every stream resets and the sink reopens at the new frame
 * depth. Scratch buffers are allocated ONCE for the largest frame (60 ms = 2880 samples); nothing
 * is allocated per frame beyond what Concentus does internally. Decode never touches the render
 * thread.
 *
 * <p>PROBE-ONLY knobs (client-JVM sysprops read once, never sheet fields, the
 * m2e rig ferries them via {@code run-voice-m2e-watch.ps1 -StallMs}): {@link #PROBE_STALL_MS}
 * stalls this thread once per new stream after its first pop (the intermittent post-prime
 * build-up on demand = the excess-depth drain's deterministic field test) and
 * {@code -Dcc.probe.voiceLiveRowMs} tightens the live-row cadence so the drain's decay (one frame
 * per ~510 ms) is visible in the live series instead of hiding inside one 5 s interval.
 * {@link #POP_OFFSET_MS} ({@code -Dcc.voice.popOffsetMs}) is a TUNING knob, not a
 * probe: the dragon late pop, default off until graded.
 */
public final class VoicePlayback implements Runnable {
    private static final int MAX_FRAME_SAMPLES = 60 * 48;
    private static final int LINE_FRAMES = 2;
    /** per-stream live-row cadence (5 s; the `ended` line covers the rest).
     *  Probe override {@code -Dcc.probe.voiceLiveRowMs=N} (100..60000 ms), default 5 s. */
    private static final long LIVE_ROW_NANOS = 1_000_000L
            * Math.max(100, Math.min(60_000, Integer.getInteger("cc.probe.voiceLiveRowMs", 5000)));
    /** {@code -Dcc.probe.voiceAudioStallMs=N} (0..5000, default 0 = off) — once
     *  per NEW stream, right after its first successful pop, the audio thread sleeps N ms: the sink
     *  starves for one audible gap while the net side keeps queueing, the next {@link #drain} pushes
     *  the pile in one go ⇒ N / frameMs frames of post-prime depth, exactly the shape the drain
     *  exists for. Probe-only; a stream is stalled once (identity set, cleared with the streams). */
    static final int PROBE_STALL_MS = Math.max(0, Math.min(5000, Integer.getInteger("cc.probe.voiceAudioStallMs", 0)));
    /** {@code -Dcc.voice.popOffsetMs=N} — the dragon LATE POP (client-JVM
     *  sysprop; DEFAULT 7 from the loopback-ear grade, which
     *  measured capture→speaker 92.4 → 84.9 ms (−7.5) at unchanged capture→sink; the
     *  flight's {@code underrun} row grades the dragon's ≈ 3 ms wake margin, fallback 5; clamped per
     *  frame length by {@link #popOffsetNanos}; knob sheet). WHY: on the kit's FFM/WASAPI sink {@code sink.write}
     *  returns AT the engine wake and the frame popped after wake k renders at wake k+1 whatever
     *  its pop instant inside the period — so parking N ms after the write moves the pop later
     *  inside the period at zero audio cost: every frame arriving in those N ms rides the SAME
     *  engine slot instead of the next ⇒ mean arrival→speaker 15 → 5 + (period − N) ms [computed;
     *  N = 7 ⇒ −7 ms]. Overshooting the next wake costs one period of silence (an {@code underrun}),
     *  so N ≤ period − 2 leaves the mix time + park scatter as margin. On the JDK line the pop
     *  re-locks to the line's fill level: the knob re-draws the arrival/pop phase once and the
     *  m2e {@code wait} law is unchanged (the ownerless negative proof). */
    static final int POP_OFFSET_MS = Math.max(0, Math.min(58, Integer.getInteger("cc.voice.popOffsetMs", 7)));

    private final TalkerFrameQueue queue;
    private final Supplier<VoiceSheet> sheet;
    private final IntSupplier configGeneration;
    private final PcmSink sink;
    private final Supplier<VoicePositions> positions;
    private final VoiceMeters meters;
    private final IntSupplier ownSeatId;
    /** Live client settings (volume / echo override / spatial / panWidth), read per frame. */
    private final Supplier<VoiceClientSettings> settings;
    private final EchoClock echo;
    private final Consumer<String> log;
    private final Thread thread;
    private volatile boolean running = true;

    private final Map<Integer, TalkerStream> streams = new HashMap<>();
    /** Streams already given their probe stall (a new burst's stream is a new object). */
    private final Set<TalkerStream> stalled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final short[] mono = new short[MAX_FRAME_SAMPLES];
    private final int[] acc = new int[2 * MAX_FRAME_SAMPLES];
    private final byte[] packed = new byte[4 * MAX_FRAME_SAMPLES];

    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong ownDropped = new AtomicLong();
    private final AtomicLong seatOverflow = new AtomicLong();
    private final AtomicLong limited = new AtomicLong();
    private volatile int grantedBufferBytes = -1;
    /** The sink's real held depth after open (0 ⇒ the {@link #LINE_FRAMES} default). */
    private volatile int lineDepthMs;
    /** The late-pop park after each {@code sink.write} (0 = off), set per line open. */
    private long popOffsetNanos;
    /** The SINK EAR — times the probe tone at the {@code sink.write} hand-off; null = probe off. */
    private final VoiceProbeTone.Detector probeEar =
            VoiceProbe.earMode() == VoiceProbe.EAR_OFF ? null : new VoiceProbeTone.Detector(48000);

    public VoicePlayback(TalkerFrameQueue queue, Supplier<VoiceSheet> sheet,
            IntSupplier configGeneration, PcmSink sink, Supplier<VoicePositions> positions,
            VoiceMeters meters, IntSupplier ownSeatId, Supplier<VoiceClientSettings> settings,
            EchoClock echo, Consumer<String> log) {
        this.queue = queue;
        this.sheet = sheet;
        this.configGeneration = configGeneration;
        this.sink = sink;
        this.positions = positions;
        this.meters = meters;
        this.ownSeatId = ownSeatId;
        this.settings = settings == null ? () -> VoiceClientSettings.DEFAULTS : settings;
        this.echo = echo;
        this.log = log;
        this.thread = new Thread(this, "cc-voice-audio");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Stops the thread, joins it, closes the sink. */
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sink.close();
    }

    public long framesWritten() {
        return framesWritten.get();
    }

    public long ownDropped() {
        return ownDropped.get();
    }

    public long seatOverflow() {
        return seatOverflow.get();
    }

    public long limited() {
        return limited.get();
    }

    /** The line buffer the sink granted (bytes), -1 before open. */
    public int grantedBufferBytes() {
        return grantedBufferBytes;
    }

    @Override
    public void run() {
        VoiceSheet cur = null;
        int gen = Integer.MIN_VALUE;
        try {
            while (running) {
                VoiceSheet s = sheet.get();
                if (s == null) {
                    Thread.sleep(20L);
                    continue;
                }
                int g = configGeneration.getAsInt();
                if (cur == null || g != gen) {
                    if (cur == null || s.frameMs != cur.frameMs) {
                        if (!reopen(s)) {
                            return;
                        }
                    } else if (!s.sameJitter(cur)) {
                        // A jitter-trio retune re-targets every live stream in
                        // place (no line reopen, no burst reset, no audible gap).
                        for (TalkerStream t : streams.values()) {
                            t.retune(s.jitterMinFrames, s.jitterMaxMs, s.jitterShrinkMs);
                        }
                        say("voice jitter retune: floor " + s.jitterMinFrames + " frames, ceiling "
                                + s.jitterMaxMs + " ms, relax " + s.jitterShrinkMs + " ms ("
                                + streams.size() + " live stream(s) re-targeted)");
                    }
                    cur = s;
                    gen = g;
                }
                long now = System.nanoTime();
                drain(cur, now);
                mix(cur, now);
                if (popOffsetNanos > 0L) {
                    // The LATE POP: the write returned (dragon: at the engine wake), so
                    // the next drain+mix waits N ms into the period. An interrupt (close()) returns
                    // at once and the loop's `running` check exits.
                    LockSupport.parkNanos(popOffsetNanos);
                }
            }
        } catch (InterruptedException e) {
            // close()
        } catch (Throwable e) {
            // log-and-stop on ANY throwable (an AssertionError out of Concentus
            // used to kill the one cc-voice-audio daemon silently; the decode wrappers now catch
            // it per frame — this is the last line, so the death at least prints its class).
            say("voice audio stopped: " + e.getClass().getSimpleName());
        } finally {
            for (TalkerStream t : streams.values()) {
                t.resetState();
            }
            streams.clear();
            stalled.clear();
        }
    }

    private boolean reopen(VoiceSheet s) {
        for (TalkerStream t : streams.values()) {
            t.resetState();
        }
        streams.clear();
        stalled.clear();
        int asked = LINE_FRAMES * s.samplesPerFrame();
        try {
            grantedBufferBytes = sink.open(48000, 2, asked);
        } catch (IllegalStateException e) {
            say("voice audio disabled: " + e.getMessage());
            return false;
        }
        lineDepthMs = Math.max(0, sink.depthMs());
        meters.lineDepthMs.set(lineDepthMs);
        popOffsetNanos = popOffsetNanos(s.frameMs, POP_OFFSET_MS);
        say("voice audio line open: frameMs " + s.frameMs + ", asked " + (asked * 4)
                + " B, granted " + grantedBufferBytes + " B, depth "
                + lineDepthMsOr(s.frameMs) + " ms, popOffset " + (popOffsetNanos / 1_000_000L) + " ms");
        return true;
    }

    /** The late-pop park for {@code frameMs} in nanos — {@code askedMs} clamped to
     *  {@code 0..frameMs − 2} (the next engine wake must still find the frame written; 0 = off).
     *  Public for the selftest. */
    public static long popOffsetNanos(int frameMs, int askedMs) {
        int max = Math.max(0, frameMs - 2);
        return 1_000_000L * Math.max(0, Math.min(max, askedMs));
    }

    /** The line-depth term in ms for {@code frameMs}: the sink's real depth, else 2 frames. */
    private int lineDepthMsOr(int frameMs) {
        int d = lineDepthMs;
        return d > 0 ? d : LINE_FRAMES * frameMs;
    }

    private void drain(VoiceSheet cur, long now) {
        int own = ownSeatId.getAsInt();
        VoiceClientSettings live = liveSettings();
        TalkerFrameQueue.Arrival a;
        while ((a = queue.poll()) != null) {
            TalkerFrame f = a.frame();
            boolean ownFrame = f.seatId() == own;
            if (ownFrame && !live.effectiveEcho(cur)) {
                ownDropped.incrementAndGet();
                continue;
            }
            TalkerStream t = streams.get(f.seatId());
            if (t == null) {
                if (streams.size() >= VoiceFrameCodec.MAX_TALKERS_WIRE) {
                    seatOverflow.incrementAndGet();
                    continue;
                }
                TalkerStream.FrameDecoder dec;
                try {
                    dec = new OpusFrameDecoder(cur.frameMs);
                } catch (Exception e) {
                    seatOverflow.incrementAndGet();
                    continue;
                }
                t = new TalkerStream(f.seatId(), cur.frameMs, dec, cur.fec, cur.jitterMinFrames,
                        cur.jitterMaxMs, cur.jitterShrinkMs);
                streams.put(f.seatId(), t);
            }
            // The push carries the NET arrival stamp (the pop's wait = tick − arrival is
            // the phase observable); the echo RTT below keeps the tick stamp it always had.
            t.push(f, a.nanos());
            if (ownFrame && echo != null) {
                long rtt = echo.rttNanos(f.seq(), now);
                if (rtt >= 0L) {
                    // Net half here; the buffered half rides mix() POST-pop
                    // (here, pre-pop, it read the policy `target`, 30 ms
                    // under the measured 48 ms plateau).
                    meters.echoRttNanos.set(rtt);
                    meters.echoStampNanos.set(now);
                }
            }
        }
    }

    private VoiceClientSettings liveSettings() {
        VoiceClientSettings s = settings.get();
        return s == null ? VoiceClientSettings.DEFAULTS : s;
    }

    private void mix(VoiceSheet cur, long now) throws InterruptedException {
        int frameSize = cur.samplesPerFrame();
        int stereo = 2 * frameSize;
        java.util.Arrays.fill(acc, 0, stereo, 0);
        VoicePositions pos = positions.get();
        if (pos == null) {
            pos = VoicePositions.EMPTY;
        }
        VoiceClientSettings live = liveSettings();
        int own = ownSeatId.getAsInt();
        int deepestBufMs = 0;
        Iterator<TalkerStream> it = streams.values().iterator();
        while (it.hasNext()) {
            TalkerStream t = it.next();
            boolean got = t.pop(mono, now);
            if (TalkerStream.BURST_TRACE) {
                String tr = t.takeTraceRow(); // One row per traced tick, async log
                if (tr != null) {
                    say(tr);
                }
            }
            if (PROBE_STALL_MS > 0 && got && stalled.add(t)) {
                // The probe stall — this stream's first frame is in hand, the line
                // now starves for PROBE_STALL_MS while the net side queues; InterruptedException
                // = close(), the run loop's own exit.
                say("voice probe stall seat=" + t.seatId() + " ms=" + PROBE_STALL_MS
                        + " popped=" + t.popped() + " buffered=" + t.buffered());
                Thread.sleep(PROBE_STALL_MS);
            }
            if (!t.ended()) {
                // The HUD buf half = the POST-pop residual (frames still
                // queued ahead of the one just handed to the mixer) × frameMs + the line depth —
                // the effective depth, not the policy target (which under-read a held stream by
                // 30 ms). Own-seat stream ⇒ the echo row's buf half too, same source.
                int residualMs = t.buffered() * cur.frameMs + lineDepthMsOr(cur.frameMs);
                deepestBufMs = Math.max(deepestBufMs, residualMs);
                if (t.seatId() == own && echo != null) {
                    meters.echoBufMs.set(residualMs);
                }
            }
            if (t.ended()) {
                // ONE per-burst line — the crackle instrumentation
                // (popped/plc/late/dup/trimmed/refused/target per talker stream).
                say("voice talker seat=" + t.seatId() + " ended popped=" + t.popped()
                        + " plc=" + t.plc() + " late=" + t.late() + " dup=" + t.duplicates()
                        + " trimmed=" + t.trimmed()
                        // The plc subset that was an underrun HOLD (empty
                        // buffer, sequence held; a cold burst's 2-frame tail is two holds) +
                        // exhaustion re-primes.
                        + " holds=" + t.holds() + " reprimes=" + t.reprimes()
                        // excess-depth drains (two heads in one pop).
                        + " drains=" + t.drains()
                        + " refused=" + t.refused()
                        + " target=" + t.target()
                        // The sheet-dictated floor/ceiling this burst ran under.
                        + " min=" + t.targetMin() + " max=" + t.targetMax()
                        // re-target events + the buf floor/ceiling in ms, and the
                        // FEC A/B term (recoveries; 0 with the sheet's FEC off ⇒ plc carries it).
                        + " grow=" + t.targetGrows() + " shrink=" + t.targetShrinks()
                        + " bufMs=" + (t.target() * cur.frameMs + lineDepthMsOr(cur.frameMs))
                        + " floorMs=" + bufFloorMs(cur, lineDepthMs)
                        + " fec=" + (cur.fec ? 1 : 0) + " fecRec=" + t.fecRecovered()
                        // The measured-loudness fields — gain/pan as integer
                        // percent, distance in su, unresolved-anchor frame count.
                        + " gain%=" + Math.round(t.lastGain() * 100f)
                        + " pan%=" + Math.round(t.lastPan() * 100f)
                        + " d=" + Math.round(t.lastDist())
                        + " unres=" + t.unresolved()
                        // Which body positioned this burst's frames (live hull / dead
                        // hulk ghost spot / last-known fallback) — the prox-offset diagnosis.
                        + " live=" + t.mixedLive() + " hulk=" + t.mixedHulk()
                        + " last=" + t.mixedLast()
                        // this burst's in-order pop wait min/avg/max ms
                        // (tick − net arrival) = residual frames × frameMs + the arrival/pop phase.
                        + " wait=" + t.burstWaitText()
                        // The burst's stage stamps (`cap>arr=- out>arr=-`
                        // unless the talker ran -Dcc.probe.voiceStageStamps).
                        + " " + t.burstStampText());
                it.remove();
                stalled.remove(t);
                continue;
            }
            if (t.liveDue(now, LIVE_ROW_NANOS)) {
                // ONE row per LIVE_ROW_NANOS per active stream —
                // a held PTT never reaches its `ended` line before teardown, so this is
                // the only place a held stream's holds / residual depth / re-primes are
                // observable. `buffered` = the post-pop residual (frames queued ahead of the
                // playing one). `say` = the async pilot-watch log, safe at this rate.
                say("voice talker seat=" + t.seatId() + " live popped=" + t.popped()
                        + " plc=" + t.plc() + " holds=" + t.holds() + " reprimes=" + t.reprimes()
                        + " late=" + t.late() + " dup=" + t.duplicates()
                        + " trimmed=" + t.trimmed() + " drains=" + t.drains()
                        + " buffered=" + t.buffered()
                        + " target=" + t.target() + " grow=" + t.targetGrows()
                        + " shrink=" + t.targetShrinks() + " fecRec=" + t.fecRecovered()
                        + " bufMs=" + (t.buffered() * cur.frameMs + lineDepthMsOr(cur.frameMs))
                        // this window's in-order pop wait min/avg/max ms
                        // (tick − net arrival), taken with the row — the phase and its drift.
                        + " wait=" + t.takeWaitRow()
                        // this window's stage stamps, taken with the row.
                        + " " + t.takeStampRow());
            }
            if (!got) {
                continue;
            }
            int rms = t.lastRms();
            long refusedDelta = t.drainRefused();
            if (refusedDelta > 0L) {
                meters.decodeRefused.addAndGet(refusedDelta);
            }
            long plcDelta = t.drainPlc();
            if (plcDelta > 0L) {
                meters.plcFrames.addAndGet(plcDelta);
            }
            meters.publish(t.seatId(), t.anchorWireId(), t.flags(), rms, now);
            float gain = 1f;
            float p = 0f;
            float d = -1f;
            boolean unresolved = false;
            byte kind = VoicePositions.KIND_NONE;
            if (t.seatId() == own) {
                // Own echo is a meter, never a positioned talker — centred, unit gain.
                gain = 1f;
                p = 0f;
            } else if ((t.flags() & VoiceFrameCodec.FLAG_PROXIMITY) != 0 && pos.ownKnown) {
                int idx = pos.indexOf(t.anchorWireId());
                if (idx >= 0) {
                    float ax = pos.anchorXY[2 * idx];
                    float ay = pos.anchorXY[2 * idx + 1];
                    float dx = ax - pos.ownX;
                    float dy = ay - pos.ownY;
                    d = (float) Math.sqrt(dx * dx + dy * dy);
                    gain = VoiceSpatializer.proximityGain(d, cur.proxFullSu, cur.proxCullSu);
                    p = VoiceSpatializer.pan(ax, pos.camX, cur.proxFullSu);
                    kind = pos.kindAt(idx);
                } else {
                    unresolved = true; // Candidate: unity fallback, counted
                }
            }
            // Width knob / distance-only; the far-ear FLOOR is applied
            // AFTER the knob, so width 1.0 (a persisted value seen in a test run) can no longer
            // silence one ear (pan%=100) — the floor is the spatializer's, not the default's.
            p = live.spatial ? VoiceSpatializer.limitPan(p * live.panWidth) : 0f;
            t.noteMix(gain, p, d, unresolved, kind);
            if (gain <= 0f) {
                continue;
            }
            float gl = gain * VoiceSpatializer.panL(p);
            float gr = gain * VoiceSpatializer.panR(p);
            for (int i = 0; i < frameSize; i++) {
                int v = mono[i];
                acc[2 * i] += (int) (v * gl);
                acc[2 * i + 1] += (int) (v * gr);
            }
        }
        meters.talkerBufMs.set(deepestBufMs);
        double vol = live.volume;
        float v = (float) (vol != vol ? 1.0 : (vol < 0.0 ? 0.0 : (vol > 2.0 ? 2.0 : vol)));
        int peak = 0;
        for (int i = 0; i < stereo; i++) {
            int a = Math.abs(acc[i]);
            if (a > peak) {
                peak = a;
            }
        }
        float scale = v;
        if (peak * v > 32767f) {
            scale = 32767f / peak;
            limited.incrementAndGet();
        }
        for (int i = 0; i < stereo; i++) {
            int o = (int) (acc[i] * scale);
            if (o > 32767) {
                o = 32767;
            } else if (o < -32768) {
                o = -32768;
            }
            packed[2 * i] = (byte) o;
            packed[2 * i + 1] = (byte) (o >> 8);
        }
        if (probeEar != null) {
            // What the sink is handed (left channel, post-volume/limiter), stamped
            // at the hand-off; `mono` is free here (every stream popped above).
            for (int i = 0; i < frameSize; i++) {
                mono[i] = (short) ((packed[4 * i] & 0xFF) | (packed[4 * i + 1] << 8));
            }
            long onset = probeEar.feed(mono, 0, frameSize, System.nanoTime());
            if (onset >= 0L) {
                VoiceProbe.noteSink(onset);
            }
        }
        sink.write(packed, 0, 4 * frameSize);
        framesWritten.incrementAndGet();
    }

    /** The HUD "buf" FLOOR in ms for {@code sheet} — the jitter
     *  target's minimum ({@link TalkerStream#TARGET_MIN} frames) plus the line depth: the
     *  sink's REAL held depth when known (the dragon holds ≈ one 10 ms engine period,
     *  so 2 × 10 + 10 = 30 at 10 ms frames), else {@link #LINE_FRAMES} frames ((2 + 2) × 20 = 80
     *  ms, (2 + 2) × 10 = 40); the ceiling is {@link TalkerStream#TARGET_MAX_MS} + the line depth
     *  (a TIME budget, so 10 ms frames keep the 80 ms jitter ceiling). Null sheet ⇒
     *  the 20 ms default. */
    public static int bufFloorMs(VoiceSheet sheet, int lineDepthMs) {
        int frameMs = sheet == null ? 20 : sheet.frameMs;
        // The floor is the sheet's jitterMinFrames (default TARGET_MIN).
        int minFrames = sheet == null ? TalkerStream.TARGET_MIN : sheet.jitterMinFrames;
        int depth = lineDepthMs > 0 ? lineDepthMs : LINE_FRAMES * frameMs;
        return minFrames * frameMs + depth;
    }

    /** The 2-frame-line default of {@link #bufFloorMs(VoiceSheet, int)} (no sink in hand). */
    public static int bufFloorMs(VoiceSheet sheet) {
        return bufFloorMs(sheet, 0);
    }

    private void say(String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
