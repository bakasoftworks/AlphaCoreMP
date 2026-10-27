package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.VariantCatalogControlMessage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code VDEF} variant-definition control frame
 * ({@link VariantCatalogControlMessage}).
 * Envelope mirrors {@link RosterAppendFrameCodec} byte-for-byte — {@code magic | schema |
 * headerBytes | wireTypeCode | bodyLen | body | CRC32} — registry-checked and fail-closed:
 * magic / schema / wire-identity / declared-size mismatch and CRC32 corruption all throw
 * {@link IOException}.
 *
 * <p>Body v2 carries ONE variant per frame (CTRL has no
 * cross-datagram reassembly — never a multi-frame blob): the W6 exporter's key set plus
 * {@code sModdedBuiltIns} / {@code suppressedMods} / {@code tags} (D6; every value
 * int/boolean/string — zero float loss). GAME-DATA ids ride as u16 indices into the boot
 * {@link IdTables} both trees derive from the loaded game (hull, weapon, hullmod, wing; slot = a
 * per-hull index into the hull's slot order); a built-in mount's weapon is the RESERVED
 * {@link IdTables#BUILT_IN_WEAPON_INDEX} whenever the weapon equals the hull's own built-in on
 * that slot (checked FIRST — the client writer mirrors the order). Free text stays u8-length
 * printable-ASCII strings: {@code variantId} (client-local handle), {@code displayName},
 * {@code tags}, {@code moduleVariantId} (a variant id, possibly a live custom — never indexed).
 * u8 counts, u16 flux. Weapons and modules are ORDERED (slot, id) pairs — the D3 order law: the
 * server encoder walks its parsed JSONObject's {@code getNames()} order and the client fold
 * re-inserts in wire order, so both ends' HashMap-backed JSONObjects iterate identically by
 * construction (ALTERNATING fire order is combat-observable). The decoded record keeps String
 * ids (policy / registrar / ledger / persist / ops GUI unchanged); an index outside the table is
 * an {@link IOException}. Schema 1 (string ids) is refused explicitly.
 *
 * <p>Security: every string is length-prefixed and law-checked
 * (ids printable-no-space ≤{@link #ID_MAX_CHARS}; displayName single-line printable
 * ≤{@link #DISPLAY_NAME_MAX_CHARS}; slot ids interior-space-allowed ≤{@link #SLOT_ID_MAX_CHARS});
 * every list is u8-capped; weapon groups cap at the engine's own {@link #MAX_WEAPON_GROUPS}.
 * Allocation is bounded by the declared body size, itself bounded by the frame budget.
 * A whole variant that cannot fit one frame is a SERVER-side loud encode refusal naming the
 * variant (a config error, never a truncated frame). UNCOMPRESSED by design (stays out of the
 * native-zlib residual).
 */
public final class VariantDefinitionFrameCodec {

    /** Frame magic: {@code "VDEF"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x56444546; // "VDEF"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverVariantCatalogWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    /** The datagram payload budget the whole frame must fit (D2). */
    public static final int MAX_FRAME_BYTES = 1164;
    private static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - Integer.BYTES;

    /** D5 id law: 1..64 chars, printable ASCII 0x21..0x7e (no spaces). */
    public static final int ID_MAX_CHARS = 64;
    /** D5 display-name law: 1..48 chars, single-line printable ASCII (interior spaces OK). */
    public static final int DISPLAY_NAME_MAX_CHARS = 48;
    /** Slot-id law: 1..32 chars, printable ASCII, interior spaces OK (vanilla {@code "WS 001"}). */
    public static final int SLOT_ID_MAX_CHARS = 32;

    /** D5 sublist caps (L6 vanilla-grounded bounds with mod headroom). */
    public static final int MAX_HULL_MODS = 32;
    public static final int MAX_PERMA_MODS = 16;
    public static final int MAX_S_MODS = 16;
    public static final int MAX_S_MODDED_BUILT_INS = 16;
    public static final int MAX_SUPPRESSED_MODS = 16;
    public static final int MAX_WINGS = 8;
    public static final int MAX_TAGS = 16;
    /** The engine's own hard cap, in HullVariantSpec. */
    public static final int MAX_WEAPON_GROUPS = 7;
    public static final int MAX_WEAPONS_PER_GROUP = 32;
    public static final int MAX_MODULES = 32;
    /** Flux vents/capacitors sanity bound (vanilla well under; OP-derived). */
    public static final int MAX_FLUX_STAT = 10000;

    /** D5 BOOT-catalog cap: the kit {@code .variant} files the server store scans at boot
     * ({@code VariantCatalogStore}), a bound the promotion lane's disk input shares
     * ({@code CatalogPromotionLane.REGISTERED_CAP}, pinned separately). The client fold has NO cap
     * of its own — it holds whatever the budget below admits. */
    public static final int MAX_VARIANTS_TOTAL = 256;
    /**
     * ONE admission-burst budget every VDEF producer sums under. A late
     * joiner's catch-up spools boot frames (≤ {@link #MAX_VARIANTS_TOTAL}) + accepted customs
     * (≤ {@code SubmittedVariantLedger.GLOBAL_CAP} 64) + live wave bodies
     * (≤ {@code WaveLoadoutInflater.REGISTER_CAP}) back-to-back into ONE CTRL spool whose death
     * bound is the reliability window + {@code PilotIntentDatagramServer.CTRL_SPOOL_BOUND} (512 + 512
     * = 1024 frames, then {@code ctrl_window_stall} kills the session); this budget leaves
     * {@link #VDEF_CTRL_MARGIN} frames of that spool to the other admission traffic (PSTB, RAPP,
     * seat baseline). The three producers' caps are asserted to sum under it
     * ({@code WaveInflateContractRows.budgetRows}) and the client queue derives from it
     * ({@link #VDEF_CLIENT_QUEUE_CAP}, mirrored by {@code CcClientVdefVerifier}).
     */
    public static final int VDEF_BUDGET_TOTAL = 960;
    /** The CTRL frames of the admission spool this budget does NOT claim (the 1024 death bound minus
     * {@link #VDEF_BUDGET_TOTAL}). */
    public static final int VDEF_CTRL_MARGIN = 64;
    /** The client's VDEF queue depth (the whole budget + margin for a re-admission burst landing before
     * the plugin's next fold); {@code PredictReconcileDatagramSession.VARIANT_DEFINITION_QUEUE_CAP}
     * carries this number as a literal (no shared class across the trees) — the verifier pins it. */
    public static final int VDEF_CLIENT_QUEUE_CAP = VDEF_BUDGET_TOTAL + 64;

    /** Frame flags. */
    private static final int FLAG_GOAL_VARIANT = 0x01;
    /**
     * Frame flag bit 1 = KEEP-ONLY, a submission intent: the host keeps
     * this build on {@code /ship} for everyone WITHOUT it becoming the submitter's own next respawn. WHY a flag bit:
     * the byte had seven must-be-zero bits, so an older kit's frame (no bit) still decodes and flies as before. It is
     * never a property of the kept ship: the minted copy is rebuilt without it, so no fanned frame carries it.
     */
    public static final int FLAG_KEEP_ONLY = 0x02;
    /** Weapon-group flags. */
    private static final int GROUP_FLAG_AUTOFIRE = 0x01;
    private static final int GROUP_FLAG_ALTERNATING = 0x02;

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

    /**
     * One wire-deliverable variant definition (D6 schema). Validation is by construction
     * (encode) and re-checked on decode (a wire body that fails it is an {@link IOException}).
     * {@code keepOnly} = the {@link #FLAG_KEEP_ONLY} bit, false unless a
     * decoded submit carried it. Invariant: only a definition decoded from a submit holds it true; the
     * 15-argument constructor (every builder, and the ledger's id swap that makes the stored copy) sets it
     * false, so the stored ship, its body CRC and its identity never depend on it.
     */
    public record VariantDefinition(
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
            List<ModuleMount> modules,
            boolean keepOnly) {
        /** The pre-flag shape — {@code keepOnly} false. */
        public VariantDefinition(String variantId, String hullId, String displayName, boolean goalVariant,
                int fluxVents, int fluxCapacitors, List<String> hullMods, List<String> permaMods, List<String> sMods,
                List<String> sModdedBuiltIns, List<String> suppressedMods, List<String> wings, List<String> tags,
                List<WeaponGroup> weaponGroups, List<ModuleMount> modules) {
            this(variantId, hullId, displayName, goalVariant, fluxVents, fluxCapacitors, hullMods, permaMods, sMods,
                    sModdedBuiltIns, suppressedMods, wings, tags, weaponGroups, modules, false);
        }

        public VariantDefinition {
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
    }

    private VariantDefinitionFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one VDEF frame (exactly ONE variant) to its full wire bytes. */
    public static byte[] encode(VariantDefinition variant, IdTables tables) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, variant, tables);
        return out.toByteArray();
    }

    /** Write one VDEF control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, VariantDefinition variant, IdTables tables)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(variant, "variant");
        byte[] body = encodeBody(variant, tables);
        if (body.length > MAX_BODY_BYTES) {
            // The assertFitsOrExplain idiom: a variant too big for one CTRL frame is a server
            // config error surfaced loudly at boot/encode — never a truncated or multi-frame blob.
            throw new IOException("variant-definition frame body " + body.length
                    + " B exceeds the wire budget (" + MAX_BODY_BYTES + " B body cap)"
                    + " for variant '" + variant.variantId() + "' — trim the variant or keep it"
                    + " kit-local");
        }
        long checksum = checksum(body);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(SCHEMA_VERSION);
        data.writeShort(HEADER_BYTES);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) checksum);
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    /**
     * Read one VDEF control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, declared body size, CRC32, and every field
     * law; throws {@link IOException} on any mismatch.
     */
    public static VariantDefinition readFrameAfterMagic(DataInputStream data, IdTables tables)
            throws IOException {
        return decodeBody(readRawBodyAfterMagic(data), tables);
    }

    /**
     * The ENVELOPE half of {@link #readFrameAfterMagic}: schema, header size, wire identity,
     * declared body size and CRC32 validated, the body bytes returned UNDECODED. For walkers that
     * only need to advance past a VDEF frame (the recordings tools' {@code DownFrameFamily}, the
     * synthetic client's admission spool) — they have no id table and never need the resolved ids.
     */
    public static byte[] readRawBodyAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("variant-definition frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("variant-definition frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("variant-definition frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("variant-definition frame checksum mismatch");
        }
        return body;
    }

    /** The body's FIRST field — the client-local {@code variantId} (a string on the wire, no table
     * needed); the synthetic client's custom-fan census reads it off a raw body. */
    public static String peekVariantId(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        return readString(data, "variantId");
    }

    /**
     * The VDEF BODY bytes alone (no envelope). Package-visible for {@link VariantSubmitFrameCodec}
     * (W15 v0): a {@code VSUB} carries {@code u16 submitSeq} + exactly these bytes, so one
     * body grammar serves both frames and the client's hand-mirror writer has ONE target.
     */
    static byte[] encodeBody(VariantDefinition variant, IdTables tables) throws IOException {
        Objects.requireNonNull(tables, "tables");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        String hullId = variant.hullId();
        writeString(data, variant.variantId());
        writeIndex(data, tables, IdTables.Kind.HULL, hullId, "hullId");
        writeString(data, variant.displayName());
        data.writeByte((variant.goalVariant() ? FLAG_GOAL_VARIANT : 0) | (variant.keepOnly() ? FLAG_KEEP_ONLY : 0));
        data.writeShort(variant.fluxVents());
        data.writeShort(variant.fluxCapacitors());
        writeIndexList(data, tables, IdTables.Kind.HULLMOD, variant.hullMods(), "hullMods");
        writeIndexList(data, tables, IdTables.Kind.HULLMOD, variant.permaMods(), "permaMods");
        writeIndexList(data, tables, IdTables.Kind.HULLMOD, variant.sMods(), "sMods");
        writeIndexList(data, tables, IdTables.Kind.HULLMOD, variant.sModdedBuiltIns(), "sModdedBuiltIns");
        writeIndexList(data, tables, IdTables.Kind.HULLMOD, variant.suppressedMods(), "suppressedMods");
        writeIndexList(data, tables, IdTables.Kind.WING, variant.wings(), "wings");
        writeStringList(data, variant.tags());
        data.writeByte(variant.weaponGroups().size());
        int g = 0;
        for (WeaponGroup group : variant.weaponGroups()) {
            int groupFlags = (group.autofire() ? GROUP_FLAG_AUTOFIRE : 0)
                    | (group.alternating() ? GROUP_FLAG_ALTERNATING : 0);
            data.writeByte(groupFlags);
            data.writeByte(group.weapons().size());
            int w = 0;
            for (WeaponMount mount : group.weapons()) {
                String what = "group " + g + " weapon " + w;
                writeSlotIndex(data, tables, hullId, mount.slotId(), what);
                // The built-in law FIRST (the wire's one reserved index), then the table.
                String builtIn = tables.builtInWeaponOrNull(hullId, mount.slotId());
                if (builtIn != null && builtIn.equals(mount.weaponId())) {
                    data.writeShort(IdTables.BUILT_IN_WEAPON_INDEX);
                } else {
                    writeIndex(data, tables, IdTables.Kind.WEAPON, mount.weaponId(), what + " weaponId");
                }
                w++;
            }
            g++;
        }
        data.writeByte(variant.modules().size());
        int m = 0;
        for (ModuleMount module : variant.modules()) {
            writeSlotIndex(data, tables, hullId, module.slotId(), "module " + m);
            writeString(data, module.moduleVariantId());
            m++;
        }
        data.flush();
        return bytes.toByteArray();
    }

    /** Inverse of {@link #encodeBody}; fail-closed (every law re-checked, trailing bytes refused). */
    static VariantDefinition decodeBody(byte[] body, IdTables tables) throws IOException {
        Objects.requireNonNull(tables, "tables");
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        String variantId = readString(data, "variantId");
        String hullId = tables.idAt(IdTables.Kind.HULL, data.readUnsignedShort());
        String displayName = readString(data, "displayName");
        int flags = data.readUnsignedByte();
        // Bits 0 and 1 are known; bits 2..7 stay must-be-zero.
        if ((flags & ~(FLAG_GOAL_VARIANT | FLAG_KEEP_ONLY)) != 0) {
            throw new IOException("variant-definition frame flags unknown: 0x"
                    + Integer.toHexString(flags));
        }
        boolean goalVariant = (flags & FLAG_GOAL_VARIANT) != 0;
        boolean keepOnly = (flags & FLAG_KEEP_ONLY) != 0;
        int fluxVents = data.readUnsignedShort();
        int fluxCapacitors = data.readUnsignedShort();
        List<String> hullMods = readIndexList(data, tables, IdTables.Kind.HULLMOD, MAX_HULL_MODS, "hullMods");
        List<String> permaMods = readIndexList(data, tables, IdTables.Kind.HULLMOD, MAX_PERMA_MODS, "permaMods");
        List<String> sMods = readIndexList(data, tables, IdTables.Kind.HULLMOD, MAX_S_MODS, "sMods");
        List<String> sModdedBuiltIns = readIndexList(data, tables, IdTables.Kind.HULLMOD,
                MAX_S_MODDED_BUILT_INS, "sModdedBuiltIns");
        List<String> suppressedMods = readIndexList(data, tables, IdTables.Kind.HULLMOD,
                MAX_SUPPRESSED_MODS, "suppressedMods");
        List<String> wings = readIndexList(data, tables, IdTables.Kind.WING, MAX_WINGS, "wings");
        List<String> tags = readStringList(data, MAX_TAGS, "tags");
        int groupCount = data.readUnsignedByte();
        if (groupCount > MAX_WEAPON_GROUPS) {
            throw new IOException("variant-definition frame weaponGroups count invalid: "
                    + groupCount);
        }
        List<WeaponGroup> weaponGroups = new ArrayList<>(groupCount);
        for (int g = 0; g < groupCount; g++) {
            int groupFlags = data.readUnsignedByte();
            if ((groupFlags & ~(GROUP_FLAG_AUTOFIRE | GROUP_FLAG_ALTERNATING)) != 0) {
                throw new IOException("variant-definition frame group " + g
                        + " flags unknown: 0x" + Integer.toHexString(groupFlags));
            }
            int weaponCount = data.readUnsignedByte();
            if (weaponCount < 1 || weaponCount > MAX_WEAPONS_PER_GROUP) {
                throw new IOException("variant-definition frame group " + g
                        + " weapon count invalid: " + weaponCount);
            }
            List<WeaponMount> weapons = new ArrayList<>(weaponCount);
            for (int w = 0; w < weaponCount; w++) {
                String slotId = tables.slotAt(hullId, data.readUnsignedShort());
                int weaponIndex = data.readUnsignedShort();
                String weaponId;
                if (weaponIndex == IdTables.BUILT_IN_WEAPON_INDEX) {
                    weaponId = tables.builtInWeaponOrNull(hullId, slotId);
                    if (weaponId == null) {
                        throw new IOException("variant-definition frame group " + g + " weapon " + w
                                + " carries the reserved built-in index on slot '" + slotId
                                + "' of hull '" + hullId + "', which has no built-in weapon");
                    }
                } else {
                    weaponId = tables.idAt(IdTables.Kind.WEAPON, weaponIndex);
                }
                try {
                    weapons.add(new WeaponMount(slotId, weaponId));
                } catch (IllegalArgumentException badMount) {
                    throw new IOException("variant-definition frame group " + g + " weapon " + w
                            + " invalid", badMount);
                }
            }
            try {
                weaponGroups.add(new WeaponGroup((groupFlags & GROUP_FLAG_AUTOFIRE) != 0,
                        (groupFlags & GROUP_FLAG_ALTERNATING) != 0, weapons));
            } catch (IllegalArgumentException badGroup) {
                throw new IOException("variant-definition frame group " + g + " invalid", badGroup);
            }
        }
        int moduleCount = data.readUnsignedByte();
        if (moduleCount > MAX_MODULES) {
            throw new IOException("variant-definition frame module count invalid: " + moduleCount);
        }
        List<ModuleMount> modules = new ArrayList<>(moduleCount);
        for (int m = 0; m < moduleCount; m++) {
            String slotId = tables.slotAt(hullId, data.readUnsignedShort());
            String moduleVariantId = readString(data, "moduleVariantId");
            try {
                modules.add(new ModuleMount(slotId, moduleVariantId));
            } catch (IllegalArgumentException badModule) {
                throw new IOException("variant-definition frame module " + m + " invalid",
                        badModule);
            }
        }
        if (data.read() != -1) {
            throw new IOException("variant-definition frame body has trailing bytes");
        }
        try {
            return new VariantDefinition(variantId, hullId, displayName, goalVariant, fluxVents,
                    fluxCapacitors, hullMods, permaMods, sMods, sModdedBuiltIns, suppressedMods,
                    wings, tags, weaponGroups, modules, keepOnly);
        } catch (IllegalArgumentException badVariant) {
            throw new IOException("variant-definition frame body invalid: "
                    + badVariant.getMessage(), badVariant);
        }
    }

    /** u8 byte-length + printable-ASCII bytes (laws re-checked by the record on decode). */
    private static void writeString(DataOutputStream data, String value) throws IOException {
        int len = value.length();
        if (len < 1 || len > 255) {
            throw new IOException("variant-definition string length un-encodable: " + len);
        }
        data.writeByte(len);
        for (int i = 0; i < len; i++) {
            data.writeByte(value.charAt(i)); // laws guarantee ASCII: bytes == chars
        }
    }

    private static String readString(DataInputStream data, String what) throws IOException {
        int len = data.readUnsignedByte();
        if (len < 1) {
            throw new IOException("variant-definition " + what + " empty on the wire");
        }
        byte[] raw = new byte[len];
        data.readFully(raw);
        StringBuilder chars = new StringBuilder(len);
        for (byte b : raw) {
            int c = b & 0xff;
            if (c < 0x20 || c > 0x7e) {
                throw new IOException("variant-definition " + what
                        + " carries a non-printable byte: 0x" + Integer.toHexString(c));
            }
            chars.append((char) c);
        }
        return chars.toString();
    }

    private static void writeStringList(DataOutputStream data, List<String> values)
            throws IOException {
        data.writeByte(values.size());
        for (String value : values) {
            writeString(data, value);
        }
    }

    private static List<String> readStringList(DataInputStream data, int maxCount, String what)
            throws IOException {
        int count = data.readUnsignedByte();
        if (count > maxCount) {
            throw new IOException("variant-definition " + what + " count invalid: " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String value = readString(data, what + "[" + i + "]");
            if (!isIdLawful(value)) {
                throw new IOException("variant-definition " + what + "[" + i
                        + "] violates the id law: '" + value + "'");
            }
            values.add(value);
        }
        return values;
    }

    // ------------------------------------------------------------------ index primitives

    /** u16 table index of a game-data id; an id the loaded game does not know cannot ride the wire
     * (an encode-side {@link IOException}: a config error on the server, a stale catalog on the
     * client — never a truncated or guessed frame). */
    private static void writeIndex(DataOutputStream data, IdTables tables, IdTables.Kind kind,
            String id, String what) throws IOException {
        int index = tables.indexOf(kind, id);
        if (index < 0) {
            throw new IOException("variant-definition " + what + " '" + id + "' is not in the "
                    + kind.label + " id table (game-data ids ride as table indices; the loaded"
                    + " game does not know this id)");
        }
        data.writeShort(index);
    }

    private static void writeSlotIndex(DataOutputStream data, IdTables tables, String hullId,
            String slotId, String what) throws IOException {
        int index = tables.slotIndex(hullId, slotId);
        if (index < 0) {
            throw new IOException("variant-definition " + what + " slot '" + slotId + "' is not a slot"
                    + " of hull '" + hullId + "' (slots ride as per-hull indices)");
        }
        data.writeShort(index);
    }

    /** u8 count + u16 indices. */
    private static void writeIndexList(DataOutputStream data, IdTables tables, IdTables.Kind kind,
            List<String> ids, String what) throws IOException {
        data.writeByte(ids.size());
        int i = 0;
        for (String id : ids) {
            writeIndex(data, tables, kind, id, what + "[" + i + "]");
            i++;
        }
    }

    private static List<String> readIndexList(DataInputStream data, IdTables tables, IdTables.Kind kind,
            int maxCount, String what) throws IOException {
        int count = data.readUnsignedByte();
        if (count > maxCount) {
            throw new IOException("variant-definition " + what + " count invalid: " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(tables.idAt(kind, data.readUnsignedShort()));
        }
        return values;
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        if (unsignedSchemaVersion != SCHEMA_VERSION) {
            // The explicit refusal naming the bump — a schema-1 STRING
            // body never reaches the index decoder (the registry check below would also refuse it
            // as unregistered; this line is the second lock and the readable reason).
            throw new IOException("variant-definition frame schema " + unsignedSchemaVersion
                    + " refused: this build speaks schema " + SCHEMA_VERSION
                    + " (dictionary ids) — an older kit's string-id body cannot decode here");
        }
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat()
                    .require(VariantCatalogControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported variant-definition frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("variant-definition frame schema rejected by compatibility"
                    + " policy: " + unsignedSchemaVersion);
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: "
                    + schemaVersion);
        }
        return (short) schemaVersion;
    }

    /** The D5 id law (1..64 printable ASCII, no spaces); package-visible for the VACK codec. */
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

    /** Interior spaces allowed (vanilla slot ids are {@code "WS 001"}-form), never blank/edges. */
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
