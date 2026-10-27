package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.combat.threat.ThreatHullmod;

/**
 * No replica hulk mints a LOCAL
 * reclamation wing.
 *
 * <p><b>Why:</b> {@code threat_hullmod} runs UNSUPPRESSED on replicas (its stats are harmless, its
 * strategy plugin is inert against {@code InertShipAI}); but {@code ThreatHullmod.advanceInCombat}
 * watches a hulk for 1 s and, when a same-owner fabricator below 100 % CR exists, adds a
 * {@code ThreatShipReclamationScript} that spawns reclamation swarms and fades the hulk away —
 * a local author of spawns and a vanishing corpse the server never confessed. The one
 * vanilla gate is the {@code ship_being_reclaimed} tag (set by the
 * script itself). Pinning it at the replica's first treatment
 * shuts the path for the body's whole life (the tag has no other consumer in the Threat package);
 * the server's own reclamation still runs headless and its wings arrive as replicas. 0 wire bytes.
 */
final class ThreatHulkReclaimLaw {

    static final String THREAT_HULLMOD_ID = "threat_hullmod";
    static int pinned;

    private ThreatHulkReclaimLaw() {
    }

    /** Idempotent; fail-soft (presentation-side treatment, never a bind breaker). */
    static void pin(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        try {
            ShipVariantAPI variant = ship.getVariant();
            if (variant == null || !variant.hasHullMod(THREAT_HULLMOD_ID)) {
                return;
            }
            if (!ship.hasTag(ThreatHullmod.SHIP_BEING_RECLAIMED)) {
                ship.addTag(ThreatHullmod.SHIP_BEING_RECLAIMED);
                pinned++;
            }
        } catch (Throwable ignore) {
            // fail open: the vanilla path stays, as before this law
        }
    }
}
