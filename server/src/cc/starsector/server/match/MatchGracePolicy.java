package cc.starsector.server.match;

/**
 * The parsed value of the W5 reconnect-grace knob pair — {@code cc.match.graceSeconds} +
 * {@code cc.match.parkPolicy}.
 * On a TRUE seat vacate (BYE/silence — never displacement) with a
 * mounted plain hull, the seat enters GRACE: a fresh "cautious"-personality AI flies the hull for
 * {@code graceSeconds}; at the deadline the seat PARKs per {@link ParkPolicy} and becomes the W3
 * command-transfer candidate. Server-internal sub-states of AFK, ZERO wire surface.
 *
 * <p><b>Terminology guard:</b> this grace is the pilot-state machine — DISTINCT from
 * the socket join-grace ({@code joinGraceMillis} lobby read-deadline). Never conflate.
 *
 * <p>Strict parse, fail-closed knob discipline (the {@link MatchRespawnPolicy} analog):
 * {@code graceSeconds} is an integer &gt;= 0 — <b>0 = OFF</b> (today's pilotless coast, the
 * compiled default: toggle discipline keeps certified lanes byte-identical) — converted to sim
 * ticks at the effective step rate; {@code parkPolicy} is {@code stay-ai | drift} (parsed
 * strictly even when OFF). Anything else THROWS. The clock is SIM TICKS, wall-clock rejected:
 * the countdown pauses with the sim and stays deterministic.
 *
 * <p>Game-free and unit-tested in {@code ServerContractTests} (SeatGraceContractRows). The serve
 * lane resolves the raw knob strings (the consumer parses loud — respawnPolicy precedent) and
 * arms {@link MatchSeatAuthority#armGraceMachine(MatchGracePolicy)}; the authority applies the
 * policy internally.
 */
public final class MatchGracePolicy {

    /** What happens to the graced hull when the deadline hits: {@code stay-ai}
     * keeps the cautious AI fighting (default); {@code drift} detaches the AI (suppressed
     * coast). Either way the seat marks PARKED — the W3 abandoned-eligible state. */
    public enum ParkPolicy { STAY_AI, DRIFT }

    /** The canonical DEFAULT step rate the 2-arg {@link #parse(String, String)} converts at
     * (serve lanes pass their EFFECTIVE rate, the {@link MatchRespawnPolicy} pattern). */
    public static final long SIM_TICKS_PER_SECOND = 60L;

    private final int graceSeconds;
    private final long graceTicks;
    private final ParkPolicy parkPolicy;

    private MatchGracePolicy(int graceSeconds, long graceTicks, ParkPolicy parkPolicy) {
        this.graceSeconds = graceSeconds;
        this.graceTicks = graceTicks;
        this.parkPolicy = parkPolicy;
    }

    /** Parse the knob pair at the canonical 60 Hz step. */
    public static MatchGracePolicy parse(String graceSecondsRaw, String parkPolicyRaw) {
        return parse(graceSecondsRaw, parkPolicyRaw, SIM_TICKS_PER_SECOND);
    }

    /**
     * {@link #parse(String, String)} converting {@code graceSeconds} at the given EFFECTIVE sim
     * tick rate (serve lanes pass {@code Math.round(1.0 / config.fixedDt())} so a
     * {@code --fixed-dt} CLI override stays coherent with the knob).
     */
    public static MatchGracePolicy parse(
            String graceSecondsRaw, String parkPolicyRaw, long ticksPerSecond) {
        if (ticksPerSecond < 1L) {
            throw new IllegalArgumentException(
                    "grace policy parse needs ticksPerSecond >= 1, got: " + ticksPerSecond);
        }
        if (graceSecondsRaw == null || graceSecondsRaw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cc.match.graceSeconds must not be blank (expected an integer >= 0; 0 = OFF)");
        }
        int seconds;
        try {
            seconds = Integer.parseInt(graceSecondsRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "cc.match.graceSeconds needs an integer >= 0 (0 = OFF), got: '"
                            + graceSecondsRaw.trim() + "'");
        }
        if (seconds < 0) {
            throw new IllegalArgumentException(
                    "cc.match.graceSeconds needs an integer >= 0 (0 = OFF), got: " + seconds);
        }
        if (parkPolicyRaw == null || parkPolicyRaw.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "cc.match.parkPolicy must not be blank (expected stay-ai | drift)");
        }
        String parkSpec = parkPolicyRaw.trim();
        ParkPolicy park;
        if ("stay-ai".equals(parkSpec)) {
            park = ParkPolicy.STAY_AI;
        } else if ("drift".equals(parkSpec)) {
            park = ParkPolicy.DRIFT;
        } else {
            throw new IllegalArgumentException(
                    "unknown cc.match.parkPolicy '" + parkSpec + "' (expected stay-ai | drift)");
        }
        return new MatchGracePolicy(seconds, seconds * ticksPerSecond, park);
    }

    /** Whether the grace machine is OFF ({@code graceSeconds == 0}) — today's pilotless coast;
     * the authority never enters GRACE and the serve stays byte-identical. */
    public boolean off() {
        return graceSeconds == 0;
    }

    /** The configured grace window in seconds ({@code 0} = OFF). */
    public int graceSeconds() {
        return graceSeconds;
    }

    /** The grace window in sim ticks at the parse-time rate ({@code 0} when OFF). */
    public long graceTicks() {
        return graceTicks;
    }

    /** The deadline disposition — meaningful only when {@link #off()} is false. */
    public ParkPolicy parkPolicy() {
        return parkPolicy;
    }

    /** The canonical knob forms, both halves. */
    @Override
    public String toString() {
        return "graceSeconds=" + graceSeconds + " parkPolicy="
                + (parkPolicy == ParkPolicy.DRIFT ? "drift" : "stay-ai");
    }
}
