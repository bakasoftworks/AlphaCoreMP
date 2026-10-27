package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * The D6 O-0 operator house file (frozen law):
 * a polled command file giving the operator verbs INTO the N-peer serve loop —
 * {@link ServerStatusLine}'s stdin {@code stop} stays scout-only. Grammar v0 = FIXED verbs, one
 * per line: {@code kick <seatId> [<cidkey>]} / {@code end-round} / {@code end-match} /
 * {@code set-sim-speed <multiplier>} (announced-dilation pacing multiplier at
 * frozen dt, clamped to [{@value #SIM_SPEED_MIN}, {@value #SIM_SPEED_MAX}] — out-of-range
 * REJECTS, never clamps silently) / {@code sim-speed-auto on|off} (arm/disarm the
 * A2/A3 auto governor; ANY manual {@code set-sim-speed} disarms it server-side) /
 * {@code skip-wave} (owner fast-testing verb — force-kill the live wave
 * field through the engine's own damage lane and let the schedule advance; F11 in-game rides
 * the launcher's log-tail bridge to this verb, the F10 {@code end-match} precedent) /
 * {@code pause-wave} (toggle — freeze the wave feed + its timer clock
 * so the operator can inspect a voice/HUD test without the next wave landing; F12 in-game rides the
 * same bridge) /
 * {@code voice <knob> <value>} (W12 L2: live {@link VoiceConfig} retune — knobs
 * {@value VoiceConfig#KNOB_NAMES}; the relay re-sends VCFG to every live session) /
 * {@code voice set <knob>=<value> ...} (the ATOMIC multi-knob retune — one
 * jointly-validated sheet, all pairs applied or none; the ops Voice panel's Apply verb) /
 * {@code set-joiner-default <variantId|none>} (the server-owned joiner default
 * variant; {@code none} clears) /
 * {@code spawn-foe <variantId> [count]} (field side-1 AI foes mid-round — the
 * {@code /spawnfoe} chat verb's house-file twin; the specimen director's feed) /
 * {@code wave-category <type> [reset|stay]} (retarget the UNSPAWNED
 * wave tail's faction immediately — the round-boundary park is gone; {@code reset}
 * also re-opens the chain at its first wave, {@code stay} = the bare form) /
 * {@code wave-set <1..9>} (operator-numbered wave {@code n} spawns next) /
 * {@code endless on|off|show} (flip or state the endless-chain dial live) /
 * {@code wave-inflate on|off|show} (flip or state the wave loadout-variety dial live —
 * vanilla fleet inflation over the wave rows; the {@code /wave inflate} chat verb's twin) /
 * {@code pod-test <scenario> <seat>} (stage a pod scenario for a seat) /
 * {@code god-mode <seat> on|off} (the {@code /godmode} chat verb's house-file
 * twin — the ownerless arena rig's seat survives its foe; no bare toggle from a file) /
 * {@code ban <seat> [<cidkey>]} / {@code ban-src <seat> [<cidkey>]} / {@code ban-cid <hexkey>} /
 * {@code ban-session <sessionId>} / {@code ban-ip <address or range>} (a
 * source ban by a typed address, IPv4 range or IPv6 range, {@link Verb#BAN_IP}) /
 * {@code unban cid|src|ip <key>} / {@code bans} (the player ban verbs;
 * {@link BanSet} keys, the serve owns the effect;
 * the {@code [<cidkey>]} expected-occupant tail refuses a seat that changed hands);
 * {@code admin-cid <hexkey>} (arm the operator's own seat key as admin at the password door for this
 * serve's life; its argument never reaches a log or the reel, {@link #redactOperatorLine} / {@link #recordedText});
 * verbs are case-insensitive, blank and {@code #}-comment lines are skipped, and every other line is
 * REJECTED for the caller to log + ignore (fail-soft — an operator file is not a wire input).
 *
 * <p>The file is CONSUMED on read: delete first, truncate as the fallback, and if BOTH fail the
 * poller DISABLES itself for the serve (loud log) — a command that cannot be consumed must never
 * refire every poll ({@code end-round} re-firing each 250 ms would wrap every round). A missing
 * file is the normal idle state; a read that fails mid-poll (operator's editor mid-write, Windows
 * sharing) just retries next poll. The PATH is the caller's to resolve LOUD at serve boot (house
 * rule: invalid knob values THROW — {@link Path#of} does); only file CONTENT is fail-soft.
 * Content is read BOM-aware (UTF-8 / UTF-16LE / UTF-16BE by BOM, else strict UTF-8) under a
 * {@value #MAX_COMMAND_FILE_BYTES}-byte cap (an oversize file is consumed whole with one row), an
 * undecodable file is consumed with one row on the second probe that reads the same bytes, and every
 * rejected line is echoed bounded ({@link #boundedReject}).
 *
 * <p>Residency (frame-loop residency law): the filesystem probe runs on
 * the {@value #POLLER_THREAD} daemon every {@value #POLL_INTERVAL_MILLIS} ms; the sim thread's
 * {@link #poll()} only drains a queue. It used to be a throttled SYNC read on the sim thread —
 * measured as a 168 ms {@code pre_op} frame stall (an HDD seek),
 * the ONE live sim-thread file op in the public shape.
 */
public final class OperatorCommandFile {

    /** Daemon cadence: one filesystem probe per this many millis (~4/s). */
    public static final long POLL_INTERVAL_MILLIS = 250L;
    /** The daemon's thread name (residency allow-list row `off-thread:cc-op-poller`). */
    public static final String POLLER_THREAD = "cc-op-poller";

    /** The largest command file a probe reads (bytes; inclusive). Why a cap: the read + decode run
     *  on the {@value #POLLER_THREAD} daemon, but the parse result lands on the SIM thread — the
     *  serve's {@link #poll()} drains it there and runs (or logs) every line, so an unbounded file
     *  (a stray paste, a log redirected here) would put unbounded work on the frame loop. An
     *  oversize file is consumed WHOLE with one {@code operator_file_oversize} row — never a partial
     *  parse. 64 KB is thousands of verb lines; an operator file is a handful.
     * */
    public static final int MAX_COMMAND_FILE_BYTES = 64 * 1024;

    /** The longest rejected line {@link #parse} keeps (chars; {@link #boundedReject}). */
    static final int REJECT_ECHO_MAX_CHARS = 120;

    /** One parsed {@code BAN_SESSION} deposit text — {@code sessionId}
     *  is any NON-ZERO signed long (the door mints {@code SecureRandom.nextLong()} minus 0; the
     *  serve guard {@code <= 0L} refused every negative id = half of all chat {@code /ban #id});
     *  {@code cidKeyOrNull} = the UNDECODED expected cid key (a chat deposit, A4), its recorded digest
     *  (a replayed reel row, {@link #isKeyDigest}), or null (house file).
     *  {@code termOrNull} = the chat deposit's {@code term=}
     *  token (null = none: the serve applies {@link BanTerm#DEFAULT} to a chat ban). */
    public record BanSessionDeposit(long sessionId, String cidKeyOrNull, BanTerm termOrNull) {
        public BanSessionDeposit(long sessionId, String cidKeyOrNull) {
            this(sessionId, cidKeyOrNull, null);
        }
    }

    /** The chat deposits' term token prefix — REQUIRED (a bare
     *  {@code 1d} is even-length lowercase hex = a lawful cid KEY, so it never reads as a term). */
    public static final String TERM_PREFIX = "term=";

    /** {@code "<sessionId>"} or {@code "<sessionId> <cidKey>"} → the deposit the serve acts on;
     *  {@code null} = refused ({@code target changed}): id 0 / not a long, or a present key that is
     *  not a cid key. Pure — the serve's {@code operatorBan} calls it; a contract row pins it.
     *  + an optional {@code term=<tok>} LAST token
     *  ({@code "<sid> <cidKey> term=<tok>"}, or {@code "<sid> term=<tok>"} for a cid-less target);
     *  a non-ladder token, or a 3rd token without the prefix, refuses.
     *  <p>The key may instead be its recorded digest ({@link #isKeyDigest}): the reel records a chat ban's key
     *  that way ({@link #recordedText}), and a re-simulation hands the recorded text back to the serve. Only a
     *  replay door acts on it (its {@code banSession} skips the key compare: the replay occupant carries the door's
     *  own client id); a live door compares the expected key with {@code BanSet.cidKey} of a real client id,
     *  which a digest never equals, so a live deposit carrying one answers {@code target_changed}, fail-closed.
     *  The chat door mints the raw key; the house file mints no key at all.
     * */
    public static BanSessionDeposit parseBanSessionDepositOrNull(String text) {
        String[] parts = text.split(" ", 3);
        long sessionId;
        try {
            sessionId = Long.parseLong(parts[0]);
        } catch (NumberFormatException notALong) {
            return null; // the file grammar admits signed digits only; a chat deposit is door-minted
        }
        String tail = parts.length == 3 ? parts[2]
                : parts.length == 2 && parts[1].startsWith(TERM_PREFIX) ? parts[1] : null;
        String key = parts.length == 3 || (parts.length == 2 && tail == null) ? parts[1] : null;
        BanTerm term = tail == null || !tail.startsWith(TERM_PREFIX) ? null
                : BanTerm.parseOrNull(tail.substring(TERM_PREFIX.length()));
        if (sessionId == 0L || (key != null && BanSet.cidOfKeyOrNull(key) == null && !isKeyDigest(key))
                || (tail != null && term == null)) {
            return null;
        }
        return new BanSessionDeposit(sessionId, key, term);
    }

    /** One parsed {@code BAN_CID} text — {@code key} (unchecked
     *  here: the door's grammar decides) and the {@code /banned} retime's term (null = the house
     *  {@code ban-cid <key>}, the permanent offline ban; its PRESENCE selects the retime, surprise 6). */
    public record BanCidDeposit(String key, BanTerm termOrNull) {}

    /** {@code "<key>"} or {@code "<key> term=<tok>"} → the deposit; null = a tail that is not exactly
     *  one {@code term=} ladder token (the serve refuses it as a bad key — never a ban by the raw text). */
    public static BanCidDeposit parseBanCidDepositOrNull(String text) {
        int space = text.indexOf(' ');
        if (space < 0) {
            return new BanCidDeposit(text, null);
        }
        String tail = text.substring(space + 1);
        BanTerm term = tail.startsWith(TERM_PREFIX) ? BanTerm.parseOrNull(tail.substring(TERM_PREFIX.length())) : null;
        return term == null ? null : new BanCidDeposit(text.substring(0, space), term);
    }

    public enum Verb {
        KICK, END_ROUND, END_MATCH, SET_SIM_SPEED, SET_SIM_SPEED_AUTO, SKIP_WAVE,
        /** {@code pause-wave} — toggle the wave schedule's pause
         * (clock frozen, never a catch-up burst); the serve prints the new state. */
        PAUSE_WAVE,
        /** W12 L2: {@code voice <knob> <value>} — a live {@link VoiceConfig}
         * retune ({@code text} = {@code "knob value"}); the relay validates and re-sends VCFG.
         * {@code voice set k=v ...} rides this verb too with
         * {@code text} = {@code "set k=v k=v"} (atomic, all-or-nothing). */
        VOICE,
        /** {@code set-joiner-default <variantId|none>} — the server-owned joiner
         * default variant; {@code none} clears (descriptor row). The serve canonicalizes
         * against the door's pickable allowlist and refuses unpickable ids loud. */
        SET_JOINER_DEFAULT,
        /** {@code spawn-foe <variantId> [count] [@x,y]} — field side-1 AI foes
         * mid-round (the {@code /spawnfoe} chat verb's house-file twin + the headless-proof
         * lane); {@code text} = {@code "variantId count[ @x,y]"}, count validated here
         * (1..{@link ChatCommands#SPAWNFOE_MAX_COUNT}, absent = 1), the optional LAST pose token
         * by {@link ChatCommands#parseSpawnPoseOrNull} (the serve applies the
         * map bounds and REFUSES an outside pose, never clamps). */
        SPAWN_FOE,
        /** {@code spawn-friend <variantId> [count] [@x,y]}
         * — {@link #SPAWN_FOE} VERBATIM (same parse, same {@code text} shape, same queue) but
         * the hull fields on the PLAYER side (side 0): the {@code /spawnfriend} chat verb's
         * house-file twin + the friendly-lash headless-proof lane ({@code -LashFriendly}). */
        SPAWN_FRIEND,
        /** {@code despawn <wireId>} — remove a NON-SEAT
         * hull from the field through the engine's own removeObject (a vanish, never a kill: no
         * hulk, the EDSP classifier publishes DEPARTED, every client evicts it corpse-free); the
         * {@code /despawn #id} chat verb's house-file twin. {@code text} = the bare digits
         * (strict, Long-bounded, > 0); the serve's engine port owns every refusal (a seat's
         * wire, a transfer pod, an unknown id) and mails the outcome as the receipt. */
        DESPAWN,
        /** {@code wave-category <type>} — switch the fortress wave FACTION (the
         * {@code /wave category} chat verb's house-file twin); {@code text} = the folded
         * category, validated here against the FIXED
         * {@link cc.starsector.server.match.WaveSchedule#WAVE_CATEGORIES} set. The serve side
         * owns the semantic (wave-shape-only; applied IMMEDIATELY to the
         * unspawned tail — the round-boundary park is gone). An
         * optional second word {@code reset} (carried as {@code "<type> reset"} in {@code text})
         * also re-opens the chain at its first wave; {@code stay} = the bare form. */
        SET_WAVE_CATEGORY,
        /** {@code wave-set <1..9>} — operator-numbered wave {@code n} becomes the
         * NEXT wave to spawn (the {@code /wave set} chat verb's house-file twin); {@code text}
         * = the bare digit (bounded 1..9 at BOTH mint sites; the serve refuses past the chain's
         * last wave with a receipt, never clamps). */
        SET_WAVE_NUMBER,
        /** {@code endless on|off|show} — flip or state the endless-chain dial
         * live (the {@code /endless} chat verb's house-file twin); {@code text} = the folded
         * word. The serve owns the semantic ({@code on} beside the per-clear reset lane is a
         * refused receipt — the mint-time throw made live). */
        SET_ENDLESS,
        /** {@code wave-inflate on|off|show} — flip or state the wave loadout-variety
         * dial live (the {@code /wave inflate} chat verb's house-file twin); {@code text} = the
         * folded word. The serve owns the semantic (ON pre-inflates the live chain; no lane on
         * this serve = a refused receipt, never a throw). */
        SET_WAVE_INFLATE,
        /** {@code knob list} / {@code knob <knob> show} / {@code knob <knob> <word>}
         * — the wire-delivered client knob census ({@code WireKnob}; the {@code /knob} chat verb's
         * house-file twin); {@code text} = the folded, FIXED-set-validated words. The serve owns
         * the set + the PSTB re-spool to every session + the receipt ({@code AdminKnobVerb}). */
        SET_KNOB,
        /** {@code terrain show} / {@code terrain <word>} / {@code terrain
         * <word> <level>} — the battle terrain pair ({@code WireKnob.TERRAIN} + {@code
         * TERRAIN_LEVEL}; the {@code /terrain} chat verb's house-file twin); {@code text} = the
         * folded, FIXED-set-validated words. The serve owns set + re-spool + the live-ship
         * re-apply + the receipt ({@code AdminTerrainVerb}). */
        SET_TERRAIN,
        /** {@code pod-test <scenario> <seat>} — stage a pod scenario for a
         * seat (the {@code /podtest} chat verb's house-file twin, for unattended test runs).
         * {@code text} = the folded scenario token, validated here against the FIXED
         * {@link PodScenarioDriver#TOKENS} set; {@code seatId} = the target seat (1..
         * {@value #POD_TEST_MAX_SEAT}). The serve loop's driver owns every semantic. */
        POD_TEST,
        /** The {@code /godmode} chat verb's queue carrier —
         * {@code seatId} = the CALLER's seat (door-bound, never typed), {@code text} = the
         * grammar-folded mode {@code toggle|on|off}. House-file twin {@code god-mode <seat> on|off}
         * (the file's bounded seat int, no bare toggle); {@link AdminSeatDebugLane} owns the
         * effect on the sim thread. */
        GOD_MODE,
        /** The {@code /teleport @x,y} chat verb's queue carrier — {@code seatId} =
         * the CALLER's seat, {@code text} = the grammar-validated {@code @x,y} token verbatim
         * (re-validated + map-clamped by {@link AdminSeatDebugLane} on the sim thread). */
        TELEPORT,
        /** {@code switch-team <seat> instant|respawn} — move a
         * player seat to the OTHER team (the {@code /switchteams} chat verb's queue carrier +
         * house-file twin, the headless-proof lane). {@code seatId} = the TARGET seat (door-
         * resolved from the menu's wire id, or the file's bounded int); {@code text} = the
         * folded mode. {@link SeatTeamLane} owns the effect on the sim thread. */
        SWITCH_TEAM,
        /** {@code team-pick <seat> north|south} — SET a seat's next fly-in side (never a toggle). The
         * carrier of every team change the door decided under its lock (a player's {@code /team}, a
         * cross-filled or restored claim, an admin switch in the PvP shape): the serve mints it from the
         * session's pending team side AFTER the admin drain, so it lands as one CMD row a re-simulation
         * replays. {@code seatId} = the seat (1..255); {@code text} = {@code "north"} (side 1) or
         * {@code "south"} (side 0). {@link SeatTeamLane#pickTeam} owns the effect on the sim thread
         * and re-checks the seat's holder first.
         * */
        TEAM_PICK,
        /** W15 v0 step 3(f): {@code custom-submissions on|off} — the
         * lane's live accept gate ({@code autoOn} carries on/off, the sim-speed-auto shape). */
        CUSTOM_SUBMISSIONS,
        /** {@code custom-reject <seat>} — drop the seat's pending customs, ban its accepted ones,
         * refuse its author for the serve ({@code seatId} = the target, the kick shape). */
        CUSTOM_REJECT,
        /** {@code custom-ban <id>} — one minted {@code cc-v…} id becomes unpickable
         * ({@code text} = the id, the set-joiner-default charset law). */
        CUSTOM_BAN,
        /** {@code custom-export <id>} — copy the accepted custom to {@code variants-inbox/}
         * (+ {@code .meta}) off-thread ({@code text} = the id). */
        CUSTOM_EXPORT,
        /** {@code custom-allow <seat>} — the inverse of {@code custom-reject}:
         * lift the seat's author block and un-ban the author's customs ({@code seatId} = the target). */
        CUSTOM_ALLOW,
        /** {@code custom-unban <id>} — the inverse of {@code custom-ban}
         * ({@code text} = the id, the set-joiner-default charset law). */
        CUSTOM_UNBAN,
        /** {@code custom-keep <id>} — keep this run's accepted
         * custom for every player: the writer saves it under {@code variants-inbox/kept/}, the catalog
         * lane lists it on {@code /ship} ({@code text} = the id, the set-joiner-default charset law). */
        CUSTOM_KEEP,
        /** {@code custom-keep-export <name>} — keep an inbox file (an earlier export {@code cc-v…} or a
         * forgotten {@code kept-…} file) for every player ({@code text} = the file stem, the same law). */
        CUSTOM_KEEP_EXPORT,
        /** {@code custom-forget <stem>} — take a kept ship off {@code /ship}: its files move back to the
         * inbox, never deleted ({@code text} = the kept stem, the same law). */
        CUSTOM_FORGET,
        /** The host's ship-list verbs, each {@code text} = ONE token under
         * {@link #joinerDefaultTokenLawful} (case preserved; the serve classifies the id, never the parser).
         * {@code ship-hide <id>} — take a ship that comes with the server off {@code /ship} for every player. */
        SHIP_HIDE,
        /** {@code ship-show <id>} — put a hidden ship that comes with the server back on {@code /ship}. */
        SHIP_SHOW,
        /** {@code ship-remove <id>} — a shipped id hides, a kept ship is taken off the list (the serve decides). */
        SHIP_REMOVE,
        /** {@code ship-restore <id>} — a hidden shipped id shows, a removed kept ship is kept again. */
        SHIP_RESTORE,
        /** {@code ship-list} (no argument) — the caller's ship-list reply ({@code text = ""}). */
        SHIP_LIST,
        /** {@code ship-keep <id>} — keep this run's accepted custom for every player (the custom-keep effect). */
        SHIP_KEEP,
        /** {@code ban <seatId>} — ban the
         * seat's cid (its live session; offline = the seat's most recent session, receipt
         * {@code offline}); {@code seatId} = the target (the kick shape).
         * {@code ban <seatId> <cidkey>} = the operator's EXPECTED
         * occupant ({@code text} = the key, "" when absent) — a seat that changed hands answers
         * {@code target_changed} (live AND offline arms); the GUI/web always send it. */
        BAN,
        /** {@code ban-src <seatId> [<cidkey>]} — ban the LIVE seat's source key (IPv4 / IPv6 /56;
         * the CGNAT/LAN collateral is the operator's call); {@code seatId} = the target,
         * {@code text} = the optional expected-occupant cid key (as {@link #BAN}). */
        BAN_SRC,
        /** {@code ban-cid <hexkey>} — an offline cid ban by its key ({@code text} = the key,
         * {@link BanSet#isCidKey}; the listings print it). */
        BAN_CID,
        /** {@code ban-session <sessionId>} — ban one live session's cid (observers / unseated;
         * the chat {@code /ban} deposit carries {@code "<sessionId> <cidKey>"}, A4). */
        BAN_SESSION,
        /** {@code ban-ip <address or range>} — a permanent source ban by
         * a TYPED key, no seat needed: a bare IPv4 address, an IPv4 range ({@code 1.2.3.0/24}), an IPv6 key
         * as a bans listing prints it, or an IPv6 provider range ({@code 2a0b:f4c0::/32}, converted);
         * {@code text} = the stored key ({@link SourceRanges#canonicalOrNull}), so the reel, the file and
         * the receipt carry one form. A live session whose peer the key covers is swept like {@link #BAN_SRC}'s. */
        BAN_IP,
        /** {@code unban cid|src <key>} — lift one ban ({@code text} = {@code "<kind> <key>"}; the
         * kind token is required — a cid key can look like an IPv4 literal, A1). {@code unban ip <key>}
         * is {@code unban src <key>} (its text says {@code src}); the key is the stored form only. */
        UNBAN,
        /** {@code bans} — list every ban to the log + a count receipt. */
        BANS,
        /** {@code admin-cid <hexkey>} — arm one seat key ({@code text} = the key,
         * {@link BanSet#isCidKey}) into the door's armed set ({@link PilotIntentDatagramServer#armAdminCid},
         * at most {@value PilotIntentDatagramServer#ARMED_ADMIN_CAP} keys): the pilot HELLO presenting that
         * cid at the password door is admitted AS ADMIN and is neither counted nor capped by the per-address
         * seat cap, and a live holder of it is elevated at the arm. The set dies with this serve (no
         * persistence: a relaunched serve admits the host plain until {@code /auth}). WHY: the console's
         * own window carries ONE password slot that a pasted brick can overwrite - the host's elevation
         * must ride the seat key the console minted, never that slot. Deposited only by the console's
         * press (an operator surface; cid keys never reach a player), and the key never reaches a log
         * row or the reel ({@link #redactOperatorLine}, {@link #recordedText}).
         * */
        ADMIN_CID
    }

    /** The largest seat id the {@code pod-test} / {@code switch-team} / {@code god-mode}
     * file verbs accept (a bounded int — the operator lane is trusted, the bound only keeps a typo
     * from reaching the driver). It tracks the match's own seat cap, so every seat a match can mint
     * is addressable here; the three parse sites read at most 3 digits, so the bound is checked on a
     * value that cannot overflow.
     * */
    public static final int POD_TEST_MAX_SEAT = cc.starsector.server.match.MatchDescriptors.MAX_SEATS;

    /** {@code set-joiner-default} argument charset — the wire id law (the JoinGui's
     * offerable charset + the HELLO variantId bound of 64), so a verb can never carry a
     * non-printable or over-long token into the door's log/canonicalizer. */
    public static final int JOINER_DEFAULT_MAX_CHARS = 64;

    /** {@code set-sim-speed} accepted range (the terminology pin: the mid-round
     * control is SIM SPEED — a pacing multiplier at frozen dt; {@code sim.tickRate} stays the
     * per-MATCH granularity knob). */
    public static final float SIM_SPEED_MIN = 0.25f;
    public static final float SIM_SPEED_MAX = 2.0f;

    /** One parsed verb; {@code seatId} is meaningful for {@link Verb#KICK} only (else 0);
     * {@code simSpeed} for {@link Verb#SET_SIM_SPEED} only (else 0); {@code autoOn} for
     * {@link Verb#SET_SIM_SPEED_AUTO} only (else false); {@code receiptSessionId} is the
     * chat return-leg — the depositing session for a CHAT-sourced admin verb
     * (wave verbs + D6 {@code SPAWN_FOE}: the serve loop mails the drained verb's REAL effect
     * back as the chat reply), 0 for every file/GUI mint (no receipt lane; this parser always
     * mints 0). */
    public record Command(Verb verb, int seatId, float simSpeed, boolean autoOn, String text,
            long receiptSessionId) {
        public Command {
            Objects.requireNonNull(verb, "verb");
            Objects.requireNonNull(text, "text");
        }

        /** The pre-D5 shape — every non-chat mint (file/GUI/tests; no receipt lane). */
        public Command(Verb verb, int seatId, float simSpeed, boolean autoOn, String text) {
            this(verb, seatId, simSpeed, autoOn, text, 0L);
        }

        /** The text-less shape — every verb without a text argument ({@code text = ""}). */
        public Command(Verb verb, int seatId, float simSpeed, boolean autoOn) {
            this(verb, seatId, simSpeed, autoOn, "", 0L);
        }
    }

    /** Argument law for {@code set-joiner-default}: 1..64 chars of
     * {@code [A-Za-z0-9._:-]}; {@code none} (case-folded) = clear. */
    static boolean joinerDefaultTokenLawful(String token) {
        if (token.isEmpty() || token.length() > JOINER_DEFAULT_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ':' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Grammar law for {@code voice set k=v ...}
     * ({@code parts[0..1]} = {@code voice set}): 1..{@link VoiceConfig#KNOB_COUNT} pairs, each
     * {@code [A-Za-z]{1,24}=[A-Za-z0-9.]{1,8}} (the per-knob verb's charsets), no knob twice.
     * Knob NAMES + value ranges stay the relay's (VoiceConfig) to refuse. */
    static boolean voiceSetLawful(String[] parts) {
        int pairs = parts.length - 2;
        if (pairs < 1 || pairs > VoiceConfig.KNOB_COUNT) {
            return false;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 2; i < parts.length; i++) {
            if (!parts[i].matches("[A-Za-z]{1,24}=[A-Za-z0-9.]{1,8}")
                    || !seen.add(parts[i].substring(0, parts[i].indexOf('=')))) {
                return false;
            }
        }
        return true;
    }

    /** Pure parse outcome: accepted commands in file order + the rejected lines (trimmed, redacted by
     *  {@link #redactOperatorLine}, then bounded by {@link #boundedReject} — the form a caller may log). */
    public record ParseResult(List<Command> commands, List<String> rejected) {
        public static final ParseResult EMPTY = new ParseResult(List.of(), List.of());

        public boolean isEmpty() {
            return commands.isEmpty() && rejected.isEmpty();
        }

        /** {@code a} then {@code b}, file order preserved (D-LOBBYEND carry: verbs read during
         *  the lobby wait that are not lobby verbs ride into the first in-round poll). */
        public static ParseResult concat(ParseResult a, ParseResult b) {
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            List<Command> commands = new ArrayList<Command>(a.commands());
            commands.addAll(b.commands());
            List<String> rejected = new ArrayList<String>(a.rejected());
            rejected.addAll(b.rejected());
            return new ParseResult(List.copyOf(commands), List.copyOf(rejected));
        }
    }

    private final Path file;
    /** Daemon → sim-thread handoff: one entry per non-empty file read, in read order. */
    private final ConcurrentLinkedQueue<ParseResult> ready = new ConcurrentLinkedQueue<ParseResult>();
    /** Serializes the daemon's probe against {@link #pumpNow()} (read+consume+enqueue is one step). */
    private final Object probeLock = new Object();
    private volatile boolean consumeBroken;
    /** The two-probe identity: the byte length + {@link Arrays#hashCode(byte[])} of the last
     *  UNDECODABLE read ({@code -1} = none pending). Guarded by {@link #probeLock} (only
     *  {@link #readOnce} touches them). */
    private int undecodableLength = -1;
    private int undecodableHash;
    private volatile boolean stopRequested;
    private volatile Thread poller;
    /** False = {@link #probeOnDemand}: {@link #poll()} never starts the daemon. */
    private final boolean daemonOnPoll;

    /** @throws java.nio.file.InvalidPathException on an unusable path (loud at serve boot). */
    public OperatorCommandFile(String path) {
        this(path, true);
    }

    private OperatorCommandFile(String path, boolean daemonOnPoll) {
        this.file = Path.of(path.trim());
        this.daemonOnPoll = daemonOnPoll;
    }

    /**
     * A probe-on-demand instance: {@link #poll()} NEVER starts the {@value #POLLER_THREAD}
     * daemon — every filesystem probe is the caller's own {@link #pumpNow()}. The contract-suite
     * seam: the daemon's lazy start on the first {@code poll()} made a
     * fixture's own {@code Files.writeString} race a concurrent reader+consumer it could not
     * control — the "flakes" (the loss = {@link #readOnce}'s
     * empty-read law). A serve never uses this; {@link #start()} stays available if a host
     * wants the daemon later.
     */
    public static OperatorCommandFile probeOnDemand(String path) {
        return new OperatorCommandFile(path, false);
    }

    public Path file() {
        return file;
    }

    /** True once consume failed hard and polling self-disabled for this serve. */
    public boolean disabled() {
        return consumeBroken;
    }

    /**
     * Sim-thread entry: drains whatever {@value #POLLER_THREAD} read since
     * the last call — commands in file/read order, never touching the filesystem here. The
     * daemon is started lazily on the first call (the serve constructs this at boot and polls
     * from frame 0, so the seam stays one call site); an empty result is the normal idle state.
     */
    public ParseResult poll() {
        if (daemonOnPoll && poller == null) {
            start();
        }
        ParseResult first = ready.poll();
        if (first == null) {
            return ParseResult.EMPTY;
        }
        ParseResult next = ready.poll();
        if (next == null) {
            return first;
        }
        List<Command> commands = new ArrayList<Command>(first.commands());
        List<String> rejected = new ArrayList<String>(first.rejected());
        do {
            commands.addAll(next.commands());
            rejected.addAll(next.rejected());
        } while ((next = ready.poll()) != null);
        return new ParseResult(List.copyOf(commands), List.copyOf(rejected));
    }

    /** Idempotent: launches the {@value #POLLER_THREAD} daemon (one per instance). */
    public synchronized void start() {
        if (poller != null) {
            return;
        }
        Thread t = new Thread(this::pollLoop, POLLER_THREAD);
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    /** Serve teardown: stops the daemon (a daemon thread never blocks JVM exit; this is tidy). */
    public void stop() {
        stopRequested = true;
        Thread t = poller;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * One synchronous probe on the CALLER's thread — the same read+consume+enqueue step the
     * daemon runs every {@value #POLL_INTERVAL_MILLIS} ms, serialized against it, so when this
     * returns the file's content (whichever thread probed it) is queued for the next
     * {@link #poll()}. The deterministic seam for the contract suite (
     * the daemon starves under a {@code -BelowNormal} build, so a wall-clock wait on it flaked).
     * NEVER called from the sim thread — the read is a disk seek (residency
     * law, class javadoc).
     *
     * @return true when this probe itself enqueued a non-empty result
     */
    public boolean pumpNow() {
        return probeOnce();
    }

    private boolean probeOnce() {
        synchronized (probeLock) {
            ParseResult read = readOnce();
            if (read.isEmpty()) {
                return false;
            }
            ready.add(read);
            return true;
        }
    }

    private void pollLoop() {
        while (!stopRequested && !consumeBroken) {
            probeOnce();
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    /**
     * One filesystem probe — DAEMON THREAD ONLY (always under {@link #probeLock}): read + consume
     * the file, parse its lines. Empty result while disabled, file missing, or unreadable this
     * probe (all normal; never throws).
     *
     * <p>The read is BOUNDED — a file over
     * {@link #MAX_COMMAND_FILE_BYTES} is consumed whole with one {@code operator_file_oversize}
     * row (the size is probed first, so an oversize file is never loaded) — and decoded by
     * {@link #decodeCommandFile}. An UNDECODABLE read keeps the old mid-save grace for ONE probe
     * (a writer caught mid-multibyte-char); a later probe that reads the SAME bytes (length +
     * hash) consumes the file with one {@code operator_file_undecodable} row. Before this, a
     * {@code MalformedInputException} read as "mid-write" forever: a UTF-16 (Windows PowerShell 5.1
     * {@code >}) or ANSI save was never consumed nor logged, and every file / console verb
     * appended behind it was silently dropped for the rest of the serve.
     * Rows carry bounded tokens only (counts, the operator-config path) — never file content.
     */
    ParseResult readOnce() {
        if (consumeBroken || !Files.isRegularFile(file)) {
            return ParseResult.EMPTY;
        }
        long size;
        byte[] bytes = null;
        try {
            size = Files.size(file);
            if (size <= MAX_COMMAND_FILE_BYTES) {
                try (InputStream in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(MAX_COMMAND_FILE_BYTES + 1);
                }
                if (bytes.length > MAX_COMMAND_FILE_BYTES) {
                    return ParseResult.EMPTY; // grew past the cap since the size probe: mid-save, retry
                }
            }
        } catch (IOException | RuntimeException midWrite) {
            return ParseResult.EMPTY; // operator mid-write / sharing race: retry next probe
        }
        if (bytes == null) {
            undecodableLength = -1;
            System.out.println("npeer-serve operator_file_oversize bytes=" + size + " cap="
                    + MAX_COMMAND_FILE_BYTES + " path=" + file);
            consumeOrDisable();
            return ParseResult.EMPTY;
        }
        String text = decodeCommandFile(bytes);
        if (text == null) {
            int hash = Arrays.hashCode(bytes);
            if (undecodableLength != bytes.length || undecodableHash != hash) {
                undecodableLength = bytes.length; // first sighting of these bytes: the mid-save grace
                undecodableHash = hash;
                return ParseResult.EMPTY;
            }
            undecodableLength = -1; // the next undecodable save starts a new two-probe cycle
            System.out.println("npeer-serve operator_file_undecodable bytes=" + bytes.length
                    + " path=" + file);
            consumeOrDisable();
            return ParseResult.EMPTY;
        }
        undecodableLength = -1;
        List<String> lines = text.lines().toList(); // readAllLines' split: \n, \r, \r\n; no empty tail line
        if (lines.isEmpty()) {
            // EMPTY-READ LAW: a 0-line read is a writer's create->write
            // window (an operator's editor save, a fixture's Files.writeString) — never a
            // command. Consuming it here unlinks or TRUNCATES the file the writer is about to
            // fill: the run-1 contract loss ("post-throttle poll serves the batch:
            // expected 2, got 0" with no disable print) reconstructs exactly as a probe that
            // read 0 lines mid-write and then consumed. Leave it; the next probe reads content.
            return ParseResult.EMPTY;
        }
        if (!consumeOrDisable()) {
            return ParseResult.EMPTY;
        }
        return parse(lines);
    }

    /** {@link #consume()}, and on a hard failure the existing disable law: the surface shuts off
     *  for this serve with one loud row. Shared by the parse, oversize and undecodable paths.
     * */
    private boolean consumeOrDisable() {
        if (consume()) {
            return true;
        }
        consumeBroken = true;
        System.out.println("npeer-serve operator_file_disabled path=" + file
                + " (consume failed: delete AND truncate — un-consumed verbs would refire"
                + " every poll, so the surface shuts off for this serve)");
        return false;
    }

    /**
     * The command file's bytes → its text. {@code EF BB BF} = UTF-8, {@code FF FE} = UTF-16LE,
     * {@code FE FF} = UTF-16BE, each decoded AFTER its BOM; no BOM = UTF-8. STRICT: a malformed or
     * unmappable sequence returns {@code null} (the caller's undecodable lane, which logs it once
     * on consume), never a replacement char. Pure, package-private, never throws. Why BOM-aware:
     * Windows PowerShell 5.1's {@code >} / {@code Out-File} save UTF-16LE with a BOM, and a UTF-8
     * BOM used to ride the first verb into a reject ({@link BanSet}'s list reader strips it too).
     *
     */
    static String decodeCommandFile(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            skip = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                    .toString();
        } catch (CharacterCodingException undecodable) {
            return null;
        }
    }

    /**
     * A rejected line in the form a caller may log: every char outside printable ASCII
     * {@code 0x20..0x7E} becomes {@code ?}, and a line longer than {@value #REJECT_ECHO_MAX_CHARS}
     * chars keeps its first {@value #REJECT_ECHO_MAX_CHARS} + {@code (+<dropped>)}. Why here: the
     * serve prints each reject on the SIM thread ({@code operator_command_ignored line=}), so the
     * bound lives at the one mint, {@link #parse}.
     */
    static String boundedReject(String line) {
        int keep = Math.min(line.length(), REJECT_ECHO_MAX_CHARS);
        StringBuilder out = new StringBuilder(keep + 12);
        for (int i = 0; i < keep; i++) {
            char c = line.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : '?');
        }
        if (line.length() > keep) {
            out.append("(+").append(line.length() - keep).append(')');
        }
        return out.toString();
    }

    /** The text a redacted operator argument reads as, in a reject echo and in the reel's CMD row. */
    public static final String REDACTED = "<redacted>";

    /**
     * A rejected line with any seat key removed: a line that CONTAINS {@code admin-cid} anywhere (case-folded)
     * reads {@code admin-cid <redacted>}; every other line is returned unchanged, so a reject that carries no
     * key still echoes byte-for-byte. WHY contains, not a first-token match: a malformed line can carry the key
     * anywhere — a line glued to one that lacked its newline ({@code end-roundadmin-cid <key>}), a no-break
     * space between the verb and the key (no split happens), a trailing extra token. Applied BEFORE
     * {@link #boundedReject}, whose length cut could otherwise drop the verb word and keep the key.
     *
     * <p>F-D-3: EVERY whitespace token of a
     * rejected line is tested on its INNER text - the token with a leading and a trailing run of the wrapper
     * characters {@code < > " ' [ ] ( ) { } , ; :} stripped ({@link #KEY_WRAPPERS}). An inner text that reads as a
     * cid key once case-folded ({@link BanSet#isCidKey}, the parser's own predicate) echoes as its
     * {@link #digestOfKey}, re-wrapped in the same characters ({@code unban cid <key>} echoes
     * {@code unban cid <h1:...>}), the line rejoined on single spaces; a line with no key-shaped token echoes
     * byte-for-byte. WHY every token and not the house grammar's position: the package console tells a host to
     * type {@code unban cid <key>} into {@code ops-cmd.txt} ({@code ServerOpsCore.unbanPickPrompt}), and a typo
     * there - the kind word left out ({@code unban <key>}), a seat left out ({@code kick <key>}), a tab or no-break
     * space pasted between the words, a double space, an UPPER-case key, a key glued onto the previous line's
     * verb - moves the key off its position and would print it raw on the serve's stdout. WHY the wrappers: the
     * prompt's own placeholder brackets, a pasted quote or a trailing comma left around the key hid it from the
     * whole-token test. A pure-decimal inner text of 1-9 digits - the house
     * grammars' SEAT-ID shape ({@code kick <seat>}, {@code god-mode <seat>}) - stays as typed even when hex-shaped
     * ({@code god-mode 1000 on} echoes whole; {@code AdminSeatDebugLaneContractRows} pins it): the key it could
     * spell belongs to a 1-4 character client id whose every UTF-8 byte reads as two decimal digits in hex
     * ({@code abcd} = {@code 61626364}), while every kit mints a 32-hex-character client id, whose 64-digit key is
     * digested.
     * <p>An inner text that fails the whole-token test is tested once more on its
     * hex runs ({@link #hexRunEnd}; the LEADING run alone until the paragraph below) - so {@code ban-ip <key>/64}, a key
     * with a stray mark or a glued word echoes the digest and keeps the tail. A run of at most
     * {@value #ADDRESS_GROUP_MAX_CHARS} chars stays as typed: every address opens with one that short, and the key
     * it could spell is a 1-2 character client id's. A longer run that is not itself a decodable key rides
     * {@link #digestOfHexRun}: its longest decodable even prefix is digested, else the run becomes
     * {@code <hex:N>} - a hex-leading tail ({@code <key>a}) or a key whose bytes are not text never echoes raw.
     * <p>The two tests run part by part ({@link #keyPartsRedactedOrNull}: the inner text split on {@code =} and
     * {@code :}, a leading {@code 0x} kept in place; an unsplit token reads as above), because a host pasting a key from a
     * log glued to a hex mark or a label ({@code ban-cid 0x<key>}, {@code key=<key>}, {@code cid:<key>}) printed it raw.
     *
     * <p>EVERY maximal hex run of a part longer than {@value #ADDRESS_GROUP_MAX_CHARS} chars is digested in place, not
     * the leading run alone: {@code unban cid<key>}, {@code #<key>},
     * {@code key/<key>}, {@code -<key>}, {@code id_<key>}, {@code key-><key>} and {@code kick 1 x<key>} opened their part
     * with chars off the key alphabet, and the second key of {@code <key>,<key>} / {@code <key>/<key>} / {@code <key>-<key>}
     * sat past the first run's end, so each printed the key raw on the serve's stdout;
     * {@code BanServeContractRows ban-stdout-key-digested-glued} pins every form.
     */
    static String redactOperatorLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("admin-cid")) {
            return "admin-cid " + REDACTED;
        }
        String[] tokens = LINE_TOKEN_SEPARATOR.split(line);
        boolean digested = false;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            int start = 0;
            int end = token.length();
            while (start < end && KEY_WRAPPERS.indexOf(token.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && KEY_WRAPPERS.indexOf(token.charAt(end - 1)) >= 0) {
                end--;
            }
            String redacted = keyPartsRedactedOrNull(token.substring(start, end));
            if (redacted != null) {
                tokens[i] = token.substring(0, start) + redacted + token.substring(end);
                digested = true;
            }
        }
        return digested ? String.join(" ", tokens) : line;
    }

    /**
     * The echo of {@code text} (one part of a token's inner text, {@link #keyPartsRedactedOrNull}), else null when it
     * stays as typed: the {@link #redactOperatorLine} whole-key test (a key of more than {@code keepChars} chars that
     * decodes to a client id echoes its {@link #digestOfKey}), then its run test (EVERY maximal run of {@code text} in
     * the cid key alphabet, {@link #hexRunEnd}, longer than {@value #ADDRESS_GROUP_MAX_CHARS} chars echoes its
     * {@link #digestOfHexRun} in place, the rest of {@code text} as typed); a key or run of {@link #SEAT_ID_SHAPE} stays
     * as typed. WHY every run and not the leading one: a key glued behind a label or mark off the
     * key alphabet ({@code cid<key>}, {@code #<key>}, {@code x<key>}) and a second key glued after a first
     * ({@code <key>,<key>}) both sat past where the leading-run test stopped, and printed raw. WHY one helper: a whole
     * token and a glued part read under the same two tests, so they never drift apart.
     *
     * <p>The runs are found on {@code text} itself, never on its case fold:
     * {@code toLowerCase(ROOT)} maps U+0130 to TWO chars, so a run index found on the fold drifted on the text - an
     * {@code IndexOutOfBoundsException} escaped {@link #parse} (documented never-throwing) and the operator-file poller
     * daemon died with it, or one raw key char leaked per such char. Each run is folded AFTER the cut (pure ASCII, so its
     * length is stable); the whole-text tests above read the fold, as their predicates take a folded key.
     */
    private static String keyRedactedOrNull(String text, int keepChars) {
        String folded = text.toLowerCase(Locale.ROOT);
        if (SEAT_ID_SHAPE.matcher(folded).matches()) {
            return null;
        }
        if (folded.length() > keepChars && BanSet.cidOfKeyOrNull(folded) != null) {
            return digestOfKey(folded);
        }
        StringBuilder out = null;
        int copied = 0;
        for (int i = 0; i < text.length(); ) {
            int end = hexRunEnd(text, i);
            if (end == i) {
                i++;
                continue;
            }
            String run = text.substring(i, end).toLowerCase(Locale.ROOT);
            if (run.length() > ADDRESS_GROUP_MAX_CHARS && !SEAT_ID_SHAPE.matcher(run).matches()) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 40);
                }
                out.append(text, copied, i).append(digestOfHexRun(run));
                copied = end;
            }
            i = end;
        }
        return out == null ? null : out.append(text, copied, text.length()).toString();
    }

    /**
     * The echo of a token's inner text split on {@code =} and {@code :} ({@link #KEY_PART_SEPARATOR}, the separators kept
     * in place), each part read by {@link #keyRedactedOrNull} after a leading {@code 0x} / {@code 0X} (kept as typed),
     * else null when no part changed. An unsplit text with no {@code 0x} lead is the whole token and reads exactly as
     * before (a whole key of any length); any other part of at most {@value #ADDRESS_GROUP_MAX_CHARS} chars is never read
     * as a key, so an address or a port echoes whole ({@code 2a0b:f4c0::/32}, {@code 10.0.0.1:443}, {@code port=8080});
     * the {@value #KEY_DIGEST_PREFIX} digest tail a host copied from a listing stays as typed. Every part is read, so a
     * second key after a first ({@code <key>:<key>}) never rides a digested head's kept tail. WHY: a key pasted glued to a
     * hex mark or a label ({@code 0x<key>}, {@code key=<key>}, {@code cid:<key>}) opens its token with chars off the key
     * alphabet, so both whole-token tests missed it and the echo printed the key raw.
     *
     */
    private static String keyPartsRedactedOrNull(String inner) {
        StringBuilder out = new StringBuilder(inner.length());
        boolean changed = false;
        for (String part : KEY_PART_SEPARATOR.split(inner, -1)) {
            int lead = part.regionMatches(true, 0, "0x", 0, 2) ? 2 : 0;
            int keep = lead == 0 && part.length() == inner.length() ? 0 : ADDRESS_GROUP_MAX_CHARS;
            int p = KEY_DIGEST_PREFIX.length();
            boolean digestTail = out.length() >= p && out.substring(out.length() - p).equalsIgnoreCase(KEY_DIGEST_PREFIX)
                    && isKeyDigest(KEY_DIGEST_PREFIX + part.toLowerCase(Locale.ROOT));
            String redacted = digestTail ? null : keyRedactedOrNull(part.substring(lead), keep);
            out.append(redacted == null ? part : part.substring(0, lead) + redacted);
            changed |= redacted != null;
        }
        return changed ? out.toString() : null;
    }

    /** The zero-width split {@link #keyPartsRedactedOrNull} cuts an inner text at: before and after each {@code =}
     *  and {@code :}, so each separator is its own part and the parts rejoin to the text byte for byte. */
    private static final Pattern KEY_PART_SEPARATOR = Pattern.compile("(?<=[=:])|(?=[=:])");

    /**
     * The echo of a hex run longer than an address group ({@link #hexRunEnd}, {@code run.length() >}
     * {@value #ADDRESS_GROUP_MAX_CHARS}): the digest of its LONGEST even prefix that decodes to a client id, then the
     * rest of the run as typed when it is at most {@value #ADDRESS_GROUP_MAX_CHARS} chars, else read again the same way
     * (no run longer than an address group ever echoes raw) - so a key with a hex-leading tail
     * glued on ({@code <key>a}, {@code <key>be}: an odd length, or an extra byte that is not UTF-8, fails the whole-run
     * test) still echoes its digest - else a fixed marker naming only the run's length ({@code <hex:N>}). WHY: a run
     * this long is never echoed raw; the shape digested the whole run or nothing, and a tail in {@code 0-9 a-f}
     * printed the key -; {@code BanServeContractRows ban-ip-key-echo-digested} pins the
     * tails.
     */
    private static String digestOfHexRun(String run) {
        for (int n = Math.min(run.length(), 2 * BanSet.CID_MAX_BYTES) & ~1; n >= 2; n -= 2) {
            String cid = BanSet.cidOfKeyOrNull(run.substring(0, n));
            if (cid != null) {
                String rest = run.substring(n);
                return CidDigest.of(cid) + (rest.length() > ADDRESS_GROUP_MAX_CHARS ? digestOfHexRun(rest) : rest);
            }
        }
        return "<hex:" + run.length() + ">";
    }

    /**
     * The end (exclusive) of the run of {@code text} in the cid key alphabet in either case ({@code 0-9 a-f A-F}; the
     * fold of such a run is what {@link BanSet#isCidKey} reads) that starts at {@code from}; {@code from} itself when
     * that char is off the alphabet. WHY: a key glued to a range length ({@code <key>/64}), a stray mark, a label or a
     * word fails the whole-token test, so {@link #keyRedactedOrNull} walks every run and digests the long ones, keeping
     * the rest as typed. WHY the text and not its fold: the caller copies {@code text} by these
     * indices, and a fold can be longer than its text (U+0130).
     * As the leading-run read; every run.
     */
    private static int hexRunEnd(String text, int from) {
        int n = from;
        while (n < text.length() && isCidKeyChar(text.charAt(n))) {
            n++;
        }
        return n;
    }

    /** True for a char of the cid key alphabet in either case ({@code 0-9}, {@code a-f}, {@code A-F}); ASCII only. */
    private static boolean isCidKeyChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** The longest hex run an address opens with (an IPv6 group is 1-4 hex digits, an IPv4 part 1-3 digits): a
     *  hex run ({@link #hexRunEnd}) no longer than this is left as typed, so an address or range echoes whole
     *  ({@code 2a0b:f4c0::/32} never has its first group digested). */
    private static final int ADDRESS_GROUP_MAX_CHARS = 4;

    /** The characters {@link #redactOperatorLine} strips from either end of
     *  a token before its key test, and puts back around the digest: brackets, quotes and list punctuation. */
    private static final String KEY_WRAPPERS = "<>\"'[](){},;:";

    /** The separators {@link #redactOperatorLine} splits a host-typed line on: whitespace ({@code parseLine}'s
     *  {@code \s+}) PLUS the no-break space (0xA0), which {@code \s} misses and a paste from a web page carries. */
    private static final Pattern LINE_TOKEN_SEPARATOR = Pattern.compile("[\\s" + (char) 0xA0 + "]+");

    /** The house grammars' seat-id shape ({@code parseLine}'s {@code [0-9]{1,9}}): a token of this shape is a seat
     *  the host typed, never digested by {@link #redactOperatorLine}. */
    private static final Pattern SEAT_ID_SHAPE = Pattern.compile("[0-9]{1,9}");

    /**
     * The text the reel's CMD row records for {@code c}: {@link #REDACTED} for {@link Verb#ADMIN_CID} (the seat
     * key never reaches a recording), else {@code c.text()}. Idempotent: a replayed arm already carries
     * {@code <redacted>} and records it again, so a resim's CMD rows compare equal to the source's.
     *
     * <p>Every cid-key TOKEN a kick or ban verb carries records as its {@link #digestOfKey}: the whole text of
     * {@link Verb#KICK}, {@link Verb#BAN} and {@link Verb#BAN_SRC} (one key, or "" for a key-less house line), the
     * leading key of {@link Verb#BAN_CID} ({@code <key>[ term=<tok>]}), and the second token of
     * {@link Verb#BAN_SESSION} ({@code <sid>[ <key>][ term=<tok>]}) and of a cid {@link Verb#UNBAN}
     * ({@code cid <key>}; {@code src <key>} is an address key, recorded verbatim). Every other token and every
     * other verb records verbatim. WHY: a cid key is the TARGET player's client id hex-encoded, so the raw key on
     * a reel hands that id to whoever holds the file; the digest is one-way and has the shape and domain tag of
     * the author key on the reel's VSUB rows, so one player reads as ONE id across both row kinds. The digest is
     * itself no cid key ({@code h} and {@code :} are off the key alphabet), so a replayed command records it
     * again unchanged and the idempotence above holds. A ban-cid and a cid unban already carry the digest live (the
     * bans file's key form), so their recording equals the command; a kick or ban expected-occupant key stays the
     * raw hex its door compares, and a re-simulation admits the digested row ({@code ReplayFeed}). Runs once per
     * operator command, never per frame; its SHA-256 is normally warm by then (every admitted session builds
     * its author digest).
     *
     */
    public static String recordedText(Command c) {
        String t = c.text();
        return switch (c.verb()) {
            case ADMIN_CID -> REDACTED;
            case KICK, BAN, BAN_SRC, BAN_CID -> digestTokenAt(t, 0);
            case BAN_SESSION -> digestTokenAt(t, 1);
            case BAN_IP -> t; // an address key names no player id: recorded verbatim
            case UNBAN -> t.startsWith(BanSet.kindToken(BanSet.Kind.CID) + " ") ? digestTokenAt(t, 1) : t;
            default -> t;
        };
    }

    /** The prefix of a recorded key digest ({@link #isKeyDigest}). */
    static final String KEY_DIGEST_PREFIX = CidDigest.PREFIX;

    /**
     * True when {@code s} is a recorded key digest: {@value #KEY_DIGEST_PREFIX} then exactly 32 lowercase hex
     * chars (length 35), the shape {@link CidDigest#of} mints ({@link CidDigest#isDigest}, the one grammar). Never
     * true of a cid key, so one token reads as a raw key or as a digest, never both.
     *
     */
    static boolean isKeyDigest(String s) {
        return CidDigest.isDigest(s);
    }

    /**
     * The token the reel records in place of {@code token}: a cid key whose bytes decode to a client id becomes
     * {@link CidDigest#of} of that id; anything else comes back unchanged — a digest
     * (idempotence), "" or a term token, and a lawful hex key whose bytes are not UTF-8. That last one names no
     * player: a HELLO-admitted client id always decodes ({@link BanSet#cidKey} is injective on them), so no
     * session can carry it, and digesting it would have to invent a client id.
     *
     */
    static String digestOfKey(String token) {
        if (isKeyDigest(token)) {
            return token;
        }
        String cid = BanSet.cidOfKeyOrNull(token);
        return cid == null ? token : CidDigest.of(cid);
    }

    /** {@code text} with its {@code index}-th space-separated token passed through {@link #digestOfKey}; the text
     *  unchanged when it has no such token. */
    private static String digestTokenAt(String text, int index) {
        int from = 0;
        for (int i = 0; i < index; i++) {
            int space = text.indexOf(' ', from);
            if (space < 0) {
                return text;
            }
            from = space + 1;
        }
        int end = text.indexOf(' ', from);
        end = end < 0 ? text.length() : end;
        String token = text.substring(from, end);
        String recorded = digestOfKey(token);
        return recorded.equals(token) ? text : text.substring(0, from) + recorded + text.substring(end);
    }

    private boolean consume() {
        try {
            Files.delete(file);
            return true;
        } catch (IOException | RuntimeException deleteFailed) {
            // fall through to truncate (e.g. the operator's tail holds a share lock)
        }
        try {
            Files.writeString(file, "", StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException | RuntimeException truncateFailed) {
            return false;
        }
    }

    /**
     * The pure grammar core (game-free-testable): fixed verbs only, no free-form strings.
     * Never throws — an unparseable line lands in {@code rejected}, redacted by {@link #redactOperatorLine}
     * (the ONE reject mint, so every consumer of {@code rejected()} sees the redacted form), then bounded by
     * {@link #boundedReject}; a refused {@code ban-ip} line then gains its server-built reason
     * ({@link #banIpRefusalSuffix}).
     */
    public static ParseResult parse(List<String> lines) {
        List<Command> commands = new ArrayList<Command>();
        List<String> rejected = new ArrayList<String>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Command command = parseLine(line);
            if (command != null) {
                commands.add(command);
            } else {
                rejected.add(boundedReject(redactOperatorLine(line)) + banIpRefusalSuffix(line));
            }
        }
        return new ParseResult(List.copyOf(commands), List.copyOf(rejected));
    }

    /**
     * The reason a refused {@code ban-ip} line's echo carries after the line, else "" (every other verb's echo is
     * the line alone): {@code " - refused: <reason>; expected <BanSet.SRC_KEY_FORM>"}. WHY: a host typing an
     * address needs to see which form is accepted and, for a range with host bits set or a /32, the exact key to
     * type instead. Bounded by construction: the reason is server text plus at most one key DERIVED by the
     * normalizer (digits, dots, hex, colons — never the typed token), so the echo stays printable ASCII.
     *
     */
    static String banIpRefusalSuffix(String line) {
        String[] parts = line.split("\\s+");
        if (!"ban-ip".equals(parts[0].toLowerCase(Locale.ROOT))) {
            return "";
        }
        StringBuilder why = new StringBuilder(96);
        if (parts.length != 2) {
            why.append("ban-ip takes exactly one address or range");
        } else if (SourceRanges.canonicalOrNull(parts[1], why) != null) {
            return "";
        }
        return " - refused: " + why + "; expected " + BanSet.SRC_KEY_FORM;
    }

    private static Command parseLine(String line) {
        String[] parts = line.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "kick": {
                // The ban idiom below, verbatim in shape —
                // one seat id > 0 + an optional EXPECTED-occupant cid key (ASCII-folded,
                // BanSet.isCidKey — else REJECTED); text = the key, "" when absent (house file: the
                // frame-time occupant). The door compares it under its lock (kickSeatSession).
                String expectedKey = parts.length == 3 ? parts[2].toLowerCase(Locale.ROOT) : "";
                if ((parts.length == 2 || (parts.length == 3 && BanSet.isCidKey(expectedKey)))
                        && parts[1].matches("[0-9]{1,9}")) {
                    int seat = Integer.parseInt(parts[1]);
                    if (seat > 0) {
                        return new Command(Verb.KICK, seat, 0f, false, expectedKey);
                    }
                }
                return null;
            }
            case "ban":
            case "ban-src": {
                // Exactly one seat id > 0 (the kick shape).
                // + an optional EXPECTED-occupant
                // cid key (ASCII-folded, BanSet.isCidKey — else the line is REJECTED); text = the
                // key, "" when absent (house file: whoever holds the seat at frame time).
                String expectedKey = parts.length == 3 ? parts[2].toLowerCase(Locale.ROOT) : "";
                if ((parts.length == 2 || (parts.length == 3 && BanSet.isCidKey(expectedKey)))
                        && parts[1].matches("[0-9]{1,9}")) {
                    int seat = Integer.parseInt(parts[1]);
                    if (seat > 0) {
                        return new Command(verb.equals("ban") ? Verb.BAN : Verb.BAN_SRC, seat, 0f, false,
                                expectedKey);
                    }
                }
                return null;
            }
            case "ban-cid": {
                // The player key in either form (BanSet.CID_KEY_FORM), never raw
                // cid text; the command carries the ban's entry key = the digest (a legacy hex key that decodes
                // converts; undecodable hex, or anything else, is REJECTED).
                String key = parts.length == 2 ? BanSet.cidBanKeyOrNull(parts[1]) : null;
                return key != null ? new Command(Verb.BAN_CID, 0, 0f, false, key) : null;
            }
            case "ban-ip": {
                // Exactly one token through the one typed-key normalizer; text =
                // the stored key. A refusal is a rejected line whose echo carries the reason (banIpRefusalSuffix).
                String key = parts.length == 2 ? SourceRanges.canonicalOrNull(parts[1], null) : null;
                return key != null ? new Command(Verb.BAN_IP, 0, 0f, false, key) : null;
            }
            case "admin-cid": {
                // The hex key grammar (BanSet.isCidKey) - one canonical hex key, else REJECTED (the
                // door refuses a key longer than any v13 cid can encode: bad_key).
                String key = parts.length == 2 ? parts[1].toLowerCase(Locale.ROOT) : "";
                return BanSet.isCidKey(key) ? new Command(Verb.ADMIN_CID, 0, 0f, false, key) : null;
            }
            case "ban-session":
                // Strict digits 1..19 with an optional leading '-', Long-bounded, NON-ZERO: a
                // session id is `SecureRandom.nextLong()` minus 0 (the no-session sentinel), so
                // HALF of all ids are negative — the "> 0" grammar refused every one of them
                // (the "flaky" BanServeContractRows F2 was this sign).
                if (parts.length == 2 && parts[1].matches("-?[0-9]{1,19}")) {
                    try {
                        if (Long.parseLong(parts[1]) != 0L) {
                            return new Command(Verb.BAN_SESSION, 0, 0f, false, parts[1]);
                        }
                    } catch (NumberFormatException overflow) {
                        // past Long.MAX_VALUE — rejected below
                    }
                }
                return null;
            case "unban": {
                // A1: `unban cid|src <key>` — the kind then the key under that kind's grammar
                // (a src key is exactly the door's sourceKey form, A17). A cid
                // key is either player-key form, normalized to the entry's digest (BanSet.cidBanKeyOrNull).
                // `unban ip <key>` = `unban src <key>` (the ban-ip verb's own word)
                BanSet.Kind kind = parts.length != 3 ? null
                        : "ip".equals(parts[1].toLowerCase(Locale.ROOT)) ? BanSet.Kind.SRC : BanSet.kindOrNull(parts[1]);
                String key = kind == BanSet.Kind.CID ? BanSet.cidBanKeyOrNull(parts[2])
                        : kind == BanSet.Kind.SRC ? parts[2] : "";
                return kind != null && key != null && BanSet.isKey(kind, key)
                        ? new Command(Verb.UNBAN, 0, 0f, false, BanSet.kindToken(kind) + " " + key)
                        : null;
            }
            case "bans":
                return parts.length == 1 ? new Command(Verb.BANS, 0, 0f, false) : null;
            case "end-round":
                return parts.length == 1 ? new Command(Verb.END_ROUND, 0, 0f, false) : null;
            case "end-match":
                return parts.length == 1 ? new Command(Verb.END_MATCH, 0, 0f, false) : null;
            case "set-sim-speed":
                if (parts.length == 2) {
                    try {
                        float speed = Float.parseFloat(parts[1]);
                        if (Float.isFinite(speed) && speed >= SIM_SPEED_MIN
                                && speed <= SIM_SPEED_MAX) {
                            return new Command(Verb.SET_SIM_SPEED, 0, speed, false);
                        }
                    } catch (NumberFormatException notANumber) {
                        // rejected below
                    }
                }
                return null;
            case "skip-wave":
                // One word, no args — the serve side owns every semantic
                // (wave-shape-only, kill-then-advance); a non-wave serve refuses with a log.
                return parts.length == 1 ? new Command(Verb.SKIP_WAVE, 0, 0f, false) : null;
            case "pause-wave":
                // One word, no args — a toggle; the serve side owns the
                // semantic (wave-shape-only; a non-wave serve refuses with a log).
                return parts.length == 1 ? new Command(Verb.PAUSE_WAVE, 0, 0f, false) : null;
            case "wave-category":
                // Exactly one arg from the FIXED category set (ASCII-folded like
                // the chat grammar); anything else rejects here so a typo'd row never reaches
                // the serve loop. An optional second word from the FIXED
                // {reset, stay} pair — `reset` rides in text as "<type> reset", `stay` is the
                // bare form (the chat grammar's twin shape, byte-identical text()).
                if (parts.length == 2 || parts.length == 3) {
                    String category = parts[1].toLowerCase(Locale.ROOT);
                    String mode = parts.length == 3 ? parts[2].toLowerCase(Locale.ROOT) : "stay";
                    if (cc.starsector.server.match.WaveSchedule.WAVE_CATEGORIES
                            .contains(category) && (mode.equals("reset") || mode.equals("stay"))) {
                        return new Command(Verb.SET_WAVE_CATEGORY, 0, 0f, false,
                                mode.equals("reset") ? category + " reset" : category);
                    }
                }
                return null;
            case "wave-set":
                // Exactly one arg, ONE digit 1..9 (the chat grammar's bounded
                // parse verbatim) — the serve refuses a number past the chain with a receipt.
                if (parts.length == 2 && parts[1].length() == 1
                        && parts[1].charAt(0) >= '1' && parts[1].charAt(0) <= '9') {
                    return new Command(Verb.SET_WAVE_NUMBER, 0, 0f, false, parts[1]);
                }
                return null;
            case "endless":
                // Exactly one word from the FIXED {on, off, show} set
                // (ASCII-folded); the serve owns every semantic.
                if (parts.length == 2) {
                    String word = parts[1].toLowerCase(Locale.ROOT);
                    if (word.equals("on") || word.equals("off") || word.equals("show")) {
                        return new Command(Verb.SET_ENDLESS, 0, 0f, false, word);
                    }
                }
                return null;
            case "wave-inflate":
                // Exactly one word from the FIXED {on, off, show} set (ASCII-folded,
                // the endless shape); the serve owns every semantic.
                if (parts.length == 2) {
                    String word = parts[1].toLowerCase(Locale.ROOT);
                    if (word.equals("on") || word.equals("off") || word.equals("show")) {
                        return new Command(Verb.SET_WAVE_INFLATE, 0, 0f, false, word);
                    }
                }
                return null;
            case "knob":
                // `knob list` | `knob <knob> show` | `knob <knob> <word>` — every
                // token from a FIXED set (WireKnob verb names + its choice words, ASCII-folded);
                // the serve owns the semantic (AdminKnobVerb).
                if (parts.length == 2 && parts[1].equalsIgnoreCase("list")) {
                    return new Command(Verb.SET_KNOB, 0, 0f, false, "list");
                }
                if (parts.length == 3) {
                    WireKnob knob = WireKnob.byVerbName(parts[1]);
                    String knobWord = parts[2].toLowerCase(Locale.ROOT);
                    if (knob != null && (knobWord.equals("show") || knob.choiceIndex(knobWord) >= 0)) {
                        return new Command(Verb.SET_KNOB, 0, 0f, false,
                                knob.verbName() + " " + knobWord);
                    }
                }
                return null;
            case "terrain":
                // `terrain show` | `terrain <word>` | `terrain <word>
                // <level>` — every token from the two knobs' FIXED choice tables (ASCII-folded);
                // the serve owns the semantic (AdminTerrainVerb).
                if (parts.length == 2 && parts[1].equalsIgnoreCase("show")) {
                    return new Command(Verb.SET_TERRAIN, 0, 0f, false, "show");
                }
                if (parts.length == 2 || parts.length == 3) {
                    int terrainWord = WireKnob.TERRAIN.choiceIndex(parts[1]);
                    if (terrainWord < 0) {
                        return null;
                    }
                    if (parts.length == 2) {
                        return new Command(Verb.SET_TERRAIN, 0, 0f, false,
                                WireKnob.TERRAIN.choiceName(terrainWord));
                    }
                    int terrainLevel = WireKnob.TERRAIN_LEVEL.choiceIndex(parts[2]);
                    if (terrainLevel >= 0) {
                        return new Command(Verb.SET_TERRAIN, 0, 0f, false,
                                WireKnob.TERRAIN.choiceName(terrainWord) + " "
                                        + WireKnob.TERRAIN_LEVEL.choiceName(terrainLevel));
                    }
                }
                return null;
            case "set-joiner-default":
                // Exactly one token; `none` clears; charset/length law above. The id's
                // CASE is preserved (the door canonicalizes against its allowlist).
                if (parts.length == 2 && joinerDefaultTokenLawful(parts[1])) {
                    String arg = parts[1].equalsIgnoreCase("none") ? "" : parts[1];
                    return new Command(Verb.SET_JOINER_DEFAULT, 0, 0f, false, arg);
                }
                return null;
            case "voice":
                // W12 L2: exactly `voice <knob> <value>`; knob = letters only
                // (1..24), value = [A-Za-z0-9.] (1..8) — the relay's VoiceConfig.retune owns
                // every semantic (unknown knob / bad value / joint-bound breach = a logged
                // refusal, the standing sheet untouched). Knob case is preserved.
                // `voice set k=v [k=v ...]` — the ATOMIC multi-knob form
                // (the Voice panel's Apply): 1..KNOB_COUNT pairs, each knob/value the per-knob
                // charset law, no knob twice; text = "set k=v k=v" and the relay builds ONE
                // jointly-validated sheet (all applied or none).
                if (parts.length >= 3 && parts[1].equalsIgnoreCase(VoiceRelay.SET_WORD)
                        && parts[2].indexOf('=') >= 0) {
                    return voiceSetLawful(parts)
                            ? new Command(Verb.VOICE, 0, 0f, false, VoiceRelay.SET_WORD + " "
                                    + String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)))
                            : null;
                }
                if (parts.length == 3 && parts[1].matches("[A-Za-z]{1,24}")
                        && parts[2].matches("[A-Za-z0-9.]{1,8}")) {
                    return new Command(Verb.VOICE, 0, 0f, false, parts[1] + " " + parts[2]);
                }
                return null;
            case "spawn-foe":
            case "spawn-friend":
                // `spawn-foe <variantId> [count]` — variant token = the
                // set-joiner-default charset/length law (case preserved; the serve validates
                // against the boot debug catalogue), count = strict digits
                // 1..ChatCommands.SPAWNFOE_MAX_COUNT (= AdhocFoeSpawner.MAX_PENDING). Malformed ⇒
                // rejected (the file lane's fail-soft law: logged + ignored, never a throw).
                // Optional LAST token `@x,y` (the chat grammar's ONE pose
                // law, ChatCommands.parseSpawnPoseOrNull; the serve applies the map clamp) —
                // text = "<variant> <count>[ @x,y]", the door deposit's exact shape.
                // `spawn-friend` = the same parse, the SPAWN_FRIEND verb (side 0).
                // The optional FIXED `officer` keyword sits between the
                // count and the pose (`<variant> [count] [officer] [@x,y]`, the chat grammar's
                // ONE order); text() carries it lowercase after the normalized count.
                if (parts.length >= 2 && parts.length <= 5
                        && joinerDefaultTokenLawful(parts[1])) {
                    int argEnd = parts.length;
                    String poseToken = null;
                    if (parts[parts.length - 1].startsWith("@")) {
                        if (ChatCommands.parseSpawnPoseOrNull(parts[parts.length - 1]) == null) {
                            return null;
                        }
                        poseToken = parts[parts.length - 1];
                        argEnd--;
                    }
                    boolean officer = false;
                    if (argEnd >= 3 && ChatCommands.isOfficerKeyword(parts[argEnd - 1])) { // optional officer keyword, before any @pose
                        officer = true;
                        argEnd--;
                    }
                    if (argEnd > 3) {
                        return null;
                    }
                    int count = 1;
                    if (argEnd == 3) {
                        if (!parts[2].matches("[0-9]{1,2}")) {
                            return null;
                        }
                        count = Integer.parseInt(parts[2]);
                        if (count < 1 || count > ChatCommands.SPAWNFOE_MAX_COUNT) {
                            return null;
                        }
                    }
                    return new Command("spawn-friend".equals(verb) ? Verb.SPAWN_FRIEND
                            : Verb.SPAWN_FOE, 0, 0f, false, parts[1] + " " + count
                            + (officer ? " " + ChatCommands.SPAWN_OFFICER_KEYWORD : "")
                            + (poseToken == null ? "" : " " + poseToken));
                }
                return null;
            case "despawn":
                // Exactly `despawn <wireId>` — strict digits 1..19, Long-bounded,
                // > 0 (the chat grammar's /kick #id law without the `#`); anything else rejects
                // here (the file lane's fail-soft law: logged + ignored, never a throw). The
                // serve's engine port owns the seat / pod / unknown-id refusals.
                if (parts.length == 2 && parts[1].matches("[0-9]{1,19}")) {
                    try {
                        if (Long.parseLong(parts[1]) > 0L) {
                            return new Command(Verb.DESPAWN, 0, 0f, false, parts[1]);
                        }
                    } catch (NumberFormatException overflow) {
                        // 19 digits past Long.MAX_VALUE — falls through to the reject
                    }
                }
                return null;
            case "pod-test":
                // Exactly `pod-test <scenario> <seat>` — scenario from the
                // FIXED driver set (folded), seat = strict digits 1..POD_TEST_MAX_SEAT. Anything
                // else rejects here (the file lane's fail-soft law: logged + ignored).
                if (parts.length == 3 && parts[2].matches("[0-9]{1,3}")) {
                    String scenario = parts[1].toLowerCase(Locale.ROOT);
                    int seat = Integer.parseInt(parts[2]);
                    if (PodScenarioDriver.TOKENS.contains(scenario)
                            && seat >= 1 && seat <= POD_TEST_MAX_SEAT) {
                        return new Command(Verb.POD_TEST, seat, 0f, false, scenario);
                    }
                }
                return null;
            case "switch-team":
                // Exactly `switch-team <seat> instant|respawn` — the pod-test seat
                // law (strict digits 1..POD_TEST_MAX_SEAT) + a FIXED folded mode keyword.
                if (parts.length == 3 && parts[1].matches("[0-9]{1,3}")) {
                    int seat = Integer.parseInt(parts[1]);
                    String mode = parts[2].toLowerCase(Locale.ROOT);
                    if (seat >= 1 && seat <= POD_TEST_MAX_SEAT
                            && (mode.equals(SeatTeamLane.MODE_INSTANT)
                                    || mode.equals(SeatTeamLane.MODE_RESPAWN))) {
                        return new Command(Verb.SWITCH_TEAM, seat, 0f, false, mode);
                    }
                }
                return null;
            case "team-pick":
                // Exactly `team-pick <seat> north|south` — the
                // switch-team seat law (strict digits 1..POD_TEST_MAX_SEAT) + a word from the FIXED
                // {north, south} pair (folded). Anything else rejects here (logged + ignored).
                if (parts.length == 3 && parts[1].matches("[0-9]{1,3}")) {
                    int seat = Integer.parseInt(parts[1]);
                    String word = parts[2].toLowerCase(Locale.ROOT);
                    if (seat >= 1 && seat <= POD_TEST_MAX_SEAT
                            && (word.equals(SeatTeamLane.WORD_NORTH) || word.equals(SeatTeamLane.WORD_SOUTH))) {
                        return new Command(Verb.TEAM_PICK, seat, 0f, false, word);
                    }
                }
                return null;
            case "god-mode":
                // Exactly `god-mode <seat> on|off` — the /godmode chat verb's
                // house-file twin (the ownerless arena rig's seat must survive its own foe: a voidblaster's
                // 700 su reach sits inside every Invictus gun's; leg H 151004 lost the seat 9 times). Seat =
                // strict digits 1..POD_TEST_MAX_SEAT (the switch-team shape); mode from the FIXED {on, off}
                // set (folded) — the bare toggle stays chat-only (a file re-fired by an operator's editor
                // must never flip a seat back). Anything else rejects here (fail-soft: logged + ignored).
                // AdminSeatDebugLane owns the effect on the sim thread; the receipt is silent (no session),
                // the serve's operator_god_mode row carries it.
                if (parts.length == 3 && parts[1].matches("[0-9]{1,3}")) {
                    int seat = Integer.parseInt(parts[1]);
                    String mode = parts[2].toLowerCase(Locale.ROOT);
                    if (seat >= 1 && seat <= POD_TEST_MAX_SEAT
                            && (mode.equals("on") || mode.equals("off"))) {
                        return new Command(Verb.GOD_MODE, seat, 0f, false, mode);
                    }
                }
                return null;
            case "sim-speed-auto":
                // Strict on|off (case-folded like every verb) — anything else
                // REJECTS; the serve side additionally refuses arming on an AWSS-only serve.
                if (parts.length == 2) {
                    String arg = parts[1].toLowerCase(Locale.ROOT);
                    if (arg.equals("on") || arg.equals("off")) {
                        return new Command(Verb.SET_SIM_SPEED_AUTO, 0, 0f, arg.equals("on"));
                    }
                }
                return null;
            case "custom-submissions":
                // W15 v0 step 3(f): strict on|off (the sim-speed-auto shape); autoOn carries it.
                if (parts.length == 2) {
                    String arg = parts[1].toLowerCase(Locale.ROOT);
                    if (arg.equals("on") || arg.equals("off")) {
                        return new Command(Verb.CUSTOM_SUBMISSIONS, 0, 0f, arg.equals("on"));
                    }
                }
                return null;
            case "custom-reject":
            case "custom-allow":
                // Step 3(f): exactly one seat id > 0 (the kick shape); the serve owns the effect.
                // custom-allow is the same shape (the inverse verb).
                if (parts.length == 2 && parts[1].matches("[0-9]{1,3}")) {
                    int seat = Integer.parseInt(parts[1]);
                    if (seat > 0) {
                        return new Command(verb.equals("custom-reject") ? Verb.CUSTOM_REJECT
                                : Verb.CUSTOM_ALLOW, seat, 0f, false);
                    }
                }
                return null;
            case "custom-ban":
            case "custom-export":
            case "custom-unban":
            case "custom-keep":
            case "custom-keep-export":
            case "custom-forget":
                // Step 3(f): exactly one minted-id token under the set-joiner-default charset/
                // length law (case preserved; the ledger answers unknown ids loud, never here).
                // custom-unban is the same shape (the inverse verb).
                // The three kept verbs are the same shape — a kept
                // stem ([a-z0-9-], <= 28 chars) is inside this law; the lane re-checks the kept stem law.
                if (parts.length == 2 && joinerDefaultTokenLawful(parts[1])) {
                    Verb minted = switch (verb) {
                        case "custom-ban" -> Verb.CUSTOM_BAN;
                        case "custom-export" -> Verb.CUSTOM_EXPORT;
                        case "custom-keep" -> Verb.CUSTOM_KEEP;
                        case "custom-keep-export" -> Verb.CUSTOM_KEEP_EXPORT;
                        case "custom-forget" -> Verb.CUSTOM_FORGET;
                        default -> Verb.CUSTOM_UNBAN;
                    };
                    return new Command(minted, 0, 0f, false, parts[1]);
                }
                return null;
            case "ship-hide":
            case "ship-show":
            case "ship-remove":
            case "ship-restore":
            case "ship-keep":
                // The supersede's house twin, "ship-keep <id> replaces <X>" (the
                // reel renders a chat /shipkeep ... replaces ... exactly so, ReplayFeed): the same ONE text the chat
                // parser mints, both ids under the id law, the third token literally the word; any other 4-token
                // shape is refused like a malformed custom-keep.
                if ("ship-keep".equals(verb) && parts.length == 4 && ChatCommands.SHIPKEEP_REPLACES_WORD.equals(parts[2])
                        && joinerDefaultTokenLawful(parts[1]) && joinerDefaultTokenLawful(parts[3])) {
                    return new Command(Verb.SHIP_KEEP, 0, 0f, false,
                            parts[1] + " " + ChatCommands.SHIPKEEP_REPLACES_WORD + " " + parts[3]);
                }
                // The custom-keep shape — exactly one token under the
                // set-joiner-default law, case preserved; anything else is refused like a malformed custom-keep.
                if (parts.length == 2 && joinerDefaultTokenLawful(parts[1])) {
                    Verb ship = switch (verb) {
                        case "ship-hide" -> Verb.SHIP_HIDE;
                        case "ship-show" -> Verb.SHIP_SHOW;
                        case "ship-remove" -> Verb.SHIP_REMOVE;
                        case "ship-restore" -> Verb.SHIP_RESTORE;
                        default -> Verb.SHIP_KEEP;
                    };
                    return new Command(ship, 0, 0f, false, parts[1]);
                }
                return null;
            case "ship-list":
                return parts.length == 1 ? new Command(Verb.SHIP_LIST, 0, 0f, false) : null;
            default:
                return null;
        }
    }
}
