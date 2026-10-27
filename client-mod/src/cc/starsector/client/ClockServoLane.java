package cc.starsector.client;

import java.util.ArrayList;

import com.fs.starfarer.api.combat.MutableStat;

/**
 * STEP-9 CLOCK SERVO — the plugin-side engine seam of
 * the presentation-pacing servo. STEP-7/8 named the client clock deficit: CombatState.traverse()
 * paces combat with an OPEN-LOOP ms-truncated relative Thread.sleep — the sim is credited the
 * PLANNED frame period while the real sleep overshoots (one-sided, never re-measured), so a
 * sleep-paced client can ONLY lose vs wall (0.84–0.87x here at fps:120/540). The owner's fps:9999
 * never-sleep A-test erased the deficit (1.03–1.05; the residual overshoot IS the formula's
 * ms-quantization floor max(f43, 1/fpsTarget)) — confirming the credit model in BOTH directions.
 * This servo closes that open loop with the engine's OWN public time-scale knob: a modifier on
 * engine.getTimeMult(), which CombatEngine.advance multiplies into the per-frame amount (STEP-7
 * disasm offsets 250–272). Each measured frame recovers the engine's RAW pacer credit
 * f5 = amount / getTimeMult() — dividing out our own modifier AND any legitimate time-dilation
 * effect, so the servo never fights a real slow-mo mechanic (it composes multiplicatively with
 * them) — then EMAs pacer-vs-wall and applies the clamped inverse, driving the client's
 * presentation clock to wall-true == the canonical server clock (STEP-8(5): absolute-deadline
 * pacer, exactly 1.0x). Outcomes stay server-owned; this corrects presentation PACING only.
 * Kill switch: -Dcc.predict.clockServo=false.
 *
 * <p>K20 extraction (the 9500-backstop payment, extraction-first as
 * its own gated commit). Own-file per the PredictReconcileCombatPlugin growth-anchor law
 * — a new lane gets its own file:
 * PRCP carries only the delegate touchpoints. MOVED here: the servo statics + {@link ClockServoLaw}
 * instance, the STEP-9 v3 trace state + emitter, the leg-6 announced-sim-speed retarget
 * pair, the I.9 combatSpeedMult latch, the D-M2-5 engine-global timeMult scrub + its
 * lifetime counter, and the servo apply/remove seam. PRCP KEEPS: {@code measureFrameClock} (the
 * frame intake — it also tallies the clock-meter and the overrun witness), the clock-meter
 * fields + {@code logClockMeter} verdict, and the 1 Hz slope/net-rate sampling buffers
 * ({@code sampleRateWindows} — the lane fires it once per filled trace interval; the FoeInterpLane
 * isolation idiom). Every log/HUD string literal is byte-identical to the pre-extraction PRCP —
 * servo-guard.ps1 and verify-servo-replay.ps1 parse the servo-trace and clock-meter lines
 * column-exact. Zero policy change.
 */
final class ClockServoLane {

    private final PredictReconcileCombatPlugin plugin;

    private static final String SERVO_MOD_ID = "cc_clock_servo";
    /** Kill switch, read by the clock-meter verdict too (package-private for that one reader). */
    static final boolean SERVO_ENABLED = !"false".equals(System.getProperty("cc.predict.clockServo"));
    /** The CONTROL LAW — constants (TC, min-window, clamp edges + their measured history, outlier
     *  guards) and the decayed-window state live in {@link ClockServoLaw} (I.13
     *  code-motion extraction, game-free so the I.8 replay harness can drive it offline). This lane
     *  keeps the engine seam only: the stat read, the modifier apply, and the trace. */
    private final ClockServoLaw law = new ClockServoLaw();

