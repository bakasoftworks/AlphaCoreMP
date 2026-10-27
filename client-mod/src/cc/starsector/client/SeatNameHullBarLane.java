package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.IdentityHashMap;

/**
 * Seat-name tags + the debug server-hull-bar overlay — the whole presentation concern
 * of names and hull bars, carved out of
 * {@link PredictReconcileCombatPlugin} whole (a whole-class extraction; the
 * deferred-refactors PRCP whole-class law). The plugin keeps only the thin touchpoints:
 * the {@code renderInUICoords} hook (relay), the protected {@code drawSeatNameTag}
 * draw-reach seam (RenderCorpseGuardSmoke overrides it cross-package — every
 * tag draws back through it), the bind/reset/drain call sites, and the net-thread
 * subscription refs. State + draw laws live HERE; plugin state is read through the
 * back-ref (the ClockServoLane idiom). Both render passes are fail-soft with their own
 * dead-latch: a draw Throwable kills that pass (tags: for the ROUND; bars: for the run),
 * never the frame.
 */
final class SeatNameHullBarLane {

    private final PredictReconcileCombatPlugin plugin;

    SeatNameHullBarLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Seat DISPLAY NAMES, knobs {@code cc.match.seat1Name}/{@code cc.match.seat2Name}
     * (config-carried, BOTH JVMs: {@code RealEngineDemoRoster} deploys the server roster under the SAME
     * values, so the parity walker's {@code ship.getName} row agrees across sides). Applied at ship-bind
     * time through the engine's OWN name slot ({@code ShipAPI.setName}); vanilla then surfaces the name
     * in the own-ship status box and the target/hover reticle with zero custom drawing (evidence).
     * Unset/blank ⇒ zero {@code setName} calls anywhere (byte-identical). Invalid
     * (>{@value #SEAT_NAME_MAX} chars, control chars) THROWS at class load, the knob discipline.
     */
    private static final int SEAT_NAME_MAX = 24;
    // Literal System.getProperty at the read site — the scan idiom.
    private static final String SEAT1_NAME =
            parseSeatName("cc.match.seat1Name", System.getProperty("cc.match.seat1Name"));
    private static final String SEAT2_NAME =
            parseSeatName("cc.match.seat2Name", System.getProperty("cc.match.seat2Name"));

    private static String parseSeatName(String prop, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null; // unset/blank = the name feature is OFF for this seat
        }
        String value = raw.trim();
        if (value.length() > SEAT_NAME_MAX) {
            throw new IllegalArgumentException(
                    prop + " must be <= " + SEAT_NAME_MAX + " chars, got " + value.length() + ": '" + value + "'");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(
                        prop + " must be single-line printable (control char at index " + i + ")");
            }
        }
        return value;
    }

    /**
     * Name a wire-bound ship via the engine's own slot,
     * WIRE-FIRST: the server-distributed name table ({@code SNMR} per-seat records fanned on
     * announce + spooled as admission catch-up, plus the legacy {@code SNMS} two-seat table folded
     * into the same map) outranks this window's {@code cc.match.seat<N>Name} config; config stays
     * the fallback for seats 1/2 ONLY where the wire carried no name (solo WATCH synthetic seat-2,
     * knob-off lanes, pre-1163 servers). Any wire id may carry a name now (N-aware — gauntlet
     * replicas included); an id with no name from any source, or a null ship, is a no-op.
     * Fail-soft: a setName failure must never break the bind path. A ship named here is recorded
     * in {@link #seatNameByShip} so the always-on tag knows the ONLY ships it may label; a
     * RE-apply that CHANGES the name (config applied at bind, a differing wire record landing
     * later) also drops the ship's cached tag label so it is rebuilt with the new text.
     * Idempotent: re-applying the already-recorded name is a silent no-op.
     */
    void applySeatName(ShipAPI ship, long seatShipId) {
        if (ship == null) {
            return;
        }
        String wireName = wireNameByShipId.get(Long.valueOf(seatShipId));
        boolean fromWire = wireName != null && !wireName.isEmpty();
        String name = fromWire ? wireName
                : seatShipId == 1L ? SEAT1_NAME : seatShipId == 2L ? SEAT2_NAME : null;
        if (name == null || name.equals(seatNameByShip.get(ship))) {
            return; // no name from any source, or this exact name is already on (re-apply no-op)
        }
        try {
            ship.setName(name);
            if (seatNameByShip.put(ship, name) != null) {
                seatLabelByShip.remove(ship); // name CHANGED after its tag label was built: rebuild
            }
            PilotWatchLog.info("predict seat-name: shipId=" + seatShipId + " <- " + FallbackAnnounce.quote(name, 64)
                    + " src=" + (fromWire ? "wire (SNMR/SNMS)"
                            : "config (cc.match.seat" + seatShipId + "Name)"));
            renameSameKeyHulks(ship, seatShipId);
        } catch (Throwable t) {
            PilotWatchLog.error("predict seat-name: setName FAILED shipId=" + seatShipId, t);
        }
    }

    /**
     * The mote-stuck defect fix, client mirror of the server's {@code SameKeyHulkRename}: vanilla
     * scripts key engine customData by {@code ship.toString()} = variant + name + hull
     * ({@code MoteControlScript.getSharedData}), so a seat body just named here shares
     * its mote list with the HULK of the seat's previous life on the same variant — and that
     * hulk's still-running system script (the script's hulk branch) flameOut()s + clears the shared
     * list every launch interval: the local puppets fade and the hum dies even after the server
     * stopped churning (the wire carries no hulk name, so each engine breaks the key itself).
     * ONE walk per naming (respawns are seconds apart), hulks only — a live same-key ship is
     * counted in the row, never renamed (owner-visible name; unobserved class). Fail-soft.
     */
    private void renameSameKeyHulks(ShipAPI named, long seatShipId) {
        if (plugin.engine == null) {
            return;
        }
        String key = named.toString();
        int renamed = 0;
        int live = 0;
        StringBuilder ids = new StringBuilder();
        for (ShipAPI s : plugin.engine.getShips()) {
            if (s == null || s == named || !key.equals(s.toString())) {
                continue;
            }
            if (!s.isHulk()) {
                live++;
                continue;
            }
            String n = s.getName();
            if (n == null) {
                continue; // a station module under its parent: getName() null by engine law
            }
            s.setName(n + " (hulk " + s.getId() + ")");
            renamed++;
            ids.append(ids.length() == 0 ? "" : ",").append(s.getId());
        }
        if (renamed > 0 || live > 0) {
            PilotWatchLog.info("predict seat-name: same-key sweep shipId=" + seatShipId + " key="
                    + FallbackAnnounce.quote(key, 128) + " hulks_renamed=" + renamed + " ids=" + ids
                    + " live_same_key=" + live);
        }
    }

    /**
     * The net-thread stash of every wire-carried display name, ONE map
     * keyed by wire id: {@code SNMR} per-seat records (N-aware lane) and the legacy two-seat
     * {@code SNMS} table both fold in here. Volatile publish of an IMMUTABLE map, copy-on-write on
     * intake (single writer = the net thread; names change rarely) — deliberately NOT a
     * {@code java.util.concurrent} map: the game's script classloader B denies new j.u.c types to
     * mod code (loader-B law). {@link #applySeatName} reads it on the render thread.
     * The dirty flag triggers the render-thread late RE-apply ({@link #maintainWireNames}) for
     * ships that bound BEFORE a name landed; a ship binding after the stash gets its wire name
     * directly at bind. setName is idempotent and {@link #applySeatName} no-ops on an unchanged
     * name, so the two paths overlap safely. The map survives match resets (the server's table
     * persists across rounds; finishMatchReset only re-marks dirty).
     */
    private volatile java.util.Map<Long, String> wireNameByShipId = java.util.Collections.emptyMap();
    private volatile boolean wireSeatNamesDirty;

    /** Net-thread copy-on-write fold of one wire name (no-op + no dirty churn on an unchanged value). */
    private void putWireName(long shipId, String name) {
        java.util.Map<Long, String> current = wireNameByShipId;
        if (name.equals(current.get(Long.valueOf(shipId)))) {
            return;
        }
        java.util.Map<Long, String> next = new java.util.HashMap<Long, String>(current);
        next.put(Long.valueOf(shipId), name);
        wireNameByShipId = java.util.Collections.unmodifiableMap(next);
        wireSeatNamesDirty = true;
    }

    /** net-thread intake of the legacy {@code SNMS} two-seat table (TCP/selftest lane
     * parity) — non-empty entries fold into {@link #wireNameByShipId} under the id contract
     * (seat 1 = wire id 1, seat 2 = wire id 2). */
    void onSeatNamesEvent(cc.starsector.client.net.ClientSeatNames names) {
        if (!names.seat1Name().isEmpty()) {
            putWireName(1L, names.seat1Name());
        }
        if (!names.seat2Name().isEmpty()) {
            putWireName(2L, names.seat2Name());
        }
        PilotWatchLog.info("predict seat-names-wire: SNMS received seat1=" + FallbackAnnounce.quote(names.seat1Name(), 64)
                + " seat2=" + FallbackAnnounce.quote(names.seat2Name(), 64) + " -> render-thread wire-first apply at/after bind");
    }

    /** net-thread intake of one {@code SNMR} per-seat name record (fan or catch-up).
     * The {@code ""} sentinel is a no-op — a name is never un-set (a standing law; the server skips
     * sentinel-resolved records anyway). */
    void onSeatNameRecordEvent(cc.starsector.client.net.ClientSeatNameRecord record) {
        if (!record.hasName()) {
            return;
        }
        putWireName(record.shipId(), record.name());
        PilotWatchLog.info("predict seat-names-wire: SNMR received seat=" + record.seatId()
                + " shipId=" + record.shipId() + " name=" + FallbackAnnounce.quote(record.name(), 64)
                + " -> render-thread wire-first apply at/after bind");
    }

    /**
     * Late re-apply (render thread, once per intake via the dirty flag): a wire
     * name landed after a ship already bound (config-named or unnamed). The wire-first
     * {@link #applySeatName} no-ops wherever the right name is already on. A still-unbound ship
     * is covered by its own bind-time apply (also wire-first, reading the same map). Registry-lane
     * replicas are named by their wire id — this sweep is what makes registryShape lanes
     * (gauntlet) see other players' names at all.
     */
    void maintainWireNames() {
        if (!wireSeatNamesDirty) {
            return;
        }
        wireSeatNamesDirty = false;
        applySeatName(plugin.ownShip, plugin.session.shipId());
        if (!plugin.registryShape) {
            applySeatName(plugin.foeShip,
                    plugin.session.shipId() == 1L ? 2L : plugin.session.shipId() == 2L ? 1L : -1L);
        }
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            if (re.ship != null) {
                applySeatName(re.ship, re.entityId);
            }
        }
    }

