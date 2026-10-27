package cc.starsector.client;

import java.util.Set;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

/**
 * The client-local "is this a Shrouded Dweller hull"
 * law, the one predicate two presentation lanes key on. Pure + game-free in {@link #isDwellerHull}
 * (selftest rows); the {@link ShipAPI} reader is try-wrapped and fails CLOSED to {@code false}
 * (an unreadable hull takes the ordinary human-ship path — the status quo, never a vanished body).
 *
 * <p><b>Why a hull-local predicate is the whole wire story.</b> Every {@code shrouded_*} row in
 * {@code starsector-core/data/hulls/ship_data.csv} (vortex, ejecta, tendril, eye,
 * maelstrom, maw) carries the hull tag {@code dweller} and the builtin
 * {@code dweller_hullmod}; that builtin runs the engine-side
 * {@code DwellerShipCreator} on EVERY engine that hosts the hull — the headless server and this
 * client's replica alike. The replica therefore already carries vanilla's own dweller laws
 * locally; what our lanes must do is stop OVERRIDING them:
 * <ul>
 * <li><b>D7 no-hulk death.</b> {@code BaseDwellerShipCreator.initBasicShipProperties} pins
 * {@code setHulkChanceOverride(0.0F)} — a
 * dweller leaves NO corpse; its sprite is never rendered
 * ({@code setDoNotRenderSprite(true)}). Our {@code presentServerDeath} forced {@code setHulkChanceOverride(1f)}, minted a
 * fracture family and pinned the hulk alive ⇒ the reported invisible, collidable, targetable
 * NEUTRALIZED hulk with a hull bar. The server's engine runs the same creator, so it never
 * publishes a hulk row for a dead dweller (the shrouded serve log carries none) — the client's
 * instant-remove death is the wire-faithful shape.</li>
 * <li><b>D4b(3) creator-governed mounts.</b> {@code ShroudedMaelstromShipCreator}'s DCP plugin
 * force-disables every mount but one EVERY FRAME ({@code setForceDisabled}, a firing stagger in
 * the creator). The engine's weapon status
 * returns {@code forceDisabled ? true : state == DISABLED}
 * (the body of
 * {@code WeaponAPI.isDisabled()}) — so the wire's per-mount
 * disabled bit, sampled off {@code isDisabled()}, toggles per mount per snapshot from the
 * SERVER's stagger phase, while the replica's local {@code isDisabled()} toggles from the LOCAL
 * stagger phase. Reconciling the two phases = {@code disable()}/{@code repair()} every snapshot
 * = the reported "dozens of Online/Offline popups" on the Maelstrom. The local creator IS the
 * equal-fidelity author of those mounts, so the weapon-state lane leaves dweller hulls alone.</li>
 * </ul>
 */
final class DwellerHullLaw {

    /** The vanilla hull tag every Shrouded Dweller hull carries ({@code ship_data.csv}). */
    static final String DWELLER_TAG = "dweller";
    /** Hull-id prefix fallback for a spec whose tag set is unreadable. */
    static final String DWELLER_ID_PREFIX = "shrouded_";

    private DwellerHullLaw() {
    }

    /** The pure law: the {@code dweller} tag, else the {@code shrouded_} id prefix. */
    static boolean isDwellerHull(Set<String> hullTags, String baseHullId) {
        if (hullTags != null && hullTags.contains(DWELLER_TAG)) {
            return true;
        }
        return baseHullId != null && baseHullId.startsWith(DWELLER_ID_PREFIX);
    }

    /** Fail-CLOSED reader: {@code false} on any unreadable body (ordinary death path). */
    static boolean isDweller(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        try {
            ShipHullSpecAPI spec = ship.getHullSpec();
            return spec != null && isDwellerHull(spec.getTags(), spec.getBaseHullId());
        } catch (Throwable ignore) {
            return false;
        }
    }
}
