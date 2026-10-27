package cc.starsector.client.voice;

import cc.starsector.server.runtime.VoiceFrameCodec;
import cc.starsector.server.runtime.VoiceFrameCodec.TalkerFrame;

/**
 * One remote talker's jitter buffer + decoder + PLC + burst reset.
 * Game-free and clock-injected (nanos are passed in) so the law is unit-
 * testable with a fake {@link FrameDecoder}; the real one is {@link OpusFrameDecoder}.
 *
 * <p>Ordering key = (burstId, seq u16 with wrap-around compare). A frame carrying
 * {@code FLAG_BURST_START} or a different burstId resets the buffer and the decoder state (a new
 * PTT press must never continue the old prediction). Duplicates and frames older than the last
 * popped one drop (the late ones grow the target depth one frame at a time up to the sheet's
 * {@code jitterMaxMs} ceiling (default {@value #TARGET_MAX_MS} ms); it shrinks back one step
 * toward the sheet's {@code jitterMinFrames} floor after {@code jitterShrinkMs} (default
 * {@value #SHRINK_AFTER_CLEAN_MS} ms) of clean pops — the trio, server-owned and
 * {@link #retune retuned} live). Buffering is bounded at
 * {@value #MAX_BUFFERED} frames and trimmed back to the target when clock drift piles frames up.
 *
 * <p>Pop law: not primed ⇒ wait until {@code buffered >= target}; at the prime, frames beyond
 * {@code target + 1} are TRIMMED oldest-first (the stream-open transient —
 * ~5 frames queued while the line opened — was kept whole by the prime and sat as a 48 ms plateau
 * for the rest of a held press; the +1 is one frame of phase hysteresis, so the post-pop residual
 * is exactly {@code target}); primed ⇒ the next seq decodes; a missing next seq with a NEWER
 * frame buffered ⇒ skip it (FEC recovery first, else PLC {@code decodePlc}); an EMPTY buffer ⇒
 * an underrun HOLD (PLC, sequence NOT advanced — the frame plays when it lands, the depth grew by
 * one; counted separately as {@link #holds()}). Either way at most
 * {@value #MAX_PLC} consecutive concealed frames, then the stream goes silent and re-primes
 * (waits for the target depth again, {@link #reprimes()}) — a bounded concealment, never an
 * endless synthetic tail. {@value #END_NANOS} ns without a pushed frame ⇒ the stream ENDS (state
 * reset, {@link #ended()} true) so the mixer can release the slot.
 *
 * <p>Excess-depth DRAIN: a
 * primed pop consumes exactly one frame per tick, so a residual built AFTER the prime (the pop side
 * briefly behind the arrivals early in a press — the JDK line's write back-pressure) stands for the
 * whole press: a held floor-1 stream measured a 3–5 frame / 48 ms plateau for two minutes with ZERO
 * holds, so no exhaustion, no re-prime, and the prime-time trim's only trigger never came. The gate
 * is WAIT-DRIVEN: every {@value #DRAIN_WINDOW_MS} ms of primed ticks is one window; at
 * its end the window ARMS a drain when at least two thirds of its ticks were in-order decodes AND
 * their mean wait (pop tick − net arrival, the phase observable of {@link #takeWaitRow}) is
 * {@code >= (target + 1) × frameMs} — the EFFECTIVE depth sits a full frame above the target's own
 * {@code target × frameMs + δ}. An armed drain fires on the first pop whose PRE-pop depth exceeds
 * {@code target + }{@value #DRAIN_HYSTERESIS_FRAMES} with two in-order heads queued: the head is
 * decoded and DISCARDED (decoder state stays continuous) and the next plays ({@link #drains()}).
 * One 10 ms splice per window at most. WHY the mean, not a strictly consecutive over-deep run:
 * the arrival/pop phase WOBBLES (± 4.5 ms on the JDK line), so a residual-2 stream
 * whose arrival straddles a pop boundary toggles pre-pop 2↔3 every tick — never over-deep for 50
 * consecutive pops although its wait sat at 21.4 ms for two minutes (2 of 8 lottery
 * runs); the window mean reads the depth the straddle hides. Losses / PLC / FEC / holds inside a
 * window are ticks without a decode (the two-thirds rule vetoes a lossy window, a lone loss does
 * not defer the drain); a late frame / trim / overflow / retune / burst reset restarts the window
 * and disarms (the depth changed under the samples). No hold/drain oscillation: a hold needs an
 * EMPTY buffer, a drain needs {@code target + 2} frames.
 *
 * <p>Decode failures (guard refusal, {@code OpusException}, any {@code RuntimeException} AND
 * {@code AssertionError} — the vendored Concentus signals invariant failures via
 * {@code Inlines.OpusAssert = throw new AssertionError()} unconditionally)
 * count and are treated exactly like a missing frame — the peer's bytes can never take the audio
 * thread down.
 */
public final class TalkerStream {
    /** Decoder seam so the selftest can inject a fake; the real impl is {@link OpusFrameDecoder}. */
    public interface FrameDecoder {
        /** Decode one frame into {@code pcmOut[0..frameSize)}; returns samples, or &lt; 0 when the
         * guard refuses the packet (counted as refused, never decoded). */
        int decode(byte[] opus, int len, short[] pcmOut, int frameSize) throws Exception;

