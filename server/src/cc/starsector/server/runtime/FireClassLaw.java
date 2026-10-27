package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The per-weapon fire-class law:
 * <b>CLASS S</b> (per-shot fidelity — the declared-discharge serve) iff the weapon's BASE-spec
 * sustained rate is at or under the knob threshold AND it is not a beam; everything else is
 * <b>CLASS H</b> (today's held/latch lanes). Pure arithmetic + string parsing — no engine types —
 * so the routing contract certifies it game-free and the resolver seam (EngineCombatBinding, the
 * production spec reads) stays a thin adapter.
 *
 * <p><b>Why sustained rate, not raw refire:</b> burst weapons lie under a refire threshold
 * (heavyac 1.0 s refire ties hammer, but 3×0.2 s burst ⇒ 1.9 shots/s sustained). The formula is
 * {@code burstSize / (refireDelay + burstSize·burstDelay)} on BASE spec reads only — never live
 * stat-modified {@code WeaponAPI.getCooldown()} (hullmod/skill-skewed). Default threshold
 * {@link #DEFAULT_RATE_MAX} = 1.2 shots/s/mount: a real measured gap separates the felt-per-shot
 * weapons (reaper 0.2, hveldriver 0.5, hammer 1.0) from the stream class (railgun 1.7, heavyac
 * 1.9, vulcan 20). Principled bound: class-S inter-shot period ≥ ~1 s means the declared FIFO
 * drains by the 60-tick head timeout faster than it fills ⇒ depth O(1) by construction; the
 * sub-second stream class fills faster than any drain.
 *
 * <p>Unclassifiable inputs (non-finite/degenerate spec numbers) fail SAFE to class H — H is
 * today's proven lane; S is the enhancement. Overrides ({@code cc.server.fireClassOverride},
 * {@code weaponId:S|H} comma list) are deliberate operator exceptions and win over the predicate
 * INCLUDING the beam exclusion; the runtime demote belts (guided / backlog — PilotApplyState)
 * still apply after any override.
 */
public final class FireClassLaw {

    /** The two serve classes: S = declared per-shot serve, H = held/latch lanes (the default). */
    public enum FireClass { S, H }

    /** Default {@code cc.server.fireClassRateMax} (shots/s/mount) — see class javadoc for why 1.2. */
    public static final double DEFAULT_RATE_MAX = 1.2;

    private FireClassLaw() {
    }

    /**
     * Sustained shots/s of one mount from BASE spec reads:
     * {@code burstSize / (refireDelay + burstSize·burstDelay)}. A degenerate denominator (≤ 0) or
     * non-finite input returns {@code POSITIVE_INFINITY} — an "infinitely fast" answer that
     * classifies H, the fail-safe direction. {@code burstSize < 1} is read as 1 (single-shot).
     */
    public static double sustainedRate(int burstSize, float refireDelaySeconds, float burstDelaySeconds) {
        if (!Float.isFinite(refireDelaySeconds) || !Float.isFinite(burstDelaySeconds)) {
            return Double.POSITIVE_INFINITY;
        }
        int burst = Math.max(1, burstSize);
        double denominator = (double) refireDelaySeconds + (double) burst * (double) burstDelaySeconds;
        if (!(denominator > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        return burst / denominator;
    }

    /**
     * The predicate: override wins (even over the beam exclusion — deliberate operator
     * exception); else beam ⇒ H; else non-finite rate ⇒ H (fail-safe); else S iff
     * {@code sustainedRate <= rateMax}.
     */
    public static FireClass classify(String weaponId, boolean beam, double sustainedRate,
            double rateMax, Map<String, FireClass> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        if (weaponId != null) {
            FireClass override = overrides.get(weaponId);
            if (override != null) {
                return override;
            }
        }
        if (beam) {
            return FireClass.H;
        }
        if (!Double.isFinite(sustainedRate)) {
            return FireClass.H;
        }
        return sustainedRate <= rateMax ? FireClass.S : FireClass.H;
    }

    /**
     * Parse {@code cc.server.fireClassOverride}: a comma list of {@code weaponId:S|H} entries
     * (blank/null ⇒ no overrides). STRICT and loud — a malformed entry throws
     * {@code IllegalArgumentException} at knob-resolve time (the project's fail-closed knob
     * posture: a boot kill beats a silently ignored operator exception). Class letters are
     * case-insensitive; weapon ids are kept verbatim (spec ids are case-sensitive).
     */
    public static Map<String, FireClass> parseOverrides(String raw) {
        Map<String, FireClass> overrides = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return overrides;
        }
        for (String token : raw.split(",")) {
            String entry = token.trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException(
                        "cc.server.fireClassOverride: empty entry in '" + raw + "'");
            }
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "cc.server.fireClassOverride entry must be weaponId:S|H; got '" + entry + "'");
            }
            String weaponId = entry.substring(0, colon).trim();
            String classText = entry.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            FireClass fireClass;
            switch (classText) {
                case "S" -> fireClass = FireClass.S;
                case "H" -> fireClass = FireClass.H;
                default -> throw new IllegalArgumentException(
                        "cc.server.fireClassOverride entry must be weaponId:S|H; got '" + entry + "'");
            }
            if (overrides.put(weaponId, fireClass) != null) {
                throw new IllegalArgumentException(
                        "cc.server.fireClassOverride: duplicate weaponId '" + weaponId + "'");
            }
        }
        return overrides;
    }
}
