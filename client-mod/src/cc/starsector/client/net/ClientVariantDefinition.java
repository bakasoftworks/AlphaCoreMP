package cc.starsector.client.net;

import java.util.List;
import java.util.Objects;

/**
 * The decoded payload of one {@code VDEF} variant-definition control frame:
 * one server-delivered ship-variant definition the
 * client folds into the engine's own spec store so RAPP rows / respawn re-points can name
 * variants that ship with NO kit-local {@code .variant} file. ONE variant per frame (CTRL has
 * no cross-datagram reassembly). The admission burst delivers the server's whole boot catalog
 * BEFORE any RAPP replay row can name a wire-only variant.
 *
 * <p>Like {@link ClientRosterAppend}, a small client-owned mirror of the server protocol
 * class ({@code cc.starsector.server.protocol.VariantCatalogControlMessage}); every invariant
 * here mirrors the server codec's {@code VariantDefinitionFrameCodec} records verbatim so a
 * malformed frame fails closed identically ({@link ClientVariantDefinitionReader} translates
 * to {@link java.io.IOException}). Weapons and modules are ORDERED (slotId, id) pairs — the
 * D3 order law: the fold re-inserts them in wire order so both ends' HashMap-backed
 * JSONObjects iterate identically (ALTERNATING fire order is combat-observable).
 */
