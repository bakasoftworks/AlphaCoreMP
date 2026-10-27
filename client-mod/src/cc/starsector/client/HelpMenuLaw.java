package cc.starsector.client;

import java.util.ArrayList;
import java.util.List;

import cc.starsector.client.net.ClientChatMessage;

/**
 * The {@code /help} menu capture law: pure (no engine / LWJGL import —
 * the {@link ListPickerLaw} boundary; {@code HelpMenuLawSelftest} drives it on an injected clock), render-thread
 * only, no synchronization.
 *
 * <p><b>Why</b> (a mid-flight report: "/help needs to open a scrollable menu like eg. /settings, rn it
 * is not readable"): the server answers a bare {@code /help} with one CHTD line per verb (about 11, about 33 for an
 * admin) and the combat feed shows about 8 rows that fade. The reply shape ({@code PidsChatVerbs} HELP arm →
 * {@code ChatCommands.helpLines}, the send loop right below it): author {@value #SERVER_AUTHOR}, stamped with the
 * REQUESTER's own seat id, unicast, sent BOTTOM-UP — so the reading-order header {@value #END_MARK} arrives LAST and is
 * the end mark. This law holds those lines out of the feed so the chat lane can open them in the
 * {@link ListPickerLaw#KIND_LOCAL_HELP} picker:
 * <ul>
 *   <li>{@link #begin} arms a capture window. {@link #CAPTURE_TIMEOUT_NANOS} is a FALLBACK bound only: the normal path
 *       closes at the end mark, one round trip after the send, so no wait is added.</li>
 *   <li>{@link #offer} holds a line authored exactly {@value #SERVER_AUTHOR} on the own seat given to {@code begin}
 *       (any seat while that is unknown, -1), at most {@link #CAPTURE_MAX_LINES} lines plus the end mark. Every
 *       other line PASSES untouched, in order.</li>
 *   <li>{@link #poll} owns the clock: READY (reading order = the server lines reversed, then the client's own lines),
 *       TIMEOUT, or FLUSH (a re-typed {@code /help} or a {@link #reset}). Lines bound for the feed come back in the
 *       order the pre-menu code put them there: the client block bottom-up, then the server lines in arrival order.</li>
 * </ul>
 * Nothing is ever dropped: a capture that cannot become a menu goes to the feed.
 *
 * <p><b>Text law:</b> a held line is WIRE text already under the chat law ({@code ChatWriter.validateChatText} ran in
 * the {@link ClientChatMessage} ctor: single-line printable, at most 120 chars). It is never logged, parsed or used
 * as a command here; {@link #wrap} only splits it for display. Only the server stamps the requester's seat on a
 * line (a peer's fanned line carries the PEER's seat), so a player named {@value #SERVER_AUTHOR} can reach the
 * capture only while the own seat is unknown, and then at most adds a line to that player's help menu.
 */
public final class HelpMenuLaw {

    /** The fallback bound of a capture window (2 s, monotonic nanos). */
    public static final long CAPTURE_TIMEOUT_NANOS = 2_000_000_000L;
    /** Server lines one capture holds before the end mark; the 65th and later PASS to the feed. */
    public static final int CAPTURE_MAX_LINES = 64;
    /** The author the server stamps on every slash-command reply ({@code PidsChatVerbs}: {@code encodeDown(seatId, "server", …)}). */
    public static final String SERVER_AUTHOR = "server";
    /** The reading-order FIRST help line, sent LAST ({@code ChatCommands.helpLines}: {@code lines.add("commands:")}). */
    public static final String END_MARK = "commands:";
    /** The indent of a wrapped continuation row. */
    public static final String CONTINUATION_INDENT = "  ";

    /** What {@link #offer} did with one line. */
    public enum Offer { PASS, CAPTURED }

    /** What {@link #poll} found. */
    public enum Kind { NONE, READY, TIMEOUT, FLUSH }

    /** One {@link #poll} result: {@link #feed} goes to the feed FIRST; on READY, {@link #lines} open the menu and
     *  {@link #fallback} goes to the feed only when the menu could not open. */
    public static final class Outcome {
        private final Kind kind;
        private final List<String> lines;
        private final List<ClientChatMessage> feed;
        private final List<ClientChatMessage> fallback;
        private final int serverLines;

