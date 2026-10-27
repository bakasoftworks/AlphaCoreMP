package cc.starsector.client;

import cc.starsector.client.net.ClientChatMessage;
import cc.starsector.client.net.ClientSeatStateEvent;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.PredictSession;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

/**
 * Chat bubbles over ships:
 * each server-relayed {@code CHTD} chat line additionally pops a transient text bubble
 * over the SENDER's ship, one row above its seat-name tag — the HUD feed tells you WHAT was said,
 * the bubble shows WHO said it, spatially. v1 is text-only: the seat name already renders as the
 * always-on tag directly below the bubble ({@link SeatNameHullBarLane}), so "above their name" is
 * satisfied positionally with zero duplication. Zero wire change — the CHTD carry
 * ({@link ClientChatMessage}: seatId + server-stamped name + text) suffices; attribution is the
 * server's authoritative seatId, correlated client-side.
 *
 * <p><b>Intake</b> = the drain-time forward from {@link ChatLane#drainChatMessages} (render
 * thread; ChatLane's bounded-64 queue stays the ONE intake — no second subscription, no new
 * sync; the forward fires regardless of the HUD addMessage outcome). Everything here is
 * render-thread-only, like every sibling collaborator.
 *
 * <p><b>Suppression laws (binding):</b> (a) name {@code "server"} never bubbles —
 * slash-command replies ride the REQUESTER's OWN seatId stamped {@code "server"}
 * ({@code PilotIntentDatagramServer.dispatchChatCommand}), so a naive seat→ship bubble would pop
 * command replies over the requester's own hull (accepted cosmetic residual: a player seat NAMED
 * "server" is wire-indistinguishable and stays suppressed — no new wire flag v1); (b) seatId 0
 * never bubbles (the local-only RATE_BLOCKED synthetic rides seat 0); (c) unresolved seat /
 * no mounted hull / unbound ship ⇒ fail-closed skip (the GPEE own-seat precedent).
 *
 * <p><b>Seat→ship correlation:</b> seatId → hull wire id via the N-peer {@code SEAT}
 * table when the live session carries one ({@link PredictReconcileDatagramSession#seatStateTable}
 * — concrete-session-only, hence the localized instanceof: the TCP fallback session is still a
 * live {@link PredictSession} impl), else the certified-1v1 identity contract (seat n ↔ wire
 * id n, the {@link SeatNameHullBarLane} SNMS fold law); wire id → {@link ShipAPI} via ownShip /
 * registry remotes / the 1v1 foe — the same id mapping the hull-bar pass uses. Resolution runs
 * per DRAW frame (bindings and the seat table both move under a live bubble).
 *
 * <p><b>Presentation:</b> one live bubble per seat, replace-on-new (timer resets), hard cap
 * {@value #BUBBLE_CAP} evict-oldest; dwell {@value #DWELL_SECONDS} s then a
 * {@value #FADE_SECONDS} s alpha fade, {@code advance(amount)}-driven (paused frames deliver
 * {@code advance(0)} — the clock freezes, no stacking). Draw = the {@code drawSeatNameTag}
 * recipe (victor10 label, GL blend prep, world→screen at the tag anchor) one row above the tag,
 * centered, over a translucent GL_QUADS backing (the {@code drawServerHullBar} idiom), behind
 * the tag lane's own gates: in-play + alive + player-POV fog-visible from the bind-time side
 * (never a live {@code getOwner()} — the corpse-poison class).
 *
 * <p><b>Text law:</b> the line is already render-INERT at intake — {@code ChatWriter.validateChatText}
 * ran in the {@link ClientChatMessage} ctor (trimmed, single-line printable, ≤120 chars) — so
 * there is NO re-filter and no hand-rolled char loop here; the visual clamp is a pure
 * substring+ASCII-ellipsis past {@value #VISUAL_MAX_CHARS} chars (no codepoint ever line-breaks;
 * neutralizes the U+2028/bidi residual risk by construction). Logs are LENGTH-ONLY,
 * never the text: machine row {@code bubble_shown seat=<n> len=<k>} at a bubble's
 * FIRST actual draw. Fail-soft: any Throwable dead-latches the lane for the ROUND (the
 * seatNameTagDead idiom); {@link #resetForMatchReset} re-arms.
 *
 * <p>Knob {@code cc.predict.chatBubbles} = {@code on|off}, compiled DEFAULT ON, invalid THROWS at
 * class load (the {@code PredictFailBannerCombatPlugin} contract). A permanent
 * player-facing toggle — NO expiry (not a class-(iii) kill-switch). {@code off} = zero state,
 * zero draw, byte-identical presentation.
 */
final class ChatBubbleLane {

    static final boolean ON = parseOnOff(System.getProperty("cc.predict.chatBubbles", "on"));

