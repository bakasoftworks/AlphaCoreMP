package cc.starsector.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ServerEntityPose;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.ConstructionSwarmSystemScript;
import com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;
import com.fs.starfarer.api.impl.combat.threat.ThreatShipConstructionScript;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.util.Misc;

/**
 *
 * The Threat FABRICATION REVEAL presentation lane (else a fabricated hull is fully visible from the start), knob
 * {@code cc.predict.constructionFx} = {@code off|meter|apply} (default {@code apply}; {@code meter}
 * = the pairing check: log would-reveal/no-carrier at bind, write nothing). Zero wire bytes:
 * everything below is derived from rows the client already receives.
 *
 *
 * <p><b>Root.</b> Vanilla's whole reveal lives in
 * {@code ThreatShipConstructionScript}: alpha ramp, EMP-fringe
 * jitter outline, engine blackout, carrier-cloud flash params,
 * negative-nebula burial + members re-rolled around the hull,
 * the {@code construction_swarm_loop} sound. That script is
 * instantiated ONLY by the carrier swarm's {@code ThreatSwarmAI}; our
 * carrier replica gets the inert {@link SubstepHoldAI} ({@link PredictReconcileCombatPlugin#neutralizeRemoteBody}),
 * so no client ever runs it — the hull replica is minted from its growth row at
 * construction START (server {@code spawnShipOrWing} = T1) with alpha 1 and, via its own
 * {@code fragment_swarm} builtin, a full cloud of its own ({@code FragmentSwarmHullmod},
 * {@code RoilingSwarmEffect}) where vanilla has none until T2.
 *
 * <p><b>Fix = vanilla's presentation half, run LOCALLY on the replica pair.</b> At growth bind of
 * a treated replica whose hull is a Threat constructable (the hull tags vanilla's own
 * {@code CONSTRUCTABLE} list resolves to — {@code threat_combat}/{@code threat_overseer}/
 * {@code threat_hive}, {@code ConstructionSwarmSystemScript} + {@code ship_data.csv}
 * rows 70/96/97/124/125; the overseer test is vanilla's own {@code hasTag("threat_overseer")})
 * with an {@code attack_swarm_Construction} carrier replica
 * ({@code SwarmLauncherEffect.CONSTRUCTION_SWARM_VARIANT}, {@code ThreatSwarmAI})
 * within {@link #PAIR_RANGE}, this lane presents the script's reveal locally minus every SIM verb
 * (the per-frame body, finish and carrier scale rewritten from the functional spec;
 * the particle burst is the engine's own, run through a
 * never-registered subclass). Never written here: armor mult,
 * {@code setShipAI}/{@code setDefaultAI}, weapon groups, {@code DECELERATE},
 * the HULL's collision class, hitpoints/
 * {@code applyDamage} on the carrier, {@code setHoldFire},
 * the carrier pin (the server did it; the carrier's wire pose already sits on the
 * hull). The authoritative hull state (hp/cr/flux/poses) keeps arriving on the wire untouched.
 * EXCEPTION (else the cloud shows a hover readout beside the hull's): the
 * CARRIER's three spawn-time verbs (vanilla's:
 * the {@code SWARM_CONSTRUCTING_SHIP} tag, {@code CollisionClass.NONE}, hull-damage-taken ×0) ARE
 * replayed on the carrier replica at pairing ({@link #begin}) — they are local-body state the wire
 * never carries, and the engine's hover/target pick reads the damage stat.
 *
 * <p><b>Duration law (T1..T2).</b> {@code fadeInTime = BASE_CONSTRUCTION_TIME + dp *
 * CONSTRUCTION_TIME_DP_MULT (+ CONSTRUCTION_TIME_OVERSEER_EXTRA)} with {@code dp =
 * hullSpec.getSuppliesToRecover()} ({@code ConstructionSwarmSystemScript};
 * statics = 5 / 1.5 / 13), read off the replica's own hull spec. The one term
 * NOT wire-derivable is the first-two-frigates fast build ({@code data.constructionTime = 2.0F},
 * fabricator-local state) — so the timer is a ceiling, and WIRE TRUTH OUTRANKS
 * IT: the reveal ends the moment the carrier replica leaves play (vanilla consumes the carrier at
 * T2 ⇒ its despawn row IS the server's T2), or the hull replica fires
 * (replay-driven weapons only fire on a wire fire row). Whichever comes first snaps alpha to 1
 * and hands the hull its cloud. NO motion cutover:
 * the earlier speed test (&gt;30 su/s sustained 0.5 s) could not tell a
 * collision shove from an early server finish and snapped a shoved hull to alpha 1 five times in
 * one watch; vanilla keeps the reveal through any collision
 * (its {@code advance} has no velocity test) and the carrier-gone end already IS the server's T2,
 * bounding the fast-frigate case by one latency.
 *
 * <p><b>Exactly-one-owner.</b> (1) {@link SwarmSpriteGate} only hides the carrier's BASE sprite
 * ({@code setDoNotRender}); this lane never touches render flags — the two compose. (2)
 * {@link SpawnSystemGate} refuses the FABRICATOR's {@code construction_swarm} press on replicas
 * (the launch, T0); this lane presents the HULL (T1..T2) — different entity, different window.
 * (3) {@link CcWrapSwarmLauncherEffect} READS {@code SHIP_UNDER_CONSTRUCTION}; this lane is the
 * one WRITER of that vanilla tag on replicas (at enroll and at end, as vanilla) and
 * declines a body already carrying it, so no second presenter can enroll the same hull. (4)
 * {@code HulkKeepAliveAdapter} pins hulk PIECES to alpha 1 — on a mid-build kill this lane
 * ramps only the ship body at vanilla's 10× rate and never the pieces
 * (an open question; the adapter owns them). (5) The predicate requires
 * {@link CcReplicaEffectGate#isTreatedReplica} — in a serverless window (unbound gate) the
 * answer is {@code false} and vanilla's own script is the sole owner.
 *
 * <p>NEW-LANE LAW collaborator (deferred-refactors): PRCP keeps touchpoints only —
 * the bind relay after {@code neutralizeRemoteBody} at the growth spawn site, an advance relay,
 * a reset relay. Render-thread only. Fail-safe per build per frame: any throw ends that build
 * (alpha snapped to 1 + cloud released, best-effort), logs ONCE, and blacklists the body for this round.
 * Census: {@link CcReplicaEffectGate#replicaFrame}/{@link CcReplicaEffectGate#visualMinted}
 * under {@link #TAG} — the teardown {@code wsg census} line shows {@code construction=frames:N,
 * doorShut:0,visual:M} when the lane ran. {@code off} = the pre-T4 bare-hull mint, byte-for-byte.
 *
 * <p>Main review (its per-frame-body findings predate the rewrite): the
 * treated-replica ledger is written INSIDE {@link PredictReconcileCombatPlugin#neutralizeRemoteBody},
 * so the bind relay placed after it sees {@code true}; {@code HulkKeepAliveAdapter} sweeps only
 * fracture-family pieces ({@code FractureFamilyCoordinator.keepAliveWireFamilies}), so the 10×
 * hulk ramp below never fights it; the carrier hull {@code attack_swarm.ship} carries the
 * {@code fragment_swarm} builtin, so its {@code RoilingSwarmEffect} exists at pairing.
 */
