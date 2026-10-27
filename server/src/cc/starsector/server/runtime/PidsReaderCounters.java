package cc.starsector.server.runtime;

/**
 * The endpoint-level
 * reader-side counters of {@link PilotIntentDatagramServer} — boot lifetime, no
 * reset, no logic. Every field stays {@code volatile}: the reader thread writes most of them
 * (several under the door lock) while the door line, the serve lanes and smoke rows read them
 * lock-free via {@link PilotIntentDatagramServer#readerCounters()} (the 62 delegating getters were
 * collapsed). The door owns every write site; nothing else writes this. The public fields are
 * written by the reader thread only (bar {@code variantReceiptsDropped}, the one two-thread bump,
 * which is an AtomicLong for that reason) and are read-only everywhere else (the
 * door line and tests read them through {@link PilotIntentDatagramServer#readerCounters()}).
 */
public final class PidsReaderCounters {
    /** Bytes of arrived datagrams, pre-decode (the sent twin stays on the door, Atomic). */
    public volatile long bytesReceivedTotal;
    // The one-line semantics below rode the door's deleted getters.
    /** Raw datagrams received off the socket, pre-decode — the await-timeout forensics anchor. */
    public volatile long receivedDatagrams;
    /** Spoofable-input drops: undecodable (CRC/magic/version/truncation) datagrams. */
    public volatile long droppedUndecodable;
    /** In-session datagrams dropped for an unknown session id (spoof/stale — silent). */
    public volatile long droppedBadSession;
    /** In-session datagrams dropped for a wrong source address (migration NOT honored v1). */
    public volatile long droppedBadSource;
    volatile long droppedMalformedHello;
    /** v1 HELLOs answered with the readable v1-shaped {@code kit_outdated} REJECT; v2..v6 likewise. */
    public volatile long helloV1Bounced;
    public volatile long helloV2Bounced;
    public volatile long helloV3Bounced;
    public volatile long helloV4Bounced;
    public volatile long helloV5Bounced;
    public volatile long helloV6Bounced;
    /** Retransmitted-HELLO WELCOME resends (nonce-keyed idempotency). */
    public volatile long helloWelcomeResends;
    /** HELLOs rejected by the R8 admission door (WELCOME status=rejected sent back). */
    public volatile long helloRejected;
    /** HELLOs refused at the P27 ban seam (a subset of
     *  {@link #helloRejected} — every one is also a REJECT). */
    public volatile long helloBanned;
    /** Seam refusals whose source was HELD at the time (A9:
     *  {@code hello_held} ⊂ {@link #helloBanned} ⊂ {@link #helloRejected} — a held refusal bumps all
     *  three; a source both banned and held counts here too). Reader thread only. */
    public volatile long helloHeld;
    /** PROBE HELLOs answered with the raw {@code /ship} listing (no claim). */
    public volatile long helloProbed;
    /** OBSERVER HELLOs admitted (fresh + takeover). */
    public volatile long observerAdmitted;
    /** PINT intents from observer sessions dropped at ingest (liveness already
     * stamped; the deposit never happens, so {@code combat_ready} never fires for seat 0). */
    public volatile long observerIntentDrops;
    /** seat-scoped CTRL frames (PDCL declarations, MARK owner marks) and seat verbs
     * from observer sessions dropped/bounced — an observer has no ship for them to bind to
     * (ship 0 would otherwise pass the PDCL pin and mint a phantom apply-state). */
    public volatile long observerCtrlDrops;
    /** credential-passed HELLOs the door's admission budget silently dropped. */
    public volatile long admissionBudgeted;
    /** Join HELLOs refused at the per-address seat allowance ({@code source_seat_cap}; a subset of
     *  {@link #helloRejected} — every one is also a REJECT). Reader thread only. */
    public volatile long sourceCapRefused;
    /** lawful-looking version-13 datagrams the door could not
     *  serve — no share pair for the bucket, a pair the timer erased mid-open, a full admitted-nonce table.
     *  Expected 0 on every serve; written under the door lock. Readers: {@code PidsHello13SeamRows} W5, lane
     *  ii-c's rows, the ownerless join rig's door line. Delete trigger: none while hs 13 serves. */
    public volatile long hs13Unserved;
    /** version-13 F2s under an ADMITTED nonce answered with silence —
     *  case (iii): a live session on another peer or another nonce, or ANY source other than the peer the nonce was
     *  admitted from, live session or dead (case (i), the live session's own peer, counts {@link #helloWelcomeResends};
     *  case (ii), the admitting peer of a dead session, {@link #hs13NonceRechallenged}). Written under the door lock.
     *  Readers: {@code PidsHello13GuardRows} G1 / G2 / G15, the door line's {@code hs13_nonce_silenced=}. Delete
     *  trigger: none (a door counter). */
    public volatile long hs13NonceSilenced;
    /** version-13 F2s under an ADMITTED nonce whose session is no longer
     *  live, from the peer that nonce was admitted from, answered with an ordinary COOKIE13 (case (ii) — never an
     *  admission; counted beside the SEND, after the closed check and the 1:1 size floor, so it counts replies SENT;
     *  never counted as {@link #cookieStale}). Written under the door lock. Readers: {@code PidsHello13GuardRows} G2 /
     *  G15, the client {@code Hs13NoFallbackRows} B8, the door line's {@code hs13_nonce_rechallenged=}. Delete
     *  trigger: none (a door counter). */
    public volatile long hs13NonceRechallenged;
    /** Distinct source addresses the admission budget has budgeted (its bounded
     * tally, mirrored here under the lock for the un-locked door line) — write-only, never reflected. */
    volatile int admissionBudgetedSources;
    /** guess-shaped credential REJECTs the door's credential-fail budget silently dropped. */
    public volatile long credentialFailBudgeted;
    /** Seat squat: never-two-way pilot claims the door evicted under a
     *  full ledger for a fresh credentialed joiner ({@code evictPendingSquatLocked}). */
    public volatile long pendingEvicted;
    /** password-passed PROBEs silently dropped by the {@link DoorSourceBudget}. */
    public volatile long probeDropped;
    /** PROBE replies that carried the match document's sealed DESC
     *  frames (a password-proven probe with a stale {@code match=} term). Reader thread only. */
    public volatile long probeDocServed;
    /** Stale-term PROBEs the document term of the probe budget held: dropped SILENTLY, nothing sent, the probe
     *  budget not asked (the kit's next try is served). Reader thread only. */
    public volatile long probeDocHeld;
    /** PROBEs whose {@code match=} term differed from the served document's id (none, absent or a null print
     *  included), counted at the door's fingerprint check before any credential compare. Reader thread only. */
    public volatile long probeDocStale;
    /** un-cookied v12 HELLOs answered with a COOKIE WELCOME. */
    public volatile long cookieChallenged;
    /** cookie-bearing HELLOs that failed verify (stale/forged/foreign) — re-challenged. */
    public volatile long cookieStale;
    /** HELLOs whose cookie verified (the only ones entering the door chain). */
    public volatile long cookieVerified;
    /** Challenges withheld because the COOKIE reply would exceed the request (silence). */
    public volatile long cookieUndersized;
    /** Verified HELLOs the {@link DoorGuessLedger} silently held
     * = the sum of the {@code guessHeld*} counters (bumped once per hold beside its verdict's). */
    public volatile long attemptHeld;
    /** {@link DoorGuessLedger#keysInBackoff()} (keys at
     * failStreak ≥ 1), mirrored under the lock after every ledger write — write-only, never reflected. */
    public volatile long attemptHeldSources;
    /** The ledger's per-verdict tallies. */
    public volatile long guessGrantedNew;
    public volatile long guessGrantedSeen;
    public volatile long guessGrantedSuspect;
    public volatile long guessGrantedLane;
    public volatile long guessHeldGap;
    public volatile long guessHeldNew;
    public volatile long guessHeldSuspect;
    public volatile long guessHeldLane;
    /** version-13 F2s the ledger's unproven-open window held just before the
     *  open ({@link DoorGuessLedger#admitOpen} false) — each admit was already counted {@link #guessGrantedSeen}, so
     *  this is the subset of {@code guess_granted_seen} that never opened; a hold bumps {@link #attemptHeld} too (the
     *  sum law). Written under the door lock. Readers: {@code Hs13UnprovenOpenRows} U6, the door line's
     *  {@code guess_held_unproven=}. Delete trigger: the ledger's own. */
    public volatile long guessHeldUnproven;
    /** Credential verdicts fed to the ledger. */
    /** guess_pass= / guess_fail= (and the streaks behind attempt_held_sources=) also count the in-session
     *  {@code /auth} verdicts — HELLO + /auth, not HELLO-only. */
    public volatile long guessPass;
    public volatile long guessFail;
    /** The ledger's key / lane table sizes,
     * mirrored under the lock after every ledger write (the door line reads them lock-free). */
    public volatile long guessKeys;
    public volatile long guessLanes;
    /** Answered REJECTs by class — {@code pw}
     * = match_password_mismatch + seat_token_unknown, {@code fp} = content_fingerprint_*,
     * {@code kit} = kit_outdated (legacy ladder), {@code other} = every other reason — and the
     * REJECT log lines the {@link DoorLogSampler} suppressed (mirrored under the lock). */
    public volatile long rejectPw;
    public volatile long rejectFp;
    public volatile long rejectKit;
    public volatile long rejectOther;
    public volatile long rejectLogSuppressed;
    /** VCHKs the per-key variant-check gap held BEFORE the compare. */
    public volatile long variantChecksHeld;
    /** VCHKs dropped because the source key
     * holds no dry-run right (never passed a cookie-verified HELLO compare, or revoked). */
    public volatile long variantChecksUnpassed;
    /** VCHK password mismatches (each revoked
     * the key's dry-run right; never a HELLO-ledger FAIL). */
    public volatile long variantChecksRevoked;
    /** Passed VCHKs the global answer budget dropped. */
    public volatile long variantChecksBudgeted;
    /** The VCHK13 door meters, all READER THREAD only —
     *  {@code vchk13Stale}: checks whose cookie did not verify (stale / forged / foreign), dropped silently before
     *  any lock (never a COOKIE13 from that path); {@code vchk13Opens}: wrap opens run (2 X25519 each, door lock
     *  released); {@code vchk13Bodies}: sealed-body trials run after a good wrap (a kind-{@code a} wrap from a key
     *  with no admin dry run is never tried). Readers: {@code PidsVariantCheck13Rows}, the door line's
     *  {@code vchk13_stale=} / {@code vchk13_opens=} / {@code vchk13_bodies=}. Delete trigger: none (door meters,
     *  like {@code variant_checks_*}). */
    public volatile long vchk13Stale;
    public volatile long vchk13Opens;
    public volatile long vchk13Bodies;
    /** TENURE_APPLIED rows printed, the max
     * {@code spool_ms} seen on them, and tenure clears run by the r2 fail-open finally (a
     * provisioning that threw before {@code applyVariantPick}). Reader thread writes. */
    public volatile long tenureApplied;
    public volatile long tenureSpoolMsMax;
    public volatile long tenureClears;
    /** well-formed v7..v11 HELLOs bounced kit_outdated (v1..v6 keep their own tallies). */
    public volatile long helloLegacyBounced;
    /** W15 v3 VCHK: dry-runs ANSWERED (a VACK on the VCHK channel) / silently DROPPED (token
     * door, credential, budget, malformed tail, a policy throw) — never a VSUB/VACK count. */
    public volatile long variantChecksAnswered;
    public volatile long variantChecksDropped;
    /** VACK encode-fail sibling of {@link #variantChecksDropped} — an unencodable
     *  LAWFUL receipt in {@code sendVariantReceipt} is a server defect, counted + rowed instead of
     *  thrown so the reader thread never dies. The ONE counter bumped off-lock from the reader AND
     *  sim threads, so it is an {@link java.util.concurrent.atomic.AtomicLong}
     *  (a volatile {@code ++} on two threads loses bumps); the door-line
     *  token is unchanged. */
    public final java.util.concurrent.atomic.AtomicLong variantReceiptsDropped =
            new java.util.concurrent.atomic.AtomicLong(); // bumped by the reader AND sim threads
    /** New-nonce re-admissions (the reconnect lane) + N-mode token takeovers. */
    public volatile long reAdmissions;
    /** Sessions whose first valid in-session datagram arrived — written under
     * the door lock ({@code markArrivalLocked}), read lock-free by the status line and the
     * {@link PilotIntentDatagramServer#readerCounters()} smoke counter (volatile). */
    public volatile long twoWayEstablished;
    /** Datagrams for a FAILED (pending-death) session, dropped unanswered — wire-dead law (B4). */
    public volatile long droppedDeadSession;
    /** Unchecked handler faults caught by the reader belt ({@code onReaderFault}) — each one
     * failed its session (or dropped an unadmitted datagram) instead of killing the reader. */
    public volatile long readerFaults;
    volatile long mrerIgnored;
    /** in-session /auth guesses the guess ledger held — reader thread, under the door lock. */
    public volatile long authHeld;
    /** version-13 {@code /auth} arguments that are not a proof
     *  (length, charset, counter 0 — a READABLE password included), answered with the usage line: no compare, no
     *  ledger, no X25519 — reader thread only. {@code auth13Replayed}: version-13 proofs whose counter was not above
     *  the session's highest granted one, dropped silently before the ledger and any X25519 — reader thread, under the
     *  door lock. Readers: {@code Handshake13AuthRows} A13-8 / A13-7, the door line's {@code auth13_malformed=} /
     *  {@code auth13_replayed=}. Delete trigger: none (door meters, like {@code auth_held=}). */
    public volatile long auth13Malformed;
    public volatile long auth13Replayed;
    /** The join door's team meters, all written under the door lock.
     *  {@code teamIgnored}: password-door claims whose nonzero team pick was ignored because the served shape
     *  seats no pilot on one of its sides (or the pick was outside 1..2). {@code teamMemoryFull}: rejoins whose
     *  remembered team had no room, so the door's own pick law placed them. {@code teamMemoryEvicted}: remembered
     *  teams dropped at the memory's bound (a departed player's first; a seated player's never). {@code
     *  helloV13Bounced}: version-13 HELLOs bounced as an outdated kit (the version-13 rung of the bounce ladder; 0
     *  while 13 is the current version). Readers: {@code SeatTeamDoorContractRows}, the door line. Delete trigger:
     *  none (door meters). */
    public volatile long teamIgnored;
    public volatile long teamMemoryFull;
    public volatile long teamMemoryEvicted;
    public volatile long helloV13Bounced;
    /** F2s whose secret the wrap proved but whose sealed body did not
     *  decode or authenticate (any body grammar; version-free), each answered {@code handshake_refused}. Expected
     *  0 on a clean door. Written under the door lock. Readers: {@code SeatTeamDoorContractRows}, the door line
     *  and the await-timeout line. Delete trigger: none (a door meter). */
    public volatile long helloBodyRefused;
    /** {@link DoorGuessLedger#laneCapEvictions()} mirror — reader thread, under the door lock. */
    public volatile long guessLaneCapped;
    /** PROBE mod-set encode failures — reader thread, under the door lock. */
    public volatile long probeModsetEncodeFailed;

