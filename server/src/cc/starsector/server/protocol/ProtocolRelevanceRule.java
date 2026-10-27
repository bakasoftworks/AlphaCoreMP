package cc.starsector.server.protocol;

/**
 * Product-owned relevance metadata for deciding who should receive a message.
 */
public record ProtocolRelevanceRule(
        String ruleId,
        boolean ownerScoped,
        boolean visibilityScoped) {

    public ProtocolRelevanceRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must be non-blank");
        }
    }
}
