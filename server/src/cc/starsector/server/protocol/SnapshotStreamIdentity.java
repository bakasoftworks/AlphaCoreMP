package cc.starsector.server.protocol;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Registry-owned identity for a bounded snapshot stream shape.
 */
public record SnapshotStreamIdentity(
        String streamId,
        int streamVersion,
        int requiredShipCount,
        int minShipCount,
        int requiredFrames,
        boolean requiresStateChanges) {

    public SnapshotStreamIdentity {
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must be non-blank");
        }
        if (streamVersion <= 0) {
            throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
        }
        if (requiredShipCount < 0) {
            throw new IllegalArgumentException("requiredShipCount must be >= 0; got " + requiredShipCount);
        }
        if (minShipCount < 0) {
            throw new IllegalArgumentException("minShipCount must be >= 0; got " + minShipCount);
        }
        if (requiredShipCount == 0 && minShipCount != 0) {
            throw new IllegalArgumentException(
                    "minShipCount must be 0 when there is no ship-count requirement; got " + minShipCount);
        }
        if (requiredShipCount > 0 && minShipCount > requiredShipCount) {
            throw new IllegalArgumentException(
                    "minShipCount must be <= requiredShipCount; got " + minShipCount + " > " + requiredShipCount);
        }
        if (requiredFrames < 0) {
            throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
        }
    }

    /**
     * Backwards-compatible constructor for the original fixed ship-count contract: the per-frame
     * ship count must equal {@code requiredShipCount} exactly (min == max). Every fixed-count
     * call site goes through this delegating constructor.
     */
    public SnapshotStreamIdentity(
            String streamId,
            int streamVersion,
            int requiredShipCount,
            int requiredFrames,
            boolean requiresStateChanges) {
        this(streamId, streamVersion, requiredShipCount, requiredShipCount, requiredFrames,
                requiresStateChanges);
    }

    public int streamTypeCode() {
        return streamTypeCode(streamId);
    }

    public boolean hasFrameRequirement() {
        return requiredFrames > 0;
    }

    public boolean hasShipCountRequirement() {
        return requiredShipCount > 0;
    }

    public static int streamTypeCode(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must be non-blank");
        }
        CRC32 crc = new CRC32();
        byte[] bytes = streamId.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }
}