    /** STEP-9 v3 servo TRACE:
     *  once per wall second emit ONE log line
     *  carrying the interval-true numbers otherwise read off the HUD by eye — fps (the verify-2 cap
     *  underrun to ~400 @ fps:540), the engine's RAW open-loop pacer rate, the CORRECTED rate the player
     *  actually experienced that second (a transient servo-lag overshoot — verify-2's "sim sped to ~1.2x on
     *  an fps spike" — lands HERE), the now-EMA span, the UNCLAMPED target vs the applied mult (shows clamp
     *  railing), and the cumulative C for HUD cross-reference. A verify run now documents itself in
     *  starsector.log ([CC-PILOT-WATCH] servo-trace), transients included. */
    private static final double SERVO_TRACE_PERIOD_SEC = 1.0d;
    private long traceFrames;          // measured frames this interval (fps = frames / wall)
    private double traceWallSec;       // Σ realDt this interval
    private double traceAmountSec;     // Σ corrected amount this interval (interval corrected rate)
    private double tracePacerSec;      // Σ raw pacer credit over the interval's NON-outlier frames
    private double tracePacerWallSec;  // Σ realDt over the SAME non-outlier frames (raw-rate denominator)
    private long traceOutlierFrames;   // frames the outlier guard kept out of the servo window
    private double traceNowMin = Double.POSITIVE_INFINITY; // span of the "now" EMA within the interval
    private double traceNowMax = Double.NEGATIVE_INFINITY;
    private int lastTraceFps = -1;     // last completed interval's fps, surfaced on the HUD sim-rate lines
    // The census window's fps mean/min, accumulated
    // from the 1 Hz trace intervals already computed here (zero new per-frame cost) and drained
    // by RemoteEntityCensus so FPS reads beside the population terms on ONE line.
    private long fpsWinSum;
    private int fpsWinCount;
    private int fpsWinMin = Integer.MAX_VALUE;

    /** I.2' (I.14 trace-touch bundle — the slope/net-rate half stays plugin-side with its buffers):
     *  the cumulative {@code C} carries warmup + spawn-hitch drag forever, so anchor a second
     *  cumulative at the frame the servo window first LOCKS — {@code C_lock} is the corrected-clock
     *  cumulative a verify run should actually read. Re-armed via {@link #resetTrace} (the
     *  no-feed→fed transition resets {@code clientSimTimeSec}). */
    private long lockWallStartNanos = Long.MIN_VALUE; // wall anchor at the servo window's first locked frame
    private double lockClientSimStart;                // clientSimTimeSec at that frame

    /** The server's ANNOUNCED sim-speed (AWSD v5 {@code simSpeedPermille}, 1000 =
     *  1.0x; wire-enforced [250,2000]) — net-thread written at the AWSD fold, render-thread consumed
     *  by {@link #advanceClockServo} (servo target x S). Hold-last by design: the server's speed
     *  persists across round wraps, and an AWSS-only serve never announces ⇒ stays 1000. */
    private volatile int announcedSimSpeedPermille = 1000;
    /** Render-thread edge detect for the retarget (initial 1000 = no log at the default speed). */
    private int simSpeedPermilleApplied = 1000;

    /** The engine multiplies the {@code combatSpeedMult} SETTINGS scalar into every
     *  frame's amount AFTER {@code getTimeMult()} (disasm offsets 251–272), where the servo's divide-out
     *  can't see it — a non-1 value is invisible plant gain the law silently inverts (S=1.25 is canceled
     *  to wall-true; S=2 rails the floor). Latent at this install (S=1); read once lazily and log LOUDLY
     *  if ≠1 so the fight is at least visible in the trace. {@code NaN} = not yet read. */
    private float combatSpeedMultSetting = Float.NaN;

    // run-cumulative APPLIED-mult stats (wall-time-weighted), emitted once
    // at teardown by removeClockServo: the (71c) adjudicator for "is the servo the accelerator".
    // share>1.05 = wall-time the servo ran the sim FAST; share<0.95 = the counter-slow regime
    // (measured live at fps 500+: the open-loop pacer OVER-credits, servo x0.87-0.95).
    private double multStatWallSec;
    private double multStatWeighted;
    private float multStatMin = Float.POSITIVE_INFINITY;
    private float multStatMax = Float.NEGATIVE_INFINITY;
    private double multStatFastSec;
    private double multStatSlowSec;
    private boolean multStatLogged;