        /** Recover the PREVIOUS (lost) frame from this packet's in-band FEC
         * copy (Opus LBRR, redundancy depth 1); returns samples, or &lt; 0 when refused / not
         * supported (the default — the selftest fakes then exercise the PLC path unchanged). */
        default int decodeFec(byte[] opus, int len, short[] pcmOut, int frameSize) throws Exception {
            return -1;
        }

        /** Packet-loss concealment for one frame. */
        int decodePlc(short[] pcmOut, int frameSize) throws Exception;

        void reset();
    }

    /** DEFAULT jitter floor in frames (2 = 20 ms at 10 ms frames). Now
     *  the LIVE floor is the sheet's {@code jitterMinFrames} (1..4, server-owned, VCFG-delivered,
     *  ops-GUI Voice tab; 1 = "try down to 10 ms") — this constant is only the
     *  no-sheet default the 3/4-arg constructors use. */
    public static final int TARGET_MIN = 2;
    /**
     * The jitter CEILING is a TIME budget, not a frame
     * count — at 10 ms frames the old {@code TARGET_MAX = 4} frames capped the buffer at 40 ms and a
     * hairpin talker sat pinned on it at 9 % PLC (late 342/3933). 80 ms = the 20 ms-frame ceiling
     * of record (4 × 20); per-stream {@link #targetMax()} = {@code max(floor, maxMs / frameMs)}.
     * DEFAULT only: the live ceiling is the sheet's {@code jitterMaxMs} (20..160).
     */
    public static final int TARGET_MAX_MS = 80;
    /** 32 = twice the widest ceiling (160 ms / 10 ms = 16 frames) so the trim bound
     *  ({@code target + targetMax}) never exceeds the ring. */
    public static final int MAX_BUFFERED = 32;
    public static final int MAX_PLC = 2;
    /** DEFAULT shrink window = 2 s of clean pops whatever the frame length (100 pops at 20 ms, 200
     *  at 10); the live window is the sheet's {@code jitterShrinkMs} (the "aggression" setting). */
    public static final int SHRINK_AFTER_CLEAN_MS = 2000;
    /** Excess-depth drain window (ms of primed ticks; was {@code DRAIN_AFTER_CLEAN_MS}
     *  while the gate was a strictly consecutive over-deep run): at each window's end the mean
     *  in-order wait is judged against {@code (target + 1) × frameMs} and at most ONE pop
     *  per window consumes two heads. Its own constant, deliberately far shorter than the shrink
     *  window (at 2 s the drain is inert on short bursts — its value is HELD streams;
     *  a 5-frame residual decays to 1 in ~2 s of a press at one frame per 500 ms). */
    public static final int DRAIN_WINDOW_MS = 500;
    /** One frame of phase hysteresis on the drain gate (pre-pop {@code buffered > target + 1}) so
     *  the arrival/pop phase wobble never drains the frame the next tick needs. */
    public static final int DRAIN_HYSTERESIS_FRAMES = 1;
    public static final long END_NANOS = 200_000_000L;
    /** BURST TRACE ({@code -Dcc.probe.voiceBurstTrace=true}, probe-only, default
     *  off): one sidelog row per audio tick for the first {@value #BURST_TRACE_TICKS} ticks of every
     *  burst (pre-prime ticks included) — the arrival clock and the pop clock side by side
     *  ({@code arr=} / {@code t=} ms from the burst's first arrival, the in-order pop's {@code wait=}),
     *  so a cold burst's wait sweep (1 → 25 ms inside 500 ms) reads as EITHER bunched
     *  arrivals (relay/admission) OR a lagging pop cadence — the {@code wait=} summary cannot tell
     *  them apart. Rows ride {@link #takeTraceRow} to the playback thread's async log. */
    public static final boolean BURST_TRACE = Boolean.getBoolean("cc.probe.voiceBurstTrace");
    public static final int BURST_TRACE_TICKS = 60;
    private static final int KIND_WAIT = 0, KIND_DEC = 1, KIND_FEC = 2, KIND_PLC = 3, KIND_HOLD = 4,
            KIND_REPRIME = 5, KIND_END = 6;
    private static final String[] KIND_NAMES = {"wait", "dec", "fec", "plc", "hold", "reprime", "end"};

    private final int seatId;
    private final int frameSize;
    private final int frameMs;
    private int targetMin;
    private int targetMax;
    private int shrinkAfterPops;
    private final FrameDecoder decoder;
    private final TalkerFrame[] buf = new TalkerFrame[MAX_BUFFERED];
    private int buffered;
    private int target;
    private int cleanPops;
    private boolean active;
    private boolean primed;
    private boolean ended;
    private int nextSeq;
    private int curBurstId = -1;
    private int consecutivePlc;
    private long lastPushNanos;
    private long lastAnchorWireId;
    private int lastFlags;
    private int lastRms;

