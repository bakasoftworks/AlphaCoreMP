package cc.starsector.server.runtime;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import cc.starsector.server.match.MatchSeatAuthority;

/**
 * W7 B1a/B1b chat-command dispatch + the per-seat {@code /ship} override tables, carved out of
 * {@link PilotIntentDatagramServer} (the deferred-refactors PIDS seam adjudicated;
 * extracted). Concern: given an already-parsed command line, decide the reply
 * lines and apply the seat-scoped state effect. Transport stays with the door: rate limiting,
 * CHTD encode/unicast, the handled counter and the stdout audit line are the caller's.
 *
 * <p>Threading: the two tables live under THIS object's monitor (this class never takes the door's
 * endpoint lock itself, and no caller holds it across a call — the pre-carve code took the endpoint
 * lock for exactly these standalone map ops). The door-supplied callbacks MAY take the door lock
 * ({@code depositSuicide}, the listing sink, and the {@code /module} re-check {@code senderHoldsSeat}):
 * each is called with neither this monitor nor the arbiter's held (never
 * nested). The {@code /suicide} deposit is per-SESSION state
 * deliberately NOT copied on takeover (a successor session starts clean), so the flag stays on
 * the door's session object — {@link #dispatch} signals it through {@code depositSuicide} with
 * no monitor held (lock-order law: never nested). W2b {@code /module} state lives in
 * {@link SeatRebindArbiter} (its OWN monitor, same never-nested law) — this class stays
 * stateless for modules and only routes.
 */
public final class ChatCommandDispatch {

    /** The {@code /module <slot>} reply when the SENDER no
     * longer holds the seat at the post-write re-check ({@code senderHoldsSeat} false: the seat was lost
     * after the door's stale-sender check) — the claim is undone and this constant replaces the arbiter's
     * line, so the {@code chat_command} audit row never echoes a false "next respawn". Chat text law; keeps
     * the {@code refused} token, names no module (mirrors {@code AdminSeatDebugLane#GODMODE_CHAT_REFUSED_NOT_BOUND}). */
    public static final String MODULE_CLAIM_REFUSED_NOT_BOUND =
            "module claim refused - your session no longer holds that seat";

    /**
     * W3 L4 S3: the dispatch-DENY wire fan — the depositSuicide
     * idiom (the door supplies transport, dispatch decides). Called on a FRESH-begin
     * claimed/floor/cooldown denial ({@code TransferBegin.denyReason()} 10..12) so the losing
     * client turns home immediately instead of riding the 4 s ack-timeout window; the chat
     * reply line is unchanged (feel text). Retarget denials fan NOTHING (chat-only — the
     * client denyOwn guard drops mid-transit frames by design; never loosen it).
     * Public for the contract rows (the twin-gate precedent).
     */
    public interface TransferDenyFan {
        void fanDeny(long targetHullWireId, int reasonCode);
    }

    /**
     * The structured-listing wire fan — the depositSuicide/
     * TransferDenyFan idiom (the door supplies transport, dispatch decides). A no-arg listing
     * command calls this INSTEAD of returning CHTD reply lines (the 16-line feed dump is
     * retired): the door mints the per-seat listingId (latest-wins), encodes via
     * {@link ListingEmitter#encodeListing}, and drips the frames ≤8/tick unicast to the
     * requesting session. {@code entries} is never empty (dispatch keeps the short CHTD
     * "none available" reply for empty sources — the emitter's empty⇒no-frames law). Public
     * for the contract rows (the TransferDenyFan precedent).
     */
    public interface ListingSink {
        void emitListing(int kind, List<ListingFrameCodec.ListingEntry> entries);
    }

    /** W7 B1b /ship state. Override = the seat's picked next-respawn variant — always
     * a CANONICAL allowlist id (server-authored; the wire string never lands here), cleared by
     * the serve loop at round wrap (v1 law: reset returns every seat to its descriptor variant).
     * Fielded = the seat's last deploy-recorded hull (the listing's "(piloting)" mark). Reader
     * thread writes overrides, serve loop reads/clears + writes fielded — both under this
     * object's monitor. Iteration order = insertion order (LinkedHashMap). */
    private final LinkedHashMap<Integer, String> overrideBySeat =
            new LinkedHashMap<Integer, String>();
    private final LinkedHashMap<Integer, String> fieldedBySeat =
            new LinkedHashMap<Integer, String>();