    // D-M2-5 — the DD2 engine-global timeMult scrub (see scrubEngineTimeMult): lifetime
    // count of foreign (non-servo) modifier ids stripped; foe-only combats expect 0 — the counter
    // doubles as a free correctness meter (any hit names an unmodeled engine-global writer).
    private long timeMultScrubCount;
    /** One-shot first-sight log latch for the scrub (the counter keeps running). */
    private boolean timeMultScrubLogged;

    ClockServoLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** One measured frame — the {@code measureFrameClock} handoff (pause gaps never reach here):
     *  servo step first, then the trace fold rates raw-vs-corrected off the step's credit. */
    void frame(float amount, double realDt) {
        traceServoFrame(amount, realDt, advanceClockServo(amount, realDt));
    }

    /** The announced-sim-speed net-thread write at the AWSD fold; consumed on the render thread by
     *  {@link #advanceClockServo} (volatile handoff, hold-last by design). */
    void noteAnnouncedSimSpeed(int permille) {
        announcedSimSpeedPermille = permille;
    }

    /** The debrief token value ({@code predict_time_scrub_foreign_ids=}). */
    long foreignScrubCount() {
        return timeMultScrubCount;
    }

    /** The clock-meter verdict's lock test ({@code law.wallAccumSec() >= SERVO_MIN_WINDOW_SEC}). */
    boolean windowLocked() {
        return law.wallAccumSec() >= ClockServoLaw.SERVO_MIN_WINDOW_SEC;
    }

    /** Reset the servo TRACE state for the no-feed→fed re-anchor
     *  ({@code resetClockMeter}). DELIBERATELY NOT reset: the {@link ClockServoLaw} state
     *  (window + mult) — the plant (fps cap + scheduler) is the same machine across the
     *  no-feed→fed transition, so the fed window starts PRE-LOCKED with the learned mult (I.7's
     *  priming for free) instead of re-paying warmup at the raw deficit. The I.14 post-lock anchor
     *  re-arms on the next locked frame — i.e. immediately — giving C_lock a clean post-reset base
     *  instead of a stale clientSim offset. */
    void resetTrace() {
        traceFrames = 0L;
        traceWallSec = 0d;
        traceAmountSec = 0d;
        tracePacerSec = 0d;
        tracePacerWallSec = 0d;
        traceOutlierFrames = 0L;
        traceNowMin = Double.POSITIVE_INFINITY;
        traceNowMax = Double.NEGATIVE_INFINITY;
        lastTraceFps = -1;
        lockWallStartNanos = Long.MIN_VALUE;
        lockClientSimStart = 0d;
    }