final class ConstructionPresentLane {

    /** Census tag on the {@link CcReplicaEffectGate} line. */
    static final String TAG = "construction";

    /** Resolved {@code cc.predict.constructionFx} mode (the shared {@code off|meter|apply}
     *  vocabulary of every predict lane, {@link PredictReconcileCombatPlugin.PredictLane#parseMode}). */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.constructionFx", System.getProperty("cc.predict.constructionFx", "apply"));
    /** {@code meter} or {@code apply}: the bind predicate + carrier pairing run and are logged. */
    static final boolean METER = !"off".equals(MODE);
    /** {@code apply} only: the reveal is presented; {@code off}/{@code meter} keep the bare-hull
     *  status quo byte-for-byte. */
    static final boolean APPLY = "apply".equals(MODE);

    /** Hull tags of vanilla's CONSTRUCTABLE set ({@code ship_data.csv} rows 70/96/97/124/125). */
    static final String TAG_THREAT_COMBAT = "threat_combat";
    static final String TAG_THREAT_OVERSEER = "threat_overseer";
    static final String TAG_THREAT_HIVE = "threat_hive";

    /** The hullmod whose swarm the hull replica would otherwise fill at frame 1. */
    static final String FRAGMENT_SWARM_HULLMOD = "fragment_swarm";

    /**
     * Carrier pairing range at bind: vanilla spawns the hull AT the carrier and
     * pins the carrier to it every frame; 300 su covers wire quantization +
     * one snapshot of interp lag.
     */
    static final float PAIR_RANGE = 300f;
    /** A hull whose carrier row lands in the same drain AFTER it retries pairing this long. */
    private static final float PENDING_SECONDS = 1.0f;

    /**
     * Reveal tuning — game facts taken from the functional spec: the
     * jitter outline (range bonus in su, copies, colour alpha; a build of at most
     * {@link #JITTER_SHORT_BUILD} s gets the tent curve, a longer one fades over the last
     * {@link #JITTER_TAIL} s), the carrier-cloud slow-down (to {@link #CARRIER_SLOW_FLOOR} of its
     * speed over the first {@link #CARRIER_SLOW_SECONDS} s), the carrier flash (live while more than
     * {@link #FLASH_LIVE_ABOVE_REMAINING} s remain) and the burst (live while more than
     * {@link #BURST_LIVE_ABOVE_REMAINING} s remain).
     */
    private static final float MAX_JITTER_RANGE_BONUS = 25f;
    private static final int JITTER_COPIES = 3;
    private static final int JITTER_ALPHA = 127;
    static final float JITTER_SHORT_BUILD = 4f;
    static final float JITTER_TAIL = 2f;
    static final float CARRIER_SLOW_FLOOR = 0.25f;
    static final float CARRIER_SLOW_SECONDS = 2f;
    static final float FLASH_LIVE_ABOVE_REMAINING = 3f;
    private static final float FLASH_PEAK_FREQUENCY = 10f;
    private static final float FLASH_FULL_MEMBERS = 150f;
    private static final float FLASH_MIN_SIZE_FACTOR = 0.25f;
    private static final int FLASH_FRINGE_ALPHA = 200;
    private static final float FLASH_CORE_RADIUS_MULT = 1.5f;
    private static final float FLASH_RADIUS = 50f;
    static final float BURST_LIVE_ABOVE_REMAINING = 1f;
    private static final String LOOP_SOUND = "construction_swarm_loop";
    private static final int LOG_MAX = 12;

    /** One hull mid-reveal: the pair, the duration, the build clock and the borrowed engine burst. */
    private static final class Build {
        final ShipAPI ship;
        final ShipAPI carrier;
        final float fadeInTime;
        final float origMaxSpeed;
        /** The engine's own burst for this build ({@link ConstructionBurst}); never registered. */
        final ConstructionBurst burst;
        float elapsed;

        Build(ShipAPI ship, ShipAPI carrier, float fadeInTime, float origMaxSpeed) {
            this.ship = ship;
            this.carrier = carrier;
            this.fadeInTime = fadeInTime;
            this.origMaxSpeed = origMaxSpeed;
            ShipVariantAPI v = ship.getVariant();
            this.burst = new ConstructionBurst(v != null ? v.getHullVariantId() : null, carrier, fadeInTime);
        }
    }

    /** A growth-born constructable whose carrier was not yet in play at bind (retried per frame). */
    private static final class Pending {
        final ShipAPI ship;
        float waited;