    /**
     * Decide the reply for a parsed command —
     * attribution = the server-stamped {@code seatId} ONLY (a security law: nothing identity-like
     * is read from the wire text). {@code /suicide} only signals the deposit — the kill is
     * sim-thread work the serve loop drains on the next advance; the mounted/dead-latch check
     * here is ADVISORY (an honest immediate reply), the drain re-checks. {@code /ship} SET
     * validates against the door's pickable allowlist and stores the CANONICAL id (the wire
     * string itself is never stored); LIST emits the allowlist marked for THIS seat as LSTD
     * frames via {@code listingSink} (SS382 L3 — every no-arg listing rides the sink and
     * returns ZERO reply lines; the CHTD feed dump is retired; usage/deny/rate replies stay
     * CHTD). A SET arg with no exact fold match is a fold-SUBSTRING filter: the matching
     * subset rides the sink the same way (the /debugships shape), only a
     * zero-match arg answers CHTD. A door with no/empty allowlist replies "picking unavailable". Every reply line is a constant
     * plus a bounded echo of already-law-conforming text, so each stays inside
     * {@code validateChatText}. Authority reads run with no monitor held (its own monitor;
     * never nested). W3 {@code /transfer}: {@code transferTargets} is the
     * serve-published census (null until the serve arms it — honest "unavailable", the
     * pickableVariants pattern); the eligibility branch here is the packet-defaults-1 CALLER
     * side, ADVISORY like every dispatch reply (the serve drain re-checks at commit);
     * {@code nowTick} is the door's serve-tick heartbeat (at most one frame stale — fine for
     * seconds-scale begin floors). W3 L1b-ii: the arbiter now mints on EVERY
     * serve (the transfer verbs + always-on vacate drain need it), so MODULE_* gates on the
     * separate {@code moduleVerbsArmed} twin (fortress row + bound mount hook) — the
     * arbiter-null guard stays too (contract fakes thread none). Class + this method are
     * public for exactly the twin-gate contract rows; every other member stays package-local.
     */
    public List<String> dispatch(ChatCommands.Parsed parsed, int seatId,
            MatchSeatAuthority authority, PickableVariants pickableVariants,
            SeatRebindArbiter rebindArbiter, boolean moduleVerbsArmed,
            TransferTargets transferTargets, long nowTick, TransferDenyFan transferDenyFan,
            ListingSink listingSink, Runnable depositSuicide) {
        return dispatch(parsed, seatId, 0L, authority, pickableVariants, rebindArbiter,
                moduleVerbsArmed, transferTargets, nowTick, transferDenyFan, listingSink,
                depositSuicide);
    }

    /**
     * The {@code ownWireId} form — the seat's FIXED
     * wire id ({@code session.shipId()}, server truth, never a census lookup). Under the
     * ONE-ENTITY pod that id rides the POD mid-flight, and the census never lists a
     * pod ({@code isShuttlePod} exclusion), so the OCCUPIED-by-self row the return-home
     * verb keyed on cannot exist while a flight does: the client's Tab self-pick
     * ({@code /transfer #<own>}) drew "unknown target" + a reason-13 fan every time. The wire
     * form now resolves the seat's OWN id to the verb; with no flight it falls through to the
     * unknown-target deny exactly as before. 0 = unknown (the 11-arg callers). Delegates with an
     * always-true {@code senderHoldsSeat} (no door, no re-check — the contract fixtures).
     */
    public List<String> dispatch(ChatCommands.Parsed parsed, int seatId, long ownWireId,
            MatchSeatAuthority authority, PickableVariants pickableVariants,
            SeatRebindArbiter rebindArbiter, boolean moduleVerbsArmed,
            TransferTargets transferTargets, long nowTick, TransferDenyFan transferDenyFan,
            ListingSink listingSink, Runnable depositSuicide) {
        return dispatch(parsed, seatId, ownWireId, authority, pickableVariants, rebindArbiter,
                moduleVerbsArmed, transferTargets, nowTick, transferDenyFan, listingSink,
                depositSuicide, () -> true);
    }

