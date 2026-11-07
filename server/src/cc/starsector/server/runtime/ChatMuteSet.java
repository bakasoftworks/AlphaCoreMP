package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The server's text-chat mutes: who an admin silenced, keyed so a re-join, a takeover or a seat
 * swap cannot shed it. A sender is muted when EITHER of its keys equals the same-kind key of an
 * entry: the id key (a token-mode pilot's seat key, else the digest of a non-blank client id) or
 * the address key the door's source hold already derives. In memory only — a mute lasts until it
 * is lifted or the server stops, and nothing here is ever written to the bans file.
 *
 * <p>NOT thread-safe by design: the door owns one instance and the CALLER holds {@code door.lock}
 * across every call (the drop check on the reader thread, the chat verbs, the console verbs), so a
 * check and the write it guards are one critical section. Pure: no door, socket or log reference,
 * and no call ever leaves this class while the lock is held.
 *
 * <p>Bounded: at most {@link #CAP} entries, REFUSED at the cap and never evicted (an eviction would
 * be a silent lift); a lift frees a slot. Every entry is two short server-minted keys plus a label
 * clipped to {@link #LABEL_MAX_CHARS} printable ASCII chars, so the whole set stays a few KB.
 *
 */
public final class ChatMuteSet {

    /**
     * The live-entry cap — the security number: twice the 16-entry ban budget (a mute is
     * reversible and is the first remedy a host reaches for), yet a leaked admin password filling it
     * at the admin token bucket's pace is done within seconds and after that is only refused. Three
     * pages of the mute list.
     */
    public static final int CAP = 32;

    /** A stored label's clip (printable ASCII after {@link Entry}'s fold), so a list row always fits
     *  the chat line cap whoever composed the label. */
    public static final int LABEL_MAX_CHARS = 40;

    /** Which admin surface set the entry (shown in the mute list; any admin surface may lift any). */
    public enum Origin { CHAT, CONSOLE }

    /**
     * One mute. A blank key normalizes to {@code null} (a blank client id digests to a value every
     * key-less session would share, so it must never become a key); an entry with BOTH keys null is
     * refused by {@link #add} ({@link Verdict#NO_KEY}) and never stored. {@code label} = the
     * human-readable target captured AT MUTE TIME (so a player who left still lists), folded to
     * printable ASCII, trimmed and clipped to {@link #LABEL_MAX_CHARS}; {@code null} = "".
     * {@code mutedAtMillis} = the caller's clock (the mute list shows its age against the same clock).
     * IDENTITY is the key pair ({@link #sameKeys}), never the label or the stamp.
     */
    public record Entry(String idKeyOrNull, String srcKeyOrNull, Origin origin, String label, long mutedAtMillis) {
        public Entry {
            idKeyOrNull = blankToNull(idKeyOrNull);
            srcKeyOrNull = blankToNull(srcKeyOrNull);
            Objects.requireNonNull(origin, "origin");
            label = foldLabel(label);
        }

        /** Constant: the record carries the mute keys, and a logged or concatenated entry must never print them
         *  (the {@code Auth13Inputs} precedent). */
        @Override
        public String toString() {
            return "ChatMuteSet.Entry";
        }

        /** At least one key: the only shape {@link #add} stores. */
        public boolean hasKey() {
            return idKeyOrNull != null || srcKeyOrNull != null;
        }

        /** True when either non-null key of the sender equals this entry's same-kind key. */
        public boolean matches(String idKeyOrNull, String srcKeyOrNull) {
            String id = blankToNull(idKeyOrNull);
            String src = blankToNull(srcKeyOrNull);
            return (id != null && id.equals(this.idKeyOrNull)) || (src != null && src.equals(this.srcKeyOrNull));
        }

        /** Key-pair identity: the same mute whatever its label, origin or stamp. */
        public boolean sameKeys(Entry other) {
            return other != null && Objects.equals(idKeyOrNull, other.idKeyOrNull)
                    && Objects.equals(srcKeyOrNull, other.srcKeyOrNull);
        }
    }

    /** {@link #add}'s outcome: stored, refused as a duplicate (any key already covered), refused at
     *  {@link #CAP} (nothing evicted), or refused for carrying no key at all. */
    public enum Verdict { ADDED, ALREADY_MUTED, CAP_FULL, NO_KEY }

    /** Insertion order, oldest first; never more than {@link #CAP} entries. */
    private final ArrayList<Entry> entries = new ArrayList<Entry>(CAP);

    /**
     * Store {@code entry} unless it has no key ({@link Verdict#NO_KEY}), either key is already
     * covered by a stored entry ({@link Verdict#ALREADY_MUTED} — setting a mute twice is a no-op,
     * and a sender reached through a shared address IS already muted) or the set is full
     * ({@link Verdict#CAP_FULL}, checked last so a duplicate at the cap still answers "already").
     */
    public Verdict add(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entry.hasKey()) {
            return Verdict.NO_KEY;
        }
        if (matches(entry.idKeyOrNull(), entry.srcKeyOrNull())) {
            return Verdict.ALREADY_MUTED;
        }
        if (entries.size() >= CAP) {
            return Verdict.CAP_FULL;
        }
        entries.add(entry);
        return Verdict.ADDED;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The per-line drop test: true when either non-null key equals a stored entry's same-kind
     *  key. One {@code isEmpty()} when nobody is muted (the common case on every chat line). */
    public boolean matches(String idKeyOrNull, String srcKeyOrNull) {
        if (entries.isEmpty()) {
            return false;
        }
        for (Entry e : entries) {
            if (e.matches(idKeyOrNull, srcKeyOrNull)) {
                return true;
            }
        }
        return false;
    }

    /** Lift the stored entry with {@code entry}'s key pair; false when none (already lifted). */
    public boolean lift(Entry entry) {
        if (entry == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).sameKeys(entry)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /** The console lift: remove EVERY entry the occupant's keys match and return them, newest
     *  first (empty = nothing matched; the receipt says so). */
    public List<Entry> liftMatching(String idKeyOrNull, String srcKeyOrNull) {
        List<Entry> lifted = new ArrayList<Entry>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).matches(idKeyOrNull, srcKeyOrNull)) {
                lifted.add(entries.remove(i));
            }
        }
        return List.copyOf(lifted);
    }

    /** An immutable copy, newest first (insertion order reversed — independent of the stamp clock). */
    public List<Entry> snapshotNewestFirst() {
        List<Entry> copy = new ArrayList<Entry>(entries.size());
        for (int i = entries.size() - 1; i >= 0; i--) {
            copy.add(entries.get(i));
        }
        return List.copyOf(copy);
    }

    /** The entries other than {@code exceptOrNull} (by key pair) that still cover a sender with these
     *  keys, newest first — the lift receipt's "still muted via" note (a shared address). */
    public List<Entry> coveringExcept(String idKeyOrNull, String srcKeyOrNull, Entry exceptOrNull) {
        List<Entry> covering = new ArrayList<Entry>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.matches(idKeyOrNull, srcKeyOrNull) && !e.sameKeys(exceptOrNull)) {
                covering.add(e);
            }
        }
        return List.copyOf(covering);
    }

    /** How many stored entries carry exactly this address key (0 for a null or blank key). */
    public int countSrc(String srcKey) {
        String src = blankToNull(srcKey);
        if (src == null) {
            return 0;
        }
        int n = 0;
        for (Entry e : entries) {
            if (src.equals(e.srcKeyOrNull())) {
                n++;
            }
        }
        return n;
    }

    static String blankToNull(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    /** Printable ASCII 0x20-0x7E only, trimmed, clipped to {@link #LABEL_MAX_CHARS} (then re-trimmed). */
    static String foldLabel(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(Math.min(raw.length(), LABEL_MAX_CHARS * 2));
        for (int i = 0; i < raw.length() && kept.length() <= LABEL_MAX_CHARS * 2; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c < 0x7F && (c != ' ' || kept.length() > 0)) { // leading spaces never count
                kept.append(c);
            }
        }
        String trimmed = kept.toString().trim();
        return trimmed.length() > LABEL_MAX_CHARS ? trimmed.substring(0, LABEL_MAX_CHARS).trim() : trimmed;
    }
}