        Pending(ShipAPI ship) {
            this.ship = ship;
        }
    }

    private final ArrayList<Build> builds = new ArrayList<Build>();
    private final ArrayList<Pending> pending = new ArrayList<Pending>();
    /** Carriers already presenting a hull (one carrier builds exactly one hull). Identity. */
    private final ArrayList<ShipAPI> pairedCarriers = new ArrayList<ShipAPI>();
    /** Bodies whose build threw — never re-enrolled this round. Identity. */
    private final ArrayList<ShipAPI> disabled = new ArrayList<ShipAPI>();

    private int reveals;
    /** {@code meter} mode: constructable binds that WOULD have revealed (carrier paired). */
    private int meterCandidates;
    private int endsCarrier;
    private int endsTimer;
    private int endsFire;
    private int endsHulk;
    private int pendingDropped;
    private int throwsSeen;
    private int logLines;
    /** Carrier-shape counters ({@link #onCarrierBound}): scaled / ratio outside the table / threw. */
    private int carrierScales;
    private int carrierScaleUnknown;
    private int carrierScaleThrows;

    /**
     * Bind relay — call ONCE per growth-born replica right after {@code neutralizeRemoteBody}
     * (so the treated-replica predicate already answers {@code true}). Declines silently for
     * everything that is not a constructable Threat hull; a constructable without a carrier in
     * range yet is parked in {@link #pending} and retried for {@link #PENDING_SECONDS}.
     * Returns {@code true} when a reveal began on this call.
     */
    boolean onReplicaBound(ShipAPI ship, CombatEngineAPI engine) {
        if (!METER || ship == null || engine == null) {
            return false;
        }
        try {
            if (!isConstructableReplica(ship) || contains(disabled, ship)) {
                return false;
            }
            if (ship.hasTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) {
                return false; // another owner already presents this hull (class doc (3))
            }
            ShipAPI carrier = findCarrier(ship, engine);
            if (!APPLY) {
                // meter = the pairing probe: prove the pairing fires from rows already received.
                if (carrier != null) {
                    meterCandidates++;
                }
                log("METER " + hullId(ship) + " on " + ident(ship) + (carrier != null
                        ? " would-reveal carrier=" + ident(carrier)
                        : " no-carrier within " + PAIR_RANGE + " su") + " (mode=meter, nothing written)");
                return false;
            }
            if (carrier == null) {
                // Park it — and mute its own cloud NOW: the builtin fills the swarm on the hull's
                // first engine step (in RoilingSwarmEffect), i.e. before the next retry.
                muteHullSwarm(ship, null);
                pending.add(new Pending(ship));
                log("PENDING " + hullId(ship) + " on " + ident(ship)
                        + " (constructable growth row, no carrier within " + PAIR_RANGE + " su yet)");
                return false;
            }
            return begin(ship, carrier);
        } catch (Throwable t) {
            throwsSeen++;
            disable(ship, "bind", t);
            return false;
        }
    }

    /**
     * An audit saw GAP + SERVER-ONLY cells on the
     * {@code attack_swarm_Construction} carrier under the operator's OWN fire (CLOSE {@code refEng=2000
     * wire=6698}) — hence the CARRIER's vanilla shape, applied to the replica at bind. Vanilla's fabricator
     * sizes the construction-swarm fighter to the hull it will build when it launches it; our replica
     * is minted from the variant by its growth row, so it kept the 24-su fighter body: the operator's
     * shots passed through the server-sized cloud where the server's body was hit — each such hit a
     * GAP cell. The keyframe's max hull is the ONE scaled number the client receives, so
     * {@code wireMax / localMax} names the size class ({@link #carrierScale} over
     * {@link ConstructionCarrierScaleLaw}); the max hull itself is mirrored by
     * {@code FoeHullReconcileLane.mirrorMaxHull} (the general lane, which runs AFTER this bind relay —
     * the ratio needs the pre-mirror local max).
     *
     * <p>Written once per carrier (the PRCP bind relay runs once per mint): every ORIGINAL bounds
     * segment re-set from its two endpoints scaled by the collision multiple about the ship origin;
     * the collision radius × the same multiple; when the radius multiple is not 1, the cloud's max
     * offset × the radius multiple (the cloud through {@code FragmentSwarmHullmod}'s public
     * create-or-get). Live growth-born construction-carrier fighters only (an admin {@code /spawnfoe
     * attack_swarm_Construction} is a roster row that never went through the fabricator — the PRCP
     * relay gates on {@code re.appended}); a ratio outside the table (a pose without the max-hull
     * field included) is counted + logged and the body left as minted (fail-open = the old unscaled
     * body); a throw is counted + logged. Not applied here: the speed mult (poses are wire-driven),
     * the base-sprite hide ({@link SwarmSpriteGate}), member counts (not wire-derivable). {@code off}
     * / {@code meter} keep the bare body. Rewritten from the functional spec.
     *
     */
    void onCarrierBound(ShipAPI ship, ServerEntityPose pose) {
        if (!APPLY || ship == null || pose == null) {
            return;
        }
        try {
            if (!ship.isFighter() || !ship.isAlive() || !isConstructionCarrier(ship)) {
                return;
            }
            float[] mult = carrierScale(pose.maxHp(), ship.getMaxHitpoints());
            if (mult == null) {
                carrierScaleUnknown++;
                log("CARRIER-SCALE unknown on " + ident(ship) + " wireMax=" + pose.maxHp()
                        + " localMax=" + ship.getMaxHitpoints() + " (outside the size table; body left as minted)");
                return;
            }
            float collisionMult = mult[0];
            float radiusMult = mult[1];
            BoundsAPI bounds = ship.getExactBounds();
            if (bounds != null) {
                for (SegmentAPI seg : bounds.getOrigSegments()) {
                    Vector2f p1 = seg.getP1();
                    Vector2f p2 = seg.getP2();
                    seg.set(p1.x * collisionMult, p1.y * collisionMult, p2.x * collisionMult, p2.y * collisionMult);
                }
            }
            ship.setCollisionRadius(ship.getCollisionRadius() * collisionMult);
            if (radiusMult != 1f) {
                RoilingSwarmEffect cloud = FragmentSwarmHullmod.createSwarmFor(ship);
                if (cloud != null) {
                    cloud.getParams().maxOffset *= radiusMult;
                }
            }
            carrierScales++;
        } catch (Throwable t) {
            carrierScaleThrows++;
            log("CARRIER-SCALE threw " + t.getClass().getSimpleName() + " on " + ident(ship)
                    + " (body left as far as the scale got)");
        }
    }

