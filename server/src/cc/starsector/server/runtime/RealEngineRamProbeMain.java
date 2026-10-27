package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.match.MatchDescriptors;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The rung-(ii) VANILLA ram BASELINE — measure the real engine's
 * ship-ship collision law headless (game-free, L3 llvmpipe substrate) on the
 * recorded specimen-#4 hull pair ({@code aurora_cc_hvb2} — its variant file
 * since retired, the recorded specimen stands — rams {@code glimmer_Support}),
 * so the live deep-overlap ram (dist-min 118.9 su vs collision-radius sum 270 su) can be
 * adjudicated vanilla-vs-defect against ground truth.
 *
 * <p><b>Law under test</b> (static-RE of the engine's broad/narrow phase +
 * its SHIP×SHIP response): the resolver is VELOCITY-ONLY —
 * a mass-weighted elastic impulse on the first contact frame, re-firing ~0.25 s (pair cull),
 * plus a soft push-apart adding 900·dt mass-weighted velocity gated OFF above ~50 su/s
 * separation; shield contact tests are ARC-GATED, off-arc falls through to hull
 * bounds-vs-bounds. Pre-registered falsifiable predictions:
 * <ul>
 *   <li><b>P1</b> — no positional snap: center distance moves only via integrated velocity
 *       (max single-tick position residual vs v·dt stays sub-snap).</li>
 *   <li><b>P2</b> — the foe takes a large single-tick velocity kick at first contact,
 *       recurring on the ~0.25 s pair-cull cadence while in contact.</li>
 *   <li><b>P3</b> — overlap does NOT decay while the rammer thrusts: dist-min tracks hull
 *       contact (~105–181 su envelope for this pair; specimen 118.9), never ~270 (CR circles
 *       were never the contact surface).</li>
 * </ul>
 *
 * <p><b>Cells</b> (all measured, none asserted mid-run): 4 BALLISTIC cells (own velocity
 * guided to 300 su gap then hands-off — contact physics fully engine-owned; speeds 300/350 ×
 * foe shield forced toward/away) grade P1/P2 onset; 4 SUSTAINED cells (~306 su/s per-tick
 * pursuit velocity rewrite — the SO-Aurora sustained-thrust stand-in; foe shield toward/away ×
 * own shield on/off) grade P3 grind depth.
 *
 * <p><b>Rung (ii-b) cell</b>: {@code s306_fface_oon_fpin} — the specimen-#4
 * ground-holding twin. The live run's server rows measured the own shield ON + facing (miss
 * 1–14°) through the whole penetration to 118.9; the first kick fired at the ~270 ring in
 * BOTH live specimens, and the divergence was the foe's counter-kinematics (specimen #1's foe
 * separated; #4's foe held ground while the rammer kept thrusting, and the ~0.25 s pair cull
 * let penetration accumulate between kicks). The original 8 cells' free foe simply fled at
 * kick speed, which is why own-ON never penetrated outside-in. This cell PINS the foe on
 * station (velocity re-zeroed per tick while thrusting — the station-keeping stand-in; the
 * engine's per-tick kick Δv stays measurable because the pre-step velocity is always zero)
 * with own shield ON: penetration through the ring to hull scale ⇒ the live deep overlap is
 * vanilla law (velocity-only + culled impulses + sustained thrust + ground-holding foe);
 * flooring rigidly at the ring ⇒ a real exclusion condition exists and rung (iii) opens.
 * Graded on its own line ({@code ram-probe-fpin}); excluded from the P2/P3 bars (a pinned
 * foe invalidates their free-foe kick kinematics). <b>SCAFFOLD flags:</b> direct velocity/pose writes
 * on the probe's OWN AI-stripped ships (collision response cares only about velocity at
 * contact), per-tick hull refill + flux zero (kill-free, the
 * zero-hull idiom) so a cell survives its own collision damage. A MEASUREMENT main, never
 * wired into the product serve loop.
 *
 * <p>Exit codes: 0 = measured (verdict {@code VANILLA_RAM_CONFIRMED} or
 * {@code RAM_LAW_DEVIATES} — both findings, read the end line); 2 = boundary thrown
 * (boot/resolve/step — stack printed); 1 = infrastructure (ships never resolved / most cells
 * failed to reach contact). A wrapper script runs it.
 *
 */
