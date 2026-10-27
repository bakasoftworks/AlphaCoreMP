package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;

/**
 * Corpse flameout fix — pin a replica corpse's engines to the vanilla DEAD-corpse
 * contract: permanently disabled, so the engine lane is inert under local (capped) damage.
 *
 * <p>Vanilla never hard-disables engines at hulk conversion — a normally-killed ship's engines
 * end DISABLED only via the in-line death blast (2000 HE + 10x
 * {@code randomDamage}), which is incidental, and a split PIECE gets a brand-new ACTIVE
 * controller with pristine engines. A DISABLED controller blocks
 * all further engine damage and on a hulk can never return to
 * ACTIVE (repair paths are {@code !isHulk}-gated) — that terminal state IS the vanilla corpse
 * contract ("vanilla hulks cannot flame out"). A live ACTIVE controller on a
 * wire-family piece lets local engine-area hits drive the flameout state machine:
 * server-owned corpse SUBSYSTEM state forked by local damage,
 * the same law family as membership and existence (those fork ONLY on wire edges).
 *
 * <p>Faithful lane: the engine's OWN public verbs — {@code getShipEngines()} +
 * {@code ShipEngineAPI.disable()} then {@code disable(true)} — the vanilla
 * {@code LowCRShipDamageSequence} idiom, split in two so the permanent upgrade is
 * PRESENTATION-SILENT (F-critical-malfunction): the health tracker presents only on its
 * ACTIVE→DISABLED transition, and the "Critical Malfunction!" floaty + {@code engine_disabled}
 * sound fire only when the permanent flag is already set at that transition (set-before-present);
 * a one-call {@code disable(true)} on a
 * corpse mint/cut spams red floaties per engine. No engine-internal links, no
 * reflection, B-clean by construction. Fail-soft: any surprise degrades to a one-shot log +
 * vanilla behavior, never a throw into the kill/apply path.
 */
final class HulkEngineQuiet {

    /** Latched on the first failure — one loud line, never per-corpse spam. */
    private static volatile boolean failureLogged;

    private HulkEngineQuiet() {
    }

    /**
     * Permanently disable every engine on one replica corpse/piece (idempotent; call once at
     * creation — {@code mintFractureFamily} root / ADD-branch cut). Returns true when the
     * engine walk completed; false on failure or a missing controller. Never throws.
     */
    static boolean quiet(ShipAPI ship) {
        try {
            ShipEngineControllerAPI controller =
                    ship == null ? null : ship.getEngineController();
            if (controller == null) {
                return false;
            }
            for (ShipEngineControllerAPI.ShipEngineAPI engine : controller.getShipEngines()) {
                if (engine != null && !engine.isPermanentlyDisabled()) {
                    // Two steps: plain disable() takes the tracker's one presenting
                    // ACTIVE→DISABLED transition with perm=false (flameout sound only — the
                    // same sound a one-call disable(true) already played; NO floaty), then
                    // disable(true) upgrades the now-DISABLED tracker to permanent silently
                    // (both presentation branches require the ACTIVE state). End state
                    // byte-identical: {perm=true, state=DISABLED, hp=0}.
                    engine.disable();
                    engine.disable(true);
                }
            }
            return true;
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                PilotWatchLog.info("predict hulk-engine-quiet: DISABLED ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
            return false;
        }
    }
}
