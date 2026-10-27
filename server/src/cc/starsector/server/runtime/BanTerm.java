package cc.starsector.server.runtime;

import java.util.Locale;

/**
 * The W16 chat-ban TERM ladder — the closed token set a
 * {@code /ban #id [<term>]} or a {@code /banned #n <term>} retime accepts, the wall-clock
 * {@code until} a term mints, and the ≤ 5-char time-left suffix the KIND_UNBAN listing appends to
 * its target label.
 *
 * <p>Laws: the set is CLOSED ({@code 30m 1d 1w 1mo perm}; {@code 1mo} = 30 days); a token outside
 * printable ASCII is refused BEFORE the {@link Locale#ROOT} fold, and there are NO aliases
 * ({@code 1m}, {@code 30d}, {@code permanent}, {@code lift} = null, the caller's usage reply). A bare
 * {@code /ban #id} takes {@link #DEFAULT} (one day). {@link #PERM} carries
 * {@code millis == 0} = no expiry ({@code until == 0} on the {@link BanSet.Entry}). The parse does
 * NOT strip: the chat tokenizer already split on whitespace, so a padded token is not a term.
 */
public enum BanTerm {
    M30("30m", 1_800_000L),
    D1("1d", 86_400_000L),
    W1("1w", 604_800_000L),
    MO1("1mo", 2_592_000_000L),
    PERM("perm", 0L);

    /** A bare {@code /ban #id} (1 day). */
    public static final BanTerm DEFAULT = D1;
    /** The help / usage wording of the ladder (every token, in ladder order). */
    public static final String HELP_TOKENS = "30m 1d 1w 1mo or perm";

    /** The one operator/chat token (lowercase ASCII). */
    public final String token;
    /** The term length in ms; {@link #PERM} = 0 (none). */
    public final long millis;

    BanTerm(String token, long millis) {
        this.token = token;
        this.millis = millis;
    }

    /** The term {@code tok} names, or null: null/blank, any char outside 0x20..0x7e, or not
     *  EXACTLY one ladder token after the {@link Locale#ROOT} fold (no strip, no alias). */
    public static BanTerm parseOrNull(String tok) {
        if (tok == null || tok.isBlank()) {
            return null;
        }
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return null;
            }
        }
        String t = tok.toLowerCase(Locale.ROOT);
        for (BanTerm term : values()) {
            if (term.token.equals(t)) {
                return term;
            }
        }
        return null;
    }

    /** The entry's wall-clock {@code until} for a ban (or retime) made at {@code wallNow}:
     *  {@link #PERM} = 0 (permanent), else {@code wallNow + millis}. */
    public long untilFrom(long wallNow) {
        return this == PERM ? 0L : wallNow + millis;
    }

    /**
     * The KIND_UNBAN target-label suffix: ALWAYS ≤ 5 chars INCLUDING its leading space (the label
     * is 19 chars and the LSTD target-label cap is 24 — {@code ListingFrameCodec}
     * {@code TARGET_LABEL_BUFFER_BYTES}; a longer label throws on the reader thread).
     * {@code until == 0} = {@code " perm"}; remaining ≤ 0 = {@code " ~0m"}; under 100 min =
     * {@code " ~NNm"}; under 100 h = {@code " ~NNh"}; ≤ 99 d = {@code " ~NNd"} (all floors); else
     * {@code " >99d"} (a hand-edited far-future {@code until=} included — never a wider number).
     */
    public static String remainingLabel(long until, long wallNow) {
        if (until == 0L) {
            return " perm";
        }
        if (until <= wallNow) {
            return " ~0m";
        }
        long remaining = until - wallNow;
        if (remaining <= 0L) {
            return " >99d"; // the subtraction overflowed: until is past any 99 d horizon
        }
        if (remaining < 100L * 60_000L) {
            return " ~" + remaining / 60_000L + "m";
        }
        if (remaining < 100L * 3_600_000L) {
            return " ~" + remaining / 3_600_000L + "h";
        }
        if (remaining <= 99L * 86_400_000L) {
            return " ~" + remaining / 86_400_000L + "d";
        }
        return " >99d";
    }
}
