package cc.starsector.server.transform;

/**
 * The ONE definition of both RNG-pin knob triples (sysprop / env / config-file
 * key) plus the seed's honest "not supplied" marker. Lives in the transform package deliberately:
 * {@code server/build.ps1} bundles {@code cc/starsector/server/transform} into
 * {@code cc-premain.jar}, so {@code CcTransformPremain} (which must resolve the master seed BEFORE
 * any runtime class is reachable) and the runtime consumers ({@code MatchRecordMeta} record side,
 * {@code CombatRngPinBoot} drive side) all read the same constants — a one-sided key rename is
 * structurally inexpressible (the step-1 adversarial-review lesson, extended to the keyed knob).
 *
 * <p>Resolution (the default-ON switch): on a POSTURED boot (a loadable {@code -Dcc.server.config}
 * file) the premain resolves the FULL triple of every pin knob itself — sysprop over env over file-row — through
 * its own bounded properties read (keys + byte cap mirrored in {@code PremainConfigFile}), mints a fresh master seed when none is supplied
 * ({@code CcTransformPremain.resolvePosturedSeed}) and publishes the decision as {@link #SEED_SYSPROP} /
 * {@link #KEYED_SYSPROP} so the runtime consumers read the arming JVM's truth. Only a FILE-LESS boot falls back
 * to sysprop / env alone (no file row exists to read). Runtime consumers still resolve the full triple; the
 * premain's sysprop publication makes both readings agree on a postured boot.
 */
public final class RngPinKnobs {

    /** Shallow pin + HOOK-0044 master seed. Unset / {@value #UNPINNED} = OFF. */
    public static final String SEED_SYSPROP = "cc.rngpin.seed";
    public static final String SEED_ENV = "CC_RNGPIN_SEED";
    public static final String SEED_FILE_KEY = "rngpin.seed";
    /** The honest "not supplied" marker recorded (and accepted) when no seed was set. */
    public static final String UNPINNED = "unpinned";

    /**
     * Deep keyed ctor-pin REQUEST knob (default false). Requesting it does not arm anything —
     * arming is the premain's HOOK-0044 retransform; {@code CombatRngPinBoot} fail-fasts when the
     * request and the actual armed state disagree in either direction.
     */
    public static final String KEYED_SYSPROP = "cc.rngpin.keyed";
    public static final String KEYED_ENV = "CC_RNGPIN_KEYED";
    public static final String KEYED_FILE_KEY = "rngpin.keyed";

    /**
     * The host-facing pin SWITCH (default TRUE): on a POSTURED
     * boot (a readable {@code -Dcc.server.config} file) the premain adds {@link #PIN_IDS} to the enable set and
     * mints the master seed unless this reads false; resolution sysprop > env > file row > true. Registered as a
     * BEHAVIOR row, so the FILE row is the host's OFF lever (a tagless sysprop override refuses on a postured boot)
     * and the behavior hash carries the pin mode — a reel's identity names whether it was pinned.
     */
    public static final String ENABLED_SYSPROP = "cc.rngpin.enabled";
    public static final String ENABLED_ENV = "CC_RNGPIN_ENABLED";
    public static final String ENABLED_FILE_KEY = "rngpin.enabled";

    /**
     * The default-ON pin set the premain adds on a postured boot: the
     * keyed {@code Random} ctor pin, the uid-mint and landing-location redirects, the threat-table order swap. Ids
     * as registered in {@code TransformRegistry}; the enable set is a {@code LinkedHashSet}, so an explicit list
     * that already names them is unchanged.
     */
    public static final java.util.List<String> PIN_IDS =
            java.util.List.of("HOOK-0044", "HOOK-0049", "HOOK-0050", "HOOK-0051");

    /**
     * The {@code Math.random()} caller CENSUS window, in sim ticks (premain-read,
     * sysprop / env only, no file tier). Unset = OFF (the shipping default: no transformer registered, no
     * per-frame cost beyond one boolean read). A diagnostic that needs the keyed pin's holder append.
     */
    public static final String CENSUS_SYSPROP = "cc.rngpin.census";
    public static final String CENSUS_ENV = "CC_RNGPIN_CENSUS";
    /**
     * The census's per-site STACK capture — comma-separated site-name
     * substrings; a counted draw at a matching site prints its caller frames. Needs the census window
     * (refused alone). Premain-read, sysprop / env only, no file tier; unset = OFF.
     */
    public static final String CENSUS_STACK_SYSPROP = "cc.rngpin.censusStack";
    public static final String CENSUS_STACK_ENV = "CC_RNGPIN_CENSUS_STACK";

    private RngPinKnobs() {
    }
}
