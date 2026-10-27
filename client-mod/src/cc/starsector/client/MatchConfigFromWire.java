package cc.starsector.client;

/**
 * The client-side mirror of the SERVER-OWNED match
 * config — seeded from the v5 WELCOME's {@code u8 matchFlags} tail at BOTH admit lanes
 * (initial connect + B4 re-admit), the {@link ServerTickRate#seedFromWire} discipline. The
 * wire is the ONE source: no sysprop, no kit line, no io (loader-B-legal by construction).
 * Pre-seed default 0 = every flag OFF, so anything reading before admit predicts the
 * certified baseline. Consumers read at MINT time (volatile) — a re-admit delta self-applies
 * at the next respawn mint; a mid-mission flip logs loud here and converges at respawn (no
 * live re-skill pass by design).
 */
public final class MatchConfigFromWire {

    /** WELCOME matchFlags bit0 (W4): every seat captain is the all-skills person and the
     * side-0 commander carries the fleetwide set — both ends mirror or prediction desyncs. */
    public static final int FLAG_OFFICER_SKILLS_ALL = 0x01;

    /** WELCOME matchFlags bit1: this served match is a DEBUG/watch match — the
     * client debug affordances (F8/F10/F11 key verbs, the F9 aim-rebase fence rider, the sys
     * HUD row, the debug hull bars) are live. OFF = public posture: keys revert to vanilla
     * meaning (F11 un-consumed) and the dev rows never render. F9 itself is PUBLIC.
     * Server lever {@code cc.server.clientDebug}, default OFF; pre-seed 0 means
     * an unadmitted client is public-postured by construction. Tracked list:
     * */
    public static final int FLAG_CLIENT_DEBUG = 0x02;

    /** WELCOME matchFlags bit2 (W12 voice L2): the server's
     * {@code cc.voice.enabled}; L3 gates voice capture/playback on it (OFF = no mic capture, no
     * playback). Wire-delivered like its siblings; pre-seed 0 = voice OFF by construction. */
    public static final int FLAG_VOICE_ENABLED = 0x04;

    /** WELCOME matchFlags bit3: THIS seat authenticated with the ADMIN
     * password at HELLO (the {@code *.adminpassword} sidecar; door-minted per session, so unlike
     * its siblings the bit is PER-SEAT, not match-wide). Gates the client's admin affordances:
     * F11/F12 send the {@code /wave skip}/{@code /wave pause} chat verbs and {@code /kick}
     * resolves server-side — every admin VERB is re-checked at the door on the session's own
     * admin flag, so this bit is presentation-only (a forged bit buys nothing). Pre-seed 0 =
     * not admin by construction. */
    public static final int FLAG_SEAT_ADMIN = 0x08;

    /** WELCOME matchFlags bit4: THIS session was admitted as an OBSERVER
     * (HELLO bit1 — {@code HandshakeCodec.HELLO_FLAG_OBSERVER}): seat 0 / ship 0, no hull, the
     * full DOWN fan-out, UP intents dropped server-side. PER-SEAT like bit3. The client half
     * (S2, {@code ObserverMode}) accepts {@code boundShipId 0} ONLY with this bit set and
     * rejects an observer-requested WELCOME that lacks it (fail-closed both ways). Pre-seed 0 =
     * not an observer by construction. Server home {@code HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER}. */
    public static final int FLAG_SEAT_OBSERVER = 0x10;

    private static final int DEFINED_FLAGS = FLAG_OFFICER_SKILLS_ALL | FLAG_CLIENT_DEBUG
            | FLAG_VOICE_ENABLED | FLAG_SEAT_ADMIN | FLAG_SEAT_OBSERVER;

    /** The EXACT {@code /auth} success reply — MIRRORED CONSTANT (server home
     * {@code ChatCommands.AUTH_ELEVATED}; byte-identical or mid-game elevation never reaches the
     * client's presentation bit). The elevation push rides the existing unicast CHTD reply lane
     * — no new wire frame kind; the server re-sends it on an already-admin re-{@code /auth}, so
     * a dropped reply is recovered by re-typing the verb. */
    public static final String AUTH_ELEVATED_REPLY = "admin elevated";

    private static volatile int flags;

    /** mid-game {@code /auth} elevation — set when the server-stamped chat
     * reply matches {@link #AUTH_ELEVATED_REPLY} exactly ({@code ChatLane}'s one receive choke
     * point), cleared on every wire re-seed (a re-admit mints a FRESH server session whose admin
     * bit re-derives from its own HELLO — client elevation must die with it, or F11/F12 would
     * send verbs that bounce {@code admin only}). PRESENTATION-ONLY like bit3: every admin verb
     * re-checks the door's session flag, so a forged flip (a player naming themselves
     * {@code server} and typing the constant) buys only self-inflicted bounced verbs. */
    private static volatile boolean chatElevated;

