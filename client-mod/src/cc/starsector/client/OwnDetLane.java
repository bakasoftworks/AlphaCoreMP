package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;

import java.util.ArrayDeque;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

/**
 * The OWN det-damage lane, knob {@code cc.predict.ownDetDamage}
 * = {@code off|meter|apply} (default {@code apply}): the MPL det sites (mine, bomb)
 * present server dets as damage-LESS FX, and the per-frame own-hull mirror wipes any local hull
 * move back to the (RTT-stale) wire value — so standing on a det felt like zero damage until the
 * wire caught up (the reported defect). This lane makes the det FELT locally, the
 * vanilla way, without touching authority:
 *
 * <p><b>Damage law</b> —
 * one instant application per det: falloff {@code f = d<=core ? 1 : 1-(d-core)/(r-core)} over the
 * det-center→hit-point distance, {@code dmg = min+(max-min)*f}, type/EMP per spec row, no IFF
 * (vanilla mines carry MISSILE_FF — own dets hurt us) — <b>for a NON-own SOURCE only</b>:
 * the engine's explosion factory adds the SOURCE ship (+ its
 * modules) to {@code damagedAlready} unconditionally
 * and {@code DamagingExplosion.getDamage()} returns 0 for any member, so a ship never takes
 * its OWN mine / bomb / charge blast; the relays pass {@link #ownSourced} and this lane skips those
 * dets ({@code skippedOwnSource}) — before the fix every presented det was applied, own-sourced
 * included, a phantom healed only by the ledger expiry. Spec table (4 rows, read
 * first-hand from the specs): {@code minelayer_mine(_heavy)} 2000/1000 r250/core200 HE;
 * {@code phase_charge_bomber} 500/250 r150/core75 HE (and its byte-identical twin
 * {@code phase_charge}, the ship-mounted phasecl);
 * {@code terminator_missile} 1200 flat
 * ENERGY (direct hit, belt-only radius, EMP wire-owned; row doc below);
 * {@code type_1_srm} 100 flat HE (swarmer direct hit, belt-only radius; row
 * doc below). Unknown specs are counted and skipped.
 * Hit point = the nearest point on the own hull's EXACT BOUNDS
 * (the pre-fix collision-circle point "erred a few su" on frigates and painted the
 * number in empty space on a Ziggurat; {@link #nearestPointOnBounds}, circle = fallback only).
 * Routing = the public {@code engine.applyDamage} (never
 * bypassShields): the target's own shield/armor chew at the hit point ≈ the server's own chew at
 * the same point — the same acceptance as replica-hit chew today. (That "NO armor reconcile lane"
 * acceptance was superseded by the own-armor reconcile:
 * this lane now also owns the OWN-armor fold collaborator {@link #ownArmorLane}, see {@link #maintainOwnArmor}.)
 * {@code OwnDamageCap} caps the lethal edge (axis B) — a local det can never decide death.
 *
 * <p><b>Ledger (the {@link FoeHullReconcileLane.HullLedger} idiom, own-side twin)</b> — the
 * as-applied hull damage is pushed as an un-acked FIFO entry; the own-mirror target at the plugin's
 * mirror site becomes {@code wire − Σ outstanding} (floor = {@code mirrorHull}'s existing
 * [1, max] clamp — no frac floor; the pushed amount is the
 * ACTUAL pre/post hull delta and {@code outstanding} is bounded to the wire's headroom
 * ({@code prevWire−1}) — the W3-K born-growth over-apply could otherwise pin the 1hp floor),
 * entries retire on the wire hull drop (FIFO
 * amount-consume) or expire after {@link #REPLAY_EXPIRY_FRAMES} local frames (≤2 s
 * darker-then-heal-up, the foe-lane disclosed artifact). Per-LIFE: an own-ship identity change
 * (respawn remount) clears the ledger and reseeds from wire. The foe lane's stale-feed
 * suspension is deliberately NOT carried — expiry alone bounds a stalled feed's harm to ≤2 s.
 *
 * <p><b>Witness</b> — {@code OwnDamageCap.reportDamageApplied} originally recorded nothing,
 * so this lane registers explicitly: it rides the own ship as a no-op
 * {@link DamageTakenModifier} + {@link DamageListener} pair, re-entrancy-flag-gated to its OWN
 * applyDamage call (synchronous dispatch), arming the {@link DamageWitness} stash with the
 * engine-true shieldHit and emitting the {@code dmg-apply} line (victim=own) with the engine's
 * as-applied split. {@code OwnDamageCap} now emits for every OTHER own
 * application (local sim hits), consulting {@link #isApplying()} so det applies stay this lane's
 * single row. Caveat [I]: if the
 * cap clamped (lethal edge) and its {@code restoreFx} ran before this listener, the recorded
 * split reads post-restore — rare, diagnostic-only, self-heals via wire consume/expiry.
 *
 * <p>Relays: the three MPL det sites call {@link #onDet} (1 line each — the MPL split stays
 * ARMED); riding inside those sites couples the damage to the FX knobs
 * ({@code mineEndFx}/{@code bombEndFx}, both default on; the terminator-conversion site rides
 * {@code droneStrikeFx} carve state — either knob arm relays, see
 * {@code MineBombEndFxLane.presentConversionEndDet}) — disclosed, accepted for the 1-line
 * relay shape. {@code off} = pre-build behavior byte-for-byte ({@link #onDet} returns, the
 * mirror target passes wire through). {@code meter} computes + logs, never applies.
 * Render-thread only; every entry point fail-soft.
 */
