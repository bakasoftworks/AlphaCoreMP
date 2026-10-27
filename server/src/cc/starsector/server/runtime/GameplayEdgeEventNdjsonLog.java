package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import cc.starsector.server.protocol.GameplayEdgeEventPhase;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The B1 NDJSON event log: one
 * line per {@link GameplayEdgeEvent}, written so that equal event streams produce
 * <strong>byte-identical files</strong> — fixed key order, every field always
 * present, {@code \n} separators regardless of platform, UTF-8, no locale-dependent
 * formatting ({@code Float.toString} is deterministic for identical float bits;
 * an unavailable {@code aimFacingDeg} ({@code NaN}) is written as JSON {@code null},
 * never a faked number).
 *
 * <p>Line shape (v9 — {@code targetWireId} appended, written
 * {@code null} for the canonical {@code TARGET_ABSENT} sentinel — the {@code NaN} law's
 * long twin; v8 appended {@code muzzleX}/{@code muzzleY};
 * v6 appended {@code birthSpeed};
 * v2 appended {@code flightBearingDeg};
 * legacy lines missing any tail field(s) still {@link #parseLine parse}, the absent
 * field(s) {@code NaN}/{@code TARGET_ABSENT}):
 * <pre>{"simTick":12,"entityId":3,"kind":"VENT_WINDOW","phase":"START","mountIndex":-1,"aimFacingDeg":null,"flightBearingDeg":null,"birthSpeed":null,"muzzleX":null,"muzzleY":null,"targetWireId":null}</pre>
 */
public final class GameplayEdgeEventNdjsonLog implements AutoCloseable {

    private final BufferedWriter writer;

    private GameplayEdgeEventNdjsonLog(BufferedWriter writer) {
        this.writer = writer;
    }

    /** Open (create/truncate) the log at {@code path} for per-tick appends. */
    public static GameplayEdgeEventNdjsonLog createAt(Path path) throws IOException {
        return new GameplayEdgeEventNdjsonLog(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    /** The canonical single-line form of one event (no trailing newline). */
    public static String format(GameplayEdgeEvent event) {
        StringBuilder line = new StringBuilder(176);
        appendMuzzle(line, event).append(",\"targetWireId\":");
        if (event.targetWireId() == GameplayEdgeEvent.TARGET_ABSENT) {
            line.append("null");
        } else {
            line.append(event.targetWireId());
        }
        return line.append('}').toString();
    }

    /** The v8 line shape (muzzle pair, no {@code targetWireId}) — only for legacy-line canonicality. */
    private static String formatLegacyNoTarget(GameplayEdgeEvent event) {
        return appendMuzzle(new StringBuilder(160), event).append('}').toString();
    }

    /** The pre-v2 line shape (no {@code flightBearingDeg}) — only for legacy-line canonicality. */
    private static String formatLegacy(GameplayEdgeEvent event) {
        return appendPrefix(new StringBuilder(96), event).append('}').toString();
    }

    /** The v2..v5 line shape (bearing, no {@code birthSpeed}) — only for legacy-line canonicality. */
    private static String formatLegacyNoSpeed(GameplayEdgeEvent event) {
        return appendBearing(new StringBuilder(112), event).append('}').toString();
    }

    /** The v6..v7 line shape (speed, no muzzle pair) — only for legacy-line canonicality. */
    private static String formatLegacyNoMuzzle(GameplayEdgeEvent event) {
        return appendSpeed(new StringBuilder(128), event).append('}').toString();
    }

    private static StringBuilder appendBearing(StringBuilder line, GameplayEdgeEvent event) {
        appendPrefix(line, event).append(",\"flightBearingDeg\":");
        appendFloatOrNull(line, event.flightBearingDeg());
        return line;
    }

    private static StringBuilder appendSpeed(StringBuilder line, GameplayEdgeEvent event) {
        appendBearing(line, event).append(",\"birthSpeed\":");
        appendFloatOrNull(line, event.birthSpeed());
        return line;
    }

    private static StringBuilder appendMuzzle(StringBuilder line, GameplayEdgeEvent event) {
        appendSpeed(line, event).append(",\"muzzleX\":");
        appendFloatOrNull(line, event.muzzleX());
        line.append(",\"muzzleY\":");
        appendFloatOrNull(line, event.muzzleY());
        return line;
    }

    private static StringBuilder appendPrefix(StringBuilder line, GameplayEdgeEvent event) {
        line.append("{\"simTick\":").append(event.simTick())
                .append(",\"entityId\":").append(event.entityId())
                .append(",\"kind\":\"").append(event.kind().name())
                .append("\",\"phase\":\"").append(event.phase().name())
                .append("\",\"mountIndex\":").append(event.mountIndex())
                .append(",\"aimFacingDeg\":");
        appendFloatOrNull(line, event.aimFacingDeg());
        return line;
    }

    private static void appendFloatOrNull(StringBuilder line, float value) {
        if (Float.isNaN(value)) {
            line.append("null");
        } else {
            line.append(Float.toString(value));
        }
    }

    /** Append one event as one line. */
    public void append(GameplayEdgeEvent event) {
        try {
            writer.write(format(event));
            writer.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Append every event in order. */
    public void appendAll(List<GameplayEdgeEvent> events) {
        for (GameplayEdgeEvent event : events) {
            append(event);
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /** Write a complete event list to {@code path} in one call (create/truncate). */
    public static void writeAll(Path path, List<GameplayEdgeEvent> events) throws IOException {
        try (GameplayEdgeEventNdjsonLog log = createAt(path)) {
            log.appendAll(events);
        }
    }

    /**
     * Parse one canonical event line back into its event (lets the
     * wire publisher consume a recorded B1 log). Fail-closed by round-trip: the
     * parsed event must {@link #format} back to exactly the input line, so any
     * non-canonical, reordered, or padded line is rejected rather than guessed at.
     */
    public static GameplayEdgeEvent parseLine(String line) throws IOException {
        try {
            long simTick = Long.parseLong(fieldText(line, "\"simTick\":", ','));
            long entityId = Long.parseLong(fieldText(line, "\"entityId\":", ','));
            GameplayEdgeEventKind kind =
                    GameplayEdgeEventKind.valueOf(fieldText(line, "\"kind\":\"", '"'));
            GameplayEdgeEventPhase phase =
                    GameplayEdgeEventPhase.valueOf(fieldText(line, "\"phase\":\"", '"'));
            int mountIndex = Integer.parseInt(fieldText(line, "\"mountIndex\":", ','));
            boolean legacyV1 = !line.contains("\"flightBearingDeg\":");
            boolean noSpeed = !line.contains("\"birthSpeed\":");
            boolean noMuzzle = !line.contains("\"muzzleX\":");
            boolean noTarget = !line.contains("\"targetWireId\":");
            String aimText = fieldText(line, "\"aimFacingDeg\":", legacyV1 ? '}' : ',');
            float aimFacingDeg = aimText.equals("null") ? Float.NaN : Float.parseFloat(aimText);
            float flightBearingDeg = Float.NaN;
            if (!legacyV1) {
                String bearingText = fieldText(line, "\"flightBearingDeg\":", noSpeed ? '}' : ',');
                flightBearingDeg = bearingText.equals("null") ? Float.NaN : Float.parseFloat(bearingText);
            }
            float birthSpeed = Float.NaN;
            if (!noSpeed) {
                String speedText = fieldText(line, "\"birthSpeed\":", noMuzzle ? '}' : ',');
                birthSpeed = speedText.equals("null") ? Float.NaN : Float.parseFloat(speedText);
            }
            float muzzleX = Float.NaN;
            float muzzleY = Float.NaN;
            if (!noMuzzle) {
                String muzzleXText = fieldText(line, "\"muzzleX\":", ',');
                muzzleX = muzzleXText.equals("null") ? Float.NaN : Float.parseFloat(muzzleXText);
                String muzzleYText = fieldText(line, "\"muzzleY\":", noTarget ? '}' : ',');
                muzzleY = muzzleYText.equals("null") ? Float.NaN : Float.parseFloat(muzzleYText);
            }
            long targetWireId = GameplayEdgeEvent.TARGET_ABSENT;
            if (!noTarget) {
                String targetText = fieldText(line, "\"targetWireId\":", '}');
                targetWireId = targetText.equals("null")
                        ? GameplayEdgeEvent.TARGET_ABSENT : Long.parseLong(targetText);
            }
            GameplayEdgeEvent event = new GameplayEdgeEvent(
                    simTick, entityId, kind, phase, mountIndex, aimFacingDeg, flightBearingDeg,
                    birthSpeed, muzzleX, muzzleY, targetWireId);
            String canonical = legacyV1 ? formatLegacy(event)
                    : noSpeed ? formatLegacyNoSpeed(event)
                    : noMuzzle ? formatLegacyNoMuzzle(event)
                    : noTarget ? formatLegacyNoTarget(event) : format(event);
            if (!canonical.equals(line)) {
                throw new IOException("gameplay event line is not canonical: " + line);
            }
            return event;
        } catch (RuntimeException badLine) {
            throw new IOException("gameplay event line unparseable: " + line, badLine);
        }
    }

    /** Read a whole NDJSON event log back ({@link #parseLine} per line, fail-closed). */
    public static List<GameplayEdgeEvent> readAll(Path path) throws IOException {
        List<GameplayEdgeEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            events.add(parseLine(line));
        }
        return List.copyOf(events);
    }

    private static String fieldText(String line, String label, char terminator) throws IOException {
        int start = line.indexOf(label);
        if (start < 0) {
            throw new IOException("gameplay event line missing field " + label + ": " + line);
        }
        start += label.length();
        int end = line.indexOf(terminator, start);
        if (end < 0) {
            throw new IOException("gameplay event line missing terminator for " + label + ": " + line);
        }
        return line.substring(start, end);
    }
}
