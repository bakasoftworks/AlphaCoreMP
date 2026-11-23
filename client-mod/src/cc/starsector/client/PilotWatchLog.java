package cc.starsector.client;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * Fail-safe logging seam for the faithful-pilot two-window watch.
 *
 * <p>Why this exists: the window's {@link PilotIntentCombatPlugin#runPilotLoop()} used to SWALLOW the
 * exception that ends the per-tick exchange, so every "window exchanged 0 frames" diagnosis was
 * server-side inference. This
 * routes the swallowed throwable + the session lifecycle into a log the next WATCH captures with the
 * EXACT window-side failure.
 *
 * <p><b>Why a dedicated, pre-warmed logger rather than ad-hoc {@code Logger.getLogger} at the call
 * site.</b> The engine installs NO SecurityManager and
 * has no combat-time gate on file ops; {@code com.fs.starfarer.loading.scripts.B} is a NAME-filtering
 * ClassLoader that refuses a mod class whose constant pool names a {@code java.io} file type or
 * {@code java.lang.reflect} (the build's b-deny gate), at any time. log4j's own classes
 * resolve through log4j's loader, so its appenders do the file work this mod's classes may not name.
 * This class is still force-loaded at application load (its first call is from
 * {@link CcClientModPlugin#onApplicationLoad()}) so the logger and its sidelog exist before the first
 * combat row, and every method is wrapped so a logging failure can NEVER break the watch.
 * If the log4j call ever fails it falls back to {@code System.out}/{@code System.err} (which Starsector
 * redirects into the same log), so a message is never silently lost. The one exception is a row the
 * write gate ({@link LogRowGateLaw}) refuses: it is dropped and COUNTED, never re-routed.
 *
 * <p><b>Log divorce.</b> This logger no longer tees into the
 * game's shared {@code starsector.log}: {@link #attachSidelog(Logger)} attaches a sidelog appender on
 * EVERY launch (an explicit watch-composed path, else the mod-owned rolling default
 * {@code cc-client.log} beside {@code starsector.log}) and a successful attach cuts the logger's
 * additivity, so players troubleshooting their OTHER mods never wade through this mod's rows — a
 * PRODUCT requirement, not tooling hygiene. Exactly one breadcrumb row (the {@code sidelog OPEN}
 * line) still lands in {@code starsector.log}, pointing at where the rows went. If NO sidelog can be
 * attached the cut is skipped (rows keep landing in the shared log — losing them entirely would be
 * worse) and one line says so. {@code CC_WATCH_SIDELOG=off} keeps the legacy everything-into-
 * starsector.log shape as a debugging escape hatch. {@code none} — no forced logs when the player
 * opts out — is the true
 * player-facing OFF: no sidelog file, and the logger level is forced to OFF so no row lands
 * anywhere — exactly one breadcrumb line ({@code sidelog DISABLED (player choice)}) goes to
 * starsector.log first so a host reading a debrief-less player's game log knows why. The kit
 * join window's "Save session logs" checkbox rides here as {@code -Dcc.watch.sideLog=none}.
 *
 * <p><b>The player's OFF wins over a host's debug bit.</b> A WELCOME
 * carrying {@code FLAG_CLIENT_DEBUG} bit1 used to force the DEFAULT lane open over {@code none}
 * (built so a debug watch had its own sidelog); that force-open
 * is DELETED — a stranger's host could switch a player's log back on, against the player's
 * choice. A debug
 * watch keeps its sidelog by the tooling lane: an armed kit is handed {@code CC_WATCH_SIDELOG} and
 * its launcher then never sends {@code none}.
 * Security law:
 * the sidelog stays a write-only sink —
 * fixed path (never wire-derived), bounded default, log4j 1.x (no message-lookup), no network
 * appenders.
 */
public final class PilotWatchLog {

    /** Tag every line so the WATCH log is greppable: {@code rg "CC-PILOT-WATCH" <sidelog>}. */
    static final String TAG = "[CC-PILOT-WATCH] ";

    /**
     * Seat discriminator: two rendered windows on one box share ONE
     * {@code starsector.log}, so every line must say WHICH window wrote it. Resolved once at class
     * load (this class is force-loaded at application load, after the launcher set the env) from
     * {@code cc.predict.shipId} / {@code CC_PREDICT_SHIP_ID} — the same knob that seats the predict
     * session. Seat 1 (and any resolution failure) keeps the bare TAG so every existing grep,
     * acceptance pattern, and single-window archive stays BYTE-IDENTICAL; only a seat-2+ window
     * appends its token: {@code [CC-PILOT-WATCH] [seat2] ...}.
     */
    static final long SEAT = resolveSeat();

    /** Mutable (volatile) since AUTO seat — knob 0 labels {@code [auto]} until
     * the predict connect adopts the server-assigned seat ({@link #adoptSeat}), then the concrete
     * token. Seat 1 (explicit, or any resolution failure) keeps the bare TAG byte-identical, and
     * an explicit seat-N window never changes label. Written once by the connect daemon, read per
     * line — a line racing the adoption just keeps the {@code [auto]} label, still greppable. */
    private static volatile String prefix =
            SEAT == 1L ? TAG : SEAT == 0L ? TAG + "[auto] " : TAG + "[seat" + SEAT + "] ";

    /** Adopt the server-assigned seat under the AUTO knob ({@code cc.predict.shipId=0}) — the
     * label flip; explicit-seat windows and non-positive ids no-op. */
    public static void adoptSeat(long shipId) {
        if (SEAT == 0L && shipId > 0L) {
            prefix = shipId == 1L ? TAG : TAG + "[seat" + shipId + "] ";
        }
    }

    private static final Logger LOG = init();

    private PilotWatchLog() {
    }

    private static long resolveSeat() {
        try {
            String raw = System.getProperty("cc.predict.shipId");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PREDICT_SHIP_ID");
            }
            if (raw == null || raw.isBlank()) {
                return 1L;
            }
            return Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return 1L; // unreadable seat = seat 1 (fail-closed: the byte-identical legacy prefix)
        }
    }

    private static Logger init() {
        try {
            Logger logger = Logger.getLogger("cc.pilot.watch");
            // Force INFO so lifecycle lines are not suppressed by an ancestor logger's level; the
            // game's file appender accepts what the logger passes (additivity to the root appender).
            logger.setLevel(Level.INFO);
            if ("none".equalsIgnoreCase(sidelogKnob())) {
                // Player logs-off: breadcrumb FIRST (additivity still on, so this
                // one row reaches starsector.log), then Level.OFF — every later info()/error()
                // fails isEnabled before any appender traversal: no rows, no file, no log4j
                // no-appender warning. The logger object stays non-null so call sites never
                // take the System.out fallback (which the game also tees into starsector.log).
                emit(logger, Level.INFO, "sidelog DISABLED (player choice; cc.watch.sideLog=none)", null, true);
                logger.setLevel(Level.OFF);
                return logger;
            }
            if (attachSidelog(logger)) {
                // The unconditional additivity cut: with a sidelog live, this mod's
                // rows never land in the players' starsector.log.
                logger.setAdditivity(false);
            }
            return logger;
        } catch (Throwable t) {
            return null; // logging is best-effort; never fatal
        }
    }

    /**
     * The log fix + log divorce:
     * attach a mod-OWNED sidelog appender, return whether one is live (the
     * caller cuts additivity on success). Two lanes:
     *
     * <ul>
     * <li><b>Explicit</b> — {@code -Dcc.watch.sideLog} (launcher-composed, per-window unique) or
     * {@code CC_WATCH_SIDELOG} env: a PLAIN {@code FileAppender} (unbounded; {@link CountingFileAppender}
     * adds only the write gate's slot release and a counted catch) — watch archives need
     * every line and the run tooling owns the file lifecycle. No game-side roll can touch it (the
     * ~10 MB starsector.log roll destroyed early one-shots + the owner's F9 markers, two runs
     * running — the trigger). With {@value SidelogCapLaw#EXPLICIT_CAP_ENV} also set (a
     * package console's debug watch sets it on each window it starts) the same file is a
     * {@link CappedSidelogAppender} at {@link SidelogCapLaw#explicitCapChars} — restart in place, and
     * NO load-time rotate, since the file is per flight. </li>
     * <li><b>Default</b> (path unset — casual play, kit joiners): {@code cc-client.log} in the
     * game's own logs dir ({@code com.fs.starfarer.settings.paths.logs} — beside
     * {@code starsector.log}: guaranteed writable, and where a troubleshooting player already
     * looks). Players never manage this file, so it is bounded two ways:
     * a PER-BOOT rotation (ONE load-time {@code rollOver()}: {@code cc-client.log} =
     * the current session, {@code .1} = the previous one, the {@code .1} shape the harness readers
     * already stitch) and an in-session size cap — {@link CappedSidelogAppender} restarts the live
     * file IN PLACE at {@link SidelogCapLaw#CAP_CHARS} (truncate, the pinned head re-emitted, one
     * marker row), never a rename roll, so {@code .1} stays the previous session.</li>
     * </ul>
     *
     * <p>Opened here, at application load (the class-header rationale; there is no SecurityManager);
     * immediateFlush so a window crash cannot eat the tail. {@code off} = no sidelog AND no additivity cut (the legacy
     * everything-shared escape hatch). Fail-soft: any failure logs ONE line, returns false, and the
     * shared-log lane continues untouched.
     */
    /** The one knob read ({@code -Dcc.watch.sideLog}, env fallback): a path, {@code off}
     * (legacy merge-back escape hatch), {@code none} (the true player OFF), or null. */
    private static String sidelogKnob() {
        try {
            String value = System.getProperty("cc.watch.sideLog");
            if (value == null || value.isBlank()) {
                value = System.getenv("CC_WATCH_SIDELOG");
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean attachSidelog(Logger logger) {
        String path = null;
        try {
            path = sidelogKnob();
            if (path != null && "off".equalsIgnoreCase(path.trim())) {
                return false; // legacy escape hatch: rows stay in starsector.log
            }
            // Layout mirrors the game's own appender ("millis [thread] LEVEL logger - msg") so every
            // existing archive consumer/parser reads sidelog lines byte-compatibly (%r = ms since
            // log4j init, the same clock the shared starsector.log lines carry).
            org.apache.log4j.PatternLayout layout =
                    new org.apache.log4j.PatternLayout("%r [%t] %-5p %c - %m%n");
            org.apache.log4j.FileAppender sidelog;
            boolean explicit = path != null && !path.isBlank();
            long explicitCap = SidelogCapLaw.EXPLICIT_UNCAPPED;
            if (explicit) {
                path = path.trim();
                // The debug-watch cap. Key absent = the plain
                // tooling file, unchanged; present = restart in place at the host's cap (a garbled
                // value caps at CAP_CHARS, never unbounded). NO rollOver(): the file is one flight's
                // (the bat's rejoin pass appends to the same file, so a load-time rotate would move
                // the flight's own rows to .1), and maximumFileSize stays Long.MAX_VALUE so log4j's
                // own rename roll never fires.
                explicitCap = SidelogCapLaw.explicitCapChars(System.getenv(SidelogCapLaw.EXPLICIT_CAP_ENV));
                if (explicitCap > 0L) {
                    CappedSidelogAppender capped = new CappedSidelogAppender(layout, path, true, explicitCap,
                            SidelogCapLaw.HEAD_MAX_CHARS, prefix, LogRowGateLaw.BOOT);
                    capped.setMaximumFileSize(Long.MAX_VALUE);
                    sidelog = capped;
                } else {
                    // log4j's plain FileAppender plus the ring
                    // gate's slot back in a finally and a counted catch (CountingFileAppender).
                    sidelog = new CountingFileAppender(layout, path, true, LogRowGateLaw.BOOT);
                }
            } else {
                // Pure string path composition — NO java.io.File: loader B
                // (com.fs.starfarer.loading.scripts.B, a name-filtering ClassLoader) refuses a mod
                // class whose constant pool names a java.io file type (the build's
                // check-b-deny-refs gate enforces it). Java file APIs (and log4j's appender
                // underneath) accept the forward slash on Windows.
                // Bounded two ways. ONE load-time rollOver()
                // here (live -> .1, fresh live file; .1 = the previous session, the shape every
                // harness reader stitches), then an in-session size cap: CappedSidelogAppender
                // restarts the live file IN PLACE at SidelogCapLaw.CAP_CHARS (truncate, the pinned
                // head re-emitted, one marker row) — never log4j's rename roll, which deletes .1.
                // maximumFileSize stays Long.MAX_VALUE so that native roll can never fire. The size
                // check runs on the async wrapper's Dispatcher thread, never the render thread.
                // The old "no mid-session file op: the combat SecurityManager throws" premise was
                // false — no SecurityManager exists (the measured throw was loader B's own;
                // an in-combat open measured fine); what stays true is that a FAILED open
                // nulls log4j's writer, which CappedSidelogAppender recovers from.
                String logsDir = System.getProperty("com.fs.starfarer.settings.paths.logs", ".");
                path = logsDir + "/cc-client.log";
                CappedSidelogAppender rolling = new CappedSidelogAppender(layout, path, true,
                        SidelogCapLaw.CAP_CHARS, SidelogCapLaw.HEAD_MAX_CHARS, prefix, LogRowGateLaw.BOOT);
                rolling.setMaximumFileSize(Long.MAX_VALUE);
                rolling.setMaxBackupIndex(1);
                rolling.rollOver();
                sidelog = rolling;
            }
            sidelog.setName("cc-watch-sidelog");
            sidelog.setImmediateFlush(true);
            // Async decouple — the file appender's write+flush used to
            // run on every LOGGING thread (dominantly the render thread). The flush measured
            // as page-cache-only (NOT the stutter mechanism); this removes the variable entirely:
            // a writeback-throttled write() can never stall a frame. log4j 1.2.9's AsyncAppender
            // has NO setBlocking (1.2.16+), so a FULL ring blocks the caller — so the ring is never
            // allowed to fill: emit() reserves an in-flight slot per row and drops (counted) past
            // LogRowGateLaw.RING_SOFT, the file appender gives the slot back after the Dispatcher's pop.
            //The dispatcher drains continuously
            // (its Dispatcher thread is a daemon, spawned HERE at application load; it also runs the
            // capped lanes' size check + in-place restart, off the render thread). Tail safety: {@link #closeSidelog()} drains the ring at the
            // cc-quit-bye hook; disclosed residue — a hard JVM abort loses the in-ring rows
            // (normally ~0; the pre-async lane lost ≤1 row on the same abort).
            org.apache.log4j.AsyncAppender async = new org.apache.log4j.AsyncAppender();
            async.setName("cc-watch-sidelog-async");
            async.setBufferSize(4096);
            async.addAppender(sidelog);
            logger.addAppender(async);
            ASYNC_SIDELOG = async;
            // Logged BEFORE the caller cuts additivity: the ONE breadcrumb starsector.log keeps,
            // pointing anyone reading it at where this mod's rows went. Both attach rows are PRIORITY
            // emits on init's LOCAL logger (LOG is still null here).
            emit(logger, Level.INFO, "sidelog OPEN " + path + (!explicit ? " (default)"
                    : explicitCap > 0L ? " (cap_chars=" + explicitCap + ")" : ""), null, true);
            // P6 debrief pre-work (sync-campaign-tooling.md): ONE wall-clock anchor row.
            // Every row's epoch derives exactly as rowEpoch = row.%r + (anchorEpoch - anchor.%r)
            // (log4j %r and event timestamps share currentTimeMillis), so cross-artifact SECOND
            // windows (JFR, server epochMs rows) are cuttable while the ~26 sidelog consumers
            // keep their byte-identical "%r [thread]" line prefix at zero per-row cost.
            emit(logger, Level.INFO, "sidelog-wallclock-anchor epoch_ms=" + System.currentTimeMillis(), null, true);
            return true;
        } catch (Throwable t) {
            emit(logger, Level.INFO, "sidelog FAILED (rows stay in starsector.log) path=" + path + " : " + t,
                    null, true); // emit never throws
            return false;
        }
    }

    /** The live async wrapper (null when the sidelog lane is off/failed) — closed at quit. */
    private static volatile org.apache.log4j.AsyncAppender ASYNC_SIDELOG;

    /**
     * The tail flush: detach + close the async sidelog wrapper (drains the
     * ring into the file appender, then closes it). Called as the LAST act of the cc-quit-bye
     * shutdown hook — after every bye row. Detach-first so any post-close row is dropped
     * silently (additivity is already cut) instead of tripping log4j's closed-appender warning.
     * Idempotent, fail-soft, never throws.
     *
     * <p>The write gate's counts print HERE, as the sidelog's
     * last row, once a boot and even when all three are 0: {@code log-gate summary global_refused=<n>
     * ring_dropped=<n> ring_dropped_priority=<n>}. PRIORITY and exempt from the bucket (a drained bucket
     * must never hide its own evidence); emitted before the detach so the drain writes it.
     */
    public static void closeSidelog() {
        try {
            org.apache.log4j.AsyncAppender async = ASYNC_SIDELOG;
            if (async == null) {
                return;
            }
            emit(LOG, Level.INFO, LogRowGateLaw.BOOT.summaryRow(), null, true);
            ASYNC_SIDELOG = null;
            if (LOG != null) {
                LOG.removeAppender(async);
            }
            async.close();
        } catch (Throwable ignore) {
            // best-effort: a failed drain must never disturb the quit path
        }
    }

    /**
     * A DEBUG watch (WELCOME {@code FLAG_CLIENT_DEBUG}) lifts the two per-shot
     * forensic log families (the B3 eventReplay intake line + AuthoritativePresenter's rows) to
     * whole-flight capture — the shipped 80-row cap left ZERO stream-weapon wire-bearing rows in the
     * 165357 corpus, so the collimator drill had nothing per-discharge to read. Lives HERE
     * (logging policy, game-API-free — the descriptor verifier compiles MatchConfigFromWire standalone);
     * server-settable through the wire bit, never kit-baked; bit off ⇒ shipped caps byte-identical.
     * Bounded: ~250 B/row ⇒ ≤ ~50 MB at the lift.
     */
    static final int DEBUG_WATCH_SHOT_LOG_MAX = 200_000;
    /** Net-thread written once (the lift), render-thread read per event — volatile suffices. */
    private static volatile int debugWatchShotLogLift;

    /** The lifted cap (0 = no debug watch seen); consumers take max(own cap, this). */
    static int debugWatchShotLogLift() {
        return debugWatchShotLogLift;
    }

    /** {@code MatchConfigFromWire.seedFromWire} on {@code FLAG_CLIENT_DEBUG} (net thread); idempotent. */
    public static void liftForensicCapsForDebugWatch() {
        if (debugWatchShotLogLift < DEBUG_WATCH_SHOT_LOG_MAX) {
            debugWatchShotLogLift = DEBUG_WATCH_SHOT_LOG_MAX;
            info("predict shot-log cap lifted -> " + DEBUG_WATCH_SHOT_LOG_MAX
                    + " (debug watch: whole-flight B3 eventReplay + foe-fire present capture)");
        }
    }

    /**
     * Every row is
     * folded HERE, on the log4j AND the stream lane (a peer's line break must never
     * start a forged row) — {@code msg} via {@link FallbackAnnounce#singleLine}, a throwable via
     * {@link FallbackAnnounce#throwableText} (log4j never sees it raw). Frame-loop cost: one linear scan of an
     * already-built string, zero allocation when clean, beside the concat + format already on this thread.
     */
    private static String render(String msg, Throwable t) {
        try {
            String row = prefix + FallbackAnnounce.singleLine(msg, Integer.MAX_VALUE);
            return t == null ? row : row + System.lineSeparator() + FallbackAnnounce.throwableText(t);
        } catch (Throwable foldFault) {
            return prefix + "row render failed: " + foldFault.getClass().getName();
        }
    }

    public static void info(String msg) {
        emit(LOG, Level.INFO, msg, null, false);
    }

    /** {@link #info} at WARN level (same prefix, same stream
     *  fallback) — for a once-per-round alert line (the replay FP WARN), never a per-hit row. */
    public static void warn(String msg) {
        emit(LOG, Level.WARN, msg, null, false);
    }

    /** The row at ERROR; its stream fallback is {@code System.err}. */
    public static void error(String msg, Throwable t) {
        emit(LOG, Level.ERROR, msg, t, false);
    }

    /**
     * The ONE write path to the sidelog logger: {@link #info},
     * {@link #warn}, {@link #error}, the attach rows (on init's LOCAL logger: {@link #LOG} is still null
     * inside init) and the quit summary row. In order:
     * <ol>
     * <li>a logger disabled at {@code level} (the player's {@code none}) returns at once: no charge, no line;</li>
     * <li>a non-PRIORITY row is charged its exact {@code msg.length()} to {@link LogRowGateLaw}'s bucket BEFORE
     * it is rendered; refused = dropped and counted, never rendered, never sent to a stream (a stream
     * fallback would move a flood into starsector.log); a PRIORITY row skips the bucket;</li>
     * <li>while the async sidelog is live the row reserves an in-flight ring slot (limit
     * {@link LogRowGateLaw#RING_SOFT}, PRIORITY {@link LogRowGateLaw#RING_HARD}); over it = dropped and counted;
     * a logger call that throws gives the slot back;</li>
     * <li>render, then log. An admitted row with no logger, or whose logger threw, keeps the stream fallback
     * ({@code System.err} for ERROR, else {@code System.out}).</li>
     * </ol>
     * Frame-loop cost: one clock read, one CAS and one increment beside what the logger
     * call already cost; it never blocks, sleeps or allocates beyond the render. A REFUSED row costs one clock
     * read, one volatile read and one counter increment (a ring drop: the bucket's CAS plus an increment, a
     * decrement and a counter); neither renders. Never throws.
     */
    private static void emit(Logger logger, Level level, String msg, Throwable t, boolean priority) {
        emitThrough(LogRowGateLaw.BOOT, logger, level, msg, t, priority, ASYNC_SIDELOG != null);
    }

    /** {@link #emit} on an explicit gate and ring flag: the selftest's seam (a fresh gate on an injected
     *  clock, its own logger); product code calls {@link #emit}. */
    static void emitThrough(LogRowGateLaw gate, Logger logger, Level level, String msg, Throwable t,
            boolean priority, boolean ring) {
        try {
            if (logger != null && !logger.isEnabledFor(level)) {
                return;
            }
            String text = msg == null ? "null" : msg;
            if (!priority && !gate.admitGlobal(text.length())) {
                return;
            }
            boolean reserved = false;
            if (logger != null && ring) {
                if (!gate.reserveRing(priority)) {
                    return;
                }
                reserved = true;
            }
            String row = render(text, t);
            if (logger != null) {
                try {
                    logger.log(level, row);
                    return;
                } catch (Throwable logFault) {
                    gate.emitFault();
                    if (reserved) {
                        gate.releaseRing();
                    }
                }
            }
            (level == Level.ERROR ? System.err : System.out).println(row);
        } catch (Throwable fault) {
            gate.emitFault(); // a throwing clock or a dead stream: counted, never worth the caller
        }
    }
}
