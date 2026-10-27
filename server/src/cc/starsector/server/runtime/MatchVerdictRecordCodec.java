package cc.starsector.server.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * line-based {@code k=v} text codec + disk writer for the per-match
 * {@link MatchTerminalVerdict} record ({@value #FILE_SUFFIX}). The "verdict's record line" the
 * verifiable-matches gap table named (gap #7): on match end
 * the terminal verdict is persisted to disk — until now {@code CombatOutcomeEvent} was emitted
 * on-wire + held in memory and NEVER written to a per-match artifact.
 *
 * <p>The record bundles the verdict (kind/winner/ticks) WITH the {@link MatchRecordMeta} re-sim
 * identity stamps (rng.seed, modset.hash, content.fingerprint, engine.build, git.head, codec schema
 * versions), so the {@value #FILE_SUFFIX} file is self-contained re-sim evidence — the same identity
 * the {@code .ccwr} meta already carries, now bound to the OUTCOME of the match those
 * frames recorded.
 *
 * <p><b>Format</b> (mirrors {@link cc.starsector.server.match.MatchDescriptorCodec}): first line is
 * exactly {@value #VERSION_LINE}; then the five {@code verdict.*} keys in canonical order; then
 * {@code meta.count=N}; then {@code N} {@code meta.<key>=<value>} lines in the supplied map's
 * iteration order (a {@link LinkedHashMap} from {@link MatchRecordMeta#base()}). Encode→decode→
 * re-encode is byte-identical. Fail-closed: a wrong/missing version line, a non-{@code k=v} line, a
 * duplicate key, a missing required key, an unknown leftover key, a wrong {@code meta.count}, or any
 * unparseable value THROWS (and the {@link MatchTerminalVerdict} canonical constructor re-validates
 * the kind/winner invariant). Blank lines are skipped (trailing-newline tolerance).
 */
public final class MatchVerdictRecordCodec {

    /** The required first line of every v1 verdict record file. */
    public static final String VERSION_LINE = "ccverdict.v=1";
    /** Conventional file suffix ("CC Verdict Record"). */
    public static final String FILE_SUFFIX = ".ccvr";
    private static final String META_PREFIX = "meta.";
    /** Sanity cap on the meta map size a decode will accept (fail-closed, far above any real map). */
    private static final int MAX_META_COUNT = 4096;

    private MatchVerdictRecordCodec() {
    }

    /** Encode in canonical order; the exact bytes (trailing newline) {@link #writeRecord} writes. */
    public static String encode(MatchTerminalVerdict verdict, Map<String, String> identityMeta) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(identityMeta, "identityMeta");
        StringBuilder sb = new StringBuilder(512);
        sb.append(VERSION_LINE).append('\n');
        sb.append("verdict.kind=").append(verdict.kind().name()).append('\n');
        sb.append("verdict.winningSide=").append(verdict.winningSideId()).append('\n');
        sb.append("verdict.terminalTick=").append(verdict.terminalTick()).append('\n');
        sb.append("verdict.ticksAdvanced=").append(verdict.ticksAdvanced()).append('\n');
        sb.append("verdict.stopReason=").append(verdict.stopReason()).append('\n');
        sb.append("meta.count=").append(identityMeta.size()).append('\n');
        for (Map.Entry<String, String> e : identityMeta.entrySet()) {
            sb.append(META_PREFIX).append(e.getKey()).append('=')
                    .append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** The decoded verdict plus its re-sim identity meta (decode preserves write order). */
    public static final class Parsed {
        public final MatchTerminalVerdict verdict;
        public final LinkedHashMap<String, String> meta;

        Parsed(MatchTerminalVerdict verdict, LinkedHashMap<String, String> meta) {
            this.verdict = verdict;
            this.meta = meta;
        }
    }

    /** Decode pre-read lines (caller owns the file read) into a validated verdict + meta. */
    public static Parsed decode(List<String> lines) {
        if (lines == null) {
            throw new IllegalArgumentException("lines must not be null");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        LinkedHashMap<String, String> meta = new LinkedHashMap<>();
        boolean versionSeen = false;
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (!versionSeen) {
                if (!VERSION_LINE.equals(line)) {
                    throw new IllegalArgumentException(
                            "first line must be '" + VERSION_LINE + "', got: '" + line + "'");
                }
                versionSeen = true;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not a k=v line: '" + line + "'");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (key.startsWith(META_PREFIX) && !key.equals("meta.count")) {
                if (meta.put(key.substring(META_PREFIX.length()), value) != null) {
                    throw new IllegalArgumentException("duplicate meta key: " + key);
                }
            } else if (fields.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
        }
        if (!versionSeen) {
            throw new IllegalArgumentException("empty verdict record (no '" + VERSION_LINE + "' line)");
        }

        MatchTerminalVerdict.Kind kind;
        try {
            kind = MatchTerminalVerdict.Kind.valueOf(take(fields, "verdict.kind"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("verdict.kind not a known terminal: " + e.getMessage());
        }
        int winningSide = parseInt("verdict.winningSide", take(fields, "verdict.winningSide"));
        long terminalTick = parseLong("verdict.terminalTick", take(fields, "verdict.terminalTick"));
        int ticksAdvanced = parseInt("verdict.ticksAdvanced", take(fields, "verdict.ticksAdvanced"));
        String stopReason = take(fields, "verdict.stopReason");

        int metaCount = parseInt("meta.count", take(fields, "meta.count"));
        if (metaCount < 0 || metaCount > MAX_META_COUNT) {
            throw new IllegalArgumentException("meta.count must be in [0, " + MAX_META_COUNT + "], got: " + metaCount);
        }
        if (metaCount != meta.size()) {
            throw new IllegalArgumentException(
                    "meta.count " + metaCount + " != actual meta entries " + meta.size());
        }
        if (!fields.isEmpty()) {
            throw new IllegalArgumentException("unknown verdict-record keys: " + fields.keySet());
        }
        // The canonical constructor re-validates the kind/winner invariant (fail-closed).
        return new Parsed(
                new MatchTerminalVerdict(kind, winningSide, terminalTick, ticksAdvanced, stopReason),
                meta);
    }

    /**
     * Persist the verdict + identity meta to {@code path} (parents created). This is the match-end
     * "record line" the gap table asked for: server-side disk write, no wire/codec change.
     */
    public static Path writeRecord(Path path, MatchTerminalVerdict verdict, Map<String, String> identityMeta)
            throws IOException {
        Objects.requireNonNull(path, "path");
        String text = encode(verdict, identityMeta);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, text);
        return path;
    }

    private static String take(Map<String, String> fields, String key) {
        String value = fields.remove(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required key: " + key);
        }
        return value;
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " not an int: '" + value + "'");
        }
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " not a long: '" + value + "'");
        }
    }
}
