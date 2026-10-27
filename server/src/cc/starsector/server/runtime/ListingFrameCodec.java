package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerListingDataMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Wire codec for the {@code LSTD} structured-listing DOWN frame family
 * ({@code server.listing.data} v1, {@link ServerListingDataMessage};
 * per the picker-paste design).
 *
 * <p>The {@code ChatFrameCodec}/{@code SeatNameFrameCodec} house shape: magic + registered
 * wire type code + schema header, zero-padded fixed-width string buffers, CRC32 over
 * everything before the trailer, fail-closed reads with field guards BEFORE the CRC compare.
 * The client mod does NOT load this class (registry statics stay out of the game JVM); it
 * reads the same bytes through the hand-mirrored {@code ClientListingReader}, and the
 * game-free client-mod chat verifier (chat family) streams
 * this codec's {@code encodeDown} bytes through that mirror — constant parity, decode
 * equality against this codec's own decode as the oracle, and guard-for-guard fail-closed
 * parity.
 *
 * <p><b>Frame geometry is LAW, not convention</b> — every frame of a listing is
 * independently checkable: {@code seqCount} must equal
 * {@code ceil(totalEntries / ENTRIES_PER_FRAME)}, every non-final frame carries exactly
 * {@link #ENTRIES_PER_FRAME} entries, the final frame carries the remainder, and
 * {@code totalEntries ≤ } {@link #MAX_TOTAL_ENTRIES}. A frame violating any of it fails
 * closed at decode — the client-side assembler (L4) never has to trust cross-frame
 * arithmetic it cannot check per-frame. The 7-entry frame width keeps the worst-case frame
 * ({@link #MAX_FRAME_BYTES} = 1073 B) under the 1200-byte datagram budget.
 *
 * <p><b>Per-entry field laws</b> (all fail-closed both directions):
 * <ul>
 * <li>{@code token} — 1..64 spaceless printable ASCII (0x21..0x7e): EXACTLY the string the
 *     existing chat-command grammar resolves ({@code /ship <id>}, {@code /module <tok>});
 *     the picker never invents its own resolution surface.</li>
 * <li>{@code markCode} — one of the 30 kind-scoped constants below (0..29); a mark from another
 *     kind's family rejects (the {@code (free)}/{@code (you)} prose collisions across kinds
 *     are disambiguated by construction, never by string compare).</li>
 * <li>{@code flags} — bit0 = the /module "(next respawn)" claim, ORTHOGONAL to occupancy
 *     (the two axes co-occur — {@code pp-recon/f} schema gap, closed here); bits 1..7 MBZ,
 *     nonzero rejects (additive room without a schema bump).</li>
 * <li>{@code targetLabel} — /transfer rows only (REQUIRED there, forbidden elsewhere):
 *     1..24 printable ASCII, trimmed (TransferTargets sanitizes at publish).
 *     Ship rows (marks 0..2): OPTIONAL = the ship's hull id under the
 *     same 1..24 printable-ASCII law, absent when unknown or longer than 24 (the emitter never cuts an id).</li>
 * <li>{@code occupantLabel} — REQUIRED exactly on the three occupant-carrying marks
 *     (MODULE_TAKEN / TRANSFER_RESERVED / TRANSFER_TAKEN), forbidden otherwise. This is the
 *     ONE player-authored field in the family: the seat-name law (≤24 chars, ≤48 UTF-8
 *     bytes, single-line printable) PLUS the widened charset-smuggle rejection of
 *     the picker-paste design's check-3 (C1 controls, NEL, LS/PS, bidi controls) —
 *     enforced at decode as REJECTION here and mirrored client-side; the L3 emitter
 *     additionally strips before encode.
 *     Ship rows (marks 0..2): OPTIONAL = the ship's
 *     display name (the seat-name law), absent when unknown.</li>
 * <li>{@code hullWireId} — /transfer rows only (positive; the picker sends the existing
 *     {@code /transfer #<wireId>} grammar form), 0 elsewhere.</li>
 * </ul>
 */
public final class ListingFrameCodec {

    /** LSTD magic = ASCII "LSTD" (LiSTing Data, server→requesting session, DOWN-only). */
    public static final int DOWN_MAGIC = 0x4C535444;

    public static final int DOWN_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverListingDataWireEntry().wireTypeCode();

    public static final short DOWN_SCHEMA_VERSION =
            (short) ServerListingDataMessage.SCHEMA_VERSION;

    /** Listing kinds (header field; also namespaces the mark codes). */
    public static final int KIND_SHIP = 1;
    public static final int KIND_MODULE = 2;
    public static final int KIND_TRANSFER = 3;
    /** W12 L2: the /mute listing — rows carry targetLabel + hullWireId like
     * /transfer (the picker pastes {@code /mute #<id>}); marks 12/13 = the muted state. */
    public static final int KIND_MUTE = 4;
    /** The admin {@code /kick} listing — the /mute row shape verbatim
     * (targetLabel + hullWireId + occupant name; the picker pastes {@code /kick #<id>});
     * mark 14 is the one kick mark (a kickable player has no state axis). */
    public static final int KIND_KICK = 5;
    /** The admin {@code /debugships} listing — ship-shaped rows (token +
     * piloting/next-respawn marks, no labels/wireId) over the FULL engine registry catalogue.
     * A dedicated kind (not KIND_SHIP) so the picker pastes {@code /debugships <token>}: a
     * registry-only variant pasted as {@code /ship} would DENY against the 16-id mod
     * allowlist (the latent defect — the kind split is the structural fix). */
    public static final int KIND_DEBUGSHIP = 6;
    /** The admin {@code /spawnfoe} listing — ship-shaped rows, one plain mark
     * (a spawnable foe has no seat state); the picker pastes {@code /spawnfoe <token>}
     * (count stays the typed power path). */
    public static final int KIND_SPAWNFOE = 7;
    /** The admin bare-{@code /wave} MENU — one ship-shaped plain-marked row
     * per fixed wave category ({@code WaveSchedule.WAVE_CATEGORIES}); the picker pastes
     * {@code /wave <token>} (the next wave becomes that faction). */
    public static final int KIND_WAVE = 8;
    /** Peer-build sharing v2 ({@code variant-maker-peer-builds.md}): the {@code /share}
     * listing — the caller's OWN accepted customs, one row per id with its share bit as the mark
     * (20 OFF / 21 ON); the picker pastes {@code /share <token>} or {@code /unshare <token>} per
     * the row's mark. Token = the canonical id ONLY (display name + hull resolve client-side
     * from the resident VDEF — no peer text rides a listing row). */
    public static final int KIND_SHARE = 9;
    /** The {@code /builds} listing — every SHARED custom (any author), plain-marked;
     * a pick is a LOCAL save on the client (never a send). Same token-only row shape. */
    public static final int KIND_BUILDS = 10;
    /** The admin {@code /spawnfriend} listing — the
     * KIND_SPAWNFOE row shape verbatim (ship-shaped, one plain mark); its own kind so the
     * picker pastes {@code /spawnfriend <token>} (a player-side spawn). */
    public static final int KIND_SPAWNFRIEND = 11;
    /** The admin {@code /switchteams} MENU — the /kick row
     * shape (targetLabel + hullWireId + occupant name), TWO rows per live player: mark 24 =
     * switch NOW, mark 25 = switch at the next respawn; the picker pastes
     * {@code /switchteams #<id> instant|respawn} by the row's mark. */
    public static final int KIND_SWITCHTEAMS = 12;
    /** The door PROBE's MOD SET listing — one row per
     * record of the serve's boot {@code ModSet} (token = the mod id, targetLabel = the version
     * through {@code ModSet.versionLabel}, hullWireId = {@code ModSet.filesPrefix} (jars word + data word), mark 26 =
     * REQUIRED). Rides the probe reply BEFORE the ship listing so the join page can name the mods
     * a joiner lacks before a 60-s boot bounces at the door; never emitted in-session. A pre-rung-2
     * kit's reader throws on the kind and its probe collector counts the frame as ignored. */
    public static final int KIND_MODSET = 13;
    /** The admin no-arg {@code /ban} MENU — the
     * {@code /kick} row shape (targetLabel + hullWireId + occupant name) minus admin sessions;
     * the picker pastes {@code /ban #<id>} (the EXISTING grammar — no new UP surface). */
    public static final int KIND_BAN = 14;
    /** The admin no-arg {@code /unban} MENU — one row per admin-origin cid ban,
     * newest first; token {@code ban<n>}, hullWireId = n = the entry's slot in the CALLER's pinned
     * ban numbering (the /bans numbering that session sees; tombstone-stable, NOT the row's position
     * and not monotonic),
     * targetLabel = day + key abbreviation (server-authored), occupantLabel = the stored ban
     * label or a {@code cid <hex>} fallback; the picker pastes {@code /unban #<n>}, which the
     * door resolves against the caller's PINNED listing by key. */
    public static final int KIND_UNBAN = 15;
    /** The highest listing kind the frame law admits (15; was 13). */
    public static final int KIND_MAX = KIND_UNBAN;

    /** Mark codes, kind-scoped. */
    public static final int MARK_SHIP_PLAIN = 0;
    public static final int MARK_SHIP_PILOTING = 1;
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
    /** The /debugships marks mirror the three ship marks (same semantics,
     * kind-scoped so a debugship row can never validate as a /ship row). */
    public static final int MARK_DEBUGSHIP_PLAIN = 15;
    public static final int MARK_DEBUGSHIP_PILOTING = 16;
    public static final int MARK_DEBUGSHIP_NEXT_RESPAWN = 17;
    public static final int MARK_SPAWNFOE_PLAIN = 18;
    /** The one /wave menu mark (a faction row has no seat state). */
    public static final int MARK_WAVE_PLAIN = 19;
    /** Peer-build sharing v2: the /share row's share bit (OFF / ON) and the one /builds mark. */
    public static final int MARK_SHARE_OFF = 20;
    public static final int MARK_SHARE_ON = 21;
    public static final int MARK_BUILDS_PLAIN = 22;
    /** The one /spawnfriend mark (a spawnable ally has no seat state). */
    public static final int MARK_SPAWNFRIEND_PLAIN = 23;
    /** The two /switchteams row marks — the MODE the row's pick pastes. */
    public static final int MARK_SWITCH_INSTANT = 24;
    public static final int MARK_SWITCH_RESPAWN = 25;
    /** The KIND_MODSET REQUIRED mark — the joiner must run this mod at this
     * version/jars (folded into the door digest). */
    public static final int MARK_MODSET_REQUIRED = 26;
    /** The KIND_MODSET OPTIONAL mark — a presentation-only mod the serve publishes
     * ({@code mods.optional}) but never loads and never folds; the join page stages it only when the
     * joiner has it at the pinned version and tells the game to exclude it from its own fold. */
    public static final int MARK_MODSET_OPTIONAL = 27;
    /** The {@code /spawnfoe} / {@code /spawnfriend} row
     * toggled to OFFICER — the {@code /switchteams} idiom: the server emits the plain (N/A) mark,
     * the client's Left/Right flips the row's mark in place, Enter pastes the trailing
     * {@code officer} keyword. Kind-scoped twins of marks 18 / 23; never occupant-carrying. */
    public static final int MARK_SPAWNFOE_OFFICER = 28;
    public static final int MARK_SPAWNFRIEND_OFFICER = 29;
    /** The one KIND_BAN mark (a live, non-admin player row — occupant-carrying like
     * {@link #MARK_KICK_PLAYER}) and the one KIND_UNBAN mark (a banned-entry row, occupant-carrying:
     * the stored label or the {@code cid <hex>} fallback). The upper mark bound moves to 31. */
    public static final int MARK_BAN_PLAYER = 30;
    public static final int MARK_UNBAN_ENTRY = 31;
    /** The highest mark code the entry law admits (31; was 29). */
    public static final int MARK_MAX = MARK_UNBAN_ENTRY;

    /** flags bit0: the /module "(next respawn)" claim — co-occurs with any occupancy mark. */
    public static final int FLAG_CLAIMED_NEXT_RESPAWN = 0x01;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** seatId(4) + kind(1) + listingId(4) + seqIndex(1) + seqCount(1) + totalEntries(u16)
     * + totalAvailable(u16) + entryCount(1). */
    public static final int LISTING_HEADER_BYTES = 4 + 1 + 4 + 1 + 1 + 2 + 2 + 1; // 16
    /** Zero-padded token buffer (spaceless printable ASCII, the resolvable grammar string). */
    public static final int TOKEN_BUFFER_BYTES = 64;
    /** Zero-padded target-label buffer (printable ASCII ≤24 — TransferTargets law). */
    public static final int TARGET_LABEL_BUFFER_BYTES = 24;
    /** Zero-padded occupant-label buffer (the seat-name 48-byte law). */
    public static final int OCCUPANT_LABEL_BUFFER_BYTES = SeatNameFrameCodec.NAME_BUFFER_BYTES; // 48
    /** Occupant-label char cap (the seat-name law's char half). */
    public static final int OCCUPANT_LABEL_MAX_CHARS = 24;
    /** tokenLen+buf + markCode + flags + targetLen+buf + occupantLen+buf + hullWireId. */
    public static final int ENTRY_BYTES =
            1 + TOKEN_BUFFER_BYTES + 1 + 1
                    + 1 + TARGET_LABEL_BUFFER_BYTES
                    + 1 + OCCUPANT_LABEL_BUFFER_BYTES
                    + Long.BYTES; // 149

    /** Entries per non-final frame; sized so the max frame stays under the 1200 B budget. */
    public static final int ENTRIES_PER_FRAME = 7;
    /** Total-entry cap per listing ("+N more" renders past it via totalAvailable). The cap was
     * raised 128→1785 = 255×{@link #ENTRIES_PER_FRAME} — the u8-seqCount natural ceiling —
     * so the full 785-id vanilla debug catalogue rides ONE listing and the client-local filter
     * searches the COMPLETE set (the old cap's unreachable tail was a reported defect).
     * This is a CROSS-KIND ceiling: the client mirror sizes its assembly for EVERY kind
     * (/mute//kick//transfer included) from this one constant, so the pair moves in lockstep.
     * A modpack catalogue >1785 re-caps with an unreachable tail again (the same defect at
     * scale) — the escape is a schema bump widening seqCount past u8, recorded here so the
     * ceiling is never rediscovered the hard way; vanilla's 785 sits safely under. */
    public static final int MAX_TOTAL_ENTRIES = 1785;
    /** Bound on the advertised full source count (u16 on the wire, lawful ceiling here). */
    public static final int MAX_TOTAL_AVAILABLE = 4096;
    /** ceil(MAX_TOTAL_ENTRIES / ENTRIES_PER_FRAME). */
    public static final int MAX_SEQ_COUNT =
            (MAX_TOTAL_ENTRIES + ENTRIES_PER_FRAME - 1) / ENTRIES_PER_FRAME; // 255

    /** Worst-case frame width: header + 7 entries + CRC = 1073 B ≤ the 1200 B floor. */
    public static final int MAX_FRAME_BYTES =
            HEADER_BYTES + LISTING_HEADER_BYTES + ENTRIES_PER_FRAME * ENTRY_BYTES + Integer.BYTES;

    /** One listing row (field laws in the class javadoc; labels {@code null} when absent). */
    public record ListingEntry(
            String token,
            int markCode,
            boolean claimedNextRespawn,
            String targetLabel,
            String occupantLabel,
            long hullWireId) {
        public ListingEntry {
            Objects.requireNonNull(token, "token");
        }
    }

    /** One decoded/encodable LSTD frame (geometry laws in the class javadoc). */
    public record ListingDown(
            int seatId,
            int kind,
            int listingId,
            int seqIndex,
            int seqCount,
            int totalEntries,
            int totalAvailable,
            List<ListingEntry> entries) {
        public ListingDown {
            Objects.requireNonNull(entries, "entries");
            entries = List.copyOf(entries);
        }
    }

    private ListingFrameCodec() {}

    /** The frame count a listing of {@code totalEntries} rows drips as (L3 emitter helper). */
    public static int framesForTotal(int totalEntries) {
        if (totalEntries < 1 || totalEntries > MAX_TOTAL_ENTRIES) {
            throw new IllegalArgumentException("totalEntries out of range [1,"
                    + MAX_TOTAL_ENTRIES + "]: " + totalEntries);
        }
        return (totalEntries + ENTRIES_PER_FRAME - 1) / ENTRIES_PER_FRAME;
    }

    /** Encode one validated {@code LSTD} frame (throws {@link IllegalArgumentException}). */
    public static byte[] encodeDown(ListingDown down) {
        validateFrame(down);
        int entryCount = down.entries().size();
        byte[] buf = new byte[frameBytes(entryCount)];
        int off = 0;
        off = putInt(buf, off, DOWN_MAGIC);
        off = putInt(buf, off, DOWN_TYPE_CODE);
        off = putShort(buf, off, DOWN_SCHEMA_VERSION);
        off = putInt(buf, off, down.seatId());
        buf[off++] = (byte) down.kind();
        off = putInt(buf, off, down.listingId());
        buf[off++] = (byte) down.seqIndex();
        buf[off++] = (byte) down.seqCount();
        off = putShort(buf, off, (short) down.totalEntries());
        off = putShort(buf, off, (short) down.totalAvailable());
        buf[off++] = (byte) entryCount;
        for (ListingEntry entry : down.entries()) {
            off = putEntry(buf, off, entry);
        }
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Frame width for {@code entryCount} entries. */
    public static int frameBytes(int entryCount) {
        return HEADER_BYTES + LISTING_HEADER_BYTES + entryCount * ENTRY_BYTES + Integer.BYTES;
    }

    /**
     * Decode + validate one full {@code LSTD} frame (fail-closed; verifier + tests — the live
     * client reads the mirrored {@code ClientListingReader}). Field guards fire before the CRC
     * compare, the family's documented order.
     */
    public static ListingDown decodeDown(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        int minBytes = frameBytes(1);
        int maxBytes = frameBytes(ENTRIES_PER_FRAME);
        if (frame.length < minBytes || frame.length > maxBytes) {
            throw new IOException("listing frame length out of range [" + minBytes + ","
                    + maxBytes + "]: " + frame.length);
        }
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != DOWN_MAGIC) {
            throw new IOException("listing bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != DOWN_TYPE_CODE) {
            throw new IOException("listing wire type code mismatch: expected "
                    + DOWN_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != DOWN_SCHEMA_VERSION) {
            throw new IOException("listing unsupported schema version: " + schema);
        }
        int seatId = getInt(frame, off); off += 4;
        int kind = frame[off++] & 0xFF;
        int listingId = getInt(frame, off); off += 4;
        int seqIndex = frame[off++] & 0xFF;
        int seqCount = frame[off++] & 0xFF;
        int totalEntries = getUnsignedShort(frame, off); off += 2;
        int totalAvailable = getUnsignedShort(frame, off); off += 2;
        int entryCount = frame[off++] & 0xFF;
        if (frame.length != frameBytes(entryCount)) {
            throw new IOException("listing frame length " + frame.length
                    + " does not match entryCount=" + entryCount);
        }
        ListingEntry[] entries = new ListingEntry[Math.max(entryCount, 1)];
        try {
            validateHeader(seatId, kind, listingId, seqIndex, seqCount,
                    totalEntries, totalAvailable, entryCount);
            for (int i = 0; i < entryCount; i++) {
                entries[i] = readEntry(frame, off, kind);
                off += ENTRY_BYTES;
            }
        } catch (IllegalArgumentException bad) {
            throw new IOException("listing frame invalid: " + bad.getMessage(), bad);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, frame.length - Integer.BYTES);
        int actualCrc = getInt(frame, frame.length - Integer.BYTES);
        if (actualCrc != (int) crc.getValue()) {
            throw new IOException("listing frame CRC mismatch");
        }
        return new ListingDown(seatId, kind, listingId, seqIndex, seqCount,
                totalEntries, totalAvailable, Arrays.asList(entries).subList(0, entryCount));
    }

    // ---- validation laws (shared by encode + decode; throw IllegalArgumentException) ----

    private static void validateFrame(ListingDown down) {
        Objects.requireNonNull(down, "down");
        validateHeader(down.seatId(), down.kind(), down.listingId(), down.seqIndex(),
                down.seqCount(), down.totalEntries(), down.totalAvailable(),
                down.entries().size());
        for (ListingEntry entry : down.entries()) {
            validateEntry(entry, down.kind());
        }
    }

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

    private static void validateEntry(ListingEntry entry, int kind) {
        Objects.requireNonNull(entry, "entry");
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
        boolean wantsOccupant = markCarriesOccupant(mark);
        // A ship row's occupantLabel is OPTIONAL — the
        // ship's display name, DISPLAY ONLY, validated below by the seat-name law when present;
        // every other kind keeps the exact-presence law.
        if (kindOfMark(mark) != KIND_SHIP && wantsOccupant == (entry.occupantLabel() == null)) {
            throw new IllegalArgumentException("occupantLabel must be present exactly on the"
                    + " occupant-carrying marks; markCode=" + mark
                    + " label=" + (entry.occupantLabel() == null ? "absent" : "present"));
        }
        if (entry.occupantLabel() != null) {
            validateOccupantLabel(entry.occupantLabel());
        }
        // W12 L2 + D4a: /mute and /kick rows share the /transfer shape (label + #wireId paste);
        // so do the /switchteams rows; and the /ban + /unban menu rows
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
            // targetLabel = the version label (REQUIRED, the 24-char law);
            // hullWireId = the jars-digest prefix, ANY long (a digest may start with zero bytes).
            if (entry.targetLabel() == null) {
                throw new IllegalArgumentException("targetLabel (version) is REQUIRED on modset rows");
            }
            validateTargetLabel(entry.targetLabel());
        } else {
            // A ship row MAY carry its hull id here, under the
            // same 24-char printable-ASCII law; every other kind still forbids the field.
            if (entry.targetLabel() != null && kind != KIND_SHIP) {
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

    /** Which listing kind a mark code belongs to. */
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
                // The ban menu row (a player) and the unban menu row (the stored
                // ban label or its cid fallback) both carry the occupant field
                || markCode == MARK_BAN_PLAYER || markCode == MARK_UNBAN_ENTRY;
    }

    /** Token law: 1..64 spaceless printable ASCII — the exact grammar-resolvable string. */
    public static void validateToken(String token) {
        Objects.requireNonNull(token, "token");
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
        Objects.requireNonNull(label, "targetLabel");
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
     * Occupant-label law: the seat-name contract (trimmed, non-blank,
     * ≤{@link #OCCUPANT_LABEL_MAX_CHARS} chars, ≤{@link #OCCUPANT_LABEL_BUFFER_BYTES} UTF-8
     * bytes, single-line printable) PLUS the widened smuggle-rejection of
     * the picker-paste design's check-3: C1 controls (U+0080–U+009F incl. NEL),
     * LS/PS (U+2028/U+2029), and bidi controls (U+061C, U+200E/U+200F, U+202A–U+202E,
     * U+2066–U+2069) all REJECT — this is the family's one player-authored field.
     */
    public static void validateOccupantLabel(String label) {
        Objects.requireNonNull(label, "occupantLabel");
        if (label.isEmpty() || !label.equals(label.trim())) {
            throw new IllegalArgumentException("occupantLabel must be trimmed and non-blank");
        }
        if (label.length() > OCCUPANT_LABEL_MAX_CHARS) {
            throw new IllegalArgumentException("occupantLabel must be <= "
                    + OCCUPANT_LABEL_MAX_CHARS + " chars, got " + label.length());
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(
                        "occupantLabel must be single-line printable (control at index " + i + ")");
            }
            if (isWidenedSmuggleChar(c)) {
                throw new IllegalArgumentException(
                        "occupantLabel carries a C1/separator/bidi control at index " + i);
            }
        }
        if (label.getBytes(StandardCharsets.UTF_8).length > OCCUPANT_LABEL_BUFFER_BYTES) {
            throw new IllegalArgumentException("occupantLabel must encode to <= "
                    + OCCUPANT_LABEL_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /** The check-3 widened strip set (REJECTED here; the emitter strips before encode) — ONE
     * definition: {@link WireTextLaw#isWidenedSmuggleChar}. */
    public static boolean isWidenedSmuggleChar(char c) {
        return WireTextLaw.isWidenedSmuggleChar(c);
    }

    // ---- entry halves ----

    private static int putEntry(byte[] buf, int off, ListingEntry entry) {
        byte[] tokenBytes = entry.token().getBytes(StandardCharsets.US_ASCII);
        buf[off] = (byte) tokenBytes.length;
        System.arraycopy(tokenBytes, 0, buf, off + 1, tokenBytes.length);
        off += 1 + TOKEN_BUFFER_BYTES;
        buf[off++] = (byte) entry.markCode();
        buf[off++] = (byte) (entry.claimedNextRespawn() ? FLAG_CLAIMED_NEXT_RESPAWN : 0);
        byte[] targetBytes = entry.targetLabel() == null
                ? new byte[0]
                : entry.targetLabel().getBytes(StandardCharsets.US_ASCII);
        buf[off] = (byte) targetBytes.length;
        System.arraycopy(targetBytes, 0, buf, off + 1, targetBytes.length);
        off += 1 + TARGET_LABEL_BUFFER_BYTES;
        byte[] occupantBytes = entry.occupantLabel() == null
                ? new byte[0]
                : entry.occupantLabel().getBytes(StandardCharsets.UTF_8);
        buf[off] = (byte) occupantBytes.length;
        System.arraycopy(occupantBytes, 0, buf, off + 1, occupantBytes.length);
        off += 1 + OCCUPANT_LABEL_BUFFER_BYTES;
        // bytes beyond every length stay zero (fresh array) — the fail-closed padding contract.
        off = putLong(buf, off, entry.hullWireId());
        return off;
    }

    private static ListingEntry readEntry(byte[] frame, int off, int kind) throws IOException {
        String token = readAsciiBuffer(frame, off, TOKEN_BUFFER_BYTES, 1, "token");
        off += 1 + TOKEN_BUFFER_BYTES;
        int markCode = frame[off++] & 0xFF;
        int flags = frame[off++] & 0xFF;
        if ((flags & ~FLAG_CLAIMED_NEXT_RESPAWN) != 0) {
            throw new IOException("listing entry flags carry MBZ bits: 0x"
                    + Integer.toHexString(flags));
        }
        String targetLabel = readAsciiBuffer(frame, off, TARGET_LABEL_BUFFER_BYTES, 0, "targetLabel");
        off += 1 + TARGET_LABEL_BUFFER_BYTES;
        String occupantLabel = readUtf8Buffer(frame, off, OCCUPANT_LABEL_BUFFER_BYTES, "occupantLabel");
        off += 1 + OCCUPANT_LABEL_BUFFER_BYTES;
        long hullWireId = getLong(frame, off);
        ListingEntry entry = new ListingEntry(token, markCode,
                (flags & FLAG_CLAIMED_NEXT_RESPAWN) != 0, targetLabel, occupantLabel, hullWireId);
        validateEntry(entry, kind);
        return entry;
    }

    /** len(u8)+zero-padded ASCII buffer; {@code minLen} 0 means an empty sentinel = null. */
    private static String readAsciiBuffer(byte[] frame, int off, int bufferBytes, int minLen,
            String what) throws IOException {
        int len = frame[off] & 0xFF;
        if (len < minLen || len > bufferBytes) {
            throw new IOException("listing " + what + " length out of range [" + minLen + ","
                    + bufferBytes + "]: " + len);
        }
        int start = off + 1;
        for (int i = start + len; i < start + bufferBytes; i++) {
            if (frame[i] != 0) {
                throw new IOException("listing " + what + " nonzero padding beyond len=" + len);
            }
        }
        if (len == 0) {
            return null;
        }
        for (int i = start; i < start + len; i++) {
            if ((frame[i] & 0xFF) >= 0x7f || (frame[i] & 0xFF) < 0x20) {
                throw new IOException("listing " + what + " is not printable ASCII");
            }
        }
        return new String(frame, start, len, StandardCharsets.US_ASCII);
    }

    /** len(u8)+zero-padded UTF-8 buffer with the round-trip guard; len 0 = absent (null). */
    private static String readUtf8Buffer(byte[] frame, int off, int bufferBytes, String what)
            throws IOException {
        int len = frame[off] & 0xFF;
        if (len > bufferBytes) {
            throw new IOException("listing " + what + " length out of range [0," + bufferBytes
                    + "]: " + len);
        }
        int start = off + 1;
        for (int i = start + len; i < start + bufferBytes; i++) {
            if (frame[i] != 0) {
                throw new IOException("listing " + what + " nonzero padding beyond len=" + len);
            }
        }
        if (len == 0) {
            return null;
        }
        String value = new String(frame, start, len, StandardCharsets.UTF_8);
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len
                || !Arrays.equals(frame, start, start + len, reencoded, 0, len)) {
            throw new IOException("listing " + what + " is not valid UTF-8");
        }
        return value;
    }

}