    /**
     * One servo step for THIS measured frame (see the field block). Reads the
     * time-mult IN EFFECT when the engine computed this frame's {@code amount} (i.e. before this frame's
     * re-apply), divides it out to recover the engine's raw open-loop pacer credit, folds pacer + wall dt
     * into the decayed window, and re-applies the clamped inverse on {@code engine.getTimeMult()}. Pause
     * gaps never reach here ({@code PredictReconcileCombatPlugin#measureFrameClock} excludes them), so a
     * stale post-pause dt cannot spike the window.
     *
     * @return the raw pacer credit this frame folded into the servo window; {@code amount} unchanged when
     *         the servo is disabled/unavailable (so the v3 trace still rates the engine); {@code NaN} when
     *         the outlier guard excluded the frame or the stat read threw (I.4: a corrected amount must not
     *         masquerade as raw).
     */
    private double advanceClockServo(float amount, double realDt) {
        // Consume the ANNOUNCED sim-speed (net-thread written at the AWSD fold)
        // — retarget the law on the render thread that owns it, one loud line per change. With
        // the servo OFF the announcement is unconsumable (presentation free-runs 1.0×) — say so.
        int permille = announcedSimSpeedPermille;
        if (permille != simSpeedPermilleApplied) {
            simSpeedPermilleApplied = permille;
            float s = permille / 1000f;
            law.setSimSpeed(s);
            PilotWatchLog.info("predict sim-speed: server announced " + fmt2(s) + "x (permille="
                    + permille + ") -> servo retargeted"
                    + (SERVO_ENABLED ? "" : " (cc.predict.clockServo=false — NOT consumable;"
                            + " presentation free-runs 1.0x)"));
        }
        if (realDt >= ClockServoLaw.SERVO_OUTLIER_DT_SEC) {
            return Double.NaN; // hitch outlier — real time jumped (load/GC); its ratio is meaningless
        }
        if (!SERVO_ENABLED || plugin.vanillaWindow || plugin.engine == null) {
            // servo off/unavailable — or a TRUE-VANILLA compare window: the law never
            // folds and no modifier of ours is ever applied, so amount IS the raw credit (the clock
            // meter keeps rating the engine's own stock pacing).
            return ClockServoLaw.passthroughRawCredit(amount);
        }
        try {
            MutableStat timeMult = plugin.engine.getTimeMult();
            if (timeMult == null) {
                // 1290 (81) R6: UNEXPECTED null (the knob-off/vanilla-window passthroughs above
                // are deliberate and stay silent) — the clock silently runs open-loop.
                FallbackAnnounce.announceOnce("r6-timemult-null",
                        "engine.getTimeMult() null -> servo passthrough (open-loop clock)");
                return ClockServoLaw.passthroughRawCredit(amount);
            }
            double rawPacerDt = law.fold(amount, realDt, timeMult.getModifiedValue());
            if (!Double.isNaN(rawPacerDt) && law.applyReady()) {
                timeMult.modifyMult(SERVO_MOD_ID, law.mult());
            }
            return rawPacerDt;
        } catch (Throwable ignore) {
            // the servo is presentation-side pacing; never break the run on a stat hiccup. NaN, not amount
            // (I.4): with a mult possibly applied, amount is CORRECTED — folding it into the trace's raw=
            // column as if raw would silently mislabel that second; excluded-as-outlier is the honest bucket.
            return Double.NaN;
        }
    }

    /**
     * D-M2-5 — the DD2 ENGINE-GLOBAL timeMult scrub:
     * on the client the engine-global {@code engine.getTimeMult()} stat belongs to the STEP-9
     * clock servo ALONE ({@link #SERVO_MOD_ID}). The engine's own stat scripts write it for the
     * PLAYER-branch ship ({@code PhaseCloakStats} + {@code TemporalShellStats} —
     * the O9 census: both self-unmodify on the non-player branch every tick), which on a replica
     * presentation would slow THIS whole engine while the server's world marches on. Strip every
     * foreign modifier id each frame — called BEFORE the paused early-return in
     * {@code PredictReconcileCombatPlugin#advance} (the player branch advances even while paused
     * via {@code advanceEvenIfPaused}) and BEFORE {@link #advanceClockServo} (its multInEffect
     * read must see a servo-only mult or the fold mislabels the frame). CME-safe:
     * {@code unmodify(id)} mutates all three live mod maps, so the key lists are COPIED first.
     * Engine-global ONLY — per-ship {@code stats.getTimeMult()} is the faithful per-ship time lane
     * and is never touched (shared-time-contract OWNER-DECIDED #1). The lifetime counter doubles
     * as a correctness meter (foe-only combats expect 0 — any hit names an unmodeled engine-global
     * writer); it rides the debrief via {@code PredictReconcileCombatPlugin#logTimeMultScrubMeter}.
     */
    void scrubEngineTimeMult() {
        if (plugin.engine == null) {
            return;
        }
        try {
            MutableStat timeMult = plugin.engine.getTimeMult();
            if (timeMult == null) {
                return;
            }
            java.util.LinkedHashSet<String> foreign = null;
            foreign = collectForeignTimeMultIds(timeMult.getMultMods(), foreign);
            foreign = collectForeignTimeMultIds(timeMult.getFlatMods(), foreign);
            foreign = collectForeignTimeMultIds(timeMult.getPercentMods(), foreign);
            if (foreign == null) {
                return;
            }
            for (String id : foreign) {
                timeMult.unmodify(id);
            }
            timeMultScrubCount += foreign.size();
            if (!timeMultScrubLogged) {
                timeMultScrubLogged = true;
                PilotWatchLog.info("predict engine-timemult-scrub: FIRST foreign engine-global"
                        + " timeMult modifier(s) stripped ids=" + foreign
                        + " (servo-only law; lifetime counter rides the debrief)");
            }
        } catch (Throwable ignore) {
            // the scrub is a presentation-side guard; never break the frame
        }
    }

