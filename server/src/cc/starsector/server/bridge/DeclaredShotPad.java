package cc.starsector.server.bridge;

/**
 * The declared-shot press pad: a class-H (held-lane) weapon's fire
 * DECLARATION is dropped at intake under class routing ({@link PilotApplyState#enqueueDeclaration},
 * the {@code lane-class} branch), yet it is evidence that the client's OWN engine fired that mount.
 * On two live runs the client fired, released the trigger 1-2 ticks before the
 * server's weapon was ready, and the server never fired the twin (server slots 5815, 3823, 9421:
 * the release-frame class). When such a declaration is dropped this pad
 * lets the seat's held-lane press predicate ({@code PilotCommandApplier.applyFireBit}) also read
 * true for {@link #DECL_PAD_TICKS} ABSOLUTE sim ticks [arm, arm + 2], so the server's own engine
 * fires the twin through the SAME aimed group press - never a force-fire, never a named mount.
 *
 * <p><b>Why this is NOT the rejected time pad</b> ({@link PilotApplyState#FIRE_EDGE_PRESS_PAD_TICKS}
 * = 0): a pad on EVERY release fired cooldown-blocked taps the client's engine had dropped.
 * This pad exists only where the client DECLARED a discharge - its engine did NOT drop
 * that shot - and every rule below only narrows when it may arm or press: mount range first (no
 * resolver read for a bad index), an eligible single-weapon non-ALTERNATING non-burst chargeup-0
 * group ({@link PilotApplyState.FireClassResolver#declPadGroupForMount}), a translatable client
 * group (fail CLOSED), the hull tenure, a measured discharge cadence, and the SERVED refusal
 * ({@code L >= clientTick - P/2}: the server already fired this shot's twin - the 2028 shape). The
 * declaration's {@code clientTick} is read ONLY to refuse; aim / ray / muzzle / speed never.
 *
 * <p><b>Lifecycle:</b> expiry is detected LAZILY at the next call ({@link #pressing},
 * {@link #onClassHDeclaration}, {@link #onOwnDischarge}, a clear), so a round's last pad can leave
 * {@code lapsed} one short at the round line (accepted). A discharge of the pad's group within one
 * tick of a release-tick press = {@code bridged} (the one-tick tolerance covers an engine discharge
 * landing the tick after the press; the serve loop's dispatch tick equals the press tick, no skew);
 * any other disarm after a release-tick press = {@code lapsed}; under a continuous hold the pad arms
 * and disarms once per shot and counts nothing but {@code armed}.
 *
 * <p><b>Delete trigger:</b> {@code armed} + {@code lapsed} + {@code refusedServed} + the
 * {@code pilot-decl-pad} log row go after two owner flights whose {@code own_discharge_client_only}
 * is free of the release-frame class; {@code bridged} stays (the mechanism's proof).
 *
 * <p>Game-free (no engine types, no reflection, no allocation after construction except the capped
 * log row); one instance per {@link PilotApplyState}; sim thread only. Value domains: sim ticks
 * {@code >= 0} ({@code applyTick = -1} = the legacy 1-arg enqueue = OFF); {@code clientTick >= 0}
 * (the record's ctor law); server group ordinals {@code [0, GROUP_MAX)}; wire id {@code > 0}.
 *
 */
public final class DeclaredShotPad {

    /** The pad's press window in ABSOLUTE sim ticks from the arm tick (r+1 .. r+3; paired p95 r+3). */
    public static final int DECL_PAD_TICKS = 3;
    /** Mount ordinals at or past this refuse BEFORE any resolver read (a spammed bad index costs 0 reads). */
    public static final int MOUNT_INDEX_MAX = 256;
    /** Server group ordinals tracked by the discharge history; an ordinal at or past it never arms. */
    public static final int GROUP_MAX = 16;

    private static final int MAX_PAD_LOGS = 32; // the pilot-fire-edge expired idiom's cap
    private static final long UNKNOWN = Long.MIN_VALUE;

    /** The arm verdict of one class-H declaration, in check order. */
    public enum Arm { ARMED, ALREADY_ARMED, OFF, MOUNT_RANGE, INELIGIBLE, GROUP_MAP, TENURE, NO_PERIOD, SERVED }

    private boolean enabled = true;
    private final long[] lastDischargeTick = new long[GROUP_MAX]; // L[g]; UNKNOWN = none this tenure
    private final long[] dischargePeriod = new long[GROUP_MAX]; // P[g]; UNKNOWN = fewer than two
    private long tenureStartTick = -1L;
    private boolean padArmed;
    private int padGroup = -1; // server space
    private int padGroupClient = -1; // client space (the frame's selectedWeaponGroup domain)
    private long firstTick;
    private long expiryTick;
    private boolean pressedOnRelease;
    private long lastPressTick;
    private long armWireId;
    private int armed;
    private int bridged;
    private int lapsed;
    private int refusedServed;
    private int logsEmitted;

    public DeclaredShotPad() {
        clearHistory();
    }