final class OwnDetLane implements DamageTakenModifier, DamageListener {

    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.ownDetDamage",
            System.getProperty("cc.predict.ownDetDamage", "apply"));
    static final boolean METER = !"off".equals(MODE);
    static final boolean APPLY = "apply".equals(MODE);

    /** Replay-entry expiry in LOCAL frames (~2 s) — the foe-lane constant, same grounds. */
    private static final int REPLAY_EXPIRY_FRAMES = 120;
    /** Retire epsilon in hp — the foe-lane constant. */
    private static final float LEDGER_EPS = 0.5f;
    /** Per-det log cap (counters continue past it). */
    private static final int DET_LOG_MAX = 40;

    /** One det-damage spec row.
     *  {@code type}/{@code emp} joined with the terminator row — the mine/bomb rows keep
     *  the original HE/0 behavior byte-for-byte. */
    private static final class DetSpec {
        final float dmgMax, dmgMin, radius, core, emp;
        final DamageType type;

        DetSpec(float dmgMax, float dmgMin, float radius, float core, DamageType type,
                float emp) {
            this.dmgMax = dmgMax;
            this.dmgMin = dmgMin;
            this.radius = radius;
            this.core = core;
            this.type = type;
            this.emp = emp;
        }
    }

    private static final DetSpec MINE =
            new DetSpec(2000f, 1000f, 250f, 200f, DamageType.HIGH_EXPLOSIVE, 0f);
    private static final DetSpec BOMB =
            new DetSpec(500f, 250f, 150f, 75f, DamageType.HIGH_EXPLOSIVE, 0f);
    /** The Apex terminator-CONVERSION direct hit (the terminator row of weapon_data.csv +
     *  terminator_missile_proj.proj, read first-hand): 1200 ENERGY on collision, flat
     *  (dmgMax==dmgMin — no falloff: the .proj explosionSpec carries NO damage keys, the AoE is
     *  0-damage cosmetic). The radius/core belt (120 su past the collision circle) only bounds
     *  relay slop — victim attribution is the relay's nearest-body sweep
     *  ({@code MineBombEndFxLane.presentConversionEndDet}); the belt must clear the
     *  shield-ring-vs-collision-circle delta on big hulls (shield hits arrive at ring range).
     *  EMP deliberately 0 locally: vanilla carries 600, but weapon-disable presentation is
     *  OWNED by the weapon-state wire lane (MEASURED: disables presented while damage
     *  didn't) — exactly-one-owner. The scalar/type pair is package-shared
     *  with the NOT-OWN (replica victim) apply in {@code MineBombEndFxLane} so the two
     *  sites of the same hit cannot drift. */
    static final float TERMINATOR_HIT_DAMAGE = 1200f;
    static final DamageType TERMINATOR_HIT_TYPE = DamageType.ENERGY;
    static final float TERMINATOR_HIT_EMP = 0f;
    private static final DetSpec TERMINATOR =
            new DetSpec(TERMINATOR_HIT_DAMAGE, TERMINATOR_HIT_DAMAGE, 120f, 120f,
                    TERMINATOR_HIT_TYPE, TERMINATOR_HIT_EMP);
    /** The Swarmer SRM direct hit (the weapon_data.csv rows {@code swarmer}/
     *  {@code swarmer_fighter} + type_1_srm.proj, read first-hand): 100 HIGH_EXPLOSIVE on
     *  collision, flat (dmgMax==dmgMin — the .proj carries NO damage keys; its
     *  explosionRadius 60 is commented "purely visual", so like the terminator row the AoE is
     *  cosmetic). EMP 0 = the CSV emp column (blank, both rows). Reaches this lane ONLY via
     *  the MINE det site (a sourceless-adopted swarmer — fighter/station volley in a
     *  source-unbound window; sourced twins ride presented-ordnance
     *  and never relay here): that site has no victim sweep, so this belt is the sole own-hit
     *  gate — 120 su past the collision circle mirrors TERMINATOR on the same grounds (must
     *  clear the shield-ring-vs-collision-circle delta; shield hits end at ring range). Fade
     *  misses never arrive (the relay's {@code t.fizzled} guard — swarmers latch it at actual
     *  fade, unlike the exempted conversion class); a no-record/feed-gap end inside the belt
     *  = the disclosed fizzle-phantom class, bounded by 100 hp + ledger expiry. */
    private static final DetSpec SWARMER =
            new DetSpec(100f, 100f, 120f, 120f, DamageType.HIGH_EXPLOSIVE, 0f);
    /** The
     *  Threat unstable fragment's proximity blast ({@code unstable_fragment_missile.proj}
     *  explosionSpec radius 120 / coreRadius 60; weapon_data.csv {@code unstable_fragment} 750
     *  FRAGMENTATION; fuse min = base/2 = 375). Originally the client
     *  only PAINTED this det ({@code SourcedMissileEndLane.presentUnstableEnd}) and never applied
     *  the blast locally, so the player saw the flash but no damage number while the server's real
     *  {@code DamagingExplosion} took 14-28 armour per det. Reaches
     *  this lane from the unstable-end relay (INTACT/FIZZLING ends only — the keying);
     *  own-ship only, reconcile-clamped like MINE/BOMB. EMP 0 = the CSV emp column (blank). */
    private static final DetSpec UNSTABLE =
            new DetSpec(750f, 375f, 120f, 60f, DamageType.FRAGMENTATION, 0f);

    private static DetSpec specFor(String specId) {
        if (SourcedMissileEndLane.UNSTABLE_SPEC_ID.equals(specId)) {
            return UNSTABLE;
        }
        if ("minelayer_mine".equals(specId) || "minelayer_mine_heavy".equals(specId)) {
            return MINE;
        }
        // A SECOND one-token add — the
        // ship-mounted phasecl's `phase_charge` is the bomber charge's byte-identical twin;
        // an own-hull det now lands the LOCAL hull hit (ABSENT-TELL class,
        // sync-gap-doctrine) instead of waiting for the mirror.
        if (MineBombEndFxLane.isProxChargeSpec(specId)) {
            return BOMB;
        }
        if (DroneStrikeFxLane.CONVERSION_SPEC.equals(specId)) {
            return TERMINATOR;
        }
        if ("type_1_srm".equals(specId)) {
            return SWARMER;
        }
        return null;
    }

    /** One un-acked local det hull hit awaiting wire acknowledgement (FIFO, partial-consumed). */
    private static final class Entry {
        final long frame;
        float hull;

        Entry(long frame, float hull) {
            this.frame = frame;
            this.hull = hull;
        }
    }

    private final PredictReconcileCombatPlugin plugin;

    // === Ledger state (render-thread only; per-LIFE — cleared on own-ship identity change) ===
    private long localFrame;
    private float prevWire = Float.NaN;
    private float outstanding;
    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();
    /** The ship the listener pair is attached to (identity change = respawn ⇒ re-attach + clear). */
    private ShipAPI attachedShip;

    // === Re-entrancy window around our own applyDamage (synchronous dispatch) ===
    private boolean applying;

    /** Lets {@code OwnDamageCap} skip its witness emit while THIS lane's
     *  applyDamage is in flight — det applies keep exactly one victim=own row (the
     *  exactly-one-owner doctrine, here applied to telemetry). */
    boolean isApplying() {
        return applying;
    }

    // === Per-run counters (cumulative across rounds; the counter-line discipline) ===
    private int dets;
    private int applies;
    private int skippedSpec;
    private int skippedDead;
    private int skippedRange;
    /** Dets whose SOURCE is the own ship — engine-exempt, never applied. */
    private int skippedOwnSource;
    private int pushed;
    private int retired;
    private int expired;
    private int lifeClears;
    private float hullAppliedTotal;
    private float maxDetDamage;
    private int detLines;
    // Phantom-excess clamp events (ledger > wire headroom) + total hp dropped.
    private int budgetClamps;
    private float budgetClampTotal;
    // mirror-step meter: TOTAL fresh-snapshot wire hull drops (includes
    // drops whose damage WAS locally felt — the wire echoes local sim ~RTT later; per-bucket
    // felt-vs-mirror discrimination is the dmg-audit's job, these are run-level magnitude only).
    // Drops across a life-clear/round-reset reseed are not counted (prevWire NaN baseline).
    private int wireDrops;
    private float wireDropTotal;
    private float wireDropMax;

    OwnDetLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        ownArmorLane.bindCellLane(ownCellLane); // v11: cellLive disengages the whole-grid fold
    }

    /** Cell-run delivery relay (NET/reader thread at PRCP's AWSD fold site —
     *  the cell lane's inbox is the cross-thread seam). */
    void onCellRecord(long entityId, long serverSimTick, java.util.List<ArmorCellLane.Run> runs) {
        ownCellLane.onRecord(entityId, serverSimTick, runs);
    }

    /**
     * TRUE when the twin's det was SOURCED by the own ship: a
     * link-only own twin ({@code t.own}, the locally fired body) or a wire source id equal to the
     * session ship id (an unlinked own-sourced end — the sourced-end lane's family). The engine
     * exempts the source from its own blast (class javadoc), so such a det never reaches
     * {@code applyDamage} here. Null-safe; a missing session reads as NOT own.
     */
    boolean ownSourced(MissilePoseLane.Twin t) {
        if (t == null) {
            return false;
        }
        if (t.own) {
            return true;
        }
        return t.sourceEntityId > 0L && plugin.session != null
                && t.sourceEntityId == plugin.session.shipId();
    }

    /** The older two-arg relay shape (a NON-own source): {@link #onDet(Vector2f, String, boolean)}. */
    void onDet(Vector2f det, String specId) {
        onDet(det, specId, false);
    }

    /**
     * One server det at {@code det} (the MPL det-site relays): resolve the spec row, run the
     * vanilla falloff against the own ship's circle point, and (apply mode) land one capped
     * {@code engine.applyDamage} + ledger push + witness emit. Fail-soft. {@code ownSourced}
     * = the det's source is the own ship ⇒ engine-exempt, counted
     * and skipped BEFORE the spec / own-hull lookups (the FX at the call site still present).
     */
    void onDet(Vector2f det, String specId, boolean ownSourced) {
        if (!METER || det == null) {
            return;
        }
        if (ownSourced) {
            skippedOwnSource++;
            return;
        }
        try {
            DetSpec spec = specFor(specId);
            if (spec == null) {
                skippedSpec++;
                // 1290 (81) S7: unknown det spec -> the own-damage application drops entirely.
                FallbackAnnounce.announceOnce("s7-det-spec-unknown#" + specId,
                        "no det spec row -> own det damage skipped");
                return;
            }
            ShipAPI own = plugin.engine != null ? plugin.engine.getPlayerShip() : null;
            Vector2f loc = own != null ? own.getLocation() : null;
            if (own == null || !own.isAlive() || loc == null) {
                skippedDead++;
                return;
            }
            Vector2f hit;
            float d; // det-center -> hit-point distance, the falloff axis
            // The hit point is the nearest point on
            // the own hull's EXACT BOUNDS (vanilla's DamagingExplosion collides the blast circle
            // against the bounds polygon, falloff measured det->that point).
            // The pre-fix collision-CIRCLE point sat far
            // outside a capital's sprite (Ziggurat: the number painted in empty space, falloff
            // too generous, and 120-radius dets that vanilla MISSES still applied). Circle math
            // stays only as the fail-soft fallback when the bounds are unreadable/empty.
            // Vanilla's contact order:
            // (i) a RAISED shield first — the radius-weighted point on the det→shield
            // centre line, accepted only inside the shield arc; (ii) else the nearest point on
            // the bounds perimeter when it lies within the blast radius; (iii) else, a det
            // whose centre sits INSIDE the polygon with no edge in reach hits at itself (d = 0);
            // (iv) else a miss. The circle is the fail-soft fallback only.
            String path;
            Vector2f shieldHit = shieldContactOrNull(own, det, spec.radius);
            float[] b = shieldHit == null ? nearestPointOnBounds(own, det) : null;
            if (shieldHit != null) {
                hit = shieldHit;
                d = dist(det, hit);
                path = "shield";
            } else if (b != null && b[2] <= spec.radius) {
                hit = new Vector2f(b[0], b[1]);
                d = b[2];
                path = "bounds";
            } else if (b != null && b[3] > 0f) {
                hit = new Vector2f(det);
                d = 0f;
                path = "inside";
            } else if (b != null) {
                hit = new Vector2f(b[0], b[1]);
                d = b[2]; // > radius: the range skip below = vanilla's miss
                path = "miss";
            } else {
                float dx = det.x - loc.x;
                float dy = det.y - loc.y;
                float centerDist = (float) Math.sqrt(dx * dx + dy * dy);
                float radius = own.getCollisionRadius();
                if (centerDist <= radius || centerDist <= 0f) {
                    hit = new Vector2f(det); // det inside the circle: the hit point IS the det
                    d = 0f;
                } else {
                    float s = radius / centerDist;
                    hit = new Vector2f(loc.x + dx * s, loc.y + dy * s);
                    d = centerDist - radius;
                }
                path = "circle";
            }
            if (d > spec.radius) {
                skippedRange++;
                return;
            }
            float f = d <= spec.core ? 1f : 1f - (d - spec.core) / (spec.radius - spec.core);
            float dmg = spec.dmgMin + (spec.dmgMax - spec.dmgMin) * f;
            dets++;
            if (dmg > maxDetDamage) {
                maxDetDamage = dmg;
            }
            float appliedHull = 0f;
            if (APPLY) {
                attachTo(own);
                applying = true;
                float preHull = own.getHitpoints();
                try {
                    // The recon-pinned public seam: routes through the target's own
                    // shield/armor at the hit point + the cap chain; never bypassShields.
                    plugin.engine.applyDamage(own, hit, dmg, spec.type, spec.emp,
                            false, false, null);
                } finally {
                    applying = false;
                }
                // Ledger the ACTUAL post-cap hull delta, never the
                // engine's pre-cap notional — result.getDamageToHull() reported 2000 on a body
                // OwnDamageCap held at the lethal edge, and the un-consumable excess pinned the
                // own-hull mirror at the 1hp floor. pre−post carries the capped truth.
                appliedHull = Math.max(0f, preHull - own.getHitpoints());
                if (appliedHull > 0f) {
                    entries.addLast(new Entry(localFrame, appliedHull));
                    outstanding += appliedHull;
                    hullAppliedTotal += appliedHull;
                    pushed++;
                }
                applies++;
            }
            if (detLines < DET_LOG_MAX) {
                detLines++;
                PilotWatchLog.info("predict own-det #" + detLines + ": spec=" + specId
                        + " det=" + (int) det.x + "," + (int) det.y
                        + " hit=" + (int) hit.x + "," + (int) hit.y + " path=" + path
                        + " d=" + fmt1(d) + " dmg=" + fmt1(dmg)
                        + " appliedHull=" + fmt1(appliedHull)
                        + " outstanding=" + fmt1(outstanding)
                        + (APPLY ? "" : " (meter)")
                        + (detLines == DET_LOG_MAX ? " [line cap reached; counters continue]" : ""));
            }
        } catch (Throwable ignore) {
            // presentation-side lane; never break the det FX that already happened
        }
    }

    /**
     * The ledger-aware own-mirror target (the plugin's mirror site, once per render frame while the own
     * block runs): tick the local clock, consume the wire drop on fresh frames, expire stale
     * entries, and return {@code wire − outstanding} — {@code mirrorHull}'s [1, max] clamp is the
     * floor. Off/meter return {@code wire} unchanged (pre-build byte-for-byte).
     */
    float ownMirrorTarget(ShipAPI own, float wireHp, boolean freshSnapshot) {
        if (!APPLY) {
            return wireHp;
        }
        try {
            localFrame++;
            attachTo(own);
            if (freshSnapshot) {
                onWire(wireHp);
            }
            expire();
            // Bound the ledger to the wire's own headroom.
            // A local det asserting more hull loss than the authoritative ship can
            // physically be losing (the born-growth armor over-apply, fault (b)) can never be
            // consumed by a wire drop, so it pinned the mirror at the 1hp floor until expiry.
            // The excess above (prevWire−1) is a PHANTOM the server never dealt — dropping it is
            // not concealment; every det the wire WILL acknowledge still darkens the hull.
            if (!Float.isNaN(prevWire) && outstanding > 0f) {
                float budget = Math.max(0f, prevWire - 1f);
                if (outstanding > budget) {
                    budgetClampTotal += outstanding - budget;
                    budgetClamps++;
                    outstanding = budget;
                }
            }
            return wireHp - outstanding;
        } catch (Throwable ignore) {
            return wireHp; // fail-soft: the mirror must never lose the wire value
        }
    }

    /** Fresh-snapshot retire — the foe-ledger FIFO amount-consume verbatim (over-retire errs
     *  toward truth; heal/flat retires nothing, entries expire instead). */
    private void onWire(float wireHp) {
        if (Float.isNaN(prevWire)) {
            prevWire = wireHp;
            return;
        }
        float drop = prevWire - wireHp;
        prevWire = wireHp;
        if (drop > 0f) {
            wireDrops++;
            wireDropTotal += drop;
            if (drop > wireDropMax) {
                wireDropMax = drop;
            }
        }
        while (drop > 0f && !entries.isEmpty()) {
            Entry head = entries.peekFirst();
            float consumed = Math.min(head.hull, drop);
            head.hull -= consumed;
            outstanding -= consumed;
            drop -= consumed;
            if (head.hull <= LEDGER_EPS) {
                outstanding -= head.hull;
                entries.pollFirst();
                retired++;
            }
        }
        if (outstanding < 0f) {
            outstanding = 0f;
        }
    }

    /** Expire entries older than the window (the ≤2 s darker-then-heal-up bound). */
    private void expire() {
        while (!entries.isEmpty()
                && localFrame - entries.peekFirst().frame >= REPLAY_EXPIRY_FRAMES) {
            outstanding -= entries.pollFirst().hull;
            expired++;
        }
        if (outstanding < 0f) {
            outstanding = 0f;
        }
    }

    /** Track the live own ship; an identity change (respawn remount) starts a fresh LIFE:
     *  clear the ledger + reseed the wire baseline. The corpse keeps its stale listener pair —
     *  harmless, the {@code applying} flag is false outside our own call (the OwnDamageCap
     *  corpse-listener precedent). */
    private void attachTo(ShipAPI own) {
        if (own == null || own == attachedShip) {
            return;
        }
        if (attachedShip != null) {
            entries.clear();
            outstanding = 0f;
            prevWire = Float.NaN;
            lifeClears++;
        }
        own.addListener(this);
        attachedShip = own;
    }

    /** Witness arm — inside our own applyDamage only ({@code applying}): the engine-true
     *  shieldHit + pre-state stash. Never modifies the damage (returns null). */
    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
            Vector2f point, boolean shieldHit) {
        if (applying) {
            plugin.dmgWitness.armApply(target, param, point, shieldHit);
        }
        return null;
    }

    /** Witness emit — inside our own applyDamage only (the ledger reads the pre/post hull
     *  delta at the call site; the result's pre-cap split stays witness-only). */
    @Override
    public void reportDamageApplied(Object source, CombatEntityAPI target,
            ApplyDamageResultAPI result) {
        if (!applying || result == null) {
            return;
        }
        try {
            plugin.dmgWitness.emitApply("own", source, target, result, Float.NaN);
        } catch (Throwable ignore) {
            // witness lane; never break the damage path
        }
    }

    /**
     * D-OWNARMOR — the OWN-ship ARMOR reconcile collaborator: the
     * {@link ArmorReconcileLane#own()} instance (knob {@code cc.predict.ownArmorReconcile}), the
     * foe lane's twin for the own ship, owned here via the FHRL 4-relay trick (field init /
     * maintain / round reset / meter flush) so PRCP grows by the one maintain relay + the posture
     * print token only. Placement rationale: this lane already owns the own-HULL mirror target,
     * and armor is hull's sibling quantity (the FHRL precedent). Independent of this lane's own
     * {@code ownDetDamage} knob — every relay below runs BEFORE any det-knob early-return.
     */
    private final ArmorReconcileLane ownArmorLane = ArmorReconcileLane.own();
    /** The OWN per-cell armor lane (the twin ownership; knob
     *  {@code cc.predict.armorCells}). Bound to the fold lane for the cellLive skip. */
    private final ArmorCellLane ownCellLane = ArmorCellLane.own(ArmorCellLane.MODE);

    /**
     * Fold the authoritative own-ship armor fraction onto the own grid — the PRCP
     * own-block relay, right after the own-hull mirror write (once per render frame while the own
     * block runs; {@code own}/{@code pose} null-checked there; the fold itself runs only on a
     * FRESH armor-bearing pose and is whole-body fail-soft inside the engine).
     */
    void maintainOwnArmor(ShipAPI own, ServerEntityPose pose, boolean freshSnapshot, long simTick) {
        if (own == null || pose == null) {
            return;
        }
        ownArmorLane.maintain(own, pose, freshSnapshot);
        // v11 per-cell relay (own fail-soft inside; simNow = FHRL's shared sim clock).
        // + the frame's snapshot tick, the foe relay's stamp clock. Without
        // it no own stamp was ever live, so a server-dealt hit whose LOCAL explosion lands after the wire's cell
        // write (a wire-END detonation: the M10 door, a mine) bit the own armor a second time and the duplicate
        // sat as a 2-s claim below the server value (pairs_drop_first=0 all flight).
        ownCellLane.maintain(own, pose, plugin.hullLane.localSimSec(), simTick);
    }

    /** Round wrap: ledger + attach state die with the round (counters are cumulative). The own
     *  armor collaborator's round line rides first (its counters are cumulative too). */
    void resetRound() {
        ownArmorLane.resetForMatchReset(); // Before the det-state reset, independent of MODE
        ownCellLane.resetForMatchReset();
        entries.clear();
        outstanding = 0f;
        prevWire = Float.NaN;
        attachedShip = null;
    }

    /** Counter line at the meter flush sites; silent until the lane sees a det or a wire drop.
     *  The own-armor collaborator flushes FIRST on its own knob (never behind a det gate). */
    void logMeter(String where) {
        ownArmorLane.logMeter(where);
        ownCellLane.logMeter(where);
        if (!METER || dets + skippedSpec + skippedRange + skippedDead + skippedOwnSource + wireDrops == 0) {
            return;
        }
        PilotWatchLog.info("predict own-det meter [" + where + "]: mode=" + MODE
                + " predict_own_det_dets=" + dets
                + " predict_own_det_applies=" + applies
                + " predict_own_det_hull_total=" + fmt1(hullAppliedTotal)
                + " predict_own_det_max=" + fmt1(maxDetDamage)
                + " predict_own_det_outstanding=" + fmt1(outstanding)
                + " predict_own_det_pushed=" + pushed
                + " predict_own_det_retired=" + retired
                + " predict_own_det_expired=" + expired
                + " predict_own_det_budget_clamps=" + budgetClamps
                + " predict_own_det_budget_clamp_total=" + fmt1(budgetClampTotal)
                + " predict_own_det_life_clears=" + lifeClears
                + " predict_own_det_skip_spec=" + skippedSpec
                + " predict_own_det_skip_range=" + skippedRange
                + " predict_own_det_skip_dead=" + skippedDead
                + " predict_own_det_skip_own_source=" + skippedOwnSource
                + " predict_own_wire_drop_n=" + wireDrops
                + " predict_own_wire_drop_total=" + fmt1(wireDropTotal)
                + " predict_own_wire_drop_max=" + fmt1(wireDropMax));
    }

    /**
     * Vanilla's shield-first contact:
     * with the shield RAISED and the
     * det within {@code shieldR + blastR} of the shield centre, the contact point is the
     * radius-weighted point on the det→shield-centre line, {@code det + (shield − det) ·
     * blastR / (shieldR + blastR)}, accepted only when the shield arc covers it; otherwise
     * the bounds path runs. Null = no shield contact (off, out of reach, outside the arc, or an
     * active arc under 1 degree: {@code isWithinArc} floors the arc at 1 degree
     * while the engine's own shield-branch test does not,
     * so an unfolding shield fails CLOSED here and no caller applies
     * at a point the engine would send to the hull; callers = the own det {@link #onDet(Vector2f, String, boolean)},
     * {@code MineBombEndFxLane.impactPoint}, {@code SourcedMissileEndLane} cause-3 guard — each falls
     * to its bounds point or its counted skip; row = MineNativeFuseLawSmoke F-1424). ACCEPT-WHY:
     * the SERVER's native collision used the FLOORED test on its path into {@code applyDamage},
     * so inside a sub-1° cone on the first unfold frame the
     * server took the SHIELD while the own det / conversion-end callers here fall to the bounds point —
     * a near-measure-zero window, accepted; the cause-3 guard's counted skip is the faithful branch.
     */
    static Vector2f shieldContactOrNull(ShipAPI ship, Vector2f det, float blastRadius) {
        try {
            com.fs.starfarer.api.combat.ShieldAPI shield = ship.getShield();
            if (shield == null || !shield.isOn()) {
                return null;
            }
            Vector2f sl = shield.getLocation();
            if (sl == null) {
                return null;
            }
            float sr = shield.getRadius();
            float dx = sl.x - det.x;
            float dy = sl.y - det.y;
            float cd = (float) Math.sqrt(dx * dx + dy * dy);
            if (cd <= 0f || cd > sr + blastRadius) {
                return null;
            }
            if (!SourcedMissileEndLane.ownRingArcUsable(shield.getActiveArc())) {
                return null; // under 1 degree isWithinArc over-admits (see the javadoc)
            }
            float k = blastRadius / (sr + blastRadius);
            Vector2f p = new Vector2f(det.x + dx * k, det.y + dy * k);
            return shield.isWithinArc(p) ? p : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The nearest point on the ship's EXACT bounds to {@code det}, as
     * {@code {hitX, hitY, dist, inside}}: the engine keeps the bounds polygon in world space
     * ({@code BoundsAPI.update(loc, facing)} — called here first, the LazyLib idiom, so a
     * stale-frame polygon never serves), and vanilla's blast-vs-hull contact is that polygon,
     * not the collision circle (the nearest perimeter point wins whenever it is within
     * the blast radius — even for a det INSIDE the polygon; only an inside det with no edge in
     * reach hits at itself). Returns {@code null} when the bounds are unreadable or empty
     * (fighters/pods carry none) — the caller falls back to the circle.
     */
    static float[] nearestPointOnBounds(ShipAPI ship, Vector2f det) {
        try {
            BoundsAPI bounds = ship.getExactBounds();
            Vector2f loc = ship.getLocation();
            if (bounds == null || loc == null) {
                return null;
            }
            bounds.update(loc, ship.getFacing());
            java.util.List<BoundsAPI.SegmentAPI> segs = bounds.getSegments();
            if (segs == null || segs.isEmpty()) {
                return null;
            }
            float[] flat = new float[segs.size() * 4];
            int n = 0;
            for (BoundsAPI.SegmentAPI s : segs) {
                Vector2f p1 = s.getP1();
                Vector2f p2 = s.getP2();
                if (p1 == null || p2 == null) {
                    continue;
                }
                flat[n++] = p1.x;
                flat[n++] = p1.y;
                flat[n++] = p2.x;
                flat[n++] = p2.y;
            }
            if (n < 8) {
                return null; // fewer than two real segments = no polygon
            }
            if (n < flat.length) {
                float[] trim = new float[n];
                System.arraycopy(flat, 0, trim, 0, n);
                flat = trim;
            }
            return nearestPointOnPolygon(det.x, det.y, flat);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Pure geometry (the {@code OwnDetBoundsSmoke} seam): {@code segs} = world-space segments
     * as {@code x1,y1,x2,y2} quads. Returns {@code {hitX, hitY, dist, inside}} — the closest
     * point on any segment (endpoint-clamped) and its distance from {@code (px,py)}, plus
     * {@code inside} = 1 when the point lies inside the closed polygon the segments trace
     * (even-odd ray cast over the same quads), else 0. The caller applies vanilla's order
     * (perimeter within radius first, then inside ⇒ self at 0).
     */
    static float[] nearestPointOnPolygon(float px, float py, float[] segs) {
        float bestX = px;
        float bestY = py;
        float bestD2 = Float.MAX_VALUE;
        boolean inside = false;
        for (int i = 0; i + 3 < segs.length; i += 4) {
            float x1 = segs[i];
            float y1 = segs[i + 1];
            float x2 = segs[i + 2];
            float y2 = segs[i + 3];
            // even-odd crossing test (half-open in y so a shared vertex counts once)
            if ((y1 > py) != (y2 > py)) {
                float xAt = x1 + (py - y1) * (x2 - x1) / (y2 - y1);
                if (px < xAt) {
                    inside = !inside;
                }
            }
            float ex = x2 - x1;
            float ey = y2 - y1;
            float len2 = ex * ex + ey * ey;
            float t = len2 > 0f ? ((px - x1) * ex + (py - y1) * ey) / len2 : 0f;
            if (t < 0f) {
                t = 0f;
            } else if (t > 1f) {
                t = 1f;
            }
            float cx = x1 + ex * t;
            float cy = y1 + ey * t;
            float ddx = px - cx;
            float ddy = py - cy;
            float d2 = ddx * ddx + ddy * ddy;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestX = cx;
                bestY = cy;
            }
        }
        return new float[] { bestX, bestY, (float) Math.sqrt(bestD2), inside ? 1f : 0f };
    }

    /** One-decimal ROOT-locale format (grep-stable) — the lane-private fmt1 twin. */
    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
