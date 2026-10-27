package cc.starsector.server.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * W2b module-seats arbitration law — the
 * TwoPlayersOneSeat guard: a first-wins claim table (seat → slot token) resolved at chat
 * dispatch, re-checked and consumed at the seat's fly-in drain, plus the voluntary-dismount
 * deposit. Deliberately rebind-generic (no "module" in the name): this same seam is the W3
 * mid-life rebind core (different eligibility predicate) and neighbors the W5(2)(3) reclaim
 * lane — only the token census and the caller's drain wiring are W2b-specific. The W3
 * command-transfer CONTEST core lives in
 * the {@code transfer*} verb family below — hull-wire-id vocabulary, same monitor, same
 * advisory-begin / authoritative-commit split.
 *
 * <p>Pure state machine: slot tokens are opaque server-authored strings (fixed census, never
 * wire-derived — the wire arg is ASCII-folded and matched against the census allowlist, the
 * /ship stem-law shape); no engine types. The serve loop owns truth and reports it through the
 * {@code note*} calls; dispatch-time grants/denials are therefore ADVISORY-fresh (an honest
 * immediate reply) and the drain's {@link #takeClaim} is the authoritative re-check — a claim
 * gone stale between grant and fly-in surfaces as a LOUD stale reason, never silently.
 *
 * <p>Threading: the {@link ChatCommandDispatch} idiom — every table lives under THIS object's
 * monitor; reader thread claims/lists, sim thread notes/takes; no caller holds another lock
 * across a call (lock-order law: never nested) — read as: NOTHING is taken under THIS monitor.
 * Callers do hold an OUTER monitor across a call: the reader its delivering session's
 * reliability monitor ({@code claim} / {@code off} / {@code transferBegin} /
 * {@code transferReturnHome} through the chat verbs inside {@code onCtrlDelivered}), the serve
 * thread the admission-cursor monitor across {@code inFlightTransfers}.
 * Both are safe only because this monitor is a LEAF — no method here calls out under it, so no
 * thread ever waits on another lock while holding it (the delta lenses' notes
 * S3-c and S3-3, recorded in the unit's evidence file).
 */
public final class SeatRebindArbiter {

    /** Longest raw-token echo in an unknown-slot reply (keeps the line law-safe). */
    private static final int UNKNOWN_TOKEN_ECHO_CHARS = 24;

    /** Census slot tokens, fixed order = listing order. */
    private final List<String> tokens;
    /** slot token → seatId currently piloting that slot's body. */
    private final LinkedHashMap<String, Integer> mountedSeatByToken =
            new LinkedHashMap<String, Integer>();
    /** seatId → slot token currently piloted (inverse of the above). */
    private final LinkedHashMap<Integer, String> mountedTokenBySeat =
            new LinkedHashMap<Integer, String>();
    /** seatId → claimed slot token, applied-and-consumed at that seat's next fly-in. */
    private final LinkedHashMap<Integer, String> claimTokenBySeat =
            new LinkedHashMap<Integer, String>();
    /** Slots whose live body is dead/gone this round (serve-loop truth). */
    private final Set<String> deadTokens = new LinkedHashSet<String>();
    /** Seats with a voluntary dismount queued (coalescing, drained by the serve loop). */
    private final Set<Integer> dismountRequested = new LinkedHashSet<Integer>();
    /**
     * seatId → resolved display name for occupancy replies (a player
     * reads "taken by Rival", never "taken by seat 2"). Fed by {@link #noteSeatName} from the
     * door's SNMR fan (reader thread, outside the door lock — never-nested law holds); values
     * are already codec-validated ({@code SeatNameFrameCodec.validateSeatName}: ≤24 chars,
     * single-line printable), so every reply line embedding one stays inside the chat line law.
     * Never cleared (a takeover's re-announce overwrites; an unnamed seat falls back to
     * "seat N" — the chat fan's own fallback vocabulary).
     */
    private final LinkedHashMap<Integer, String> nameBySeatId =
            new LinkedHashMap<Integer, String>();

    // ---- W3 command-transfer contest core -------------------

    /**
     * W3 contest mode — the server knob (`transfer.contest`):
     * {@code PRIORITY} = a granted begin LOCKS the target (other seats' begins deny
     * "already claimed" until the flight resolves); {@code RACE} = concurrent flights toward
     * one target are all granted and arbitration moves to dock-commit — the first
     * {@link #transferCommitWinner} takes the hull, every other flight toward it aborts.
     * Before L2 pods exist, begin+commit run back-to-back and both modes degenerate to
     * instant first-wins.
     */
    public enum TransferContest { PRIORITY, RACE }

    /**
     * W3 arbitration policy (server-authored; {@link #parse} builds it from the serve's knobs).
     * Floors are SIM TICKS at the policy's own {@code ticksPerSecond} (the serve's effective
     * rate, which a host may set away from 60): {@code beginFloorTicks} = min gap between one
     * seat's GRANTED begins (denials are free — only grants consume the rate slot);
     * {@code leftHullCooldownTicks} = how long a just-vacated hull refuses new begins
     * (the anti-ping-pong law, default 600 at 60/s). Both floors bind in BOTH contest modes.
     * {@code ticksPerSecond} (> 0) is the divisor the countdown reply texts use to turn a tick
     * wait back into wall seconds, so "claimable in Ns" / "retry in Ns" stay true at any rate;
     * it is a required component with no default-rate constructor, so no caller can build a
     * policy that silently assumes 60.
     *
     */
    public record TransferPolicy(TransferContest contest, long beginFloorTicks,
            long leftHullCooldownTicks, long ticksPerSecond) {
        public TransferPolicy {
            if (contest == null) {
                throw new IllegalArgumentException("contest mode required");
            }
            if (beginFloorTicks < 0 || leftHullCooldownTicks < 0) {
                throw new IllegalArgumentException("transfer floors must be >= 0");
            }
            if (ticksPerSecond <= 0) {
                throw new IllegalArgumentException(
                        "transfer policy needs ticksPerSecond > 0, got: " + ticksPerSecond);
            }
        }

        /**
         * v1 defaults (options doc default 7): priority, 5 s begin floor, 10 s hull cooldown,
         * at the 60/s compiled default rate.
         */
        public static TransferPolicy defaults() {
            return new TransferPolicy(TransferContest.PRIORITY, 300, 600, 60);
        }

        /**
         * Parse the W3 knob trio ({@code transfer.contest} + {@code transfer.beginFloorSeconds}
         * + {@code transfer.hullCooldownSeconds}) — strict and LOUD at boot (the
         * {@code MatchGracePolicy} precedent: a bad knob value fails the serve, never the first
         * {@code /transfer}). Floor knobs are WALL SECONDS converted at the serve's EFFECTIVE
         * tick rate, so a {@code --fixed-dt} override keeps the operator-facing cadence in seconds.
         */
        public static TransferPolicy parse(String contestRaw, String beginFloorSecondsRaw,
                String hullCooldownSecondsRaw, long ticksPerSecond) {
            if (ticksPerSecond <= 0) {
                throw new IllegalArgumentException(
                        "transfer knob parse needs ticksPerSecond > 0, got: " + ticksPerSecond);
            }
            if (contestRaw == null || contestRaw.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "transfer.contest must not be blank (expected 'priority' or 'race')");
            }
            TransferContest contest = switch (ChatCommands.asciiLower(contestRaw.trim())) {
                case "priority" -> TransferContest.PRIORITY;
                case "race" -> TransferContest.RACE;
                default -> throw new IllegalArgumentException(
                        "transfer.contest must be 'priority' or 'race', got: '"
                                + contestRaw + "'");
            };
            return new TransferPolicy(contest,
                    secondsKnobToTicks(beginFloorSecondsRaw, "transfer.beginFloorSeconds",
                            ticksPerSecond),
                    secondsKnobToTicks(hullCooldownSecondsRaw, "transfer.hullCooldownSeconds",
                            ticksPerSecond),
                    ticksPerSecond);
        }

        /**
         * The D7 presentation gate ({@code transfer.enabled} — the compiled
         * default is TRUE;
         * this parser owns
         * no default): STRICT bool, LOUD like the trio —
         * {@code ServerConfig.resolveBoolean}'s junk-reads-as-false announce would let a typo
         * silently arm/disarm a knob whose flip is a deliberate operator decision.
         */
        public static boolean parseEnabledKnob(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "transfer.enabled must not be blank (expected 'true' or 'false')");
            }
            return switch (ChatCommands.asciiLower(raw.trim())) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException(
                        "transfer.enabled must be 'true' or 'false', got: '" + raw + "'");
            };
        }

        private static long secondsKnobToTicks(String raw, String knob, long ticksPerSecond) {
            if (raw == null || raw.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        knob + " must not be blank (expected an integer >= 0; 0 = no floor)");
            }
            long seconds;
            try {
                seconds = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        knob + " needs an integer >= 0 (0 = no floor), got: '" + raw + "'");
            }
            if (seconds < 0) {
                throw new IllegalArgumentException(
                        knob + " needs an integer >= 0 (0 = no floor), got: " + seconds);
            }
            return Math.multiplyExact(seconds, ticksPerSecond);
        }
    }

    /** One begin verdict: the reply line always; {@code granted} says whether a flight minted.
     * {@code denyReason}: a {@code CommandTransferFrameCodec}
     * reason code 10..12 ONLY on a FRESH-begin (no in-flight row) claimed/floor/cooldown
     * denial — the dispatch-DENY fan's trigger; 0 on every grant, every retarget denial
     * (chat-only by design — the client's denyOwn guard drops mid-transit frames) and the
     * same-target informational dup. */
    public record TransferBegin(boolean granted, String replyLine, int denyReason) {}

    /** A race loser drained at commit: the seat plus its ready-made reason line. */
    public record TransferAbort(int seatId, String reasonLine) {}

    /** A dock commit: the taken hull plus the race losers (empty in PRIORITY by construction). */
    public record TransferCommit(long targetHullId, List<TransferAbort> losers) {}

    /** A seat's parked return-home request — the flight it saw
     * (the serve drain aborts ONLY that flight) + the home hull's label for the narration. */
    public record ReturnHome(long targetHullId, String label) {}

    private final TransferPolicy transferPolicy;
    /** seatId → return-home request parked by the reader thread for the serve drain. */
    private final LinkedHashMap<Integer, ReturnHome> returnHomeBySeat =
            new LinkedHashMap<Integer, ReturnHome>();
    /** seatId → target hull wire id of the seat's in-flight transfer (one per seat, max). */
    private final LinkedHashMap<Integer, Long> flightTargetBySeat =
            new LinkedHashMap<Integer, Long>();
    /** seatId → tick of the seat's last GRANTED begin (the rate floor's stamp). */
    private final LinkedHashMap<Integer, Long> lastBeginTickBySeat =
            new LinkedHashMap<Integer, Long>();
    /**
     * seatId → tick of the seat's last COMMIT: a FRESH begin inside
     * {@link #COMMIT_SETTLE_TICKS} of it is denied RATE_LIMITED ("settling") — a human cannot
     * dock and re-pick within half a second; a recorded "/transfer #55" arrived 4 ms after
     * the commit = the client's stale pre-COMMIT warroom TO echoed as a fresh ask (two pods).
     * Server-authoritative backstop; the client-side cause is fixed in CommandTransferLane.
     */
    private final LinkedHashMap<Integer, Long> lastCommitTickBySeat =
            new LinkedHashMap<Integer, Long>();
    /** Half a second of sim ticks (60/s) after a COMMIT during which a FRESH begin is an echo. */
    public static final long COMMIT_SETTLE_TICKS = 30L;
    /**
     * seatId → the NEW target of a GRANTED mid-flight retarget, PARKED for the serve drain:
     * the live row keeps the
     * PRIOR target until {@link #transferRetargetApply} moves it after the drain validated the
     * new id (registered, eligible, alive). Before this the begin overwrote the row at once and
     * an unregistered id STRANDED the seat in the pod pump before the drain could deny — the
     * pod flipped pilotable mid-space with a live home a plain abort would have flown to. A deny
     * now leaves the prior flight untouched; every row-consuming path also drops the park.
     */
    private final LinkedHashMap<Integer, Long> pendingRetargetBySeat =
            new LinkedHashMap<Integer, Long>();
    /** hull wire id → tick until which begins toward it deny (left-hull cooldown). */
    private final LinkedHashMap<Long, Long> hullCooldownUntilTick =
            new LinkedHashMap<Long, Long>();

    /**
     * @param slotTokens fixed census in listing order — server-authored constants, so a bad
     *     census fails the boot loudly: 1..24 chars, ASCII lowercase/digit only, distinct.
     */
    public SeatRebindArbiter(List<String> slotTokens) {
        this(slotTokens, TransferPolicy.defaults());
    }

    /** W3 ctor: same census law plus the transfer contest policy. */
    public SeatRebindArbiter(List<String> slotTokens, TransferPolicy transferPolicy) {
        Objects.requireNonNull(slotTokens, "slotTokens");
        if (slotTokens.isEmpty()) {
            throw new IllegalArgumentException("slot census must be non-empty");
        }
        for (String token : slotTokens) {
            Objects.requireNonNull(token, "slot token");
            if (token.isEmpty() || token.length() > 24) {
                throw new IllegalArgumentException("slot token length 1..24, got: " + token);
            }
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                    throw new IllegalArgumentException(
                            "slot token must be ASCII lowercase/digit: " + token);
                }
            }
        }
        List<String> copy = List.copyOf(slotTokens);
        if (new LinkedHashSet<String>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("slot census must be distinct: " + copy);
        }
        this.tokens = copy;
        this.transferPolicy = Objects.requireNonNull(transferPolicy, "transferPolicy");
    }

    /**
     * W3 begin (dispatch- or serve-side): arbitrates CONTEST + FLOORS only — eligibility
     * (friendly AI hull / PARKED abandoned / occupied = hard deny; packet defaults 1) is the
     * CALLER's check BEFORE this call (authority truth would nest locks here — never-nested
     * law), advisory at begin and re-checked authoritatively by the serve loop at commit.
     * {@code hullLabel} is caller-resolved and line-law-safe (ship display name). A granted
     * begin toward a NEW target PARKS the retarget ({@link #pendingRetargetBySeat}: the
     * serve drain validates it and moves the row via {@link #transferRetargetApply} — vanilla's
     * mid-flight retarget mechanic, made drain-validated here); a
     * same-target re-begin is an informational deny that also cancels any parked retarget.
     * Denials never stamp the rate floor. A call from OFF the sim thread is journaled with its
     * verdict ({@link #drainTransferJournal}).
     */
    public synchronized TransferBegin transferBegin(
            int seatId, long targetHullId, String hullLabel, long nowTick) {
        boolean fresh = flightTargetBySeat.get(seatId) == null;
        TransferBegin begin = transferBeginLaw(seatId, targetHullId, hullLabel, nowTick);
        if (Thread.currentThread() != journalSimThread) {
            journalLocked(beginRow(seatId, targetHullId, hullLabel, nowTick, fresh, begin));
        }
        return begin;
    }

    /**
     * The begin law itself, run under this monitor by {@link #transferBegin} and by the
     * re-simulation's {@link #replayTransfer}, so both apply one law.
     *
     */
    private TransferBegin transferBeginLaw(
            int seatId, long targetHullId, String hullLabel, long nowTick) {
        Objects.requireNonNull(hullLabel, "hullLabel");
        Long current = flightTargetBySeat.get(seatId);
        boolean fresh = current == null;
        if (current != null && current == targetHullId) {
            pendingRetargetBySeat.remove(seatId);
            return new TransferBegin(false, "already transferring to " + hullLabel,
                    CommandTransferFrameCodec.REASON_NONE);
        }
        // W3 L4 S3 R1: the begin floor rates FRESH begins only — a
        // retarget reuses the live pod, and vanilla already retargeted natively on the
        // click, so flooring it would only desync client-vanilla from the server flight.
        if (fresh) {
            Long lastBegin = lastBeginTickBySeat.get(seatId);
            if (lastBegin != null && nowTick - lastBegin < transferPolicy.beginFloorTicks()) {
                long waitTicks = transferPolicy.beginFloorTicks() - (nowTick - lastBegin);
                return new TransferBegin(false,
                        "transfer cooling down - retry in " + secondsCeil(waitTicks) + "s",
                        CommandTransferFrameCodec.REASON_RATE_LIMITED);
            }
            Long lastCommit = lastCommitTickBySeat.get(seatId);
            if (lastCommit != null && nowTick - lastCommit < COMMIT_SETTLE_TICKS) {
                // Post-COMMIT settle (see lastCommitTickBySeat): an echo, not a pick.
                return new TransferBegin(false, "transfer settling - retry in 1s",
                        CommandTransferFrameCodec.REASON_RATE_LIMITED);
            }
        }
        Long cooldownUntil = hullCooldownUntilTick.get(targetHullId);
        if (cooldownUntil != null && nowTick < cooldownUntil) {
            return new TransferBegin(false, hullLabel + " was just vacated - claimable in "
                    + secondsCeil(cooldownUntil - nowTick) + "s",
                    fresh ? CommandTransferFrameCodec.REASON_HULL_COOLDOWN
                            : CommandTransferFrameCodec.REASON_NONE);
        }
        if (transferPolicy.contest() == TransferContest.PRIORITY) {
            for (var entry : flightTargetBySeat.entrySet()) {
                if (entry.getValue() == targetHullId && entry.getKey() != seatId) {
                    return new TransferBegin(false, hullLabel + " already claimed by "
                            + seatLabel(entry.getKey()),
                            fresh ? CommandTransferFrameCodec.REASON_TARGET_CLAIMED
                                    : CommandTransferFrameCodec.REASON_NONE);
                }
            }
        }
        if (fresh) {
            flightTargetBySeat.put(seatId, targetHullId);
            lastBeginTickBySeat.put(seatId, nowTick);
        } else {
            pendingRetargetBySeat.put(seatId, targetHullId);
        }
        return new TransferBegin(true, "transferring to " + hullLabel,
                CommandTransferFrameCodec.REASON_NONE);
    }

    /**
     * Sim-thread take-and-clear of every parked mid-flight retarget (deposit order) — the
     * serve lane validates each with the drain's own facts BEFORE {@link #transferRetargetApply}
     * moves the row. A deny = nothing to undo: the row never moved.
     */
    public synchronized LinkedHashMap<Integer, Long> takeRetargetRequests() {
        LinkedHashMap<Integer, Long> taken =
                new LinkedHashMap<Integer, Long>(pendingRetargetBySeat);
        pendingRetargetBySeat.clear();
        return taken;
    }

    /** The seat's parked (granted, not yet drain-applied) retarget target, or null. */
    public synchronized Long pendingRetargetOrNull(int seatId) {
        return pendingRetargetBySeat.get(seatId);
    }

    /**
     * Move the seat's live row onto the drain-validated {@code targetHullId}
     * (the ONLY writer of a retarget). False = nothing moved: the flight ended meanwhile (no
     * live row — a vacate/abort/commit raced the park), or under PRIORITY another seat locked
     * the hull since the begin (the contest re-checked at apply, as the begin checked it at
     * grant); the caller narrates, the prior flight stands. Idempotent on an already-moved row.
     * A successful move stamps the rate floor as the earlier begin-time put did.
     */
    public synchronized boolean transferRetargetApply(int seatId, long targetHullId,
            long nowTick) {
        Long current = flightTargetBySeat.get(seatId);
        if (current == null) {
            return false;
        }
        if (Long.valueOf(targetHullId).equals(pendingRetargetBySeat.get(seatId))) {
            pendingRetargetBySeat.remove(seatId); // a NEWER park (different id) survives
        }
        if (current == targetHullId) {
            return true;
        }
        if (transferPolicy.contest() == TransferContest.PRIORITY) {
            for (var entry : flightTargetBySeat.entrySet()) {
                if (entry.getValue() == targetHullId && entry.getKey() != seatId) {
                    return false;
                }
            }
        }
        flightTargetBySeat.put(seatId, targetHullId);
        lastBeginTickBySeat.put(seatId, nowTick);
        return true;
    }

    /**
     * The seat's pod docked (or the pre-pod instant path): consume its flight and, in RACE
     * mode, every OTHER flight toward the same hull — the losers come back with ready-made
     * reason lines for the serve loop's retarget-back fan. Caller laws: re-check target
     * eligibility authoritatively BEFORE committing; stamp the winner's LEFT hull via
     * {@link #noteHullVacated} AFTER (the arbiter never knows which hull the winner left).
     * Committing a seat with no flight is a wiring bug and throws.
     */
    public synchronized TransferCommit transferCommitWinner(int seatId) {
        Long target = flightTargetBySeat.remove(seatId);
        if (target == null) {
            throw new IllegalStateException("transfer commit for seat " + seatId
                    + " with no in-flight transfer");
        }
        pendingRetargetBySeat.remove(seatId);
        List<TransferAbort> losers = new ArrayList<TransferAbort>();
        List<Integer> loserSeats = new ArrayList<Integer>();
        for (var entry : flightTargetBySeat.entrySet()) {
            if (entry.getValue().longValue() == target) {
                loserSeats.add(entry.getKey());
            }
        }
        for (int loser : loserSeats) {
            flightTargetBySeat.remove(loser);
            pendingRetargetBySeat.remove(loser);
            losers.add(new TransferAbort(loser,
                    "transfer lost - " + seatLabel(seatId) + " docked first"));
        }
        return new TransferCommit(target, List.copyOf(losers));
    }

    /**
     * {@link #transferCommitWinner(int)} + the settle stamp ({@link #lastCommitTickBySeat})
     * — the serve drain's form; the tickless form stays for callers that never re-begin.
     */
    public synchronized TransferCommit transferCommitWinner(int seatId, long nowTick) {
        TransferCommit commit = transferCommitWinner(seatId);
        lastCommitTickBySeat.put(seatId, nowTick);
        return commit;
    }

    /**
     * Consume the seat's flight without a commit (target died/timeout/deny-at-dock, or the
     * seat left); returns the target it was flying toward, or null. Reason lines are the
     * caller's (it knows why).
     */
    public synchronized Long transferAbort(int seatId) {
        pendingRetargetBySeat.remove(seatId);
        return flightTargetBySeat.remove(seatId);
    }

    /**
     * The return-home verb (reader thread): a
     * self-target while a flight exists parks a request keyed to THAT flight for the serve
     * drain ({@link #takeReturnHomeRequests}) — the flight itself survives here so the drain's
     * own abort narrates it. Returns false with no flight (the caller keeps its own-hull
     * deny). A repeat while parked is idempotent. A call from OFF the sim thread is journaled
     * with its verdict ({@link #drainTransferJournal}).
     */
    public synchronized boolean transferReturnHome(int seatId, String homeLabel) {
        boolean parked = transferReturnHomeLaw(seatId, homeLabel);
        if (Thread.currentThread() != journalSimThread) {
            journalLocked(returnHomeRow(seatId, homeLabel, parked));
        }
        return parked;
    }

    /** The return-home law, under this monitor. */
    private boolean transferReturnHomeLaw(int seatId, String homeLabel) {
        Objects.requireNonNull(homeLabel, "homeLabel");
        Long target = flightTargetBySeat.get(seatId);
        if (target == null) {
            return false;
        }
        returnHomeBySeat.put(seatId, new ReturnHome(target.longValue(), homeLabel));
        return true;
    }

    /** Sim-thread take-and-clear of every parked return-home request (deposit order). */
    public synchronized LinkedHashMap<Integer, ReturnHome> takeReturnHomeRequests() {
        LinkedHashMap<Integer, ReturnHome> taken =
                new LinkedHashMap<Integer, ReturnHome>(returnHomeBySeat);
        returnHomeBySeat.clear();
        return taken;
    }

    // ---- the command-transfer INPUT JOURNAL (the match record's TRANSFER rows) ---------------

    /**
     * The journal's bound. The serve drains it at every tick top, so it only ever holds the
     * begins of one tick: overflowing it needs more than 64 sessions each spending a chat rate
     * token inside one tick. A holder that never drains (a rig, a scenario) keeps at most 64
     * small rows. Past it the OLDEST row is dropped and counted.
     */
    public static final int TRANSFER_JOURNAL_CAP = 64;

    /**
     * Every begin / return-home that reached this arbiter from OFF the sim thread, with the
     * verdict it got, in deposit order — the inputs a re-simulation cannot re-derive, so the
     * serve records them and the replay feeds them back. A call ON the sim thread (the pod
     * scenario's own begins) is NOT journaled: the replay re-derives it from the operator
     * command that started the scenario, and feeding it as well would begin it twice. The sim
     * thread is the one that last called {@link #drainTransferJournal}; before the first drain
     * every caller counts as off it.
     */
    private final ArrayDeque<MatchRecordRows.TransferRow> transferJournal =
            new ArrayDeque<MatchRecordRows.TransferRow>();
    private volatile Thread journalSimThread;
    private long transferJournalOverflow;
    private long transferJournalRoundCleared;
    /** Drainer-thread only: the dropped total at the last drain, and the round of the last row. */
    private long journalDroppedSeen;
    private int journalDropRowRound = Integer.MIN_VALUE;

    /**
     * Sim-thread take-and-clear of the journal (deposit order), registering the caller as the
     * sim thread. New drops since the last drain print ONE bounded row per round, after the
     * monitor is released (this monitor stays a leaf).
     *
     */
    public List<MatchRecordRows.TransferRow> drainTransferJournal(int round) {
        Thread self = Thread.currentThread();
        if (journalSimThread != self) {
            journalSimThread = self;
        }
        List<MatchRecordRows.TransferRow> drained;
        long overflow;
        long cleared;
        synchronized (this) {
            drained = transferJournal.isEmpty() ? List.of()
                    : new ArrayList<MatchRecordRows.TransferRow>(transferJournal);
            transferJournal.clear();
            overflow = transferJournalOverflow;
            cleared = transferJournalRoundCleared;
        }
        if (overflow + cleared > journalDroppedSeen) {
            journalDroppedSeen = overflow + cleared;
            if (journalDropRowRound != round) {
                journalDropRowRound = round;
                System.out.println("npeer-serve transfer_journal_dropped overflow=" + overflow
                        + " round_cleared=" + cleared + " round=" + round
                        + " (first this round; later ones count only)");
            }
        }
        return drained;
    }

    /**
     * The re-simulation's feed of one recorded row, on the sim thread: runs the SAME law the
     * live verb ran with the recorded inputs and journals the recomputed row as if it had
     * arrived off the sim thread, so the replay's own drain records it into its reel. Returns
     * that row; the caller compares its outcome + deny reason with the recorded pair.
     *
     *
     * @throws IllegalArgumentException a verb that is neither BEGIN nor RETURN_HOME
     */
    public synchronized MatchRecordRows.TransferRow replayTransfer(
            MatchRecordRows.TransferRow recorded) {
        int seat = recorded.seat();
        MatchRecordRows.TransferRow row;
        if (recorded.verb() == MatchRecordRows.TRANSFER_VERB_BEGIN) {
            boolean fresh = flightTargetBySeat.get(seat) == null;
            row = beginRow(seat, recorded.targetHullId(), recorded.hullLabel(), recorded.nowTick(),
                    fresh, transferBeginLaw(seat, recorded.targetHullId(), recorded.hullLabel(),
                            recorded.nowTick()));
        } else if (recorded.verb() == MatchRecordRows.TRANSFER_VERB_RETURN_HOME) {
            row = returnHomeRow(seat, recorded.hullLabel(),
                    transferReturnHomeLaw(seat, recorded.hullLabel()));
        } else {
            throw new IllegalArgumentException("transfer verb " + recorded.verb()
                    + " is neither BEGIN nor RETURN_HOME");
        }
        journalLocked(row);
        return row;
    }

    /** Rows dropped past {@link #TRANSFER_JOURNAL_CAP} (cumulative). */
    public synchronized long transferJournalOverflow() {
        return transferJournalOverflow;
    }

    /** Rows {@link #clearRound} discarded undrained (cumulative). */
    public synchronized long transferJournalRoundCleared() {
        return transferJournalRoundCleared;
    }

    /** Rows deposited and not yet drained - read at the serve's end, where nonzero = a begin that landed after
     *  the last tick's drain and reached no reel (the END trailer's third journal key). */
    public synchronized int transferJournalSize() {
        return transferJournal.size();
    }

    private void journalLocked(MatchRecordRows.TransferRow row) {
        if (transferJournal.size() >= TRANSFER_JOURNAL_CAP) {
            transferJournal.pollFirst();
            transferJournalOverflow++;
        }
        transferJournal.addLast(row);
    }

    private static MatchRecordRows.TransferRow beginRow(int seatId, long targetHullId,
            String hullLabel, long nowTick, boolean fresh, TransferBegin begin) {
        int outcome = !begin.granted() ? MatchRecordRows.TRANSFER_DENIED
                : fresh ? MatchRecordRows.TRANSFER_GRANTED_FRESH
                        : MatchRecordRows.TRANSFER_GRANTED_RETARGET;
        return new MatchRecordRows.TransferRow(MatchRecordRows.TRANSFER_VERB_BEGIN, seatId,
                targetHullId, hullLabel, nowTick, outcome, begin.denyReason());
    }

    /** Under this monitor right after the law ran (it never moves the flight row it read). */
    private MatchRecordRows.TransferRow returnHomeRow(int seatId, String homeLabel,
            boolean parked) {
        Long target = flightTargetBySeat.get(seatId);
        return new MatchRecordRows.TransferRow(MatchRecordRows.TRANSFER_VERB_RETURN_HOME, seatId,
                target == null ? 0L : target.longValue(), homeLabel, -1L,
                parked ? MatchRecordRows.TRANSFER_RETURN_HOME_PARKED
                        : MatchRecordRows.TRANSFER_DENIED,
                CommandTransferFrameCodec.REASON_NONE);
    }

    /**
     * Consume EVERY flight toward {@code hullId} (target died, or became untakeable by
     * non-transfer means), deposit order — the serve loop retargets each pod back home.
     */
    public synchronized List<Integer> abortFlightsTowardHull(long hullId) {
        List<Integer> seats = new ArrayList<Integer>();
        for (var entry : flightTargetBySeat.entrySet()) {
            if (entry.getValue() == hullId) {
                seats.add(entry.getKey());
            }
        }
        for (int seat : seats) {
            flightTargetBySeat.remove(seat);
            pendingRetargetBySeat.remove(seat);
        }
        return seats;
    }

    /** Serve-loop truth: {@code hullId} was just vacated — begins toward it cool down. */
    public synchronized void noteHullVacated(long hullId, long nowTick) {
        hullCooldownUntilTick.put(hullId, nowTick + transferPolicy.leftHullCooldownTicks());
    }

    /** The hull the seat's transfer is flying toward, or null. */
    public synchronized Long transferTargetOrNull(int seatId) {
        return flightTargetBySeat.get(seatId);
    }

    /**
     * Every in-flight transfer (seatId → target hull wire id, begin order) as an independent
     * COPY — NON-consuming by design (the {@link #takeAllDismountRequests} copy precedent
     * inverted: the serve drain re-checks each row authoritatively and consumes individually
     * through {@link #transferCommitWinner}/{@link #transferAbort}, so a row that fails its
     * re-check this tick must survive for the next). The serve drain's read.
     */
    public synchronized LinkedHashMap<Integer, Long> inFlightTransfers() {
        return new LinkedHashMap<Integer, Long>(flightTargetBySeat);
    }

    /**
     * The seat's announced display name, or null until one is known — the census
     * {@code occupantLabel} feed: {@link TransferTargets} rows carry the raw
     * name and re-sanitize at publish, so the reply-label law holds there too.
     */
    public synchronized String seatNameOrNull(int seatId) {
        return nameBySeatId.get(seatId);
    }

    /**
     * A tick wait as whole wall seconds, rounded UP (a 1-tick wait reads "1s", never "0s"),
     * at the policy's own tick rate — the floors were converted from seconds at that rate, so
     * dividing by any other number would misstate the wait.
     *
     */
    private long secondsCeil(long ticks) {
        long tps = transferPolicy.ticksPerSecond();
        return (ticks + tps - 1) / tps;
    }

    /**
     * Dispatch-time claim (first-wins): DENY when the slot is dead, piloted by another seat, or
     * claimed by another seat; a grant REPLACES this seat's own earlier claim. Returns the one
     * reply line (constant + census token / seat int / bounded fold of the wire arg — inside
     * the chat single-line law by construction).
     */
    public synchronized String claim(int seatId, String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        String folded = ChatCommands.asciiLower(rawToken);
        if (!tokens.contains(folded)) {
            return "unknown module '" + bound(folded) + "' - /module lists slots";
        }
        if (deadTokens.contains(folded)) {
            return "module " + folded + " unavailable (destroyed)";
        }
        Integer pilot = mountedSeatByToken.get(folded);
        if (pilot != null && pilot != seatId) {
            return "module " + folded + " unavailable (taken by " + seatLabel(pilot) + ")";
        }
        for (var entry : claimTokenBySeat.entrySet()) {
            if (entry.getValue().equals(folded) && entry.getKey() != seatId) {
                return "module " + folded + " unavailable (taken by "
                        + seatLabel(entry.getKey()) + ")";
            }
        }
        claimTokenBySeat.put(seatId, folded);
        return "next respawn: module " + folded;
    }

    /**
     * W3-Q last-write-wins: drop the seat's pending claim, true iff one
     * existed. SHIP_SET's dispatch calls this AFTER storing a {@code /ship} override — the
     * fly-in drain consumes claims BEFORE reading the override ({@code tryMountClaim}
     * pre-mint), so a standing {@code /module} claim would silently outrank the newer pick
     * while SHIP_SET's own reply asserted a false "next respawn". Mount state
     * is untouched — this clears only the not-yet-consumed claim.
     */
    public synchronized boolean clearClaim(int seatId) {
        return claimTokenBySeat.remove(seatId) != null;
    }

    /**
     * {@code /module off}: piloting a slot ⇒ queue a voluntary dismount (coalescing — the sim
     * thread drains it as mount-loss DEPARTED without a kill; hull respawn then follows the
     * normal respawn policy, cooldown included); otherwise clear any pending claim.
     */
    public synchronized String off(int seatId) {
        if (mountedTokenBySeat.containsKey(seatId)) {
            dismountRequested.add(seatId);
            return "dismount queued - hull respawn follows the respawn policy";
        }
        if (claimTokenBySeat.remove(seatId) != null) {
            return "module claim cleared";
        }
        return "no module claim";
    }

    /** {@code /module} occupancy — ONE axis of a listing row; the "(next respawn)" claim is
     * the other, ORTHOGONAL one (they co-occur). */
    public enum ModuleOccupancy { YOU, TAKEN, DESTROYED, FREE }

    /**
     * One structured {@code /module} listing row:
     * the LSTD emitter's source shape. {@code occupantLabelOrNull} is non-null exactly
     * on {@link ModuleOccupancy#TAKEN} — the {@link #seatLabel} vocabulary (announced
     * seat-name-law name, {@code "seat N"} fallback).
     */
    public record ModuleRow(String token, ModuleOccupancy occupancy,
            String occupantLabelOrNull, boolean claimedNextRespawn) {}

    /**
     * The structured {@code /module} listing rows, occupancy-marked for THIS seat.
     * {@link #listingLines} is reimplemented ON TOP of this (single occupancy switch) so the
     * prose listing stays byte-identical by construction — the CHTD reply vocabulary and its
     * contract rows are the guard.
     */
    public synchronized List<ModuleRow> listingRows(int seatId) {
        List<ModuleRow> rows = new ArrayList<ModuleRow>(tokens.size());
        String claimed = claimTokenBySeat.get(seatId);
        for (String token : tokens) {
            Integer pilot = mountedSeatByToken.get(token);
            ModuleOccupancy occupancy;
            String occupantLabel = null;
            if (pilot != null && pilot == seatId) {
                occupancy = ModuleOccupancy.YOU;
            } else if (pilot != null) {
                occupancy = ModuleOccupancy.TAKEN;
                occupantLabel = seatLabel(pilot);
            } else if (deadTokens.contains(token)) {
                occupancy = ModuleOccupancy.DESTROYED;
            } else {
                occupancy = ModuleOccupancy.FREE;
            }
            rows.add(new ModuleRow(token, occupancy, occupantLabel, token.equals(claimed)));
        }
        return rows;
    }

    /** The {@code /module} listing: every census slot, occupancy-marked for THIS seat. */
    public synchronized List<String> listingLines(int seatId) {
        List<ModuleRow> rows = listingRows(seatId);
        List<String> lines = new ArrayList<String>(rows.size());
        for (ModuleRow row : rows) {
            String occupancy = switch (row.occupancy()) {
                case YOU -> "(you)";
                case TAKEN -> "(taken: " + row.occupantLabelOrNull() + ")";
                case DESTROYED -> "(destroyed)";
                case FREE -> "(free)";
            };
            lines.add(row.token() + " " + occupancy
                    + (row.claimedNextRespawn() ? " (next respawn)" : ""));
        }
        return lines;
    }

    /** True while the seat pilots a slot body — the {@code /suicide} refusal predicate. */
    public synchronized boolean isMounted(int seatId) {
        return mountedTokenBySeat.containsKey(seatId);
    }

    /** The slot the seat pilots, or null. */
    public synchronized String mountedTokenOrNull(int seatId) {
        return mountedTokenBySeat.get(seatId);
    }

    /**
     * Fly-in drain re-check — CONSUMES the seat's claim either way (a stale claim never
     * survives to a later fly-in): exactly one of {@code token} (mount this slot) or
     * {@code staleReason} (LOUD line, then hull fly-in fallback) is non-null.
     */
    public record ClaimTake(String token, String staleReason) {}

    /** Null when the seat has no pending claim; else the consumed {@link ClaimTake}. */
    public synchronized ClaimTake takeClaim(int seatId) {
        String claimed = claimTokenBySeat.remove(seatId);
        if (claimed == null) {
            return null;
        }
        if (deadTokens.contains(claimed)) {
            return new ClaimTake(null, "module " + claimed + " destroyed - normal respawn");
        }
        Integer pilot = mountedSeatByToken.get(claimed);
        if (pilot != null && pilot != seatId) {
            return new ClaimTake(null, "module " + claimed + " taken by " + seatLabel(pilot)
                    + " - normal respawn");
        }
        return new ClaimTake(claimed, null);
    }

    /**
     * Take-and-clear EVERY voluntary-dismount deposit, deposit order (sim-thread drain).
     * Deliberately session-INDEPENDENT: a true seat vacate while mounted
     * deposits here and the seat's session may already be gone when the drain runs — iterating
     * live sessions would strand exactly those deposits and leave a ghost module (AI-stripped,
     * pilotless).
     */
    public synchronized List<Integer> takeAllDismountRequests() {
        if (dismountRequested.isEmpty()) {
            return List.of();
        }
        List<Integer> taken = List.copyOf(dismountRequested);
        dismountRequested.clear();
        return taken;
    }

    /** Sim-thread truth: the seat now pilots {@code token} (clears its claim + any deposit). */
    public synchronized void noteMounted(int seatId, String token) {
        requireCensus(token);
        mountedSeatByToken.put(token, seatId);
        mountedTokenBySeat.put(seatId, token);
        claimTokenBySeat.remove(seatId);
        dismountRequested.remove(seatId);
    }

    /** Sim-thread truth: the seat no longer pilots its slot (death or dismount). */
    public synchronized void noteDismounted(int seatId) {
        String token = mountedTokenBySeat.remove(seatId);
        if (token != null) {
            mountedSeatByToken.remove(token);
        }
        dismountRequested.remove(seatId);
    }

    /** Sim-thread truth: the slot's live body died/left — claims on it go stale-loud. */
    public synchronized void noteSlotDead(String token) {
        requireCensus(token);
        deadTokens.add(token);
    }

    /**
     * A TRUE seat vacate (door BYE/silence — never the displacement/takeover pre-release, where
     * the mount survives into the successor): mounted ⇒ queue the serve-loop
     * dismount and drop the claim — never a bare {@link #clearSeat}, which would strip the
     * tables and leave a ghost module (AI-yielded, pilotless, no drain ever restoring it);
     * unmounted ⇒ the plain footprint clear.
     */
    public synchronized void vacateSeat(int seatId) {
        if (mountedTokenBySeat.containsKey(seatId)) {
            dismountRequested.add(seatId);
            claimTokenBySeat.remove(seatId);
            clearTransferFootprint(seatId);
        } else {
            clearSeat(seatId);
        }
    }

    /** A departing seat's whole footprint (claim, mount, deposit, W3 flight) — vacate-site call. */
    public synchronized void clearSeat(int seatId) {
        noteDismounted(seatId);
        claimTokenBySeat.remove(seatId);
        clearTransferFootprint(seatId);
    }

    /** A leaver's W3 rows: the in-flight transfer (its pod dies with the seat) + rate stamp. */
    private void clearTransferFootprint(int seatId) {
        flightTargetBySeat.remove(seatId);
        pendingRetargetBySeat.remove(seatId);
        lastBeginTickBySeat.remove(seatId);
        lastCommitTickBySeat.remove(seatId);
        returnHomeBySeat.remove(seatId);
    }

    /** Round wrap: modules re-mint, every table resets (beside clearSeatVariantOverrides). */
    public synchronized void clearRound() {
        mountedSeatByToken.clear();
        mountedTokenBySeat.clear();
        claimTokenBySeat.clear();
        deadTokens.clear();
        dismountRequested.clear();
        flightTargetBySeat.clear();
        pendingRetargetBySeat.clear();
        lastBeginTickBySeat.clear();
        lastCommitTickBySeat.clear();
        hullCooldownUntilTick.clear();
        returnHomeBySeat.clear();
        // An input no tick-top drain took dies with the round
        // state it acted on (recording it would make the replay begin a flight the round wiped); counted.
        transferJournalRoundCleared += transferJournal.size();
        transferJournal.clear();
    }

    /**
     * The door's SNMR fan teaches the arbiter a seat's resolved display name.
     * Caller law: {@code name} is codec-validated non-empty ({@code hasName()} rows
     * only — the {@code ""} sentinel never lands here); a re-announce (takeover) overwrites.
     */
    public synchronized void noteSeatName(int seatId, String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("noteSeatName takes hasName() rows only");
        }
        nameBySeatId.put(seatId, name);
    }

    /** Occupancy-reply label: the seat's announced name, or "seat N" until one is known. */
    private String seatLabel(int seatId) {
        String name = nameBySeatId.get(seatId);
        return name != null ? name : ("seat " + seatId);
    }

    private void requireCensus(String token) {
        if (!tokens.contains(token)) {
            throw new IllegalArgumentException("not a census slot token: " + token);
        }
    }

    private static String bound(String s) {
        return s.length() <= UNKNOWN_TOKEN_ECHO_CHARS
                ? s : s.substring(0, UNKNOWN_TOKEN_ECHO_CHARS);
    }
}
