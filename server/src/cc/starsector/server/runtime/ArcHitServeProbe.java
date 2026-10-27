package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * ARC_HIT(9) serve probe (GPEE schema v13; pinned contract):
 * the server names every EMP-arc hit — victim
 * ship, node world point, applied armor/hull/shield split, flavor — from the engine's own
 * {@code DamageListener} seam, so the client draws the arc to the TRUE node and prints the
 * vanilla-split floaty instead of re-running {@code findTarget} locally (D-INIMPHASEDMG /
 * D-INIMDMGNUM).
 *
 * <p>Seam: {@code EmpArcEntity.advance} applies the arc's
 * damage on its FIRST engine advance via {@code doDamage()} —
 * {@code ship.setParamAboutToApplyDamage(this)} then
 * {@code ship.applyDamage(point, dmg, shieldHit, 1f, source)};
 * {@code Ship.applyDamage} runs {@code applyDamageInner} then
 * {@code CombatListenerUtil.reportDamageApplied(this, source, this, result)} (the GLOBAL
 * {@code CombatEngine.getListenerManager()} list) and nulls
 * the param only after that call ⇒ inside the callback
 * {@code target.getParamAboutToApplyDamage()} IS the arc; {@code source} = the SOURCE SHIP
 * ({@code EmpArcEntity.source}). Event simTick = the drain tick (discharge + ≥1).
 *
 * <p>Shape: server/src is game-jar-FREE (reflection only) ⇒ a {@link java.lang.reflect.Proxy}
 * over the API {@code DamageListener} (the {@code PhaseDiveServeProbe.AdvanceCounter} idiom,
 * shared base {@link ServeProbeSupport.ListenerHandler}) installed ONCE
 * on the global manager, lazily on the first drain with a live ship in hand (classloader from
 * the ship); before {@code addListener} any prior entry whose {@code toString()} equals
 * {@link #MARKER} is removed (a re-minted probe on a reused engine must never double-emit).
 * Capture = the minimum sim-thread read: one cached reflective param read + an
 * {@code isInstance} (the hot path for every non-arc damage event), then the node point,
 * shield flag, three result floats and the flavor — object refs + floats appended to a
 * per-tick list. Wire-id resolution, mint, meters and log ride the serve-loop {@link #drain}
 * (take-and-clear, never stale). Fail-soft: {@link ServeProbeSupport.FailSoft#FAIL_LOG_MAX}
 * lines then quiet — the probe never breaks the serve loop. The missile-impact memo
 * feed sits OUTSIDE that budget (its own try/catch, ahead of the arc processing; a feed throw
 * counts {@code missile_impact_blind=}) — an arc-lane fault never blinds the devour end verdict.
 *
 *
 * <p>Flavor: {@code EmpArcEntity.impactSet} (private field) is the
 * {@code com.fs.starfarer.loading.specs.K} spec resolved at spawn from the impact-set id
 * (a map-backed store lookup, one instance per
 * id). {@code K} carries NO id string (a sounds map only), so the
 * probe resolves the two known specs by id through the SAME store at install and classifies by
 * IDENTITY: {@code inimical_emanation_impact} ⇒ {@link GameplayEdgeEvent#ARC_FLAVOR_INIMICAL},
 * {@code voltaic_discharge_emp_impact} ⇒ {@link GameplayEdgeEvent#ARC_FLAVOR_VOLTAIC}, else
 * {@link GameplayEdgeEvent#ARC_FLAVOR_OTHER}. A field/store miss degrades to OTHER-only (one
 * log line), never a probe failure.
 *
 * <p>Beam-missile feed (the LOCAL-EARLY Hammer grader's server half): the SAME
 * callback, MISSILE targets only. Seam: the ray-collision applier
 * sets {@code missile.setParamAboutToApplyDamage(ray)} then calls
 * {@code applyDamage(..., ray.getSource())}; {@code BeamWeaponRay} implements {@code BeamAPI};
 * {@code Missile.applyDamage} subtracts the dealt damage, {@code setHitpoints}, then
 * reports {@code (source, missile, G(type, dealt, 0, 0, 0))} ⇒ {@code getDamageToHull} = the dealt
 * damage (UNCAPPED — the killing report carries the overkill: the missile clamps
 * hp to 0 before the report, so a row's {@code dmg=} sum can exceed the missile's hp),
 * armor 0, and {@code getHitpoints()} = the POST-hit hp. A seat
 * ship's beam aggregates per (missile, source ship, weapon id) and flushes ONE
 * {@code beam-missile-hit} row when {@link #BEAM_MISSILE_STALE_TICKS} stale or at the round tail
 * (see {@link #feedBeamMissile} / {@link #beamDrain}).
 *
 * <p>Own-file per the PersistentPilotIntentCombatServer growth-anchor law:
 * PPICS carries the field + the per-tick drain + the compose
 * concat + the meters only.
 */
public final class ArcHitServeProbe { // public: the forTest seam's rows live in server.tests

    /** {@code toString()} of the installed proxy — the dedupe key on (re)install. */
    static final String MARKER = "cc-arc-hit-listener";
    /** The hit-cell tap's dedupe marker — the literal lives in {@link ArcHitCellTap}. */
    public static final String HIT_CELL_MARKER = ArcHitCellTap.HIT_CELL_MARKER;
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String MISSILE_CLASS = "com.fs.starfarer.combat.entities.Missile";
    private static final String ARC_CLASS = "com.fs.starfarer.combat.systems.EmpArcEntity";
    private static final String DAMAGE_LISTENER =
            "com.fs.starfarer.api.combat.listeners.DamageListener";
    private static final String LISTENER_MANAGER =
            "com.fs.starfarer.api.combat.listeners.CombatListenerManagerAPI";
    private static final String RESULT_API =
            "com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI";
    private static final String SPEC_STORE = "com.fs.starfarer.loading.SpecStore";
    private static final String IMPACT_SET_CLASS = "com.fs.starfarer.loading.specs.K";
    private static final String IMPACT_SET_FIELD = "impactSet";
    /**
     * The Voltaic CANNON ({@code VoltaicCannonEffect}) spawns via
     * {@code spawnEmpArcPierceShields} with the SAME {@code voltaic_discharge_emp_impact} id as the
     * swarm discharge ({@code VoltaicDischargeOnFireEffect}, plain {@code spawnEmpArc}) — the
     * impact-set identity alone would flavor cannon hits VOLTAIC and the client would double-present
     * them (its own cannon wrapper + the wire arc from a swarm fragment). The arc's private
     * {@code pierceTargetShields} separates them: VOLTAIC
     * = voltaic set AND non-piercing. A field miss degrades to the identity-only rule (one log line).
     */
    private static final String PIERCE_FIELD = "pierceTargetShields";
    private static final String INIMICAL_IMPACT_SET_ID = "inimical_emanation_impact";
    private static final String VOLTAIC_IMPACT_SET_ID = "voltaic_discharge_emp_impact";
    /**
     * The Overseer lash's two impact sets
     * ({@code EnergyLashSystemScript} friendly / enemy). Identity-classified like
     * the pair above. A LASH (enemy) capture additionally reads the victim's {@code isPhased()} and
     * {@code getFluxTracker().isOverloaded()} — the script's {@code applyEffectToTarget}
     * overloads a PHASED victim in the SPAWN frame and the arc's damage lands on its first advance
     * ({@code EmpArcEntity.advance}), so at this callback {@code tgt_ovl=true} IS the
     * "seat overload within 0.1 s" witness. Three extra invokes, lash arcs only (rare).
     */
    private static final String LASH_ENEMY_IMPACT_SET_ID = "energy_lash_enemy_impact";
    private static final String LASH_FRIENDLY_IMPACT_SET_ID = "energy_lash_friendly_impact";
    /**
     * The EMP ion slice: the projectile ion family
     * ({@code IonCannonOnHitEffect}, shared by {@code ioncannon_shot} + {@code ionpulser_shot})
     * spawns its 25 % arc with the id {@code tachyon_lance_emp_impact} — SHARED with the tachyon
     * lance ({@code TachyonLanceEffect}), the ion beam ({@code IonBeamEffect}), the pilum
     * child ({@code PilumOnHitEffect}) and the sabot child ({@code SabotOnHitEffect}).
     * Identity alone would flavor all five ION. Two more private finals on the arc separate them:
     * the three beam/pilum twins spawn through {@code spawnEmpArcPierceShields}
     * ({@link #PIERCE_FIELD} true) and the sabot child passes {@code dam = 0.0F}
     * while the ion bolt passes its projectile damage
     * (always {@code > 0}). ION = tachyon set AND non-piercing AND
     * {@code damAmount > 0}; a field miss keeps ion arcs OTHER (one log line), never a mis-flavor.
     * The non-piercing dam-0 leg IS the sabot child (the tachyon-set author list is closed
     * at those five) — named {@code ARC_FLAVOR_SABOT} on the same resolved keys.
     */
    private static final String TACHYON_IMPACT_SET_ID = "tachyon_lance_emp_impact";
    private static final String DAM_FIELD = "damAmount";
    /** First-N emits logged as rows, then meters only (shot-scale volume — the MISSILE_END law). */
    private static final int LOG_MAX = 20;
    /** Lash rows keep their own cap — a voltaic flood must never starve the witness.
     *  80 (was 20) — one volley = 3 rows, the lash rig's 12,000-frame serve lands
     *  ~20 volleys; the earlier 6,000-frame runs hit the old cap at volley 7 with volleys unlogged. */
    private static final int LASH_LOG_MAX = 80;

    private final ServeProbeSupport.FailSoft failSoft = new ServeProbeSupport.FailSoft("arc-hit");
    private boolean installed;
    /** Stale Capture listeners removed by {@link #installOn}'s dedupe, ACCUMULATED across this
     *  probe's install attempts (the dedupe now runs before the throwing resolves) — the {@code prior_removed=}
     *  of the installed line. */
    private int captureRemoved;
    private Class<?> shipClass;
    private Class<?> missileClass;
    private Class<?> arcClass;
    private Method shipGetParam;
    private Method missileGetParam;
    private Method getTargetLocation;
    private Method isShieldHit;
    private Method getTotalDamageToArmor;
    private Method getDamageToHull;
    private Method getDamageToShields;
    private Method getOverMaxDamageToShields;  // The zero-headroom shield-hit tell (null ⇒ read as 0)
    private Field vecX;
    private Field vecY;
    private Field impactSetField;
    private Field pierceField;      // null ⇒ identity-only flavor rule (the behavior before the pierce split)
    private Object inimicalImpactSet;
    private Object voltaicImpactSet;
    private Object lashEnemyImpactSet;      // null ⇒ lash arcs stay OTHER
    private Object lashFriendlyImpactSet;
    private Object tachyonImpactSet;        // null ⇒ ion arcs stay OTHER
    private Field damField;                 // null ⇒ ion arcs stay OTHER (the sabot guard)
    private Method shipIsPhased;            // lash witness reads (null ⇒ '?' on the row)
    private Method shipGetFluxTracker;
    private Method fluxIsOverloaded;

    /** Sim-thread captures awaiting the serve-loop drain (serve loop + engine advance share one thread). */
    private final List<Raw> pending = new ArrayList<>();
    /**
     * Per-victim arc attribution past the {@link #LOG_MAX} row cap
     * — one {@code arc_hit_hist} row per {@link ArcHitHistogram#DEFAULT_WIDTH}-tick bucket (flushed
     * from {@link #drain} on the bucket change, the round tail by {@link #flushHistTail}) + the
     * {@code arc_hit_by_tgt=} flight summary on the counters line. Drain-side only (resolved ids +
     * the wire event's clamped split): zero extra sim-thread reads.
     */
    private final ArcHitHistogram hist = new ArcHitHistogram(ArcHitHistogram.DEFAULT_WIDTH);
    /**
     * The per-tick hull-SOURCE ledger behind the {@code src=} fields on
     * the binding's {@code dmg-taken} hulldelta/armordelta rows. Fed from {@link #capture} (the SAME
     * callback, ship targets only: two more cached invokes — no second sim-thread listener); the
     * binding joins on victim identity at its observation pass ({@code logHullDrops}).
     */
    private final HullSourceLedger hullSources = new HullSourceLedger();
    /**
     * The per-missile last-impact memo (GPEE v15) behind the {@code MISSILE_END} victim
     * form — fed from the SAME callback when the ship target's param is a {@code Missile} (two more
     * cached invokes on missile impacts only); the binding joins it at delist ({@link MissileImpactMemo}).
     */
    private final MissileImpactMemo missileImpacts = new MissileImpactMemo();
    /**
     * The hit-cell tap ({@code cc.server.hitCellRow}, {@link #armHitCell}) — its handler, grid
     * handles, cost meter and install live in {@link ArcHitCellTap}. Shares this probe's
     * ledger and fail-soft budget, so this initializer stays textually AFTER {@link #failSoft} and
     * {@link #hullSources} (field-initializer order).
     */
    private final ArcHitCellTap hitCellTap = new ArcHitCellTap(hullSources, failSoft);
    /** EVERY {@code reportDamageApplied} callback (arc or not) — the sim-thread invoke volume (T2 F2). */
    private long callbacks;
    private long seen;
    private long emitted;
    private long lashEmitted;
    /** Emitted ION arcs (a subset of {@link #emitCount()}); round line {@code arc_hit_ion=}. */
    private long ionEmitted;
    /** Emitted SABOT arcs (the dam-0 twin); round line {@code arc_hit_sabot=}. */
    private long sabotEmitted;
    /** LASH (enemy) arcs whose victim read {@code isOverloaded()} at the seam — the
     *  uncapped served-overload witness (the script overloads ONLY a {@code isPhased()} target;
     *  the arc lands before or after the kick-out by advance order, so {@code tgt_phased} is not
     *  part of the count). Round line {@code arc_hit_lash_ovl=}. */
    private long lashOverloaded;
    private long skipUnresolved;
    private long skipNullResult;
    private long skipNonShip;
    private int logRows;
    private int lashLogRows;

    /** Open (un-flushed) beam-missile aggregates at most; a report whose key would
     *  open the 65th counts {@code beamOpenOverflow} and is not aggregated.
     * */
    static final int BEAM_MISSILE_OPEN_MAX = 64;
    /** {@code beam-missile-hit} rows per probe (= per serve round), then counted only ({@code beamRowsCapped}). */
    static final int BEAM_MISSILE_ROW_MAX = 2000;
    /** An aggregate flushes at the first drain where {@code simTick - tickLast >=} this. */
    static final long BEAM_MISSILE_STALE_TICKS = 3L;
    /** The {@link #beamMissileRowsForTest()} ring: the last N PRINTED rows. */
    private static final int BEAM_MISSILE_SEAM_ROWS = 16;
    private static final String BEAM_API = "com.fs.starfarer.api.combat.BeamAPI";
    private static final String MISSILE_API = "com.fs.starfarer.api.combat.MissileAPI";
    /** false ⇒ the feed is inert (install miss, or the 4-arg {@link #forTest} probe). */
    private boolean beamFeedLive;
    private Class<?> beamApi;
    private Method beamGetWeapon;
    private Method weaponGetId;
    private Method missileGetHitpoints;
    /** The SEAT ships (identity set) the LAST drain cached — empty until the first drain. */
    private Set<Object> seatShips = Collections.emptySet();
    /** The binding's non-minting missile stable-id peek, from the LAST drain ({@code null} ⇒ -1). */
    private ToLongFunction<Object> missileStableIdFn;
    private long beamLastDrainTick = -1L;
    private final List<BeamAgg> beamOpen = new ArrayList<>();
    /** beam object → sanitized weapon id; ≤ {@link #BEAM_MISSILE_OPEN_MAX} entries, cleared when it
     *  fills, when the open table empties at a flush, and at the round tail. */
    private final IdentityHashMap<Object, String> beamWeaponIds = new IdentityHashMap<>();
    private final ArrayDeque<String> beamRowsSeam = new ArrayDeque<>();
    private Consumer<String> beamRowSink = System.out::println;
    // The beam feed's counters: only beamQuietDrops prints (beam_feed_quiet=); the other seven lost their
    // round-line tokens as unread and are read by the BM rows via beamMissileCounterForTest.
    private long beamReports;
    private long beamRows;
    private long beamRowsCapped;
    private long beamOpenOverflow;
    private long beamUnresolved;
    private long beamNullResult;
    private long beamBlind;
    private long beamQuietDrops;
    private boolean beamBlindLogged;

    /**
     * One open (missile, source ship, weapon id) aggregate. {@code pend*} = the reports captured since
     * the last drain — stamped with the NEXT drain's simTick (the arc lane's {@code pending}
     * convention) by {@link #stamp}. The missile's stable id is captured at record time (the binding's
     * map prunes at delist); the source's wire id resolves at the first stamping drain (the ship may
     * leave the ship map before the flush). Sim thread only.
     */
    private static final class BeamAgg {
        final Object missile;
        final Object source;
        final String weaponId;
        long missileId = -1L;
        long srcWireId = -1L;
        long tickFirst = -1L;
        long tickLast = -1L;
        int ticks;
        int reports;
        double dmg;
        float hpLast = -1f;
        boolean touched;
        int pendReports;
        double pendDmg;
        float pendHp = -1f;

        BeamAgg(Object missile, Object source, String weaponId) {
            this.missile = missile;
            this.source = source;
            this.weaponId = weaponId;
        }

        /** Fold the pending reports into drain tick {@code simTick} (one distinct tick). */
        void stamp(long simTick) {
            if (ticks == 0) {
                tickFirst = simTick;
            }
            if (ticks == 0 || tickLast != simTick) {
                ticks++;
            }
            tickLast = simTick;
            reports += pendReports;
            dmg += pendDmg;
            hpLast = pendHp;
            touched = false;
            pendReports = 0;
            pendDmg = 0d;
        }
    }

    /** The raw capture: engine object refs + floats only; ids resolve at drain. {@code tgtPhased} /
     *  {@code tgtOverloaded} are read for LASH (enemy) arcs only, else {@code null}. */
    private record Raw(Object source, Object target, int flavor, float x, float y,
            boolean shield, float armor, float hull, float shields,
            Boolean tgtPhased, Boolean tgtOverloaded) {
    }

    /** The proxy handler: capture on {@code reportDamageApplied}; the Object trio rides the base. */
    private final class Capture extends ServeProbeSupport.ListenerHandler {
        Capture() {
            super(MARKER);
        }

        @Override
        void onCall(String method, Object[] args) {
            if ("reportDamageApplied".equals(method) && args != null && args.length == 3) {
                capture(args[0], args[1], args[2]);
            }
        }
    }

    /**
     * Serve loop, once per tick BEFORE the compose: lazily installs on the first tick with a
     * live ship, then takes-and-clears this tick's captures, resolves wire ids through the
     * SAME supplier pair as the sibling probes ({@code shipsSupplier} = binding
     * ship→positionShipId; {@code wireIdByPositionShipId} = positionShipId→wire id) and mints
     * {@link GameplayEdgeEvent#arcHit}. Unresolved source/target, a non-finite node point =
     * counted skip (never a sentinel on the wire). {@code seatShipsSupplier} (the
     * binding's seat ships as an identity set) and {@code missileStableIds} (its NON-minting stable-id
     * peek) feed the beam-missile aggregate — {@link #beamDrain} runs in its OWN try/catch, also on a
     * quiet arc lane (open aggregates keep flushing).
     */
    List<GameplayEdgeEvent> drain(long simTick, Supplier<Map<Object, Long>> shipsSupplier,
            Supplier<Map<Long, Long>> wireIdByPositionShipId, Supplier<Set<Object>> seatShipsSupplier,
            ToLongFunction<Object> missileStableIds) {
        if (failSoft.quiet()) {
            pending.clear();
            if (beamFeedLive) {
                beamDrain(simTick, shipsSupplier, wireIdByPositionShipId, seatShipsSupplier, missileStableIds);
            }
            return List.of();
        }
        try {
            Map<Object, Long> ships = shipsSupplier.get();
            if (!installed) {
                Object anyShip = firstShip(ships);
                if (anyShip == null) {
                    return List.of();
                }
                install(simTick, anyShip);
            }
            if (beamFeedLive) {
                beamDrain(simTick, shipsSupplier, wireIdByPositionShipId, seatShipsSupplier, missileStableIds);
            }
            // The bucket ledger rolls on EVERY drain (a quiet bucket closes the previous
            // one promptly) — before the empty-pending early return.
            String closedBucket = hist.rollTo(simTick);
            if (closedBucket != null) {
                System.out.println(closedBucket);
            }
            if (pending.isEmpty()) {
                return List.of();
            }
            List<Raw> taken = new ArrayList<>(pending);
            pending.clear();
            Map<Long, Long> wireIds = wireIdByPositionShipId.get();
            List<GameplayEdgeEvent> out = new ArrayList<>(taken.size());
            for (Raw raw : taken) {
                long src = ServeProbeSupport.wireIdOf(raw.source(), ships, wireIds);
                long tgt = ServeProbeSupport.wireIdOf(raw.target(), ships, wireIds);
                if (src <= 0L || tgt <= 0L || !Float.isFinite(raw.x()) || !Float.isFinite(raw.y())) {
                    skipUnresolved++;
                    continue;
                }
                // Shield law at mint: shield hit ⇒ armor/hull NaN; else shield NaN.
                GameplayEdgeEvent event = raw.shield()
                        ? GameplayEdgeEvent.arcHit(simTick, src, tgt, raw.flavor(), raw.x(), raw.y(),
                                Float.NaN, Float.NaN, clampDamage(raw.shields()))
                        : GameplayEdgeEvent.arcHit(simTick, src, tgt, raw.flavor(), raw.x(), raw.y(),
                                clampDamage(raw.armor()), clampDamage(raw.hull()), Float.NaN);
                out.add(event);
                emitted++;
                // The wire event's clamped split (NaN on the un-hit side reads as 0)
                hist.add(tgt, raw.flavor(), event.dmgArmor(), event.dmgHull(), event.dmgShield());
                boolean lash = raw.flavor() == GameplayEdgeEvent.ARC_FLAVOR_LASH
                        || raw.flavor() == GameplayEdgeEvent.ARC_FLAVOR_LASH_FRIENDLY;
                if (lash) {
                    lashEmitted++;
                    if (Boolean.TRUE.equals(raw.tgtOverloaded())) {
                        lashOverloaded++;
                    }
                } else if (raw.flavor() == GameplayEdgeEvent.ARC_FLAVOR_ION) {
                    ionEmitted++; // The uncapped ion witness (rows share the LOG_MAX cap)
                } else if (raw.flavor() == GameplayEdgeEvent.ARC_FLAVOR_SABOT) {
                    sabotEmitted++; // The sabot-child twin's witness
                }
                boolean logIt = lash ? lashLogRows < LASH_LOG_MAX : logRows < LOG_MAX;
                if (logIt) {
                    if (lash) {
                        lashLogRows++;
                    } else {
                        logRows++;
                    }
                    System.out.println("npeer-serve arc_hit src=" + src + " tgt=" + tgt
                            + " flavor=" + raw.flavor() + " shield=" + raw.shield()
                            + " x=" + raw.x() + " y=" + raw.y()
                            + " armor=" + event.dmgArmor() + " hull=" + event.dmgHull()
                            + " shields=" + event.dmgShield()
                            + " tick=" + simTick
                            // the lash-open witness — phased victim + overload
                            + (lash ? " lash tgt_phased=" + flag(raw.tgtPhased())
                                    + " tgt_ovl=" + flag(raw.tgtOverloaded()) : "")
                            + (!lash && logRows == LOG_MAX ? " [arc_hit row cap; meters only from here]" : "")
                            + (lash && lashLogRows == LASH_LOG_MAX ? " [lash row cap; arc_hit_lash= continues]" : ""));
                }
            }
            return out;
        } catch (Throwable failure) {
            pending.clear();
            failSoft.fail("drain", simTick, failure);
            return List.of();
        }
    }

    /** Arm the ledger's shield window (the {@code cc.server.shieldedRow} INSTRUMENT
     *  knob) — the capture then also reads {@code getDamageToShields} per ship-target report. */
    public void armShieldWindow(boolean on) {
        hullSources.armShieldWindow(on);
    }

    /** Arm the hit-cell tap + the ledger's hit* tokens (the {@code cc.server.hitCellRow}
     *  INSTRUMENT knob) — at the mint, BEFORE the lazy {@link #install}. */
    public void armHitCell(boolean on) {
        hitCellTap.arm(on);
        hullSources.armHitCell(on);
    }

    /** round-line tail: the ledger's four pairing meters + {@code hit_cell_faults=} (tap
     *  throws swallowed in {@link ArcHitCellTap#modifyDamageTaken}); {@code ""} unarmed = byte-identical round lines.
     *  The faults token follows the round-line meter law (a meter rides a round line or does not exist). */
    public String hitCellMeterTokens() {
        return hitCellTap.armed() ? hullSources.hitCellMeterTokens() + " hit_cell_faults=" + hitCellTap.faults() : "";
    }

    /** The hull-source ledger this probe feeds (the binding's {@code logHullDrops} joins on it). */
    public HullSourceLedger hullSources() {
        return hullSources;
    }

    /** The missile last-impact memo this probe feeds (the binding's delist triage joins on it). */
    public MissileImpactMemo missileImpacts() {
        return missileImpacts;
    }

    /** Emitted ARC_HIT events (cumulative, this probe). */
    long emitCount() {
        return emitted;
    }

    /** Emitted LASH / LASH_FRIENDLY events (a subset of {@link #emitCount()}). */
    long lashEmitCount() {
        return lashEmitted;
    }

    /** LASH arcs that landed on an OVERLOADED victim (a subset of {@link #lashEmitCount()}). */
    long lashOverloadedCount() {
        return lashOverloaded;
    }

    /** Emitted ION events (a subset of {@link #emitCount()}). */
    long ionEmitCount() {
        return ionEmitted;
    }

    /** Emitted SABOT events (a subset of {@link #emitCount()}). */
    long sabotEmitCount() {
        return sabotEmitted;
    }

    /**
     * Round end — print the last PARTIAL {@code arc_hit_hist} bucket (the drain
     * only closes a bucket when a later tick arrives). Idempotent; silent when empty.
     * also flushes every open beam-missile aggregate ({@link #flushBeamTail}).
     */
    void flushHistTail() {
        String tail = hist.flush();
        if (tail != null) {
            System.out.println(tail);
        }
        flushBeamTail();
    }

    /**
     * The beam feed's round tail (called from {@link #flushHistTail}, ahead of the round line): one
     * row per STAMPED open aggregate, {@code tick=} the last drain's simTick; reports captured after
     * that drain were never stamped and are dropped — exactly like the arc lane's un-drained
     * {@code pending} (a never-stamped aggregate prints nothing). Clears the open table and the
     * weapon-id cache (no engine object outlives the round here). Idempotent.
     *
     */
    private void flushBeamTail() {
        try {
            for (BeamAgg a : beamOpen) {
                if (a.ticks > 0) {
                    emitBeamRow(beamLastDrainTick, a);
                }
            }
        } catch (Throwable failure) {
            noteBeamBlind("tail", failure);
        }
        beamOpen.clear();
        beamWeaponIds.clear();
    }

    /**
     * The beam feed's ONE round-line token (appended by {@code DownLaneSeams}; leading space). Public:
     * the rows parse it. The seven {@code beam_missile_*} tokens it printed before were deleted as
     * unread (three flight grades read none); their counters stay, read by the
     * BM rows through {@link #beamMissileCounterForTest}.
     *
     * <p>{@code beam_feed_quiet=}: {@code reportDamageApplied} callbacks with a non-null
     * target that {@link #capture} dropped at the arc lane's quiet gate while the beam feed was live —
     * EVERY such report (ship or missile target, beam or not; no extra engine read to classify it), so
     * it is an upper bound on the seat beam reports the tripped arc lane kept from the feed; 0 while
     * the arc lane is live.
     */
    public String beamMissileMeterTokens() {
        return " beam_feed_quiet=" + beamQuietDrops;
    }

    /** counters-line value: top victims by arc-dealt hull ({@link ArcHitHistogram#summary}). */
    String byTargetSummary() {
        return hist.summary();
    }

    /** Victims the summary did not name. */
    int byTargetMore() {
        return hist.summaryMore();
    }

    /** {@code arc_hit_hist} rows printed so far. */
    long histRowCount() {
        return hist.rowCount();
    }

    private static String flag(Boolean b) {
        return b == null ? "?" : b.toString();
    }

    /** Arc hits dropped at DRAIN only: source/target wire id unresolvable or a non-finite node point. */
    long skipUnresolvedCount() {
        return skipUnresolved;
    }

    /** Ship-on-ship arcs dropped at CAPTURE: the engine handed a null {@code ApplyDamageResultAPI} (T2 F3). */
    long skipNullResultCount() {
        return skipNullResult;
    }

    /** Arc damage events whose target (missile) or source was not a Ship. */
    long skipNonShipCount() {
        return skipNonShip;
    }

    /** Damage callbacks whose param was an {@code EmpArcEntity} (all outcomes). */
    long seenCount() {
        return seen;
    }

    /**
     * EVERY {@code reportDamageApplied} callback the proxy received (arc or not, quiet or not) —
     * the per-serve count of sim-thread invokes the hot path paid (T2 F2 / S3(b)).
     */
    long callbackCount() {
        return callbacks;
    }

    /**
     * Sim thread. The hot path for every non-arc damage event is ONE cached invoke + one
     * {@code isInstance}; asteroid targets carry no param seam
     * and pass uncounted.
     */
    private void capture(Object source, Object target, Object result) {
        callbacks++;
        if (target == null) {
            if (failSoft.quiet()) {
                hullSources.markBlind(); // R4: a quiet probe feeds nothing — rows read src=blind, never script
            }
            return;
        }
        // the memo feed runs FIRST in
        // its OWN try/catch, OUTSIDE the arc lane's 3-strike fail-soft (sum-code-gpee-v15.md:9-12:
        // three throws in ANY arc-lane code used to make every later hull contact TARGET_ABSENT for
        // the session). A feed throw counts missile_impact_blind= (MissileImpactMemo.noteFeedBlind,
        // logged once), never strikes failSoft, never blocks the arc processing below. The param and
        // the result floats it reads are handed on, so the sim-thread invoke count is unchanged while the arc lane is live;
        // once quiet, the feed alone still reads (param + 3 floats on a missile impact) — the intended cost
        // of an unbudgeted feed.
        boolean shipTarget = false;
        boolean paramRead = false;
        Object param = null;
        boolean floatsRead = false;
        float dmgHull = 0f;
        float dmgArmor = 0f;
        float dmgShields = 0f;
        try {
            shipTarget = shipClass.isInstance(target);
            if (shipTarget) {
                param = shipGetParam.invoke(target);
                paramRead = true;
                // A MISSILE param = a missile impact on this ship —
                // memo its victim + shield verdict for the MISSILE_END victim form (missile impacts
                // only: the shield pair costs nothing on any other report).
                if (result != null && missileClass != null && missileClass.isInstance(param)) {
                    dmgHull = ServeProbeSupport.floatOf(getDamageToHull.invoke(result));
                    dmgArmor = ServeProbeSupport.floatOf(getTotalDamageToArmor.invoke(result));
                    dmgShields = ServeProbeSupport.floatOf(getDamageToShields.invoke(result));
                    floatsRead = true;
                    float overMax = getOverMaxDamageToShields == null ? 0f
                            : ServeProbeSupport.floatOf(getOverMaxDamageToShields.invoke(result));
                    missileImpacts.record(param, target, dmgHull, dmgArmor, dmgShields, overMax);
                }
            }
        } catch (Throwable feedFailure) {
            floatsRead = false; // a partial read is never trusted by the hull-source record below
            missileImpacts.noteFeedBlind(feedFailure);
        }
        if (failSoft.quiet()) {
            hullSources.markBlind(); // R4: a quiet probe feeds nothing — rows read src=blind, never script
            // The beam feed below never sees this report — count it.
            if (beamFeedLive) {
                beamQuietDrops++;
            }
            return;
        }
        boolean recorded = false; // A throw BEFORE the ledger record strands this hit's pre-hit
        try {
            if (!shipTarget) {
                if (missileGetParam != null && missileClass.isInstance(target)) {
                    param = missileGetParam.invoke(target);
                    // A SEAT beam on a missile feeds the
                    // beam-missile aggregate — its own try/catch, never a fail-soft strike.
                    if (beamFeedLive) {
                        feedBeamMissile(source, target, param, result);
                    }
                } else {
                    return;
                }
            } else if (!paramRead) {
                param = shipGetParam.invoke(target); // the feed's read threw: the arc lane re-reads (today's strike)
            }
            if (shipTarget && result != null) {
                // Every ship-target report (arc or not) names this tick's hull source.
                // The shield sum rides only while the shield window is armed
                // (cc.server.shieldedRow) — unarmed, the hot path stays two invokes exactly.
                if (!floatsRead) {
                    dmgHull = ServeProbeSupport.floatOf(getDamageToHull.invoke(result));
                    dmgArmor = ServeProbeSupport.floatOf(getTotalDamageToArmor.invoke(result));
                    dmgShields = hullSources.shieldWindowArmed()
                            ? ServeProbeSupport.floatOf(getDamageToShields.invoke(result)) : 0f;
                }
                // Set BEFORE the call — record() pops this report's own pre-hit first, so a
                // throw inside it (OOM only) must not make the catch discard a SECOND slot (the outer hit's).
                recorded = true;
                hullSources.record(source, target, param, dmgHull, dmgArmor,
                        hullSources.shieldWindowArmed() ? dmgShields : 0f);
            }
            if (param == null || !arcClass.isInstance(param)) {
                return;
            }
            seen++;
            if (!shipTarget || source == null || !shipClass.isInstance(source)) {
                skipNonShip++;
                return;
            }
            if (result == null) {
                skipNullResult++;
                return;
            }
            Object loc = getTargetLocation.invoke(param);
            float x = loc == null ? Float.NaN : vecX.getFloat(loc);
            float y = loc == null ? Float.NaN : vecY.getFloat(loc);
            boolean shield = Boolean.TRUE.equals(isShieldHit.invoke(param));
            float armor = ServeProbeSupport.floatOf(getTotalDamageToArmor.invoke(result));
            float hull = ServeProbeSupport.floatOf(getDamageToHull.invoke(result));
            float shields = ServeProbeSupport.floatOf(getDamageToShields.invoke(result));
            int flavor = GameplayEdgeEvent.ARC_FLAVOR_OTHER;
            Boolean tgtPhased = null;
            Boolean tgtOverloaded = null;
            if (impactSetField != null) {
                Object set = impactSetField.get(param);
                if (set != null) {
                    if (set == inimicalImpactSet) {
                        flavor = GameplayEdgeEvent.ARC_FLAVOR_INIMICAL;
                    } else if (set == voltaicImpactSet
                            && !(pierceField != null && pierceField.getBoolean(param))) {
                        // The shield-piercing twin is the Voltaic CANNON — stays OTHER
                        flavor = GameplayEdgeEvent.ARC_FLAVOR_VOLTAIC;
                    } else if (lashEnemyImpactSet != null && set == lashEnemyImpactSet) {
                        // The lash ENEMY branch + the witness reads
                        flavor = GameplayEdgeEvent.ARC_FLAVOR_LASH;
                        if (shipIsPhased != null) {
                            tgtPhased = asBoolean(shipIsPhased.invoke(target));
                        }
                        if (shipGetFluxTracker != null && fluxIsOverloaded != null) {
                            Object tracker = shipGetFluxTracker.invoke(target);
                            tgtOverloaded = tracker == null ? null
                                    : asBoolean(fluxIsOverloaded.invoke(tracker));
                        }
                    } else if (lashFriendlyImpactSet != null && set == lashFriendlyImpactSet) {
                        flavor = GameplayEdgeEvent.ARC_FLAVOR_LASH_FRIENDLY;
                    } else if (tachyonImpactSet != null && set == tachyonImpactSet
                            && pierceField != null && !pierceField.getBoolean(param)
                            && damField != null) {
                        // The projectile ion family — non-piercing (not the lance / ion
                        // beam / pilum twins) with arc damage > 0; the same recipe at
                        // dam 0 is the sabot MIRV child, its own code.
                        flavor = damField.getFloat(param) > 0f
                                ? GameplayEdgeEvent.ARC_FLAVOR_ION
                                : GameplayEdgeEvent.ARC_FLAVOR_SABOT;
                    }
                }
            }
            pending.add(new Raw(source, target, flavor, x, y, shield, armor, hull, shields,
                    tgtPhased, tgtOverloaded));
        } catch (Throwable failure) {
            if (shipTarget && !recorded) {
                // This report never reached the ledger, so its own pre-hit would sit
                // above an OUTER hit's and hand that outer report the wrong cell — discard it, counted.
                hullSources.discardPreHit(target);
            }
            failSoft.fail("capture", -1L, failure);
        }
    }

    /**
     * The beam feed's capture side — sim thread, called from {@link #capture}'s MISSILE-target branch
     * with the param already read. Records when the param is a {@code BeamAPI} AND {@code source} is in
     * the seat set the LAST drain cached (captures before the first drain see an empty set; a seat
     * change lags one tick). Cost law: a projectile / arc param = ONE {@code isInstance};
     * a FOE beam = + ONE identity-set probe; a SEAT beam report = {@code getDamageToHull} +
     * {@code getTotalDamageToArmor} + {@code getHitpoints} (3 cached invokes), + {@code getWeapon} +
     * {@code getId} on the FIRST report of each beam object only (the {@link #beamWeaponIds} identity
     * cache), + one identity-map get while the aggregate's missile id still reads -1, + a ≤ 64-entry
     * identity key scan. No lock: capture and {@link #beamDrain} share the sim thread. The WHOLE feed
     * rides its OWN try/catch (the memo-feed law): a throw counts {@code beamBlind} (logged
     * once), never a {@code failSoft} strike, never blocks the arc lane. It sits behind the arc lane's
     * quiet gate: a tripped arc lane records no new beam reports (counted {@code beam_feed_quiet=};
     * open aggregates still flush).
     *
     */
    private void feedBeamMissile(Object source, Object missile, Object param, Object result) {
        try {
            if (param == null || source == null || !beamApi.isInstance(param) || !seatShips.contains(source)) {
                return;
            }
            beamReports++;
            float dmg = 0f;
            if (result == null) {
                beamNullResult++;
            } else {
                dmg = clampDamage(ServeProbeSupport.floatOf(getDamageToHull.invoke(result)))
                        + clampDamage(ServeProbeSupport.floatOf(getTotalDamageToArmor.invoke(result)));
            }
            float hp = -1f;
            try {
                float read = ServeProbeSupport.floatOf(missileGetHitpoints.invoke(missile));
                if (Float.isFinite(read) && read >= 0f) {
                    hp = read;
                } else {
                    noteBeamBlind("hp", null);
                }
            } catch (Throwable hpFailure) {
                noteBeamBlind("hp", hpFailure); // the report still records, hp=-1
            }
            BeamAgg agg = openBeamAgg(missile, source, beamWeaponIdOf(param));
            if (agg == null) {
                return; // beamOpenOverflow counted
            }
            if (agg.missileId < 0L && missileStableIdFn != null) {
                agg.missileId = missileStableIdFn.applyAsLong(missile);
            }
            agg.touched = true;
            agg.pendReports++;
            agg.pendDmg += dmg;
            agg.pendHp = hp;
        } catch (Throwable feedFailure) {
            noteBeamBlind("capture", feedFailure);
        }
    }

    /** The open aggregate for this key, opening one if the table has room; {@code null} = overflow
     *  (counted). Identity on missile + source, equals on the weapon id. */
    private BeamAgg openBeamAgg(Object missile, Object source, String weaponId) {
        for (int i = 0; i < beamOpen.size(); i++) {
            BeamAgg a = beamOpen.get(i);
            if (a.missile == missile && a.source == source && a.weaponId.equals(weaponId)) {
                return a;
            }
        }
        if (beamOpen.size() >= BEAM_MISSILE_OPEN_MAX) {
            beamOpenOverflow++;
            return null;
        }
        BeamAgg a = new BeamAgg(missile, source, weaponId);
        beamOpen.add(a);
        return a;
    }

    /** {@code BeamAPI.getWeapon().getId()}, sanitized ({@code "-"} when the beam has no weapon), cached
     *  per beam object. */
    private String beamWeaponIdOf(Object beam) throws ReflectiveOperationException {
        String cached = beamWeaponIds.get(beam);
        if (cached != null) {
            return cached;
        }
        Object weapon = beamGetWeapon.invoke(beam);
        Object id = weapon == null ? null : weaponGetId.invoke(weapon);
        String weaponId = HullSourceLedger.sanitize(id == null ? null : id.toString());
        if (beamWeaponIds.size() >= BEAM_MISSILE_OPEN_MAX) {
            beamWeaponIds.clear();
        }
        beamWeaponIds.put(beam, weaponId);
        return weaponId;
    }

    /**
     * Drain side — serve loop, once per tick, sim thread: (1) refresh the seat set
     * (ONE supplier call — the binding allocates ≤ one identity set per call) and the stable-id peek;
     * (2) stamp every aggregate touched since the last drain with {@code simTick} (a source wire id
     * resolves here, lazily fetching the two maps only when one is still unresolved); (3) flush every
     * untouched aggregate {@link #BEAM_MISSILE_STALE_TICKS} or more ticks stale. Steps (1) and (2)-(3)
     * ride separate try/catches (a seat-supplier fault never stalls the flush); a fault counts
     * {@code beamBlind}, never a {@code failSoft} strike.
     */
    private void beamDrain(long simTick, Supplier<Map<Object, Long>> shipsSupplier,
            Supplier<Map<Long, Long>> wireIdByPositionShipId, Supplier<Set<Object>> seatShipsSupplier,
            ToLongFunction<Object> missileStableIds) {
        missileStableIdFn = missileStableIds;
        try {
            Set<Object> seats = seatShipsSupplier == null ? null : seatShipsSupplier.get();
            seatShips = seats == null ? Collections.emptySet() : seats;
        } catch (Throwable seatFailure) {
            noteBeamBlind("seats", seatFailure);
        }
        beamLastDrainTick = simTick;
        if (beamOpen.isEmpty()) {
            return;
        }
        try {
            Map<Object, Long> ships = null;
            Map<Long, Long> wireIds = null;
            Iterator<BeamAgg> it = beamOpen.iterator();
            while (it.hasNext()) {
                BeamAgg a = it.next();
                if (a.touched) {
                    a.stamp(simTick);
                    if (a.srcWireId <= 0L) {
                        if (wireIds == null) {
                            ships = shipsSupplier.get();
                            wireIds = wireIdByPositionShipId.get();
                        }
                        a.srcWireId = ServeProbeSupport.wireIdOf(a.source, ships, wireIds);
                    }
                } else if (simTick - a.tickLast >= BEAM_MISSILE_STALE_TICKS) {
                    it.remove();
                    emitBeamRow(simTick, a);
                }
            }
            if (beamOpen.isEmpty()) {
                beamWeaponIds.clear();
            }
        } catch (Throwable drainFailure) {
            noteBeamBlind("drain", drainFailure);
        }
    }

    /** One flush: an unresolved source (wire id ≤ 0) counts {@code beamUnresolved}, no row; past
     *  {@link #BEAM_MISSILE_ROW_MAX} counted only. */
    private void emitBeamRow(long flushTick, BeamAgg a) {
        if (a.srcWireId <= 0L) {
            beamUnresolved++;
            return;
        }
        if (beamRows >= BEAM_MISSILE_ROW_MAX) {
            beamRowsCapped++;
            return;
        }
        beamRows++;
        String row = beamMissileRow(flushTick, a);
        beamRowSink.accept(row);
        if (beamRowsSeam.size() >= BEAM_MISSILE_SEAM_ROWS) {
            beamRowsSeam.removeFirst();
        }
        beamRowsSeam.addLast(row);
    }

    /**
     * THE {@code beam-missile-hit} row grammar (printed AND handed to {@link #beamMissileRowsForTest}):
     * {@code tick=} the flush drain; {@code ship=} the source seat's wire id (> 0); {@code weapon=} the
     * sanitized weapon id; {@code missile=} the stable id captured at record time (≥ 1e9) or -1;
     * {@code first=}/{@code last=} the first/last stamped drain ticks; {@code ticks=} distinct drain
     * ticks (≥ 1); {@code dmg=} Σ (hull + armor) dealt, clamped ≥ 0; {@code hp=} the missile's POST-hit
     * hp at the last stamped report, -1 when unreadable; {@code reports=} the stamped report count
     * (appended: ticks vs reports separates frames-per-tick from missed ticks).
     *
     */
    private static String beamMissileRow(long flushTick, BeamAgg a) {
        return "beam-missile-hit tick=" + flushTick
                + " ship=" + a.srcWireId
                + " weapon=" + a.weaponId
                + " missile=" + (a.missileId >= 0L ? a.missileId : -1L)
                + " first=" + a.tickFirst
                + " last=" + a.tickLast
                + " ticks=" + a.ticks
                + " dmg=" + String.format(Locale.ROOT, "%.1f", a.dmg)
                + " hp=" + (a.hpLast < 0f ? "-1" : String.format(Locale.ROOT, "%.1f", a.hpLast))
                + " reports=" + a.reports;
    }

    /** Count one beam-feed fault; the FIRST logs one bounded line (site + exception class name only).
     * */
    private void noteBeamBlind(String site, Throwable failure) {
        beamBlind++;
        if (!beamBlindLogged) {
            beamBlindLogged = true;
            Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                    ? failure.getCause() : failure; // name the engine-side throw, not the reflective wrapper
            System.out.println("arc-hit probe beam-missile feed fault site=" + site + " cause="
                    + (cause == null ? "non-finite-hp" : cause.getClass().getName())
                    + " [counted; later faults count silently, this row prints once per probe]");
        }
    }

    /**
     * Test factory (game-free rows):
     * a probe marked installed over stand-in classes, resolving the SAME method names
     * {@link #install} resolves ({@code getParamAboutToApplyDamage} on ship/missile,
     * {@code getTargetLocation}/{@code isShieldHit} on the arc, the four result getters, the
     * location's public {@code x}/{@code y}) — WITHOUT the global-manager install or the flavor-key
     * store (every arc = OTHER). {@code missile} may be {@code null} (the install's missile miss).
     *
     */
    public static ArcHitServeProbe forTest(Class<?> ship, Class<?> missile, Class<?> arc, Class<?> resultApi)
            throws ReflectiveOperationException {
        return forTest(ship, missile, arc, resultApi, null);
    }

    /**
     * The {@link #forTest(Class, Class, Class, Class)} factory plus the beam feed over
     * a stand-in {@code beam} class ({@code getWeapon()} whose return type answers {@code getId()}) and
     * the fake missile's {@code getHitpoints()} — the SAME method names {@link #resolveBeamFeed} resolves
     * off the API interfaces. {@code beam == null} (or {@code missile == null}) ⇒ the feed stays inert,
     * so every older row's probe is byte-equal.
     */
    public static ArcHitServeProbe forTest(Class<?> ship, Class<?> missile, Class<?> arc, Class<?> resultApi,
            Class<?> beam) throws ReflectiveOperationException {
        ArcHitServeProbe p = new ArcHitServeProbe();
        p.shipClass = ship;
        p.arcClass = arc;
        p.shipGetParam = accessible(ship.getMethod("getParamAboutToApplyDamage"));
        if (missile != null) {
            p.missileClass = missile;
            p.missileGetParam = accessible(missile.getMethod("getParamAboutToApplyDamage"));
        }
        p.getTargetLocation = accessible(arc.getMethod("getTargetLocation"));
        p.isShieldHit = accessible(arc.getMethod("isShieldHit"));
        p.getTotalDamageToArmor = accessible(resultApi.getMethod("getTotalDamageToArmor"));
        p.getDamageToHull = accessible(resultApi.getMethod("getDamageToHull"));
        p.getDamageToShields = accessible(resultApi.getMethod("getDamageToShields"));
        p.getOverMaxDamageToShields = accessible(resultApi.getMethod("getOverMaxDamageToShields"));
        Class<?> vecClass = p.getTargetLocation.getReturnType();
        p.vecX = vecClass.getField("x");
        p.vecY = vecClass.getField("y");
        p.hitCellTap.bindShipClass(ship);
        p.hitCellTap.resolveHitCellHandles(ship, vecClass); // The SAME names installHitCellTap resolves
        if (beam != null && missile != null) {
            p.beamApi = beam;
            p.beamGetWeapon = accessible(beam.getMethod("getWeapon"));
            p.weaponGetId = accessible(p.beamGetWeapon.getReturnType().getMethod("getId"));
            p.missileGetHitpoints = accessible(missile.getMethod("getHitpoints"));
            p.beamFeedLive = true;
        }
        p.installed = true;
        p.hullSources.markLive();
        return p;
    }

    /** Test seam: the REAL serve-loop {@link #drain} with all four suppliers.
     * */
    public List<GameplayEdgeEvent> drainForTest(long simTick, Supplier<Map<Object, Long>> shipsSupplier,
            Supplier<Map<Long, Long>> wireIdByPositionShipId, Supplier<Set<Object>> seatShipsSupplier,
            ToLongFunction<Object> missileStableIds) {
        return drain(simTick, shipsSupplier, wireIdByPositionShipId, seatShipsSupplier, missileStableIds);
    }

    /** Test seam: the round tail ({@link #flushHistTail}). */
    public void flushRoundTailForTest() {
        flushHistTail();
    }

    /** Test seam: the last ≤ {@link #BEAM_MISSILE_SEAM_ROWS} PRINTED {@code beam-missile-hit} rows, oldest
     *  first — the same strings the sink printed. */
    public List<String> beamMissileRowsForTest() {
        return new ArrayList<>(beamRowsSeam);
    }

    /** Test seam: one beam-feed counter by FIELD name (their round-line tokens were deleted as unread;
     * the BM rows still pin every count). An unknown name throws, so a typo fails loud.
     * */
    public long beamMissileCounterForTest(String field) {
        switch (field) {
            case "beamReports": return beamReports;
            case "beamRows": return beamRows;
            case "beamRowsCapped": return beamRowsCapped;
            case "beamOpenOverflow": return beamOpenOverflow;
            case "beamUnresolved": return beamUnresolved;
            case "beamNullResult": return beamNullResult;
            case "beamBlind": return beamBlind;
            default: throw new IllegalArgumentException("no beam-feed counter " + field);
        }
    }

    /** Test seam: redirect the row sink (default {@code System.out::println}) — the row-cap row mutes
     *  2000 prints. The row string and the seam ring are unchanged. */
    public void setBeamRowSinkForTest(Consumer<String> sink) {
        beamRowSink = sink;
    }

    /** Test seam: the listener's {@code reportDamageApplied(source, target, result)} entry.
     * */
    public void captureForTest(Object source, Object target, Object result) {
        capture(source, target, result);
    }

    /**
     * Test seams: {@code modifyForTest} = the REAL tap body with the engine's five
     * arguments, returning what the engine would receive; {@code hitCellProxyForTest} = the REAL
     * {@code ArcHitCellTap.HitCellTap} behind a {@link java.lang.reflect.Proxy} over {@code iface} (a stand-in
     * String-returning interface). All delegate to {@link #hitCellTap} since the carve.
     *
     */
    public Object modifyForTest(Object param, Object target, Object damage, Object point, boolean shieldHit) {
        return hitCellTap.modifyDamageTaken(new Object[] {param, target, damage, point, shieldHit});
    }

    public <T> T hitCellProxyForTest(Class<T> iface) {
        return hitCellTap.proxyForTest(iface);
    }

    public long hitCellFaultsForTest() {
        return hitCellTap.faults();
    }

    /** Test seam: the tap's cost-meter sample count (every armed call = one sample).
     * */
    public int hitCostSamplesForTest() {
        return hitCellTap.hitCostSamplesForTest();
    }

    /** Test seams (rows R10-R13): the tap's cost-meter state {@code {firstNanos, warmSamples,
     *  warmNanos, maxNanos, maxAt}} and the pre-warm's iteration count. */
    public long[] hitCostStatsForTest() {
        return hitCellTap.hitCostStatsForTest();
    }

    public static int hitCellPrewarmCallsForTest() {
        return ArcHitCellTap.PREWARM_CALLS;
    }

    /**
     * Test seam: {@link #installOn}'s hit-cell half, in its order — the tap's marker-dedupe, the Ship
     * class bind ({@code shipClass}; the rows bind the API {@code ShipAPI} their proxy hull implements), then the
     * REAL {@code installHitCellTap} with a caller's {@code vecClass} and {@code ship} (nullable = no pre-warm).
     * WHY a second seam: {@link #installOn} resolves the engine-internal classes first, which the rows' API-jar loader
     * lacks (row 9 relies on that throw), so it can never reach the tap's add there.
     */
    public void installHitCellTapForTest(long simTick, ClassLoader loader, Object manager, Class<?> shipClass,
            Class<?> vecClass, Object ship) throws Exception {
        Class<?> managerClass = Class.forName(LISTENER_MANAGER, false, loader);
        hitCellTap.removeStale(loader, manager, managerClass.getMethod("getListeners", Class.class),
                managerClass.getMethod("removeListener", Object.class));
        hitCellTap.bindShipClass(shipClass);
        hitCellTap.installHitCellTap(simTick, loader, manager, managerClass.getMethod("addListener", Object.class),
                vecClass, ship);
    }

    /** Test seam: the REAL {@link #installOn} over a caller's loader + listener manager (row 9
     *  drives it through the game's API jar and a fake manager).
     *  No hull here — {@code ship = null}, so no pre-warm. */
    public void installOnForTest(long simTick, ClassLoader loader, Object manager) throws Exception {
        installOn(simTick, loader, manager, null);
    }

    /** Lens h2 Q6 test seams: the two accumulated dedupe counts the install lines
     *  print as {@code prior_removed=} — the hit-cell tap's and the Capture's (row 9 reads both across attempts). */
    public int hitCellRemovedForTest() {
        return hitCellTap.removed();
    }

    public int captureRemovedForTest() {
        return captureRemoved;
    }

    /** Test seam: arc captures awaiting a drain. */
    public int pendingCountForTest() {
        return pending.size();
    }

    /** Test seam: the arc lane's fail-soft strikes so far ({@code >= 3} = quiet).
     * */
    public int failSoftLinesForTest() {
        return failSoft.lines();
    }

    /** One-time reflective resolution + the global-manager install (marker-dedupe first). */
    private void install(long simTick, Object ship) throws Exception {
        ClassLoader loader = ship.getClass().getClassLoader();
        ServeProbeSupport.EngineHandle engine = ServeProbeSupport.combatEngine(loader);
        Object manager = engine.type().getMethod("getListenerManager").invoke(engine.instance());
        installOn(simTick, loader, manager, ship);
    }

    /**
     * The install body over a resolved manager. BOTH marker
     * dedupes run FIRST — the hit-cell tap's ({@link ArcHitCellTap#removeStale}, never throws), then the
     * Capture's (propagates, as before; MAIN DECISION 2(a)) — ahead of EVERY resolve that can throw. The engine
     * (and its global listener manager) is REUSED across rounds, so a round whose install threw at a resolve used
     * to leave the PRIOR round's tap and Capture feeding the dead probe's ledger (never {@code endTick}ed) and
     * its unbounded {@link #pending} for the whole round (the drain retries until quiet). The adds keep their
     * order (Capture, then the tap LAST) and a successful install prints the same lines; both removal counts
     * ACCUMULATE across attempts, one per removal (a throw mid-dedupe keeps the partial count — lens h2 S3-1,
     * row 9), so a retry that succeeds prints the true {@code prior_removed=}. NOT handled
     * (the wrap window): between a round's mint and its first install the prior round's listeners still fire —
     * a round-wrap uninstall would live in PilotServeSession.
     *
     * <p>{@code ship} = the live hull {@link #install} holds (nullable; {@link #installOnForTest}
     * passes null), threaded to the tap's install-time pre-warm.
     */
    private void installOn(long simTick, ClassLoader loader, Object manager, Object ship) throws Exception {
        Class<?> managerClass = Class.forName(LISTENER_MANAGER, false, loader);
        Method getListeners = managerClass.getMethod("getListeners", Class.class);
        Method removeListener = managerClass.getMethod("removeListener", Object.class);
        Method addListener = managerClass.getMethod("addListener", Object.class);
        hitCellTap.removeStale(loader, manager, getListeners, removeListener);
        Class<?> listenerClass = Class.forName(DAMAGE_LISTENER, false, loader);
        List<?> existing = (List<?>) getListeners.invoke(manager, listenerClass);
        if (existing != null) {
            for (Object prior : new ArrayList<>(existing)) {
                if (prior != null && MARKER.equals(prior.toString())) {
                    removeListener.invoke(manager, prior);
                    captureRemoved++; // per removal: a mid-loop throw keeps the partial count (lens h2 S3-1)
                }
            }
        }
        shipClass = Class.forName(SHIP_CLASS, false, loader);
        hitCellTap.bindShipClass(shipClass);
        arcClass = Class.forName(ARC_CLASS, false, loader);
        Class<?> resultApi = Class.forName(RESULT_API, false, loader);
        shipGetParam = accessible(shipClass.getMethod("getParamAboutToApplyDamage"));
        try {
            missileClass = Class.forName(MISSILE_CLASS, false, loader);
            missileGetParam = accessible(missileClass.getMethod("getParamAboutToApplyDamage"));
        } catch (Throwable missileMiss) {
            // Missile-node arcs then pass uncounted — a census nicety, not a wire fact.
            missileClass = null;
            missileGetParam = null;
        }
        getTargetLocation = accessible(arcClass.getMethod("getTargetLocation"));
        isShieldHit = accessible(arcClass.getMethod("isShieldHit"));
        getTotalDamageToArmor = resultApi.getMethod("getTotalDamageToArmor");
        getDamageToHull = resultApi.getMethod("getDamageToHull");
        getDamageToShields = resultApi.getMethod("getDamageToShields");
        try {
            getOverMaxDamageToShields = resultApi.getMethod("getOverMaxDamageToShields");
        } catch (Throwable overMaxMiss) {
            getOverMaxDamageToShields = null; // The zero-headroom tell degrades to 0 (one log token)
        }
        Class<?> vecClass = getTargetLocation.getReturnType();
        vecX = vecClass.getField("x");
        vecY = vecClass.getField("y");
        resolveFlavorKeys(simTick, loader);
        resolveBeamFeed(simTick, loader);
        ServeProbeSupport.installListener(loader, listenerClass, new Capture(), addListener, manager);
        installed = true;
        hullSources.markLive();
        System.out.println("arc-hit probe installed tick=" + simTick
                + " prior_removed=" + captureRemoved
                + " flavor_keys=" + (inimicalImpactSet != null) + "/" + (voltaicImpactSet != null)
                + " lash_keys=" + (lashEnemyImpactSet != null) + "/" + (lashFriendlyImpactSet != null)
                + " pierce_field=" + (pierceField != null)
                + " ion_key=" + (tachyonImpactSet != null && damField != null)
                + " missile_param=" + (missileGetParam != null)
                + " missile_memo=" + (missileClass != null)
                + " overmax_shield=" + (getOverMaxDamageToShields != null)
                + " beam_feed=" + beamFeedLive);
        hitCellTap.installHitCellTap(simTick, loader, manager, addListener, vecClass, ship);
    }

    /**
     * The beam feed's handles, resolved ONCE off the API interfaces (the
     * {@code EngineCombatBinding.resolveMissilePoseMethods} idiom — never a per-report
     * {@code getClass().getMethod}): {@code BeamAPI.getWeapon()} → {@code WeaponAPI.getId()}, and
     * {@code MissileAPI.getHitpoints()}. Needs the missile param seam; any miss leaves the feed inert
     * (one line, {@code beam_feed=false} on the install line).
     */
    private void resolveBeamFeed(long simTick, ClassLoader loader) {
        try {
            if (missileClass == null || missileGetParam == null) {
                throw new IllegalStateException("missile param seam unresolved");
            }
            Class<?> beam = Class.forName(BEAM_API, false, loader);
            Method getWeapon = beam.getMethod("getWeapon");
            Method getId = getWeapon.getReturnType().getMethod("getId");
            Method getHitpoints = Class.forName(MISSILE_API, false, loader).getMethod("getHitpoints");
            beamApi = beam;
            beamGetWeapon = getWeapon;
            weaponGetId = getId;
            missileGetHitpoints = getHitpoints;
            beamFeedLive = true;
        } catch (Throwable beamMiss) {
            beamFeedLive = false;
            System.out.println("arc-hit probe beam_feed_unresolved tick=" + simTick
                    + " [no beam-missile-hit rows] " + beamMiss.getClass().getName());
        }
    }

    /** Identity keys for the two named impact sets; a miss ⇒ every arc is OTHER (one line). */
    private void resolveFlavorKeys(long simTick, ClassLoader loader) {
        try {
            impactSetField = arcClass.getDeclaredField(IMPACT_SET_FIELD);
            impactSetField.setAccessible(true);
            try {
                pierceField = arcClass.getDeclaredField(PIERCE_FIELD);
                pierceField.setAccessible(true);
            } catch (Throwable pierceMiss) {
                pierceField = null;
                System.out.println("arc-hit probe pierce_field_unresolved tick=" + simTick
                        + " [voltaic cannon arcs flavor VOLTAIC] " + pierceMiss.getClass().getName());
            }
            Class<?> specStore = Class.forName(SPEC_STORE, true, loader);
            Class<?> impactSetClass = Class.forName(IMPACT_SET_CLASS, false, loader);
            Method lookup = specStore.getMethod("o00000", Class.class, String.class);
            inimicalImpactSet = lookup.invoke(null, impactSetClass, INIMICAL_IMPACT_SET_ID);
            voltaicImpactSet = lookup.invoke(null, impactSetClass, VOLTAIC_IMPACT_SET_ID);
            try {
                // The lash pair + the witness handles; a miss keeps lash arcs OTHER
                lashEnemyImpactSet = lookup.invoke(null, impactSetClass, LASH_ENEMY_IMPACT_SET_ID);
                lashFriendlyImpactSet = lookup.invoke(null, impactSetClass, LASH_FRIENDLY_IMPACT_SET_ID);
                shipIsPhased = accessible(shipClass.getMethod("isPhased"));
                shipGetFluxTracker = accessible(shipClass.getMethod("getFluxTracker"));
                fluxIsOverloaded = shipGetFluxTracker.getReturnType().getMethod("isOverloaded");
            } catch (Throwable lashMiss) {
                lashEnemyImpactSet = null;
                lashFriendlyImpactSet = null;
                System.out.println("arc-hit probe lash_keys_unresolved tick=" + simTick
                        + " [lash arcs = flavor OTHER] " + lashMiss.getClass().getName()
                        + ": " + lashMiss.getMessage());
            }
            try {
                // The ion key = the SHARED tachyon set + the damage final (the sabot
                // guard); the pierce field above is the third leg. Any miss keeps ion arcs OTHER.
                tachyonImpactSet = lookup.invoke(null, impactSetClass, TACHYON_IMPACT_SET_ID);
                damField = arcClass.getDeclaredField(DAM_FIELD);
                damField.setAccessible(true);
                if (pierceField == null) {
                    throw new IllegalStateException("pierce field unresolved (the beam twins would mis-flavor ION)");
                }
            } catch (Throwable ionMiss) {
                tachyonImpactSet = null;
                damField = null;
                System.out.println("arc-hit probe ion_keys_unresolved tick=" + simTick
                        + " [ion arcs = flavor OTHER] " + ionMiss.getClass().getName()
                        + ": " + ionMiss.getMessage());
            }
        } catch (Throwable miss) {
            impactSetField = null;
            inimicalImpactSet = null;
            voltaicImpactSet = null;
            System.out.println("arc-hit probe flavor_keys_unresolved tick=" + simTick
                    + " [every arc = flavor OTHER] " + miss.getClass().getName()
                    + ": " + miss.getMessage());
        }
    }

    private static Object firstShip(Map<Object, Long> ships) {
        if (ships == null) {
            return null;
        }
        for (Object ship : ships.keySet()) {
            if (ship != null) {
                return ship;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object v) {
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** A non-finite or negative applied damage is never honest — 0 keeps the record legal. */
    private static float clampDamage(float v) {
        return Float.isFinite(v) && v > 0f ? v : 0f;
    }

    private static Method accessible(Method m) {
        m.setAccessible(true);
        return m;
    }
}
