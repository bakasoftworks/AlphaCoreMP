package cc.starsector.client.voice;

import java.util.ArrayList;
import java.util.List;

import cc.starsector.client.net.ClientListingReader;
import cc.starsector.client.net.ClientListingRecord;

/**
 * The {@code /settings} menu law: a CLIENT-LOCAL
 * listing rendered by the list picker (kind {@code ListPickerLaw.KIND_LOCAL_SETTINGS}, never on
 * the wire) whose picks are applied purely here — the lane feeds the picked row id back through
 * {@link #pick} and gets the next settings value (or null = unchanged) plus the listing to show
 * next. Game-free so the selftest pins every row and every pick.
 *
 * <p>Rows are {@link ClientListingRecord.Entry} values with the display text as the token
 * ({@code MARK_SHIP_PLAIN}, no labels ⇒ the lane prints the token verbatim) and a stable row id
 * in {@code hullWireId}: ROOT ids 1..18 (13..18 = the HUD row toggles), the volume ladder 100+i, the mic-gain ladder 200+i,
 * the quality ladder 300+i (0 = server default, 1..11 = complexity 0..10), the stereo-width
 * ladder 400+i — disjoint by construction. ROOT: {@code Voice volume N%}, {@code Mic gain N%},
 * {@code Mic ON|OFF}, {@code Voice ON|OFF}, {@code Talking HUD row ON|OFF}, {@code Keys: Space =
 * prox, B = global} (read-only), then the rows: {@code Voice
 * quality: server default (N)|N}, {@code Hear own voice (echo): ON|OFF (server default|yours)}
 * (a toggle = an explicit override of the EFFECTIVE value), {@code Prox voice:
 * spatial|distance-only}, {@code Stereo width N%}, {@code Reset voice quality + echo to server
 * default}. The server {@link VoiceSheet} (null = no session yet) resolves the effective values.
 * Ladders: 0 25 50 75 100 125 150 200 % (volume/gain); 0 25 50 75 100 % (width).
 */
