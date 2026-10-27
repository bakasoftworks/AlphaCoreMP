package cc.starsector.server.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The reject-reason REGISTRY — the ONE home for every {@code WELCOME_REJECTED} reason token the
 * server mints at the join / probe door.
 * Before this class the 17-token
 * vocabulary lived as free literals at ~30 mint / compare sites across five files
 * ({@code kit_outdated} alone was minted at six {@link HandshakeCodec} version-tier branches) and
 * as three client hand-lists; a token added on ONE side only was invisible until a joiner hit it.
 *
 * <p>LAWS. (1) Canonical spelling = the CURRENT wire literal — the client already switches on these
 * exact strings ({@code PredictFailBannerCombatPlugin.describeRefusal}, {@code DoorProbe.rejectMessage});
 * never rename. (2) Every token obeys {@link #isWellFormed}: lowercase ASCII letters, digits and
 * {@code _}, 1..32 chars — the same single-line printable law the WELCOME reject field already
 * carries ({@link WireTextLaw}) narrowed to a machine token. (3) {@link #joinDoor()} is the set a
 * JOIN hello can be answered with, {@link #probe()} the pre-claim subset a PROBE hello can be
 * answered with (the two {@code probe_*} tokens are probe-only); the contract suite pins both to the
 * enumeration and the lockstep smoke sweeps them. (4) Adjacent kinds stay OUT of the parity
 * set on purpose: {@code JOINER_DEFAULT_UNPICKABLE} / {@code VARIANT_UNPICKABLE} (log + chat sentence,
 * never a wire token), {@code operator_kick} / {@code not_live} (session END, not a door reject).
 * The snapshot re-admission gate ({@code SnapshotClientAdmissionGate}) reuses the same four
 * fingerprint / seat-token constants so they cannot re-drift, but it is not a door.
 *
 * <p>W15 v0: the {@code VACK} verdict reason tokens
 * are the {@link #custom()} group — one home, never a second vocabulary. They are NOT door tokens
 * (a custom-variant refusal never ends a session; the submitter keeps its seat and the server
 * pick): {@link #joinDoor()} / {@link #probe()} exclude them and {@link #all()} includes them.
 * The {@code VACK} wire carries a token as its 1-based INDEX into {@link #custom()} (u8), so the
 * list ORDER is wire-visible — append only, never reorder ({@code VariantReceiptFrameCodec}).
 * Pipeline mapping: unavailable/disabled/rate/caps = pre-pipeline
 * gates; hull 1–2; weapon 5; wing 6; hullmod 7; shape 4/5(slot)/6(count)/7(dup)/12/13;
 * inapplicable 9–10; smod_cap 11; op 14; engine 3/8/15 (ctor or register throw); too_large 15.
 *
 * <p>Bundled WHOLE into the mod jar like {@link HandshakeCodec} ({@code client-mod/build.ps1} fold
 * list + jar-content gate; build rider {@code 1346-9-3} pays that half at the next roll): only
 * {@code java.lang} + {@code java.util} names, so the classloader-B deny predicate never fires.
 */
public final class DoorRejectReasons {

    private DoorRejectReasons() {}

    // ---- kit ----
    public static final String KIT_OUTDATED = "kit_outdated";
    // ---- content fingerprint ----
    public static final String CONTENT_FINGERPRINT_REQUIRED = "content_fingerprint_required";
    public static final String CONTENT_FINGERPRINT_MISMATCH = "content_fingerprint_mismatch";
    /** A session claim whose print differs from the door's ONLY in the {@code match=} term: the host changed the
     *  match, and the player's join window fetches the new one. In {@link #joinDoor()} / {@link #all()}, never
     *  {@link #probe()}: a stale-term PROBE is answered with the document, not refused.
     * */
    public static final String MATCH_STALE = "match_stale";
    // ---- match / session ----
    public static final String MATCH_PASSWORD_REQUIRED = "match_password_required";
    public static final String MATCH_PASSWORD_MISMATCH = "match_password_mismatch";
    public static final String CLIENT_ID_REQUIRED = "client_id_required";
    public static final String SESSION_TABLE_FULL = "session_table_full";
    public static final String MATCH_FULL = "match_full";
    // ---- seat ----
    public static final String SEAT_TOKEN_REQUIRED = "seat_token_required";
    public static final String SEAT_TOKEN_UNKNOWN = "seat_token_unknown";
    public static final String SEAT_UNKNOWN = "seat_unknown";
    public static final String SEAT_ALREADY_OCCUPIED = "seat_already_occupied";
    // ---- observer ----
    public static final String OBSERVER_PASSWORD_MODE_ONLY = "observer_password_mode_only";
    public static final String OBSERVER_CID_IN_USE = "observer_cid_in_use";
    public static final String OBSERVER_FULL = "observer_full";
    // ---- probe (answer a PROBE hello only) ----
    public static final String PROBE_UNSUPPORTED = "probe_unsupported";
    public static final String PROBE_NO_PICKABLES = "probe_no_pickables";
    // ---- ban: the door's ban seam answers EVERY hello kind (join,
    // probe, observer) with this token — terminal on the client (no re-dial), never a credential
    // oracle (the seam runs before the password / seat-token compares) ----
    public static final String BANNED = "banned";
    /** A version-13 F2 whose secret the wrap PROVED but whose
     *  sealed body or lane handle was not lawful — never a credential reason (the secret was right).
     *  In {@link #JOIN_DOOR} / {@link #PROBE} / {@link #ALL} since THE FLIP's stage Y: the open that
     *  earns it precedes any flag read, so a join AND a probe flight can draw it; the kit has a sentence for it. */
    public static final String HANDSHAKE_REFUSED = "handshake_refused";
    /** A JOIN hello whose source address already holds the host's per-address seat allowance
     *  (a moderation setting; the host's own box is exempt). Never terminal on the client: a kit that
     *  predates the token shows the raw token and keeps retrying, so a seat freed later admits it;
     *  the kit's sentence for it rides the next kit roll. */
    public static final String SOURCE_SEAT_CAP = "source_seat_cap";
    /** Vocabulary only: the door never sends it. A JOIN hello that picks a full team while the OTHER team still
     *  has room is seated on the other team and told so in-session (the cross-fill); when neither has room the
     *  door says {@link #MATCH_FULL}. Only a shape that seats players on both sides honours a pick; anywhere
     *  else the pick is ignored, never refused. The token stays in the join-door set.
     *  The cross-fill retirement: */
    public static final String TEAM_FULL = "team_full";
    // ---- custom variant (answer a VSUB submission only; W15 v0, wire = 1-based index) ----
    public static final String CUSTOM_UNAVAILABLE = "custom_unavailable";
    public static final String CUSTOM_DISABLED = "custom_disabled";
    public static final String CUSTOM_RATE_LIMITED = "custom_rate_limited";
    public static final String CUSTOM_SEAT_CAP = "custom_seat_cap";
    public static final String CUSTOM_GLOBAL_CAP = "custom_global_cap";
    public static final String CUSTOM_HULL_DENIED = "custom_hull_denied";
    public static final String CUSTOM_WEAPON_DENIED = "custom_weapon_denied";
    public static final String CUSTOM_WING_DENIED = "custom_wing_denied";
    public static final String CUSTOM_HULLMOD_DENIED = "custom_hullmod_denied";
    public static final String CUSTOM_SHAPE_INVALID = "custom_shape_invalid";
    public static final String CUSTOM_HULLMOD_INAPPLICABLE = "custom_hullmod_inapplicable";
    public static final String CUSTOM_SMOD_CAP = "custom_smod_cap";
    public static final String CUSTOM_OP_EXCEEDED = "custom_op_exceeded";
    public static final String CUSTOM_ENGINE_REJECTED = "custom_engine_rejected";
    public static final String CUSTOM_TOO_LARGE = "custom_too_large";
    /** A SERVER-side fault in the engine pass (reflection bridge
     *  broke, registrar contract violated, an {@code Error}) — NOT the submitter's doing, so NOT in
     *  the hostile set; {@link #CUSTOM_ENGINE_REJECTED} stays for content the engine ctor refuses. */
    public static final String CUSTOM_ENGINE_FAULT = "custom_engine_fault";
    /** The same body re-submitted after {@code custom-ban} — refused per id, never
     *  dup-answered ACCEPT with the banned canonical ({@code SubmittedVariantLedger.offer}). */
    public static final String CUSTOM_BANNED = "custom_banned";

    /** Token grammar bound: 1..32 chars of {@code [a-z0-9_]}. */
    public static final int MAX_TOKEN_LENGTH = 32;

    /** APPEND-ONLY (the index is the VACK wire code): 15 tokens at v0, 17 after a later append (codes 16–17). */
    private static final List<String> CUSTOM = Collections.unmodifiableList(Arrays.asList(
            CUSTOM_UNAVAILABLE, CUSTOM_DISABLED, CUSTOM_RATE_LIMITED, CUSTOM_SEAT_CAP,
            CUSTOM_GLOBAL_CAP, CUSTOM_HULL_DENIED, CUSTOM_WEAPON_DENIED, CUSTOM_WING_DENIED,
            CUSTOM_HULLMOD_DENIED, CUSTOM_SHAPE_INVALID, CUSTOM_HULLMOD_INAPPLICABLE,
            CUSTOM_SMOD_CAP, CUSTOM_OP_EXCEEDED, CUSTOM_ENGINE_REJECTED, CUSTOM_TOO_LARGE,
            CUSTOM_ENGINE_FAULT, CUSTOM_BANNED));

    private static final List<String> JOIN_DOOR = Collections.unmodifiableList(Arrays.asList(
            MATCH_PASSWORD_MISMATCH, MATCH_PASSWORD_REQUIRED, KIT_OUTDATED,
            CONTENT_FINGERPRINT_MISMATCH, CONTENT_FINGERPRINT_REQUIRED, CLIENT_ID_REQUIRED,
            SEAT_TOKEN_REQUIRED, SEAT_TOKEN_UNKNOWN, SEAT_UNKNOWN, SEAT_ALREADY_OCCUPIED,
            MATCH_FULL, SESSION_TABLE_FULL,
            OBSERVER_PASSWORD_MODE_ONLY, OBSERVER_CID_IN_USE, OBSERVER_FULL,
            BANNED, HANDSHAKE_REFUSED, SOURCE_SEAT_CAP, MATCH_STALE, TEAM_FULL));

    private static final List<String> PROBE = Collections.unmodifiableList(Arrays.asList(
            MATCH_PASSWORD_MISMATCH, MATCH_PASSWORD_REQUIRED, KIT_OUTDATED,
            CONTENT_FINGERPRINT_MISMATCH, CONTENT_FINGERPRINT_REQUIRED, CLIENT_ID_REQUIRED,
            PROBE_UNSUPPORTED, PROBE_NO_PICKABLES, BANNED, HANDSHAKE_REFUSED));

    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            KIT_OUTDATED,
            CONTENT_FINGERPRINT_REQUIRED, CONTENT_FINGERPRINT_MISMATCH,
            MATCH_PASSWORD_REQUIRED, MATCH_PASSWORD_MISMATCH, CLIENT_ID_REQUIRED, SESSION_TABLE_FULL, MATCH_FULL,
            SEAT_TOKEN_REQUIRED, SEAT_TOKEN_UNKNOWN, SEAT_UNKNOWN, SEAT_ALREADY_OCCUPIED,
            OBSERVER_PASSWORD_MODE_ONLY, OBSERVER_CID_IN_USE, OBSERVER_FULL,
            PROBE_UNSUPPORTED, PROBE_NO_PICKABLES, BANNED, HANDSHAKE_REFUSED, SOURCE_SEAT_CAP, MATCH_STALE, TEAM_FULL,
            CUSTOM_UNAVAILABLE, CUSTOM_DISABLED, CUSTOM_RATE_LIMITED, CUSTOM_SEAT_CAP,
            CUSTOM_GLOBAL_CAP, CUSTOM_HULL_DENIED, CUSTOM_WEAPON_DENIED, CUSTOM_WING_DENIED,
            CUSTOM_HULLMOD_DENIED, CUSTOM_SHAPE_INVALID, CUSTOM_HULLMOD_INAPPLICABLE,
            CUSTOM_SMOD_CAP, CUSTOM_OP_EXCEEDED, CUSTOM_ENGINE_REJECTED, CUSTOM_TOO_LARGE,
            CUSTOM_ENGINE_FAULT, CUSTOM_BANNED));

    /** Every reason a JOIN hello can be rejected with (20: the original enumeration + {@link #BANNED}
     * + {@link #HANDSHAKE_REFUSED} + {@link #SOURCE_SEAT_CAP}
     * + {@link #MATCH_STALE} + {@link #TEAM_FULL},
     *order-free; {@link #TEAM_FULL} stays listed though the door never sends it). */
    public static List<String> joinDoor() { return JOIN_DOOR; }

    /** Every reason a PROBE hello can be rejected with (10 since THE FLIP; no seat is ever claimed on
     *  this path). */
    public static List<String> probe() { return PROBE; }

    /** Every reason a VSUB submission can be refused with (17; ORDER = the VACK wire code). */
    public static List<String> custom() { return CUSTOM; }

    /**
     * The VACK wire code for a custom token: its 1-based index in {@link #custom()}; 0 for a
     * token that is not a custom reason (the codec refuses to encode that).
     */
    public static int customCode(String token) {
        return CUSTOM.indexOf(token) + 1;
    }

    /** Inverse of {@link #customCode}: null when {@code code} is outside 1..custom().size(). */
    public static String customTokenOrNull(int code) {
        return code >= 1 && code <= CUSTOM.size() ? CUSTOM.get(code - 1) : null;
    }

    /** The whole vocabulary (39 distinct tokens: 22 door + 17 custom). */
    public static List<String> all() { return ALL; }

    /** True iff {@code token} is exactly one of the registry's canonical spellings. */
    public static boolean isKnown(String token) {
        return token != null && ALL.contains(token);
    }

    /** The token grammar: non-empty, at most {@link #MAX_TOKEN_LENGTH}, only {@code [a-z0-9_]}. */
    public static boolean isWellFormed(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
