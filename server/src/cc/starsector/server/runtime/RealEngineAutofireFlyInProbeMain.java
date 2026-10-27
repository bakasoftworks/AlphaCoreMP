package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.PilotApplyState;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineDemoRoster;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Headless REPRO probe for the autofire fly-in
 * break seen in a live test match — server autofire mounts went permanently silent after foe fly-in #4 while the manual
 * lane kept pairing. The engine's autofire acquisition law
 * gates its scan and its keep law on:
 * owner mismatch, {@code isTargetable()}, fog-of-war {@code isVisible(target)}, {@code !isHulk},
 * {@code owner != 100}, AI-grid reachability ({@code getAiGridShips().getCheckIterator}), plus
 * keep-only {@code wasRemoved()} / {@code isExpired()}. This probe meters ALL of them per frame
 * against scripted foe kill&rarr;vacate&rarr;re-mint cycles through the PRODUCT fly-in lane
 * ({@link RealEngineDemoRoster#vacateSeatHull} / {@link RealEngineDemoRoster#midDeploySeat} — the
 * same calls the serve loop's seat machine drives), game-free under the L3 llvmpipe substrate.
 *
 * <p><b>Level-A scope:</b> no socket, no clients, no wire registry —
 * a repro here pins the break to the roster/engine re-mint path; a clean run pins it to the
 * serve/wire layer or client-coupled state (the next step then being a registry-site meter
 * on the next live test match). Cadence knobs discriminate re-mint-COUNT onset from TIME onset (~tick 7265):
 * fast cycles put fly-in #4 well before 7265; {@code --cycles 0 --tail-frames N} is the no-kill
 * time-onset control.
 *
 * <p><b>Rig fidelity choices:</b> seat AI suppressed per-frame via the product
 * {@link EngineCombatBinding#idleOwnedShipAi} (the serve loop's own suppression, NOT an AI strip);
 * Onslaught autofire groups stay deploy-default ON; foe groups toggled OFF (survivability only —
 * acquisition OF the foe needs no foe fire); flux auto-vented at 70% through the engine's own
 * {@code ventFlux()} so discharges don't stall on a pilotless flux wall; ONE setup-time
 * reposition parks the Onslaught at a fixed standoff off the foe's fly-in landing spot BEFORE the
 * first measured window (every cycle then shares identical in-range geometry; nothing is moved
 * mid-measurement). Foe kills are aimed engine {@code applyDamage} hits (deck-damage idiom,
 * cosmetic-FX NPE swallowed); hulks are deliberately left in-world — the live match that broke fought over a 4-hulk
 * pile.
 *
 * <p>Exit codes: 0 = ran to completion with baseline acquisition (verdict names REPRODUCED or
 * ALL_RECOVERED — both are findings); 2 = boundary thrown (fly-in/step/kill threw — stack
 * printed); 3 = INCONCLUSIVE_BASELINE (geometry never yielded a baseline acquisition; fix the
 * rig, don't read the cycles); 1 = infrastructure failure.
 *
 *
 * <p><b>Edge-proof mode</b> ({@code --edge-proof 1}): skips the foe/kill cycles
 * entirely and instead proves the engine's rising-edge TOGGLE_AUTOFIRE law + the server mirror's
 * click fix against the real engine — see {@link #runEdgeProof}.
 *
 */
public final class RealEngineAutofireFlyInProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineZeroHullProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String DAMAGE_TYPE = "com.fs.starfarer.api.combat.DamageType";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    private static final String PROJECTILE_CLASS = "com.fs.starfarer.combat.entities.BallisticProjectile";

    private static final String P = "af-flyin-probe";

    private RealEngineAutofireFlyInProbeMain() {
    }

    /** Reflective rig — every engine read the meter makes, resolved once. */
    private static final class Rig {
        Object engine;
        Class<?> shipClass;
        Method repoGetList;
        Method getObjects;
        Method isShipAlive;
        Method getFogOfWar;
        Method getAiGridShips;
        Method applyDamage;
        Object damageTypeHe;
        Method shipGetLocation;
        Method shipGetVelocity;
        Method shipGetOwner;
        Method shipGetId;
        Method shipIsHulk;
        Method shipIsTargetable;
        Method shipWasRemoved;
        Method shipIsExpired;          // nullable: keep-law input, resolve-optional
        Method shipGetCollisionRadius;
        Method shipGetWeaponGroupsCopy;
        Method shipGetAllWeapons;
        Method shipGetFluxTracker;     // nullable trio: vent rig is best-effort
        Method fluxGetLevel;
        Method fluxIsVenting;
        Method fluxVent;
        Method shipSetFacing;
        Method shipGetHullLevel;
        Field vecX;
        Field vecY;
        java.lang.reflect.Constructor<?> vec2Ctor;
        Class<?> projClass;            // nullable: discharge proxy is best-effort
        // Lazily resolved off the first live instance (engine impl classes):
        Method groupIsAutofiring;
        Method groupGetAiPlugins;
        Method groupToggleOn;
        Method groupToggleOff;
        Method pluginGetTargetShip;
        Method pluginShouldFire;
        Method fogIsVisible;
        Method gridCheckIterator;
    }

    /** One metering window's aggregate (a settle window, a cooldown, or a post-re-mint window). */
    private static final class Agg {
        int frames;
        int framesTgtFoe;
        int firstTgtFoeOffset = -1;
        int framesShouldFire;
        int framesTargetable;
        int framesFogVisible;
        int framesFogNull;
        int framesGridAtFoe;
        int framesGridFromSrv;
        int framesInRange;
        int framesHulk;
        int framesRemoved;
        float minDist = Float.MAX_VALUE;
        int maxTgtFoe;
        int maxProj;

        String summary() {
            return "frames=" + frames
                    + " tgt_foe_frames=" + framesTgtFoe
                    + " first_tgt_foe_offset=" + firstTgtFoeOffset
                    + " max_tgt_foe=" + maxTgtFoe
                    + " should_fire_frames=" + framesShouldFire
                    + " targetable_frames=" + framesTargetable
                    + " fog_visible_frames=" + framesFogVisible
                    + " fog_null_frames=" + framesFogNull
                    + " grid_at_foe_frames=" + framesGridAtFoe
                    + " grid_from_srv_frames=" + framesGridFromSrv
                    + " in_range_frames=" + framesInRange
                    + " hulk_frames=" + framesHulk
                    + " removed_frames=" + framesRemoved
                    + " min_dist=" + f1(minDist == Float.MAX_VALUE ? -1f : minDist)
                    + " max_proj=" + maxProj;
        }
    }

    public static void main(String[] args) throws Exception {
        int cycles = 6;
        int settleFrames = 600;    // post-re-mint measured window (burn ~240f + settle)
        int cooldownFrames = 240;  // vacate -> re-mint gap (the seat machine's respawn cooldown stand-in)
        int tailFrames = 0;        // >0 with --cycles 0 = the no-kill time-onset control
        int killCapFrames = 900;
        float killDamage = 2500f;
        float standoff = 700f;
        float fixedDt = SimTickRate.fixedDt();
        int sampleEvery = 60;
        boolean edgeProof = false;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--edge-proof": edgeProof = Integer.parseInt(args[++i]) != 0; break;
                case "--cycles": cycles = Integer.parseInt(args[++i]); break;
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--cooldown-frames": cooldownFrames = Integer.parseInt(args[++i]); break;
                case "--tail-frames": tailFrames = Integer.parseInt(args[++i]); break;
                case "--kill-cap-frames": killCapFrames = Integer.parseInt(args[++i]); break;
                case "--kill-damage": killDamage = Float.parseFloat(args[++i]); break;
                case "--standoff": standoff = Float.parseFloat(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--sample-every": sampleEvery = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin cycles=" + cycles + " settle_frames=" + settleFrames
                + " cooldown_frames=" + cooldownFrames + " tail_frames=" + tailFrames
                + " kill_damage=" + killDamage + " standoff=" + standoff
                + " fixed_dt=" + fixedDt + " edge_proof=" + edgeProof
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        // ---- Gated zero-hull boot (the serveNPeer construct), descriptor-driven. ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        Rig rig = resolveRig();
        long[] seatWireIds = RealEngineDemoRoster.descriptorSeatShipIds();
        if (seatWireIds.length != 2) {
            System.out.println(P + "-end verdict=FAILED reason=descriptor_seats_" + seatWireIds.length);
            System.exit(1);
            return;
        }
        long foeWireId = seatWireIds[1];

        // ---- Seat-1 Onslaught fly-in (side 0, the autofiring server hull) + solo drain. Autofire
        // is held OFF through the whole setup: fly-in landing spots sit ~800 su apart, and the
        // XIV's deploy-default groups otherwise shoot the arriving foe dead during the burn window
        // (R1 first attempt — baseline foe was already hulk/owner-100 at tick 303). ----
        Object srv = flyIn(1, step, fixedDt, 0L);
        long tick = 1;
        ensureAutofire(rig, srv, false);
        System.out.println(P + " srv_ready tick=" + tick + " autofire_held_off_for_setup=true"
                + " id=" + rig.shipGetId.invoke(srv));
        for (int f = 0; f < 300; f++) {
            idleSeats(binding);
            step.step(fixedDt, tick++);
        }

        if (edgeProof) {
            runEdgeProof(rig, binding, step, srv, fixedDt, tick, seatWireIds[0]);
            return; // unreachable — runEdgeProof exits
        }

        // ---- Evict the srv from the shared fly-in corridor BEFORE the foe enters it. Both
        // seats' fly-ins travel the same x≈const lane and engine RNG varies burn-in overshoot
        // run-to-run: in R3 the baseline foe rammed the srv mid-burn (hulk at first sample, zero
        // projectiles, corpse momentum drift) — a collision kill the autofire hold cannot prevent.
        // Crude lateral shove now; the precise standoff park happens after the foe lands. ----
        Object srvLoc = rig.shipGetLocation.invoke(srv);
        float srvX0 = rig.vecX.getFloat(srvLoc);
        float evictDir = srvX0 >= 0f ? 1f : -1f;
        rig.vecX.setFloat(srvLoc, srvX0 - evictDir * 2500f);
        Object srvVel = rig.shipGetVelocity.invoke(srv);
        rig.vecX.setFloat(srvVel, 0f);
        rig.vecY.setFloat(srvVel, 0f);

        // ---- Seat-2 foe fly-in (#1) + drain; groups OFF; burn window so it settles. ----
        Object foe = flyIn(2, step, fixedDt, tick);
        tick++;
        ensureAutofire(rig, foe, false);
        for (int f = 0; f < 300; f++) {
            idleSeats(binding);
            step.step(fixedDt, tick++);
        }

        // ---- Setup-time park: standoff PERPENDICULAR to the fly-in corridor (center-ward on X),
        // facing the foe. Off-axis keeps every later fly-in's burn path — and hulk momentum drift
        // (R1 flyin_6's corpse closed to 289 su of the old in-lane park) — a full standoff clear
        // of the srv. Never moved after this: every cycle lands at the same edge spot, so all
        // measured windows share this geometry. ----
        float[] foeAt = vecF(rig.shipGetLocation.invoke(foe), rig.vecX, rig.vecY);
        float parkDir = foeAt[0] >= 0f ? 1f : -1f;
        float srvX = foeAt[0] - parkDir * standoff;
        rig.vecX.setFloat(srvLoc, srvX);
        rig.vecY.setFloat(srvLoc, foeAt[1]);
        rig.vecX.setFloat(srvVel, 0f);
        rig.vecY.setFloat(srvVel, 0f);
        if (rig.shipSetFacing != null) {
            rig.shipSetFacing.invoke(srv, foeAt[0] > srvX ? 0f : 180f);
        }
        System.out.println(P + " srv_parked tick=" + tick + " srv=(" + f1(srvX) + ","
                + f1(foeAt[1]) + ") foe=(" + f1(foeAt[0]) + "," + f1(foeAt[1]) + ")");

        float maxRange = maxWeaponRange(rig, srv);
        // Setup done — arm the autofire groups (deploy-default set, via the engine's own toggles).
        int srvAutofireGroups = ensureAutofire(rig, srv, true);
        System.out.println(P + " srv_max_weapon_range=" + f1(maxRange)
                + " autofire_groups_armed=" + srvAutofireGroups);
        if (srvAutofireGroups == 0) {
            System.out.println(P + "-end verdict=FAILED reason=no_autofire_groups_on_srv");
            System.exit(1);
            return;
        }

        // ---- Baseline window (fly-in #1 in-range): MUST acquire, else the rig is wrong. ----
        Agg baseline = meterWindow(rig, binding, step, srv, foe, fixedDt, tick, settleFrames,
                sampleEvery, maxRange, "baseline_flyin_1");
        tick += settleFrames;
        System.out.println(P + " window phase=baseline_flyin_1 " + baseline.summary());
        boolean baselineAcquired = baseline.framesTgtFoe > 0;
        if (!baselineAcquired) {
            System.out.println(P + "-end verdict=INCONCLUSIVE_BASELINE " + baseline.summary());
            System.exit(3);
            return;
        }

        // ---- Kill / vacate / cooldown / re-mint cycles (fly-ins #2..#cycles+1). ----
        StringBuilder cycleAcquired = new StringBuilder();
        int firstBreakCycle = -1;
        for (int k = 1; k <= cycles; k++) {
            // Kill phase: one aimed lethal hit per frame until the engine declares the hull dead.
            int killFrames = 0;
            while (killFrames < killCapFrames) {
                boolean alive = (Boolean) rig.isShipAlive.invoke(rig.engine, foe);
                boolean hulk = (Boolean) rig.shipIsHulk.invoke(foe);
                if (!alive || hulk) {
                    break;
                }
                applyAimedHit(rig, srv, foe, killDamage);
                idleSeats(binding);
                step.step(fixedDt, tick++);
                killFrames++;
            }
            boolean deadNow = !((Boolean) rig.isShipAlive.invoke(rig.engine, foe))
                    || (Boolean) rig.shipIsHulk.invoke(foe);
            if (!deadNow) {
                System.out.println(P + "-end verdict=FAILED reason=foe_unkillable cycle=" + k
                        + " kill_frames=" + killFrames);
                System.exit(1);
                return;
            }
            idleSeats(binding);
            step.step(fixedDt, tick++); // one settle step post-death (serve observes on snapshot)
            RealEngineDemoRoster.vacateSeatHull(2, foeWireId);
            System.out.println(P + " cycle=" + k + " foe_dead tick=" + tick
                    + " kill_frames=" + killFrames
                    + " foe_hulk=" + rig.shipIsHulk.invoke(foe)
                    + " foe_owner=" + rig.shipGetOwner.invoke(foe));

            // Cooldown: metered against the DEAD ref — shows the autofire AIs dropping the hulk.
            Agg cool = meterWindow(rig, binding, step, srv, foe, fixedDt, tick, cooldownFrames,
                    sampleEvery, maxRange, "cooldown_" + k);
            tick += cooldownFrames;
            System.out.println(P + " window phase=cooldown_" + k + " " + cool.summary());

            // Re-mint through the product lane (fly-in #k+1) + drain.
            foe = flyIn(2, step, fixedDt, tick);
            tick++;
            ensureAutofire(rig, foe, false);
            Agg post = meterWindow(rig, binding, step, srv, foe, fixedDt, tick, settleFrames,
                    sampleEvery, maxRange, "flyin_" + (k + 1));
            tick += settleFrames;
            System.out.println(P + " window phase=flyin_" + (k + 1) + " " + post.summary());
            boolean acquired = post.framesTgtFoe > 0;
            cycleAcquired.append(acquired ? '1' : '0');
            if (!acquired && firstBreakCycle < 0) {
                firstBreakCycle = k;
            }
            System.out.println(P + " cycle=" + k + " flyin=" + (k + 1) + " acquired=" + acquired
                    + " first_tgt_foe_offset=" + post.firstTgtFoeOffset);
        }

        // ---- Optional tail (the --cycles 0 time-onset control rides this). ----
        int tgtFoeLostAtTick = -1;
        boolean controlVoidFoeDied = false;
        if (tailFrames > 0) {
            int chunk = Math.min(tailFrames, 600);
            int lastAcquiredChunkEnd = -1;
            for (int done = 0; done < tailFrames; done += chunk) {
                int n = Math.min(chunk, tailFrames - done);
                Agg a = meterWindow(rig, binding, step, srv, foe, fixedDt, tick, n,
                        sampleEvery, maxRange, "tail@" + tick);
                tick += n;
                System.out.println(P + " window phase=tail tick_end=" + tick + " " + a.summary());
                if (cycles == 0 && (a.framesHulk > 0 || a.framesRemoved > 0)) {
                    // The control mode has no scripted kill — a dead foe means the tail is
                    // metering a corpse, and tgt_foe=0 says nothing about autofire (the R2
                    // false-green: AF_CONTROL_HELD over a hulk). Void the control and stop.
                    controlVoidFoeDied = true;
                    break;
                }
                if (a.framesTgtFoe > 0) {
                    lastAcquiredChunkEnd = (int) tick;
                } else if (lastAcquiredChunkEnd > 0 && tgtFoeLostAtTick < 0) {
                    tgtFoeLostAtTick = (int) tick - n;
                }
            }
        }

        String verdict;
        if (firstBreakCycle > 0) {
            verdict = "AF_FLYIN_BREAK_REPRODUCED";
        } else if (cycles > 0) {
            verdict = "AF_FLYIN_ALL_RECOVERED";
        } else if (controlVoidFoeDied) {
            verdict = "AF_CONTROL_VOID_FOE_DIED";
        } else {
            verdict = tgtFoeLostAtTick > 0 ? "AF_TIME_ONSET_REPRODUCED" : "AF_CONTROL_HELD";
        }
        System.out.println(P + "-end verdict=" + verdict
                + " baseline_acquired=" + baselineAcquired
                + " cycles=" + cycles
                + " cycle_acquired=" + (cycleAcquired.length() == 0 ? "-" : cycleAcquired)
                + " first_break_cycle=" + firstBreakCycle
                + " tail_frames=" + tailFrames
                + " tgt_foe_lost_at_tick=" + tgtFoeLostAtTick
                + " end_tick=" + tick);
        System.exit(controlVoidFoeDied ? 3 : 0);
    }

    // ---- edge-proof mode ----

    /**
     * Prove the engine's rising-edge TOGGLE_AUTOFIRE law and the server mirror's click fix
     * against the REAL engine, game-free on the L3 substrate.
     * Everything drives the ship's own {@code giveCommand(TOGGLE_AUTOFIRE)} — never
     * {@code WeaponGroupAPI.toggleOn()}, which writes the tracker directly and bypasses the broken
     * command path. One hull, no foe, four scenarios:
     * (A) HOLD — 8 pressed frames flip exactly ONCE; an extra held frame never re-flips; a released
     * frame re-arms the edge so the next press flips.
     * (B) CLICK — 6 press+release clicks = 6 flips (every click lands).
     * (C) OLD LAW — the retired read-compare-issue mirror emulated verbatim over the field's stuck
     * shape (desired OFF@T, back@T+1): a press on every disagreeing tick keeps the group OFF for
     * the whole 20-tick span (the one-press-per-tick signature), and ONE released frame
     * + one press recovers — the hold was the whole mechanism.
     * (D) THE FIX — the LIVE {@link EngineCombatBinding#applyPilotIntent} click lane over the same
     * shape: mask 0@T presses OFF, the mask returning @T+1 is WITHHELD ({@code
     * autofireClickReleases}), the re-press @T+2 restores (≤2-tick recovery), and a 10-tick
     * agreeing stream issues nothing.
     * Verdict {@code AF_EDGE_CLICK_FIX_PROVEN} when every check passes (exit 0), else
     * {@code AF_EDGE_PROOF_FAILED} with {@code checks=} naming the failed checks (exit 3 —
     * scripted callers must see FAIL); no weapon groups on the hull =
     * exit 3 (rig).
     */
    private static void runEdgeProof(Rig rig, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, Object srv, float fixedDt, long tick,
            long srvWireId) throws Exception {
        List<?> groups = (List<?>) rig.shipGetWeaponGroupsCopy.invoke(srv);
        if (groups.isEmpty() || rig.groupIsAutofiring == null) {
            System.out.println(P + "-end verdict=AF_EDGE_INCONCLUSIVE reason=no_weapon_groups");
            System.exit(3);
            return;
        }
        ClassLoader cl = srv.getClass().getClassLoader();
        Class<?> shipCommand = Class.forName("com.fs.starfarer.api.combat.ShipCommand", false, cl);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object toggleCmd = Enum.valueOf((Class) shipCommand, "TOGGLE_AUTOFIRE");
        Method giveCommand =
                srv.getClass().getMethod("giveCommand", shipCommand, Object.class, int.class);
        final int g = 0;
        StringBuilder fails = new StringBuilder();
        // Setup toggled every group OFF and 300 quiet frames have run since: every uncommanded
        // frame delivers autofire(false), so all wasDown latches are clear.

        // (A) HOLD: 8 pressed frames = ONE flip; extra held frame swallowed; release re-arms.
        boolean af0 = groupAf(rig, srv, g);
        boolean afterHold = af0;
        int holdFlips = 0;
        for (int f = 0; f < 8; f++) {
            boolean now = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true,
                    fixedDt, tick++);
            if (now != afterHold) {
                holdFlips++;
            }
            afterHold = now;
        }
        boolean extraHeld = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true,
                fixedDt, tick++);
        boolean releasedSame = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false,
                fixedDt, tick++);
        boolean rearmed = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true,
                fixedDt, tick++);
        check(fails, holdFlips == 1, "hold_one_flip(" + holdFlips + ")");
        check(fails, extraHeld == afterHold, "held_extra_swallowed");
        check(fails, releasedSame == extraHeld, "release_no_flip");
        check(fails, rearmed != releasedSame, "release_rearms");
        System.out.println(P + " edge A_hold start=" + af0 + " flips_8_held=" + holdFlips
                + " extra_held_flip=" + (extraHeld != afterHold)
                + " rearm_flip=" + (rearmed != releasedSame) + " tick=" + tick);

        // (B) CLICK: 6 press+release clicks, every one flips. Scenario A ended ON A PRESS FRAME
        // (the re-arm press) — without this released frame the first B press would be back-to-back
        // with it and get swallowed (measured: the first probe run scored 5/6 exactly this way —
        // the latch law confirming across the scenario boundary).
        boolean cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false,
                fixedDt, tick++);
        int clickFlips = 0;
        for (int c = 0; c < 6; c++) {
            boolean pressed = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true,
                    fixedDt, tick++);
            if (pressed != cur) {
                clickFlips++;
            }
            boolean released = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false,
                    fixedDt, tick++);
            check(fails, released == pressed, "click_release_flipped@" + c);
            cur = released;
        }
        check(fails, clickFlips == 6, "click_flips_6(" + clickFlips + ")");
        System.out.println(P + " edge B_click flips=" + clickFlips + "/6 tick=" + tick);

        // (C) OLD LAW: the retired mirror (press every disagreeing tick, no withhold) over the
        // stuck field shape. Arm ON first so the dead-pilot OFF press at T is the opening edge.
        if (!cur) {
            cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true, fixedDt,
                    tick++);
            check(fails, cur, "c_arm_on");
            cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false, fixedDt,
                    tick++);
        }
        int oldPresses = 0;
        int stuckTicks = 0;
        boolean desired = false; // T: the dead pilot's mask-0; T+1 on: back to ON, forever
        for (int t = 0; t <= 20; t++) {
            boolean press = cur != desired; // read-compare-issue, verbatim
            if (press) {
                oldPresses++;
            }
            cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, press, fixedDt,
                    tick++);
            if (t == 0) {
                check(fails, !cur, "old_first_press_flips_off");
                desired = true;
            } else if (!cur) {
                stuckTicks++;
            }
        }
        check(fails, stuckTicks == 20, "old_mirror_stuck_20(" + stuckTicks + ")");
        check(fails, oldPresses == 21, "old_mirror_pressed_every_tick(" + oldPresses + ")");
        cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false, fixedDt,
                tick++); // the released frame the old mirror never granted
        boolean recovered = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true,
                fixedDt, tick++);
        check(fails, recovered, "old_shape_recovers_after_release");
        System.out.println(P + " edge C_oldlaw stuck_ticks=" + stuckTicks + "/20 presses="
                + oldPresses + " recovered_after_release=" + recovered + " tick=" + tick);
        // Park OFF so the fix scenario opens on the contract row's shape (mask ON, hull OFF).
        cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false, fixedDt, tick++);
        cur = edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, true, fixedDt, tick++);
        check(fails, !cur, "post_c_park_off");
        edgeFrame(rig, binding, step, srv, giveCommand, toggleCmd, g, false, fixedDt, tick++);

        // (D) THE FIX: the live applyPilotIntent/applyAutofireMask click lane, contract-row shape
        // (MatchLawContractRows.autofireMaskMirrorClicksNeverHolds) on the real engine.
        PilotApplyState state = new PilotApplyState(0);
        int[] seq = {1};
        int d1 = applyMaskFrame(binding, step, srvWireId, seq, 0b1, fixedDt, tick++, state, fails);
        boolean afD1 = groupAf(rig, srv, g);
        int d2 = applyMaskFrame(binding, step, srvWireId, seq, 0b1, fixedDt, tick++, state, fails);
        int d3 = applyMaskFrame(binding, step, srvWireId, seq, 0b0, fixedDt, tick++, state, fails);
        boolean afD3 = groupAf(rig, srv, g);
        int d4 = applyMaskFrame(binding, step, srvWireId, seq, 0b1, fixedDt, tick++, state, fails);
        boolean afD4 = groupAf(rig, srv, g);
        int releasesAtD4 = state.autofireClickReleases();
        int d5 = applyMaskFrame(binding, step, srvWireId, seq, 0b1, fixedDt, tick++, state, fails);
        boolean afD5 = groupAf(rig, srv, g);
        int agreeToggles = 0;
        for (int t = 0; t < 10; t++) {
            agreeToggles += applyMaskFrame(binding, step, srvWireId, seq, 0b1, fixedDt, tick++,
                    state, fails);
        }
        boolean afEnd = groupAf(rig, srv, g);
        check(fails, d1 == 1 && afD1, "fix_first_press_on(" + d1 + "," + afD1 + ")");
        check(fails, d2 == 0, "fix_agree_free_release(" + d2 + ")");
        check(fails, d3 == 1 && !afD3, "fix_dead_pilot_off(" + d3 + "," + afD3 + ")");
        check(fails, d4 == 0 && !afD4 && releasesAtD4 == 1,
                "fix_withheld_press(" + d4 + "," + afD4 + "," + releasesAtD4 + ")");
        check(fails, d5 == 1 && afD5, "fix_repress_recovers(" + d5 + "," + afD5 + ")");
        check(fails, agreeToggles == 0 && afEnd,
                "fix_agree_stream_quiet(" + agreeToggles + "," + afEnd + ")");
        System.out.println(P + " edge D_fix toggles=" + d1 + "," + d2 + "," + d3 + "," + d4 + ","
                + d5 + " click_releases=" + state.autofireClickReleases()
                + " agree_stream_toggles=" + agreeToggles + " af_end=" + afEnd + " tick=" + tick);

        String verdict = fails.length() == 0 ? "AF_EDGE_CLICK_FIX_PROVEN" : "AF_EDGE_PROOF_FAILED";
        System.out.println(P + "-end verdict=" + verdict
                + " hold_flips=" + holdFlips
                + " click_flips=" + clickFlips + "/6"
                + " old_stuck_ticks=" + stuckTicks + "/20"
                + " old_presses=" + oldPresses
                + " fix_toggles=" + d1 + "," + d2 + "," + d3 + "," + d4 + "," + d5
                + " fix_click_releases=" + state.autofireClickReleases()
                + " agree_stream_toggles=" + agreeToggles
                + (fails.length() == 0 ? "" : " checks=" + fails)
                + " end_tick=" + tick);
        System.exit(fails.length() == 0 ? 0 : 3);
    }

    /** One engine frame: seat AI idled, an optional TOGGLE_AUTOFIRE press on {@code group}, step. */
    private static boolean edgeFrame(Rig rig, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, Object srv, Method giveCommand, Object toggleCmd,
            int group, boolean press, float fixedDt, long tick) throws Exception {
        binding.idleOwnedShipAi(1L);
        if (press) {
            giveCommand.invoke(srv, toggleCmd, null, group);
        }
        step.step(fixedDt, tick);
        return groupAf(rig, srv, group);
    }

    /**
     * Apply one quiet wire frame (only the autofire mask lives; selection DESELECTED, no held
     * bits, no fire) through the LIVE serve seam, then step — the serve loop's own
     * apply-then-advance order.
     */
    private static int applyMaskFrame(EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, long shipWireId, int[] seq, int mask,
            float fixedDt, long tick, PilotApplyState state,
            StringBuilder fails) {
        cc.starsector.server.runtime.PilotIntentFrame frame =
                new cc.starsector.server.runtime.PilotIntentFrame(
                        shipWireId, seq[0]++, 0.0f, 0, false,
                        cc.starsector.server.runtime.PilotIntentFrame.SELECTED_GROUP_DESELECTED,
                        0.0f, 0.0f, 0.0f, mask,
                        cc.starsector.server.runtime.PilotIntentFrame.TARGET_ABSENT, 0,
                        cc.starsector.server.runtime.PilotIntentFrame.TUBE_ORDINAL_ABSENT);
        EngineCombatBinding.PilotApplyResult res = binding.applyPilotIntent(frame, tick, state);
        if (!res.accepted()) {
            check(fails, false, "apply_rejected@" + tick);
        }
        step.step(fixedDt, tick);
        return res.autofireTogglesIssued();
    }

    private static boolean groupAf(Rig rig, Object srv, int group) throws Exception {
        List<?> groups = (List<?>) rig.shipGetWeaponGroupsCopy.invoke(srv);
        return (Boolean) rig.groupIsAutofiring.invoke(groups.get(group));
    }

    private static void check(StringBuilder fails, boolean ok, String name) {
        if (!ok) {
            fails.append(fails.length() == 0 ? "" : ",").append(name);
        }
    }

    // ---- rig ----

    private static Rig resolveRig() throws Exception {
        ClassLoader loader = RealEngineAutofireFlyInProbeMain.class.getClassLoader();
        Rig r = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        r.engine = engineClass.getMethod("getInstance").invoke(null);
        if (r.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        r.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> entityApi = Class.forName(ENTITY_API, false, loader);
        Class<?> damageType = Class.forName(DAMAGE_TYPE, false, loader);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        r.getObjects = engineClass.getMethod("getObjects");
        r.repoGetList = r.getObjects.invoke(r.engine).getClass().getMethod("getList", Class.class);
        r.isShipAlive = engineClass.getMethod("isShipAlive", shipApi);
        r.getFogOfWar = engineClass.getMethod("getFogOfWar", int.class);
        r.getAiGridShips = engineClass.getMethod("getAiGridShips");
        r.applyDamage = engineClass.getMethod("applyDamage",
                Object.class, entityApi, vec2, float.class, damageType,
                float.class, boolean.class, boolean.class, Object.class, boolean.class);
        r.damageTypeHe = damageType.getField("HIGH_EXPLOSIVE").get(null);
        r.shipGetLocation = r.shipClass.getMethod("getLocation");
        r.shipGetVelocity = r.shipClass.getMethod("getVelocity");
        r.shipGetOwner = r.shipClass.getMethod("getOwner");
        r.shipGetId = r.shipClass.getMethod("getId");
        r.shipIsHulk = r.shipClass.getMethod("isHulk");
        r.shipIsTargetable = r.shipClass.getMethod("isTargetable");
        r.shipWasRemoved = r.shipClass.getMethod("wasRemoved");
        r.shipIsExpired = optional(r.shipClass, "isExpired");
        r.shipGetCollisionRadius = r.shipClass.getMethod("getCollisionRadius");
        r.shipGetWeaponGroupsCopy = r.shipClass.getMethod("getWeaponGroupsCopy");
        r.shipGetAllWeapons = r.shipClass.getMethod("getAllWeapons");
        r.shipGetFluxTracker = optional(r.shipClass, "getFluxTracker");
        r.shipSetFacing = optional(r.shipClass, "setFacing", float.class);
        r.shipGetHullLevel = optional(r.shipClass, "getHullLevel");
        r.vecX = vec2.getField("x");
        r.vecY = vec2.getField("y");
        r.vec2Ctor = vec2.getConstructor(float.class, float.class);
        try {
            r.projClass = Class.forName(PROJECTILE_CLASS, false, loader);
        } catch (ClassNotFoundException e) {
            r.projClass = null;
        }
        return r;
    }

    /** Per-frame product-lane seat AI suppression (serve fidelity; a vacated id drops harmlessly). */
    private static void idleSeats(EngineCombatBinding binding) {
        binding.idleOwnedShipAi(1L);
        binding.idleOwnedShipAi(2L);
    }

    /**
     * Ensure the hull's autofire groups are ON (server hull) or OFF (foe) through the engine's own
     * group toggles; returns the count of autofiring groups after. Group/plugin handles resolve
     * lazily off the first instance seen.
     */
    private static int ensureAutofire(Rig r, Object ship, boolean on) throws Exception {
        List<?> groups = (List<?>) r.shipGetWeaponGroupsCopy.invoke(ship);
        int autofiring = 0;
        for (Object g : groups) {
            if (r.groupIsAutofiring == null) {
                r.groupIsAutofiring = g.getClass().getMethod("isAutofiring");
                r.groupGetAiPlugins = g.getClass().getMethod("getAIPlugins");
                r.groupToggleOn = g.getClass().getMethod("toggleOn");
                r.groupToggleOff = g.getClass().getMethod("toggleOff");
            }
            boolean is = (Boolean) r.groupIsAutofiring.invoke(g);
            boolean hasPlugins = !((List<?>) r.groupGetAiPlugins.invoke(g)).isEmpty();
            if (on && !is && hasPlugins) {
                r.groupToggleOn.invoke(g);
            } else if (!on && is) {
                r.groupToggleOff.invoke(g);
            }
            if ((Boolean) r.groupIsAutofiring.invoke(g)) {
                autofiring++;
            }
        }
        return autofiring;
    }

    private static float maxWeaponRange(Rig r, Object ship) throws Exception {
        float max = 0f;
        for (Object w : (List<?>) r.shipGetAllWeapons.invoke(ship)) {
            Method getRange = w.getClass().getMethod("getRange");
            max = Math.max(max, ((Number) getRange.invoke(w)).floatValue());
        }
        return max;
    }

    /**
     * Meter {@code frames} steps: every frame reads the acquisition-law gates on {@code foe} and
     * the per-weapon autofire AI state on {@code srv}; prints a sample line every
     * {@code sampleEvery} frames and on every acquired&harr;lost transition.
     */
    private static Agg meterWindow(Rig r, EngineCombatBinding binding,
            EngineBackedCombatStepBoundary step, Object srv, Object foe, float fixedDt, long tick,
            int frames, int sampleEvery, float maxRange, String phase) throws Exception {
        Agg a = new Agg();
        boolean prevAcquired = false;
        for (int f = 0; f < frames; f++) {
            idleSeats(binding);
            maybeVent(r, srv);
            step.step(fixedDt, tick + f);
            a.frames++;

            boolean hulk = (Boolean) r.shipIsHulk.invoke(foe);
            boolean removed = (Boolean) r.shipWasRemoved.invoke(foe);
            boolean targetable = (Boolean) r.shipIsTargetable.invoke(foe);
            int foeOwner = ((Number) r.shipGetOwner.invoke(foe)).intValue();
            int srvOwner = ((Number) r.shipGetOwner.invoke(srv)).intValue();
            Object fog = r.getFogOfWar.invoke(r.engine, srvOwner);
            boolean fogNull = fog == null;
            boolean fogVisible = false;
            if (!fogNull) {
                if (r.fogIsVisible == null) {
                    r.fogIsVisible = fog.getClass().getMethod("isVisible",
                            Class.forName(ENTITY_API, false, foe.getClass().getClassLoader()));
                }
                fogVisible = (Boolean) r.fogIsVisible.invoke(fog, foe);
            }
            Object foeLoc = r.shipGetLocation.invoke(foe);
            Object srvLocNow = r.shipGetLocation.invoke(srv);
            float dist = (float) Math.hypot(
                    r.vecX.getFloat(foeLoc) - r.vecX.getFloat(srvLocNow),
                    r.vecY.getFloat(foeLoc) - r.vecY.getFloat(srvLocNow));
            float foeRad = ((Number) r.shipGetCollisionRadius.invoke(foe)).floatValue();
            boolean inRange = dist <= maxRange + foeRad;
            boolean gridAtFoe = gridContains(r, foeLoc, 400f, foe);
            // The acquisition scan's own query shape: from the server hull, range*2+50 box.
            boolean gridFromSrv = gridContains(r, srvLocNow, maxRange * 2f + 50f, foe);

            int plugins = 0;
            int tgtFoe = 0;
            int tgtAny = 0;
            int shouldFire = 0;
            int groupsAf = 0;
            for (Object g : (List<?>) r.shipGetWeaponGroupsCopy.invoke(srv)) {
                if (!((Boolean) r.groupIsAutofiring.invoke(g))) {
                    continue;
                }
                groupsAf++;
                for (Object plugin : (List<?>) r.groupGetAiPlugins.invoke(g)) {
                    if (plugin == null) {
                        continue;
                    }
                    if (r.pluginGetTargetShip == null) {
                        r.pluginGetTargetShip = plugin.getClass().getMethod("getTargetShip");
                        r.pluginShouldFire = plugin.getClass().getMethod("shouldFire");
                    }
                    plugins++;
                    Object tgt = r.pluginGetTargetShip.invoke(plugin);
                    if (tgt != null) {
                        tgtAny++;
                        if (tgt == foe) {
                            tgtFoe++;
                        }
                    }
                    if ((Boolean) r.pluginShouldFire.invoke(plugin)) {
                        shouldFire++;
                    }
                }
            }
            int proj = -1;
            if (r.projClass != null) {
                proj = ((List<?>) r.repoGetList.invoke(r.getObjects.invoke(r.engine), r.projClass))
                        .size();
            }

            if (tgtFoe > 0) {
                a.framesTgtFoe++;
                if (a.firstTgtFoeOffset < 0) {
                    a.firstTgtFoeOffset = f;
                }
            }
            a.maxTgtFoe = Math.max(a.maxTgtFoe, tgtFoe);
            if (shouldFire > 0) a.framesShouldFire++;
            if (targetable) a.framesTargetable++;
            if (fogVisible) a.framesFogVisible++;
            if (fogNull) a.framesFogNull++;
            if (gridAtFoe) a.framesGridAtFoe++;
            if (gridFromSrv) a.framesGridFromSrv++;
            if (inRange) a.framesInRange++;
            if (hulk) a.framesHulk++;
            if (removed) a.framesRemoved++;
            a.minDist = Math.min(a.minDist, dist);
            a.maxProj = Math.max(a.maxProj, proj);

            boolean acquired = tgtFoe > 0;
            if (f % sampleEvery == 0 || f == frames - 1 || acquired != prevAcquired) {
                System.out.println(P + " s phase=" + phase + " tick=" + (tick + f + 1)
                        + " dist=" + f1(dist) + " in_range=" + inRange
                        + " foe[hulk=" + hulk + " owner=" + foeOwner
                        + " targetable=" + targetable + " removed=" + removed
                        + " fog=" + (fogNull ? "null" : (fogVisible ? "vis" : "HIDDEN"))
                        + " grid_at=" + gridAtFoe + " grid_src=" + gridFromSrv + "]"
                        + " af[groups=" + groupsAf + " plugins=" + plugins
                        + " tgt_foe=" + tgtFoe + " tgt_any=" + tgtAny
                        + " should_fire=" + shouldFire + "]"
                        + " proj=" + proj);
            }
            prevAcquired = acquired;
        }
        return a;
    }

    private static boolean gridContains(Rig r, Object centerLoc, float halfSpan, Object needle)
            throws Exception {
        Object grid = r.getAiGridShips.invoke(r.engine);
        if (grid == null) {
            return false;
        }
        if (r.gridCheckIterator == null) {
            r.gridCheckIterator = grid.getClass().getMethod("getCheckIterator",
                    Class.forName(LWJGL_VECTOR2F_CLASS, false, needle.getClass().getClassLoader()),
                    float.class, float.class);
        }
        Iterator<?> it = (Iterator<?>) r.gridCheckIterator.invoke(grid, centerLoc, halfSpan, halfSpan);
        while (it.hasNext()) {
            if (it.next() == needle) {
                return true;
            }
        }
        return false;
    }

    /** Best-effort flux relief through the engine's own vent (the rig has no venting pilot). */
    private static void maybeVent(Rig r, Object srv) {
        if (r.shipGetFluxTracker == null) {
            return;
        }
        try {
            Object tracker = r.shipGetFluxTracker.invoke(srv);
            if (tracker == null) {
                return;
            }
            if (r.fluxGetLevel == null) {
                r.fluxGetLevel = tracker.getClass().getMethod("getFluxLevel");
                r.fluxIsVenting = tracker.getClass().getMethod("isVenting");
                r.fluxVent = tracker.getClass().getMethod("ventFlux");
            }
            float level = ((Number) r.fluxGetLevel.invoke(tracker)).floatValue();
            if (level > 0.7f && !((Boolean) r.fluxIsVenting.invoke(tracker))) {
                r.fluxVent.invoke(tracker);
            }
        } catch (ReflectiveOperationException e) {
            r.shipGetFluxTracker = null; // surface once, then disable the vent rig
            System.out.println(P + " vent_rig_disabled reason=" + e);
        }
    }

    /** One aimed engine hit (deck-damage idiom); headless cosmetic-FX NPEs count as applied. */
    private static void applyAimedHit(Rig r, Object srv, Object foe, float damage) throws Exception {
        Object foeLoc = r.shipGetLocation.invoke(foe);
        Object point = r.vec2Ctor.newInstance(r.vecX.getFloat(foeLoc), r.vecY.getFloat(foeLoc));
        try {
            r.applyDamage.invoke(r.engine, srv, foe, point, damage, r.damageTypeHe, 0f,
                    Boolean.TRUE, Boolean.FALSE, null, Boolean.FALSE);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            if (!SwallowCosmeticFxAuthoritativeDamageSink.isHeadlessCosmeticFxNpe(cause)
                    && !(cause instanceof NullPointerException
                            && java.util.Arrays.stream(cause.getStackTrace()).anyMatch(
                                    el -> el.getClassName().startsWith("com.fs.graphics")))) {
                throw new IllegalStateException("applyDamage threw a non-cosmetic boundary", cause);
            }
        }
    }

    /** One product-lane fly-in + the drain step (zero-hull probe idiom; a throw = exit 2). */
    private static Object flyIn(int seatId, EngineBackedCombatStepBoundary step, float fixedDt,
            long tick) {
        try {
            Object ship = RealEngineDemoRoster.midDeploySeat(seatId);
            step.step(fixedDt, tick);
            return ship;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=FLY_IN_THREW seat=" + seatId + " tick=" + tick
                    + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            throw new IllegalStateException("unreachable");
        }
    }

    private static Method optional(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static float[] vecF(Object v, Field x, Field y) throws Exception {
        return new float[]{x.getFloat(v), y.getFloat(v)};
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
