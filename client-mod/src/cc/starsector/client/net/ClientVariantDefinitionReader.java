package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import cc.starsector.server.runtime.IdTables;

/**
 * Client-side decoder for the server's {@code VDEF} variant-definition control wire frame:
 * the receiving half of the project's
 * {@code cc.starsector.server.runtime.VariantDefinitionFrameCodec}. Schema 2:
 * game-data ids ride as u16 indices into the boot {@link IdTables} (the
 * SAME class both trees run, bundled into the mod jar) — {@code hullId}, the six id lists, and
 * per mount a per-hull slot index + a weapon index (the reserved
 * {@link IdTables#BUILT_IN_WEAPON_INDEX} = the hull's own built-in on that slot); strings stay
 * strings ({@code variantId}, {@code displayName}, {@code tags}, {@code moduleVariantId}). The
 * decoded record keeps String ids (the fold is unchanged); an index outside the table is an
 * {@link IOException} like every other law (the sender's game data differs, or the frame is
 * corrupt — an armed door already refused such a session at HELLO by the folded fingerprint).
 *
 * <p>The server unicast-bursts its whole boot variant catalog (one frame per variant) to each
 * session once per admission at the seat-name catch-up seam, BEFORE the newly-live RAPP
 * replay — so every RAPP row / respawn re-point naming a wire-only variant finds it already
 * foldable. Emission is gated on the ARMED door, so only current-kit clients ever see one.
 *
 * <p>Like {@link ClientRosterAppendReader}, the mod deliberately does not bundle the server
 * codec (which uses {@code java.io.DataInputStream} — blocked by the mod-script sandbox
 * classloader, see {@link WireBytes}). It reads exactly the bytes the server writes — magic /
 * schema / headerBytes / wireTypeCode / bodyLen / body / CRC32; the body is field-encoded
 * u8-length printable-ASCII strings, u8 counts, u16 flux, ORDERED (slotId, id) pairs — over
 * plain {@link InputStream} primitives plus the allowlisted {@link CRC32}. The server codec's
 * SOURCE is the layout authority; this reader mirrors it field-for-field, and the game-free
 * {@code verify-client-mod-vdef.ps1} verifier asserts both ends against the same frozen
 * live-producer goldens.
 *
 * <p>Fail-closed (D5, DROP-FRAME tier): a wrong magic (when read standalone), an unexpected
 * schema version, a header/body size mismatch, a CRC32 mismatch, a non-printable byte, an
 * unknown flag bit, an over-cap count, a law-violating field, or trailing body bytes all
 * throw {@link IOException}; a clean end-of-stream at a frame boundary returns {@code null}
 * from {@link #readDefinition(InputStream)}. (The SKIP-ENTRY tier — well-formed but
 * engine-unresolvable — lives in the fold, not here.)
 */
public final class ClientVariantDefinitionReader {

    /** Frame magic: ASCII "VDEF". Mirrors {@code VariantDefinitionFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x56444546;

    /** Schema version this reader understands — EXACT (a schema-1 string-id body never reaches the
     *  index decoder). Mirrors {@code VariantCatalogControlMessage.SCHEMA_VERSION} (= 2). */
    public static final int SCHEMA_VERSION = 2;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Whole-frame budget. Mirrors {@code VariantDefinitionFrameCodec.MAX_FRAME_BYTES}. */
    public static final int MAX_FRAME_BYTES = 1164;

    /** The variable body's size bound implied by the frame budget (frame − header − CRC32). */
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - 4;

    /** Frame flags. Mirrors the codec. */
    private static final int FLAG_GOAL_VARIANT = 0x01;
    /** The submission's keep-only ask
     *  ({@link ClientVariantDefinitionWriter#FLAG_KEEP_ONLY}): ACCEPTED here so the one body grammar
     *  stays one grammar both ways, and carries no meaning on a fanned definition (the record has no
     *  such field; only the server's submission lane acts on it). Any other unknown bit still throws. */
    private static final int KNOWN_FRAME_FLAGS = FLAG_GOAL_VARIANT | ClientVariantDefinitionWriter.FLAG_KEEP_ONLY;
    /** Weapon-group flags. Mirrors the codec. */
    private static final int GROUP_FLAG_AUTOFIRE = 0x01;
    private static final int GROUP_FLAG_ALTERNATING = 0x02;

    private ClientVariantDefinitionReader() {
    }

