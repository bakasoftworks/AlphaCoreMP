package cc.starsector.client;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;

import cc.starsector.client.voice.VoiceClientSettings;

/**
 * The HUD DECLUTTER:
 * the bottom-right readout block — the {@code ping + updates/sec} line that used to ride two
 * vanilla left-HUD status rows ({@code cc_net_ping}/{@code cc_net_tick}) and
 * the {@code sim rate} line that used to ride the left-HUD {@code cc_sim_rate} row — drawn in
 * the SAME idiom as the voice rtt row ({@link VoicePresenceLane#drawTalkRow}: victor10, colour
 * (200,230,255), right-anchored at {@code screenWidth-25}), plus the per-frame BOTTOM-UP STACKER
 * all three rows share so hidden rows collapse (no voids): each visible row claims the next slot
 * from the bottom, first-drawn = bottom. Draw order in {@code renderInUICoordsBody}:
 * {@link #renderNet} FIRST (bottom slot), the voice row above it, {@link
 * #renderSim} above the voice row; a silent voice row collapses the stack.
 *
 * <p>Samplers live in {@link OwnReadoutLane} — their STATE
 * stays in the plugin ({@code maintainNetReadout}: {@code netFramesPublished} is cross-thread,
 * {@code netRateSamples} a plain deque; {@code maintainSimRateReadout} /
 * {@code maintainNoFeedSimRateReadout}: the clock-servo state) and they hand ONE composed line per
 * row here per fresh frame; a line expires after {@value #LINE_TTL_NANOS} ns so a dead feed collapses
 * its row the way an un-maintained vanilla status key fades. The {@code hud.netRow} /
 * {@code hud.simRate} toggles ({@link VoiceClientSettings#HUD_NET_ROW} /
 * {@link VoiceClientSettings#HUD_SIM_RATE}) hide them; {@link #prefs()} is the ONE resolver every
 * HUD toggle reads (live voice client's settings, else the file loaded once + refreshed by the
 * picker's apply). The sim row's {@code hilite} = the former left-HUD debuff colouring (a clock
 * divergence past the plugin's threshold) rendered as the vanilla debuff red. Presentation only;
 * fail-soft dead-latch like its voice twin. B-legal: engine API + GL11 only. Own file per
 * NEW-LANE-LAW (PRCP is past its WarnNb).
 */
final class HudReadoutLane {
    /** Bottom row baseline (UI y-up): the voice row's historic {@code 25 + 4}. */
    static final float BASE_Y = 29f;
    static final float ROW_GAP = 4f;
    static final long LINE_TTL_NANOS = 1_500_000_000L;
    private static final java.awt.Color ROW_COLOR = new java.awt.Color(200, 230, 255, 255);
    /** The sim row's divergence colour: the vanilla status-row debuff red. */
    private static final java.awt.Color HILITE_COLOR = new java.awt.Color(255, 100, 100, 255);

    /** One composed row: the sampler's line + its freshness stamp + the width cache. */
    private static final class Row {
        String line;
        long lineNanos;
        boolean hilite;
        String textCache;
        float widthCache;

        void set(String text, boolean hiliteFlag) {
            line = text;
            hilite = hiliteFlag;
            lineNanos = System.nanoTime();
        }
    }

    private final PredictReconcileCombatPlugin plugin;
    private LabelAPI rowLabel;
    private float rowH;
    private boolean laneDead;
    /** Stacker: height already claimed above {@link #BASE_Y} this frame. */
    private float usedHeight;
    private final Row net = new Row();
    private final Row sim = new Row();
    /** File-loaded settings for the no-session case (a serverless/vanilla window). */
    private VoiceClientSettings filePrefs;

    HudReadoutLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ toggles

    /** The HUD toggles: the live voice client's settings, else the settings file (loaded once). */
    VoiceClientSettings prefs() {
        cc.starsector.client.voice.VoiceClient v = plugin.voice();
        if (v != null) {
            return v.settings();
        }
        if (filePrefs == null) {
            try {
                filePrefs = cc.starsector.client.voice.VoiceSettingsStore.load(
                        com.fs.starfarer.api.Global.getSettings(), PilotWatchLog::info);
            } catch (Throwable ignore) {
                filePrefs = VoiceClientSettings.DEFAULTS;
            }
        }
        return filePrefs;
    }

    /** A {@code /settings} pick applied (any session state): the file copy follows it. */
    void onSettings(VoiceClientSettings next) {
        if (next != null) {
            filePrefs = next;
        }
    }

    // ------------------------------------------------------------------ stacker

    /** Top of the UI pass: no slots claimed yet. */
    void beginFrame() {
        usedHeight = 0f;
    }

    /** Claim the next slot from the bottom for a row {@code rowH} tall; returns its y. */
    float nextY(float rowH) {
        float y = BASE_Y + usedHeight;
        usedHeight += rowH + ROW_GAP;
        return y;
    }

    // ------------------------------------------------------------------ rows

    /** The net sampler's composed line this fresh frame (null/empty = nothing to show). */
    void setNetLine(String line) {
        net.set(line, false);
    }

    /** The sim-rate sampler's composed line (title + value) this fresh frame;
     *  {@code hilite} = the clock-divergence colouring. */
    void setSimLine(String line, boolean hilite) {
        sim.set(line, hilite);
    }

    /** UI-coords pass, BEFORE the voice row (bottom slot): the net line, if fresh and enabled. */
    void renderNet(ViewportAPI vp) {
        renderRow(vp, net, VoiceClientSettings.HUD_NET_ROW);
    }

    /** UI-coords pass, AFTER the voice row (above it): the sim-rate line, if fresh and enabled. */
    void renderSim(ViewportAPI vp) {
        renderRow(vp, sim, VoiceClientSettings.HUD_SIM_RATE);
    }

    private void renderRow(ViewportAPI vp, Row row, int toggleBit) {
        if (laneDead || plugin.engine == null || vp == null) {
            return;
        }
        try {
            String text = row.line;
            if (text == null || text.isEmpty() || System.nanoTime() - row.lineNanos > LINE_TTL_NANOS
                    || !prefs().hudRow(toggleBit)) {
                return;
            }
            com.fs.starfarer.api.SettingsAPI settings = com.fs.starfarer.api.Global.getSettings();
            if (rowLabel == null) {
                rowLabel = settings.createLabel("ping", "graphics/fonts/victor10.fnt");
                rowLabel.setAlignment(Alignment.LMID);
                rowH = rowLabel.computeTextHeight("Ag");
            }
            if (!text.equals(row.textCache)) {
                // Right-anchored: size by the widest same-shape text (digits → '8') so a
                // 9→10 ms change never shifts the row (the voice row's idiom).
                char[] wide = text.toCharArray();
                for (int i = 0; i < wide.length; i++) {
                    if (wide[i] >= '0' && wide[i] <= '9') {
                        wide[i] = '8';
                    }
                }
                row.widthCache = Math.max(rowLabel.computeTextWidth(text),
                        rowLabel.computeTextWidth(new String(wide)));
                row.textCache = text;
            }
            float w = row.widthCache;
            float x = settings.getScreenWidth() - 25f - w;
            float y = nextY(rowH);
            rowLabel.setText(text);
            rowLabel.setColor(row.hilite ? HILITE_COLOR : ROW_COLOR);
            rowLabel.getPosition().setSize(w, rowH);
            rowLabel.getPosition().setLocation(x, y);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f); // the font renderer binds its own glyph texture
            rowLabel.render(1f);
        } catch (Throwable t) {
            laneDead = true;
            PilotWatchLog.info("hud readout row: lane dead (" + t + ")");
        }
    }
}
