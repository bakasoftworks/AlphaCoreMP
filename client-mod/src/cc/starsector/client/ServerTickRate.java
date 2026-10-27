package cc.starsector.client;

/**
 * The client-side mirror of the server's FLAT sim tick rate — the rate every
 * rate-coupled predict/reconcile tick-window constant derives from. Knob:
 * {@code cc.predict.serverTickRate} sysprop, {@code CC_PREDICT_SERVER_TICK_RATE} env fallback,
 * default 60. The v3 WELCOME now carries the rate ({@code u16 tickRate}
 * tail) and {@link #seedFromWire(int)} adopts it at connect — the wire wins over the kit
 * sysprop, which is retired (an old kit's line still parses; the seed simply overrides it).
 *
 * <p>Strict parse, fail-closed (the deflate-resolver / resolveLeverKnob discipline):
 * absent/blank ⇒ 60; a non-integer or out-of-[10,600] value THROWS. NOTE: the consumers are
 * static finals, so an invalid knob surfaces as an {@code ExceptionInInitializerError} wrapping
 * this {@link IllegalArgumentException} (the message names the knob) — acceptable fail-closed;
 * the selftest proves the clean IAE on the pure {@link #parse}. Loader-B-safe:
 * {@code System.getProperty}/{@code getenv} only on the resolve path; the wire-seed rows log via
 * the pre-warmed {@link PilotWatchLog} seam (the log divorce — these were the last
 * client stragglers still printing into starsector.log).
 */
public final class ServerTickRate {

    public static final int DEFAULT_TICKS_PER_SECOND = 60;
    public static final int MIN_TICKS_PER_SECOND = 10;
    public static final int MAX_TICKS_PER_SECOND = 600;

    private static volatile int resolved; // 0 = not yet resolved (every valid value is >= 10)

    private ServerTickRate() {
    }

    /**
     * Adopt the v3 WELCOME's {@code u16 tickRate} (the wire wins over the
     * sysprop/env cache — re-seedable on every admit, including the B4 re-admit lane). An
     * out-of-range wire value is REFUSED loud (keep the current resolution — self-healing mid-
     * join, never a throw): the server validates its own advertisement, so a breach here is a
     * codec/spoof symptom, not a config error. Sink = {@link PilotWatchLog} (combat-SM-safe:
     * force-loaded + appender pre-warmed at application load; the log divorce).
     */
    public static void seedFromWire(int rate) {
        if (rate < MIN_TICKS_PER_SECOND || rate > MAX_TICKS_PER_SECOND) {
            // (83) CL-6: routed through FallbackAnnounce (was a bare info line) so a refused wire
            // rate rides the watch-verdict FALLBACK section; announceOnce logs via the same
            // pre-warmed PilotWatchLog sink.
            FallbackAnnounce.announceOnce("tickrate-wire-refused", "rate=" + rate
                    + " out of [" + MIN_TICKS_PER_SECOND + "," + MAX_TICKS_PER_SECOND
                    + "] -> keeping=" + ticksPerSecond());
            return;
        }
        int prior = resolved;
        resolved = rate;
        if (prior != 0 && prior != rate) {
            PilotWatchLog.info("cc-tickrate wire_seed_override prior=" + prior + " rate=" + rate);
        }
    }

    /** The resolved server tick rate in Hz (lazy-cached; throws on an invalid explicit value). */
    public static int ticksPerSecond() {
        int v = resolved;
        if (v == 0) {
            String raw = System.getProperty("cc.predict.serverTickRate");
            if (raw == null || raw.trim().isEmpty()) {
                raw = System.getenv("CC_PREDICT_SERVER_TICK_RATE");
            }
            v = parse(raw);
            resolved = v;
        }
        return v;
    }

    /**
     * PURE strict parse (selftest-reachable): {@code null}/blank ⇒
     * {@value #DEFAULT_TICKS_PER_SECOND}; anything else must be an integer in
     * [{@value #MIN_TICKS_PER_SECOND}, {@value #MAX_TICKS_PER_SECOND}] or this throws
     * {@link IllegalArgumentException}.
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
                    "cc.predict.serverTickRate must be an integer tick rate in Hz, got: '" + spec + "'");
        }
        if (rate < MIN_TICKS_PER_SECOND || rate > MAX_TICKS_PER_SECOND) {
            throw new IllegalArgumentException(
                    "cc.predict.serverTickRate must be in [" + MIN_TICKS_PER_SECOND + ", "
                            + MAX_TICKS_PER_SECOND + "] Hz, got: " + rate);
        }
        return rate;
    }
}
