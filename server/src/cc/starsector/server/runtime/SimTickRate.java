package cc.starsector.server.runtime;

/**
 * The operator FLAT tick-rate knob (the law: tickrate
 * is a variable — cadences derive from the rate, never a hardcoded 60): {@code cc.sim.tickRate}
 * sysprop / {@code CC_SIM_TICK_RATE} env / config-file {@code sim.tickRate}, integer Hz,
 * default 60.
 *
 * <p>Strict parse, fail-closed (the {@code MatchRespawnPolicy} knob discipline — deliberately NOT
 * the fail-soft {@code ServerConfig.resolveInt}): ABSENT/blank resolves 60; a present-but-invalid
 * value (non-integer, &lt;{@value #MIN_TICKS_PER_SECOND} or &gt;{@value #MAX_TICKS_PER_SECOND})
 * THROWS at first consult, i.e. at boot — a silently-wrong tick rate wrecks feel end-to-end.
 *
 * <p>An explicit {@code --fixed-dt} CLI still WINS over this knob in every main, and every
 * tick-window derivation keys off the EFFECTIVE dt ({@code Math.round(1.0 / config.fixedDt())}),
 * never this knob directly, so CLI overrides stay coherent. Float-shape law: seconds→window
 * consumers derive by DIVISION by rate ({@code 1.5f / rate} at rate=60 ≡ the old
 * {@code 1.5f / 60.0f} bit-exactly; {@code seconds * fixedDt} is 1 ulp off).
 */
public final class SimTickRate {

    public static final int DEFAULT_TICKS_PER_SECOND = 60;
    public static final int MIN_TICKS_PER_SECOND = 10;
    public static final int MAX_TICKS_PER_SECOND = 600;

    private static volatile int resolved; // 0 = not yet resolved (every valid value is >= 10)

    private SimTickRate() {
    }

    /** The resolved knob in Hz (lazy-cached; throws on an invalid explicit value — never fail-soft). */
    public static int ticksPerSecond() {
        int v = resolved;
        if (v == 0) {
            v = parse(ServerConfig.resolve(
                    "cc.sim.tickRate", "CC_SIM_TICK_RATE", "sim.tickRate", null));
            resolved = v;
        }
        return v;
    }

    /** The knob as the fixed sim step: {@code 1f / ticksPerSecond()} (60 ⇒ the canonical 1/60 s). */
    public static float fixedDt() {
        return 1.0f / ticksPerSecond();
    }

    /**
     * PURE strict parse (game-free-testable): {@code null}/blank ⇒ {@value #DEFAULT_TICKS_PER_SECOND};
     * anything else must be an integer in [{@value #MIN_TICKS_PER_SECOND},
     * {@value #MAX_TICKS_PER_SECOND}] or this throws {@link IllegalArgumentException}.
     */
    public static int parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_TICKS_PER_SECOND;
        }
        String spec = raw.trim();
        int rate;
        try {
            rate = Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "cc.sim.tickRate must be an integer tick rate in Hz, got: '" + spec + "'");
        }
        if (rate < MIN_TICKS_PER_SECOND || rate > MAX_TICKS_PER_SECOND) {
            throw new IllegalArgumentException(
                    "cc.sim.tickRate must be in [" + MIN_TICKS_PER_SECOND + ", "
                            + MAX_TICKS_PER_SECOND + "] Hz, got: " + rate);
        }
        return rate;
    }
}