    private long late;
    private long duplicates;
    private long overflow;
    private long trimmed;
    private long refused;
    private long decodeErrors;
    private long plc;
    private long popped;
    /** Underrun HOLDs (the {@code plc} subset with an EMPTY buffer — a stretch,
     *  not a loss) and exhaustion re-primes (silence after {@value #MAX_PLC} concealments) — the
     *  held-stream observables the {@code ended} line never showed (a held PTT ends only
     *  at teardown), printed on the periodic live row. */
    private long holds;
    private long reprimes;
    /** The live-row cadence clock (audio thread; {@link #liveDue}). */
    private long lastLiveNanos;
    private boolean liveClockSet;
    /** The drain window in pops ({@value #DRAIN_WINDOW_MS} / frameMs);
     *  the current window's primed ticks, in-order decodes and their wait sum (judged at the
     *  window's end: two thirds decoded AND mean wait ≥ (target + 1) × frameMs ⇒ {@code drainArmed},
     *  cleared when the drain fires or by the next judgment); drains done; the decode-and-discard
     *  scratch (allocated on the first drain; the audio thread owns it). */
    private final int drainAfterPops;
    private int drainWinTicks;
    private int drainWinDecodes;
    private long drainWinWaitSum;
    private boolean drainArmed;
    private long drains;
    private short[] drainScratch;
    /** The arrival/pop PHASE observable: each buffered frame's NET-thread
     *  arrival stamp by seq (a 64-slot ring; {@value #MAX_BUFFERED} keeps every buffered seq
     *  unambiguous), and the in-order pop's WAIT = pop tick − arrival = residual × frameMs + the
     *  phase, accumulated per live-row window ({@link #takeWaitRow}) and per burst
     *  ({@link #burstWaitText}). Holds / PLC / FEC / drained heads add nothing. Audio thread. */
    private static final int ARRIVAL_SLOTS = 64;
    private final long[] arrivedNanos = new long[ARRIVAL_SLOTS];
    private int waitN;
    private long waitSum;
    private long waitMin;
    private long waitMax;
    private int burstWaitN;
    private long burstWaitSum;
    private long burstWaitMin;
    private long burstWaitMax;
    /** Burst-trace state (audio thread; inert unless {@link #BURST_TRACE}): the burst's first
     *  arrival = the trace origin, ticks traced this burst, the last tick's kind / seq / drain, the
     *  pending row. */
    private long traceT0;
    private int traceTicks;
    private int lastKind;
    private int lastSeq;
    private boolean lastDrained;
    private String traceRow;
    /** STAGE STAMPS (frames carrying {@code FLAG_STAMP}; written by push on the audio
     *  thread): capture→arrival and relay-out→arrival in µs (wrap-aware int diffs — loopback = one
     *  QPC clock), per live-row window ({@link #takeStampRow}) and per burst
     *  ({@link #burstStampText}). EVERY pushed frame counts, late/duplicate included: the
     *  pipeline's timing does not depend on the frame's buffer fate. */
    private int stampN;
    private long stampCapMin;
    private long stampCapSum;
    private long stampCapMax;
    private long stampOutMin;
    private long stampOutSum;
    private long stampOutMax;
    private int burstStampN;
    private long burstCapMin;
    private long burstCapSum;
    private long burstCapMax;
    private long burstOutMin;
    private long burstOutSum;
    private long burstOutMax;
    // The mixer's LAST applied
    // gain/pan/distance for this stream + how many mixed frames had NO resolvable anchor
    // (unity fallback) — printed on the per-burst "ended" line so a reported loudness
    // asymmetry reads as numbers, never a guess. Written by the audio thread only.
    private float lastGain = -1f;
    private float lastPan;
    private float lastDist = -1f;
    private long unresolved;
    /** Mixed proximity frames by anchor resolution kind (live hull / hulk / last-known). */
    private long mixedLive;
    private long mixedHulk;
    private long mixedLast;
    /** Jitter-target re-target events this stream —
     * grows (a late frame while primed) and shrinks ({@value #SHRINK_AFTER_CLEAN_MS} ms of clean
     * pops) — printed on the per-burst line beside the floor so the HUD's buf swing reads as
     * events, never a guess. */
    private long targetGrows;
    private long targetShrinks;
    /** Frames recovered from the NEXT packet's in-band FEC copy (only attempted when
     * the sheet says the talkers encode FEC — the A/B's measurable term; PLC otherwise). */
    private final boolean fec;
    private long fecRecovered;

    public TalkerStream(int seatId, int frameMs, FrameDecoder decoder) {
        this(seatId, frameMs, decoder, false);
    }

    public TalkerStream(int seatId, int frameMs, FrameDecoder decoder, boolean fec) {
        this(seatId, frameMs, decoder, fec, TARGET_MIN, TARGET_MAX_MS, SHRINK_AFTER_CLEAN_MS);
    }

