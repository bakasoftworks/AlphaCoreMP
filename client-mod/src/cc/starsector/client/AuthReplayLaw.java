package cc.starsector.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code /auth} hold → promote →
 * replay-after-re-admit law, carved VERBATIM out of {@link ChatLane} as a pure, game-free state
 * machine so {@code ChatInputLawSelftest} (rows AR1–AR14) drives it headless. No LWJGL/API imports
 * by design; the session is an opaque identity key and the WELCOME admin bit is injected.
 *
 * <p>One retry: the replay lands milliseconds
 * after the WELCOME, and when that re-admit came in on the door's KEY path the ledger is still
 * inside its 250 ms streak-0 gap, so the door HOLDS the replay and answers
 * {@link ChatInputLaw#AUTH_HELD_REPLY} (no compare ran; a hold never refreshes the gap). That
 * exact line, while the replay is the unanswered {@code /auth}, arms ONE retry of the same line
 * at least {@link #RETRY_DELAY_MILLIS} after the writer first saw it armed. A second hold ends it
 * (the retry is never itself retried), a typed line's hold arms nothing (the admin SEES the
 * constant and re-types), and a fresh re-admit cancels a retry still armed — never a loop.
 *
 * <p>Why it exists: {@code /auth} elevation is a per-SERVER-SESSION fact the door never copies on a
 * takeover, so an in-game rejoin silently lost kick/wave/settings verbs. The
 * last {@code /auth} line SENT is held (render thread), promoted to the {@link Store} when the
 * server's exact elevation reply arrives (net thread) with exactly ONE line in flight (replies
 * carry no correlation id — two in flight, or a silently budget-dropped miss, leave the accepted
 * TEXT unknown ⇒ cache nothing: the owner re-types after a rejoin, never a wrong replay), and
 * re-queued ONCE after every observed re-admit whose WELCOME carried no credential admin bit.
 * The line is held in memory only and NEVER logged (the send log prints lengths; the server's
 * audit line redacts the verb).
 *
 * <p>Two scopes, deliberately: the pending line + in-flight count are per LANE (a lane is per
 * combat, like before the carve), while the accepted line + its re-admit watermark are per
 * SESSION via the {@link Store}: the in-game rejoin (Esc → title →
 * Multiplayer Test) builds a NEW lane over the SAME kept-open session, so the accepted state must
 * outlive the lane; bound to the session OBJECT so a fresh connect never inherits a line.
 *
 * <p>Threading: {@link #noteSend} render thread; {@link #noteServerReply} net/reader thread;
 * {@link #replayDue} net/writer thread — the same three threads as the ChatLane sites that call
 * them, with the same volatile/atomic fields the ChatLane originals used.
 */
public final class AuthReplayLaw {

    /** The SESSION-scoped half: the accepted line, the session it was accepted on, and the
     *  re-admit count already answered for it. {@code ChatLane} holds ONE static instance; the
     *  session's close clears it ({@link #forget}). */
    public static final class Store {
        volatile String line;
        volatile Object session;
        volatile long reAdmits;

        public Store() {
        }

        /**
         * The session's close: when {@code closed} IS the stored session (the identity key
         * {@link AuthReplayLaw#noteServerReply} binds), drop the accepted line, the session reference and the
         * watermark, so the line no longer outlives the session it was accepted on (nor pins that object for the
         * JVM's life). Another session, or null, changes nothing. A promotion racing the close on the reader thread
         * can still re-bind the closed session; its line is then unreachable (a replay needs that same session).
         *
         */
        public void forget(Object closed) {
            if (closed != null && closed == session) {
                line = null;
                session = null;
                reAdmits = 0L;
            }
        }
    }

    /** The server's reserved author name — only ITS relay lines carry the elevation reply. */
    static final String SERVER_AUTHOR = "server";

    /** The held re-admit replay is re-sent no sooner than this after the writer
     *  first saw the retry armed — past the ledger's 250 ms streak-0 gap ({@code
     *  DoorGuessLedger.FLOOR_GAP_MILLIS}) that held it; a streak ≥ 1 (500 ms .. 8 s) holds the retry
     *  too, and that second hold ends the law. */
    public static final long RETRY_DELAY_MILLIS = 300L;

    private final Store store;
    private volatile String pendingLine;                        // the LAST /auth line sent
    private final AtomicInteger inFlight = new AtomicInteger(); // sent, not yet answered
    private int replays;                                        // auth_replays (net/writer thread)
    // The held-replay retry. replayOutstanding: the re-admit replay was queued
    // and no terminal reply (elevated / miss / held) has landed since (writer sets, reader clears).
    // retryArmed: the held replay line the writer re-sends once (reader arms, writer takes);
    // retryDueMillis: 0 until the writer stamps its own clock on first sight of the armed line.
    private volatile boolean replayOutstanding;
    private final AtomicReference<String> retryArmed = new AtomicReference<>();
    private volatile long retryDueMillis;
    private int retries;                                        // auth_replay_retries (net/writer thread)
    // The version-13 drain's LOCAL outcomes (net/writer thread writes, the
    // render thread's round line reads): auth13_unavailable / auth13_refused / auth13_retired (proof frames the
    // session dropped on a retired core) / auth13_retired_dropped (lines dropped at their second RETIRED).
    private volatile int auth13Unavailable;
    private volatile int auth13Refused;
    private volatile int auth13Retired;
    private volatile int auth13RetiredDropped;
    private String auth13RetiredOnce; // the line re-queued after its first RETIRED (identity; writer thread only)

    public AuthReplayLaw(Store store) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        this.store = store;
    }

    /**
     * Render thread, at the deposit: hold {@code line} when it is an {@code /auth} line (the
     * server grammar's STRICT shape, {@link ChatInputLaw#isStrictAuthLine}) and count it in
     * flight. Returns whether it was held — a plain chat line changes nothing.
     *
     * <p>NOT the wide {@link ChatInputLaw#isAuthLine} — the server
     * only NAMES a typo'd verb ("did you mean /auth?") and never answers it with an elevation or a
     * miss reply, so a wide hold would pin {@code inFlight} at 1 and the real {@code /auth} that
     * follows would make it 2: the elevation would never promote (admin lost after a rejoin).
     *
     */
    public boolean noteSend(String line) {
        if (!ChatInputLaw.isStrictAuthLine(line)) {
            return false;
        }
        pendingLine = line;
        inFlight.incrementAndGet();
        replayOutstanding = false; // a typed /auth supersedes the replay — its hold arms no retry
        return true;
    }

    /**
     * Net/reader thread, the ONE receive choke point: a relayed chat line by {@code author}.
     * Exact full-text equality of the server-stamped success constant on the {@code server}
     * author is the elevation (no fixed reply template embeds player text that could equal the
     * whole constant; a player who NAMES themselves {@code server} and types it flips only
     * presentation — every verb re-checks the door's session flag). Promotes the held line ONLY
     * when exactly one is in flight; a mirrored miss reply retires one in-flight line, and so does
     * the door's HELD constant — which, while the re-admit replay is the
     * unanswered line, also arms its one retry for {@link #retryDue}.
     *
     * @param session  the live session object the reply arrived on (identity key; may be null)
     * @param reAdmits that session's re-admit count at this moment (the replay watermark)
     * @return true when the line IS the elevation reply (the caller flips the presentation bit)
     */
    public boolean noteServerReply(String author, String text, Object session, long reAdmits) {
        if (!SERVER_AUTHOR.equals(author)) {
            return false;
        }
        if (MatchConfigFromWire.AUTH_ELEVATED_REPLY.equals(text)) {
            int n = inFlight.getAndSet(0);
            String sent = pendingLine;
            if (n == 1 && sent != null && session != null) {
                store.line = sent;
                store.session = session;
                store.reAdmits = reAdmits;
            }
            replayOutstanding = false; // answered (idempotent: a replayed line re-elevates)
            retryArmed.set(null);      // the seat is admin — a retry still armed is moot
            return true;
        }
        if (ChatInputLaw.AUTH_HELD_REPLY.equals(text)) {
            inFlight.updateAndGet(k -> k > 0 ? k - 1 : 0);
            if (replayOutstanding) {
                replayOutstanding = false; // the ONE retry; a hold of the retry lands here with it false
                retryDueMillis = 0L;       // before the arm: the writer stamps its clock on first sight
                retryArmed.set(store.line);
            }
            return false;
        }
        if (ChatInputLaw.isAuthMissReply(text)) {
            inFlight.updateAndGet(k -> k > 0 ? k - 1 : 0);
            replayOutstanding = false;
        }
        return false;
    }

    /**
     * Net/writer thread, ahead of every send drain: when {@code reAdmits} has advanced past the
     * accepted line's watermark on the SAME session, answer that re-admit exactly once — the
     * accepted line to re-queue at the head of the deposit, or {@code null} when the new WELCOME
     * already carried the credential admin bit ({@code seatAdmin}: the watermark still advances,
     * so a LATER re-admit without the bit replays). Nothing accepted, another session, or a
     * re-admit already answered ⇒ {@code null}. A wrong line (password re-armed mid-match)
     * bounces once server-side and spends one credential-fail budget unit, like a typed miss.
     */
    public String replayDue(Object session, long reAdmits, boolean seatAdmin) {
        String line = store.line;
        if (line == null || session == null || session != store.session) {
            return null; // nothing accepted on THIS session (a fresh connect never inherits a line)
        }
        if (reAdmits <= store.reAdmits) {
            return null;
        }
        store.reAdmits = reAdmits; // once per re-admit (a dropped send waits for the next one)
        retryArmed.set(null);      // a fresh re-admit cancels a retry still armed for the old one
        if (seatAdmin) {
            replayOutstanding = false;
            return null; // the new session's HELLO credential is admin already
        }
        replays++;
        replayOutstanding = true;
        return line;
    }

    /**
     * Net/writer thread, right after {@link #replayDue} on every send pass: the
     * ONE retry of a re-admit replay the door HELD. The first pass that sees the retry armed stamps
     * {@code nowMillis + RETRY_DELAY_MILLIS} on the WRITER's clock (the reader stays clock-free)
     * and returns {@code null}; a later pass at or past that stamp takes the line — exactly once.
     * Nothing armed, or not yet due ⇒ {@code null}.
     */
    public String retryDue(long nowMillis) {
        String line = retryArmed.get();
        if (line == null) {
            return null;
        }
        long due = retryDueMillis;
        if (due == 0L) {
            retryDueMillis = nowMillis + RETRY_DELAY_MILLIS;
            return null;
        }
        if (nowMillis < due || !retryArmed.compareAndSet(line, null)) {
            return null;
        }
        retries++;
        return line;
    }

    /**
     * Net/writer thread, the version-13 drain: {@code line} was
     * answered LOCALLY — refused ({@code refused}: never sent readable, never an attempt) or its proof could not be
     * made — so no door reply will ever retire it. Counts the meter, and retires ONE in-flight line only when
     * {@link #noteSend} counted this shape ({@link ChatInputLaw#isStrictAuthLine}; floor 0, like a miss reply): a
     * typo'd line was never counted, and retiring for it would steal the count of a real {@code /auth} still in flight.
     *
     */
    public void noteAuth13Local(String line, boolean refused) {
        if (refused) {
            auth13Refused++;
        } else {
            auth13Unavailable++;
        }
        if (line == auth13RetiredOnce) {
            auth13RetiredOnce = null;
        }
        retireIfCounted(line);
    }

    /** The version-13 drain handed {@code line}'s proof to the session (SENT): a RETIRED re-queue of it is spent. */
    public void noteAuth13Sent(String line) {
        if (line == auth13RetiredOnce) {
            auth13RetiredOnce = null;
        }
    }

    /**
     * The session dropped {@code line}'s proof on a RETIRED core (an adopt swapped it mid-send; lane iv2):
     * {@code true} = re-queue it at the head ONCE (re-proven under the new context; {@code inFlight} unchanged — the
     * line is still owed its answer); {@code false} = this line's SECOND RETIRED: drop it, count it, and retire it as a
     * local outcome (no door reply will come). Identity of the line object: the drain re-queues the same object.
     */
    public boolean noteAuth13Retired(String line) {
        auth13Retired++;
        if (line != auth13RetiredOnce) {
            auth13RetiredOnce = line;
            return true;
        }
        auth13RetiredOnce = null;
        auth13RetiredDropped++;
        retireIfCounted(line);
        return false;
    }

    private void retireIfCounted(String line) {
        if (ChatInputLaw.isStrictAuthLine(line)) {
            inFlight.updateAndGet(k -> k > 0 ? k - 1 : 0);
            replayOutstanding = false; // answered locally: a later HELD reply arms no retry for it
        }
    }

    /** {@code auth13_unavailable} — version-13 proofs that could not be made (the chat round line). */
    public int auth13Unavailable() {
        return auth13Unavailable;
    }

    /** {@code auth13_refused} — version-13 lines refused locally (the chat round line). */
    public int auth13Refused() {
        return auth13Refused;
    }

    /** {@code auth13_retired} — proofs dropped on a retired core (the chat round line). */
    public int auth13Retired() {
        return auth13Retired;
    }

    /** {@code auth13_retired_dropped} — lines dropped at their second RETIRED (the chat round line). */
    public int auth13RetiredDropped() {
        return auth13RetiredDropped;
    }

    /** {@code auth_replays} — re-admit replays queued by THIS lane (log/stat only). */
    public int replays() {
        return replays;
    }

    /** {@code auth_replay_retries} — held replays re-sent once by THIS lane (log/stat only). */
    public int retries() {
        return retries;
    }

    /** {@code /auth} lines sent and not yet answered on this lane (selftest + stat). */
    public int inFlight() {
        return inFlight.get();
    }
}
