package cc.starsector.client;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import cc.starsector.client.net.ClientChatMessage;
import cc.starsector.client.net.ClientVariantReceipt;
import cc.starsector.client.net.VariantRejectHints;
import cc.starsector.client.net.VariantSubmitWriter;

/**
 * The combat-side {@code VACK} receipt lane (W15 v0):
 * drain-takes the session's queued receipts each render frame (beside {@code vdef.drain()}) and
 * TOASTS the verdict into the chat log as a seat-0 {@code "customs"} line — ACCEPTED is the next
 * respawn (the server's own VDEF fan + RAPP repoint carry the hull; nothing to fold here), REJECTED
 * carries the {@code DoorRejectReasons.custom()} token. Either line names the build by the name the
 * player typed when this client remembers one for the receipt's submit seq
 * ({@link #noteSubmitted}), else by the receipt's id (the server-minted canonical id on ACCEPT, the
 * echoed local id on REJECT). Seat 0 never bubbles (ChatBubbleLane law (b)), so the line lives in
 * the log only. The typed name is the player's own text, cut to printable ASCII
 * {@code <=} {@link #NAME_MAX_CHARS} before it is stored and quoted when painted; the id is wire-law
 * printable ASCII, the token is the server's own vocabulary; the {@link ClientChatMessage} ctor
 * re-checks the caps. No name ever reaches the log.
 */
public final class VariantReceiptLane {
    static final String AUTHOR = "customs";
    /** How many submitted names this client remembers at once; the oldest goes
     *  first. A receipt takes its entry, so the memory only fills when receipts never come back (a lost session). */
    public static final int SUBMITTED_NAMES_CAP = 16;
    /** The typed-name cut, the same 48 the peer-build label applies. */
    static final int NAME_MAX_CHARS = 48;
    /** submit seq (0..{@code SUBMIT_SEQ_MAX}) -> {local id as submitted, the bounded typed name}; insertion order =
     *  age. Guarded by itself: the editor's submit runs off the render thread. */
    private static final LinkedHashMap<Integer, String[]> SUBMITTED_NAMES = new LinkedHashMap<>();

    /** W15 v1: the reject token → fix hint ({@link VariantRejectHints}; the
     *  verifier holds the token-parity row); {@code null} = unknown token, the raw token stands. */
    static String hintOrNull(String reasonToken) {
        return VariantRejectHints.hintOrNull(reasonToken);
    }

    private final PredictReconcileCombatPlugin plugin;
    private int accepted;
    private int rejected;

    VariantReceiptLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        clearSubmittedNames(); // a new combat = the previous match is over: no name outlives it
    }

    /**
     * Remembers the name the player typed for one submission, so its receipt
     * toast can name the build instead of an id. The key is the submit seq because it is the one field both verdicts
     * echo: an ACCEPT carries only the server-minted canonical id, never the submitted one. Written once per submit,
     * never per tick; bounded at {@link #SUBMITTED_NAMES_CAP}, the oldest out; a re-used (wrapped) seq re-enters as
     * the newest. A null, blank or out-of-range input stores nothing (that receipt then shows its id). Any thread.
     */
    public static void noteSubmitted(int submitSeq, String localId, String typedName) {
        String name = boundedNameOrNull(typedName);
        if (submitSeq < 0 || submitSeq > VariantSubmitWriter.SUBMIT_SEQ_MAX || name == null) {
            return;
        }
        synchronized (SUBMITTED_NAMES) {
            SUBMITTED_NAMES.remove(submitSeq);
            SUBMITTED_NAMES.put(submitSeq, new String[] {localId, name});
            Iterator<Integer> oldest = SUBMITTED_NAMES.keySet().iterator();
            while (SUBMITTED_NAMES.size() > SUBMITTED_NAMES_CAP) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** Forgets every remembered name: the match is over. */
    public static void clearSubmittedNames() {
        synchronized (SUBMITTED_NAMES) {
            SUBMITTED_NAMES.clear();
        }
    }

    static int submittedNamesSize() {
        synchronized (SUBMITTED_NAMES) {
            return SUBMITTED_NAMES.size();
        }
    }

    /**
     * Takes the name remembered for this receipt's seq (the entry leaves either
     * way: one receipt per seq); null = no name known. A REJECT echoes the local id that was submitted, so a
     * remembered id that differs is a stale seq (an earlier session's numbering): no name rather than a wrong one.
     */
    static String takeSubmittedName(ClientVariantReceipt receipt) {
        String[] noted;
        synchronized (SUBMITTED_NAMES) {
            noted = SUBMITTED_NAMES.remove(receipt.submitSeq());
        }
        if (noted == null || (!receipt.accepted() && noted[0] != null && !noted[0].equals(receipt.variantId()))) {
            return null;
        }
        return noted[1];
    }

    /** The verdict line: the quoted typed name when one is known, else the
     *  receipt's id (the line before names existed). */
    static String verdictLine(ClientVariantReceipt receipt, String nameOrNull) {
        String shown = nameOrNull != null ? "'" + nameOrNull + "'" : receipt.variantId();
        // The accept names the BUILD (a server build re-flown through Fly is not
        // "a custom variant" to the player); the server's own chat line says the same words.
        return receipt.accepted() ? "next respawn: " + shown + " (accepted)"
                : "Custom variant REJECTED (" + receipt.reasonToken() + "): " + shown;
    }

    /** The typed name as it may be painted: trimmed, printable ASCII (anything
     *  else becomes {@code ?}), at most {@link #NAME_MAX_CHARS} with a {@code ~} mark on a cut; null when blank. */
    static String boundedNameOrNull(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) {
            return null;
        }
        boolean over = t.length() > NAME_MAX_CHARS;
        int keep = over ? NAME_MAX_CHARS - 1 : t.length();
        StringBuilder sb = new StringBuilder(keep + 1);
        for (int i = 0; i < keep; i++) {
            char c = t.charAt(i);
            sb.append(c < 0x20 || c > 0x7e ? '?' : c);
        }
        if (over) {
            sb.append('~');
        }
        return sb.toString();
    }

    /** Render-thread poll — order-free (presentation only). */
    void drain() {
        List<ClientVariantReceipt> takes = plugin.session.takeVariantReceipts();
        for (ClientVariantReceipt receipt : takes) {
            fold(receipt);
        }
    }

    private void fold(ClientVariantReceipt receipt) {
        String text;
        // A KEEP-ONLY accept (the host's Promote) is not this player's next
        // respawn and its id is not for the chat: the host card's own line ("'<name>' changed - ...") and the room fan
        // tell the outcome; only the log keeps the id. The mark is taken once per seq (the session's send set it).
        boolean keepOnly = receipt.accepted() && plugin.session.takeKeepOnlyMark(receipt.submitSeq());
        String name = takeSubmittedName(receipt); // taken even for a keep-only accept: one receipt per seq
        if (receipt.accepted()) {
            accepted++;
        } else {
            rejected++;
        }
        text = verdictLine(receipt, name);
        PilotWatchLog.info("predict vack seq=" + receipt.submitSeq()
                + (receipt.accepted() ? " accepted" : " rejected:" + receipt.reasonToken())
                + " id=" + receipt.variantId() + " named=" + (name != null ? 1 : 0)
                + " (accepted=" + accepted + " rejected=" + rejected + ")"
                + (keepOnly ? " keep_only=1 (no chat line)" : ""));
        if (keepOnly) {
            return;
        }
        try {
            plugin.chat.enqueueChatMessage(new ClientChatMessage(0, AUTHOR, clip(text)));
            // W15 v1: the fix hint rides a SECOND line (the verdict line alone
            // can already reach the 120-char cap with a 64-char id); unknown token = no hint.
            String hint = receipt.accepted() ? null : hintOrNull(receipt.reasonToken());
            if (hint != null) {
                plugin.chat.enqueueChatMessage(new ClientChatMessage(0, AUTHOR, clip("-> " + hint)));
            }
        } catch (RuntimeException never) {
            PilotWatchLog.error("predict vack: toast line refused by the chat law", never);
        }
    }

    /** The chat-line cap (ChatWriter.TEXT_MAX_CHARS = 120); the longest lawful line is 118. */
    private static String clip(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    int accepted() {
        return accepted;
    }

    int rejected() {
        return rejected;
    }
}
