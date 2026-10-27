package cc.starsector.server.platform;

import cc.starsector.server.match.AdhocOfficerFactory;
import cc.starsector.server.match.OfficerRosterCodec;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The boot-warmed table of {@link AdhocOfficerFactory} picks, one per
 * {@code /spawnfoe} / {@code /spawnfriend} catalogue id — classified ONCE at serve boot from the
 * engine's own variant facts (the vanilla {@code Misc.isAutomated} predicate = hullmod OR variant
 * tag OR hull tag; the hull id {@code ziggurat}; the fitted-weapon type counts), skill-checked and
 * wire-round-tripped through {@link OfficerAttach#validateRowSpecs} under the SAME
 * {@code row:<variantId>:<level>:<personality>} cache key the drain's attach reads, so no served
 * frame pays a first-sight skill check or a registry walk (the sim thread does the
 * minimum engine read; the drain only does one map lookup here).
 *
 * <p>Fail-SOFT per id (an unresolvable / odd registry entry is counted and skipped — the admin
 * simply gets an unofficered hull for it, logged at the drain), fail-CLOSED on OUR recipes (an
 * unknown skill id in a pool throws at boot exactly like a wave table row). Game-free contexts
 * (no {@code Global.getSettings()}, no API jar) warm nothing and say so.
 */
public final class AdhocOfficerTable {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String HULL_API = "com.fs.starfarer.api.combat.ShipHullSpecAPI";
    private static final String WEAPON_SPEC_API = "com.fs.starfarer.api.loading.WeaponSpecAPI";

    /** The vanilla automated predicate's one tag / hullmod id ({@code Misc.isAutomated}). */
    static final String AUTOMATED = "automated";
    /** The hull id of every Ziggurat variant (vanilla ships _Experimental / _Strike / _HF). */
    static final String ZIGGURAT_HULL_ID = "ziggurat";
    /** {@code Factions.DERELICT} — the hull tag the vanilla adapt step reads. */
    static final String DERELICT_TAG = "derelict";

    private static Map<String, AdhocOfficerFactory.Pick> table = Collections.emptyMap();

    private AdhocOfficerTable() {
    }

    /** The boot-warmed pick for {@code variantId}, or null (not warmed / unknown id). */
    public static synchronized AdhocOfficerFactory.Pick pickOrNull(String variantId) {
        return variantId == null ? null : table.get(variantId);
    }

    public static synchronized int size() {
        return table.size();
    }

    /** Contract-row seam: install a pre-built table (game-free). */
    static synchronized void install(Map<String, AdhocOfficerFactory.Pick> picks) {
        table = picks == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(picks));
    }

    /**
     * Classify + mint a pick for every id in {@code catalogueIds}, validate the set, install it.
     * Returns the number of picks warmed (0 game-free / knob off / empty catalogue). One
     * greppable {@code adhoc_officer_table} row either way.
     */
    public static synchronized int bootWarm(Collection<String> catalogueIds) {
        if (catalogueIds == null || catalogueIds.isEmpty()) {
            System.out.println("npeer-serve adhoc_officer_table warmed=0 reason=empty_catalogue");
            return 0;
        }
        if (!OfficerAttach.officerSkillsEnabled()) {
            System.out.println("npeer-serve adhoc_officer_table warmed=0 reason=officer_knob_off"
                    + " catalogue=" + catalogueIds.size());
            return 0;
        }
        long t0 = System.nanoTime();
        Handles h;
        try {
            h = handlesOrNull();
        } catch (Exception e) {
            System.out.println("npeer-serve adhoc_officer_table warmed=0 reason=no_engine_api err="
                    + e);
            return 0;
        }
        if (h == null) {
            System.out.println("npeer-serve adhoc_officer_table warmed=0 reason=game_free");
            return 0;
        }
        Map<String, AdhocOfficerFactory.Pick> picks = new LinkedHashMap<>();
        int automated = 0;
        int ziggurat = 0;
        int human = 0;
        int skipped = 0;
        for (String id : catalogueIds) {
            if (id == null || id.isEmpty() || picks.containsKey(id)) {
                continue;
            }
            try {
                Object variant = h.getVariant.invoke(h.settings, id);
                if (variant == null) {
                    skipped++;
                    continue;
                }
                AdhocOfficerFactory.HullKind kind = hullKindOf(variant, h);
                AdhocOfficerFactory.WeaponMix mix = kind == AdhocOfficerFactory.HullKind.HUMAN
                        ? weaponMixOf(variant, h) : AdhocOfficerFactory.WeaponMix.MIXED;
                AdhocOfficerFactory.AlphaLoadout loadout =
                        kind == AdhocOfficerFactory.HullKind.AUTOMATED
                                ? alphaLoadoutOf(variant, h) : AdhocOfficerFactory.AlphaLoadout.NONE;
                picks.put(id, AdhocOfficerFactory.pick(id, kind, mix, loadout));
                switch (kind) {
                    case AUTOMATED: automated++; break;
                    case ZIGGURAT: ziggurat++; break;
                    default: human++; break;
                }
            } catch (Exception odd) {
                skipped++;
                if (skipped <= 5) {
                    System.out.println("npeer-serve adhoc_officer_table skip id=" + id
                            + " err=" + odd);
                }
            }
        }
        List<OfficerRosterCodec.OfficerSpec> specs = new ArrayList<>(picks.size());
        for (AdhocOfficerFactory.Pick p : picks.values()) {
            specs.add(p.spec());
        }
        // Fail-CLOSED on our own recipes (unknown skill id / a line the wire cannot carry) —
        // the wave-table law; also warms the attach-site skill cache under the row key.
        int checked = OfficerAttach.validateRowSpecs(specs);
        install(picks);
        System.out.println("npeer-serve adhoc_officer_table warmed=" + picks.size()
                + " automated=" + automated + " ziggurat=" + ziggurat + " human=" + human
                + " skipped=" + skipped + " distinct_checked=" + checked
                + " ms=" + ((System.nanoTime() - t0) / 1_000_000L)
                + " (/spawnfoe|/spawnfriend 'officer' picks, boot-warmed)");
        return picks.size();
    }

    /** {@code Misc.isAutomated(variant)} verbatim (hullmod OR variant tag OR hull tag), then the
     *  Ziggurat hull id; everything else is HUMAN. */
    static AdhocOfficerFactory.HullKind hullKindOf(Object variant, Handles h) throws Exception {
        Object hull = h.getHullSpec.invoke(variant);
        boolean automated = Boolean.TRUE.equals(h.hasHullMod.invoke(variant, AUTOMATED))
                || Boolean.TRUE.equals(h.variantHasTag.invoke(variant, AUTOMATED))
                || (hull != null && Boolean.TRUE.equals(h.hullHasTag.invoke(hull, AUTOMATED)));
        if (automated) {
            return AdhocOfficerFactory.HullKind.AUTOMATED;
        }
        Object hullId = hull == null ? null : h.getHullId.invoke(hull);
        if (ZIGGURAT_HULL_ID.equals(hullId)) {
            return AdhocOfficerFactory.HullKind.ZIGGURAT;
        }
        return AdhocOfficerFactory.HullKind.HUMAN;
    }

    /** Fitted-weapon type counts → {@link AdhocOfficerFactory#mixOf}. */
    static AdhocOfficerFactory.WeaponMix weaponMixOf(Object variant, Handles h) throws Exception {
        Object slots = h.getFittedWeaponSlots.invoke(variant);
        int ballistic = 0;
        int energy = 0;
        int missile = 0;
        if (slots instanceof Collection) {
            for (Object slot : (Collection<?>) slots) {
                Object spec = h.getWeaponSpec.invoke(variant, slot);
                if (spec == null) {
                    continue;
                }
                Object type = h.getType.invoke(spec);
                String name = type == null ? "" : String.valueOf(type);
                if ("BALLISTIC".equals(name)) {
                    ballistic++;
                } else if ("ENERGY".equals(name)) {
                    energy++;
                } else if ("MISSILE".equals(name)) {
                    missile++;
                }
            }
        }
        return AdhocOfficerFactory.mixOf(ballistic, energy, missile);
    }

    /**
     * The remnant-fleet adapt inputs of {@code variant}, rewritten from the functional spec:
     * the thin engine read in front of the pure {@link #weighLoadout}.
     * Every fitted weapon slot whose spec resolves gives its size name, whether its AI hints hold
     * {@code PD}, whether its type is {@code MISSILE}; the hull spec gives its fighter-bay count,
     * its hull size name and the {@code derelict} tag; the variant gives {@code isStation()}. A
     * null hull spec reads as no bays, no hull size, not derelict. Enum values compared by name
     * (public-interface reflection, no API enum linkage).
     *
     */
    static AdhocOfficerFactory.AlphaLoadout alphaLoadoutOf(Object variant, Handles h)
            throws Exception {
        List<String> sizeNames = new ArrayList<>();
        List<Boolean> pdHinted = new ArrayList<>();
        List<Boolean> missileTyped = new ArrayList<>();
        Object slots = h.getFittedWeaponSlots.invoke(variant);
        if (slots instanceof Collection) {
            for (Object slot : (Collection<?>) slots) {
                Object spec = h.getWeaponSpec.invoke(variant, slot);
                if (spec == null) {
                    continue;
                }
                sizeNames.add(nameOf(h.getSize.invoke(spec)));
                boolean pd = false;
                Object hints = h.getAIHints.invoke(spec);
                if (hints instanceof Collection) {
                    for (Object hint : (Collection<?>) hints) {
                        if ("PD".equals(nameOf(hint))) {
                            pd = true;
                            break;
                        }
                    }
                }
                pdHinted.add(pd);
                missileTyped.add("MISSILE".equals(nameOf(h.getType.invoke(spec))));
            }
        }
        Object hull = h.getHullSpec.invoke(variant);
        int bays = 0;
        String hullSize = "";
        boolean derelict = false;
        if (hull != null) {
            Object bayCount = h.getFighterBays.invoke(hull);
            bays = bayCount instanceof Number ? ((Number) bayCount).intValue() : 0;
            hullSize = nameOf(h.getHullSize.invoke(hull));
            derelict = Boolean.TRUE.equals(h.hullHasTag.invoke(hull, DERELICT_TAG));
        }
        boolean station = Boolean.TRUE.equals(h.variantIsStation.invoke(variant));
        return weighLoadout(sizeNames, pdHinted, missileTyped, bays, hullSize, station, derelict);
    }

    /**
     * The remnant-fleet adapt WEIGHING law, engine-free (public only so the contract rows in
     * {@code cc.starsector.server.tests} can drive it), from the functional spec.
     * The three lists are parallel, one entry per fitted weapon (equal sizes, else
     * {@link IllegalArgumentException}): its size name (weighed by
     * {@link AdhocOfficerFactory.AlphaLoadout#weaponWeight}: SMALL 1, MEDIUM 2, LARGE 4, anything
     * else 1), whether it is PD-hinted (its weight also counts as PD weight), whether it is
     * MISSILE-typed (its weight also counts as missile weight). Each of the hull's
     * {@code fighterBays} (&ge; 0, the hull spec's count) adds
     * {@link AdhocOfficerFactory.AlphaLoadout#DECK_WEIGHT} to BOTH the total and the PD weight.
     * They weigh even with NO fitted weapon (a bays-only carrier is all PD weight); a hull with
     * neither weighs all three 0, so it never takes the PD branch. {@code capitalOrStation} =
     * {@code hullSizeName} {@code CAPITAL_SHIP} OR {@code station}; {@code derelict} passes through.
     *
     */
    public static AdhocOfficerFactory.AlphaLoadout weighLoadout(List<String> sizeNames,
            List<Boolean> pdHinted, List<Boolean> missileTyped, int fighterBays,
            String hullSizeName, boolean station, boolean derelict) {
        int n = sizeNames.size();
        if (pdHinted.size() != n || missileTyped.size() != n) {
            throw new IllegalArgumentException("weighLoadout: parallel lists differ in size ("
                    + n + "/" + pdHinted.size() + "/" + missileTyped.size() + ")");
        }
        boolean capitalOrStation = station || "CAPITAL_SHIP".equals(hullSizeName);
        float weight = 0f;
        float pd = 0f;
        float missile = 0f;
        for (int i = 0; i < n; i++) {
            float w = AdhocOfficerFactory.AlphaLoadout.weaponWeight(sizeNames.get(i));
            weight += w;
            if (Boolean.TRUE.equals(pdHinted.get(i))) {
                pd += w;
            }
            if (Boolean.TRUE.equals(missileTyped.get(i))) {
                missile += w;
            }
        }
        float decks = fighterBays * AdhocOfficerFactory.AlphaLoadout.DECK_WEIGHT;
        return new AdhocOfficerFactory.AlphaLoadout(weight + decks, pd + decks, missile,
                capitalOrStation, derelict);
    }

    /** An enum constant's {@code name()} (a reflected API enum), else its string form; "" for
     *  null. */
    private static String nameOf(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    /** Public-interface handles ({@code SettingsAPI.getVariant} + the variant/hull/weapon-spec
     *  reads), or null when {@code Global.getSettings()} is null (game-free). Throws when the
     *  API classes are absent (no starfarer.api jar). */
    private static Handles handlesOrNull() throws Exception {
        ClassLoader loader = AdhocOfficerTable.class.getClassLoader();
        Object settings = Class.forName(GLOBAL_CLASS, true, loader)
                .getMethod("getSettings").invoke(null);
        if (settings == null) {
            return null;
        }
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> variantApi = Class.forName(VARIANT_API, false, loader);
        Class<?> hullApi = Class.forName(HULL_API, false, loader);
        Class<?> weaponSpecApi = Class.forName(WEAPON_SPEC_API, false, loader);
        Handles h = new Handles();
        h.settings = settings;
        h.getVariant = settingsApi.getMethod("getVariant", String.class);
        h.hasHullMod = variantApi.getMethod("hasHullMod", String.class);
        h.variantHasTag = variantApi.getMethod("hasTag", String.class);
        h.getHullSpec = variantApi.getMethod("getHullSpec");
        h.getFittedWeaponSlots = variantApi.getMethod("getFittedWeaponSlots");
        h.getWeaponSpec = variantApi.getMethod("getWeaponSpec", String.class);
        h.hullHasTag = hullApi.getMethod("hasTag", String.class);
        h.getHullId = hullApi.getMethod("getHullId");
        h.getType = weaponSpecApi.getMethod("getType");
        h.getSize = weaponSpecApi.getMethod("getSize");
        h.getAIHints = weaponSpecApi.getMethod("getAIHints");
        h.getFighterBays = hullApi.getMethod("getFighterBays");
        h.getHullSize = hullApi.getMethod("getHullSize");
        h.variantIsStation = variantApi.getMethod("isStation");
        return h;
    }

    static final class Handles {
        Object settings;
        Method getVariant;
        Method hasHullMod;
        Method variantHasTag;
        Method getHullSpec;
        Method getFittedWeaponSlots;
        Method getWeaponSpec;
        Method hullHasTag;
        Method getHullId;
        Method getType;
        Method getSize;          // WeaponSpecAPI.getSize() → WeaponSize
        Method getAIHints;       // WeaponSpecAPI.getAIHints() → EnumSet<AIHints>
        Method getFighterBays;   // ShipHullSpecAPI.getFighterBays()
        Method getHullSize;      // ShipHullSpecAPI.getHullSize() → HullSize
        Method variantIsStation; // ShipVariantAPI.isStation()
    }
}
