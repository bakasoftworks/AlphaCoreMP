package cc.starsector.client;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ShotBehaviorSpecAPI;

import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * The GENERIC FUSE BURST law — pure statics + ONE per-spec cache.
 *
 * <p>WHY: a bomb the engine flies on a {@code ProximityFuseAI} (vanilla: the Wasp's {@code fragbomb_proj})
 * ends in vanilla as ONE blast — the behaviour's own explosionSpec, radius 100, 150 particles,
 * {@code explosion_flak} — whether the fuse tripped on a nearby target or on the fade. The client drew a small
 * silent puff for the first and NOTHING for the second (run B, 189 bombs: 86 proximity bursts, 58 flight-time
 * bursts). This law names that family and its end verdict; the act
 * ({@code SourcedMissileEndLane.presentFuseBurst}) spawns the engine's own {@code DamagingExplosion} on a
 * damage-inert clone of that spec. Damage stays the server's.
 *
 * <p>THE FAMILY KEY is the engine's BOMB / BOMB_WITH_SLOW fuse-install branch minus the rows that already
 * have a lane: type BOMB / BOMB_WITH_SLOW + a non-null
 * behaviorSpec. It is that ONE branch, not the engine's whole rule: PHASE_CHARGE installs the fuse with no
 * behaviorSpec test, and a mod's {@code pickMissileAIOverride} wins before any branch
 * — a family body a mod flies on its own AI still draws
 * this burst at its wire END.
 * The behaviour STRING is never read at mint there, and keying on it would admit the MISSILE-type assaying
 * rift / unstable fragment. Named rows (mines, phase charges, nova / orion, canister, conversion, unstable
 * fragment, assaying rift) keep their own lanes — {@code isFuseDetSpec} is NOT widened by this class.
 *
 * <p>NOT REUSED, on purpose: the named prox door ({@code createProximityFuseAI} + {@code flameOut}) deals REAL
 * local damage that only its DetSpec rows book; a behaviour-keyed family has no such row.
 * {@code MissileAPI.explode()} spawns the TOP-LEVEL spec (fragbomb: a silent r75 contact blast) with real damage.
 */
final class FuseBurstLaw {

    private FuseBurstLaw() {
    }

    /** End verdicts ({@link #endVerdict}). NONE = not this law's end, flow on unchanged. */
    static final int NONE = 0, PUFF = 1, CONTACT = 2, BURST = 3;

    /** Burst routes ({@link #burstRoute}): PRESENT, or the refusal that is counted by name. */
    static final int ACT_PRESENT = 0, ACT_BODYLESS = 1, ACT_NO_SOURCE = 2, ACT_NO_SPEC = 3;

    /** The engine's BOMB / BOMB_WITH_SLOW fuse-install branch minus the named rows. Pure (row G1). */
    static boolean family(boolean mine, String specId, String typeString, boolean hasBehaviorSpec,
            boolean hasBurstSpec) {
        if (mine || specId == null || !hasBehaviorSpec || !hasBurstSpec) {
            return false;
        }
        if (!"BOMB".equals(typeString) && !"BOMB_WITH_SLOW".equals(typeString)) {
            return false;
        }
        return !(MineBombEndFxLane.isFuseDetSpec(specId)
                || MineBombEndFxLane.isCanisterSpec(specId)
                || DroneStrikeFxLane.isConversionSpec(specId)
                || SourcedMissileEndLane.isUnstableFragmentSpec(specId)
                || SourcedMissileEndLane.isAssayingRiftSpec(specId));
    }

    /** DESTROYED = PUFF (shot down: the death puff, as today). FIZZLING = BURST, victim or not: on a fuse
     *  missile flameout IS the fade, and vanilla's fuse bursts on the first fading frame. INTACT with no victim
     *  = BURST (the fuse tripped and removed the body). INTACT / SHIELD_ABSORBED with a victim = CONTACT (a
     *  direct hit: today's contact door). Anything else = NONE. Pure (row G2). */
    static int endVerdict(boolean family, int endCauseCode, long victimWireId) {
        if (!family) {
            return NONE;
        }
        boolean victim = victimWireId > 0L;
        switch (endCauseCode) {
            case ClientGameplayEdgeEvent.END_CAUSE_DESTROYED:
                return PUFF;
            case ClientGameplayEdgeEvent.END_CAUSE_FIZZLING:
                return BURST;
            case ClientGameplayEdgeEvent.END_CAUSE_INTACT:
                return victim ? CONTACT : BURST;
            case ClientGameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED:
                return victim ? CONTACT : NONE;
            default:
                return NONE;
        }
    }

    /** Refusal precedence: a body out of play (it presented its own local end) &gt; a null source (the ONE
     *  input {@code spawnDamagingExplosion} dereferences unguarded;
     * a dead or hulked source is safe) &gt; no cached spec. Pure (G3). */
    static int burstRoute(boolean inPlay, boolean sourceKnown, boolean specKnown) {
        if (!inPlay) {
            return ACT_BODYLESS;
        }
        if (!sourceKnown) {
            return ACT_NO_SOURCE;
        }
        if (!specKnown) {
            return ACT_NO_SPEC;
        }
        return ACT_PRESENT;
    }

    /** Where the blast draws: the body's own pose wins (vanilla bursts at the projectile's location, and the
     *  player's eye is on the sprite); else the finite in-ceiling wire END pose; else the last wire pose.
     *  Always a fresh vector — never the body's live location object. Pure (row G4). */
    static Vector2f burstPose(Vector2f body, boolean hasEnd, float endX, float endY, float lastX, float lastY) {
        if (body != null && Math.abs(body.x) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU
                && Math.abs(body.y) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU) {
            return new Vector2f(body.x, body.y);
        }
        if (hasEnd && Math.abs(endX) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU
                && Math.abs(endY) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU) {
            return new Vector2f(endX, endY);
        }
        return new Vector2f(lastX, lastY);
    }

    /** A non-own family twin never takes the wire-FIZZLING flameOut relay: the relay fades the sprite a tick
     *  before the END that bursts it (the phase-charge precedent). v1 leaves the seat hull's OWN twins alone
     *  (no vanilla hull mounts a bomb-type fuse weapon; a parked line). Pure (row G5). */
    static boolean relaySkips(boolean family, boolean own) {
        return family && !own;
    }

    /** How long a fire-presented FOE family body may fly with no wire record before it is the server's
     *  "never carried": 30 server ticks (0.5 s).
     *
     *  <p>WHY: the server reads its missile list AFTER each sim step, so a bomb that ends inside the step it
     *  was born in (dropped overlapping a hostile: the engine's direct-hit path) gets no wire record and no END;
     *  only its fire event reaches the client. The body presented from that event then had no release but the
     *  120-tick link window, flying on fuseless and tangible: in the measured rig run one flew 54 ticks,
     *  touched a replica ship and its contact blast dealt local hull damage the server never dealt.
     *
     *  <p>THE NUMBER is read off that run, not guessed: a carried bomb's first wire record sits on its fire
     *  tick (240 of 240 tracks; every logged client link at lag 0), the widest link lag any missile class
     *  logged was 12 ticks and the snapshot merger's widest mixed-tick frame was 18. 30 = 18 + that 12-tick
     *  margin; it is also the lane's 0.5 s end-removal bar, and it stays under the 61-tick same-mount refire,
     *  so an unconfirmed body is normally gone before the NEXT bomb's record arrives (the link runs before the
     *  leash, so a stale gap past 30 ticks or a faster-firing modded bomb can still let it claim one: narrower
     *  than the old 120, not closed). An early retire of a bomb the server does carry is self-healing:
     *  presence-adopt re-mints it from its record. */
    static final int UNCONFIRMED_TICKS = 30;

    /** A foe family body the wire has not linked {@link #UNCONFIRMED_TICKS} after its fire = the server never
     *  carried it: retire it quietly (the caller holds it collision NONE first). OWN twins keep their own
     *  ghost law; a negative spawnTick (the legacy sentinel) stays the link window's. Pure (row G8). */
    static boolean unconfirmedDue(boolean family, boolean own, long wireId, long spawnTick, long tick) {
        return family && !own && wireId == -1L && spawnTick >= 0L && tick - spawnTick >= UNCONFIRMED_TICKS;
    }

    /** A foe family twin adopted with NO source hull (its launcher was already gone) still bursts at a BURST
     *  end. The body is not source-less to the engine: a mint with a null ship gets the engine's own stand-in
     *  ship, the same object vanilla's fuse would
     *  hand the explosion builder, so {@link #burstRoute}'s null refusal still guards the act. Every other
     *  end of that twin (contact, shot down) stays the quiet legacy end. Pure (row G9). */
    static boolean sourcelessBursts(boolean family, boolean own, long sourceEntityId, int endCauseCode,
            long victimWireId) {
        return sourceEntityId <= 0L && !own && endVerdict(family, endCauseCode, victimWireId) == BURST;
    }

    /** The behaviour's explosionSpec loaded the way {@code ProximityFuseAI}'s ctor loads it,
     * then made INERT: both collision classes NONE (the grid
     *  never pairs it), damage and EMP 0, effect null. Null when the params carry no explosionSpec or the
     *  loader throws ({@code Misc.mapToEnum} throws a RuntimeException on a missing collisionClass). Never
     *  writes to {@code behaviorParams}. Pure (row G6). */
    static DamagingExplosionSpec inertBurstSpec(JSONObject behaviorParams) {
        try {
            JSONObject burst = behaviorParams == null ? null : behaviorParams.optJSONObject("explosionSpec");
            DamagingExplosionSpec spec = burst == null ? null : DamagingExplosionSpec.loadFromJSON(burst);
            if (spec == null) {
                return null;
            }
            spec.setCollisionClass(CollisionClass.NONE);
            spec.setCollisionClassByFighter(CollisionClass.NONE);
            spec.setMaxDamage(0f);
            spec.setMinDamage(0f);
            spec.setMaxEMPDamage(0f);
            spec.setMinEMPDamage(0f);
            spec.setEffect(null);
            return spec;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The cache's NOT-FAMILY answer (a spec id is classified once, family or not). */
    private static final Object NOT_FAMILY = new Object();

    /** spec id -> the inert burst spec, or {@link #NOT_FAMILY}. Its OWN map — never
     *  {@code SourcedMissileEndLane.inertBlastBySpec}, which keys the TOP-LEVEL explosionSpec by the same
     *  spec id (a collision there would draw fragbomb's silent r75 contact spec). Combat thread only; one
     *  entry per missile spec seen, for the JVM's life (specs do not change under a running game). */
    private static final Map<String, Object> BY_SPEC = new HashMap<>();

    /** Classify throws OR burst-spec load failures since boot (metered {@code fuse_classify_fault}); each
     *  announces once per class. A load failure = a BOMB-type spec whose behaviour DOES carry an explosionSpec
     *  the engine's loader refused (e.g. no collisionClass): not family, and no longer silently so. */
    static int classifyFaults;

    /** The Twin flag, read ONCE per spec id from the live spec (a mint pays one map read after the
     *  first). A mine is never family and never cached (isMine is per body). A body whose own spec id is not
     *  {@code specId} (a twin dressed in another spec) is never family and never cached — the cache would
     *  otherwise file one spec's verdict under another's id. A body with no readable spec (a selftest proxy)
     *  is false and uncached. A throw = NOT family for that id, counted + announced. */
    static boolean classify(MissileAPI missile, String specId, boolean mine) {
        if (mine || missile == null || specId == null) {
            return false;
        }
        try {
            if (!specId.equals(missile.getProjectileSpecId())) {
                return false;
            }
            Object cached = BY_SPEC.get(specId);
            if (cached != null) {
                return cached != NOT_FAMILY;
            }
            MissileSpecAPI spec = missile.getSpec();
            if (spec == null) {
                return false;
            }
            ShotBehaviorSpecAPI behavior = spec.getBehaviorSpec();
            JSONObject params = behavior == null ? null : behavior.getParams();
            DamagingExplosionSpec inert = inertBurstSpec(params);
            String type = spec.getTypeString();
            if (inert == null && params != null && params.has("explosionSpec")
                    && family(false, specId, type, true, true)) {
                classifyFaults++; // family but for the load: counted, never a silent legacy end
                FallbackAnnounce.announceOnce("fuse-burst-spec-load",
                        "FuseBurstLaw: the behaviour explosionSpec did not load (first spec=" + specId
                        + ") -> that spec's fuse ends keep the legacy look");
            }
            boolean fam = family(false, specId, type, behavior != null, inert != null);
            BY_SPEC.put(specId, fam ? inert : NOT_FAMILY);
            return fam;
        } catch (Throwable e) {
            classifyFaults++;
            BY_SPEC.put(specId, NOT_FAMILY);
            FallbackAnnounce.announceOnce("fuse-burst-classify-" + e.getClass().getSimpleName(),
                    "FuseBurstLaw.classify threw (first spec=" + specId + ") -> that spec's fuse ends keep the legacy look");
            return false;
        }
    }

    /** The cached inert burst spec for a classified family spec id, else null. */
    static DamagingExplosionSpec burstSpecFor(String specId) {
        Object cached = specId == null ? null : BY_SPEC.get(specId);
        return cached instanceof DamagingExplosionSpec ? (DamagingExplosionSpec) cached : null;
    }
}
