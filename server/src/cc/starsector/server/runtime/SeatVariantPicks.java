package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The door's seat-variant PICK surface, carved out of {@code PilotIntentDatagramServer}:
 * the static allowlist
 * canonicalizer, the server-owned joiner default, the fresh-claim RAW request the
 * first fly-in's announce reads, the HELLO-pick apply — and NEW, the
 * own-custom fallback: a pick that misses the mod-scan allowlist resolves against the
 * seat's OWN accepted customs ({@link CustomPickSource}, = {@code ledger.ownCanonicalOrNull};
 * own-only by design, sharing is v3) and a pick naming a custom that is still in flight
 * is HELD {@link #PENDING_HOLD_MILLIS} as the design's {@code pendingUnresolvedPick}: seeded the
 * moment the lane ACCEPTs it ({@link #onCustomAccepted}), dropped loud at the deadline
 * ({@link #sweepExpired}). An unlisted HELLO pick is held whenever customs are on — by
 * construction a joiner's HELLO precedes its outbox VSUBs (by design), so at HELLO time
 * the ledger cannot know the id yet — EXCEPT an id the host replaced or hides, answered at once
 * ({@link Kind#REPLACED} / {@link Kind#NOT_SERVED}); a {@code /ship} pick is held only when a matching own
 * submission is already deposited or in flight (the client's normal flow is submit → VACK →
 * {@code /ship}, so an unknown {@code /ship} arg falls through to the dispatch at once —
 * that is the fold-substring FILTER listing, or the immediate
 * "no variants match" when nothing matches).
 *
 * <p>Threads: reader thread ({@link #onFreshClaim} under the door lock, {@link #applyPendingPick}
 * and {@link #shipSetCustomOrNull} outside it), sim thread ({@link #onCustomAccepted} from the
 * lane's drain), door timer thread ({@link #sweepExpired}), serve loop ({@link #onRoundWrap},
 * {@link #takeRequestedOrNull}). ONE monitor ({@code this}) guards the two maps; the
 * {@link Seeder} (the dispatch's own monitor) and the {@link Notifier} (the door's CTRL send)
 * are ALWAYS called outside it — the door's resolve-under-lock / send-outside law; nothing here
 * ever takes the door lock. With no {@link CustomPickSource} attached ({@code custom.submissions}
 * off) every path is the pre-carve door verbatim.
 */
public final class SeatVariantPicks {

    /** How long a pick naming a not-yet-accepted custom waits for its ACCEPT. */
    public static final long PENDING_HOLD_MILLIS = 30_000L;

    /** The W15 lane's view for the pick fallback (implemented by {@code CustomVariantLane}). */
    public interface CustomPickSource {
        /** The canonical id this author may pick by {@code raw} (canonical or their local id), else null. */
        String ownCanonicalOrNull(String authorKey, String raw);

        /** True while this author has a deposited or in-flight submission under local id {@code raw}. */
        boolean ownPending(String authorKey, String raw);

        /** Step 3(f) {@code /customs}: this author's pickable canonical ids, mint order (own-only law). */
        List<String> ownCanonicalIds(String authorKey);
    }

    /**
     * {@link Seeder#seed}'s outcome: {@code REFUSED_HIDDEN} stored nothing —
     * the id was demoted between this class's resolve and the dispatch's store — and the caller words its
     * VARIANT line as a FALLBACK ({@link #seedRefused}), never "canonical=X" for a seed that did not land.
     */
    public enum Seed {
        REFUSED_HIDDEN, SEEDED, SEEDED_CLAIM_CLEARED;

        public boolean seeded() {
            return this != REFUSED_HIDDEN;
        }

        public boolean claimCleared() {
            return this == SEEDED_CLAIM_CLEARED;
        }
    }

    /** Seed the seat's next-respawn override; the outcome says whether it landed and whether a standing
     * module claim was cleared. */
    public interface Seeder {
        Seed seed(int seatId, String canonical);

        /** The seat's STANDING override (the id a released hold falls back
         * to), or null = the descriptor row. Default null keeps the two-method lambda rigs. */
        default String standingOrNull(int seatId) {
            return null;
        }

        /** true iff {@code sessionId} is live AND bound to {@code seatId} right now —
         * the submitter seed's guard against a seat re-bound between deposit and the drain's
         * accept. Default true keeps the lambda rigs. */
        default boolean bound(long sessionId, int seatId) {
            return true;
        }

        /** Drop the seat's STANDING override (a fresh
         * tenure whose apply stores nothing — a pick dies with its tenure); returns the dropped
         * id or null. Default = no-op null, which keeps the lambda rigs. */
        default String clearStanding(int seatId) {
            return null;
        }
    }

    /** One server chat line to the SESSION (dropped by the door when it is gone). */
    public interface Notifier {
        void chat(long sessionId, int seatId, String line);
    }

    /**
     * The kept copy a host put in a ship's place (implemented by {@code CatalogPromotionLane::copySupersedingOrNull};
     * any thread, the last kept scan's view). WHY: a join window REMEMBERS the id it
     * last flew, and a host that has since replaced that ship serves its copy under another id - the HELLO pick then
     * names an id the listing no longer holds and no submission will ever confirm.
     */
    public interface SupersedeSource {
        /** The kept copy whose link names {@code replacedId} (ASCII fold), or null = none kept now. */
        String copySupersedingOrNull(String replacedId);

        /**
         * The ROOT a REMOVED kept stem stood for - the link its forgotten
         * {@code .meta} carried (implemented by {@code CatalogPromotionLane::removedSupersededByOrNull}) - or null = not
         * a removed kept stem with a link. WHY: a copy of a copy links the ROOT, so a re-edited or removed kept stem a
         * join window remembers is named by no kept copy's link; only its own link leads to the copy standing in its
         * place now. Default null: a reader built from the copy link alone never follows a removed stem.
         */
        default String removedSupersededByOrNull(String removedId) {
            return null;
        }

        /**
         * true = {@code id} (ASCII fold) is a kept copy the host REMOVED - an
         * inbox {@code kept-} file no kept stem holds, the same removed list a restore takes back from (implemented by
         * {@code CustomVariantOps.removedStemOrNull}: the last scans' bounded lists, any thread, no file I/O). WHY: a
         * join window that last flew a kept copy sends that stem as its pick; once the host removes the copy no
         * submission can ever confirm it, so a hold only delays the default 30 s. A plain removal carries no link to
         * walk, and the stem itself is never hidden, so neither the redirect nor the hidden test sees it.
         * Default false: a reader built from the links alone never answers a removal.
         */
        default boolean isRemovedKeptStem(String id) {
            return false;
        }

        /**
         * Every reader of one catalog, e.g. {@code of(promo::copySupersedingOrNull, promo::removedSupersededByOrNull,
         * id -> CustomVariantOps.removedStemOrNull(lane, id) != null)}. The removal test is a {@link Predicate}, a type
         * apart from the two link readers, so it cannot trade places with one and still compile.
         */
        static SupersedeSource of(UnaryOperator<String> copySuperseding, UnaryOperator<String> removedSupersededBy,
                Predicate<String> removedKeptStem) {
            Objects.requireNonNull(copySuperseding, "copySuperseding");
            Objects.requireNonNull(removedSupersededBy, "removedSupersededBy");
            Objects.requireNonNull(removedKeptStem, "removedKeptStem");
            return new SupersedeSource() {
                @Override
                public String copySupersedingOrNull(String replacedId) {
                    return copySuperseding.apply(replacedId);
                }

                @Override
                public String removedSupersededByOrNull(String removedId) {
                    return removedSupersededBy.apply(removedId);
                }

                @Override
                public boolean isRemovedKeptStem(String id) {
                    return removedKeptStem.test(id);
                }
            };
        }
    }

    /**
     * Links one redirect walk follows before it answers {@code NOT_SERVED}.
     * Every link the last scans hold is a kept copy's (at most {@link CatalogPromotionLane#KEPT_CAP} kept stems per
     * scan) or a removed kept stem's (at most {@link CatalogPromotionLane#INBOX_LIST_CAP} kept names listed per scan),
     * so a chain of distinct ids never needs more; past it the reader is not the scans' bounded view and the walk stops
     * rather than spin on peer-named input.
     */
    public static final int SUPERSEDE_HOPS_MAX = CatalogPromotionLane.KEPT_CAP + CatalogPromotionLane.INBOX_LIST_CAP;

    /** {@code REPLACED} = an unlisted id whose listed kept copy flies in its place; {@code NOT_SERVED} = an id this
     *  server hides with no listed copy, a kept copy the host removed, an id whose supersede links end with no listed
     *  copy, or whose supersede chain cycles or runs past {@link #SUPERSEDE_HOPS_MAX} (both only while customs are on
     *  - without them a miss is {@code UNKNOWN}). */
    public enum Kind { ALLOWLIST, OWN_CUSTOM, HELD, UNKNOWN, UNAVAILABLE, REPLACED, NOT_SERVED }

    /** {@code canonical} is non-null for {@link Kind#ALLOWLIST} / {@link Kind#OWN_CUSTOM} / {@link Kind#REPLACED} only. */
    public record Resolution(Kind kind, String canonical) {
    }

    /** What a fresh claim carries into the post-spool apply ({@code PeerSession.pendingVariantPick}). */
    public record Requested(String pick, boolean isDefault) {
    }

    private record Held(long sessionId, String authorKey, String raw, long deadlineMillis) {
    }

    private final PickableVariants pickableVariants;
    private final Seeder seeder;
    private final Notifier notifier;
    private final LongSupplier clock;
    private volatile CustomPickSource customSource;
    private volatile SupersedeSource supersedeSource;
    private volatile String joinerDefaultVariant;
    /** seat → RAW fresh-claim request ("" = none); guarded by {@code this}. */
    private final Map<Integer, String> requestedBySeat = new HashMap<>();
    /** seat → the held pick; guarded by {@code this}. */
    private final Map<Integer, Held> heldBySeat = new HashMap<>();
    private int heldTotal;
    private int resolvedTotal;
    private int expiredTotal;
    private int releasedTotal; // Holds dropped by a REJECT (D3)
    private int submittedTotal; // Accepts that seeded the SUBMITTER's seat (no hold)
    /** Redirect walks that met a cycle or ran past {@link #SUPERSEDE_HOPS_MAX} (each answered {@code NOT_SERVED}). */
    private final AtomicInteger supersedeChainBrokenTotal = new AtomicInteger();

    public SeatVariantPicks(PickableVariants pickableVariantsOrNull, Seeder seeder, Notifier notifier) {
        this(pickableVariantsOrNull, seeder, notifier, System::currentTimeMillis);
    }

    /** Test constructor: an injectable clock for the hold deadline. */
    public SeatVariantPicks(PickableVariants pickableVariantsOrNull, Seeder seeder, Notifier notifier,
            LongSupplier clock) {
        this.pickableVariants = pickableVariantsOrNull;
        this.seeder = Objects.requireNonNull(seeder, "seeder");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Attach (or detach, null) the custom-variant lane; thread-agnostic. */
    public void attachCustomSource(CustomPickSource sourceOrNull) {
        customSource = sourceOrNull;
    }

    /** Attach (or detach, null) the kept-copy link reader; thread-agnostic. Read only while customs are on. */
    public void attachSupersedeSource(SupersedeSource sourceOrNull) {
        supersedeSource = sourceOrNull;
    }

    /**
     * The answer for an unlisted {@code raw} the host's supersede links can give: {@code REPLACED} = the first LISTED
     * kept copy down the chain, {@code NOT_SERVED} = a walk that followed at least one link and ends with no listed
     * copy, or a broken chain (counted, one log row), null = no link reader or no link names {@code raw} (the
     * caller's removed / hidden / hold answer stands). A redirect never names a ship
     * {@code /ship} refuses: a copy the listing no longer holds (hidden or banned since) is a link to follow, never a
     * target. {@code raw} is peer text (the HELLO pick, printable, at most 64 chars): folded and compared, printed
     * only through {@link PeerText#quote}, never stored.
     * The walk follows the whole chain, not its first link. At each id the
     * copy kept in its place comes first (it names this very id); an id no copy names follows its own removed-stem
     * link to the ROOT, whose copy is read on the next step. The root itself is never a target, listed or not: the
     * redirect names only a kept copy standing in a place. A revisited id (ASCII fold) or a walk past
     * {@link #SUPERSEDE_HOPS_MAX} links = {@code NOT_SERVED}, never a spin and never a 30 s hold for a submission that
     * cannot come.
     * A walk that followed a link and then finds no link onward and no listed
     * copy is NOT_SERVED too, uncounted (the chain is whole; the place it leads to is just empty): {@code raw} named a
     * ship the host replaced or removed, whose root is still hidden with no copy listed, or was put back on the list -
     * the build the window remembers is gone either way, so it is neither held nor flown as the root.
     */
    private Resolution redirectOrNull(String raw) {
        SupersedeSource source = supersedeSource;
        if (source == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        seen.add(ChatCommands.asciiLower(raw));
        String at = raw;
        for (int hop = 1; hop <= SUPERSEDE_HOPS_MAX; hop++) {
            String copy = source.copySupersedingOrNull(at);
            String next;
            if (copy != null) {
                String listed = canonicalPickOrNull(copy);
                if (listed != null) {
                    return new Resolution(Kind.REPLACED, listed);
                }
                next = copy;
            } else {
                next = source.removedSupersededByOrNull(at);
                if (next == null) {
                    return hop == 1 ? null : new Resolution(Kind.NOT_SERVED, null);
                }
            }
            if (!seen.add(ChatCommands.asciiLower(next))) {
                return brokenChain(raw, "cycle", hop);
            }
            at = next;
        }
        return brokenChain(raw, "bound", SUPERSEDE_HOPS_MAX);
    }

    private Resolution brokenChain(String raw, String reason, int hops) {
        supersedeChainBrokenTotal.incrementAndGet();
        System.out.println("pilot-datagram-admission VARIANT_SUPERSEDE_CHAIN_BROKEN pick=" + PeerText.quote(raw)
                + " reason=" + reason + " hops=" + hops + " (not served)");
        return new Resolution(Kind.NOT_SERVED, null);
    }

    /** true = {@code raw} names a kept copy the host removed (no link reader
     *  = false). {@code raw} is peer text: folded and compared by the reader, never stored and never printed here. */
    private boolean removedKeptStem(String raw) {
        SupersedeSource source = supersedeSource;
        return source != null && source.isRemovedKeptStem(raw);
    }

    /** Redirect walks answered {@code NOT_SERVED} for a cycle or a chain past {@link #SUPERSEDE_HOPS_MAX}. */
    public int supersedeChainBrokenTotal() {
        return supersedeChainBrokenTotal.get();
    }

    // ------------------------------------------------------------------ the allowlist half (moved verbatim)

    /** True when the mod scan can canonicalize an id. The ONE pick allowlist on every door
     * (debug included) is the curated mod scan — the debug-door widening to the
     * pilotable registry catalogue was RETIRED and
     * DELETED — never re-wire it. */
    public boolean pickingAvailable() {
        return pickableVariants != null && !pickableVariants.ids().isEmpty();
    }

    /** The mod scan's canonical spelling; null = not in the allowlist (customs are NOT consulted). */
    public String canonicalPickOrNull(String raw) {
        return pickingAvailable() && raw != null ? pickableVariants.canonicalIdOrNull(raw) : null;
    }

    public boolean customsAvailable() {
        return customSource != null;
    }

    /** {@code /customs}: the author's own pickable customs (empty when the lane is off). */
    public List<String> ownCanonicalIds(String authorKey) {
        CustomPickSource source = customSource;
        return source == null ? List.of() : source.ownCanonicalIds(authorKey);
    }

    /**
     * The full resolution for one author: allowlist first (static spelling wins), then the
     * author's own accepted customs, then the hold decision — {@code holdUnknown} makes every
     * miss a hold (the HELLO path), else only a deposited/in-flight own submission holds.
     * Before the blanket hold, an id this server no longer lists is answered at
     * once: a listed kept copy standing in its place = {@code REPLACED}, a hidden id with none = {@code NOT_SERVED}.
     * "In its place" is the whole supersede chain ({@link #redirectOrNull}): a
     * remembered kept stem the host has since re-edited or removed reaches the newest listed copy through its own
     * root link, and a cycle or an over-long chain is {@code NOT_SERVED} too.
     * A remembered kept copy the host REMOVED with no listed copy in its
     * place is {@code NOT_SERVED} at once - through its link when the walk ends empty (the root still hidden, or put
     * back on the list: the root is never flown in the copy's stead), else through the removed list
     * ({@link SupersedeSource#isRemovedKeptStem}, a plain removal carries no link). Only an id that no link and no
     * removed list names still takes the blanket hold.
     * The flight held a remembered {@code afflictor_Antimatter} 30 s for a submission that could not come and
     * flew the descriptor row. An own accepted custom and an own in-flight submission still win first (the
     * author's build under that local id beats the host's copy, as before).
     */
    public Resolution resolve(String authorKey, String raw, boolean holdUnknown) {
        String allow = canonicalPickOrNull(raw);
        if (allow != null) {
            return new Resolution(Kind.ALLOWLIST, allow);
        }
        CustomPickSource src = customSource;
        if (src != null && raw != null) {
            String own = src.ownCanonicalOrNull(authorKey, raw);
            if (own != null) {
                return new Resolution(Kind.OWN_CUSTOM, own);
            }
            if (src.ownPending(authorKey, raw)) {
                return new Resolution(Kind.HELD, null);
            }
            Resolution redirect = redirectOrNull(raw);
            if (redirect != null) {
                return redirect;
            }
            if (removedKeptStem(raw)) {
                return new Resolution(Kind.NOT_SERVED, null);
            }
            if (pickableVariants != null && pickableVariants.isHidden(raw)) {
                return new Resolution(Kind.NOT_SERVED, null);
            }
            if (holdUnknown) {
                return new Resolution(Kind.HELD, null);
            }
        }
        if (!pickingAvailable() && src == null) {
            return new Resolution(Kind.UNAVAILABLE, null);
        }
        return new Resolution(Kind.UNKNOWN, null);
    }

    // ------------------------------------------------------------------ joiner default

    /** The server-owned joiner default (canonical pickable id) or null = descriptor row. */
    public String joinerDefaultVariantOrNull() {
        return joinerDefaultVariant;
    }

    /**
     * Set (or clear with null/blank) the server-owned joiner default. The raw id is
     * canonicalized against the door's pickable allowlist ONLY (a server default is nobody's
     * custom — own-only law) — an unpickable id (or a door with no pickables) is REFUSED loud
     * and the standing value stays: the default can never name a hull the server itself could
     * not field. Returns the canonical id now standing, or null.
     */
    public String setJoinerDefaultVariant(String rawOrNull) {
        String raw = rawOrNull == null ? "" : rawOrNull.trim();
        if (raw.isEmpty()) {
            joinerDefaultVariant = null;
            System.out.println("pilot-datagram-admission JOINER_DEFAULT cleared (descriptor row)");
            return null;
        }
        String canonical = canonicalPickOrNull(raw); // the mod scan alone
        String echo = raw.length() <= 64 ? raw : raw.substring(0, 64);
        if (canonical == null) {
            System.out.println("pilot-datagram-admission JOINER_DEFAULT_UNPICKABLE id=" + echo
                    + " standing=" + joinerDefaultVariant
                    + (!pickingAvailable()
                            ? " (picking unavailable on this door)"
                            : " (not in the allowlist)"));
            // The refused default is a FALLBACK every debrief must list (the
            // rig asked for ziggurat_Strike, got the descriptor grendel, and read motes).
            FallbackAnnounce.announceOnce("joiner-default-refused",
                    "id=" + echo + " standing=" + joinerDefaultVariant
                            + " (joiners fly the standing default / descriptor row)");
            return joinerDefaultVariant;
        }
        joinerDefaultVariant = canonical;
        System.out.println("pilot-datagram-admission JOINER_DEFAULT id=" + echo
                + " canonical=" + canonical);
        return canonical;
    }

    // ------------------------------------------------------------------ fresh claim + first fly-in

    /**
     * A FRESH seat claim named {@code rawVariantId} ("" = none): remember the RAW request for
     * the first fly-in's announce decision, drop any pick still held for the seat's previous
     * tenure, and return what the post-spool apply must resolve — the explicit pick, else the
     * joiner default (marked), else null. Called under the door lock (never blocks). A fresh
     * claim here = a NEW tenure (an unmounted seat — a held-hull re-claim
     * never reaches this); its apply ({@link #applyPendingPick} with {@code freshTenure}) either
     * seeds the tenure's pick or clears the previous tenure's standing override, and the door's
     * tenure gate keeps the seat's first fly-in waiting until that apply ran.
     */
    public Requested onFreshClaim(int seatId, String rawVariantId) {
        String raw = rawVariantId == null ? "" : rawVariantId;
        synchronized (this) {
            requestedBySeat.put(seatId, raw);
            heldBySeat.remove(seatId);
        }
        if (!raw.isEmpty()) {
            return new Requested(raw, false);
        }
        String joinerDefault = joinerDefaultVariant;
        if (joinerDefault != null && canonicalPickOrNull(joinerDefault) == null) {
            // A default the catalog overlay HID seeds nothing — the standing value
            // stays (an Undo restores it); the fresh claim flies the descriptor row
            System.out.println("pilot-datagram-admission JOINER_DEFAULT hidden id=" + joinerDefault + " seat=" + seatId);
            return null;
        }
        return joinerDefault == null ? null : new Requested(joinerDefault, true);
    }

    /** The RAW request of the seat's fresh claim ("" = none), or null when none is pending a
     * first fly-in — consumed ONCE (serve-loop read at the first fly-in after admit). A held
     * pick that resolved re-points this to the canonical id, so a seat that got EXACTLY what
     * it asked for (under the minted id) is never announced as a fallback. */
    public synchronized String takeRequestedOrNull(int seatId) {
        return requestedBySeat.remove(seatId);
    }

    /** W7 B1b round-wrap law: the raw requests AND the held picks go with the overrides (a seat
     * admitted but never flown in before the wrap must not carry its request into the reset
     * fly-in; a hold that outlives the wrap would seed the next round from the old one). */
    public void onRoundWrap() {
        int dropped;
        synchronized (this) {
            requestedBySeat.clear();
            dropped = heldBySeat.size();
            heldBySeat.clear();
        }
        if (dropped > 0) {
            System.out.println("pilot-datagram-admission VARIANT_HELD_DROPPED_AT_WRAP count=" + dropped);
        }
    }

    // ------------------------------------------------------------------ the HELLO pick apply (moved + the fallback)

    /**
     * Apply the fresh claim's pick read out of the session (reader thread, no lock held):
     * the joiner default seeds straight in; an explicit pick resolves through
     * {@link #resolve} with {@code holdUnknown = customs on} — an allowlist or own-custom hit
     * seeds, a hold waits for the ACCEPT, a miss is the loud descriptor fallback (log +
     * {@link FallbackAnnounce} + one CHTD notice on the session's reliable lane).
     * The tenure law: with {@code freshTenure} (the door's fresh claim of an
     * unmounted seat) EVERY exit that stores nothing — no pick, a refused joiner default,
     * UNAVAILABLE, UNKNOWN, HELD, a refused allowlist/own-custom seed — drops the seat's standing
     * override through {@link Seeder#clearStanding}, so the new tenure flies its OWN pick or the
     * descriptor, never the previous tenure's (a HELD custom seeds on its ACCEPT). A seeded exit
     * replaces the standing value in the dispatch's own monitor step and clears nothing. Without
     * {@code freshTenure} (live takeover, password REJOINED, held-hull re-claim) unchanged.
     */
    public void applyPendingPick(long sessionId, int seatId, String authorKey, String pick, boolean isDefault,
            boolean freshTenure) {
        if (pick == null) {
            tenureClear(seatId, freshTenure);
            return;
        }
        if (isDefault) {
            // The joiner default is already canonical + allowlisted at set time; seed
            // it straight in (no CHTD notice — the joiner asked for nothing).
            Seed s = seeder.seed(seatId, pick);
            if (!s.seeded()) {
                seedRefused(sessionId, seatId, pick, pick, "joiner-default", false);
                tenureClear(seatId, freshTenure);
                return;
            }
            System.out.println("pilot-datagram-admission VARIANT seat=" + seatId
                    + " pick=" + PeerText.quote(pick) + " canonical=" + pick + " (joiner default)"
                    + (s.claimCleared() ? " (module claim cleared)" : ""));
            return;
        }
        // `pick` is the HELLO variantId — peer text held only to
        // printable + ≤ 64, so every `pick=` print delimits it (PeerText.quote, security doc);
        // `canonical=` is the allowlist's / the ledger's own spelling and stays bare.
        String echo = echo(pick);
        Resolution r = resolve(authorKey, pick, customsAvailable());
        switch (r.kind()) {
            case UNAVAILABLE -> {
                System.out.println("pilot-datagram-admission VARIANT_UNPICKABLE seat=" + seatId
                        + " pick=" + PeerText.quote(pick) + " (picking unavailable on this door)");
                // A refused pick is a FALLBACK the seat's
                // window may not know about — announce it so every debrief's FALLBACK section
                // lists it (two ownerless rigs read a refused Ziggurat as a mote-generator bug).
                FallbackAnnounce.announceOnce("hello-pick-refused-seat-" + seatId,
                        "pick=" + PeerText.quote(echo)
                                + " picking_unavailable=true (seat flies the descriptor row)");
                notifier.chat(sessionId, seatId, "variant '" + pickNoticeName(pickableVariants, pick, null)
                        + "' ignored - picking unavailable here; spawning default");
                tenureClear(seatId, freshTenure);
            }
            case UNKNOWN -> {
                System.out.println("pilot-datagram-admission VARIANT_UNPICKABLE seat=" + seatId
                        + " pick=" + PeerText.quote(pick));
                FallbackAnnounce.announceOnce("hello-pick-refused-seat-" + seatId, //
                        "pick=" + PeerText.quote(echo)
                                + " not_pickable=true (seat flies the descriptor row)");
                notifier.chat(sessionId, seatId, "variant '" + pickNoticeName(pickableVariants, pick, null)
                        + "' not pickable - spawning default; /ship lists ids");
                tenureClear(seatId, freshTenure);
            }
            case NOT_SERVED -> {
                // An id this server hides, no listed kept copy in its place, or a supersede chain
                // that cycles or runs past its bound, or a kept copy the host removed with no
                // listed copy in its place - answered now, never held
                // (the UNKNOWN twin: log + FALLBACK row + one chat line; an own submission under this local id
                // still seeds the seat on its ACCEPT through the submitter path)
                System.out.println("pilot-datagram-admission VARIANT_UNPICKABLE seat=" + seatId
                        + " pick=" + PeerText.quote(pick) + " (not served: hidden or removed with no listed kept copy,"
                        + " or a broken supersede chain)");
                FallbackAnnounce.announceOnce("hello-pick-refused-seat-" + seatId,
                        "pick=" + PeerText.quote(echo)
                                + " not_served=true (seat flies the descriptor row)");
                notifier.chat(sessionId, seatId, "variant '" + pickNoticeName(pickableVariants, pick, null)
                        + "' is not on this server - spawning default; /ship lists ids");
                tenureClear(seatId, freshTenure);
            }
            case REPLACED -> {
                // The seat flies the host's kept copy of the ship it asked for. The fresh-claim
                // request is NOT re-pointed: the joiner's own window may have fielded the old id at t=0, so the
                // first fly-in must announce the copy (SeatAnnounceTracker's requestDiffers) and list the swap.
                Seed s = seeder.seed(seatId, r.canonical());
                if (!s.seeded()) {
                    seedRefused(sessionId, seatId, pick, r.canonical(), "hello", true);
                    tenureClear(seatId, freshTenure);
                    return;
                }
                System.out.println("pilot-datagram-admission VARIANT seat=" + seatId
                        + " pick=" + PeerText.quote(pick) + " canonical=" + r.canonical() + " (replaced: its kept copy)"
                        + (s.claimCleared() ? " (module claim cleared)" : ""));
                // NO chat line: a host re-edit keeps the ship's name, so a line naming both
                // ships named one ship twice, and it replayed at EVERY connect - the join window keeps the id it
                // remembers. The seat asked for a ship and flies the host's current version of it; the VARIANT row
                // above is the swap's one record.
            }
            case HELD -> {
                // The tenure clear runs BEFORE the hold — an ACCEPT can seed
                // from the sim thread the moment the hold registers, and a clear after it would
                // erase the tenure's own just-accepted custom.
                tenureClear(seatId, freshTenure);
                hold(sessionId, seatId, authorKey, pick, "hello");
                notifier.chat(sessionId, seatId, pendingLine(pickNoticeName(pickableVariants, pick, null)));
            }
            case ALLOWLIST, OWN_CUSTOM -> {
                Seed s = seeder.seed(seatId, r.canonical());
                if (!s.seeded()) {
                    seedRefused(sessionId, seatId, pick, r.canonical(), "hello", true);
                    tenureClear(seatId, freshTenure);
                    return;
                }
                System.out.println("pilot-datagram-admission VARIANT seat=" + seatId
                        + " pick=" + PeerText.quote(pick) + " canonical=" + r.canonical()
                        + (r.kind() == Kind.OWN_CUSTOM ? " (own custom)" : "")
                        + (s.claimCleared() ? " (module claim cleared)" : ""));
            }
        }
    }

    /** The tenure clear at a non-storing exit of a
     * fresh tenure's apply — the {@link Seeder} is called outside this class's monitor (the
     * class law); a non-fresh apply touches nothing. */
    private void tenureClear(int seatId, boolean freshTenure) {
        if (freshTenure) {
            seeder.clearStanding(seatId);
        }
    }

    /**
     * A seed the dispatch REFUSED — the id was demoted between this class's
     * resolve and the dispatch's store — is a FALLBACK the seat's window may not know about: one
     * VARIANT_SEED_REFUSED line + the debrief's FALLBACK row (never a "canonical=X" success line for a
     * pick that did not land — sync-gap doctrine), and a chat notice when the seat asked by name.
     */
    private void seedRefused(long sessionId, int seatId, String pick, String canonical, String source, boolean tellSeat) {
        System.out.println("pilot-datagram-admission VARIANT_SEED_REFUSED seat=" + seatId + " session=" + sessionId
                + " pick=" + PeerText.quote(pick) + " canonical=" + canonical + " source=" + source
                + " (demoted before the seed; the seat flies its standing pick, or the descriptor on a fresh tenure)");
        FallbackAnnounce.announceOnce("custom-demoted-seed-seat-" + seatId,
                "canonical=" + canonical + " source=" + source + " (demoted between resolve and seed; the standing pick is unchanged)");
        if (tellSeat) {
            notifier.chat(sessionId, seatId, "variant '" + pickNoticeName(pickableVariants, pick, canonical)
                    + "' was just demoted - not seeded; /ship lists ids");
        }
    }

    // ------------------------------------------------------------------ /ship <id> (the custom half)

    /**
     * The door-owned half of {@code /ship <id>} (the author key is the SESSION's, which the
     * dispatch never sees — the {@code /help} precedent): null = not ours, let the dispatch
     * answer (allowlist hit, plain unknown, picking unavailable); else the reply lines — an own
     * accepted custom seeds now ("next respawn"), an own in-flight submission is held.
     */
    public List<String> shipSetCustomOrNull(long sessionId, int seatId, String authorKey, String raw) {
        if (customSource == null || raw == null || canonicalPickOrNull(raw) != null) {
            return null;
        }
        Resolution r = resolve(authorKey, raw, false);
        switch (r.kind()) {
            case OWN_CUSTOM -> {
                Seed s = seeder.seed(seatId, r.canonical());
                if (!s.seeded()) {
                    seedRefused(sessionId, seatId, raw, r.canonical(), "ship", false);
                    return List.of("variant '" + pickNoticeName(pickableVariants, raw, r.canonical())
                            + "' was just demoted - not seeded; /ship lists ids");
                }
                System.out.println("pilot-datagram-admission VARIANT_CUSTOM seat=" + seatId
                        + " session=" + sessionId + " pick=" + PeerText.quote(raw)
                        + " canonical=" + r.canonical() + " source=ship"
                        + (s.claimCleared() ? " (module claim cleared)" : ""));
                return List.of("next respawn: '" + submittedName(r.canonical(), raw) + "'"
                        + (s.claimCleared() ? " (module claim cleared)" : ""));
            }
            case HELD -> {
                hold(sessionId, seatId, authorKey, raw, "ship");
                return List.of(pendingLine(pickNoticeName(pickableVariants, raw, null)));
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ the hold

    private void hold(long sessionId, int seatId, String authorKey, String raw, String source) {
        long deadline = clock.getAsLong() + PENDING_HOLD_MILLIS;
        synchronized (this) {
            heldBySeat.put(seatId, new Held(sessionId, authorKey == null ? "" : authorKey, raw, deadline));
            heldTotal++;
        }
        System.out.println("pilot-datagram-admission VARIANT_HELD seat=" + seatId + " session=" + sessionId
                + " pick=" + PeerText.quote(raw) + " source=" + source + " hold_ms=" + PENDING_HOLD_MILLIS);
    }

    /**
     * The lane ACCEPTed (or dup-answered) one of {@code authorKey}'s submissions: every pick
     * held for that author under the local id or the canonical id seeds NOW (sim thread or
     * reader thread — the order law register ≺ fan ≺ VACK ≺ seed holds because the
     * lane calls this after the receipt is mailed).
     *
     * <p>{@code submitterSeat} (the seat the door bound the VSUB to;
     * {@code < 0} = unknown) seeds TOO when no hold resolved it — the in-match {@code /submit}
     * has no HELLO hold, and a conforming client sends a VSUB only for the pick it wants (ONE
     * per connect for the HELLO pick, else the editor's Submit), so an accepted submission IS the
     * seat's next-respawn pick. Seeded once: a hold hit on that seat already seeded it. A pending
     * fresh-claim request on that seat re-points to the canonical (the hold-hit law: the first
     * fly-in's announce must not read the seat's own submission as a join-pick fallback). Seeded
     * by SEAT like the hold path — the accept lands within a sim tick of the VSUB, and a round wrap
     * clears every override, so a vacated-and-refilled seat in that window is not modelled.
     */
    public void onCustomAccepted(String authorKey, String canonicalId, String localId,
            long submitterSession, int submitterSeat) {
        if (authorKey == null || canonicalId == null) {
            return;
        }
        List<Map.Entry<Integer, Held>> hits = new ArrayList<>();
        boolean submitterHit = false;
        synchronized (this) {
            Iterator<Map.Entry<Integer, Held>> it = heldBySeat.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Held> e = it.next();
                Held h = e.getValue();
                if (!h.authorKey().equals(authorKey)
                        || !(foldEquals(h.raw(), localId) || foldEquals(h.raw(), canonicalId))) {
                    continue;
                }
                hits.add(e);
                it.remove();
                resolvedTotal++;
                submitterHit |= e.getKey() == submitterSeat;
                // The first fly-in's announce must not read "asked for X, got cc-vN-x" as a fallback.
                if (h.raw().equals(requestedBySeat.get(e.getKey()))) {
                    requestedBySeat.put(e.getKey(), canonicalId);
                }
            }
            if (submitterSeat >= 0 && !submitterHit && requestedBySeat.containsKey(submitterSeat)) {
                requestedBySeat.put(submitterSeat, canonicalId);
            }
        }
        for (Map.Entry<Integer, Held> e : hits) {
            int seatId = e.getKey();
            Held h = e.getValue();
            Seed s = seeder.seed(seatId, canonicalId);
            if (!s.seeded()) {
                seedRefused(h.sessionId(), seatId, h.raw(), canonicalId, "held", true);
                continue;
            }
            System.out.println("pilot-datagram-admission VARIANT_HELD_RESOLVED seat=" + seatId
                    + " session=" + h.sessionId() + " pick=" + PeerText.quote(h.raw())
                    + " canonical=" + canonicalId + (s.claimCleared() ? " (module claim cleared)" : ""));
            notifier.chat(h.sessionId(), seatId, "next respawn: '" + submittedName(canonicalId, localId) + "' (pick confirmed)"
                    + (s.claimCleared() ? " (module claim cleared)" : ""));
        }
        if (submitterSeat >= 0 && !submitterHit) {
            if (!seeder.bound(submitterSession, submitterSeat)) {
                // The submitting session is gone (or the seat re-bound) between deposit and the
                // drain's accept: seeding by seat would hand the pick to a stranger — skip, loud.
                System.out.println("pilot-datagram-admission VARIANT_CUSTOM_SKIPPED seat=" + submitterSeat
                        + " session=" + submitterSession + " canonical=" + canonicalId
                        + " source=submit cause=session_unbound");
                return;
            }
            Seed s = seeder.seed(submitterSeat, canonicalId);
            if (!s.seeded()) {
                seedRefused(submitterSession, submitterSeat, localId == null ? "" : localId, canonicalId, "submit", true);
                return;
            }
            synchronized (this) {
                submittedTotal++;
            }
            System.out.println("pilot-datagram-admission VARIANT_CUSTOM seat=" + submitterSeat
                    + " session=" + submitterSession + " pick=" + PeerText.quote(localId == null ? "" : localId)
                    + " canonical=" + canonicalId + " source=submit"
                    + (s.claimCleared() ? " (module claim cleared)" : ""));
            // Names the build, never "custom"; by its
            // quoted NAME, never the minted id (the VARIANT_CUSTOM row above keeps the id)
            notifier.chat(submitterSession, submitterSeat, "next respawn: '" + submittedName(canonicalId, localId) + "'"
                    + (s.claimCleared() ? " (module claim cleared)" : ""));
        }
    }

    /**
     * The name the submitter's "next respawn" line shows - the allowlist's
     * remembered display name of {@code canonicalId}, else the local id the seat typed, else the minted id; bounded
     * by {@link CatalogPromotionLane#chatShipName} (a local id is the seat's own text, echoed back to it alone).
     * Also names the own-custom /ship reply, the held pick's "pick confirmed" line and the standing pick a reject
     * keeps (null local id = the stored id).
     */
    String submittedName(String canonicalId, String localIdOrNull) {
        PickableVariants pickable = pickableVariants;
        String remembered = pickable == null ? null : pickable.displayNameOrNull(canonicalId);
        return CatalogPromotionLane.chatShipName(remembered,
                localIdOrNull == null || localIdOrNull.trim().isEmpty() ? canonicalId : localIdOrNull);
    }

    /**
     * The lane REJECTed one of {@code authorKey}'s submissions
     * (operator gate, policy, ledger gate, engine pass or an operator {@code custom-reject}) —
     * every pick held for that author under that local id is dropped NOW, loud (log + FALLBACK
     * row + CHTD naming the standing pick), instead of sitting out the 30 s deadline and then
     * chatting "not confirmed" after the REJECT toast. Reader or sim thread; the seeder's
     * standing read happens outside our monitor (the door's dispatch monitor is never nested).
     */
    public void onCustomRejected(String authorKey, String localId, String reasonToken) {
        if (authorKey == null || localId == null) {
            return;
        }
        List<Map.Entry<Integer, Held>> hits = new ArrayList<>();
        synchronized (this) {
            if (heldBySeat.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<Integer, Held>> it = heldBySeat.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Held> e = it.next();
                Held h = e.getValue();
                if (!h.authorKey().equals(authorKey) || !foldEquals(h.raw(), localId)) {
                    continue;
                }
                hits.add(e);
                it.remove();
                releasedTotal++;
            }
        }
        String reason = reasonToken == null ? "rejected" : reasonToken;
        for (Map.Entry<Integer, Held> e : hits) {
            int seatId = e.getKey();
            Held h = e.getValue();
            String echo = echo(h.raw());
            String standing = seeder.standingOrNull(seatId);
            System.out.println("pilot-datagram-admission VARIANT_HELD_RELEASED seat=" + seatId
                    + " session=" + h.sessionId() + " pick=" + PeerText.quote(h.raw())
                    + " reason=" + reason + " standing=" + (standing == null ? "none" : standing)
                    + " (seat keeps its standing pick / descriptor row)");
            // A refused pick is a FALLBACK every debrief must list (the expiry twin).
            FallbackAnnounce.announceOnce("hello-pick-rejected-seat-" + seatId,
                    "pick=" + PeerText.quote(echo) + " rejected=" + reason
                            + " standing=" + (standing == null ? "none" : standing)
                            + " (seat keeps its standing pick / descriptor row)");
            // The chat names both ships (a stored kept stem reads by its typed
            // name); the log row and the FALLBACK row above keep the ids
            notifier.chat(h.sessionId(), seatId, "variant '" + pickNoticeName(pickableVariants, h.raw(), null)
                    + "' rejected (" + reason + ") - "
                    + (standing == null ? "flying the server default (no standing pick)"
                            : "keeping your standing pick: '" + submittedName(standing, null) + "'"));
        }
    }

    /** Door timer cadence: drop every hold past its deadline, loud (log + FALLBACK row + CHTD). */
    public void sweepExpired() {
        long now = clock.getAsLong();
        List<Map.Entry<Integer, Held>> expired = new ArrayList<>();
        synchronized (this) {
            if (heldBySeat.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<Integer, Held>> it = heldBySeat.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Held> e = it.next();
                if (now >= e.getValue().deadlineMillis()) {
                    expired.add(e);
                    it.remove();
                    expiredTotal++;
                }
            }
        }
        for (Map.Entry<Integer, Held> e : expired) {
            int seatId = e.getKey();
            Held h = e.getValue();
            String echo = echo(h.raw());
            System.out.println("pilot-datagram-admission VARIANT_HELD_EXPIRED seat=" + seatId
                    + " session=" + h.sessionId() + " pick=" + PeerText.quote(h.raw())
                    + " hold_ms=" + PENDING_HOLD_MILLIS + " (seat keeps its standing pick / descriptor row)");
            // An unconfirmed pick is a FALLBACK every debrief must list.
            FallbackAnnounce.announceOnce("hello-pick-expired-seat-" + seatId,
                    "pick=" + PeerText.quote(echo) + " not_confirmed_in_ms=" + PENDING_HOLD_MILLIS
                            + " (seat keeps its standing pick / descriptor row)");
            notifier.chat(h.sessionId(), seatId, "variant '" + pickNoticeName(pickableVariants, h.raw(), null) + "' not confirmed in "
                    + (PENDING_HOLD_MILLIS / 1000) + " s - keeping your standing pick");
        }
    }

    // ------------------------------------------------------------------ counters (status rows / tests)

    public synchronized int heldCount() {
        return heldBySeat.size();
    }

    public synchronized int heldTotal() {
        return heldTotal;
    }

    public synchronized int resolvedTotal() {
        return resolvedTotal;
    }

    public synchronized int expiredTotal() {
        return expiredTotal;
    }

    /** Holds dropped by a REJECT before their deadline. */
    public synchronized int releasedTotal() {
        return releasedTotal;
    }

    /** Accepts that seeded the SUBMITTER's seat with no hold to resolve. */
    public synchronized int submittedTotal() {
        return submittedTotal;
    }

    // ------------------------------------------------------------------ helpers

    private static String pendingLine(String echo) {
        return "variant '" + echo + "' pending confirmation - flies once accepted ("
                + (PENDING_HOLD_MILLIS / 1000) + " s)";
    }

    private static String echo(String peerText) {
        return peerText.length() <= 24 ? peerText : peerText.substring(0, 24);
    }

    /**
     * The quoted part of a {@code variant '...'} pick notice - the remembered
     * display name of the ship the token answers to (looked up by {@code canonicalOrNull} when the pick resolved, else
     * by the token itself; the allowlist never forgets a name, so a kept copy that has since left /ship still reads by
     * it), bounded by {@link CatalogPromotionLane#chatShipName} (printable ASCII, at most 24 chars); a token no ship
     * answers to echoes back as typed, cut by {@link #echo}. WHY: a kept copy's stem ends in a hex tail no player
     * should read back, while an unknown token is the player's own input. Only the quoted part moves: the client's
     * /ship reply latch matches the fixed text before the quote. The log and FALLBACK rows keep the token.
     */
    static String pickNoticeName(PickableVariants pickableOrNull, String token, String canonicalOrNull) {
        String raw = token == null ? "" : token;
        String name = pickableOrNull == null ? null
                : pickableOrNull.displayNameOrNull(canonicalOrNull != null ? canonicalOrNull : raw);
        return name == null || name.trim().isEmpty() ? echo(raw) : CatalogPromotionLane.chatShipName(name, raw);
    }

    /** The pick allowlist's own ASCII fold — a typed local id matches its submission's case-insensitively. */
    static boolean foldEquals(String a, String b) {
        return a != null && b != null && ChatCommands.asciiLower(a).equals(ChatCommands.asciiLower(b));
    }

    /**
     * The standing-pick store a supersede MOVES picks across: {@code takeNaming}
     * removes and returns every seat's standing override whose value is one of the ids (the dispatch's
     * {@code clearOverridesNaming}); {@code put} seeds {@code canonical} as the seat's standing override, false when
     * the store refused it (a hidden id). The door implements it over its dispatch under ONE monitor.
     */
    public interface StandingStore {
        Map<Integer, String> takeNaming(Collection<String> canonicalIds);

        boolean put(int seatId, String canonical);
    }

    /**
     * Move every standing pick naming {@code from} to {@code to} - a superseded ship's seats fly the copy at their
     * next respawn with NO pick-clear line (the hide that follows finds no pick naming X); a put-back moves them home
     * the same way. Pure over the store; null ids or a fold-equal pair move nothing. Returns the seats moved.
     */
    public static int moveStanding(StandingStore store, String from, String to) {
        if (store == null || from == null || to == null || foldEquals(from, to)) {
            return 0;
        }
        Map<Integer, String> taken = store.takeNaming(List.of(from));
        int moved = 0;
        for (Integer seat : taken.keySet()) {
            if (store.put(seat, to)) {
                moved++;
            }
        }
        return moved;
    }
}