    /** {@link #scrubEngineTimeMult} helper: fold this live mod map's non-servo keys (COPIED —
     *  never iterate a map {@code unmodify} mutates) into {@code out} ({@code null} until the
     *  first hit; a set so an id present in two maps counts once). */
    private static java.util.LinkedHashSet<String> collectForeignTimeMultIds(
            java.util.Map<String, MutableStat.StatMod> mods, java.util.LinkedHashSet<String> out) {
        if (mods == null || mods.isEmpty()) {
            return out;
        }
        for (String id : new ArrayList<String>(mods.keySet())) {
            if (!SERVO_MOD_ID.equals(id)) {
                if (out == null) {
                    out = new java.util.LinkedHashSet<String>();
                }
                out.add(id);
            }
        }
        return out;
    }

    /**
     * STEP-9 v3 — fold ONE measured frame into the servo trace interval and emit the once-per-second
     * trace line when the interval fills ({@link #SERVO_TRACE_PERIOD_SEC}). {@code rawPacerDt} is what
     * {@link #advanceClockServo} folded into its window ({@code NaN} = outlier-excluded). {@code raw} vs
     * {@code corrected} is the servo's whole job in one line; {@code corrected} far above 1.0 for a second
     * is the verify-2 fps-spike overshoot, now measurable without recording software. Fail-soft by
     * construction (pure arithmetic + the proven {@link PilotWatchLog} seam).
     */
    private void traceServoFrame(float amount, double realDt, double rawPacerDt) {
        // Fold the mult ACTUALLY in effect this frame (1.0 during warmup/off —
        // nothing applied then) into the run-cumulative stats removeClockServo emits.
        float applied = SERVO_ENABLED && !plugin.vanillaWindow
                && law.wallAccumSec() >= ClockServoLaw.SERVO_MIN_WINDOW_SEC ? law.mult() : 1f;
        multStatWallSec += realDt;
        multStatWeighted += applied * realDt;
        if (applied < multStatMin) {
            multStatMin = applied;
        }
        if (applied > multStatMax) {
            multStatMax = applied;
        }
        if (applied > 1.05f) {
            multStatFastSec += realDt;
        } else if (applied < 0.95f) {
            multStatSlowSec += realDt;
        }
        traceFrames++;
        traceWallSec += realDt;
        traceAmountSec += amount;
        if (Double.isNaN(rawPacerDt)) {
            traceOutlierFrames++;
        } else {
            tracePacerSec += rawPacerDt;
            tracePacerWallSec += realDt;
        }
        if (law.wallAccumSec() >= ClockServoLaw.SERVO_MIN_WINDOW_SEC) {
            // I.2′: anchor the post-lock cumulative at the first locked frame (re-armed by resetClockMeter).
            if (lockWallStartNanos == Long.MIN_VALUE) {
                lockWallStartNanos = System.nanoTime();
                lockClientSimStart = plugin.clientSimTimeSec;
            }
            double now = law.corrAccumSec() / law.wallAccumSec();
            if (now < traceNowMin) {
                traceNowMin = now;
            }
            if (now > traceNowMax) {
                traceNowMax = now;
            }
        }
        if (traceWallSec < SERVO_TRACE_PERIOD_SEC) {
            return;
        }
        lastTraceFps = (int) Math.round(traceFrames / traceWallSec);
        fpsWinSum += lastTraceFps;
        fpsWinCount++;
        if (lastTraceFps < fpsWinMin) {
            fpsWinMin = lastTraceFps;
        }
        double wallSec = (System.nanoTime() - plugin.combatWallStartNanos) / 1_000_000_000.0d;
        // The 1 Hz slope/net-rate sampling rides the trace cadence by design (I.3);
        // the buffers and their readers stay plugin-side (K20 extraction seam).
        plugin.sampleRateWindows(wallSec);
        // I.9: first emission reads combatSpeedMult once; ≠1 logs loudly (the servo silently inverts it).
        if (Float.isNaN(combatSpeedMultSetting)) {
            combatSpeedMultSetting = readCombatSpeedMult();
            if (combatSpeedMultSetting != 1f) {
                PilotWatchLog.info("predict clock servo I.9 WARNING: combatSpeedMult="
                        + fmt2(combatSpeedMultSetting) + " ≠ 1 — invisible plant gain; the servo will fight"
                        + " it (cancel toward wall-true or rail a clamp). S= rides every servo-trace line.");
            }
        }
        String raw = tracePacerWallSec > 0d ? fmt2(tracePacerSec / tracePacerWallSec) : "n/a";
        String nowSpan = traceNowMin <= traceNowMax
                ? fmt2(traceNowMin) + ".." + fmt2(traceNowMax) : "warmup";
        // I.4 companion: `now` with OUR mult divided back out — disambiguates servo action from a legitimate
        // dilation mechanic (now≈1 + nowEx≈raw = servo correcting; now<1 + nowEx<raw = real slow-mo active).
        String nowEx = law.wallAccumSec() >= ClockServoLaw.SERVO_MIN_WINDOW_SEC && law.mult() > 0f
                ? fmt2(law.corrAccumSec() / law.wallAccumSec() / law.mult()) : "warmup";
        String target = (law.wallAccumSec() >= ClockServoLaw.SERVO_MIN_WINDOW_SEC && law.pacerAccumSec() > 0d)
                ? fmt2(law.wallAccumSec() / law.pacerAccumSec()) : "warmup";
        String cumulative = wallSec > 0.001d ? fmt2(plugin.clientSimTimeSec / wallSec) : "n/a";
        double lockWallSec = lockWallStartNanos != Long.MIN_VALUE
                ? (System.nanoTime() - lockWallStartNanos) / 1_000_000_000.0d : 0d;
        String cLock = lockWallSec > 0.5d ? fmt2((plugin.clientSimTimeSec - lockClientSimStart) / lockWallSec) : "n/a";
        PilotWatchLog.info("predict clock servo-trace [t=" + fmt2(wallSec) + "s]: fps=" + lastTraceFps
                + " raw=" + raw + " corrected=" + fmt2(traceAmountSec / traceWallSec)
                + " now=" + servoNowRate() + " [" + nowSpan + "]" + " nowEx=" + nowEx
                + " target=" + target + " servo=" + servoState()
                + " outliers=" + traceOutlierFrames + "/" + traceFrames
                + " C=" + cumulative + " C_lock=" + cLock
                + (law.simSpeed() != 1f ? " speed=" + fmt2(law.simSpeed()) : "")
                + (!Float.isNaN(combatSpeedMultSetting) && combatSpeedMultSetting != 1f
                        ? " S=" + fmt2(combatSpeedMultSetting) : ""));
        traceFrames = 0L;
        traceWallSec = 0d;
        traceAmountSec = 0d;
        tracePacerSec = 0d;
        tracePacerWallSec = 0d;
        traceOutlierFrames = 0L;
        traceNowMin = Double.POSITIVE_INFINITY;
        traceNowMax = Double.NEGATIVE_INFINITY;
    }

