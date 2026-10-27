package cc.starsector.client.net;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * W15 v1: the custom-variant reject token → a one-line FIX HINT the combat
 * receipt lane toasts as a second {@code customs} chat line ({@code -> ...}) and the mission-build
 * peek logs. Client-side, {@code java.util} only (B-deny safe); an unknown token (a newer server's
 * vocabulary) gets NO hint and the raw token stands — fail-open on display. Every hint is
 * ≤ {@value #MAX_HINT_CHARS} printable-ASCII chars (the 120-char chat law minus the prefix).
 *
 * <p>Meanings = the server's emitters: {@code VariantPolicy} (tier denials + shape), the engine
 * registrar (slot fit / OP / s-mod cap / inapplicable / fault), {@code SubmittedVariantLedger}
 * (caps + banned), {@code PilotIntentDatagramServer} (rate + unavailable), {@code CustomVariantLane}
 * (disabled). The game-free verifier ({@code CcClientVsubVerifier}) asserts every
 * {@code DoorRejectReasons.custom()} token has a hint and every hint key is a custom token —
 * the fold of a new server token fails the roll instead of shipping a hint-less toast.
 */
public final class VariantRejectHints {
    public static final int MAX_HINT_CHARS = 115;
    private static final Map<String, String> HINTS = new HashMap<>();
    static {
        HINTS.put("custom_unavailable",
                "the server has no custom-variant lane installed; pick a ship from the server list");
        HINTS.put("custom_disabled",
                "custom submissions are OFF on this server, or the host rejected your seat's customs; ask the host");
        HINTS.put("custom_rate_limited",
                "too many submissions (1 pending per seat, 8 per minute); wait a minute and resubmit;"
                        + " reconnecting won't refill it"); // the bucket rides a takeover; <= MAX_HINT_CHARS
        HINTS.put("custom_seat_cap",
                "your seat already holds its cap of accepted customs (server custom.perSeat)");
        HINTS.put("custom_global_cap",
                "the server holds its global cap of accepted customs (64); try again after a reset");
        HINTS.put("custom_hull_denied",
                "the hull is unknown to the server or outside its allowed tier; change hullId");
        HINTS.put("custom_weapon_denied",
                "a mounted weapon is unknown or not allowed by the server tier; swap or remove it");
        HINTS.put("custom_wing_denied",
                "a fighter wing is unknown or not allowed by the server tier; remove it");
        HINTS.put("custom_hullmod_denied",
                "a hullmod is unknown or not allowed by the server tier; remove it");
        HINTS.put("custom_shape_invalid",
                "file shape law: goalVariant false, no tags/modules, no repeated mods, weapons must fit their slot");
        HINTS.put("custom_hullmod_inapplicable",
                "a hullmod cannot be applied to this hull (engine rule); remove it");
        HINTS.put("custom_smod_cap",
                "more s-mods than the server cap (custom.sModCap); remove one");
        HINTS.put("custom_op_exceeded",
                "ordnance points over the hull's budget; remove a weapon, vents, capacitors or a hullmod");
        HINTS.put("custom_engine_rejected",
                "the game engine refused to build it (a reserved id prefix or content the engine cannot load)");
        HINTS.put("custom_too_large",
                "the encoded variant is over the wire budget (1144 bytes); trim weapon groups or mods");
        HINTS.put("custom_engine_fault",
                "the server's engine faulted while building it - not your file's fault; tell the host");
        HINTS.put("custom_banned",
                "the host banned this exact variant (custom-ban); it will not be accepted again");
    }

    private VariantRejectHints() {
    }

    /** The fix hint for a reject token, or {@code null} for an unknown/null token (raw token stands). */
    public static String hintOrNull(String reasonToken) {
        return reasonToken == null ? null : HINTS.get(reasonToken);
    }

    /** The tokens that carry a hint (the verifier's parity row vs the bundled vocabulary). */
    public static Set<String> tokens() {
        return Collections.unmodifiableSet(HINTS.keySet());
    }
}
