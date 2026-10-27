package cc.starsector.server.runtime;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The MATCH-BOUNDED SOURCE HOLD and the match's ONE
 * moderation budget.
 *
 * <p>A hold bars a door source KEY ({@link DoorSourceBudget#sourceKey}: IPv4 as-is, IPv6 by its
 * /56) at the HELLO ban seam — the same terminal {@code banned} reply as a ban (no oracle). It is
 * installed only by a chat-origin ({@code ADMIN}) CID ban, per the operator knob {@link Policy}
 * ({@code moderation.chatSourceHold}), never on a LOOPBACK peer (the host's own windows) and never
 * on the requesting admin's own source key (the self-hold guard).
 * The W16 law: every pair carries the ban's wall-clock
 * {@code until} (epoch ms, 0 = permanent), persisted on the paired cid entry as
 * {@code src=}/{@code until=} ({@link BanSet.Entry}); at boot {@link #hydrate} re-installs the live
 * pairs UNCHARGED; a pair expires LAZILY (a source whose pairs are all expired is dropped at the
 * next {@link #isHeld} / {@link #install} / {@link #keysForLog(int)} that touches it). The door
 * holds this as a {@code final} field ({@code PSS.serve()}'s try-with-resources closes the door at
 * serve end); the file, not this object, carries a hold across restarts.
 *
 * <p>Pairing (A3): a hold is keyed by source and records the cid keys whose bans installed or
 * joined it; {@link #liftByCidCount} removes one pair and the hold lifts when its last LIVE pair
 * goes; {@link #liftBySrc} lifts it outright (the house {@code unban src <key>}, A8).
 *
 * <p>Budget (A3, re-cut at the merge): {@link PilotIntentDatagramServer#ADMIN_BAN_CAP} units
 * per door, where a unit = a hold together with EVERY cid entry paired with it, or a cid entry with
 * no hold wanted (knob {@code off}, a loopback / self-source / non-observer-under-{@code observer}
 * target). {@link #atCapForNewUnit} refuses only a verb that would add a NEW unit; {@link #charge}
 * is called once per verb that added one. No refund on a lift (today's law). The entries a held
 * source can pair are bounded by the sessions live on it at install (a held source cannot HELLO),
 * plus the persisted pairs the boot hydrate re-installs (≤ {@link BanSet#MAX_ENTRIES}).
 *
 * <p>Concurrency: its OWN monitor; it never calls out (the hydrate snapshots the {@link BanSet}
 * BEFORE taking this monitor). Lock order = the door {@code lock} → this monitor only (the
 * install/charge path runs inside the door's ban lock take); a lift or a read may take this monitor
 * alone. Lifts run on the SERVE thread (the {@code banKey} caller's thread, {@code PilotServeSession}'s
 * operator lane): the pre-check / charge pair in {@code banKey} relies on no lift landing between
 * them. The hydrate runs once at boot, before the door's reader starts.
 */
public final class DoorSourceHold {

    /** The {@code moderation.chatSourceHold} arm: {@code off} = A7 strict (a chat ban is a cid bar
     *  only; a null policy maps here); {@code observer} = holds on OBSERVER targets only;
     *  {@code match} = holds on every chat-origin ban. A hold lasts the ban's term across restarts;
     *  the shipped default is the serve resolver's, not this class's. */
    public enum Policy {
        OFF, OBSERVER, MATCH;

        /** The operator token ({@code off} / {@code observer} / {@code match}). */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** LOUD parse: an unknown or blank token throws naming the three (the boot refuses). */
        public static Policy parse(String raw) {
            String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            for (Policy p : values()) {
                if (p.token().equals(t)) {
                    return p;
                }
            }
            throw new IllegalArgumentException("moderation.chatSourceHold must be one of off | observer"
                    + " | match, got: '" + t + "'");
        }
    }

    /** The pair key a null cid installs under (never a paired key: the door pairs only a cid entry's key, the
     *  35-char digest {@link BanSet#isKey} demands), so a cid-less install still holds until {@link #liftBySrc}. */
    private static final String NO_CID = "";

    private final Policy policy;
    /** src key → (paired cid key → wall-clock {@code until}, 0 = permanent). Insertion order =
     *  install order (a source re-held after all its pairs expired re-enters as the newest). */
    private final LinkedHashMap<String, LinkedHashMap<String, Long>> holds = new LinkedHashMap<>();
    private int unitsCharged;

    public DoorSourceHold(Policy policy) {
        this.policy = policy == null ? Policy.OFF : policy;
    }

    public Policy policy() {
        return policy;
    }

    /**
     * The install decision (A1/A2 + the self-hold guard), pure: the target's source key when a
     * hold is wanted, else null. {@code cidBan} = a CID ban of ANY origin — chat, console or command
     * file (every ban records the address under the one
     * {@link Policy}; an explicit SRC ban already bars the source durably); {@code peer} = the target's
     * PEER address (its loopback bit, never the key's: IPv4 127/8 and IPv6 {@code ::1}; a v4-mapped
     * loopback arrives as IPv4); {@code requesterOrNull} = the requesting admin session's peer (null =
     * none known — a console / file ban has no requester, so no self guard).
     */
    String wantedSrcOrNull(boolean cidBan, boolean targetObserver, InetAddress peer,
            InetAddress requesterOrNull) {
        if (!cidBan || peer == null || peer.isLoopbackAddress()
                || !(policy == Policy.MATCH || (policy == Policy.OBSERVER && targetObserver))) {
            return null;
        }
        String src = DoorSourceBudget.sourceKey(peer);
        boolean self = requesterOrNull != null && src.equals(DoorSourceBudget.sourceKey(requesterOrNull));
        return src.isEmpty() || self ? null : src;
    }

    /** A permanent pair ({@link #install(String, String, long)} with {@code until = 0}). */
    public synchronized boolean install(String srcKey, String cidKeyOrNull) {
        return install(srcKey, cidKeyOrNull, 0L);
    }

    /** Hold {@code srcKey} and pair {@code cidKeyOrNull}
     *  with it until {@code until} (wall-clock epoch ms, 0 = permanent). A NEW hold iff the source
     *  is absent OR every pair it has is expired on the wall clock (swept first); an existing pair
     *  takes the new {@code until}. True iff a NEW hold was added. */
    public synchronized boolean install(String srcKey, String cidKeyOrNull, long until) {
        return installLocked(srcKey, cidKeyOrNull, until, System.currentTimeMillis());
    }

    /** The door's install on the CALLER's clock — the same
     *  {@code wallNow} its cap pre-check read, so a pair lapsing between the two reads cannot count as
     *  held there and as a new unit here (a 17th unit). Row (m). */
    public synchronized boolean install(String srcKey, String cidKeyOrNull, long until, long wallNow) {
        return installLocked(srcKey, cidKeyOrNull, until, wallNow);
    }

    private boolean installLocked(String srcKey, String cidKeyOrNull, long until, long wallNow) {
        LinkedHashMap<String, Long> pairs = liveOrSweep(srcKey, wallNow);
        boolean added = pairs == null;
        if (added) {
            pairs = new LinkedHashMap<>();
            holds.put(srcKey, pairs);
        }
        pairs.put(cidKeyOrNull == null ? NO_CID : cidKeyOrNull, Math.max(0L, until));
        return added;
    }

    /** The HELLO seam's question on the WALL clock ({@link #isHeld(String, long)}). */
    public boolean isHeld(String srcKey) {
        return isHeld(srcKey, System.currentTimeMillis());
    }

    /** The HELLO seam's question (the door calls it under its
     *  {@code lock}) — held iff ≥ 1 LIVE pair ({@code until == 0 || until > wallNow}); a source
     *  whose pairs are all expired is REMOVED here (the lazy sweep) and not held. */
    public synchronized boolean isHeld(String srcKey, long wallNow) {
        return srcKey != null && liveOrSweep(srcKey, wallNow) != null;
    }

    /** The pairs of {@code srcKey} when it has ≥ 1 live pair at {@code wallNow} (its expired pairs
     *  dropped), else null — a source left with none is removed. Monitor held by the caller. */
    private LinkedHashMap<String, Long> liveOrSweep(String srcKey, long wallNow) {
        LinkedHashMap<String, Long> pairs = holds.get(srcKey);
        if (pairs == null) {
            return null;
        }
        pairs.values().removeIf(until -> until != 0L && until <= wallNow);
        if (pairs.isEmpty()) {
            holds.remove(srcKey);
            return null;
        }
        return pairs;
    }

    /** Remove the pair {@code cidKey} from every hold; true iff a hold LIFTED (its last live pair
     *  went) — {@code liftByCidCount(cidKey) == 2}. */
    public synchronized boolean liftByCid(String cidKey) {
        return liftByCidCount(cidKey) == 2;
    }

    /** Remove the pair {@code cidKey} from every hold →
     *  0 = paired nowhere; 1 = a pair removed, its hold remains (another live pair); 2 = a hold
     *  lifted (no live pair left on the wall clock — the max over every hold it was paired in). */
    public synchronized int liftByCidCount(String cidKey) {
        if (cidKey == null) {
            return 0;
        }
        long wallNow = System.currentTimeMillis();
        int result = 0;
        for (Iterator<LinkedHashMap<String, Long>> it = holds.values().iterator(); it.hasNext(); ) {
            LinkedHashMap<String, Long> pairs = it.next();
            if (pairs.remove(cidKey) == null) {
                continue;
            }
            pairs.values().removeIf(until -> until != 0L && until <= wallNow);
            if (pairs.isEmpty()) {
                it.remove();
                result = 2;
            } else {
                result = Math.max(result, 1);
            }
        }
        return result;
    }

    /** Lift the hold on {@code srcKey} outright (all its pairs); true iff one was held. */
    public synchronized boolean liftBySrc(String srcKey) {
        return srcKey != null && holds.remove(srcKey) != null;
    }

    /** The retime twin of {@link BanSet#retime} (the door
     *  calls both in ONE lock take — the entry and its pairs never drift): every pair with
     *  {@code cidKey} takes {@code until} (0 = permanent); true iff ≥ 1 pair found. */
    public synchronized boolean retime(String cidKey, long until) {
        if (cidKey == null) {
            return false;
        }
        boolean found = false;
        long u = Math.max(0L, until);
        for (LinkedHashMap<String, Long> pairs : holds.values()) {
            if (pairs.containsKey(cidKey)) {
                pairs.put(cidKey, u);
                found = true;
            }
        }
        return found;
    }

    /** The boot re-install — every CID entry of {@code bans}
     *  (any origin) with a {@code src} and live at {@code wallNow} re-pairs as
     *  {@code install(src, key, until)}. Charges NOTHING (a reloaded bar spends no unit — the
     *  ≤ 16 units are per-serve chat verbs) and runs regardless of the {@link Policy} (the knob
     *  governs NEW installs only). The snapshot is taken BEFORE this monitor (never calls
     *  out under it). Returns the PAIR count installed.
     *  A {@code src} that lies within loopback
     *  ({@link SourceRanges#isLoopbackKey}, the one predicate the file parse and the typed-key normalizer share) is
     *  never re-installed: a loopback hold would refuse the host's own windows. */
    public int hydrate(BanSet bans, long wallNow) {
        List<BanSet.Entry> snapshot = bans.snapshot();
        synchronized (this) {
            int pairs = 0;
            for (BanSet.Entry e : snapshot) {
                if (e.kind() == BanSet.Kind.CID && e.src() != null && !SourceRanges.isLoopbackKey(e.src())
                        && !e.expired(wallNow)) {
                    installLocked(e.src(), e.key(), e.until(), wallNow);
                    pairs++;
                }
            }
            return pairs;
        }
    }

    /** The live hold count ({@code door_source_holds=}; RAW — an all-expired source counts until a
     *  lazy sweep drops it). */
    public synchronized int size() {
        return holds.size();
    }

    /** The FULL held source keys, insertion order, RAW — the operator {@code bans} verb only (keys
     *  are server-minted; never a chat reply, never a peer string). */
    public synchronized List<String> keysForLog() {
        return new ArrayList<>(holds.keySet());
    }

    /** The NEWEST {@code max} held keys, NEWEST FIRST (the
     *  reverse of install order), after a lazy sweep of every all-expired source on the wall clock
     *  — so {@code size() - keysForLog(max).size()} is the exact {@code holds_more=}. Same audience
     *  as {@link #keysForLog()}. */
    public synchronized List<String> keysForLog(int max) {
        long wallNow = System.currentTimeMillis();
        for (String src : new ArrayList<>(holds.keySet())) {
            liveOrSweep(src, wallNow);
        }
        List<String> all = new ArrayList<>(holds.keySet());
        List<String> newest = new ArrayList<>(Math.min(Math.max(0, max), all.size()));
        for (int i = all.size() - 1; i >= 0 && newest.size() < max; i--) {
            newest.add(all.get(i));
        }
        return newest;
    }

    /** {@link #atCapForNewUnit(boolean, String, long)} on the WALL clock. */
    public synchronized boolean atCapForNewUnit(boolean cidEntryExists, String srcKeyOrNull) {
        return atCapForNewUnit(cidEntryExists, srcKeyOrNull, System.currentTimeMillis());
    }

    /** A3 pre-check (door {@code lock} held): true iff the budget is spent AND the verb would add a
     *  NEW unit. Re-cut at the merge: with a hold wanted ({@code srcKeyOrNull != null}) the
     *  unit IS the hold — new iff that source is not HELD at {@code wallNow} (a cid entry pairs into
     *  a live hold for free; an all-expired source is a new unit); with none wanted the
     *  unit is the cid entry — new iff none exists for its key. The same term {@code banKey}
     *  charges, so the count never passes the cap. */
    public synchronized boolean atCapForNewUnit(boolean cidEntryExists, String srcKeyOrNull, long wallNow) {
        boolean newUnit = srcKeyOrNull != null ? !isHeld(srcKeyOrNull, wallNow) : !cidEntryExists;
        return unitsCharged >= PilotIntentDatagramServer.ADMIN_BAN_CAP && newUnit;
    }

    /** A3 charge: +1 for a verb that added a NEW unit (a hold, or a cid entry with no hold wanted);
     *  called once per such verb. */
    public synchronized void charge() {
        unitsCharged++;
    }
}
