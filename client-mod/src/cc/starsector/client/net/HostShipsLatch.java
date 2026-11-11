package cc.starsector.client.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ship editor's host card reads the SERVER's answers to its chat admin verbs ({@code /shiplist},
 * {@code /shipkeep}, {@code /shipremove}, {@code /shiprestore}) and to its in-editor {@code /auth} at the ONE
 * receive choke ({@code PredictReconcileDatagramSession} CHAT routing, net thread). The sibling of
 * {@link ShipReplyLatch}, but SLOTTED: a single any-head waiter lets a re-arm drop a pending keep and lets a late
 * {@code shipkeep A: kept as ...} pose as the verdict of a later {@code /shipremove B}, so every in-flight verb owns
 * a slot keyed on its EXACT receipt prefix {@code "<verb> <id>: "}; the list owns one LIST slot (head
 * {@code "shiplist "}, collected until {@link #LIST_END}); the sign-in owns one AUTH slot (the door's exact reply
 * constants).
 *
 * <p>Laws: only the author {@code server} on the slot's OWN seat id is read (the server stamps a receipt with the
 * caller's seat; a peer who names itself "server" speaks on its own seat, and an unknown own seat, -1, takes
 * nothing: so the editor neither arms a slot nor sends its line before its own seat is known, or the answer would
 * print in the host's chat). {@link #offer} returns {@code true} = CONSUMED for exactly three classes of line: a {@code shiplist } line
 * while the LIST slot is armed (the host's chat is not spammed with the card's list), a {@link #NAMES_HEAD} line
 * with no list slot armed when the whole line is the names shape on the own seat once known (hex-coded machine data
 * no chat line should show), and a {@code "<verb> <id>: "}
 * receipt (its QUEUED line or its final one) that lands on an ARMED verb slot - the editor's own verbs answer on the
 * card, and their heads carry ids no chat line should show. A receipt with no armed slot (a verb the host typed in
 * chat, or a line after its slot's wait ended), a sign-in reply and a terminal line return {@code false}, latched or
 * not, so they still reach the chat feed. The server's three
 * head-less terminal lines (an admin verb from a non-admin seat, the admin-command queue's eviction notice, the
 * operator-fault receipt; {@link #TERMINAL_LINES}) carry no verb prefix, so they settle EVERY open slot on that seat
 * as a refusal. A QUEUED verdict ({@code saving} / {@code removing} / {@code restoring}) is the server's "accepted,
 * working" line: the waiter keeps waiting for the ONE final line the server mails after the work (it answers within
 * 15 s, the "no result" line at worst). Every wait is bounded; the server drains chat only during a running round,
 * so a timeout is a normal answer. Bounded memory: at most {@link #MAX_VERB_SLOTS} verb slots and
 * {@link #MAX_LIST_LINES} list lines. B-clean: java.lang, java.util, the pure chat-law reply constants and the
 * java.lang-only file-id law ({@link LocalVariantIdLaw}).
 * The consumed receipts:
 */
public final class HostShipsLatch {
    /** The server-stamped author every receipt carries. */
    static final String SERVER_AUTHOR = "server";
    /** Every {@code /shiplist} line starts with this head; the LIST slot consumes exactly these lines. */
    public static final String LIST_HEAD = "shiplist ";
    /** The list's last line. */
    public static final String LIST_END = "shiplist end";
    /**
     * The head of the list's names lines ({@code <stem>=<hullId>=<hex of the typed name>} entries): machine data for
     * the editor's card, so {@link #offer} consumes a server line under this head with an armed slot or not (a
     * {@code /shiplist} typed in chat, a reply after its wait ended), but unarmed only when the WHOLE line is the
     * server's names shape ({@link #namesLineWhole}) on the session's own seat once that is known: the head alone is
     * text a player can type into a seat name.
     */
    public static final String NAMES_HEAD = "shiplist names:";
    /**
     * A names entry's hex digits at most: two per char of the typed name, whose cap is the server's 24-char label law
     * (the editor's {@code HostShips.MAX_WIRE_NAME_CHARS}; a row pins the two equal).
     *
     */
    public static final int NAMES_HEX_MAX_DIGITS = 48;
    /** The tails of a QUEUED verdict ({@code "<verb> <id>: saving"} ...): the final line follows later. */
    static final String[] QUEUED_TAILS = {"saving", "removing", "restoring"};
    /**
     * The server's head-less terminal lines (exact text, byte-identical to the server constants: the admin-only
     * refusal, the admin-command queue's eviction receipt, the operator-fault belt's receipt). Drift here only
     * turns a prompt refusal into a timeout (fail-safe).
     */
    static final String[] TERMINAL_LINES = {
            "admin only",
            "admin command dropped (queue full) - retry",
            "that command failed on the server (logged) - the match continues"};
    /** Verb slots alive at once; the card sends ONE verb at a time, so this only bounds a misuse. */
    static final int MAX_VERB_SLOTS = 8;
    /** List lines kept per {@code /shiplist}; past it lines are still consumed but not stored. */
    static final int MAX_LIST_LINES = 512;

    /** The sign-in slot's answer classes. */
    public enum AuthAnswer { SIGNED_IN, REFUSED, HELD }

    /** One awaited verb verdict: {@code line} null = no final line in the window ({@code queued} = a QUEUED line
     *  did land); {@code terminal} = the line is a head-less terminal line, not a {@code <verb> <id>: } receipt. */
    public static final class Verdict {
        public final String line;
        public final boolean terminal;
        public final boolean queued;

        Verdict(String line, boolean terminal, boolean queued) {
            this.line = line;
            this.terminal = terminal;
            this.queued = queued;
        }
    }

    /** One awaited list: the collected lines in arrival order (the end line included when it came), whether the
     *  end line came, and the terminal line that settled it instead (null when none). */
    public static final class ListResult {
        public final List<String> lines;
        public final boolean ended;
        public final String terminal;

        ListResult(List<String> lines, boolean ended, String terminal) {
            this.lines = lines;
            this.ended = ended;
            this.terminal = terminal;
        }
    }

    private static final class VerbSlot {
        final long gen;
        final int seat;
        String finalLine;
        boolean terminal;
        boolean queued;

        VerbSlot(long gen, int seat) {
            this.gen = gen;
            this.seat = seat;
        }
    }

    private static final Object LOCK = new Object();
    private static long generation;
    private static final Map<String, VerbSlot> VERBS = new LinkedHashMap<>();
    private static long listGen;
    private static boolean listArmed;
    private static int listSeat = -1;
    private static List<String> listLines = new ArrayList<>();
    private static boolean listEnded;
    private static String listTerminal;
    private static long authGen;
    private static boolean authArmed;
    private static int authSeat = -1;
    private static AuthAnswer authAnswer;
    /** host_lines_consumed: lines kept OFF the chat feed - {@code shiplist } lines and the receipts of the editor's
     *  own verbs (JVM-cumulative; ChatLane's chat round line prints it). */
    private static long linesConsumed;
    /** host_latch_faults: {@link #offer} throws caught by its belt (the line then flows on to chat; round line). */
    private static long offerFaults;

    private HostShipsLatch() {
    }

    /** Arm the verb slot for {@code prefix} ({@code "<verb> <id>: "}) on {@code ownSeatId}; a re-arm of the same
     *  prefix supersedes the older waiter (it returns a timed-out verdict). Returns the generation to await. */
    public static long armVerb(String prefix, int ownSeatId) {
        synchronized (LOCK) {
            generation++;
            VERBS.remove(prefix);
            if (VERBS.size() >= MAX_VERB_SLOTS) {
                Iterator<String> oldest = VERBS.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            VERBS.put(prefix, new VerbSlot(generation, ownSeatId));
            LOCK.notifyAll(); // a superseded waiter wakes and sees its generation gone
            return generation;
        }
    }

    /**
     * The waiting HTTP thread: the final line for {@code prefix} / {@code gen}. Waits {@code firstWaitMs}; once a
     * QUEUED line landed the window grows to {@code queuedTotalMs} from the call. The slot is gone on return (a
     * late line then reaches only the chat feed).
     */
    public static Verdict awaitVerdict(String prefix, long gen, long firstWaitMs, long queuedTotalMs) {
        long start = System.nanoTime();
        synchronized (LOCK) {
            while (true) {
                VerbSlot slot = VERBS.get(prefix);
                if (slot == null || slot.gen != gen) {
                    return new Verdict(null, false, false);
                }
                if (slot.finalLine != null) {
                    VERBS.remove(prefix);
                    return new Verdict(slot.finalLine, slot.terminal, slot.queued);
                }
                long budget = slot.queued ? queuedTotalMs : firstWaitMs;
                long leftMs = budget - (System.nanoTime() - start) / 1_000_000L;
                if (leftMs <= 0L) {
                    VERBS.remove(prefix);
                    return new Verdict(null, false, slot.queued);
                }
                try {
                    LOCK.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    VERBS.remove(prefix);
                    return new Verdict(null, false, slot.queued);
                }
            }
        }
    }

    /** Drop the verb slot when its line was never sent (a send failure). */
    public static void disarmVerb(String prefix, long gen) {
        synchronized (LOCK) {
            VerbSlot slot = VERBS.get(prefix);
            if (slot != null && slot.gen == gen) {
                VERBS.remove(prefix);
            }
        }
    }

    /** Arm the LIST slot on {@code ownSeatId} (a re-arm supersedes the older waiter). */
    public static long armList(int ownSeatId) {
        synchronized (LOCK) {
            generation++;
            listGen = generation;
            listArmed = true;
            listSeat = ownSeatId;
            listLines = new ArrayList<>();
            listEnded = false;
            listTerminal = null;
            LOCK.notifyAll();
            return listGen;
        }
    }

    /** The waiting HTTP thread: the list for {@code gen} after its end line, a terminal line or {@code timeoutMs};
     *  the slot is disarmed on return (later {@code shiplist } lines reach the chat feed, a names line excepted). */
    public static ListResult awaitList(long gen, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (LOCK) {
            while (listGen == gen && !listEnded && listTerminal == null) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0L) {
                    break;
                }
                try {
                    LOCK.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (listGen != gen) {
                return new ListResult(new ArrayList<>(), false, null);
            }
            listArmed = false;
            ListResult r = new ListResult(listLines, listEnded, listTerminal);
            listLines = new ArrayList<>();
            return r;
        }
    }

    /** Drop the LIST slot when {@code /shiplist} was never sent. */
    public static void disarmList(long gen) {
        synchronized (LOCK) {
            if (listGen == gen) {
                listArmed = false;
            }
        }
    }

    /** Arm the AUTH slot on {@code ownSeatId} (a re-arm supersedes the older waiter). */
    public static long armAuth(int ownSeatId) {
        synchronized (LOCK) {
            generation++;
            authGen = generation;
            authArmed = true;
            authSeat = ownSeatId;
            authAnswer = null;
            LOCK.notifyAll();
            return authGen;
        }
    }

    /** Drop the AUTH slot when nothing was queued. */
    public static void disarmAuth(long gen) {
        synchronized (LOCK) {
            if (authGen == gen) {
                authArmed = false;
            }
        }
    }

    /** The waiting HTTP thread: the sign-in answer for {@code gen}, or null after {@code timeoutMs} / a re-arm. */
    public static AuthAnswer awaitAuth(long gen, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (LOCK) {
            while (authGen == gen && authAnswer == null) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0L) {
                    break;
                }
                try {
                    LOCK.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (authGen != gen) {
                return null;
            }
            authArmed = false;
            AuthAnswer a = authAnswer;
            authAnswer = null;
            return a;
        }
    }

    /**
     * Net thread, the receive choke: one relayed chat line. Returns {@code true} when the line was CONSUMED (a
     * {@code shiplist } line for the armed LIST slot, a server names line with no slot armed - see below - or a
     * receipt that landed on an armed verb slot: the caller
     * passes it to no other reader); every other line returns {@code false}, latched or not. Never throws: a fault
     * is counted and the line flows on.
     *
     * <p>The unarmed names cut: a seat name is player text, and the server's own join line puts it at the HEAD of a
     * server-authored line on the joiner's seat; so a {@link #NAMES_HEAD} head alone never makes a line machine data,
     * and the cut needs the whole names shape ({@link #namesLineWhole}) plus the own seat.
     *
     *
     * <p> {@code ownSeatOrMinusOne} = the receiving session's own seat AS OF THIS LINE
     * (its {@code ownSeatIdOrMinusOne}), or -1 while it knows none - then the shape alone decides. WHY the caller
     * passes it: the server stamps a reply with the asker's CURRENT seat, and the latch's only own knowledge was the
     * seat of its last list arm, which a seat change (a rejoin past the hold, another server in the same game run)
     * left stale - a hand-typed {@code /shiplist} then printed its names lines as hex until the editor's next list
     * read. The armed slots keep the seat they were armed on (armed just before their line is sent).
     */
    public static boolean offer(ClientChatMessage chat, int ownSeatOrMinusOne) {
        try {
            return offerUnbelted(chat, ownSeatOrMinusOne);
        } catch (RuntimeException fault) {
            synchronized (LOCK) {
                offerFaults++;
            }
            return false;
        }
    }

    private static boolean offerUnbelted(ClientChatMessage chat, int ownSeat) {
        if (chat == null) {
            return false;
        }
        String text = chat.text();
        if (text == null || !SERVER_AUTHOR.equals(chat.name())) {
            return false;
        }
        int seat = chat.seatId();
        synchronized (LOCK) {
            if (listArmed && seat == listSeat && text.startsWith(LIST_HEAD)) {
                if (listLines.size() < MAX_LIST_LINES) {
                    listLines.add(text);
                }
                if (LIST_END.equals(text)) {
                    listEnded = true;
                    listArmed = false;
                }
                linesConsumed++;
                LOCK.notifyAll();
                return true;
            }
            // A names line with no slot armed for it is still consumed; it is
            // stored nowhere (only an armed read parses one) and never reaches the chat feed
            // only a WHOLE names line on the own seat (any seat while
            // none is known); a line that merely starts with the head flows on to chat. The own seat is the
            // session's, read per line - never the last list arm's, which a seat change left stale
            if ((ownSeat < 0 || seat == ownSeat) && namesLineWhole(text)) {
                linesConsumed++;
                return true;
            }
            boolean changed = false;
            boolean receipt = false;
            if (isTerminal(text)) {
                for (VerbSlot slot : VERBS.values()) {
                    if (slot.seat == seat && slot.finalLine == null) {
                        slot.finalLine = text;
                        slot.terminal = true;
                        changed = true;
                    }
                }
                if (listArmed && seat == listSeat) {
                    listTerminal = text;
                    listArmed = false;
                    changed = true;
                }
            } else {
                for (Map.Entry<String, VerbSlot> e : VERBS.entrySet()) {
                    VerbSlot slot = e.getValue();
                    if (slot.seat != seat || slot.finalLine != null || !text.startsWith(e.getKey())) {
                        continue;
                    }
                    if (isQueuedTail(text.substring(e.getKey().length()))) {
                        slot.queued = true;
                    } else {
                        slot.finalLine = text;
                    }
                    changed = true;
                    receipt = true;
                }
            }
            if (receipt) {
                linesConsumed++;
            }
            if (authArmed && seat == authSeat) {
                AuthAnswer a = authAnswerOrNull(text);
                if (a != null) {
                    authAnswer = a;
                    authArmed = false;
                    changed = true;
                }
            }
            if (changed) {
                LOCK.notifyAll();
            }
            return receipt;
        }
    }

    /** The door's exact reply constants, the SAME ones the {@code /auth} replay law keys on: the elevation
     *  ({@code MatchConfigFromWire.AUTH_ELEVATED_REPLY}), the HELD line ({@code ChatInputLaw.AUTH_HELD_REPLY}) or one
     *  of the three misses ({@code ChatInputLaw.isAuthMissReply}); anything else null. */
    static AuthAnswer authAnswerOrNull(String text) {
        if (cc.starsector.client.MatchConfigFromWire.AUTH_ELEVATED_REPLY.equals(text)) {
            return AuthAnswer.SIGNED_IN;
        }
        if (cc.starsector.client.ChatInputLaw.AUTH_HELD_REPLY.equals(text)) {
            return AuthAnswer.HELD;
        }
        return cc.starsector.client.ChatInputLaw.isAuthMissReply(text) ? AuthAnswer.REFUSED : null;
    }

    static boolean isTerminal(String text) {
        for (String t : TERMINAL_LINES) {
            if (t.equals(text)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code tail} (the text after the {@code "<verb> <id>: "} prefix) is a QUEUED tail. */
    public static boolean isQueuedTail(String tail) {
        for (String q : QUEUED_TAILS) {
            if (q.equals(tail)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code text} is WHOLE a server names line, exactly the shape the server packs: {@link #NAMES_HEAD}, ONE
     * space, then one or more entries joined {@code ", "}, with nothing before, between or after them. An entry is
     * {@code <stem>=<hullId>=<hex>}: exactly two {@code =}; stem and hull id each pass the file-id law
     * ({@link LocalVariantIdLaw}, the editor's id law); the hex is LOWERCASE (the server's encoding), even-length,
     * 2..{@link #NAMES_HEX_MAX_DIGITS} digits, every byte printable ASCII (0x20..0x7e), not all spaces. One failing
     * entry, an empty body, a trailing separator or any tail (a join line's {@code " joined"}) refuses the WHOLE line.
     * The editor's armed read ({@code HostShips.readNames}) stays the lenient parser: it drops a bad entry alone and
     * also takes uppercase hex; every entry this check passes is one that parser reads (a row pins it). A repeated
     * stem is not refused here: the server never sends one, and the parser keeps the first. WHY this shape: the line
     * is wire input whose head a player can type, so it is bounded first (the chat cap, then every field's length
     * before its copy), walked once with no regex, and no input makes it throw.
     *
     */
    public static boolean namesLineWhole(String text) {
        int head = NAMES_HEAD.length();
        if (text == null || text.length() > ChatWriter.TEXT_MAX_CHARS || text.length() < head + 2
                || !text.startsWith(NAMES_HEAD) || text.charAt(head) != ' ') {
            return false;
        }
        int from = head + 1;
        while (true) {
            int comma = text.indexOf(',', from);
            int to = comma < 0 ? text.length() : comma;
            if (!namesEntryWhole(text, from, to)) {
                return false;
            }
            if (comma < 0) {
                return true;
            }
            if (comma + 1 >= text.length() || text.charAt(comma + 1) != ' ') {
                return false;
            }
            from = comma + 2;
        }
    }

    /** {@code text} from {@code from} to {@code to} as ONE names entry ({@link #namesLineWhole} holds the law). */
    private static boolean namesEntryWhole(String text, int from, int to) {
        int a = text.indexOf('=', from);
        if (a < 0 || a >= to) {
            return false;
        }
        int b = text.indexOf('=', a + 1);
        if (b < 0 || b >= to) {
            return false;
        }
        return idWhole(text, from, a) && idWhole(text, a + 1, b) && hexNameWhole(text, b + 1, to);
    }

    /** The span passes the file-id law; its length is checked before it is copied. */
    private static boolean idWhole(String text, int from, int to) {
        int len = to - from;
        return len > 0 && len <= LocalVariantIdLaw.MAX_CHARS && LocalVariantIdLaw.isLawful(text.substring(from, to));
    }

    /** The span as lowercase hex of printable-ASCII bytes, not all spaces, checked in place (no buffer is built); a
     *  third {@code =} lands here and fails as a non-hex digit. */
    private static boolean hexNameWhole(String text, int from, int to) {
        int digits = to - from;
        if (digits <= 0 || (digits & 1) != 0 || digits > NAMES_HEX_MAX_DIGITS) {
            return false;
        }
        boolean blank = true;
        for (int i = from; i < to; i += 2) {
            int hi = lowerHexDigit(text.charAt(i));
            int lo = lowerHexDigit(text.charAt(i + 1));
            if (hi < 0 || lo < 0) {
                return false;
            }
            int c = (hi << 4) | lo;
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
            blank &= c == ' ';
        }
        return !blank;
    }

    /** A lowercase ASCII hex digit's value, else -1. */
    private static int lowerHexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    /** host_lines_consumed (the chat round line's reader). */
    public static long linesConsumed() {
        synchronized (LOCK) {
            return linesConsumed;
        }
    }

    /** host_latch_faults (the chat round line's reader). */
    public static long offerFaults() {
        synchronized (LOCK) {
            return offerFaults;
        }
    }
}