    /**
     * The {@code senderHoldsSeat} form — carries the
     * body; the door ({@code PidsChatVerbs}) is its one production caller. {@code senderHoldsSeat} =
     * the door's post-write re-check (one door-lock take; false also counts {@code chat_stale_drop=}).
     * Asked by {@code /module <slot>} ONLY, exactly once, AFTER {@link SeatRebindArbiter#claim}
     * returns (the arbiter monitor released — never nested), so a seat lost after the door's
     * stale-sender check but before the write is seen. False ⇒ an UNCONDITIONAL
     * {@link SeatRebindArbiter#clearClaim} — the reply String is never parsed as a grant, so a denied
     * claim also drops the seat's earlier own claim (the leaver's footprint the true-vacate drain
     * clears anyway; the reader thread is serial, so no other session claimed that seat meanwhile) —
     * and {@link #MODULE_CLAIM_REFUSED_NOT_BOUND} replaces the reply. {@code /module off} (idempotent)
     * and {@code /transfer} (no undo, ruling R3) never ask it.
     */
    public List<String> dispatch(ChatCommands.Parsed parsed, int seatId, long ownWireId,
            MatchSeatAuthority authority, PickableVariants pickableVariants,
            SeatRebindArbiter rebindArbiter, boolean moduleVerbsArmed,
            TransferTargets transferTargets, long nowTick, TransferDenyFan transferDenyFan,
            ListingSink listingSink, Runnable depositSuicide, BooleanSupplier senderHoldsSeat) {
        switch (parsed.kind()) {
            case SUICIDE -> {
                // W2b: a module-piloting seat must never free-kill fortress
                // modules — refuse BEFORE the alive-check (mounted implies alive; both
                // checks are advisory, the drain re-checks authoritatively).
                if (rebindArbiter != null && rebindArbiter.isMounted(seatId)) {
                    return List.of("you are a fortress module - /module off first");
                }
                boolean alive = authority != null && authority.seatMachineArmed()
                        && authority.isMounted(seatId) && !authority.isDeadLatched(seatId);
                if (!alive) {
                    return List.of("no live ship");
                }
                depositSuicide.run();
                return List.of("self-destruct queued");
            }
            case MODULE_LIST -> {
                if (rebindArbiter == null || !moduleVerbsArmed) {
                    return List.of("/module unavailable on this server");
                }
                // SS382 L3: structured rows → LSTD (the feed dump retired). Empty census =
                // the honest CHTD short reply (also shields the audit line's reply echo —
                // the old formatListing(empty) path would have returned zero lines).
                List<SeatRebindArbiter.ModuleRow> rows = rebindArbiter.listingRows(seatId);
                if (rows.isEmpty()) {
                    return List.of("no modules this round");
                }
                listingSink.emitListing(ListingFrameCodec.KIND_MODULE,
                        ListingEmitter.moduleEntries(rows));
                return List.of();
            }
            case MODULE_SET -> {
                if (rebindArbiter == null || !moduleVerbsArmed) {
                    return List.of("/module unavailable on this server");
                }
                String reply = rebindArbiter.claim(seatId, parsed.arg());
                if (!senderHoldsSeat.getAsBoolean()) {
                    rebindArbiter.clearClaim(seatId); // unconditional: never parse `reply` as a grant
                    return List.of(MODULE_CLAIM_REFUSED_NOT_BOUND);
                }
                return List.of(reply);
            }
            case MODULE_OFF -> {
                if (rebindArbiter == null || !moduleVerbsArmed) {
                    return List.of("/module unavailable on this server");
                }
                return List.of(rebindArbiter.off(seatId));
            }
            case TRANSFER_LIST -> {
                if (transferTargets == null || rebindArbiter == null) {
                    return List.of("/transfer unavailable on this server");
                }
                // The requesting seat's OWN team's table (a seat with no team yet: the south one).
                List<TransferTargets.Target> targets = transferTargets.targetsSnapshot(seatId);
                if (targets.isEmpty()) {
                    return List.of("no transfer targets this round");
                }
                listingSink.emitListing(ListingFrameCodec.KIND_TRANSFER,
                        ListingEmitter.transferEntries(seatId, targets));
                return List.of();
            }
            case TRANSFER_SET -> {
                // Every fresh-begin refusal fans KIND_DENY (FRESH_DENIED)
                // beside its chat line — the client's sole collapse signal is the frame
                // (status denies used to be chat-only = the W3-A ack-timeout strand).
                // Pre-resolve sites fan target 0 (legal on DENY); retarget denials stay
                // chat-only by design (the client denyOwn guard drops mid-transit frames).
                if (transferTargets == null || rebindArbiter == null) {
                    transferDenyFan.fanDeny(0L, CommandTransferFrameCodec.REASON_FRESH_DENIED);
                    return List.of("/transfer unavailable on this server");
                }
                // F-B: a module-piloting seat's begin is LEGAL — the old
                // mounted pre-deny is LIFTED (transfer FROM a module
                // is allowed; the serve-lane commit owns the dismount side).
                return transferTail(transferTargets.resolveOrNull(seatId, parsed.arg()),
                        parsed.arg(), seatId, rebindArbiter, nowTick, transferDenyFan);
            }
            case TRANSFER_WIRE -> {
                // W3 L4 S2: the client trigger's numeric form — the digit string
                // is grammar-validated ([0-9] ≤19, Long-bounded, >0), so the re-parse below is
                // unreachable; it stays as the fail-closed twin (security), denying like a
                // census miss rather than trusting the Parsed contract.
                if (transferTargets == null || rebindArbiter == null) {
                    transferDenyFan.fanDeny(0L, CommandTransferFrameCodec.REASON_FRESH_DENIED);
                    return List.of("/transfer unavailable on this server");
                }
                // F-B: mounted begins pass here too (the TRANSFER_SET lift's numeric twin).
                TransferTargets.Target resolved;
                long askedWireId;
                try {
                    askedWireId = Long.parseLong(parsed.arg());
                    resolved = transferTargets.resolveByWireIdOrNull(seatId, askedWireId);
                } catch (NumberFormatException unreachableByGrammar) {
                    askedWireId = 0L;
                    resolved = null;
                }
                if (resolved == null && ownWireId > 0L && askedWireId == ownWireId) {
                    // The seat's own wire id = the pod mid-flight
                    // (census-unlisted) ⇒ the return-home verb (see the overload doc). The
                    // home label = the seat name (the vacated hull carries it as its ship
                    // name, "transferring to <seat name>"); no flight ⇒ the unknown-target deny.
                    String home = rebindArbiter.seatNameOrNull(seatId);
                    if (rebindArbiter.transferReturnHome(seatId,
                            home == null ? "your ship" : home)) {
                        return List.of();
                    }
                }
                return transferTail(resolved, "#" + parsed.arg(), seatId, rebindArbiter,
                        nowTick, transferDenyFan);
            }
            case SHIP_SET -> {
                if (pickableVariants == null || pickableVariants.ids().isEmpty()) {
                    return List.of("/ship picking unavailable on this server");
                }
                String canonical = pickableVariants.canonicalIdOrNull(parsed.arg());
                if (canonical == null) {
                    // /ship <name> opens the picker with
                    // the search pre-entered, like /spawnfoe <name>: a non-exact arg is a fold-
                    // substring FILTER over the pickable view (the /debugships + /spawnfoe UX) —
                    // the matching subset rides the KIND_SHIP listing with this seat's marks; zero
                    // matches keep a constant + bounded-echo reply. The view already excludes
                    // demoted ids (PickableVariants.ids()), so a hidden stem never lists here.
                    // The filter folds each ship's id, display name
                    // and hull id, so the no-match tail names all three; the head stays the client latch's prefix.
                    List<String> matches = pickableVariants.idsMatching(parsed.arg());
                    if (matches.isEmpty()) {
                        return List.of("no variants match '" + boundEcho(parsed.arg())
                                + "' - /ship searches names, hulls or ids");
                    }
                    String fielded;
                    String override;
                    synchronized (this) {
                        fielded = fieldedBySeat.get(seatId);
                        override = overrideBySeat.get(seatId);
                    }
                    // Each row carries the ship's display name and hull id
                    // the kept copies list newest first
                    listingSink.emitListing(ListingFrameCodec.KIND_SHIP,
                            ListingEmitter.shipEntries(
                                    ListingEmitter.keptNewestFirst(matches, pickableVariants::keptAtMsOrNull),
                                    fielded, override,
                                    pickableVariants::displayNameOrNull, pickableVariants::hullIdOrNull));
                    return List.of();
                }
                synchronized (this) {
                    // A demote's clear runs under THIS monitor after the
                    // overlay swap — re-check here so a resolve that raced the swap never stores.
                    if (pickableVariants.isHidden(canonical)) {
                        return List.of("unknown variant '" + boundEcho(parsed.arg())
                                + "' - /ship lists ids");
                    }
                    overrideBySeat.put(seatId, canonical);
                }
                // W3-Q last-write-wins: the fly-in consumes a module
                // claim BEFORE reading this override, so a standing /module claim would
                // silently outrank this newer pick — clear it and say so. No moduleVerbsArmed
                // gate: unarmed serves can hold no claims, the clear is a harmless false.
                if (rebindArbiter != null && rebindArbiter.clearClaim(seatId)) {
                    return List.of(pickReply(pickableVariants, canonical) + " (module claim cleared)");
                }
                return List.of(pickReply(pickableVariants, canonical));
            }
            case SHIP_LIST -> {
                if (pickableVariants == null || pickableVariants.ids().isEmpty()) {
                    return List.of("/ship picking unavailable on this server");
                }
                String fielded;
                String override;
                synchronized (this) {
                    fielded = fieldedBySeat.get(seatId);
                    override = overrideBySeat.get(seatId);
                }
                // Ids non-empty by the guard above, so this branch always emits.
                // Each row carries the ship's display name and hull id
                // the kept copies list newest first
                listingSink.emitListing(ListingFrameCodec.KIND_SHIP,
                        ListingEmitter.shipEntries(
                                ListingEmitter.keptNewestFirst(pickableVariants.ids(), pickableVariants::keptAtMsOrNull),
                                fielded, override,
                                pickableVariants::displayNameOrNull, pickableVariants::hullIdOrNull));
                return List.of();
            }
            case USAGE, UNKNOWN -> {
                return List.of(parsed.reply());
            }
            default -> throw new IllegalStateException(
                    "non-command text reached dispatch: " + parsed.kind());
        }
    }