    /**
     * The sheet-dictated jitter trio — {@code minFrames} = the floor the
     * target starts at and shrinks back to, {@code maxMs} = the ceiling as a time budget,
     * {@code shrinkMs} = the clean-pop window before one shrink step. Out-of-domain values are
     * clamped here (the reader already refused them fail-closed; this is the last line).
     */
    public TalkerStream(int seatId, int frameMs, FrameDecoder decoder, boolean fec, int minFrames,
            int maxMs, int shrinkMs) {
        this.seatId = seatId;
        this.frameMs = Math.max(1, frameMs);
        this.frameSize = frameMs * 48;
        this.decoder = decoder;
        this.fec = fec;
        this.drainAfterPops = Math.max(1, DRAIN_WINDOW_MS / this.frameMs);
        retune(minFrames, maxMs, shrinkMs);
        this.target = targetMin;
    }

    /**
     * Adopt a new jitter trio on a LIVE stream (a {@code /voice jitter*} retune re-sends VCFG;
     * the playback thread calls this on every stream instead of resetting them — no audible gap).
     * The current target is clamped into the new [floor, ceiling]; the clean-pop count restarts.
     */
    public void retune(int minFrames, int maxMs, int shrinkMs) {
        // Bounded: targetMax ≤ MAX_BUFFERED / 2 so the trim bound (target + targetMax) never
        // exceeds the ring (16 + 16 = 32 at 10 ms frames / 160 ms).
        this.targetMin = Math.max(1, Math.min(MAX_BUFFERED / 2, minFrames));
        this.targetMax = Math.max(targetMin, Math.min(MAX_BUFFERED / 2, maxMs / frameMs));
        this.shrinkAfterPops = Math.max(1, shrinkMs / frameMs);
        if (target < targetMin) {
            target = targetMin;
        } else if (target > targetMax) {
            target = targetMax;
        }
        cleanPops = 0;
        resetDrainWindow();
    }

    /** The depth changed under the window's samples (late-grow / trim / overflow / retune /
     *  burst reset) ⇒ the window restarts and a pending arm is dropped. */
    private void resetDrainWindow() {
        drainWinTicks = 0;
        drainWinDecodes = 0;
        drainWinWaitSum = 0L;
        drainArmed = false;
    }

    /** this stream's jitter-target ceiling in frames ({@code jitterMaxMs} / frameMs). */
    public int targetMax() {
        return targetMax;
    }

    /** this stream's jitter-target floor in frames ({@code jitterMinFrames}). */
    public int targetMin() {
        return targetMin;
    }

    /** Wrap-aware u16 difference {@code a - b} in -32768..32767. */
    static int seqDiff(int a, int b) {
        return (short) ((a - b) & 0xFFFF);
    }

    /** Net side (via the audio thread): one frame for this seat. */
    public void push(TalkerFrame f, long nowNanos) {
        if (f == null) {
            return;
        }
        boolean burstStart = (f.flags() & VoiceFrameCodec.FLAG_BURST_START) != 0;
        if (burstStart || f.burstId() != curBurstId) {
            resetState();
            curBurstId = f.burstId();
            burstWaitN = 0; // The burst wait restarts here, never in resetState (the
                            // `ended` line prints AFTER pop's END reset)
            traceT0 = nowNanos; // The burst trace's origin + a fresh row budget
            traceTicks = 0;
            burstStampN = 0; // The burst's stage stamps restart with it
        }
        lastPushNanos = nowNanos;
        lastAnchorWireId = f.anchorWireId();
        lastFlags = f.flags();
        if ((f.flags() & VoiceFrameCodec.FLAG_STAMP) != 0) {
            int arrMicros = (int) (nowNanos / 1000L); // The net thread's arrival, µs
            noteStamp(arrMicros - f.capMicros(), arrMicros - f.outMicros());
        }
        active = true;
        ended = false;
        if (primed && seqDiff(f.seq(), nextSeq) < 0) {
            late++;
            cleanPops = 0;
            resetDrainWindow();
            if (target < targetMax) {
                target++;
                targetGrows++;
            }
            return;
        }
        int pos = buffered;
        for (int i = 0; i < buffered; i++) {
            int d = seqDiff(f.seq(), buf[i].seq());
            if (d == 0) {
                duplicates++;
                return;
            }
            if (d < 0) {
                pos = i;
                break;
            }
        }
        if (buffered == MAX_BUFFERED) {
            // Drop the OLDEST: shift everything down one slot, the insert position moves with it.
            System.arraycopy(buf, 1, buf, 0, MAX_BUFFERED - 1);
            buffered--;
            overflow++;
            // An overflow drop is a trim ⇒ the drain window restarts (on the spec's
            // restart list) — the depth changed under the window's wait samples.
            resetDrainWindow();
            pos = pos == 0 ? 0 : pos - 1;
        }
        arrivedNanos[f.seq() & (ARRIVAL_SLOTS - 1)] = nowNanos; // The frame's arrival
        System.arraycopy(buf, pos, buf, pos + 1, buffered - pos);
        buf[pos] = f;
        buffered++;
    }

    /**
     * Audio thread, once per frame tick: fills {@code pcmOut[0..frameSize)} and returns true when
     * a frame (decoded or concealed) is available; false = silence this tick.
     */
    public boolean pop(short[] pcmOut, long nowNanos) {
        if (!BURST_TRACE) {
            return popTick(pcmOut, nowNanos);
        }
        // The traced tick — kind/seq/drain are written by popTick's exits, the row after.
        int pre = buffered;
        lastKind = KIND_WAIT;
        lastSeq = -1;
        lastDrained = false;
        boolean got = popTick(pcmOut, nowNanos);
        traceTick(nowNanos, pre, got);
        return got;
    }

