package cc.starsector.client;

/**
 * OBSERVER client posture ("some way to
 * view a render of the server somewhere ... to more easily identify desync or unsync gaps").
 * Per-INSTANCE presentation posture — never match config, never the kit manifest (the
 * server-settable law covers SETTINGS; this is what THIS window is): {@code -Dcc.observer=on}
 * (alias env {@code CC_OBSERVER}), default OFF, carried by the existing
 * {@code CC_CLIENT_JVM_FLAGS -> JAVA_TOOL_OPTIONS} seam on both launchers.
 *
 * <p>What ON means: the HELLO carries
 * {@code HandshakeCodec.HELLO_FLAG_OBSERVER}; the door admits seat 0 / ship 0 and sets WELCOME
 * bit4 ({@code MatchConfigFromWire.FLAG_SEAT_OBSERVER}) — the session accepts
 * {@code boundShipId 0} ONLY with that bit (fail-closed both ways, {@code
 * PredictReconcileDatagramSession.connect}); {@code CcClientModPlugin} forces the AUTO seat +
 * an EPHEMERAL client id; {@code MatchMissionBuilder} fields NO own flagship, so the engine
 * lazily mints its invisible {@code shuttlepod_xfer} "Command Shuttle" as the player ship
 * (never added to the ship list, its commands are
 * dropped, and it is excluded from the end-of-combat
 * count; the plugin's standing {@code setDoNotEndCombat(true)} holds
 * the empty player side open). S2b (camera + frame dump) reads {@link #frameDir()} et al.
 *
 * <p>Force-loaded at application load (from {@link CcClientModPlugin}, the {@link KnobParse}
 * pre-warm pattern): the in-combat script SecurityManager forbids NEW class loading and the
 * mission builder reads {@link #ON} mid-mission.
 */
public final class ObserverMode {

    /** THIS window is an observer. Resolved once at class init from {@code cc.observer} /
     * {@code CC_OBSERVER}: {@code on|true|1|yes} ⇒ on; unset/blank/{@code off|false|0|no} ⇒ off;
     * anything else ⇒ off + one FALLBACK-ANNOUNCE row (a typo must never silently pilot). */
    public static final boolean ON = resolveOn();

    /** S2b frame-dump knobs (launcher-owned; the launcher creates the dir — {@code
     * java.nio.file.Files} is classloader-B DENIED, the client only opens files inside it). */
    public static final String FRAME_DIR_PROP = "cc.observer.frameDir";
    public static final int DEFAULT_FRAME_EVERY_TICKS = 20;
    public static final int DEFAULT_FRAME_MAX = 1800;

    /** The ANY-window frame dump (a piloted local window recording its own
     * view — the unattended podtest run, or an operator's own flight self-recording for post-hoc grading).
     * Resolved FIRST by {@code ObserverFrameDump}; the observer knobs above stay observer-only.
     * Own cadence/cap knobs: the observer's 1800 cap is sized for a 30-min 1 Hz compare. */
    public static final String FRAMEDUMP_DIR_PROP = "cc.framedump.dir";
    public static final int DEFAULT_FRAMEDUMP_EVERY_TICKS = 20;
    public static final int DEFAULT_FRAMEDUMP_MAX = 6000;

    /**
     * The observer FOLLOW posture knob — what THIS
     * window's auto-follow picks ({@code ObserverCameraLane}); per-instance presentation, never
     * match config (the server is 1:N; a camera preference is not wire-delivered). Values:
     * {@link #FOLLOW_SEAT} (unset/blank = default: the lowest-id living own-side ship, today's
     * behaviour); {@link #FOLLOW_ENGAGEMENT} (the enemy hull nearest ANY own-side hull — the
     * ownerless faction-compare leg wants the enemy in frame; sticky on that enemy until its
     * hulk is gone, then re-picked; an ally while no enemy is followable); {@link #FOLLOW_ENEMY}
     * (the lowest-id living enemy, seat semantics mirrored to side 1). STRICT parse: an unknown
     * value announces once and falls back to seat — never a throw in-game. Resolved ONCE at class
     * init like {@link #ON}; F / Home keys are unchanged by the knob.
     */
    public static final String FOLLOW_PROP = "cc.observer.follow";
    public static final String FOLLOW_SEAT = "seat";
    public static final String FOLLOW_ENGAGEMENT = "engagement";
    public static final String FOLLOW_ENEMY = "enemy";
    /** The resolved follow posture — always one of the three {@code FOLLOW_*} constants. */
    public static final String FOLLOW = resolveFollow();

