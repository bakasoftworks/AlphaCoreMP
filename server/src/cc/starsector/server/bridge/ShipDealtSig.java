package cc.starsector.server.bridge;

import java.util.Collection;

/**
 * The per-ship damage-DEALT stat signature, the server twin of the
 * client-mod's {@code cc.starsector.client.DealtStatSig} (the owner skills-hypothesis census:
 * does either end run a shooter whose dealt-side mults the other end lacks — e.g. the other
 * player's skills never applied clientside). Renders the SAME getters, in the SAME order,
 * through the SAME float/mods law as {@link ShipStatSig} (whose package-static helpers this
 * class reuses — ONE rendering law server-side, byte-comparable to the client per the parity
 * walk's proven idiom). Any field-law change is a same-commit both-trees edit.
 *
 * <p>Field law (VERBATIM from the client twin): {@code bwd}/{@code ewd}/{@code mwd}/{@code bmd}
 * = ballistic/energy/missile/beam weapon damage mult (modified); {@code dtf}/{@code dtm}/
 * {@code dfr}/{@code dds}/{@code dcr}/{@code dcp} = damage to fighters/missiles/frigates/
 * destroyers/cruisers/capitals; {@code dte}/{@code dtw}/{@code dts}/{@code dth} = damage to
 * target engines/weapons/shields/hull mult; {@code hsb} = hit-strength bonus as its mods lanes
 * (a StatBonus — map getters {@code getPercentBonuses}/{@code getFlatBonuses}/
 * {@code getMultBonuses}); {@code fc}/{@code cmdElite}/{@code capElite}/{@code hm} = the
 * ShipStatSig identity-context law verbatim. RoF mults deliberately OUT (cadence, not per-hit
 * application). Verbose adds a mods lane per MutableStat field. Reflection, public-API method
 * names only; every read fail-soft — a witness lane never breaks the serve loop it observes.
 */
public final class ShipDealtSig {

    private ShipDealtSig() {
    }

    /** Getter names by ordinal — index-matched to {@link #KEYS} (the client twin's switch). */
    private static final String[] GETTERS = {
            "getBallisticWeaponDamageMult", "getEnergyWeaponDamageMult",
            "getMissileWeaponDamageMult", "getBeamWeaponDamageMult",
            "getDamageToFighters", "getDamageToMissiles", "getDamageToFrigates",
            "getDamageToDestroyers", "getDamageToCruisers", "getDamageToCapital",
            "getDamageToTargetEnginesMult", "getDamageToTargetWeaponsMult",
            "getDamageToTargetShieldsMult", "getDamageToTargetHullMult"};

    private static final String[] KEYS = {
            "bwd", "ewd", "mwd", "bmd", "dtf", "dtm", "dfr", "dds", "dcr", "dcp",
            "dte", "dtw", "dts", "dth"};

    /** The compact per-shooter signature (one line fragment, no spaces inside values). */
    public static String compact(Object ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(256);
        appendCore(sb, ship, false);
        return sb.toString();
    }

    /** The compact signature + per-modifier attribution + captain/commander ids. */
    public static String verbose(Object ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(768);
        appendCore(sb, ship, true);
        return sb.toString();
    }

    private static void appendCore(StringBuilder sb, Object ship, boolean verbose) {
        Object s = ShipStatSig.callOrNull(ship, "getMutableStats");
        Object[] stats = new Object[GETTERS.length];
        for (int i = 0; i < GETTERS.length; i++) {
            stats[i] = ShipStatSig.callOrNull(s, GETTERS[i]);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(KEYS[i]).append('=').append(ShipStatSig.mod(stats[i]));
        }
        sb.append(" hsb=").append(hsbMods(ShipStatSig.callOrNull(s, "getHitStrengthBonus")));
        Object member = ShipStatSig.callOrNull(ship, "getFleetMember");
        Object commander = member == null ? null
                : ShipStatSig.callOrNull(member, "getFleetCommanderForStats");
        sb.append(" fc=").append(member == null ? "-" : (commander != null ? "1" : "0"));
        sb.append(" cmdElite=").append(commander == null ? "-" : ShipStatSig.eliteCount(commander));
        Object captain = ShipStatSig.callOrNull(ship, "getCaptain");
        sb.append(" capElite=").append(captain == null ? "-" : ShipStatSig.eliteCount(captain));
        Object variant = ShipStatSig.callOrNull(ship, "getVariant");
        Object hullmodsRaw = variant == null ? null : ShipStatSig.callOrNull(variant, "getHullMods");
        Collection<?> hullmods = hullmodsRaw instanceof Collection ? (Collection<?>) hullmodsRaw : null;
        sb.append(" hm=").append(hullmods == null ? "-" : String.valueOf(hullmods.size()));
        if (!verbose) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            sb.append(' ').append(KEYS[i]).append("Mods=").append(ShipStatSig.mods(stats[i]));
        }
        sb.append(" captain=").append(ShipStatSig.personTag(captain));
        sb.append(" commander=").append(ShipStatSig.personTag(commander));
    }

    /** StatBonus mods lanes — the bonus-map getter names, same lane render as MutableStat. */
    private static String hsbMods(Object hsb) {
        if (hsb == null) {
            return "null";
        }
        try {
            StringBuilder sb = new StringBuilder(96);
            ShipStatSig.appendModsLane(sb, 'p', ShipStatSig.call(hsb, "getPercentBonuses"));
            ShipStatSig.appendModsLane(sb, 'f', ShipStatSig.call(hsb, "getFlatBonuses"));
            ShipStatSig.appendModsLane(sb, 'm', ShipStatSig.call(hsb, "getMultBonuses"));
            return sb.toString();
        } catch (Throwable t) {
            return "err";
        }
    }
}