    private boolean popTick(short[] pcmOut, long nowNanos) {
        if (!active) {
            return false;
        }
        if (nowNanos - lastPushNanos > END_NANOS) {
            resetState();
            active = false;
            ended = true;
            lastKind = KIND_END;
            return false;
        }
        if (!primed) {
            if (buffered < target) {
                return false;
            }
            primed = true;
            // PRIME-TIME TRIM: everything older than the newest target+1
            // frames is skipped, so a bunched arrival at stream open (or at a re-prime after a
            // pop-thread stall) never becomes a standing plateau — the ring trim below
            // (target + targetMax, 90 ms at floor 1) fired far too late for that.
            if (buffered > target + 1) {
                int drop = buffered - (target + 1);
                System.arraycopy(buf, drop, buf, 0, buffered - drop);
                for (int i = buffered - drop; i < buffered; i++) {
                    buf[i] = null;
                }
                buffered -= drop;
                trimmed += drop;
                resetDrainWindow();
            }
            nextSeq = buf[0].seq();
            consecutivePlc = 0;
        }
        int trimTo = target + targetMax;
        if (buffered > trimTo) {
            int drop = buffered - target;
            System.arraycopy(buf, drop, buf, 0, buffered - drop);
            buffered -= drop;
            trimmed += drop;
            resetDrainWindow();
            nextSeq = buf[0].seq();
        }
        lastSeq = nextSeq; // The seq this tick plays, recovers or conceals
        boolean underrun = buffered == 0;
        // EXCESS-DEPTH DRAIN, gated on the window's MEAN WAIT with the
        // PRE-pop depth as the firing precondition: a primed pop consumes one frame per
        // tick, so a residual built after the prime (the pop side behind the 10 ms arrivals
        // for a few frames early in a press) stands for the whole press — 3–5 frames = the
        // measured 48 ms plateau, zero holds, so nothing else ever fires. Every drainAfterPops
        // primed ticks the window is judged: two thirds in-order decodes AND mean wait ≥
        // (target + 1) × frameMs ⇒ ARMED (the effective depth is a full frame above the target's
        // own frame + phase; a straddling residual-2 stream reads 20 + δ whatever its per-tick
        // 2↔3 toggle). An armed pop with two in-order heads and the hysteresis cleared
        // consumes both: the head decodes into scratch (continuity) and is dropped, the next
        // plays. `overDeep` false ⇒ the arm waits (the hysteresis frame is never drained).
        if (drainWinTicks >= drainAfterPops) {
            drainArmed = drainWinDecodes > 0 && drainWinDecodes * 3 >= drainWinTicks * 2
                    && drainWinWaitSum / drainWinDecodes >= (long) (target + 1) * frameMs * 1_000_000L;
            drainWinTicks = 0;
            drainWinDecodes = 0;
            drainWinWaitSum = 0L;
        }
        drainWinTicks++;
        boolean overDeep = buffered > target + DRAIN_HYSTERESIS_FRAMES;
        if (buffered > 0 && buf[0].seq() == nextSeq) {
            if (drainArmed && overDeep && buffered >= 2
                    && buf[1].seq() == ((nextSeq + 1) & 0xFFFF)) {
                TalkerFrame head = buf[0];
                System.arraycopy(buf, 1, buf, 0, buffered - 1);
                buf[--buffered] = null;
                nextSeq = (nextSeq + 1) & 0xFFFF;
                if (drainScratch == null) {
                    drainScratch = new short[frameSize];
                }
                // Decoded for the decoder's state only; a refusal/error counts as usual and the
                // frame is gone either way (the depth drained regardless).
                decodeInto(head, drainScratch);
                drains++;
                drainArmed = false;
                lastDrained = true;
                lastSeq = nextSeq;
            }
            TalkerFrame f = buf[0];
            System.arraycopy(buf, 1, buf, 0, buffered - 1);
            buf[--buffered] = null;
            nextSeq = (nextSeq + 1) & 0xFFFF;
            if (decodeInto(f, pcmOut)) {
                consecutivePlc = 0;
                popped++;
                lastKind = KIND_DEC;
                noteWait(nowNanos - arrivedNanos[f.seq() & (ARRIVAL_SLOTS - 1)]);
                if (++cleanPops >= shrinkAfterPops) {
                    cleanPops = 0;
                    if (target > targetMin) {
                        target--;
                        targetShrinks++;
                    }
                }
                lastRms = rms(pcmOut, frameSize);
                return true;
            }
        } else if (buffered > 0) {
            // A loss (FEC-recovered or concealed) is a drain-window tick without a
            // decode — the two-thirds rule, never a window restart.
            // A NEWER frame is buffered ⇒ nextSeq is lost (or hopelessly late): skip it.
            // With FEC on the sheet, the NEXT packet (seq+1, already buffered — the
            // target depth ≥ 2 means it usually is) carries an LBRR copy of THIS lost frame:
            // recover it instead of concealing. Adds no buffering (retroactive fill).
            boolean fecHit = fec && seqDiff(buf[0].seq(), nextSeq) == 1
                    && decodeFecInto(buf[0], pcmOut);
            nextSeq = (nextSeq + 1) & 0xFFFF;
            if (fecHit) {
                consecutivePlc = 0;
                fecRecovered++;
                lastKind = KIND_FEC;
                lastRms = rms(pcmOut, frameSize);
                return true;
            }
        }
        // else buffered == 0: UNDERRUN, not loss (the floor-1 echo rig).
        // Nothing newer has arrived, so the frame at nextSeq may be a millisecond away: HOLD the
        // sequence — one concealed frame with no advance = a one-frame stretch that grows the
        // effective depth by one. Advancing here guaranteed the arriving frame was dropped as
        // `late`, and because target growth only ever mattered at a RE-PRIME (which the
        // decode/conceal alternation never reached), a phase-unlucky burst at floor 1 ran
        // plc≈late for its whole length with target pinned at the ceiling (the flight-#13
        // "9 % PLC at the cap" shape). Bounded by MAX_PLC consecutive holds, then silence and a
        // re-prime at the (grown) target exactly as before.
        // Missing (or undecodable) frame at nextSeq — or an underrun hold (a drain-window tick
        // without a decode).
        if (consecutivePlc < MAX_PLC) {
            consecutivePlc++;
            plc++;
            if (underrun) {
                holds++;
            }
            lastKind = underrun ? KIND_HOLD : KIND_PLC;
            if (concealInto(pcmOut)) {
                lastRms = rms(pcmOut, frameSize);
                return true;
            }
            return false;
        }
        // Concealment exhausted: silence and re-prime on whatever arrives next. NO target bump
        // here: this branch is reached on EVERY burst tail (+30 ms; END needs
        // 200 ms), so a bump would ratchet every tap-tap press to the ceiling.
        primed = false;
        consecutivePlc = 0;
        reprimes++;
        // The re-prime is a depth discontinuity like
        // every trim — the window's pre-gap wait samples would otherwise judge the recovered
        // stream and a stale arm could drain its first transient overshoot (the strict gate's
        // removed `deepCleanRun = 0` line DID reset here).
        resetDrainWindow();
        lastKind = KIND_REPRIME;
        lastRms = 0;
        return false;
    }

