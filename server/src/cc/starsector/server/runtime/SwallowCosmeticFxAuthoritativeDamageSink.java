package cc.starsector.server.runtime;

import java.lang.reflect.InvocationTargetException;

/**
 * Product-owned {@link AuthoritativeDamageSink} that drives <em>lethal</em>,
 * engine-resolved server-authored damage headless by carrying the engine's per-hit
 * cosmetic-FX boundary explicitly.
 *
 * <p>Measurement proved that the engine <strong>applies the authoritative armor/hull
 * reduction inside {@code CombatEngineAPI.applyDamage} and THEN runs a per-hit cosmetic
 * damage-effect</strong> (decal/explosion) that dereferences a null
 * {@code com.fs.graphics.Object} headless (no GL context) and throws a
 * {@link NullPointerException}. That NPE is thrown <em>after</em> the damage has already
 * landed (proven by the 1500-dmg control: every {@code applyDamage} threw the NPE yet
 * both ships still died). So the authoritative damage/death/despawn is <em>not</em>
 * render-coupled; only the cosmetic FX is.
 *
 * <p>The design requires the product sink to handle this on three separate try-scopes so a
 * swallowed cosmetic NPE never hides the landed damage or the kill:
 * <ol>
 *   <li><b>apply</b> — invoke the engine's authoritative {@code applyDamage} (the
 *       GL-free engine-internal descriptor) via the supplied
 *       {@link EngineDamageApplication} plug-in;</li>
 *   <li><b>classify + swallow</b> — if the apply threw, unwrap any reflective wrapper and
 *       test it against {@link #isHeadlessCosmeticFxNpe}; swallow a headless cosmetic-FX
 *       NPE (count it, treat the impact as applied) but fail-closed (return {@code false})
 *       on any other throwable;</li>
 *   <li><b>read result</b> — on a <em>separate</em> try-scope, read the authoritative
 *       post-apply outcome (hull/alive/kill) via the optional
 *       {@link AuthoritativeOutcomeReader}. A read failure here (e.g. the ship was removed
 *       from the engine repo on death) is tolerated: the damage already landed, so the
 *       impact stays applied — the read failure never un-applies a landed hit.</li>
 * </ol>
 *
 * <p>This class owns the <em>decision</em> logic (the boundary fingerprint, the
 * phase separation, the fail-closed rule, the swallow/applied/error counters). The
 * engine-internal identity — the actual reflective {@code applyDamage} call and the
 * durability read — stays behind the two functional plug-ins, which the bridge/scout
 * supplies; that keeps the version-specific engine identity out of {@code server/src}
 * (which compiles with no game jars) while the product owns the load-bearing handling.
 * The server never reimplements collision/armor/damage and never holds a GL context;
 * armor/hull/death stay engine-authoritative and snapshot-replicated.
 */
public final class SwallowCosmeticFxAuthoritativeDamageSink implements AuthoritativeDamageSink {

    /**
     * The version-independent marker that identifies the engine's headless cosmetic-FX
     * NPE: a {@link NullPointerException} whose message names the GL texture/decal type
     * {@code com.fs.graphics.Object} that is null without an OpenGL context. Matched as a
     * substring of the NPE message (same family as the earlier spawn and armor-cell findings).
     */
    public static final String HEADLESS_COSMETIC_FX_NPE_MARKER = "com.fs.graphics";

    /**
     * Applies one resolved impact's raw damage to the engine's authoritative ship state.
     * Implementations make the reflective engine-internal {@code applyDamage} call.
     * They are expected to throw the engine's headless cosmetic-FX {@link NullPointerException}
     * after the damage lands; this sink classifies and swallows that. Any other throwable is
     * treated as a real failure and fail-closes the impact.
     */
    @FunctionalInterface
    public interface EngineDamageApplication {
        void applyToEngine(ServerAuthoredProjectileImpact impact) throws Throwable;
    }

    /**
     * Reads the authoritative post-apply outcome (hull/alive/kill) on a try-scope separate
     * from the apply, so a swallowed cosmetic-FX NPE never hides the landed damage. Invoked
     * only after the apply phase did not fail-closed. The {@code cosmeticFxSwallowed} flag
     * tells the reader whether this impact's cosmetic FX NPE was swallowed. May throw; a
     * throw is tolerated (the damage already landed) — see the class contract.
     */
    @FunctionalInterface
    public interface AuthoritativeOutcomeReader {
        void readOutcome(ServerAuthoredProjectileImpact impact, boolean cosmeticFxSwallowed) throws Throwable;
    }

    private final EngineDamageApplication engineApply;
    private final AuthoritativeOutcomeReader outcomeReader;

    private long appliedCount;
    private long cosmeticFxNpeSwallowedCount;
    private String firstSinkError = "";
    private String firstSwallowedFxDetail = "";
    private String firstOutcomeReadError = "";

