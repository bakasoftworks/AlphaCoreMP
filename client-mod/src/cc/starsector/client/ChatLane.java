package cc.starsector.client;

import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Wire chat (legs 3+4) — the input-law feed, typing-line render, CHTD HUD drain
 * and CHTU send drain — extracted VERBATIM from {@link PredictReconcileCombatPlugin} as the
 * ChatLane pre-pay (the NEW-LANE-LAW shape; deferred-refactors
 * PRCP-row standing candidate K4, cold, cross-thread): the one chat concern behind THIN plugin
 * relays, buying back 9500-nb backstop margin BEFORE the leg-3 reconcile build spends any.
 *
 * <p>Bodies, string literals and log shapes are byte-identical to the PRCP originals (log
 * graders and the length-only hygiene keep matching). Declared deviations: the
 * {@code plugin.} qualifier on the three PRCP touchpoints ({@code engine}, {@code session},
 * {@code blockShipKeyCommandsWhileTyping}), method visibilities at the new class boundary, and the
 * two javadoc links that crossed it.
 *
 * <p><b>Threading contract</b> (the queues stay synchronized for exactly this): render thread =
 * {@link #feedChatInput} / {@link #renderChatTypingLine} / {@link #drainChatMessages} /
 * {@link #resetForMatchReset}; net/reader thread = {@link #enqueueChatMessage} (the demux CHTD
 * callback); net/writer thread = {@link #sendPendingChats} (strictly behind an intent at both
 * send sites). Everything unsynchronized is render-thread-only, like every sibling collaborator.
 * The class is public for exactly two static members, {@link #editorLive} and {@link #editorAuth} (the ship
 * editor's host sign-in, HTTP thread); everything else stays package-private.
 */
public final class ChatLane {

    private final PredictReconcileCombatPlugin plugin;

    ChatLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    // Chat thread plumbing (leg 3; the UX halves — input law, HUD addMessage
    // drain — are leg 4). DOWN: the net-thread receive consumer queues each server-relayed
    // CHTD here for the render thread's drain (the edgeEventQueue idiom), bounded drop-oldest —
    // chat is presentation, and a backlog older than the HUD widget's own 20 s removal is
    // worthless. UP: the render-thread input law deposits submitted lines; the net/writer
    // thread drains them strictly behind an intent at BOTH send sites (the PDCL/SNNK slot).
    private final ArrayDeque<cc.starsector.client.net.ClientChatMessage> chatQueue = new ArrayDeque<>();
    private static final int CHAT_QUEUE_MAX = 64;
    private final ArrayDeque<String> pendingChatSends = new ArrayDeque<>();
    private static final int CHAT_SEND_PENDING_MAX = 16;
    private int chatSent;        // chat_sent — CHTU frames actually sent on CTRL
    private int chatSendDropped; // chat_send_dropped — deposit-queue overflow (net loop dead/backed up)
    /** spawn_pose_append_skipped — cursor poses NOT
     *  appended because the line would break the chat cap (render thread; chat round line). */
    private volatile int spawnPoseAppendSkipped;
    /** chat_lines_dropped_invalid — deposited lines
     *  refused by validateChatText before the writer (net thread; chat round line). */
    private volatile int chatLinesDroppedInvalid;
    /** The {@code /auth} hold → promote → replay-after-re-admit
     *  law is {@link AuthReplayLaw} (pure; ChatInputLawSelftest rows AR1–AR9) — the SNAM/PSTE/VSUB
     *  replay's shape ({@code replayAttachFramesAfterAdopt}) for the one user-typed verb with
     *  durable server state. The accepted line + its watermark are SESSION-scoped, hence the ONE
     *  static store: a ChatLane is per COMBAT, and the in-game rejoin (Esc → title → Multiplayer
     *  Test) builds a new lane over the SAME kept-open session — bound to the session OBJECT so a
     *  fresh connect (another serve, another server) never inherits the line. Held in memory
     *  only, NEVER logged (the send log prints lengths; the server's audit line redacts the verb),
     *  and dropped when THAT session closes ({@link #forgetAuthFor}: the mission-exit teardown that
     *  closes it, or the defunct session's close before a fresh dial). */
    private static final AuthReplayLaw.Store AUTH_STORE = new AuthReplayLaw.Store();
    private final AuthReplayLaw authReplay = new AuthReplayLaw(AUTH_STORE);

    // Chat UX state (render thread only). The input law is the pure
    // game-free state machine (ChatInputLawSelftest pins it); non-final so a lane failure can
    // swap in a fresh CLOSED instance (never leave the box stuck open with feeding dead).
    // chatLaneDead = the seatNameTagDead idiom: any chat-UX Throwable kills the lane for the
    // ROUND (fail-soft, no per-frame spam); finishMatchReset re-arms. The typing label is
    // lazy-built on the drawSeatNameTag recipe (victor10.fnt) and re-set each open frame.
    // Ctrl+V paste — the capture-side half:
    // the clipboard read lives HERE so ChatInputLaw keeps its no-LWJGL purity + selftest
    // boundary. Key ids pinned FROM the install's own lwjgl.jar (KEY_V=47 — a recon
    // bank's 46 was KEY_C, wrong; build pins from the jar's bytecode, never a recon bank). Detect prefers the
    // event's own isCtrlDown() with the pinned physical Keyboard poll as an OR-fallback: whether
    // isCtrlDown reports pre-consume at runtime is unverified until the proof flight, the OR
    // lands the paste under either behavior, and only genuinely-held Ctrl can satisfy the poll.
    private static final int KEY_V = 47;
    private static final int KEY_LCONTROL = 29;
    private static final int KEY_RCONTROL = 157;

    /** Knob {@code cc.predict.chatPaste} = {@code on|off}, compiled DEFAULT ON, invalid THROWS
     *  at class load (the {@code cc.predict.chatBubbles} family shape) — a deliberate
     *  player-facing toggle, NO expiry. {@code off}: the branch never matches — ctrl+V falls
     *  back to today's behavior (eventChar 0x16 dropped by the printability gate). */
    static final boolean PASTE_ON = parseOnOff(System.getProperty("cc.predict.chatPaste", "on"));

    /** The submitted-slash-command ring behind Up/Down, ONE static store on the
     *  {@link #AUTH_STORE} shape: a lane is per combat and {@link #chatInput} is swapped fresh on
     *  a lane death, so the ring lives here and every law instance walks it (an in-game rejoin
     *  keeps the history). Memory only, never logged. */
    private static final ChatInputLaw.History CHAT_HISTORY = new ChatInputLaw.History();

    private ChatInputLaw chatInput = new ChatInputLaw(CHAT_HISTORY);
    private boolean chatLaneDead;
    // wq P24 → v2: this round's picker-save count
    // (PeerBuildVerbLaw.SAVES_PER_ROUND; re-armed per round in resetForMatchReset). Render thread
    // (the picker lane's KIND_BUILDS arm is the only writer).
    private int peerSavesThisRound;
    /** v2: the client's "Auto-share builds I use" toggle, mirrored here for the
     *  NET thread's admission send — UNKNOWN until the render thread seeds it from the live
     *  voice client / the settings file ({@link #drainChatMessages}), then {@code 0} off / {@code 1} on. */
    private volatile int autoShareBuilds = AUTO_SHARE_UNKNOWN;
    private static final int AUTO_SHARE_UNKNOWN = -1;
    /** The {@code session.reAdmits()} value the auto-share declaration was last sent for (-1 = never). */
    private long autoShareSentAtReAdmits = -1L;
    private int autoShareSends;
    /** The {@code /teleport} arm + cursor→world lane (render thread, like the law). */
    private final TeleportCursorLane teleport = new TeleportCursorLane();
    private LabelAPI chatTypingLabel;
    private float chatTypingRowH;
    private Color chatNameColor;
    private Color chatTextColor;
    private int chatHudDrained;  // chat_hud_drained — CHTD lines actually put on the HUD feed
    private static final int CHAT_HUD_ERROR_LOG_MAX = 8;
    private int chatHudErrors;   // per-message addMessage failures (message dropped, lane lives)
    /** The bare-{@code /help} reply capture behind the help menu (render
     *  thread: armed in {@link #feedChatInput}, offered + polled in {@link #drainChatMessages}). The session object
     *  and its {@code reAdmits()} at the arm: a capture never survives a session change or a re-admit. */
    private final HelpMenuLaw helpMenu = new HelpMenuLaw();
    private Object helpArmedSession;
    private long helpArmedReAdmits;
    private int helpMenusOpened;   // help_menus_opened — captures shown as the picker menu
    private int helpFeedFallbacks; // help_feed_fallbacks — polls that put help lines in the feed instead

    /**
     * Chat colors, read once per combat (fail-soft to fixed fallbacks): the
     * sender name renders in the HUD's own {@code textFriendColor}, the line body in
     * {@code standardTextColor} — v1 keeps ONE name color for all senders (a side-keyed color
     * needs a seat→ship mapping the CHTD frame deliberately does not carry; the stamped name is
     * the attribution).
     */
    private void ensureChatColors() {
        if (chatNameColor != null) {
            return;
        }
        Color name = null;
        Color text = null;
        try {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            name = settings.getColor("textFriendColor");
            text = settings.getColor("standardTextColor");
        } catch (Throwable ignore) {
            // fall through to the fixed fallbacks — a color must never cost the lane
        }
        chatNameColor = name != null ? name : new Color(100, 255, 100);
        chatTextColor = text != null ? text : Color.WHITE;
    }

    /**
     * Typing line: {@code say: <before>|<after>} (the bar sits at
     * the law's caret; it was a trailing {@code _}) on the mod's victor10 HUD-font
     * label path (the {@code drawSeatNameTag} recipe), rendered only while the chat box is
     * open — typing is transient, deliberately NOT {@code addMessage}. Geometry [observed,
     * engine code]: the vanilla message feed is TOP-LEFT anchored —
     * {@code CombatState} renders it at {@code (20, screenH-20)} with a 5px text inset, so
     * its NEWEST row's top edge sits at {@code y = screenH-25}, {@code x = 25}, rows stacking
     * DOWNWARD 30px apart. "Above the feed" is therefore the band between that row and the
     * screen top: label bottom = {@code screenH-25+3} (3px gap; victor10 ≈ 12px tall fits the
     * 25px band), left = the feed's own text inset. Same coordinate space as this UI pass
     * (bottom-left origin, y-up; extent = {@code SettingsAPI.getScreenWidth/Height}, the exact
     * source the engine's HUD ortho uses). Fail-soft: a draw Throwable kills the chat lane for
     * the ROUND and force-closes the box (never leave the keyboard captured by a dead lane).
     */
    void renderChatTypingLine() {
        if (chatLaneDead || !chatInput.isOpen()) {
            return;
        }
        try {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            ensureChatColors();
            if (chatTypingLabel == null) {
                chatTypingLabel = settings.createLabel("say: |", "graphics/fonts/victor10.fnt");
                chatTypingLabel.setAlignment(Alignment.LMID);
                chatTypingRowH = chatTypingLabel.computeTextHeight("Ag");
                chatTypingLabel.setColor(chatNameColor);
            }
            // The bar glyph AT the law's caret (victor10.fnt
            // carries char id=124, xadvance 5); the clamp only guards a law bug from killing the
            // lane — the law's own invariant keeps caret within 0..length. An /auth
            // argument draws as '*' (length kept, so the caret indexes it unchanged; the buffer is untouched).
            String text = ChatInputLaw.maskAuthForDisplay(chatInput.bufferText());
            int caret = Math.max(0, Math.min(chatInput.caret(), text.length()));
            String line = "say: " + text.substring(0, caret) + "|" + text.substring(caret);
            chatTypingLabel.setText(line);
            chatTypingLabel.getPosition().setSize(chatTypingLabel.computeTextWidth(line), chatTypingRowH);
            chatTypingLabel.getPosition().setLocation(25f, settings.getScreenHeight() - 25f + 3f);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture
            chatTypingLabel.render(1f);
        } catch (Throwable t) {
            chatLaneDead = true;
            chatInput = new ChatInputLaw(CHAT_HISTORY);
            PilotWatchLog.error("predict chat-typing-line: draw FAILED, chat lane disabled until"
                    + " next round: " + t, t);
        }
    }

    /**
     * Feed KEYBOARD events to the chat {@link ChatInputLaw} and mirror its
     * verdicts onto the engine: consume what the law swallows (CONSUMED only — the RETURN
     * dispositions handle-and-pass-through per the send-both rule, so chat Enter never blocks
     * the engine's retreat prompt), deposit a SENT line for the net
     * thread ({@link #queueChatSend}), and surface RATE_BLOCKED as the local-only
     * {@code "chat: too fast"} line down the same HUD drain path (never sent — the local bucket
     * mirrors the server's, so a conforming user never trips the server's {@code chatRateDropped}).
     * While the box is open every KEYBOARD-driven ship verb is additionally blocked per frame
     * ({@link PredictReconcileCombatPlugin#blockShipKeyCommandsWhileTyping}) so native control and the captured {@code heldControls}
     * read neutral TOGETHER — the leg-4 twin gate as re-cut: ship keys are
     * engine-POLLED, so the command block (not event consumption) is what keeps typing off the
     * ship; mouse aim/fire and the mouse shield toggle stay live by design. Fail-soft: any
     * Throwable kills the lane for the ROUND (the seatNameTagDead idiom)
     * and force-closes the box so the keyboard is never left captured by a dead lane.
     */
    void feedChatInput(List<InputEventAPI> events) {
        if (chatLaneDead) {
            return;
        }
        try {
            for (InputEventAPI event : events) {
                if (event.isConsumed() || !event.isKeyboardEvent()) {
                    continue;
                }
                // Read BEFORE consume: the engine's consumed-event guard throws on post-consume
                // type/value access (getEventChar is the one unguarded accessor).
                boolean keyDown = event.isKeyDownEvent();
                boolean isRepeat = event.isRepeat();
                int keyCode = event.getEventValue();
                char eventChar = event.getEventChar();
                // /teleport arm-key: box CLOSED only (an open box owns the keyboard
                // and a typed 't' must stay text), fresh T press while armed ⇒ the cursor's
                // world pose rides the ordinary chat deposit as "/teleport @x,y" (the server
                // re-checks admin + re-parses + map-clamps; nothing is trusted from here).
                // No pose (no engine/viewport, off-grammar) ⇒ a local line, nothing sent.
                if (teleport.isArmed() && !chatInput.isOpen() && keyDown && !isRepeat
                        && keyCode == TeleportCursorLane.KEY_T) {
                    String pose = teleport.cursorPoseOrNull(plugin.engine);
                    if (pose != null) {
                        queueChatSend("/teleport " + pose);
                    } else {
                        enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                                0, "teleport", TeleportCursorLane.NO_CURSOR_TEXT));
                    }
                    event.consume();
                    continue;
                }
                // Ctrl+V paste: capture branch — OPEN box only (closed falls
                // through untouched), fresh V press with Ctrl reported by the event OR physically
                // held. The law strips + clamps (appendPasted = the six-check shape). Inner
                // catch: a throwing clipboard is a NO-OP paste, never a dead chat lane (paste
                // check-5 fail-closed; Sys.getClipboard() returns null on an empty/non-text
                // clipboard, and the pathological-size LWJGL-side String alloc is accepted
                // client-local risk). All event reads above happen BEFORE this consume.
                if (PASTE_ON && chatInput.isOpen() && keyDown && !isRepeat && keyCode == KEY_V
                        && (event.isCtrlDown()
                                || org.lwjgl.input.Keyboard.isKeyDown(KEY_LCONTROL)
                                || org.lwjgl.input.Keyboard.isKeyDown(KEY_RCONTROL))) {
                    try {
                        String clip = org.lwjgl.Sys.getClipboard();
                        if (clip != null) {
                            chatInput.appendPasted(clip);
                        }
                    } catch (Throwable ignore) {
                        // fail-closed: unreadable clipboard = nothing pasted, lane lives
                    }
                    event.consume();
                    continue;
                }
                ChatInputLaw.Result verdict = chatInput.onKeyboardEvent(
                        keyDown, isRepeat, keyCode, eventChar, System.currentTimeMillis());
                // The send-both rule: consume ONLY on CONSUMED — the law's RETURN dispositions
                // (open, blank-close, SENT, RATE_BLOCKED) handle-AND-pass-through so the engine's
                // own Enter consumers (the retreat prompt) still see the press. PASS may still
                // have acted (opened/closed the box); SENT/RATE_BLOCKED act AND pass through.
                if (verdict == ChatInputLaw.Result.CONSUMED) {
                    event.consume();
                } else if (verdict == ChatInputLaw.Result.SENT) {
                    String sent = chatInput.sentText();
                    plugin.picker.noteSentLine(sent); // A bare /unban vs /banned picks the menu's open rung
                    if (sent != null && sent.trim().equalsIgnoreCase("/settings")) {
                        // W12 VOICE L5: the client-local settings menu; the
                        // line never reaches the server (with arguments it still does, and
                        // bounces as unknown like today).
                        plugin.picker.openSettings();
                    } else if (sent != null && sent.trim().equalsIgnoreCase("/teleport")
                            && MatchConfigFromWire.seatAdmin()) {
                        // Bare /teleport from an admin seat = the LOCAL arm toggle
                        // (the /settings intercept shape; presentation-only gate — a non-admin's
                        // bare line still goes to the server and bounces "admin only" there).
                        // /teleport is ADMIN-ONLY in every match since the server dropped its
                        // debug-watch carve-out: the gate here matches the
                        // server's verb gate, so a non-admin debug seat never arms what the door refuses.
                        enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                                0, "teleport", teleport.toggle()));
                    } else if (sent != null && sent.trim().equalsIgnoreCase("/despawn")) {
                        // R-hover despawn:
                        // bare /despawn resolves the engine's OWN ship
                        // target (vanilla R) to its registry id HERE — the server holds no cursor —
                        // and goes up as `/despawn #<id>` (the /kick #id digit law; the server
                        // admin-gates and owns every refusal: a seat, a pod, an unknown id). No
                        // replica under the target (nothing, the own ship, the 1v1 foe, a corpse)
                        // = a local line, never a send. Any seat may type it; a non-admin's line
                        // bounces "admin only" at the door like every admin verb.
                        long despawnTarget = despawnTargetIdOrMinusOne();
                        if (despawnTarget > 0L) {
                            queueChatSend("/despawn #" + despawnTarget);
                        } else {
                            enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                                    0, "chat", "no ship targeted - R-target a ship, then /despawn"));
                        }
                    } else if (sent != null && sent.trim().equalsIgnoreCase("/help")) {
                        // Bare /help goes to the server AS TODAY
                        // (its reply = the door-gated verb list) AND this client appends what
                        // only it knows (ClientHelpLines: /settings, the chat + list keys, the
                        // push-to-talk keys on the match voice bit, death → Play). On the feed
                        // path it is deposited NOW so the block renders BELOW the server's, which
                        // lands one RTT later (the panel stacks newest on TOP); bottom-up like the door.
                        // With an argument the line goes to the server alone (USAGE bounce).
                        queueChatSend(sent);
                        cc.starsector.client.voice.VoicePtt ptt =
                                cc.starsector.client.voice.VoicePtt.fromSysprops();
                        List<String> local = ClientHelpLines.lines(
                                MatchConfigFromWire.voiceEnabled(), PASTE_ON,
                                keyName(ptt.keyProx()), keyName(ptt.keyGlobal()));
                        cc.starsector.client.net.PredictSession s = plugin.session;
                        if (ListPickerLane.ON && s != null) {
                            /* The reply opens as the help MENU —
                             * capture the server's lines (HelpMenuLaw); this block joins them in the menu, or
                             * goes to the feed with them on any fallback. Picker knob off / no session = the
                             * feed deposit below, as before. */
                            helpArmedSession = s;
                            helpArmedReAdmits = s.reAdmits();
                            helpMenu.begin(System.nanoTime(), s.ownSeatIdOrMinusOne(), local);
                        } else {
                            for (int i = local.size() - 1; i >= 0; i--) {
                                enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                                        0, ClientHelpLines.AUTHOR, local.get(i)));
                            }
                        }
                    } else {
                        // Peer-build sharing v2: v1's client-local /builds rows +
                        // /savebuild are RETIRED — /share, /unshare and /builds are SERVER verbs now
                        // (door-side, the /customs precedent); a /builds pick saves through the picker
                        // (ListPickerLane.applyLocalBuildSave), so every such line simply goes up.
                        queueChatSend(sent);
                    }
                } else if (verdict == ChatInputLaw.Result.RATE_BLOCKED) {
                    enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(
                            0, "chat", "too fast"));
                }
            }
            if (chatInput.isOpen()) {
                plugin.blockShipKeyCommandsWhileTyping();
            }
        } catch (Throwable t) {
            chatLaneDead = true;
            chatInput = new ChatInputLaw(CHAT_HISTORY); // force-closed: never leave the keyboard captured
            PilotWatchLog.error("predict chat-input: feed FAILED, chat lane disabled until next round: " + t, t);
        }
    }

    /** A push-to-talk key's display name for the local /help block — LWJGL's own
     *  key-name table (render thread; the same {@code Keyboard} the paste branch polls), else
     *  the honest {@code key <code>} for an unnamed sysprop override or a throwing table. */
    private static String keyName(int keyCode) {
        try {
            String name = org.lwjgl.input.Keyboard.getKeyName(keyCode);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignore) {
            // fall through: the name is presentation only
        }
        return "key " + keyCode;
    }

    /** Whether the chat box currently owns the keyboard: the picker
     * lane's PARK signal (a listing arriving while the box is open parks, prompting on close).
     * A dead lane's box is always force-closed ({@code chatInput} swapped fresh), so the raw law
     * read is the truth either way. Render thread only, like the law itself. */
    boolean isBoxOpen() {
        return chatInput.isOpen();
    }

    /** The picker's resolve-to-send relay: deposit one PICKER-built
     * command string ({@code ListPickerLaw.selectionCommand} grammar) onto the SAME bounded
     * render→net deposit the typed path uses, so a selection rides the unchanged CHTU lane,
     * bucket and send-site ordering (zero new UP surface by construction). */
    void queueCommandSend(String text) {
        queueChatSend(text);
    }

    /** Net-thread side of the chat intake — queue one server-relayed chat line for the
     * render thread's HUD drain (leg 4). Bounded drop-oldest: presentation traffic, never fatal. */
    void enqueueChatMessage(cc.starsector.client.net.ClientChatMessage chat) {
        // The mid-game /auth elevation derivation — the ONE receive choke
        // point sees every server-relayed line; an exact match of the server-stamped success
        // constant flips the presentation-only admin bit (no new wire frame kind — the reply IS
        // the push). Exact full-text equality on the "server" author: no fixed reply template
        // embeds player text that could equal the whole constant, and a player who NAMES
        // themselves "server" and types it flips only presentation (every verb re-checks the
        // door's session flag — MatchConfigFromWire.noteChatElevated javadoc carries the law).
        // The auth replay (→ AuthReplayLaw): the same call promotes the sent line
        // ONLY when it is unambiguous — exactly one /auth in flight — and retires one on a miss.
        cc.starsector.client.net.PredictSession s = plugin.session;
        if (authReplay.noteServerReply(chat.name(), chat.text(), s, s == null ? 0L : s.reAdmits())) {
            MatchConfigFromWire.noteChatElevated();
        }
        synchronized (chatQueue) {
            if (chatQueue.size() >= CHAT_QUEUE_MAX) {
                chatQueue.pollFirst();
            }
            chatQueue.addLast(chat);
        }
    }

    /**
     * render-thread drain of the chat queue onto the vanilla HUD message
     * feed: {@code addMessage(0, nameColor, name+": ", textColor, text)} — the engine's OWN
     * widget (insignia21LTaa.fnt, 15 s fade / 20 s removal), which is how the owner's HUD-font
     * ask is met with zero engine-internal access. Segments law: strictly alternating Color/String (the engine's
     * feed THROWS on any other type). A null combat UI (between rounds / pre-combat) holds the
     * queue — chat delivers between rounds and drains on the first UI-bearing frame; the
     * sender's own line arrives here only as the server echo (one display path = delivery
     * confirmation). Per-MESSAGE fail-soft with a capped error log: a bad line is dropped, the
     * lane lives (unlike the per-frame draw lanes there is nothing to spam — messages are rare).
     */
    void drainChatMessages() {
        seedAutoShareIfUnknown(); // v2: the render-thread seed the NET thread's admission send waits on
        com.fs.starfarer.api.combat.CombatEngineAPI engine = plugin.engine;
        com.fs.starfarer.api.combat.CombatUIAPI ui = engine != null ? engine.getCombatUI() : null;
        if (ui == null) {
            return;
        }
        pollHelpMenu(ui); // Once per drain, BEFORE the queue (a flushed capture is older)
        while (true) {
            cc.starsector.client.net.ClientChatMessage chat;
            synchronized (chatQueue) {
                chat = chatQueue.pollFirst();
            }
            if (chat == null) {
                return;
            }
            if (helpMenu.offer(chat) == HelpMenuLaw.Offer.CAPTURED) {
                continue; // Held for the /help menu — no feed line, no bubble forward
            }
            toHud(ui, chat);
        }
    }

    /** One line onto the HUD feed + the bubble forward — the drain's per-line body (shared
     *  with the help fallbacks). */
    private void toHud(com.fs.starfarer.api.combat.CombatUIAPI ui, cc.starsector.client.net.ClientChatMessage chat) {
        // The chat-bubble intake rides this ONE drain (no second subscription,
        // no new sync) — forwarded BEFORE the HUD try so it fires regardless of the addMessage
        // outcome; the bubble lane is fail-soft internally and can never cost this drain.
        plugin.chatBubbles.onChatMessage(chat);
        try {
            ensureChatColors();
            ui.addMessage(0, chatNameColor, chat.name() + ": ", chatTextColor, chat.text());
            chatHudDrained++;
            PilotWatchLog.info("predict chat hud #" + chatHudDrained + ": seat=" + chat.seatId()
                    + " (" + chat.text().length() + " chars)"); // length only, never the text
        } catch (Throwable t) {
            chatHudErrors++;
            if (chatHudErrors <= CHAT_HUD_ERROR_LOG_MAX) {
                PilotWatchLog.error("predict chat hud: addMessage FAILED (message dropped,"
                        + " lane lives; errors=" + chatHudErrors + "): " + t, t);
            }
        }
    }

    /**
     * The help capture's per-drain step (render thread, live combat UI):
     * a session change or re-admit since the arm disarms it ({@link HelpMenuLaw#reset}); then {@link
     * HelpMenuLaw#poll}: flushed or timed-out lines go to the feed first, a READY capture opens the picker menu
     * ({@link ListPickerLane#openHelp}) or, when it cannot open, goes to the feed in the pre-menu order. Logs are
     * counts only, never a line.
     */
    private void pollHelpMenu(com.fs.starfarer.api.combat.CombatUIAPI ui) {
        cc.starsector.client.net.PredictSession s = plugin.session;
        if (helpMenu.armed() && (s != helpArmedSession || (s != null && s.reAdmits() != helpArmedReAdmits))) {
            helpMenu.reset();
        }
        HelpMenuLaw.Outcome outcome = helpMenu.poll(System.nanoTime());
        if (outcome.kind() == HelpMenuLaw.Kind.NONE) {
            return;
        }
        List<cc.starsector.client.net.ClientChatMessage> toFeed = outcome.feed();
        if (outcome.kind() == HelpMenuLaw.Kind.READY) {
            if (plugin.picker.openHelp(outcome.lines())) {
                helpMenusOpened++;
                PilotWatchLog.info("predict chat: /help menu #" + helpMenusOpened + " (" + outcome.serverLines()
                        + " server + " + (outcome.lines().size() - outcome.serverLines()) + " client lines)");
            } else {
                List<cc.starsector.client.net.ClientChatMessage> both = new java.util.ArrayList<>(toFeed);
                both.addAll(outcome.fallback());
                toFeed = both;
            }
        }
        if (toFeed.isEmpty()) {
            return;
        }
        helpFeedFallbacks++;
        PilotWatchLog.info("predict chat: /help lines to the feed (" + outcome.kind() + ", " + toFeed.size()
                + " lines; help_feed_fallbacks=" + helpFeedFallbacks + ")");
        for (cc.starsector.client.net.ClientChatMessage chat : toFeed) {
            toHud(ui, chat);
        }
    }

    /**
     * render-thread intake: deposit one submitted chat line for the net/writer thread
     * (the leg-4 input law's send half is the sole caller once it lands). Bounded drop-oldest
     * like the sibling deposits — the leg-4 local rate mirror keeps a legit user far under the
     * cap, so overflow means the net loop is dead/backed up and the oldest line is already stale.
     */
    /** The registry entity id under the player's engine ship target
     *  ({@code ShipAPI.getShipTarget()}, vanilla R — the AutopilotFireLane / AuthoritativePresenter
     *  read), or {@code -1} when there is no target or it is not a registry replica (the own ship,
     *  the 1v1 foe, a corpse). Never throws: any engine miss reads as no target. */
    private long despawnTargetIdOrMinusOne() {
        try {
            com.fs.starfarer.api.combat.ShipAPI own =
                    plugin.engine == null ? null : plugin.engine.getPlayerShip();
            com.fs.starfarer.api.combat.ShipAPI target = own == null ? null : own.getShipTarget();
            return target == null ? -1L : plugin.remoteEntityIdOf(target);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * The ship editor's host sign-in seam. The holder names the lane of the combat plugin whose net threads run
     * {@link #sendPendingChats} right now: bound where those threads start, cleared at that plugin's teardown. A
     * kept-open session at the title has no lane here, so no drain would ever send a deposit (and no answer could
     * land): {@link #editorLive} is false there. Compare-and-clear, so a later lane's bind is never undone by an
     * older lane's teardown.
     *
     */
    private static final java.util.concurrent.atomic.AtomicReference<ChatLane> EDITOR_LANE =
            new java.util.concurrent.atomic.AtomicReference<>();

    static void bindEditorLane(ChatLane lane) {
        EDITOR_LANE.set(lane);
    }

    static void unbindEditorLane(ChatLane lane) {
        EDITOR_LANE.compareAndSet(lane, null);
    }

    /** True while an active combat plugin's chat drain runs (the editor's host card gates on it). */
    public static boolean editorLive() {
        return EDITOR_LANE.get() != null;
    }

    /**
     * HTTP thread (the editor's {@code host/signin} route): deposit {@code "/auth " + secret} on the bound lane's
     * send queue, exactly as a typed {@code /auth} lands there: held by the replay law first
     * ({@link AuthReplayLaw#noteSend}: the once-per-re-admit replay), then queued under the same lock and cap as
     * every deposit, so the drain's version-13 gate proves it ({@code sendAuth13}: the secret never goes out
     * readable, the send log prints the proof line's length only). Never through {@link #withSpawnFoeCursorPose}
     * (it reads the mouse, render thread only) and never into the typed-line history. Takes {@code /auth} ONLY: a
     * line {@link ChatInputLaw#auth13Class} does not call PROVE is refused here. Returns false with no lane bound
     * or a refused line (nothing queued). The secret is never logged and never kept beyond the queued line and the
     * replay law's held line (memory only).
     *
     */
    public static boolean editorAuth(String secret) {
        ChatLane lane = EDITOR_LANE.get();
        if (lane == null || secret == null) {
            return false;
        }
        String line = "/auth " + secret;
        if (ChatInputLaw.auth13Class(line) != ChatInputLaw.Auth13Line.PROVE) {
            return false;
        }
        lane.authReplay.noteSend(line);
        synchronized (lane.pendingChatSends) {
            if (lane.pendingChatSends.size() >= CHAT_SEND_PENDING_MAX) {
                lane.pendingChatSends.pollFirst();
                lane.chatSendDropped++;
            }
            lane.pendingChatSends.addLast(line);
        }
        return true;
    }

    private void queueChatSend(String text) {
        String line = withSpawnFoeCursorPose(text);
        authReplay.noteSend(line); // An /auth line is held for the replay law
        synchronized (pendingChatSends) {
            if (pendingChatSends.size() >= CHAT_SEND_PENDING_MAX) {
                pendingChatSends.pollFirst();
                chatSendDropped++;
            }
            pendingChatSends.addLast(line);
        }
    }

    /**
     * Cursor-placed {@code /spawnfoe}:
     * a {@code /spawnfoe <variant> [count]} line with NO explicit pose gets the mouse CURSOR's
     * world position appended as the server grammar's last token {@code @x,y}
     * ({@code ChatCommands.parseSpawnPoseOrNull}: signed, ≤ 6 integer digits, ≤ 3 decimals,
     * {@code Locale.ROOT} '.'), so the typed path AND the picker paste
     * ({@code ListPickerLaw.selectionCommand}) both place at the cursor. Untouched: a bare
     * {@code /spawnfoe} (the listing), an explicit {@code @} last token, a 4-token line whose 4th
     * token is not {@code officer} or any 5+-token line (the server's usage reply is
     * the honest answer), any other verb. Fail-soft: no engine/viewport,
     * a non-finite or out-of-grammar coordinate, or any throw ⇒ the line goes as typed (the
     * server then places at the wave edge). Render thread (the deposit's own thread; Mouse is
     * read here exactly as {@code processInputPreCoreControls} reads it for aim). The server
     * re-validates + map-clamps every pose — this is presentation-side convenience only.
     * {@code /spawnfriend} rides the same law (the
     * player-side twin — a friendly placed at the cursor is the whole point of the verb).
     */
    private String withSpawnFoeCursorPose(String text) {
        if (text == null) {
            return null;
        }
        try {
            String trimmed = text.trim();
            String[] tokens = trimmed.split("\\s+");
            // The server grammar is `<v> [count] [officer] [@x,y]`
            // — the pose stays LAST, so a 4-token line is appended to ONLY when its 4th token is
            // the folded `officer` keyword (`/spawnfoe <v> 3 officer`); any other 4-token shape
            // goes as typed and earns the server's usage reply.
            if (tokens.length < 2 || tokens.length > 4
                    || !("/spawnfoe".equalsIgnoreCase(tokens[0])
                            || "/spawnfriend".equalsIgnoreCase(tokens[0]))
                    || tokens[tokens.length - 1].startsWith("@")
                    || (tokens.length == 4
                            && !ListPickerLaw.SPAWN_OFFICER_KEYWORD.equalsIgnoreCase(tokens[3]))) {
                return text;
            }
            com.fs.starfarer.api.combat.CombatEngineAPI engine = plugin.engine;
            com.fs.starfarer.api.combat.ViewportAPI vp = engine == null ? null : engine.getViewport();
            if (vp == null) {
                return text;
            }
            float x = vp.convertScreenXToWorldX(org.lwjgl.input.Mouse.getX());
            float y = vp.convertScreenYToWorldY(org.lwjgl.input.Mouse.getY());
            if (!Float.isFinite(x) || !Float.isFinite(y)
                    || Math.abs(x) >= 999999f || Math.abs(y) >= 999999f) {
                return text; // outside the digits-only grammar — send as typed, edge placement
            }
            String pose = String.format(java.util.Locale.ROOT, "@%.1f,%.1f", x, y);
            String out = appendPoseIfFits(text, pose);
            if (out == text) { // identity: the pure law hands back the SAME reference on refusal
                spawnPoseAppendSkipped++;
                if (spawnPoseAppendSkipped == 1) {
                    PilotWatchLog.info("predict chat: " + tokens[0].toLowerCase(java.util.Locale.ROOT)
                            + " cursor pose SKIPPED - appended line would exceed the chat cap ("
                            + text.length() + " chars; server places at the wave edge;"
                            + " spawn_pose_append_skipped counts the rest on the chat round line)");
                }
                return text;
            }
            PilotWatchLog.info("predict chat: " + tokens[0].toLowerCase(java.util.Locale.ROOT)
                    + " cursor pose appended " + pose
                    + " (tokens=" + tokens.length + ")"); // the pose only, never the typed text
            return out;
        } catch (Throwable t) {
            return text; // presentation-side convenience; the line itself is never lost
        }
    }

    /**
     *
     * The pure cap law behind {@link #withSpawnFoeCursorPose}: {@code text.trim() + " " + poseSuffix}
     * when that line still satisfies the {@code ChatWriter.validateChatText} caps
     * (≤{@code TEXT_MAX_CHARS} chars AND ≤{@code TEXT_BUFFER_BYTES} UTF-8 bytes), else {@code text} itself — the SAME reference, so the caller counts the refusal by
     * identity. WHY: a lawful 99-120 char line plus a ~22-char pose used to fail validateChatText
     * inside the writer's encodeFrame, and the writer loop's catch(Throwable) closed the session.
     * Only refuses — never widens what the input law accepted. ChatInputLawSelftest rows CP1-CP4.
     */
    static String appendPoseIfFits(String text, String poseSuffix) {
        if (text == null || poseSuffix == null) {
            return text;
        }
        String out = text.trim() + " " + poseSuffix;
        if (out.length() > cc.starsector.client.net.ChatWriter.TEXT_MAX_CHARS
                || utf8Bytes(out) > cc.starsector.client.net.ChatWriter.TEXT_BUFFER_BYTES) {
            return text;
        }
        return out;
    }

    /** Twin of {@code ChatInputLaw.utf8Bytes}
     *  (private there) — the byte half of the validateChatText cap. */
    private static int utf8Bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Send half, NET/WRITER-thread drain: send every deposited chat line as a
     * {@code CHTU} frame on the reliable CTRL lane, called ONLY immediately after
     * {@code sendPendingAwsdNacks()} at both send sites — the same strictly-behind-an-intent
     * slot the PDCL/SNNK lanes ride (single sender thread per mode preserves ordering; the CTRL
     * lane is demuxed by magic server-side). A send failure propagates and fail-closes the loop
     * exactly like a failed intent send. Logs length only, never the text — the server's
     * {@code chat_relay} line carries it (the console-encoding lesson).
     */
    void sendPendingChats() throws IOException {
        replayAuthAfterReAdmit();
        retryHeldAuthReplay();
        declareAutoShareAfterAdmit();
        while (true) {
            String text;
            synchronized (pendingChatSends) {
                text = pendingChatSends.pollFirst();
            }
            if (text == null) {
                return;
            }
            // A belt: an invalid line must never reach encodeFrame, whose IAE
            // escapes to the writer loop's catch(Throwable) and CLOSES the session. Pre-validate
            // with the SAME law and drop just this line (length logged once, never the text nor
            // the IAE message — the trim branch echoes the text).
            try {
                cc.starsector.client.net.ChatWriter.validateChatText(text);
            } catch (IllegalArgumentException invalid) {
                chatLinesDroppedInvalid++;
                if (chatLinesDroppedInvalid == 1) {
                    PilotWatchLog.info("predict chat: invalid line DROPPED before the writer ("
                            + text.length() + " chars; session kept; chat_lines_dropped_invalid"
                            + " counts the rest on the chat round line)");
                }
                continue;
            }
            cc.starsector.client.net.PredictSession session = plugin.session;
            // the version-13 gate + the readable send = sendOrGate13
            ChatInputLaw.Auth13Line kind = sendOrGate13(session, text);
            if (kind != null) {
                drainAuth13(session, text, kind);
                continue;
            }
            chatSent++;
            PilotWatchLog.info("predict chat sent #" + chatSent + " (" + text.length() + " chars)");
        }
    }

    /**
     * The version-13 gate of {@link #sendPendingChats}, in its place and order: on a version-13 session a line
     * {@link ChatInputLaw#auth13Class} calls PROVE or REFUSE is handed back for {@link #drainAuth13} with nothing sent;
     * any other line goes out readable through {@code session.sendChat}, and {@code null} says it did (a send failure
     * propagates). WHY static: ChatInputLawSelftest QV3d drives the real gate over a counting fake session; on that
     * leg's classpath the plugin cannot be built (lwjgl_util) and the drain's log lines cannot load (log4j).
     *
     */
    static ChatInputLaw.Auth13Line sendOrGate13(cc.starsector.client.net.PredictSession session, String text)
            throws IOException {
        if (session.handshake13()) {
            ChatInputLaw.Auth13Line kind = ChatInputLaw.auth13Class(text);
            if (kind != ChatInputLaw.Auth13Line.PLAIN) {
                return kind;
            }
        }
        session.sendChat(text);
        return null;
    }

    /** The {@code /auth} proof line's length: {@code "/auth "} + 104 hex (the only length a version-13 send logs). */
    private static final int AUTH13_PROOF_LINE_CHARS = 6 + cc.starsector.server.runtime.Handshake13Auth.PROOF_HEX_CHARS;

    /**
     * NET/WRITER thread: one version-13 {@code /auth}-shaped line
     * ({@link ChatInputLaw#auth13Class} said PROVE or REFUSE) — the typed secret never leaves the process. REFUSE: a
     * local line ({@link ChatInputLaw#auth13RefusedReply}: the usage, or the led-line text), nothing sent. PROVE: {@code session.sendAuth13(secret)} — SENT counts as a sent chat (the log
     * prints the PROOF line's length, never the typed line's: that would leak the secret's length); RETIRED (an adopt
     * retired the core mid-send) re-queues the SAME line at the head once ({@link AuthReplayLaw#noteAuth13Retired}),
     * so it is re-proven under the new context; anything else — UNAVAILABLE, or NOT_VERSION_13 from a session that
     * said 13 (fail-closed: never the readable path) — a local "unavailable" line. Every local line goes through
     * {@link #enqueueChatMessage} under a NON-"server" author, so {@code noteServerReply} never reads it as a door miss.
     * Logs counters only.
     *
     */
    private void drainAuth13(cc.starsector.client.net.PredictSession session, String text,
            ChatInputLaw.Auth13Line kind) throws IOException {
        if (kind == ChatInputLaw.Auth13Line.REFUSE) {
            authReplay.noteAuth13Local(text, true);
            enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(0, "chat", ChatInputLaw.auth13RefusedReply(text)));
            PilotWatchLog.info("predict chat: /auth-shaped line refused locally on a version-13 session"
                    + " (auth13_refused=" + authReplay.auth13Refused() + ")");
            return;
        }
        cc.starsector.client.net.PredictSession.Auth13Send outcome =
                session.sendAuth13(ChatInputLaw.auth13SecretOrNull(text));
        if (outcome == cc.starsector.client.net.PredictSession.Auth13Send.SENT) {
            authReplay.noteAuth13Sent(text);
            chatSent++;
            PilotWatchLog.info("predict chat sent #" + chatSent + " (" + AUTH13_PROOF_LINE_CHARS + " chars)");
        } else if (outcome == cc.starsector.client.net.PredictSession.Auth13Send.RETIRED) {
            boolean requeue = authReplay.noteAuth13Retired(text);
            if (requeue) {
                synchronized (pendingChatSends) {
                    pendingChatSends.addFirst(text); // re-proven at once under the adopted context
                }
            }
            PilotWatchLog.info("predict chat: /auth proof dropped on a retired core ("
                    + (requeue ? "re-queued once" : "second time: line dropped") + "; auth13_retired="
                    + authReplay.auth13Retired() + " auth13_retired_dropped=" + authReplay.auth13RetiredDropped() + ")");
        } else {
            authReplay.noteAuth13Local(text, false);
            enqueueChatMessage(new cc.starsector.client.net.ClientChatMessage(0, "chat",
                    ChatInputLaw.AUTH13_UNAVAILABLE_LOCAL));
            PilotWatchLog.info("predict chat: /auth proof unavailable on this connection (auth13_unavailable="
                    + authReplay.auth13Unavailable() + ")");
        }
    }

    /**
     * Re-admit replay (net thread, ahead of the drain): when {@code session.reAdmits()} has
     * advanced since the last send pass, the fresh server session has NO {@code /auth}
     * elevation (the door re-derives admin from its own HELLO; {@code MatchConfigFromWire}
     * cleared the presentation bit at the re-seed). If the door had ACCEPTED an {@code /auth}
     * line on this run and the new WELCOME carried no credential admin bit, re-queue that exact
     * line at the head of the deposit — the door's reply (the same exact constant) re-flips the
     * presentation bit through the normal receive choke. A wrong line (password re-armed
     * mid-match) bounces once and spends one credential-fail budget unit, like a typed miss.
     * State is session-bound and STATIC (the lane is per combat; the kept-open session is not —
     * the in-game rejoin swaps lanes), watermarked by the re-admit count at promotion — the
     * decision itself is {@link AuthReplayLaw#replayDue} (the seat-admin bit is
     * read here, seeded before {@code reAdmits++}).
     */
    private void replayAuthAfterReAdmit() {
        cc.starsector.client.net.PredictSession s = plugin.session;
        if (s == null) {
            return;
        }
        long reAdmits = s.reAdmits();
        String line = authReplay.replayDue(s, reAdmits, MatchConfigFromWire.seatAdmin());
        if (line == null) {
            return;
        }
        synchronized (pendingChatSends) {
            pendingChatSends.addFirst(line); // the drain right below bounds the queue: never drop a live line for it
        }
        PilotWatchLog.info("predict chat: /auth replay queued after re-admit #" + reAdmits
                + " (auth_replays=" + authReplay.replays() + ")");
    }

    /**
     * {@code closed} has really closed — drop the accepted {@code /auth} line from the static
     * {@link #AUTH_STORE} when it was accepted on THAT session object ({@link AuthReplayLaw.Store#forget}); any other
     * session, or null, changes nothing. Callers: {@code PredictReconcileCombatPlugin.teardownActiveBody} (the
     * mission-exit close) and {@code PredictDialLane.startFreshDial} (the defunct session's close) — both hold the
     * one {@code CcClientModPlugin.predictSession} object every lane reads as {@code plugin.session}. Never throws.
     *
     */
    static void forgetAuthFor(Object closed) {
        AUTH_STORE.forget(closed);
    }

    /**
     * The held-replay retry — NET thread, right behind
     * {@link #replayAuthAfterReAdmit}: the ONE retry of a re-admit replay the door HELD (the ledger's
     * 250 ms streak-0 gap after a KEY-path re-admit answers the replay with the exact
     * {@code ChatInputLaw.AUTH_HELD_REPLY} line and runs no compare). Queued at the head like the
     * replay, ≥ {@code AuthReplayLaw.RETRY_DELAY_MILLIS} after this thread first saw the hold; the
     * decision, its once-only law and the second-hold-ends-it rule are {@link AuthReplayLaw#retryDue}.
     */
    private void retryHeldAuthReplay() {
        // The stamp and the compare share this one MONOTONIC clock (a wall-clock
        // step would only delay or hasten the single retry, but nanoTime removes even that).
        String line = authReplay.retryDue(System.nanoTime() / 1_000_000L);
        if (line == null) {
            return;
        }
        synchronized (pendingChatSends) {
            pendingChatSends.addFirst(line); // same head slot as the replay: never dropped for a live line
        }
        PilotWatchLog.info("predict chat: /auth replay RETRY queued after a held reply"
                + " (auth_replay_retries=" + authReplay.retries() + ")");
    }

    /**
     * Peer-build sharing v2 — NET thread, ahead of the drain like
     * {@link #replayAuthAfterReAdmit}: once per admission ({@code session.reAdmits()} watermark)
     * queue the SILENT auto-share declaration {@code /share auto on|off silent} so the server's
     * per-session flag (wire default OFF) mirrors this client's toggle. Sent ONLY when the
     * WELCOME said custom submissions are on ({@code CustomConfigFromWire.submissions()}): a
     * no-customs serve answers every share verb "custom variants unavailable" — silent or not —
     * and a player must never see that line for a setting they never touched. Waits for the
     * render thread's seed (UNKNOWN sends nothing; the next send pass retries). A toggle inside
     * a match re-sends through {@link #onSettings}.
     */
    private void declareAutoShareAfterAdmit() {
        cc.starsector.client.net.PredictSession s = plugin.session;
        int on = autoShareBuilds;
        if (s == null || on == AUTO_SHARE_UNKNOWN || !CustomConfigFromWire.submissions()) {
            return;
        }
        long reAdmits = s.reAdmits();
        if (reAdmits == autoShareSentAtReAdmits) {
            return;
        }
        autoShareSentAtReAdmits = reAdmits;
        queueAutoShareDeclaration(on == 1);
    }

    private void queueAutoShareDeclaration(boolean on) {
        synchronized (pendingChatSends) {
            if (pendingChatSends.size() >= CHAT_SEND_PENDING_MAX) {
                pendingChatSends.pollFirst();
                chatSendDropped++;
            }
            pendingChatSends.addLast("/share auto " + (on ? "on" : "off") + " silent");
        }
        autoShareSends++;
        PilotWatchLog.info("predict chat: auto-share declaration queued on=" + on
                + " (#" + autoShareSends + ")");
    }

    /**
     * v2: the render thread's settings hook — the picker's {@code /settings} apply ({@code
     * ListPickerLane.applyLocalSettingsPick}) and the per-frame seed. A CHANGED value inside a
     * customs match re-declares at once (silent); the seed itself never sends (the admission
     * pass does).
     */
    void onSettings(cc.starsector.client.voice.VoiceClientSettings settings) {
        if (settings == null) {
            return;
        }
        int next = settings.autoShareBuilds ? 1 : 0;
        int before = autoShareBuilds;
        autoShareBuilds = next;
        if (before != AUTO_SHARE_UNKNOWN && before != next && CustomConfigFromWire.submissions()) {
            queueAutoShareDeclaration(next == 1);
        }
    }

    /** Render thread: seed the auto-share mirror once (live voice client, else the settings file). */
    private void seedAutoShareIfUnknown() {
        if (autoShareBuilds != AUTO_SHARE_UNKNOWN) {
            return;
        }
        try {
            cc.starsector.client.voice.VoiceClient voice = plugin.voice();
            cc.starsector.client.voice.VoiceClientSettings s = voice != null ? voice.settings()
                    : cc.starsector.client.voice.VoiceSettingsStore.load(
                            com.fs.starfarer.api.Global.getSettings(), PilotWatchLog::info);
            onSettings(s == null ? cc.starsector.client.voice.VoiceClientSettings.DEFAULTS : s);
        } catch (RuntimeException fault) {
            PilotWatchLog.info("predict chat: auto-share seed failed (defaults): " + fault);
            onSettings(cc.starsector.client.voice.VoiceClientSettings.DEFAULTS);
        }
    }

    /** v2: the picker's per-round save budget (render thread). */
    boolean peerSaveAllowed() {
        return PeerBuildVerbLaw.saveAllowed(peerSavesThisRound);
    }

    void notePeerSaved() {
        peerSavesThisRound++;
    }

    // isAuthLine / isAuthMissReply live on ChatInputLaw (public; selftest rows
    // A1-A3); the hold/promote/replay/retry state machine is AuthReplayLaw (rows AR1-AR14).

    /**
     * Per-ROUND re-arm — the {@code finishMatchReset} relay. The queues
     * deliberately SURVIVE the wrap: chat delivers between rounds and drains on the
     * first UI-bearing frame of the next round.
     */
    void resetForMatchReset() {
        // The chat round line — cumulative per lane (per combat),
        // one line per round wrap, so every chat meter is printed somewhere.
        PilotWatchLog.info("predict chat round: chat_sent=" + chatSent
                + " chat_send_dropped=" + chatSendDropped
                + " chat_hud_drained=" + chatHudDrained
                + " spawn_pose_append_skipped=" + spawnPoseAppendSkipped
                + " chat_lines_dropped_invalid=" + chatLinesDroppedInvalid
                + " auth_replays=" + authReplay.replays()             // Both meters
                + " auth_replay_retries=" + authReplay.retries()      // exported here, per lane
                // The version-13 drain's local outcomes (AuthReplayLaw meters)
                + " auth13_unavailable=" + authReplay.auth13Unavailable()
                + " auth13_refused=" + authReplay.auth13Refused()
                + " auth13_retired=" + authReplay.auth13Retired()
                + " auth13_retired_dropped=" + authReplay.auth13RetiredDropped()
                + " help_menus_opened=" + helpMenusOpened             // The /help menu meters
                + " help_feed_fallbacks=" + helpFeedFallbacks
                // The editor host card's list lines kept off this feed + the latch belt's catches; JVM-cumulative
                // (the latch is one per JVM).
                + " host_lines_consumed=" + cc.starsector.client.net.HostShipsLatch.linesConsumed()
                + " host_latch_faults=" + cc.starsector.client.net.HostShipsLatch.offerFaults());
        helpMenu.reset(); // A capture never survives the wrap — its lines go to the feed
        chatLaneDead = false;   // Same per-ROUND re-arm for the chat UX lane
        chatTypingLabel = null;  // rebuilt lazily (the seatLabelByShip.clear() idiom)
        // The ≤ 8 picker-save budget is per ROUND (it once re-armed only
        // with a new lane = per combat); saveFromRegistry re-checks the fold on every pick.
        peerSavesThisRound = 0;
    }

    private static boolean parseOnOff(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException("cc.predict.chatPaste must be on|off; got '" + raw + "'");
    }
}
