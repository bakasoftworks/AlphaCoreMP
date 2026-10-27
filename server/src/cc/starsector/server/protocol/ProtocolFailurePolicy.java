package cc.starsector.server.protocol;

/**
 * Product-owned failure metadata for protocol validation or compatibility rejects.
 */
public record ProtocolFailurePolicy(
        String failureCode,
        boolean failClosed) {

    public ProtocolFailurePolicy {
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must be non-blank");
        }
    }

    public ProtocolFailureResult reject(String reason) {
        return new ProtocolFailureResult(failureCode, reason, failClosed);
    }
}