    /** per-verdict tallies + the ledger mirror.
     * A hold also bumps {@code attemptHeld} (so it stays the sum of the {@code guessHeld*}).
     * Caller holds the door lock (the ledger is lock-guarded; was the door's
     * {@code countGuessVerdictLocked}). */
    void countGuess(DoorGuessLedger.Verdict verdict, DoorGuessLedger guessLedger) {
        switch (verdict) {
            case GRANTED_NEW: guessGrantedNew++; break;
            case GRANTED_SEEN: guessGrantedSeen++; break;
            case GRANTED_SUSPECT: guessGrantedSuspect++; break;
            case GRANTED_LANE: guessGrantedLane++; break;
            case HELD_GAP: guessHeldGap++; break;
            case HELD_NEW_WINDOW: guessHeldNew++; break;
            case HELD_SUSPECT_WINDOW: guessHeldSuspect++; break;
            case HELD_LANE_GAP: guessHeldLane++; break;
            default: break;
        }
        if (!verdict.granted()) {
            attemptHeld++;
        }
        mirrorGuess(guessLedger);
    }

    /** One unproven-open hold ({@link DoorGuessLedger#admitOpen} false) — bumps
     *  {@code guessHeldUnproven} AND {@code attemptHeld}, so {@code attemptHeld} stays the sum of the
     *  {@code guessHeld*}. Its admit's {@code GRANTED_SEEN} was counted by {@link #countGuess}; the ledger tables did
     *  not change, so nothing is mirrored. Caller holds the door lock. */
    void countHeldUnproven() {
        guessHeldUnproven++;
        attemptHeld++;
    }

