package cc.starsector.client;

import cc.starsector.client.net.ClientSeatStateEvent;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.voice.VoiceClient;
import cc.starsector.client.voice.VoiceClientSettings;
import cc.starsector.client.voice.VoiceMeters;
import cc.starsector.client.voice.VoicePlayback;
import cc.starsector.client.voice.VoicePositions;
import cc.starsector.client.voice.VoicePtt;
import cc.starsector.server.runtime.VoiceFrameCodec;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.SortedMap;

/**
 * W12 VOICE L3 presence lane — the
 * render-thread half of voice chat, extracted whole so the host plugin keeps only thin relays
 * (field + render + advance + input + bind + reset; both hosts are past their WARN tier).
 *
 * <p><b>Positions (D8, {@link #advance}):</b> every live frame publish ONE immutable
 * {@link VoicePositions} snapshot for the audio thread — own BODY = the piloted ship's location
 * (alive OR hulk — the server ranges by the same ghost spot; {@code ownKnown=false}
 * only without a body), camera = the engine viewport centre, and one world anchor per talker
 * inside the 200 ms hold, resolved wire-id → ship by the certified seat chain
 * ({@code ChatBubbleLane.resolveSeatShip} idiom: own ship / {@code remoteEntities} under the
 * registry shape / the 1v1 foe), a despawned anchor carried at its last-known xy per seat. Never
 * blocks, allocates only two small arrays (≤ 8 talkers) per frame. The same pass polls the
 * physical PTT keys while a mode is engaged (F1 lost key-up ⇒ release).
 *
 * <p><b>Glyph + HUD (D12, {@link #render}):</b> a speaker glyph just right of the talker's seat
 * name tag (same tag geometry as {@code SeatNameHullBarLane.drawSeatNameTag}; fog/alive gated
 * like the tag) whose three sound-wave arcs light by tier from the decoded-PCM RMS the audio thread
 * publishes, through a per-seat envelope (instant attack, ~120 ms release) with 3 dB hysteresis so
 * no arc flickers. Tier thresholds are the design's dBFS points converted to the
 * meter's RMS domain (0..32767): −40 dBFS = 328, −28 dBFS = 1304, −16 dBFS = 5193 on; 3 dB lower
 * off (232 / 923 / 3676). Alpha eases 0.6→1.0 with the envelope; global talkers wear a thin ring.
 * The HUD "talking:" row (settings {@code hud.talkRow}) is one victor10 line under the chat band
 * naming ONE active talker (cyan head + a thin 24 px level bar on that talker's envelope) + own
 * PTT state, with the silent-mic and capture-unsupported warnings; every FURTHER talker gets a
 * short {@code name + bar} row stacked ABOVE it (the fixed row never
 * widens, the rtt tail never moves). Drawn with the seat-tag lane's label idiom (no new render
 * subsystem); fail-soft dead-latch per round like every HUD lane.
 *
 * <p><b>PTT relay (D9, {@link #feedInput}):</b> pre-core-controls key events → {@link VoicePtt}
 * (consume only what the law says: Space, while voice is live AND no text lane owns the keyboard).
 * A text lane taking the keyboard mid-press drops every held key ({@code releaseAll}) so a Space
 * whose release the chat box ate can never leave the mic stuck open.
 *
 * <p><b>Settings + seat (D11, {@link #onSessionBound}):</b> {@code cc_client_settings.txt} is read
 * ONCE at session bind (render thread, before the net loop can admit ⇒ no race with the start
 * gate); own seat id (VCDN seatId domain) is refreshed from the seat table every ~1 s.
 */
final class VoicePresenceLane {

    /** Tier ON thresholds, RMS 0..32767: −40 / −28 / −16 dBFS. */
    static final int[] TIER_ON = {328, 1304, 5193};
    /** Where victor10's capitals centre inside its line box, as a fraction of the line height
     * measured from the line TOP: victor10.fnt lineHeight=9, caps yoffset=3 height=5 ⇒
     * (3 + 5/2) / 9 (flight #7 glyph grade; the tag font is fixed, see
     * {@code SeatNameHullBarLane#drawSeatNameTag}). */
    static final float VICTOR10_CAP_CENTRE_FRAC = (3f + 5f / 2f) / 9f;
    /** Tier OFF thresholds: 3 dB below ON (hysteresis). */
    static final int[] TIER_OFF = {232, 923, 3676};
    /** Envelope release time constant (the design's "~120 ms release"). */
    private static final float RELEASE_SECONDS = 0.12f;
    /** Silent-mic warning: consecutive all-zero capture frames while PTT held (~0.5 s at 20 ms). */
    static final int SILENT_WARN_FRAMES = 25;
    private static final int MAX_TALKERS = 8;
    private static final int SEAT_SLOTS = 256;
    private static final int SEAT_REFRESH_FRAMES = 60;

