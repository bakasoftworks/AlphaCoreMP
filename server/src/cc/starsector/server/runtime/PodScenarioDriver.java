package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code /podtest} scenario driver — a tick-stepped state machine that
 * stages ONE command-shuttle ("pod") scenario for a seat, server-side, through the engine's own
 * existing paths (spawn a friendly target, begin the transfer, kill the right hull at the right
 * moment, re-pick on cue) and grades the outcome from the pod lane's settled state.
 *
 * <p>WHY: the pod scenarios (a target
 * dying en route, own ship dying first, a re-pick mid-return) are hard to stage by hand in a live
 * match; they are never again a manual staging step for the operator. This driver
 * serves BOTH callers with one command: a human tester ({@code /podtest f1} in their window = their
 * seat; they only watch) and an unattended run ({@code pod-test f1 1} on the ops file, seat-1 local
 * window + observer window rendering, frames on the HDD, graded from the frames + the
 * {@code podtest_result} line this driver prints).
 *
 * <p>Sim-thread only, pumped once per tick by the serve loop right AFTER the transfer lane's pump
 * (so every read sees that tick's settled lane state; every mutation lands before the advance).
 * The driver never touches the pod lane's mutators — it seeds the arbiter row through
 * {@link Port#beginOrDeny} and lets the serve lane's drain mint/fly/commit exactly as a chat
 * {@code /transfer} would. One scenario at a time; a second start while one runs is refused.
 *
 * <p>Grading law: the lane's transient phases (DOCKED, RETURNED) are consumed the same tick they
 * are set, so the verdict keys on what PERSISTS — the seat's live body identity ({@link
 * Port#seatBodyStableOrNull}: the pod during a flight, the pod BODY after a strand, the target
 * after a commit, the source after a dock-back), the lane's {@code hasFlight/hasPod} pair (FLYING
 * = both, RETURNING = flight without pod) and the pod-spawn counter (a pod-body resume must spawn
 * NONE). The stdout rows ({@code podtest step=…}, {@code podtest_result …}) are the server-side
 * record; the frames grade the visuals.
 */
public final class PodScenarioDriver {

    /** The fixed scenario set (grammar + file verb validate against THIS list). */
    // Emp/empv added for the EMP-arc isolation rig.
    public static final List<String> TOKENS =
            List.of("f1", "f2", "return", "repick", "emp", "empv");

    /** The chat start's refusal when the SENDER no longer
     * holds the seat at apply time (mailed to the sender: chat text law; keeps the {@code refused} token). */
    public static final String CHAT_START_REFUSED_NOT_BOUND =
            "podtest: refused - your session no longer holds that seat";
    /** The FAIL details of the two external aborts — the
     * scenario's seat TRUE-vacated mid-run ({@link Port#seatVacatedThisPump}) and the round re-mint
     * ({@link #roundEnded}). Constants only: no player text reaches the verdict row or the receipt. */
    public static final String SEAT_VACATED = "seat vacated";
    public static final String ROUND_ENDED = "round ended";

    public enum Scenario {
        /** Both dead en route ⇒ STRAND (pilotable pod body) ⇒ hold ⇒ re-pick resumes the body
         * (zero spawns) ⇒ commit onto the second target. */
        F1,
        /** From a wreck: own hull killed FIRST, transfer from the hulk, target dies en route ⇒
         * STRAND ⇒ resume as f1. */
        F2,
        /** Target dies en route ⇒ the pod RETURNS and docks back onto the source. */
        RETURN,
        /** As return, then a re-pick mid-return swings the pod onto a second target. */
        REPICK,
        /** EMP-arc isolation rig — an {@code omen_PD}
         * FOE (the PROVEN own-disable source, flight mark #5) held ~400 su facing the seat ~18 s.
         * The driver has no EMP-landing read, so PASS just means "foe stayed pinned"; the real
         * grade is the seat sidelog ({@code predict v4 weapon-state APPLY disable}, logged by
         * {@code FoeStateLaneCoordinator}) + the filmstrip. */
        EMP,
        /** As {@code emp} but FOE =
         * {@code skirmish_unit_Type100} (threat voltaic_discharge, range 500) — the UNPROVEN EMP
         * source; a zero own-disable sidelog IS the probe answer. */
        EMPV;