    /**
     * The {@code /debugships} verb body — the {@code /ship} pipeline over the
     * DEBUG catalogue (the FULL engine registry; admin-gated at the DOOR, never here). LIST ⇒
     * the whole catalogue as KIND_DEBUGSHIP LSTD frames (a dedicated kind so the
     * picker pastes {@code /debugships <token>} — the KIND_SHIP paste denied registry-only ids
     * against the mod allowlist; the 1785-entry codec cap fits the full 785-id catalogue, and
     * {@code totalAvailable} carries the "+N more" truth past it); SET = exact fold-match ⇒ PICK
     * via {@link #seedOverride} (the SAME store/monitor as {@code /ship}, W3-Q module-claim
     * clear included), no exact match ⇒ the arg is a SUBSTRING FILTER (D1) emitting the
     * matching subset, zero matches ⇒ a bounded no-match reply. Piloting/next-respawn marks
     * read under the SAME dispatch monitor as {@code /ship}. Public for the contract rows
     * (the dispatch/twin-gate precedent — the store stays package-local).
     */
    /**
     * The {@code /ship <id>} pick's reply names the ship the way the listing and the picker do - its quoted display
     * name, the id only when none is remembered (the {@code SeatVariantPicks.submittedName} shape, bounded by
     * {@link CatalogPromotionLane#chatShipName}): a kept copy's stem carries a hex tail no player should read back.
     *The client's Fly line
     * tests only the {@code next respawn} head (editor.js hostFlyLine); the debug pick keeps its id.
     */
    static String pickReply(PickableVariants pickableOrNull, String canonical) {
        String name = pickableOrNull == null ? null : pickableOrNull.displayNameOrNull(canonical);
        return "next respawn: '" + CatalogPromotionLane.chatShipName(name, canonical) + "'";
    }