    private final PredictReconcileCombatPlugin plugin;
    private final float[] envelope = new float[SEAT_SLOTS];
    private final int[] litTier = new int[SEAT_SLOTS];
    /** One {@code voice glyph geom} log line per seat slot (grade diagnosis). */
    private final boolean[] glyphGeomLogged = new boolean[SEAT_SLOTS];
    private final long[] anchorScratch = new long[MAX_TALKERS];
    private final float[] xyScratch = new float[MAX_TALKERS * 2];
    private final byte[] kindScratch = new byte[MAX_TALKERS];
    /** Last-known anchor xy per talker SEAT: a talker whose anchor no longer
     * resolves (ship despawned) keeps mixing at its last position, never at full gain centred.
     * Written ONLY from a LIVE body (a hulk's ghost spot is used
     * directly while it exists, never remembered), EXPIRED the frame the id enters its remount
     * window (fresh hull in flight, old body kept as a mirrored wreck — the wreck must never
     * position the respawned talker) and after {@link #LAST_KNOWN_MAX_NANOS} of continuous use. */
    private final float[] lastKnownXY = new float[SEAT_SLOTS * 2];
    private final boolean[] lastKnown = new boolean[SEAT_SLOTS];
    /** First frame the LAST fallback was used for the slot since it last resolved (0 = not in use). */
    private final long[] lastKnownUsedSinceNanos = new long[SEAT_SLOTS];
    /** Ceiling on the last-known fallback: past this the anchor is gone for good (the server
     *  culls the talker anyway) and a frozen spot would only mislead — "d=614 frozen ~45 s" was seen. */
    static final long LAST_KNOWN_MAX_NANOS = 10_000_000_000L;
    /** Per-slot cache of the glyph name's measured half width (computeTextWidth
     *  ran per talker per frame; the name changes at most at a seat re-name). */
    private final String[] glyphNameCache = new String[SEAT_SLOTS];
    private final float[] glyphHalfCache = new float[SEAT_SLOTS];
    /** Talk-row TAIL text → width cache (changes only on a PTT/mic/rtt-hold edge);
     *  the {@code talking:} HEAD has its own pair (changes only on a talker edge). */
    private String rowTextCache;
    private float rowWidthCache;
    private String headTextCache;
    private float headWidthCache;
    /** this frame's active talkers, kept in ascending seat order (parallel arrays,
     *  reused per frame — no per-frame list). */
    private final int[] talkSeat = new int[SEAT_SLOTS];
    private final String[] talkName = new String[SEAT_SLOTS];
    private final float[] talkEnv = new float[SEAT_SLOTS];
    /** The seat the fixed {@code talking:} head names — STICKY while it keeps
     *  talking (a joiner never bumps the head off the fixed row, it takes an extra row above);
     *  -1 = nobody. */
    private int headSeat = -1;
    /** per-slot FULL-name width for the extra talker rows (the glyph cache holds halves). */
    private final String[] rowNameCache = new String[SEAT_SLOTS];
    private final float[] rowNameWidthCache = new float[SEAT_SLOTS];
    /** Extra talker rows above the fixed row are bounded — a 32-player pile-up must not eat the
     *  HUD; the surplus keeps its hull glyph only. */
    static final int EXTRA_TALKER_ROWS_MAX = 6;
    private static final java.awt.Color ROW_COLOR = new java.awt.Color(200, 230, 255, 255);
    /** The talker names in a standout cyan. */
    private static final java.awt.Color TALKER_COLOR = new java.awt.Color(80, 235, 255, 255);
    private final int keyProx;
    private final int keyGlobal;
    private LabelAPI rowLabel;
    /** The rtt numbers shown on the row are held for 250 ms —
     *  the raw per-frame net value flickered 5↔13 and the digit-count change re-justified the
     *  whole right-anchored row every frame. */
    private long rttShownNet = -1L;
    private int rttShownBuf;
    private long rttHoldNanos;
    /** Nanos of the last sidelog sink of the rtt row
     *  (instruments LOG them); one row per ~5 s while shown. */
    private long rttLogNanos;
    private float rowH;
    private boolean laneDead;
    private int seatRefreshCountdown;
    private long lastAdvanceNanos;
    private VoiceClient lastSeen;

    VoicePresenceLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
        // The same sysprop read VoicePtt.fromSysprops() makes: the liveness poll must watch the
        // very keys the law binds (owner overrides included).
        this.keyProx = VoicePtt.intProp("cc.voice.keyProx", VoicePtt.DEFAULT_KEY_PROX);
        this.keyGlobal = VoicePtt.intProp("cc.voice.keyGlobal", VoicePtt.DEFAULT_KEY_GLOBAL);
    }

    // ------------------------------------------------------------------ bind / reset

    /** Session bind (render thread, before the net loop): settings file → voice (D11). */
    void onSessionBound(PredictSession s) {
        VoiceClient v = s == null ? null : s.voice();
        if (v == null) {
            return;
        }
        try {
            VoiceClientSettings settings = cc.starsector.client.voice.VoiceSettingsStore.load(
                    com.fs.starfarer.api.Global.getSettings(), PilotWatchLog::info);
            v.setSettings(settings);
            PilotWatchLog.info("voice settings: " + settings);
        } catch (Throwable t) {
            PilotWatchLog.error("voice settings: load FAILED, defaults stay: " + t, t);
        }
        seatRefreshCountdown = 0;
        lastSeen = v;
    }

    void resetForMatchReset() {
        java.util.Arrays.fill(envelope, 0f);
        java.util.Arrays.fill(litTier, 0);
        java.util.Arrays.fill(lastKnown, false);
        java.util.Arrays.fill(lastKnownUsedSinceNanos, 0L);
        laneDead = false;
        seatRefreshCountdown = 0;
        VoiceClient v = plugin.voice();
        if (v != null) {
            v.ptt().releaseAll();
        }
    }

    // ------------------------------------------------------------------ per frame (D8)

    /** Every live frame: publish own-body/camera/anchor positions; refresh the own seat id. */
    void advance(float amount) {
        VoiceClient v = plugin.voice();
        if (v == null || !v.started()) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastAdvanceNanos == 0L ? amount : (now - lastAdvanceNanos) / 1e9f;
        lastAdvanceNanos = now;
        try {
            if (--seatRefreshCountdown <= 0) {
                seatRefreshCountdown = SEAT_REFRESH_FRAMES;
                v.setOwnSeatId(resolveOwnSeatId());
            }
            pollPttLiveness(v.ptt());
            publishPositions(v, now);
            decayEnvelopes(dt);
        } catch (Throwable t) {
            if (!laneDead) {
                laneDead = true;
                PilotWatchLog.error("voice presence: advance FAILED, lane dark until next round: " + t, t);
            }
        }
    }

    /**
     * Stuck-open mic guard: while a PTT mode is engaged, read the PHYSICAL key state
     * each frame — a key-up swallowed by alt-tab / focus loss / the ESC menu never reaches
     * {@link #feedInput}, so the law would otherwise keep the mic open until the next press+release.
     * Window not active ⇒ every held key counts as up (LWJGL delivers no key events to an inactive
     * window). Idle cost is one atomic read; polling only happens while talking.
     */
    private void pollPttLiveness(VoicePtt ptt) {
        if (ptt.mode() == VoicePtt.MODE_OFF) {
            return;
        }
        if (!Display.isCreated() || !Display.isActive() || !Keyboard.isCreated()) {
            ptt.releaseAll();
            return;
        }
        ptt.pollHeld(Keyboard.isKeyDown(keyProx), Keyboard.isKeyDown(keyGlobal));
    }

    private void publishPositions(VoiceClient v, long now) {
        ShipAPI own = plugin.ownShip;
        // A dead own hull still HAS a location (the hulk), and the server keeps
        // ranging proximity talkers by that ghost spot — so does the mixer. Only a missing body
        // (pre-spawn / spectating) is unknown.
        boolean ownKnown = own != null;
        float ownX = ownKnown ? own.getLocation().x : 0f;
        float ownY = ownKnown ? own.getLocation().y : 0f;
        float camX = ownX;
        float camY = ownY;
        ViewportAPI vp = plugin.engine != null ? plugin.engine.getViewport() : null;
        if (vp != null) {
            Vector2f c = vp.getCenter();
            if (c != null) {
                camX = c.x;
                camY = c.y;
            }
        }
        List<VoiceMeters.Talker> active = v.meters().activeTalkers(now);
        int n = 0;
        for (VoiceMeters.Talker t : active) {
            if (n >= MAX_TALKERS) {
                break;
            }
            long anchor = t.anchorWireId.get();
            int slot = t.seatId & 0xFF;
            // ONE live-first precedence (never a fourth patch):
            //   LIVE  the bound body is alive ⇒ its spot, and the only writer of last-known;
            //   HULK  the bound body is a dead hulk (pre-remount) ⇒ its ghost spot directly
            //         (the server ranges by the same hulk) — never remembered;
            //   remount window (id reset UNBOUND, fresh hull in flight, old body kept as a
            //         mirrored wreck) ⇒ EXPIRE last-known and publish nothing: unity-centred for
            //         the fly-in beats the wreck's spot ("left of the other client, heard right");
            //   LAST  no body and a fresh last-known ⇒ that spot, ≤ LAST_KNOWN_MAX_NANOS;
            //   else nothing (the mixer's unity fallback; the server already culled).
            ShipAPI ship = resolveWireShip(anchor);
            float ax;
            float ay;
            byte kind;
            if (ship != null && ship.isAlive()) {
                kind = VoicePositions.KIND_LIVE;
                ax = ship.getLocation().x;
                ay = ship.getLocation().y;
                lastKnownXY[2 * slot] = ax;
                lastKnownXY[2 * slot + 1] = ay;
                lastKnown[slot] = true;
                lastKnownUsedSinceNanos[slot] = 0L;
            } else if (ship != null) {
                kind = VoicePositions.KIND_HULK;
                ax = ship.getLocation().x;
                ay = ship.getLocation().y;
                lastKnownUsedSinceNanos[slot] = 0L;
            } else if (inRemountWindow(anchor)) {
                lastKnown[slot] = false;
                lastKnownUsedSinceNanos[slot] = 0L;
                continue;
            } else if (lastKnown[slot]) {
                if (lastKnownUsedSinceNanos[slot] == 0L) {
                    lastKnownUsedSinceNanos[slot] = now;
                } else if (now - lastKnownUsedSinceNanos[slot] > LAST_KNOWN_MAX_NANOS) {
                    lastKnown[slot] = false;
                    lastKnownUsedSinceNanos[slot] = 0L;
                    continue;
                }
                kind = VoicePositions.KIND_LAST;
                ax = lastKnownXY[2 * slot];
                ay = lastKnownXY[2 * slot + 1];
            } else {
                continue; // never resolved: the mixer's unity fallback (server already culled)
            }
            anchorScratch[n] = anchor;
            xyScratch[2 * n] = ax;
            xyScratch[2 * n + 1] = ay;
            kindScratch[n] = kind;
            n++;
        }
        long[] ids = new long[n];
        float[] xy = new float[2 * n];
        byte[] kinds = new byte[n];
        System.arraycopy(anchorScratch, 0, ids, 0, n);
        System.arraycopy(xyScratch, 0, xy, 0, 2 * n);
        System.arraycopy(kindScratch, 0, kinds, 0, n);
        v.positions().set(new VoicePositions(ownX, ownY, camX, camY, ownKnown, ids, xy, kinds));
    }

    /** True while a registry id sits between its REMOUNT reset ({@code respawnRemote}: body
     *  released, {@code remounted} armed) and the fresh hull's spawn-on-sight mint (which binds
     *  {@code ship} again). Own id / 1v1 shape: never (own respawn re-fields synchronously). */
    private boolean inRemountWindow(long shipId) {
        PredictSession session = plugin.session;
        if (session == null || !plugin.registryShape || shipId == session.shipId()) {
            return false;
        }
        PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(Long.valueOf(shipId));
        return re != null && re.ship == null && re.remounted;
    }

    private void decayEnvelopes(float dt) {
        if (dt <= 0f) {
            return;
        }
        float k = (float) Math.exp(-dt / RELEASE_SECONDS);
        for (int i = 0; i < SEAT_SLOTS; i++) {
            if (envelope[i] > 0f) {
                envelope[i] *= k;
                if (envelope[i] < 1f) {
                    envelope[i] = 0f;
                }
            }
        }
    }

    /** Wire id → ship, the {@code ChatBubbleLane.resolveSeatShip} chain (own / registry / 1v1 foe). */
    private ShipAPI resolveWireShip(long shipId) {
        PredictSession session = plugin.session;
        if (session == null || shipId < 0L) {
            return null;
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
        boolean isFoeId = (ownId == 1L && shipId == 2L) || (ownId == 2L && shipId == 1L);
        return isFoeId ? plugin.foeShip : null;
    }

    /** Own seat id = the seat-table row carrying our wire id; identity fallback (seat == wire id). */
    private int resolveOwnSeatId() {
        PredictSession session = plugin.session;
        if (session == null) {
            return -1;
        }
        long ownId = session.shipId();
        if (session instanceof PredictReconcileDatagramSession) {
            SortedMap<Integer, ClientSeatStateEvent> table =
                    ((PredictReconcileDatagramSession) session).seatStateTable();
            for (ClientSeatStateEvent e : table.values()) {
                if (e.shipId() == ownId) {
                    return e.seatId();
                }
            }
        }
        return ownId >= 0L && ownId <= 0xFFL ? (int) ownId : -1;
    }

    // ------------------------------------------------------------------ input (D9)

    /** Pre-core-controls key events → the PTT law; consumes only what it says (Space, live). */
    void feedInput(List<InputEventAPI> events) {
        VoiceClient v = plugin.voice();
        if (v == null) {
            return;
        }
        VoicePtt ptt = v.ptt();
        boolean chatOpen = plugin.chat.isBoxOpen();
        boolean pickerOpen = plugin.picker.isPanelOpen();
        if (chatOpen || pickerOpen) {
            ptt.releaseAll(); // a text lane owns the keyboard: never a stuck-open mic
            return;
        }
        if (events == null) {
            return;
        }
        boolean live = v.started() && v.enabled() && plugin.session != null && !plugin.vanillaWindow;
        for (InputEventAPI event : events) {
            // isConsumed() FIRST — getEventValue()/isRepeat() THROW on a consumed event
            if (event == null || event.isConsumed() || !event.isKeyboardEvent()) {
                continue;
            }
            boolean down = event.isKeyDownEvent(); // repeats count as down; the law dedups
            if (!down && !event.isKeyUpEvent()) {
                continue;
            }
            if (ptt.onKey(event.getEventValue(), down, live, false, false)) {
                event.consume();
            }
        }
    }

    // ------------------------------------------------------------------ draw (D12)

    /** UI-coords pass, after the seat tags: glyph per talker + the HUD talking row. */
    void render(ViewportAPI vp) {
        VoiceClient v = plugin.voice();
        if (v == null || !v.started() || laneDead || plugin.engine == null || vp == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            List<VoiceMeters.Talker> active = v.meters().activeTalkers(now);
            int ownSide = plugin.ownSideAtBind >= 0 ? plugin.ownSideAtBind : 0;
            com.fs.starfarer.api.combat.FogOfWarAPI fog = plugin.engine.getFogOfWar(ownSide);
            int talkers = 0;
            int ownSeat = v.ownSeatId();
            boolean ownDrawn = false;
            for (VoiceMeters.Talker t : active) {
                int slot = t.seatId & 0xFF;
                float env = Math.max(envelope[slot], t.level.get());
                envelope[slot] = env;
                int tier = tierFor(slot, env);
                boolean global = (t.flags.get() & VoiceFrameCodec.FLAG_PROXIMITY) == 0;
                ShipAPI ship = resolveWireShip(t.anchorWireId.get());
                String name = ship != null ? plugin.seatHud.seatNameOf(ship) : null;
                if (t.seatId == ownSeat) {
                    name = "you";
                    ownDrawn = true;
                }
                // Insertion in ascending seat order (the meter map iterates by hash)
                // so the row order is deterministic frame to frame.
                int at = talkers;
                while (at > 0 && talkSeat[at - 1] > t.seatId) {
                    talkSeat[at] = talkSeat[at - 1];
                    talkName[at] = talkName[at - 1];
                    talkEnv[at] = talkEnv[at - 1];
                    at--;
                }
                talkSeat[at] = t.seatId;
                talkName[at] = name != null ? name : "seat " + t.seatId;
                talkEnv[at] = env;
                talkers++;
                if (ship != null && plugin.engine.isEntityInPlay(ship) && ship.isAlive()
                        && (fog == null || fog.isVisible(ship))) {
                    drawGlyph(vp, ship, tier, env, global, slot,
                            ship != null ? plugin.seatHud.seatNameOf(ship) : null);
                }
            }
            int pttMode = v.meters().pttMode.get();
            if (pttMode != VoicePtt.MODE_OFF && !ownDrawn && plugin.ownShip != null
                    && plugin.ownShip.isAlive() && plugin.engine.isEntityInPlay(plugin.ownShip)) {
                // own hull while PTT is held (the design's "live mic check"): the arcs light from the
                // CAPTURED level — the same envelope/tier law as a decoded talker.
                int ownSlot = ownSeat & 0xFF;
                float env = Math.max(envelope[ownSlot], v.meters().ownLevel.get());
                envelope[ownSlot] = env;
                int tier = tierFor(ownSlot, env);
                drawGlyph(vp, plugin.ownShip, tier, env, pttMode == VoicePtt.MODE_GLOBAL,
                        ownSlot, plugin.seatHud.seatNameOf(plugin.ownShip));
            }
            if (v.settings().hudTalkRow) {
                drawTalkRow(v, talkers, pttMode);
            }
        } catch (Throwable t) {
            laneDead = true;
            PilotWatchLog.error("voice presence: draw FAILED, lane dark until next round: " + t, t);
        }
    }

    /** Tier with 3 dB hysteresis: climb at ON[n], fall only below OFF[n]. */
    private int tierFor(int slot, float env) {
        int tier = litTier[slot];
        while (tier < TIER_ON.length && env >= TIER_ON[tier]) {
            tier++;
        }
        while (tier > 0 && env < TIER_OFF[tier - 1]) {
            tier--;
        }
        litTier[slot] = tier;
        return tier;
    }

    /** Speaker cone + up to three arcs right of the seat tag; a thin ring for global talkers.
     *  The tag half-width is floored by the seat NAME's measured width (the
     *  flight overlapped a long name — static reading could not explain it, so the geometry is
     *  logged ONCE per seat slot for the next grade) and the pad grew 9 → 14 px, then
     *  6 px further right ⇒ 20 px. */
    private void drawGlyph(ViewportAPI vp, ShipAPI ship, int tier, float env, boolean global,
            int slot, String name) {
        float rowH = plugin.seatHud.seatNameRowH();
        float tagHalf = plugin.seatHud.seatTagHalfWidth(ship);
        float half = tagHalf;
        if (rowH <= 0f) {
            rowH = 12f;
        }
        if (half < 0f) {
            half = 20f;
        }
        float measuredHalf = -1f;
        if (name != null) {
            if (!name.equals(glyphNameCache[slot])) {
                ensureRowLabel(com.fs.starfarer.api.Global.getSettings());
                glyphHalfCache[slot] = rowLabel.computeTextWidth(name) / 2f;
                glyphNameCache[slot] = name;
            }
            measuredHalf = glyphHalfCache[slot];
            if (measuredHalf > half) {
                half = measuredHalf;
            }
        }
        if (!glyphGeomLogged[slot]) {
            glyphGeomLogged[slot] = true;
            // The seat name is player-authored
            // and this line travels in debriefs — printed through the one QUOTE helper (delimiter
            // escaped), never raw, never a bare '…'.
            PilotWatchLog.info("voice glyph geom seat=" + slot + " name="
                    + FallbackAnnounce.quote(name, 64) + " tagHalf="
                    + tagHalf + " measuredHalf=" + measuredHalf + " rowH=" + rowH);
        }
        float r = Math.min(ship.getHullSpec().getShieldSpec().getRadius() / vp.getViewMult(), 150f) * 0.66f;
        float anchorX = (int) vp.convertWorldXtoScreenX(ship.getLocation().x);
        float anchorY = (int) vp.convertWorldYtoScreenY(ship.getLocation().y);
        // LEFT of the tag — the right side is covered by
        // the captain portraits on small ships; same offset magnitude, mirrored.
        float cx = anchorX - half - 20f;
        // The derived row landed a row ABOVE the name — centre on
        // the tag label's actual rendered centre (drawn earlier this frame); the derivation is
        // only the never-tagged fallback.
        float tagCy = plugin.seatHud.seatTagCentreY(ship);
        // The label centre is the LINE-BOX centre,
        // but victor10's capitals sit low in that box (victor10.fnt: lineHeight=9, caps yoffset=3
        // height=5 ⇒ cap centre 5.5 px from the line top vs the box centre at 4.5), and the engine's
        // LMID draw puts the line top at centre + (int)(h/2) (the font renderer's draw origin =
        // text TOP, text extends downward). Centre the glyph on the CAPS: top − 0.611·rowH.
        float cy;
        if (Float.isNaN(tagCy)) {
            cy = anchorY + r + 3f + 2f * rowH + 3f + rowH / 2f;
        } else {
            float textTop = tagCy + (int) (rowH / 2f);
            cy = textTop - rowH * VICTOR10_CAP_CENTRE_FRAC;
        }
        float alpha = 0.6f + 0.4f * Math.min(1f, env / TIER_ON[TIER_ON.length - 1]);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1f);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        GL11.glBegin(GL11.GL_TRIANGLES); // the cone
        GL11.glVertex2f(cx - 5f, cy);
        GL11.glVertex2f(cx - 1f, cy + 4f);
        GL11.glVertex2f(cx - 1f, cy - 4f);
        GL11.glEnd();
        for (int arc = 0; arc < tier; arc++) {
            float rad = 3f + 3f * arc;
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int i = 0; i <= 6; i++) {
                double a = Math.toRadians(-50 + 100 * i / 6.0);
                GL11.glVertex2f(cx - 1f + (float) (rad * Math.cos(a)), cy + (float) (rad * Math.sin(a)));
            }
            GL11.glEnd();
        }
        if (global) {
            GL11.glColor4f(1f, 1f, 1f, alpha * 0.6f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16.0;
                GL11.glVertex2f(cx + 1f + (float) (13 * Math.cos(a)), cy + (float) (13 * Math.sin(a)));
            }
            GL11.glEnd();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A RIGHT-anchored piece is sized by the widest same-shape text (every digit
     *  → '8') so a 1→2 digit change never shifts it; measured only on a text change. */
    private float widestSameShapeWidth(String text) {
        char[] wide = text.toCharArray();
        for (int i = 0; i < wide.length; i++) {
            if (wide[i] >= '0' && wide[i] <= '9') {
                wide[i] = '8';
            }
        }
        return Math.max(rowLabel.computeTextWidth(text), rowLabel.computeTextWidth(new String(wide)));
    }

    /** The shared victor10 label (talk row text + glyph name measurement), created once. */
    private void ensureRowLabel(com.fs.starfarer.api.SettingsAPI settings) {
        if (rowLabel == null) {
            rowLabel = settings.createLabel("talking:", "graphics/fonts/victor10.fnt");
            rowLabel.setAlignment(Alignment.LMID);
            rowH = rowLabel.computeTextHeight("Ag");
            rowLabel.setColor(ROW_COLOR);
        }
    }

    /** One text line bottom-right; hidden when there is nothing to say. The 24 px level bar
     *  sits DIRECTLY right of the {@code talking: <name>} head and the
     *  rest of the row (PTT/mic/rtt = the tail) stays right-anchored — the head grows the row to
     *  the LEFT, the rtt numbers never move (before: bar reserved at the far right ⇒ the whole
     *  row jumped 32 px left whenever someone talked). The head
     *  names ONE talker (sticky — {@link #headSeat}); every further talker is its own short
     *  {@code name + bar} row stacked ABOVE this one (the shared stacker claims upward), capped at
     *  {@link #EXTRA_TALKER_ROWS_MAX}; the bar on each row is THAT talker's envelope.
     *  Talker names in {@link #TALKER_COLOR}, the tail in {@link #ROW_COLOR}. */
    private void drawTalkRow(VoiceClient v, int talkers, int pttMode) {
        VoiceMeters m = v.meters();
        int headIdx = -1;
        if (talkers > 0) {
            headIdx = 0;
            for (int i = 0; i < talkers; i++) {
                if (talkSeat[i] == headSeat) {
                    headIdx = i;
                    break;
                }
            }
            headSeat = talkSeat[headIdx];
        } else {
            headSeat = -1;
        }
        String head = headIdx >= 0 ? "talking: " + talkName[headIdx] : null;
        float peak = headIdx >= 0 ? talkEnv[headIdx] : 0f;
        StringBuilder line = new StringBuilder(96);
        if (pttMode == VoicePtt.MODE_PROXIMITY) {
            line.append(line.length() > 0 ? " | " : "").append("PTT prox");
        } else if (pttMode == VoicePtt.MODE_GLOBAL) {
            line.append(line.length() > 0 ? " | " : "").append("PTT global");
        }
        int cap = m.captureState.get();
        if (cap == VoiceMeters.CAPTURE_UNSUPPORTED) {
            line.append(line.length() > 0 ? " | " : "").append("mic: no usable capture format");
        } else if (cap == VoiceMeters.CAPTURE_LOST) {
            line.append(line.length() > 0 ? " | " : "").append("mic lost (device removed?)");
        } else if (pttMode != VoicePtt.MODE_OFF && m.silentCaptureFrames.get() >= SILENT_WARN_FRAMES) {
            line.append(line.length() > 0 ? " | " : "").append("mic silent?");
        } else if (pttMode != VoicePtt.MODE_OFF && cap == VoiceMeters.CAPTURE_OFF) {
            line.append(line.length() > 0 ? " | " : "").append("mic off");
        }
        // The echo round trip, shown within 1.5 s of the last stamp — net half
        // (wire rtt at the envelope seq) + buf half (the own stream's
        // POST-pop residual frames × frameMs + line depth — the effective depth, set in
        // VoicePlayback.mix; the policy target under-read a held stream by 30 ms).
        // The row is its OWN setting ({@code voice.rttStats};
        // default ON, decoupled from echo — it shows regardless of echo). With no fresh echo stamp
        // it derives from what the client already has (prefer-client-side): the session's
        // snap-echo wire rtt + the deepest active talker's jitter depth (the floor when nobody is
        // talking). The "echo off" hint stays REVERTED.
        long stamp = m.echoStampNanos.get();
        long nowNanos = System.nanoTime();
        boolean echoFresh = stamp >= 0L && nowNanos - stamp <= 1_500_000_000L;
        if (echoFresh || v.settings().rttStats) {
            if (rttShownNet < 0L || nowNanos - rttHoldNanos >= 250_000_000L) {
                if (echoFresh) {
                    rttShownNet = m.echoRttNanos.get() / 1_000_000L;
                    rttShownBuf = m.echoBufMs.get();
                } else {
                    double ping = plugin.session == null ? Double.NaN : plugin.session.netPingMillis();
                    rttShownNet = Double.isNaN(ping) ? 0L : Math.round(ping);
                    int talkerBuf = m.talkerBufMs.get();
                    rttShownBuf = talkerBuf > 0 ? talkerBuf
                            : VoicePlayback.bufFloorMs(v.sheet(), m.lineDepthMs.get());
                }
                rttHoldNanos = nowNanos;
            }
            long net = rttShownNet;
            int buf = rttShownBuf;
            line.append(line.length() > 0 ? " | " : "").append("voice rtt ").append(net + buf)
                    .append(" ms (net ").append(net).append(" + buf ").append(buf).append(')');
            // The same figures the HUD shows, sunk to the sidelog every ~5 s (numeric
            // only; src= names which half fed net — a fresh echo stamp or the derived path).
            if (nowNanos - rttLogNanos >= 5_000_000_000L) {
                rttLogNanos = nowNanos;
                PilotWatchLog.info("voice rtt total_ms=" + (net + buf) + " net_ms=" + net
                        + " buf_ms=" + buf + " src=" + (echoFresh ? "echo" : "derived")
                        + " talkers=" + talkers);
            }
        } else {
            rttShownNet = -1L;
        }
        if (head == null && line.length() == 0) {
            return;
        }
        com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
        ensureRowLabel(settings);
        // The tail opens with the separator only when a head precedes it (the bar sits between).
        String tail = line.length() == 0 ? null : head != null ? "| " + line : line.toString();
        if (head != null && !head.equals(headTextCache)) {
            headWidthCache = widestSameShapeWidth(head);
            headTextCache = head;
        }
        if (tail != null && !tail.equals(rowTextCache)) {
            rowWidthCache = widestSameShapeWidth(tail);
            rowTextCache = tail;
        }
        float headW = head != null ? headWidthCache : 0f;
        float tailW = tail != null ? rowWidthCache : 0f;
        float barSpan = head != null ? 8f + 24f + 8f : 0f; // pad + bar + pad, right of the head
        // BOTTOM-RIGHT (UI y-up; the ListPickerLane right-edge idiom).
        float x = settings.getScreenWidth() - 25f - headW - barSpan - tailW;
        float y = plugin.hudReadout.nextY(rowH); // Shared bottom-up stacker (net row below, sim row above)
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture
        if (head != null) {
            rowLabel.setColor(TALKER_COLOR);
            rowLabel.setText(head);
            rowLabel.getPosition().setSize(headW, rowH);
            rowLabel.getPosition().setLocation(x, y);
            rowLabel.render(1f);
        }
        if (tail != null) {
            rowLabel.setColor(ROW_COLOR);
            rowLabel.setText(tail);
            rowLabel.getPosition().setSize(tailW, rowH);
            rowLabel.getPosition().setLocation(x + headW + barSpan, y);
            rowLabel.render(1f);
        }
        if (head != null) {
            drawLevelBar(x + headW + 8f, y + rowH / 2f - 2f, peak);
        }
        // The extra talkers, one short right-anchored row each, stacked ABOVE.
        int extra = 0;
        for (int i = 0; i < talkers && extra < EXTRA_TALKER_ROWS_MAX; i++) {
            if (i == headIdx) {
                continue;
            }
            extra++;
            int slot = talkSeat[i] & 0xFF;
            String name = talkName[i];
            if (!name.equals(rowNameCache[slot])) {
                rowNameWidthCache[slot] = rowLabel.computeTextWidth(name);
                rowNameCache[slot] = name;
            }
            float nameW = rowNameWidthCache[slot];
            float rx = settings.getScreenWidth() - 25f - nameW - barSpan;
            float ry = plugin.hudReadout.nextY(rowH);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            rowLabel.setColor(TALKER_COLOR);
            rowLabel.setText(name);
            rowLabel.getPosition().setSize(nameW, rowH);
            rowLabel.getPosition().setLocation(rx, ry);
            rowLabel.render(1f);
            drawLevelBar(rx + nameW + 8f, ry + rowH / 2f - 2f, talkEnv[i]);
        }
    }

    /** The thin 24×4 px level bar (outline + fill on the envelope vs the top tier threshold);
     *  leaves GL_TEXTURE_2D enabled for the next label. */
    private void drawLevelBar(float bx, float by, float env) {
        float fill = 24f * Math.min(1f, env / TIER_ON[TIER_ON.length - 1]);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 0.35f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(bx, by);
        GL11.glVertex2f(bx + 24f, by);
        GL11.glVertex2f(bx + 24f, by + 4f);
        GL11.glVertex2f(bx, by + 4f);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 0.9f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(bx, by);
        GL11.glVertex2f(bx + fill, by);
        GL11.glVertex2f(bx + fill, by + 4f);
        GL11.glVertex2f(bx, by + 4f);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