        /** The FOE variant an EMP-rig scenario spawns,
         * null for the pod scenarios (F1/F2/RETURN/REPICK). Bare variant ids — both resolve from
         * the loaded engine registry (starsector-core: omen_PD.variant, threat/
         * skirmish_unit_Type100.variant), verified via {@code RealEngineDemoRoster.variantResolvable}. */
        String empFoeVariantOrNull() {
            return switch (this) {
                case EMP -> "omen_PD";
                case EMPV -> "skirmish_unit_Type100";
                default -> null;
            };
        }

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Case-folded token → scenario, null for anything outside {@link #TOKENS}. */
        public static Scenario parseOrNull(String token) {
            if (token == null) {
                return null;
            }
            String folded = token.trim().toLowerCase(Locale.ROOT);
            for (Scenario s : values()) {
                if (s.token().equals(folded)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * The engine seam (live impl {@link PodScenarioEnginePort}; contract rows bind a fake). Every
     * method is total — a fault answers false/0/null/NaN and the driver grades it as a FAIL step,
     * never a throw into the serve loop.
     */
    public interface Port {
        /** The seat is occupied and has a live body (alive hull, pod, or a hulk in play). */
        boolean seatReady(int seatId);
        /** Stable id of the seat's CURRENT live body (pod / hull / pod body), null = none. */
        String seatBodyStableOrNull(int seatId);
        /** The seat's current body is a stranded pod BODY (own-hull-IS-pod). */
        boolean seatBodyIsPod(int seatId);
        /** The seat's current body reads engine-alive (not a hulk). */
        boolean seatBodyAlive(int seatId);
        /** Spawn a friendly target at rest near the seat's body; its wire id, 0 = failed. */
        long spawnTargetOrZero(int seatId, String label, long tick);
        /** Spawn a HOSTILE emp foe (side = 1 − seatSide)
         * ~400 su from the seat body, facing it (180°), pinned at rest; its wire id, 0 = failed. */
        long spawnEmpFoeOrZero(int seatId, String variantId, String label, long tick);
        /** The target (by wire id) resolves on the live ship list and reads alive. */
        boolean targetAlive(long targetWireId);
        /** Pin the (now-resolved) target in place; false = not resolvable yet. */
        boolean holdTarget(long targetWireId);
        /** Arbiter begin for the seat toward the target; null = granted, else the deny line. */
        String beginOrDeny(int seatId, long targetWireId, long tick);
        /** Kill the target through the engine's normal death path. */
        boolean killTarget(long targetWireId);
        /** Kill the HOME body of the seat's live flight (the source hull) the same way. */
        boolean killFlightSource(int seatId);
        /** Kill the seat's current live body (pre-flight; the {@code /suicide} path). */
        boolean killSeatBody(int seatId);
        boolean hasFlight(int seatId);
        boolean hasPod(int seatId);
        /** Pod → target distance (su), NaN when either is unresolvable. */
        float podDistanceOrNaN(int seatId, long targetWireId);
        /** Cumulative fresh pod spawns this round (resume must not move it). */
        int podSpawns();
        String stableForWireOrNull(long wireId);
        /** {@code seatId} was TRUE-vacated (silence / BYE /
         * session death — the kick and ban funnel) in THIS tick's transfer-lane pump; a same-client-id
         * takeover (DISPLACED) is never recorded. Answers from the list that pump already took (no door lock). */
        boolean seatVacatedThisPump(int seatId);
    }

    /** One step: {@code enter} runs once at entry, {@code poll} every tick (true = done);
     * exceeding {@code timeoutTicks} fails the scenario with the step's name. */
    private abstract static class Step {
        final String name;
        final long timeoutTicks;
        long enteredTick = -1L;

        Step(String name, long timeoutTicks) {
            this.name = name;
            this.timeoutTicks = timeoutTicks;
        }

        /** Return a failure reason to fail immediately, null to continue. */
        String enter(long tick) {
            return null;
        }

        /** True when the step is satisfied; a failure reason via {@link #fail}. */
        abstract boolean poll(long tick);

        String failReason;

        boolean fail(String reason) {
            failReason = reason;
            return false;
        }
    }

    private final Port port;
    private final int round;
    private final long tps;

    private Scenario scenario;
    private int seatId;
    private long startTick;
    private List<Step> steps = List.of();
    private int stepIndex;
    private String verdict;
    private OperatorCommandFile.Command receiptTarget; // the verdict's receipt target: see receiptTargetOrNull

    // Scenario-scoped facts (reset at start).
    private long target1;
    private long target2;
    private long empFoe; // The hostile foe's wire id
    private String sourceStable;
    private float startDistance = Float.NaN;
    private int spawnsBeforeResume;
    private String lastDeny;
    private int targetsSpawned;

    /** {@code ticksPerSecond} sizes every deadline/hold (the serve's fixed step). */
    public PodScenarioDriver(Port port, int round, long ticksPerSecond) {
        this.port = Objects.requireNonNull(port, "port");
        this.round = round;
        this.tps = Math.max(1L, ticksPerSecond);
    }

    public boolean active() {
        return scenario != null && verdict == null;
    }

    public Scenario activeScenarioOrNull() {
        return active() ? scenario : null;
    }

    public int activeSeatId() {
        return seatId;
    }

    /**
     * The serve's ONE {@code POD_TEST} entry (the
     * operator switch in {@code PilotServeSession}, sim thread): chat {@code /podtest} = the caller's own
     * seat, {@code receiptSessionId} = the sender (signed, != 0); the house file
     * {@code pod-test <scenario> <seat>} = a bounded seat int, receipt 0. A chat start asks
     * {@code senderHoldsSeat} FIRST with exactly (receipt, seat): a kick / timeout / takeover landing between
     * the door's check and this drain refuses with {@link #CHAT_START_REFUSED_NOT_BOUND}, touching no driver
     * state and calling no port method. Receipt 0 never asks it (the file start, unchanged). The returned
     * start receipt mails now; the verdict mails later from the serve's pump to {@link #receiptTargetOrNull},
     * which ONLY a started start re-targets (F3: a refused start — already running, unknown token, no live
     * ship, stale sender — never re-targets the running scenario's verdict). A throwing predicate propagates
     * before any state is touched (the serve's operator fault belt counts it).
     */
    public String start(OperatorCommandFile.Command command, long tick,
            AdminSeatDebugLane.SessionSeatPredicate senderHoldsSeat) {
        long receipt = command.receiptSessionId();
        if (receipt != 0L && !senderHoldsSeat.holds(receipt, command.seatId())) {
            return CHAT_START_REFUSED_NOT_BOUND;
        }
        return startFor(command.seatId(), command.text(), tick, command);
    }

    /**
     * Start a scenario for a seat with NO receipt target (= receipt 0: never sender-gated, no verdict
     * mail) — the contract rows' seam; the serve enters through
     * {@link #start(OperatorCommandFile.Command, long, AdminSeatDebugLane.SessionSeatPredicate)}. Refuses
     * (never throws) while one runs, for an unknown token, or for a seat with no live body.
     */
    public String start(int seat, String token, long tick) {
        return startFor(seat, token, tick, null);
    }

    /** The command that STARTED the running — or the most
     * recently ended — scenario, i.e. the verdict's receipt target (it replaced the serve's own
     * {@code podTestCommand} field); null before any started start, or after a started 3-arg start. */
    public OperatorCommandFile.Command receiptTargetOrNull() {
        return receiptTarget;
    }

    /**
     * The serve's round re-mint hook ({@code mintRound}, run
     * BEFORE this round's driver is replaced): a scenario still RUNNING settles FAIL {@link #ROUND_ENDED} at
     * {@code tick} (the old round's last sim tick) — its {@code podtest_result} row plus the returned receipt,
     * which the serve mails to {@link #receiptTargetOrNull}. Idle ⇒ null (no verdict invented). Calls no port
     * method. A scenario still running when the SERVE ends is not settled here (no caller at serve end).
     */
    public String roundEnded(long tick) {
        return active() ? finish(false, activeStepName(), ROUND_ENDED, tick) : null;
    }

    private String activeStepName() {
        return stepIndex < steps.size() ? steps.get(stepIndex).name : "start";
    }

    private String startFor(int seat, String token, long tick, OperatorCommandFile.Command target) {
        Scenario s = Scenario.parseOrNull(token);
        if (s == null) {
            return "podtest: unknown scenario (" + String.join("|", TOKENS) + ")";
        }
        if (active()) {
            return "podtest: " + scenario.token() + " already running for seat " + seatId;
        }
        if (seat <= 0 || !port.seatReady(seat)) {
            return "podtest: seat " + seat + " has no live ship";
        }
        scenario = s;
        receiptTarget = target; // F3: reached only by a STARTED start
        seatId = seat;
        startTick = tick;
        verdict = null;
        target1 = 0L;
        target2 = 0L;
        empFoe = 0L; //
        sourceStable = null;
        startDistance = Float.NaN;
        spawnsBeforeResume = 0;
        lastDeny = null;
        targetsSpawned = 0;
        steps = buildSteps(s);
        stepIndex = 0;
        log("podtest scenario=" + s.token() + " seat=" + seat + " step=start tick=" + tick
                + " steps=" + steps.size());
        return "podtest: " + s.token() + " started for seat " + seat + " (" + steps.size()
                + " steps; watch the pod, verdict follows)";
    }

    /**
     * Once per tick, after the transfer lane's pump. Returns the verdict line the tick the
     * scenario ends (the serve loop mails it to {@link #receiptTargetOrNull}), else null.
     */
    public String pump(long tick) {
        if (!active()) {
            return null;
        }
        // The scenario's seat TRUE-vacated in this tick's
        // transfer pump (BYE / silence / kick) ends the run FAIL before any step reads a gone seat.
        if (port.seatVacatedThisPump(seatId)) {
            return finish(false, activeStepName(), SEAT_VACATED, tick);
        }
        while (stepIndex < steps.size()) {
            Step step = steps.get(stepIndex);
            if (step.enteredTick < 0L) {
                step.enteredTick = tick;
                log("podtest scenario=" + scenario.token() + " seat=" + seatId + " step="
                        + step.name + " tick=" + tick);
                // r8: enter under the poll catch's belt (a
                // throwing enter left pump every frame outside any catch); SimpleName only, both
                // arms — never a message into the verdict row / receipt.
                String enterFail;
                try {
                    enterFail = step.enter(tick);
                } catch (RuntimeException fault) {
                    return finish(false, step.name, "threw " + fault.getClass().getSimpleName(), tick);
                }
                if (enterFail != null) {
                    return finish(false, step.name, enterFail, tick);
                }
            }
            boolean done;
            try {
                done = step.poll(tick);
            } catch (RuntimeException fault) {
                return finish(false, step.name, "threw " + fault.getClass().getSimpleName(), tick);
            }
            if (step.failReason != null) {
                return finish(false, step.name, step.failReason, tick);
            }
            if (!done) {
                if (tick - step.enteredTick > step.timeoutTicks) {
                    return finish(false, step.name, "timeout after " + step.timeoutTicks
                            + " ticks" + (lastDeny == null ? "" : " last_deny=\"" + lastDeny + "\""),
                            tick);
                }
                return null;
            }
            stepIndex++;
        }
        return finish(true, "done", "all " + steps.size() + " steps", tick);
    }

    private String finish(boolean pass, String step, String detail, long tick) {
        String line = "podtest_result scenario=" + scenario.token() + " seat=" + seatId
                + " verdict=" + (pass ? "PASS" : "FAIL") + " step=" + step
                + " detail=\"" + detail + "\" targets=" + targetsSpawned
                + " elapsed_ticks=" + (tick - startTick) + " round=" + round + " tick=" + tick;
        log(line);
        verdict = line;
        String receipt = "podtest " + scenario.token() + " seat " + seatId + ": "
                + (pass ? "PASS" : "FAIL at " + step + " - " + detail);
        scenario = null;
        return receipt;
    }

    private void log(String line) {
        System.out.println("npeer-serve " + line + (line.startsWith("podtest_result")
                ? "" : " round=" + round));
    }

    private long secs(double seconds) {
        return Math.max(1L, Math.round(seconds * tps));
    }

    // ------------------------------------------------------------------ scenario programs

    private List<Step> buildSteps(Scenario s) {
        List<Step> list = new ArrayList<>();
        switch (s) {
            case F1 -> {
                list.add(spawnTarget(1));
                list.add(begin(1, false));
                list.add(enRoute(1));
                list.add(killBoth());
                list.add(strandCheck());
                list.add(hold());
                list.add(spawnTarget(2));
                list.add(begin(2, true));
                list.add(commitOnto(2));
            }
            case F2 -> {
                list.add(killSeatFirst());
                list.add(settle());
                list.add(spawnTarget(1));
                list.add(begin(1, false));
                list.add(enRoute(1));
                list.add(killTargetExpectStrand());
                list.add(strandCheck());
                list.add(hold());
                list.add(spawnTarget(2));
                list.add(begin(2, true));
                list.add(commitOnto(2));
            }
            case RETURN -> {
                list.add(spawnTarget(1));
                list.add(begin(1, false));
                list.add(enRoute(1));
                list.add(killTargetExpectReturning());
                list.add(dockBack());
            }
            case REPICK -> {
                list.add(spawnTarget(1));
                list.add(begin(1, false));
                list.add(enRoute(1));
                list.add(killTargetExpectReturning());
                list.add(wait("repick_delay", secs(20.0 / 60.0)));
                list.add(spawnTarget(2));
                list.add(begin(2, false));
                list.add(commitOnto(2));
            }
            // Spawn the hostile foe -> settle -> dwell.
            case EMP, EMPV -> {
                list.add(spawnEmpFoe(s.empFoeVariantOrNull()));
                list.add(holdEmpFoe());
                list.add(dwellEmp(secs(18.0)));
            }
            default -> throw new IllegalStateException("unhandled scenario " + s);
        }
        return list;
    }

    private long targetWire(int which) {
        return which == 1 ? target1 : target2;
    }

    /** Spawn target N at rest near the seat; done when it resolves alive (pending-add lands
     * on the next advance), pinning it in place at that moment. */
    private Step spawnTarget(int which) {
        return new Step("spawn_target" + which, secs(3)) {
            @Override
            String enter(long tick) {
                long wire = port.spawnTargetOrZero(seatId, "AlphaCoreMP PODTEST T" + which, tick); // W11 public name
                if (wire <= 0L) {
                    return "spawn failed";
                }
                targetsSpawned++;
                if (which == 1) {
                    target1 = wire;
                } else {
                    target2 = wire;
                }
                log("podtest scenario=" + scenario.token() + " seat=" + seatId
                        + " spawned target" + which + " wire=" + wire + " tick=" + tick);
                return null;
            }

            @Override
            boolean poll(long tick) {
                long wire = targetWire(which);
                if (!port.targetAlive(wire)) {
                    return false;
                }
                port.holdTarget(wire);
                return true;
            }
        };
    }

    // The 3-step EMP-arc isolation program, modelled on
    // spawnTarget/hold/wait above. The driver never grades the EMP landing (no port read exists) —
    // PASS = "foe stayed pinned"; the real grade is the seat sidelog (FoeStateLaneCoordinator's
    // "predict v4 weapon-state APPLY disable") + the filmstrip.

    /** Spawn the hostile foe at rest facing the seat;
     * done when it resolves alive (pending-add lands next advance), pinning it at that moment
     * (mirror of {@link #spawnTarget}). */
    private Step spawnEmpFoe(String variant) {
        return new Step("spawn_emp_foe", secs(3)) {
            @Override
            String enter(long tick) {
                long wire = port.spawnEmpFoeOrZero(seatId, variant, "AlphaCoreMP PODTEST EMP", tick); // W11
                if (wire <= 0L) {
                    return "emp foe spawn failed";
                }
                empFoe = wire;
                targetsSpawned++;
                log("podtest scenario=" + scenario.token() + " seat=" + seatId
                        + " spawned emp_foe wire=" + wire + " variant=" + variant + " tick=" + tick);
                return null;
            }

            @Override
            boolean poll(long tick) {
                if (!port.targetAlive(empFoe)) {
                    return false;
                }
                port.holdTarget(empFoe);
                return true;
            }
        };
    }

    /** One settle beat — confirm the foe is pinned (a
     * live-AI hostile drifts otherwise; the recon's per-tick holdStill note). */
    private Step holdEmpFoe() {
        return new Step("emp_foe_hold", secs(3)) {
            @Override
            boolean poll(long tick) {
                return port.holdTarget(empFoe);
            }
        };
    }

    /** The measured window — re-pin every tick for the
     * dwell (~18 s) while the foe autofires on the seat. PASS iff the foe stayed resolvable; the
     * EMP verdict itself lives in the seat sidelog/frames, not here (mirror of {@link #wait}). */
    private Step dwellEmp(long ticks) {
        return new Step("emp_dwell", ticks + 1) {
            @Override
            boolean poll(long tick) {
                if (!port.targetAlive(empFoe)) {
                    return fail("emp foe died/despawned during dwell");
                }
                port.holdTarget(empFoe);
                return tick - enteredTick >= ticks;
            }
        };
    }

    /** Begin toward target N, retrying a denied begin every tick (the fresh-begin floor); done
     * when the seat has a pod (fresh spawn, retarget or resume). {@code resume} pins the
     * zero-spawn law: a pod-body resume must leave the spawn counter untouched. */
    private Step begin(int which, boolean resume) {
        return new Step("begin_target" + which + (resume ? "_resume" : ""), secs(12)) {
            boolean granted;

            @Override
            String enter(long tick) {
                if (which == 1) {
                    sourceStable = port.seatBodyStableOrNull(seatId);
                }
                spawnsBeforeResume = port.podSpawns();
                return null;
            }

            @Override
            boolean poll(long tick) {
                if (!granted) {
                    String deny = port.beginOrDeny(seatId, targetWire(which), tick);
                    if (deny != null) {
                        // Each DISTINCT deny reason is logged ONCE as it
                        // appears (a 155-tick retry loop was opaque on the real-engine
                        // repick); the last one still rides the timeout FAIL row.
                        if (!deny.equals(lastDeny)) {
                            log("podtest scenario=" + scenario.token() + " seat=" + seatId
                                    + " begin denied target" + which + " wire="
                                    + targetWire(which) + " reason=\"" + deny + "\" tick="
                                    + tick);
                        }
                        lastDeny = deny;
                        return false;
                    }
                    granted = true;
                    log("podtest scenario=" + scenario.token() + " seat=" + seatId
                            + " begin granted target" + which + " wire=" + targetWire(which)
                            + " tick=" + tick);
                    return false; // the drain mints next pump
                }
                if (!port.hasPod(seatId)) {
                    return false;
                }
                if (resume && port.podSpawns() != spawnsBeforeResume) {
                    return fail("resume spawned a pod (spawns " + spawnsBeforeResume + " -> "
                            + port.podSpawns() + ")");
                }
                startDistance = port.podDistanceOrNaN(seatId, targetWire(which));
                return true;
            }
        };
    }

    /** Done when the pod has closed to under 60 % of its start distance. */
    private Step enRoute(int which) {
        return new Step("en_route" + which, secs(60)) {
            @Override
            boolean poll(long tick) {
                if (!port.hasPod(seatId)) {
                    return fail("flight ended before en route");
                }
                float d = port.podDistanceOrNaN(seatId, targetWire(which));
                if (Float.isNaN(d)) {
                    return false;
                }
                if (Float.isNaN(startDistance) || startDistance <= 0f) {
                    startDistance = d;
                    return false;
                }
                return d < startDistance * 0.6f;
            }
        };
    }

    /** f1: kill the target AND the flight's home body the same tick; done when the flight
     * ends (the lane strands it on its next pump). */
    private Step killBoth() {
        return new Step("kill_target_and_source", secs(6)) {
            @Override
            String enter(long tick) {
                boolean src = port.killFlightSource(seatId);
                boolean tgt = port.killTarget(target1);
                if (!src || !tgt) {
                    return "kill failed source=" + src + " target=" + tgt;
                }
                return null;
            }

            @Override
            boolean poll(long tick) {
                return !port.hasFlight(seatId);
            }
        };
    }

    /** f2: kill the target only — the home is already a hulk, so the lane strands. */
    private Step killTargetExpectStrand() {
        return new Step("kill_target_strand", secs(6)) {
            @Override
            String enter(long tick) {
                return port.killTarget(target1) ? null : "kill target failed";
            }

            @Override
            boolean poll(long tick) {
                return !port.hasFlight(seatId);
            }
        };
    }

    /** return/repick: kill the target only; done when the lane reads RETURNING (flight, no
     * pod) — a strand or an instant end here is the defect the scenario exists to catch. */
    private Step killTargetExpectReturning() {
        return new Step("kill_target_returning", secs(6)) {
            @Override
            String enter(long tick) {
                return port.killTarget(target1) ? null : "kill target failed";
            }

            @Override
            boolean poll(long tick) {
                if (!port.hasFlight(seatId)) {
                    return fail("flight ended instead of returning (seat body pod="
                            + port.seatBodyIsPod(seatId) + ")");
                }
                return !port.hasPod(seatId);
            }
        };
    }

    /** After a strand the seat's body must BE the pilotable pod body. */
    private Step strandCheck() {
        return new Step("strand_check", 1L) {
            @Override
            boolean poll(long tick) {
                if (port.hasFlight(seatId)) {
                    return fail("flight still live after the kill");
                }
                if (!port.seatBodyIsPod(seatId)) {
                    return fail("seat body is not a pod body (stable="
                            + port.seatBodyStableOrNull(seatId) + ")");
                }
                return true;
            }
        };
    }

    /** The visible stop: the stranded body holds for ~3 s (filmstrip + owner see it). */
    private Step hold() {
        return wait("strand_hold", secs(3));
    }

    /** Let the death fan + hulk settle before staging from the wreck. */
    private Step settle() {
        return wait("wreck_settle", secs(1));
    }

    private Step wait(String name, long ticks) {
        return new Step(name, ticks + 1) {
            @Override
            boolean poll(long tick) {
                return tick - enteredTick >= ticks;
            }
        };
    }

    /** f2: the seat's own hull dies first (the /suicide path); done when it reads dead. */
    private Step killSeatFirst() {
        return new Step("kill_seat_first", secs(3)) {
            @Override
            String enter(long tick) {
                return port.killSeatBody(seatId) ? null : "seat kill failed";
            }

            @Override
            boolean poll(long tick) {
                return !port.seatBodyAlive(seatId);
            }
        };
    }

    /** Done when the flight is gone AND the seat's body is target N (commit), never a pod. */
    private Step commitOnto(int which) {
        return new Step("commit_target" + which, secs(60)) {
            @Override
            boolean poll(long tick) {
                if (port.hasFlight(seatId)) {
                    return false;
                }
                String body = port.seatBodyStableOrNull(seatId);
                String expect = port.stableForWireOrNull(targetWire(which));
                if (expect != null && expect.equals(body) && !port.seatBodyIsPod(seatId)) {
                    return true;
                }
                return fail("expected seat on target" + which + " (" + expect + ") got body="
                        + body + " pod=" + port.seatBodyIsPod(seatId));
            }
        };
    }

    /** return: the flight ends by docking back — the seat's body is the source again. */
    private Step dockBack() {
        return new Step("dock_back", secs(60)) {
            @Override
            boolean poll(long tick) {
                if (port.hasFlight(seatId)) {
                    return false;
                }
                String body = port.seatBodyStableOrNull(seatId);
                if (sourceStable != null && sourceStable.equals(body)
                        && !port.seatBodyIsPod(seatId)) {
                    return true;
                }
                return fail("expected dock-back onto " + sourceStable + " got body=" + body
                        + " pod=" + port.seatBodyIsPod(seatId));
            }
        };
    }
}
