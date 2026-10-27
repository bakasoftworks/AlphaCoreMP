package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * The per-target DAMAGE-TAKEN stat signature, the "fixed" gate for
 * D-DMGDIV (client over-damaged the seat-2 replica 1.21× on the bare-hull regime; root = the
 * replica's {@code fleetCommanderForStats} installed POST-spawn, so Cybernetic Augmentation +
 * the threshold-scaled fleetwide damage-taken writers ELIDED at instantiate). This class reads
 * the stats the per-hit damage formula derives from and renders them in ONE law both ends can
 * byte-compare: the server twin ({@code cc.starsector.server.bridge} stat-sig dump, reflective)
 * renders the same getters through the same {@link ClientParityWalker#modsString} /
 * {@code Double.toString((double) v)} idiom the parity walk already proves byte-identical.
 *
 * <p>Two renders: {@link #compact} rides every {@code dmg-apply} row (cheap, no allocation
 * beyond the builder); {@link #verbose} rides each instantiate ({@code SPAWNED} / own bind /
 * respawn) and the first hit per target instance — it carries the per-modifier attribution so
 * a grade can NAME the missing writer (e.g. no {@code cybernetic_augmentation} key on the
 * replica's {@code hdm} lane) instead of inferring it from a ratio.
 *
 * <p>Fields: {@code hdm}/{@code adm}/{@code sdm} = hull/armor/shield damage-taken mult
 * (modified); {@code mar} = max armor damage reduction; {@code ab} = min armor fraction ({@code getMinArmorFraction}); {@code cr}
 * = current CR; {@code fc} = member has a fleet-commander-for-stats (1/0; {@code -} = no
 * member); {@code cmdElite}/{@code capElite} = elite (level≥2) skill counts on that commander /
 * the ship's captain ({@code -} = none); {@code hm} = hullmod count. Every read is fail-soft
 * ({@code err}) — a witness lane never breaks the damage path it observes. Classloader-B legal:
 * public API only, no reflection, no java.io.
 */
final class TargetStatSig {

    private TargetStatSig() {
    }

    /** The compact per-hit signature (one line fragment, no spaces inside values). */
    static String compact(ShipAPI ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(160);
        appendCore(sb, ship, false);
        return sb.toString();
    }

    /** The compact signature + per-modifier attribution + hullmods + captain/commander ids. */
    static String verbose(ShipAPI ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(512);
        appendCore(sb, ship, true);
        return sb.toString();
    }

    private static void appendCore(StringBuilder sb, ShipAPI ship, boolean verbose) {
        MutableShipStatsAPI s = null;
        try {
            s = ship.getMutableStats();
        } catch (Throwable ignore) {
            // stats read is best-effort
        }
        MutableStat hdm = statOrNull(s, 0);
        MutableStat adm = statOrNull(s, 1);
        MutableStat sdm = statOrNull(s, 2);
        MutableStat mar = statOrNull(s, 3);
        MutableStat ab = statOrNull(s, 4);
        sb.append("hdm=").append(mod(hdm));
        sb.append(" adm=").append(mod(adm));
        sb.append(" sdm=").append(mod(sdm));
        sb.append(" mar=").append(mod(mar));
        sb.append(" ab=").append(mod(ab));
        sb.append(" cr=");
        try {
            sb.append(num(ship.getCurrentCR()));
        } catch (Throwable t) {
            sb.append("err");
        }
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
        sb.append(" cmdElite=").append(commander == null ? "-" : eliteCount(commander));
        PersonAPI captain = null;
        try {
            captain = ship.getCaptain();
        } catch (Throwable ignore) {
            // best-effort
        }
        sb.append(" capElite=").append(captain == null ? "-" : eliteCount(captain));
        Collection<String> hullmods = null;
        try {
            hullmods = ship.getVariant() == null ? null : ship.getVariant().getHullMods();
        } catch (Throwable ignore) {
            // best-effort
        }
        sb.append(" hm=").append(hullmods == null ? "-" : String.valueOf(hullmods.size()));
        if (!verbose) {
            return;
        }
        sb.append(" hdmMods=").append(mods(hdm));
        sb.append(" admMods=").append(mods(adm));
        sb.append(" marMods=").append(mods(mar));
        sb.append(" abMods=").append(mods(ab));
        sb.append(" hullmods=[");
        if (hullmods != null) {
            boolean first = true;
            for (String id : new TreeSet<String>(hullmods)) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(id);
            }
        }
        sb.append(']');
        sb.append(" captain=").append(personTag(captain));
        sb.append(" commander=").append(personTag(commander));
    }

    /** The five damage-formula stats by ordinal — one switch so every read shares the guard. */
    private static MutableStat statOrNull(MutableShipStatsAPI s, int which) {
        if (s == null) {
            return null;
        }
        try {
            switch (which) {
                case 0: return s.getHullDamageTakenMult();
                case 1: return s.getArmorDamageTakenMult();
                case 2: return s.getShieldDamageTakenMult();
                case 3: return s.getMaxArmorDamageReduction();
                case 4: return s.getMinArmorFraction();
                default: return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Package-shared with {@link DealtStatSig} — ONE rendering law, both sigs. */
    static String mod(MutableStat stat) {
        if (stat == null) {
            return "null";
        }
        try {
            return num(stat.getModifiedValue());
        } catch (Throwable t) {
            return "err";
        }
    }

    static String mods(MutableStat stat) {
        if (stat == null) {
            return "null";
        }
        try {
            return ClientParityWalker.modsString(stat.getPercentMods(), stat.getFlatMods(),
                    stat.getMultMods());
        } catch (Throwable t) {
            return "err";
        }
    }

    /** Elite (level ≥ 2) skill count over THIS engine's skill table, or {@code err}. */
    static String eliteCount(PersonAPI person) {
        try {
            List<String> ids = Global.getSettings().getSortedSkillIds();
            int n = 0;
            for (String id : ids) {
                if (person.getStats().getSkillLevel(id) >= 2f) {
                    n++;
                }
            }
            return String.valueOf(n);
        } catch (Throwable t) {
            return "err";
        }
    }

    /** {@code 'First Last'/L<level>/<skills>} — identity of the stats person, never a stat. */
    static String personTag(PersonAPI p) {
        if (p == null) {
            return "null";
        }
        try {
            String name = p.getName() == null ? "?" : p.getName().getFullName();
            return "'" + name + "'/L" + p.getStats().getLevel() + "/" + p.getStats().getSkillsCopy().size();
        } catch (Throwable t) {
            return "err";
        }
    }

    /** The parity walk's float law: rendered through double (server boxes the same way). */
    private static String num(float v) {
        double d = v;
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Inf" : "-Inf";
        }
        return String.valueOf(d);
    }
}
