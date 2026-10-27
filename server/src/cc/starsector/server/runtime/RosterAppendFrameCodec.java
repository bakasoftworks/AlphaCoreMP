package cc.starsector.server.runtime;

import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.RosterAppendControlMessage;
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
 * Product-owned binary frame codec for the {@code RAPP} roster-append control frame
 * ({@link RosterAppendControlMessage}). Envelope mirrors
 * {@link MatchResetFrameCodec} — {@code magic | schema | headerBytes | wireTypeCode | bodyLen |
 * body | CRC32} — the declared {@code bodyLen} makes the VARIABLE body self-delimiting for the
 * {@code DownFrameFamily} walk. Framing is registry-checked and fail-closed exactly like the
 * MRST codec: magic / schema / wire-identity / declared-size mismatch and CRC32 corruption all
 * throw {@link IOException}.
 *
 * <p>Body v3: {@code u8 rowCount} + rows of {@code u32 wireId | u8 side | UTF variantId |
 * UTF shipName | u16 crPerMille | u32 carrierWireId | u8 bayIndex | UTF officerLine} (v2 =
 * the carrier+bay tail; v1 ended at crPerMille; v3 = the
 * officer tail: the {@code OfficerRosterCodec} grammar line of the captain the server
 * ATTACHED to this hull, empty = none — the client twin attaches the same captain so its
 * damage-taken stats match the server's, the "boss heals / snaps back" overshoot).
 * Body v5 appends {@code | u8 flags} to every row (bit0 = {@link #FLAG_AT_REST}: the server hull
 * was placed with {@code burnDur 0} — AT its pose, no travel drive — so the client twin mints
 * with no parity burn; bit1 = {@link #FLAG_ALL_SKILLS_CAPTAIN}: the hull's
 * restored captain IS its all-skills seat mint, exclusive with an officer line; other bits
 * reject). v4 = v3 bytes with the optional
 * {@code |aiCoreId} officer-line field; {@link #readArchivedFrameAfterMagic} still reads v4
 * reels (flags absent ⇒ not at rest), the LIVE read is exact-schema.
 * Side is descriptor-ABSOLUTE; no spawn pose (the client's
 * first-sight adopt spawns at the first authoritative wire pose). Rows per frame cap at
 * {@link #MAX_ROWS_PER_FRAME}; the emitter chunks larger waves into multiple frames BY
 * BYTES ({@link #chunk}: an officered row is ~300 B, so ~3 ride a frame). The whole
 * frame must fit the datagram payload budget ({@link #MAX_FRAME_BYTES}) — encode
 * fails closed on a frame that would not (a wave descriptor with absurd names is a config
 * error, never a truncated frame).
 */
public final class RosterAppendFrameCodec {

    /** Frame magic: {@code "RAPP"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x52415050; // "RAPP"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverRosterAppendWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    /** Rows per frame cap; the emit site chunks a larger drained wave. */
    public static final int MAX_ROWS_PER_FRAME = 16;
    /** The datagram payload budget the whole frame must fit. */
    public static final int MAX_FRAME_BYTES = 1164;
    private static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - Integer.BYTES;

    /** v2 {@code bayIndex} sentinel: the row is not a carrier-born fighter. */
    public static final int BAY_NONE = 0xFF;

    /** v5 row {@code flags} bit0: the hull was placed at rest ({@code burnDur 0}). */
    public static final int FLAG_AT_REST = 0x01;

    /**
     * v5 row {@code flags} bit1: the FACT "the captain the server restored on this hull IS its
     * all-skills seat mint" — set only when the server's all-skills knob is ON and the person
     * carries the mint's identity, so the client twin re-mints that same person through its own
     * all-skills lever. The person's level is past the officer-line grammar's bound, so it can never
     * ride the line; the bit and a non-empty officer line are EXCLUSIVE (the row ctor refuses both,
     * the decode wrap turns that into the frame refusal). A pre-bit kit's net loop ENDS loud at its
     * first bit row (its reader refuses the reserved bit) — the pair-mismatch posture at the
     * protocol-break tier, not a frame drop; no hs bump: no public kit predates the bit.
     *
     */
    public static final int FLAG_ALL_SKILLS_CAPTAIN = 0x02;

    /** The pre-flags schema the ARCHIVED reel read still accepts (v4 = v3 bytes, no flags byte). */
    private static final int ARCHIVED_SCHEMA_V4 = 4;
    /** The first schema whose rows carry the {@code u8 flags} tail. */
    private static final int FLAGS_SCHEMA_V5 = 5;

    /**
     * One appended hull: {wireId, ABSOLUTE side, variantId, shipName, crPerMille,
     * carrierWireId, bayIndex}. Validation is by construction (encode) and re-checked on
     * decode (a wire row that fails it is an {@link IOException}, the MRST decode idiom).
     *
     * <p>v2 (fighter HUD): {@code carrierWireId} = the wire id of
     * the carrier whose bay minted this hull ({@code 0} = none) and {@code bayIndex} = that
     * bay's index in the carrier's {@code getLaunchBays()} order ({@link #BAY_NONE} = none) —
     * the client registers the replica as a member of a fleet-manager wing sourced from that
     * carrier+bay so vanilla's wing panel/pips light for it. Both ride the canonical absent
     * shape together (0 + {@link #BAY_NONE}); the 5-arg ctor mints it.
     *
     * <p>v5 {@code atRest}: the server hull was placed
     * AT its pose with {@code burnDur < 1} (no travel drive)
     * — the posed adhoc / pod / posed-wave mints. Sourced from the
     * spawn's OWN burn arg ({@code RealEngineDemoRoster.WaveDeployResult#atRest}), never
     * guessed; every legacy shape reads {@code false} = "the twin burns like a fly-in".
     *
     * <p>v5 {@code allSkillsCaptain}: the
     * {@link #FLAG_ALL_SKILLS_CAPTAIN} fact — set only by {@link #allSkillsCaptain}, never beside
     * an officer line; every other shape reads {@code false}.
     */
    public record AppendRow(long wireId, int side, String variantId, String shipName,
            int crPerMille, long carrierWireId, int bayIndex, String officerLine, boolean atRest,
            boolean allSkillsCaptain) {
        /** Non-fighter row (deploy/wave/probe/pod/fly-in lanes): no carrier, no bay, no captain. */
        public AppendRow(long wireId, int side, String variantId, String shipName,
                int crPerMille) {
            this(wireId, side, variantId, shipName, crPerMille, 0L, BAY_NONE, "", false, false);
        }

        /** The v2 shape (carrier+bay, no captain) — the growth lane's fighter rows. */
        public AppendRow(long wireId, int side, String variantId, String shipName,
                int crPerMille, long carrierWireId, int bayIndex) {
            this(wireId, side, variantId, shipName, crPerMille, carrierWireId, bayIndex, "", false,
                    false);
        }

        /** The v3/v4 shape (carrier+bay+officer line, not at rest) — growth rows + the contract rows. */
        public AppendRow(long wireId, int side, String variantId, String shipName,
                int crPerMille, long carrierWireId, int bayIndex, String officerLine) {
            this(wireId, side, variantId, shipName, crPerMille, carrierWireId, bayIndex,
                    officerLine, false, false);
        }

        /**
         * The v5 at-rest shape without the all-skills bit — the wave officer guard and the contract
         * rows build it.
         */
        public AppendRow(long wireId, int side, String variantId, String shipName,
                int crPerMille, long carrierWireId, int bayIndex, String officerLine,
                boolean atRest) {
            this(wireId, side, variantId, shipName, crPerMille, carrierWireId, bayIndex,
                    officerLine, atRest, false);
        }

        /**
         * The bare non-fighter row carrying the {@link #FLAG_ALL_SKILLS_CAPTAIN} bit: no carrier,
         * no bay, no officer line (exclusive with the bit).
         */
        public static AppendRow allSkillsCaptain(long wireId, int side, String variantId,
                String shipName, int crPerMille, boolean atRest) {
            return new AppendRow(wireId, side, variantId, shipName, crPerMille, 0L, BAY_NONE, "",
                    atRest, true);
        }

        /**
         * The OFFICERED non-fighter row — {@code officerOrNull} = the captain
         * {@code OfficerAttach} actually attached (null = the bare 5-arg shape, byte-identical
         * to v2 + an empty line). The spec is encoded through the ONE grammar
         * ({@link OfficerRosterCodec#encodeLine}); its variant must be the row's own.
         */
        public static AppendRow officered(long wireId, int side, String variantId,
                String shipName, int crPerMille, OfficerRosterCodec.OfficerSpec officerOrNull) {
            return officered(wireId, side, variantId, shipName, crPerMille, officerOrNull, false);
        }

        /**
         * The officered row with the v5 {@code atRest} bit — the wave / probe / adhoc drains pass
         * the spawn's own {@code atRest()}.
         */
        public static AppendRow officered(long wireId, int side, String variantId,
                String shipName, int crPerMille, OfficerRosterCodec.OfficerSpec officerOrNull,
                boolean atRest) {
            return new AppendRow(wireId, side, variantId, shipName, crPerMille, 0L, BAY_NONE,
                    officerOrNull == null ? "" : OfficerRosterCodec.encodeLine(officerOrNull),
                    atRest, false);
        }

        /**
         * The EMIT side of the wire text law — the kit drops the
         * WHOLE RAPP frame on a refused char. Names fold SILENTLY to {@code '?'}, the variant id folds
         * LOUD (one FALLBACK-ANNOUNCE per boot; the kit resolves the folded id to nothing); nothing
         * here throws on the text law: several of the 8 construct sites run inside {@code serveFrame},
         * where a throw ends the match for every seat — a fold costs one hull's presentation.
         */
        public AppendRow {
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(shipName, "shipName");
            Objects.requireNonNull(officerLine, "officerLine");
            shipName = WireTextLaw.foldUnprintable(shipName);
            int badVariantAt = WireTextLaw.firstUnprintableIndex(variantId);
            if (badVariantAt >= 0) {
                FallbackAnnounce.announceOnce("rapp-variant-id-folded", "bad_index=" + badVariantAt
                        + " length=" + variantId.length());
                variantId = WireTextLaw.foldUnprintable(variantId);
            }
            if (wireId < 1L || wireId > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("wireId outside u32 range: " + wireId);
            }
            if (side != 0 && side != 1) {
                throw new IllegalArgumentException("side must be 0 or 1, got " + side);
            }
            if (variantId.isBlank()) {
                throw new IllegalArgumentException("variantId must be non-blank");
            }
            if (shipName.isBlank()) {
                throw new IllegalArgumentException("shipName must be non-blank");
            }
            if (crPerMille < 0 || crPerMille > 1000) {
                throw new IllegalArgumentException("crPerMille outside [0,1000]: " + crPerMille);
            }
            if (carrierWireId < 0L || carrierWireId > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(
                        "carrierWireId outside u32 range: " + carrierWireId);
            }
            if (bayIndex < 0 || bayIndex > BAY_NONE) {
                throw new IllegalArgumentException("bayIndex outside u8 range: " + bayIndex);
            }
            if ((carrierWireId == 0L) != (bayIndex == BAY_NONE)) {
                // One bit pattern per meaning: carrier and bay are present together or absent
                // together (the AWSD sparse-tier idiom) — a half-shape is a codec bug.
                throw new IllegalArgumentException("carrierWireId/bayIndex must be both present"
                        + " or both absent; got " + carrierWireId + "/" + bayIndex);
            }
            if (carrierWireId == wireId) {
                throw new IllegalArgumentException("a hull cannot be its own carrier: " + wireId);
            }
            if (allSkillsCaptain && !officerLine.isEmpty()) {
                // One captain fact per row — the bit
                // names a person the line cannot carry, so both at once is a codec bug; the server
                // never builds it, and the decode wrap below turns this into the frame refusal.
                throw new IllegalArgumentException("roster-append row " + wireId
                        + ": an officer line and the all-skills bit are exclusive");
            }
            if (allSkillsCaptain && carrierWireId != 0L) {
                // A bay-born fighter never carries a captain — the line
                // law's sibling below; the bit names the same fact, so it is refused too.
                throw new IllegalArgumentException("roster-append row " + wireId
                        + ": a carrier-born row cannot carry the all-skills bit");
            }
            if (!officerLine.isEmpty()) {
                if (carrierWireId != 0L) {
                    // A bay-born fighter never carries a captain (the wing's carrier does).
                    throw new IllegalArgumentException("a carrier-born row cannot carry an"
                            + " officer line: " + wireId);
                }
                // Grammar + bounds + the row's own variant, both ends (decodeOne
                // throws IllegalArgumentException; the decode idiom wraps it in IOException).
                OfficerRosterCodec.decodeOne(officerLine, variantId);
            }
        }

        /** True when this row is a carrier-born fighter (carrier + bay present). */
        public boolean hasCarrier() {
            return carrierWireId != 0L;
        }

        /** True when this row carries the captain the server attached (v3 officer tail). */
        public boolean hasOfficer() {
            return !officerLine.isEmpty();
        }

        /** The decoded captain, or null on a bare row (a fresh decode per call; validated at construction). */
        public OfficerRosterCodec.OfficerSpec officerOrNull() {
            return officerLine.isEmpty() ? null : OfficerRosterCodec.decodeOne(officerLine, variantId);
        }
    }

    private RosterAppendFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one RAPP frame (1..{@link #MAX_ROWS_PER_FRAME} rows) to its full wire bytes. */
    public static byte[] encode(List<AppendRow> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, rows);
        return out.toByteArray();
    }

    /** Write one RAPP control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, List<AppendRow> rows) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty() || rows.size() > MAX_ROWS_PER_FRAME) {
            throw new IllegalArgumentException("roster-append frame rows must be 1.."
                    + MAX_ROWS_PER_FRAME + ", got " + rows.size());
        }
        byte[] body = encodeBody(rows);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("roster-append frame body " + body.length
                    + " B exceeds the wire budget (" + MAX_BODY_BYTES + " B body cap)");
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
     * Read one RAPP control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, declared body size, CRC32, and every row;
     * throws {@link IOException} on any mismatch.
     */
    public static List<AppendRow> readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        return decodeBody(readCheckedBody(data), true);
    }

    /**
     * The ARCHIVED-reel read (the reel tools: {@code DownFrameFamily}, {@code CcwrDespawnCensus},
     * {@code CcwrEscapeParkAudit}, {@code CcwrMmntCensus}): accepts schema 4 OR 5 BY VALUE —
     * the registry is NOT consulted (it registers only the live schema) — so a v4 reel recorded
     * before the v5 at-rest flag still walks; a v4 body has no flags byte and decodes
     * {@code atRest=false} and {@code allSkillsCaptain=false}. Every other check is the live read's. The LIVE entry
     * ({@link #readFrameAfterMagic}) stays exact-schema: a stale kit / peer fails closed there.
     *
     */
    public static List<AppendRow> readArchivedFrameAfterMagic(DataInputStream data)
            throws IOException {
        Objects.requireNonNull(data, "data");
        int schema = Short.toUnsignedInt(data.readShort());
        if (schema != ARCHIVED_SCHEMA_V4 && schema != FLAGS_SCHEMA_V5) {
            throw new IOException("unsupported roster-append frame schema: " + schema);
        }
        return decodeBody(readCheckedBody(data), schema >= FLAGS_SCHEMA_V5);
    }

    /** The post-schema envelope (header size, wire identity, declared body size, CRC32) → the body. */
    private static byte[] readCheckedBody(DataInputStream data) throws IOException {
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("roster-append frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("roster-append frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("roster-append frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("roster-append frame checksum mismatch");
        }
        return body;
    }

    private static byte[] encodeBody(List<AppendRow> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(rows.size());
        for (AppendRow row : rows) {
            data.writeInt((int) row.wireId());
            data.writeByte(row.side());
            data.writeUTF(row.variantId());
            data.writeUTF(row.shipName());
            data.writeShort(row.crPerMille());
            data.writeInt((int) row.carrierWireId()); // v2
            data.writeByte(row.bayIndex());           // v2
            data.writeUTF(row.officerLine());         // v3 ("" = no captain)
            data.writeByte((row.atRest() ? FLAG_AT_REST : 0)
                    | (row.allSkillsCaptain() ? FLAG_ALL_SKILLS_CAPTAIN : 0)); // v5 flags
        }
        data.flush();
        return bytes.toByteArray();
    }

    /** One row's encoded size (the v5 layout; UTF = u16 length + modified-UTF-8 bytes; + u8 flags). */
    public static int rowBytes(AppendRow row) {
        return Integer.BYTES + 1 + utfBytes(row.variantId()) + utfBytes(row.shipName())
                + Short.BYTES + Integer.BYTES + 1 + utfBytes(row.officerLine()) + 1;
    }

    private static int utfBytes(String s) {
        int n = 2;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
        }
        return n;
    }

    /**
     * Split {@code rows} into frame-sized chunks BY BYTES — each chunk holds at
     * most {@link #MAX_ROWS_PER_FRAME} rows and its body ({@code u8 rowCount} + rows) fits
     * the budget. The pre-v3 emitter chunked by count alone (16 bare rows ≈ 700 B);
     * an officered row is ~300 B, so a count-only chunk would fail the encode closed
     * mid-drain. Order is preserved. A single row that cannot fit a frame alone THROWS
     * {@link IOException} — a config error, never a truncated frame.
     */
    public static List<List<AppendRow>> chunk(List<AppendRow> rows) throws IOException {
        Objects.requireNonNull(rows, "rows");
        List<List<AppendRow>> chunks = new ArrayList<>();
        List<AppendRow> current = new ArrayList<>();
        int currentBytes = 1; // u8 rowCount
        for (AppendRow row : rows) {
            int bytes = rowBytes(row);
            if (1 + bytes > MAX_BODY_BYTES) {
                throw new IOException("roster-append row " + row.wireId() + " (" + bytes
                        + " B) cannot fit one frame body (" + MAX_BODY_BYTES + " B cap)");
            }
            if (!current.isEmpty()
                    && (current.size() >= MAX_ROWS_PER_FRAME || currentBytes + bytes > MAX_BODY_BYTES)) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 1;
            }
            current.add(row);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /** {@code hasFlags} = the frame's schema carries the v5 {@code u8 flags} row tail (live: always). */
    private static List<AppendRow> decodeBody(byte[] body, boolean hasFlags) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int rowCount = data.readUnsignedByte();
        if (rowCount < 1 || rowCount > MAX_ROWS_PER_FRAME) {
            throw new IOException("roster-append frame row count invalid: " + rowCount);
        }
        List<AppendRow> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            long wireId = Integer.toUnsignedLong(data.readInt());
            int side = data.readUnsignedByte();
            String variantId = data.readUTF();
            String shipName = data.readUTF();
            int crPerMille = data.readUnsignedShort();
            long carrierWireId = Integer.toUnsignedLong(data.readInt());
            int bayIndex = data.readUnsignedByte();
            String officerLine = data.readUTF(); // v3
            boolean atRest = false; // a v4 archived body has no flags byte
            boolean allSkills = false;
            if (hasFlags) {
                int flags = data.readUnsignedByte(); // v5
                if ((flags & ~(FLAG_AT_REST | FLAG_ALL_SKILLS_CAPTAIN)) != 0) {
                    throw new IOException("roster-append frame row " + i + " unknown flags: " + flags);
                }
                atRest = (flags & FLAG_AT_REST) != 0;
                allSkills = (flags & FLAG_ALL_SKILLS_CAPTAIN) != 0;
            }
            // The emit side folds, the DECODE side refuses (the kit's
            // ClientRosterAppendReader drops the frame on the same bytes) — a reel tool must never silently
            // rewrite an archived id through the ctor's fold. Row index only, never the text.
            if (WireTextLaw.firstUnprintableIndex(variantId) >= 0 || WireTextLaw.firstUnprintableIndex(shipName) >= 0) {
                throw new IOException("roster-append frame row " + i + " carries a char the wire text law refuses");
            }
            try {
                // The row ctor's own laws (an officer line beside the all-skills bit included)
                // throw IllegalArgumentException; this wrap makes each one the frame refusal.
                rows.add(new AppendRow(wireId, side, variantId, shipName, crPerMille,
                        carrierWireId, bayIndex, officerLine, atRest, allSkills));
            } catch (IllegalArgumentException badRow) {
                throw new IOException("roster-append frame row " + i + " invalid", badRow);
            }
        }
        if (data.read() != -1) {
            throw new IOException("roster-append frame body has trailing bytes after "
                    + rowCount + " rows");
        }
        return rows;
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat()
                    .require(RosterAppendControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported roster-append frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("roster-append frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