    /**
     * Name-tag state. {@code seatNameByShip}: seat ships successfully named this
     * combat, keyed by engine identity — empty ⇔ no name from config OR wire ⇔
     * {@link #renderSeatNameTags} exits before any label/GL work (byte-identical presentation, the
     * name-less guarantee). One label per seat ship, created with its then-current text; the ONE
     * legal mid-combat change (a config-applied name superseded by a late wire table)
     * drops the label in {@link #applySeatName} so it is rebuilt. A draw Throwable kills the lane
     * for the ROUND (fail-soft, no per-frame log spam) — presentation must never touch the round;
     * the match-reset rebuild re-arms it (it used to stay dead for the whole combat,
     * which turned one own-death NPE into "nametags gone for the remaining 9 rounds").
     */
    private final IdentityHashMap<ShipAPI, String> seatNameByShip = new IdentityHashMap<ShipAPI, String>();
    private final IdentityHashMap<ShipAPI, LabelAPI> seatLabelByShip = new IdentityHashMap<ShipAPI, LabelAPI>();
    private float seatNameRowH;
    private boolean seatNameTagDead;

    /**
     * The DEBUG server-hull bar, for comparing client hull vs server hull by eye.
     *
     * Watch-only overlay: for every wire-mapped live
     * ship, two stacked GL_QUADS bars under the hull — top = the RAW LOCAL hull (the live desync
     * witness: never glide it), bottom = the authoritative wire hull
     * ({@link ServerEntityPose#hitpoints}, already decoded — zero wire cost). Gate = WELCOME
     * matchFlags bit1 via {@link MatchConfigFromWire#clientDebug()} (server lever
     * {@code cc.server.clientDebug}, wire-delivered to EVERY seat identically — read live per
     * frame like the other gate members). The old client sysprop
     * {@code cc.debug.serverHullBar} is RETIRED:
     * it rode launcher-shell JTO, so host-launched windows got bars while the kit-joined window
     * never did — the reported one-window-only mismatch. Denominator = the LOCAL
     * {@code getMaxHitpoints()} for both bars (a server-side stat-mod / script-grown max-hull skew
     * would show as a fraction offset; the hull lane now mirrors the keyframe's
     * {@code maxHp} onto every treated replica ({@code FoeHullReconcileLane.mirrorMaxHull}), so the
     * denominator IS the server's there; the own ship's skew is measured, never written). Own
     * dead-latch, never shared with the seat-tag lane's.
     */
    private boolean serverHullBarDead;

