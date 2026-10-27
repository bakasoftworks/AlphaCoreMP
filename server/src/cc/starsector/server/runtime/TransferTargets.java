package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * W3 command-transfer target census: the serve loop's
 * PUBLISHED view of every hull {@code /transfer} may name — friendly AI hulls and PARKED
 * abandoned player hulls (the eligible set, packet defaults 1), plus occupied and
 * grace-reserved hulls so a deny can say WHY (the occupied hard-deny stays caller-side at
 * dispatch; the serve drain re-checks authoritatively at commit). The sim thread re-publishes
 * whole tables on roster change; the door's reader thread resolves and lists — each call swaps
 * or reads one immutable snapshot under this object's monitor (never-nested lock law: no
 * caller holds another lock across a call).
 *
 * <p>Vocabulary law (the {@code /ship} stem-law shape): the chat arg is ASCII-folded and
 * matched EXACTLY against server-authored stem tokens minted at publish time — the wire string
 * is never stored and never resolves an engine identity directly. A stem is the label's LAST
 * word folded to {@code [a-z0-9]} (ship names share a leading prefix — "ISS Hamburg" stems to
 * "hamburg"); collisions take positional numeric suffixes ("hamburg", "hamburg2") in publish
 * order. Labels arrive as engine ship names and are sanitized HERE to bounded printable ASCII
 * (chat single-line law upstream of every reply that embeds one).
 *
 * <p>
 * Teams: a publish carries TWO tables — south (side 0) and north (side 1) — plus the seat
 * -&gt; team map, and every dispatch read is seat-keyed: a seat lists and resolves against its
 * OWN team's table only, so a north seat never sees (or names) a south hull and vice versa. A
 * seat with no team in the map (not flown in yet, or its team did not read) reads the south
 * table, the pre-team behaviour; a match with no side-1 seat never reads the north table.
 */
public final class TransferTargets {

    /** Longest sanitized label/token (the seat-name law's bound — replies embed these). */
    private static final int LABEL_MAX_CHARS = 24;

    /**
     * The serve loop's classification of one published hull. {@code AI} = friendly AI-flown
     * (eligible); {@code PARKED} = abandoned player hull past its grace window (eligible,
     * {@code MatchSeatAuthority.abandonedEligible}); {@code GRACE} = a leaver's hull still
     * reserved for its owner (deny, names the owner); {@code OCCUPIED} = a seat pilots it
     * (hard deny, names the pilot — or "you").
     */
    public enum Status { AI, PARKED, GRACE, OCCUPIED }

    /**
     * One hull as the serve loop publishes it. {@code occupantSeatId} is the piloting
     * (OCCUPIED) or reserving (GRACE/PARKED) seat, {@code -1} for none (AI); {@code
     * occupantLabel} is that seat's display name or null (replies fall back to "seat N").
     */
    public record PublishRow(long hullWireId, String label, Status status, int occupantSeatId,
            String occupantLabel) {}

    /** One resolvable target: a {@link PublishRow} plus its minted stem token. */
    public record Target(long hullWireId, String token, String label, Status status,
            int occupantSeatId, String occupantLabel) {

        /** Deny-reply vocabulary: the occupant's announced name, or "seat N" until known. */
        public String occupantOrSeatLabel() {
            return occupantLabel != null ? occupantLabel : ("seat " + occupantSeatId);
        }
    }

    /** The south (side 0) table — also the table of every seat with no team in {@link #seatSides}. */
    private List<Target> targets = List.of();

    /**
     *
     * The north (side 1) table and the seat -&gt; team map (seat id &gt; 0 -&gt; 0 or 1) that
     * picks a seat's table; both swap with {@link #targets} in one publish. An empty north table
     * (no seat flies for side 1, or its census read failed) leaves a north seat with nothing to
     * list — never the south table.
     */
    private List<Target> northTargets = List.of();
    private Map<Integer, Integer> seatSides = Map.of();

