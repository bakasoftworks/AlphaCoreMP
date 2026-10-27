package cc.starsector.client;

/**
 * The client-log size cap's pure law: the numbers and the two
 * row texts {@link CappedSidelogAppender} writes. log4j-free and game-free, so a selftest reads them
 * without a logger.
 *
 * <p><b>Why a cap.</b> The client log is capped before the release.
 *
 * The default sidelog
 * ({@code cc-client.log}, the file a stranger's game writes) was MEASURED at 6.0–7.3 MB / min per
 * window in default mode and 8.8–9.6 MB / min in debug mode,
 * about 400 MB / h with no in-session bound.
 *
 * <p><b>Why restart IN PLACE and not log4j's own size roll.</b> log4j 1.2.9's {@code rollOver()}
 * deletes {@code .1}, ignores {@code renameTo}'s result, then truncates the live file. But
 * {@code cc-client.log.1} must stay "the previous session": {@code joiner-debrief.ps1} sends only
 * the live file on that premise, and the harness readers stitch that {@code .1} shape. So at the cap
 * the live file is truncated and reopened under its OWN name (no rename, no delete), the session's
 * pinned head is re-emitted, and ONE {@link #markerRow} says how much was dropped.
 *
 * <p>No wire field, and the DEFAULT lane has no knob: the player's disk is not match behaviour (the
 * server-settable rule governs match config). The one knob is the EXPLICIT lane's
 * {@link #explicitCapChars}: a host's own console starts its debug-watch windows on this PC and hands each
 * one its cap through the process environment, never over the wire. Units are CHARS: log4j's
 * {@code CountingQuietWriter} counts {@code String.length()}, while the file's bytes follow the JVM's
 * native charset — about 1× for the ASCII rows, more only for folded peer text.
 */
final class SidelogCapLaw {

    /** The live file's restart threshold in chars (&gt; 0): 96 MiB ≈ 10.5–16.6 min of rows at the
     *  measured rates; disk ≤ 2 × (cap + one row) ≈ 201 MB with {@code .1}. */
    static final long CAP_CHARS = 96L << 20;

    /** The pinned head bound in chars (≥ 0): the session's first rows — the {@code sidelog OPEN} and
     *  wall-clock anchor rows, the kit-launch rows {@code joiner-debrief.ps1} greps (about 11.7 KB in)
     *  — re-emitted after every restart. */
    static final int HEAD_MAX_CHARS = 64 << 10;

    /** After a failed truncate the next attempt waits cap / 16 more chars — never a truncate per row. */
    static final int RETRY_STEP_DIVISOR = 16;

    /** Truncate failures in a row before the appender stops writing: 8 × cap / 16 bounds the live
     *  file at about 1.5 × cap — the disk bound outranks the tail. */
    static final int MAX_TRUNCATE_FAILURES = 8;

    /**
     * The EXPLICIT lane's cap knob: the environment key a host's
     * console sets on each debug-watch window it starts, its value a whole number of MiB in
     * {@link #EXPLICIT_MIN_MIB}..{@link #EXPLICIT_MAX_MIB}. WHY a cap there at all: those windows write about
     * 200 MB per hour each with every row kept, so a stranger host's long debug flight would otherwise fill
     * the disk. WHY a knob and not {@link #CAP_CHARS}: a debug flight is graded from its log, and 96 MiB
     * keeps only about half an hour of it. Key absent = the plain uncapped tooling file (the run tooling owns
     * that file's lifecycle and never sets the key).
     */
    static final String EXPLICIT_CAP_ENV = "CC_WATCH_SIDELOG_MAX_MIB";
    /** The knob's smallest accepted value in MiB (= {@link #CAP_CHARS}' size). */
    static final int EXPLICIT_MIN_MIB = 96;
    /** The knob's largest accepted value in MiB (64 GiB). */
    static final int EXPLICIT_MAX_MIB = 65536;
    /** {@link #explicitCapChars}' answer for an absent key: no cap, the plain appender. */
    static final long EXPLICIT_UNCAPPED = -1L;

    private SidelogCapLaw() {
    }

    /**
     * The explicit lane's restart threshold in chars from the knob's raw value ({@code System.getenv}):
     * null (absent) = {@link #EXPLICIT_UNCAPPED}; a whole number of MiB in range (surrounding blanks
     * ignored) = that many MiB in chars (&gt; 0, at most 2^36); anything else present — blank, a sign, a
     * fraction, junk or out of range — = {@link #CAP_CHARS}, so a garbled key still caps the file (fail
     * closed) instead of leaving it unbounded.
     */
    static long explicitCapChars(String mibOrNull) {
        if (mibOrNull == null) {
            return EXPLICIT_UNCAPPED;
        }
        String v = mibOrNull.trim();
        if (v.isEmpty() || v.length() > 5) {
            return CAP_CHARS;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return CAP_CHARS;
            }
        }
        int mib = Integer.parseInt(v);
        return mib >= EXPLICIT_MIN_MIB && mib <= EXPLICIT_MAX_MIB ? ((long) mib) << 20 : CAP_CHARS;
    }

    /** Restart once the live count reaches the threshold; a non-positive threshold never restarts
     *  (both args are char counts, count ≥ 0). */
    static boolean shouldRestart(long countChars, long capChars) {
        return capChars > 0L && countChars >= capChars;
    }

    /** The ONE row written after the re-emitted head: bounded tokens only (a prefix from
     *  {@link PilotWatchLog}'s fixed label set, non-negative counts), greppable on
     *  {@code sidelog RESTART}. {@code droppedChars} = this restart's dropped chars, not a running total. */
    static String markerRow(String prefix, int restartNo, long droppedChars, int headRows, long capChars) {
        return (prefix == null ? "" : prefix) + "sidelog RESTART n=" + restartNo
                + " dropped_chars=" + droppedChars + " head_rows=" + headRows + " cap_chars=" + capChars
                + " (size cap: rows between the head above and this row were dropped)";
    }

    /** The row written when a stalled appender (its file could not be reopened) writes again;
     *  {@code droppedRows} = the appender's running total of rows it could not write (≥ 0). */
    static String resumedRow(String prefix, long droppedRows) {
        return (prefix == null ? "" : prefix) + "sidelog RESUMED dropped_rows=" + droppedRows
                + " (the log file could not be opened for a while)";
    }
}