    /**
     * @param engineApply the engine apply plug-in (required); makes the reflective
     *                    {@code applyDamage} call and may throw the cosmetic-FX NPE.
     * @param outcomeReader the post-apply outcome reader (optional; {@code null} for
     *                    apply-only use); runs on a separate try-scope.
     */
    public SwallowCosmeticFxAuthoritativeDamageSink(
            EngineDamageApplication engineApply,
            AuthoritativeOutcomeReader outcomeReader) {
        if (engineApply == null) {
            throw new IllegalArgumentException("engineApply must not be null");
        }
        this.engineApply = engineApply;
        this.outcomeReader = outcomeReader;
    }

    /**
     * Tests whether a throwable is the engine's headless cosmetic-FX NPE — a
     * {@link NullPointerException} whose message contains {@link #HEADLESS_COSMETIC_FX_NPE_MARKER}.
     * Such a throwable is safe to swallow because measurement proved the authoritative damage
     * has already landed before it is thrown. Any other throwable is a real failure.
     *
     * <p>Pass the <em>unwrapped</em> cause (see {@link #unwrapReflectiveCause}); the actual
     * call site is reflective so the cosmetic NPE typically arrives wrapped in an
     * {@link InvocationTargetException}.
     */
    public static boolean isHeadlessCosmeticFxNpe(Throwable cause) {
        return cause instanceof NullPointerException
                && cause.getMessage() != null
                && cause.getMessage().contains(HEADLESS_COSMETIC_FX_NPE_MARKER);
    }

    /**
     * Unwraps the engine-thrown cause from the reflective wrapper. The
     * {@code applyDamage} call is invoked via reflection, so the engine's real throwable
     * (the cosmetic-FX NPE) arrives as the {@link InvocationTargetException#getCause()}.
     * Returns the wrapped cause when present, otherwise the throwable unchanged.
     */
    public static Throwable unwrapReflectiveCause(Throwable thrown) {
        if (thrown instanceof InvocationTargetException && thrown.getCause() != null) {
            return thrown.getCause();
        }
        return thrown;
    }

    @Override
    public boolean apply(ServerAuthoredProjectileImpact impact) {
        boolean cosmeticFxSwallowed = false;

        // Phase 1 (apply, own try-scope): invoke the engine's authoritative applyDamage.
        try {
            engineApply.applyToEngine(impact);
        } catch (Throwable applyFailure) {
            // Phase 2 (classify + swallow): the engine applies armor/hull damage and THEN
            // throws the cosmetic-FX NPE, so a swallowed NPE means the damage already landed.
            Throwable cause = unwrapReflectiveCause(applyFailure);
            if (isHeadlessCosmeticFxNpe(cause)) {
                cosmeticFxSwallowed = true;
                cosmeticFxNpeSwallowedCount++;
                if (firstSwallowedFxDetail.isEmpty()) {
                    firstSwallowedFxDetail = describe(cause);
                }
            } else {
                // Any other throwable is a real failure: fail-closed, leave nothing applied.
                if (firstSinkError.isEmpty()) {
                    firstSinkError = describe(cause);
                }
                return false;
            }
        }

        // The authoritative damage has landed (clean apply, or after a swallowed cosmetic FX).
        appliedCount++;

        // Phase 3 (read result, separate try-scope): never un-applies a landed hit.
        if (outcomeReader != null) {
            try {
                outcomeReader.readOutcome(impact, cosmeticFxSwallowed);
            } catch (Throwable readFailure) {
                // Tolerated: the ship may have been removed from the engine repo on death.
                // The kill still counts (it left the live set); the impact stays applied.
                if (firstOutcomeReadError.isEmpty()) {
                    firstOutcomeReadError = describe(unwrapReflectiveCause(readFailure));
                }
            }
        }
        return true;
    }

    /** Impacts whose authoritative damage landed (clean apply, or after a swallowed cosmetic FX). */
    public long appliedCount() {
        return appliedCount;
    }

    /** Count of impacts whose headless cosmetic-FX NPE was swallowed after the damage landed. */
    public long cosmeticFxNpeSwallowedCount() {
        return cosmeticFxNpeSwallowedCount;
    }

    /** First non-cosmetic apply failure that fail-closed an impact, or {@code ""} if none. */
    public String firstSinkError() {
        return firstSinkError;
    }

    /** Detail of the first swallowed cosmetic-FX NPE (for evidence), or {@code ""} if none. */
    public String firstSwallowedFxDetail() {
        return firstSwallowedFxDetail;
    }

    /** First tolerated outcome-read failure (e.g. post-kill removal), or {@code ""} if none. */
    public String firstOutcomeReadError() {
        return firstOutcomeReadError;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getClass().getName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }
}
