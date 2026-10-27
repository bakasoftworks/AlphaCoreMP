package cc.starsector.client.net;

import java.util.Objects;

/**
 * One tolerated RESERVED control frame off the multiplexed server stream:
 * a {@code DESC} descriptor-on-CTRL
 * or {@code HOUS} house/operator frame this client generation has no consumer for. The
 * decoded envelope (magic, schema, wire type code) plus the opaque body are carried so a
 * future consumer arc becomes a routing switch arm, not a reader change; today's predict
 * sessions count-ignore the whole message.
 *
 * <p>The schema field is deliberately NOT validated ({@link ClientReservedControlReader}) —
 * tolerance across future body revisions is the reservation's entire point.
 */
public final class ClientReservedControl {

    private final int magic;
    private final int schemaVersion;
    private final int wireTypeCode;
    private final byte[] body;

    public ClientReservedControl(int magic, int schemaVersion, int wireTypeCode, byte[] body) {
        this.magic = magic;
        this.schemaVersion = schemaVersion;
        this.wireTypeCode = wireTypeCode;
        this.body = Objects.requireNonNull(body, "body").clone();
    }

    /** The frame's 4-byte magic ({@code "DESC"} or {@code "HOUS"}). */
    public int magic() {
        return magic;
    }

    /** The stamped u16 schema version — carried, never validated (tolerance by design). */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** The stamped u32 wire type code — carried, never validated (the client has no registry). */
    public int wireTypeCode() {
        return wireTypeCode;
    }

    /** The opaque body bytes (CRC-verified; semantics belong to the kind's consumer arc). */
    public byte[] body() {
        return body.clone();
    }

    /** Body length in bytes (without copying the body). */
    public int bodyLength() {
        return body.length;
    }

    @Override
    public String toString() {
        return "ClientReservedControl{magic=" + Integer.toHexString(magic)
                + ",schema=" + schemaVersion
                + ",body=" + body.length + "B}";
    }
}
