package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import cc.starsector.server.runtime.WireTextLaw;

/**
 * Client-side decoder for the server's {@code RAPP} roster-append control wire frame:
 * the receiving half of the project's
 * {@code cc.starsector.server.runtime.RosterAppendFrameCodec}.
 *
 * <p>The server fans one {@code RAPP} frame per drained mid-round wave (and replays the
 * round's frames to late joiners) so every client can APPEND the wave hulls to its
 * descriptor-built replica roster before their first pose arrives — without it, mid-round
 * wire ids stay unknown-ids-inert forever (the ghost match). The
 * {@link MultiplexedServerStreamReader} routes the frame here by its leading magic.
 *
 * <p>Like {@link ClientMatchResetReader}, the mod deliberately does not bundle the server
 * codec (which uses {@code java.io.DataInputStream} — blocked by the mod-script sandbox
 * classloader, see {@link WireBytes}). It reads exactly the bytes the server writes — magic /
 * schema / headerBytes / wireTypeCode / bodyLen / body / CRC32, body = {@code u8 rowCount} +
 * rows of {@code u32 wireId | u8 side | UTF variantId | UTF shipName | u16 crPerMille |
 * u32 carrierWireId | u8 bayIndex | UTF officerLine | u8 flags | u32 fleetKeyDigest} (v2 = the
 * carrier+bay tail; v3 = the officer tail, "" = no captain; v5 = the
 * flags byte; v6 = flags bit2 / bit3 + the big-endian digest tail;
 *v7 = flags bit4, the record-removal row;
 * a bit outside {@link #KNOWN_FLAGS} rejects) —
 * over plain {@link InputStream} primitives plus the allowlisted {@link CRC32}. The UTF
 * strings are {@code DataOutputStream.writeUTF} modified UTF-8 (u16 byte length + 1/2/3-byte
 * chars), decoded here with the same accept/reject rules as {@code DataInputStream.readUTF}.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version, a
 * header/body size mismatch, a CRC32 mismatch, a malformed UTF sequence, an out-of-range row,
 * a bad row count, or trailing body bytes all throw {@link IOException}; a clean end-of-stream
 * at a frame boundary returns {@code null} from {@link #readAppend(InputStream)}.
 */
public final class ClientRosterAppendReader {

    /** Frame magic: ASCII "RAPP". Mirrors {@code RosterAppendFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x52415050;

    /** Schema version this reader understands. Mirrors {@code RosterAppendControlMessage.SCHEMA_VERSION}
     *  (v3 = the officer tail; v4 = the optional |aiCoreId officer-line
     *  field; v5 = the {@code u8 flags} row tail; v6 = the fleet-fielded bits + the
     *  {@code u32 fleetKeyDigest} row tail; v7 = the
     *  {@link #FLAG_FLEET_VACATED} row, same row layout). Exact match: a v5
     *  frame fails closed here (its rows have no digest tail), as a v4 one does (no flags byte). The v7
     *  bump exists ONLY to keep this compare loud: a v6 kit meeting a v7 serve ends its net loop at the
     *  first RAPP frame of the join, not at the first removal row deep into a round. */
    public static final int SCHEMA_VERSION = 7;

    /** v5 row {@code flags} bit0 = AT_REST. Mirrors {@code RosterAppendFrameCodec.FLAG_AT_REST}. */
    public static final int FLAG_AT_REST = 0x01;

    /**
     * v5 row {@code flags} bit1 = ALL_SKILLS_CAPTAIN. Mirrors
     * {@code RosterAppendFrameCodec.FLAG_ALL_SKILLS_CAPTAIN}. The FACT the server states: the captain
     * it restored on this hull IS its all-skills seat mint (a vacated transfer source keeps its seat
     * person), so the replica mints the seat-shaped all-skills captain when this kit's all-skills match
     * flag is ON too, and stays bare (one loud line) when it is OFF. Never set together with an officer
     * line: the row's own invariant refuses that pair, and the frame fails closed. A pre-bit kit reads
     * the bit as an unknown flag, so its net loop ENDS loud at its first bit row: the pair-mismatch
     * posture at the protocol-break tier, not a frame drop; no handshake bump: no public kit predates
     * the bit.
     *
     *
     */
    public static final int FLAG_ALL_SKILLS_CAPTAIN = 0x02;

