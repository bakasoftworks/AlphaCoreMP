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
     * edges; the pointer must ride as STATE). Versions are different widths, so the
     * version check fail-closes an old peer against a new server (and vice versa) — as with v1→v2,
     * both halves ship in lockstep (one shared codec class folded into the client jar).
     */
    public static final short SCHEMA_VERSION = 6;
    public static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Short.BYTES; // 8
    public static final int PAYLOAD_BYTES =
            Long.BYTES + Integer.BYTES + Float.BYTES + Integer.BYTES + Integer.BYTES + Byte.BYTES
            + Float.BYTES + Float.BYTES + Float.BYTES
            + Integer.BYTES
            + Long.BYTES + Byte.BYTES
            + Byte.BYTES; // 51 (v2: + ownShipFacingDeg; v3: + aimWorldX/Y; v4: + autofireGroupsMask; v5: + targetWireId + fireEdgeSeq; v6: + activeTubeOrdinal)
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 63

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
     * legitimately follows on the same stream, so a remaining-bytes check would be wrong.
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
     * RECORDED-CORPUS compat read: decode one frame that may be the current v6
     * width OR the immediately-prior v5 width (62 B — no {@code activeTubeOrdinal} byte; the field
     * decodes to {@link PilotIntentFrame#TUBE_ORDINAL_ABSENT}). For committed {@code .ccwr}
     * evidence corpora and their readers ({@code CcwrUpReplayReader}, {@code CcwrDeadClocksScan},
     * {@code CcwrIntentSourceReader}, {@code CcwrFireLoopAudit}) ONLY — a LIVE wire lane must keep
     * the strict {@link #readFrame}/{@link #readFrameAfterMagic}, whose version check fail-closes
     * an old peer by design (both halves ship in lockstep). Same magic/CRC/truncation fail-closed
     * discipline as {@link #readFrame}, per accepted width; allowlist-clean like the rest of the
     * codec (loader-B safe — {@code CcwrIntentSourceReader} calls this in-game).
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
        if (version != SCHEMA_VERSION && version != 5) {
            throw new IOException("pilot intent wire frame unsupported schema version: " + version);
        }
        int payloadBytes = version == 5 ? PAYLOAD_BYTES - Byte.BYTES : PAYLOAD_BYTES;
        byte[] rest = in.readNBytes(payloadBytes + Integer.BYTES);
        if (rest.length != payloadBytes + Integer.BYTES) {
            throw new IOException("pilot intent wire frame truncated: expected "
                    + (payloadBytes + Integer.BYTES) + " body bytes, got " + rest.length);
        }
        byte[] frame = new byte[HEADER_BYTES + payloadBytes + Integer.BYTES];
        System.arraycopy(header, 0, frame, 0, HEADER_BYTES);
        System.arraycopy(rest, 0, frame, HEADER_BYTES, rest.length);
        return decodeFields(frame, version, payloadBytes);
    }

    private static PilotIntentFrame decodeAfterMagic(byte[] frame) throws IOException {
        return decodeFields(frame, SCHEMA_VERSION, PAYLOAD_BYTES);
    }

    private static PilotIntentFrame decodeFields(byte[] frame, int expectedVersion, int payloadBytes)
            throws IOException {
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
        int activeTubeOrdinal = payloadBytes == PAYLOAD_BYTES
                ? frame[off++] : PilotIntentFrame.TUBE_ORDINAL_ABSENT;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("pilot intent wire frame CRC mismatch");
        }
        try {
            return new PilotIntentFrame(
                    shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld, selectedWeaponGroup,
                    ownShipFacingDeg, aimWorldX, aimWorldY, autofireGroupsMask, targetWireId,
                    fireEdgeSeq, activeTubeOrdinal);
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
