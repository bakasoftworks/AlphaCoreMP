package cc.starsector.server.runtime;

import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantSubmitFrameCodec.VariantSubmit;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * W15 v0 (the submission lifecycle and caps): the server's
 * ledger of submitted custom variants — the reader-thread {@link #offer} gate (author caps,
 * global cap, dup-safety) and the sim-thread {@link #drain} / {@link #mint} /
 * {@link #recordAccepted} half. Every method is {@code synchronized}: the datagram reader deposits,
 * the serve tick drains — the same two-thread shape as the door's AWSD-NACK spool.
 *
 * <p>Laws pinned here: ids are SERVER-MINTED
 * {@code cc-v<serial>-<slug>} — the player owns only {@code displayName}; per author
 * {@code perSeat} accepted + {@link #PENDING_PER_AUTHOR} pending; {@link #GLOBAL_CAP} accepted +
 * pending across the serve; a re-submission of a body already accepted by the SAME author (the
 * client's 10 s receipt timeout re-sends dup-safe) is answered with the SAME canonical id —
 * identity = {@link #sameBody the full VDEF body with the id normalised} (the {@link #bodyCrc CRC32}
 * only indexes the lookup), so the client-local id is free to differ. v0 has NO replace: the {@code perSeat}-th+1 submission is refused
 * {@code custom_seat_cap} (eviction of a live registry entry needs the no-live-hull check — step 3).
 *
 * <p>Persistence ({@code <persistDir>/<stamp>/<canonical>.variant} + {@code ledger.ndjson}) is
 * {@link CustomVariantPersistSink}'s off-thread write, not this class's — the ledger is in-memory
 * truth; a server restart forgets customs by design (the persisted files
 * are evidence, not catalog). Step 3(f) operator verbs ({@link #ban}, {@link #banAllByAuthor},
 * {@link #dropPendingForSeat}) run on the sim thread like the drain.
 */
public final class SubmittedVariantLedger {

    /** Accepted + pending across the whole serve. */
    public static final int GLOBAL_CAP = 64;
    /** Un-drained submissions one author may hold (a flood bound; the reader answers the excess). */
    public static final int PENDING_PER_AUTHOR = 1;
    /** Sim-thread drain bound per tick. */
    public static final int DRAIN_PER_TICK = 4;
    /** Reserved id prefix (kit files carrying it are refused at scan — step 3). */
    public static final String ID_PREFIX = "cc-v";
    /** Slug cap — keeps every minted id well inside {@link VariantDefinitionFrameCodec#ID_MAX_CHARS}. */
    public static final int SLUG_MAX_CHARS = 16;

    /** One reader-thread deposit awaiting the sim thread. {@code submitFrame} = the submit frame verbatim
     *  as the client's codec framed it (EMPTY = not carried: the frame failed to encode, or a caller on
     *  the pre-frame arity), carried so the drain's record row holds the body without encoding on the
     *  sim thread. Record equality on it is by REFERENCE (the ledger removes the instance it drained). */
    public record Pending(long sessionId, int seatId, String authorKey, int submitSeq,
            VariantDefinition submitted, long bodyCrc, byte[] submitFrame) {
        private static final byte[] EMPTY = new byte[0];

        public Pending {
            Objects.requireNonNull(authorKey, "authorKey");
            Objects.requireNonNull(submitted, "submitted");
            submitFrame = submitFrame == null ? EMPTY : submitFrame;
        }

        /** The pre-frame arity (no carried frame). */
        public Pending(long sessionId, int seatId, String authorKey, int submitSeq,
                VariantDefinition submitted, long bodyCrc) {
            this(sessionId, seatId, authorKey, submitSeq, submitted, bodyCrc, EMPTY);
        }
    }

    /** One accepted custom: the minted id + who owns it + the canonical (re-idd) definition.
     * {@code unusedOp} = the engine pass's OP headroom under the spawn person ({@code -1} =
     * not reported — a fake registrar / the pre-3(f) shape); ops-row + persistence data only. */
    public record Accepted(String canonicalId, long serial, String authorKey, long sessionId, int seatId,
            String localId, long bodyCrc, VariantDefinition canonical, int unusedOp) {
        /** The pre-step-3(f) arity (no OP reading). */
        public Accepted(String canonicalId, long serial, String authorKey, long sessionId, int seatId,
                String localId, long bodyCrc, VariantDefinition canonical) {
            this(canonicalId, serial, authorKey, sessionId, seatId, localId, bodyCrc, canonical, -1);
        }
    }

    /** The reader-thread verdict of {@link #offer}. */
    public enum OfferKind {
        /** Queued for the sim thread; NO receipt now (the drain mails it). */
        DEPOSITED,
        /** Already accepted by this author — answer ACCEPT with {@link Offer#canonicalId()} now. */
        DUPLICATE,
        /** The same body is already pending for this author — say nothing; the pending answers. */
        PENDING_DUPLICATE,
        /** Refused at the gate — answer REJECT with {@link Offer#reasonToken()} now. */
        REFUSED
    }

    public record Offer(OfferKind kind, String canonicalId, String reasonToken) {
        static Offer deposited() {
            return new Offer(OfferKind.DEPOSITED, null, null);
        }

        static Offer duplicate(String canonicalId) {
            return new Offer(OfferKind.DUPLICATE, canonicalId, null);
        }

        static Offer pendingDuplicate() {
            return new Offer(OfferKind.PENDING_DUPLICATE, null, null);
        }

        static Offer refused(String token) {
            return new Offer(OfferKind.REFUSED, null, token);
        }
    }

    private final int perSeat;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** Drained but not yet recorded (the sim thread's engine pass is running) — still "pending"
     * to the pick fallback ({@link #ownPendingLocalId}), so a {@code /ship} landing inside the
     * pass is held, never refused. */
    private final List<Pending> inFlight = new ArrayList<>();
    /** canonicalId → entry, insertion order = mint order. */
    private final Map<String, Accepted> accepted = new LinkedHashMap<>();
    /** authorKey → (bodyCrc → canonicalIds) for the dup-safe answer. The CRC is
     * an INDEX, never the identity — a hit is confirmed by {@link #sameBody} before it answers
     * DUPLICATE, and two bodies colliding in CRC32 each keep their own canonical here. */
    private final Map<String, Map<Long, List<String>>> acceptedByAuthorCrc = new HashMap<>();
    /** W15 v0 step 3(f): operator-BANNED canonical ids — unpickable + unlisted + unspooled from
     * now on; the engine registry entry stays (v0 has no eviction: a live hull/corpse/RAPP row
     * may still name it), so {@link #acceptedCount} keeps counting it against the caps. */
    private final java.util.Set<String> banned = new java.util.LinkedHashSet<>();
    /** Peer-build sharing v2 ({@code variant-maker-peer-builds.md}): the SHARE bit = a
     * server-owned LISTING filter over the accepted set (every body is already resident on every
     * peer — sharing never moves a body). The three sets ride this monitor like {@link #banned};
     * {@link Accepted} stays immutable. {@code shared} = listed under {@code /builds};
     * {@code unsharedByOwner} = the author said {@code /unshare} — the fielding auto-share
     * ({@link #autoShareFielded}) never re-lists it (an explicit {@code /share} does, and clears
     * the mark); {@code announced} = the once-per-id-per-match broadcast latch (a share/unshare/
     * share toggle mints ONE line, ever). */
    private final java.util.Set<String> shared = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> unsharedByOwner = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> announced = new java.util.LinkedHashSet<>();
    /** Ownership = the LIVE session that owns the id; seeded from {@link Accepted#sessionId}
     * at record time. A departed author's rows are immutable to everyone else — but the SAME
     * author key re-joining (a new session; the kit re-offers its pick at every connect and is
     * dup-answered the OLD canonical) may RECLAIM them once the door has verified the recorded
     * owner session is gone ({@link #reclaimOwner}; the door owns the liveness fact). Never a
     * {@code clientId}-only check: a live author's rows cannot be taken by a HELLO-string spoof. */
    private final Map<String, Long> ownerSession = new HashMap<>();
    private long nextSerial = 1;
    private int rejectedAtGate;
    private int rejectedByRegistrar;

    /** The boot id table every body encode (bodyCrc, the VDEF fan) indexes against. */
    private final IdTables tables;

    public SubmittedVariantLedger(int perSeat, IdTables tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
        if (perSeat < 1) {
            throw new IllegalArgumentException("perSeat must be >= 1, got " + perSeat);
        }
        this.perSeat = perSeat;
    }

    public IdTables tables() {
        return tables;
    }

    public int perSeat() {
        return perSeat;
    }

    // ------------------------------------------------------------------ reader thread

    /**
     * The gate (run AFTER the pure policy passed): dup → the same canonical;
     * pending dup → silent; pending cap → {@code custom_rate_limited} (well-behaved, never
     * hostile); author cap → {@code custom_seat_cap}; global cap → {@code custom_global_cap};
     * else deposit for the sim thread.
     */
    public synchronized Offer offer(long sessionId, int seatId, String authorKey, VariantSubmit submit) {
        return offer(sessionId, seatId, authorKey, submit, null);
    }

    /** {@link #offer(long, int, String, VariantSubmit)} carrying the submit frame the drain's record row
     *  keeps ({@code submitFrameOrEmpty}: null / EMPTY = none carried); the gate is the same. */
    public synchronized Offer offer(long sessionId, int seatId, String authorKey, VariantSubmit submit,
            byte[] submitFrameOrEmpty) {
        Objects.requireNonNull(authorKey, "authorKey");
        Objects.requireNonNull(submit, "submit");
        VariantDefinition v = submit.variant();
        long crc = bodyCrc(v, tables);
        Map<Long, List<String>> mine = acceptedByAuthorCrc.get(authorKey);
        if (mine != null) {
            // A CRC hit is a CANDIDATE — only a full-body match answers DUPLICATE.
            // A colliding body (32-bit CRC, trivially forgeable) falls through as a fresh submission
            // instead of being silently substituted by the older canonical.
            String canonical = sameBodyCanonicalOrNull(mine.get(crc), v);
            if (canonical != null) {
                if (banned.contains(canonical)) {
                    // A banned body re-submitted was dup-answered
                    // ACCEPT with the banned canonical (ban keeps acceptedByAuthorCrc by design — the id
                    // still counts against the caps). Refuse it per id; non-hostile (the author's own
                    // oracle accepted the body once).
                    rejectedAtGate++;
                    return Offer.refused(DoorRejectReasons.CUSTOM_BANNED);
                }
                return Offer.duplicate(canonical);
            }
        }
        int myPending = 0;
        for (Pending p : pending) {
            if (p.authorKey().equals(authorKey)) {
                if (p.bodyCrc() == crc && sameBody(p.submitted(), v)) {
                    return Offer.pendingDuplicate();
                }
                myPending++;
            }
        }
        if (myPending >= PENDING_PER_AUTHOR) {
            rejectedAtGate++;
            return Offer.refused(DoorRejectReasons.CUSTOM_RATE_LIMITED);
        }
        if (acceptedCount(authorKey) + myPending >= perSeat) {
            rejectedAtGate++;
            return Offer.refused(DoorRejectReasons.CUSTOM_SEAT_CAP);
        }
        if (accepted.size() + pending.size() >= GLOBAL_CAP) {
            rejectedAtGate++;
            return Offer.refused(DoorRejectReasons.CUSTOM_GLOBAL_CAP);
        }
        pending.addLast(new Pending(sessionId, seatId, authorKey, submit.submitSeq(), v, crc, submitFrameOrEmpty));
        return Offer.deposited();
    }

    /**
     * The replay's deposit: {@code addLast} ONLY — no dup answer, no pending cap, no seat / global cap.
     * A recorded VSUB row exists only because the live gate answered DEPOSITED on the reader thread at
     * arrival, so the row is the proof the gate passed; re-taking the gate here, at the drain, would
     * judge from a DIFFERENT ledger state (two same-author rows in one gate window, or an editor
     * double-click still in flight, meet {@link #PENDING_PER_AUTHOR} where live had already drained the
     * first) and refuse a registration live made. The replay deposits one row and drains to empty before
     * the next, so the minted serials follow the file order. Replay only: the live reader never calls it.
     *
     */
    public synchronized void depositReplayed(Pending p) {
        pending.addLast(Objects.requireNonNull(p, "p"));
    }

    // ------------------------------------------------------------------ sim thread

    /** Take up to {@code max} deposits, oldest first (the per-tick band drains {@link #DRAIN_PER_TICK}). */
    public synchronized List<Pending> drain(int max) {
        List<Pending> out = new ArrayList<>();
        while (out.size() < max && !pending.isEmpty()) {
            out.add(pending.pollFirst());
        }
        inFlight.addAll(out);
        return out;
    }

    /**
     * Mint the canonical id for one drained deposit: {@code cc-v<serial>-<slug>} — serial is the
     * serve-monotonic counter (never reused, so an evicted id never comes back), slug = the
     * display name folded to {@code [a-z0-9]} (≤ {@link #SLUG_MAX_CHARS}), else the hull id's.
     * Always lawful under the codec id law and never a registry collision by construction
     * (the {@link #ID_PREFIX} is reserved); the registrar's overwrite=false put is the belt.
     */
    public synchronized String mint(Pending p) {
        long serial = nextSerial++;
        String slug = slug(p.submitted().displayName());
        if (slug.isEmpty()) {
            slug = slug(p.submitted().hullId());
        }
        if (slug.isEmpty()) {
            slug = "custom";
        }
        return ID_PREFIX + serial + "-" + slug;
    }

    /** Record a registrar ACCEPT; {@code canonical} is the submitted definition re-idd to {@code canonicalId}. */
    public synchronized void recordAccepted(Pending p, String canonicalId, VariantDefinition canonical) {
        recordAccepted(p, canonicalId, canonical, -1);
    }

    /** {@link #recordAccepted(Pending, String, VariantDefinition)} + the engine pass's unused-OP reading. */
    public synchronized void recordAccepted(Pending p, String canonicalId, VariantDefinition canonical,
            int unusedOp) {
        Objects.requireNonNull(p, "pending");
        Objects.requireNonNull(canonicalId, "canonicalId");
        Objects.requireNonNull(canonical, "canonical");
        if (!canonicalId.equals(canonical.variantId())) {
            throw new IllegalArgumentException("canonical definition id " + canonical.variantId()
                    + " != minted " + canonicalId);
        }
        if (accepted.containsKey(canonicalId)) {
            throw new IllegalStateException("canonical id already recorded: " + canonicalId);
        }
        long serial = serialOf(canonicalId);
        inFlight.remove(p);
        accepted.put(canonicalId, new Accepted(canonicalId, serial, p.authorKey(), p.sessionId(),
                p.seatId(), p.submitted().variantId(), p.bodyCrc(), canonical, unusedOp));
        ownerSession.put(canonicalId, p.sessionId());
        acceptedByAuthorCrc.computeIfAbsent(p.authorKey(), k -> new HashMap<>())
                .computeIfAbsent(p.bodyCrc(), k -> new ArrayList<>(1)).add(canonicalId);
    }

    /** The accepted canonical among {@code candidates} whose body equals {@code v} (id aside); null when none. */
    private String sameBodyCanonicalOrNull(List<String> candidates, VariantDefinition v) {
        if (candidates == null) {
            return null;
        }
        for (String canonicalId : candidates) {
            Accepted entry = accepted.get(canonicalId);
            if (entry != null && sameBody(entry.canonical(), v)) {
                return canonicalId;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ operator verbs (sim thread)

    /**
     * W15 v0 step 3(f) {@code custom-ban <id>}: mark an accepted custom BANNED — unpickable
     * ({@link #ownCanonicalOrNull}), unlisted ({@link #acceptedSnapshot}, {@link #ownAcceptedIds})
     * and never spooled to a late joiner from now on. The registry entry stays (no v0 eviction);
     * the id keeps counting against the caps. Returns the entry when newly banned, null when the
     * id is unknown or already banned (the caller logs which).
     */
    public synchronized Accepted ban(String canonicalId) {
        Accepted a = canonicalId == null ? null : accepted.get(canonicalId);
        if (a == null || !banned.add(canonicalId)) {
            return null;
        }
        return a;
    }

    public synchronized boolean isBanned(String canonicalId) {
        return canonicalId != null && banned.contains(canonicalId);
    }

    /** {@code custom-unban <id>}: the inverse of {@link #ban} — the entry when newly
     * un-banned (pickable / listed / spooled again), null when the id is unknown or not banned. */
    public synchronized Accepted unban(String canonicalId) {
        Accepted a = canonicalId == null ? null : accepted.get(canonicalId);
        if (a == null || !banned.remove(canonicalId)) {
            return null;
        }
        return a;
    }

    // ------------------------------------------------------------------ peer-build sharing v2 (door + serve thread)

    /** One {@code /share} listing row: an OWN accepted id + its share bit. */
    public record ShareRow(String canonicalId, boolean shared) {
        public ShareRow {
            Objects.requireNonNull(canonicalId, "canonicalId");
        }
    }

    /** The verdict of {@link #share} / {@link #unshare} / {@link #autoShareFielded}. */
    public enum ShareOutcome {
        /** The bit flipped OFF→ON now. */
        SHARED,
        /** Already ON — nothing changed. */
        ALREADY_SHARED,
        /** The bit flipped ON→OFF now. */
        UNSHARED,
        /** Already OFF — nothing changed. */
        ALREADY_UNSHARED,
        /** The auto-share skipped it: the author {@code /unshare}d this id (or it is not an
         * accepted custom the seat owns) — silent. */
        SKIPPED,
        /** Unknown, banned, or not owned by the calling session — the caller answers ONE constant
         * line (no enumeration: foreign and unknown are indistinguishable on the wire). */
        REFUSED
    }

    /** {@code entry} is non-null exactly for the non-REFUSED outcomes; {@code announce} = mint
     * the once-per-id broadcast line NOW (only ever true on a {@link ShareOutcome#SHARED} from a
     * non-silent {@code /share}). */
    public record ShareResult(ShareOutcome outcome, Accepted entry, boolean announce) {
        static ShareResult refused() {
            return new ShareResult(ShareOutcome.REFUSED, null, false);
        }
    }

    /** The accepted entry {@code sessionId} may share/unshare — non-null iff the id is a known,
     * not-banned canonical whose LIVE owner is that session (the ownership law). */
    private Accepted ownedLocked(String canonicalId, long sessionId) {
        Accepted a = canonicalId == null ? null : accepted.get(canonicalId);
        if (a == null || banned.contains(canonicalId)) {
            return null;
        }
        Long owner = ownerSession.get(canonicalId);
        return owner != null && owner == sessionId ? a : null;
    }

    /** The body of {@code canonicalId} when {@code sessionId} owns it (live owner, not
     * banned — the {@link #share} ownership law), else null; the {@code /share} stock-redirect read. */
    public synchronized VariantDefinition ownedCanonicalOrNull(String canonicalId, long sessionId) {
        Accepted a = ownedLocked(canonicalId, sessionId);
        return a == null ? null : a.canonical();
    }

    /** The session that currently owns {@code canonicalId} (the recorded submitter until a
     * {@link #reclaimOwner}); 0 when the id is unknown. The door reads this to decide a reclaim. */
    public synchronized long ownerSessionOrZero(String canonicalId) {
        Long owner = canonicalId == null ? null : ownerSession.get(canonicalId);
        return owner == null ? 0L : owner;
    }

    /** One reclaim candidate: an accepted id + the session currently recorded as its owner. */
    public record OwnerRow(String canonicalId, long ownerSessionId) {
    }

    /** Step (1) of the door's reclaim: this author key's not-banned accepted ids whose recorded
     * owner is some OTHER session than {@code sessionId}, with that owner — the door then checks
     * each owner's liveness under its own lock (step 2) and re-binds the departed ones through
     * {@link #reclaimOwner} (step 3); the monitors never nest. */
    public synchronized List<OwnerRow> reclaimCandidates(String authorKey, long sessionId) {
        List<OwnerRow> out = new ArrayList<>();
        if (authorKey == null) {
            return out;
        }
        for (Accepted a : accepted.values()) {
            String id = a.canonicalId();
            Long owner = ownerSession.get(id);
            if (a.authorKey().equals(authorKey) && !banned.contains(id)
                    && owner != null && owner != sessionId) {
                out.add(new OwnerRow(id, owner));
            }
        }
        return out;
    }

    /**
     * Re-bind {@code canonicalId}'s ownership to {@code sessionId}: the door calls this ONLY after
     * verifying (under its own lock, outside this monitor) that the recorded owner session is no
     * longer live, and that the caller's author key equals the entry's — the re-joined author,
     * never a spoof of a present one. Returns whether the owner changed (false: unknown id, banned,
     * author key mismatch, or already that session).
     */
    public synchronized boolean reclaimOwner(String canonicalId, long sessionId, String authorKey) {
        Accepted a = canonicalId == null ? null : accepted.get(canonicalId);
        if (a == null || banned.contains(canonicalId) || authorKey == null
                || !a.authorKey().equals(authorKey)) {
            return false;
        }
        Long owner = ownerSession.get(canonicalId);
        if (owner != null && owner == sessionId) {
            return false;
        }
        ownerSession.put(canonicalId, sessionId);
        return true;
    }

    /**
     * {@code /share <id> [silent]} from the session that owns it: the bit goes ON (clearing an
     * earlier {@code /unshare} mark); {@code announce} is true on the FIRST OFF→ON of this id in
     * the match when {@code silent} is false (the latch never re-arms — a toggle war mints one
     * line, total). Unknown / banned / foreign = {@link ShareOutcome#REFUSED}. The per-match share
     * budget is the ownership law itself: an author owns at most {@link #perSeat} accepted ids.
     */
    public synchronized ShareResult share(String canonicalId, long sessionId, boolean silent) {
        Accepted a = ownedLocked(canonicalId, sessionId);
        if (a == null) {
            return ShareResult.refused();
        }
        unsharedByOwner.remove(canonicalId);
        if (!shared.add(canonicalId)) {
            return new ShareResult(ShareOutcome.ALREADY_SHARED, a, false);
        }
        boolean announce = !silent && announced.add(canonicalId);
        return new ShareResult(ShareOutcome.SHARED, a, announce);
    }

    /** {@code /unshare <id>} from the owning session: the bit goes OFF and the id is marked
     * owner-unshared (the fielding auto-share leaves it alone until an explicit {@code /share}). */
    public synchronized ShareResult unshare(String canonicalId, long sessionId) {
        Accepted a = ownedLocked(canonicalId, sessionId);
        if (a == null) {
            return ShareResult.refused();
        }
        unsharedByOwner.add(canonicalId);
        return new ShareResult(shared.remove(canonicalId) ? ShareOutcome.UNSHARED
                : ShareOutcome.ALREADY_UNSHARED, a, false);
    }

    /**
     * The fielding seam: the seat whose auto-share flag is ON just DEPLOYED {@code variantId}.
     * Shares it silently when it is an accepted custom this session owns and the author never
     * {@code /unshare}d it; a stock/allowlist id, a foreign or banned custom, or an owner-unshared
     * one = {@link ShareOutcome#SKIPPED} (never REFUSED — nothing was asked). Never announces.
     */
    public synchronized ShareResult autoShareFielded(String variantId, long sessionId) {
        Accepted a = ownedLocked(variantId, sessionId);
        if (a == null || unsharedByOwner.contains(variantId)) {
            return new ShareResult(ShareOutcome.SKIPPED, a, false);
        }
        return new ShareResult(shared.add(variantId) ? ShareOutcome.SHARED
                : ShareOutcome.ALREADY_SHARED, a, false);
    }

    /** {@code /builds}: every shared, not-banned canonical id, mint order (a snapshot). A ban
     * un-lists without touching the bit — an unban lists it again. */
    public synchronized List<String> sharedIds() {
        List<String> out = new ArrayList<>(shared.size());
        for (Accepted a : accepted.values()) {
            if (shared.contains(a.canonicalId()) && !banned.contains(a.canonicalId())) {
                out.add(a.canonicalId());
            }
        }
        return out;
    }

    /** {@code /share} (no arg): the calling session's OWN not-banned accepted ids with their share
     * bit, mint order — own-only, by the live-owner law (never another session's). */
    public synchronized List<ShareRow> ownShareRows(long sessionId) {
        List<ShareRow> out = new ArrayList<>();
        for (Accepted a : accepted.values()) {
            String id = a.canonicalId();
            Long owner = ownerSession.get(id);
            if (owner != null && owner == sessionId && !banned.contains(id)) {
                out.add(new ShareRow(id, shared.contains(id)));
            }
        }
        return out;
    }

    public synchronized boolean isShared(String canonicalId) {
        return canonicalId != null && shared.contains(canonicalId) && !banned.contains(canonicalId);
    }

    public synchronized int sharedCount() {
        return sharedIds().size();
    }

    /** Every banned accepted custom of this author un-banned, mint order — {@code custom-allow <seat>}. */
    public synchronized List<String> unbanAllByAuthor(String authorKey) {
        List<String> out = new ArrayList<>();
        if (authorKey == null) {
            return out;
        }
        for (Accepted a : accepted.values()) {
            if (a.authorKey().equals(authorKey) && banned.remove(a.canonicalId())) {
                out.add(a.canonicalId());
            }
        }
        return out;
    }

    public synchronized int bannedCount() {
        return banned.size();
    }

    /** Every not-yet-banned accepted custom of this author, mint order — {@code custom-reject <seat>}. */
    public synchronized List<String> banAllByAuthor(String authorKey) {
        List<String> out = new ArrayList<>();
        if (authorKey == null) {
            return out;
        }
        for (Accepted a : accepted.values()) {
            if (a.authorKey().equals(authorKey) && banned.add(a.canonicalId())) {
                out.add(a.canonicalId());
            }
        }
        return out;
    }

    /** Drop every un-drained deposit of this seat ({@code custom-reject <seat>}); the caller mails
     * each one a REJECT receipt so the client's re-submit timer stops. In-flight entries are the
     * sim thread's own tick — this runs on that thread between ticks, so none exist here. */
    public synchronized List<Pending> dropPendingForSeat(int seatId) {
        List<Pending> dropped = new ArrayList<>();
        java.util.Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.seatId() == seatId) {
                dropped.add(p);
                it.remove();
            }
        }
        return dropped;
    }

    /** Author key recorded for a seat (its latest deposit or accept), else null — the operator
     * verbs name SEATS while the ledger keys AUTHORS. */
    public synchronized String authorKeyForSeatOrNull(int seatId) {
        String found = null;
        for (Accepted a : accepted.values()) {
            if (a.seatId() == seatId) {
                found = a.authorKey();
            }
        }
        for (Pending p : pending) {
            if (p.seatId() == seatId) {
                found = p.authorKey();
            }
        }
        return found;
    }

    /** Record a registrar REJECT (counted; nothing else changes — the author may re-submit a fix). */
    public synchronized void recordRejected(Pending p, String reasonToken) {
        Objects.requireNonNull(p, "pending");
        Objects.requireNonNull(reasonToken, "reasonToken");
        inFlight.remove(p);
        rejectedByRegistrar++;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The pick fallback: the canonical id an author may pick by {@code raw} — either their
     * canonical id verbatim or the client-local id they submitted it under (latest wins).
     * Null when the author owns no such custom (sharing comes in a later version).
     */
    public synchronized String ownCanonicalOrNull(String authorKey, String raw) {
        if (authorKey == null || raw == null) {
            return null;
        }
        Accepted direct = accepted.get(raw);
        if (direct != null && direct.authorKey().equals(authorKey) && !banned.contains(raw)) {
            return direct.canonicalId();
        }
        // The pick allowlist's own ASCII fold (PickableVariants.canonicalIdOrNull): a typed
        // id matches case-insensitively; ownership scopes it, so no fold crosses authors.
        String fold = ChatCommands.asciiLower(raw);
        String byLocal = null;
        for (Accepted a : accepted.values()) {
            if (!a.authorKey().equals(authorKey) || banned.contains(a.canonicalId())) {
                continue;
            }
            if (ChatCommands.asciiLower(a.canonicalId()).equals(fold)) {
                return a.canonicalId();
            }
            if (ChatCommands.asciiLower(a.localId()).equals(fold)) {
                byLocal = a.canonicalId();
            }
        }
        return byLocal;
    }

    /**
     * W15 v0 step 3(c) ({@code SeatVariantPicks} hold law): true while this author has a
     * submission under local id {@code raw} that is deposited or in flight (drained, engine
     * pass running) — a pick naming it waits for the verdict instead of being refused.
     */
    public synchronized boolean ownPendingLocalId(String authorKey, String raw) {
        if (authorKey == null || raw == null) {
            return false;
        }
        String fold = ChatCommands.asciiLower(raw);
        for (Pending p : pending) {
            if (p.authorKey().equals(authorKey) && ChatCommands.asciiLower(p.submitted().variantId()).equals(fold)) {
                return true;
            }
        }
        for (Pending p : inFlight) {
            if (p.authorKey().equals(authorKey) && ChatCommands.asciiLower(p.submitted().variantId()).equals(fold)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int inFlightCount() {
        return inFlight.size();
    }

    public synchronized Accepted acceptedOrNull(String canonicalId) {
        return accepted.get(canonicalId);
    }

    /** Accepted entries NOT banned, mint order (a snapshot) — the runtime VDEF catalog, the
     * persistence catch-up and every listing read this. */
    public synchronized List<Accepted> acceptedSnapshot() {
        List<Accepted> out = new ArrayList<>(accepted.size());
        for (Accepted a : accepted.values()) {
            if (!banned.contains(a.canonicalId())) {
                out.add(a);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** W15 v0 step 3(f) {@code /customs}: this author's not-banned canonical ids, mint order
     * (the own-only law — never another author's). */
    public synchronized List<String> ownAcceptedIds(String authorKey) {
        List<String> out = new ArrayList<>();
        if (authorKey == null) {
            return out;
        }
        for (Accepted a : accepted.values()) {
            if (a.authorKey().equals(authorKey) && !banned.contains(a.canonicalId())) {
                out.add(a.canonicalId());
            }
        }
        return out;
    }

    public synchronized int acceptedCount() {
        return accepted.size();
    }

    public synchronized int acceptedCount(String authorKey) {
        int n = 0;
        for (Accepted a : accepted.values()) {
            if (a.authorKey().equals(authorKey)) {
                n++;
            }
        }
        return n;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized int rejectedCount() {
        return rejectedAtGate + rejectedByRegistrar;
    }

    /** The gate half of {@link #rejectedCount} (rate / seat-cap / global-cap refusals). */
    public synchronized int rejectedAtGateCount() {
        return rejectedAtGate;
    }

    /** The registrar half of {@link #rejectedCount} (engine refusals + faults). */
    public synchronized int rejectedByRegistrarCount() {
        return rejectedByRegistrar;
    }

    public synchronized long nextSerial() {
        return nextSerial;
    }

    // ------------------------------------------------------------------ pure helpers

    /**
     * Identity of a submission independent of its client-local id: CRC32 over the VDEF body of
     * the definition re-idd to {@link #ID_PREFIX} ("CRC32 of body minus id").
     */
    public static long bodyCrc(VariantDefinition v, IdTables tables) {
        byte[] body;
        try {
            body = VariantDefinitionFrameCodec.encodeBody(withId(v, ID_PREFIX), tables);
        } catch (IOException unencodable) {
            // A decoded VDEF always re-encodes (the codec's own round-trip law); a throw here is a codec bug.
            throw new IllegalStateException("VDEF body re-encode failed for CRC", unencodable);
        }
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    /**
     * The FULL-BODY identity behind {@link #bodyCrc} — two definitions are the same
     * submission iff they are equal with the id normalised (records: every list/flag compared
     * elementwise; the same fields the body encoder writes). The CRC only narrows the search.
     */
    public static boolean sameBody(VariantDefinition a, VariantDefinition b) {
        return withId(a, ID_PREFIX).equals(withId(b, ID_PREFIX));
    }

    /** The same definition under another id (records are immutable). */
    public static VariantDefinition withId(VariantDefinition v, String id) {
        return new VariantDefinition(id, v.hullId(), v.displayName(), v.goalVariant(), v.fluxVents(),
                v.fluxCapacitors(), v.hullMods(), v.permaMods(), v.sMods(), v.sModdedBuiltIns(),
                v.suppressedMods(), v.wings(), v.tags(), v.weaponGroups(), v.modules());
    }

    /** {@code [a-z0-9]} fold of peer text, runs collapsed to one {@code -}, ≤ {@link #SLUG_MAX_CHARS}, no edge dashes. */
    public static String slug(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < text.length() && b.length() < SLUG_MAX_CHARS; i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                b.append(c);
                dash = false;
            } else if (b.length() > 0 && !dash) {
                b.append('-');
                dash = true;
            }
        }
        int end = b.length();
        while (end > 0 && b.charAt(end - 1) == '-') {
            end--;
        }
        return b.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /** The serial a minted id carries (the digits between the prefix and the first dash). */
    static long serialOf(String canonicalId) {
        if (!canonicalId.startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("not a minted id: " + canonicalId);
        }
        int dash = canonicalId.indexOf('-', ID_PREFIX.length());
        String digits = dash < 0 ? canonicalId.substring(ID_PREFIX.length())
                : canonicalId.substring(ID_PREFIX.length(), dash);
        return Long.parseLong(digits);
    }
}