    /** The ledger's table sizes + keys in
     * backoff, mirrored for the lock-free door line ({@code attempt_held_sources=} = keysInBackoff).
     * Caller holds the door lock (was the door's {@code mirrorGuessLedgerLocked}). */
    void mirrorGuess(DoorGuessLedger guessLedger) {
        guessKeys = guessLedger.trackedKeys();
        guessLanes = guessLedger.trackedLanes();
        attemptHeldSources = guessLedger.keysInBackoff();
        guessLaneCapped = guessLedger.laneCapEvictions(); //
    }

    /** Classifies one answered door REJECT
     * ({@code reject_pw/fp/kit/other}); {@code reason} is a server constant (never wire text).
     * Caller holds the door lock (the classify half of the door's {@code printRejectSampledLocked}). */
    void countReject(String reason) {
        if (DoorRejectReasons.MATCH_PASSWORD_MISMATCH.equals(reason)
                || DoorRejectReasons.SEAT_TOKEN_UNKNOWN.equals(reason)) {
            rejectPw++;
        } else if (DoorRejectReasons.CONTENT_FINGERPRINT_REQUIRED.equals(reason)
                || DoorRejectReasons.CONTENT_FINGERPRINT_MISMATCH.equals(reason)) {
            rejectFp++;
        } else if (DoorRejectReasons.KIT_OUTDATED.equals(reason)) {
            rejectKit++;
        } else {
            rejectOther++;
        }
    }

