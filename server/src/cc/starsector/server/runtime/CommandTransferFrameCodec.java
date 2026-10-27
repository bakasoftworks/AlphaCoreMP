package cc.starsector.server.runtime;

import cc.starsector.server.protocol.CommandTransferControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code XFER} command-transfer control frame
 * ({@link CommandTransferControlMessage}, W3 L3 — packet {@code w3-command-transfer-packet.md}).
 * Envelope byte-identical to {@link ModuleMountFrameCodec} — {@code magic |
 * schema | headerBytes | wireTypeCode | bodyLen | body | CRC32} — registry-checked and
 * fail-closed the same way: magic / schema / wire-identity / declared-size mismatch and CRC32
 * corruption all throw {@link IOException}.
 *
 * <p>Body v1 is FIXED 34 B, no UTF: {@code u64 seatWireId | u64 fromWireId | u64 toWireId |
 * u64 podWireId | u8 kind | u8 reason} — decode REJECTS any other declared body length.
 * Kind 1 = BEGIN (a pod flight minted; {@code toWireId} = the flight's target). Kind 2 = DENY
 * (the row died with NO flight ever minted). Kind 3 = COMMIT (the dock-gated re-key executed —
 * MMNT stale-map semantics: {@code fromWireId} = the source hull's FRESH above-band id,
 * {@code toWireId} = the target's RETIRING old id; the seat's fixed wire id re-points).
 * Kind 4 = ABORT (a flight existed and died; its pod turns home). {@code podWireId} is the
 * {@code 0} sentinel through L3 (the pod is wire-invisible by the AWSS identity exclusion);
 * decode admits any ≥0 value from day one so the L4 own-view lift
 * changes no schema. Reason rides only on DENY/ABORT (BEGIN/COMMIT carry exactly
 * {@link #REASON_NONE}); an unknown reason value REJECTS both ends (255 = the explicit
 * catch-all, never a dumping ground). Validation is by construction (encode) and re-checked
 * on decode.
 */
public final class CommandTransferFrameCodec {

    /** Frame magic: {@code "XFER"}, distinct from every {@code DownFrameFamily} magic. */
    public static final int MAGIC = 0x58464552; // "XFER"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverCommandTransferWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    public static final int KIND_BEGIN = 1;
    public static final int KIND_DENY = 2;
    public static final int KIND_COMMIT = 3;
    public static final int KIND_ABORT = 4;

    public static final int REASON_NONE = 0;
    public static final int REASON_UNAVAILABLE = 1;
    public static final int REASON_TIMEOUT = 2;
    public static final int REASON_WINNER_INELIGIBLE = 3;
    public static final int REASON_TARGET_INELIGIBLE = 4;
    public static final int REASON_TARGET_DESTROYED = 5;
    public static final int REASON_COMMIT_RACE = 6;
    public static final int REASON_ENGINE_DENY = 7;
    public static final int REASON_RACE_LOST = 8;
    public static final int REASON_PILOT_LEFT = 9;
    /** W3 L4 S3: the dispatch-DENY pre-pod refusal band — begin-time
     * refusals fanned so the losing client turns home immediately, orthogonal to the
     * post-grant abort reasons 1..9. Fanned on FRESH begins only (retarget denials stay
     * chat-only — the client's denyOwn guard drops mid-transit frames by design). */
    public static final int REASON_TARGET_CLAIMED = 10;
    public static final int REASON_RATE_LIMITED = 11;
    public static final int REASON_HULL_COOLDOWN = 12;
    /** W3-FIX: the generic pre-pod fresh-begin refusal — dispatch denies
     * a begin BEFORE any pod/arbiter state exists (unavailable/mounted/unknown-target/
     * occupied/grace); itemization rides the same-tick chat line, the byte only says
     * "collapse now". Fanned on FRESH begins only, like 10..12. */
    public static final int REASON_FRESH_DENIED = 13;
    /** The seat asked for its OWN hull mid-flight — the
     * return-home verb; the flight aborts softly (pod turns home). Log-only on the client. */
    public static final int REASON_RETURN_HOME = 14;
    public static final int REASON_OTHER = 255;

    /** Fixed body: seat(8) + from(8) + to(8) + pod(8) + kind(1) + reason(1). */
    public static final int BODY_BYTES = 34;

    /**
     * One transfer edge: {seatWireId, fromWireId, toWireId, podWireId, kind, reason}.
     * Validation is by construction (encode) and re-checked on decode (a wire frame that
     * fails it is an {@link IOException}, the RAPP decode idiom). {@code toWireId} may be
     * {@code 0} only on DENY/ABORT (the not-carried race-loser class); {@code podWireId} is
     * always the {@code 0} sentinel at v1 emission but decode admits any ≥0 (the L4 lift).
     */
    public record TransferControl(long seatWireId, long fromWireId, long toWireId,
            long podWireId, int kind, int reason) {
        public TransferControl {
            if (kind < KIND_BEGIN || kind > KIND_ABORT) {
                throw new IllegalArgumentException(
                        "kind must be 1 (begin), 2 (deny), 3 (commit) or 4 (abort), got " + kind);
            }
            if (kind == KIND_DENY || kind == KIND_ABORT) {
                if (reason < REASON_UNAVAILABLE
                        || (reason > REASON_RETURN_HOME && reason != REASON_OTHER)) {
                    throw new IllegalArgumentException(
                            "deny/abort reason must be 1..14 or 255, got " + reason);
                }
            } else if (reason != REASON_NONE) {
                throw new IllegalArgumentException(
                        "begin/commit carry no reason (must be 0), got " + reason);
            }
            if (seatWireId < 1L) {
                throw new IllegalArgumentException("seatWireId must be positive: " + seatWireId);
            }
            if (fromWireId < 1L) {
                throw new IllegalArgumentException("fromWireId must be positive: " + fromWireId);
            }
            if (toWireId < 0L) {
                throw new IllegalArgumentException("toWireId must be non-negative: " + toWireId);
            }
            if ((kind == KIND_BEGIN || kind == KIND_COMMIT) && toWireId == 0L) {
                throw new IllegalArgumentException(
                        "begin/commit require a positive toWireId (got 0)");
            }
            if (podWireId < 0L) {
                throw new IllegalArgumentException("podWireId must be non-negative: " + podWireId);
            }
        }
    }

    private CommandTransferFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one XFER frame to its full wire bytes. */
    public static byte[] encode(TransferControl control) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, control);
        return out.toByteArray();
    }

    /** Write one XFER control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, TransferControl control) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(control, "control");
        byte[] body = encodeBody(control);
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
     * Read one XFER control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller (the demux peeks the magic to route the frame).
     * Validates schema, header size, wire identity, the FIXED declared body size, CRC32, and
     * the transfer law; throws {@link IOException} on any mismatch.
     */
    public static TransferControl readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("command-transfer frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("command-transfer frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes != BODY_BYTES) {
            throw new IOException("command-transfer frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("command-transfer frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(TransferControl control) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(BODY_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeLong(control.seatWireId());
        data.writeLong(control.fromWireId());
        data.writeLong(control.toWireId());
        data.writeLong(control.podWireId());
        data.writeByte(control.kind());
        data.writeByte(control.reason());
        data.flush();
        return bytes.toByteArray();
    }

    private static TransferControl decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        long seatWireId = data.readLong();
        long fromWireId = data.readLong();
        long toWireId = data.readLong();
        long podWireId = data.readLong();
        int kind = data.readUnsignedByte();
        int reason = data.readUnsignedByte();
        TransferControl control;
        try {
            control = new TransferControl(seatWireId, fromWireId, toWireId, podWireId, kind,
                    reason);
        } catch (IllegalArgumentException badFrame) {
            throw new IOException("command-transfer frame invalid", badFrame);
        }
        if (data.read() != -1) {
            throw new IOException("command-transfer frame body has trailing bytes");
        }
        return control;
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
                    .require(CommandTransferControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported command-transfer frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("command-transfer frame schema rejected by compatibility"
                    + " policy: " + unsignedSchemaVersion);
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