    /** Drop the servo modifier and re-neutralize (idempotent; teardown hygiene). */
    void removeClockServo() {
        // One servo-rollup line per combat (latched — remove is idempotent).
        if (!multStatLogged && multStatWallSec > 0.5d) {
            multStatLogged = true;
            PilotWatchLog.info("predict clock servo-rollup: mult mean=" + fmt2(multStatWeighted
                    / multStatWallSec) + " min=" + fmt2(multStatMin) + " max=" + fmt2(multStatMax)
                    + " share>1.05=" + fmt2(100d * multStatFastSec / multStatWallSec)
                    + "% share<0.95=" + fmt2(100d * multStatSlowSec / multStatWallSec)
                    + "% wall=" + fmt2(multStatWallSec) + "s");
        }
        try {
            if (plugin.engine != null && plugin.engine.getTimeMult() != null) {
                plugin.engine.getTimeMult().unmodify(SERVO_MOD_ID);
            }
        } catch (Throwable ignore) {
            // teardown is best-effort
        }
        law.neutralize();
    }

    /** The instantaneous (servo-window) corrected clock rate as a display string, {@code …}
     *  during warmup. The cumulative HUD {@code C} value carries the warmup + spawn-hitch seconds forever
     *  (the owner's fps:540 verify read 0.98–0.99 from that drag alone), so this shows what the corrected
     *  clock is doing NOW — the number a verify run should read (expect ≈1.00). */
    String servoNowRate() {
        if (law.wallAccumSec() < ClockServoLaw.SERVO_MIN_WINDOW_SEC) {
            return "…";
        }
        return fmt2(law.corrAccumSec() / law.wallAccumSec());
    }