    /** One trace row for this tick (audio thread) — skipped once the burst's budget is
     *  spent or for an inactive stream (its {@code end} row is the last). */
    private void traceTick(long nowNanos, int pre, boolean got) {
        if (traceTicks >= BURST_TRACE_TICKS || (!active && lastKind != KIND_END)) {
            return;
        }
        traceTicks++;
        StringBuilder sb = new StringBuilder(160);
        sb.append("voice trace seat=").append(seatId).append(" burst=").append(curBurstId)
                .append(" n=").append(traceTicks).append(" kind=").append(KIND_NAMES[lastKind])
                .append(" seq=").append(lastSeq < 0 ? "-" : Integer.toString(lastSeq))
                .append(" pre=").append(pre).append(" post=").append(buffered)
                .append(" t=").append(tenthsMs(nowNanos - traceT0));
        if (lastKind == KIND_DEC) {
            long arr = arrivedNanos[lastSeq & (ARRIVAL_SLOTS - 1)];
            sb.append(" arr=").append(tenthsMs(arr - traceT0))
                    .append(" wait=").append(tenthsMs(nowNanos - arr));
        }
        if (lastDrained) {
            sb.append(" drained=1");
        }
        if (!got) {
            sb.append(" silent=1");
        }
        traceRow = sb.toString();
    }

    /** The pending trace row (null = none); the playback thread logs it after each pop. */
    public String takeTraceRow() {
        String r = traceRow;
        traceRow = null;
        return r;
    }

    /**
     * true once per {@code intervalNanos} per stream (audio thread) — the
     * mixer's periodic live row. The clock arms on the first call, so the first row lands one
     * interval after the first pop; a stream shorter than that prints only its {@code ended} line.
     */
    public boolean liveDue(long nowNanos, long intervalNanos) {
        if (!liveClockSet) {
            liveClockSet = true;
            lastLiveNanos = nowNanos;
            return false;
        }
        if (nowNanos - lastLiveNanos >= intervalNanos) {
            lastLiveNanos = nowNanos;
            return true;
        }
        return false;
    }

    private boolean decodeInto(TalkerFrame f, short[] pcmOut) {
        try {
            int n = decoder.decode(f.opus(), f.opus().length, pcmOut, frameSize);
            if (n < 0) {
                refused++;
                return false;
            }
            if (n < frameSize) {
                java.util.Arrays.fill(pcmOut, n, frameSize, (short) 0);
            }
            return true;
        } catch (Exception | AssertionError e) {
            // Concentus OpusAssert = AssertionError, unconditionally.
            decodeErrors++;
            return false;
        }
    }

