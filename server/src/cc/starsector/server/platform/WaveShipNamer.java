package cc.starsector.server.platform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Vanilla-style random ship names for the server-spawned Remnant wave hulls, like the random
 * Remnant fleet ones in the vanilla campaign.
 *
 *
 *
 * <p><b>Why a re-implementation and not the engine's picker:</b> vanilla names a campaign fleet's
 * ships in {@code FleetData.pickShipName} — a WEIGHTED pick of one name-source group from the
 * faction spec's {@code shipNameSources} (remnants.faction: {@code MERCANTILE}/{@code SPACE}/
 * {@code DERELICT_DRONE}, weight 1 each), then a uniform draw WITH REPLACEMENT from that group's
 * list in {@code data/strings/ship_names.json}, formatted {@code "<shipNamePrefix> <name>"}
 * ({@code "TTDS Nova"}, {@code "TTDS Delta-Max"}). The engine's store ({@code ShipNameStore})
 * is engine-internal and its public wrapper ({@code FactionAPI.pickRandomShipName}) needs a campaign
 * {@code CampaignEngine} the headless serve never has — so this class reads the SAME two data
 * seams through the public API only ({@code SettingsAPI.getFactionSpec} + {@code loadJSON},
 * both plain data loads the headless boot already performs) and copies the 2-draw rule verbatim.
 * No engine-internal identity, no campaign dependency; mods that extend {@code ship_names.json} or the
 * faction file are honoured because the engine's own loaders serve the merged data.
 *
 * <p><b>Fail-closed to the slot name:</b> this is cosmetic — {@link #displayNameOrNull} NEVER
 * throws and returns {@code null} whenever the game data is unreachable (game-free contract
 * runs, a missing faction/list, a malformed name) so the caller keeps the authored
 * {@code "CC WAVE w.i"} slot id. The first failure logs ONE line; later calls stay silent.
 * Names are server-authored from game data and ride the RAPP roster-append frame; the
 * {@link #printableOrNull} fence keeps the wire value single-line printable and bounded
 * (the wire text laws) even if a mod ships odd data.
 *
 * <p>Wave hulls are Remnant by the BASTION shape (handoff: BASTION-ONLY), so the faction id is
 * fixed here; a descriptor-level faction knob is an owner-pull rider.
 * Reflection stays server-side (mod-jar B-deny law never applies to this tree).
 */
public final class WaveShipNamer {
    /** The naming faction: remnants.faction ({@code id "remnant"}, prefix {@code TTDS}). */
    public static final String FACTION_ID = "remnant";
    /** Engine ship-name lists (same file the engine's own store loads at data init). */
    public static final String SHIP_NAMES_JSON = "data/strings/ship_names.json";
    /** Wire fence for a display name (bounded; the longest vanilla entry is far shorter). */
    public static final int MAX_NAME_CHARS = 48;

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    /** Public API homes of every reflected member (see {@link #resolve()}). */
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String FACTION_SPEC_API = "com.fs.starfarer.api.campaign.FactionSpecAPI";
    private static final String PICKER_CLASS = "com.fs.starfarer.api.util.WeightedRandomPicker";
    private static final String JSON_OBJECT_CLASS = "org.json.JSONObject";
    private static final String JSON_ARRAY_CLASS = "org.json.JSONArray";

    private static final Object LOCK = new Object();
    private static Pools pools;            // resolved once per JVM (null until the first pick)
    private static boolean resolveFailed;  // one log line, then silent nulls
    private static final Random RNG = new Random();

    private WaveShipNamer() {
    }

    /**
     * Reseed the name stream (the per-round RNG re-arm of a pinned serve). Names never touch the
     * sim, but a pinned replay must also repeat the names a recording carries, and this stream
     * lives across rounds. The field stays final: {@code setSeed} is an instance call, under the
     * same lock the pick takes.
     */
    public static void reseed(long seed) {
        synchronized (LOCK) {
            RNG.setSeed(seed);
        }
    }

    /** One weighted name-source group: its list + the faction weight. */
    private record Group(String key, List<String> names, float weight) {
    }

    private record Pools(String prefix, List<Group> groups, float totalWeight) {
    }

    /**
     * A fresh vanilla-style Remnant name (e.g. {@code "TTDS Azimuth"}) or {@code null} when the
     * game data is unreachable — the caller then keeps {@code slotName}. Never throws.
     *
     * @param slotName the authored {@code "CC WAVE w.i"} id, used only for the one failure line
     */
    public static String displayNameOrNull(String slotName) {
        try {
            Pools p = poolsOrNull(slotName);
            if (p == null) {
                return null;
            }
            String name;
            synchronized (LOCK) {
                Group g = pickGroup(p, RNG);
                name = g.names().get(RNG.nextInt(g.names().size()));
            }
            String full = p.prefix().isEmpty() ? name : p.prefix() + " " + name;
            return printableOrNull(full);
        } catch (Throwable t) {
            noteFailure(slotName, "pick", t);
            return null;
        }
    }

    /**
     * Off-frame resolve for the boot path: the pools resolve LAZILY on the
     * first pick, and the first pick is otherwise the served wave-spawn loop on the SIM thread — a
     * JSON/spec load on a served frame (residency law 2026-08-09). {@code Main} calls this right
     * after the warm-up round, before the door binds. {@code true} = names available. Never throws.
     */
    public static boolean prewarm() {
        return poolsOrNull("prewarm") != null;
    }

    /** Vanilla {@code WeightedRandomPicker.pick} semantics: uniform draw over the total weight. */
    private static Group pickGroup(Pools p, Random rng) {
        float target = rng.nextFloat() * p.totalWeight();
        float acc = 0f;
        for (Group g : p.groups()) {
            acc += g.weight();
            if (target < acc) {
                return g;
            }
        }
        return p.groups().get(p.groups().size() - 1);
    }

    /** Single-line printable ASCII, 1..{@link #MAX_NAME_CHARS}; anything else ⇒ {@code null}. */
    public static String printableOrNull(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_NAME_CHARS) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
        }
        return s.trim().isEmpty() ? null : s;
    }

    private static Pools poolsOrNull(String slotName) {
        synchronized (LOCK) {
            if (pools != null) {
                return pools;
            }
            if (resolveFailed) {
                return null;
            }
            try {
                pools = resolve();
                if (pools == null) {
                    resolveFailed = true;
                    return null;
                }
                StringBuilder sb = new StringBuilder("wave-ship-namer READY faction=" + FACTION_ID
                        + " prefix='" + pools.prefix() + "' groups=");
                for (Group g : pools.groups()) {
                    sb.append(g.key()).append('(').append(g.names().size()).append('x')
                            .append(g.weight()).append(") ");
                }
                System.out.println(sb.toString().trim());
                return pools;
            } catch (Throwable t) {
                noteFailure(slotName, "resolve", t);
                return null;
            }
        }
    }

    /**
     * API-only data read; {@code null} = game-free context (no settings) or an empty spec.
     *
     * <p>Every {@link Method} is looked up on the PUBLIC API type (interface / public class),
     * never on {@code instance.getClass()}: the engine's {@code SettingsAPI} is an anonymous
     * {@code StarfarerSettings$1}, and a Method resolved on a non-public class throws
     * {@code IllegalAccessException} at invoke even though the member is public — the
     * failure (every wave shipped its slot name).
     */
    private static Pools resolve() throws Exception {
        ClassLoader loader = WaveShipNamer.class.getClassLoader();
        Class<?> settingsApi = Class.forName(SETTINGS_API, true, loader);
        Class<?> factionSpecApi = Class.forName(FACTION_SPEC_API, true, loader);
        Class<?> pickerClass = Class.forName(PICKER_CLASS, true, loader);
        Class<?> jsonObjectClass = Class.forName(JSON_OBJECT_CLASS, true, loader);
        Class<?> jsonArrayClass = Class.forName(JSON_ARRAY_CLASS, true, loader);
        Object settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings")
                .invoke(null);
        if (settings == null) {
            System.out.println("wave-ship-namer OFF: Global.getSettings() null (game-free"
                    + " context) — wave hulls keep their CC WAVE slot names");
            return null;
        }
        Object spec = settingsApi.getMethod("getFactionSpec", String.class)
                .invoke(settings, FACTION_ID);
        if (spec == null) {
            System.out.println("wave-ship-namer OFF: no faction spec '" + FACTION_ID + "'");
            return null;
        }
        Object prefixRaw = factionSpecApi.getMethod("getShipNamePrefix").invoke(spec);
        String prefix = prefixRaw == null ? "" : prefixRaw.toString().trim();
        Object picker = factionSpecApi.getMethod("getShipNameSources").invoke(spec);
        if (picker == null) {
            System.out.println("wave-ship-namer OFF: faction '" + FACTION_ID
                    + "' has no shipNameSources");
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Object> keys = (List<Object>) pickerClass.getMethod("getItems").invoke(picker);
        Method getWeight = pickerClass.getMethod("getWeight", int.class);
        Object json = settingsApi.getMethod("loadJSON", String.class)
                .invoke(settings, SHIP_NAMES_JSON);
        Method getJSONArray = jsonObjectClass.getMethod("getJSONArray", String.class);
        Method arrayLength = jsonArrayClass.getMethod("length");
        Method getString = jsonArrayClass.getMethod("getString", int.class);
        List<Group> groups = new ArrayList<>();
        float total = 0f;
        for (int i = 0; i < keys.size(); i++) {
            String key = String.valueOf(keys.get(i));
            float weight = ((Number) getWeight.invoke(picker, i)).floatValue();
            Object arr = getJSONArray.invoke(json, key);
            int n = ((Number) arrayLength.invoke(arr)).intValue();
            List<String> names = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                String candidate = printableOrNull(String.valueOf(getString.invoke(arr, k)));
                if (candidate != null) {
                    names.add(candidate);
                }
            }
            if (weight > 0f && !names.isEmpty()) {
                groups.add(new Group(key, List.copyOf(names), weight));
                total += weight;
            }
        }
        if (groups.isEmpty() || total <= 0f) {
            System.out.println("wave-ship-namer OFF: faction '" + FACTION_ID
                    + "' name sources resolved to zero usable names");
            return null;
        }
        return new Pools(prefix, List.copyOf(groups), total);
    }

    private static void noteFailure(String slotName, String phase, Throwable t) {
        synchronized (LOCK) {
            if (!resolveFailed) {
                resolveFailed = true;
                System.out.println("wave-ship-namer OFF (" + phase + " failed at " + slotName
                        + "): " + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " — wave hulls keep their CC WAVE slot names");
            }
        }
    }
}