    /**
     * The pure carrier size law: {@code {collisionMult, radiusMult}} for a construction carrier whose
     * wire max hull is {@code wireMax} over its local (pre-mirror) {@code localMax}, or {@code null}
     * when the ratio names no size class (a non-positive local max, a pose without the field =
     * {@code wireMax < 0}, an off-table ratio). Frigate 2 / 1, destroyer 4 / 2, cruiser 6 / 3.5,
     * capital 8 / 4 (the class and the multiples = {@link ConstructionCarrierScaleLaw}).
     *
     */
    static float[] carrierScale(float wireMax, float localMax) {
        if (!(localMax > 0f)) {
            return null;
        }
        int scaleClass = ConstructionCarrierScaleLaw.carrierScaleClass(wireMax / localMax);
        if (scaleClass == ConstructionCarrierScaleLaw.SCALE_UNKNOWN) {
            return null;
        }
        return new float[] {ConstructionCarrierScaleLaw.carrierCollisionMult(scaleClass),
                ConstructionCarrierScaleLaw.carrierRadiusMult(scaleClass)};
    }

    /**
     * Tick — per live frame: retry pending pairs, then the vanilla {@code advance}
     * presentation half on every build (see class doc for the SIM verbs left out).
     */
    void advance(CombatEngineAPI engine, float amount) {
        if (!APPLY || engine == null || (builds.isEmpty() && pending.isEmpty())) {
            return;
        }
        if (!pending.isEmpty()) {
            advancePending(engine, amount);
        }
        if (engine.isPaused()) {
            return; // as vanilla
        }
        for (Iterator<Build> it = builds.iterator(); it.hasNext();) {
            Build b = it.next();
            try {
                if (advanceBuild(b, engine, amount)) {
                    it.remove();
                }
            } catch (Throwable t) {
                throwsSeen++;
                it.remove();
                remove(pairedCarriers, b.carrier);
                disable(b.ship, "advance", t);
            }
        }
    }

