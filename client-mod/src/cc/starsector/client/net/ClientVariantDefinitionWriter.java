package cc.starsector.client.net;

import java.util.List;

import cc.starsector.server.runtime.IdTables;

/**
 * Hand-mirror ENCODER of the {@code VDEF} BODY grammar — the client half of W15 v0,
 * schema 2.
 * Byte-for-byte the server's {@code VariantDefinitionFrameCodec.encodeBody}: 15 fields in record
 * order — u8-length printable-ASCII strings ({@code variantId}, {@code displayName}), the
 * {@code hullId} as a u16 index into the boot {@link IdTables} hull table, u8 flags
 * ({@code goalVariant} = 0x01, a submission's keep-only ask = 0x02), u16 {@code fluxVents}, u16 {@code fluxCapacitors}, SIX u8-count
 * u16-index lists (the five hullmod-family lists against ONE hullmod table, the wings against
 * the wing table), the u8-count {@code tags} STRING list, u8-count weapon groups (u8 flags
 * autofire 0x01 | alternating 0x02, u8 weapon count, per mount a u16 per-hull SLOT index + a u16
 * weapon index — the RESERVED {@link IdTables#BUILT_IN_WEAPON_INDEX} whenever the weapon IS the
 * hull's own built-in on that slot: the built-in law runs FIRST, then the table), u8-count
 * modules (u16 slot index + the {@code moduleVariantId} as a STRING — a variant id, never
 * indexed). The BODY only — the {@link VariantSubmitWriter} wraps it in the VSUB envelope; the
 * server re-uses ONE decoder for the catalog fan and the submission lane.
 *
 * <p>The {@link IdTables} is the SAME class both trees run (bundled into the mod jar — never a
 * mirror); the in-game caller passes the boot singleton ({@code IdTables.installedOrNull()},
 * minted by {@code ClientIdTableBoot} at {@code onApplicationLoad}), the game-free join page
 * passes the table the game cached for it, the parity verifiers pass a fixture. An id the table
 * does not know, a slot the hull lacks, or a missing table = a LOUD local refusal
 * ({@link IllegalArgumentException}, the writer's standing contract) — never a guessed frame.
 *
 * <p>No {@code Data*Stream} / {@code ByteArrayOutputStream} (classloader-B denied — the
 * {@link ClientVariantDefinitionReader} precedent): a local growable byte sink. Every string law
 * (1..255 bytes, printable ASCII) is already guaranteed by the {@link ClientVariantDefinition}
 * record's own constructor laws; the writer re-checks the encodable range only so a drifted law
 * can never emit a frame the server's fail-closed decode kills the session over.
 * Parity is proved game-free by a verifier script
 * ({@code CcClientVsubVerifier}: JSON → both parsers → both encoders → identical bytes).
 */
public final class ClientVariantDefinitionWriter {
    private static final int FLAG_GOAL_VARIANT = 0x01;
    /**
     * Frame-flag bit 0x02 = KEEP ONLY: the submitter asks the
     * server to accept and mint the build WITHOUT making it the seat's next-respawn pick (the host's
     * Promote adds a ship to {@code /ship} for everyone and must never change what the host flies next).
     * Set only on a submission ({@link #encodeBody(ClientVariantDefinition, IdTables, boolean)}); a
     * server that predates the bit fails the frame's decode closed (an unknown flag — kit and server
     * ship as a pair), and a body without it flies as before. Mirrors the server codec's keep-only bit.
     */
    public static final int FLAG_KEEP_ONLY = 0x02;
    private static final int GROUP_FLAG_AUTOFIRE = 0x01;
    private static final int GROUP_FLAG_ALTERNATING = 0x02;

    private ClientVariantDefinitionWriter() {
    }

    /** The VDEF body bytes for {@code variant} against {@code tables} (no envelope, no CRC); keep-only clear. */
    public static byte[] encodeBody(ClientVariantDefinition variant, IdTables tables) {
        return encodeBody(variant, tables, false);
    }

