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
 * nothing). {@link #offer} returns {@code true} = CONSUMED for exactly two classes of line: a {@code shiplist } line
 * while the LIST slot is armed (the host's chat is not spammed with the card's list), and a {@code "<verb> <id>: "}
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
 * {@link #MAX_LIST_LINES} list lines. B-clean: java.lang, java.util and the pure chat-law reply constants.
 * The consumed receipts:
 */
public final class HostShipsLatch {
    /** The server-stamped author every receipt carries. */
    static final String SERVER_AUTHOR = "server";
    /** Every {@code /shiplist} line starts with this head; the LIST slot consumes exactly these lines. */
    public static final String LIST_HEAD = "shiplist ";
    /** The list's last line. */
    public static final String LIST_END = "shiplist end";
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
     *  the slot is disarmed on return (later {@code shiplist } lines reach the chat feed). */
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
     * {@code shiplist } line for the armed LIST slot, or a receipt that landed on an armed verb slot: the caller
     * passes it to no other reader); every other line returns {@code false}, latched or not. Never throws: a fault
     * is counted and the line flows on.
     */
    public static boolean offer(ClientChatMessage chat) {
        try {
            return offerUnbelted(chat);
        } catch (RuntimeException fault) {
            synchronized (LOCK) {
                offerFaults++;
            }
            return false;
        }
    }

    private static boolean offerUnbelted(ClientChatMessage chat) {
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