    /**
     * The census seat-hull status law, pure for its contract rows: precedence
     * occupied &gt; grace &gt; parked (mirrors the drain's {@code TransferEligibility} chain);
     * null = none of the three — a dead/never-mounted seat hull, which the census leaves
     * UNLISTED (the drain would deny it target-gone).
     */
    public static Status seatStatusOrNull(boolean occupied, boolean inGraceWindow,
            boolean parkedAbandoned) {
        if (occupied) {
            return Status.OCCUPIED;
        }
        if (inGraceWindow) {
            return Status.GRACE;
        }
        if (parkedAbandoned) {
            return Status.PARKED;
        }
        return null;
    }

    /**
     *
     * Replace the whole census with ONE south table and no teams: the north table empties and the
     * seat -&gt; team map clears, so every seat reads {@code rows}; tokens re-mint in row order.
     * The round wrap's empty publish and the single-table contract fixtures.
     */
    public synchronized void publish(List<PublishRow> rows) {
        List<Target> south = mint(rows);
        targets = south;
        northTargets = List.of();
        seatSides = Map.of();
    }

    /**
     *
     * Replace the whole census per team (sim thread, on roster change): the south and north
     * tables mint their tokens independently, each in its own row order (one word may mint on
     * both tables — a seat only ever resolves against its own), and the seat -&gt; team map
     * replaces the last one whole. All three swap together under this monitor, so a reader never
     * pairs one publish's map with another publish's table; a row that fails to mint throws
     * before anything swaps.
     */
    public synchronized void publish(CommandTransferServeLane.SideCensus census) {
        Objects.requireNonNull(census, "census");
        List<Target> south = mint(census.south());
        List<Target> north = mint(census.north());
        targets = south;
        northTargets = north;
        seatSides = census.seatSides();
    }

    /**
     *
     * The table {@code seatId} reads: the north table for a side-1 seat, else the south one (a
     * side-0 seat, and a seat with no team yet). Caller holds this monitor; ONE map lookup.
     */
    private List<Target> tableFor(int seatId) {
        Integer side = seatSides.get(seatId);
        return side != null && side.intValue() == 1 ? northTargets : targets;
    }

    /**
     *
     * One table's sanitize + stem-mint pass (tokens collide-suffix in row order), immutable.
     */
    private static List<Target> mint(List<PublishRow> rows) {
        Objects.requireNonNull(rows, "rows");
        Set<String> usedTokens = new LinkedHashSet<String>();
        List<Target> minted = new ArrayList<Target>(rows.size());
        for (PublishRow row : rows) {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(row.status(), "row status");
            String label = sanitizeLabel(row.label(), row.hullWireId());
            String occupant = row.occupantLabel() == null
                    ? null : sanitizeLabel(row.occupantLabel(), row.hullWireId());
            String stem = stemOf(label);
            String token = stem;
            for (int n = 2; usedTokens.contains(token); n++) {
                String suffix = Integer.toString(n);
                token = bound(stem, LABEL_MAX_CHARS - suffix.length()) + suffix;
            }
            usedTokens.add(token);
            minted.add(new Target(row.hullWireId(), token, label, row.status(),
                    row.occupantSeatId(), occupant));
        }
        return List.copyOf(minted);
    }

    /**
     *
     * The {@code /transfer #<wireId>} resolve for {@code seatId}: exact numeric match against
     * that seat's OWN team's table, or null — a hull of the other team misses like any unlisted
     * id.
     */
    public synchronized Target resolveByWireIdOrNull(int seatId, long wireId) {
        return byWireIdOrNull(tableFor(seatId), wireId);
    }

    /** The south table's wire-id match, seat-blind (the census-law contract rows' pin; the
     * dispatch reads the seat-keyed form). */
    public synchronized Target resolveByWireIdOrNull(long wireId) {
        return byWireIdOrNull(targets, wireId);
    }

