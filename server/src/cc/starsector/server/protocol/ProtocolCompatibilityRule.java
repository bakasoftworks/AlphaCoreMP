package cc.starsector.server.protocol;

/**
 * Product-owned compatibility metadata for accepting a protocol schema.
 */
public record ProtocolCompatibilityRule(
        String predicateId,
        int minimumSchemaVersion,
        int maximumSchemaVersion,
        boolean requiresContentCompatibility) {

    public ProtocolCompatibilityRule {
        if (predicateId == null || predicateId.isBlank()) {
            throw new IllegalArgumentException("predicateId must be non-blank");
        }
        if (minimumSchemaVersion <= 0) {
            throw new IllegalArgumentException("minimumSchemaVersion must be > 0; got " + minimumSchemaVersion);
        }
        if (maximumSchemaVersion < minimumSchemaVersion) {
            throw new IllegalArgumentException(
                    "maximumSchemaVersion must be >= minimumSchemaVersion; got " + maximumSchemaVersion);
        }
    }

    public boolean accepts(int peerSchemaVersion, boolean contentCompatible) {
        if (peerSchemaVersion <= 0) {
            throw new IllegalArgumentException("peerSchemaVersion must be > 0; got " + peerSchemaVersion);
        }
        if (peerSchemaVersion < minimumSchemaVersion || peerSchemaVersion > maximumSchemaVersion) {
            return false;
        }
        return !requiresContentCompatibility || contentCompatible;
    }
}
