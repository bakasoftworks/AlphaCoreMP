package cc.starsector.server.runtime;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;

/**
 * The hs-13 door's admitted-nonce memory
 * as ONE table: client nonce → the session id that nonce admitted and the peer it was admitted from, aged by the
 * bucket of the cookie that carried it. WHY: a replayed (or retransmitted) F2 must never admit twice — while the nonce
 * is remembered the door resends the cached WELCOME13 to the live session's own peer, re-challenges the ADMITTING peer
 * of a dead session with an ordinary COOKIE13, or stays silent; and since the table is keyed by nonce it
 * doubles as the resend index. An entry lives exactly as long as its cookie can still verify — its {@code cookieBucket}
 * and the bucket after it — so a forgotten nonce can only come back inside an F2 whose cookie no longer verifies.
 *
 * <p><b>Bound.</b> {@link #CAP} = {@link DoorSourceBudget#ADMISSION_GLOBAL_PER_SECOND} × 122, derived, never a
 * literal (an entry lives in a 120 s two-bucket span, and the budget's one-second windows open lazily, so 121
 * of them can touch that span; the 122nd window absorbs the lag of the timer's {@link #age} pass, which forgets an
 * expired bucket on its first pass AFTER the boundary, not at it). While every write sits behind the admission budget
 * and the timer ages within that window, the count across all held buckets cannot pass it; a timer stall over 1 s at
 * the full admission rate CAN still reach it, and a full table REFUSES the admit ({@link #hasRoom} false — the caller
 * answers with silence, counted) — never an eviction, which would re-open a remembered nonce to replay.
 *
 * <p><b>Threading:</b> NOT self-synchronized — its one owner, the door's version-13 handshake ({@code PidsHello13},
 * one table per door), must call EVERY method holding the door's {@code lock}: the reader thread's F2 path
 * ({@link #sessionIdOrZero} / {@link #peerOrNull} lookups, {@link #hasRoom}, {@link #put}) and the timer thread's
 * {@link #age} pass alike.
 *
 * <p><b>VALUE-DOMAIN:</b> a nonce is any signed long (0 and negatives are legal keys); {@code sessionId} is a SIGNED
 * long, never 0 (0 is {@link #sessionIdOrZero}'s "nothing remembered"); {@code cookieBucket} / {@code nowBucket} =
 * {@link DoorCookie#bucketOf} values, signed (negative is normal).
 *
 *
 *
 */
final class DoorAdmittedNonces {

    /** 1,952 at {@code ADMISSION_GLOBAL_PER_SECOND = 16} (class javadoc). */
    static final int CAP = DoorSourceBudget.ADMISSION_GLOBAL_PER_SECOND * 122;

    /** One remembered admit. {@code peer} = the source the admitting F2 came from (the door's dead-nonce re-challenge
     *  answers that source only;). */
    private static final class Entry {
        long sessionId;
        long cookieBucket;
        InetSocketAddress peer;

        Entry(long sessionId, long cookieBucket, InetSocketAddress peer) {
            this.sessionId = sessionId;
            this.cookieBucket = cookieBucket;
            this.peer = peer;
        }
    }

    private final HashMap<Long, Entry> byNonce = new HashMap<>();
    /** A LOWER bound on every held entry's {@code cookieBucket} (exact after each sweep) — lets {@link #age}, called
     * every timer tick, return without a walk until a bucket can actually expire. */
    private long oldestBucket = Long.MAX_VALUE;

    /** The session id {@code nonce} admitted, or 0 = this nonce admitted nothing we still remember. */
    long sessionIdOrZero(long nonce) {
        Entry e = byNonce.get(nonce);
        return e == null ? 0L : e.sessionId;
    }

    /**
     * The peer {@code nonce} was admitted FROM (the latest admit's source), or {@code null} = nothing remembered. The
     * door's case (ii) compares a dead nonce's sender against THIS, never a session object, so a source that is not
     * the admitting peer gets one answer whether the session lives or not.
     */
    InetSocketAddress peerOrNull(long nonce) {
        Entry e = byNonce.get(nonce);
        return e == null ? null : e.peer;
    }

    /** False at {@link #CAP}: the caller REFUSES the admit (silence, counted) — the table never evicts. */
    boolean hasRoom() {
        return byNonce.size() < CAP;
    }

    /**
     * Remembers {@code nonce} → ({@code sessionId}, {@code peer}) for the life of {@code cookieBucket}'s cookie. A
     * nonce already present is REPLACED (session id, bucket and peer: the entry names the latest admit) and the table
     * does not grow — so a replace succeeds even at the cap. {@code IllegalArgumentException("sessionId")} for a 0
     * session id; {@code NullPointerException("peer")} for a null peer; {@code IllegalStateException} for a NEW nonce
     * at {@link #CAP} (the caller asks {@link #hasRoom} first).
     */
    void put(long nonce, long sessionId, long cookieBucket, InetSocketAddress peer) {
        if (sessionId == 0L) {
            throw new IllegalArgumentException("sessionId");
        }
        if (peer == null) {
            throw new NullPointerException("peer");
        }
        Entry e = byNonce.get(nonce);
        if (e != null) {
            e.sessionId = sessionId;
            e.cookieBucket = cookieBucket;
            e.peer = peer;
        } else {
            if (byNonce.size() >= CAP) {
                throw new IllegalStateException("hs13 nonce table full");
            }
            byNonce.put(nonce, new Entry(sessionId, cookieBucket, peer));
        }
        oldestBucket = Math.min(oldestBucket, cookieBucket);
    }

    /**
     * Forgets every entry whose {@code cookieBucket < nowBucket - 1} (its cookie can no longer verify). Saturating:
     * {@code nowBucket = Long.MIN_VALUE} forgets nothing, never everything. O(1) until an entry can expire, then one
     * walk that also re-computes the oldest held bucket.
     */
    void age(long nowBucket) {
        if (nowBucket == Long.MIN_VALUE) {
            return;
        }
        long expiredBelow = nowBucket - 1;
        if (oldestBucket >= expiredBelow) {
            return;
        }
        long oldest = Long.MAX_VALUE;
        for (Iterator<Entry> it = byNonce.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.cookieBucket < expiredBelow) {
                it.remove();
            } else if (e.cookieBucket < oldest) {
                oldest = e.cookieBucket;
            }
        }
        oldestBucket = oldest;
    }

    /** Entries held now (≤ {@link #CAP}). */
    int size() {
        return byNonce.size();
    }
}