    /**
     *
     * The {@code /transfer <name>} resolve for {@code seatId}: exact fold-match of the chat arg
     * against the tokens minted on that seat's OWN team's table, or null (unknown target — a
     * token minted only on the other team's table included).
     */
    public synchronized Target resolveOrNull(int seatId, String rawArg) {
        return byTokenOrNull(tableFor(seatId), rawArg);
    }

    /** The south table's token match, seat-blind (the census-law contract rows' pin; the
     * dispatch reads the seat-keyed form). */
    public synchronized Target resolveOrNull(String rawArg) {
        return byTokenOrNull(targets, rawArg);
    }

    /**
     *
     * Exact numeric match against one published table, or null. Iteration over that
     * table IS the membership gate — any id outside it denies exactly like a token miss; the
     * wire number is a lookup key into server-authored rows, never stored and never an
     * identity/path/exec input.
     */
    private static Target byWireIdOrNull(List<Target> table, long wireId) {
        for (Target target : table) {
            if (target.hullWireId() == wireId) {
                return target;
            }
        }
        return null;
    }

    /**
     *
     * Exact fold-match of the chat arg against one published table's minted tokens, or null.
     */
    private static Target byTokenOrNull(List<Target> table, String rawArg) {
        Objects.requireNonNull(rawArg, "rawArg");
        String folded = ChatCommands.asciiLower(rawArg);
        for (Target target : table) {
            if (target.token().equals(folded)) {
                return target;
            }
        }
        return null;
    }

    /**
     *
     * {@code seatId}'s OWN team's last-published census rows (immutable snapshot — safe to hand
     * out). Dispatch feeds these through {@link ListingEmitter#transferEntries} for the LSTD
     * listing; empty = nothing published for that team this round (the caller's "no transfer
     * targets" reply, which stays CHTD).
     */
    public synchronized List<Target> targetsSnapshot(int seatId) {
        return tableFor(seatId);
    }

    /**
     *
     * The {@code /transfer} listing prose of THIS seat's team's table, occupancy-marked for this
     * seat (the {@code /module} listing shape). SS382 L3 retired its dispatch use (the LSTD
     * listing rides {@link #targetsSnapshot} + {@link ListingEmitter}); this stays as the
     * readable mark-vocabulary spec + the contract rows' pin. Empty = nothing published this
     * round.
     */
    public synchronized List<String> listingLines(int seatId) {
        List<Target> table = tableFor(seatId);
        List<String> lines = new ArrayList<String>(table.size());
        for (Target target : table) {
            String mark = switch (target.status()) {
                case AI -> "(free)";
                case PARKED -> "(abandoned)";
                case GRACE -> "(reserved: " + target.occupantOrSeatLabel() + ")";
                case OCCUPIED -> target.occupantSeatId() == seatId
                        ? "(you)" : ("(taken: " + target.occupantOrSeatLabel() + ")");
            };
            lines.add(target.token() + " " + mark);
        }
        return lines;
    }

    /**
     * Engine ship names are the one census input the server did not author: keep printable
     * ASCII only, bound to {@value #LABEL_MAX_CHARS} chars; an empty survivor falls back to
     * {@code "hull <wireId>"} so every row stays resolvable and every reply line lawful.
     */
    private static String sanitizeLabel(String raw, long hullWireId) {
        Objects.requireNonNull(raw, "label");
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                kept.append(c);
            }
        }
        String trimmed = kept.toString().trim();
        if (trimmed.isEmpty()) {
            return "hull " + hullWireId;
        }
        return bound(trimmed, LABEL_MAX_CHARS);
    }

    /** The label's last word folded to {@code [a-z0-9]}; empty survivor → {@code "ship"}. */
    private static String stemOf(String label) {
        String folded = ChatCommands.asciiLower(label);
        int lastSpace = folded.lastIndexOf(' ');
        String word = lastSpace < 0 ? folded : folded.substring(lastSpace + 1);
        StringBuilder stem = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                stem.append(c);
            }
        }
        return stem.length() == 0 ? "ship" : bound(stem.toString(), LABEL_MAX_CHARS);
    }

    private static String bound(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