    private MatchConfigFromWire() {
    }

    /**
     * Adopt the v5 WELCOME's matchFlags (re-seedable on every admit — the wire wins). The
     * decoder already rejected undefined bits both ends (fail-closed law); the in-range guard
     * here keeps the twin self-defending anyway, the {@link ServerTickRate#seedFromWire}
     * refuse-loud posture (keep the current resolution, never a throw). Sink =
     * {@link PilotWatchLog} (combat-SM-safe: pre-warmed at application load).
     */
    public static void seedFromWire(int wireFlags) {
        if ((wireFlags & ~DEFINED_FLAGS) != 0) {
            FallbackAnnounce.announceOnce("matchflags-wire-refused", "flags=0x"
                    + Integer.toHexString(wireFlags) + " carries undefined bits -> keeping=0x"
                    + Integer.toHexString(flags));
            return;
        }
        int prior = flags;
        flags = wireFlags;
        chatElevated = false; // Elevation is per-server-session; every admit re-derives
        if (prior != wireFlags) {
            PilotWatchLog.info("cc-matchconfig wire_seed flags=0x"
                    + Integer.toHexString(wireFlags) + " prior=0x" + Integer.toHexString(prior)
                    + " (change applies at the next mint/respawn)");
        }
        // A host's DEBUG bit (bit1) no longer force-opens the sidelog
        // over a player's "logs off" (cc.watch.sideLog=none) — the player's off WINS.
        //
        // The debug watch the force-open was built for keeps its sidelog
        // by the tooling lane instead (an armed kit gets CC_WATCH_SIDELOG and never sends none).
        //
        if ((wireFlags & FLAG_CLIENT_DEBUG) != 0) {
            // The bit lifts the per-shot forensic log cap to whole-flight
            // capture (the wire-bearing corpus the collimator drill reads); idempotent, and a
            // no-op in rows when the player's logs are off.
            PilotWatchLog.liftForensicCapsForDebugWatch(); // state lives in PilotWatchLog (descriptor verifier compiles this file game-API-free)
        }
    }

    /** Is the W4 all-skills match config ON (WELCOME bit0 — the server lever, wire-delivered)? */
    public static boolean officerSkillsAll() {
        return (flags & FLAG_OFFICER_SKILLS_ALL) != 0;
    }

    /** Is this a debug/watch match (WELCOME bit1 — {@code cc.server.clientDebug},
     * wire-delivered)? Consumers read LIVE per frame (one volatile read): the gate members
     * (F8/F10/F11 verbs, the F9 fence rider, dev HUD rows, debug hull bars) go dead the frame
     * this reads false. */
    public static boolean clientDebug() {
        return (flags & FLAG_CLIENT_DEBUG) != 0;
    }

    /** Is voice chat enabled for this match (WELCOME bit2 — {@code cc.voice.enabled},
     * wire-delivered)? L3 gates mic capture + playback on this (one volatile read per use). */
    public static boolean voiceEnabled() {
        return (flags & FLAG_VOICE_ENABLED) != 0;
    }

    /** Did THIS seat admit with the admin password (WELCOME bit3, per-seat) OR elevate mid-game
     * via {@code /auth} (the exact-reply derivation, {@link #noteChatElevated})?
     * Consumers read LIVE per use (volatile reads); presentation-only — the server re-checks
     * every admin verb at the door, so folding the elevation in HERE flips
     * every admin affordance (F11/F12 verbs) at once with zero consumer edits. */
    public static boolean seatAdmin() {
        return (flags & FLAG_SEAT_ADMIN) != 0 || chatElevated;
    }

    /** Was THIS session admitted as an OBSERVER (WELCOME bit4, per-seat)? Seeded at
     * connect AND every re-admit; presentation-only like {@link #seatAdmin} (the door already
     * drops an observer's intents/CTRL at ingest). The S2b camera / frame-dump lanes and the
     * selftest read it; the admission GATE itself lives in {@code
     * PredictReconcileDatagramSession.connect} on the raw WELCOME flags, not here. */
    public static boolean seatObserver() {
        return (flags & FLAG_SEAT_OBSERVER) != 0;
    }

    /** The one elevation-set site — {@code ChatLane.enqueueChatMessage} on an
     * exact {@link #AUTH_ELEVATED_REPLY} match from the server-stamped reply lane (net thread;
     * volatile write). Cleared only by {@link #seedFromWire} (fresh admit = fresh session). */
    public static void noteChatElevated() {
        if (!chatElevated) {
            chatElevated = true;
            PilotWatchLog.info("cc-matchconfig chat_elevated (mid-game /auth reply matched;"
                    + " admin affordances live until the next re-admit)");
        }
    }
}
