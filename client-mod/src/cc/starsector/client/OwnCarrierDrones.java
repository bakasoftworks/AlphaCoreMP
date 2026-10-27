package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

import java.util.List;

/**
 * The piloted carrier's PUPPET-DRONE roster: the one seam between the
 * fighter-HUD lane (which knows which wire-born fighter replicas are the piloted hull's bay
 * fighters) and the mod-shipped {@link CcDroneStrikeStats} (which the engine instantiates BY
 * CLASS NAME from {@code data/shipsystems/drone_strike.system} — no plugin reference reaches it).
 *
 * <p><b>Why.</b> Own-ship fighters are wire puppets and the bay's own wing field is never set (the
 * fighter-HUD lane's replica wing stays inert, {@link FighterWingHudLane} class doc), so vanilla
 * {@code DroneStrikeStats.getDrones} — a walk of {@code bay.getWing().getWingMembers()} — is EMPTY
 * on the piloted Tempest/Apex: the engine refuses the press
 * ({@code isUsable} false) and the HUD reads NO DRONES.
 * Because the up-lane USE_SYSTEM bit is {@code getSystem().isOn()}
 * (PRCP bit 7 — the engine's own activation decision, never the raw key), a refused local press
 * never reaches the serve either: zero seat-2 {@code pilot-sysint} rows in the whole flight.
 *
 * <p><b>Side law.</b> The serve JVM loads the same mod data + jar, so the script
 * runs server-side too; there NOTHING installs a roster, {@link #puppetDronesOf} returns null and
 * the script is vanilla byte-for-byte. Only the client's fighter-HUD lane installs itself, for the
 * piloted carrier only, and uninstalls at round wrap / carrier change / dead-latch. Render thread
 * only. Class-load law: API types only (the holder loads in any JVM the plugin loads in).
 */
final class OwnCarrierDrones {

    /** Installed by the fighter-HUD lane while it has a registered piloted carrier. */
    interface Roster {
        /** The live enrolled puppet fighters of {@code carrier}, or null when {@code carrier} is
         *  not the registered piloted carrier (the caller falls back to vanilla). */
        List<ShipAPI> puppetDronesOf(ShipAPI carrier);
    }

    private static volatile Roster roster;
    /** Meter: own presses whose LOCAL conversion was skipped (the serve converts). */
    private static int ownPresses;

    private OwnCarrierDrones() {
    }

    static void install(Roster r) {
        roster = r;
    }

    static void uninstall(Roster r) {
        if (roster == r) {
            roster = null;
        }
    }

    /** Null = not the piloted carrier (or no roster at all: the serve JVM, a local round) —
     *  vanilla path. Fail-soft: a throwing roster reads as vanilla. */
    static List<ShipAPI> puppetDronesOf(ShipAPI carrier) {
        Roster r = roster;
        if (r == null || carrier == null) {
            return null;
        }
        try {
            return r.puppetDronesOf(carrier);
        } catch (Throwable ignore) {
            return null; // presentation seam: vanilla on any hiccup
        }
    }

    /** The grading row for the next live test run: one line per own press. */
    static void ownPressSkipped(ShipAPI carrier, int puppets, ShipAPI target) {
        ownPresses++;
        try {
            String hull = carrier == null || carrier.getHullSpec() == null ? "-"
                    : FallbackAnnounce.sanitize(carrier.getHullSpec().getHullId(), 32);
            String tgt = target == null || target.getHullSpec() == null ? "-"
                    : FallbackAnnounce.sanitize(target.getHullSpec().getHullId(), 32);
            PilotWatchLog.info("predict drone-strike OWN press #" + ownPresses + " on '" + hull
                    + "': local conversion SKIPPED (the serve converts; the wire delivers the missile"
                    + " + arc) puppets=" + puppets + " target=" + tgt);
        } catch (Throwable ignore) {
            // meter line only
        }
    }
}
