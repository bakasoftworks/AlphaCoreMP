package cc.starsector.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;

/**
 * OBSERVER free camera: under
 * {@link ObserverMode#ON} the window has no own hull (the engine's invisible Command Shuttle pod
 * is the "player ship"), so the vanilla camera would sit on an empty deploy zone. This lane takes
 * the viewport over every frame — {@code setExternalControl(true)} + {@code set(llx,lly,w,h)} —
 * which the engine honours by skipping its own per-frame {@code setViewport} and projecting from
 * the viewport's rect (the mouse
 * offset is replaced by {@code getCenter()}); {@code CombatViewport.set}
 * derives {@code viewMult} itself, so no zoom mirror is owed.
 *
 * <p>Keys are LWJGL-POLLED ({@code Keyboard.isKeyDown}, the {@code PilotIntentCombatPlugin}
 * held-controls idiom — non-consuming, so nothing here fights the engine), muted while the chat
 * box is open: WASD / arrows pan (∝ visible width; Shift ×3), {@code +}/{@code -} zoom, {@code F}
 * cycles follow across the wire-registered ships (own side first, ascending id — the map is a
 * {@code TreeMap} — then the enemy, then free), {@code Home} = whole-map overview.
 * Pan/zoom use WALL time, so the observer can look around a paused match.
 *
 * <p><b>Follow is sticky:</b> a stray 1.3 s {@code S} in
 * the observer window (focus slip while the operator flew their own client) used to RELEASE the
 * follow, and the window then sat on one spot for the rest of the match ("weirdly stuck").
 * Now a pan while following only adds a look-around OFFSET (clamped ∝ visible width) that
 * glides back to the target once the key is up; the follow is dropped ONLY by a deliberate
 * {@code F} wrap-to-free or {@code Home}. Auto-follow = the LOWEST-id alive own-side ship (seat 1
 * = the human; the station is id 4) and it re-acquires on target death and switches back when a
 * lower id re-binds (respawn), until {@code F} picks a target by hand. Start zoom = 3000 su
 * ({@code START_VIS_W}): the operator zoomed 1280→3050 by hand in the flight that found this and left it there.
 * <b>Follow knob:</b> {@code -Dcc.observer.follow} =
 * {@link ObserverMode#FOLLOW} picks WHAT auto-follow acquires — {@code seat} (the above),
 * {@code engagement} (the enemy nearest any ally, sticky until its hulk is gone: the ownerless
 * faction-compare leg) or {@code enemy} (lowest-id enemy); see {@link #autoFollow}.
 *
 * <p>Fog: the pod's side (owner 0) only ever sees its sensor bubble; the lane reveals the visible
 * rect around the camera every frame through {@code getFogOfWar(0).revealAroundPoint(this, …)}
 * (source = any map key), so the observer renders SERVER
 * TRUTH everywhere — that IS the owner's desync eye. One HUD row (top-left,
 * the {@link HudReadoutLane} label idiom) names the posture + follow target + its hull/flux
 * readout + the key map.
 *
 * <p>Presentation only, fail-soft dead-latch (a camera must never take the frame down); B-legal
 * (engine API + LWJGL). Own file per NEW-LANE-LAW (PRCP is past its WarnNb); the plugin splices
 * are {@code advance} (above the no-feed return — a free camera needs no feed) and
 * {@code renderInUICoords}.
 */