    /** Round wrap: every build and pending pair dies with the round (fail-soft snap to alpha 1). */
    void resetRound(CombatEngineAPI engine) {
        for (Build b : builds) {
            try {
                b.ship.removeTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION);
                b.ship.setAlphaMult(1f);
            } catch (Throwable ignore) {
                // reset-time sweep; never break the round wrap
            }
        }
        for (Pending p : pending) {
            try {
                releaseHullSwarm(p.ship);
            } catch (Throwable ignore) {
                // same: a dead body's swarm is gone with the round
            }
        }
        builds.clear();
        pending.clear();
        pairedCarriers.clear();
        disabled.clear();
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line). */
    String meterTokens() {
        return " predict_construction_mode=" + MODE
                + " predict_construction_reveals=" + reveals
                + " predict_construction_meter_candidates=" + meterCandidates
                + " predict_construction_ends_carrier=" + endsCarrier
                + " predict_construction_ends_timer=" + endsTimer
                + " predict_construction_ends_fire=" + endsFire
                + " predict_construction_ends_hulk=" + endsHulk
                + " predict_construction_pending_dropped=" + pendingDropped
                + " predict_construction_throws=" + throwsSeen
                + " predict_construction_carrier_scales=" + carrierScales
                + " predict_construction_carrier_scale_unknown=" + carrierScaleUnknown
                + " predict_construction_carrier_scale_throws=" + carrierScaleThrows;
    }

    // ------------------------------------------------------------------ pure laws (selftest-visible)
    // (the carrier size law lives engine-free in ConstructionCarrierScaleLaw)

    /**
     * The pure hull law: is this hull one vanilla's construction swarm can build? Vanilla's
     * {@code CONSTRUCTABLE} ids (in {@code ConstructionSwarmSystemScript}) are the
     * {@code skirmish/assault/standoff} ({@code threat_combat}), {@code overseer}
     * ({@code threat_overseer}) and {@code hive} ({@code threat_hive}) hulls — and NOT the
     * fabricator ({@code threat_fabricator}) nor the swarm fighter. Tag-keyed like
     * {@link DwellerHullLaw}; a null tag set answers {@code false} (fail closed = bare hull, the
     * status quo, never a hidden ship).
     */
    static boolean isConstructableHull(Set<String> hullTags) {
        return hullTags != null && (hullTags.contains(TAG_THREAT_COMBAT)
                || hullTags.contains(TAG_THREAT_OVERSEER)
                || hullTags.contains(TAG_THREAT_HIVE));
    }

    /**
     * The pure duration law, from {@code ConstructionSwarmSystemScript} verbatim
     * over its own statics; the fast-frigate {@code 2.0F} case is
     * fabricator-local and not derivable — the wire-truth cutovers bound it.
     */
    static float nominalFadeInTime(float suppliesToRecover, boolean overseer) {
        float t = ConstructionSwarmSystemScript.BASE_CONSTRUCTION_TIME
                + suppliesToRecover * ConstructionSwarmSystemScript.CONSTRUCTION_TIME_DP_MULT;
        if (overseer) {
            t += ConstructionSwarmSystemScript.CONSTRUCTION_TIME_OVERSEER_EXTRA;
        }
        return Math.max(0.5f, t);
    }

    /**
     * Is this variant the construction carrier? {@code ThreatSwarmAI.isConstructionSwarm}
     * keys on {@code getVariant().getHullVariantId()}; a member-first-minted replica may carry a
     * per-ship clone id, so the original-variant id and an id PREFIX are accepted too.
     */
    static boolean isConstructionCarrierVariant(String hullVariantId, String originalVariantId) {
        String want = SwarmLauncherEffect.CONSTRUCTION_SWARM_VARIANT;
        if (hullVariantId != null && (hullVariantId.equals(want) || hullVariantId.startsWith(want))) {
            return true;
        }
        return originalVariantId != null && originalVariantId.equals(want);
    }

    // ------------------------------------------------------------------ enrolment

    /** Treated replica + non-fighter/non-station + constructable hull tags (fail closed). */
    private static boolean isConstructableReplica(ShipAPI ship) {
        if (ship.isFighter() || ship.isStation() || ship.isHulk() || !ship.isAlive()) {
            return false;
        }
        if (!CcReplicaEffectGate.isTreatedReplica(ship)) {
            return false; // serverless window / untreated body → vanilla owns any reveal
        }
        ShipHullSpecAPI spec = ship.getHullSpec();
        return spec != null && isConstructableHull(spec.getTags());
    }

    /** The nearest unpaired live construction-carrier replica within {@link #PAIR_RANGE}. */
    private ShipAPI findCarrier(ShipAPI ship, CombatEngineAPI engine) {
        ShipAPI best = null;
        float bestDist = PAIR_RANGE;
        Vector2f at = ship.getLocation();
        for (ShipAPI s : engine.getShips()) {
            if (s == null || s == ship || !s.isFighter() || !s.isAlive() || s.isHulk()) {
                continue;
            }
            if (contains(pairedCarriers, s) || !isConstructionCarrier(s)) {
                continue;
            }
            float d = Misc.getDistance(s.getLocation(), at);
            if (d <= bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /** Package-private: {@link AuthoritativePresenter} mirrors vanilla's SILENT carrier kill (D-FABPOP). */
    static boolean isConstructionCarrier(ShipAPI s) {
        ShipVariantAPI v = s.getVariant();
        if (v == null) {
            return false;
        }
        if (isConstructionCarrierVariant(v.getHullVariantId(), v.getOriginalVariant())) {
            return true;
        }
        // Wing-linked fallback (roster-shape mint): the carrier's wing id.
        return s.getWing() != null
                && SwarmLauncherEffect.CONSTRUCTION_SWARM_WING.equals(s.getWing().getWingId());
    }

    /**
     * Enrol: the {@code spawnShip} presentation half — mute the hull's own cloud
     * (the swarm is created now, idempotently, so the flags land BEFORE the
     * builtin's first {@code advance} would fill it), copy the
     * carrier cloud's fringe colour, stop the carrier cloud outrunning its body,
     * plant the vanilla tag, and start dark (alpha 0 = the
     * vanilla ramp's frame-1 value) with engines already blacked out.
     */
    private boolean begin(ShipAPI ship, ShipAPI carrier) {
        ShipHullSpecAPI spec = ship.getHullSpec();
        float fadeInTime = nominalFadeInTime(spec.getSuppliesToRecover(),
                spec.hasTag(TAG_THREAT_OVERSEER));
        RoilingSwarmEffect sourceSwarm = RoilingSwarmEffect.getSwarmFor(carrier);
        float origMaxSpeed = sourceSwarm != null ? sourceSwarm.getParams().maxSpeed : 500f; //
        RoilingSwarmEffect swarm = muteHullSwarm(ship, sourceSwarm); //
        if (sourceSwarm != null) {
            sourceSwarm.getParams().outspeedAttachedEntityBy = 0f; //
            // Vanilla nulls this right before IT kills the carrier; here
            // the SERVER kills the carrier and the client learns after the fact (the common
            // carrier-gone finish), so the silence is planted at pairing or the T2 handoff pops.
            sourceSwarm.getParams().despawnSound = null;
        }
        ship.addTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION); // local, non-wire
        //
        // Vanilla's spawnShip
        // (ThreatShipConstructionScript) makes the CARRIER intangible and
        // invulnerable for the whole build — the tag, CollisionClass.NONE, HullDamageTakenMult ×0 —
        // and the engine's hover/target pick reads that stat
        // (isTargetable → isHullDamageable; the hover picker never excludes
        // fighters), so vanilla shows NO readout for the cloud once construction starts, only the
        // ship being built. The server's own script did exactly this to its body; the replica must
        // match, else own shots land locally on a cloud the server never damages and the cloud keeps
        // a hover box vanilla hides. Never restored: vanilla kills the carrier at T2 and
        // the server's despawn row removes the replica.
        carrier.addTag(ThreatShipConstructionScript.SWARM_CONSTRUCTING_SHIP); //
        carrier.setCollisionClass(CollisionClass.NONE); //
        carrier.getMutableStats().getHullDamageTakenMult().modifyMult("ThreatShipConstructionScript", 0f); //
        Build b = new Build(ship, carrier, fadeInTime, origMaxSpeed);
        ship.setAlphaMult(0f); // at progress 0
        ship.getEngineController().fadeToOtherColor(b, Misc.zeroColor, Misc.zeroColor, 1f, 1f); //
        builds.add(b);
        pairedCarriers.add(carrier);
        reveals++;
        CcReplicaEffectGate.replicaFrame(TAG);
        CcReplicaEffectGate.visualMinted(TAG);
        log("REVEAL " + hullId(ship) + " on " + ident(ship) + " carrier=" + ident(carrier)
                + " fadeIn=" + fadeInTime + "s hullSwarm=" + (swarm != null ? "muted" : "none")
                + " carrierSwarm=" + (sourceSwarm != null ? "yes" : "none")
                + " (vanilla ThreatShipConstructionScript presentation half, local)");
        return true;
    }

    /**
     * The {@code spawnShip} cloud mute (plus the fringe colour copy when the carrier's swarm is known): the hull's own
     * cloud stays empty until the reveal ends. Returns the swarm (null = no builtin, nothing to mute).
     */
    private static RoilingSwarmEffect muteHullSwarm(ShipAPI ship, RoilingSwarmEffect sourceSwarm) {
        RoilingSwarmEffect swarm = hullSwarmOrNull(ship);
        if (swarm != null) {
            swarm.getParams().withInitialMembers = false; //
            swarm.getParams().withRespawn = false;        //
            if (sourceSwarm != null) {
                swarm.getParams().flashFringeColor = sourceSwarm.getParams().flashFringeColor; //
            }
        }
        return swarm;
    }

    /** Undo {@link #muteHullSwarm} for a hull that never revealed (pending drop / round wrap):
     *  the builtin then fills the cloud on its next step — the bare-hull status quo. */
    private static void releaseHullSwarm(ShipAPI ship) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm != null) {
            swarm.getParams().withRespawn = true;
            swarm.getParams().withInitialMembers = true;
        }
    }

    /** The hull's own swarm, created now if its builtin has not yet (idempotent). */
    private static RoilingSwarmEffect hullSwarmOrNull(ShipAPI ship) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm != null) {
            return swarm;
        }
        ShipVariantAPI v = ship.getVariant();
        if (v == null || !v.hasHullMod(FRAGMENT_SWARM_HULLMOD)) {
            return null; // no builtin → the hull never gets a cloud; nothing to mute
        }
        return FragmentSwarmHullmod.createSwarmFor(ship);
    }

    private void advancePending(CombatEngineAPI engine, float amount) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
            Pending p = it.next();
            try {
                p.waited += amount;
                if (!engine.isEntityInPlay(p.ship) || !p.ship.isAlive() || p.ship.isHulk()) {
                    it.remove();
                    releaseHullSwarm(p.ship); // every pending exit un-mutes
                    continue;
                }
                ShipAPI carrier = findCarrier(p.ship, engine);
                if (carrier != null) {
                    it.remove();
                    begin(p.ship, carrier);
                    continue;
                }
                if (p.waited >= PENDING_SECONDS) {
                    it.remove();
                    pendingDropped++;
                    releaseHullSwarm(p.ship);
                    log("PENDING-DROP " + hullId(p.ship) + " on " + ident(p.ship)
                            + " (no carrier arrived; bare-hull status quo, cloud released)");
                }
            } catch (Throwable t) {
                throwsSeen++;
                it.remove();
                disable(p.ship, "pending", t);
            }
        }
    }

    // ------------------------------------------------------------------ per-frame body

    /**
     * One live frame of one build — the presentation half of the vanilla construction reveal,
     * rewritten from the functional spec. Wire truth ends it first: the
     * hull out of play (a dead-hull end), the carrier out of play or dead, the hull firing
     * ({@link #isFiring}). Otherwise the build clock advances by the frame step ({@link #frameStep}:
     * × {@code ThreatShipConstructionScript.FADE_IN_RATE_MULT_WHEN_DESTROYED}, read off the engine
     * class, while the hull is a hulk) — and once it passes the fade-in time the build ends on the
     * timer (a dead-hull end when the hull is a hulk). A frame still building writes: hull alpha =
     * progress ({@link #buildProgress}); the {@code construction_swarm_loop} on the hull; the
     * EMP-fringe jitter outline ({@link #jitterLevel} / {@link #jitterRange}); engines faded to fully
     * transparent; the carrier cloud's max speed ({@link #carrierSpeedMult}) and flash
     * ({@link #flashFrequency} / {@link #flashProbability}); the hull's own cloud held empty (initial
     * members + respawn off); and the engine's own burst ({@link #spawnParticles}) on the SAME frame
     * step as the clock. Returns {@code true} when the build is finished (caller removes it).
     *
     */
    private boolean advanceBuild(Build b, CombatEngineAPI engine, float amount) {
        ShipAPI ship = b.ship;
        if (!engine.isEntityInPlay(ship)) {
            finish(b, END_HULK);
            return true;
        }
        if (!engine.isEntityInPlay(b.carrier) || !b.carrier.isAlive()) {
            finish(b, END_CARRIER);
            return true;
        }
        if (isFiring(b)) {
            finish(b, END_FIRE);
            return true;
        }
        boolean hulk = ship.isHulk();
        float step = frameStep(amount, hulk, ThreatShipConstructionScript.FADE_IN_RATE_MULT_WHEN_DESTROYED);
        b.elapsed += step;
        if (b.elapsed > b.fadeInTime) {
            finish(b, hulk ? END_HULK : END_TIMER);
            return true;
        }
        float t = b.elapsed;
        float remaining = b.fadeInTime - t;
        float progress = buildProgress(t, b.fadeInTime);
        ship.setAlphaMult(progress);
        Global.getSoundPlayer().playLoop(LOOP_SOUND, ship, 1f, 1f, ship.getLocation(), ship.getVelocity());
        Color empFringe = VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
        ship.setJitter(b, Misc.setAlpha(empFringe, JITTER_ALPHA), jitterLevel(t, b.fadeInTime), JITTER_COPIES,
                0f, jitterRange(t, b.fadeInTime));
        ship.getEngineController().fadeToOtherColor(b, Misc.zeroColor, Misc.zeroColor, 1f, 1f);
        RoilingSwarmEffect carrierCloud = RoilingSwarmEffect.getSwarmFor(b.carrier);
        if (carrierCloud != null) {
            RoilingSwarmEffect.RoilingSwarmParams cp = carrierCloud.getParams();
            cp.maxSpeed = b.origMaxSpeed * carrierSpeedMult(t);
            cp.flashFrequency = flashFrequency(progress, remaining, cp.initialMembers);
            cp.flashProbability = flashProbability(remaining);
            if (remaining > FLASH_LIVE_ABOVE_REMAINING) {
                cp.flashFringeColor = Misc.setAlpha(empFringe, FLASH_FRINGE_ALPHA);
                cp.flashCoreRadiusMult = FLASH_CORE_RADIUS_MULT;
                cp.flashRadius = FLASH_RADIUS;
                cp.renderFlashOnSameLayer = true;
            }
        }
        RoilingSwarmEffect hullCloud = RoilingSwarmEffect.getSwarmFor(ship);
        if (hullCloud != null) {
            hullCloud.getParams().withInitialMembers = false;
            hullCloud.getParams().withRespawn = false;
        }
        spawnParticles(b, engine, step, remaining);
        return false;
    }

    // ------------------------------------------------------------------ reveal laws (selftest-visible, engine-free)

    /** One frame's build step (s, >= 0 for a frame {@code amount} >= 0): {@code amount × hulkMult} on a
     *  hulk, else {@code amount} — scaled ONCE per frame, and that one value feeds both the build clock
     *  and the burst. {@code hulkMult} = the engine class's hulk fade-in rate multiplier (passed in so
     *  no engine type rides the signature). */
    static float frameStep(float amount, boolean hulk, float hulkMult) {
        return hulk ? amount * hulkMult : amount;
    }

    /** Build progress {@code p = min(1, t / F)} (the hull's alpha); a non-positive F reads as done.
     * */
    static float buildProgress(float elapsed, float fadeInTime) {
        if (!(fadeInTime > 0f)) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, elapsed / fadeInTime));
    }

    /**
     * Jitter outline intensity at build clock {@code t} of fade-in {@code F}: rises 2p to p = 0.5;
     * then a build of at most {@link #JITTER_SHORT_BUILD} s falls 2(1 - p) (a tent), a longer one
     * holds 1 until {@link #JITTER_TAIL} s remain and then falls remaining / {@link #JITTER_TAIL};
     * the square root of that is the level, in [0, 1].
     */
    static float jitterLevel(float elapsed, float fadeInTime) {
        float p = buildProgress(elapsed, fadeInTime);
        float remaining = Math.max(0f, fadeInTime - elapsed);
        float level;
        if (p < 0.5f) {
            level = 2f * p;
        } else if (fadeInTime <= JITTER_SHORT_BUILD) {
            level = 2f * (1f - p);
        } else if (remaining <= JITTER_TAIL) {
            level = remaining / JITTER_TAIL;
        } else {
            level = 1f;
        }
        return (float) Math.sqrt(Math.max(0f, Math.min(1f, level)));
    }

    /**
     * Jitter outline range (su) at build clock {@code t} of fade-in {@code F}: the fraction is
     * remaining / {@link #JITTER_TAIL} once fewer than {@link #JITTER_TAIL} s remain, else
     * t / max(1, F - {@link #JITTER_TAIL}); × {@link #MAX_JITTER_RANGE_BONUS} (base range 0).
     *
     */
    static float jitterRange(float elapsed, float fadeInTime) {
        float remaining = Math.max(0f, fadeInTime - elapsed);
        float fraction = remaining < JITTER_TAIL
                ? remaining / JITTER_TAIL
                : Math.max(0f, elapsed) / Math.max(1f, fadeInTime - JITTER_TAIL);
        return MAX_JITTER_RANGE_BONUS * fraction;
    }

    /** Carrier cloud max-speed multiple at build clock {@code t}: full to {@link #CARRIER_SLOW_FLOOR}
     *  over the first {@link #CARRIER_SLOW_SECONDS} s, then held. */
    static float carrierSpeedMult(float elapsed) {
        return CARRIER_SLOW_FLOOR
                + (1f - CARRIER_SLOW_FLOOR) * Math.max(0f, 1f - elapsed / CARRIER_SLOW_SECONDS);
    }

    /**
     * Carrier flash frequency: while more than {@link #FLASH_LIVE_ABOVE_REMAINING} s remain,
     * {@link #FLASH_PEAK_FREQUENCY} × progress × clamp(initialMembers / {@link #FLASH_FULL_MEMBERS},
     * {@link #FLASH_MIN_SIZE_FACTOR}, 1); after that 1 (with probability 0, {@link #flashProbability}).
     *
     */
    static float flashFrequency(float progress, float remaining, int initialMembers) {
        if (!(remaining > FLASH_LIVE_ABOVE_REMAINING)) {
            return 1f;
        }
        float sizeFactor = Math.max(FLASH_MIN_SIZE_FACTOR, Math.min(1f, initialMembers / FLASH_FULL_MEMBERS));
        return FLASH_PEAK_FREQUENCY * progress * sizeFactor;
    }

    /** Carrier flash probability: 1 while more than {@link #FLASH_LIVE_ABOVE_REMAINING} s remain, else 0. */
    static float flashProbability(float remaining) {
        return remaining > FLASH_LIVE_ABOVE_REMAINING ? 1f : 0f;
    }

    /** Members moved carrier → hull at the finish: min(the hull cloud's maintain count, the carrier
     *  cloud's active members), never negative. */
    static int seedCount(int hullMaintain, int carrierActive) {
        return Math.max(0, Math.min(hullMaintain, carrierActive));
    }

    /**
     * The wire-truth FIRE cutover (class doc "Duration law"); the motion half was removed
     * (D-FABCOLLFX — a collision shove is not a finish, vanilla never ends the reveal on motion).
     */
    private static boolean isFiring(Build b) {
        if (b.elapsed > 0.5f) { // never on the bind frame (fire rows queued at birth are stale)
            List<WeaponAPI> weapons = b.ship.getAllWeapons();
            if (weapons != null) {
                for (WeaponAPI w : weapons) {
                    if (w != null && w.isFiring()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The burst, while more than {@link #BURST_LIVE_ABOVE_REMAINING} s of the build remain: the
     * engine's OWN construction burst, run through the build's {@link ConstructionBurst} — on the
     * engine's own interval (first frame immediate) the carrier's members re-roll their offset around
     * the HULL (the "buried in the cloud" look) and the dark puffs land. {@code engine} is unused (the
     * call shape is kept). Rewritten from the functional spec.
     *
     */
    private void spawnParticles(Build b, CombatEngineAPI engine, float amount, float remaining) {
        if (remaining > BURST_LIVE_ABOVE_REMAINING) {
            b.burst.burst(b.ship, b.elapsed, amount);
        }
    }

    /**
     * The engine's own construction burst, borrowed: a subclass of the public
     * {@code ThreatShipConstructionScript}, built with the carrier as source, delay 0 and the build's
     * fade-in time, whose {@code spawnShip()} does nothing (so building it spawns no hull — the hull
     * replica is the wire's) and whose protected {@code spawnParticles} is driven by hand with the
     * hull and the build clock. NEVER add it as a combat plugin: its {@code advance} is the sim body
     * (armour, the carrier pin, the AI, the carrier kill) — the server's, never the client's.
     * Rewritten from the functional spec.
     */
    private static final class ConstructionBurst extends ThreatShipConstructionScript {

        ConstructionBurst(String hullVariantId, ShipAPI carrier, float fadeInTime) {
            super(hullVariantId, carrier, 0f, fadeInTime);
        }

        /** Never spawns: the hull replica is minted from its wire growth row. */
        @Override
        protected void spawnShip() {
            // intentionally empty
        }

        /** One frame of the engine's burst for {@code hull} at build clock {@code clock}. */
        void burst(ShipAPI hull, float clock, float amount) {
            this.ship = hull;
            this.elapsed = clock;
            this.spawnParticles(amount);
        }
    }

    /** {@link #finish} reasons — each feeds its own {@code predict_construction_ends_*} meter token. */
    private static final String END_CARRIER = "carrier";
    private static final String END_TIMER = "timer";
    private static final String END_FIRE = "fire";
    private static final String END_HULK = "hulk";

    /**
     * End of a build: the {@code SHIP_UNDER_CONSTRUCTION} tag off, hull alpha 1; the carrier cloud's
     * despawn sound cleared and its max speed restored (the restore is ours — the server consumes the
     * carrier and its despawn row removes our replica); {@link #seedCount} members moved carrier →
     * hull through the cloud's public transfer; then the hull cloud's respawn + initial members back
     * on ({@link #releaseHullSwarm}). {@code why} (one of the {@code END_*} reasons) picks the ends
     * counter; every end releases the carrier from {@link #pairedCarriers} first, so
     * {@link #findCarrier} may pair it again. Rewritten from the functional spec.
     *
     */
    private void finish(Build b, String why) {
        switch (why) {
            case END_CARRIER: endsCarrier++; break;
            case END_TIMER: endsTimer++; break;
            case END_FIRE: endsFire++; break;
            case END_HULK: endsHulk++; break;
            default: break;
        }
        remove(pairedCarriers, b.carrier);
        ShipAPI ship = b.ship;
        ship.removeTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION);
        ship.setAlphaMult(1f);
        RoilingSwarmEffect carrierCloud = RoilingSwarmEffect.getSwarmFor(b.carrier);
        if (carrierCloud != null) {
            carrierCloud.getParams().despawnSound = null;
            carrierCloud.getParams().maxSpeed = b.origMaxSpeed;
            RoilingSwarmEffect hullCloud = RoilingSwarmEffect.getSwarmFor(ship);
            if (hullCloud != null) {
                int moved = seedCount(hullCloud.getNumMembersToMaintain(), carrierCloud.getNumActiveMembers());
                if (moved > 0) {
                    carrierCloud.transferMembersTo(hullCloud, moved);
                }
            }
        }
        releaseHullSwarm(ship);
    }

    // ------------------------------------------------------------------ fail-safe + log

    /** Any throw: best-effort snap to the finished look, blacklist the body, log ONCE. */
    private void disable(ShipAPI ship, String where, Throwable t) {
        if (ship != null && !contains(disabled, ship)) {
            disabled.add(ship);
            try {
                ship.removeTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION);
                ship.setAlphaMult(1f);
                // The "finished look" includes the RELEASED
                // cloud — without this a throw after the mute left a full-alpha hull cloudless
                // for the round, below the bare-hull baseline the lane exists to improve.
                releaseHullSwarm(ship);
            } catch (Throwable ignore) {
                // the snap itself failed — the body stays as the engine left it
            }
        }
        PilotWatchLog.error("predict threat construction-present: " + where + " FAILED on "
                + ident(ship) + " (lane disabled for this body; hull shown as-is)", t);
    }

    private static String hullId(ShipAPI ship) {
        try {
            ShipHullSpecAPI spec = ship.getHullSpec();
            return spec != null ? spec.getBaseHullId() : "?";
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private static String ident(ShipAPI ship) {
        return ship == null ? "null" : Integer.toHexString(System.identityHashCode(ship));
    }

    private static boolean contains(ArrayList<ShipAPI> list, ShipAPI ship) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == ship) {
                return true;
            }
        }
        return false;
    }

    private static void remove(ArrayList<ShipAPI> list, ShipAPI ship) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == ship) {
                list.remove(i);
                return;
            }
        }
    }

    private void log(String body) {
        if (logLines >= LOG_MAX) {
            return;
        }
        logLines++;
        PilotWatchLog.info("predict threat construction-present " + body
                + (logLines == LOG_MAX ? " [lane log cap; counters continue]" : ""));
    }
}