    /**
     * Decode one variant-definition frame from {@code in} against {@code tables} (the boot id
     * table; {@code null} = every body is refused, an {@link IOException}), or return
     * {@code null} at a clean end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientVariantDefinition readDefinition(InputStream in, IdTables tables)
            throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("variant-definition frame magic mismatch: "
                    + Integer.toHexString(magic));
        }
        return readDefinitionAfterMagic(in, tables);
    }

    /**
     * Decode one variant-definition frame whose 4-byte {@link #MAGIC} has <em>already</em>
     * been read and validated by the caller — the demux entry point.
     */
    public static ClientVariantDefinition readDefinitionAfterMagic(InputStream in, IdTables tables)
            throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("variant-definition frame schema " + schema + " refused: this"
                    + " kit speaks schema " + SCHEMA_VERSION + " (dictionary ids)");
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("variant-definition frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("variant-definition frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("variant-definition frame checksum mismatch");
        }
        return decodeBody(body, tables);
    }

    private static ClientVariantDefinition decodeBody(byte[] body, IdTables tables)
            throws IOException {
        if (tables == null) {
            throw new IOException("variant-definition frame refused: no boot id table is installed"
                    + " — the onApplicationLoad mint failed (see the log)");
        }
        int[] cursor = {0};
        String variantId = readString(body, cursor, "variantId");
        String hullId = tables.idAt(IdTables.Kind.HULL, readUnsignedShort(body, cursor, "hullId"));
        String displayName = readString(body, cursor, "displayName");
        int flags = readUnsignedByte(body, cursor, "flags");
        if ((flags & ~KNOWN_FRAME_FLAGS) != 0) {
            throw new IOException("variant-definition frame flags unknown: 0x"
                    + Integer.toHexString(flags));
        }
        boolean goalVariant = (flags & FLAG_GOAL_VARIANT) != 0;
        int fluxVents = readUnsignedShort(body, cursor, "fluxVents");
        int fluxCapacitors = readUnsignedShort(body, cursor, "fluxCapacitors");
        List<String> hullMods = readIndexList(body, cursor, tables, IdTables.Kind.HULLMOD,
                ClientVariantDefinition.MAX_HULL_MODS, "hullMods");
        List<String> permaMods = readIndexList(body, cursor, tables, IdTables.Kind.HULLMOD,
                ClientVariantDefinition.MAX_PERMA_MODS, "permaMods");
        List<String> sMods = readIndexList(body, cursor, tables, IdTables.Kind.HULLMOD,
                ClientVariantDefinition.MAX_S_MODS, "sMods");
        List<String> sModdedBuiltIns = readIndexList(body, cursor, tables, IdTables.Kind.HULLMOD,
                ClientVariantDefinition.MAX_S_MODDED_BUILT_INS, "sModdedBuiltIns");
        List<String> suppressedMods = readIndexList(body, cursor, tables, IdTables.Kind.HULLMOD,
                ClientVariantDefinition.MAX_SUPPRESSED_MODS, "suppressedMods");
        List<String> wings = readIndexList(body, cursor, tables, IdTables.Kind.WING,
                ClientVariantDefinition.MAX_WINGS, "wings");
        List<String> tags = readStringList(body, cursor,
                ClientVariantDefinition.MAX_TAGS, "tags");
        int groupCount = readUnsignedByte(body, cursor, "weaponGroups count");
        if (groupCount > ClientVariantDefinition.MAX_WEAPON_GROUPS) {
            throw new IOException("variant-definition frame weaponGroups count invalid: "
                    + groupCount);
        }
        List<ClientVariantDefinition.WeaponGroup> weaponGroups =
                new ArrayList<ClientVariantDefinition.WeaponGroup>(groupCount);
        for (int g = 0; g < groupCount; g++) {
            int groupFlags = readUnsignedByte(body, cursor, "group flags");
            if ((groupFlags & ~(GROUP_FLAG_AUTOFIRE | GROUP_FLAG_ALTERNATING)) != 0) {
                throw new IOException("variant-definition frame group " + g
                        + " flags unknown: 0x" + Integer.toHexString(groupFlags));
            }
            int weaponCount = readUnsignedByte(body, cursor, "group weapon count");
            if (weaponCount < 1 || weaponCount > ClientVariantDefinition.MAX_WEAPONS_PER_GROUP) {
                throw new IOException("variant-definition frame group " + g
                        + " weapon count invalid: " + weaponCount);
            }
            List<ClientVariantDefinition.WeaponMount> weapons =
                    new ArrayList<ClientVariantDefinition.WeaponMount>(weaponCount);
            for (int w = 0; w < weaponCount; w++) {
                String slotId = tables.slotAt(hullId, readUnsignedShort(body, cursor, "weapon slotId"));
                int weaponIndex = readUnsignedShort(body, cursor, "weaponId");
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
                    weapons.add(new ClientVariantDefinition.WeaponMount(slotId, weaponId));
                } catch (IllegalArgumentException badMount) {
                    throw new IOException("variant-definition frame group " + g + " weapon " + w
                            + " invalid", badMount);
                }
            }
            try {
                weaponGroups.add(new ClientVariantDefinition.WeaponGroup(
                        (groupFlags & GROUP_FLAG_AUTOFIRE) != 0,
                        (groupFlags & GROUP_FLAG_ALTERNATING) != 0, weapons));
            } catch (IllegalArgumentException badGroup) {
                throw new IOException("variant-definition frame group " + g + " invalid",
                        badGroup);
            }
        }
        int moduleCount = readUnsignedByte(body, cursor, "module count");
        if (moduleCount > ClientVariantDefinition.MAX_MODULES) {
            throw new IOException("variant-definition frame module count invalid: " + moduleCount);
        }
        List<ClientVariantDefinition.ModuleMount> modules =
                new ArrayList<ClientVariantDefinition.ModuleMount>(moduleCount);
        for (int m = 0; m < moduleCount; m++) {
            String slotId = tables.slotAt(hullId, readUnsignedShort(body, cursor, "module slotId"));
            String moduleVariantId = readString(body, cursor, "moduleVariantId");
            try {
                modules.add(new ClientVariantDefinition.ModuleMount(slotId, moduleVariantId));
            } catch (IllegalArgumentException badModule) {
                throw new IOException("variant-definition frame module " + m + " invalid",
                        badModule);
            }
        }
        if (cursor[0] != body.length) {
            throw new IOException("variant-definition frame body has trailing bytes");
        }
        try {
            return new ClientVariantDefinition(variantId, hullId, displayName, goalVariant,
                    fluxVents, fluxCapacitors, hullMods, permaMods, sMods, sModdedBuiltIns,
                    suppressedMods, wings, tags, weaponGroups, modules);
        } catch (IllegalArgumentException badVariant) {
            throw new IOException("variant-definition frame body invalid: "
                    + badVariant.getMessage(), badVariant);
        }
    }

    private static int readUnsignedByte(byte[] body, int[] cursor, String what)
            throws IOException {
        if (cursor[0] + 1 > body.length) {
            throw new IOException("variant-definition " + what + " truncated");
        }
        int value = body[cursor[0]] & 0xff;
        cursor[0] += 1;
        return value;
    }

    private static int readUnsignedShort(byte[] body, int[] cursor, String what)
            throws IOException {
        if (cursor[0] + 2 > body.length) {
            throw new IOException("variant-definition " + what + " truncated");
        }
        int value = ((body[cursor[0]] & 0xff) << 8) | (body[cursor[0] + 1] & 0xff);
        cursor[0] += 2;
        return value;
    }

    /** u8 byte-length + printable-ASCII bytes — the codec's {@code writeString} twin. */
    private static String readString(byte[] body, int[] cursor, String what) throws IOException {
        int len = readUnsignedByte(body, cursor, what + " length");
        if (len < 1) {
            throw new IOException("variant-definition " + what + " empty on the wire");
        }
        if (cursor[0] + len > body.length) {
            throw new IOException("variant-definition " + what + " bytes truncated: " + len);
        }
        StringBuilder chars = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int c = body[cursor[0] + i] & 0xff;
            if (c < 0x20 || c > 0x7e) {
                throw new IOException("variant-definition " + what
                        + " carries a non-printable byte: 0x" + Integer.toHexString(c));
            }
            chars.append((char) c);
        }
        cursor[0] += len;
        return chars.toString();
    }

    /** u8 count + u16 table indices — the codec's {@code readIndexList} twin. */
    private static List<String> readIndexList(byte[] body, int[] cursor, IdTables tables,
            IdTables.Kind kind, int maxCount, String what) throws IOException {
        int count = readUnsignedByte(body, cursor, what + " count");
        if (count > maxCount) {
            throw new IOException("variant-definition " + what + " count invalid: " + count);
        }
        List<String> values = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            values.add(tables.idAt(kind, readUnsignedShort(body, cursor, what + "[" + i + "]")));
        }
        return values;
    }

    private static List<String> readStringList(byte[] body, int[] cursor, int maxCount,
            String what) throws IOException {
        int count = readUnsignedByte(body, cursor, what + " count");
        if (count > maxCount) {
            throw new IOException("variant-definition " + what + " count invalid: " + count);
        }
        List<String> values = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String value = readString(body, cursor, what + "[" + i + "]");
            if (!ClientVariantDefinition.isIdLawful(value)) {
                throw new IOException("variant-definition " + what + "[" + i
                        + "] violates the id law: '" + value + "'");
            }
            values.add(value);
        }
        return values;
    }
}
