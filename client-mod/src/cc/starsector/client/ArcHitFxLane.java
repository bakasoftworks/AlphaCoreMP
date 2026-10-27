package cc.starsector.client;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.RealityDisruptorChargeGlow;
import com.fs.starfarer.api.impl.combat.dweller.DwellerShroud;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.ThreatSwarmAI;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * The GPEE v13 {@code ARC_HIT} (kind 9) presenter (the {@link PaladinDetFxLane}
 * template).
 * Fixes D-INIMPHASEDMG + D-INIMDMGNUM: the replica wrappers used to re-run
 * vanilla {@code findTarget} LOCALLY (an invented target — a phased hull the local pick skipped,
 * a stale replica pose) and printed the NOMINAL projectile damage. Now the server names the hit
 * from its own {@code DamageListener} seam (victim ship, the arc's node world point, the APPLIED
 * armor/hull/shield split) and this lane draws the damage-free arc to the server's node and prints
 * the vanilla-shaped floaty from the wire split. D-VOLTAICEMP gains the true-node arc the same way.
 *
 * <p><b>One presenter</b> ({@code CcWrapPaladinEffect} law): the wrappers'
 * ({@link CcWrapInimicalEmanationOnFireEffect}, {@link CcWrapVoltaicDischargeOnFireEffect}) replica
 * branches keep vanilla's no-target flail (local {@code findTarget} null) and
 * the MISSILE hit (local pick is a {@code MissileAPI}): the server's
 * {@code ArcHitServeProbe} names SHIP victims only ({@code arc_hit_skip_nonship}), so a missile
 * kill never rides the wire and the wrapper draws it locally through {@link #presentInimical} /
 * {@link #presentVoltaic} (anchor = the missile twin, damage-free; the server ends the missile).
 * A local SHIP pick is census-only (door shut) because THIS lane owns every ship hit the server
 * saw. Residual: a local missile pick while the server picked a ship = two arcs; both picks are
 * the same deterministic nearest-in-arc rule, so it needs a pose/liveness boundary — metered, not
 * hidden, by {@link #doubleMintSuspect()} (a wire ship hit within {@code DOUBLE_MINT_WINDOW_NANOS}
 * of a local missile arc from the same source, either order). Skips: (1) own-source
 * events — the own ship fires natively and runs vanilla {@code onFire} locally, a wire arc would
 * double-mint; (2) flavor {@code ARC_FLAVOR_OTHER} — tachyon/ion-beam/voltaic-cannon/sabot arcs
 * are already presented by their local wrappers; (3) an unresolvable source replica. The
 * projectile-ION arc (flavor 5) is presented HERE: {@code CcWrapIonCannonOnHitEffect}
 * shuts the replica bolt's own 25 % roll and hands this lane the local impact point
 * ({@link #noteLocalIonHit}), so {@link #presentIon} draws the server's arc from where the bolt
 * struck to the wire node — vanilla's own shape, one authoritative roll. The sabot MIRV child's
 * arc (flavor 6, {@code CcWrapSabotOnHitEffect}) is the same recipe at dam 0 and rides
 * the same note + body.
 *
 * <p><b>Damage-LESS by law</b>: {@code spawnEmpArcVisual}, never
 * {@code spawnEmpArc}; hull/flux/armor/EMP stay wire-authoritative (own-hull reconcile + the
 * per-mount disabled bit). The inimical / voltaic / lash looks were rewritten from the
 * functional spec — the vanilla arcs' look, drawn damage-free; the voltaic
 * from-point is the LOCAL swarm-fragment pick (the server cannot name a fragment), falling back to
 * the source location without a swarm or a fragment in range. Floaties mirror {@code Ship.applyDamage}'s
 * own placement (shield/armor at the hit point, hull at {@code loc + 0.6·(hit − loc)}), gated by the
 * engine's visibility rule {@code Misc.shouldShowDamageFloaty}.
 *
 * <p>Knob {@code cc.predict.arcHitFx} = {@code on|off}, DEFAULT ON — the FX-lane kill-switch idiom;
 * invalid values THROW at class load; statics live HERE,
 * never in PRCP. Everything in try/catch-Throwable; render-thread only; counters cumulative.
 */
final class ArcHitFxLane {

    /** The arc-hit FX kill-switch, {@code cc.predict.arcHitFx} = {@code on|off}, DEFAULT ON
     *  ({@code off} = no wire arcs/floaties; the wrappers' no-target flail still draws). */
    static final boolean ARC_HIT_FX = parseOnOff("cc.predict.arcHitFx",
            System.getProperty("cc.predict.arcHitFx", "on"));

    private static boolean parseOnOff(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    /** The client-side magnitude ceilings, the
     *  {@code SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU} idiom: the decoder proves the ARC_HIT
     *  slots FINITE and {@code >= 0} ({@code ClientGameplayEdgeEvent}) but not BOUNDED,
     *  and this lane paints an arc to the confessed node and a floaty of the confessed number.
     *  An absurd value SKIPS (counted), never clamps — a presented lie is worse than a gap. Pose
     *  ceiling = the missile-end lane's 1e6 su; damage ceiling = well above any vanilla single
     *  hit (the largest vanilla per-shot damage is ~4 digits; 1e5 leaves 25× headroom). */
    static final float ARC_HIT_POSE_CEIL_SU = 1_000_000f;
    static final float ARC_HIT_DMG_CEIL = 100_000f;
    /** Both vanilla scripts' arc thickness (inimical and voltaic). */
    private static final float ARC_THICKNESS = 30.0F;
    /** Vanilla voltaic fragment pick radius ({@code VoltaicDischargeOnFireEffect}). */
    private static final float VOLTAIC_FRAGMENT_RANGE = 150.0F;
    /** Vanilla hull-floaty placement factor. */
    private static final float HULL_FLOATY_LERP = 0.6F;

    /** Per-FLAVOR log cap (counters continue past it) — arcs are shot-scale (inimical 2755 rows
     *  in one test match); the first rows prove the lane fed, the meters carry the rest.
     *  One shared cap was spent by flavor 1/2 arcs by tick
     *  ~2000 of a threat flight, so no lash (3/4) line ever reached the sidelog — the cap is
     *  per flavor code now. The budget is PER ROUND ({@link #resetRound} at the
     *  match reset; before it, round 2+ of a serve logged zero arc rows), and slot 0 (OTHER /
     *  unknown) is never reached — those events skip before the log — so the bound is
     *  LOG_MAX × 4 = 80 rows per round, not 100. */
    private static final int LOG_MAX = 20;
    /** Flavor-slot count for the per-flavor log counters (codes 0..6; higher codes share slot 0). */
    private static final int LOG_FLAVOR_SLOTS = 7;

    // The projectile-ion arc (IonCannonOnHitEffect's numbers, verbatim).
    /** Vanilla ion arc thickness and colours (the bolt's own fringe). */
    private static final float ION_THICKNESS = 20.0F;
    private static final Color ION_FRINGE = new Color(25, 100, 155, 255);
    private static final Color ION_CORE = new Color(255, 255, 255, 255);
    /** The wire arc lands one-way latency + ≤1 drain tick after the replica bolt's local hit; a
     *  recorded hit older than this is a different bolt (the pulser fires ~4/s) — fall back. */
    private static final long ION_LOCAL_HIT_WINDOW_NANOS = 1_000_000_000L;
    /** Victim → the last replica ion bolt's impact point in the VICTIM's frame (offset from its
     *  location, un-rotated by its facing) + the nanoTime of the hit. WeakHashMap: dead hulls fall
     *  out. Combat thread only (the wrapper's onHit and this lane's present share it). */
    private static final java.util.Map<ShipAPI, float[]> localIonHit = new java.util.WeakHashMap<ShipAPI, float[]>();
    private static final java.util.Map<ShipAPI, Long> localIonHitAt = new java.util.WeakHashMap<ShipAPI, Long>();

    /** {@code CcWrapIonCannonOnHitEffect} (and {@code CcWrapSabotOnHitEffect}):
     *  a replica ion bolt / sabot child struck {@code victim} at {@code point} (world); the wire
     *  ION / SABOT arc for this hit starts here. Stored in the victim's frame so the arc rides the
     *  hull through the latency window. */
    static void noteLocalIonHit(ShipAPI victim, Vector2f point) {
        if (victim == null || point == null || victim.getLocation() == null) {
            return;
        }
        Vector2f rel = Vector2f.sub(point, victim.getLocation(), new Vector2f());
        Vector2f off = Misc.rotateAroundOrigin(rel, -victim.getFacing());
        localIonHit.put(victim, new float[] {off.x, off.y});
        localIonHitAt.put(victim, System.nanoTime());
    }

    /** The recorded local ion impact re-projected onto the victim NOW, or {@code null} when none
     *  landed inside {@link #ION_LOCAL_HIT_WINDOW_NANOS}. */
    private static Vector2f recentLocalIonHit(ShipAPI victim, long now) {
        Long at = localIonHitAt.get(victim);
        float[] off = localIonHit.get(victim);
        if (at == null || off == null || now - at > ION_LOCAL_HIT_WINDOW_NANOS
                || victim.getLocation() == null) {
            return null;
        }
        Vector2f world = Misc.rotateAroundOrigin(new Vector2f(off[0], off[1]), victim.getFacing());
        Vector2f.add(world, victim.getLocation(), world);
        return world;
    }

    /**
     * The vanilla ion arc ({@code IonCannonOnHitEffect}) on the damage-free
     * overload, both anchors on the victim: from = the replica bolt's recorded local impact point
     * (the wire carries only the terminus node), falling back to the victim's collision-circle
     * point facing the shooter (where a bolt from that source lands) when no local hit is on
     * record (the presented bolt missed locally — the reconcile-fought case); to = the wire node.
     * Impact cue = the arc's own impact set ({@code tachyon_lance_emp_impact}, played at damage
     * time by vanilla; here at the node). EMP/disable stay on the weapon-state lane.
     */
    static void presentIon(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            Vector2f to, CombatEntityAPI toAnchor, long now) {
        Vector2f from = target != null ? recentLocalIonHit(target, now) : null;
        if (from == null) {
            Vector2f at = target != null && target.getLocation() != null
                    ? target.getLocation() : to;
            Vector2f dir = Vector2f.sub(source.getLocation(), at, new Vector2f());
            float len = dir.length();
            float radius = target != null ? target.getCollisionRadius() : 0f;
            if (len > 0.001f && radius > 0f) {
                dir.scale(radius / len);
                from = Vector2f.add(at, dir, new Vector2f());
            } else {
                from = new Vector2f(to);
            }
        }
        engine.spawnEmpArcVisual(from, toAnchor, to, toAnchor, ION_THICKNESS, ION_FRINGE, ION_CORE);
        Global.getSoundPlayer().playSound("tachyon_lance_emp_impact", 1.0F, 1.0F, to, new Vector2f());
    }

    // The double-mint meter (see the class doc's One presenter paragraph). Statics live HERE (the
    // wrappers have no lane instance); WeakHashMap so dead replicas fall out; main thread only.
    private static final long DOUBLE_MINT_WINDOW_NANOS = 250_000_000L;
    private static final int DOUBLE_MINT_LOG_MAX = 10;
    private static final java.util.Map<ShipAPI, Long> localMissileArcAt = new java.util.WeakHashMap<ShipAPI, Long>();
    private static final java.util.Map<ShipAPI, Long> wireShipHitAt = new java.util.WeakHashMap<ShipAPI, Long>();
    private static int doubleMintSuspect;
    private static int doubleMintLogLines;

    /** A wrapper drew a LOCAL missile arc from {@code source}; bumps the
     *  double-mint meter when a wire ship hit from the same source landed just before. */
    static void noteLocalMissileArc(ShipAPI source) {
        if (source == null) {
            return;
        }
        long now = System.nanoTime();
        localMissileArcAt.put(source, now);
        Long wireAt = wireShipHitAt.get(source);
        if (wireAt != null && now - wireAt < DOUBLE_MINT_WINDOW_NANOS) {
            doubleMintSuspect(source, "local missile arc " + ((now - wireAt) / 1_000_000L) + "ms after wire ship hit");
        }
    }

    private static void doubleMintSuspect(ShipAPI source, String detail) {
        doubleMintSuspect++;
        if (doubleMintLogLines < DOUBLE_MINT_LOG_MAX) {
            doubleMintLogLines++;
            PilotWatchLog.info("predict arc-hit FX: double-mint suspect src=" + source.getId() + " " + detail);
        }
    }

    /** Suspected double mints (local missile arc + wire ship hit, same source,
     *  within the window); cumulative, never reset. */
    static int doubleMintSuspect() {
        return doubleMintSuspect;
    }

    // The Overseer lash arcs (sizes per the functional spec's lash table).
    /** Vanilla enemy-arc thickness and core width. */
    private static final float LASH_THICKNESS = 60.0F;
    private static final float LASH_CORE_WIDTH = 40.0F;
    /** The SAME-OWNER lash ({@code EnergyLashSystemScript}: thickness 100, core 50). */
    private static final float LASH_FRIENDLY_THICKNESS = 100.0F;
    private static final float LASH_FRIENDLY_CORE_WIDTH = 50.0F;
    /** The server spawns one arc per SYSTEM slot 30 ms apart; arcs from one
     *  source inside this window cycle the replica's system slots in vanilla order. */
    private static final long LASH_VOLLEY_WINDOW_NANOS = 500_000_000L;
    /** Both flux lanes' lookback: an auth overload rising this soon after a lash hit on a PHASED
     *  hull IS the phase-field disruption. The script overloads in the spawn frame and the hit
     *  lands one tick later; {@code drainEdgeEvents} holds the arc until a snapshot at/after its
     *  tick, so arc-first is same-frame and flag-first is one snapshot interval — the window only
     *  needs to cover jitter/loss, never RTT (750 → 250 ms; a wider window let a
     *  hull that self-overloaded long after a lash wear the disruption colour). */
    static final long LASH_OVERLOAD_WINDOW_NANOS = 250_000_000L;
    private static final java.util.Map<ShipAPI, long[]> lashVolley = new java.util.WeakHashMap<ShipAPI, long[]>();
    private static long lastLashHitOwnNanos = OverloadWindowLaw.NO_ARC_NANOS;
    private static int lashHitsOwn;

    /** {@code true} while a wire LASH arc on the OWN hull is within the lookback —
     *  {@code maintainOwnFlux} paints the vanilla overload colour + floaty on the slaved rise.
     *  The sentinel-first read ({@link OverloadWindowLaw#arcWithinLookback})
     *  — the bare {@code now - Long.MIN_VALUE} wrapped negative and read "recent" before any hit. */
    static boolean lashHitOwnRecently() {
        return OverloadWindowLaw.arcWithinLookback(lastLashHitOwnNanos, System.nanoTime(), LASH_OVERLOAD_WINDOW_NANOS);
    }

    /** Wire LASH arcs that landed on the own hull (cumulative). */
    static int lashHitsOwn() {
        return lashHitsOwn;
    }

    /** The last wire LASH arc on the own hull ({@code System.nanoTime}; {@code Long.MIN_VALUE}
     *  before any) — the own-flux lane's held-floaty release reads it. */
    static long lastLashHitOwnNanos() {
        return lastLashHitOwnNanos;
    }

    // The REMOTE lash-overload colouring (as EnergyLashSystemScript paints it on a
    // non-own victim). ZERO-WIRE: the replica's overload STATE is slaved from the snapshot flux
    // flags (FoeStateLaneCoordinator.maintainFoeFluxFlags), so the COLOUR keys off the slaved rise
    // — the own-hull idiom (maintainOwnFlux): a lash arc on the hull inside
    // LASH_OVERLOAD_WINDOW_NANOS before the rise paints AT the rise. The other order is real too:
    // the script overloads in the SPAWN frame and the hit lands one tick later, and a snapshot
    // boundary can split them (the flag applies a frame before the arc drains) — so a rise with no
    // arc yet HOLDS the plain "Overloaded!" floaty for LASH_FLOATY_HOLD_NANOS and paints late when
    // the arc lands inside the hold. Reset = vanilla's every-frame plugin folded into the
    // slave's per-frame call: resetOverloadColor once !isOverloadedOrVenting, idempotent per ship.
    // PHASE GATE: vanilla begins the lash overload ONLY `if (target.isPhased())`
    // in the spawn frame — a lashed UNPHASED hull takes the arc's EMP and never overloads
    // from it, so an unphased hull that overloads inside the window did so on its own flux and
    // wears vanilla's plain colour. The frame carries the collision truth (ServerEntityPose
    // FLAG_PHASED, the same flux-flags byte the slave reads): the gate = phased on the PRIOR fresh
    // frame (the cloak drops ≤ 4 ticks after the overload, so the rise frame itself may already
    // read unphased — the prev-frame idiom of the own lane's prevOwnAuth* bits) OR on the rise
    // frame (the hull phased between the two frames). An unphased rise shows the plain floaty
    // IMMEDIATELY — the hold exists only for a paint the hull can still earn.
    // WeakHashMap so dead replicas fall out; render thread only.
    /** How long a slaved overload rise on a PHASE hull waits for a trailing lash arc before the
     *  plain floaty shows (the arc trails the flag by ≤ 1 server tick + jitter; a floaty this late
     *  is imperceptible). Unphased hulls never wait. */
    static final long LASH_FLOATY_HOLD_NANOS = 150_000_000L;
    private final java.util.Map<ShipAPI, Long> lashHitAt = new java.util.WeakHashMap<ShipAPI, Long>();
    private final java.util.Map<ShipAPI, Long> floatyHeldSince = new java.util.WeakHashMap<ShipAPI, Long>();
    private final java.util.Map<ShipAPI, Boolean> lashColored = new java.util.WeakHashMap<ShipAPI, Boolean>();
    private final java.util.Map<ShipAPI, Boolean> phasedLastFresh = new java.util.WeakHashMap<ShipAPI, Boolean>();
    private int lashHitsRemote;           // wire LASH arcs on a resolved NON-own ship target
    private int lashRemoteOverloads;      // painted at the slaved rise (arc landed first)
    private int lashRemoteOverloadsLate;  // painted on a held floaty (flag beat the arc by ≤ the hold)
    private int lashRemoteUnphasedSkips;  // rises with an arc inside the window on a hull the gate read UNPHASED (F2 class)

    /** {@code true} while a wire LASH arc on {@code foe} is inside the lookback — the remote twin
     *  of {@link #lashHitOwnRecently}, read by the foe flux-flags slave at its overload rise. */
    boolean lashHitRecently(ShipAPI foe) {
        Long at = lashHitAt.get(foe);
        return at != null && OverloadWindowLaw.arcWithinLookback(at, System.nanoTime(), LASH_OVERLOAD_WINDOW_NANOS);
    }

    /** The phase-gate memory: records {@code phasedNow} for {@code foe} on a fresh
     *  frame and returns the bit the PRIOR fresh frame carried ({@code false} before any). Called
     *  once per flux-flags pass, before the edges, so a rise reads the frame before it. */
    boolean rememberPhased(ShipAPI foe, boolean phasedNow, boolean freshSnapshot) {
        Boolean prior = freshSnapshot ? phasedLastFresh.put(foe, phasedNow) : phasedLastFresh.get(foe);
        return prior != null && prior;
    }

    /** A slaved rise with a lash arc inside the window on a hull the gate read UNPHASED: vanilla's
     *  plain colour + floaty (the F2 class the gate now refuses); counted for the post-flight read. */
    void noteUnphasedLashRise(ShipAPI foe, FluxTrackerAPI ft, long entityId) {
        lashRemoteUnphasedSkips++;
        ft.showOverloadFloatyIfNeeded();
        plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload lash=UNPHASED-SKIP (arc inside the window, hull unphased)"
                + " entity=" + entityId);
    }

    /** The slaved overload ROSE on {@code foe} with no lash arc in the lookback: hold the plain
     *  floaty for {@link #LASH_FLOATY_HOLD_NANOS} in case the arc is one tick behind the flag. */
    void holdOverloadFloaty(ShipAPI foe) {
        floatyHeldSince.put(foe, System.nanoTime());
    }

    /** Paints the phase-field disruption on a remote hull ({@code EnergyLashSystemScript}):
     *  the bright EMP overload colour + vanilla's floaty under the tracker's OWN visibility gate
     *  ({@code showFloaty()}; vanilla forces it only when a player ship is involved — never here:
     *  own-source arcs are skipped and the own hull rides {@code maintainOwnFlux}). The colour is
     *  ours to reset ({@link #maintainRemoteLashOverload}). */
    void paintRemoteLashOverload(ShipAPI foe, FluxTrackerAPI ft, boolean late, long entityId) {
        foe.setOverloadColor(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR_BRIGHT);
        ft.showOverloadFloatyIfNeeded("Phase Field Disruption!",
                VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, 4.0F, false);
        lashColored.put(foe, Boolean.TRUE);
        floatyHeldSince.remove(foe);
        if (late) {
            lashRemoteOverloadsLate++;
        } else {
            lashRemoteOverloads++;
        }
        plugin.foeFluxFlagsLane.log("predict foe-fluxflags overload lash=PHASE-FIELD-DISRUPTION"
                + (late ? " (arc after the rise)" : "") + " entity=" + entityId);
    }

    /** Every frame per remote hull (the flux-flags slave's call site, before its edges): releases a
     *  held floaty — the overload already ended ⇒ nothing (checked FIRST: an
     *  arc after the end must not paint a colour the next statement resets); a lash arc landed
     *  since the rise ⇒ the disruption paint (late); else the plain vanilla floaty once the hold
     *  lapses — and resets a painted colour once the overload ended, as vanilla's plugin does;
     *  idempotent, no-op for an untouched hull. */
    void maintainRemoteLashOverload(ShipAPI foe, FluxTrackerAPI ft, long entityId) {
        Long held = floatyHeldSince.get(foe);
        if (held != null) {
            Long hit = lashHitAt.get(foe);
            if (!ft.isOverloadedOrVenting()) {
                floatyHeldSince.remove(foe); // the overload ended inside the hold: nothing to show
            } else if (hit != null && hit >= held) {
                paintRemoteLashOverload(foe, ft, true, entityId);
            } else if (System.nanoTime() - held > LASH_FLOATY_HOLD_NANOS) {
                floatyHeldSince.remove(foe);
                ft.showOverloadFloatyIfNeeded(); // plain "Overloaded!", vanilla's own gate + text
            }
        }
        if (lashColored.containsKey(foe) && !ft.isOverloadedOrVenting()) {
            foe.resetOverloadColor();
            lashColored.remove(foe);
        }
    }

    /** Wire LASH arcs that landed on a resolved remote (non-own) hull (cumulative). */
    int lashHitsRemote() {
        return lashHitsRemote;
    }

    /** Remote disruption paints at the slaved rise (arc first). */
    int lashRemoteOverloads() {
        return lashRemoteOverloads;
    }

    /** Remote disruption paints released from a held floaty (flag first, arc inside the hold). */
    int lashRemoteOverloadsLate() {
        return lashRemoteOverloadsLate;
    }

    /** Slaved rises the phase gate refused despite a lash arc inside the window. */
    int lashRemoteUnphasedSkips() {
        return lashRemoteUnphasedSkips;
    }

    private final PredictReconcileCombatPlugin plugin;
    private int presented;          // predict_event_rebase_arc_hit_presented (arc drawn)
    private int skippedOwn;         // own-source events (vanilla onFire ran locally)
    private int skippedOther;       // ARC_FLAVOR_OTHER / unknown flavor (local wrappers present)
    private int skippedUnresolved;  // source replica / engine unresolvable
    private int skippedAbsurd;      // Confessed node pose / damage past the client ceiling
    private final int[] logLinesByFlavor = new int[LOG_FLAVOR_SLOTS]; // A cap per flavor code

    ArcHitFxLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** The per-flavor log budget is round-local (the MRST rebuild fields fresh
     *  hulls; a fresh round's first arcs are the rows a reader wants). The meters stay cumulative. */
    void resetRound() {
        java.util.Arrays.fill(logLinesByFlavor, 0);
    }

    /** PRCP kind-9 intake (route-FIRST, the trap law). {@code own} = PRCP's
     *  {@code entityId == session.shipId()} verdict for the SOURCE. */
    void present(ClientGameplayEdgeEvent event, boolean own) {
        if (!ARC_HIT_FX) {
            return;
        }
        try {
            if (own) {
                skippedOwn++;
                return;
            }
            int flavor = event.arcFlavor();
            if (flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_INIMICAL
                    && flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_VOLTAIC
                    && flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_LASH
                    && flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_LASH_FRIENDLY
                    && flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_ION
                    && flavor != ClientGameplayEdgeEvent.ARC_FLAVOR_SABOT) {
                skippedOther++; // ARC_FLAVOR_OTHER / unknown: local wrappers present those
                return;
            }
            CombatEngineAPI engine = plugin.engine;
            ShipAPI source = resolve(event.entityId());
            if (engine == null || source == null || source.getLocation() == null) {
                skippedUnresolved++;
                return;
            }
            // Every wire ARC_HIT is a SHIP hit (the probe skips non-ships)
            long now = System.nanoTime();
            wireShipHitAt.put(source, now);
            Long localAt = localMissileArcAt.get(source);
            if (localAt != null && now - localAt < DOUBLE_MINT_WINDOW_NANOS) {
                doubleMintSuspect(source, "wire ship hit " + ((now - localAt) / 1_000_000L) + "ms after local missile arc");
            }
            if (!(Math.abs(event.hitX()) <= ARC_HIT_POSE_CEIL_SU)
                    || !(Math.abs(event.hitY()) <= ARC_HIT_POSE_CEIL_SU)
                    || !(dmgOrZero(event.dmgShield()) <= ARC_HIT_DMG_CEIL)
                    || !(dmgOrZero(event.dmgArmor()) <= ARC_HIT_DMG_CEIL)
                    || !(dmgOrZero(event.dmgHull()) <= ARC_HIT_DMG_CEIL)) {
                skippedAbsurd++; // wire-value guard: an absurd node/number never presents
                return;
            }
            ShipAPI target = resolve(event.targetWireId());
            Vector2f to = new Vector2f(event.hitX(), event.hitY());
            // Vanilla's no-target visual anchors the terminus on the source ship itself
            // (InimicalEmanationOnFireEffect, VoltaicDischargeOnFireEffect) — kept as the
            // fallback rather than a null anchor (null-anchor safety in the engine's ctor unverified).
            CombatEntityAPI toAnchor = target != null ? target : source;
            if (flavor == ClientGameplayEdgeEvent.ARC_FLAVOR_INIMICAL) {
                presentInimical(engine, source, target, to, toAnchor);
            } else if (flavor == ClientGameplayEdgeEvent.ARC_FLAVOR_LASH_FRIENDLY) {
                presentLashFriendly(engine, source, target, to, toAnchor); // No overload / flux bookkeeping — the friendly branch hurts nothing
            } else if (flavor == ClientGameplayEdgeEvent.ARC_FLAVOR_ION
                    || flavor == ClientGameplayEdgeEvent.ARC_FLAVOR_SABOT) {
                // The replica bolt's / sabot child's shut roll, drawn from
                // the wire — one body, the sabot arc IS the ion recipe at dam 0.
                presentIon(engine, source, target, to, toAnchor, now);
            } else if (flavor == ClientGameplayEdgeEvent.ARC_FLAVOR_LASH) {
                presentLash(engine, source, target, to, toAnchor);
                if (target != null && target == plugin.ownShip) {
                    lastLashHitOwnNanos = now; // the overload lane reads this (Phase Field Disruption!)
                    lashHitsOwn++;
                } else if (target != null) {
                    lashHitsRemote++;
                    lashHitAt.put(target, now); // the foe flux-flags slave reads this
                }
            } else {
                presentVoltaic(engine, source, target, to, toAnchor);
            }
            // The number for EVERY flavor is the wire's own split —
            // the server engine's ONE split (its Ship.applyDamage ran the armor formula once); the
            // ion/sabot re-apply through applyWireAckedHit re-split it (1.89 printed for
            // 13.97) and is gone. Hull/armor/flux stay wire-mirrored.
            presentFloaty(engine, source, target, event, to);
            presented++;
            int logSlot = flavor >= 0 && flavor < LOG_FLAVOR_SLOTS ? flavor : 0;
            if (logLinesByFlavor[logSlot] < LOG_MAX) {
                logLinesByFlavor[logSlot]++;
                PilotWatchLog.info("predict arc-hit FX: src=" + event.entityId()
                        + " tgt=" + event.targetWireId() + " flavor=" + flavor
                        + " shield=" + event.isShieldHit()
                        + " at=" + (int) to.x + "," + (int) to.y
                        + " armor=" + event.dmgArmor() + " hull=" + event.dmgHull()
                        + " sdmg=" + event.dmgShield() + " tick=" + event.simTick());
            }
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** Wire id → local ShipAPI: the own ship via the session id, else the PRCP replica
     *  resolution idiom ({@code remoteEntities} when {@code registryShape}, else the 1v1 foe).
     *  Delegates to the ONE shared
     *  {@link WireShipResolver#resolve} (was a copy of the crit/missile-end lanes' lookup). */
    private ShipAPI resolve(long wireId) {
        return WireShipResolver.resolve(plugin, wireId);
    }

    /** The inimical-emanation HIT look on the damage-free arc, rewritten from the functional spec:
     *  one {@code DwellerShroud.SHROUD_GLOW_COLOR} arc (fringe = core,
     *  thickness = core width = {@link #ARC_THICKNESS}) from the origin to {@code to}, then the
     *  engine's own INIMICAL_EMANATION particles and one {@code inimical_emanation_impact} sound at
     *  that end (the sound plays even when no arc came back). Origin = {@link #shroudArcOrigin}
     *  around the source centre when the source wears a dweller shroud (the arc then starts faded
     *  out), else the source location. Package-visible: the wrapper's missile
     *  branch reuses it with {@code target = null}, {@code toAnchor} = the missile twin.
     * */
    static void presentInimical(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            Vector2f to, CombatEntityAPI toAnchor) {
        DwellerShroud shroud = DwellerShroud.getShroudFor(source);
        Vector2f from;
        if (shroud != null && shroud.getParams() != null) {
            Vector2f centre = source.getLocation();
            Vector2f victim = victimPoint(target, to);
            float[] at = shroudArcOrigin(centre.x, centre.y, victim.x, victim.y,
                    shroud.getParams().maxOffset, (float) Math.random(), (float) Math.random());
            from = new Vector2f(at[0], at[1]);
        } else {
            from = new Vector2f(source.getLocation());
        }
        EmpArcEntityAPI.EmpArcParams look = new EmpArcEntityAPI.EmpArcParams();
        look.fadeOutDist = 150.0F;
        look.flickerRateMult = inimicalFlickerRate((float) Math.random());
        look.glowSizeMult = 0.5F;
        look.maxZigZagMult = 0.25F;
        look.minFadeOutMult = 5.0F;
        look.segmentLengthMult = 4.0F;
        look.zigZagReductionFactor = 1.0F;
        Color glow = DwellerShroud.SHROUD_GLOW_COLOR;
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, source, to, toAnchor, ARC_THICKNESS, glow, glow, look);
        if (arc != null) {
            arc.setCoreWidthOverride(ARC_THICKNESS);
            arc.setSingleFlickerMode();
            arc.setRenderGlowAtStart(false);
            if (shroud != null) {
                arc.setFadedOutAtStart(true);
            }
            arc.setWarping(0.2F);
        }
        RealityDisruptorChargeGlow.spawnEMPParticles(
                RealityDisruptorChargeGlow.EMPArcHitType.INIMICAL_EMANATION, null, to, toAnchor);
        Global.getSoundPlayer().playSound("inimical_emanation_impact", 1.0F, 1.0F, to, new Vector2f());
    }

    /** The shrouded inimical arc's origin law (game-free, from the functional spec): a point
     *  around the source centre {@code (cx, cy)} at {@link #inimicalOriginDistance} of the shroud's
     *  {@code maxOffset}, on the bearing centre → victim turned by {@link #inimicalOriginBearing}.
     *  {@code uDist} / {@code uTurn} = uniform draws in [0, 1). Returns {@code {x, y}}. */
    static float[] shroudArcOrigin(float cx, float cy, float victimX, float victimY, float maxOffset,
            float uDist, float uTurn) {
        float dist = inimicalOriginDistance(maxOffset, uDist);
        float toVictimDeg = (float) Math.toDegrees(Math.atan2(victimY - cy, victimX - cx));
        double bearing = Math.toRadians(inimicalOriginBearing(toVictimDeg, uTurn));
        return new float[] {cx + (float) (Math.cos(bearing) * dist), cy + (float) (Math.sin(bearing) * dist)};
    }

    /** Origin distance band: {@code maxOffset × [0.5, 0.75)} for a uniform draw {@code u} in [0, 1). */
    static float inimicalOriginDistance(float maxOffset, float u) {
        return maxOffset * (0.5F + 0.25F * u);
    }

    /** Origin bearing band: the bearing to the victim turned by {@code [-90, +90)} degrees for a
     *  uniform draw {@code u} in [0, 1). */
    static float inimicalOriginBearing(float bearingToVictimDeg, float u) {
        return bearingToVictimDeg + (u * 180.0F - 90.0F);
    }

    /** The inimical arc's flicker-rate multiplier: uniform {@code [0.75, 1.0)} for {@code u} in [0, 1). */
    static float inimicalFlickerRate(float u) {
        return 0.75F + 0.25F * u;
    }

    /** The point an arc aims at: the resolved victim ship's centre, else the arc's end {@code to}
     *  (the missile twin's location on the wrappers' missile branch; the wire node when the
     *  victim does not resolve). */
    private static Vector2f victimPoint(ShipAPI target, Vector2f to) {
        return target != null && target.getLocation() != null ? target.getLocation() : to;
    }

    /** The voltaic-discharge HIT look on the damage-free arc, rewritten from the functional spec:
     *  from-point = the source swarm's fragment that the parent class's own
     *  public {@code pickFragmentTowardsPointWithinRange} picks toward the victim within
     *  {@link #VOLTAIC_FRAGMENT_RANGE} — marked recently-picked for 1 s and flashed through its own
     *  {@code flash()} (forced in, out over 0.25 s), the arc starting at the fragment's LIVE location
     *  and following it every frame — else a copy of the source location when no swarm / fragment
     *  qualifies (this lane's fallback: a hit the server dealt keeps its arc; the wrapper's missile
     *  branch pre-checks the pick and draws nothing without one).
     *  Thickness {@link #ARC_THICKNESS}, core width 0.75 × it; colours = {@link #voltaicPalette};
     *  one {@code voltaic_discharge_emp_impact} sound at {@code to} (even when no arc came back).
     *  Package-visible: the wrapper's missile branch reuses it with
     *  {@code target = null} (so never the phased core), {@code toAnchor} = the missile twin.
     * */
    static void presentVoltaic(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            Vector2f to, CombatEntityAPI toAnchor) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(source);
        RoilingSwarmEffect.SwarmMember fragment = swarm == null ? null
                : VoltaicDischargeOnFireEffect.pickFragmentTowardsPointWithinRange(
                        swarm, victimPoint(target, to), VOLTAIC_FRAGMENT_RANGE);
        Vector2f from;
        if (fragment != null && fragment.loc != null) {
            fragment.setRecentlyPicked(1.0F);
            fragment.flash();
            if (fragment.flash != null) {
                fragment.flash.forceIn();
                fragment.flash.setDurationOut(0.25F);
            }
            from = fragment.loc;
        } else {
            from = new Vector2f(source.getLocation());
        }
        Color[] palette = voltaicPalette(VoltaicDischargeOnFireEffect.isSwarmPhaseMode(source),
                target != null && target.isPhased(), VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR,
                VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR, VoltaicDischargeOnFireEffect.PHASE_CORE_COLOR);
        EmpArcEntityAPI.EmpArcParams look = new EmpArcEntityAPI.EmpArcParams();
        look.fadeOutDist = 1000.0F;
        look.flickerRateMult = 1.0F;
        look.glowAlphaMult = 0.75F;
        look.glowSizeMult = 0.5F;
        look.minFadeOutMult = 1.0F;
        look.segmentLengthMult = 8.0F;
        look.zigZagReductionFactor = 0.5F;
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, source, to, toAnchor, ARC_THICKNESS,
                palette[0], palette[1], look);
        if (arc != null) {
            arc.setCoreWidthOverride(0.75F * ARC_THICKNESS);
            arc.setSingleFlickerMode();
            arc.setUpdateFromOffsetEveryFrame(true);
            arc.setRenderGlowAtStart(false);
            arc.setFadedOutAtStart(true);
        }
        Global.getSoundPlayer().playSound("voltaic_discharge_emp_impact", 1.0F, 1.0F, to, new Vector2f());
    }

    /** The voltaic hit-arc palette law (game-free; the colours are passed in), {@code {fringe, core}}:
     *  swarm phase mode ⇒ the phase fringe, plus the phase core only when the victim is a phased
     *  ship; otherwise the EMP fringe over a white core. */
    static Color[] voltaicPalette(boolean swarmPhaseMode, boolean victimPhased,
            Color empFringe, Color phaseFringe, Color phaseCore) {
        return new Color[] {swarmPhaseMode ? phaseFringe : empFringe,
                swarmPhaseMode && victimPhased ? phaseCore : Color.WHITE};
    }

    /** The Overseer lash ENEMY arc on the damage-free arc, rewritten from the
     *  functional spec: ONE arc per wire arc from the replica's next SYSTEM
     *  slot (the server fires one arc per slot, 30 ms apart — {@link #lashSlotPosition} cycles the
     *  slots per source inside {@link #LASH_VOLLEY_WINDOW_NANOS}) to the wire point anchored on the
     *  victim; thickness {@link #LASH_THICKNESS} / core {@link #LASH_CORE_WIDTH}; colours =
     *  {@link #lashPalette} (the phase pair on a PHASED victim); the arc sweeps from the lasher to
     *  the far end (single flicker, sweep on); {@code energy_lash_fire_at_enemy} at the LASHER and
     *  one {@code energy_lash_enemy_impact} at {@code to} per arc. EMP, the 1 s phase overload and the
     *  per-mount disable stay wire-authoritative (own-flux slave +); the overload COLOUR +
     *  "Phase Field Disruption!" ride the slaved rise (own: {@code maintainOwnFlux}; remote:
     *  {@link #paintRemoteLashOverload}).
     * */
    static void presentLash(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            Vector2f to, CombatEntityAPI toAnchor) {
        Color[] palette = lashPalette(target != null && target.isPhased(),
                VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR,
                VoltaicDischargeOnFireEffect.PHASE_CORE_COLOR);
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(lashSlotPosition(source), source, to, toAnchor,
                LASH_THICKNESS, palette[0], palette[1], lashLook(0.4F));
        if (arc != null) {
            arc.setCoreWidthOverride(LASH_CORE_WIDTH);
            arc.setSingleFlickerMode(true);
        }
        Global.getSoundPlayer().playSound("energy_lash_fire_at_enemy", 1.0F, 1.0F,
                source.getLocation(), source.getVelocity());
        Global.getSoundPlayer().playSound("energy_lash_enemy_impact", 1.0F, 1.0F, to, new Vector2f());
    }

    /** The hostile lash palette law (game-free; the colours are passed in), {@code {fringe, core}}:
     *  a phased victim ⇒ the phase fringe over the phase core; otherwise the EMP fringe over white. */
    static Color[] lashPalette(boolean victimPhased, Color empFringe, Color phaseFringe, Color phaseCore) {
        return victimPhased ? new Color[] {phaseFringe, phaseCore} : new Color[] {empFringe, Color.WHITE};
    }

    /** The friendly lash palette law (game-free; the colours are passed in), {@code {fringe, core}}:
     *  an attack-swarm ally ⇒ the phase fringe, else the EMP fringe; the core is white either way. */
    static Color[] lashFriendlyPalette(boolean allyAttackSwarm, Color empFringe, Color phaseFringe) {
        return new Color[] {allyAttackSwarm ? phaseFringe : empFringe, Color.WHITE};
    }

    /** Both lash arcs' look (from the functional spec's lash table): segment length ×8,
     *  zig-zag reduction 0.15, fade-out distance 500, minimum fade-out ×2; the flicker rate is the
     *  one entry that differs between the two branches.
     *  the fields are assigned in field-name order. */
    private static EmpArcEntityAPI.EmpArcParams lashLook(float flickerRateMult) {
        EmpArcEntityAPI.EmpArcParams look = new EmpArcEntityAPI.EmpArcParams();
        look.fadeOutDist = 500.0F;
        look.flickerRateMult = flickerRateMult;
        look.minFadeOutMult = 2.0F;
        look.segmentLengthMult = 8.0F;
        look.zigZagReductionFactor = 0.15F;
        return look;
    }

    /** The Overseer lash SAME-OWNER arc on the damage-free arc (flavor 4; the
     *  friendly lash deals no damage / EMP in vanilla either), rewritten from the functional spec:
     *  ONE arc from the lasher's MEDIUM system slot
     *  ({@link #lashMainSlotPosition}) to the ally's CENTRE, anchored on the ally when it resolves
     *  (else the wire point on {@code toAnchor}); thickness {@link #LASH_FRIENDLY_THICKNESS} /
     *  core {@link #LASH_FRIENDLY_CORE_WIDTH}, flicker ×0.3; colours = {@link #lashFriendlyPalette}
     *  (the phase fringe when the ally is an attack swarm); the arc sweeps from the lasher to the
     *  ally (single flicker, sweep on); {@code energy_lash_fire} at the lasher and one
     *  {@code energy_lash_friendly_impact} at the arc's far end. What the lash DOES to the ally (its
     *  system fired, the lasher's cooldown) stays engine-authoritative: the ally's own SYSTEM_WINDOW
     *  edge drives its replica. The replica never lashes on its own any more — {@code energy_lash}
     *  is press-gated ({@link SpawnSystemGate#SCRIPT_SPAWN_SYSTEM_IDS}), so this wire arc is the ONLY
     *  friendly arc drawn. */
    static void presentLashFriendly(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            Vector2f to, CombatEntityAPI toAnchor) {
        boolean allyKnown = target != null && target.getLocation() != null;
        Vector2f end = allyKnown ? new Vector2f(target.getLocation()) : to;
        CombatEntityAPI endAnchor = allyKnown ? target : toAnchor;
        Color[] palette = lashFriendlyPalette(allyKnown && ThreatSwarmAI.isAttackSwarm(target),
                VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR);
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(lashMainSlotPosition(source), source, end, endAnchor,
                LASH_FRIENDLY_THICKNESS, palette[0], palette[1], lashLook(0.3F));
        if (arc != null) {
            arc.setCoreWidthOverride(LASH_FRIENDLY_CORE_WIDTH);
            arc.setSingleFlickerMode(true);
        }
        Global.getSoundPlayer().playSound("energy_lash_fire", 1.0F, 1.0F,
                source.getLocation(), source.getVelocity());
        Global.getSoundPlayer().playSound("energy_lash_friendly_impact", 1.0F, 1.0F, end, new Vector2f());
    }

    /** The lasher's MEDIUM system slot ({@code findSlots} {@code mainSlot}); no medium system
     *  slot ⇒ the first system slot; none ⇒ the source location. */
    private static Vector2f lashMainSlotPosition(ShipAPI source) {
        com.fs.starfarer.api.loading.WeaponSlotAPI main = null;
        if (source.getHullSpec() != null) {
            for (com.fs.starfarer.api.loading.WeaponSlotAPI slot : source.getHullSpec().getAllWeaponSlotsCopy()) {
                if (!slot.isSystemSlot()) {
                    continue;
                }
                if (main == null || slot.getSlotSize() == com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.MEDIUM) {
                    main = slot;
                }
            }
        }
        Vector2f at = main == null ? null : main.computePosition(source);
        return at != null ? at : new Vector2f(source.getLocation());
    }

    /** The next SYSTEM slot's world position on the replica ({@code findSlots} order),
     *  cycling per source within one volley window; no system slot ⇒ the source location. */
    private static Vector2f lashSlotPosition(ShipAPI source) {
        java.util.List<com.fs.starfarer.api.loading.WeaponSlotAPI> slots =
                new java.util.ArrayList<com.fs.starfarer.api.loading.WeaponSlotAPI>();
        if (source.getHullSpec() != null) {
            for (com.fs.starfarer.api.loading.WeaponSlotAPI slot : source.getHullSpec().getAllWeaponSlotsCopy()) {
                if (slot.isSystemSlot()) {
                    slots.add(slot);
                }
            }
        }
        if (slots.isEmpty()) {
            return new Vector2f(source.getLocation());
        }
        long now = System.nanoTime();
        long[] volley = lashVolley.get(source); // [lastNanos, nextIndex]
        if (volley == null || now - volley[0] > LASH_VOLLEY_WINDOW_NANOS) {
            volley = new long[] {now, 0L};
            lashVolley.put(source, volley);
        }
        int index = (int) (volley[1] % slots.size());
        volley[0] = now;
        volley[1] = index + 1;
        Vector2f at = slots.get(index).computePosition(source);
        return at != null ? at : new Vector2f(source.getLocation());
    }

    /** The vanilla-shaped floaty from the WIRE split ({@code Ship.applyDamage} placement:
     *  shield / armor at the hit point, hull
     *  at {@code loc + 0.6·(hit − loc)}), only for a resolved ship target and
     *  under the engine's own visibility rule. A number is presentation only. */
    private static void presentFloaty(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
            ClientGameplayEdgeEvent event, Vector2f to) {
        if (target == null || !Misc.shouldShowDamageFloaty(source, target)) {
            return;
        }
        if (event.isShieldHit()) {
            if (event.dmgShield() > 0f) {
                engine.addFloatingDamageText(to, event.dmgShield(),
                        Misc.FLOATY_SHIELD_DAMAGE_COLOR, target, source);
            }
            return;
        }
        if (event.dmgArmor() > 0f) {
            engine.addFloatingDamageText(to, event.dmgArmor(),
                    Misc.FLOATY_ARMOR_DAMAGE_COLOR, target, source);
        }
        if (event.dmgHull() > 0f && target.getLocation() != null) {
            Vector2f tl = target.getLocation();
            Vector2f hullAt = new Vector2f(tl.x + HULL_FLOATY_LERP * (to.x - tl.x),
                    tl.y + HULL_FLOATY_LERP * (to.y - tl.y));
            engine.addFloatingDamageText(hullAt, event.dmgHull(),
                    Misc.FLOATY_HULL_DAMAGE_COLOR, target, source);
        }
    }

    /** Arcs FX-presented — the meter-line liveness token. */
    int presented() {
        return presented;
    }

    /** The decoder's shield law leaves the unused damage slots NaN — NaN reads as 0 for the ceiling. */
    private static float dmgOrZero(float dmg) {
        return Float.isNaN(dmg) ? 0f : dmg;
    }


    /** Own-source events skipped (vanilla {@code onFire} ran locally). */
    int skippedOwn() {
        return skippedOwn;
    }

    /** Events whose confessed node pose or damage number sat past the client ceiling. */
    int skippedAbsurd() {
        return skippedAbsurd;
    }

    /** OTHER/unknown-flavor events skipped (local wrappers present those arcs). */
    int skippedOther() {
        return skippedOther;
    }

    /** Events whose source replica (or the engine) could not be resolved. */
    int skippedUnresolved() {
        return skippedUnresolved;
    }
}