    /**
     * v6 row {@code flags} bit2 = FLEET_FIELDED: this row is a fleet-record write; its wire id holds
     * (row side, the goal of its variant) in the round's record from here on, and the row's
     * {@code fleetKeyDigest} is the digest of the side's fleet key the hull read (every u32 value is
     * lawful, 0 included). Without this bit the digest must be 0, else the frame fails closed.
     *
     *
     */
    public static final int FLAG_FLEET_FIELDED = 0x04;

    /** v6 row {@code flags} bit3 = FLEET_ROUND_OPEN: the write belongs to the round-open pre-pass.
     *  Lawful ONLY beside {@link #FLAG_FLEET_FIELDED}; alone it fails the frame closed. */
    public static final int FLAG_FLEET_ROUND_OPEN = 0x08;

    /**
     * v7 row {@code flags} bit4 = FLEET_VACATED: this row REMOVES its wire id's entry from the round's
     * fleet record; it is never a hull announce. Lawful ONLY as the whole flags byte ({@code flags ==
     * 0x10}): beside any other bit the frame fails closed, so one bit pattern carries one meaning. The
     * row's {@code fleetKeyDigest} is the digest of the side's fleet key right AFTER the removal (every
     * u32 lawful, 0 included); its other fields are REQUIRED inert (no officer line, no carrier / bay,
     * cr 0, shipName == variantId = the removed entry's goal id, side = the entry's side), a law
     * {@link ClientRosterAppend.Row} enforces. A pre-v7 kit never meets this bit: the exact schema
     * compare refuses the whole v7 frame first.
     *
     *
     */
    public static final int FLAG_FLEET_VACATED = 0x10;

    /** Every admitted v7 {@code flags} bit; any other bit fails the frame closed (a newer server's
     *  bit ends this kit's net loop loud rather than being read as something it is not). */
    public static final int KNOWN_FLAGS = FLAG_AT_REST | FLAG_ALL_SKILLS_CAPTAIN
            | FLAG_FLEET_FIELDED | FLAG_FLEET_ROUND_OPEN | FLAG_FLEET_VACATED;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Rows per frame cap. Mirrors {@code RosterAppendFrameCodec.MAX_ROWS_PER_FRAME}. */
    public static final int MAX_ROWS_PER_FRAME = 16;

    /** Whole-frame budget. Mirrors {@code RosterAppendFrameCodec.MAX_FRAME_BYTES}. */
    public static final int MAX_FRAME_BYTES = 1164;

    /** The variable body's size bound implied by the frame budget (frame − header − CRC32). */
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - 4;

    private ClientRosterAppendReader() {
    }