    /**
     * wq P18: the IN-PROCESS REEL FEED — {@code cc.observer.reel} (env
     * {@code CC_OBSERVER_REEL}) = the absolute path of a serve {@code .ccwr}; unset = a live server.
     * Observer-only (a pilot window ignores it with one announce). With it set the window dials
     * {@code cc.starsector.client.net.ReelLoopbackDoor} instead of any port file / host knob.
     * Siblings, same prop-then-env resolution: {@code cc.observer.reel.speed} (1.0; 0 = unpaced),
     * {@code cc.observer.reel.flags} (hex WELCOME matchFlags, default 0x10 = observer bit only;
     * the 09-05 fixture flight ran 0x15), {@code cc.observer.reel.drop} =
     * {@code <shipId>:<fromTick>-<toTick>} (the negative control: that ship's chunks withheld).
     */
    public static final String REEL_PROP = "cc.observer.reel";

    public static String reel() {
        return propOrEnv(REEL_PROP, "CC_OBSERVER_REEL");
    }

    public static double reelSpeed() {
        return parseReelSpeed(propOrEnv("cc.observer.reel.speed", "CC_OBSERVER_REEL_SPEED"));
    }

    /** Pure parse of a {@code cc.observer.reel.speed} value (a selftest seam — the public getter
     * delegates here byte-identically): {@code null} (unset/blank) ⇒ {@code 1.0}; a finite
     * {@code >= 0} double ⇒ itself ({@code 0} = unpaced, kept as-is); a negative or NaN value ⇒
     * {@code 1.0}; non-numeric garbage ⇒ {@code 1.0} + one FALLBACK-ANNOUNCE. Never throws. */
    static double parseReelSpeed(String raw) {
        if (raw == null) {
            return 1.0d;
        }
        try {
            double v = Double.parseDouble(raw);
            return v < 0d || Double.isNaN(v) ? 1.0d : v;
        } catch (NumberFormatException e) {
            FallbackAnnounce.announceOnce("observer-reel-speed", "knob=cc.observer.reel.speed raw="
                    + FallbackAnnounce.sanitize(raw, 16) + " -> default=1.0");
            return 1.0d;
        }
    }

    public static int reelFlags() {
        return parseReelFlags(propOrEnv("cc.observer.reel.flags", "CC_OBSERVER_REEL_FLAGS"));
    }

    /** Pure parse of a {@code cc.observer.reel.flags} value (a selftest seam — the public getter
     * delegates here byte-identically): {@code null} (unset/blank) ⇒ {@code 0x10} (observer bit
     * only); a hex string with or without the {@code 0x} prefix ⇒ its low 5 bits ({@code & 0x1F},
     * the WELCOME matchFlags width — so {@code 0xFF} clamps to {@code 0x1F}); non-hex garbage ⇒
     * {@code 0x10} + one FALLBACK-ANNOUNCE. Never throws. */
    static int parseReelFlags(String raw) {
        if (raw == null) {
            return 0x10;
        }
        try {
            String v = raw.toLowerCase(java.util.Locale.ROOT);
            return Integer.parseInt(v.startsWith("0x") ? v.substring(2) : v, 16) & 0x1F;
        } catch (NumberFormatException e) {
            FallbackAnnounce.announceOnce("observer-reel-flags", "knob=cc.observer.reel.flags raw="
                    + FallbackAnnounce.sanitize(raw, 16) + " -> default=0x10");
            return 0x10;
        }
    }

    public static String reelDrop() {
        return propOrEnv("cc.observer.reel.drop", "CC_OBSERVER_REEL_DROP");
    }

    /** Pure parse of a {@code cc.observer.reel.drop} value (a selftest seam; {@code
     * ClientLaunchKnobs.resolveReelDoorEndpoint} delegates here): {@code null} (unset/blank) ⇒
     * {@code null} (no negative-control drop); {@code <shipId>:<fromTick>-<toTick>} (all decimal,
     * each part trimmed) ⇒ {@code {shipId, fromTick, toTick}}. A malformed value (no colon, no
     * dash after the colon, wrong order, or a non-numeric part) THROWS — the caller's try/catch
     * turns that into a LOCAL-VANILLA degrade (no dial), because an operator typo must never
     * silently pilot without its withheld-ship control. Semantics are byte-identical to the
     * former inline parse in {@code resolveReelDoorEndpoint}. */
    static long[] parseReelDrop(String raw) {
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        int dash = raw.indexOf('-', colon + 1);
        if (colon <= 0 || dash <= colon) {
            throw new IllegalArgumentException("cc.observer.reel.drop must be <shipId>:<fromTick>-<toTick>; got " + raw);
        }
        return new long[] {
            Long.parseLong(raw.substring(0, colon).trim()),
            Long.parseLong(raw.substring(colon + 1, dash).trim()),
            Long.parseLong(raw.substring(dash + 1).trim()),
        };
    }

