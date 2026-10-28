package cc.starsector.client;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import com.fs.starfarer.api.characters.PersonAPI;

/**
 * The per-SHOOTER damage-DEALT stat signature, the owner
 * skills-hypothesis census (the client may not apply the other player's skills on its side).
 * The
 * {@link TargetStatSig} twin on the OTHER side of the damage formula: where tsig renders the
 * victim's damage-TAKEN mults, this renders the shooter's damage-DEALT mults, in the same
 * byte-comparable law ({@code Double.toString((double) v)}; mods lanes via
 * {@link ClientParityWalker#modsString}) the server twin ({@code ShipDealtSig}, reflective)
 * mirrors getter-for-getter. Any field-law change is a same-commit both-trees edit.
 *
 * <p>Field law (VERBATIM shared with the server twin): {@code bwd}/{@code ewd}/{@code mwd}/
 * {@code bmd} = ballistic/energy/missile/beam weapon damage mult (modified); {@code dtf}/
 * {@code dtm}/{@code dfr}/{@code dds}/{@code dcr}/{@code dcp} = damage to fighters/missiles/
 * frigates/destroyers/cruisers/capitals; {@code dte}/{@code dtw}/{@code dts}/{@code dth} =
 * damage to target engines/weapons/shields/hull mult; {@code hsb} = hit-strength bonus rendered
 * as its mods lanes (a {@link StatBonus} — no single modified value; usually {@code p[]f[]m[]});
 * {@code fc}/{@code cmdElite}/{@code capElite}/{@code hm} = the tsig identity-context law
 * verbatim. RoF mults are deliberately OUT: they scale fire cadence, never a landed hit's
 * per-hit application — the census question. Verbose adds the per-modifier mods lane per
 * MutableStat field so a grade can NAME the missing writer.
 *
 * <p>Every read is fail-soft ({@code err}/{@code null}); classloader-B legal (public API only).
 */
final class DealtStatSig {

    private DealtStatSig() {
    }

    /** The compact per-shooter signature (one line fragment, no spaces inside values). */
    static String compact(ShipAPI ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(256);
        appendCore(sb, ship, false);
        return sb.toString();
    }

    /** The compact signature + per-modifier attribution + captain/commander ids. */
    static String verbose(ShipAPI ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(768);
        appendCore(sb, ship, true);
        return sb.toString();
    }

    /** The 14 dealt-side stats by ordinal — one switch so every read shares the guard. */
    private static MutableStat statOrNull(MutableShipStatsAPI s, int which) {
        if (s == null) {
            return null;
        }
        try {
            switch (which) {
                case 0: return s.getBallisticWeaponDamageMult();
                case 1: return s.getEnergyWeaponDamageMult();
                case 2: return s.getMissileWeaponDamageMult();
                case 3: return s.getBeamWeaponDamageMult();
                case 4: return s.getDamageToFighters();
                case 5: return s.getDamageToMissiles();
                case 6: return s.getDamageToFrigates();
                case 7: return s.getDamageToDestroyers();
                case 8: return s.getDamageToCruisers();
                case 9: return s.getDamageToCapital();
                case 10: return s.getDamageToTargetEnginesMult();
                case 11: return s.getDamageToTargetWeaponsMult();
                case 12: return s.getDamageToTargetShieldsMult();
                case 13: return s.getDamageToTargetHullMult();
                default: return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String[] KEYS = {
            "bwd", "ewd", "mwd", "bmd", "dtf", "dtm", "dfr", "dds", "dcr", "dcp",
            "dte", "dtw", "dts", "dth"};

    private static void appendCore(StringBuilder sb, ShipAPI ship, boolean verbose) {
        MutableShipStatsAPI s = null;
        try {
            s = ship.getMutableStats();
        } catch (Throwable ignore) {
            // stats read is best-effort
        }
        MutableStat[] stats = new MutableStat[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            stats[i] = statOrNull(s, i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(KEYS[i]).append('=').append(TargetStatSig.mod(stats[i]));
        }
        sb.append(" hsb=").append(hsbMods(s));
        FleetMemberAPI member = null;
        try {
            member = ship.getFleetMember();
        } catch (Throwable ignore) {
            // member resolve is best-effort (a game-free harness ship may carry none)
        }
        PersonAPI commander = null;
        if (member != null) {
            try {
                commander = member.getFleetCommanderForStats();
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        sb.append(" fc=").append(member == null ? "-" : (commander != null ? "1" : "0"));
        sb.append(" cmdElite=").append(commander == null ? "-" : TargetStatSig.eliteCount(commander));
        PersonAPI captain = null;
        try {
            captain = ship.getCaptain();
        } catch (Throwable ignore) {
            // best-effort
        }
        sb.append(" capElite=").append(captain == null ? "-" : TargetStatSig.eliteCount(captain));
        java.util.Collection<String> hullmods = null;
        try {
            hullmods = ship.getVariant() == null ? null : ship.getVariant().getHullMods();
        } catch (Throwable ignore) {
            // best-effort
        }
        sb.append(" hm=").append(hullmods == null ? "-" : String.valueOf(hullmods.size()));
        if (!verbose) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            sb.append(' ').append(KEYS[i]).append("Mods=").append(TargetStatSig.mods(stats[i]));
        }
        sb.append(" captain=").append(TargetStatSig.personTag(captain));
        sb.append(" commander=").append(TargetStatSig.personTag(commander));
    }

    /** Hit-strength bonus as its three mods lanes (StatBonus map getters; {@code err} on any). */
    private static String hsbMods(MutableShipStatsAPI s) {
        if (s == null) {
            return "null";
        }
        try {
            StatBonus hsb = s.getHitStrengthBonus();
            if (hsb == null) {
                return "null";
            }
            return ClientParityWalker.modsString(hsb.getPercentBonuses(), hsb.getFlatBonuses(),
                    hsb.getMultBonuses());
        } catch (Throwable t) {
            return "err";
        }
    }
}