    /**
     * Decode one roster-append frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientRosterAppend readAppend(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("roster-append frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readAppendAfterMagic(in);
    }

    /**
     * Decode one roster-append frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point.
     */
    public static ClientRosterAppend readAppendAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("roster-append frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("roster-append frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("roster-append frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("roster-append frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static ClientRosterAppend decodeBody(byte[] body) throws IOException {
        int rowCount = body[0] & 0xff;
        if (rowCount < 1 || rowCount > MAX_ROWS_PER_FRAME) {
            throw new IOException("roster-append frame row count invalid: " + rowCount);
        }
        int[] cursor = {1};
        List<ClientRosterAppend.Row> rows = new ArrayList<ClientRosterAppend.Row>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            if (cursor[0] + 5 > body.length) {
                throw new IOException("roster-append frame row " + i + " truncated");
            }
            long wireId = WireBytes.getInt(body, cursor[0]) & 0xffffffffL;
            int side = body[cursor[0] + 4] & 0xff;
            cursor[0] += 5;
            String variantId = readUtf(body, cursor, "variantId");
            String shipName = readUtf(body, cursor, "shipName");
            if (cursor[0] + 2 > body.length) {
                throw new IOException("roster-append frame row " + i + " truncated");
            }
            int crPerMille = ((body[cursor[0]] & 0xff) << 8) | (body[cursor[0] + 1] & 0xff);
            cursor[0] += 2;
            // v2 tail: u32 carrierWireId + u8 bayIndex.
            if (cursor[0] + 5 > body.length) {
                throw new IOException("roster-append frame row " + i + " truncated (v2 tail)");
            }
            long carrierWireId = WireBytes.getInt(body, cursor[0]) & 0xffffffffL;
            int bayIndex = body[cursor[0] + 4] & 0xff;
            cursor[0] += 5;
            // v3 tail: UTF officerLine — the OfficerRosterCodec grammar line of
            // the captain the server attached ("" = none). Decoded through the ONE shared
            // parser (the same class the curated table rides), bounded + printable-ASCII +
            // exactly one row + the row's own variant; any breach fails the frame closed.
            String officerLine = readUtf(body, cursor, "officerLine");
            cc.starsector.server.match.OfficerRosterCodec.OfficerSpec officer = null;
            if (!officerLine.isEmpty()) {
                try {
                    officer = cc.starsector.server.match.OfficerRosterCodec.decodeOne(
                            officerLine, variantId);
                } catch (IllegalArgumentException badLine) {
                    throw new IOException("roster-append frame row " + i
                            + " officer line invalid: " + badLine.getMessage(), badLine);
                }
            }
            // v5 tail: u8 flags — bit0 = AT_REST (the server hull was placed
            // with burnDur 0, so the twin mints with no parity burn), bit1 = ALL_SKILLS_CAPTAIN
            // (the server's restored captain on this hull is its all-skills seat mint; the Row
            // refuses it beside an officer line, wrapped below), bit2 / bit3 = the v6 fleet bits,
            // bit4 = the v7 vacated bit (alone or not at all); a reserved bit (0x20 and up) fails
            // the frame closed. Short-body guard BEFORE the read (the reader's order).
            if (cursor[0] + 1 > body.length) {
                throw new IOException("roster-append frame row " + i + " truncated (v5 tail)");
            }
            int flags = body[cursor[0]] & 0xff;
            cursor[0] += 1;
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw new IOException("roster-append frame row " + i + " unknown flags: " + flags);
            }
            // The vacated bit is the whole flags byte or absent: checked BEFORE the round-open law so
            // 0x18 names this law, the number only.
            boolean fleetVacated = (flags & FLAG_FLEET_VACATED) != 0;
            if (fleetVacated && flags != FLAG_FLEET_VACATED) {
                throw new IOException("roster-append frame row " + i
                        + " vacated flag beside another flag: " + flags);
            }
            boolean allSkills = (flags & FLAG_ALL_SKILLS_CAPTAIN) != 0;
            boolean fleetFielded = (flags & FLAG_FLEET_FIELDED) != 0;
            boolean fleetRoundOpen = (flags & FLAG_FLEET_ROUND_OPEN) != 0;
            if (fleetRoundOpen && !fleetFielded) {
                throw new IOException("roster-append frame row " + i
                        + " round-open flag without the fielded flag: " + flags);
            }
            // v6 tail: u32 fleetKeyDigest, AFTER the flags byte (the server's write order). The
            // field carries the u32 bit pattern in a signed int (every value lawful on a fielded or
            // a vacated row, 0 included); a non-zero digest on a row with neither bit2 nor bit4 fails
            // the frame closed, naming the number only.
            //
            if (cursor[0] + 4 > body.length) {
                throw new IOException("roster-append frame row " + i + " truncated (v6 tail)");
            }
            int fleetKeyDigest = WireBytes.getInt(body, cursor[0]);
            cursor[0] += 4;
            if (!fleetFielded && !fleetVacated && fleetKeyDigest != 0) {
                throw new IOException("roster-append frame row " + i
                        + " fleet-key digest without the fielded or vacated flag: 0x"
                        + Integer.toHexString(fleetKeyDigest));
            }
            try {
                rows.add(new ClientRosterAppend.Row(wireId, side, variantId, shipName, crPerMille,
                        carrierWireId, bayIndex, officer, (flags & FLAG_AT_REST) != 0, allSkills,
                        fleetFielded, fleetRoundOpen, fleetVacated, fleetKeyDigest));
            } catch (IllegalArgumentException badRow) {
                // The Row's messages carry numbers and fixed words only (never an id string), so
                // the frame-drop reason names the broken invariant.
                throw new IOException("roster-append frame row " + i + " invalid: "
                        + badRow.getMessage(), badRow);
            }
        }
        if (cursor[0] != body.length) {
            throw new IOException("roster-append frame body has trailing bytes after "
                    + rowCount + " rows");
        }
        try {
            return new ClientRosterAppend(rows);
        } catch (IllegalArgumentException badFrame) {
            throw new IOException("roster-append frame body invalid: " + badFrame.getMessage(),
                    badFrame);
        }
    }

