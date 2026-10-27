package cc.starsector.server.runtime;

import cc.starsector.server.platform.HeadlessCombatContext;

/**
 * The serve-boot caller that WAKES the shallow combat-damage RNG pin
 * ({@link HeadlessCombatContext#pinGlobalCombatDamageRng}) on the PRODUCT pilot serve lane.
 * An earlier sweep found the ported pin method DORMANT — present in {@code server/src} but with no
 * serve-loop/factory/Main caller — so a "pinned" run was never actually pinned. This is the one boot
 * hook that drives it, resolved through {@link ServerConfig} (sysprop &gt; env &gt; config file &gt;
 * default) under the SAME key triple {@link MatchRecordMeta} already records into the {@code .ccwr}
 * header as {@code rng.seed}: the value that drove the pin and the value the record carries cannot
 * differ, because both sides resolve the identical knob. The coupling is STRUCTURAL — this class
 * aliases {@link MatchRecordMeta}'s one definition of the triple, so a one-sided key rename is not
 * expressible; {@code CombatRngPinBootContract} additionally confirms the drive→record behavior.
 *
 * <p><b>Default OFF.</b> The resolved default is the honest {@value #UNPINNED} marker (the exact
 * string {@link MatchRecordMeta} records when no seed was supplied); nothing is reflected, nothing is
 * reseeded, the serve path is byte-identical to the boot before this hook. It is staged
 * server-first behind this one toggle.
 *
 * <p><b>Fail-fast when set.</b> A seed that is set but unparseable throws
 * {@link IllegalArgumentException}, and a pin attempt without {@code --add-opens
 * java.base/java.lang=ALL-UNNAMED} propagates the pin's own fail-closed
 * {@code HeadlessCombatContextException} — the boot exits 1 rather than silently running an
 * unpinned "determinism" measurement (the same posture as the two-sided predict knobs: invalid
 * values THROW). {@code run-real-pilot-server.ps1} already launches with the required add-opens.
 *
 * <p><b>Placement.</b> Called AFTER binding/roster construction and immediately BEFORE the serve
 * loop (scout precedent: the gate pin sits right before the pilot fire loop), so however
 * many global-stream draws engine setup consumed, the pinned stream starts from a known state at
 * serve entry. Scope: drives the SHALLOW {@code Math.random()} pin and ENFORCES (not arms) the deep
 * keyed HOOK-0044 state — the keyed {@code java.util.Random.<init>} transform itself arms in
 * {@code CcTransformPremain}; this boot fail-fasts when the
 * {@code cc.rngpin.keyed} request and the actual armed state disagree in either direction.
 */
public final class CombatRngPinBoot {

    /**
     * The one toggle (sysprop form). Unset / {@value #UNPINNED} = OFF; a long = pin to that seed.
     * Aliases of {@link MatchRecordMeta}'s single definition (see its RNG_SEED_* javadoc): the drive
     * side and the record side share ONE set of key names by construction.
     */
    public static final String SEED_SYSPROP = MatchRecordMeta.RNG_SEED_SYSPROP;
    public static final String SEED_ENV = MatchRecordMeta.RNG_SEED_ENV;
    public static final String SEED_FILE_KEY = MatchRecordMeta.RNG_SEED_FILE_KEY;
    /** The honest "not supplied" marker — the same default {@link MatchRecordMeta} records. */
    public static final String UNPINNED = MatchRecordMeta.RNG_SEED_UNPINNED;

    private CombatRngPinBoot() {
    }