    public List<String> debugShips(ChatCommands.Parsed parsed, int seatId,
            PickableVariants debugVariants, SeatRebindArbiter rebindArbiter,
            ListingSink listingSink) {
        if (debugVariants == null || debugVariants.ids().isEmpty()) {
            return List.of("debug picking unavailable on this server");
        }
        if (parsed.kind() == ChatCommands.Kind.DEBUGSHIPS_SET) {
            String canonical = debugVariants.canonicalIdOrNull(parsed.arg());
            if (canonical != null) {
                if (seedOverride(seatId, canonical, rebindArbiter, null).claimCleared()) {
                    return List.of("next respawn: " + canonical + " (module claim cleared)");
                }
                return List.of("next respawn: " + canonical);
            }
            List<String> matches = debugVariants.idsMatching(parsed.arg());
            if (matches.isEmpty()) {
                return List.of("no variants match '" + boundEcho(parsed.arg()) + "'");
            }
            return emitMarkedShips(matches, seatId, listingSink);
        }
        return emitMarkedShips(debugVariants.ids(), seatId, listingSink);
    }

    /** Emit ids as KIND_DEBUGSHIP entries marked for THIS seat — the SHIP_LIST read discipline
     * (fielded/override under this object's monitor), shared by both /debugships listings. */
    private List<String> emitMarkedShips(List<String> ids, int seatId, ListingSink listingSink) {
        String fielded;
        String override;
        synchronized (this) {
            fielded = fieldedBySeat.get(seatId);
            override = overrideBySeat.get(seatId);
        }
        listingSink.emitListing(ListingFrameCodec.KIND_DEBUGSHIP,
                ListingEmitter.debugShipEntries(ids, fielded, override));
        return List.of();
    }