    /** STEP-9 v3 — the last completed trace interval's fps for the HUD sim-rate lines ({@code …}
     *  until the first interval fills). The owner's verify-2 observations all tracked framerate (cap
     *  underrun to ~400, overshoot on fps spikes), so the HUD now carries the number beside the rates. */
    String fpsState() {
        return lastTraceFps >= 0 ? Integer.toString(lastTraceFps) : "…";
    }

    /** The census window's trace-interval fps as {@code mean/min} ("-" before the
     *  first interval), and reset the window (render thread; one caller per census line). */
    String fpsWindowTake() {
        String out = fpsWindowPeek();
        fpsWinSum = 0L;
        fpsWinCount = 0;
        fpsWinMin = Integer.MAX_VALUE;
        return out;
    }

    /** The window WITHOUT the reset — safe to read from the net-thread
     *  backstops (a racy diagnostic read at worst; the drain stays render-thread-only). */
    String fpsWindowPeek() {
        int count = fpsWinCount;
        return count == 0 ? "-" : (fpsWinSum / count) + "/" + fpsWinMin;
    }

    /** Short servo state for the HUD/log lines: {@code x1.19}, {@code warmup}, or {@code off}. */
    String servoState() {
        if (!SERVO_ENABLED || plugin.vanillaWindow) {
            return "off"; // vanilla: window — servo silenced; init logged the why
        }
        if (law.wallAccumSec() < ClockServoLaw.SERVO_MIN_WINDOW_SEC) {
            return "warmup";
        }
        return "x" + fmt2(law.mult());
    }

    /** I.9: the {@code combatSpeedMult} settings scalar, read via the public settings API; {@code 1} on any
     *  read failure (the engine's own default). */
    private static float readCombatSpeedMult() {
        try {
            return com.fs.starfarer.api.Global.getSettings().getFloat("combatSpeedMult");
        } catch (Throwable ignore) {
            return 1f;
        }
    }

    /** Two-decimal format, ROOT locale (grep-stable) — own copy per the FoeFacingLane lane idiom. */
    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
