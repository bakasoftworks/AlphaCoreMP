package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ProtocolRegistryEntry;

/**
 * Metadata for one serialized offline-combat step frame.
 *
 * <p>The payload remains an {@link OfflineCombatStepResult}; the frame adds the
 * ordered publication sequence, bounded payload size, and CRC32 readback proof.
 */
public record OfflineCombatStepWireFrame(
        long sequence,
        OfflineCombatStepResult result,
        int payloadByteCount,
        long checksumCrc32) {

    public OfflineCombatStepWireFrame {
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be >= 0; got " + sequence);
        }
        if (result == null) {
            throw new NullPointerException("result");
        }
        if (payloadByteCount <= 0) {
            throw new IllegalArgumentException("payloadByteCount must be > 0; got " + payloadByteCount);
        }
        if (checksumCrc32 < 0L || checksumCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("checksumCrc32 must be an unsigned 32-bit value");
        }
    }

    public ProtocolRegistryEntry registryEntry() {
        return OfflineCombatStepWireCodec.registryEntry();
    }

    public int messageTypeCode() {
        return registryEntry().wireTypeCode();
    }
}
