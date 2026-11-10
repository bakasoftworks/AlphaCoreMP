package cc.starsector.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * The GPEE v17 {@code BLAST_HIT} (kind 11) presenter: one server-named victim of one fuse bomb's burst,
 * shown as the engine's own hit on the victim's local body.
 *
 *
 * <p><b>Why.</b> A fuse bomb's burst (vanilla: the Wasp's fragbomb) is a server-side
 * {@code DamagingExplosion}; the client draws that burst inert (no local damage), so a burst hit had no
 * number, flash or hit sound on the victim. The server names every victim the burst really hurt (the
 * bomb, the victim, the damage type, shield or hull, the burst centre, the pre-armor amount). This class
 * replays that hit through {@link PredictReconcileCombatPlugin#applyWireAckedHit} on the victim's
 * replica: the engine's own damage path paints the number, flash and sound, and the wire-acked idiom
 * restores hull, armor and flux right after, so the body never drifts from the server's. Never
 * hand-painted particles, never a hand-made floating number.
 *
 * <p><b>The contact point is vanilla's</b>:
 * a SHIELD record lands on the radius-weighted shield contact for the burst's OWN radius
 * ({@link OwnDetLane#shieldContactOrNull}; the fuse fires off the target's edge, so a small fixed slack
 * would miss most shield hits) with the shield branch allowed; a HULL record lands on the nearest point
 * of the exact bounds (else the collision-circle surface point) with {@code bypassShields} TRUE, because
 * the engine picks the shield branch from the bearing alone and a hull record must never show as a
 * shield hit. The source handed to the engine is the BOMB's own source ship, read off its local twin
 * (live, or the {@link EndedBombLatch} when the bomb's end retired the twin first): the engine's floaty
 * gate and its damage-vs-size stat both read it. The amount applied is the wire's pre-armor amount;
 * the wire's {@code applied} figure is a meter only.
 *
 * <p><b>Exits</b>, in order, each a named counter on the edge-event meter line: no victim body, dead or
 * hulk, a fighter victim, an unknown type or family code (the record constructor accepts them on
 * purpose, since a throw there reads as a corrupt payload), an untreated body (the own ship is no replica
 * and skips this test), no twin (live or latched), a twin that is not a fuse bomb, a twin whose source is
 * the own ship though the own seat never fired it, the local engine already presented this bomb (a second
 * number would double it), no source ship, no burst radius for a shield record, then for the OWN victim
 * only: a bomb sourced by the own ship itself, an own body that carries no damage cap, a bomb already
 * applied to the own ship; then a centre out of the burst's reach of the victim, a shield record the local
 * shield gives no contact for, the per-pass apply cap, and for the own victim a shield record whose flux
 * the own ship cannot hold. Applied, own-applied and every exit sum to received
 * ({@code blast_hit_own_skipped} keeps its slot and stays 0). EMP is applied as 0 (an EMP apply's weapon
 * and engine disables are not restored [hypothesis]); a dropped EMP is counted.
 *
 * <p><b>The own door.</b> A foe bomb bursting on the player's own ship is presented through
 * {@link ReplicaBodyLane#applyWireAckedOwnHit}: the engine's own damage path paints the number, flash and
 * sound on the own body, and everything else it wrote (hull, armor, flux, weapon and engine damage) is
 * put back before the call returns; the wire owns them. A record past the bomb gate whose twin is LIVE,
 * a fuse bomb and not own-sourced also turns that twin intangible ({@link #act}): the server's bomb has
 * burst, so its local body may no longer touch anything.
 *
 * <p><b>Refusals of an unlawful record.</b> Every field of the record (bomb, victim, centre, tick, shield
 * or hull) is the server's word, and a hostile host controls the server. So the twin must be a fuse bomb
 * (else a record could hang a hit on any live missile's twin, the seat's own handed-off missile included,
 * and make the own ship the engine's damage source); a fuse twin the WIRE minted with the own ship as
 * its source is refused too ({@link #wireOwnSource}: only a bomb the own seat itself fired may name the
 * own ship to the engine); the victim must not be a fighter (the server skips fighter victims at
 * capture, so a lawful server never names one); the centre must lie within the burst's reach of the
 * victim ({@link #withinReach}); and the apply cap is sized from the client's own drain pass and the
 * snapshot tick it drains at, never from a record's tick.
 *
 * <p><b>Bounds</b> (a DOWN-wire input): every field is range-checked by the record constructor; this class
 * keeps no structure that grows with the wire (the latch is a fixed ring), logs numbers and fixed tokens
 * only, and applies at most {@link #PER_TICK_CAP} hits per snapshot tick a drain pass covers, never over
 * {@link #PASS_TICKS_MAX} ticks' worth in one pass. Combat thread only (the edge-event drain inside the
 * plugin's advance); fail-soft, every throw counted and logged once.
 */
final class BlastHitLane {

    /** Exit codes of {@link #act}; each is also the index of its counter in {@link #TOKENS}. */
    static final int APPLIED = 0;
    static final int NO_VICTIM = 1;
    static final int DEAD = 2;
    static final int OWN_SKIPPED = 3;
    static final int FIGHTER = 4;
    static final int UNKNOWN_CODE = 5;
    static final int UNTREATED = 6;
    static final int NO_TWIN = 7;
    static final int NOT_BOMB = 8;
    static final int OWN_SOURCE = 9;
    static final int LOCAL_FIRST = 10;
    static final int NO_SOURCE = 11;
    static final int NO_SPEC = 12;
    static final int OUT_OF_REACH = 13;
    static final int SHIELD_MISS = 14;
    static final int OVER_CAP = 15;
    static final int FAILED = 16;
    /** The own-door exits, appended so no earlier index moves: the own victim's body carries no damage cap
     *  ({@link #ownUncapped}), a second own record for a bomb already applied to the own ship, a shield
     *  record whose flux the own ship cannot hold, and the own apply itself (a success, never a skip).
     * */
    static final int OWN_UNCAPPED = 17;
    static final int OWN_REPEAT = 18;
    static final int OWN_FLUX_REFUSED = 19;
    static final int OWN_APPLIED = 20;

    /** The meter token of each exit code, same index. */
    static final String[] TOKENS = {
        "blast_hit_applied", "blast_hit_no_victim", "blast_hit_dead", "blast_hit_own_skipped",
        "blast_hit_fighter", "blast_hit_unknown_code", "blast_hit_untreated", "blast_hit_no_twin",
        "blast_hit_not_bomb", "blast_hit_own_source", "blast_hit_local_first", "blast_hit_no_source",
        "blast_hit_no_spec", "blast_hit_out_of_reach", "blast_hit_shield_miss", "blast_hit_over_cap",
        "blast_hit_failures", "blast_hit_own_uncapped", "blast_hit_own_repeat", "blast_hit_own_flux_refused",
        "blast_hit_own_applied"};

    /**
     * The most bombs remembered as already applied to the own ship (a fixed ring, the oldest out, emptied at
     * the round reset). A lawful server sends one record per bomb and victim, so a second own record for a
     * remembered bomb is a replay and is refused {@code blast_hit_own_repeat} before it takes a cap slot;
     * missile wire ids are stable for a missile's whole life and never reused, so a lawful bomb is never
     * refused here. A replay can re-apply only after 64 other own applies pushed its bomb out.
     *
     */
    static final int OWN_APPLIED_MAX = 64;

    /**
     * Hits applied per snapshot tick a drain pass covers; the rest of that pass is counted
     * {@code blast_hit_over_cap}. It is the server's own emission bound (32 records a server tick),
     *so a lawful pass never reaches it: one render pass releases
     * the held records of every tick the snapshot advanced by (about two at 60 fps, more after a hitch),
     * and a cap of one tick's worth per PASS dropped lawful hits. The tick count comes from
     * {@link #beginDrainPass}, never from a record: record ticks that alternate (A, B, A ...) would re-arm
     * a record-keyed cap on every record, and one drain would then apply as many engine hits (each with
     * its sound and number) as the intake holds.
     */
    static final int PER_TICK_CAP = 32;

    /** The most snapshot ticks one drain pass is credited with (a hitch or a tick jump past it is capped
     *  here: 8 ticks' worth, 256 applies, is the most one pass ever runs). */
    static final int PASS_TICKS_MAX = 8;

    /**
     * The reach gate's slack, in su. A lawful burst touches its victim at the server's burst tick, so its
     * centre lies within the burst radius plus the victim's collision or shield radius of the victim's
     * server position; the local body trails that position by the render delay (two measured snapshot
     * intervals), and 300 su covers about 0.37 s of that trail at the fastest
     * vanilla burn (about 800 su/s: Plasma Burn adds a flat 600).
     * An over-refusal costs one damage number and is counted
     * ({@code blast_hit_out_of_reach}). Two lawful records can still be refused: a victim whose Phase
     * Teleporter jump (range 1500) lands between the burst tick and the apply, and a late record (one
     * behind the snapshot is presented with no lateness bound) against a fast victim once the lateness
     * passes that 0.37 s.
     *
     */
    static final float REACH_SLACK_SU = 300f;

    /** Applied-hit log rows per round (counters continue past it). */
    static final int LOG_MAX = 20;

    /** {@link Bomb#from}: the facts came from the live twin / from the ended-bomb latch. */
    static final int FROM_LIVE = 1;
    static final int FROM_LATCH = 2;

    /** The bomb facts a hit presents with (one reused holder: no per-record allocation). */
    static final class Bomb {
        Object source;
        float burstRadius;
        /** True when the twin is a fuse-family bomb; a twin of any other missile is refused. */
        boolean fuseFamily;
        boolean localFirst;
        /** True when the source is the own ship though the own seat never fired this bomb
         *  ({@link #wireOwnSource}); such a twin is refused. */
        boolean wireOwnSource;
        /** True when the own seat fired this bomb or its source is the own ship: its live twin is never
         *  turned intangible by a burst record. */
        boolean ownSourced;
        int from;

        void clear() {
            source = null;
            burstRadius = -1f;
            fuseFamily = false;
            localFirst = false;
            wireOwnSource = false;
            ownSourced = false;
            from = 0;
        }
    }

    /** Everything {@link #act} reads from or does to the game; the plugin implements it live and the
     *  selftest fakes it, so the whole exit ladder runs game-free. */
    interface Env {
        /** The local body of wire ship {@code wireId}, or null. */
        ShipAPI victim(long wireId);

        /** True when {@code wireId} / {@code victim} is the own seat's ship. */
        boolean ownShip(long wireId, ShipAPI victim);

        /** True when {@code victim} is a fully treated replica (a hit applied to anything else would drift). */
        boolean treated(ShipAPI victim);

        /** Fills {@code into} from the LIVE twin of missile {@code bombWireId} (through {@link #fillBomb});
         *  false = no live twin. A twin of any missile is found; {@code into.fuseFamily} says whether it is
         *  a fuse bomb. */
        boolean liveBomb(long bombWireId, Bomb into);

        /** The wire-acked apply; returns the hull the engine dealt, or a negative value when nothing applied. */
        float apply(ShipAPI victim, Vector2f at, float damage, DamageType type, float emp, Object source,
                boolean bypassShields, long tick);

        /** True when the own victim's body carries no damage cap, read through {@link #ownUncapped}. The cap
         *  is what keeps an own apply from deciding the own ship's death, so an uncapped body gets none.
         * */
        boolean ownUncapped(ShipAPI victim);

        /** The own ship's local body, or null. */
        Object ownBody();

        /** Turns the LIVE twin of missile {@code bombWireId} intangible (collision class NONE); true only when
         *  its class changed (a twin already NONE, or none at all, reads false). */
        boolean markBurst(long bombWireId);

        /** The own ship's wire-acked apply ({@link ReplicaBodyLane#applyWireAckedOwnHit}); returns the hull the
         *  engine dealt (at least 0), or NaN when nothing was applied (the own ship cannot hold the shield
         *  flux). */
        float applyOwn(ShipAPI own, Vector2f at, float damage, DamageType type, Object source,
                boolean bypassShields, long tick);
    }

    /** Distinct failure messages already logged (one line per distinct message), capped at 64. */
    private static final Set<String> failuresLogged = new HashSet<String>();

    private final PredictReconcileCombatPlugin plugin; // null in the selftest (it drives act with a fake Env)
    private final Env live;
    final EndedBombLatch latch = new EndedBombLatch();
    private final Bomb bomb = new Bomb();
    private final int[] counts = new int[TOKENS.length];
    private final boolean[] skipLoggedThisRound = new boolean[TOKENS.length];
    private int received;
    private int empDropped;
    private int latched;
    private int latchFailures;
    /** The snapshot tick of the last drain pass; {@code Long.MIN_VALUE} = none yet this round. */
    private long passTick = Long.MIN_VALUE;
    /** The current pass's apply cap. One tick's worth until a pass opens, so a lane whose pass hook never
     *  runs stops at 32 applies in all (fail-closed, and its over-cap counter shows it). */
    private int passCap = PER_TICK_CAP;
    private int capCount;
    private int logLines;
    /** The own-applied ring ({@link #OWN_APPLIED_MAX}): bomb wire ids, the next slot to write, the fill. */
    private final long[] ownAppliedIds = new long[OWN_APPLIED_MAX];
    private int ownAppliedNext;
    private int ownAppliedSize;
    /** Live twins a burst record turned intangible (one per body), and the marks that threw. */
    private int liveMarked;
    private int liveMarkFailures;

    BlastHitLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        this.live = new LiveEnv();
    }

    /** The edge-event drain's kind-11 intake (route-first: the window folds would mis-fold it). */
    void present(ClientGameplayEdgeEvent event) {
        act(event, live);
    }

    /** One record through the exit ladder; returns its exit code (exactly one counter moves). */
    int act(ClientGameplayEdgeEvent event, Env env) {
        received++;
        int exit;
        try {
            exit = actOn(event, env);
        } catch (Throwable t) {
            exit = FAILED;
            logOnce("blast_hit_failed " + t.getClass().getSimpleName());
        }
        counts[exit]++;
        if (exit != APPLIED && exit != OWN_APPLIED && !skipLoggedThisRound[exit]) {
            skipLoggedThisRound[exit] = true;
            PilotWatchLog.info("predict v17 blast-hit first-skip-this-round reason=" + TOKENS[exit]
                    + " tick=" + event.simTick() + " bomb=" + event.entityId()
                    + " victim=" + event.targetWireId() + " code=" + event.blastCode()
                    + " (later skips of this reason are counted only)");
        }
        return exit;
    }

    private int actOn(ClientGameplayEdgeEvent event, Env env) {
        long victimId = event.targetWireId();
        ShipAPI victim = env.victim(victimId);
        boolean found = victim != null;
        boolean own = found && env.ownShip(victimId, victim);
        int exit = victimGate(found, found && (!victim.isAlive() || victim.isHulk()), own,
                found && victim.isFighter(), event.blastTypeOrdinal(), event.blastFamily(), DamageType.values().length,
                found && !own && env.treated(victim));
        if (exit != APPLIED) {
            return exit;
        }
        Bomb b = bomb;
        b.clear();
        boolean bombFound = env.liveBomb(event.entityId(), b);
        if (bombFound) {
            b.from = FROM_LIVE;
        } else {
            int slot = latch.find(event.entityId(), event.simTick());
            if (slot >= 0) {
                b.source = latch.source(slot);
                b.burstRadius = latch.burstRadius(slot);
                b.localFirst = latch.endedOnLocalContact(slot);
                b.wireOwnSource = latch.wireOwnSource(slot);
                b.fuseFamily = true; // the latch admits fuse-family twins only (latchFuseEnd)
                b.from = FROM_LATCH;
                bombFound = true;
            }
        }
        boolean shieldHit = event.blastShieldHit();
        exit = bombGate(bombFound, b.fuseFamily, b.wireOwnSource, b.localFirst, b.source != null, shieldHit,
                b.burstRadius);
        // Past the not-bomb rung, a LIVE fuse twin that is not own-sourced
        // turns intangible WHATEVER exit this record then takes: the server's bomb has burst (a burst consumes
        // the missile), so its local body may no longer touch anything. A latched bomb has no body to mark.
        if (marksLive(bombFound, b.fuseFamily, b.from, b.ownSourced)) {
            markLive(env, event.entityId());
        }
        if (exit != APPLIED) {
            return exit;
        }
        if (own) {
            exit = ownGate(b.source, env.ownBody(), env.ownUncapped(victim), ownApplied(event.entityId()));
            if (exit != APPLIED) {
                return exit; // before the reach gate and the cap: an own refusal takes no cap slot
            }
        }
        // The reach gate runs for BOTH record kinds, before any contact
        // point is computed, against the local body's location, collision radius and shield radius.
        ShieldAPI shield = victim.getShield();
        Vector2f loc = victim.getLocation();
        if (!withinReach(event.blastX(), event.blastY(), loc.x, loc.y, b.burstRadius, victim.getCollisionRadius(),
                shield == null ? 0f : shield.getRadius())) {
            return OUT_OF_REACH;
        }
        Vector2f at = contactPoint(victim, new Vector2f(event.blastX(), event.blastY()), shieldHit, b.burstRadius);
        if (at == null) {
            return SHIELD_MISS;
        }
        if (!admitPass()) {
            return OVER_CAP;
        }
        if (event.blastEmp() > 0f) {
            empDropped++;
        }
        DamageType type = DamageType.values()[event.blastTypeOrdinal()];
        float dealt;
        if (own) {
            // The own door. NaN = the own ship cannot hold the shield
            // flux, nothing applied and the bomb not remembered (a later lawful record may still apply).
            dealt = env.applyOwn(victim, at, event.blastAmount(), type, b.source, bypassShieldsFor(shieldHit),
                    event.simTick());
            if (Float.isNaN(dealt)) {
                return OWN_FLUX_REFUSED;
            }
            rememberOwnApplied(event.entityId());
        } else {
            dealt = env.apply(victim, at, event.blastAmount(), type, 0f, b.source, bypassShieldsFor(shieldHit),
                    event.simTick());
            if (dealt < 0f) {
                return UNTREATED; // the apply's own treated test refused it (the body changed since the gate)
            }
        }
        if (logLines < LOG_MAX) {
            logLines++;
            PilotWatchLog.info("predict v17 blast-hit: tick=" + event.simTick() + " bomb=" + event.entityId()
                    + " victim=" + victimId + " code=" + event.blastCode() + " amount=" + event.blastAmount()
                    + " local_hull=" + dealt + " wire_applied=" + event.blastApplied() + (own ? " own=1" : ""));
            if (logLines == LOG_MAX) {
                PilotWatchLog.info("predict v17 blast-hit rows LOG_CAP reached cap=" + LOG_MAX
                        + " (counters continue; later rows this round unlogged)");
            }
        }
        return own ? OWN_APPLIED : APPLIED;
    }

    /** The victim and code half of the exit ladder, in counter order. {@code typeCount} = the local
     *  {@link DamageType} count (an ordinal at or past it is a type this client does not know). A fighter
     *  victim is refused: a lawful server never names one. The OWN victim skips only the treated test (the
     *  own ship is no replica; its door is {@link #ownGate}). Pure. (
     *  the own door). */
    static int victimGate(boolean found, boolean deadOrHulk, boolean own, boolean fighter, int typeOrdinal,
            int family, int typeCount, boolean treated) {
        if (!found) {
            return NO_VICTIM;
        }
        if (deadOrHulk) {
            return DEAD;
        }
        if (fighter) {
            return FIGHTER;
        }
        if (typeOrdinal < 0 || typeOrdinal >= typeCount
                || family != ClientGameplayEdgeEvent.BLAST_FAMILY_FUSE_BURST) {
            return UNKNOWN_CODE;
        }
        if (!own && !treated) {
            return UNTREATED;
        }
        return APPLIED;
    }

    /** The own victim's half, after the bomb gate: a bomb whose source is the own ship itself (a self-hit
     *  vanilla never deals) is {@link #OWN_SOURCE}; an own body with no damage cap is {@link #OWN_UNCAPPED}; a
     *  bomb already applied to the own ship is {@link #OWN_REPEAT}. A null source or own body never matches.
     *  Pure. */
    static int ownGate(Object source, Object ownBody, boolean uncapped, boolean repeat) {
        if (source != null && source == ownBody) {
            return OWN_SOURCE;
        }
        if (uncapped) {
            return OWN_UNCAPPED;
        }
        if (repeat) {
            return OWN_REPEAT;
        }
        return APPLIED;
    }

    /** True when the own victim's body carries no damage cap: the cap is off ({@code capOwnDamage} false, a
     *  released seat or the kill blow), the victim is not the own ship's body, or the cap was never installed
     *  on THIS body ({@code ownCapBody}: set only after the listener was added, so a transit pod or a failed
     *  install reads uncapped). Pure. */
    static boolean ownUncapped(boolean capOwnDamage, Object victim, Object ownShip, Object ownCapBody) {
        return !capOwnDamage || victim != ownShip || ownCapBody != victim;
    }

    /** True when a record's twin is to be turned intangible: found, a fuse bomb, resolved from the LIVE twin
     *  (a latched bomb has no body left) and not own-sourced. Pure. */
    static boolean marksLive(boolean found, boolean fuseFamily, int from, boolean ownSourced) {
        return found && fuseFamily && from == FROM_LIVE && !ownSourced;
    }

    /** One mark through the env, counted once per body; a throw is counted and logged once, and never
     *  changes the record's exit. */
    private void markLive(Env env, long bombWireId) {
        try {
            if (env.markBurst(bombWireId)) {
                liveMarked++;
            }
        } catch (Throwable t) {
            liveMarkFailures++;
            logOnce("blast_hit_live_mark_failed " + t.getClass().getSimpleName());
        }
    }

    /** True when bomb {@code bombWireId} is in the own-applied ring ({@link #OWN_APPLIED_MAX}). */
    boolean ownApplied(long bombWireId) {
        for (int i = 0; i < ownAppliedSize; i++) {
            if (ownAppliedIds[i] == bombWireId) {
                return true;
            }
        }
        return false;
    }

    /** Remembers an own apply of bomb {@code bombWireId}; past the ring's size the oldest entry is
     *  overwritten. */
    void rememberOwnApplied(long bombWireId) {
        ownAppliedIds[ownAppliedNext] = bombWireId;
        ownAppliedNext = (ownAppliedNext + 1) % OWN_APPLIED_MAX;
        if (ownAppliedSize < OWN_APPLIED_MAX) {
            ownAppliedSize++;
        }
    }

    /** The bomb half: a twin (live or latched) must exist, must be a fuse bomb (a found twin of any other
     *  missile is its own exit, never no-twin), must not name the own ship as the source of a bomb the own
     *  seat never fired (refused before the local verdict, so that counter holds every such record), must
     *  not have been presented by the local engine already, must name a source ship, and a shield record
     *  needs the burst radius its contact is weighted by (a hull record's bounds point does not read it).
     *  Pure. */
    static int bombGate(boolean found, boolean fuseFamily, boolean wireOwnSource, boolean localFirst,
            boolean sourceKnown, boolean shieldHit, float burstRadius) {
        if (!found) {
            return NO_TWIN;
        }
        if (!fuseFamily) {
            return NOT_BOMB;
        }
        if (wireOwnSource) {
            return OWN_SOURCE;
        }
        if (localFirst) {
            return LOCAL_FIRST;
        }
        if (!sourceKnown) {
            return NO_SOURCE;
        }
        if (shieldHit && !(burstRadius > 0f && burstRadius < Float.POSITIVE_INFINITY)) {
            return NO_SPEC;
        }
        return APPLIED;
    }

    /** True when the local engine already presented this bomb's damage: its body hit something locally
     *  ({@code didDamage}), or it is an own-seat body still flying its own fuse AI (not yet handed to the
     *  wire puppet), whose local burst is real. Pure. */
    static boolean localFirst(boolean didDamage, boolean own, boolean handedOff) {
        return didDamage || (own && !handedOff);
    }

    /**
     * True when a twin names the own ship as its source although the own seat never fired it ({@code own}
     * false). The wire can mint such a twin: a record carrying the own ship's id with no weapon mount is
     * adopted with the own ship's local body as the mint source, so
     * a hostile host could mint one and then name it in a burst hit, making the own ship the source the
     * engine's damage path, its damage listeners and its number gate all read. A bomb the own seat really
     * fired carries {@code own} true and stays lawful. The cost: the rare lawful twin of this shape (an own
     * script-spawned bomb whose local newborn never linked to its wire record) shows no number, counted
     * {@code blast_hit_own_source}. A null source or a null own ship never matches. Pure.
     */
    static boolean wireOwnSource(Object source, Object ownShip, boolean own) {
        return source != null && source == ownShip && !own;
    }

    /**
     * Fills {@code into} from one twin's facts; the live lookup and the selftest both come through here,
     * so the rows drive the same fill the game does. A twin that is not a fuse bomb is marked found and
     * nothing else: its source is never carried toward the apply. {@code burstRadius} is the fuse spec's
     * ({@link #burstRadiusOf}), or -1 when unknown. {@code ownSourced} = the own seat fired it or its source
     * is the own ship. Pure.
     */
    static void fillBomb(Bomb into, boolean fuseBurst, Object source, Object ownShip, float burstRadius,
            boolean didDamage, boolean own, boolean handedOff) {
        into.fuseFamily = fuseBurst;
        if (!fuseBurst) {
            return;
        }
        into.source = source;
        into.burstRadius = burstRadius;
        into.localFirst = localFirst(didDamage, own, handedOff);
        into.wireOwnSource = wireOwnSource(source, ownShip, own);
        into.ownSourced = own || (source != null && source == ownShip);
    }

    /** Vanilla's contact point for one burst victim: shield record = the radius-weighted shield contact
     *  (null = the local shield gives none: skip, never re-route to the hull); hull record = the nearest
     *  exact-bounds point, else the collision-circle surface point toward the centre. */
    static Vector2f contactPoint(ShipAPI victim, Vector2f centre, boolean shieldHit, float burstRadius) {
        if (shieldHit) {
            return OwnDetLane.shieldContactOrNull(victim, centre, burstRadius);
        }
        float[] b = OwnDetLane.nearestPointOnBounds(victim, centre);
        if (b != null) {
            return new Vector2f(b[0], b[1]);
        }
        return MineBombEndFxLane.surfaceToward(victim, centre);
    }

    /** A hull record bypasses the shield (the engine would pick the shield from the bearing alone). Pure. */
    static boolean bypassShieldsFor(boolean shieldHit) {
        return !shieldHit;
    }

    /**
     * The reach gate: true when the burst centre ({@code cx}, {@code cy}) lies close enough to the victim's
     * local location ({@code vx}, {@code vy}) for a lawful burst to have touched it: within the burst radius
     * (read as 0 when unknown (-1) or not finite: a hull record does not demand it, and 0 only tightens the
     * bound) plus the larger of the collision and shield radius, plus {@link #REACH_SLACK_SU}. A NaN
     * location or body radius fails closed (the comparison reads false). Pure.
     *
     */
    static boolean withinReach(float cx, float cy, float vx, float vy, float burstRadius, float collisionRadius,
            float shieldRadius) {
        double burst = burstRadius > 0f && burstRadius < Float.POSITIVE_INFINITY ? burstRadius : 0d;
        double reach = burst + Math.max(collisionRadius, shieldRadius) + REACH_SLACK_SU;
        double dx = (double) cx - vx;
        double dy = (double) cy - vy;
        return dx * dx + dy * dy <= reach * reach;
    }

    /**
     * Opens one edge-event drain pass: called by the drain itself, once, before it presents anything, with
     * the snapshot tick it drains at. The pass's apply cap is {@link #PER_TICK_CAP} for each snapshot tick
     * since the last pass ({@link #ticksCredited}): held records are released as the snapshot tick reaches
     * theirs, so the advance is the number of server ticks this pass can lawfully release.
     */
    void beginDrainPass(long snapshotTick) {
        passCap = PER_TICK_CAP * ticksCredited(snapshotTick, passTick);
        passTick = snapshotTick;
        capCount = 0;
    }

    /**
     * The snapshot ticks one drain pass is credited with: the advance since the last pass, never under 1
     * and never over {@link #PASS_TICKS_MAX}. The floor matters: the drain runs every render frame, a
     * record behind the snapshot is presented the frame it arrives, and most frames carry no new snapshot
     * tick, so an advance of 0 must still admit one tick's worth; the first pass of a round ({@code lastTick}
     * = {@code Long.MIN_VALUE}) and a tick that ran backwards read 1 the same way. An advance past the
     * maximum, or one whose subtraction overflowed, reads the maximum. Pure.
     */
    static int ticksCredited(long snapshotTick, long lastTick) {
        if (lastTick == Long.MIN_VALUE || snapshotTick <= lastTick) {
            return 1;
        }
        long advance = snapshotTick - lastTick;
        return advance < 0L || advance > PASS_TICKS_MAX ? PASS_TICKS_MAX : (int) advance;
    }

    /** The per-pass apply cap: false = this drain pass already applied its {@link #beginDrainPass} cap. */
    boolean admitPass() {
        if (capCount >= passCap) {
            return false;
        }
        capCount++;
        return true;
    }

    /** The burst radius of a fuse-family missile spec (its behaviour's explosionSpec), or -1 when unknown. */
    static float burstRadiusOf(String specId) {
        DamagingExplosionSpec spec = FuseBurstLaw.burstSpecFor(specId);
        return spec == null ? -1f : spec.getRadius();
    }

    /**
     * The end consumer's retire hook: remembers a fuse-family twin's source ship, burst radius and local
     * verdict BEFORE the twin leaves the maps (the burst's later hits find it here). Called while the body
     * is still in its pre-retire state; a non-family twin is ignored.
     */
    void latchFuseEnd(MissilePoseLane.Twin t, long endTick) {
        try {
            if (t == null || !t.fuseBurst || t.wireId <= 0L) {
                return;
            }
            MissileAPI m = t.missile;
            Object source = m == null ? null : m.getSource();
            latch.put(t.wireId, source, burstRadiusOf(t.specId),
                    localFirst(m != null && m.didDamage(), t.own, t.handedOff),
                    wireOwnSource(source, plugin == null ? null : plugin.ownShip, t.own), endTick);
            latched++;
        } catch (Throwable e) {
            latchFailures++;
            logOnce("blast_hit_latch_failed " + e.getClass().getSimpleName());
        }
    }

    /** Round reset: the latch, the per-round log budgets and the pass cap re-arm (counters stay cumulative).
     *  The last pass tick is forgotten: the new round's ticks restart, and its first pass reads one tick's
     *  worth whatever tick it opens at. */
    void resetRound() {
        latch.clear();
        logLines = 0;
        Arrays.fill(skipLoggedThisRound, false);
        passTick = Long.MIN_VALUE;
        passCap = PER_TICK_CAP;
        capCount = 0;
        ownAppliedNext = 0; // the own-applied ring empties with the latch
        ownAppliedSize = 0;
    }

    /** The meter tokens, each with a leading space, appended at the END of the edge-event meter line. */
    String meterTokens() {
        StringBuilder sb = new StringBuilder(768);
        sb.append(" blast_hit_received=").append(received);
        for (int i = 0; i < TOKENS.length; i++) {
            sb.append(' ').append(TOKENS[i]).append('=').append(counts[i]);
        }
        sb.append(" blast_hit_emp_dropped=").append(empDropped)
                .append(" blast_hit_latched=").append(latched)
                .append(" blast_hit_latch_failures=").append(latchFailures)
                .append(" blast_hit_live_marked=").append(liveMarked)
                .append(" blast_hit_live_mark_failures=").append(liveMarkFailures)
                .append(" blast_hit_own_overload_fault=")
                .append(plugin == null ? 0 : plugin.replicaBody.blastOwnOverloadFaults)
                .append(" blast_hit_own_restore_faults=")
                .append(plugin == null ? 0 : plugin.replicaBody.blastOwnRestoreFaults);
        return sb.toString();
    }

    int count(int exit) {
        return counts[exit];
    }

    int liveMarked() {
        return liveMarked;
    }

    int empDropped() {
        return empDropped;
    }

    int received() {
        return received;
    }

    private static void logOnce(String msg) {
        synchronized (failuresLogged) {
            if (failuresLogged.size() < 64 && failuresLogged.add(msg)) {
                PilotWatchLog.info(msg);
            }
        }
    }

    /** The live game side of {@link Env}. */
    private final class LiveEnv implements Env {
        @Override
        public ShipAPI victim(long wireId) {
            return WireShipResolver.resolve(plugin, wireId);
        }

        @Override
        public boolean ownShip(long wireId, ShipAPI victim) {
            return (plugin.session != null && wireId == plugin.session.shipId())
                    || (victim != null && victim == plugin.ownShip);
        }

        @Override
        public boolean treated(ShipAPI victim) {
            return plugin.remoteBodyFullyTreated(victim);
        }

        @Override
        public boolean liveBomb(long bombWireId, Bomb into) {
            MissilePoseLane.Twin t = plugin.missileLane.twinByWireId.get(Long.valueOf(bombWireId));
            if (t == null) {
                return false;
            }
            MissileAPI m = t.missile;
            fillBomb(into, t.fuseBurst, m == null ? null : m.getSource(), plugin.ownShip,
                    t.fuseBurst ? burstRadiusOf(t.specId) : -1f, m != null && m.didDamage(), t.own, t.handedOff);
            return true;
        }

        @Override
        public float apply(ShipAPI victim, Vector2f at, float damage, DamageType type, float emp, Object source,
                boolean bypassShields, long tick) {
            return plugin.applyWireAckedHit(victim, at, damage, type, emp, source, bypassShields, true, tick);
        }

        /** The own door's four reads and acts. */
        @Override
        public boolean ownUncapped(ShipAPI victim) {
            return BlastHitLane.ownUncapped(plugin.capOwnDamage, victim, plugin.ownShip, plugin.ownCapBody);
        }

        @Override
        public Object ownBody() {
            return plugin.ownShip;
        }

        @Override
        public boolean markBurst(long bombWireId) {
            MissilePoseLane.Twin t = plugin.missileLane.twinByWireId.get(Long.valueOf(bombWireId));
            MissileAPI m = t == null ? null : t.missile;
            if (m == null || m.getCollisionClass() == CollisionClass.NONE) {
                return false;
            }
            m.setCollisionClass(CollisionClass.NONE);
            return true;
        }

        @Override
        public float applyOwn(ShipAPI own, Vector2f at, float damage, DamageType type, Object source,
                boolean bypassShields, long tick) {
            return plugin.replicaBody.applyWireAckedOwnHit(own, at, damage, type, bypassShields, source, tick);
        }
    }
}