    /** The lost frame from {@code next}'s in-band FEC copy; a refusal / error is just
     *  "no recovery" (the PLC path follows) — the peer's bytes never take the thread down. */
    private boolean decodeFecInto(TalkerFrame next, short[] pcmOut) {
        try {
            int n = decoder.decodeFec(next.opus(), next.opus().length, pcmOut, frameSize);
            if (n < 0) {
                return false;
            }
            if (n < frameSize) {
                java.util.Arrays.fill(pcmOut, n, frameSize, (short) 0);
            }
            return true;
        } catch (Exception | AssertionError e) {
            decodeErrors++;
            return false;
        }
    }

    private boolean concealInto(short[] pcmOut) {
        try {
            int n = decoder.decodePlc(pcmOut, frameSize);
            if (n < frameSize) {
                java.util.Arrays.fill(pcmOut, Math.max(n, 0), frameSize, (short) 0);
            }
            return true;
        } catch (Exception | AssertionError e) {
            decodeErrors++;
            return false;
        }
    }

    /** Clear the buffer and decoder state (burst reset / stream end); counters survive. */
    public void resetState() {
        for (int i = 0; i < buffered; i++) {
            buf[i] = null;
        }
        buffered = 0;
        primed = false;
        consecutivePlc = 0;
        cleanPops = 0;
        resetDrainWindow();
        lastRms = 0;
        decoder.reset();
    }

    static int rms(short[] pcm, int n) {
        if (n <= 0) {
            return 0;
        }
        long acc = 0L;
        for (int i = 0; i < n; i++) {
            long v = pcm[i];
            acc += v * v;
        }
        return (int) Math.sqrt((double) acc / n);
    }

    public int seatId() {
        return seatId;
    }

    public int frameSize() {
        return frameSize;
    }

    /** jitter-target grow events (late frame while primed) this stream. */
    public long targetGrows() {
        return targetGrows;
    }

    /** jitter-target shrink events (100 clean pops) this stream. */
    public long targetShrinks() {
        return targetShrinks;
    }

    /** Frames recovered from in-band FEC (0 unless the sheet enables FEC). */
    public long fecRecovered() {
        return fecRecovered;
    }

    /** True while frames are flowing (from first push until the 200 ms end). */
    public boolean active() {
        return active;
    }

    /** True once the 200 ms silence law ended the stream; cleared by the next push. */
    public boolean ended() {
        return ended;
    }

    public int target() {
        return target;
    }

    public int buffered() {
        return buffered;
    }

    public int burstId() {
        return curBurstId;
    }

    public long anchorWireId() {
        return lastAnchorWireId;
    }

    public int flags() {
        return lastFlags;
    }

    /** RMS 0..32767 of the last popped frame (decoded or concealed). */
    public int lastRms() {
        return lastRms;
    }

    public long late() {
        return late;
    }

    /** Audio thread, once per mixed frame: the gain/pan/distance the mixer just applied
     *  ({@code dist < 0} = not a positioned talker; {@code unresolved} = proximity frame whose
     *  anchor was not in the positions snapshot ⇒ unity fallback). */
    public void noteMix(float gain, float pan, float dist, boolean unresolved, byte kind) {
        lastGain = gain;
        lastPan = pan;
        lastDist = dist;
        if (unresolved) {
            this.unresolved++;
        } else if (kind == VoicePositions.KIND_LIVE) {
            mixedLive++;
        } else if (kind == VoicePositions.KIND_HULK) {
            mixedHulk++;
        } else if (kind == VoicePositions.KIND_LAST) {
            mixedLast++;
        }
    }

    /** Mixed proximity frames positioned by the talker's LIVE hull. */
    public long mixedLive() {
        return mixedLive;
    }

    /** Mixed proximity frames positioned by the talker's dead HULK (ghost spot, pre-remount). */
    public long mixedHulk() {
        return mixedHulk;
    }

    /** Mixed proximity frames positioned by the LAST-known fallback (no body). */
    public long mixedLast() {
        return mixedLast;
    }

    /** Last applied mixer gain 0..1 (-1 before the first mixed frame). */
    public float lastGain() {
        return lastGain;
    }

    /** Last applied pan -1..1 (post width knob). */
    public float lastPan() {
        return lastPan;
    }

    /** Last body-to-anchor distance in su (-1 = global / own / unresolved). */
    public float lastDist() {
        return lastDist;
    }

    /** Mixed proximity frames whose anchor was unresolved (played at unity, centred). */
    public long unresolved() {
        return unresolved;
    }

    public long duplicates() {
        return duplicates;
    }

    public long overflow() {
        return overflow;
    }

    public long trimmed() {
        return trimmed;
    }

    public long refused() {
        return refused;
    }

    public long decodeErrors() {
        return decodeErrors;
    }

    public long plc() {
        return plc;
    }

    public long popped() {
        return popped;
    }

    /** Underrun holds (the empty-buffer subset of {@link #plc()}). */
    public long holds() {
        return holds;
    }

    /** Exhaustion re-primes (silence after {@value #MAX_PLC} concealments). */
    public long reprimes() {
        return reprimes;
    }

