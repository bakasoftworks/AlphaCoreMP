package cc.starsector.server.bridge;

/**
 * The shared arm / row-cap / disarm skeleton of the headless census probes — carved from the
 * four census probes ({@link MoteCensusProbe}, {@link FragmentSwarmCensusProbe},
 * {@link DwellerCensusProbe}, {@link OwnCarrierZeroFluxProbe}).
 * Each probe keeps only its own census logic and
 * holds ONE static instance of this; the probe's own {@code ARMED} static final stays the per-tick
 * gate (one constant boolean test while OFF, zero engine reads), this object owns everything after it.
 *
 * <p>Laws (unchanged from the probes it was carved from):
 * <ul>
 * <li>arm law {@link #resolveArmed}: sysprop {@code true} OR env {@code true}, case-insensitive;
 *     else OFF — contract-pinned through each probe's public {@code resolveArmed}.</li>
 * <li>sink = {@code System.out} rows ({@code server.out.log}); {@link #row} counts every row against
 *     {@code rowCap} and prints {@code "<tag> ROW CAP reached (<cap>); further rows suppressed"} on
 *     the row that reaches it; {@link #closed} is the per-tick gate once the cap or a disarm hit.</li>
 * <li>{@link #logArmed} prints {@code "<tag> ARMED (<prop>) rowCap=<cap><extra>"} once — a probe
 *     calls it AFTER its reflective binding succeeded, so a probe that never binds never claims
 *     ARMED (it disarms instead).</li>
 * <li>{@link #disarm}: fail-soft — any reflective failure latches the probe OFF for the session and
 *     logs once ({@code "<tag> DISARMED (reflective failure): <t>[ cause=<cause>]"}); never throws.</li>
 * </ul>
 * Sim-thread only (the probes sample after the engine advance); no synchronization by design. Row
 * texts are the probes' own — this class never formats a census row, only counts and prints it.
 */
final class CappedProbeSink {
    private final String tag;
    private final String prop;
    private final int rowCap;
    private int rows;
    private boolean disarmed;
    private boolean armedLogged;

    /**
     * @param tag    the probe's line prefix for the ARMED / ROW CAP / DISARMED lines (e.g. {@code mote-probe})
     * @param prop   the arming sysprop, echoed in the ARMED line
     * @param rowCap total rows this sink prints for the session
     */
    CappedProbeSink(String tag, String prop, int rowCap) {
        this.tag = tag;
        this.prop = prop;
        this.rowCap = rowCap;
    }

    /** Pure arm law (contract-pinned): sysprop {@code true} OR env {@code true}, case-insensitive; else OFF. */
    public static boolean resolveArmed(String sysprop, String env) {
        return "true".equalsIgnoreCase(sysprop) || "true".equalsIgnoreCase(env);
    }

    /** The per-tick gate after the probe's own {@code ARMED} test: disarmed, or the row cap reached. */
    boolean closed() {
        return disarmed || rows >= rowCap;
    }

    /** Row cap reached (the mid-sample loop break). */
    boolean full() {
        return rows >= rowCap;
    }

    /** Whether the one-time ARMED line went out (test first so the {@code extra} concat runs once). */
    boolean armedLogged() {
        return armedLogged;
    }

    /** Prints the one-time ARMED line; {@code extra} = the probe's own cadence suffix (leading space). */
    void logArmed(String extra) {
        armedLogged = true;
        System.out.println(tag + " ARMED (" + prop + ") rowCap=" + rowCap + extra);
    }

    /** One counted row, printed verbatim; the cap line rides the row that reaches the cap. */
    void row(String text) {
        rows++;
        System.out.println(text);
        if (rows == rowCap) {
            System.out.println(tag + " ROW CAP reached (" + rowCap + "); further rows suppressed");
        }
    }

    /** Latches the probe OFF for the session and logs once (cause appended when the throwable has one). */
    void disarm(Throwable t) {
        disarmed = true;
        System.out.println(tag + " DISARMED (reflective failure): " + t
                + (t.getCause() != null ? " cause=" + t.getCause() : ""));
    }
}