    /**
     * W15 v0 step 3(f) {@code /customs}: the caller's OWN accepted customs as a KIND_SHIP listing
     * (the picker pastes {@code /ship <id>} — own customs resolve there since step 3(c); a NEW
     * listing kind waits for the client roll: the kit reader fail-closes on kind > 8). Marks read
     * under this monitor like SHIP_LIST. Empty ⇒ the short CHTD reply (the emitter's
     * empty⇒no-frames law). Public for the contract rows.
     */
    public List<String> emitOwnCustoms(List<String> ownIds, int seatId, ListingSink listingSink) {
        if (ownIds == null || ownIds.isEmpty()) {
            return List.of("no accepted custom variants yet (submit one from the join window)");
        }
        String fielded;
        String override;
        synchronized (this) {
            fielded = fieldedBySeat.get(seatId);
            override = overrideBySeat.get(seatId);
        }
        listingSink.emitListing(ListingFrameCodec.KIND_SHIP,
                ListingEmitter.shipEntries(ownIds, fielded, override));
        return List.of();
    }

    /** Peer-build sharing v2: bare {@code /share} — the caller's OWN accepted ids with
     * their share bit as a KIND_SHARE listing (empty ⇒ the short CHTD reply, the emitter's
     * empty⇒no-frames law). Rows are the door's ledger read (live-owner law); nothing here is
     * wire text. Public for the contract rows. */
    public static List<String> emitShareRows(List<SubmittedVariantLedger.ShareRow> rows,
            ListingSink listingSink) {
        if (rows == null || rows.isEmpty()) {
            return List.of("no accepted custom variants yet (submit one from the join window)");
        }
        listingSink.emitListing(ListingFrameCodec.KIND_SHARE, ListingEmitter.shareEntries(rows));
        return List.of();
    }

    /** {@code /builds} — every shared id as a KIND_BUILDS listing (a pick saves locally on
     * the client); empty ⇒ the short CHTD reply. Public for the contract rows. */
    public static List<String> emitBuilds(List<String> sharedIds, ListingSink listingSink) {
        if (sharedIds == null || sharedIds.isEmpty()) {
            return List.of("no shared builds yet (share one of yours with /share)");
        }
        listingSink.emitListing(ListingFrameCodec.KIND_BUILDS, ListingEmitter.buildsEntries(sharedIds));
        return List.of();
    }