        Outcome(Kind kind, List<String> lines, List<ClientChatMessage> feed, List<ClientChatMessage> fallback,
                int serverLines) {
            this.kind = kind;
            this.lines = lines;
            this.feed = feed;
            this.fallback = fallback;
            this.serverLines = serverLines;
        }

        public Kind kind() {
            return kind;
        }

        /** READY: the menu lines in reading order (unwrapped); empty otherwise. */
        public List<String> lines() {
            return lines;
        }

        /** Lines to put into the feed now, oldest first (a flush and / or a timed-out capture). */
        public List<ClientChatMessage> feed() {
            return feed;
        }

        /** READY: the same capture in feed order, for a menu that could not open; empty otherwise. */
        public List<ClientChatMessage> fallback() {
            return fallback;
        }

        /** READY: how many of {@link #lines} came from the server (the rest are the client's). */
        public int serverLines() {
            return serverLines;
        }
    }

    private static final Outcome NONE = new Outcome(Kind.NONE, List.of(), List.of(), List.of(), 0);

    private boolean armed;
    private long armedAtNanos;
    private int ownSeatId = -1;
    private List<String> clientLines = List.of();
    private List<ClientChatMessage> clientMessages = List.of();
    private final ArrayList<ClientChatMessage> held = new ArrayList<>();
    /** A capture closed by its end mark, waiting for the next {@link #poll} (arrival order); null = none. */
    private List<ClientChatMessage> readyServer;
    private List<String> readyClientLines = List.of();
    private List<ClientChatMessage> readyClientMessages = List.of();
    /** Lines a re-typed {@code /help} or a {@link #reset} flushed, emitted by the next {@link #poll}. */
    private final ArrayList<ClientChatMessage> pendingFeed = new ArrayList<>();

    /** True while a capture window is open. */
    public boolean armed() {
        return armed;
    }

    /**
     * A bare {@code /help} was sent: arm a capture window. A window already open flushes its held server lines to
     * the feed first (nothing dropped) and restarts; a closed capture still waiting for {@link #poll} is kept.
     *
     * @param nowNanos    monotonic clock ({@code System.nanoTime()}), the same clock {@link #poll} gets
     * @param ownSeatId   the seat the server stamps on this client's replies when known ({@code >= 0} matches
     *                    exactly; the session's own-hull seat is {@code >= 1}), else -1 = unknown (author-only match)
     * @param clientLines the client's own help lines in reading order ({@code ClientHelpLines.lines}); null = none
     * @throws IllegalArgumentException a client line outside the chat law (the {@link ClientChatMessage} ctor)
     */
    public void begin(long nowNanos, int ownSeatId, List<String> clientLines) {
        List<String> lines = clientLines == null ? List.of() : List.copyOf(clientLines);
        List<ClientChatMessage> messages = new ArrayList<>(lines.size());
        for (String line : lines) {
            messages.add(new ClientChatMessage(0, ClientHelpLines.AUTHOR, line));
        }
        if (armed) {
            pendingFeed.addAll(held);
        }
        held.clear();
        armed = true;
        armedAtNanos = nowNanos;
        this.ownSeatId = ownSeatId;
        this.clientLines = lines;
        this.clientMessages = List.copyOf(messages);
    }

    /** One drained chat line: CAPTURED (hold it, keep it off the feed and the bubbles) or PASS (today's path). No
     *  clock here: {@link #poll} runs first in every drain and closes an expired window before any line is offered. */
    public Offer offer(ClientChatMessage chat) {
        if (!armed || chat == null || !SERVER_AUTHOR.equals(chat.name())
                || (ownSeatId >= 0 && chat.seatId() != ownSeatId)) {
            return Offer.PASS;
        }
        boolean endMark = END_MARK.equals(chat.text());
        if (!endMark && held.size() >= CAPTURE_MAX_LINES) {
            return Offer.PASS;
        }
        held.add(chat);
        if (endMark) {
            readyServer = List.copyOf(held);
            readyClientLines = clientLines;
            readyClientMessages = clientMessages;
            held.clear();
            armed = false;
        }
        return Offer.CAPTURED;
    }

