package cc.starsector.client;

import cc.starsector.server.match.AllSkillsFleetLaw;
import java.util.Locale;

import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * The per-ship fleet-context echo. One row per freshly created ship, every
 * value read FROM THE SHIP through typed API calls (never from the fleet data an attach meant to install), so a
 * pair that landed after the ship was created reads as what the engine derived at creation, not what was meant:
 * {@code fleet_ctx_ship id=<wire id> side=<absolute side> fab=<0|1> capt_default=<0|1> pc=<%.2f> fdp=<%.2f>}.
 * The server prints the same text for the same hull, so a grader diffs the two rows.
 * <ul>
 * <li>{@code fab}: the ship's member carries fleet data, the fleet-size throttle's input (a threshold skill
 * reads {@code maxBonus} at any fleet size without it);</li>
 * <li>{@code capt_default}: the captain IS that fleet data's commander (the engine's flagship path swaps the
 * captain for the fleet's bare commander, which drops every personal skill);</li>
 * <li>{@code pc}: the phase-cloak speed bonus the threshold skill left on the hull;</li>
 * <li>{@code fdp}: the sum of the percent modifiers on flux dissipation.</li>
 * </ul>
 * Spawn time only, never per frame: the first {@link #ECHO_CAP} ships of a mission build print, later ships are
 * counted. Sim thread only (every caller is a spawn site on it). Never throws: a failed read counts and logs once.
 */
final class FleetCtxShipEcho {

    /** Rows printed per mission build; later ships are counted in {@link #suppressedThisBuild}. */
    static final int ECHO_CAP = 64;

    private static int printedThisBuild;
    private static int suppressedThisBuild;
    private static int readFailures;
    private static boolean readFailureLogged;

    private FleetCtxShipEcho() {
    }

    /** The row text, pure; {@link Locale#ROOT} so a comma-decimal default locale never changes a byte of it. */
    static String format(String id, int side, boolean fab, boolean captDefault, float pc, float fdp) {
        return format(id, side, fab, captDefault, pc, fdp, null);
    }

    /**
     * The row with its key tail: {@code " key=<hex8>"} (the digest of the key the hull's fab was minted for)
     * exactly when {@code fab} and {@code keyOrNull} is known; a {@code fab=0} row never carries one, whatever
     * key is handed in. The server twin prints the same tail by the same rule, so the grader joins each ship's
     * sums on the key both ends minted it under.
     *
     *
     */
    static String format(String id, int side, boolean fab, boolean captDefault, float pc, float fdp,
            AllSkillsFleetLaw.FleetKey keyOrNull) {
        return "fleet_ctx_ship id=" + id + " side=" + side + " fab=" + (fab ? 1 : 0)
                + " capt_default=" + (captDefault ? 1 : 0)
                + " pc=" + String.format(Locale.ROOT, "%.2f", pc)
                + " fdp=" + String.format(Locale.ROOT, "%.2f", fdp)
                + (fab && keyOrNull != null ? " key=" + AllSkillsFleetLaw.hex8(keyOrNull.digest()) : "");
    }

    /** Called where the combat plugin starts a mission build: that build prints its own first ships. */
    static void resetForBuild() {
        printedThisBuild = 0;
        suppressedThisBuild = 0;
    }

    /**
     * Prints the row for a ship created right now. {@code wireId} = its wire entity id in decimal (a signed long,
     * &gt; 0 for a hull holder); {@code absSide} = its FIELDED absolute side (0 or 1, -1 = unresolved, printed
     * as is).
     */
    static void echo(ShipAPI ship, String wireId, int absSide) {
        if (ship == null) {
            return;
        }
        if (printedThisBuild >= ECHO_CAP) {
            suppressedThisBuild++;
            return;
        }
        String row;
        try {
            row = line(ship, wireId, absSide);
        } catch (RuntimeException e) {
            readFailures++;
            if (!readFailureLogged) {
                readFailureLogged = true;
                PilotWatchLog.error("allskills fleet_ctx_ship read FAILED (" + e.getClass().getSimpleName()
                        + ") -> no row for this ship; later failures counted", null);
            }
            return;
        }
        printedThisBuild++;
        PilotWatchLog.info("allskills " + row);
    }

    /** The row for {@code ship}, every value read from the ship itself; a null member or fleet data = {@code fab=0}.
     *  A fab this end minted adds its key tail; a fab no context here
     *  minted prints none. */
    static String line(ShipAPI ship, String wireId, int absSide) {
        FleetMemberAPI member = ship.getFleetMember();
        FleetDataAPI data = member == null ? null : member.getFleetData();
        boolean fab = data != null;
        PersonAPI captain = ship.getCaptain();
        boolean captDefault = fab && captain != null && captain == data.getCommander();
        MutableShipStatsAPI stats = ship.getMutableStats();
        float pc = stats.getDynamic().getMod(Stats.PHASE_CLOAK_SPEED_MOD).computeEffective(0f);
        float fdp = 0f;
        for (MutableStat.StatMod mod : stats.getFluxDissipation().getPercentMods().values()) {
            fdp += mod.value;
        }
        return format(wireId, absSide, fab, captDefault, pc, fdp, fab ? OfficerLoadout.fleetKeyOfFabOrNull(data) : null);
    }

    /** Rows printed since the last {@link #resetForBuild}. */
    static int printedThisBuild() {
        return printedThisBuild;
    }

    /** Ships past {@link #ECHO_CAP} since the last {@link #resetForBuild}. */
    static int suppressedThisBuild() {
        return suppressedThisBuild;
    }

    /** Reads that threw, cumulative for the process. */
    static int readFailures() {
        return readFailures;
    }
}