    /**
     * ALWAYS-VISIBLE seat name tags, superseding the earlier hover-gated
     * form. Each named seat ship
     * carries its seat name every frame, hover or not — vertical position keeps the small-box
     * formula (the row just above where the vanilla hover FLUX/HULL box tops out, so tag and box
     * read as one unit whenever the box shows), horizontal is CENTERED on the ship (the
     * earlier form was left-aligned with the box edge). The always-on form also
     * retires the earlier form's residuals structurally: no hover test (no UI-scale mouse mismatch) and no
     * box-coupled appearance (no fade-in pop-in). Per-ship gates: still in play, alive, and
     * player-POV fog-visible (a fogged foe must not leak its position through a tag). Client-only,
     * no wire bytes, no roster touch.
     */
    void renderSeatNameTags(ViewportAPI viewport) {
        if (seatNameByShip.isEmpty() || seatNameTagDead || plugin.engine == null || viewport == null) {
            return;
        }
        try {
            // NEVER a ship's live getOwner() for a side: a dead own ship reads owner 100 and
            // engine.getFogOfWar(100) is null -> NPE killed the lane at the owner's first death
            // in the real 1v1 — the same corpse-poison class the
            // spawn path already guards. Bind-time capture is the side authority.
            int ownSide = plugin.ownSideAtBind >= 0 ? plugin.ownSideAtBind : 0;
            com.fs.starfarer.api.combat.FogOfWarAPI fog = plugin.engine.getFogOfWar(ownSide);
            for (ShipAPI ship : seatNameByShip.keySet()) {
                if (!plugin.engine.isEntityInPlay(ship) || !ship.isAlive()) {
                    continue; // dead/removed seat ship: the tag dies with it
                }
                if (fog == null || !fog.isVisible(ship)) {
                    continue; // no fog authority -> no tag (never leak a fogged position)
                }
                plugin.drawSeatNameTag(viewport, ship, seatNameByShip.get(ship));
            }
        } catch (Throwable t) {
            seatNameTagDead = true;
            // Throwable summary goes IN the message line: the watch harvest keeps only the
            // cc.pilot.watch-prefixed lines, so a log4j-appended stack trace never reaches the
            // per-run client-log artifact (a past fix had to recover it from raw starsector.log).
            PilotWatchLog.error("predict seat-name-tag: draw FAILED, lane disabled until next round: " + t, t);
        }
    }

