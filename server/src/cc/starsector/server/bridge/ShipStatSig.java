package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Server half of the per-ship DAMAGE-TAKEN stat signature — the server
 * twin of the client-mod's {@code cc.starsector.client.TargetStatSig}. D-DMGDIV: the
 * shooter-client over-damaged the seat-2 replica 1.21× on the bare-hull regime because the
 * replica's {@code fleetCommanderForStats} landed POST-instantiate (Cybernetic Augmentation +
 * the fleetwide damage-taken writers elided). The "fixed" gate is BOTH ends rendering the stats
 * the per-hit damage formula derives from in ONE byte-comparable law: this class renders the
 * SAME getters, in the SAME order, through the SAME float/mods rendering as the client
 * ({@code Double.toString((double) v)}; {@code p[id:v,...]f[...]m[...]} lanes sorted by id — the
 * parity walk's proven byte-identical law, {@code ParityAuditWalker.renderModsString}).
 *
 * <p>Field law (VERBATIM from the client; any change is a same-commit both-trees edit):
 * {@code hdm}/{@code adm}/{@code sdm} = hull/armor/shield damage-taken mult (modified);
 * {@code mar} = max armor damage reduction; {@code ab} = min armor fraction; {@code cr} = current
 * CR; {@code fc} = member has a fleet-commander-for-stats ({@code 1}/{@code 0}; {@code -} = no
 * member); {@code cmdElite}/{@code capElite} = elite (level ≥ 2) skill counts on that commander /
 * the ship's captain ({@code -} = none); {@code hm} = hullmod count. Verbose adds
 * {@code hdmMods admMods marMods abMods} (per-modifier attribution), sorted {@code hullmods=[...]},
 * {@code captain=}/{@code commander=} {@code 'Full Name'/L<level>/<skillCount>}.
 *
 * <p>Reflection, public-API method names only ({@code getMutableStats}, {@code getHullDamageTakenMult},
 * {@code getModifiedValue}, {@code getFleetMember}, {@code getFleetCommanderForStats}, {@code getCaptain},
 * {@code getStats}, {@code getSkillsCopy}, {@code getVariant}, {@code getHullMods} — the
 * {@code ship.getClass().getMethod(...)} idiom ECB/ParityAuditWalker already prove in production);
 * handles cached per (class, name) — serve-thread confined, never shared. Every read is fail-soft
 * ({@code err}/{@code null} per field): a witness lane never breaks the serve loop it observes.
 *
 * <p>ONE deliberate getter delta vs the client: elite counts walk {@code getStats().getSkillsCopy()}
 * ({@code getLevel() >= 2}) instead of {@code Global.getSettings().getSortedSkillIds()} +
 * {@code getSkillLevel(id) >= 2} — identical counts (a held skill is in both tables; an unheld id
 * reads level 0) without a {@code Global} dependency headless; the cost is O(held skills) per person
 * and is cached by person identity (skills never change mid-combat; the cache clears at 512).
 */
public final class ShipStatSig {

    private ShipStatSig() {
    }

    /** Sentinel for a (class, name) lookup that already failed — never retried. */
    private static final Method MISSING;
    static {
        Method m = null;
        try {
            m = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException impossible) {
            // Object.hashCode always exists
        }
        MISSING = m;
    }

    /** (class → (name → Method|MISSING)); serve-thread confined, unbounded by design (few classes). */
    private static final Map<Class<?>, Map<String, Method>> METHODS = new HashMap<>();
    /** person identity → elite count string; cleared when it grows past 512 (wave officers). */
    private static final Map<Object, String> ELITE_BY_PERSON = new IdentityHashMap<>();

    /** The compact per-hit signature (one line fragment, no spaces inside values). */
    public static String compact(Object ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(160);
        appendCore(sb, ship, false);
        return sb.toString();
    }

    /** The compact signature + per-modifier attribution + hullmods + captain/commander ids. */
    public static String verbose(Object ship) {
        if (ship == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(512);
        appendCore(sb, ship, true);
        return sb.toString();
    }

    private static void appendCore(StringBuilder sb, Object ship, boolean verbose) {
        Object s = callOrNull(ship, "getMutableStats");
        Object hdm = callOrNull(s, "getHullDamageTakenMult");
        Object adm = callOrNull(s, "getArmorDamageTakenMult");
        Object sdm = callOrNull(s, "getShieldDamageTakenMult");
        Object mar = callOrNull(s, "getMaxArmorDamageReduction");
        Object ab = callOrNull(s, "getMinArmorFraction");
        sb.append("hdm=").append(mod(hdm));
        sb.append(" adm=").append(mod(adm));
        sb.append(" sdm=").append(mod(sdm));
        sb.append(" mar=").append(mod(mar));
        sb.append(" ab=").append(mod(ab));
        sb.append(" cr=");
        try {
            Object cr = call(ship, "getCurrentCR");
            sb.append(cr instanceof Number ? num(((Number) cr).floatValue()) : "err");
        } catch (Throwable t) {
            sb.append("err");
        }
        Object member = callOrNull(ship, "getFleetMember");
        Object commander = member == null ? null : callOrNull(member, "getFleetCommanderForStats");
        sb.append(" fc=").append(member == null ? "-" : (commander != null ? "1" : "0"));
        sb.append(" cmdElite=").append(commander == null ? "-" : eliteCount(commander));
        Object captain = callOrNull(ship, "getCaptain");
        sb.append(" capElite=").append(captain == null ? "-" : eliteCount(captain));
        Object variant = callOrNull(ship, "getVariant");
        Object hullmodsRaw = variant == null ? null : callOrNull(variant, "getHullMods");
        Collection<?> hullmods = hullmodsRaw instanceof Collection ? (Collection<?>) hullmodsRaw : null;
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
            TreeSet<String> sorted = new TreeSet<>();
            for (Object id : hullmods) {
                sorted.add(String.valueOf(id));
            }
            boolean first = true;
            for (String id : sorted) {
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

    static String mod(Object stat) {
        if (stat == null) {
            return "null";
        }
        try {
            Object v = call(stat, "getModifiedValue");
            return v instanceof Number ? num(((Number) v).floatValue()) : "err";
        } catch (Throwable t) {
            return "err";
        }
    }

    /** {@code p[id:v,...]f[id:v,...]m[id:v,...]} — the parity walk's attribution law, byte-identical. */
    static String mods(Object stat) {
        if (stat == null) {
            return "null";
        }
        try {
            StringBuilder sb = new StringBuilder(96);
            appendModsLane(sb, 'p', call(stat, "getPercentMods"));
            appendModsLane(sb, 'f', call(stat, "getFlatMods"));
            appendModsLane(sb, 'm', call(stat, "getMultMods"));
            return sb.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    static void appendModsLane(StringBuilder sb, char lane, Object modsRaw) {
        sb.append(lane).append('[');
        if (modsRaw instanceof Map && !((Map<?, ?>) modsRaw).isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) modsRaw).entrySet()) {
                float v = statModValue(e.getValue());
                sorted.put(String.valueOf(e.getKey()), Double.toString((double) v));
            }
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
        }
        sb.append(']');
    }

    /** {@code StatMod.value} (public field) — 0 on a null/unreadable mod, like both parity halves. */
    static float statModValue(Object mod) {
        if (mod == null) {
            return 0.0F;
        }
        try {
            return mod.getClass().getField("value").getFloat(mod);
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /** Elite (level ≥ 2) skill count on the person's own skill table, cached by identity; {@code err}. */
    static String eliteCount(Object person) {
        String cached = ELITE_BY_PERSON.get(person);
        if (cached != null) {
            return cached;
        }
        String out;
        try {
            Object stats = call(person, "getStats");
            Object skills = call(stats, "getSkillsCopy");
            int n = 0;
            if (skills instanceof List) {
                for (Object sl : (List<?>) skills) {
                    Object level = call(sl, "getLevel");
                    if (level instanceof Number && ((Number) level).floatValue() >= 2f) {
                        n++;
                    }
                }
            }
            out = String.valueOf(n);
        } catch (Throwable t) {
            return "err"; // not cached: a transient failure may heal
        }
        if (ELITE_BY_PERSON.size() >= 512) {
            ELITE_BY_PERSON.clear();
        }
        ELITE_BY_PERSON.put(person, out);
        return out;
    }

    /** {@code 'First Last'/L<level>/<skills>} — identity of the stats person, never a stat. */
    static String personTag(Object p) {
        if (p == null) {
            return "null";
        }
        try {
            Object name = call(p, "getName");
            String full = name == null ? "?" : String.valueOf(call(name, "getFullName"));
            Object stats = call(p, "getStats");
            Object level = call(stats, "getLevel");
            Object skills = call(stats, "getSkillsCopy");
            int n = skills instanceof Collection ? ((Collection<?>) skills).size() : -1;
            return "'" + full + "'/L" + level + "/" + n;
        } catch (Throwable t) {
            return "err";
        }
    }

    /** The parity walk's float law: rendered through double (the client boxes the same way). */
    static String num(float v) {
        double d = v;
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Inf" : "-Inf";
        }
        return String.valueOf(d);
    }

    /** Invoke a no-arg public getter by name; null target → null; any failure → null. */
    static Object callOrNull(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return call(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invoke a no-arg public getter by name through the per-class handle cache; throws on any failure. */
    static Object call(Object target, String name) throws Exception {
        if (target == null) {
            throw new NullPointerException(name + " on null");
        }
        Class<?> c = target.getClass();
        Map<String, Method> byName = METHODS.get(c);
        if (byName == null) {
            byName = new HashMap<>();
            METHODS.put(c, byName);
        }
        Method m = byName.get(name);
        if (m == null) {
            try {
                m = c.getMethod(name);
            } catch (NoSuchMethodException e) {
                byName.put(name, MISSING);
                throw e;
            }
            byName.put(name, m);
        } else if (m == MISSING) {
            throw new NoSuchMethodException(c.getName() + "." + name);
        }
        return m.invoke(target);
    }
}