    /** The VDEF body bytes with the {@link #FLAG_KEEP_ONLY} bit set when {@code keepOnly} (a submission's
     *  ask, never a property of the variant itself — the record carries no such field). */
    public static byte[] encodeBody(ClientVariantDefinition variant, IdTables tables, boolean keepOnly) {
        if (variant == null) {
            throw new IllegalArgumentException("variant must not be null");
        }
        requireTables(tables);
        Sink out = new Sink(256);
        String hullId = variant.hullId();
        writeString(out, variant.variantId());
        writeIndex(out, tables, IdTables.Kind.HULL, hullId, "hullId");
        writeString(out, variant.displayName());
        out.u8((variant.goalVariant() ? FLAG_GOAL_VARIANT : 0) | (keepOnly ? FLAG_KEEP_ONLY : 0));
        out.u16(variant.fluxVents());
        out.u16(variant.fluxCapacitors());
        writeIndexList(out, tables, IdTables.Kind.HULLMOD, variant.hullMods(), "hullMods");
        writeIndexList(out, tables, IdTables.Kind.HULLMOD, variant.permaMods(), "permaMods");
        writeIndexList(out, tables, IdTables.Kind.HULLMOD, variant.sMods(), "sMods");
        writeIndexList(out, tables, IdTables.Kind.HULLMOD, variant.sModdedBuiltIns(), "sModdedBuiltIns");
        writeIndexList(out, tables, IdTables.Kind.HULLMOD, variant.suppressedMods(), "suppressedMods");
        writeIndexList(out, tables, IdTables.Kind.WING, variant.wings(), "wings");
        writeStringList(out, variant.tags());
        out.u8(variant.weaponGroups().size());
        int g = 0;
        for (ClientVariantDefinition.WeaponGroup group : variant.weaponGroups()) {
            int groupFlags = (group.autofire() ? GROUP_FLAG_AUTOFIRE : 0)
                    | (group.alternating() ? GROUP_FLAG_ALTERNATING : 0);
            out.u8(groupFlags);
            out.u8(group.weapons().size());
            int w = 0;
            for (ClientVariantDefinition.WeaponMount mount : group.weapons()) {
                String what = "group " + g + " weapon " + w;
                writeSlotIndex(out, tables, hullId, mount.slotId(), what);
                // The built-in law FIRST (the wire's one reserved index), then the table — the
                // server encoder's exact order.
                String builtIn = tables.builtInWeaponOrNull(hullId, mount.slotId());
                if (builtIn != null && builtIn.equals(mount.weaponId())) {
                    out.u16(IdTables.BUILT_IN_WEAPON_INDEX);
                } else {
                    writeIndex(out, tables, IdTables.Kind.WEAPON, mount.weaponId(), what + " weaponId");
                }
                w++;
            }
            g++;
        }
        out.u8(variant.modules().size());
        int m = 0;
        for (ClientVariantDefinition.ModuleMount module : variant.modules()) {
            writeSlotIndex(out, tables, hullId, module.slotId(), "module " + m);
            writeString(out, module.moduleVariantId());
            m++;
        }
        return out.toByteArray();
    }

    /** The one refusal text for a missing table (the in-game mint failed, or a game-free JVM). */
    static void requireTables(IdTables tables) {
        if (tables == null) {
            throw new IllegalArgumentException("variant-definition encode needs the boot id table"
                    + " and none is installed: the onApplicationLoad mint failed (see the"
                    + " log) or this JVM has no game loaded");
        }
    }

    /** u8 byte-length + the chars as bytes (the record laws guarantee printable ASCII). */
    private static void writeString(Sink out, String value) {
        int len = value.length();
        if (len < 1 || len > 255) {
            throw new IllegalArgumentException("variant-definition string length un-encodable: "
                    + len);
        }
        out.u8(len);
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException("variant-definition string carries a"
                        + " non-printable char at index " + i);
            }
            out.u8(c);
        }
    }

    private static void writeStringList(Sink out, List<String> values) {
        if (values.size() > 255) {
            throw new IllegalArgumentException("variant-definition list un-encodable: "
                    + values.size() + " entries");
        }
        out.u8(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    // ------------------------------------------------------------------ index primitives

    /** u16 table index of a game-data id; an id THIS game does not know cannot ride the wire. */
    private static void writeIndex(Sink out, IdTables tables, IdTables.Kind kind, String id,
            String what) {
        int index = tables.indexOf(kind, id);
        if (index < 0) {
            throw new IllegalArgumentException("variant-definition " + what + " '" + id
                    + "' is not in the " + kind.label + " id table (wire format: game-data ids ride as"
                    + " table indices; this game does not know the id)");
        }
        out.u16(index);
    }

    private static void writeSlotIndex(Sink out, IdTables tables, String hullId, String slotId,
            String what) {
        int index = tables.slotIndex(hullId, slotId);
        if (index < 0) {
            throw new IllegalArgumentException("variant-definition " + what + " slot '" + slotId
                    + "' is not a slot of hull '" + hullId + "' (wire format: slots ride as per-hull"
                    + " indices)");
        }
        out.u16(index);
    }

    /** u8 count + u16 indices. */
    private static void writeIndexList(Sink out, IdTables tables, IdTables.Kind kind,
            List<String> ids, String what) {
        if (ids.size() > 255) {
            throw new IllegalArgumentException("variant-definition list un-encodable: "
                    + ids.size() + " entries");
        }
        out.u8(ids.size());
        int i = 0;
        for (String id : ids) {
            writeIndex(out, tables, kind, id, what + "[" + i + "]");
            i++;
        }
    }

    /** A growable byte sink (java.util-free, loader-B-clean). */
    private static final class Sink {
        private byte[] buf;
        private int len;

        Sink(int capacity) {
            buf = new byte[capacity];
        }

        void u8(int v) {
            ensure(1);
            buf[len++] = (byte) v;
        }

        void u16(int v) {
            if (v < 0 || v > 0xFFFF) {
                throw new IllegalArgumentException("u16 out of range: " + v);
            }
            ensure(2);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
        }

        private void ensure(int more) {
            if (len + more > buf.length) {
                byte[] grown = new byte[Math.max(buf.length * 2, len + more)];
                System.arraycopy(buf, 0, grown, 0, len);
                buf = grown;
            }
        }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