final class ObserverCameraLane {
    /** Pan speed as a fraction of the visible width per second (Shift multiplies). */
    private static final float PAN_FRAC_PER_SEC = 0.75f;
    private static final float FAST_MULT = 3f;
    /** Zoom rate in natural-log units per second (≈ ×5 per second held). */
    private static final float ZOOM_LN_PER_SEC = 1.6f;
    private static final float MIN_VIS_W = 600f;
    private static final float MAX_VIS_W = 40000f;
    private static final float DEFAULT_VIS_W = 4000f;
    /** Visible width at arm (su): an operator's own hand zoom in a recorded flight (1280 → 3050). */
    private static final float START_VIS_W = 3000f;
    /** Look-around offset glide-back rate (1/s, exponential: ≈95 % of the way back in 1 s). */
    private static final float OFFSET_RETURN_PER_SEC = 3f;
    /** Look-around offset clamp as a multiple of the visible width (the target stays near). */
    private static final float OFFSET_MAX_FRAC = 1.5f;
    /** Wall dt ceiling per frame (a hitch must not fling the camera). */
    private static final float MAX_DT = 0.1f;
    private static final java.awt.Color ROW_COLOR = new java.awt.Color(255, 220, 120, 255);
    private static final String KEY_MAP = "WASD/arrows look (glides back; Shift fast)  +/- zoom  F follow  Home overview";

    private final PredictReconcileCombatPlugin plugin;
    private boolean armed;
    private boolean laneDead;
    private boolean fogDead;
    /** False only after a DELIBERATE free (F wrap-to-free / Home); true = (re)acquire a target. */
    private boolean wantFollow = true;
    /** True while the follow target was chosen automatically (lowest alive own-side id wins). */
    private boolean autoPick = true;
    /** Look-around offset from the followed ship (su); glides back to 0 once no pan key is held. */
    private float offX;
    private float offY;
    private boolean panHeld;
    private float cx;
    private float cy;
    private float visW = DEFAULT_VIS_W;
    private float aspect = 9f / 16f;
    /** Followed wire entity id; 0 = free camera. */
    private long followId;
    private String followLabel = "free";
    /** The HUD twin of {@link #followLabel} ({@link #followText} display form; {@link #render} draws it). */
    private String followHud = "free";
    private boolean prevF;
    private boolean prevHome;
    private long lastNanos = Long.MIN_VALUE;
    private LabelAPI rowLabel;
    private float rowH;

    ObserverCameraLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Per live frame from {@code advanceBody}, ABOVE the no-feed early return. */
    void advance(float amount) {
        if (!ObserverMode.ON || laneDead) {
            return;
        }
        try {
            CombatEngineAPI engine = plugin.engine;
            if (engine == null) {
                return;
            }
            ViewportAPI vp = engine.getViewport();
            if (vp == null) {
                return;
            }
            if (!armed) {
                arm(vp);
            }
            float dt = wallDt();
            pollKeys(engine, dt);
            if (wantFollow && (followId == 0L || autoPick)) {
                autoFollow();
            }
            if (followId != 0L) {
                applyFollow(dt);
            }
            clampToMap(engine);
            float visH = visW * aspect;
            vp.setExternalControl(true);
            vp.set(cx - visW / 2f, cy - visH / 2f, visW, visH);
            revealFog(engine, visW, visH);
        } catch (Throwable t) {
            laneDead = true;
            PilotWatchLog.error("observer camera: lane dead (fail-soft)", t);
        }
    }

    private void arm(ViewportAPI vp) {
        armed = true;
        float w = vp.getVisibleWidth();
        float h = vp.getVisibleHeight();
        if (w > 0f && h > 0f && !Float.isNaN(w) && !Float.isNaN(h)) {
            aspect = h / w;
        } else {
            com.fs.starfarer.api.SettingsAPI s = com.fs.starfarer.api.Global.getSettings();
            aspect = s.getScreenHeightPixels() / Math.max(1f, s.getScreenWidthPixels());
        }
        org.lwjgl.util.vector.Vector2f c = vp.getCenter();
        if (c != null && !Float.isNaN(c.x) && !Float.isNaN(c.y)) {
            cx = c.x;
            cy = c.y;
        }
        visW = clampW(START_VIS_W);
        PilotWatchLog.info("observer camera ARMED (external viewport control): center=" + fmt(cx) + ","
                + fmt(cy) + " visW=" + fmt(visW) + " (engine 1:1 width " + fmt(w) + ") aspect=" + fmt(aspect)
                + " follow=" + ObserverMode.FOLLOW + " (cc.observer.follow); keys: " + KEY_MAP);
    }

