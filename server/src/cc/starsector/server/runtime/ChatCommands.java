package cc.starsector.server.runtime;

import cc.starsector.server.match.WaveSchedule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The chat-command grammar: pure static classification of one
 * law-conforming chat line (the {@link ChatFrameCodec#validateChatText} contract — the caller
 * already decoded it) into a command verdict. NO dispatch, NO stored state, NO session types —
 * the endpoint's dispatcher owns every reply that needs seat state; verdicts here carry only
 * the grammar-owned private replies (usage/unknown), each inside the
 * {@link ChatFrameCodec#TEXT_MAX_CHARS} single-line law by construction (constants + a bounded
 * echo of already-printable text).
 *
 * <p>Parsing law: exact-ASCII command names matched
 * case-insensitively via ASCII fold ONLY (never a Locale {@code toLowerCase} — the Turkish-I
 * class); STRICT single-space arg split (an empty token from a doubled interior space is
 * malformed grammar — the wire law already forbids leading/trailing spaces); unknown command ⇒
 * a private-reply verdict, never a session penalty; no token is ever a path/reflection/exec
 * input (the {@code /ship} arg becomes an in-memory allowlist lookup key in B1b).
 */
public final class ChatCommands {

    /** Longest command-name echo in an unknown-command reply (keeps the line law-safe). */
    private static final int UNKNOWN_NAME_ECHO_CHARS = 24;

    /** The one /transfer usage reply (W3 L4 S2: both arg forms; constant-only, no junk echo). */
    private static final String TRANSFER_USAGE = "usage: /transfer [target|#id]";

    /** The one {@code /team} usage reply (constant-only: a bad argument is never echoed). */
    public static final String TEAM_USAGE = "usage: /team (show) or /team north|south";

    public enum Kind { NOT_COMMAND, SUICIDE, SHIP_LIST, SHIP_SET,
        MODULE_LIST, MODULE_SET, MODULE_OFF, TRANSFER_LIST, TRANSFER_SET, TRANSFER_WIRE,
        /** {@code /team} — a player's own team in the PvP shape. Bare = {@link #TEAM_SHOW}; one
         * ASCII-folded word from the FIXED {north, south} pair = {@link #TEAM_SET} with {@code arg}
         * the word. The door answers both on the caller's OWN seat (never a typed id): a SET takes
         * effect at the seat's next respawn. */
        TEAM_SHOW, TEAM_SET,
        /** W12 L2: /mute — list / name / {@code #<wireId>} (the /transfer shape). */
        MUTE_LIST, MUTE_SET, MUTE_WIRE,
        /** /help — the door answers from {@link #helpLines}
         * with the verbs armed on THIS serve (never dispatch: the voice flag lives door-side). */
        HELP,
        /** The admin-gated verbs — {@code /wave skip|pause} (the F11/F12
         * hotkey bodies as ONE chat verb) and {@code /kick [player|#id]} (the /mute grammar).
         * Authorization is the SESSION's HELLO-derived admin bit, checked door-side at dispatch
         * ({@link #ADMIN_ONLY}); nothing in the text ever grants it. */
        WAVE_SKIP, WAVE_PAUSE,
        /** {@code /wave wrap} (exactly 2 tokens, folded
         * like skip|pause) — the admin chat door to the operator {@code END_ROUND} arm (the real
         * round wrap: gated reseed, MRST, next round redeploys every seat). Admin-gated at
         * dispatch; the serve drain refuses it on the serve's LAST round
         * ({@link #WRAP_REFUSED_LAST_ROUND}) and latches a double press
         * ({@link #WRAP_ALREADY_ENDING}). */
        WAVE_WRAP,
        /** {@code /endmatch} (exactly 1 token) — the admin chat door to the operator
         * {@code END_MATCH} arm (the serve ends after that tick with the goodbye fan to every seat;
         * wins over end-round); the in-game F10 of a signed-in seat sends it. Admin-gated at
         * dispatch ({@link #ADMIN_ONLY}); no inline reply (the match ending is the effect). */
        END_MATCH,
        /** The wave-category verb:
         * {@code /wave <type>} (alias {@code /wave category <type>}) — the NEXT wave becomes
         * that fortress faction, mid-match ("/wave omega ... next wave will become that
         * kind"). The grammar validates against the FIXED
         * {@link cc.starsector.server.match.WaveSchedule#WAVE_CATEGORIES} set (the skip|pause
         * fixed-arg-set precedent); {@code arg} carries the folded category. Admin-gated at
         * dispatch; the serve loop grafts the unspawned tail immediately.
         * an optional trailing word from the FIXED {reset, stay} pair —
         * {@code reset} rides as {@code arg = "<type> reset"} (the graft + a chain restart at
         * the opener), {@code stay} = the bare form (keep the wave progress). */
        WAVE_CATEGORY,
        /** {@code /wave set <1..9>} — the operator-numbered wave N spawns next;
         * {@code arg} = the bare digit (a bounded ONE-digit parse, the security sheet's
         * digits-only law; the serve refuses a number past the chain's last wave with a
         * receipt, never a clamp). Admin-gated at dispatch like every wave verb. */
        WAVE_SET,
        /** {@code /endless} bare = the endless-dial STATE receipt
         * ({@link #ENDLESS_SHOW}); {@code /endless on|off} = {@link #ENDLESS_SET} with
         * {@code arg} the folded word (the /godmode FIXED-set shape). Admin-gated at dispatch;
         * the serve drain owns the semantic ({@code on} beside the per-clear reset lane is a
         * refused receipt — the boot-time throw made live). */
        ENDLESS_SHOW, ENDLESS_SET,
        /** {@code /wave inflate} bare = the wave loadout-variety dial's STATE
         * receipt ({@link #WAVE_INFLATE_SHOW}); {@code /wave inflate on|off} =
         * {@link #WAVE_INFLATE_SET} with {@code arg} the folded word (the /endless shape).
         * Admin-gated at dispatch; the serve drain owns the semantic (ON pre-inflates the live
         * chain; a serve with no wave lane / no engine half answers a refused receipt). */
        WAVE_INFLATE_SHOW, WAVE_INFLATE_SET,
        /** {@code /knob} — the wire-delivered client knob census ({@code WireKnob}).
         * Bare = the list receipt ({@link #KNOB_SHOW}, {@code arg} "list"); {@code /knob <knob>} =
         * that knob's state ({@code arg} "<knob> show"); {@code /knob <knob> <word>} =
         * {@link #KNOB_SET} ({@code arg} "<knob> <word>", both folded against FIXED sets — the
         * knob's verb name + its choice table). Admin-gated at dispatch; the serve drain
         * ({@code AdminKnobVerb}) sets, re-spools the PSTB block to every seat, mails the receipt. */
        KNOB_SHOW, KNOB_SET,
        /** {@code /terrain} — the battle terrain pair ({@code WireKnob.TERRAIN}
         * + {@code TERRAIN_LEVEL}) in ONE verb. Bare = {@link #TERRAIN_SHOW} ({@code arg} "show");
         * {@code /terrain <word> [level]} = {@link #TERRAIN_SET} ({@code arg} "<word>" or "<word>
         * <level>", both folded against the two knobs' FIXED choice tables). Admin-gated at
         * dispatch; the serve drain ({@code AdminTerrainVerb}) sets both knobs, re-spools the PSTB
         * block, re-applies the terrain stat lane on the live ships, mails ONE receipt. */
        TERRAIN_SHOW, TERRAIN_SET,
        /** Bare {@code /wave} — the category MENU (a KIND_WAVE picker listing,
         * the bare-/spawnfoe shape; was USAGE, a usage line that helped a player less than a menu).
         * Admin-gated at dispatch. */
        WAVE_LIST, KICK_LIST, KICK_SET, KICK_WIRE,
        /** The admin debug-spawn verbs — {@code /debugships [variant]}
         * (the /ship shape over the FULL engine registry catalogue; the one-arg exact-pick vs
         * substring-filter split happens at dispatch, which holds the catalogue) and
         * {@code /spawnfoe <variant> [count]} (field side-1 AI foes mid-match). Admin-gated at
         * dispatch like /kick and /wave ({@link #ADMIN_ONLY}). Bare
         * {@code /spawnfoe} classifies as {@link #SPAWNFOE_LIST} (the door emits the full
         * catalogue as a KIND_SPAWNFOE picker listing — was USAGE). */
        DEBUGSHIPS_LIST, DEBUGSHIPS_SET, SPAWNFOE, SPAWNFOE_LIST,
        /** {@code /spawnfriend <variant> [count]
         * [@x,y]} = {@code /spawnfoe} EXACTLY, but the hull fields on the PLAYER side (side 0)
         * — "for testing things like this" (the LASH_FRIENDLY
         * presentation). Same grammar, same catalogue, same admin gate, same adhoc queue; bare
         * {@code /spawnfriend} = the KIND_SPAWNFRIEND picker listing. */
        SPAWNFRIEND, SPAWNFRIEND_LIST,
        /** The despawn verb ("despawn whatever unit i have selected with
         * R-hover"): {@code /despawn #<wireId>} — remove a NON-SEAT hull from the field (a vanish
         * through the engine's own removeObject, never a kill). The grammar accepts ONLY the
         * {@code #}-digits form (the /kick #id law; {@code arg} = the bare digits): the modded
         * client resolves its engine ship target (vanilla R) to the registry id and sends this
         * form; a bare line = no target / an un-modded client ⇒ the constant usage reply.
         * Admin-gated at dispatch; the serve refuses a seat's wire, a transfer pod and an unknown
         * id with a receipt (D5 law). */
        DESPAWN,
        /** {@code /switchteams [text]} — the admin team-switch
         * MENU (a KIND_SWITCHTEAMS picker listing: ONE row per live player, its mark the mode
         * {@code instant} / {@code respawn} the client toggles locally; {@code arg} = the
         * optional name pre-filter, the {@code /ship <text>}
         * shape — null = everyone). {@code /switchteams #<id> instant|respawn} = the menu's
         * paste form ({@code arg} = the bare wire-id digits, the {@code /kick #id} law; the mode
         * is a FIXED ASCII-folded keyword and rides the KIND). Admin-gated at dispatch; the
         * effect is the serve loop's {@link SeatTeamLane}. */
        SWITCHTEAMS_LIST, SWITCHTEAMS_INSTANT, SWITCHTEAMS_RESPAWN,
        /** {@code /auth <password>} — MID-GAME admin elevation. The
         * grammar only classifies; the DOOR owns the constant-time compare against the armed
         * admin secret, the D4 credential-fail budget (a chat {@code /auth} is a GUESS SITE,
         * same class as the HELLO password sites) and the elevation itself. {@code arg} carries
         * the presented password IN MEMORY ONLY — never echoed in any reply and redacted from
         * the audit line ({@link #auditText}). */
        AUTH,
        /** {@code /podtest <scenario>} — the admin pod-scenario driver verb
         * (pod scenarios are never again staged by hand). The
         * grammar validates the scenario against the FIXED {@link PodScenarioDriver#TOKENS}
         * set; the door admin-gates and deposits {@code POD_TEST} for the CALLER's seat; the
         * serve loop's {@link PodScenarioDriver} stages the scenario and mails the verdict. */
        PODTEST,
        /** The god-mode verb ("a /godmode cmd that makes me not take dmg ...
         * debug-only / admin-only"): {@code /godmode [on|off]} — bare = TOGGLE, {@code on}/
         * {@code off} = explicit set ({@code arg} carries the folded mode token, one of
         * {@code toggle|on|off}). The door admin-gates and deposits {@code GOD_MODE} for the
         * CALLER's seat; the serve loop's {@link AdminSeatDebugLane} owns the effect (the
         * engine's own damage-taken-mult-0 invulnerability idiom). */
        GODMODE,
        /** The teleport verb ("/teleport ... to where my mouse cursor is located
         * on [the tab map]"): {@code /teleport @x,y} — the {@code /spawnfoe} pose grammar
         * VERBATIM ({@link #parseSpawnPoseOrNull}); {@code pose} carries the validated token,
         * null = bare {@code /teleport} (the door answers the usage line to an admin — the client
         * mod intercepts the bare verb locally as the ARM toggle, so a bare line reaching the
         * server is an un-modded or non-admin client). Serve-side map clamp like the spawn pose. */
        TELEPORT,
        /** W15 v0 step 3(f): {@code /customs} — the caller's OWN accepted custom
         * variants as a KIND_SHIP picker listing (own-only law; the picker pastes
         * {@code /ship <id>}, which resolves own customs since step 3(c)). No arg form. The door
         * answers it (the /help precedent: the author key is the door's customAuthorKey). */
        CUSTOMS_LIST,
        /** Peer-build sharing v2 ({@code variant-maker-peer-builds.md}): the SHARE verbs,
         * all door-answered (ownership = the session's LIVE id, which dispatch never sees).
         * {@code /share} bare = a KIND_SHARE listing of the caller's own accepted ids with their
         * share bit; {@code /share <id>} = share it (a broadcast line on the first OFF→ON);
         * {@code /share <id> silent} = the same, no line ({@code silent} is a FIXED keyword —
         * any other 2nd token is USAGE); {@code /share auto on|off [silent]} = the seat's
         * fielding auto-share flag ({@code arg} = the folded {@code on}/{@code off}; the client
         * sends the silent form at admission + on toggle); {@code /unshare <id>};
         * {@code /builds} = a KIND_BUILDS listing of every shared id (a pick saves LOCALLY). Ids
         * ride as the canonical STRING token — the {@code #} form stays /transfer's numeric law. */
        SHARE_LIST, SHARE_SET, SHARE_SET_SILENT, SHARE_AUTO, SHARE_AUTO_SILENT, UNSHARE_SET,
        BUILDS_LIST,
        /** The P27 admin ban verbs — bare or
         * malformed {@code /ban} = {@code BAN_USAGE} (its OWN usage line, answered only to an
         * admin: the dispatch gate runs first); {@code /ban #<wireId>} = {@code BAN_WIRE}
         * ({@code arg} = the digits, the /kick #id law — never a bare name); {@code /unban #<n>}
         * = {@code UNBAN_INDEX} ({@code arg} = 1..4 digits, an index into {@code /bans});
         * {@code /bans [page]} = {@code BANS_LIST} ({@code arg} = the page digits or null).
         * Admin-gated at DISPATCH ({@link #ADMIN_ONLY}); bodies in {@link AdminBanCommand}.
         * BARE {@code /ban} = {@code BAN_LIST} (the
         * KIND_BAN picker menu) and BARE {@code /unban} = {@code UNBAN_LIST} (the KIND_UNBAN
         * menu, newest first); a malformed arg keeps its usage line. */
        BAN_USAGE, BAN_LIST, BAN_WIRE, UNBAN_LIST, UNBAN_INDEX, BANS_LIST,
        /** {@code /banned #<n> <term>} — re-time slot n of
         * the caller's pinned ban numbering ({@code arg} = the index digits, the UNBAN_INDEX law;
         * {@code term} = the canonical {@link BanTerm} token); bare {@code /banned} = UNBAN_LIST. */
        BANNED_RETIME,
        /** The host's ship-list verbs (admin-gated at DISPATCH, {@link #ADMIN_ONLY}): {@code /shiplist} (exactly 1
         * token) and {@code /shipkeep <id>} / {@code /shipremove <id>} / {@code /shiprestore <id>} (exactly 2 tokens,
         * the id one token under the operator file's id law, case kept: {@code arg} = the id AS TYPED — the serve
         * classifies it and its receipts echo it verbatim). Anything else = the verb's constant usage reply.
         * {@code /shipkeep} also takes exactly 4 tokens,
         * {@code /shipkeep <id> replaces <ship id>} (the supersede): {@code arg} = {@code "<id> replaces <ship id>"},
         * both ids as typed. */
        HOST_SHIP_LIST, HOST_SHIP_KEEP, HOST_SHIP_REMOVE, HOST_SHIP_RESTORE,
        USAGE, UNKNOWN }

    /** The /godmode usage reply (constant-only; bare = toggle). */
    private static final String GODMODE_USAGE = "usage: /godmode (toggle; admin) or /godmode on|off";

    /** Peer-build sharing v2: the constant usage replies (never an echo of the arg). */
    public static final String SHARE_USAGE =
            "usage: /share [id] [silent] (no arg = list yours) or /share auto on|off";
    public static final String UNSHARE_USAGE = "usage: /unshare <id>";
    public static final String BUILDS_USAGE = "usage: /builds (no argument)";
    /** The ONE refusal for an unknown, banned or foreign id (no enumeration: the wire cannot
     * tell "not yours" from "no such id"). */
    public static final String SHARE_REFUSED = "unknown build id (see /share for your own ids)";
    /** The fixed 2nd keyword that suppresses the broadcast line. */
    public static final String SHARE_SILENT_KEYWORD = "silent";

    /**
     * The server-minted broadcast line for a seat's first {@code /share} of an id:
     * {@code seat N broadcast a build: "<displayName>" (<hullId>) - /builds to save it}. The seat
     * label is the SERVER vocabulary ({@code "seat " + seatId}, never the player-announced name);
     * the display name is peer TEXT — quoted via {@link PeerText#quote} (escapes {@code "} and
     * {@code \}, folds any non-printable to a space) — and the WHOLE line is hard-clamped to
     * {@link ChatFrameCodec#TEXT_MAX_CHARS} by cutting the NAME first (the tail verb hint always
     * survives), so {@code ChatFrameCodec.encodeDown} never refuses it. {@code hullId} is server
     * truth (the canonical's hull), printable ASCII by the VDEF id law.
     */
    public static String shareBroadcastLine(int seatId, String displayName, String hullId) {
        return clampQuotedName("seat " + seatId + " broadcast a build: ", displayName,
                " (" + (hullId == null ? "" : hullId) + ") - /builds to save it");
    }

    /**
     * The PRIVATE reply to a {@code /share} of an owned custom whose loadout IS a
     * {@code /ship}-pickable server build ({@link StockBuildRedirect}):
     * {@code not broadcast: it is the stock build "<displayName>" - use /ship <stockId>}. Both are
     * server kit DATA (the catalog's lawful display name, the pickable canonical id ≤ 64 chars);
     * the name is still quoted and cut first, so the id always survives inside the 120-char law.
     */
    public static String stockRedirectLine(String displayName, String stockId) {
        return clampQuotedName("not broadcast: it is the stock build ", displayName,
                " - use /ship " + (stockId == null ? "" : stockId));
    }

    /** {@code head + PeerText.quote(displayName) + tail}, the WHOLE line clamped to
     * {@link ChatFrameCodec#TEXT_MAX_CHARS} by cutting the quoted NAME first (the tail survives). */
    private static String clampQuotedName(String head, String displayName, String tail) {
        String quoted = cc.starsector.server.ops.PeerText.quote(displayName == null ? "" : displayName);
        int room = ChatFrameCodec.TEXT_MAX_CHARS - head.length() - tail.length();
        if (quoted.length() > room) {
            // Cut the quoted BODY (between the quotes) so the closing quote survives; a cut that
            // strands an odd run of trailing backslashes would escape that closing quote, so one
            // more char goes. room >= 2 for every caller's lawful ids (head + tail <= 114 chars).
            int bodyRoom = Math.max(0, room - 2);
            String body = quoted.substring(1, quoted.length() - 1);
            body = body.substring(0, Math.min(body.length(), bodyRoom));
            int trailing = 0;
            for (int i = body.length() - 1; i >= 0 && body.charAt(i) == '\\'; i--) {
                trailing++;
            }
            if ((trailing & 1) == 1) {
                body = body.substring(0, body.length() - 1);
            }
            quoted = "\"" + body + "\"";
        }
        String line = head + quoted + tail;
        return line.length() <= ChatFrameCodec.TEXT_MAX_CHARS
                ? line : line.substring(0, ChatFrameCodec.TEXT_MAX_CHARS);
    }

    /** The /teleport usage reply — PUBLIC: the door sends it to an ADMIN whose bare
     * {@code /teleport} reached the server (a client without the arm-key lane). Names the
     * client UX so the line teaches the verb (constant-only, no echo). */
    public static final String TELEPORT_USAGE = "usage: /teleport @x,y (admin) - or type /teleport"
            + " then press T with the cursor over the map";

    /** The /endmatch usage reply (constant-only, no echo): the verb takes no argument. */
    public static final String ENDMATCH_USAGE = "usage: /endmatch (admin; no argument) - ends the match now";

    /** The host ship verbs' usage replies (constants: a malformed id is never echoed).
     * */
    public static final String SHIPLIST_USAGE = "usage: /shiplist (admin; no argument)";
    public static final String SHIPKEEP_USAGE = "usage: /shipkeep <id> [replaces <ship id>] (admin)";
    /** The supersede spelling's third token, exactly {@code /shipkeep <id> replaces <ship id>}. */
    public static final String SHIPKEEP_REPLACES_WORD = "replaces";
    public static final String SHIPREMOVE_USAGE = "usage: /shipremove <id> (admin)";
    public static final String SHIPRESTORE_USAGE = "usage: /shiprestore <id> (admin)";

    /** The /podtest usage reply — names the fixed scenario set (constant-only,
     * built from the driver's canonical token list so the grammar can never drift). */
    private static final String PODTEST_USAGE = "usage: /podtest <"
            + String.join("|", PodScenarioDriver.TOKENS) + "> (admin; stages that pod scenario"
            + " for your seat)";

    /** The one /mute usage reply (W12 L2; constant-only, mirrors {@link #TRANSFER_USAGE}). */
    private static final String MUTE_USAGE = "usage: /mute [player|#id]";

    /** The /kick and /wave usage replies (constant-only, the same law). */
    private static final String KICK_USAGE = "usage: /kick [player|#id]";
    /** The /unban and /bans usage replies (constants).
     *  The /unban line names the no-arg menu. */
    public static final String UNBAN_USAGE = "usage: /unban #n (admin; no arg = the menu; n from it or /bans)";
    public static final String BANS_USAGE = "usage: /bans [page] (admin)";
    /** The /banned usage ({@code #n} alone, {@code #n lift},
     *  a non-ladder term — never an echo). */
    public static final String BANNED_USAGE = "usage: /banned [#n <term>] (admin; 30m 1d 1w 1mo perm)";
    // + `wrap` (the round-wrap staging verb).
    private static final String WAVE_USAGE =
            "usage: /wave skip|pause|wrap|set <n>|inflate [on|off]|<type> [reset] (no arg = menu)";

    /** The /wave wrap refusal on the serve's LAST round
     * (an END_ROUND there ends the whole serve instead of wrapping; constant-only receipt). */
    public static final String WRAP_REFUSED_LAST_ROUND =
            "round wrap refused: this is the last round of the serve (it would end the serve)";
    /** The /wave wrap double-press receipt (the round
     * end is already latched this tick; constant-only). */
    public static final String WRAP_ALREADY_ENDING =
            "round wrap: this round is already ending - the next round redeploys every seat";

    /**
     * The /wave wrap success receipt. {@code roundIndex}
     * is the serve's ZERO-based round counter (PilotServeSession's {@code round}); the text is
     * 1-based for humans ({@code round N ends now - round N+1 ...} with N = roundIndex + 1).
     * Digits only are interpolated, never player text.
     */
    public static String wrapReceipt(int roundIndex) {
        int n = roundIndex + 1;
        return "round wrap: round " + n + " ends now - round " + (n + 1) + " redeploys every seat";
    }

    /** The /wave &lt;type&gt; usage reply. Earlier it
     * enumerated {@link cc.starsector.server.match.WaveSchedule#WAVE_CATEGORIES}; thirteen
     * categories no longer fit the {@link ChatFrameCodec#TEXT_MAX_CHARS} chat line, so the
     * reply points at the bare-/wave MENU (the KIND_WAVE picker, which IS the set) and names
     * the optional reset word. Still constant-only, no echo: any unknown /wave arg answers this
     * (the arg was most likely a typo'd faction). */
    private static final String WAVE_CATEGORY_USAGE =
            "usage: /wave <type> [reset] (next wave becomes that faction; no arg = the type menu)";
    /** The /wave set usage reply (constant-only; the digit bound is named). */
    private static final String WAVE_SET_USAGE =
            "usage: /wave set <1-9> (that wave spawns next; 1 or 2 = restart at the opener)";
    /** The /endless usage reply (constant-only, the /godmode shape). */
    private static final String ENDLESS_USAGE = "usage: /endless (no arg = show; on or off sets it)";
    /** The /wave inflate usage reply (constant-only, the /endless shape). */
    private static final String WAVE_INFLATE_USAGE =
            "usage: /wave inflate (no arg = show; on or off sets it)";
    /** The /knob usage reply (constant-only; the one knob today is cloaklead). */
    private static final String KNOB_USAGE =
            "usage: /knob (no arg = list; cloaklead shows it; cloaklead lead or wait sets it)";
    /** The {@code /terrain} usage reply (constant-only, the same law). */
    private static final String TERRAIN_USAGE =
            "usage: /terrain (no arg = show; none, corona, pulsar or abyss sets it, + 0, 25, 50, 75 or 100 = level)";

    /** The /debugships and /spawnfoe usage replies (constant-only, the same law). */
    private static final String DEBUGSHIPS_USAGE = "usage: /debugships [variant]";
    private static final String SPAWNFOE_USAGE =
            "usage: /spawnfoe <variant> [count] [officer] [@x,y]";
    /** The {@code /spawnfriend} twin's usage reply (constant-only, the same law). */
    private static final String SPAWNFRIEND_USAGE =
            "usage: /spawnfriend <variant> [count] [officer] [@x,y]";
    /** The {@code /despawn} usage reply (constant-only; public for the contract
     *  rows). A modded client never sends the bare verb — it resolves the R-target locally. */
    public static final String DESPAWN_USAGE = "usage: /despawn #id (R-target a ship, then type /despawn)";
    /** The FIXED spawn-grammar keyword that fields the hull with a maxed captain
     *  (ASCII-folded at parse; the deposit text + operator file carry it verbatim, lowercase). */
    public static final String SPAWN_OFFICER_KEYWORD = "officer";

    /** The ONE ASCII fold of {@link #SPAWN_OFFICER_KEYWORD} on
     *  every parse seam (chat verb, {@code AdminWaveVerbs.spawn}, the operator file) — the three
     *  used to fold three ways (asciiLower / equalsIgnoreCase / exact). Null-safe. */
    public static boolean isOfficerKeyword(String token) {
        return token != null && SPAWN_OFFICER_KEYWORD.equals(asciiLower(token));
    }
    /** The {@code /switchteams} usage reply (constant-only, the same law). */
    public static final String SWITCHTEAMS_USAGE =
            "usage: /switchteams [player] (menu) or /switchteams #id instant|respawn";

    /** The optional {@code @x,y} spawn-pose token — two decimal numbers,
     * digits-only with an optional sign / ≤ 3 decimals, ≤ 6 integer digits (so the value is
     * finite and Float-exact enough; NO exponent, NO whitespace, NO locale): the security
     * sheet's digits-only law. Bounded length keeps the regex linear. */
    private static final java.util.regex.Pattern SPAWN_POSE_TOKEN = java.util.regex.Pattern
            .compile("^@-?\\d{1,6}(\\.\\d{1,3})?,-?\\d{1,6}(\\.\\d{1,3})?$");
    private static final int SPAWN_POSE_MAX_CHARS = 24;

    /**
     * The shared {@code /spawnfoe} / {@code /spawnfriend} grammar (one refactor folded the
     * spawnfoe case body into this one helper, byte-identical semantics): {@code <variant>}
     * (verbatim case — a boot-catalogue allowlist key at the door, never an engine input),
     * {@code [count]} optional — the wire-id digit law (fail-closed: digits only,
     * Long-bounded), then 1..{@link #SPAWNFOE_MAX_COUNT}; absent ⇒ the 0 sentinel (see the
     * {@code Parsed} javadoc). A bare verb = {@code listKind}; anything else = the constant
     * usage reply. An optional LAST token {@code @x,y}
     * ({@link #parseSpawnPoseOrNull} — digits-only, bounded; a malformed {@code @...} is USAGE,
     * never a count).
     */
    private static Parsed parseSpawn(String[] tokens, boolean emptyToken, Kind setKind,
            Kind listKind, String usage) {
        if (emptyToken || tokens.length > 5) {
            return new Parsed(Kind.USAGE, null, usage);
        }
        if (tokens.length == 1) {
            return new Parsed(listKind, null, null);
        }
        // ONE token order on every seam — <variant> [count] [officer] [@x,y].
        // The pose stays LAST (the client's cursor-pose appender + both depositors hold that
        // law); the FIXED ASCII-folded `officer` keyword sits right before it (the /switchteams
        // mode-keyword idiom). A 2-token line never reads its second token as the keyword: that
        // token IS the variant (an id literally named "officer" is the door's unknown-id reply).
        String pose = null;
        int countTokens = tokens.length;
        if (tokens[tokens.length - 1].startsWith("@")) {
            if (parseSpawnPoseOrNull(tokens[tokens.length - 1]) == null) {
                return new Parsed(Kind.USAGE, null, usage);
            }
            pose = tokens[tokens.length - 1];
            countTokens--;
        }
        boolean officer = false;
        if (countTokens >= 3 && isOfficerKeyword(tokens[countTokens - 1])) {
            officer = true;
            countTokens--;
        }
        if (countTokens > 3) {
            return new Parsed(Kind.USAGE, null, usage);
        }
        int count = 0;
        if (countTokens == 3) {
            String digits = tokens[2];
            if (digits.isEmpty() || digits.length() > 19) {
                return new Parsed(Kind.USAGE, null, usage);
            }
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                if (c < '0' || c > '9') {
                    return new Parsed(Kind.USAGE, null, usage);
                }
            }
            try {
                long parsed = Long.parseLong(digits);
                if (parsed < 1 || parsed > SPAWNFOE_MAX_COUNT) {
                    return new Parsed(Kind.USAGE, null, usage);
                }
                count = (int) parsed;
            } catch (NumberFormatException overflow) {
                return new Parsed(Kind.USAGE, null, usage);
            }
        }
        return new Parsed(setKind, tokens[1], null, count, pose, officer);
    }

    /**
     * Validate one {@code @x,y} token → {@code {x, y}} or null (anything
     * off-grammar, over-long, or non-finite). Pure, never throws — the door, the operator
     * file and the serve's operator switch all call this ONE law (the map clamp is separate:
     * {@link AdhocFoeSpawner#poseInsideMap}).
     */
    public static float[] parseSpawnPoseOrNull(String token) {
        if (token == null || token.length() < 4 || token.length() > SPAWN_POSE_MAX_CHARS
                || token.charAt(0) != '@' || !SPAWN_POSE_TOKEN.matcher(token).matches()) {
            return null;
        }
        int comma = token.indexOf(',');
        try {
            float x = Float.parseFloat(token.substring(1, comma));
            float y = Float.parseFloat(token.substring(comma + 1));
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                return null;
            }
            return new float[] {x, y};
        } catch (NumberFormatException off) {
            return null;
        }
    }

    /** Most foes ONE /spawnfoe command may field.
     * ONE home — this IS the adhoc queue's aggregate cap {@link AdhocFoeSpawner#MAX_PENDING} (was
     * 8; counts 9 and 10 answered USAGE); a count above the queue's
     * free room drops the remainder with the queue-full receipt. A constant, no knob. */
    public static final int SPAWNFOE_MAX_COUNT = AdhocFoeSpawner.MAX_PENDING;

    /** The one reply a non-admin session gets from ANY admin verb — constant, no
     * echo, no hint which credential would have worked (the door decides; the grammar only owns
     * the text so every admin kind answers identically). */
    public static final String ADMIN_ONLY = "admin only";

    /** The /auth usage reply (constant-only, the same no-echo law — a
     * malformed attempt may hold a mistyped password, so the reply never quotes anything). */
    static final String AUTH_USAGE = "usage: /auth <password>";

    /** The EXACT /auth success reply. MIRRORED CONSTANT — the client flips its
     * presentation-only elevation bit on an exact match of this text from the server-stamped
     * reply lane ({@code MatchConfigFromWire.AUTH_ELEVATED_REPLY} must stay byte-identical; the
     * elevation PUSH rides the existing unicast CHTD reply, no new wire frame kind). Sent on
     * every successful /auth including an already-admin re-auth (idempotent), so a client that
     * missed the reply re-syncs by re-typing the verb. */
    public static final String AUTH_ELEVATED = "admin elevated";

    /** The EXACT reply to an in-session /auth the
     * {@code DoorGuessLedger} KEY path HELD before any compare (the door's AUTH branch — no
     * compare ran, the seat is admitted, so this is neither an oracle nor an amplifier).
     * MIRRORED CONSTANT — byte-identical to the client mirror {@code ChatInputLaw.AUTH_HELD_REPLY}:
     * the client retires its in-flight line on an exact match and retries a held one-shot re-admit
     * replay ONCE ({@code AuthReplayLaw}); drift stops that retry (fail-safe), never elevation.
     * Pinned by the P-A1 smoke row ({@code PilotDatagramEndpointSmoke}). */
    public static final String AUTH_HELD = "admin auth held - retry in a moment";

    /** The private reply to a line {@link #ledCommandLine} catches (the door answers the sender only; the line is
     *  neither relayed nor logged). A constant: no echo of any typed character. */
    public static final String LED_COMMAND_NOT_SENT =
            "not sent: a command starts with / as the very first character (nothing was shown to other players)";

    /**
     * A chat line the door must NOT relay although it does not start with {@code '/'}.
     * WHY: the secret of a mistyped {@code /auth <secret>} must never ride ordinary
     * chat — a relayed line reaches every seat AND the {@code chat_relay} log row, verbatim
     * ({@code CtrlFanout.fanChat}). A version-13 kit refuses such a line before sending
     * ({@code ChatInputLaw.auth13Class}); a version-12 session, an older kit and any other sender have only this door.
     * Two shapes, read per CODE POINT:
     * <ul>
     * <li>a COMMAND BEHIND A LEADING RUN — one or more space / whitespace / FORMAT code points (U+00A0, U+3000, U+200B,
     *     U+2060, U+FEFF: a paste, an IME, a stray key; the wire law's {@code trim} strips only up to U+0020), then
     *     {@code '/'}. ANY verb, not only {@code auth}: a typo'd verb with the secret glued on ({@code /atuhpw}) is
     *     indistinguishable from any other, and a command behind an invisible character was never meant as chat;</li>
     * <li>a SLASH LOOK-ALIKE opener (after the same optional run) — a {@link #slashLookAlike} character, then a run of
     *     slashes, look-alikes, space characters and FORMAT code points, then {@code auth} in any case. Only
     *     {@code auth} here: {@code \o/} is ordinary chat.</li>
     * </ul>
     * NOT caught, by design: real text before the slash ({@code hi /auth pw} — a sentence, the kit's law too) and a
     * look-alike opener on a typo'd verb ({@code \aut pw}); a punctuation-only paste prefix (a quote or a backtick
     * before the slash) is the queued rider {@code led-relay-punct-prefix}. Pure; a line that starts with {@code '/'} is a command to
     * {@link #parse} and answers {@code false} here. Rows: ChatCommandContractRows (the table), Handshake13AuthRows
     * A13-14 (the door).
     */
    public static boolean ledCommandLine(String text) {
        int k = 0;
        while (k < text.length()) {
            int cp = text.codePointAt(k);
            if (!Character.isSpaceChar(cp) && !Character.isWhitespace(cp) && Character.getType(cp) != Character.FORMAT) {
                break;
            }
            k += Character.charCount(cp);
        }
        if (k >= text.length()) {
            return false;
        }
        if (text.charAt(k) == '/') {
            return k > 0;
        }
        if (!slashLookAlike(text.charAt(k))) {
            return false;
        }
        k++;
        while (k < text.length()) {
            int cp = text.codePointAt(k);
            if (!(cp <= 0xFFFF && (cp == '/' || slashLookAlike((char) cp))) && !Character.isSpaceChar(cp)
                    && Character.getType(cp) != Character.FORMAT) {
                break;
            }
            k += Character.charCount(cp);
        }
        return authAt(text, k);
    }

    /** {@code auth} at {@code k} in any case, a FULL-WIDTH form (U+FF01..U+FF5E: an IME's wide mode) read as its ASCII
     *  twin: {@code regionMatches} never folds a wide letter, so a wide slash + wide
     *  {@code auth} + U+3000 + the secret was relayed to every seat. Rows: ChatCommandContractRows led[9] / led[10]. */
    private static boolean authAt(String text, int k) {
        if (k + 4 > text.length()) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(k + i);
            if (c >= (char) 0xFF01 && c <= (char) 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            if (Character.toLowerCase(c) != "auth".charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The slash LOOK-ALIKES this door catches; none is a command to {@link #parse}. The set itself =
     *  {@link WireTextLaw#isSlashLookAlike} (ONE predicate shared with the kit's {@code ChatInputLaw.slashLike},
     *  the kit's own copy was a subset). NOT every key a hand can land on (named
     *  residuals): a neighbouring or other-layout key ({@code 7 : ? . - |}) and a forgotten slash ({@code auth pw})
     *  still relay. */
    private static boolean slashLookAlike(char c) {
        return WireTextLaw.isSlashLookAlike(c);
    }

    /**
     * One grammar verdict. {@code arg} is non-null only for {@link Kind#SHIP_SET}/
     * {@link Kind#MODULE_SET}/{@link Kind#TRANSFER_SET} (verbatim case — the allowlist match
     * is case-insensitive at lookup) and {@link Kind#TRANSFER_WIRE} (the validated digit
     * string, {@code #} stripped); {@code reply} is non-null only for {@link Kind#USAGE}/
     * {@link Kind#UNKNOWN}, the grammar-owned private replies; {@code count} is meaningful only
     * for {@link Kind#SPAWNFOE}: an explicit count validates 1..{@link #SPAWNFOE_MAX_COUNT},
     * an ABSENT count parses as the 0 sentinel (the door spawns 1 on an exact
     * variant but falls to the substring-FILTER listing on a non-exact one; an explicit count
     * keeps the hard unknown-variant reply: typing a count states spawn intent). Every other
     * kind rides the 3-arg convenience ctor's 0. {@code pose} is the
     * VALIDATED {@code @x,y} token verbatim (only for {@link Kind#SPAWNFOE}; null = no pose =
     * wave-edge placement). {@code term} = the canonical
     * {@link BanTerm} token of a {@link Kind#BAN_WIRE} (null = bare: the serve applies
     * {@link BanTerm#DEFAULT}) or a {@link Kind#BANNED_RETIME}; null for every other kind.
     */
    public record Parsed(Kind kind, String arg, String reply, int count, String pose,
            boolean officer, String note, String term) {

        /** The older 7-arg shape — no ban term. */
        public Parsed(Kind kind, String arg, String reply, int count, String pose,
                boolean officer, String note) {
            this(kind, arg, reply, count, pose, officer, note, null);
        }

        /** The older 3-arg shape — every non-counted verb; {@code count} stays 0. */
        public Parsed(Kind kind, String arg, String reply) {
            this(kind, arg, reply, 0, null, false, null);
        }

        /** The older 4-arg shape — a counted verb with no pose. */
        public Parsed(Kind kind, String arg, String reply, int count) {
            this(kind, arg, reply, count, null, false, null);
        }

        /** The older 5-arg shape — a counted, posed verb with no officer keyword. */
        public Parsed(Kind kind, String arg, String reply, int count, String pose) {
            this(kind, arg, reply, count, pose, false, null);
        }

        /** The older 6-arg shape — no autocorrect note. */
        public Parsed(Kind kind, String arg, String reply, int count, String pose,
                boolean officer) {
            this(kind, arg, reply, count, pose, officer, null);
        }

        /** The same verdict carrying the ONE fixed-shape autocorrect note
         *  ({@code "corrected /<typed alnum run ≤24> -> /<verb>"}) the door prepends to its replies. */
        Parsed withNote(String correctionNote) {
            return new Parsed(kind, arg, reply, count, pose, officer, correctionNote, term);
        }
    }

    private ChatCommands() {}

    /** Classify one law-conforming chat line; text NOT starting {@code /} is NOT_COMMAND. */
    public static Parsed parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.startsWith("/")) {
            return new Parsed(Kind.NOT_COMMAND, null, null);
        }
        String[] tokens = text.split(" ", -1);
        String name = asciiLower(tokens[0].substring(1));
        boolean emptyToken = false;
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].isEmpty()) {
                emptyToken = true;
                break;
            }
        }
        switch (name) {
            case "suicide":
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, "usage: /suicide (no argument)");
                }
                return new Parsed(Kind.SUICIDE, null, null);
            case "ships":
                // /ships = /ship VERBATIM — one grammar,
                // one usage text, the same kinds (the door + dispatch never see the spelling).
            case "ship":
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, "usage: /ship [variant]");
                }
                return tokens.length == 1
                        ? new Parsed(Kind.SHIP_LIST, null, null)
                        : new Parsed(Kind.SHIP_SET, tokens[1], null);
            case "module":
                // W2b: no arg = listing; the ONE reserved arg "off" = dismount /
                // claim-clear; anything else = a claim token for the arbiter's allowlist.
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, "usage: /module [slot|off]");
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.MODULE_LIST, null, null);
                }
                return asciiLower(tokens[1]).equals("off")
                        ? new Parsed(Kind.MODULE_OFF, null, null)
                        : new Parsed(Kind.MODULE_SET, tokens[1], null);
            case "transfer":
                // W3: no arg = eligible-target listing; one arg = a target stem
                // resolved against the serve-published census ({@code TransferTargets}) — the
                // /ship shape: an in-memory allowlist lookup key, never a path/exec input.
                // W3 L4 S2: a "#"-prefixed arg is the client trigger's numeric
                // wireId form — bounded-digit fail-closed decode HERE in the pure parser
                // (security law: ≤19 digits, [0-9] only, Long-bounded, >0; ANY reject = the
                // constant usage reply, no junk echo); dispatch resolves it against the SAME
                // published census, so membership stays the only identity path.
                return playerArg(tokens, emptyToken, TRANSFER_USAGE, Kind.TRANSFER_LIST,
                        Kind.TRANSFER_SET, Kind.TRANSFER_WIRE);
            case "mute":
                // W12 L2: the /transfer grammar verbatim — no arg
                // = listing; "#"-digits = a hull WIRE id (≤19 digits, >0, Long-bounded; ANY
                // reject = the constant usage reply); else a name stem resolved by dispatch
                // against the live-session census (never a path/exec input).
                return playerArg(tokens, emptyToken, MUTE_USAGE, Kind.MUTE_LIST, Kind.MUTE_SET,
                        Kind.MUTE_WIRE);
            case "kick":
                // The /mute grammar verbatim — no arg = the live-player
                // listing; "#"-digits = a hull WIRE id; else a name stem resolved by dispatch
                // against the live sessions. Admin-gated at DISPATCH (session bit), not here.
                return playerArg(tokens, emptyToken, KICK_USAGE, Kind.KICK_LIST, Kind.KICK_SET,
                        Kind.KICK_WIRE);
            case "ban": {
                // Exactly `#<wireId>` (the /kick #id digit
                // law); anything else = BAN_USAGE (its own line, admin-gated at dispatch).
                // BARE = the KIND_BAN menu.
                if (!emptyToken && tokens.length == 1) {
                    return new Parsed(Kind.BAN_LIST, null, null);
                }
                // + an optional 3rd token = a BanTerm ladder
                // token (else BAN_USAGE, never echoed); term = its canonical token, null = bare.
                String banDigits = emptyToken || tokens.length < 2 || tokens.length > 3 || !tokens[1].startsWith("#")
                        ? null : wireDigitsOrNull(tokens[1]);
                BanTerm banTerm = tokens.length == 3 ? BanTerm.parseOrNull(tokens[2]) : null;
                return banDigits == null || (tokens.length == 3 && banTerm == null)
                        ? new Parsed(Kind.BAN_USAGE, null, AdminBanCommand.BAN_USAGE)
                        : new Parsed(Kind.BAN_WIRE, banDigits, null, 0, null, false, null,
                                banTerm == null ? null : banTerm.token);
            }
            case "banned": {
                // BARE = the /unban menu (time left per row);
                // exactly `#<n> <term>` = BANNED_RETIME (the /unban #n index law + a ladder token);
                // `#n` alone, `#n lift` or any other shape = the usage line (never an echo).
                if (!emptyToken && tokens.length == 1) {
                    return new Parsed(Kind.UNBAN_LIST, null, null);
                }
                String slot = emptyToken || tokens.length != 3 || !tokens[1].startsWith("#") ? null
                        : smallDigitsOrNull(tokens[1].substring(1));
                BanTerm retime = slot == null ? null : BanTerm.parseOrNull(tokens[2]);
                return retime == null ? new Parsed(Kind.USAGE, null, BANNED_USAGE)
                        : new Parsed(Kind.BANNED_RETIME, slot, null, 0, null, false, null, retime.token);
            }
            case "unban": {
                // A8: exactly `#<1..4 digits>` > 0 — an index into the caller's /bans listing.
                // BARE = the KIND_UNBAN menu (its #n
                // indexes the caller's PINNED listing).
                if (!emptyToken && tokens.length == 1) {
                    return new Parsed(Kind.UNBAN_LIST, null, null);
                }
                String index = emptyToken || tokens.length != 2 || !tokens[1].startsWith("#") ? null
                        : smallDigitsOrNull(tokens[1].substring(1));
                return index == null ? new Parsed(Kind.USAGE, null, UNBAN_USAGE)
                        : new Parsed(Kind.UNBAN_INDEX, index, null);
            }
            case "bans": {
                // A8: bare = page 1; one token of 1..4 digits > 0 = that page.
                if (!emptyToken && tokens.length == 1) {
                    return new Parsed(Kind.BANS_LIST, null, null);
                }
                String page = emptyToken || tokens.length != 2 ? null : smallDigitsOrNull(tokens[1]);
                return page == null ? new Parsed(Kind.USAGE, null, BANS_USAGE)
                        : new Parsed(Kind.BANS_LIST, page, null);
            }
            case "endmatch":
                // The in-game F10 of a signed-in seat — no-argument form only (the
                // /suicide shape); the door gates it on the SESSION's admin bit, never on the text.
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, ENDMATCH_USAGE);
                }
                return new Parsed(Kind.END_MATCH, null, null);
            case "wave":
                // The F11/F12 hotkey bodies as ONE admin verb over a FIXED arg set
                // (ASCII-folded); anything malformed = the constant usage reply. A later change
                // added `category <type>` over the second fixed set (WaveSchedule.WAVE_CATEGORIES
                // — the same grammar-validated precedent); a further change makes
                // the bare verb the MENU listing and `<type>` alone the shorthand ("/wave omega")
                // — `category <type>` stays an alias so the old usage never breaks.
                // `/wave category <type> [reset|stay]` is the longest shape
                // (4 tokens); `/wave set <n>` and `/wave <type> [reset|stay]` are 3.
                if (emptyToken || tokens.length > 4) {
                    return new Parsed(Kind.USAGE, null, WAVE_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.WAVE_LIST, null, null);
                }
                String waveSub = asciiLower(tokens[1]);
                switch (waveSub) {
                    case "skip":
                        return tokens.length == 2 ? new Parsed(Kind.WAVE_SKIP, null, null)
                                : new Parsed(Kind.USAGE, null, WAVE_USAGE);
                    case "pause":
                        return tokens.length == 2 ? new Parsed(Kind.WAVE_PAUSE, null, null)
                                : new Parsed(Kind.USAGE, null, WAVE_USAGE);
                    case "wrap":
                        // Exactly 2 tokens (the skip shape)
                        return tokens.length == 2 ? new Parsed(Kind.WAVE_WRAP, null, null)
                                : new Parsed(Kind.USAGE, null, WAVE_USAGE);
                    case "set":
                        // Exactly one arg, ONE digit 1..9 — digits-only and
                        // bounded in the pure parser (security); the serve bounds it against
                        // the chain's real length with a receipt.
                        return tokens.length == 3 && tokens[2].length() == 1
                                && tokens[2].charAt(0) >= '1' && tokens[2].charAt(0) <= '9'
                                ? new Parsed(Kind.WAVE_SET, tokens[2], null)
                                : new Parsed(Kind.USAGE, null, WAVE_SET_USAGE);
                    case "inflate":
                        // Bare = the state receipt; one arg from the FIXED {on, off}
                        // set (ASCII-folded, the /endless shape); anything else = the constant
                        // usage reply. Admin-gated at dispatch; the serve drain owns the semantic.
                        if (tokens.length == 2) {
                            return new Parsed(Kind.WAVE_INFLATE_SHOW, null, null);
                        }
                        String inflateMode = tokens.length == 3 ? asciiLower(tokens[2]) : "";
                        return inflateMode.equals("on") || inflateMode.equals("off")
                                ? new Parsed(Kind.WAVE_INFLATE_SET, inflateMode, null)
                                : new Parsed(Kind.USAGE, null, WAVE_INFLATE_USAGE);
                    case "category":
                        return tokens.length >= 3
                                ? waveCategory(asciiLower(tokens[2]),
                                        tokens.length == 4 ? asciiLower(tokens[3]) : null)
                                : new Parsed(Kind.USAGE, null, WAVE_CATEGORY_USAGE);
                    default:
                        // the shorthand: `<type> [reset|stay]`, else the menu-naming usage
                        return tokens.length <= 3
                                ? waveCategory(waveSub,
                                        tokens.length == 3 ? asciiLower(tokens[2]) : null)
                                : new Parsed(Kind.USAGE, null, WAVE_USAGE);
                }
            case "knob": {
                // Bare = the census list; `<knob>` = that knob's state; `<knob>
                // <word>` = set. Both tokens from FIXED sets (WireKnob's verb names + its choice
                // words, ASCII-folded); anything else = the constant usage reply. Admin-gated at
                // dispatch; the serve drain (AdminKnobVerb) owns set + re-spool + receipt.
                if (emptyToken || tokens.length > 3) {
                    return new Parsed(Kind.USAGE, null, KNOB_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.KNOB_SHOW, "list", null);
                }
                WireKnob knob = WireKnob.byVerbName(asciiLower(tokens[1]));
                if (knob == null) {
                    return new Parsed(Kind.USAGE, null, KNOB_USAGE);
                }
                if (tokens.length == 2) {
                    return new Parsed(Kind.KNOB_SHOW, knob.verbName() + " show", null);
                }
                int choice = knob.choiceIndex(asciiLower(tokens[2]));
                return choice >= 0
                        ? new Parsed(Kind.KNOB_SET, knob.verbName() + " " + knob.choiceName(choice), null)
                        : new Parsed(Kind.USAGE, null, KNOB_USAGE);
            }
            case "terrain": {
                // Bare = show both knobs; `<word>` = set the terrain (level
                // kept); `<word> <level>` = set both. Every token from the two knobs' FIXED choice
                // tables (ASCII-folded); anything else = the constant usage reply. Admin-gated at
                // dispatch; the serve drain (AdminTerrainVerb) owns set + re-spool + re-apply + receipt.
                if (emptyToken || tokens.length > 3) {
                    return new Parsed(Kind.USAGE, null, TERRAIN_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.TERRAIN_SHOW, "show", null);
                }
                int word = WireKnob.TERRAIN.choiceIndex(asciiLower(tokens[1]));
                if (word < 0) {
                    return new Parsed(Kind.USAGE, null, TERRAIN_USAGE);
                }
                if (tokens.length == 2) {
                    return new Parsed(Kind.TERRAIN_SET, WireKnob.TERRAIN.choiceName(word), null);
                }
                int level = WireKnob.TERRAIN_LEVEL.choiceIndex(asciiLower(tokens[2]));
                return level >= 0
                        ? new Parsed(Kind.TERRAIN_SET, WireKnob.TERRAIN.choiceName(word) + " "
                                + WireKnob.TERRAIN_LEVEL.choiceName(level), null)
                        : new Parsed(Kind.USAGE, null, TERRAIN_USAGE);
            }
            case "endless": {
                // Bare = the state receipt; one arg from the FIXED {on, off}
                // set (ASCII-folded, the /godmode shape); anything else = the constant usage
                // reply. Admin-gated at dispatch; the serve drain owns semantic + receipt.
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, ENDLESS_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.ENDLESS_SHOW, null, null);
                }
                String mode = asciiLower(tokens[1]);
                return mode.equals("on") || mode.equals("off")
                        ? new Parsed(Kind.ENDLESS_SET, mode, null)
                        : new Parsed(Kind.USAGE, null, ENDLESS_USAGE);
            }
            case "debugships":
                // The /ship shape VERBATIM over the debug catalogue
                // (the full engine variant registry, D2) — list/set only, no #id wire form.
                // The one-arg exact-pick-else-substring-filter UX lives at dispatch (it needs
                // the catalogue); the grammar only classifies. Admin-gated at dispatch.
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, DEBUGSHIPS_USAGE);
                }
                return tokens.length == 1
                        ? new Parsed(Kind.DEBUGSHIPS_LIST, null, null)
                        : new Parsed(Kind.DEBUGSHIPS_SET, tokens[1], null);
            case "spawnfoe":
                return parseSpawn(tokens, emptyToken, Kind.SPAWNFOE, Kind.SPAWNFOE_LIST,
                        SPAWNFOE_USAGE);
            case "spawnfriend":
                // The /spawnfoe grammar VERBATIM — only
                // the kinds (and the usage text) differ; the side is the door's + serve's law.
                return parseSpawn(tokens, emptyToken, Kind.SPAWNFRIEND, Kind.SPAWNFRIEND_LIST,
                        SPAWNFRIEND_USAGE);
            case "despawn": {
                // Exactly `#<wireId>` — the /kick #id digit
                // law (bounded, [0-9] only, Long-bounded, > 0; ANY reject = the constant usage
                // reply, no echo). The modded client sends this form from its R-target; a bare
                // line (no target, an un-modded client) answers the usage. The seat / pod /
                // unknown-id refusals are the serve's (it holds the maps); admin-gated at
                // DISPATCH, not here.
                if (emptyToken || tokens.length != 2 || !tokens[1].startsWith("#")) {
                    return new Parsed(Kind.USAGE, null, DESPAWN_USAGE);
                }
                String despawnDigits = wireDigitsOrNull(tokens[1]);
                return despawnDigits == null ? new Parsed(Kind.USAGE, null, DESPAWN_USAGE)
                        : new Parsed(Kind.DESPAWN, despawnDigits, null);
            }
            case "switchteams": {
                // Bare = the player MENU; one non-# token = the
                // menu pre-filtered by that name text (the /ship <text> shape — dispatch owns the
                // match, it holds the names); "#<id> instant|respawn" = the menu's paste form
                // (the /kick #id digit law + a FIXED ASCII-folded mode keyword). Anything else =
                // the constant usage reply, no echo. Admin-gated at DISPATCH, not here.
                if (emptyToken || tokens.length > 3) {
                    return new Parsed(Kind.USAGE, null, SWITCHTEAMS_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.SWITCHTEAMS_LIST, null, null);
                }
                if (!tokens[1].startsWith("#")) {
                    return tokens.length == 2
                            ? new Parsed(Kind.SWITCHTEAMS_LIST, tokens[1], null)
                            : new Parsed(Kind.USAGE, null, SWITCHTEAMS_USAGE);
                }
                String switchDigits = wireDigitsOrNull(tokens[1]);
                if (switchDigits == null || tokens.length != 3) {
                    return new Parsed(Kind.USAGE, null, SWITCHTEAMS_USAGE);
                }
                String switchMode = asciiLower(tokens[2]);
                if (switchMode.equals("instant")) {
                    return new Parsed(Kind.SWITCHTEAMS_INSTANT, switchDigits, null);
                }
                return switchMode.equals("respawn")
                        ? new Parsed(Kind.SWITCHTEAMS_RESPAWN, switchDigits, null)
                        : new Parsed(Kind.USAGE, null, SWITCHTEAMS_USAGE);
            }
            case "team": {
                // Bare = show; exactly one word from the FIXED
                // {north, south} pair (ASCII-folded) = set; anything else = the constant usage reply,
                // no echo. The seat is never an argument: the door binds the SESSION's seat.
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, TEAM_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.TEAM_SHOW, null, null);
                }
                String teamWord = asciiLower(tokens[1]);
                return teamWord.equals(SeatTeamLane.WORD_NORTH) || teamWord.equals(SeatTeamLane.WORD_SOUTH)
                        ? new Parsed(Kind.TEAM_SET, teamWord, null)
                        : new Parsed(Kind.USAGE, null, TEAM_USAGE);
            }
            case "podtest": {
                // Exactly one arg from the FIXED scenario set (ASCII-folded
                // like /wave category); anything else answers the set-naming usage reply. The
                // seat is never an argument — the door binds the SESSION's seat.
                if (emptyToken || tokens.length != 2) {
                    return new Parsed(Kind.USAGE, null, PODTEST_USAGE);
                }
                String scenario = asciiLower(tokens[1]);
                return PodScenarioDriver.TOKENS.contains(scenario)
                        ? new Parsed(Kind.PODTEST, scenario, null)
                        : new Parsed(Kind.USAGE, null, PODTEST_USAGE);
            }
            case "godmode": {
                // Bare = toggle; one arg from the FIXED {on, off} set (ASCII-folded,
                // the /wave skip|pause precedent); anything else = the constant usage reply.
                // The seat is never an argument — the door binds the SESSION's seat.
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, GODMODE_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.GODMODE, "toggle", null);
                }
                String mode = asciiLower(tokens[1]);
                return mode.equals("on") || mode.equals("off")
                        ? new Parsed(Kind.GODMODE, mode, null)
                        : new Parsed(Kind.USAGE, null, GODMODE_USAGE);
            }
            case "teleport": {
                // Bare = TELEPORT with a null pose (the door decides: admin ⇒ usage,
                // non-admin ⇒ admin-only); exactly one `@x,y` token = the /spawnfoe pose law
                // (parseSpawnPoseOrNull: anchored digits-only regex, ≤ 24 chars, never throws);
                // anything else = the constant usage reply (a malformed pose is never echoed).
                if (emptyToken || tokens.length > 2) {
                    return new Parsed(Kind.USAGE, null, TELEPORT_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.TELEPORT, null, null, 0, null);
                }
                return parseSpawnPoseOrNull(tokens[1]) != null
                        ? new Parsed(Kind.TELEPORT, null, null, 0, tokens[1])
                        : new Parsed(Kind.USAGE, null, TELEPORT_USAGE);
            }
            case "auth":
                // Exactly one arg (the presented password). The arg is a
                // SECRET-shaped token: any malformed shape answers the constant usage reply
                // (never an echo), and the audit line redacts the whole text (auditText).
                if (emptyToken || tokens.length != 2) {
                    return new Parsed(Kind.USAGE, null, AUTH_USAGE);
                }
                return new Parsed(Kind.AUTH, tokens[1], null);
            case "help":
                // The briefing promises "/help" — no arg
                // form only; the reply is door-assembled (helpLines) so it names ONLY the
                // verbs this serve armed instead of advertising "unavailable" ones.
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, "usage: /help (no argument)");
                }
                return new Parsed(Kind.HELP, null, null);
            case "customs":
                // W15 v0 step 3(f): no-arg listing of the caller's own customs (door-answered).
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, "usage: /customs (no argument)");
                }
                return new Parsed(Kind.CUSTOMS_LIST, null, null);
            case "share": {
                // Peer-build sharing v2: bare = own listing; "<id>" = share; "<id> silent"
                // = share without the broadcast line; "auto on|off [silent]" = the fielding flag.
                // The keyword set is FIXED (silent / auto / on / off, ASCII-folded); the id is a
                // verbatim token the door resolves against the ledger map (never a path/exec input).
                if (emptyToken || tokens.length > 4) {
                    return new Parsed(Kind.USAGE, null, SHARE_USAGE);
                }
                if (tokens.length == 1) {
                    return new Parsed(Kind.SHARE_LIST, null, null);
                }
                String first = asciiLower(tokens[1]);
                if (first.equals("auto")) {
                    if (tokens.length < 3) {
                        return new Parsed(Kind.USAGE, null, SHARE_USAGE);
                    }
                    String mode = asciiLower(tokens[2]);
                    if (!mode.equals("on") && !mode.equals("off")) {
                        return new Parsed(Kind.USAGE, null, SHARE_USAGE);
                    }
                    if (tokens.length == 3) {
                        return new Parsed(Kind.SHARE_AUTO, mode, null);
                    }
                    return asciiLower(tokens[3]).equals(SHARE_SILENT_KEYWORD)
                            ? new Parsed(Kind.SHARE_AUTO_SILENT, mode, null)
                            : new Parsed(Kind.USAGE, null, SHARE_USAGE);
                }
                if (tokens.length == 2) {
                    return new Parsed(Kind.SHARE_SET, tokens[1], null);
                }
                if (tokens.length == 3 && asciiLower(tokens[2]).equals(SHARE_SILENT_KEYWORD)) {
                    return new Parsed(Kind.SHARE_SET_SILENT, tokens[1], null);
                }
                return new Parsed(Kind.USAGE, null, SHARE_USAGE);
            }
            case "unshare":
                if (emptyToken || tokens.length != 2) {
                    return new Parsed(Kind.USAGE, null, UNSHARE_USAGE);
                }
                return new Parsed(Kind.UNSHARE_SET, tokens[1], null);
            case "builds":
                // no-arg listing of every SHARED build (door-answered; a pick saves locally).
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, BUILDS_USAGE);
                }
                return new Parsed(Kind.BUILDS_LIST, null, null);
            case "shiplist":
                // The host's ship list, no-argument form only.
                if (tokens.length > 1) {
                    return new Parsed(Kind.USAGE, null, SHIPLIST_USAGE);
                }
                return new Parsed(Kind.HOST_SHIP_LIST, null, null);
            case "shipkeep":
            case "shipremove":
            case "shiprestore":
                return hostShipVerb(name, tokens, emptyToken);
            default:
                // Autocorrect ("if someone misspells a slash command the
                // game would try to autocorrect it"): the UNIQUE nearest table verb runs in the
                // typed verb's place (re-parsed ONCE — a table verb never lands here again) with
                // a fixed-shape note; a SUGGEST_ONLY verb is only named. Replies stay constants
                // + the one ≤24-char bounded echo of the name's leading ASCII-alnum run
                // (verbEcho).
                String near = nearestVerbOrNull(name);
                if (near == null) {
                    return new Parsed(Kind.UNKNOWN, null, "unknown command /"
                            + verbEcho(name) + " (try /help)");
                }
                if (SUGGEST_ONLY.contains(near)) {
                    return new Parsed(Kind.UNKNOWN, null, "unknown command /"
                            + verbEcho(name) + " - did you mean /" + near
                            + "? (try /help)");
                }
                Parsed corrected = parse("/" + near + text.substring(tokens[0].length()));
                return corrected.withNote("corrected /" + verbEcho(name) + " -> /" + near);
        }
    }

    /**
     * Verbs autocorrect only SUGGESTS — never runs a corrected line for: the
     * password verb; every verb that acts on ANOTHER seat ({@code kick}, {@code ban} /
     * {@code unban}, {@code switchteams}); the self-destructive, match-state and debug-state
     * verbs ({@code suicide}, {@code godmode}, {@code teleport}, {@code wave}, {@code endless},
     * {@code podtest}, {@code knob}, {@code customs}, {@code despawn}, {@code terrain},
     * {@code bans}, {@code banned}); and the client-local {@code /settings} menu the server cannot
     * open. Everything else in {@link Correctable#VERBS} auto-runs — including the spawn and
     * next-ship admin verbs ({@code spawnfoe}, {@code spawnfriend}, {@code debugships}), which act
     * for the CALLER behind their admin gate (the {@code /spwnfoe} contract row pins it).
     * Reworded to the policy in force (was "destructive/
     * admin state verbs", which over-claimed: the spawn verbs always auto-ran).
     */
    static final Set<String> SUGGEST_ONLY = Set.of("auth", "kick", "suicide", "godmode",
            "teleport", "wave", "endless", "podtest", "knob", "customs", "settings",
            "despawn", // Removes a hull — named, never run off a typo
            //
            "terrain", // re-stats every live ship — named, never run off a typo
            "ban", "unban", "bans", // P27 — never off a typo
            "switchteams", // Moves ANOTHER seat — named, never run
            "endmatch", // Ends the match for every seat — named, never run off a typo
            "team", // A team change off a typo would surprise — named, never run
            // the host ship verbs change the list for every player — named, never run off a typo
            "shiplist", "shipkeep", "shipremove", "shiprestore",
            "banned"); // A retime, never run off a typo (surprise 9)

    /** {@link #KNOWN_VERBS} + the client-local {@code settings} (suggest-only), sorted so the
     *  tie rule is order-independent. A lazy holder: {@code KNOWN_VERBS} is declared BELOW this
     *  point and class-init runs in textual order (the first build tripped exactly that). */
    private static final class Correctable {
        static final List<String> VERBS = build();

        private static List<String> build() {
            java.util.TreeSet<String> all = new java.util.TreeSet<>(KNOWN_VERBS);
            all.add("settings");
            return List.copyOf(all);
        }
    }

    /**
     * The unique nearest verb — optimal-string-alignment distance (insert /
     * delete / substitute / adjacent transposition) ≤ 1 for a typed name of ≤ 4 chars, ≤ 2
     * otherwise; a TIE at the best distance corrects nothing (null). Bounded CPU: the folded
     * name is ≤ {@value #UNKNOWN_NAME_ECHO_CHARS} chars against a fixed table; an empty or
     * over-long name is never matched.
     */
    public static String nearestVerbOrNull(String name) {
        if (name.isEmpty() || name.length() > UNKNOWN_NAME_ECHO_CHARS) {
            return null;
        }
        int limit = name.length() <= 4 ? 1 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (String verb : Correctable.VERBS) {
            int d = osaDistance(name, verb);
            if (d < bestDistance) {
                bestDistance = d;
                best = verb;
                tie = false;
            } else if (d == bestDistance) {
                tie = true;
            }
        }
        return best != null && bestDistance <= limit && !tie ? best : null;
    }

    /** Optimal string alignment (restricted Damerau–Levenshtein) distance, O(|a|·|b|). */
    public static int osaDistance(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    /**
     * The {@code chat_command} audit-line text — {@code /auth} lines redact to
     * a fixed marker because the tail token is a password (a MALFORMED attempt classifies as
     * {@link Kind#USAGE}, so redaction keys on the VERB TOKEN, never the parsed kind — a
     * mistyped {@code /auth goose blade} must not log its fragments either; a known leak
     * class). Every UNKNOWN verb's tail redacts too — a
     * mistyped {@code /aut <pw>} / {@code /auths <pw>} classifies UNKNOWN and the old
     * pass-through logged the password verbatim. The verb is read as the maximal ASCII-alnum
     * run after {@code /} (never the space-split token): a non-space separator (U+00A0) glues
     * the secret INTO the split token, so {@code /auth pw} would otherwise log whole.
     * KNOWN non-auth verbs keep the verbatim audit line (their args are allowlist keys/names,
     * and the line is the abuse-forensics record) but only under the grammar's own {@code ' '}
     * separator. An UNKNOWN verb's NAME is free player text too (≤ 24 alnum
     * chars) and a secret glued to a mistyped verb ({@code /atuhMyPw}, no separator at all) is
     * indistinguishable from one, so the row carries {@link #AUDIT_UNKNOWN_VERB} in its place —
     * no name, no length (the length of verb + secret is a tell). The sender still gets the
     * echo in the private reply; {@link #auditReply} keeps it out of the row's {@code reply=}.
     */
    public static String auditText(String text) {
        if (!text.startsWith("/")) {
            return text;
        }
        int end = 1;
        while (end < text.length()) {
            char c = text.charAt(end);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!alnum) {
                break;
            }
            end++;
        }
        String name = asciiLower(text.substring(1, end));
        if ("auth".equals(name)) {
            return "/auth <redacted>";
        }
        boolean known = KNOWN_VERBS.contains(name);
        if (end == text.length()) {
            return known || name.isEmpty() ? text : AUDIT_UNKNOWN_VERB; // a bare table verb or "/" has no tail to leak
        }
        if (text.charAt(end) == ' ' && known) {
            return text;
        }
        return (known ? "/" + name : AUDIT_UNKNOWN_VERB) + " <redacted>";
    }

    /** The {@code chat_command} row's stand-in for an unknown verb's name ({@link #auditText}). */
    public static final String AUDIT_UNKNOWN_VERB = "/<unknown>";

    /**
     * The {@code chat_command} row's {@code reply=} text: the first reply line,
     * EXCEPT the two replies that echo the typed verb ({@link #verbEcho}) — a {@link Kind#UNKNOWN}
     * reply and an autocorrect note. The sender receives both verbatim; the row carries a constant,
     * for {@link #auditText}'s reason (a verb name is player text and may hold a glued secret).
     */
    public static String auditReply(Parsed parsed, List<String> replyLines) {
        if (parsed.kind() == Kind.UNKNOWN) {
            return "<unknown-command>";
        }
        if (parsed.note() != null) {
            return "<corrected>";
        }
        return replyLines.isEmpty() ? "<listing-or-receipt>" : replyLines.get(0);
    }

    /**
     * The {@link #parse} switch's exact case list, for {@link #auditText}'s known-verb test —
     * keep in LOCKSTEP with the switch. Drift is fail-closed: a verb added there but not here
     * gets its audit-line args over-redacted (never a leak), and the contract rows pin every
     * member's verbatim audit behavior.
     */
    private static final Set<String> KNOWN_VERBS = Set.of(
            "suicide", "ship", "ships", "module", "transfer", "mute", "kick", "wave",
            "debugships", "spawnfoe", "spawnfriend", "auth", "help", "podtest", "godmode",
            "teleport", "customs", "switchteams",
            "share", "unshare", "builds", "endless",
            "knob", // Was missing since the knob verb landed (fail-closed over-redaction only)
            "despawn", //
            "terrain", //
            "ban", "unban", "bans", //
            "banned", //
            "team", //
            "shiplist", "shipkeep", "shipremove", "shiprestore", //
            "endmatch"); //

    /**
     * The {@code /wave <type> [reset|stay]} tail shared by the shorthand and
     * the {@code category} alias — BOTH words from FIXED sets (the category set, then the
     * {reset, stay} pair), so no player text ever reaches the deposit: {@code reset} rides as
     * {@code arg = "<type> reset"} (the serve drain splits it), {@code stay} = the bare form.
     */
    /**
     * The three host ship verbs that name a ship: exactly {@code /<verb> <id>}, the id one token under the
     * operator file's id law ({@link OperatorCommandFile#joinerDefaultTokenLawful}: 1..64 chars of
     * {@code [A-Za-z0-9._:-]}), carried AS TYPED; any other shape = the verb's constant usage reply.
     *
     */
    private static Parsed hostShipVerb(String name, String[] tokens, boolean emptyToken) {
        Kind kind;
        String usage;
        switch (name) {
            case "shipkeep" -> {
                kind = Kind.HOST_SHIP_KEEP;
                usage = SHIPKEEP_USAGE;
            }
            case "shipremove" -> {
                kind = Kind.HOST_SHIP_REMOVE;
                usage = SHIPREMOVE_USAGE;
            }
            default -> {
                kind = Kind.HOST_SHIP_RESTORE;
                usage = SHIPRESTORE_USAGE;
            }
        }
        if (kind == Kind.HOST_SHIP_KEEP && !emptyToken && tokens.length == 4) {
            return shipKeepReplaces(tokens);
        }
        if (emptyToken || tokens.length != 2 || !OperatorCommandFile.joinerDefaultTokenLawful(tokens[1])) {
            return new Parsed(Kind.USAGE, null, usage);
        }
        return new Parsed(kind, tokens[1], null);
    }

    /**
     * {@code /shipkeep <id> replaces <ship id>} - the host promotes a build
     * loaded from an existing ship, so the build takes that ship's place on {@code /ship} instead of listing beside it.
     * WHY one arg: the serve's ship drain already carries ONE text per verb, so the pair rides as
     * {@code "<id> replaces <ship id>"} (both ids under the operator file's id law, case kept, the third token
     * literally {@value #SHIPKEEP_REPLACES_WORD}) and the serve splits it; the receipt head stays
     * {@code shipkeep <id>: } because the editor keys its pending slot on that exact prefix. Any other 4-token
     * shape = the usage line. The serve validates the replaced ship (it must be on the server), never this parse.
     */
    private static Parsed shipKeepReplaces(String[] tokens) {
        if (!SHIPKEEP_REPLACES_WORD.equals(tokens[2]) || !OperatorCommandFile.joinerDefaultTokenLawful(tokens[1])
                || !OperatorCommandFile.joinerDefaultTokenLawful(tokens[3])) {
            return new Parsed(Kind.USAGE, null, SHIPKEEP_USAGE);
        }
        return new Parsed(Kind.HOST_SHIP_KEEP, tokens[1] + " " + SHIPKEEP_REPLACES_WORD + " " + tokens[3], null);
    }

    private static Parsed waveCategory(String category, String modeOrNull) {
        if (!WaveSchedule.WAVE_CATEGORIES.contains(category)) {
            return new Parsed(Kind.USAGE, null, WAVE_CATEGORY_USAGE);
        }
        if (modeOrNull == null || modeOrNull.equals("stay")) {
            return new Parsed(Kind.WAVE_CATEGORY, category, null);
        }
        return modeOrNull.equals("reset")
                ? new Parsed(Kind.WAVE_CATEGORY, category + " reset", null)
                : new Parsed(Kind.USAGE, null, WAVE_CATEGORY_USAGE);
    }

    /**
     * The shared {@code /verb [player|#id]} arg grammar — W3 L4 S2's /transfer form, reused
     * VERBATIM by /mute (W12 L2) and /kick (the three copies folded into this one
     * helper at that landing, the contract rows pin every branch): no arg = {@code list}; a
     * {@code #}-prefixed arg is the numeric wireId form — bounded-digit fail-closed decode HERE
     * in the pure parser (security law: ≤19 digits, [0-9] only, Long-bounded, >0; ANY reject =
     * the constant usage reply, no junk echo) = {@code wire} carrying the bare digits; any other
     * single arg = {@code set} (verbatim case — dispatch resolves it against a live census, never
     * a path/exec input).
     */
    private static Parsed playerArg(String[] tokens, boolean emptyToken, String usage,
            Kind list, Kind set, Kind wire) {
        if (emptyToken || tokens.length > 2) {
            return new Parsed(Kind.USAGE, null, usage);
        }
        if (tokens.length == 1) {
            return new Parsed(list, null, null);
        }
        if (tokens[1].startsWith("#")) {
            String digits = wireDigitsOrNull(tokens[1]);
            return digits == null ? new Parsed(Kind.USAGE, null, usage)
                    : new Parsed(wire, digits, null);
        }
        return new Parsed(set, tokens[1], null);
    }

    /**
     * The {@code #<wireId>} token law, shared by {@link #playerArg} and {@code /switchteams}
     * (one refactor folded the copy out): the bare digits when {@code token} is {@code #} + 1..19
     * chars of {@code [0-9]} that parse to a positive {@code long}; null on ANY reject (the
     * caller answers its constant usage reply — never an echo of the junk).
     */
    /** 1..4 ASCII digits with a value &gt; 0, else null
     *  (the /unban index and the /bans page). */
    private static String smallDigitsOrNull(String digits) {
        if (digits.isEmpty() || digits.length() > 4) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.parseInt(digits) > 0 ? digits : null;
    }

    private static String wireDigitsOrNull(String token) {
        String digits = token.substring(1);
        if (digits.isEmpty() || digits.length() > 19) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(digits) <= 0 ? null : digits;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * The {@code /help} reply lines in READING order — a header,
     * then ONE command per line in one shape {@code /cmd <arg> - what it does} (the old
     * packed three-verbs-in-a-sentence line + {@code [a|b]} bracket grammar read
     * as "a jumble"), each a constant inside the {@link ChatFrameCodec#TEXT_MAX_CHARS} law by
     * construction (the contract rows validate every line). The always-on verbs come first;
     * each feature-gated verb appears ONLY when the door says it is armed on this serve (the
     * same three gates the dispatch/door "unavailable" replies key on: fortress
     * {@code moduleVerbsArmed}, the published transfer census, the voice endpoint) — a joiner is
     * never told to try a verb that would answer "unavailable". Pure + static: the door threads
     * the flags and sends the list BOTTOM-UP (the vanilla combat message panel stacks newest on
     * TOP — flight #10b screenshot: a 3-line burst rendered exactly reversed).
     */
    public static List<String> helpLines(boolean moduleArmed, boolean transferArmed,
            boolean voiceArmed) {
        return helpLines(moduleArmed, transferArmed, voiceArmed, false);
    }

    /**
     * The same list, plus the admin verbs when {@code admin} (the SESSION's
     * HELLO-derived bit — a non-admin is never told about /kick or /wave, exactly the
     * "never advertise a verb that answers unavailable" law above; here the answer would be
     * {@link #ADMIN_ONLY}). Each admin line keeps the one-shape law (no {@code |}/{@code [}).
     */
    public static List<String> helpLines(boolean moduleArmed, boolean transferArmed,
            boolean voiceArmed, boolean admin) {
        return helpLines(moduleArmed, transferArmed, voiceArmed, admin, false);
    }

    /** W15 v0 step 3(f): the same list + {@code /customs} when the custom-variant lane is armed
     * (the same never-advertise-unavailable law; the line sits right after {@code /ship}). */
    public static List<String> helpLines(boolean moduleArmed, boolean transferArmed,
            boolean voiceArmed, boolean admin, boolean customsArmed) {
        return helpLines(moduleArmed, transferArmed, voiceArmed, admin, customsArmed, false);
    }

    /**
     * The same list + {@code /team} when the serve plays the PvP shape (the door's team table counts
     * teams), right after {@code /help}: everywhere else the verb answers "Teams are fixed", so the
     * never-advertise-unavailable law keeps the line out. One-shape law: no bracket, no bar.
     *
     */
    public static List<String> helpLines(boolean moduleArmed, boolean transferArmed,
            boolean voiceArmed, boolean admin, boolean customsArmed, boolean pvpArmed) {
        List<String> lines = new ArrayList<String>(10);
        lines.add("commands:");
        // The ships alias + the text-filter listing ride the one line —
        // the one-shape help law allows no second slash, so the alias is named bare.
        lines.add("/ship <variant> - pick your next respawn ship (no arg = list; text = filter; ships is an alias)");
        if (customsArmed) {
            lines.add("/customs - list your accepted custom variants (pick one via the ship verb)");
            // Peer-build sharing v2: the three share verbs ride the same customs gate; the
            // one-shape law holds (no brackets, no bar, no second slash).
            lines.add("/share <id> - share one of your customs with everyone (no arg = list yours)");
            lines.add("/unshare <id> - stop sharing one of your customs");
            lines.add("/builds - list the builds other players shared (pick one to save it)");
        }
        lines.add("/suicide - self-destruct");
        lines.add("/help - this list");
        if (pvpArmed) {
            lines.add("/team north or south - switch team at your next respawn");
        }
        if (moduleArmed) {
            lines.add("/module <slot> - claim a fortress module seat (no arg = list, off = leave)");
        }
        if (transferArmed) {
            lines.add("/transfer <player> - take over another ship (no arg = list; #id works)");
        }
        if (voiceArmed) {
            lines.add("/mute <player> - toggle a voice mute (no arg = list; #id works)");
        }
        if (admin) {
            lines.add("/kick <player> - kick a player (admin; no arg = list; #id works)");
            lines.add("/wave skip - kill the current wave (admin)");
            lines.add("/wave pause - pause or resume the wave clock (admin)");
            lines.add("/wave <type> - next wave becomes that faction (admin; no arg = menu; add reset to restart at the opener)");
            // The wave-number set + the endless dial (one-shape law: no
            // brackets, no bar, no second slash; the usage replies carry the exact bounds).
            lines.add("/wave set <n> - that wave spawns next (admin; 1 to 9)");
            lines.add("/endless - show the endless-waves dial (admin; on or off sets it)");
            // The wave loadout-variety dial (vanilla fleet inflation over wave rows)
            lines.add("/wave inflate - show the wave loadout-variety dial (admin; on or off sets it)");
            // The round-wrap staging verb
            lines.add("/wave wrap - end this round now, the next round starts (admin)");
            // The whole-match end (the signed-in seat's F10 sends it)
            lines.add("/endmatch - end the match now for every player (admin)");
            // The wire-delivered client knobs (the foe-cloak lead/wait A/B today)
            lines.add("/knob - list the client knobs (admin; cloaklead lead or wait sets the foe-cloak test)");
            // The battle terrain pair in one verb (the two knobs' words)
            // the level names WireKnob.TERRAIN_LEVEL's five
            // choices (it parses ONLY these, never "0 to 100"); the line is 118 of the 120 chars.
            lines.add("/terrain <word> <level> - show or set the battle terrain (admin; none, corona, pulsar or abyss + 0, 25, 50, 75 or 100)");
            lines.add("/debugships <variant> - pick any variant to respawn as (admin; no arg = list; text = filter)");
            // The optional count + @x,y pose are named without brackets or a
            // second slash (the one-shape admin help-line law); the usage reply carries them.
            // The trailing grammar names the maxed-captain keyword (<= 120 chars).
            lines.add("/spawnfoe <variant> - field an AI foe (admin; no arg = list;"
                    + " add a count, then officer, then @x,y to place it)");
            // The player-side twin (same shape; the one-shape admin help-line law).
            lines.add("/spawnfriend <variant> - field an AI ally on your side (admin; no arg = list;"
                    + " add a count, then officer, then @x,y)");
            // The spawn verbs' undo — the R-targeted hull vanishes (one-shape law).
            lines.add("/despawn - remove the ship you have R-targeted (admin; never a player; #id works)");
            lines.add("/podtest <scenario> - stage a pod test scenario for your seat (admin; "
                    + String.join(" ", PodScenarioDriver.TOKENS) + ")");
            // The two debug verbs (one-shape law: no brackets, no second slash).
            lines.add("/godmode - toggle your ship taking no damage (admin; on or off sets it)");
            lines.add("/teleport - arm teleport, then press T with the cursor over the map"
                    + " (admin; @x,y teleports there directly)");
            // The team-switch menu (one-shape law: no brackets, no second slash).
            lines.add("/switchteams <player> - move a player to the other team"
                    + " (admin; no arg = menu; pick instant or at next respawn)");
            // The P27 verbs (one-shape law: no brackets,
            // no bar, no second slash — so the id sources are named in words).
            // The design's
            // stated limit, never "they cannot rejoin" — a cid ban misses a fresh id (observer dials
            // mint one). "no arg = menu" replaces the
            // kick-list pointer (the menu IS that list). The
            // term ladder (97 chars); the fresh-id caveat moved into the ban RECEIPT (target-accurate).
            lines.add("/ban #id 1d - ban a player for " + BanTerm.HELP_TOKENS
                    + " (admin; no arg = menu, arrows pick the time)");
            lines.add("/unban #n - lift an admin ban (admin; no arg = menu, newest first;"
                    + " n from it or the bans list)");
            lines.add("/banned #n 1w - reset an admin ban to " + BanTerm.HELP_TOKENS
                    + " from now (admin; no arg = menu with time left)");
            lines.add("/bans - list admin bans (admin; a page number selects a page)");
        }
        return lines;
    }

    /**
     * W7 B1b: the {@code /ship} no-arg listing lines, one per pickable id (canonical ids,
     * caller-ordered), marked for THIS seat: {@code (piloting)} = the seat's last FIELDED hull
     * (deploy-recorded), {@code (next respawn)} = the seat's stored override when it DIFFERS
     * from the fielded hull (the rule: annotate when they differ — an override equal to the
     * fielded hull adds nothing). SS382 L3 retired the CHTD feed dump (the old
     * {@code formatListing} 16-line cap died with it): dispatch now emits the listing as LSTD
     * frames via {@link ListingEmitter#shipEntries}, whose mark precedence mirrors THIS method
     * row-for-row — this prose form stays as the readable spec + the emitter contract rows'
     * parity oracle.
     */
    public static List<String> shipListingLines(List<String> ids, String fieldedOrNull,
            String overrideOrNull) {
        Objects.requireNonNull(ids, "ids");
        List<String> lines = new ArrayList<String>(ids.size());
        for (String id : ids) {
            if (id.equals(fieldedOrNull)) {
                lines.add(id + " (piloting)");
            } else if (id.equals(overrideOrNull)) {
                lines.add(id + " (next respawn)");
            } else {
                lines.add(id);
            }
        }
        return lines;
    }

    /** ASCII-only case fold ({@code 'A'..'Z'} += 32) — matching must never ride a Locale.
     * Package-shared: {@link PickableVariants} keys its allowlist on the SAME fold. */
    static String asciiLower(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 'A' && c <= 'Z') {
                chars[i] = (char) (c + 32);
            }
        }
        return new String(chars);
    }

    private static String bound(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    /**
     * The ONE echo of a typed verb in an UNKNOWN /
     * suggest reply and the autocorrect note = the maximal LEADING ASCII-alnum run of the folded
     * {@code name}, bounded — the {@link #auditText} verb rule applied to the reply. WHY:
     * {@link #parse} splits on ASCII space only, so a printable non-ASCII space (U+00A0, U+3000 —
     * both wire-legal) glues an {@code /auth} password INTO the name; the whole-token echo carried
     * it into the private CHTD reply AND the {@code chat_command} row's {@code reply=}
     * ({@code ChatCommandDispatch}'s USAGE/UNKNOWN arm, {@code PidsChatVerbs}' audit print).
     * {@code name} is already ASCII-folded, so {@code [a-z0-9]} is the whole alphabet here.
     */
    private static String verbEcho(String name) {
        int end = 0;
        while (end < name.length()) {
            char c = name.charAt(end);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                break;
            }
            end++;
        }
        return bound(name.substring(0, end), UNKNOWN_NAME_ECHO_CHARS);
    }
}
