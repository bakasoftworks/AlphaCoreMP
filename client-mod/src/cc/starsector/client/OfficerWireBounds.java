package cc.starsector.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The PURE bounds the two
 *
 * WIRE-carried officer strings must pass before they reach an engine setter. The shared grammar
 * parser ({@code OfficerRosterCodec.lineToken}) only proves they are non-empty printable ASCII
 * without '|' inside a ≤512-char line — enough to stop row/comment smuggling, NOT enough to name
 * an engine spec. Kept engine-free (String/Set only) so the build-gate smoke can pin the laws
 * without the api jar on its classpath, and B-deny-clean by construction.
 *
 * <p>Ground truth for the two defaults (game 0.98a-RC8): an unknown personality does NOT
 * throw — the person stores a spec-store lookup, which
 * returns null for an unknown id, so the Person
 * survives with a null spec and NPEs later in the engine's AI derive; and {@code createPerson()}
 * already mints "steady".
 *
 */
public final class OfficerWireBounds {

    /** The engine's OWN default personality — what {@code createPerson()} stamps before any setter. */
    public static final String DEFAULT_PERSONALITY = "steady";

    /**
     * Vanilla's personality ids ({@code data/characters/personalities.csv}).
     * Backstop for a game-free harness; at
     * runtime the engine registry is asked first so a mod-added personality still passes.
     */
    public static final Set<String> VANILLA_PERSONALITIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("timid", "cautious", "steady", "aggressive", "reckless")));

    /** Every portrait this kit can name lives under the engine's portrait root. */
    public static final String PORTRAIT_PREFIX = "graphics/portraits/";
    public static final String PORTRAIT_SUFFIX = ".png";

    /**
     * 128 chars. Measured ceiling in this install (0.98a-RC8): the longest real portrait path is 52
     * ({@code graphics/portraits/characters/*.png}; flat dir max 49, {@code bgs/} max 35) and
     * {@code client-mod/mod/} ships no graphics of its own, so 128 is ~2.5x headroom for a deeper
     * mod subdir while staying far under the 512-char officer-line cap
     * ({@code OfficerRosterCodec.MAX_LINE_CHARS}) and keeping any logged echo sane.
     */
    public static final int PORTRAIT_MAX_CHARS = 128;

    /** Bounded log echo of a rejected wire value (the "bounded echo" idiom). */
    private static final int LOG_ECHO_MAX_CHARS = 64;

    private OfficerWireBounds() {
    }

    /** Is this one of vanilla's five personality ids? */
    public static boolean isVanillaPersonality(String id) {
        return id != null && VANILLA_PERSONALITIES.contains(id);
    }

    /**
     * A portrait path this kit would itself author: the engine's portrait root, a .png leaf, ≤128
     * chars, and a stem of [A-Za-z0-9_-/] only — which admits the subdirectory rows our own tables
     * use ({@code FactionWaveTables}, e.g. graphics/portraits/characters/zig_captain.png) while
     * rejecting traversal ('..'), a backslash, an empty segment ('//'), a drive/scheme (':') and
     * every other punctuation class.
     *
     * <p>ONE copy of the check:
     * delegates to the server codec's mirror, which the server applies before minting a
     * line and which ships in this kit jar ({@code client-mod/build.ps1} pilot-class list).
     */
    public static boolean isSafePortraitPath(String path) {
        return cc.starsector.server.match.OfficerRosterCodec.isWireSafePortraitPath(path);
    }

    /** Never echo an unbounded wire string into a sink — truncate with the true length appended. */
    public static String forLog(String wire) {
        if (wire == null) {
            return "<null>";
        }
        return wire.length() <= LOG_ECHO_MAX_CHARS ? wire
                : wire.substring(0, LOG_ECHO_MAX_CHARS) + "...(" + wire.length() + " chars)";
    }
}
