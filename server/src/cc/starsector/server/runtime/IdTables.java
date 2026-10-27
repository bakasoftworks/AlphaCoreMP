package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The boot-derived DICTIONARY the VSUB / VDEF body encodes
 * game-data ids against — four kind-separated u16 tables (hull, weapon, hullmod, wing = the FULL
 * loaded universe, ordinal String order, never a tier subset) plus each hull's slot order (a
 * per-hull u16 index into {@code getAllWeaponSlotsCopy()} order, EVERY slot) and its built-in
 * (slot → weapon) pairs (the reserved weapon index {@link #BUILT_IN_WEAPON_INDEX} resolves through
 * them). Both trees derive the same table from the same engine enumerators; the digest
 * ({@link #vtab()}, {@link ContentFingerprints#variantTableMd5}) is folded into the HELLO
 * content-fingerprint's {@code data=} component so mod-set / game-version drift is a refusal AT
 * THE DOOR ({@code content_fingerprint_mismatch}), never a silent mis-decode.
 *
 * <p>Immutable and pure ({@code java.util} + {@code java.io.IOException} + the bundled
 * {@link ContentFingerprints} only) BUNDLED into the client mod jar by
 * {@code client-mod/build.ps1} ({@code $pilotClasses}): ONE class in both trees, never a mirror;
 * nothing server-only may EVER be referenced from here (classloader B name-denies
 * {@code java.io.File} / {@code java.nio.file.*} and any server-only ref in the constant pool).
 * Mint-time asserts: a table over the u16
 * domain is a LOUD boot failure, never a silent wrap. The decoded {@code VariantDefinition} keeps
 * String ids — the table is a decode-time resolve, not a model change; {@link #idAt} /
 * {@link #slotAt} throw {@link IOException} on an out-of-range index (the UP-fuzz law: the
 * dispatch site's ONE caught class ⇒ session kill).
 *
 * <p>The ONE boot singleton ({@link #install}) is minted by {@code PilotIntentCombatServerMain}
 * BEFORE the catalog scan and reached by the callers with no natural injection
 * point (the door's decode, the lane wiring, the synthetic client); everything else takes the
 * table as an explicit parameter.
 */
public final class IdTables {

    /** The four wire-indexed id kinds, in digest order. */
    public enum Kind {
        HULL("hull"), WEAPON("weapon"), HULLMOD("hullmod"), WING("wing");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** The RESERVED weapon index: "the hull's own built-in weapon on this slot". */
    public static final int BUILT_IN_WEAPON_INDEX = 0xFFFF;
    /** Weapon indices are 0..0xFFFE (0xFFFF reserved): the table holds fewer than 0xFFFF ids. */
    public static final int MAX_WEAPON_IDS = 0xFFFF;
    /** Every other kind: indices 0..0xFFFF. */
    public static final int MAX_IDS_PER_KIND = 0x10000;
    /** Slot indices are per hull, u16. */
    public static final int MAX_SLOTS_PER_HULL = 0x10000;

    private static volatile IdTables installed;

    private final Map<Kind, List<String>> ids;
    private final Map<Kind, Map<String, Integer>> index;
    private final Map<String, List<String>> slotOrderByHull;
    private final Map<String, Map<String, Integer>> slotIndexByHull;
    private final Map<String, Map<String, String>> builtInWeaponsByHull;
    private final String vtab;

    private IdTables(Map<Kind, List<String>> ids, Map<String, List<String>> slotOrderByHull,
            Map<String, Map<String, String>> builtInWeaponsByHull) {
        this.ids = ids;
        Map<Kind, Map<String, Integer>> idx = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            List<String> list = ids.get(kind);
            Map<String, Integer> m = new HashMap<>(list.size() * 2);
            for (int i = 0; i < list.size(); i++) {
                m.put(list.get(i), i);
            }
            idx.put(kind, Collections.unmodifiableMap(m));
        }
        this.index = Collections.unmodifiableMap(idx);
        this.slotOrderByHull = slotOrderByHull;
        Map<String, Map<String, Integer>> slotIdx = new HashMap<>(slotOrderByHull.size() * 2);
        for (Map.Entry<String, List<String>> e : slotOrderByHull.entrySet()) {
            Map<String, Integer> m = new HashMap<>(e.getValue().size() * 2);
            for (int i = 0; i < e.getValue().size(); i++) {
                m.putIfAbsent(e.getValue().get(i), i); // first occurrence wins (an engine dup slot id)
            }
            slotIdx.put(e.getKey(), Collections.unmodifiableMap(m));
        }
        this.slotIndexByHull = Collections.unmodifiableMap(slotIdx);
        this.builtInWeaponsByHull = builtInWeaponsByHull;
        this.vtab = ContentFingerprints.variantTableMd5(ids.get(Kind.HULL), ids.get(Kind.WEAPON),
                ids.get(Kind.HULLMOD), ids.get(Kind.WING), slotOrderByHull, builtInWeaponsByHull);
    }

    /**
     * Build from raw id collections (any iteration order — sorted here; a duplicate id THROWS) plus
     * each hull's slot order (kept in the GIVEN order = the engine's definition order) and built-in
     * pairs. Hulls missing from the slot/built-in maps have none. Mint-time size asserts.
     */
    public static IdTables of(Collection<String> hulls, Collection<String> weapons,
            Collection<String> hullMods, Collection<String> wings,
            Map<String, ? extends List<String>> slotOrderByHull,
            Map<String, ? extends Map<String, String>> builtInWeaponsByHull) {
        Map<Kind, List<String>> ids = new EnumMap<>(Kind.class);
        ids.put(Kind.HULL, sorted(Kind.HULL, hulls, MAX_IDS_PER_KIND));
        ids.put(Kind.WEAPON, sorted(Kind.WEAPON, weapons, MAX_WEAPON_IDS));
        ids.put(Kind.HULLMOD, sorted(Kind.HULLMOD, hullMods, MAX_IDS_PER_KIND));
        ids.put(Kind.WING, sorted(Kind.WING, wings, MAX_IDS_PER_KIND));
        Objects.requireNonNull(slotOrderByHull, "slotOrderByHull");
        Objects.requireNonNull(builtInWeaponsByHull, "builtInWeaponsByHull");
        Map<String, List<String>> slots = new LinkedHashMap<>();
        for (String hull : ids.get(Kind.HULL)) {
            List<String> order = slotOrderByHull.get(hull);
            if (order == null || order.isEmpty()) {
                continue;
            }
            if (order.size() > MAX_SLOTS_PER_HULL) {
                throw new IllegalArgumentException("id-tables: hull '" + hull + "' declares "
                        + order.size() + " slots — over the u16 slot index domain");
            }
            for (String slot : order) {
                if (slot == null || slot.isEmpty()) {
                    throw new IllegalArgumentException("id-tables: hull '" + hull
                            + "' carries a null/empty slot id");
                }
            }
            slots.put(hull, List.copyOf(order));
        }
        for (String hull : slotOrderByHull.keySet()) {
            if (!ids.get(Kind.HULL).contains(hull)) {
                throw new IllegalArgumentException("id-tables: slot order for unknown hull '" + hull + "'");
            }
        }
        Map<String, Map<String, String>> builtIns = new LinkedHashMap<>();
        for (String hull : ids.get(Kind.HULL)) {
            Map<String, String> pairs = builtInWeaponsByHull.get(hull);
            if (pairs == null || pairs.isEmpty()) {
                continue;
            }
            TreeMap<String, String> copy = new TreeMap<>();
            for (Map.Entry<String, String> e : pairs.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue().isEmpty()) {
                    throw new IllegalArgumentException("id-tables: hull '" + hull
                            + "' carries a null/empty built-in pair");
                }
                copy.put(e.getKey(), e.getValue());
            }
            builtIns.put(hull, Collections.unmodifiableMap(copy));
        }
        for (String hull : builtInWeaponsByHull.keySet()) {
            if (!ids.get(Kind.HULL).contains(hull)) {
                throw new IllegalArgumentException("id-tables: built-ins for unknown hull '" + hull + "'");
            }
        }
        return new IdTables(Collections.unmodifiableMap(ids), Collections.unmodifiableMap(slots),
                Collections.unmodifiableMap(builtIns));
    }

    // fromCatalog(VariantPolicy.Catalog) moved to
    // VariantPolicy.Catalog.idTables() so this class carries NO server-only ref (classloader B).

    /** No ids at all (an engine-less boot): every index is out of range, the digest is still defined. */
    public static IdTables empty() {
        return of(List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ lookups

    public List<String> ids(Kind kind) {
        return ids.get(kind);
    }

    public int size(Kind kind) {
        return ids.get(kind).size();
    }

    /** The u16 index of {@code id} in the kind's table, or -1 when the loaded game does not know it. */
    public int indexOf(Kind kind, String id) {
        Integer i = index.get(kind).get(id);
        return i == null ? -1 : i;
    }

    /** Resolve a wire index; an out-of-range index is an {@link IOException} (fail-closed decode). */
    public String idAt(Kind kind, int wireIndex) throws IOException {
        List<String> list = ids.get(kind);
        if (wireIndex < 0 || wireIndex >= list.size()) {
            throw new IOException("variant-definition " + kind.label + " index " + wireIndex
                    + " outside the id table [0," + list.size() + ") (the sender's game data"
                    + " differs from this server's, or the frame is corrupt)");
        }
        return list.get(wireIndex);
    }

    /** The hull's every slot id in engine definition order; empty for an unknown hull. */
    public List<String> slotOrder(String hullId) {
        List<String> order = slotOrderByHull.get(hullId);
        return order == null ? List.of() : order;
    }

    public Map<String, List<String>> slotOrderByHull() {
        return slotOrderByHull;
    }

    /** The per-hull u16 slot index, or -1 when the hull has no such slot. */
    public int slotIndex(String hullId, String slotId) {
        Map<String, Integer> m = slotIndexByHull.get(hullId);
        Integer i = m == null ? null : m.get(slotId);
        return i == null ? -1 : i;
    }

    public String slotAt(String hullId, int wireIndex) throws IOException {
        List<String> order = slotOrder(hullId);
        if (wireIndex < 0 || wireIndex >= order.size()) {
            throw new IOException("variant-definition slot index " + wireIndex + " outside hull '"
                    + hullId + "' slot order [0," + order.size() + ")");
        }
        return order.get(wireIndex);
    }

    /** The hull's built-in weapon on {@code slotId}, or null (the slot is not a built-in mount). */
    public String builtInWeaponOrNull(String hullId, String slotId) {
        Map<String, String> pairs = builtInWeaponsByHull.get(hullId);
        return pairs == null ? null : pairs.get(slotId);
    }

    public Map<String, Map<String, String>> builtInWeaponsByHull() {
        return builtInWeaponsByHull;
    }

    /** The digest folded into the HELLO {@code data=} component (32 lowercase hex). */
    public String vtab() {
        return vtab;
    }

    /** One boot line's worth of census. */
    public String census() {
        return "hulls=" + size(Kind.HULL) + " weapons=" + size(Kind.WEAPON) + " hullmods="
                + size(Kind.HULLMOD) + " wings=" + size(Kind.WING) + " slot_hulls=" + slotOrderByHull.size()
                + " builtin_hulls=" + builtInWeaponsByHull.size() + " vtab=" + vtab;
    }

    // ------------------------------------------------------------------ the boot singleton

    /**
     * Install the boot table. Idempotent for the SAME table (equal {@link #vtab}); a DIFFERENT table
     * over an installed one THROWS — two universes in one JVM is a wiring bug, never a silent swap.
     */
    public static void install(IdTables tables) {
        Objects.requireNonNull(tables, "tables");
        IdTables current = installed;
        if (current != null && !current.vtab.equals(tables.vtab)) {
            throw new IllegalStateException("id-tables already installed with vtab=" + current.vtab
                    + "; refusing a different table (vtab=" + tables.vtab + ")");
        }
        installed = tables;
    }

    /** The installed table; THROWS when none — the Main mint runs before any codec use. */
    public static IdTables installed() {
        IdTables current = installed;
        if (current == null) {
            throw new IllegalStateException("id-tables not installed — PilotIntentCombatServerMain mints"
                    + " them from the engine snapshot before the catalog scan");
        }
        return current;
    }

    public static IdTables installedOrNull() {
        return installed;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> sorted(Kind kind, Collection<String> raw, int max) {
        Objects.requireNonNull(raw, kind.label + " ids");
        TreeSet<String> set = new TreeSet<>();
        for (String id : raw) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("id-tables: " + kind.label + " table carries a null/empty id");
            }
            if (!set.add(id)) {
                throw new IllegalArgumentException("id-tables: duplicate " + kind.label + " id '" + id + "'");
            }
        }
        if (set.size() >= max + (kind == Kind.WEAPON ? 0 : 1)) {
            throw new IllegalArgumentException("id-tables: " + kind.label + " table has " + set.size()
                    + " ids — over the u16 wire index domain (a loud boot failure,"
                    + " never a silent wrap)");
        }
        return List.copyOf(new ArrayList<>(set));
    }
}
