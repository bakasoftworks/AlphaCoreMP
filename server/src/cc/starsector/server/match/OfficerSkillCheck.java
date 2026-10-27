package cc.starsector.server.match;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The ONE skill-id validation law BOTH ends run before any
 * {@code setSkillLevel} lands — hoisted verbatim from the server's {@code OfficerAttach}
 * so the client twin ({@code OfficerLoadout.loadOnce}) fail-closes legibly at LOAD instead
 * of NPEing deep in the engine's apply chain at replica spawn mid-round
 * ({@code applyPersonalToShip} reads the skill map).
 *
 * <p>Engine-free by construction: the resolver is injected because the two ends resolve
 * differently by law — the server through its reflective {@code SkillProbe} (no compile-time
 * API link), the client through the typed public API (classloader-B: no reflection).
 */
public final class OfficerSkillCheck {

    /** How the calling end resolves a skill id against ITS engine's skill table. */
    public interface SkillResolver {

        /** The engine skill spec for the id, or null when the id is unknown. */
        Object skillSpecOrNull(String skillId) throws Exception;

        /** Whether the RESOLVED spec is a combat-officer skill (diagnostics only). */
        boolean isCombatOfficerSkill(Object skillSpec) throws Exception;
    }

    private OfficerSkillCheck() {
    }

    /**
     * Fail-CLOSED validation of every skill id in the row: an unknown id (null spec) or a
     * resolver throw is an {@link IllegalStateException} naming variant + skill — refusing to
     * attach a captain whose skills the engine cannot apply. Returns the resolved-but-non-combat
     * ids in row order (an admin/admiral skill applies nothing on a captain, but it is not a
     * broken table) — the caller logs/dedups on its own sink. A failed CLASSIFICATION read on a
     * RESOLVED id is diagnostics only and never fails a valid table closed.
     */
    public static Set<String> validate(String variantId, OfficerRosterCodec.OfficerSpec spec,
            SkillResolver resolver) {
        return validateIds(variantId, spec.skills.keySet(), resolver);
    }

    /**
     * The same law over a bare id collection — the W4 all-skills lane's invariant assert
     * (the sets are engine-sourced, so this re-check is
     * cheap and can only fire on an engine-table inconsistency). {@code label} names the caller
     * in the fail-closed message where the curated lane names a variant.
     */
    public static Set<String> validateIds(String variantId, java.util.Collection<String> skillIds,
            SkillResolver resolver) {
        Set<String> inert = new LinkedHashSet<String>();
        for (String skillId : skillIds) {
            Object skillSpec;
            try {
                skillSpec = resolver.skillSpecOrNull(skillId);
            } catch (Exception resolveFailure) {
                Throwable cause = (resolveFailure instanceof InvocationTargetException
                        && resolveFailure.getCause() != null)
                        ? resolveFailure.getCause() : resolveFailure;
                throw new IllegalStateException("officer row for variant '" + variantId
                        + "' names skill '" + skillId + "' which the engine's skill table"
                        + " REFUSED to resolve (" + cause + ") — refusing to attach a captain"
                        + " whose skills the engine cannot apply", cause);
            }
            if (skillSpec == null) {
                throw new IllegalStateException("officer row for variant '" + variantId
                        + "' names UNKNOWN skill '" + skillId + "' (SettingsAPI.getSkillSpec"
                        + " returned null) — fail-closed: an unknown id NPEs deep in the engine's"
                        + " apply chain mid-round instead");
            }
            boolean combat;
            try {
                combat = resolver.isCombatOfficerSkill(skillSpec);
            } catch (Exception readFailure) {
                // The id RESOLVED — the engine can apply it. A failed classification read is
                // diagnostics only and must never fail a valid table closed.
                combat = true;
            }
            if (!combat) {
                inert.add(skillId);
            }
        }
        return inert;
    }
}