    /**
     * Decode one {@code DataOutputStream.writeUTF} string at {@code cursor[0]} — u16 byte
     * length + modified UTF-8 (1/2/3-byte chars, {@code DataInputStream.readUTF} semantics) —
     * advancing the cursor past it. Malformed or truncated sequences throw {@link IOException}, and
     * so does a char the ONE
     * {@link WireTextLaw} rejects — the ids reach {@code variantById} + the bind rows raw; this runs
     * before {@code decodeOne}, names an index only, and keeps spaces + non-ASCII printables legal.
     */
    private static String readUtf(byte[] body, int[] cursor, String what) throws IOException {
        int off = cursor[0];
        if (off + 2 > body.length) {
            throw new IOException("roster-append " + what + " length truncated");
        }
        int len = ((body[off] & 0xff) << 8) | (body[off + 1] & 0xff);
        off += 2;
        int end = off + len;
        if (end > body.length) {
            throw new IOException("roster-append " + what + " bytes truncated: " + len);
        }
        StringBuilder chars = new StringBuilder(len);
        while (off < end) {
            int a = body[off] & 0xff;
            if ((a & 0x80) == 0) {
                chars.append((char) a);
                off += 1;
            } else if ((a >> 5) == 0x6) {
                if (off + 1 >= end) {
                    throw new IOException("roster-append " + what + " UTF sequence truncated");
                }
                int b = body[off + 1] & 0xff;
                if ((b & 0xC0) != 0x80) {
                    throw new IOException("roster-append " + what + " UTF sequence malformed");
                }
                chars.append((char) (((a & 0x1F) << 6) | (b & 0x3F)));
                off += 2;
            } else if ((a >> 4) == 0xE) {
                if (off + 2 >= end) {
                    throw new IOException("roster-append " + what + " UTF sequence truncated");
                }
                int b = body[off + 1] & 0xff;
                int c = body[off + 2] & 0xff;
                if ((b & 0xC0) != 0x80 || (c & 0xC0) != 0x80) {
                    throw new IOException("roster-append " + what + " UTF sequence malformed");
                }
                chars.append((char) (((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F)));
                off += 3;
            } else {
                throw new IOException("roster-append " + what + " UTF sequence malformed");
            }
        }
        String value = chars.toString();
        int bad = WireTextLaw.firstUnprintableIndex(value);
        if (bad >= 0) {
            throw new IOException("roster-append " + what + " carries a non-printable char at index " + bad);
        }
        cursor[0] = end;
        return value;
    }
}