    /** Every counter token of the
     * {@code pilot-datagram-door} line after its {@code live=} prefix, in the door line's frozen
     * order (appended-only grammar; counts only — never cookie or password bytes). The four
     * parameters are the door-owned terms (two lock-read getters + the timer-thread fault count +
     * the source-hold census). */
    void appendDoorTokens(StringBuilder sb, long variantSubmits, long variantReceipts, long timerFaults,
            int sourceHolds) {
        sb.append(""
                + " received=" + receivedDatagrams
                + " undecodable=" + droppedUndecodable
                + " malformed_hello=" + droppedMalformedHello
                + " hello_rejected=" + helloRejected
                + " hello_probed=" + helloProbed
                + " probe_dropped=" + probeDropped
                + " probe_doc_served=" + probeDocServed
                + " probe_doc_held=" + probeDocHeld
                + " probe_doc_stale=" + probeDocStale
                + " variant_checks=" + variantChecksAnswered
                + " variant_checks_dropped=" + variantChecksDropped
                + " observer_admitted=" + observerAdmitted
                + " observer_intent_drops=" + observerIntentDrops
                + " observer_ctrl_drops=" + observerCtrlDrops
                + " variant_submits=" + variantSubmits
                + " variant_receipts=" + variantReceipts
                + " variant_receipts_dropped=" + variantReceiptsDropped.get() // the one AtomicLong here: get(), not a volatile read
                + " admission_budgeted=" + admissionBudgeted
                + " admission_budgeted_sources=" + admissionBudgetedSources
                + " credential_fail_budgeted=" + credentialFailBudgeted
                + " pending_evicted=" + pendingEvicted
                + " welcome_resends=" + helloWelcomeResends
                + " bad_session=" + droppedBadSession
                + " bad_source=" + droppedBadSource
                + " dead_session=" + droppedDeadSession
                + " reader_faults=" + readerFaults
                + " readmissions=" + reAdmissions
                + " two_way=" + twoWayEstablished
                // The v12 door meters (appended fields
                // only — grammar rule; counts only, never cookie bytes)
                + " cookie_challenged=" + cookieChallenged
                + " cookie_stale=" + cookieStale
                + " cookie_verified=" + cookieVerified
                + " cookie_undersized=" + cookieUndersized
                + " attempt_held=" + attemptHeld
                + " attempt_held_sources=" + attemptHeldSources
                + " variant_checks_held=" + variantChecksHeld
                + " hello_legacy_bounced=" + helloLegacyBounced
                // The guess
                // ledger, REJECT-class and VCHK meters (appended only; counts only — never cookie
                // or password bytes). attempt_held above = the sum of guess_held_*;
                // attempt_held_sources = the ledger's keysInBackoff().
                + " guess_granted_new=" + guessGrantedNew
                + " guess_granted_suspect=" + guessGrantedSuspect
                + " guess_granted_lane=" + guessGrantedLane
                + " guess_held_gap=" + guessHeldGap
                + " guess_held_new=" + guessHeldNew
                + " guess_held_suspect=" + guessHeldSuspect
                + " guess_held_lane=" + guessHeldLane
                + " guess_pass=" + guessPass
                + " guess_fail=" + guessFail
                + " guess_keys=" + guessKeys
                + " guess_lanes=" + guessLanes
                + " reject_pw=" + rejectPw
                + " reject_fp=" + rejectFp
                + " reject_kit=" + rejectKit
                + " reject_other=" + rejectOther
                + " reject_log_suppressed=" + rejectLogSuppressed
                + " variant_checks_unpassed=" + variantChecksUnpassed
                + " variant_checks_revoked=" + variantChecksRevoked
                + " variant_checks_budgeted=" + variantChecksBudgeted
                + " timer_faults=" + timerFaults //
                // Appended
                // AFTER timer_faults= (appended-only grammar; timer_faults= is no longer the LAST
                // field — auth_held= .. probe_modset_encode_failed= follow it, in this order).
                + " auth_held=" + authHeld
                + " guess_lane_capped=" + guessLaneCapped
                + " guess_granted_seen=" + guessGrantedSeen
                + " probe_modset_encode_failed=" + probeModsetEncodeFailed
                // The P27 ban seam meter — appended LAST
                // (appended-only grammar), a subset of hello_rejected=.
                + " hello_banned=" + helloBanned
                // Appended LAST (A9) — the held subset of
                // hello_banned=, then the door's live source-hold count (no key ever prints here).
                + " hello_held=" + helloHeld
                + " door_source_holds=" + sourceHolds
                // Appended after door_source_holds= (appended-only), a count only
                + " hs13_unserved=" + hs13Unserved
                // Appended LAST (appended-only grammar), a count only
                + " hs13_nonce_silenced=" + hs13NonceSilenced
                // Appended LAST (appended-only grammar), counts only
                + " auth13_malformed=" + auth13Malformed
                + " auth13_replayed=" + auth13Replayed
                // Appended LAST (appended-only grammar), counts only
                + " vchk13_stale=" + vchk13Stale
                + " vchk13_opens=" + vchk13Opens
                + " vchk13_bodies=" + vchk13Bodies
                // Appended LAST (appended-only grammar), a count only
                + " hs13_nonce_rechallenged=" + hs13NonceRechallenged
                // Appended LAST (appended-only grammar), a count only
                + " guess_held_unproven=" + guessHeldUnproven
                // Appended LAST (appended-only grammar), a count only — the
                // seat-allowance subset of hello_rejected=.
                + " source_cap_refused=" + sourceCapRefused
                // Appended LAST (appended-only grammar), counts only
                + " team_ignored=" + teamIgnored
                + " team_memory_full=" + teamMemoryFull
                + " team_memory_evicted=" + teamMemoryEvicted
                + " hello_v13_bounced=" + helloV13Bounced
                + " hello_body_refused=" + helloBodyRefused);
    }
}