    /** excess-depth drains — pops that consumed TWO in-order heads (one
     *  decoded and discarded), at most one per {@value #DRAIN_WINDOW_MS} ms window whose mean
     *  in-order wait read a full frame above the target. */
    public long drains() {
        return drains;
    }

    /** One in-order decoded pop's wait (nanos) into the live-row window, the burst, and
     *  the drain window's accumulators. */
    private void noteWait(long w) {
        drainWinDecodes++;
        drainWinWaitSum += w;
        if (waitN++ == 0) {
            waitSum = w;
            waitMin = w;
            waitMax = w;
        } else {
            waitSum += w;
            waitMin = Math.min(waitMin, w);
            waitMax = Math.max(waitMax, w);
        }
        if (burstWaitN++ == 0) {
            burstWaitSum = w;
            burstWaitMin = w;
            burstWaitMax = w;
        } else {
            burstWaitSum += w;
            burstWaitMin = Math.min(burstWaitMin, w);
            burstWaitMax = Math.max(burstWaitMax, w);
        }
    }

    /** The live-row window's in-order pop wait as {@code min/avg/max} ms (one
     *  decimal; {@code -} when no in-order pop landed), and TAKES the window — the audio thread
     *  calls it once per live row, so consecutive rows read consecutive windows (the phase drift). */
    public String takeWaitRow() {
        String s = waitText(waitN, waitSum, waitMin, waitMax);
        waitN = 0;
        return s;
    }

    /** this burst's in-order pop wait as {@code min/avg/max} ms — the {@code ended} line's
     *  phase term (a cold burst's wait = the residual-0 phase alone). */
    public String burstWaitText() {
        return waitText(burstWaitN, burstWaitSum, burstWaitMin, burstWaitMax);
    }

    /** One stamped arrival's capture→arrival / relay-out→arrival (µs) into the live-row
     *  window and the burst accumulators. */
    private void noteStamp(int capToArrMicros, int outToArrMicros) {
        long c = capToArrMicros;
        long o = outToArrMicros;
        if (stampN++ == 0) {
            stampCapMin = c;
            stampCapSum = c;
            stampCapMax = c;
            stampOutMin = o;
            stampOutSum = o;
            stampOutMax = o;
        } else {
            stampCapSum += c;
            stampCapMin = Math.min(stampCapMin, c);
            stampCapMax = Math.max(stampCapMax, c);
            stampOutSum += o;
            stampOutMin = Math.min(stampOutMin, o);
            stampOutMax = Math.max(stampOutMax, o);
        }
        if (burstStampN++ == 0) {
            burstCapMin = c;
            burstCapSum = c;
            burstCapMax = c;
            burstOutMin = o;
            burstOutSum = o;
            burstOutMax = o;
        } else {
            burstCapSum += c;
            burstCapMin = Math.min(burstCapMin, c);
            burstCapMax = Math.max(burstCapMax, c);
            burstOutSum += o;
            burstOutMin = Math.min(burstOutMin, o);
            burstOutMax = Math.max(burstOutMax, o);
        }
    }

    /** The live-row window's stage stamps as {@code cap>arr=min/avg/max out>arr=min/avg/max}
     *  ms ({@code -} halves when no stamped frame arrived), and TAKES the window. */
    public String takeStampRow() {
        String s = stampText(stampN, stampCapMin, stampCapSum, stampCapMax, stampOutMin, stampOutSum,
                stampOutMax);
        stampN = 0;
        return s;
    }

    /** this burst's stage stamps (the {@code ended} line's term). */
    public String burstStampText() {
        return stampText(burstStampN, burstCapMin, burstCapSum, burstCapMax, burstOutMin,
                burstOutSum, burstOutMax);
    }

    static String stampText(int n, long cMin, long cSum, long cMax, long oMin, long oSum, long oMax) {
        if (n == 0) {
            return "cap>arr=- out>arr=-";
        }
        return "cap>arr=" + waitText(n, cSum * 1000L, cMin * 1000L, cMax * 1000L)
                + " out>arr=" + waitText(n, oSum * 1000L, oMin * 1000L, oMax * 1000L);
    }

    static String waitText(int n, long sum, long min, long max) {
        if (n == 0) {
            return "-";
        }
        return tenthsMs(min) + "/" + tenthsMs(sum / n) + "/" + tenthsMs(max);
    }

    /** Nanos as ms with one decimal ({@code 7_000_000 → "7.0"}); a negative reads with its sign.
     *  Public for the selftest (another package). */
    public static String tenthsMs(long nanos) {
        long t = Math.abs(nanos + (nanos < 0 ? -50_000L : 50_000L)) / 100_000L;
        return (nanos < 0 ? "-" : "") + (t / 10) + "." + (t % 10);
    }

    private long refusedDrained;
    private long plcDrained;

    /** Refusals (guard + decode errors) since the last drain — for the aggregate meter. */
    public long drainRefused() {
        long total = refused + decodeErrors;
        long d = total - refusedDrained;
        refusedDrained = total;
        return d;
    }

    /** PLC frames since the last drain — for the aggregate meter. */
    public long drainPlc() {
        long d = plc - plcDrained;
        plcDrained = plc;
        return d;
    }
}
