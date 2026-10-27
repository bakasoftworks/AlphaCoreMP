package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PilotIntentDatagramServer.CredentialVerdict;
import cc.starsector.server.runtime.PilotIntentDatagramServer.PeerSession;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The version-13 HELLO door: the match password /
 * seat token never crosses the network readable — the joiner proves it through the CPace wraps of an F2 the door
 * opens with {@link DoorShares13#open}. Carved beside {@link PilotIntentDatagramServer} (the {@link PidsVariantSubmit}
 * shape: the door + its own {@code lock}). Armed ONLY by {@link PilotIntentDatagramServer#acceptHs13} (dual-stack
 * during the hs-13 build); an unarmed door never constructs this class.
 *
 * <p><b>Thread + lock split.</b> {@link #onHello} runs on the door's READER thread only; {@link #onTimer} on its
 * TIMER thread. Every counter, the guess ledger, the budgets, the admitted-nonce table and every send sit under the
 * door's {@code lock}; {@link DoorShares13#open}, {@link DoorShares13#newAuthShare} and {@link DoorShares13#roll}
 * (the X25519s) NEVER do — an F2 takes the lock, releases it across the open, and re-takes it (re-reading
 * {@code closed} and the ban state). Resend dedup between step 5's lookup and the admit's {@code put} rests on ONE
 * reader thread.
 *
 * <p><b>Every OPEN ends in a verdict</b>: a granted F2 that reaches the open records PASS / PASS_ADMIN /
 * FAIL with the guess ledger — except an open over an ERASED pair, which proves NOTHING: zeroed scalars
 * derive a key the SENDER can compute with no secret, so a non-null result from one is never believed, and a failed
 * one says nothing about the secret either (a lawful joiner must not read "wrong password") — that F2 is answered
 * with silence and counted {@code hs13_unserved}, no verdict — and except a door CLOSED during the open (step 7
 * re-reads {@code closed} first; the ledger dies with the door). Exits with NO open and NO verdict: the resend, a held
 * grant, the source ban / hold, the print, the no-secret bit, a token-door probe / observer, a passed key's budget
 * denial, a never-passed key's unproven-open hold ({@link DoorGuessLedger#admitOpen}).
 *
 * <p><b>No secret in text.</b> No log row, exception text or {@code toString} here carries a password, token, scalar,
 * share, root, salt or handle; the handle's hex is a guess-ledger LANE KEY only. Version-13 rejects ride the door's
 * existing sampled REJECT line (cid blank before the open). The door-built {@code HelloPayload} handed to the v12
 * tail carries in its credential slot the secret the wrap PROVED — never wire text.
 *
 *
 */
final class PidsHello13 {

    private static final byte[] EMPTY = new byte[0];
    /** F1 head {@code ver ‖ nonce ‖ flags ‖ 0}: {@code [10] == 0} = an OPEN, no credential follows. */
    private static final int OPEN_HEAD_BYTES = 11;
    private static final int CLEAR_BITS = Handshake13.FLAG_PROBE | Handshake13.FLAG_OBSERVER;

    private final PilotIntentDatagramServer door;
    /** {@code passwordLedger != null} at arm time (the door's mode is fixed for its life). */
    private final boolean passwordDoor;
    /** The secrets {@code <proven>} names: the door's own config, never wire text. */
    private final String matchPassword;
    private final String adminSecret;
    /** Token door: the minted seat tokens, SORTED (natural order) — {@code mixes[i] = secretBytes(tokens[i])}. */
    private final String[] tokens;
    private final byte[][] mixes;
    private final SecureRandom rng = new SecureRandom();
    private final DoorShares13 shares;
    /** Door lock only (its class law). */
    private final DoorAdmittedNonces nonces = new DoorAdmittedNonces();

    /**
     * Builds the proven-secret table and the share table. Password door: {@code prsMatch = secretBytes(match
     * password)}, {@code prsAdmin} from the armed admin secret (or null), {@code mixes = {EMPTY}}. Token door:
     * {@code prsMatch = EMPTY}, no admin, {@code mixes} = the minted tokens' bytes. {@code IllegalStateException} for
     * a secret that maps to ZERO bytes (it would be the PUBLIC generator / an EMPTY mix any non-holder opens)
     * or a token door with no tokens.
     */
    PidsHello13(PilotIntentDatagramServer door, String matchPassword) {
        this.door = door;
        HandshakeCrypto.warm();
        byte[] prsMatch;
        byte[] prsAdmin;
        this.passwordDoor = door.passwordLedger != null;
        if (passwordDoor) {
            this.matchPassword = matchPassword;
            this.adminSecret = door.adminPassword;
            this.tokens = null;
            prsMatch = Handshake13.secretBytes(matchPassword);
            prsAdmin = adminSecret == null ? null : Handshake13.secretBytes(adminSecret);
            if (prsMatch.length == 0 || (prsAdmin != null && prsAdmin.length == 0)) {
                throw new IllegalStateException("hs13: a blank door secret would arm the public generator");
            }
            this.mixes = new byte[][] {EMPTY};
        } else {
            this.matchPassword = null;
            this.adminSecret = null;
            if (door.seatAuthority == null) {
                throw new IllegalStateException("hs13: a token door needs a seat authority");
            }
            String[] minted = door.seatAuthority.mintedTokens().toArray(new String[0]);
            Arrays.sort(minted);
            if (minted.length == 0) {
                throw new IllegalStateException("hs13: a token door with no minted token");
            }
            byte[][] m = new byte[minted.length][];
            for (int i = 0; i < minted.length; i++) {
                m[i] = Handshake13.secretBytes(minted[i]);
                if (m[i].length == 0) {
                    throw new IllegalStateException("hs13: a blank seat token would arm an empty mix");
                }
            }
            this.tokens = minted;
            this.mixes = m;
            prsMatch = EMPTY;
            prsAdmin = null;
        }
        this.shares = new DoorShares13(prsMatch, prsAdmin, rng,
                DoorCookie.bucketOf(PilotIntentDatagramServer.nowMillis()));
    }

    /** The dispatch test: a non-empty payload whose version byte is {@link Handshake13#VERSION}. */
    static boolean isVersion13(byte[] payload) {
        return payload != null && payload.length > 0 && (payload[0] & 0xFF) == Handshake13.VERSION;
    }

    /**
     * The door's mode, fixed at arm ({@code passwordLedger != null}): VCHK13 serves a password door only. This and
     * the two members below are {@link PidsVariantCheck}'s only view of the armed door ({@code shares} and
     * {@code mixes} stay private).
     */
    boolean passwordDoor() {
        return passwordDoor;
    }

    /** {@link DoorShares13#pairOrNull} for a VCHK13's verified cookie bucket: any thread, never mints, no X25519. */
    DoorShares13.Pair vchkPairOrNull(long bucket) {
        return shares.pairOrNull(bucket);
    }

    /**
     * One VCHK13's wraps through {@link DoorShares13#openWraps} with this door's own {@code mixes} ({@code {EMPTY}}
     * on a password door): 2 X25519, constant work — READER THREAD, NEVER under the door's {@code lock} (the class
     * law above). Read {@code pairErased()} FIRST; the caller zeroes an opened {@code ck}. Throws only on a caller
     * bug (a null {@code pair}; a cookie outside 1..32 B, which {@code VariantCheckFrameCodec.decode13OrNull} refuses).
     */
    DoorShares13.OpenWrapsResult openVchkWraps(DoorShares13.Pair pair, byte[] ya, byte[] wrapM, byte[] wrapA,
            byte[] ada, byte[] cookie) {
        return shares.openWraps(pair, ya, wrapM, wrapA, ada, cookie, mixes);
    }

    /**
     * One version-13 HELLO datagram (READER THREAD ONLY): an F1 OPEN ({@code [10] == 0}) earns a COOKIE13; anything
     * else is an F2 SEAL. Returns the admitted session (ADMIT / TAKEOVER / OBSERVER — the caller runs the same
     * post-admit provisioning as for version 12), or null (a cookie, a reject, a resend, a drop). Takes and releases
     * the door's {@code lock} itself.
     */
    PeerSession onHello(byte[] payload, InetSocketAddress from) {
        if (payload.length >= OPEN_HEAD_BYTES && payload[10] == 0) {
            Handshake13.Open open = Handshake13.decodeOpenOrNull(payload);
            if (open == null) {
                countLocked(true);
                return null;
            }
            answerCookie(open.nonce(), PilotIntentDatagramServer.nowMillis(), from, payload.length, REPLY_CHALLENGE);
            return null;
        }
        return onSeal(payload, from);
    }

    /**
     * The TIMER thread's per-pass call: ORDER LAW — {@code roll} then {@code age}, ONE
     * {@code now}, {@code age} never in a {@code finally}: a nonce is forgotten only after its pair is erased, so a
     * replay that finds its nonce gone meets {@code pairErased()}. A throwing {@code roll} lands in the timer's
     * per-pass catch ({@code onTimerFault}).
     */
    void onTimer(long nowMillis) {
        long bucket = DoorCookie.bucketOf(nowMillis);
        shares.roll(bucket);
        synchronized (door.lock) {
            nonces.age(bucket);
        }
    }

    /** One counted drop under the door lock: {@code malformed} = {@code malformed_hello}, else {@code hs13_unserved}. */
    private void countLocked(boolean malformed) {
        synchronized (door.lock) {
            if (malformed) {
                door.readerCounters.droppedMalformedHello++;
            } else {
                door.readerCounters.hs13Unserved++;
            }
        }
    }

    /** {@link #answerCookie}'s reply kinds — each counts its OWN door meter (a dead-nonce re-challenge is never a stale
     *  cookie: rows pin {@code cookie_stale} deltas). */
    private static final int REPLY_CHALLENGE = 0;
    private static final int REPLY_STALE = 1;
    private static final int REPLY_RECHALLENGE = 2;

    /**
     * F1, F2 step 2's stale cookie and the dead-nonce RE-CHALLENGE (case (ii) of {@link #onSeal}'s admitted-nonce
     * branch): a fresh COOKIE13 for {@code now}'s bucket — the cookie and the share pair name the SAME bucket (one
     * {@code now}). The 1:1 send-site floor of the version-12 gate: a reply larger than the request earns silence
     * ({@code cookie_undersized}). {@code kind} picks the meter counted beside the send ({@code cookie_challenged} /
     * {@code cookie_stale} / {@code hs13_nonce_rechallenged}). Takes {@code shares} then the door's {@code lock}
     * itself: a caller holds NEITHER.
     */
    private void answerCookie(long nonce, long now, InetSocketAddress from, int requestPayloadBytes, int kind) {
        DoorShares13.Pair pair = shares.pairOrNull(DoorCookie.bucketOf(now));
        if (pair == null) {
            countLocked(false);
            return;
        }
        byte[] reply = Handshake13.encodeCookie(nonce, door.doorCookie.mint(from, now), pair.ybM(), pair.ybA(),
                shares.laneSalt());
        synchronized (door.lock) {
            if (door.closed) {
                return;
            }
            if (reply.length > DatagramFrameCodec.FRAMING_BYTES + requestPayloadBytes) {
                door.readerCounters.cookieUndersized++;
                return;
            }
            if (kind == REPLY_RECHALLENGE) {
                door.readerCounters.hs13NonceRechallenged++;
            } else if (kind == REPLY_STALE) {
                door.readerCounters.cookieStale++;
            } else {
                door.readerCounters.cookieChallenged++;
            }
            door.sendRawDatagram(reply, from);
        }
    }

    /** F2 in the designed order (steps 1–7; do not reorder). */
    private PeerSession onSeal(byte[] payload, InetSocketAddress from) {
        Handshake13.Seal13 seal = Handshake13.parseSealOrNull(payload);
        if (seal == null) {
            countLocked(true);
            return null;
        }
        long now = PilotIntentDatagramServer.nowMillis();
        long bucket = door.doorCookie.bucketOfVerified(from, seal.cookie(), now);
        if (bucket == DoorCookie.NO_BUCKET) {
            answerCookie(seal.nonce(), now, from, payload.length, REPLY_STALE);
            return null;
        }
        DoorShares13.Pair pair = shares.pairOrNull(bucket);
        if (pair == null) {
            countLocked(false);
            return null;
        }
        String key = DoorSourceBudget.sourceKey(from.getAddress());
        String lane = DoorGuessLedger.laneKey(key, HexFormat.of().formatHex(seal.handle()));
        int bits = seal.flags() & CLEAR_BITS;
        // The CLEARTEXT view for the pre-open rejects: cid and credential blank by law.
        HandshakeCodec.HelloPayload clear = new HandshakeCodec.HelloPayload(seal.nonce(), "", "", seal.print(), 0L,
                "", bits, seal.cookie());
        boolean probe = clear.probe();
        Hs13 hs = new Hs13();
        DoorGuessLedger.Verdict verdict = null; // assigned below on every path that reaches the open
        boolean rechallenge = false;
        lookup:
        synchronized (door.lock) {
            if (door.closed) {
                return null;
            }
            door.readerCounters.cookieVerified++;
            long sid = nonces.sessionIdOrZero(seal.nonce());
            if (sid != 0L) {
                // An admitted nonce is never opened again (before the grant: a resend never pays the ledger's gap).
                // (i) its LIVE session's own peer, the session still on this nonce → the cached WELCOME13; (ii) NO
                // live session holds it and the sender is the peer the TABLE says admitted it → an ordinary COOKIE13,
                // sent after this block; (iii) anything else — a live
                // session elsewhere / on another nonce, ANY other source live or dead → silence, hs13_nonce_silenced.
                // The peer test reads the table, never the session: a stranger's answer cannot depend on liveness.
                PeerSession s = door.sessionsById.get(sid);
                if (s != null && s.isLiveLocked() && from.equals(s.peer) && s.helloNonce == seal.nonce()) {
                    door.readerCounters.helloWelcomeResends++;
                    door.sendToSession(s, s.welcomeDatagram);
                    return null;
                }
                if ((s == null || !s.isLiveLocked()) && from.equals(nonces.peerOrNull(seal.nonce()))) {
                    rechallenge = true;
                    break lookup; // answerCookie takes shares, then this lock: never under it
                }
                door.readerCounters.hs13NonceSilenced++;
                return null;
            }
            verdict = door.guessLedger.admit(key, lane, now);
            door.readerCounters.countGuess(verdict, door.guessLedger);
            if (!verdict.granted()) {
                return null;
            }
            if (rejectedByBanLocked(clear, key, from, hs, false)) {
                return null;
            }
            if (door.rejectedByDoorFingerprintLocked(clear, from, hs)) {
                return null;
            }
            if ((seal.flags() & Handshake13.FLAG_NO_SECRET) != 0) {
                rejectCredentialLocked(clear, from, hs, true);
                return null;
            }
            // A token door refuses EVERY probe and EVERY observer (the
            // tail's handleProbeLocked / admitObserverLocked), so both are refused HERE, before the open — no
            // X25519, no AEAD trial, no verdict, no admission slot — with the reason and suffix the tail would send.
            if ((probe || clear.observer()) && !passwordDoor) {
                door.sendRejectLocked(clear, from, probe ? DoorRejectReasons.PROBE_UNSUPPORTED
                        : DoorRejectReasons.OBSERVER_PASSWORD_MODE_ONLY, suffix(clear), hs);
                return null;
            }
            if (door.guessLedger.everPassed(key)) {
                // A PASSED key asks the budget its request faces at the pin BEFORE the open;
                // a denial records NOTHING (no lane, no dry run).
                if (probe) {
                    // A stale-term probe's document term is asked first;
                    // a denial is silence with nothing stamped (the probe budget unasked, budgetAsked unset).
                    if (door.matchDocHeldLocked(hs, from, now)) {
                        return null;
                    }
                    if (!door.probeBudget.admit(from.toString(), now)) {
                        door.readerCounters.probeDropped++;
                        return null;
                    }
                } else if (!door.admissionBudget.admit(from.getAddress().getHostAddress(), now)) {
                    door.readerCounters.admissionBudgeted++;
                    door.readerCounters.admissionBudgetedSources = door.admissionBudget.budgetedSources();
                    return null;
                }
                hs.budgetAsked = true;
            } else if (!door.guessLedger.admitOpen(key, verdict, now)) {
                // One law, two halves — BEFORE the open a PASSED key asks the
                // budget its request faces (above), a NEVER-PASSED key's window-free GRANTED_SEEN asks the ledger's
                // unproven-open window, here after every no-verdict exit so only an F2 about to open can spend it. The
                // SAME `now` the admit used. A denial is silence and records nothing BEYOND the admit's own grant stamp
                // and guess_granted_seen count (no verdict, no lane, no dry run), and never sets hs.budgetAsked: a
                // never-passed key that wins a slot and PASSES still faces its post-open budget in the tail.
                door.readerCounters.countHeldUnproven();
                return null;
            }
        }
        if (rechallenge) {
            // case (ii): no ledger ask, no ban check, no open, no admission; the nonce STAYS remembered, so a later
            // copy of the same F2 earns the same answer and can never admit
            answerCookie(seal.nonce(), now, from, payload.length, REPLY_RECHALLENGE);
            return null;
        }
        boolean lanedGrant = verdict == DoorGuessLedger.Verdict.GRANTED_LANE;
        DoorShares13.OpenResult result = shares.open(pair, seal, mixes);
        Handshake13.Opened opened = result.openedOrNull();
        if (!result.pairErased() && opened != null && opened.bodyOk() && !probe) {
            hs.auth = shares.newAuthShare();
        }
        synchronized (door.lock) {
            if (door.closed) {
                return null;
            }
            if (result.pairErased()) {
                door.readerCounters.hs13Unserved++; // The open proved nothing — silence, no verdict
                return null;
            }
            if (opened == null) {
                door.recordCredentialVerdictLocked(CredentialVerdict.FAIL, key, lane, now);
                rejectCredentialLocked(clear, from, hs, false);
                return null;
            }
            CredentialVerdict cv = opened.kind() == Handshake13.KIND_ADMIN
                    ? CredentialVerdict.PASS_ADMIN : CredentialVerdict.PASS;
            if (!opened.bodyOk()) {
                door.readerCounters.helloBodyRefused++; // any body grammar, version-free
                door.recordCredentialVerdictLocked(cv, key, null, now); // the secret was proven; NO lane
                door.sendRejectLocked(clear, from, DoorRejectReasons.HANDSHAKE_REFUSED, suffix(clear), hs);
                return null;
            }
            if (!MessageDigest.isEqual(Handshake13.laneHandle(shares.laneSalt(), opened.cid()), seal.handle())) {
                door.recordCredentialVerdictLocked(lanedGrant ? CredentialVerdict.FAIL : cv, key,
                        lanedGrant ? lane : null, now);
                door.sendRejectLocked(clear, from, DoorRejectReasons.HANDSHAKE_REFUSED, suffix(clear), hs);
                return null;
            }
            door.recordCredentialVerdictLocked(cv, key, lane, now);
            String proven = opened.kind() == Handshake13.KIND_ADMIN ? adminSecret
                    : passwordDoor ? matchPassword : tokens[opened.mixIndex()];
            HandshakeCodec.HelloPayload built = new HandshakeCodec.HelloPayload(seal.nonce(), opened.cid(), proven,
                    seal.print(), opened.desiredShipId(), opened.variantId(), bits, seal.cookie());
            // The lock was released across the open: cid ban, source ban and hold are all re-read.
            if (rejectedByBanLocked(built, key, from, hs, true)) {
                return null;
            }
            hs.root = opened.root();
            hs.cookieBucket = bucket;
            hs.team = opened.team(); // 0..2 here: a 3..255 byte already left at the bodyOk == false refusal above
            return door.admitBranchesLocked(built, from, hs);
        }
    }

    /** The door's ban / hold seam; {@code withCid} = also the cid ban (only after the open names the cid). */
    private boolean rejectedByBanLocked(HandshakeCodec.HelloPayload hello, String key, InetSocketAddress from, Hs13 hs,
            boolean withCid) {
        long wallNow = System.currentTimeMillis();
        boolean held = door.sourceHold.isHeld(key, wallNow);
        // The source ask is the exact key, then the range index on the raw address
        if ((withCid && door.bans.isBannedCid(hello.clientId(), wallNow))
                || door.bans.isBannedSource(key, from.getAddress().getAddress()) || held) {
            door.readerCounters.helloBanned++;
            door.readerCounters.helloHeld += held ? 1 : 0;
            if (withCid) {
                // Version 12 asked the cid ban BEFORE any credential verdict; here the PASS is
                // already on the ledger (the open names the cid), so the dry run it opened is closed again — a
                // banned player holding the password earns no VCHK verdicts. The guess_pass count and streak stay.
                door.guessLedger.revokeDryRun(key);
            }
            door.sendRejectLocked(hello, from, DoorRejectReasons.BANNED, "", hs);
            return true;
        }
        return false;
    }

    /**
     * The REJECT today's v12 tail gives a BLANK ({@code blank}) or a WRONG credential for these flags — password
     * door: {@code match_password_required} / {@code match_password_mismatch}; token door: a probe
     * {@code probe_unsupported}, an observer {@code observer_password_mode_only} (PIDS {@code admitObserverLocked}
     * refuses every token-door observer), else {@code SeatAdmission.authorize}'s {@code seat_token_required} /
     * {@code seat_token_unknown}. Lock held.
     */
    private void rejectCredentialLocked(HandshakeCodec.HelloPayload clear, InetSocketAddress from, Hs13 hs,
            boolean blank) {
        String reason;
        if (passwordDoor) {
            reason = blank ? DoorRejectReasons.MATCH_PASSWORD_REQUIRED : DoorRejectReasons.MATCH_PASSWORD_MISMATCH;
        } else if (clear.probe()) {
            reason = DoorRejectReasons.PROBE_UNSUPPORTED;
        } else if (clear.observer()) {
            reason = DoorRejectReasons.OBSERVER_PASSWORD_MODE_ONLY;
        } else {
            reason = blank ? DoorRejectReasons.SEAT_TOKEN_REQUIRED : DoorRejectReasons.SEAT_TOKEN_UNKNOWN;
        }
        door.sendRejectLocked(clear, from, reason, suffix(clear), hs);
    }

    /** The REJECT line suffix the tail's sites use (a probe first: the door's frozen branch order). */
    private static String suffix(HandshakeCodec.HelloPayload hello) {
        return hello.probe() ? " (probe)" : hello.observer() ? " (observer)" : "";
    }

    /**
     * One F2's door context, threaded nullable through the v12 tail ({@code null} = a v12 HELLO):
     * whether the passed-key budget was already asked, the cookie's bucket, the proven root and the auth share whose
     * {@code Yb″} rides the WELCOME13. Lock-guarded like the tail that reads it.
     */
    final class Hs13 {
        boolean budgetAsked;
        long cookieBucket;
        byte[] root;
        DoorShares13.AuthShare auth;
        /** A PROBE whose {@code match=} term differs from the door's served match id (set by the door's fingerprint
         *  check, armed or not; false on a door that serves no document): its reply carries the document.
         * */
        boolean matchStale;
        /** The joiner's team pick: 0 = none (the door picks), 1 = south (side 0), 2 = north (side 1) — the sealed
         *  F2 body's team byte, set at the open once the body decoded (0 until then; a probe / observer / token
         *  door ignores it at the claim). A byte outside 0..2 never lands here: it fails the body decode.
         * */
        int team = 0;
        /** The salt {@link #sealWelcome} drew — kept for {@link #onAdmittedLocked}'s session root. */
        private byte[] doorSalt;

        /** The nonce-table belt (BEFORE any seat / ledger / session mutation): a full table = silence, counted. */
        boolean hasRoomLocked() {
            if (nonces.hasRoom()) {
                return true;
            }
            door.readerCounters.hs13Unserved++;
            return false;
        }

        /** The sealed WELCOME13: 16 fresh {@code doorSalt} bytes per seal, the proven root, {@code Yb″}. */
        byte[] sealWelcome(long nonceEcho, long sessionId, int startingRoundId, long boundShipId, int tickRate,
                int matchFlags, long serverLife, int customTier, int customPerSeat, int customSModCap,
                int customFlags) {
            doorSalt = new byte[Handshake13.SALT_BYTES];
            rng.nextBytes(doorSalt);
            return Handshake13.encodeAdmitted(nonceEcho, doorSalt, root, sessionId, startingRoundId, boundShipId,
                    tickRate, matchFlags, serverLife, customTier, customPerSeat, customSModCap, customFlags,
                    auth.share());
        }

        /**
         * After the session is registered: its nonce → (session id, admitting peer), aged by the cookie's bucket (the
         * resend index; the peer = {@code session.peer}, the source this F2 came from),
         * and two of lane iv's three {@code /auth} inputs on the session — the session root and the auth scalar; the
         * third, the share {@code YbAuth}, is set by PIDS {@code admitLocked} right after this call (the
         * root is the one THIS F2 proved, salted by the WELCOME13 just sealed — {@code admitLocked} seals before it
         * registers).
         */
        void onAdmittedLocked(PeerSession session) {
            nonces.put(session.helloNonce, session.sessionId, cookieBucket, session.peer); // peer: D3's case (ii)
            session.hs13SessionRoot = Handshake13.sessionRoot(root, doorSalt);
            session.hs13AuthScalar = auth.scalar();
        }
    }
}