public record ClientVariantDefinition(
        String variantId,
        String hullId,
        String displayName,
        boolean goalVariant,
        int fluxVents,
        int fluxCapacitors,
        List<String> hullMods,
        List<String> permaMods,
        List<String> sMods,
        List<String> sModdedBuiltIns,
        List<String> suppressedMods,
        List<String> wings,
        List<String> tags,
        List<WeaponGroup> weaponGroups,
        List<ModuleMount> modules) {

    /** D5 id law: 1..64 chars, printable ASCII 0x21..0x7e (no spaces). */
    public static final int ID_MAX_CHARS = 64;
    /** D5 display-name law: 1..48 chars, single-line printable ASCII (interior spaces OK). */
    public static final int DISPLAY_NAME_MAX_CHARS = 48;
    /** Slot-id law: 1..32 chars, printable ASCII, interior spaces OK (vanilla "WS 001"). */
    public static final int SLOT_ID_MAX_CHARS = 32;

    /** D5 sublist caps — mirror {@code VariantDefinitionFrameCodec} verbatim. */
    public static final int MAX_HULL_MODS = 32;
    public static final int MAX_PERMA_MODS = 16;
    public static final int MAX_S_MODS = 16;
    public static final int MAX_S_MODDED_BUILT_INS = 16;
    public static final int MAX_SUPPRESSED_MODS = 16;
    public static final int MAX_WINGS = 8;
    public static final int MAX_TAGS = 16;
    /** The engine's own hard cap. */
    public static final int MAX_WEAPON_GROUPS = 7;
    public static final int MAX_WEAPONS_PER_GROUP = 32;
    public static final int MAX_MODULES = 32;
    /** Flux vents/capacitors sanity bound (vanilla well under; OP-derived). */
    public static final int MAX_FLUX_STAT = 10000;

    public ClientVariantDefinition {
        requireId(variantId, "variantId");
        requireId(hullId, "hullId");
        requireDisplayName(displayName);
        requireFluxStat(fluxVents, "fluxVents");
        requireFluxStat(fluxCapacitors, "fluxCapacitors");
        hullMods = requireIdList(hullMods, MAX_HULL_MODS, "hullMods");
        permaMods = requireIdList(permaMods, MAX_PERMA_MODS, "permaMods");
        sMods = requireIdList(sMods, MAX_S_MODS, "sMods");
        sModdedBuiltIns = requireIdList(sModdedBuiltIns, MAX_S_MODDED_BUILT_INS,
                "sModdedBuiltIns");
        suppressedMods = requireIdList(suppressedMods, MAX_SUPPRESSED_MODS, "suppressedMods");
        wings = requireIdList(wings, MAX_WINGS, "wings");
        tags = requireIdList(tags, MAX_TAGS, "tags");
        Objects.requireNonNull(weaponGroups, "weaponGroups");
        if (weaponGroups.size() > MAX_WEAPON_GROUPS) {
            throw new IllegalArgumentException("weaponGroups must be 0.." + MAX_WEAPON_GROUPS
                    + ", got " + weaponGroups.size());
        }
        weaponGroups = List.copyOf(weaponGroups);
        Objects.requireNonNull(modules, "modules");
        if (modules.size() > MAX_MODULES) {
            throw new IllegalArgumentException("modules must be 0.." + MAX_MODULES
                    + ", got " + modules.size());
        }
        modules = List.copyOf(modules);
    }

    /** One (slotId, weaponId) pair; list order in the group IS the ALTERNATING fire order. */
    public record WeaponMount(String slotId, String weaponId) {
        public WeaponMount {
            requireSlotId(slotId, "weapon slotId");
            requireId(weaponId, "weaponId");
        }
    }

    /** One weapon group: LINKED unless {@code alternating}; order in the list = group number. */
    public record WeaponGroup(boolean autofire, boolean alternating, List<WeaponMount> weapons) {
        public WeaponGroup {
            Objects.requireNonNull(weapons, "weapons");
            if (weapons.isEmpty() || weapons.size() > MAX_WEAPONS_PER_GROUP) {
                throw new IllegalArgumentException("weapon group weapons must be 1.."
                        + MAX_WEAPONS_PER_GROUP + ", got " + weapons.size());
            }
            weapons = List.copyOf(weapons);
        }
    }

    /** One (slotId, moduleVariantId) station-module pair, ORDERED like weapons. */
    public record ModuleMount(String slotId, String moduleVariantId) {
        public ModuleMount {
            requireSlotId(slotId, "module slotId");
            requireId(moduleVariantId, "moduleVariantId");
        }
    }

    static boolean isIdLawful(String id) {
        if (id.isEmpty() || id.length() > ID_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static void requireId(String id, String what) {
        Objects.requireNonNull(id, what);
        if (!isIdLawful(id)) {
            throw new IllegalArgumentException(what + " violates the id law (1.." + ID_MAX_CHARS
                    + " printable ASCII, no spaces): '" + id + "'");
        }
    }

    /** Interior spaces allowed (vanilla slot ids are "WS 001"-form), never blank/edges. */
    private static void requireSlotId(String slotId, String what) {
        Objects.requireNonNull(slotId, what);
        boolean lawful = !slotId.isEmpty() && slotId.length() <= SLOT_ID_MAX_CHARS
                && !slotId.startsWith(" ") && !slotId.endsWith(" ");
        if (lawful) {
            for (int i = 0; i < slotId.length(); i++) {
                char c = slotId.charAt(i);
                if (c < 0x20 || c > 0x7e) {
                    lawful = false;
                    break;
                }
            }
        }
        if (!lawful) {
            throw new IllegalArgumentException(what + " violates the slot-id law (1.."
                    + SLOT_ID_MAX_CHARS + " printable ASCII, no edge spaces): '" + slotId + "'");
        }
    }

    private static void requireDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        boolean lawful = !displayName.isBlank()
                && displayName.length() <= DISPLAY_NAME_MAX_CHARS;
        if (lawful) {
            for (int i = 0; i < displayName.length(); i++) {
                char c = displayName.charAt(i);
                if (c < 0x20 || c > 0x7e) {
                    lawful = false;
                    break;
                }
            }
        }
        if (!lawful) {
            throw new IllegalArgumentException("displayName violates the single-line law (1.."
                    + DISPLAY_NAME_MAX_CHARS + " printable ASCII): '" + displayName + "'");
        }
    }

    private static void requireFluxStat(int value, String what) {
        if (value < 0 || value > MAX_FLUX_STAT) {
            throw new IllegalArgumentException(what + " outside [0," + MAX_FLUX_STAT + "]: "
                    + value);
        }
    }

    private static List<String> requireIdList(List<String> values, int maxCount, String what) {
        Objects.requireNonNull(values, what);
        if (values.size() > maxCount) {
            throw new IllegalArgumentException(what + " must be 0.." + maxCount + ", got "
                    + values.size());
        }
        for (String value : values) {
            requireId(value, what + " entry");
        }
        return List.copyOf(values);
    }
}
