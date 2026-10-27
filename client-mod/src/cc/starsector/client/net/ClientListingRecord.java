package cc.starsector.client.net;

import java.util.List;
import java.util.Objects;

/**
 * One decoded {@code LSTD} structured-listing frame off the live server stream (SS382
 * commission B; {@link ClientListingReader} is the
 * decoder): ONE frame of the server's reply to a no-arg {@code /ship}, {@code /module} or
 * {@code /transfer}, unicast to the requesting seat's session. A listing drips as
 * {@code seqCount} frames keyed by a per-seat {@code listingId} mint; latest-wins by design —
 * a newer {@code listingId} for the same seat supersedes any partial older one, so the
 * assembler never blocks on a lost frame.
 *
 * <p>Mirrors the server codec's {@code ListingFrameCodec.ListingDown}/{@code ListingEntry}
 * value shape exactly, including its minimal-guard stance: field LAWS live in the decoder
 * (every frame that reaches a constructed record has already passed the full fail-closed
 * validation), so this class only null-guards and defensively copies. The listing vocabulary
 * (kinds, mark codes, the claim flag) lives on {@link ClientListingReader} beside the laws
 * that police it.
 */
public final class ClientListingRecord {

    private final int seatId;
    private final int kind;
    private final int listingId;
    private final int seqIndex;
    private final int seqCount;
    private final int totalEntries;
    private final int totalAvailable;
    private final List<Entry> entries;

    public ClientListingRecord(int seatId, int kind, int listingId, int seqIndex, int seqCount,
            int totalEntries, int totalAvailable, List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        this.seatId = seatId;
        this.kind = kind;
        this.listingId = listingId;
        this.seqIndex = seqIndex;
        this.seqCount = seqCount;
        this.totalEntries = totalEntries;
        this.totalAvailable = totalAvailable;
        this.entries = List.copyOf(entries);
    }

    /** The requesting seat this listing was unicast to. */
    public int seatId() {
        return seatId;
    }

    /** Listing kind ({@link ClientListingReader#KIND_SHIP} / MODULE / TRANSFER). */
    public int kind() {
        return kind;
    }

    /** The per-seat listing mint — a higher id for the same seat supersedes this listing. */
    public int listingId() {
        return listingId;
    }

    /** This frame's position in the drip ({@code 0..seqCount-1}). */
    public int seqIndex() {
        return seqIndex;
    }

    /** Total frames in the listing ({@code == ceil(totalEntries / ENTRIES_PER_FRAME)} by law). */
    public int seqCount() {
        return seqCount;
    }

    /** Rows in the whole listing (capped; the wire never carries more than 128). */
    public int totalEntries() {
        return totalEntries;
    }

    /** The full source count before the cap — the picker's "+N more" renders from this. */
    public int totalAvailable() {
        return totalAvailable;
    }

    /** This frame's rows, in listing order (immutable). */
    public List<Entry> entries() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientListingRecord)) {
            return false;
        }
        ClientListingRecord other = (ClientListingRecord) o;
        return seatId == other.seatId && kind == other.kind && listingId == other.listingId
                && seqIndex == other.seqIndex && seqCount == other.seqCount
                && totalEntries == other.totalEntries && totalAvailable == other.totalAvailable
                && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seatId, kind, listingId, seqIndex, seqCount,
                totalEntries, totalAvailable, entries);
    }

    @Override
    public String toString() {
        return "ClientListingRecord{seatId=" + seatId + ", kind=" + kind
                + ", listingId=" + listingId + ", seq=" + seqIndex + "/" + seqCount
                + ", totalEntries=" + totalEntries + ", totalAvailable=" + totalAvailable
                + ", entries=" + entries + "}";
    }

    /**
     * One listing row. {@code token} is the EXACT grammar-resolvable string the existing
     * chat commands accept ({@code /ship <id>}, {@code /module <tok>}) — the picker never
     * invents its own resolution surface. {@code targetLabel} and {@code occupantLabel} are
     * {@code null} when absent (field laws in the {@link ClientListingReader} javadoc).
     */
    public static final class Entry {

        private final String token;
        private final int markCode;
        private final boolean claimedNextRespawn;
        private final String targetLabel;
        private final String occupantLabel;
        private final long hullWireId;
        private final String value;

        public Entry(String token, int markCode, boolean claimedNextRespawn,
                String targetLabel, String occupantLabel, long hullWireId) {
            this(token, markCode, claimedNextRespawn, targetLabel, occupantLabel, hullWireId, null);
        }

        /** {@code value} = the display-only SET value inside a client-local row's token (drawn
         *  cyan); never on the wire, outside equals/hashCode. On a /ban or /unban
         *  menu row it is instead the picker-local ladder term Left/Right stepped (null = the open rung). */
        public Entry(String token, int markCode, boolean claimedNextRespawn,
                String targetLabel, String occupantLabel, long hullWireId, String value) {
            Objects.requireNonNull(token, "token");
            this.token = token;
            this.markCode = markCode;
            this.claimedNextRespawn = claimedNextRespawn;
            this.targetLabel = targetLabel;
            this.occupantLabel = occupantLabel;
            this.hullWireId = hullWireId;
            this.value = value;
        }

        /** The display-only value substring (a suffix of the token), a /ban or /unban row's
         *  picker-local ladder term, or {@code null}. */
        public String value() {
            return value;
        }

        /** The grammar-resolvable selection string (1..64 spaceless printable ASCII). */
        public String token() {
            return token;
        }

        /** Kind-scoped presentation mark ({@code ClientListingReader.MARK_*}). */
        public int markCode() {
            return markCode;
        }

        /** The /module "(next respawn)" claim flag — orthogonal to the occupancy mark. */
        public boolean claimedNextRespawn() {
            return claimedNextRespawn;
        }

        /** /transfer rows only: the sanitized target hull label; {@code null} elsewhere. */
        public String targetLabel() {
            return targetLabel;
        }

        /** The occupant's seat name on the three occupant-carrying marks; {@code null} elsewhere. */
        public String occupantLabel() {
            return occupantLabel;
        }

        /** /transfer rows only: the {@code /transfer #<wireId>} grammar id; 0 elsewhere. */
        public long hullWireId() {
            return hullWireId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry other = (Entry) o;
            return token.equals(other.token) && markCode == other.markCode
                    && claimedNextRespawn == other.claimedNextRespawn
                    && Objects.equals(targetLabel, other.targetLabel)
                    && Objects.equals(occupantLabel, other.occupantLabel)
                    && hullWireId == other.hullWireId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(token, markCode, claimedNextRespawn,
                    targetLabel, occupantLabel, hullWireId);
        }

        @Override
        public String toString() {
            return "Entry{token='" + token + "', mark=" + markCode
                    + (claimedNextRespawn ? ", claimed" : "")
                    + (targetLabel == null ? "" : ", target='" + targetLabel + "'")
                    + (occupantLabel == null ? "" : ", occupant='" + occupantLabel + "'")
                    + (hullWireId == 0 ? "" : ", hullWireId=" + hullWireId) + "}";
        }
    }
}
