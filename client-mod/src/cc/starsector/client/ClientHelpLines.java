package cc.starsector.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The CLIENT half of the {@code /help} reply — what only this
 * client knows and the server's door-assembled list ({@code ChatCommands.helpLines}) cannot
 * name: the client-local {@code /settings} verb, the chat-box and list-panel keys, the
 * push-to-talk keys, and the death → respawn motion. Pure + static, the server's help-line laws
 * verbatim: one line per row in the {@code <thing> - what it does} shape, every line a constant
 * inside the {@code ChatWriter.TEXT_MAX_CHARS} cap (the selftest pins each), and a feature-gated
 * row appears ONLY when the feature is live on this match (the voice keys ride the WELCOME voice
 * bit — a joiner is never told to hold a key that does nothing here). The chat lane deposits
 * the list BOTTOM-UP (the vanilla combat message panel stacks newest on TOP) and BEFORE the
 * server's reply lands, so the panel reads: the server's {@code commands:} block, then this one.
 * The key names are resolved by the caller (LWJGL's key-name table is render-thread truth; this
 * class never touches it) — nothing here is player-authored text.
 */
public final class ClientHelpLines {

    /** The HUD author prefix of every local line ({@code client: …}); never {@code "server"} —
     *  that name is the /auth elevation derivation's exact-match key in {@code ChatLane}. */
    public static final String AUTHOR = "client";

    /** The block header (first line in READING order). */
    public static final String HEADER = "local commands and keys:";

    private ClientHelpLines() {
    }

    /**
     * The local help lines in READING order.
     *
     * @param voiceEnabled the match's WELCOME voice bit ({@code MatchConfigFromWire.voiceEnabled})
     * @param pasteOn      the chat lane's Ctrl+V paste switch ({@code cc.predict.chatPaste})
     * @param proxKey      the resolved name of the proximity push-to-talk key (e.g. {@code SPACE})
     * @param globalKey    the resolved name of the global push-to-talk key (e.g. {@code B})
     */
    public static List<String> lines(boolean voiceEnabled, boolean pasteOn, String proxKey,
            String globalKey) {
        List<String> lines = new ArrayList<String>(6);
        lines.add(HEADER);
        lines.add("/settings - voice and HUD settings menu (this client only, never sent)");
        // Peer-build sharing v2: /share, /unshare and /builds are SERVER verbs now —
        // the server's own /help block lists them under its customs gate; nothing client-local here.
        lines.add(pasteOn
                ? "Enter - open the chat box; Enter sends, Esc cancels, Ctrl+V pastes"
                : "Enter - open the chat box; Enter sends, Esc cancels");
        lines.add("lists - Up/Down moves, Enter picks, Left/Right steps a value, Esc closes, typing filters");
        if (voiceEnabled) {
            lines.add("hold " + proxKey + " - voice to nearby players; hold " + globalKey
                    + " - voice to everyone");
        }
        // Esc opens the game's own exit menu, whose End Mission leaves the match; the respawn needs
        // no key at all (q-K3: the kit README's "a fresh ship flies in" is the code's behaviour, this line was not).
        lines.add("after death - no key needed; your next ship flies in by itself if the host has respawns on");
        return lines;
    }
}
