package cc.starsector.server.runtime;

/**
 * The post-defeat linger law.
 *
 * <p>On the serve-ending round, a decisive ELWSS outcome with a surviving side does not end the
 * serve immediately: the loop keeps serving NORMAL frames (intents consumed, sim stepped,
 * snapshots published) for a configurable window so survivors can retreat, then fans MOVR and
 * exits as before. Client-side this needs nothing: both native-end suppressors hold while
 * snapshots flow, and the end ladder only ever drives on the (now delayed) MOVR fan.
 *
 * <p>The hold never engages when: the knob is 0 (off), the outcome has no surviving side
 * (roster-0 mutual kill, winner -1 — nobody left to retreat), or no client session is live at
 * latch time (synthetic/rig serves exit as today). It cuts short the moment the last live
 * session disappears. Operator ends and the frame budget bypass/truncate the hold at the serve
 * loop's own checks — this class only answers "arm?" and "still holding?".
 */
public final class MatchLingerLaw {

    /** Default hold, seconds: on by default. */
    public static final int DEFAULT_LINGER_SEC = 45;
    public static final int MAX_LINGER_SEC = 600;

    private final int lingerSec;
    private final int lingerTicks;
    private int holdUntilFrame = -1;

    public MatchLingerLaw(int lingerSec, double fixedDt) {
        if (lingerSec < 0 || lingerSec > MAX_LINGER_SEC) {
            throw new IllegalArgumentException("lingerSec must be in [0, " + MAX_LINGER_SEC
                    + "]; got " + lingerSec);
        }
        if (fixedDt <= 0.0d) {
            throw new IllegalArgumentException("fixedDt must be positive; got " + fixedDt);
        }
        this.lingerSec = lingerSec;
        this.lingerTicks = lingerSec == 0 ? 0 : (int) Math.ceil(lingerSec / fixedDt);
    }

    /**
     * STRICT parse of the raw knob value (reject-never-clamp, the {@code fortressDp} idiom).
     * Blank = the on-by-default {@link #DEFAULT_LINGER_SEC}; 0 = off; whole seconds only.
     */
    public static int parseLingerSec(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LINGER_SEC;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("match.lingerSec must be whole seconds in [0, "
                    + MAX_LINGER_SEC + "] (0 = off, blank = " + DEFAULT_LINGER_SEC + "); got '"
                    + raw + "'");
        }
        if (parsed < 0 || parsed > MAX_LINGER_SEC) {
            throw new IllegalArgumentException("match.lingerSec must be in [0, " + MAX_LINGER_SEC
                    + "] (0 = off); got '" + raw + "'");
        }
        return parsed;
    }

    /** The ONE ambient resolve (sysprop → env → config file → blank ⇒ default-on). */
    public static int resolveMatchLingerSec() {
        return parseLingerSec(ServerConfig.resolve(
                "cc.match.lingerSec", "CC_MATCH_LINGER_SEC", "match.lingerSec", ""));
    }

    /**
     * First decisive latch on the serve-ending round. Returns true exactly once, when the hold
     * arms (callers log then); false = no hold (off / no survivor side / empty room / re-latch).
     */
    public boolean noteDecisiveLatch(int frame, int winnerSide, boolean sessionsEmpty) {
        if (lingerTicks <= 0 || winnerSide < 0 || sessionsEmpty || holdUntilFrame >= 0) {
            return false;
        }
        holdUntilFrame = frame + lingerTicks;
        return true;
    }

    public boolean armed() {
        return holdUntilFrame >= 0;
    }

    /** Still inside the hold window with someone left to serve? */
    public boolean holding(int frame, boolean sessionsEmpty) {
        return holdUntilFrame >= 0 && frame < holdUntilFrame && !sessionsEmpty;
    }

    /** Why an armed hold ended (only meaningful once {@link #holding} turns false). */
    public String endReason(boolean sessionsEmpty) {
        return sessionsEmpty ? "sessions_empty" : "elapsed";
    }

    public int lingerSec() {
        return lingerSec;
    }

    public int lingerTicks() {
        return lingerTicks;
    }
}