    private static String propOrEnv(String prop, String env) {
        String raw = null;
        try {
            raw = System.getProperty(prop);
            if (raw == null || raw.trim().isEmpty()) {
                raw = System.getenv(env);
            }
        } catch (Throwable ignore) {
            return null; // unreadable = unset (fail-soft)
        }
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    private ObserverMode() {
    }

    /** Force-load handle for the application-load pre-warm (see {@link FallbackAnnounce#describe}). */
    public static String describe() {
        return ON ? "ON (seat 0 / ship 0, no own hull; follow=" + FOLLOW + "; frameDir="
                + (frameDir() == null ? "(unset — no frame dump)" : FallbackAnnounce.sanitize(frameDir(), 96))
                + " every=" + frameEveryTicks() + " max=" + frameMax()
                + (reel() != null ? "; REEL FEED " + FallbackAnnounce.sanitize(reel(), 96)
                        + " speed=" + reelSpeed() : "") + ")" : "off";
    }

    /**
     * Pure parse of a {@code cc.observer.follow} value (a selftest seam): {@code null}/blank ⇒
     * {@link #FOLLOW_SEAT}; the three known words (trimmed, case-insensitive) ⇒ their constant;
     * anything else ⇒ {@code null} = unknown (the caller decides the fallback + announce).
     */
    public static String parseFollow(String raw) {
        if (raw == null) {
            return FOLLOW_SEAT;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty() || v.equals(FOLLOW_SEAT)) {
            return FOLLOW_SEAT;
        }
        if (v.equals(FOLLOW_ENGAGEMENT)) {
            return FOLLOW_ENGAGEMENT;
        }
        if (v.equals(FOLLOW_ENEMY)) {
            return FOLLOW_ENEMY;
        }
        return null;
    }

    private static String resolveFollow() {
        String raw = null;
        try {
            raw = System.getProperty(FOLLOW_PROP);
        } catch (Throwable ignore) {
            return FOLLOW_SEAT; // unreadable = default (fail-soft, no announce)
        }
        String mode = parseFollow(raw);
        if (mode == null) {
            FallbackAnnounce.announceOnce("observer-follow-knob", "knob=" + FOLLOW_PROP + " raw="
                    + FallbackAnnounce.sanitize(raw.trim(), 32) + " -> default=" + FOLLOW_SEAT
                    + " (known: seat|engagement|enemy)");
            return FOLLOW_SEAT;
        }
        return mode;
    }

    /** Frame-dump directory or {@code null} = dump disarmed (default). */
    public static String frameDir() {
        String raw = null;
        try {
            raw = System.getProperty(FRAME_DIR_PROP);
        } catch (Throwable ignore) {
            // unreadable property source = unset
        }
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    /** any-window frame-dump directory or {@code null} = not requested (default). */
    public static String frameDumpDir() {
        String raw = null;
        try {
            raw = System.getProperty(FRAMEDUMP_DIR_PROP);
        } catch (Throwable ignore) {
            // unreadable property source = unset
        }
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        return raw.isEmpty() ? null : raw;
    }

    /** any-window dump cadence in server ticks (default {@value #DEFAULT_FRAMEDUMP_EVERY_TICKS}), clamped ≥ 1. */
    public static int frameDumpEveryTicks() {
        return Math.max(1, KnobParse.intOr("cc.framedump.everyTicks", DEFAULT_FRAMEDUMP_EVERY_TICKS,
                "framedump-every"));
    }

    /** any-window dump frame cap per process (default {@value #DEFAULT_FRAMEDUMP_MAX}). */
    public static int frameDumpMax() {
        return Math.max(1, KnobParse.intOr("cc.framedump.max", DEFAULT_FRAMEDUMP_MAX, "framedump-max"));
    }

    /** Frame cadence in server ticks (default {@value #DEFAULT_FRAME_EVERY_TICKS} = 1 Hz at 20 tps), clamped ≥ 1. */
    public static int frameEveryTicks() {
        return Math.max(1, KnobParse.intOr("cc.observer.frameEvery", DEFAULT_FRAME_EVERY_TICKS,
                "observer-frame-every"));
    }

    /** Frame cap per process (default {@value #DEFAULT_FRAME_MAX}) — no pileups on the HDD. */
    public static int frameMax() {
        return Math.max(1, KnobParse.intOr("cc.observer.frameMax", DEFAULT_FRAME_MAX,
                "observer-frame-max"));
    }

    private static boolean resolveOn() {
        String raw = null;
        try {
            raw = System.getProperty("cc.observer");
            if (raw == null || raw.trim().isEmpty()) {
                raw = System.getenv("CC_OBSERVER");
            }
        } catch (Throwable ignore) {
            return false; // unreadable = off (fail-soft, no announce)
        }
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("on") || v.equals("true") || v.equals("1") || v.equals("yes")) {
            return true;
        }
        if (v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no")) {
            return false;
        }
        FallbackAnnounce.announceOnce("observer-mode-knob", "knob=cc.observer raw=" + v
                + " -> default=off");
        return false;
    }
}