    /** Full-brightness dwell before the fade starts (s). */
    private static final float DWELL_SECONDS = 6f;
    /** Linear alpha fade tail after the dwell (s); the bubble dies at dwell+fade. */
    private static final float FADE_SECONDS = 1.5f;
    /** Hard cap on concurrently-live bubbles (evict-oldest); one per seat keeps this theoretical. */
    private static final int BUBBLE_CAP = 32;
    /** Visual clamp: past this many chars the bubble shows a substring + "..." (display only). */
    private static final int VISUAL_MAX_CHARS = 64;

    private final PredictReconcileCombatPlugin plugin;

    /** Live bubbles keyed by sender seatId — replace-on-new resets the seat's timer. */
    private final Map<Integer, Bubble> bubbleBySeat = new HashMap<Integer, Bubble>();
    private boolean bubbleLaneDead;
    private float bubbleRowH;
    private Color bubbleTextColor;

    private static final class Bubble {
        final int seatId;
        final String displayText; // visually clamped; the wire line itself is never re-filtered
        final int textLen;        // ORIGINAL line length, for the length-only machine row
        float age;
        LabelAPI label;           // lazy-built at first draw (the seatLabelByShip idiom)
        boolean shownLogged;      // bubble_shown fires once, at the first frame it actually draws

        Bubble(int seatId, String displayText, int textLen) {
            this.seatId = seatId;
            this.displayText = displayText;
            this.textLen = textLen;
        }
    }

    ChatBubbleLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Render-thread intake — one drained CHTD line ({@link ChatLane#drainChatMessages} forward).
     * Applies the suppression laws, mints/replaces the seat's bubble, and stays fail-soft
     * internally so a bubble failure can never cost the HUD drain it rides.
     */
    void onChatMessage(ClientChatMessage chat) {
        if (!ON || bubbleLaneDead) {
            return;
        }
        try {
            if (chat.seatId() == 0 || "server".equals(chat.name())) {
                return; // System sender / local rate notice — never a bubble
            }
            String text = chat.text();
            String display = text.length() > VISUAL_MAX_CHARS
                    ? text.substring(0, VISUAL_MAX_CHARS - 1) + "..." : text;
            if (bubbleBySeat.size() >= BUBBLE_CAP && !bubbleBySeat.containsKey(chat.seatId())) {
                Integer oldestSeat = null;
                float oldestAge = -1f;
                for (Bubble b : bubbleBySeat.values()) {
                    if (b.age > oldestAge) {
                        oldestAge = b.age;
                        oldestSeat = b.seatId;
                    }
                }
                bubbleBySeat.remove(oldestSeat);
            }
            bubbleBySeat.put(chat.seatId(), new Bubble(chat.seatId(), display, text.length()));
        } catch (Throwable t) {
            bubbleLaneDead = true;
            bubbleBySeat.clear();
            PilotWatchLog.error("predict chat-bubble: intake FAILED, lane disabled until next"
                    + " round: " + t, t);
        }
    }

    /** Dwell/fade clock — the PRCP advance relay; paused frames deliver {@code amount=0}. */
    void advance(float amount) {
        if (!ON || bubbleLaneDead || amount <= 0f || bubbleBySeat.isEmpty()) {
            return;
        }
        for (Iterator<Bubble> it = bubbleBySeat.values().iterator(); it.hasNext();) {
            Bubble b = it.next();
            b.age += amount;
            if (b.age >= DWELL_SECONDS + FADE_SECONDS) {
                it.remove();
            }
        }
    }

    /**
     * The renderInUICoords relay, called AFTER {@code renderSeatNameTags} — per-frame seat→ship
     * resolve + the tag-lane gates + the draw recipe (class javadoc). A draw Throwable kills the
     * lane for the ROUND, loud once.
     */
    void render(ViewportAPI vp) {
        if (!ON || bubbleLaneDead || bubbleBySeat.isEmpty() || plugin.engine == null || vp == null) {
            return;
        }
        try {
            int ownSide = plugin.ownSideAtBind >= 0 ? plugin.ownSideAtBind : 0;
            com.fs.starfarer.api.combat.FogOfWarAPI fog = plugin.engine.getFogOfWar(ownSide);
            if (fog == null) {
                return; // no fog authority -> no bubble (never leak a fogged position)
            }
            // ONE table snapshot per frame for every live bubble (it is already a copy).
            PredictSession session = plugin.session;
            SortedMap<Integer, ClientSeatStateEvent> seatTable =
                    session instanceof PredictReconcileDatagramSession
                            ? ((PredictReconcileDatagramSession) session).seatStateTable() : null;
            for (Bubble b : bubbleBySeat.values()) {
                ShipAPI ship = resolveSeatShip(b.seatId, session, seatTable);
                if (ship == null || !plugin.engine.isEntityInPlay(ship) || !ship.isAlive()
                        || !fog.isVisible(ship)) {
                    continue; // fail-closed + the tag lane's per-ship gates
                }
                drawBubble(vp, b, ship);
            }
        } catch (Throwable t) {
            bubbleLaneDead = true;
            PilotWatchLog.error("predict chat-bubble: draw FAILED, lane disabled until next"
                    + " round: " + t, t);
        }
    }

    /**
     * Correlation chain: SEAT table (N-peer serves; a known seat with NO mounted hull is a
     * fail-closed skip, not a fallback) → certified-1v1 identity contract (seat n ↔ wire id n) →
     * wire id to ship via ownShip / registry remotes / the 1v1 foe. Null = suppress this frame.
     */
    private ShipAPI resolveSeatShip(int seatId, PredictSession session,
            SortedMap<Integer, ClientSeatStateEvent> seatTable) {
        if (session == null) {
            return null;
        }
        long shipId = seatId; // identity fallback: the certified lane's seat↔wire-id contract
        if (seatTable != null) {
            ClientSeatStateEvent seat = seatTable.get(Integer.valueOf(seatId));
            if (seat != null) {
                if (!seat.hasHull()) {
                    return null; // seat known, no mounted hull: nothing to anchor to
                }
                shipId = seat.shipId();
            }
        }
        long ownId = session.shipId();
        if (shipId == ownId) {
            return plugin.ownShip;
        }
        if (plugin.registryShape) {
            PredictReconcileCombatPlugin.RemoteEntity re =
                    plugin.remoteEntities.get(Long.valueOf(shipId));
            return re != null ? re.ship : null;
        }
        // 1v1: the single foe carries the other certified wire id; anything else stays suppressed.
        boolean isFoeId = (ownId == 1L && shipId == 2L) || (ownId == 2L && shipId == 1L);
        return isFoeId ? plugin.foeShip : null;
    }

    /**
     * One bubble: translucent backing quad + victor10 label, centered ONE ROW ABOVE the seat-name
     * tag row ({@code SeatNameHullBarLane.drawSeatNameTag} geometry: tag bottom =
     * {@code anchorY + r + 3 + 2*rowH + 3}; bubble bottom = tag + rowH + 3). Alpha = 1 through the
     * dwell, then linear to 0 across the fade; the backing scales with it.
     */
    private void drawBubble(ViewportAPI vp, Bubble b, ShipAPI ship) {
        if (b.label == null) {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            if (bubbleTextColor == null) {
                Color text = null;
                try {
                    text = settings.getColor("standardTextColor");
                } catch (Throwable ignore) {
                    // fall through — a color must never cost the lane (the ensureChatColors law)
                }
                bubbleTextColor = text != null ? text : Color.WHITE;
            }
            b.label = settings.createLabel(b.displayText, "graphics/fonts/victor10.fnt");
            b.label.setAlignment(Alignment.LMID);
            bubbleRowH = b.label.computeTextHeight("Ag");
            b.label.getPosition().setSize(b.label.computeTextWidth(b.displayText), bubbleRowH);
            b.label.setColor(bubbleTextColor);
        }
        float alpha = b.age <= DWELL_SECONDS ? 1f
                : Math.max(0f, 1f - (b.age - DWELL_SECONDS) / FADE_SECONDS);
        float r = Math.min(ship.getHullSpec().getShieldSpec().getRadius() / vp.getViewMult(), 150f) * 0.66f;
        float anchorX = (int) vp.convertWorldXtoScreenX(ship.getLocation().x);
        float anchorY = (int) vp.convertWorldYtoScreenY(ship.getLocation().y);
        float w = b.label.getPosition().getWidth();
        float x = anchorX - w / 2f;
        float y = anchorY + r + 3f + 2f * bubbleRowH + 3f + bubbleRowH + 3f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0f, 0f, 0f, 0.55f * alpha);
        GL11.glVertex2f(x - 3f, y - 2f);
        GL11.glVertex2f(x + w + 3f, y - 2f);
        GL11.glVertex2f(x + w + 3f, y + bubbleRowH + 2f);
        GL11.glVertex2f(x - 3f, y + bubbleRowH + 2f);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        b.label.getPosition().setLocation(x, y);
        GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture
        b.label.render(alpha);
        if (!b.shownLogged) {
            b.shownLogged = true;
            PilotWatchLog.info("predict chat-bubble bubble_shown seat=" + b.seatId
                    + " len=" + b.textLen); // length only, never the text
        }
    }

    /** Per-ROUND re-arm at the match-reset rebuild: bubbles/labels die with the round (fresh
     * ships), the dead-latch re-arms (the law — a draw failure must not outlive the
     * reset). */
    void resetForMatchReset() {
        bubbleBySeat.clear();
        bubbleLaneDead = false;
    }

    private static boolean parseOnOff(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException("cc.predict.chatBubbles must be on|off; got '" + raw + "'");
    }
}
