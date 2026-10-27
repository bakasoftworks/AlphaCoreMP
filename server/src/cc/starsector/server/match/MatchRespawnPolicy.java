package cc.starsector.server.match;

/**
 * The parsed value of the {@code cc.match.respawnPolicy} knob — the carrier
 * (amended 2026-07-06: the T4 respawn ability is BUILT, demo v1 runs {@code disabled}).
 *
 * <p>Strict parse, fail-closed knob discipline (the {@code cc.match.shape} mint analog):
 * {@code disabled | instant | cooldown:K} with K integer seconds &gt;= 1, converted to sim ticks
 * at the fixed 60fps step; anything else THROWS. The knob is read by BOTH serve lanes (built at N
 * first, then ported to the legacy lane — which requires
 * {@code cc.server.perSideRoundEnd} OFF when enabled, fail-closed at boot); this value type is
 * game-free and unit-tested in {@code ServerContractTests}.
 *
 * <p>{@link #allowsRemountAt(long, long)} is the policy half of the ONE reconcile rule that
 * unifies the T1 fly-in and the T4 remount:
 * {@code occupied && mount==NONE && (neverMountedThisRound || policy.allows(nowTick, lossTick))}.
 * Under {@code disabled} it never allows — mount-loss latches the seat dead-for-the-round and
 * the round reset is the respawn.
 */
public final class MatchRespawnPolicy {

    /** The canonical DEFAULT step rate the 1-arg {@link #parse(String)} converts at (
     * the serve lanes pass their EFFECTIVE rate to {@link #parse(String, long)} instead). */
    public static final long SIM_TICKS_PER_SECOND = 60L;

    private enum Kind { DISABLED, INSTANT, COOLDOWN }

    private static final MatchRespawnPolicy DISABLED =
            new MatchRespawnPolicy(Kind.DISABLED, 0L, SIM_TICKS_PER_SECOND);
    private static final MatchRespawnPolicy INSTANT =
            new MatchRespawnPolicy(Kind.INSTANT, 0L, SIM_TICKS_PER_SECOND);

    private final Kind kind;
    private final long cooldownTicks;
    private final long ticksPerSecond; // the rate cooldownTicks was converted at (toString divides by it)

    private MatchRespawnPolicy(Kind kind, long cooldownTicks, long ticksPerSecond) {
        this.kind = kind;
        this.cooldownTicks = cooldownTicks;
        this.ticksPerSecond = ticksPerSecond;
    }

    /**
     * Parse a knob value at the canonical 60 Hz step: {@code disabled | instant | cooldown:K}
     * (K integer seconds &gt;= 1), surrounding whitespace tolerated, everything else an
     * {@link IllegalArgumentException}.
     */
    public static MatchRespawnPolicy parse(String raw) {
        return parse(raw, SIM_TICKS_PER_SECOND);
    }

    /**
     * {@link #parse(String)} converting {@code cooldown:K} seconds at the given EFFECTIVE sim
     * tick rate (serve lanes pass {@code Math.round(1.0 / config.fixedDt())} so a
     * {@code --fixed-dt} CLI override stays coherent with the knob).
     */
    public static MatchRespawnPolicy parse(String raw, long ticksPerSecond) {
        if (ticksPerSecond < 1L) {
            throw new IllegalArgumentException(
                    "respawn policy parse needs ticksPerSecond >= 1, got: " + ticksPerSecond);
        }
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "respawn policy must not be blank (expected disabled | instant | cooldown:K)");
        }
        String spec = raw.trim();
        if ("disabled".equals(spec)) {
            return DISABLED;
        }
        if ("instant".equals(spec)) {
            return INSTANT;
        }
        if (spec.startsWith("cooldown:")) {
            String arg = spec.substring("cooldown:".length());
            int seconds;
            try {
                seconds = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "respawn policy 'cooldown:K' needs an integer K seconds, got: '" + spec + "'");
            }
            if (seconds < 1) {
                throw new IllegalArgumentException(
                        "respawn policy cooldown needs K >= 1 second, got: " + seconds);
            }
            return new MatchRespawnPolicy(Kind.COOLDOWN, seconds * ticksPerSecond, ticksPerSecond);
        }
        throw new IllegalArgumentException(
                "unknown respawn policy '" + spec + "' (expected disabled | instant | cooldown:K)");
    }

    /** Whether this is the {@code disabled} policy (mount-loss latches the seat for the round). */
    public boolean disabled() {
        return kind == Kind.DISABLED;
    }

    /** The cooldown in sim ticks; {@code 0} for {@code disabled}/{@code instant}. */
    public long cooldownTicks() {
        return cooldownTicks;
    }

    /**
     * Whether a seat that lost its mount at {@code lossTick} may remount at {@code nowTick}.
     * {@code disabled} never allows; {@code instant} always; {@code cooldown:K} at
     * {@code lossTick + K*60}. A cooldown consult requires a recorded loss tick
     * ({@code lossTick >= 0}) — the reconcile rule only consults the policy after a mount loss,
     * so a negative loss tick here is a caller wiring bug and throws.
     */
    public boolean allowsRemountAt(long nowTick, long lossTick) {
        switch (kind) {
            case DISABLED:
                return false;
            case INSTANT:
                return true;
            default:
                if (lossTick < 0L) {
                    throw new IllegalArgumentException(
                            "cooldown remount consult requires the recorded mount-loss tick, got: " + lossTick);
                }
                return nowTick - lossTick >= cooldownTicks;
        }
    }

    /** The canonical knob form: {@code disabled | instant | cooldown:K}. */
    @Override
    public String toString() {
        switch (kind) {
            case DISABLED:
                return "disabled";
            case INSTANT:
                return "instant";
            default:
                return "cooldown:" + (cooldownTicks / ticksPerSecond);
        }
    }
}