public final class VoiceSettingsMenu {
    public static final long ROW_VOLUME = 1;
    public static final long ROW_MIC_GAIN = 2;
    public static final long ROW_MIC_ON = 3;
    public static final long ROW_VOICE_ON = 4;
    public static final long ROW_HUD_TALK_ROW = 5;
    public static final long ROW_KEYS = 6;
    public static final long ROW_COMPLEXITY = 7;
    public static final long ROW_ECHO = 8;
    public static final long ROW_SPATIAL = 9;
    public static final long ROW_PAN_WIDTH = 10;
    public static final long ROW_RESET_SERVER = 11;
    /** The HUD rtt row's own toggle (no longer echo-coupled). */
    public static final long ROW_RTT_STATS = 12;
    /** The six HUD row toggles, ids {@code ROW_HUD_BASE + i} for bit
     * {@code 1 << i} of {@link VoiceClientSettings#hudRows} ({@code HUD_ROW_KEYS} order). */
    public static final long ROW_HUD_BASE = 13;
    /** Peer-build sharing v2: "Auto-share builds I use" (ids
     *  13..18 are the six HUD rows). */
    public static final long ROW_AUTO_SHARE = 19;
    public static final String[] HUD_ROW_LABELS = {"Ping/updates HUD row ", "Sim-rate HUD row ",
            "Client speed HUD row ", "Server speed HUD row ", "Client flux HUD row ",
            "Server flux HUD row "};
    public static final long LADDER_VOLUME_BASE = 100;
    public static final long LADDER_GAIN_BASE = 200;
    public static final long LADDER_COMPLEXITY_BASE = 300;
    public static final long LADDER_WIDTH_BASE = 400;
    /** Ladder values (fractions of 1.0); index = id − base. */
    public static final float[] LADDER = {0f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    /** Stereo-width ladder (pan multiplier); index = id − {@link #LADDER_WIDTH_BASE}. */
    public static final float[] WIDTH_LADDER = {0f, 0.25f, 0.5f, 0.75f, 1f};
    /** Quality ladder rows: 0 = server default, then complexity 0..10. */
    public static final int COMPLEXITY_ROWS = VoiceClientSettings.MAX_COMPLEXITY + 2;

    /** A pick's result: {@code next} null = settings unchanged; {@code listing} = show next. */
    public static final class Outcome {
        public final VoiceClientSettings next;
        public final List<ClientListingRecord.Entry> listing;

        Outcome(VoiceClientSettings next, List<ClientListingRecord.Entry> listing) {
            this.next = next;
            this.listing = listing;
        }
    }

    private VoiceSettingsMenu() {
    }

    /** The ROOT listing for {@code current} with no sheet (pre-session). */
    public static List<ClientListingRecord.Entry> root(VoiceClientSettings current) {
        return root(current, null);
    }

    /** The ROOT listing for {@code current}; {@code sheet} (nullable) resolves the effective values. */
    public static List<ClientListingRecord.Entry> root(VoiceClientSettings current, VoiceSheet sheet) {
        VoiceClientSettings s = current == null ? VoiceClientSettings.DEFAULTS : current;
        List<ClientListingRecord.Entry> rows = new ArrayList<>(11);
        rows.add(row("Voice volume ", percent(s.volume) + "%", ROW_VOLUME));
        rows.add(row("Mic gain ", percent(s.micGain) + "%", ROW_MIC_GAIN));
        rows.add(row("Mic ", onOff(s.micOn), ROW_MIC_ON));
        rows.add(row("Voice ", onOff(s.voiceOn), ROW_VOICE_ON));
        rows.add(row("Talking HUD row ", onOff(s.hudTalkRow), ROW_HUD_TALK_ROW));
        rows.add(row("Keys: Space = prox, B = global", ROW_KEYS));
        rows.add(row("Voice quality: ", s.complexity == VoiceClientSettings.SERVER_DEFAULT
                ? "server default (" + s.effectiveComplexity(sheet) + ")"
                : String.valueOf(s.complexity), ROW_COMPLEXITY));
        rows.add(row("Hear own voice (echo): ", onOff(s.effectiveEcho(sheet))
                + (s.echo == VoiceClientSettings.SERVER_DEFAULT ? " (server default)" : " (yours)"),
                ROW_ECHO));
        rows.add(row("Voice rtt HUD row ", onOff(s.rttStats), ROW_RTT_STATS));
        for (int i = 0; i < HUD_ROW_LABELS.length; i++) {
            rows.add(row(HUD_ROW_LABELS[i], onOff(s.hudRow(1 << i)), ROW_HUD_BASE + i));
        }
        rows.add(row("Prox voice: ", s.spatial ? "spatial" : "distance-only", ROW_SPATIAL));
        rows.add(row("Stereo width ", percent(s.panWidth) + "%", ROW_PAN_WIDTH));
        rows.add(row("Auto-share builds I use ", onOff(s.autoShareBuilds), ROW_AUTO_SHARE));
        rows.add(row("Reset voice quality + echo to server default", ROW_RESET_SERVER));
        return rows;
    }

    /** The value ladder for {@code base} ({@link #LADDER_VOLUME_BASE} / {@link #LADDER_GAIN_BASE}). */
    public static List<ClientListingRecord.Entry> ladder(long base) {
        List<ClientListingRecord.Entry> rows = new ArrayList<>(LADDER.length);
        for (int i = 0; i < LADDER.length; i++) {
            rows.add(row("", percent(LADDER[i]) + "%", base + i));
        }
        return rows;
    }

    /** The quality ladder: row 0 = server default (its value named), then complexity 0..10. */
    public static List<ClientListingRecord.Entry> complexityLadder(VoiceSheet sheet) {
        List<ClientListingRecord.Entry> rows = new ArrayList<>(COMPLEXITY_ROWS);
        rows.add(row("", "server default (" + VoiceClientSettings.DEFAULTS.effectiveComplexity(sheet)
                + ")", LADDER_COMPLEXITY_BASE));
        for (int c = 0; c <= VoiceClientSettings.MAX_COMPLEXITY; c++) {
            rows.add(row("", String.valueOf(c), LADDER_COMPLEXITY_BASE + 1 + c));
        }
        return rows;
    }

    /** The stereo-width ladder ({@link #WIDTH_LADDER}). */
    public static List<ClientListingRecord.Entry> widthLadder() {
        List<ClientListingRecord.Entry> rows = new ArrayList<>(WIDTH_LADDER.length);
        for (int i = 0; i < WIDTH_LADDER.length; i++) {
            rows.add(row("", percent(WIDTH_LADDER[i]) + "%", LADDER_WIDTH_BASE + i));
        }
        return rows;
    }

    /** {@link #pick(long, VoiceClientSettings, VoiceSheet)} with no sheet. */
    public static Outcome pick(long rowId, VoiceClientSettings current) {
        return pick(rowId, current, null);
    }

    /**
     * Apply a picked row id: a ladder root row ⇒ its ladder (unchanged); a ladder value ⇒
     * applied + ROOT; a toggle ⇒ flipped + ROOT (echo flips the EFFECTIVE value into an explicit
     * override); reset ⇒ server defaults + ROOT; Keys / unknown ⇒ unchanged + ROOT.
     */
    public static Outcome pick(long rowId, VoiceClientSettings current, VoiceSheet sheet) {
        VoiceClientSettings s = current == null ? VoiceClientSettings.DEFAULTS : current;
        if (rowId == ROW_VOLUME) {
            return new Outcome(null, ladder(LADDER_VOLUME_BASE));
        }
        if (rowId == ROW_MIC_GAIN) {
            return new Outcome(null, ladder(LADDER_GAIN_BASE));
        }
        if (rowId == ROW_COMPLEXITY) {
            return new Outcome(null, complexityLadder(sheet));
        }
        if (rowId == ROW_PAN_WIDTH) {
            return new Outcome(null, widthLadder());
        }
        VoiceClientSettings next = null;
        if (rowId == ROW_MIC_ON) {
            next = s.withMicOn(!s.micOn);
        } else if (rowId == ROW_VOICE_ON) {
            next = s.withVoiceOn(!s.voiceOn);
        } else if (rowId == ROW_HUD_TALK_ROW) {
            next = s.withHudTalkRow(!s.hudTalkRow);
        } else if (rowId == ROW_ECHO) {
            // Echo and the rtt row are DECOUPLED — the
            // auto-flip is gone; ROW_RTT_STATS is the row's only switch (default ON).
            next = s.withEcho(s.effectiveEcho(sheet) ? 0 : 1);
        } else if (rowId == ROW_RTT_STATS) {
            next = s.withRttStats(!s.rttStats);
        } else if (isHudRow(rowId)) {
            int bit = 1 << (int) (rowId - ROW_HUD_BASE);
            next = s.withHudRow(bit, !s.hudRow(bit));
        } else if (rowId == ROW_SPATIAL) {
            next = s.withSpatial(!s.spatial);
        } else if (rowId == ROW_AUTO_SHARE) {
            next = s.withAutoShareBuilds(!s.autoShareBuilds);
        } else if (rowId == ROW_RESET_SERVER) {
            next = s.withServerDefaults();
        } else if (rowId >= LADDER_VOLUME_BASE && rowId < LADDER_VOLUME_BASE + LADDER.length) {
            next = s.withVolume(LADDER[(int) (rowId - LADDER_VOLUME_BASE)]);
        } else if (rowId >= LADDER_GAIN_BASE && rowId < LADDER_GAIN_BASE + LADDER.length) {
            next = s.withMicGain(LADDER[(int) (rowId - LADDER_GAIN_BASE)]);
        } else if (rowId == LADDER_COMPLEXITY_BASE) {
            next = s.withComplexity(VoiceClientSettings.SERVER_DEFAULT);
        } else if (rowId > LADDER_COMPLEXITY_BASE
                && rowId < LADDER_COMPLEXITY_BASE + COMPLEXITY_ROWS) {
            next = s.withComplexity((int) (rowId - LADDER_COMPLEXITY_BASE - 1));
        } else if (rowId >= LADDER_WIDTH_BASE && rowId < LADDER_WIDTH_BASE + WIDTH_LADDER.length) {
            next = s.withPanWidth(WIDTH_LADDER[(int) (rowId - LADDER_WIDTH_BASE)]);
        }
        return new Outcome(next, root(next != null ? next : s, sheet));
    }

    /**
     * Left/Right on a ROOT row steps its value IN PLACE
     * ({@code dir} = ±1): ladder rows move one rung (clamped at the ends; a value between rungs
     * snaps to the nearest first), toggles flip either way, quality walks server-default → 0 →
     * … → 10, Keys / reset / unknown stay unchanged. A ladder VALUE id (the user is inside a
     * ladder) ⇒ that ladder unchanged, so the keys never yank the view back to ROOT. Pure.
     */
    public static Outcome step(long rowId, int dir, VoiceClientSettings current, VoiceSheet sheet) {
        VoiceClientSettings s = current == null ? VoiceClientSettings.DEFAULTS : current;
        int d = dir < 0 ? -1 : 1;
        if (rowId >= LADDER_VOLUME_BASE && rowId < LADDER_VOLUME_BASE + LADDER.length) {
            return new Outcome(null, ladder(LADDER_VOLUME_BASE));
        }
        if (rowId >= LADDER_GAIN_BASE && rowId < LADDER_GAIN_BASE + LADDER.length) {
            return new Outcome(null, ladder(LADDER_GAIN_BASE));
        }
        if (rowId >= LADDER_COMPLEXITY_BASE && rowId < LADDER_COMPLEXITY_BASE + COMPLEXITY_ROWS) {
            return new Outcome(null, complexityLadder(sheet));
        }
        if (rowId >= LADDER_WIDTH_BASE && rowId < LADDER_WIDTH_BASE + WIDTH_LADDER.length) {
            return new Outcome(null, widthLadder());
        }
        if (rowId == ROW_MIC_ON || rowId == ROW_VOICE_ON || rowId == ROW_HUD_TALK_ROW
                || rowId == ROW_ECHO || rowId == ROW_SPATIAL || rowId == ROW_RTT_STATS
                || rowId == ROW_AUTO_SHARE || isHudRow(rowId)) {
            return pick(rowId, s, sheet); // a toggle flips either way (same as Enter)
        }
        VoiceClientSettings next = null;
        if (rowId == ROW_VOLUME) {
            next = s.withVolume(LADDER[stepIndex(LADDER, s.volume, d)]);
        } else if (rowId == ROW_MIC_GAIN) {
            next = s.withMicGain(LADDER[stepIndex(LADDER, s.micGain, d)]);
        } else if (rowId == ROW_PAN_WIDTH) {
            next = s.withPanWidth(WIDTH_LADDER[stepIndex(WIDTH_LADDER, s.panWidth, d)]);
        } else if (rowId == ROW_COMPLEXITY) {
            int c = s.complexity == VoiceClientSettings.SERVER_DEFAULT ? -1 : s.complexity;
            c = Math.max(-1, Math.min(VoiceClientSettings.MAX_COMPLEXITY, c + d));
            next = s.withComplexity(c < 0 ? VoiceClientSettings.SERVER_DEFAULT : c);
        }
        return new Outcome(next, root(next != null ? next : s, sheet));
    }

    static boolean isHudRow(long rowId) {
        return rowId >= ROW_HUD_BASE && rowId < ROW_HUD_BASE + HUD_ROW_LABELS.length;
    }

    /** Index of the rung nearest {@code value}, moved by {@code dir} and clamped to the ladder. */
    static int stepIndex(float[] ladder, float value, int dir) {
        int nearest = 0;
        float best = Math.abs(ladder[0] - value);
        for (int i = 1; i < ladder.length; i++) {
            float diff = Math.abs(ladder[i] - value);
            if (diff < best) {
                best = diff;
                nearest = i;
            }
        }
        return Math.max(0, Math.min(ladder.length - 1, nearest + dir));
    }

    private static ClientListingRecord.Entry row(String text, long id) {
        return new ClientListingRecord.Entry(text, ClientListingReader.MARK_SHIP_PLAIN, false,
                null, null, id);
    }

    /** The row's SET {@code value} trails its label and draws cyan; the token
     *  stays the whole text (a ladder row is all value). */
    private static ClientListingRecord.Entry row(String label, String value, long id) {
        return new ClientListingRecord.Entry(label + value, ClientListingReader.MARK_SHIP_PLAIN, false,
                null, null, id, value);
    }

    private static int percent(float v) {
        return Math.round(v * 100f);
    }

    private static String onOff(boolean on) {
        return on ? "ON" : "OFF";
    }
}