public final class RealEngineRamProbeMain {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String SHIELD_API = "com.fs.starfarer.api.combat.ShieldAPI";
    private static final String FLUX_API = "com.fs.starfarer.api.combat.FluxTrackerAPI";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "ram-probe";

    /** Foe single-tick |Δv| ≥ this = an impulse KICK (soft push maxes ~15 su/s·tick at 900·dt). */
    private static final float KICK_MIN = 40f;
    /** Single-tick position residual ≥ this = a positional SNAP (P1 RED). */
    private static final float SNAP_MAX = 40f;
    /** P3 hull-scale bar: dist-min at or under the pair's hull-contact envelope (+ margin). */
    private static final float HULL_SCALE_MAX = 190f;
    /** "Never ~270": any sustained grind flooring above this is CR-circle-scale (P3 RED). */
    private static final float CR_SCALE_MIN = 240f;

    private RealEngineRamProbeMain() {
    }

    private static final class Rig {
        Object engine;
        Class<?> shipClass;
        Method getObjects;
        Method repoGetList;
        Method isShipAlive;
        Method getVariant;
        Method getShipAI;
        Method setShipAI;
        Method getWeaponGroupsCopy;
        Method getShield;
        Method shipGetLocation;
        Method shipGetVelocity;
        Method entGetFacing;
        Method entSetFacing;
        Method entGetAngVel;    // optional
        Method entSetAngVel;    // optional
        Method entGetMass;      // optional
        Method entGetMaxHp;     // optional
        Method entSetHp;        // optional
        Method shieldIsOn;
        Method shieldToggleOn;
        Method shieldToggleOff;
        Method shieldGetFacing;
        Method shieldGetActiveArc;
        Method shieldForceFacing; // optional
        Method getFluxTracker;
        Method fluxSetCurr;       // optional
        Method fluxIsOverloaded;  // optional
        Field vecX;
        Field vecY;

        List<?> ships() throws Exception {
            return (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        }

        boolean alive(Object ship) throws Exception {
            return (Boolean) isShipAlive.invoke(engine, ship);
        }

        String variantId(Object ship) {
            try {
                Object variant = getVariant.invoke(ship);
                if (variant == null) {
                    return "?";
                }
                Object id = variant.getClass().getMethod("getHullVariantId").invoke(variant);
                return id instanceof String ? (String) id : "?";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "?";
            }
        }
    }

    /** One matrix cell: speed + drive mode + both shield postures (+ ground-holding foe). */
    private static final class Cell {
        final String name;
        final float speed;
        final boolean sustained;
        final boolean foeShieldToward; // forced shield facing: toward the rammer vs away
        final boolean ownShieldOn;
        final boolean pinFoe;          // re-zero foe velocity per tick while thrusting

        Cell(String name, float speed, boolean sustained, boolean foeShieldToward,
                boolean ownShieldOn) {
            this(name, speed, sustained, foeShieldToward, ownShieldOn, false);
        }

        Cell(String name, float speed, boolean sustained, boolean foeShieldToward,
                boolean ownShieldOn, boolean pinFoe) {
            this.name = name;
            this.speed = speed;
            this.sustained = sustained;
            this.foeShieldToward = foeShieldToward;
            this.ownShieldOn = ownShieldOn;
            this.pinFoe = pinFoe;
        }
    }

    /** One cell's measured outcome (grading happens once, at the end). */
    private static final class CellResult {
        final String name;
        boolean contact;
        boolean died;
        float contactOnsetDist = Float.NaN;
        float contactOnsetSpeed = Float.NaN;
        float firstKickDv = Float.NaN;
        float distMin = Float.POSITIVE_INFINITY;
        int kicks;
        int kickMinSpacing = -1;
        int kickMedianSpacing = -1;
        float snapMax;
        int overlapTicks;          // sustained: ticks with dist <= CR_SCALE_MIN while thrusting
        float releaseRate = Float.NaN; // sustained: separation su/s once thrust released
        String foeShieldAtOnset = "?";
        String ownShieldAtOnset = "?";
        float foeSfaceMissAtOnset = Float.NaN;
        boolean pinned;                // Fpin cell: excluded from the P2/P3 free-foe bars

