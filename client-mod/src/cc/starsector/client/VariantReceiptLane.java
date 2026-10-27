package cc.starsector.client;

import java.util.List;

import cc.starsector.client.net.ClientChatMessage;
import cc.starsector.client.net.ClientVariantReceipt;
import cc.starsector.client.net.VariantRejectHints;

/**
 * The combat-side {@code VACK} receipt lane (W15 v0):
 * drain-takes the session's queued receipts each render frame (beside {@code vdef.drain()}) and
 * TOASTS the verdict into the chat log as a seat-0 {@code "customs"} line — ACCEPTED names the
 * server-minted canonical id the seat now flies (the server's own VDEF fan + RAPP repoint carry
 * the hull; nothing to fold here), REJECTED names the {@code DoorRejectReasons.custom()} token.
 * Seat 0 never bubbles (ChatBubbleLane law (b)), so the line lives in the log only. Player-
 * authored text never rides this line: the id is wire-law printable ASCII, the token is the
 * server's own vocabulary; the {@link ClientChatMessage} ctor re-checks both caps.
 */
final class VariantReceiptLane {
    static final String AUTHOR = "customs";
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
        if (receipt.accepted()) {
            accepted++;
            // The accept names the BUILD (a server build re-flown through Fly is not
            // "a custom variant" to the player); the server's own chat line says the same words.
            text = "next respawn: " + receipt.variantId() + " (accepted)";
        } else {
            rejected++;
            text = "Custom variant REJECTED (" + receipt.reasonToken() + "): " + receipt.variantId();
        }
        PilotWatchLog.info("predict vack seq=" + receipt.submitSeq()
                + (receipt.accepted() ? " accepted" : " rejected:" + receipt.reasonToken())
                + " id=" + receipt.variantId() + " (accepted=" + accepted + " rejected=" + rejected + ")"
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
