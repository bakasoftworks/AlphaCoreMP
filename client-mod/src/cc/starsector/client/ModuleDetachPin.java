package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.DynamicStatsAPI;

/**
 * Server-owned module detach (RE report):
 * pin {@code module_detach_chance_mult} to 0 on every
 * station-module body this client binds, so the CLIENT's engine never rolls its own detach.
 *
 * <p>WHY: the client kills a replica module through real engine damage, so the vanilla death
 * branch (re-rolled per slotted child at root death,
 * and the sibling knock-off) runs a 50 % × mult {@code Math.random()} roll with
 * the client's OWN RNG. When the server keeps a dead module SLOTTED but this window rolled DETACH,
 * the local wreck breaks free, collides with the root replica, gets pushed out, and every 0.5 s
 * hulk keyframe (slot pose riding the root, v=0) snaps it back INTO the hulk — the reported "stuck
 * colliding in the geometry of the mothership".
 *
 * <p>WHICH MAP (the pin fired 29x and cured nothing): the
 * engine's {@code DynamicStats} keeps TWO maps — {@code getStat(id)} → {@code MutableStat}
 * (base 1.0) and {@code getMod(id)} → {@code StatBonus}. All three detach rolls call the ONE-ARG
 * {@code getValue(id)}, which reads ONLY
 * the {@code MutableStat} map; the {@code StatBonus} map feeds only the two-arg
 * {@code getValue(id, base)}. The pin wrote {@code getMod(...)} = the map the rolls never
 * read, so every window kept rolling its own 50 %. Vanilla's own {@code NeverDetaches} hullmod
 * is
 * {@code getStat(MODULE_DETACH_CHANCE_MULT).modifyMult(id, 0f)} — this pin now does exactly that
 * (mult 0 ⇒ {@code getModifiedValue()} = 0 ⇒ {@code 0.5F × 0} never beats {@code Math.random()};
 * the {@code always_detaches} flat on own-side fortress modules is a flat on the SAME stat, so
 * mult 0 beats it too), keeps the {@code getMod} write for the two-arg overload, and the proof
 * row READS BACK {@code getValue(id)} — the engine's own read path — so a row can never again
 * claim a pin the roll does not see. The server's already-built {@code ROOT_DETACHED} kind-4 hulk
 * edge ({@code HulkFractureFrameCodec}, client latch
 * {@code FractureFamilyCoordinator.assertModuleDetach}) is the ONE detach authority = the
 * server's vanilla roll mirrored exactly. Zero per-tick cost; nothing on the wire; the server
 * needs nothing.
 *
 * <p>Site = {@link PredictReconcileCombatPlugin#neutralizeRemoteBody} (every replica body treatment:
 * descriptor fortress modules, appended-station modules, repairs, re-adopts, remote mounts) plus
 * the OWN mounted module ({@link OwnMountLifecycleLane#adoptOwnMountedModule}). Idempotent: the
 * mult entry is keyed by {@value #SOURCE}; a second pin on the same body is a no-op in the stat.
 * The one-shot {@code detach-pin} row per station module is the flight's proof row (grade =
 * a derelict flight with no stuck report + {@code slotted_skips>0} on server-slotted wrecks).
 * Classloader-B law: API-only refs ({@code ShipAPI}, {@code Stats}, {@code StatBonus}).
 */
final class ModuleDetachPin {
    static final String SOURCE = "cc_server_owned_detach";

    private static int pins;
    private static int failures;
    private static int readbackFailures;

    private ModuleDetachPin() {
    }

    /**
     * Pin {@code ship}'s detach mult to 0. Fail-soft: a throw logs once per 50 and never reaches
     * the caller. Logs one row per STATION MODULE body (the proof row); other bodies pin silently
     * (a non-module's mult is never read).
     *
     * @return true when the mult was written
     */
    static boolean pin(ShipAPI ship, String why) {
        if (ship == null) {
            return false;
        }
        try {
            DynamicStatsAPI dyn = ship.getMutableStats().getDynamic();
            // The map the engine's three rolls READ: 1-arg getValue(id) -> MutableStat map
            // Vanilla NeverDetaches writes exactly this.
            dyn.getStat(Stats.MODULE_DETACH_CHANCE_MULT).modifyMult(SOURCE, 0f);
            // The other map (2-arg getValue(id, base) -> StatBonus); unread by the rolls today,
            // pinned so either overload sees 0.
            dyn.getMod(Stats.MODULE_DETACH_CHANCE_MULT).modifyMult(SOURCE, 0f);
            // Readback through the engine's own read path — the row states what the roll sees.
            float read = dyn.getValue(Stats.MODULE_DETACH_CHANCE_MULT);
            pins++;
            String hull = ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId();
            if (read != 0f) {
                readbackFailures++;
                PilotWatchLog.error("predict detach-pin: READBACK " + read + " != 0 hull=" + hull
                        + " (" + why + ", readbackFailures=" + readbackFailures
                        + ") — the engine's roll would still see this value",
                        new IllegalStateException("detach-pin readback " + read));
                return false;
            }
            if (ship.isStationModule()) {
                PilotWatchLog.info("predict detach-pin: module hull=" + hull
                        + " mult=0 read=" + read + " (" + why + ", pins=" + pins + ")");
            }
            return true;
        } catch (Throwable t) {
            if (failures++ % 50 == 0) {
                PilotWatchLog.error("predict detach-pin: FAILED (" + why + ", failures="
                        + failures + ") — this body may roll its own detach", t);
            }
            return false;
        }
    }

    /** Selftest/diagnostic readout. */
    static String describe() {
        return "pins=" + pins + " failures=" + failures + " readbackFailures=" + readbackFailures;
    }
}