        CellResult(String name) {
            this.name = name;
        }
    }

    public static void main(String[] args) throws Exception {
        float approachDist = 900f;
        int settleFrames = 45;
        int sustainFrames = 300;
        int releaseFrames = 240;
        int postContactFrames = 120;
        int cellCapFrames = 1500;
        float fixedDt = SimTickRate.fixedDt();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--approach-dist": approachDist = Float.parseFloat(args[++i]); break;
                case "--settle-frames": settleFrames = Integer.parseInt(args[++i]); break;
                case "--sustain-frames": sustainFrames = Integer.parseInt(args[++i]); break;
                case "--release-frames": releaseFrames = Integer.parseInt(args[++i]); break;
                case "--post-contact-frames": postContactFrames = Integer.parseInt(args[++i]); break;
                case "--cell-cap-frames": cellCapFrames = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin approach_dist=" + f1(approachDist)
                + " settle=" + settleFrames + " sustain=" + sustainFrames
                + " release=" + releaseFrames + " cap=" + cellCapFrames
                + " fixed_dt=" + fixedDt
                + " descriptor=" + System.getProperty("cc.match.descriptor", "(unset)"));

        EngineCombatBinding binding;
        EngineBackedCombatStepBoundary step;
        Rig rig;
        Object aurora;
        Object glimmer;
        long tick = 1;
        try {
            binding = RealEngineCombatBindingFactory.create(new TreeSet<Integer>());
            EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
            step = new EngineBackedCombatStepBoundary(world, binding);
            step.step(fixedDt, tick++);
            rig = buildRig();
            // Roster rows land through the pending-add queue; give them a few frames.
            aurora = null;
            glimmer = null;
            for (int f = 0; f < 300 && (aurora == null || glimmer == null); f++) {
                aurora = byVariant(rig, MatchDescriptors.BASTION_AURORA_VARIANT_ID);
                glimmer = byVariant(rig, MatchDescriptors.REMNANT_GLIMMER_SUPPORT_VARIANT_ID);
                if (aurora == null || glimmer == null) {
                    step.step(fixedDt, tick++);
                }
            }
            if (aurora == null || glimmer == null) {
                System.out.println(P + "-end verdict=SHIPS_NOT_RESOLVED aurora="
                        + (aurora != null) + " glimmer=" + (glimmer != null));
                System.exit(1);
                return;
            }
            Object bounds = binding.applyCombatMapBounds(6000f);
            int stripped = 0;
            int autofireOff = 0;
            for (Object s : rig.ships()) {
                if (rig.getShipAI.invoke(s) != null) {
                    rig.setShipAI.invoke(s, new Object[]{null});
                    stripped++;
                }
                for (Object group : (List<?>) rig.getWeaponGroupsCopy.invoke(s)) {
                    if ((Boolean) group.getClass().getMethod("isAutofiring").invoke(group)) {
                        group.getClass().getMethod("toggleOff").invoke(group);
                        autofireOff++;
                    }
                }
            }
            System.out.println(P + " boot_ok tick=" + tick + " ai_stripped=" + stripped
                    + " autofire_off=" + autofireOff + " bounds=" + String.valueOf(bounds)
                    + " own_mass=" + f1(massOrNaN(rig, aurora))
                    + " foe_mass=" + f1(massOrNaN(rig, glimmer))
                    + " refill=" + (rig.entSetHp != null) + " flux_zero=" + (rig.fluxSetCurr != null)
                    + " force_sface=" + (rig.shieldForceFacing != null));
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=BOOT_OR_RESOLVE_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        Cell[] cells = {
                new Cell("b300_fface_oon", 300f, false, true, true),
                new Cell("b300_foff_oon", 300f, false, false, true),
                new Cell("b350_fface_oon", 350f, false, true, true),
                new Cell("b350_foff_oon", 350f, false, false, true),
                new Cell("s306_fface_oon", 306f, true, true, true),   // specimen-#4 twin
                new Cell("s306_foff_oon", 306f, true, false, true),
                new Cell("s306_fface_ooff", 306f, true, true, false),
                new Cell("s306_foff_ooff", 306f, true, false, false),
                // Rung (ii-b): specimen-#4 ground-holding twin (see class javadoc).
                new Cell("s306_fface_oon_fpin", 306f, true, true, true, true),
        };
        List<CellResult> results = new ArrayList<CellResult>();
        try {
            for (Cell cell : cells) {
                CellResult r = runCell(rig, step, aurora, glimmer, cell, approachDist,
                        settleFrames, sustainFrames, releaseFrames, postContactFrames,
                        cellCapFrames, fixedDt, tick);
                tick += cellCapFrames + settleFrames + releaseFrames + 8;
                results.add(r);
                if (r.died) {
                    System.out.println(P + " abort_remaining cell=" + cell.name
                            + " reason=ship_died_and_refill_unavailable");
                    break;
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println(P + "-end verdict=CELL_THREW boundary="
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // ---- Grade P1–P3 (pre-registered) ----
        int contactCells = 0;
        float snapMax = 0f;
        boolean p2Ballistic = true;
        boolean p2Sustained = true;
        boolean p3 = true;
        boolean anySustainedContact = false;
        StringBuilder summary = new StringBuilder();
        for (CellResult r : results) {
            if (r.contact) {
                contactCells++;
            }
            snapMax = Math.max(snapMax, r.snapMax);
            boolean sustained = r.name.startsWith("s");
            if (r.contact && !sustained && r.firstKickDv < 100f) {
                p2Ballistic = false;
            }
            // Pinned-foe cells are excluded from the free-foe P2/P3 bars (class javadoc).
            if (r.contact && sustained && !r.pinned) {
                anySustainedContact = true;
                if (r.kicks < 2 || r.kickMinSpacing < 8) {
                    p2Sustained = false;
                }
                if (r.distMin > HULL_SCALE_MAX || r.overlapTicks < 30
                        || r.distMin >= CR_SCALE_MIN) {
                    p3 = false;
                }
            }
            summary.append(" ").append(r.name)
                    .append("[contact=").append(r.contact)
                    .append(" onset=").append(f1(r.contactOnsetDist))
                    .append("@").append(f1(r.contactOnsetSpeed))
                    .append(" kick1=").append(f1(r.firstKickDv))
                    .append(" kicks=").append(r.kicks)
                    .append(" span_min=").append(r.kickMinSpacing)
                    .append(" span_med=").append(r.kickMedianSpacing)
                    .append(" dmin=").append(f1(r.distMin))
                    .append(" overlap_t=").append(r.overlapTicks)
                    .append(" snap=").append(f2(r.snapMax))
                    .append(" rel_rate=").append(f1(r.releaseRate))
                    .append(" fsh=").append(r.foeShieldAtOnset)
                    .append(" miss=").append(f1(r.foeSfaceMissAtOnset))
                    .append(" osh=").append(r.ownShieldAtOnset)
                    .append(" died=").append(r.died).append("]");
        }
        if (contactCells < results.size() - 2 || !anySustainedContact) {
            System.out.println(P + "-end verdict=INSUFFICIENT_CONTACT contact_cells="
                    + contactCells + "/" + results.size() + summary);
            System.exit(1);
            return;
        }
        for (CellResult r : results) {
            if (!r.pinned) {
                continue;
            }
            String outcome = !r.contact ? "NO_CONTACT"
                    : r.distMin <= HULL_SCALE_MAX ? "PENETRATED_TO_HULL_SCALE"
                    : r.distMin >= 210f ? "FLOORED_AT_RING" : "PENETRATED_PARTIAL";
            System.out.println(P + "-fpin cell=" + r.name + " onset=" + f1(r.contactOnsetDist)
                    + " dmin=" + f1(r.distMin) + " kicks=" + r.kicks
                    + " span_med=" + r.kickMedianSpacing + " overlap_t=" + r.overlapTicks
                    + " rel_rate=" + f1(r.releaseRate) + " outcome=" + outcome);
        }
        boolean p1 = snapMax < SNAP_MAX;
        boolean p2 = p2Ballistic && p2Sustained;
        String verdict = (p1 && p2 && p3) ? "VANILLA_RAM_CONFIRMED" : "RAM_LAW_DEVIATES";
        System.out.println(P + "-end verdict=" + verdict
                + " p1=" + (p1 ? "GREEN" : "RED") + " p2=" + (p2 ? "GREEN" : "RED")
                + " p3=" + (p3 ? "GREEN" : "RED")
                + " snap_max=" + f2(snapMax) + " contact_cells=" + contactCells + "/"
                + results.size() + summary);
        System.exit(0);
    }

    /**
     * One matrix cell: pose both ships fresh (foe broadside at origin, rammer {@code
     * approachDist} east facing west), settle, drive the ram, measure per tick. Ballistic =
     * velocity guided until the gap closes to 300 su then hands-off (contact physics fully
     * engine-owned); sustained = per-tick pursuit rewrite until first-contact + {@code
     * sustainFrames}, then released for {@code releaseFrames} (push-apart observation).
     */
    private static CellResult runCell(Rig rig, EngineBackedCombatStepBoundary step,
            Object aurora, Object glimmer, Cell cell, float approachDist, int settleFrames,
            int sustainFrames, int releaseFrames, int postContactFrames, int cellCapFrames,
            float fixedDt, long tick) throws Exception {
        CellResult r = new CellResult(cell.name);
        r.pinned = cell.pinFoe;
        float foeSfaceDeg = cell.foeShieldToward ? 0f : 180f;
        System.out.println(P + " cell_begin name=" + cell.name + " tick=" + tick
                + " speed=" + f1(cell.speed) + " sustained=" + cell.sustained
                + " foe_sface=" + f1(foeSfaceDeg) + " own_shield=" + cell.ownShieldOn
                + " pin_foe=" + cell.pinFoe);

        // ---- pose + settle ----
        setVec(rig, rig.shipGetLocation.invoke(glimmer), 0f, 0f);
        setVec(rig, rig.shipGetVelocity.invoke(glimmer), 0f, 0f);
        rig.entSetFacing.invoke(glimmer, 90f);
        setVec(rig, rig.shipGetLocation.invoke(aurora), approachDist, 0f);
        setVec(rig, rig.shipGetVelocity.invoke(aurora), 0f, 0f);
        rig.entSetFacing.invoke(aurora, 180f);
        Object foeShield = rig.getShield.invoke(glimmer);
        Object ownShield = rig.getShield.invoke(aurora);
        for (int f = 0; f < settleFrames; f++) {
            setVec(rig, rig.shipGetVelocity.invoke(glimmer), 0f, 0f);
            setVec(rig, rig.shipGetVelocity.invoke(aurora), 0f, 0f);
            zeroAngVel(rig, glimmer);
            zeroAngVel(rig, aurora);
            holdShield(rig, foeShield, true, foeSfaceDeg);
            holdShield(rig, ownShield, cell.ownShieldOn, Float.NaN);
            step.step(fixedDt, tick++);
            refill(rig, aurora);
            refill(rig, glimmer);
        }
        System.out.println(P + " cell_posed name=" + cell.name + " tick=" + tick
                + " foe_shield=" + shieldWord(rig, foeShield)
                + " foe_sface=" + f1(shieldFacingOrNaN(rig, foeShield))
                + " own_shield=" + shieldWord(rig, ownShield));

        // ---- drive + measure ----
        float[] prevOwn = pose(rig, aurora);
        float[] prevFoe = pose(rig, glimmer);
        List<Integer> kickTicks = new ArrayList<Integer>();
        boolean thrusting = true;
        int contactTick = -1;
        int releaseStartT = -1;
        float releaseStartDist = Float.NaN;
        int recedingRun = 0;
        for (int t = 0; t < cellCapFrames; t++) {
            float dx = prevFoe[0] - prevOwn[0];
            float dy = prevFoe[1] - prevOwn[1];
            float dist = (float) Math.hypot(dx, dy);
            boolean guide = thrusting
                    && (cell.sustained || dist > 300f)
                    && (contactTick < 0 || cell.sustained);
            if (guide) {
                float ux = dx / Math.max(dist, 1e-3f);
                float uy = dy / Math.max(dist, 1e-3f);
                setVec(rig, rig.shipGetVelocity.invoke(aurora), ux * cell.speed, uy * cell.speed);
                rig.entSetFacing.invoke(aurora,
                        (float) Math.toDegrees(Math.atan2(uy, ux)));
                zeroAngVel(rig, aurora);
            }
            if (cell.pinFoe && thrusting) {
                // ground-holding stand-in: pre-step foe velocity is always zero, so the
                // post-step foe velocity IS the engine's per-tick applied impulse.
                setVec(rig, rig.shipGetVelocity.invoke(glimmer), 0f, 0f);
                zeroAngVel(rig, glimmer);
            }
            holdShield(rig, foeShield, true, foeSfaceDeg);
            holdShield(rig, ownShield, cell.ownShieldOn, Float.NaN);
            step.step(fixedDt, tick++);
            refill(rig, aurora);
            refill(rig, glimmer);
            float[] own = pose(rig, aurora);
            float[] foe = pose(rig, glimmer);
            float distNow = (float) Math.hypot(foe[0] - own[0], foe[1] - own[1]);
            float foeDv = (cell.pinFoe && thrusting)
                    ? (float) Math.hypot(foe[2], foe[3])
                    : (float) Math.hypot(foe[2] - prevFoe[2], foe[3] - prevFoe[3]);
            float snap = Math.max(residual(prevOwn, own, fixedDt), residual(prevFoe, foe, fixedDt));
            r.snapMax = Math.max(r.snapMax, snap);
            if (distNow < r.distMin) {
                r.distMin = distNow;
            }
            boolean kicked = foeDv >= KICK_MIN && distNow < 500f;
            if (kicked) {
                kickTicks.add(t);
                if (contactTick < 0) {
                    contactTick = t;
                    r.contact = true;
                    r.contactOnsetDist = (float) Math.hypot(prevFoe[0] - prevOwn[0],
                            prevFoe[1] - prevOwn[1]);
                    r.contactOnsetSpeed = (float) Math.hypot(prevOwn[2], prevOwn[3]);
                    r.firstKickDv = foeDv;
                    r.foeShieldAtOnset = shieldWord(rig, foeShield);
                    r.ownShieldAtOnset = shieldWord(rig, ownShield);
                    float bearingFoeToOwn = (float) Math.toDegrees(
                            Math.atan2(own[1] - foe[1], own[0] - foe[0]));
                    r.foeSfaceMissAtOnset = Math.abs(normDeg(
                            shieldFacingOrNaN(rig, foeShield) - bearingFoeToOwn));
                }
                System.out.println(P + " kick cell=" + cell.name + " t=" + t
                        + " dv=" + f1(foeDv) + " dist=" + f1(distNow)
                        + " foe_v=(" + f1(foe[2]) + "," + f1(foe[3]) + ")");
            }
            if (cell.sustained && thrusting && contactTick >= 0 && distNow <= CR_SCALE_MIN) {
                r.overlapTicks++;
            }
            if (distNow <= 320f || t % 25 == 0 || kicked) {
                System.out.println(P + " s cell=" + cell.name + " t=" + t
                        + " dist=" + f1(distNow)
                        + " own=(" + f1(own[0]) + "," + f1(own[1]) + "," + f1(own[2]) + ","
                        + f1(own[3]) + "," + f1(own[4]) + ")"
                        + " foe=(" + f1(foe[0]) + "," + f1(foe[1]) + "," + f1(foe[2]) + ","
                        + f1(foe[3]) + "," + f1(foe[4]) + ")"
                        + " fsh=" + shieldWord(rig, foeShield) + "@"
                        + f1(shieldFacingOrNaN(rig, foeShield))
                        + " osh=" + shieldWord(rig, ownShield)
                        + " snap=" + f2(snap));
            }
            if (!rig.alive(aurora) || !rig.alive(glimmer)) {
                r.died = true;
                System.out.println(P + " died cell=" + cell.name + " t=" + t
                        + " own_alive=" + rig.alive(aurora) + " foe_alive=" + rig.alive(glimmer));
                break;
            }
            // ---- phase transitions / termination ----
            if (cell.sustained) {
                if (thrusting && contactTick >= 0 && t >= contactTick + sustainFrames) {
                    thrusting = false;
                    releaseStartT = t;
                    releaseStartDist = distNow;
                    System.out.println(P + " release cell=" + cell.name + " t=" + t
                            + " dist=" + f1(distNow));
                }
                if (!thrusting && t >= releaseStartT + releaseFrames) {
                    r.releaseRate = (distNow - releaseStartDist)
                            / ((t - releaseStartT) * fixedDt);
                    break;
                }
                if (thrusting && contactTick < 0 && t > cellCapFrames * 2 / 3) {
                    break; // never reached contact — reported, graded as missing
                }
            } else {
                if (contactTick >= 0 && t >= contactTick + postContactFrames) {
                    break;
                }
                recedingRun = distNow > dist ? recedingRun + 1 : 0;
                if (contactTick < 0 && distNow > 1400f && recedingRun > 30) {
                    break; // flew past without contact
                }
            }
            prevOwn = own;
            prevFoe = foe;
        }
        spacingStats(kickTicks, r);
        System.out.println(P + " cell_done name=" + cell.name + " contact=" + r.contact
                + " onset=" + f1(r.contactOnsetDist) + " kick1=" + f1(r.firstKickDv)
                + " kicks=" + r.kicks + " dmin=" + f1(r.distMin)
                + " overlap_t=" + r.overlapTicks + " snap_max=" + f2(r.snapMax)
                + " release_rate=" + f1(r.releaseRate) + " died=" + r.died);
        return r;
    }

    /** Single-tick integration residual: |pos1 - pos0 - v·dt|, min over v0/v1 (engine order). */
    private static float residual(float[] p0, float[] p1, float dt) {
        float r0 = (float) Math.hypot(p1[0] - p0[0] - p0[2] * dt, p1[1] - p0[1] - p0[3] * dt);
        float r1 = (float) Math.hypot(p1[0] - p0[0] - p1[2] * dt, p1[1] - p0[1] - p1[3] * dt);
        return Math.min(r0, r1);
    }

    private static void spacingStats(List<Integer> kickTicks, CellResult r) {
        r.kicks = kickTicks.size();
        if (kickTicks.size() < 2) {
            return;
        }
        List<Integer> gaps = new ArrayList<Integer>();
        for (int i = 1; i < kickTicks.size(); i++) {
            gaps.add(kickTicks.get(i) - kickTicks.get(i - 1));
        }
        java.util.Collections.sort(gaps);
        r.kickMinSpacing = gaps.get(0);
        r.kickMedianSpacing = gaps.get(gaps.size() / 2);
    }

    /** {x, y, vx, vy, facing} off the live ship (reflective, per tick). */
    private static float[] pose(Rig rig, Object ship) throws Exception {
        Object loc = rig.shipGetLocation.invoke(ship);
        Object vel = rig.shipGetVelocity.invoke(ship);
        float facing = ((Number) rig.entGetFacing.invoke(ship)).floatValue();
        return new float[]{rig.vecX.getFloat(loc), rig.vecY.getFloat(loc),
                rig.vecX.getFloat(vel), rig.vecY.getFloat(vel), facing};
    }

    private static void setVec(Rig rig, Object vec, float x, float y) throws Exception {
        rig.vecX.setFloat(vec, x);
        rig.vecY.setFloat(vec, y);
    }

    private static void zeroAngVel(Rig rig, Object ship) throws Exception {
        if (rig.entSetAngVel != null) {
            rig.entSetAngVel.invoke(ship, 0f);
        }
    }

    /** Kill-free scaffold: hull refilled post-step (collision damage must not end the cell). */
    private static void refill(Rig rig, Object ship) throws Exception {
        if (rig.entSetHp != null && rig.entGetMaxHp != null) {
            rig.entSetHp.invoke(ship, ((Number) rig.entGetMaxHp.invoke(ship)).floatValue());
        }
        if (rig.fluxSetCurr != null) {
            Object tracker = rig.getFluxTracker.invoke(ship);
            if (tracker != null) {
                rig.fluxSetCurr.invoke(tracker, 0f);
            }
        }
    }

    /** Hold a shield in the cell's posture: on/off + (optionally) a forced OMNI facing. */
    private static void holdShield(Rig rig, Object shield, boolean on, float forcedFacingDeg)
            throws Exception {
        if (shield == null) {
            return;
        }
        boolean isOn = (Boolean) rig.shieldIsOn.invoke(shield);
        if (on && !isOn) {
            rig.shieldToggleOn.invoke(shield);
        } else if (!on && isOn) {
            rig.shieldToggleOff.invoke(shield);
        }
        if (on && !Float.isNaN(forcedFacingDeg) && rig.shieldForceFacing != null) {
            rig.shieldForceFacing.invoke(shield, forcedFacingDeg);
        }
    }

    private static String shieldWord(Rig rig, Object shield) {
        if (shield == null) {
            return "none";
        }
        try {
            return (Boolean) rig.shieldIsOn.invoke(shield) ? "on" : "off";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    private static float shieldFacingOrNaN(Rig rig, Object shield) {
        if (shield == null) {
            return Float.NaN;
        }
        try {
            return ((Number) rig.shieldGetFacing.invoke(shield)).floatValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    private static float massOrNaN(Rig rig, Object ship) {
        if (rig.entGetMass == null) {
            return Float.NaN;
        }
        try {
            return ((Number) rig.entGetMass.invoke(ship)).floatValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    private static Object byVariant(Rig rig, String variantId) throws Exception {
        for (Object ship : rig.ships()) {
            if (variantId.equals(rig.variantId(ship))) {
                return ship;
            }
        }
        return null;
    }

    /** Signed degrees normalized into (-180, 180]. */
    private static float normDeg(float d) {
        d %= 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d <= -180f) {
            d += 360f;
        }
        return d;
    }

    private static Rig buildRig() throws Exception {
        ClassLoader loader = RealEngineRamProbeMain.class.getClassLoader();
        Rig rig = new Rig();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        rig.shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> entityApi = Class.forName(ENTITY_API, false, loader);
        Class<?> shieldApi = Class.forName(SHIELD_API, false, loader);
        Class<?> fluxApi = Class.forName(FLUX_API, false, loader);
        Class<?> vec2 = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        rig.engine = engineClass.getMethod("getInstance").invoke(null);
        if (rig.engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after gated create()");
        }
        rig.getObjects = engineClass.getMethod("getObjects");
        rig.repoGetList = rig.getObjects.invoke(rig.engine).getClass()
                .getMethod("getList", Class.class);
        rig.isShipAlive = engineClass.getMethod("isShipAlive", shipApi);
        rig.getVariant = rig.shipClass.getMethod("getVariant");
        rig.getShipAI = rig.shipClass.getMethod("getShipAI");
        rig.setShipAI = findOneArg(rig.shipClass, "setShipAI");
        rig.getWeaponGroupsCopy = rig.shipClass.getMethod("getWeaponGroupsCopy");
        rig.getShield = rig.shipClass.getMethod("getShield");
        rig.getFluxTracker = rig.shipClass.getMethod("getFluxTracker");
        rig.shipGetLocation = rig.shipClass.getMethod("getLocation");
        rig.shipGetVelocity = rig.shipClass.getMethod("getVelocity");
        rig.entGetFacing = entityApi.getMethod("getFacing");
        rig.entSetFacing = entityApi.getMethod("setFacing", float.class);
        rig.entGetAngVel = optional(entityApi, "getAngularVelocity");
        rig.entSetAngVel = optional(entityApi, "setAngularVelocity", float.class);
        rig.entGetMass = optional(entityApi, "getMass");
        rig.entGetMaxHp = optional(entityApi, "getMaxHitpoints");
        rig.entSetHp = optional(entityApi, "setHitpoints", float.class);
        rig.shieldIsOn = shieldApi.getMethod("isOn");
        rig.shieldToggleOn = shieldApi.getMethod("toggleOn");
        rig.shieldToggleOff = shieldApi.getMethod("toggleOff");
        rig.shieldGetFacing = shieldApi.getMethod("getFacing");
        rig.shieldGetActiveArc = shieldApi.getMethod("getActiveArc");
        rig.shieldForceFacing = optional(shieldApi, "forceFacing", float.class);
        rig.fluxSetCurr = optional(fluxApi, "setCurrFlux", float.class);
        rig.fluxIsOverloaded = optional(fluxApi, "isOverloaded");
        rig.vecX = vec2.getField("x");
        rig.vecY = vec2.getField("y");
        return rig;
    }

    private static Method optional(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method findOneArg(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + cls.getName());
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String f1(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }

    private static String f2(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }
}