    private float wallDt() {
        long now = System.nanoTime();
        float dt = lastNanos == Long.MIN_VALUE ? 0f : (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        return Math.max(0f, Math.min(MAX_DT, dt));
    }

    private void pollKeys(CombatEngineAPI engine, float dt) {
        if (!Keyboard.isCreated()) {
            return;
        }
        boolean f = Keyboard.isKeyDown(Keyboard.KEY_F);
        boolean home = Keyboard.isKeyDown(Keyboard.KEY_HOME);
        boolean fEdge = f && !prevF;
        boolean homeEdge = home && !prevHome;
        prevF = f;
        prevHome = home;
        if (plugin.chat.isBoxOpen()) {
            return; // typed chat never steers the camera
        }
        float dx = 0f;
        float dy = 0f;
        if (Keyboard.isKeyDown(Keyboard.KEY_W) || Keyboard.isKeyDown(Keyboard.KEY_UP)) {
            dy += 1f;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_S) || Keyboard.isKeyDown(Keyboard.KEY_DOWN)) {
            dy -= 1f;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_D) || Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
            dx += 1f;
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_A) || Keyboard.isKeyDown(Keyboard.KEY_LEFT)) {
            dx -= 1f;
        }
        if (dx != 0f || dy != 0f) {
            boolean fast = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            float step = visW * PAN_FRAC_PER_SEC * dt * (fast ? FAST_MULT : 1f);
            if (followId != 0L) {
                // Look-around: an offset from the target, never a release.
                float lim = visW * OFFSET_MAX_FRAC;
                offX = Math.max(-lim, Math.min(lim, offX + dx * step));
                offY = Math.max(-lim, Math.min(lim, offY + dy * step));
                if (!panHeld) {
                    PilotWatchLog.info("observer camera pan while following " + followLabel
                            + " (look-around offset, glides back on release; F cycles, Home frees)");
                }
            } else {
                cx += dx * step;
                cy += dy * step;
            }
            panHeld = true;
        } else {
            panHeld = false;
        }
        boolean zoomIn = Keyboard.isKeyDown(Keyboard.KEY_EQUALS) || Keyboard.isKeyDown(Keyboard.KEY_ADD);
        boolean zoomOut = Keyboard.isKeyDown(Keyboard.KEY_MINUS) || Keyboard.isKeyDown(Keyboard.KEY_SUBTRACT);
        if (zoomIn != zoomOut) {
            visW = clampW(visW * (float) Math.exp((zoomIn ? -1.0 : 1.0) * ZOOM_LN_PER_SEC * dt));
        }
        if (fEdge) {
            cycleFollow();
            autoPick = false;              // a hand-picked target is kept until it dies
            wantFollow = followId != 0L;   // the wrap-to-free is the deliberate free
        }
        if (homeEdge) {
            releaseFollow("home");
            wantFollow = false;
            autoPick = false;
            cx = 0f;
            cy = 0f;
            float mapW = engine.getMapWidth();
            float mapH = engine.getMapHeight();
            float fitW = Math.max(mapW, aspect > 0f ? mapH / aspect : mapW) * 1.05f;
            visW = clampW(fitW > 0f ? fitW : DEFAULT_VIS_W);
        }
    }

    /**
     * Auto-follow dispatcher on
     * {@link ObserverMode#FOLLOW}. {@code seat} = the LOWEST-id alive own-side ship
     * ({@code remoteEntities} is a {@code TreeMap}, so the first hit wins): seat 1 is the human,
     * the fortress station binds as id 4. Runs every frame while {@link #autoPick}, so a death
     * re-acquires and a lower id re-binding (respawn) takes the camera back; a no-op when the pick
     * is unchanged. {@code enemy} = the same law on side 1 (an ally while no enemy is followable).
     * {@code engagement} = the enemy nearest ANY ally, then STICKY: while the followed target is
     * an enemy no re-pick happens (a nearest-per-frame pick would flicker between hulls); the
     * hulk linger holds it dead, the wire REMOVE edge releases it ({@link #applyFollow}
     * "target gone") and the NEXT engagement is picked the following frame — exactly the seat
     * mode's re-acquire rule. An ally is followed only while no enemy is followable (pre-wave),
     * and that fallback IS re-evaluated so the first enemy takes the camera. F / Home unchanged.
     */
    private void autoFollow() {
        if (lingeringOnHulk()) {
            return; // S6: the hulk holds the camera; only a press (F / Home) or its removal moves on
        }
        PredictReconcileCombatPlugin.RemoteEntity pick;
        if (ObserverMode.FOLLOW_ENGAGEMENT.equals(ObserverMode.FOLLOW)) {
            PredictReconcileCombatPlugin.RemoteEntity cur = plugin.remoteEntities.get(Long.valueOf(followId));
            if (cur != null && cur.side == 1 && followable(cur)) {
                return; // sticky on the engaged enemy until it dies (linger) and is removed
            }
            pick = nearestEnemyToAnyAlly();
            if (pick == null) {
                pick = lowestFollowable(0);
            }
        } else if (ObserverMode.FOLLOW_ENEMY.equals(ObserverMode.FOLLOW)) {
            pick = lowestFollowable(1);
            if (pick == null) {
                pick = lowestFollowable(0);
            }
        } else {
            pick = lowestFollowable(0);
            if (pick == null) {
                // The follow DEFAULT: own side ABSENT (pre-wave, or every
                // ally dead once the S6 hulk linger released) => follow the live side-1 action instead
                // of drifting free — the on-disk negative control (a frame set with no
                // orange rings) was exactly this branch framing nothing. Own-side-first is unchanged.
                pick = lowestFollowable(1);
            }
        }
        if (pick != null && pick.entityId != followId) {
            setFollow(pick);
            autoPick = true;
        }
    }

    /** Lowest-id followable ship on {@code side} (TreeMap order), or {@code null}. */
    private PredictReconcileCombatPlugin.RemoteEntity lowestFollowable(int side) {
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.side == side && followable(re)) {
                return re;
            }
        }
        return null;
    }

    /**
     * The followable enemy whose engine body is nearest (squared distance) to ANY followable ally,
     * or {@code null} when either side is empty. O(enemies × allies) on the render thread, but only
     * on frames with no engaged enemy (the sticky rule above) — a handful of hulls.
     */
    private PredictReconcileCombatPlugin.RemoteEntity nearestEnemyToAnyAlly() {
        PredictReconcileCombatPlugin.RemoteEntity best = null;
        float bestD2 = Float.MAX_VALUE;
        for (PredictReconcileCombatPlugin.RemoteEntity foe : plugin.remoteEntities.values()) {
            if (foe.side != 1 || !followable(foe)) {
                continue;
            }
            org.lwjgl.util.vector.Vector2f fl = foe.ship.getLocation();
            if (fl == null) {
                continue;
            }
            for (PredictReconcileCombatPlugin.RemoteEntity ally : plugin.remoteEntities.values()) {
                if (ally.side != 0 || !followable(ally)) {
                    continue;
                }
                org.lwjgl.util.vector.Vector2f al = ally.ship.getLocation();
                if (al == null) {
                    continue;
                }
                float dx = fl.x - al.x;
                float dy = fl.y - al.y;
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) { // NaN never wins
                    bestD2 = d2;
                    best = foe;
                }
            }
        }
        return best;
    }

    /** free → own side (ascending id) → enemy side → free. */
    private void cycleFollow() {
        java.util.ArrayList<PredictReconcileCombatPlugin.RemoteEntity> order = new java.util.ArrayList<>();
        for (int side = 0; side <= 1; side++) {
            for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                if (re.side == side && followable(re)) {
                    order.add(re);
                }
            }
        }
        if (order.isEmpty()) {
            releaseFollow("cycle-empty");
            return;
        }
        int at = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).entityId == followId) {
                at = i;
                break;
            }
        }
        int next = at + 1;
        if (next >= order.size()) {
            releaseFollow("cycle-wrap");
            return;
        }
        setFollow(order.get(next));
    }

    /** PICK predicate (auto-pick + F cycle): a LIVING ship only — a hulk is never picked. */
    private static boolean followable(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI ship = re.ship;
        return ship != null && !re.killed && ship.isAlive();
    }

    /**
     * HOLD predicate (the observer "immediately un-track[s]
     * the ship as soon as it dies ... it should linger at the hulk and only change targets if
     * user presses something"): an ALREADY-followed ship keeps the camera while its engine body
     * is still in play — dead or alive — so the hulk drifts under the camera through its whole
     * life (fracture, dissolve, fade) until the wire REMOVE edge takes it; only F / Home or that
     * removal changes target. Split from {@link #followable}: picking still wants a living ship.
     */
    private boolean holdable(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI ship = re.ship;
        return ship != null && plugin.engine != null && plugin.engine.isEntityInPlay(ship);
    }

    /** True while the followed target is a hulk still in play (S6 linger) — auto-pick stands down. */
    private boolean lingeringOnHulk() {
        if (followId == 0L) {
            return false;
        }
        PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(Long.valueOf(followId));
        return re != null && !followable(re) && holdable(re);
    }

    private void setFollow(PredictReconcileCombatPlugin.RemoteEntity re) {
        followId = re.entityId;
        offX = 0f;
        offY = 0f;
        ShipAPI ship = re.ship;
        String name = ship != null ? ship.getName() : null;
        String hull = ship != null && ship.getHullSpec() != null ? ship.getHullSpec().getHullName() : "?";
        followLabel = followText(re.side, re.entityId, hull, name, false);
        followHud = followText(re.side, re.entityId, hull, name, true);
        PilotWatchLog.info("observer camera follow -> " + followLabel);
    }

    private void releaseFollow(String why) {
        if (followId != 0L) {
            PilotWatchLog.info("observer camera follow released (" + why + ") at " + fmt(cx) + "," + fmt(cy));
        }
        followId = 0L;
        followLabel = "free";
        followHud = "free";
        offX = 0f;
        offY = 0f;
    }

    /**
     * The follow label, built once per follow change.
     * LOG form ({@code hud} false): the name rides {@link FallbackAnnounce#quote} (the delimiter
     * law). HUD form: {@link FallbackAnnounce#sanitize} (printable ASCII, cap 24) between plain
     * apostrophes (char literals: the peer-text quote gate scans this class), so a wave name with an
     * apostrophe draws unescaped (lens kit-log-peer-text F1). Null/empty name = no name part;
     * {@code side} 0 = ally, else enemy; {@code entityId} = the followed wire id (> 0).
     */
    static String followText(int side, long entityId, String hull, String name, boolean hud) {
        String head = (side == 0 ? "ally " : "enemy ") + "#" + entityId + " " + hull;
        if (name == null || name.isEmpty()) {
            return head;
        }
        return hud ? head + " " + '\'' + FallbackAnnounce.sanitize(name, 24) + '\''
                : head + " " + FallbackAnnounce.quote(name, 24);
    }

    /** Camera = target + look-around offset; the offset glides back once no pan key is held. */
    private void applyFollow(float dt) {
        PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(Long.valueOf(followId));
        if (re == null || !holdable(re)) { // S6: a dead-but-in-play hulk still holds
            releaseFollow("target gone"); // wantFollow stays true -> re-acquired next frame
            return;
        }
        if (!panHeld && (offX != 0f || offY != 0f)) {
            float k = (float) Math.exp(-OFFSET_RETURN_PER_SEC * dt);
            offX *= k;
            offY *= k;
            if (Math.abs(offX) < 1f && Math.abs(offY) < 1f) {
                offX = 0f;
                offY = 0f;
            }
        }
        org.lwjgl.util.vector.Vector2f loc = re.ship.getLocation();
        if (loc != null && !Float.isNaN(loc.x) && !Float.isNaN(loc.y)) {
            cx = loc.x + offX;
            cy = loc.y + offY;
        }
    }

    private void clampToMap(CombatEngineAPI engine) {
        float mapW = engine.getMapWidth();
        float mapH = engine.getMapHeight();
        if (mapW > 0f && mapH > 0f) {
            float limX = mapW / 2f + visW / 4f;
            float limY = mapH / 2f + visW * aspect / 4f;
            cx = Math.max(-limX, Math.min(limX, cx));
            cy = Math.max(-limY, Math.min(limY, cy));
        }
    }

    private void revealFog(CombatEngineAPI engine, float visW, float visH) {
        if (fogDead) {
            return;
        }
        try {
            FogOfWarAPI fog = engine.getFogOfWar(0);
            if (fog != null) {
                fog.revealAroundPoint(this, cx, cy, Math.max(visW, visH) * 0.75f);
            }
        } catch (Throwable t) {
            fogDead = true;
            PilotWatchLog.info("observer camera: fog reveal dead (fail-soft): " + t);
        }
    }

    private static float clampW(float w) {
        if (Float.isNaN(w)) {
            return DEFAULT_VIS_W;
        }
        return Math.max(MIN_VIS_W, Math.min(MAX_VIS_W, w));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /**
     * Followed-ship readout: hull % + flux % (+
     * OVERLOAD) of the followed wire entity's engine ship, appended to the top-left row. The
     * vanilla bottom-left readout stays the pod's own (engine-owned) — this
     * row is the observer's only view of the ship it is watching. Empty when free / no ship.
     */
    private String followReadout() {
        if (followId == 0L) {
            return "";
        }
        PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(Long.valueOf(followId));
        ShipAPI ship = re != null ? re.ship : null;
        if (ship == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(40);
        if (!ship.isAlive()) {
            sb.append("  HULK"); // S6 linger: the camera is holding a dead ship's body
        }
        sb.append("  hull ").append(Math.round(clamp01(ship.getHullLevel()) * 100f)).append('%');
        sb.append("  flux ").append(Math.round(clamp01(ship.getFluxLevel()) * 100f)).append('%');
        com.fs.starfarer.api.combat.FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux != null && flux.isOverloaded()) {
            sb.append(" OVERLOAD");
        }
        return sb.toString();
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0f : Math.max(0f, Math.min(1f, v));
    }

    /** One top-left HUD row (UI coords, from {@code renderInUICoordsBody}). */
    void render(ViewportAPI viewport) {
        if (!ObserverMode.ON || laneDead || !armed) {
            return;
        }
        try {
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            if (rowLabel == null) {
                rowLabel = settings.createLabel("observer", "graphics/fonts/victor10.fnt");
                rowLabel.setAlignment(Alignment.LMID);
                rowH = rowLabel.computeTextHeight("Ag");
            }
            String text = "OBSERVER  follow: " + followHud + followReadout() + "  view "
                    + Math.round(visW) + " su" + "  |  " + KEY_MAP;
            float w = rowLabel.computeTextWidth(text);
            rowLabel.setText(text);
            rowLabel.setColor(ROW_COLOR);
            rowLabel.getPosition().setSize(w, rowH);
            rowLabel.getPosition().setLocation(25f, settings.getScreenHeight() - 25f - rowH);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture
            rowLabel.render(1f);
        } catch (Throwable t) {
            laneDead = true;
            PilotWatchLog.info("observer camera: HUD row dead (fail-soft): " + t);
        }
    }
}