    /** The boot kill switch ({@code cc.server.declPad}, default on); off = every arm answers OFF. */
    public void enabled(boolean on) {
        this.enabled = on;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * One class-H declaration dropped at intake ({@code lane-class}) at {@code applyTick} (the
     * serve tick its drain feeds). Checks run in {@link Arm} order; the first refusal returns.
     * {@code wireId} only names the log row.
     */
    public Arm onClassHDeclaration(long applyTick, long clientTick, int mountIndex,
            PilotApplyState.FireClassResolver resolver, int[] groupIdentityInverse, long wireId) {
        if (!enabled || applyTick < 0L) {
            return Arm.OFF;
        }
        settleIfExpired(applyTick);
        if (mountIndex < 0 || mountIndex >= MOUNT_INDEX_MAX) {
            return Arm.MOUNT_RANGE;
        }
        int g = resolver == null ? -1 : resolver.declPadGroupForMount(mountIndex);
        if (g < 0 || g >= GROUP_MAX) {
            return Arm.INELIGIBLE;
        }
        int gc = groupIdentityInverse == null ? g
                : (g < groupIdentityInverse.length ? groupIdentityInverse[g] : -1);
        if (gc < 0) {
            return Arm.GROUP_MAP;
        }
        if (clientTick <= tenureStartTick) {
            return Arm.TENURE;
        }
        long l = lastDischargeTick[g];
        long p = dischargePeriod[g];
        if (l == UNKNOWN || p == UNKNOWN) {
            return Arm.NO_PERIOD;
        }
        if (l >= clientTick - p / 2) {
            refusedServed++;
            return Arm.SERVED;
        }
        if (padArmed && padGroup == g) {
            return Arm.ALREADY_ARMED;
        }
        if (padArmed) {
            settle(); // re-key: the other group's pad is disarmed unpaired
        }
        padArmed = true;
        padGroup = g;
        padGroupClient = gc;
        firstTick = applyTick;
        expiryTick = applyTick + DECL_PAD_TICKS;
        pressedOnRelease = false;
        lastPressTick = -1L;
        armWireId = wireId;
        armed++;
        return Arm.ARMED;
    }

    /**
     * The pad's own press term. The caller asks ONLY on a tick whose wire trigger is UP and whose
     * fire-edge latch is NOT pressing. A DESELECTED frame keeps the pad armed; a different selected
     * group disarms it; an alternating tube on the wire never presses.
     */
    public boolean pressing(long simTick, int frameSelectedGroupClient, int activeTubeOrdinal) {
        if (!padArmed) {
            return false;
        }
        if (simTick >= expiryTick) {
            settle();
            return false;
        }
        if (simTick < firstTick) {
            return false;
        }
        if (frameSelectedGroupClient == cc.starsector.server.runtime.PilotIntentFrame.SELECTED_GROUP_DESELECTED) {
            return false;
        }
        if (frameSelectedGroupClient != padGroupClient) {
            settle();
            return false;
        }
        if (activeTubeOrdinal >= 0) {
            return false;
        }
        pressedOnRelease = true;
        lastPressTick = simTick;
        return true;
    }

    /**
     * An own-ship discharge of server group {@code dischargeGroupServer} (-1 = unresolvable) at the
     * step's {@code simTick}. History first (a multi-mount volley in one tick is ONE event), then the
     * pairing: a release-tick press within one tick = bridged; an expired pad settles; any other
     * discharge of the pad's group (the held stream fired it) disarms silently.
     */
    public void onOwnDischarge(long simTick, int dischargeGroupServer) {
        int g = dischargeGroupServer;
        if (g >= 0 && g < GROUP_MAX && lastDischargeTick[g] != simTick) {
            long l = lastDischargeTick[g];
            if (l != UNKNOWN && simTick > l) {
                dischargePeriod[g] = simTick - l;
            }
            lastDischargeTick[g] = simTick;
        }
        if (!padArmed) {
            return;
        }
        if (g == padGroup && pressedOnRelease && simTick <= lastPressTick + 1L) {
            bridged++;
            logOutcome("bridged");
            disarm();
            return;
        }
        if (simTick >= expiryTick) {
            settle();
            return;
        }
        if (g == padGroup) {
            disarm();
        }
    }

    /** Hull loss / transfer / module seat / new client session: pad + history die; tenure stamp kept monotone. */
    public void clearForTenure(long tenureStart) {
        if (padArmed) {
            settle();
        }
        clearHistory();
        tenureStartTick = Math.max(tenureStartTick, tenureStart);
    }

    /** A {@code seat_variant_repoint} rebind: the roster changed, so pad + history die; the stamp stays. */
    public void clearForRebind() {
        if (padArmed) {
            settle();
        }
        clearHistory();
    }

    /** Contract surface: whether a pad is armed right now (not a meter). */
    public boolean isArmed() {
        return padArmed;
    }

    public int armed() {
        return armed;
    }

    public int bridged() {
        return bridged;
    }

    public int lapsed() {
        return lapsed;
    }

    public int refusedServed() {
        return refusedServed;
    }

    private void settleIfExpired(long simTick) {
        if (padArmed && simTick >= expiryTick) {
            settle();
        }
    }

    private void settle() {
        if (pressedOnRelease) {
            lapsed++;
            logOutcome("lapsed");
        }
        disarm();
    }

    private void disarm() {
        padArmed = false;
        pressedOnRelease = false;
        padGroup = -1;
        padGroupClient = -1;
    }

    private void clearHistory() {
        java.util.Arrays.fill(lastDischargeTick, UNKNOWN);
        java.util.Arrays.fill(dischargePeriod, UNKNOWN);
    }

    private void logOutcome(String outcome) {
        if (logsEmitted >= MAX_PAD_LOGS) {
            return;
        }
        logsEmitted++;
        System.out.println("pilot-decl-pad outcome=" + outcome + " wire_id=" + armWireId
                + " arm_tick=" + firstTick + " press_tick=" + lastPressTick + " group=" + padGroup
                + (logsEmitted == MAX_PAD_LOGS ? " (log capped; counters continue)" : ""));
    }
}