    /** The shared /transfer tail BOTH arg forms converge on after their resolve (W3 L4 S2):
     * null = the unknown-target deny (for the wire form, census membership IS the gate — an
     * id outside the seat's team's table denies exactly like a token miss); then the caller-side
     * status switch (packet defaults 1: occupied hard-denies self included, GRACE stays
     * reserved) and the contest-core begin, whose floors/claim-lock own every remaining deny.
     * {@code echoArg} is already law-conforming ({@code #}+digits or the raw stem token). */
    private static List<String> transferTail(TransferTargets.Target target, String echoArg,
            int seatId, SeatRebindArbiter rebindArbiter, long nowTick,
            TransferDenyFan transferDenyFan) {
        if (target == null) {
            transferDenyFan.fanDeny(0L, CommandTransferFrameCodec.REASON_FRESH_DENIED);
            return List.of("unknown target '" + boundEcho(echoArg)
                    + "' - /transfer lists targets");
        }
        switch (target.status()) {
            case OCCUPIED -> {
                // The seat's OWN hull while its shuttle is in
                // flight = the RETURN-HOME verb — no deny fan (retarget class, chat-only)
                // and no reply line: the serve drain's abort fan narrates
                // "returning to <hull>" as the pod turns. No flight ⇒ the plain own deny.
                if (target.occupantSeatId() == seatId
                        && rebindArbiter.transferReturnHome(seatId, target.label())) {
                    return List.of();
                }
                transferDenyFan.fanDeny(target.hullWireId(),
                        CommandTransferFrameCodec.REASON_FRESH_DENIED);
                return List.of(target.occupantSeatId() == seatId
                        ? "you are piloting " + target.label()
                        : target.label() + " is occupied by "
                                + target.occupantOrSeatLabel());
            }
            case GRACE -> {
                transferDenyFan.fanDeny(target.hullWireId(),
                        CommandTransferFrameCodec.REASON_FRESH_DENIED);
                return List.of(target.label() + " is reserved for "
                        + target.occupantOrSeatLabel());
            }
            default -> { }
        }
        SeatRebindArbiter.TransferBegin begin = rebindArbiter.transferBegin(
                seatId, target.hullWireId(), target.label(), nowTick);
        if (begin.denyReason() != CommandTransferFrameCodec.REASON_NONE) {
            transferDenyFan.fanDeny(target.hullWireId(), begin.denyReason());
        }
        return List.of(begin.replyLine());
    }

    /** W7 B1b: the seat's {@code /ship}-picked next-respawn variant (canonical allowlist id),
     * or null = descriptor-pure. Serve-loop read at every seat fly-in. Public for the
     * contract rows (the {@code debugShips} precedent — the store stays package-local). */
    public synchronized String overrideOrNull(int seatId) {
        return overrideBySeat.get(seatId);
    }

    /** round-wrap law: a pick belongs to a seat's live
     * TENURE — at the wrap the store keeps ONLY the live (occupied) seats'
     * entries; a vacated seat's entry is dropped so a later occupant never inherits the
     * previous pilot's (author-scoped) custom. Returns the number dropped. */
    synchronized int retainOverrides(java.util.Set<Integer> liveSeatIds) {
        int before = overrideBySeat.size();
        overrideBySeat.keySet().retainAll(liveSeatIds);
        return before - overrideBySeat.size();
    }

    /** The tenure clear — a FRESH claim whose
     * post-spool apply stores nothing drops the seat's standing override, so a new tenure never
     * flies the previous tenure's pick. A {@code synchronized} leaf (no other monitor taken
     * inside). Returns the removed id, or null when the seat had none (then nothing prints). */
    synchronized String clearOverride(int seatId) {
        String old = overrideBySeat.remove(seatId);
        if (old != null) {
            System.out.println("chat-command tenure_override_cleared seat=" + seatId + " id=" + old
                    + " (a pick dies with its tenure)");
        }
        return old;
    }

