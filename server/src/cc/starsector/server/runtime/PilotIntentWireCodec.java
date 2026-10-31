package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getFloat;
import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putByte;
import static cc.starsector.server.runtime.WireByteOps.putFloat;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for {@link PilotIntentFrame} — the faithful pilot lane's missing socket
 * representation. Mirrors the framing discipline of
 * {@link OfflineCombatStepWireCodec} (magic + schema version + fixed-width payload + CRC32) so a
 * frame survives a real TCP stream: a single {@link #writeFrame} emits exactly {@link #FRAME_BYTES}
 * bytes, and {@link #readFrame} reads exactly that many and validates magic / version / CRC before
 * decoding. Game-free (java.base only), so it is exercisable by a real off-loopback socket
 * round-trip without the engine.
 *
 * <p><b>Allowlist-clean transport.</b> This codec is folded into the client mod jar
 * (the in-game pilot seam reuses it via {@link cc.starsector.server.client.PilotIntentClientSession}),
 * where it is loaded by Starsector's script classloader {@code com.fs.starfarer.loading.scripts.B}.
 * That loader is a STATIC name blocklist: it denies
 * {@code java.io.DataOutputStream}/{@code DataInputStream}/{@code ByteArrayOutputStream} (none are on
 * its allowlist), which is what failed five live test runs. So the (de)serialization here builds
 * directly into a fixed {@code byte[}{@link #FRAME_BYTES}{@code ]} using only the shared
 * {@link WireByteOps} big-endian byte ops (folded into the mod jar beside this
 * codec) and the allowlisted {@code java.util.zip.CRC32} — never {@code Data*Stream}/{@code ByteArrayOutputStream}
 * — exactly like the proven {@code WireBytes}/{@code ServerSnapshotClient} discipline.
 * The wire bytes are unchanged from the prior {@code DataOutputStream} encoding, so every off-engine
 * byte-identity proof still holds.
 */
public final class PilotIntentWireCodec {

    /** Magic = ASCII "PINT" (Pilot INTent). */
    public static final int MAGIC = 0x50494E54;
    /**
     * Schema v2 (the facing-frame fix) appended {@code ownShipFacingDeg} (the own ship's
     * real A/D-driven heading, distinct from the cursor aim) to the v1 payload. Schema v3 (the aim
     * carry) appends {@code aimWorldX}/{@code aimWorldY} — the player's aim
     * WORLD POINT ({@code getMouseTarget()}), so the server can drive its engine's own per-frame
     * mount-slew target instead of leaving it at world origin. Schema v4 (the autofire mirror)
     * appends {@code autofireGroupsMask} — the client engine's own per-group {@code isAutofiring()}
     * toggle state ({@code -1} = absent), so the server can mirror autofire via the engine's own
     * {@code TOGGLE_AUTOFIRE}. Schema v5 (the carry-cells bump) appends {@code targetWireId}
     * (8 B — the R-key ship-target designation; {@code -1} ABSENT / {@code -2} NONE) and
     * {@code fireEdgeSeq} (1 B unsigned — the wrapping fire rising-edge counter arming the server's
     * held-lane depth-1 latch); the retreat cell rides {@code heldControls} bit 9 for zero bytes.
     * Schema v6 (the tube mirror) appends {@code activeTubeOrdinal} (1 B signed — the
     * selected ALTERNATING group's active-tube pointer, {@code -1} = absent), so the server can
     * conform its hull's tube pointer at fire-quiescent ticks (re-selects are wire-invisible
     * edges; the pointer must ride as STATE). Schema v7 (the raw input levels)
     * appends the pilot's held keys and mouse buttons as LEVELS: {@code key[8]} (u8 each —
     * the held LWJGL key codes strictly ascending, then zero pad: {@link PilotIntentFrame#keyCodes}),
     * {@code mouseButtons} (u8, bit b = button b held) and {@code controlKeys} (u16 — bit i = the pilot's own
     * key for {@link PilotIntentFrame#CONTROL_KEY_NAMES}{@code [i]} held), so the server can answer a ship-scoped
     * script's key poll with that seat's own keys (+11 B). Versions are different widths, so the
     * version check fail-closes an old peer against a new server (and vice versa) — as with v1→v2,
     * both halves ship in lockstep (one shared codec class folded into the client jar); RECORDED frames
     * of v5 / v6 / v7 still decode through {@link #decodeRecorded} / {@link #readFrameRecorded}.
     */
    public static final short SCHEMA_VERSION = 7;
    public static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Short.BYTES; // 8
    /** The v6 payload width — the recorded-corpus table's v6 row (a v5 body is this minus the tube byte). */
    private static final int PAYLOAD_BYTES_V6 =
            Long.BYTES + Integer.BYTES + Float.BYTES + Integer.BYTES + Integer.BYTES + Byte.BYTES
            + Float.BYTES + Float.BYTES + Float.BYTES
            + Integer.BYTES
            + Long.BYTES + Byte.BYTES
            + Byte.BYTES; // 51 (v2: + ownShipFacingDeg; v3: + aimWorldX/Y; v4: + autofireGroupsMask; v5: + targetWireId + fireEdgeSeq; v6: + activeTubeOrdinal)
    /** The v7 raw-input-level tail: one byte per key slot + {@code mouseButtons} u8 + {@code controlKeys} u16. */
    private static final int KEY_LEVEL_BYTES = PilotIntentFrame.KEY_SLOTS + Byte.BYTES + Short.BYTES; // 11
    public static final int PAYLOAD_BYTES = PAYLOAD_BYTES_V6 + KEY_LEVEL_BYTES; // 62
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 74

    private PilotIntentWireCodec() {}

    /** Serialize {@code frame} to {@code out} as exactly {@link #FRAME_BYTES} bytes (header + payload + CRC32). */
    public static void writeFrame(OutputStream out, PilotIntentFrame frame) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeFrame(frame);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Encode {@code frame} into a fresh {@link #FRAME_BYTES}-long array (header + payload + CRC32),
     * using only allowlist-clean big-endian byte ops (no {@code Data*Stream}/{@code ByteArrayOutputStream})
     * so the in-game pilot seam can serialize under the script classloader {@code B}. The
     * bytes are identical to the prior {@code DataOutputStream} encoding. Used by {@link #writeFrame} and
     * by {@link cc.starsector.server.client.PilotIntentClientSession#sendIntent} (which digests then
     * writes exactly these bytes).
     */
    public static byte[] encodeFrame(PilotIntentFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] buf = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MAGIC);
        off = putShort(buf, off, SCHEMA_VERSION);
        off = putShort(buf, off, HEADER_BYTES);
        off = putLong(buf, off, frame.shipId());
        off = putInt(buf, off, frame.frameSeq());
        off = putFloat(buf, off, frame.cursorFacingDeg());
        off = putInt(buf, off, frame.heldControls());
        off = putInt(buf, off, frame.selectedWeaponGroup());
        off = putByte(buf, off, frame.fireHeld() ? 1 : 0);
        off = putFloat(buf, off, frame.ownShipFacingDeg()); // v2 (own-ship heading)
        off = putFloat(buf, off, frame.aimWorldX()); // v3 (aim carry)
        off = putFloat(buf, off, frame.aimWorldY()); // v3 (aim carry)
        off = putInt(buf, off, frame.autofireGroupsMask()); // v4 (autofire mirror)
        off = putLong(buf, off, frame.targetWireId()); // v5 (R-key target designation)
        off = putByte(buf, off, frame.fireEdgeSeq()); // v5 (fire rising-edge counter, 0..255)
        off = putByte(buf, off, frame.activeTubeOrdinal()); // v6 (active-tube pointer, signed, -1 = absent)
        // The v7 raw input levels — the eight key-code slots in slot order
        // (the canonical pack: ascending codes, then zero pad), the mouse-button byte, the control-key u16.
        for (int slot = 0; slot < PilotIntentFrame.KEY_SLOTS; slot++) {
            off = putByte(buf, off, frame.keyCode(slot));
        }
        off = putByte(buf, off, frame.mouseButtons());
        off = putShort(buf, off, frame.controlKeys());
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@link PilotIntentFrame} from {@code in}, blocking until {@link #FRAME_BYTES}
     * arrive. Returns {@code null} at a clean end of stream (zero bytes available). Throws on a
     * truncated frame, bad magic / version / header size, or a CRC mismatch.
     */
    public static PilotIntentFrame readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRAME_BYTES) {
            throw new IOException("pilot intent wire frame truncated: expected " + FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        int magic = getInt(frame, 0);
        if (magic != MAGIC) {
            throw new IOException("pilot intent wire frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(frame);
    }

    /**
     * The demux-read half: the caller (the up-lane magic peek routing {@code PINT} vs
     * {@code PDCL}, {@link PilotIntentSocketServer}) has already consumed the 4 magic bytes; read the
     * remaining {@link #FRAME_BYTES}{@code - 4} bytes and validate the reconstructed full frame (the
     * CRC covers the magic too, so the wire bytes checked are identical to {@link #readFrame}'s).
     * Fail-closed like {@link #readFrame}; an EOF here is a truncation, never a clean end.
     */
    public static PilotIntentFrame readFrameAfterMagic(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] rest = in.readNBytes(FRAME_BYTES - Integer.BYTES);
        if (rest.length != FRAME_BYTES - Integer.BYTES) {
            throw new IOException("pilot intent wire frame truncated after magic: expected "
                    + (FRAME_BYTES - Integer.BYTES) + " bytes, got " + rest.length);
        }
        byte[] frame = new byte[FRAME_BYTES];
        putInt(frame, 0, MAGIC);
        System.arraycopy(rest, 0, frame, Integer.BYTES, rest.length);
        return decodeAfterMagic(frame);
    }

    /**
     * The DATAGRAM entry — one UDP payload IS one whole
     * frame (magic included), so it must be exactly {@link #FRAME_BYTES}: a valid frame plus trailing
     * bytes is refused (the dispatch site
     * used to stream {@link #readFrameAfterMagic} over the payload and the junk rode into the wire
     * record). The TCP demux ({@code UpLaneDemux}) keeps the stream readers: there the NEXT frame
     * legitimately follows on the same stream, so a remaining-bytes check would be wrong. This is the
     * LIVE door's entry: current schema only; a recorded frame of an older schema decodes through
     * {@link #decodeRecorded}.
     */
    public static PilotIntentFrame decodeDatagram(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != FRAME_BYTES) {
            throw new IOException("pilot intent datagram frame must be " + FRAME_BYTES
                    + " bytes, got " + payload.length);
        }
        int magic = getInt(payload, 0);
        if (magic != MAGIC) {
            throw new IOException("pilot intent wire frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(payload);
    }

    /**
     * RECORDED-CORPUS compat read: decode one frame whose width follows its own
     * header version by {@link #payloadBytesFor} — the current v7 (74 B), v6 (63 B — no raw input
     * levels: keys {@link PilotIntentFrame#KEYS_EMPTY}, mouse / control 0) or v5 (62 B — also no
     * {@code activeTubeOrdinal} byte: {@link PilotIntentFrame#TUBE_ORDINAL_ABSENT}). For committed
     * {@code .ccwr} evidence corpora and their stream readers (the up-record verifier, the replay
     * reader, the in-game intent-source reader and the offline reel tools) ONLY — a LIVE wire lane
     * must keep the strict {@link #readFrame}/{@link #readFrameAfterMagic}, whose version check
     * fail-closes an old peer by design (both halves ship in lockstep). Same magic/CRC/truncation
     * fail-closed discipline as {@link #readFrame}, per accepted width; allowlist-clean like the rest
     * of the codec (loader-B safe — the in-game intent-source reader calls this).
     */
    public static PilotIntentFrame readFrameRecorded(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] header = in.readNBytes(HEADER_BYTES);
        if (header.length == 0) {
            return null; // clean end of stream (readFrame parity)
        }
        if (header.length != HEADER_BYTES) {
            throw new IOException("pilot intent wire frame truncated: expected " + HEADER_BYTES
                    + " header bytes, got " + header.length);
        }
        int magic = getInt(header, 0);
        if (magic != MAGIC) {
            throw new IOException("pilot intent wire frame bad magic: 0x" + Integer.toHexString(magic));
        }
        int version = getUnsignedShort(header, Integer.BYTES);
        int payloadBytes = payloadBytesFor(version);
        if (payloadBytes < 0) {
            throw new IOException("pilot intent wire frame unsupported schema version: " + version);
        }
        byte[] rest = in.readNBytes(payloadBytes + Integer.BYTES);
        if (rest.length != payloadBytes + Integer.BYTES) {
            throw new IOException("pilot intent wire frame truncated: expected "
                    + (payloadBytes + Integer.BYTES) + " body bytes, got " + rest.length);
        }
        byte[] frame = new byte[HEADER_BYTES + payloadBytes + Integer.BYTES];
        System.arraycopy(header, 0, frame, 0, HEADER_BYTES);
        System.arraycopy(rest, 0, frame, HEADER_BYTES, rest.length);
        return decodeFields(frame, version);
    }

    /**
     * The RECORDED-frame entry for one whole frame held in a byte array
     * (a TAKES row's frame in a re-simulation, one UP record): the width is EXACT per the frame's own header
     * version by {@link #payloadBytesFor} — v7 = {@link #FRAME_BYTES}, v6 = 63 B, v5 = 62 B; any other length
     * or version, a bad magic or a CRC mismatch is an {@link IOException}. A v5 / v6 frame decodes with
     * the raw input levels EMPTY (those recordings never carried them, so a re-simulation answers no key
     * held, as the recording's serve did); a v5 frame also has the tube ABSENT. A LIVE lane never calls
     * this: the door's {@link #decodeDatagram} stays strict to the current schema.
     */
    public static PilotIntentFrame decodeRecorded(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < HEADER_BYTES) {
            throw new IOException("pilot intent recorded frame truncated: " + frame.length + " bytes");
        }
        int magic = getInt(frame, 0);
        if (magic != MAGIC) {
            throw new IOException("pilot intent wire frame bad magic: 0x" + Integer.toHexString(magic));
        }
        int version = getUnsignedShort(frame, Integer.BYTES);
        int payloadBytes = payloadBytesFor(version);
        if (payloadBytes < 0) {
            throw new IOException("pilot intent wire frame unsupported schema version: " + version);
        }
        int width = HEADER_BYTES + payloadBytes + Integer.BYTES;
        if (frame.length != width) {
            throw new IOException("pilot intent recorded v" + version + " frame must be " + width
                    + " bytes, got " + frame.length);
        }
        return decodeFields(frame, version);
    }

    /**
     * The ONE per-version payload width table the recorded readers and the
     * field decoder share — v5 = 50 B (no tube byte), v6 = 51 B (no raw input levels), v7 = {@link #PAYLOAD_BYTES};
     * any other version answers -1 (refused by every caller).
     */
    private static int payloadBytesFor(int version) {
        return switch (version) {
            case 5 -> PAYLOAD_BYTES_V6 - Byte.BYTES;
            case 6 -> PAYLOAD_BYTES_V6;
            case 7 -> PAYLOAD_BYTES;
            default -> -1;
        };
    }

    private static PilotIntentFrame decodeAfterMagic(byte[] frame) throws IOException {
        return decodeFields(frame, SCHEMA_VERSION);
    }

    /** {@code frame} is exactly {@code HEADER_BYTES + payloadBytesFor(expectedVersion) + 4} long (every caller checked). */
    private static PilotIntentFrame decodeFields(byte[] frame, int expectedVersion) throws IOException {
        int payloadBytes = payloadBytesFor(expectedVersion);
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + payloadBytes);
        int expectedCrc = (int) crc.getValue();
        int off = Integer.BYTES; // magic already validated/reconstructed by the caller
        int version = getUnsignedShort(frame, off); off += 2;
        if (version != expectedVersion) {
            throw new IOException("pilot intent wire frame unsupported schema version: " + version);
        }
        int headerSize = getUnsignedShort(frame, off); off += 2;
        if (headerSize != HEADER_BYTES) {
            throw new IOException("pilot intent wire frame bad header size: " + headerSize);
        }
        long shipId = getLong(frame, off); off += 8;
        int frameSeq = getInt(frame, off); off += 4;
        float cursorFacingDeg = getFloat(frame, off); off += 4;
        int heldControls = getInt(frame, off); off += 4;
        int selectedWeaponGroup = getInt(frame, off); off += 4;
        boolean fireHeld = frame[off] != 0; off += 1;
        float ownShipFacingDeg = getFloat(frame, off); off += 4; // v2 (own-ship heading)
        float aimWorldX = getFloat(frame, off); off += 4; // v3 (aim carry)
        float aimWorldY = getFloat(frame, off); off += 4; // v3 (aim carry)
        int autofireGroupsMask = getInt(frame, off); off += 4; // v4 (autofire mirror)
        long targetWireId = getLong(frame, off); off += 8; // v5 (R-key target designation)
        int fireEdgeSeq = frame[off] & 0xFF; off += 1; // v5 (fire rising-edge counter, unsigned)
        // v6 (active-tube pointer, SIGNED: -1 = absent); a recorded v5 body ends here.
        int activeTubeOrdinal = expectedVersion >= 6
                ? frame[off++] : PilotIntentFrame.TUBE_ORDINAL_ABSENT;
        // v7 raw input levels; a recorded v5 / v6 body ends before them and
        // decodes EMPTY. The canonical-pack law (ascending codes, then zero pad) is the record's constructor, so a
        // hole, a repeat or a descending pair fails below as a fields-invalid IOException.
        long keyCodes = PilotIntentFrame.KEYS_EMPTY;
        int mouseButtons = 0;
        int controlKeys = 0;
        if (expectedVersion >= 7) {
            for (int slot = 0; slot < PilotIntentFrame.KEY_SLOTS; slot++) {
                keyCodes = PilotIntentFrame.withKeyCode(keyCodes, slot, frame[off++]);
            }
            mouseButtons = frame[off++] & 0xFF;
            controlKeys = getUnsignedShort(frame, off); off += 2;
        }
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("pilot intent wire frame CRC mismatch");
        }
        try {
            return new PilotIntentFrame(
                    shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld, selectedWeaponGroup,
                    ownShipFacingDeg, aimWorldX, aimWorldY, autofireGroupsMask, targetWireId,
                    fireEdgeSeq, activeTubeOrdinal, keyCodes, mouseButtons, controlKeys);
        } catch (IllegalArgumentException badFields) {
            // A CRC-VALID frame whose fields are out
            // of the record's domain (NaN/inf aim floats, negative shipId/frameSeq, stray held
            // bits) is a malformed WIRE frame, so it fails closed as IOException like every
            // sibling reader — the server's SNAP dispatch catches IOException only; an escaping
            // IllegalArgumentException killed the datagram reader thread (whole-server deaf).
            throw new IOException(
                    "pilot intent wire frame fields invalid: " + badFields.getMessage(), badFields);
        }
    }

}
