package cc.starsector.server.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic owner-scoped command arbitration for concurrent clients.
 */
public final class ConcurrentCommandAuthorityArbiter {
    private final ProtocolRelevanceRule relevanceRule;

    public ConcurrentCommandAuthorityArbiter(ProtocolRelevanceRule relevanceRule) {
        this.relevanceRule = Objects.requireNonNull(relevanceRule, "relevanceRule");
        if (!relevanceRule.ownerScoped()) {
            throw new IllegalArgumentException("concurrent command authority requires owner-scoped relevance");
        }
    }

    public List<CommandAuthorityDecision> arbitrate(List<ConcurrentCommandClaim> claims) {
        Objects.requireNonNull(claims, "claims");
        List<ConcurrentCommandClaim> orderedClaims = new ArrayList<>(claims.size());
        for (ConcurrentCommandClaim claim : claims) {
            orderedClaims.add(Objects.requireNonNull(claim, "claim"));
        }
        orderedClaims.sort(Comparator
                .comparingLong(ConcurrentCommandClaim::targetShipId)
                .thenComparing(claim -> claim.client().clientId())
                .thenComparingLong(ConcurrentCommandClaim::simTick));

        Set<Long> grantedShips = new HashSet<>();
        List<CommandAuthorityDecision> decisions = new ArrayList<>(orderedClaims.size());
        for (ConcurrentCommandClaim claim : orderedClaims) {
            boolean ownsTarget = claim.client().ownedShipId() == claim.targetShipId();
            boolean alreadyGranted = grantedShips.contains(Long.valueOf(claim.targetShipId()));
            boolean granted = ownsTarget && !alreadyGranted;
            String reason;
            ReliabilityLane lane;
            if (granted) {
                grantedShips.add(Long.valueOf(claim.targetShipId()));
                reason = "owner_scoped_authority_granted";
                lane = ReliabilityLane.DURABLE_GAMEPLAY_EVENT;
            } else if (!ownsTarget) {
                reason = "owner_scoped_authority_denied: client " + claim.client().clientId()
                        + " owns ship " + claim.client().ownedShipId()
                        + " not target " + claim.targetShipId();
                lane = ReliabilityLane.CONTROL_SETUP;
            } else {
                reason = "owner_scoped_authority_denied: target ship "
                        + claim.targetShipId() + " already granted";
                lane = ReliabilityLane.CONTROL_SETUP;
            }
            decisions.add(new CommandAuthorityDecision(claim, granted, reason, relevanceRule, lane));
        }
        return List.copyOf(decisions);
    }

    public record ClientAuthorityIdentity(String clientId, long ownedShipId) {
        public ClientAuthorityIdentity {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId must be non-blank");
            }
            if (ownedShipId <= 0L) {
                throw new IllegalArgumentException("ownedShipId must be > 0; got " + ownedShipId);
            }
        }
    }

    public record ConcurrentCommandClaim(
            ClientAuthorityIdentity client,
            long targetShipId,
            long simTick) {

        public ConcurrentCommandClaim {
            client = Objects.requireNonNull(client, "client");
            if (targetShipId <= 0L) {
                throw new IllegalArgumentException("targetShipId must be > 0; got " + targetShipId);
            }
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
        }
    }

    public record CommandAuthorityDecision(
            ConcurrentCommandClaim claim,
            boolean granted,
            String reason,
            ProtocolRelevanceRule relevanceRule,
            ReliabilityLane lane) {

        public CommandAuthorityDecision {
            claim = Objects.requireNonNull(claim, "claim");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must be non-blank");
            }
            relevanceRule = Objects.requireNonNull(relevanceRule, "relevanceRule");
            lane = Objects.requireNonNull(lane, "lane");
        }
    }
}
