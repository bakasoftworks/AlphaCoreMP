package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-based {@code k=v} text codec for {@link MatchDescriptor} — the port-file
 * family format the mod already reads in-game. First line is exactly {@value #VERSION_LINE};
 * encode→decode→re-encode is byte-identical (canonical key order, {@code Float.toString} floats,
 * values verbatim — never trimmed).
 *
 * <p><b>String ops only, by design:</b> this class is bundled into the client mod jar and runs
 * under Starsector's script classloader B, which blocklists {@code Data*}/{@code ByteArray*}
 * stream classes. Decode takes pre-read lines (the caller owns the allowlisted
 * {@code FileReader}); encode returns a {@code String} the server-side mint writes to disk.
 *
 * <p>Fail-closed: a wrong/missing version line, a non-{@code k=v} line, a duplicate key, a
 * missing required key, an unknown leftover key, an out-of-range count, or any unparseable
 * value THROWS — never a silent default. Blank lines are skipped (trailing-newline tolerance).
 * Every decode refusal is a {@link MatchDocumentRefused} that names its reason token plus OUR
 * field literal, the 1-based input line index, or a count — never the bytes it read (a document
 * can come from a hostile host, and a refusal text reaches windows and logs); no parse exception
 * is chained as a cause (their messages quote the input).
 *
 *
 */
public final class MatchDescriptorCodec {

    /** The required first line of every v1 descriptor file. */
    public static final String VERSION_LINE = "ccmatch.v=1";

    /**
     * The bound on the SUMMED {@code roster.*.count} (every roster row expands into that many ships in
     * the fleet expander), thrown at the FIRST row that crosses it - so a hostile document cannot make
     * the expander mint more than this many ships. The shared laws' ships cap: one number for the
     * codec and {@link MatchDocumentLaws#check}.
     */
    static final int MAX_TOTAL_SHIPS = MatchDocumentLaws.SHIPS_MAX;

    private MatchDescriptorCodec() {
    }

    /** Encode in canonical order; the exact bytes (with a trailing newline) the mint writes. */
    public static String encode(MatchDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(VERSION_LINE).append('\n');
        sb.append("match.id=").append(descriptor.matchId()).append('\n');
        sb.append("match.shape=").append(descriptor.shape()).append('\n');
        sb.append("map.halfWidth=").append(Float.toString(descriptor.halfWidth())).append('\n');
        sb.append("map.halfHeight=").append(Float.toString(descriptor.halfHeight())).append('\n');
        // The backdrop word rides ONLY when set — a `none` descriptor
        // encodes byte-identically to every descriptor from before the backdrop word (old kits, goldens, redacts).
        if (!MatchDescriptor.TERRAIN_NONE.equals(descriptor.terrain())) {
            sb.append("map.terrain=").append(descriptor.terrain()).append('\n');
        }
        sb.append("rules.deployCr=").append(Float.toString(descriptor.deployCr())).append('\n');
        sb.append("rules.endCondition=").append(descriptor.endCondition()).append('\n');
        sb.append("side.count=").append(descriptor.sides().size()).append('\n');
        for (int i = 0; i < descriptor.sides().size(); i++) {
            MatchDescriptor.Side side = descriptor.sides().get(i);
            sb.append("side.").append(i).append(".name=").append(side.name()).append('\n');
            sb.append("side.").append(i).append(".tagline=").append(side.tagline()).append('\n');
            sb.append("side.").append(i).append(".goal=").append(side.goal()).append('\n');
        }
        sb.append("roster.count=").append(descriptor.roster().size()).append('\n');
        for (int i = 0; i < descriptor.roster().size(); i++) {
            MatchDescriptor.RosterRow row = descriptor.roster().get(i);
            sb.append("roster.").append(i).append(".side=").append(row.side()).append('\n');
            sb.append("roster.").append(i).append(".variant=").append(row.variantId()).append('\n');
            sb.append("roster.").append(i).append(".count=").append(row.count()).append('\n');
            sb.append("roster.").append(i).append(".pilot=").append(row.pilot().name()).append('\n');
        }
        sb.append("seat.count=").append(descriptor.seats().size()).append('\n');
        for (int i = 0; i < descriptor.seats().size(); i++) {
            MatchDescriptor.Seat seat = descriptor.seats().get(i);
            sb.append("seat.").append(i).append(".id=").append(seat.seatId()).append('\n');
            sb.append("seat.").append(i).append(".role=").append(seat.role().name()).append('\n');
            sb.append("seat.").append(i).append(".side=").append(seat.side()).append('\n');
            sb.append("seat.").append(i).append(".ship=").append(seat.shipId()).append('\n');
            sb.append("seat.").append(i).append(".token=").append(seat.seatToken()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Decode pre-read lines (the caller owns the file read) into a validated descriptor. Throws
     * ONLY {@link MatchDocumentRefused} (an {@link IllegalArgumentException}): a wrong first line or
     * no version line = malformed {@code line 1}; a non-{@code k=v} line or a duplicate key =
     * malformed {@code line <n>} (n = the 1-based index in {@code lines}, never the key); a missing
     * key or an unparseable value = malformed {@code <key>} (our literal); leftover keys = malformed
     * {@code unknown-keys:<count>}; a count, an enum word or a model value outside its law = out of
     * range at our field.
     */
    public static MatchDescriptor decode(List<String> lines) {
        if (lines == null) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "line 1");
        }
        Map<String, String> kv = new HashMap<String, String>();
        boolean versionSeen = false;
        int n = 0;
        for (String line : lines) {
            n++;
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (!versionSeen) {
                if (!VERSION_LINE.equals(line)) {
                    throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "line 1");
                }
                versionSeen = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "line " + n);
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (kv.put(key, value) != null) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "line " + n);
            }
        }
        if (!versionSeen) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "line 1");
        }

        String matchId = take(kv, "match.id");
        String shape = take(kv, "match.shape");
        float halfWidth = parseFloat("map.halfWidth", take(kv, "map.halfWidth"));
        float halfHeight = parseFloat("map.halfHeight", take(kv, "map.halfHeight"));
        // OPTIONAL key; absent = none. The value law (exact table) is the
        // descriptor constructor's — an off-table word throws there, same as any bad row.
        String terrain = kv.remove("map.terrain");
        if (terrain == null) {
            terrain = MatchDescriptor.TERRAIN_NONE;
        }
        float deployCr = parseFloat("rules.deployCr", take(kv, "rules.deployCr"));
        String endCondition = take(kv, "rules.endCondition");

        int sideCount = parseInt("side.count", take(kv, "side.count"));
        if (sideCount != 2) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "side.count");
        }
        List<MatchDescriptor.Side> sides = new ArrayList<MatchDescriptor.Side>(sideCount);
        for (int i = 0; i < sideCount; i++) {
            String name = take(kv, "side." + i + ".name");
            String tagline = take(kv, "side." + i + ".tagline");
            String goal = take(kv, "side." + i + ".goal");
            try {
                sides.add(new MatchDescriptor.Side(name, tagline, goal));
            } catch (MatchDocumentRefused refused) {
                throw refused;
            } catch (IllegalArgumentException modelRefused) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "side." + i);
            }
        }

        // Rows 1..ROWS_MAX and seats 1..SEATS_MAX (the shared laws' list caps) bound what a decode
        // allocates; each roster row's count is 1..SHIPS_MAX and the running SUM is bounded too
        // (thrown at the FIRST row that crosses = bounded work), so a hostile document cannot make
        // the fleet expander mint more than SHIPS_MAX ships; RosterRow keeps >= 1.
        int rosterCount = parseCount("roster.count", take(kv, "roster.count"), MatchDocumentLaws.ROWS_MAX);
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>(rosterCount);
        long rosterTotal = 0L;
        for (int i = 0; i < rosterCount; i++) {
            String countKey = "roster." + i + ".count";
            int rowCount = parseCount(countKey, take(kv, countKey), MAX_TOTAL_SHIPS);
            rosterTotal += rowCount;
            if (rosterTotal > MAX_TOTAL_SHIPS) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, countKey);
            }
            int side = parseInt("roster." + i + ".side", take(kv, "roster." + i + ".side"));
            String variant = take(kv, "roster." + i + ".variant");
            String pilotKey = "roster." + i + ".pilot";
            String pilotWord = take(kv, pilotKey);
            MatchDescriptor.PilotKind pilot;
            try {
                pilot = MatchDescriptor.PilotKind.valueOf(pilotWord);
            } catch (IllegalArgumentException offTable) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, pilotKey);
            }
            try {
                roster.add(new MatchDescriptor.RosterRow(side, variant, rowCount, pilot));
            } catch (MatchDocumentRefused refused) {
                throw refused;
            } catch (IllegalArgumentException modelRefused) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "roster." + i);
            }
        }

        int seatCount = parseCount("seat.count", take(kv, "seat.count"), MatchDocumentLaws.SEATS_MAX);
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>(seatCount);
        for (int i = 0; i < seatCount; i++) {
            int seatId = parseInt("seat." + i + ".id", take(kv, "seat." + i + ".id"));
            String roleKey = "seat." + i + ".role";
            String roleWord = take(kv, roleKey);
            SeatRole role;
            try {
                role = SeatRole.valueOf(roleWord);
            } catch (IllegalArgumentException offTable) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, roleKey);
            }
            int side = parseInt("seat." + i + ".side", take(kv, "seat." + i + ".side"));
            long ship = parseLong("seat." + i + ".ship", take(kv, "seat." + i + ".ship"));
            String token = take(kv, "seat." + i + ".token");
            try {
                seats.add(new MatchDescriptor.Seat(seatId, role, side, ship, token));
            } catch (MatchDocumentRefused refused) {
                throw refused;
            } catch (IllegalArgumentException modelRefused) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "seat." + i);
            }
        }

        if (!kv.isEmpty()) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, "unknown-keys:" + kv.size());
        }
        try {
            return new MatchDescriptor(MatchDescriptor.VERSION, matchId, shape, halfWidth, halfHeight,
                    deployCr, endCondition, sides, roster, seats, terrain);
        } catch (MatchDocumentRefused refused) {
            throw refused;
        } catch (IllegalArgumentException modelRefused) {
            // The model's own law refused a parsed value (a blank / non-ASCII id, a half <= 0, a CR
            // outside (0, 1], a duplicate seat id or token): named by scope, its message (which may
            // quote a value) dropped.
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "descriptor");
        }
    }

    private static String take(Map<String, String> kv, String key) {
        String value = kv.remove(key);
        if (value == null) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, key);
        }
        return value;
    }

    private static float parseFloat(String key, String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, key);
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, key);
        }
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, key);
        }
    }

    /** An int in {@code 1..max}; a non-int = malformed, outside the range = out of range, both at {@code key}. */
    private static int parseCount(String key, String value, int max) {
        int count = parseInt(key, value);
        if (count < 1 || count > max) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, key);
        }
        return count;
    }
}