    /**
     * Resolve the seed knob and pin the JVM-global combat-damage RNG when one is supplied. FIRST
     * (unconditionally — before any seed handling) enforces that the HOOK-0044
     * keyed REQUEST knob ({@code cc.rngpin.keyed} triple, strict true/false) matches the ACTUAL
     * armed state ({@code CcRngKeyed.isArmed()}): a mismatch in EITHER direction throws naming the
     * boundary — launcher misconfig (requested but the premain never armed) or manual enable drift
     * (armed but not requested) — never a silent wrong-mode "determinism" run. The serve lane
     * thereby enforces knob==reality before any sink opens, so the recorded actual state also
     * matches the request.
     *
     * @return {@code null} when the seed knob is absent/{@value #UNPINNED} (the default-OFF no-op),
     *         else a one-line boot receipt naming the seed, the keyed state, and the installed
     *         treatment descriptor.
     */
    public static String pinFromConfigOrNull() {
        boolean keyedArmed = enforceKeyedRequestMatchesArmed();
        enforceCensusRequestArmed();
        String raw = ServerConfig.resolve(SEED_SYSPROP, SEED_ENV, SEED_FILE_KEY, UNPINNED);
        String value = raw == null ? UNPINNED : raw.trim();
        if (value.isEmpty() || UNPINNED.equalsIgnoreCase(value)) {
            return null;
        }
        long seed;
        try {
            seed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    SEED_SYSPROP + " must be a long seed or '" + UNPINNED + "', got: " + raw, e);
        }
        String treatment = HeadlessCombatContext.pinGlobalCombatDamageRng(seed);
        return "rng_pin status=pinned hook=combat-damage-rng seed=" + seed + " keyed=" + keyedArmed
                + " treatment=" + treatment;
    }

    /**
     * A {@code cc.rngpin.census} request (sysprop / env, the premain's
     * own read — no file tier) in a JVM whose holder never armed the census (the rig-only
     * {@code -SkipPremain} boot, or a premain that refused silently) THROWS instead of running a census-less
     * "census" re-sim. The holder class is touched only when the knob is set — an unarmed default serve
     * never loads it here.
     */
    private static void enforceCensusRequestArmed() {
        String raw = System.getProperty(cc.starsector.server.transform.RngPinKnobs.CENSUS_SYSPROP);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(cc.starsector.server.transform.RngPinKnobs.CENSUS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (!cc.starsector.server.rngpin.CcRngRedirect.censusArmed()) {
            throw new IllegalStateException("rng_census request/armed MISMATCH: "
                    + cc.starsector.server.transform.RngPinKnobs.CENSUS_SYSPROP + "=" + raw.trim()
                    + " but the holder's census never armed — a boot without -javaagent:cc-premain.jar"
                    + " (-SkipPremain?) cannot count Math.random() sites; refusing a silent census-less run");
        }
    }

    /** @return the (verified request-matching) actual armed state. Invalid knob values THROW. */
    private static boolean enforceKeyedRequestMatchesArmed() {
        String raw = ServerConfig.resolve(cc.starsector.server.transform.RngPinKnobs.KEYED_SYSPROP,
                cc.starsector.server.transform.RngPinKnobs.KEYED_ENV,
                cc.starsector.server.transform.RngPinKnobs.KEYED_FILE_KEY, "false");
        String value = raw == null ? "false" : raw.trim();
        boolean requested;
        if ("true".equalsIgnoreCase(value)) {
            requested = true;
        } else if ("false".equalsIgnoreCase(value) || value.isEmpty()) {
            requested = false;
        } else {
            throw new IllegalArgumentException(cc.starsector.server.transform.RngPinKnobs.KEYED_SYSPROP
                    + " must be true or false, got: " + raw);
        }
        boolean armed = cc.starsector.server.rngpin.CcRngKeyed.isArmed();
        if (requested != armed) {
            throw new IllegalStateException("rng_pin keyed request/armed MISMATCH: "
                    + cc.starsector.server.transform.RngPinKnobs.KEYED_SYSPROP + "=" + requested
                    + " but HOOK-0044 armed=" + armed + " — "
                    + (requested
                            ? "requested but the premain never armed (missing -javaagent:cc-premain.jar"
                                    + " with -Dcc.transform.enable=...,HOOK-0044 + seed?)"
                            : "armed without the request knob (manual -Dcc.transform.enable drift?)")
                    + "; refusing a silent wrong-mode determinism run");
        }
        return armed;
    }
}