    /** Once per drain, BEFORE the queued lines are offered (a flushed capture is older than every queued line). */
    public Outcome poll(long nowNanos) {
        boolean expired = armed && nowNanos - armedAtNanos > CAPTURE_TIMEOUT_NANOS;
        if (pendingFeed.isEmpty() && readyServer == null && !expired) {
            return NONE;
        }
        List<ClientChatMessage> feed = new ArrayList<>(pendingFeed);
        pendingFeed.clear();
        if (readyServer != null) {
            // A timed-out window (if any) waits for the next poll: the ready capture's lines arrived first.
            List<String> lines = readingOrder(readyServer, readyClientLines);
            List<ClientChatMessage> fallback = feedOrder(readyClientMessages, readyServer);
            int serverLines = readyServer.size();
            readyServer = null;
            return new Outcome(Kind.READY, lines, List.copyOf(feed), fallback, serverLines);
        }
        Kind kind = Kind.FLUSH;
        if (expired) {
            feed.addAll(feedOrder(clientMessages, held));
            held.clear();
            armed = false;
            kind = Kind.TIMEOUT;
        }
        return new Outcome(kind, List.of(), List.copyOf(feed), List.of(), 0);
    }

    /** A match reset or a session change: disarm; every held line (a waiting closed capture too) goes to the feed
     *  at the next {@link #poll}. A capture never survives either. */
    public void reset() {
        if (readyServer != null) {
            pendingFeed.addAll(feedOrder(readyClientMessages, readyServer));
            readyServer = null;
        }
        if (armed) {
            pendingFeed.addAll(feedOrder(clientMessages, held));
            held.clear();
            armed = false;
        }
    }

    private static List<String> readingOrder(List<ClientChatMessage> server, List<String> client) {
        List<String> out = new ArrayList<>(server.size() + client.size());
        for (int i = server.size() - 1; i >= 0; i--) {
            out.add(server.get(i).text());
        }
        out.addAll(client);
        return List.copyOf(out);
    }

    /** The pre-menu feed order: the client block bottom-up (it was deposited at send), then the server lines as they
     *  arrived (already bottom-up), so the newest-on-top feed reads top-down. */
    private static List<ClientChatMessage> feedOrder(List<ClientChatMessage> client, List<ClientChatMessage> server) {
        List<ClientChatMessage> out = new ArrayList<>(client.size() + server.size());
        for (int i = client.size() - 1; i >= 0; i--) {
            out.add(client.get(i));
        }
        out.addAll(server);
        return List.copyOf(out);
    }

    /**
     * Split one line into display rows of at most {@code maxChars} (clamped to at least 4): breaks at the last space
     * that fits, a continuation row is indented {@value #CONTINUATION_INDENT}, a single token longer than a row is
     * hard-split (never between a surrogate pair); leading spaces are dropped. Null, empty or all-space = no rows.
     * So nothing is cut by the picker's visual clamp and its draw code needs no change.
     */
    public static List<String> wrap(String line, int maxChars) {
        String rest = line == null ? "" : stripLeadingSpaces(line);
        if (rest.isEmpty()) {
            return List.of();
        }
        int max = Math.max(CONTINUATION_INDENT.length() + 2, maxChars);
        List<String> rows = new ArrayList<>(2);
        boolean first = true;
        while (!rest.isEmpty()) {
            String prefix = first ? "" : CONTINUATION_INDENT;
            int room = max - prefix.length();
            if (rest.length() <= room) {
                rows.add(prefix + rest);
                break;
            }
            int cut = rest.lastIndexOf(' ', room);
            String chunk;
            if (cut > 0) {
                chunk = stripTrailingSpaces(rest.substring(0, cut));
                rest = rest.substring(cut + 1);
            } else {
                cut = Character.isHighSurrogate(rest.charAt(room - 1)) ? room - 1 : room;
                chunk = rest.substring(0, cut);
                rest = rest.substring(cut);
            }
            rows.add(prefix + chunk);
            rest = stripLeadingSpaces(rest);
            first = false;
        }
        return rows;
    }

    /** {@link #wrap} over every line, in order. */
    public static List<String> wrapAll(List<String> lines, int maxChars) {
        List<String> rows = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                rows.addAll(wrap(line, maxChars));
            }
        }
        return rows;
    }

    private static String stripLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
