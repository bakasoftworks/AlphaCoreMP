package cc.starsector.server.protocol;

/** Structured protocol failure result for diagnostics and tests. */
public record ProtocolFailureResult(
        String failureCode,
        String reason,
        boolean failClosed) {

    public ProtocolFailureResult {
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must be non-blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must be non-blank");
        }
    }
}