    /** The admit-time seed of the join-request variant pick —
     * the SAME store + monitor as {@code SHIP_SET} (the pick == a {@code /ship} pick made before
     * the first fly-in; it rides the round wrap while its seat stays OCCUPIED —
     * {@link #retainOverrides} drops a vacated seat's entry at the wrap, and a
     * fresh tenure whose apply stores nothing drops it via {@link #clearOverride}; the
     * W7 v1 descriptor-pure wrap is gone). {@code canonical} is
     * already the allowlist's canonical id (the door resolved it through
     * {@link PickableVariants#canonicalIdOrNull}). Returns the {@link SeatVariantPicks.Seed} outcome:
     * {@code SEEDED_CLAIM_CLEARED} when a standing {@code /module} claim was cleared (the W3-Q
     * last-write-wins law, exactly like SHIP_SET), {@code REFUSED_HIDDEN} when nothing was stored
     * (the caller words its VARIANT line as a FALLBACK, never a success). The arbiter's
     * monitor is taken AFTER ours is released — never nested. Package-private: the door is the
     * one writer. {@code hiddenCheckOrNull}: the mod allowlist whose live
     * overlay may have HIDDEN {@code canonical} between the caller's resolve and this store — a
     * demote's clear runs under this monitor, so the re-check here closes every ordering (a
     * refused seed logs and stores nothing; null = no overlay applies, e.g. {@code /debugships}). */
    SeatVariantPicks.Seed seedOverride(int seatId, String canonical, SeatRebindArbiter rebindArbiter,
            PickableVariants hiddenCheckOrNull) {
        synchronized (this) {
            if (hiddenCheckOrNull != null && hiddenCheckOrNull.isHidden(canonical)) {
                System.out.println("chat-command SEED_REFUSED seat=" + seatId + " id=" + canonical
                        + " reason=hidden (demoted between resolve and store; the standing pick is unchanged)");
                return SeatVariantPicks.Seed.REFUSED_HIDDEN;
            }
            overrideBySeat.put(seatId, canonical);
        }
        return rebindArbiter != null && rebindArbiter.clearClaim(seatId)
                ? SeatVariantPicks.Seed.SEEDED_CLAIM_CLEARED : SeatVariantPicks.Seed.SEEDED;
    }

    /**
     * An operator {@code custom-ban} / {@code custom-reject} banned
     * these canonical ids — drop every seat's seeded next-respawn override that NAMES one of them,
     * so a banned custom stops respawning NOW instead of at round wrap. The override VALUE is the
     * ledger's minted canonical (the accept seed writes it verbatim), so the scan is an EXACT
     * value match — never the localId confusable fold. Returns seat → the cleared canonical, seat
     * order (empty = nothing named). Public for the contract rows (the {@link #overrideOrNull}
     * precedent — the store stays package-local); the door is the one production caller.
     */
    public synchronized Map<Integer, String> clearOverridesNaming(Collection<String> canonicalIds) {
        Map<Integer, String> cleared = new LinkedHashMap<Integer, String>();
        if (canonicalIds == null || canonicalIds.isEmpty() || overrideBySeat.isEmpty()) {
            return cleared;
        }
        Iterator<Map.Entry<Integer, String>> it = overrideBySeat.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, String> e = it.next();
            if (canonicalIds.contains(e.getValue())) {
                cleared.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return cleared;
    }

    /** W7 B1b: record a seat fly-in's DEPLOYED variant (the listing's {@code (piloting)} mark;
     * overwrites — last deploy wins). */
    synchronized void recordFielded(int seatId, String variantId) {
        fieldedBySeat.put(seatId, variantId);
    }

    /** The seat's last DEPLOYED variant, or null before its first fly-in
     * (the ops status file's ship column reads the fielded truth, never the descriptor guess). */
    synchronized String fieldedOrNull(int seatId) {
        return fieldedBySeat.get(seatId);
    }

    /** The unknown-variant reply's echo bound (raw is already law-conforming printable ASCII —
     * the bound only keeps the reply line well inside the CHTD length law). Package-visible:
     * the door's {@code /spawnfoe} unknown-variant reply shares the law. */
    static String boundEcho(String raw) {
        return raw.length() <= 24 ? raw : raw.substring(0, 24);
    }
}
