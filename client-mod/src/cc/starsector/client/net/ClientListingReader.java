package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code LSTD} structured-listing wire frame (SS382
 * commission B): the receiving
 * half of the server's {@code ListingFrameCodec} ({@code server.listing.data} v1). The server
 * answers a no-arg {@code /ship}, {@code /module} or {@code /transfer} with LSTD frames
 * UNICAST to the requesting seat's session on the reliable CTRL lane; selection sends NO new
 * UP surface — the picker answers through the existing chat-command grammar unchanged. The
 * demux route ({@link MultiplexedServerStreamReader}) and the picker lane consume this reader
 * at the L4 leg BY DESIGN; until then the game-free cross-check
 * script is the only caller, streaming real server
 * {@code ListingFrameCodec} bytes through this reader and asserting the decodes match.
 *
 * <p>Like {@link ChatReader}, the mod deliberately does not bundle the server codec (whose
 * static type-code initializers drag {@code NetworkMessageRegistry} into the game JVM). It
 * reads exactly the bytes the server writes over plain byte-array primitives plus the
 * allowlisted {@link CRC32}, mirroring the server decode guard-for-guard in the codec's
 * documented order (field guards fire before the CRC compare).
 *
 * <p><b>Frame geometry is LAW, not convention</b> (the server codec's words): {@code seqCount}
 * must equal {@code ceil(totalEntries / ENTRIES_PER_FRAME)}, every non-final frame carries
 * exactly {@link #ENTRIES_PER_FRAME} entries, the final frame carries the remainder, and the
 * frame length must equal {@link #frameBytes} of its {@code entryCount} — so the L4 assembler
 * never has to trust cross-frame arithmetic it cannot check per-frame. Every violation, plus
 * a bad magic/type/schema, an out-of-family mark, MBZ flag bits, a label breaking its law
 * (occupant = the seat-name contract PLUS the widened C1/separator/bidi rejection — the
 * family's ONE player-authored field), nonzero buffer padding, invalid UTF-8, or a CRC32
 * mismatch throws {@link IOException}. Allocation is bounded by construction: the largest
 * lawful frame is {@link #MAX_FRAME_BYTES} = 1073 B, under the 1200-byte budget.
 */
public final class ClientListingReader {

    /** Frame magic: ASCII "LSTD". Mirrors {@code ListingFrameCodec.DOWN_MAGIC}. */
    public static final int MAGIC = 0x4C535444;

    /** Registry message id this frame carries. Mirrors {@code ServerListingDataMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.listing.data";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerListingDataMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Listing kinds (header field; also namespaces the mark codes). Mirror the codec. */
    public static final int KIND_SHIP = 1;
    public static final int KIND_MODULE = 2;
    public static final int KIND_TRANSFER = 3;
    /** W12 L2: the /mute listing (same row shape as /transfer). NOTE: the
     * picker branch pasting {@code /mute #<id>} lands at L3. Mirrors {@code ListingFrameCodec.KIND_MUTE}. */
    public static final int KIND_MUTE = 4;
    /** The admin {@code /kick} listing (the /mute row shape; the picker pastes
     * {@code /kick #<id>}). Mirrors {@code ListingFrameCodec.KIND_KICK}. */
    public static final int KIND_KICK = 5;
    /** The admin {@code /debugships} listing — ship-shaped rows over the full
     * engine registry; the picker pastes {@code /debugships <token>} (a registry-only id
     * pasted as {@code /ship} would DENY against the mod allowlist — the kind split is the
     * structural fix). Mirrors {@code ListingFrameCodec.KIND_DEBUGSHIP}. */
    public static final int KIND_DEBUGSHIP = 6;
    /** The admin {@code /spawnfoe} listing (ship-shaped, one plain mark); the
     * picker pastes {@code /spawnfoe <token>}. Mirrors {@code ListingFrameCodec.KIND_SPAWNFOE}. */
    public static final int KIND_SPAWNFOE = 7;
    /** The admin bare-{@code /wave} MENU (one plain row per wave faction);
     * the picker pastes {@code /wave <token>}. Mirrors {@code ListingFrameCodec.KIND_WAVE}. */
    public static final int KIND_WAVE = 8;
    /** Peer-build sharing v2 ({@code variant-maker-peer-builds.md}): the {@code /share}
     * listing — the caller's OWN accepted customs, one row per id with its share bit as the mark
     * (20 OFF / 21 ON); the picker pastes {@code /share <token>} or {@code /unshare <token>} per
     * the row's mark. Token = the canonical id ONLY (display name + hull resolve client-side
     * from the resident VDEF — no peer text rides a listing row). Mirrors
     * {@code ListingFrameCodec.KIND_SHARE}. */
    public static final int KIND_SHARE = 9;
    /** The {@code /builds} listing — every SHARED custom (any author), plain-marked;
     * a pick is a LOCAL save ({@code PeerBuilds.saveFromRegistry}), never a send. Mirrors
     * {@code ListingFrameCodec.KIND_BUILDS}. */
    public static final int KIND_BUILDS = 10;
    /** The admin {@code /spawnfriend} listing — the
     * KIND_SPAWNFOE row shape verbatim; the picker pastes {@code /spawnfriend <token>} (a
     * player-side spawn). Mirrors {@code ListingFrameCodec.KIND_SPAWNFRIEND}. */
    public static final int KIND_SPAWNFRIEND = 11;
    /** The admin {@code /switchteams} listing — ONE row per
     * live player with a Left/Right mode toggle picking instant / respawn in place
     * (NOT the earlier two-rows-per-player shape), the KIND_KICK row shape (safe
     * label + {@code #wireId} paste); the picker pastes {@code /switchteams #<id> instant|respawn}.
     * Mirrors {@code ListingFrameCodec.KIND_SWITCHTEAMS}. */
    public static final int KIND_SWITCHTEAMS = 12;
    /** The door PROBE's mod-set listing (token = mod id, targetLabel = the
     * version label, hullWireId = the jars-digest prefix; mark 26). Probe-only, never in-session.
     * Mirrors {@code ListingFrameCodec.KIND_MODSET}. */
    public static final int KIND_MODSET = 13;
    /** The admin no-arg {@code /ban} MENU — the /kick
     * row shape minus admin sessions; a pick pastes {@code /ban #<id>}. Mirrors
     * {@code ListingFrameCodec.KIND_BAN}. */
    public static final int KIND_BAN = 14;
    /** The admin no-arg {@code /unban} MENU — one row per admin-origin cid ban,
     * newest first (token {@code ban<n>}, hullWireId = n = the entry's slot in the caller's pinned
     * ban numbering — NOT the row's position, not monotonic, targetLabel = day + key
     * abbreviation, occupantLabel = the stored label or a {@code cid <hex>} fallback); an untouched
     * pick pastes the re-time {@code /banned #<n> 1d} (the open rung);
     * Left x2 to the {@code lift} rung pastes {@code /unban #<n>}. Mirrors
     * {@code ListingFrameCodec.KIND_UNBAN}. */
    public static final int KIND_UNBAN = 15;
    /** The highest listing kind the frame law admits. Mirrors {@code ListingFrameCodec.KIND_MAX}. */
    public static final int KIND_MAX = KIND_UNBAN;

    /** Mark codes, kind-scoped. Mirror {@code ListingFrameCodec.MARK_*} exactly. */
    public static final int MARK_SHIP_PLAIN = 0;
    public static final int MARK_SHIP_PILOTING = 1;
    /** Occupancy mark: this ship is the seat's next-respawn pick.
     *  <p>WIRE PUN: in a PROBE reply (the empty-pick join door) mark-2 is reused to carry the
     *  server-owned joiner <em>default</em> variant — {@code DoorProbe} reads the entry bearing
     *  this mark as the published {@code defaultId} (host-picked default; absent when none), not
     *  as a live occupancy claim. */
    public static final int MARK_SHIP_NEXT_RESPAWN = 2;
    public static final int MARK_MODULE_FREE = 3;
    public static final int MARK_MODULE_YOU = 4;
    public static final int MARK_MODULE_TAKEN = 5;
    public static final int MARK_MODULE_DESTROYED = 6;
    public static final int MARK_TRANSFER_FREE = 7;
    public static final int MARK_TRANSFER_ABANDONED = 8;
    public static final int MARK_TRANSFER_RESERVED = 9;
    public static final int MARK_TRANSFER_YOU = 10;
    public static final int MARK_TRANSFER_TAKEN = 11;
    public static final int MARK_MUTE_OFF = 12;
    public static final int MARK_MUTE_ON = 13;
    public static final int MARK_KICK_PLAYER = 14;
    /** The /debugships marks mirror the three ship marks (kind-scoped so a
     * debugship row can never validate as a /ship row). Mirror the codec. */
    public static final int MARK_DEBUGSHIP_PLAIN = 15;
    public static final int MARK_DEBUGSHIP_PILOTING = 16;
    public static final int MARK_DEBUGSHIP_NEXT_RESPAWN = 17;
    public static final int MARK_SPAWNFOE_PLAIN = 18;
    /** The one /wave menu mark. Mirrors {@code ListingFrameCodec.MARK_WAVE_PLAIN}. */
    public static final int MARK_WAVE_PLAIN = 19;
    /** Peer-build sharing v2: the /share row's share bit (OFF / ON) and the one /builds
     * mark. Mirror {@code ListingFrameCodec.MARK_SHARE_OFF/ON} / {@code MARK_BUILDS_PLAIN}. */
    public static final int MARK_SHARE_OFF = 20;
    public static final int MARK_SHARE_ON = 21;
    public static final int MARK_BUILDS_PLAIN = 22;
    /** The one /spawnfriend mark. Mirrors {@code ListingFrameCodec.MARK_SPAWNFRIEND_PLAIN}. */
    public static final int MARK_SPAWNFRIEND_PLAIN = 23;
    /** The two /switchteams marks — the row's MODE. Mirror
     * {@code ListingFrameCodec.MARK_SWITCH_INSTANT} / {@code MARK_SWITCH_RESPAWN}. */
    public static final int MARK_SWITCH_INSTANT = 24;
    public static final int MARK_SWITCH_RESPAWN = 25;
    /** The KIND_MODSET REQUIRED mark. Mirrors
     * {@code ListingFrameCodec.MARK_MODSET_REQUIRED}. */
    public static final int MARK_MODSET_REQUIRED = 26;
    /** The KIND_MODSET OPTIONAL mark (a presentation-only mod the joiner MAY run;
     * never folded). Mirrors {@code ListingFrameCodec.MARK_MODSET_OPTIONAL}. */
    public static final int MARK_MODSET_OPTIONAL = 27;
    /** The /spawnfoe / /spawnfriend row toggled to OFFICER (the client's Left/Right
     * flips 18⇄28 / 23⇄29 in place; Enter pastes the trailing {@code officer} keyword). Mirror
     * {@code ListingFrameCodec.MARK_SPAWNFOE_OFFICER} / {@code MARK_SPAWNFRIEND_OFFICER}. */
    public static final int MARK_SPAWNFOE_OFFICER = 28;
    public static final int MARK_SPAWNFRIEND_OFFICER = 29;
    /** The one KIND_BAN mark and the one KIND_UNBAN mark, both occupant-carrying.
     * Mirror {@code ListingFrameCodec.MARK_BAN_PLAYER} / {@code MARK_UNBAN_ENTRY}; the upper mark
     * bound moves to 31 ({@link #MARK_MAX}). */
    public static final int MARK_BAN_PLAYER = 30;
    public static final int MARK_UNBAN_ENTRY = 31;
    /** The highest mark code the entry law admits. Mirrors {@code ListingFrameCodec.MARK_MAX}. */
    public static final int MARK_MAX = MARK_UNBAN_ENTRY;

    /** flags bit0: the /module "(next respawn)" claim — co-occurs with any occupancy mark. */
    public static final int FLAG_CLAIMED_NEXT_RESPAWN = 0x01;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code ListingFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** seatId(4) + kind(1) + listingId(4) + seqIndex(1) + seqCount(1) + totalEntries(u16)
     * + totalAvailable(u16) + entryCount(1). Mirrors the codec. */
    public static final int LISTING_HEADER_BYTES = 4 + 1 + 4 + 1 + 1 + 2 + 2 + 1; // 16
    /** Zero-padded token buffer (spaceless printable ASCII, the resolvable grammar string). */
    public static final int TOKEN_BUFFER_BYTES = 64;
    /** Zero-padded target-label buffer (printable ASCII ≤24 — TransferTargets law). */
    public static final int TARGET_LABEL_BUFFER_BYTES = 24;
    /** Zero-padded occupant-label buffer — the seat-name 48-byte law, one shared constant. */
    public static final int OCCUPANT_LABEL_BUFFER_BYTES = SeatNameWriter.NAME_BUFFER_BYTES; // 48
    /** Occupant-label char cap (the seat-name law's char half). */
    public static final int OCCUPANT_LABEL_MAX_CHARS = SeatNameWriter.NAME_MAX_CHARS; // 24
    /** tokenLen+buf + markCode + flags + targetLen+buf + occupantLen+buf + hullWireId. */
    public static final int ENTRY_BYTES =
            1 + TOKEN_BUFFER_BYTES + 1 + 1
                    + 1 + TARGET_LABEL_BUFFER_BYTES
                    + 1 + OCCUPANT_LABEL_BUFFER_BYTES
                    + Long.BYTES; // 149

    /** Entries per non-final frame. Mirrors the codec's 1200-byte-budget sizing. */
    public static final int ENTRIES_PER_FRAME = 7;
    /** Total-entry cap per listing ("+N more" renders past it via totalAvailable), raised
     * 128→1785 = 255×7 (the u8-seqCount ceiling) so the full debug catalogue rides
     * one listing; a CROSS-KIND ceiling — this mirror sizes assembly for every kind from it.
     * Mirrors {@code ListingFrameCodec.MAX_TOTAL_ENTRIES} (ceiling + schema-bump escape
     * recorded there). */
    public static final int MAX_TOTAL_ENTRIES = 1785;
    /** Bound on the advertised full source count (u16 on the wire, lawful ceiling here). */
    public static final int MAX_TOTAL_AVAILABLE = 4096;
    /** ceil(MAX_TOTAL_ENTRIES / ENTRIES_PER_FRAME). */
    public static final int MAX_SEQ_COUNT =
            (MAX_TOTAL_ENTRIES + ENTRIES_PER_FRAME - 1) / ENTRIES_PER_FRAME; // 255
    /** Worst-case frame width: header + 7 entries + CRC. Mirrors {@code MAX_FRAME_BYTES}. */
    public static final int MAX_FRAME_BYTES =
            HEADER_BYTES + LISTING_HEADER_BYTES + ENTRIES_PER_FRAME * ENTRY_BYTES + Integer.BYTES; // 1073

    private ClientListingReader() {
    }

    /** Frame width for {@code entryCount} entries. Mirrors {@code ListingFrameCodec.frameBytes}. */
    public static int frameBytes(int entryCount) {
        return HEADER_BYTES + LISTING_HEADER_BYTES + entryCount * ENTRY_BYTES + Integer.BYTES;
    }

    /**
     * Decode one listing frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the {@link MultiplexedServerStreamReader} entry-point shape.
     * The frame is variable-width: the fixed 22 bytes after the magic end in {@code entryCount},
     * which is range-guarded BEFORE the entry bytes are read (bounded allocation — never more
     * than {@link #MAX_FRAME_BYTES}). The CRC32 covers the frame INCLUDING the magic, so this
     * reconstructs the full frame buffer before validating.
     */
    public static ClientListingRecord readAfterMagic(InputStream in) throws IOException {
        byte[] fixed = new byte[HEADER_BYTES - 4 + LISTING_HEADER_BYTES]; // 22
        WireBytes.readFully(in, fixed); // mid-frame EOF throws — the magic is already consumed
        int entryCount = fixed[fixed.length - 1] & 0xff;
        if (entryCount < 1 || entryCount > ENTRIES_PER_FRAME) {
            throw new IOException("listing down entryCount out of range [1," + ENTRIES_PER_FRAME
                    + "]: " + entryCount);
        }
        byte[] frame = new byte[frameBytes(entryCount)];
        WireBytes.putInt(frame, 0, MAGIC);
        System.arraycopy(fixed, 0, frame, 4, fixed.length);
        byte[] rest = new byte[frame.length - 4 - fixed.length];
        WireBytes.readFully(in, rest);
        System.arraycopy(rest, 0, frame, 4 + fixed.length, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code LSTD} frame — mirrors {@code ListingFrameCodec.decodeDown}. */
    public static ClientListingRecord decodeFrame(byte[] frame) throws IOException {
        if (frame == null) {
            throw new IOException("listing down frame must not be null");
        }
        int minBytes = frameBytes(1);
        int maxBytes = frameBytes(ENTRIES_PER_FRAME);
        if (frame.length < minBytes || frame.length > maxBytes) {
            throw new IOException("listing down frame length out of range [" + minBytes + ","
                    + maxBytes + "]: " + frame.length);
        }
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("listing down bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("listing down wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("listing down unsupported schema version: " + schema);
        }
        int seatId = WireBytes.getInt(frame, off);
        off += 4;
        int kind = frame[off++] & 0xff;
        int listingId = WireBytes.getInt(frame, off);
        off += 4;
        int seqIndex = frame[off++] & 0xff;
        int seqCount = frame[off++] & 0xff;
        int totalEntries = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        int totalAvailable = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        int entryCount = frame[off++] & 0xff;
        if (frame.length != frameBytes(entryCount)) {
            throw new IOException("listing down frame length " + frame.length
                    + " does not match entryCount=" + entryCount);
        }
        List<ClientListingRecord.Entry> entries = new ArrayList<>(entryCount);
        try {
            validateHeader(seatId, kind, listingId, seqIndex, seqCount,
                    totalEntries, totalAvailable, entryCount);
            for (int i = 0; i < entryCount; i++) {
                entries.add(readEntry(frame, off, kind));
                off += ENTRY_BYTES;
            }
        } catch (IllegalArgumentException bad) {
            throw new IOException("listing down frame invalid: " + bad.getMessage(), bad);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, frame.length - Integer.BYTES);
        int actualCrc = WireBytes.getInt(frame, frame.length - Integer.BYTES);
        if (actualCrc != (int) crc.getValue()) {
            throw new IOException("listing down frame CRC mismatch");
        }
        return new ClientListingRecord(seatId, kind, listingId, seqIndex, seqCount,
                totalEntries, totalAvailable, entries);
    }

    // ---- validation laws (mirror the server codec's; throw IllegalArgumentException) ----

    private static void validateHeader(int seatId, int kind, int listingId, int seqIndex,
            int seqCount, int totalEntries, int totalAvailable, int entryCount) {
        if (seatId < 0) {
            throw new IllegalArgumentException("seatId must be >= 0; got " + seatId);
        }
        if (kind < KIND_SHIP || kind > KIND_MAX) {
            throw new IllegalArgumentException("kind out of range [1," + KIND_MAX + "]: " + kind);
        }
        if (listingId < 1) {
            throw new IllegalArgumentException("listingId must be >= 1; got " + listingId);
        }
        if (totalEntries < 1 || totalEntries > MAX_TOTAL_ENTRIES) {
            throw new IllegalArgumentException("totalEntries out of range [1,"
                    + MAX_TOTAL_ENTRIES + "]: " + totalEntries);
        }
        if (totalAvailable < totalEntries || totalAvailable > MAX_TOTAL_AVAILABLE) {
            throw new IllegalArgumentException("totalAvailable out of range ["
                    + totalEntries + "," + MAX_TOTAL_AVAILABLE + "]: " + totalAvailable);
        }
        int lawfulSeqCount = (totalEntries + ENTRIES_PER_FRAME - 1) / ENTRIES_PER_FRAME;
        if (seqCount != lawfulSeqCount) {
            throw new IllegalArgumentException("seqCount " + seqCount
                    + " does not match ceil(totalEntries/" + ENTRIES_PER_FRAME + ")="
                    + lawfulSeqCount);
        }
        if (seqIndex < 0 || seqIndex >= seqCount) {
            throw new IllegalArgumentException("seqIndex out of range [0," + (seqCount - 1)
                    + "]: " + seqIndex);
        }
        int lawfulEntryCount = (seqIndex == seqCount - 1)
                ? totalEntries - ENTRIES_PER_FRAME * (seqCount - 1)
                : ENTRIES_PER_FRAME;
        if (entryCount != lawfulEntryCount) {
            throw new IllegalArgumentException("entryCount " + entryCount
                    + " does not match the frame-geometry law (expected " + lawfulEntryCount
                    + " at seqIndex " + seqIndex + "/" + seqCount + ")");
        }
    }

    private static void validateEntry(ClientListingRecord.Entry entry, int kind) {
        validateToken(entry.token());
        int mark = entry.markCode();
        if (mark < MARK_SHIP_PLAIN || mark > MARK_MAX) {
            throw new IllegalArgumentException("markCode out of range [0," + MARK_MAX + "]: " + mark);
        }
        if (kindOfMark(mark) != kind) {
            throw new IllegalArgumentException("markCode " + mark
                    + " does not belong to listing kind " + kind);
        }
        if (entry.claimedNextRespawn() && kind != KIND_MODULE) {
            throw new IllegalArgumentException(
                    "claimedNextRespawn is a /module-only flag (kind " + kind + ")");
        }
        // Ship rows (marks 0..2): OPTIONAL = the ship's
        // display name (the seat-name law), absent when unknown — a present label still passes
        // validateOccupantLabel below; every other kind keeps the exact-presence law. Mirrors
        // the server codec (ListingFrameCodec.validateEntry).
        boolean shipRow = mark >= MARK_SHIP_PLAIN && mark <= MARK_SHIP_NEXT_RESPAWN;
        boolean wantsOccupant = markCarriesOccupant(mark);
        if (!shipRow && wantsOccupant == (entry.occupantLabel() == null)) {
            throw new IllegalArgumentException("occupantLabel must be present exactly on the"
                    + " occupant-carrying marks; markCode=" + mark
                    + " label=" + (entry.occupantLabel() == null ? "absent" : "present"));
        }
        if (entry.occupantLabel() != null) {
            validateOccupantLabel(entry.occupantLabel());
        }
        // /mute, /kick and /switchteams rows share the /transfer shape
        // (label + #wireId paste); so do the /ban + /unban menu rows
        if (kind == KIND_TRANSFER || kind == KIND_MUTE || kind == KIND_KICK
                || kind == KIND_SWITCHTEAMS || kind == KIND_BAN || kind == KIND_UNBAN) {
            if (entry.targetLabel() == null) {
                throw new IllegalArgumentException("targetLabel is REQUIRED on /transfer rows");
            }
            validateTargetLabel(entry.targetLabel());
            if (entry.hullWireId() < 1) {
                throw new IllegalArgumentException(
                        "hullWireId must be >= 1 on /transfer rows; got " + entry.hullWireId());
            }
        } else if (kind == KIND_MODSET) {
            // the modset row law, mirrored: version label REQUIRED, jars prefix = ANY long
            if (entry.targetLabel() == null) {
                throw new IllegalArgumentException("targetLabel (version) is REQUIRED on modset rows");
            }
            validateTargetLabel(entry.targetLabel());
        } else {
            // A ship row (marks 0..2) MAY carry its hull id in
            // targetLabel (the picker shows the hull and searches it; an older server never sends one), under
            // the same <= 24 printable-ASCII law; every other kind keeps the transfer-only law. Mirrors the
            // server codec's ship-row branch.
            if (entry.targetLabel() != null && !shipRow) {
                throw new IllegalArgumentException(
                        "targetLabel is a /transfer-only field (kind " + kind + ")");
            }
            if (entry.targetLabel() != null) {
                validateTargetLabel(entry.targetLabel());
            }
            if (entry.hullWireId() != 0) {
                throw new IllegalArgumentException(
                        "hullWireId must be 0 off /transfer rows; got " + entry.hullWireId());
            }
        }
    }

    /** Which listing kind a mark code belongs to. Mirrors {@code ListingFrameCodec.kindOfMark}. */
    public static int kindOfMark(int markCode) {
        if (markCode >= MARK_SHIP_PLAIN && markCode <= MARK_SHIP_NEXT_RESPAWN) {
            return KIND_SHIP;
        }
        if (markCode >= MARK_MODULE_FREE && markCode <= MARK_MODULE_DESTROYED) {
            return KIND_MODULE;
        }
        if (markCode >= MARK_MUTE_OFF && markCode <= MARK_MUTE_ON) {
            return KIND_MUTE;
        }
        if (markCode == MARK_KICK_PLAYER) {
            return KIND_KICK;
        }
        if (markCode >= MARK_DEBUGSHIP_PLAIN && markCode <= MARK_DEBUGSHIP_NEXT_RESPAWN) {
            return KIND_DEBUGSHIP;
        }
        if (markCode == MARK_SPAWNFOE_PLAIN || markCode == MARK_SPAWNFOE_OFFICER) {
            return KIND_SPAWNFOE;
        }
        if (markCode == MARK_WAVE_PLAIN) {
            return KIND_WAVE;
        }
        if (markCode >= MARK_SHARE_OFF && markCode <= MARK_SHARE_ON) {
            return KIND_SHARE;
        }
        if (markCode == MARK_BUILDS_PLAIN) {
            return KIND_BUILDS;
        }
        if (markCode == MARK_SPAWNFRIEND_PLAIN || markCode == MARK_SPAWNFRIEND_OFFICER) {
            return KIND_SPAWNFRIEND;
        }
        if (markCode == MARK_SWITCH_INSTANT || markCode == MARK_SWITCH_RESPAWN) {
            return KIND_SWITCHTEAMS;
        }
        if (markCode == MARK_MODSET_REQUIRED || markCode == MARK_MODSET_OPTIONAL) {
            return KIND_MODSET;
        }
        // The /ban and /unban menu marks
        if (markCode == MARK_BAN_PLAYER) {
            return KIND_BAN;
        }
        if (markCode == MARK_UNBAN_ENTRY) {
            return KIND_UNBAN;
        }
        return KIND_TRANSFER;
    }

    /** True for the marks whose prose form carries a player/occupant name. */
    public static boolean markCarriesOccupant(int markCode) {
        return markCode == MARK_MODULE_TAKEN
                || markCode == MARK_TRANSFER_RESERVED
                || markCode == MARK_TRANSFER_TAKEN
                || markCode == MARK_MUTE_OFF || markCode == MARK_MUTE_ON
                || markCode == MARK_KICK_PLAYER
                || markCode == MARK_SWITCH_INSTANT || markCode == MARK_SWITCH_RESPAWN
                // The ban menu row and the unban menu row both carry the occupant field
                || markCode == MARK_BAN_PLAYER || markCode == MARK_UNBAN_ENTRY;
    }

    /** Token law: 1..64 spaceless printable ASCII — mirrors {@code ListingFrameCodec.validateToken}. */
    public static void validateToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }
        if (token.isEmpty() || token.length() > TOKEN_BUFFER_BYTES) {
            throw new IllegalArgumentException("token length out of range [1,"
                    + TOKEN_BUFFER_BYTES + "]: " + token.length());
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                throw new IllegalArgumentException(
                        "token must be spaceless printable ASCII (bad char at index " + i + ")");
            }
        }
    }

    /** Target-label law: trimmed, non-blank, ≤24 printable ASCII (interior spaces OK). */
    public static void validateTargetLabel(String label) {
        if (label == null) {
            throw new IllegalArgumentException("targetLabel must not be null");
        }
        if (label.isEmpty() || !label.equals(label.trim())) {
            throw new IllegalArgumentException("targetLabel must be trimmed and non-blank");
        }
        if (label.length() > TARGET_LABEL_BUFFER_BYTES) {
            throw new IllegalArgumentException("targetLabel must be <= "
                    + TARGET_LABEL_BUFFER_BYTES + " chars, got " + label.length());
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < 0x20 || c >= 0x7f) {
                throw new IllegalArgumentException(
                        "targetLabel must be printable ASCII (bad char at index " + i + ")");
            }
        }
    }

    /**
     * Occupant-label law — the seat-name contract via {@link SeatNameWriter#validateSeatName}
     * (trimmed, non-blank, ≤24 chars, ≤48 UTF-8 bytes, single-line printable) PLUS the widened
     * smuggle-rejection of the picker-paste design: C1 controls (incl. NEL),
     * LS/PS, and bidi controls all REJECT — this is the family's one player-authored field.
     * Mirrors {@code ListingFrameCodec.validateOccupantLabel} accept-for-accept.
     */
    public static void validateOccupantLabel(String label) {
        SeatNameWriter.validateSeatName(label);
        for (int i = 0; i < label.length(); i++) {
            if (isWidenedSmuggleChar(label.charAt(i))) {
                throw new IllegalArgumentException(
                        "occupantLabel carries a C1/separator/bidi control at index " + i);
            }
        }
    }

    /**
     * The check-3 widened strip set — mirrors {@code ListingFrameCodec.isWidenedSmuggleChar}.
     * u-escaped on purpose: no invisible/bidi code point may ride a SOURCE file
     * (the hygiene law).
     */
    public static boolean isWidenedSmuggleChar(char c) {
        return (c >= '\u0080' && c <= '\u009f')     // C1 controls incl. NEL U+0085
                || c == '\u2028' || c == '\u2029'   // line / paragraph separator
                || c == '\u061c'                      // Arabic letter mark (bidi)
                || c == '\u200e' || c == '\u200f'   // LRM / RLM
                || (c >= '\u202a' && c <= '\u202e') // LRE / RLE / PDF / LRO / RLO
                || (c >= '\u2066' && c <= '\u2069'); // LRI / RLI / FSI / PDI
    }

    // ---- entry half (mirrors the codec's readEntry / readAsciiBuffer / readUtf8Buffer) ----

    private static ClientListingRecord.Entry readEntry(byte[] frame, int off, int kind)
            throws IOException {
        String token = readAsciiBuffer(frame, off, TOKEN_BUFFER_BYTES, 1, "token");
        off += 1 + TOKEN_BUFFER_BYTES;
        int markCode = frame[off++] & 0xff;
        int flags = frame[off++] & 0xff;
        if ((flags & ~FLAG_CLAIMED_NEXT_RESPAWN) != 0) {
            throw new IOException("listing down entry flags carry MBZ bits: 0x"
                    + Integer.toHexString(flags));
        }
        String targetLabel = readAsciiBuffer(frame, off, TARGET_LABEL_BUFFER_BYTES, 0, "targetLabel");
        off += 1 + TARGET_LABEL_BUFFER_BYTES;
        String occupantLabel = readUtf8Buffer(frame, off, OCCUPANT_LABEL_BUFFER_BYTES, "occupantLabel");
        off += 1 + OCCUPANT_LABEL_BUFFER_BYTES;
        long hullWireId = WireBytes.getLong(frame, off);
        ClientListingRecord.Entry entry = new ClientListingRecord.Entry(token, markCode,
                (flags & FLAG_CLAIMED_NEXT_RESPAWN) != 0, targetLabel, occupantLabel, hullWireId);
        validateEntry(entry, kind);
        return entry;
    }

    /** len(u8)+zero-padded ASCII buffer; {@code minLen} 0 means an empty sentinel = null. */
    private static String readAsciiBuffer(byte[] frame, int off, int bufferBytes, int minLen,
            String what) throws IOException {
        int len = frame[off] & 0xff;
        if (len < minLen || len > bufferBytes) {
            throw new IOException("listing down " + what + " length out of range [" + minLen + ","
                    + bufferBytes + "]: " + len);
        }
        int start = off + 1;
        for (int i = start + len; i < start + bufferBytes; i++) {
            if (frame[i] != 0) {
                throw new IOException("listing down " + what + " nonzero padding beyond len=" + len);
            }
        }
        if (len == 0) {
            return null;
        }
        for (int i = start; i < start + len; i++) {
            if ((frame[i] & 0xff) >= 0x7f || (frame[i] & 0xff) < 0x20) {
                throw new IOException("listing down " + what + " is not printable ASCII");
            }
        }
        return new String(frame, start, len, StandardCharsets.US_ASCII);
    }

    /** len(u8)+zero-padded UTF-8 buffer with the round-trip guard; len 0 = absent (null). */
    private static String readUtf8Buffer(byte[] frame, int off, int bufferBytes, String what)
            throws IOException {
        int len = frame[off] & 0xff;
        if (len > bufferBytes) {
            throw new IOException("listing down " + what + " length out of range [0," + bufferBytes
                    + "]: " + len);
        }
        int start = off + 1;
        for (int i = start + len; i < start + bufferBytes; i++) {
            if (frame[i] != 0) {
                throw new IOException("listing down " + what + " nonzero padding beyond len=" + len);
            }
        }
        if (len == 0) {
            return null;
        }
        String value = new String(frame, start, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than surface a mangled label (the server codec's exact guard).
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len) {
            throw new IOException("listing down " + what + " is not valid UTF-8");
        }
        for (int i = 0; i < len; i++) {
            if (reencoded[i] != frame[start + i]) {
                throw new IOException("listing down " + what + " is not valid UTF-8");
            }
        }
        return value;
    }

}
