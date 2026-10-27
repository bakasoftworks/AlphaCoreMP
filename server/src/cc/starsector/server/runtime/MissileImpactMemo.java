package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * GPEE v15: the per-missile LAST-IMPACT memo behind the {@code MISSILE_END}
 * victim form — WHICH ship a missile last hit and whether that hit was SHIELD-absorbed, read
 * off the server engine's own damage report and joined onto the missile's end confession.
 *
 * <p>Why: the wire's {@code MISSILE_END} cause is hp/fizzle triage only, so a shield-absorbed
 * devouring-swarm carrier arrived INTACT exactly like a hull consume and the client GUESSED the
 * contact from geometry — one test flight: 7 of 7 presented DoTs were phantoms (802 armor
 * numbers printed for damage the server never dealt). Vanilla mints the DoT
 * only on {@code onHit(..., shieldHit=false, ...)},
 * so the server names the contact and the client stops guessing (authority server-side).
 *
 * <p>Seam: the projectile-vs-ship collision handler
 * runs {@code ship.setParamAboutToApplyDamage(projectile)} then
 * {@code ship.applyDamage(point, dmg, shieldHit, mult, projectile.getSource())};
 * {@code Ship.applyDamage} reports {@code (this, source, this, result)} to the GLOBAL
 * {@code DamageListener}s ⇒ inside {@link ArcHitServeProbe#capture} {@code target} = the victim ship,
 * {@code target.getParamAboutToApplyDamage()} = the MISSILE object, {@code source} = the FIRING ship.
 * The result ({@code ApplyDamageResultAPI}) carries no shield flag,
 * so the contact is CLASSIFIED from its floats: {@code Ship.applyDamageInner} writes
 * {@code damageToShields} only on the shield branch (a hit that overloads a shield with ZERO flux
 * headroom lands as {@code overMaxDamageToShields} with {@code damageToShields=0}),
 * else armor/hull. All four zero = UNKNOWN ⇒ no memo (fail-closed toward "ended on nothing").
 * The same handler removes an impacting missile ({@code removeObject}) ⇒ its post-mortem
 * triage is INTACT for shield AND hull, which is exactly the ambiguity this memo resolves.
 *
 * <p>Threading: fed on the sim thread inside the engine advance (the probe's own callback — ZERO
 * extra listeners; per missile impact two more cached invokes, none on any other damage report);
 * consumed + pruned on the same thread at the binding's missile read (serve loop + advance share one
 * thread, the {@link ArcHitServeProbe} {@code pending} law). Identity-keyed ({@link IdentityHashMap}:
 * engine entities are never compared by value); {@link #take} consumes at delist, {@link #retainLive}
 * drops the rest (a missile that impacted but stayed live — pierce/no-collision specs — never leaks).
 */
public final class MissileImpactMemo {

    /** One missile's last ship impact: the engine victim object + the shield verdict. */
    public record Impact(Object victimShip, boolean shieldAbsorbed) {
    }

    /** {@link #classify} verdicts. */
    public static final int CONTACT_UNKNOWN = 0;
    public static final int CONTACT_HULL = 1;
    public static final int CONTACT_SHIELD = 2;

    private final IdentityHashMap<Object, Impact> byMissile = new IdentityHashMap<>();
    private long recorded;
    private long unknown;
    private long taken;
    private long pruned;
    /**
     * Ship-target reports the memo could NOT consume: the probe's isolated memo-feed step threw
     * (a reflective read on the victim/param/result — a param-read throw counts too, since the memo
     * then cannot tell whether it was a missile impact), so that impact's victim + shield verdict
     * never landed and its {@code MISSILE_END} will name no victim.
     * without this meter "the server was blind" and
     * "the missile hit nothing" look identical on the wire ({@code TARGET_ABSENT} ⇒ the client's
     * {@code DEVOUR_SKIP_UNNAMED}). Round line {@code missile_impact_blind=} (0 on a clean serve; the
     * batch flight grades a nonzero value as a server-side FINDING, never a phantom fix). The feed
     * failure is folded into this one meter (brief r4 contract 1) — every feed throw drops exactly
     * one report. The arc lane's 3-strike fail-soft no longer gates the feed ({@link
     * ArcHitServeProbe#capture} runs the feed in its OWN try/catch ahead of the arc processing), so
     * an arc-lane quiet is NOT a memo blindness and does not count here.
     *
     */
    private long blind;
    /** First feed failure logged once (then the meter only). */
    private boolean blindLogged;

    /**
     * The contact law (pure): shield damage or over-max shield damage {@code > 0} ⇒ SHIELD (the
     * shield branch is the ONLY writer of either, {@code Ship.applyDamageInner}); else
     * armor or hull {@code > 0} ⇒ HULL; else UNKNOWN (a zero-damage report — never guessed).
     * Non-finite floats count as zero.
     */
    public static int classify(float dmgHull, float dmgArmor, float dmgShields, float overMaxShields) {
        if (positive(dmgShields) || positive(overMaxShields)) {
            return CONTACT_SHIELD;
        }
        if (positive(dmgHull) || positive(dmgArmor)) {
            return CONTACT_HULL;
        }
        return CONTACT_UNKNOWN;
    }

    private static boolean positive(float v) {
        return Float.isFinite(v) && v > 0f;
    }

    /**
     * Sim thread: one ship-target damage report whose param was a missile. Overwrites — the LAST
     * impact is the one the delist confesses. An UNKNOWN contact clears any earlier memo for the
     * missile (never a stale verdict) and counts.
     */
    public void record(Object missile, Object victimShip, float dmgHull, float dmgArmor,
            float dmgShields, float overMaxShields) {
        if (missile == null || victimShip == null) {
            return;
        }
        int contact = classify(dmgHull, dmgArmor, dmgShields, overMaxShields);
        if (contact == CONTACT_UNKNOWN) {
            unknown++;
            byMissile.remove(missile);
            return;
        }
        recorded++;
        byMissile.put(missile, new Impact(victimShip, contact == CONTACT_SHIELD));
    }

    /** Take-and-clear the memo of a missile delisted this read; {@code null} when it never hit a ship. */
    public Impact take(Object missile) {
        Impact impact = missile == null ? null : byMissile.remove(missile);
        if (impact != null) {
            taken++;
        }
        return impact;
    }

    /** Drop every memo whose missile is NOT in {@code liveMissiles} (identity) — the leak guard. */
    public void retainLive(Set<Object> liveMissiles) {
        if (liveMissiles == null) {
            return;
        }
        for (Iterator<Object> it = byMissile.keySet().iterator(); it.hasNext();) {
            if (!liveMissiles.contains(it.next())) {
                it.remove();
                pruned++;
            }
        }
    }

    /**
     * Serve loop: resolve a delist read's {@code stableId → Impact} map to the detector's wire-level
     * {@code stableId → Contact} map through the SAME supplier pair the sibling probes use
     * ({@code ships} = binding ship→positionShipId; {@code wireIds} = positionShipId→wire id). An
     * unresolvable victim keeps the shield verdict but names NO ship ({@code victimWireId = -1}).
     */
    public static Map<Long, MissileSplitEventDetector.Contact> toContacts(
            Map<Long, Impact> impactsByStableId, Map<Object, Long> ships, Map<Long, Long> wireIds) {
        if (impactsByStableId == null || impactsByStableId.isEmpty()) {
            return Map.of();
        }
        Map<Long, MissileSplitEventDetector.Contact> out = new HashMap<>(impactsByStableId.size() * 2);
        for (Map.Entry<Long, Impact> e : impactsByStableId.entrySet()) {
            Impact impact = e.getValue();
            if (impact == null) {
                continue;
            }
            long victim = ServeProbeSupport.wireIdOf(impact.victimShip(), ships, wireIds);
            out.put(e.getKey(), new MissileSplitEventDetector.Contact(victim, impact.shieldAbsorbed()));
        }
        return out;
    }

    /** Live memo entries (impacted, not yet delisted). */
    public int pendingCount() {
        return byMissile.size();
    }

    public long recordedCount() {
        return recorded;
    }

    public long unknownCount() {
        return unknown;
    }

    public long takenCount() {
        return taken;
    }

    public long prunedCount() {
        return pruned;
    }

    /**
     * Sim thread: the probe's memo-feed step threw on one report — count it into
     * {@code missile_impact_blind=} and log the FIRST cause once (counter-only afterwards;
     * no per-report print on the sim thread). Never throws.
     *
     */
    public void noteFeedBlind(Throwable cause) {
        blind++;
        if (!blindLogged) {
            blindLogged = true;
            if (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause(); // name the reflective read's REAL failure, not the wrapper
            }
            System.out.println("missile-impact memo feed FAIL (first; missile_impact_blind= counts from here) "
                    + (cause == null ? "null" : cause.getClass().getName() + ": " + cause.getMessage()));
        }
    }

    /** Reports the memo could not consume ({@link #noteFeedBlind}). */
    public long blindCount() {
        return blind;
    }

    /** Round-line token block ({@code DownLaneSeams}). {@code missile_impact_blind=} appended later
     * — the existing tokens keep their shape. */
    public String meterTokens() {
        return " missile_impact_memo=" + recorded + "/" + taken + "/" + pruned
                + " missile_impact_unknown=" + unknown
                + " missile_impact_blind=" + blind;
    }
}