    /**
     * The debug-bar render pass (gated by {@link MatchConfigFromWire#clientDebug()},
     * see {@link #serverHullBarDead} docs): own ship via the
     * session shipId pose, registry remotes via their entry ids, the 1v1 single foe via
     * {@code firstOther} — the same id mapping the reconcile path uses, read off the volatile
     * {@code latestSnapshot} (render thread; a torn frame is impossible, the net thread swaps
     * whole frames). Per-ship gates mirror the seat-tag lane: in-play + alive + fog-visible from
     * the bind-time own side (never a live {@code getOwner()} — the corpse-poison class).
     * A draw Throwable kills THIS lane only (own latch), loud once.
     */
    void renderServerHullBars(ViewportAPI vp) {
        if (!MatchConfigFromWire.clientDebug() || serverHullBarDead || plugin.engine == null || vp == null) {
            return;
        }
        ServerSnapshotFrame snapshot = plugin.latestSnapshot;
        if (snapshot == null) {
            return;
        }
        // The render pass can fire BEFORE the predict-session opens (first
        // frames of a watch window) — session.shipId() below would NPE and the catch would latch
        // the lane dead for the whole run. A pre-session frame is skippable, not a draw failure.
        if (plugin.session == null) {
            return;
        }
        try {
            int ownSide = plugin.ownSideAtBind >= 0 ? plugin.ownSideAtBind : 0;
            com.fs.starfarer.api.combat.FogOfWarAPI fog = plugin.engine.getFogOfWar(ownSide);
            ServerEntityPose ownPose = snapshot.entityById(plugin.session.shipId());
            // The OWN bar keeps the engine read (NaN; own lane untouched);
            // replica bars switch the green source to the raw ledger — with apply live the engine
            // value is the RECONCILED scalar, and without the switch both bars collapse to wire.
            drawServerHullBar(vp, fog, plugin.ownShip, ownPose, Float.NaN);
            if (plugin.registryShape) {
                for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                    if (re.ship != null) {
                        drawServerHullBar(vp, fog, re.ship, snapshot.entityById(re.entityId),
                                re.ledger != null ? re.ledger.rawHull : Float.NaN);
                    }
                }
            } else {
                drawServerHullBar(vp, fog, plugin.foeShip,
                        PredictReconcileCombatPlugin.firstOther(snapshot, ownPose),
                        plugin.hullLane.foeHullLedger != null
                                ? plugin.hullLane.foeHullLedger.rawHull : Float.NaN);
            }
        } catch (Throwable t) {
            serverHullBarDead = true;
            PilotWatchLog.error("predict server-hull-bar: draw FAILED, lane disabled: " + t, t);
        }
    }

    /**
     * One ship's stacked pair: top bar = LOCAL {@code getHitpoints()}, bottom bar = wire
     * {@code pose.hitpoints()}, both over the local max. Anchored BELOW the hull (the vanilla
     * hover box + seat tag own the space above; below is empty) off the same shield-radius
     * formula as the tag — a shieldless hull reads r=0 and the bars sit just under the sprite
     * center, still legible. GL: untextured blended quads, texture re-enabled after (the label
     * renderer expects it on).
     */
    private void drawServerHullBar(ViewportAPI vp, com.fs.starfarer.api.combat.FogOfWarAPI fog,
            ShipAPI ship, ServerEntityPose pose, float rawHull) {
        if (ship == null || pose == null || !plugin.engine.isEntityInPlay(ship) || !ship.isAlive()) {
            return;
        }
        if (ship.isDoNotRender()) {
            return; // A hidden body (swarm fighter, SwarmSpriteGate) draws no bar either
        }
        if (fog == null || !fog.isVisible(ship)) {
            return; // no fog authority -> no bar (never leak a fogged position)
        }
        float maxHp = ship.getMaxHitpoints();
        if (maxHp <= 0f) {
            return;
        }
        // Green = raw local-applied ledger when carried (NaN = engine fallback,
        // identical to the ledger when reconcile is off); orange stays wire — raw on both sides.
        float localHp = Float.isNaN(rawHull) ? ship.getHitpoints() : rawHull;
        float localFrac = Math.max(0f, Math.min(1f, localHp / maxHp));
        float serverFrac = Math.max(0f, Math.min(1f, pose.hitpoints() / maxHp));
        float r = Math.min(ship.getHullSpec().getShieldSpec().getRadius() / vp.getViewMult(), 150f) * 0.66f;
        float cx = (int) vp.convertWorldXtoScreenX(ship.getLocation().x);
        float cy = (int) vp.convertWorldYtoScreenY(ship.getLocation().y);
        final float w = 60f, h = 4f, gap = 2f;
        float x0 = cx - w / 2f;
        float yLocal = cy - r - 12f;
        float yServer = yLocal - (h + gap);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        hullBarQuad(x0, yLocal, w, h, 0f, 0f, 0f, 0.55f);
        hullBarQuad(x0, yServer, w, h, 0f, 0f, 0f, 0.55f);
        hullBarQuad(x0, yLocal, w * localFrac, h, 0.45f, 1f, 0.45f, 0.9f); // local = green
        hullBarQuad(x0, yServer, w * serverFrac, h, 1f, 0.62f, 0.1f, 0.9f); // server = orange
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** One colored quad inside an open {@code glBegin(GL_QUADS)} block (glColor4f is begin-legal). */
    private void hullBarQuad(float x, float y, float w, float h,
            float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
    }

    /**
     * Tag geometry (v0.6): vertical = the small-box formula (the engine's box renderer
     * in small mode, brightness=1) — {@code r = min(shieldSpec.radius/zoom, 150) * 0.66}
     * (NO 50px floor in small mode; a shieldless hull like the Mudskipper gets r=0), box top =
     * {@code r + 3 + 2*rowH}, tag one row above that (+3px pad) — exactly where the hover row
     * sat, so the tag lines up with the vanilla box whenever the box shows. Horizontal = centered
     * on the ship's screen anchor (label x = anchor.x - textWidth/2). Anchor floored to int (the
     * renderer's own idiom). Called ONLY through the plugin's protected draw-reach seam
     * ({@code PredictReconcileCombatPlugin#drawSeatNameTag} — the RenderCorpseGuardSmoke override
     * law); never called outside the render thread / that smoke.
     */
    void drawSeatNameTag(ViewportAPI vp, ShipAPI ship, String name) {
        LabelAPI label = seatLabelByShip.get(ship);
        if (label == null) {
            label = com.fs.starfarer.api.Global.getSettings()
                    .createLabel(name, "graphics/fonts/victor10.fnt"); // the box's own font
            label.setAlignment(Alignment.LMID);
            seatNameRowH = label.computeTextHeight("Ag");
            label.getPosition().setSize(label.computeTextWidth(name), seatNameRowH);
            seatLabelByShip.put(ship, label);
        }
        label.setColor(seatNameColor(ship));
        float r = Math.min(ship.getHullSpec().getShieldSpec().getRadius() / vp.getViewMult(), 150f) * 0.66f;
        float anchorX = (int) vp.convertWorldXtoScreenX(ship.getLocation().x);
        float anchorY = (int) vp.convertWorldYtoScreenY(ship.getLocation().y);
        label.getPosition().setLocation(anchorX - label.getPosition().getWidth() / 2f,
                anchorY + r + 3f + 2f * seatNameRowH + 3f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture (GL prep)
        label.render(1f);
    }

    /** The tag's half width for {@code ship} so the voice
     * presence lane can seat its speaker glyph just right of the tag, or -1 when no tag was
     * ever drawn for it (render thread only, like the maps it reads). */
    float seatTagHalfWidth(ShipAPI ship) {
        LabelAPI label = seatLabelByShip.get(ship);
        return label == null ? -1f : label.getPosition().getWidth() / 2f;
    }

    /** Fixes a glyph that sat a row ABOVE the name: the name label's
     * vertical CENTRE in screen px as the engine placed it this frame, or NaN when no tag was ever
     * drawn — the glyph centres on THIS, never on a re-derived row (render thread only). */
    float seatTagCentreY(ShipAPI ship) {
        LabelAPI label = seatLabelByShip.get(ship);
        return label == null ? Float.NaN : label.getPosition().getCenterY();
    }

    /** The label cache size for the periodic census (render thread). */
    int labelCacheSize() {
        return seatLabelByShip.size();
    }

    /** Row height of the tag font (0 before the first tag draw). */
    float seatNameRowH() {
        return seatNameRowH;
    }

    /** The seat name bound to {@code ship} (for the HUD talking row) or null. */
    String seatNameOf(ShipAPI ship) {
        return ship == null ? null : seatNameByShip.get(ship);
    }

    /**
     * Vanilla box text color (the engine's side/ally color pick → settings colors): own side ⇒
     * {@code textFriendColor}; enemy ⇒ {@code textEnemyColor}, or the engine's hardcoded blue when
     * {@code colorblindMode} is on. Seat ships are never allied/neutral, so those Utils branches are
     * deliberately not replicated.
     */
    private Color seatNameColor(ShipAPI ship) {
        int ownSide = plugin.sideAuthority(); // never the live own owner: a corpse reads 100
        com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
        if (ship.getOwner() == ownSide) {
            return settings.getColor("textFriendColor");
        }
        return settings.getBoolean("colorblindMode")
                ? new Color(0, 100, 255) : settings.getColor("textEnemyColor");
    }

    /**
     * Per-ROUND re-arm at the match-reset rebuild: tag/label state dies with the round and the
     * tag dead-latch re-arms (a draw failure must not outlive the reset). The
     * wire-name table itself PERSISTS (the server's table spans rounds); it is only re-marked
     * dirty so the fresh ships re-apply their names. The hull-bar dead-latch is deliberately
     * NOT reset — a bar draw failure disables that debug lane for the run (its own javadoc law).
     */
    /**
     * A seat LEFT {@code ship} (own-COMMIT re-home,
     * {@code CommandTransferLane.reHomeSourceUnder}) — drop its tag + label and give the hull its
     * fleet-member name back. The seat name was stamped by {@code ship.setName} and this map is
     * keyed by ShipAPI identity (cleared only at match reset), so the vacated hull kept drawing
     * the leaver's tag AND listed under their name in the warroom — the stale "mark" seen in play. A
     * new occupant's applySeatName re-stamps. Fail-soft: the name restore is best effort.
     */
    void forgetSeatName(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        String had = seatNameByShip.remove(ship);
        seatLabelByShip.remove(ship);
        if (had == null) {
            return;
        }
        try {
            com.fs.starfarer.api.fleet.FleetMemberAPI member = ship.getFleetMember();
            String vanilla = member != null ? member.getShipName() : null;
            if (vanilla != null && !vanilla.isEmpty() && !vanilla.equals(had)) {
                ship.setName(vanilla);
            }
        } catch (Throwable ignore) {
            // Presentation only — the tag is already gone.
        }
        PilotWatchLog.info("predict seat-name: vacated hull dropped tag " + FallbackAnnounce.quote(had, 64));
    }

    void resetForMatchReset() {
        seatNameByShip.clear();
        seatLabelByShip.clear();
        seatNameTagDead = false;
        wireSeatNamesDirty = true;
    }
}
