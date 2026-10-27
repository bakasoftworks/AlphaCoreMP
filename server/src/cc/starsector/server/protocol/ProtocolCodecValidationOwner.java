package cc.starsector.server.protocol;

/**
 * Product-owned owner metadata for a protocol message's codec and validation.
 */
public record ProtocolCodecValidationOwner(
        String codecOwner,
        String validationOwner) {

    public ProtocolCodecValidationOwner {
        if (codecOwner == null || codecOwner.isBlank()) {
            throw new IllegalArgumentException("codecOwner must be non-blank");
        }
        if (validationOwner == null || validationOwner.isBlank()) {
            throw new IllegalArgumentException("validationOwner must be non-blank");
        }
    }
}
