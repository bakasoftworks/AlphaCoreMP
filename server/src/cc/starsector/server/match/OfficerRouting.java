package cc.starsector.server.match;

import java.util.ArrayList;
import java.util.List;

/**
 * The officer curated-row ROUTING law: the
 * fielded→seat-descriptor fallback order is a declared row-for-row parity obligation between
 * the ends (server {@code OfficerAttach.maybeAttachSeat} ↔ client
 * {@code OfficerLoadout.captainOrNull} + the all-skills portrait fallback) — stats are
 * off-wire, so divergent routing is a silent stats desync. This class is the ONE pure
 * decision both ends iterate (game-free; client build.ps1 allowlist), pinned by
 * {@code OfficerRoutingContractRows}.
 *
 * <p>The owner law it encodes: "the player's skills should stay attached wherever the
 * seat goes" — try the FIELDED variant's curated row first (a {@code /ship} pick with its own
 * row keeps the variant-tuned officer), and only for a SEAT whose fielded variant differs
 * from its descriptor variant, fall back to the seat's DESCRIPTOR row. Non-seat hulls stay
 * strict-variant. A hit at index &gt; 0 is the TRANSFER lane (each end logs it in its own
 * sink).
 */
public final class OfficerRouting {

    private OfficerRouting() {
    }

    /**
     * The ordered curated-row lookup candidates. Index 0 is ALWAYS the fielded variant id
     * (even null — the miss stays at the lookup site, preserving each end's null-lenient
     * table read); the seat-descriptor id is appended only when {@code seat} and it is
     * non-null and differs from the fielded id.
     */
    public static List<String> curatedLookupOrder(String fieldedVariantId,
            String seatDescriptorVariantIdOrNull, boolean seat) {
        List<String> order = new ArrayList<>(2);
        order.add(fieldedVariantId);
        if (seat && seatDescriptorVariantIdOrNull != null
                && !seatDescriptorVariantIdOrNull.equals(fieldedVariantId)) {
            order.add(seatDescriptorVariantIdOrNull);
        }
        return order;
    }
}
