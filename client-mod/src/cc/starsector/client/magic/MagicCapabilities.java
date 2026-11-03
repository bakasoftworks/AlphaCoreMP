package cc.starsector.client.magic;

import cc.starsector.client.replica.ReplicaReceipt;

/**
 * The MagicLib-free capability memo both HELLO sites OR into their flags: {@code 0x08} = this game reports its
 * MagicLib ability states (claimed only after the local-state reader's guarded probe passed), {@code 0x10} = this
 * game's MagicLib subsystem class was patched (a report only, never an authority gate), {@code 0x20} = this game's
 * pilot-replica agent arm rewrote at least one mod class (a report the serve prints per seat, never an authority
 * gate). The value is memoised per process so every HELLO and re-HELLO carries equal bits. This class links nothing
 * under {@code org.magiclib}: a HELLO caller verifies without MagicLib on the classpath.
 *
 * <p>Value domain: 0 or a subset of {@code 0x38}; the handshake refuses any other bit.
 *
 * <p><b>Who sets what.</b> The kit plugin's application load runs the reader's probe
 * ({@link MagicLocalStateReader#probe()}) only when MagicLib is enabled, after the forced define of MagicLib's
 * subsystem class, so {@code 0x10}'s source (the {@link #SEAM_PROPERTY} receipt a load-time patch writes at that
 * define) is settled before any HELLO: the first HELLO is a player's join click, long after application load.
 * {@code 0x20}'s source is the pilot-replica agent's receipt ({@link #pilotApplied()}), written as the agent rewrites
 * mod classes at their define. The memo is decided at the FIRST {@link #helloFlagBits()} call and never changes after
 * it, so a receipt that turns {@code applied:} later moves no bit (the claim is a report, so a late miss costs a
 * print, never a join).
 *
 * <p><b>The reader's guard.</b> {@link #readerArmed()} is the one check the per-frame caller makes BEFORE it touches
 * the MagicLib-linked reader, so a game without MagicLib never loads that class. It is true only after the probe
 * passed, and drops for good on a LinkageError in the reader. A reader throw (a LinkageError, or one subsystem's
 * own getter throwing) also withdraws the {@code 0x08} claim for a HELLO whose memo is not yet decided; once
 * decided the memo holds (re-HELLO equality wins), and the throw is counted and logged by the reader.
 *
 */
public final class MagicCapabilities {

    /** HELLO flag: this game reports its own MagicLib ability states. */
    public static final int FLAG_MAGIC_UP = 0x08;
    /** HELLO flag: this game's MagicLib subsystem class was patched (a report only). */
    public static final int FLAG_MAGIC_REPLICA = 0x10;
    /**
     * HELLO flag: this game's pilot-replica agent arm applied (a report only; = the door's
     * {@code FLAG_PILOT_REPLICA}). It never gates what the serve accepts: the serve only prints it per seat, so a
     * kit whose arm refused every class still joins and plays.
     *
     */
    public static final int FLAG_PILOT_REPLICA = 0x20;
    /** The receipt a load-time patch of MagicLib's subsystem class writes: {@code applied:<16 hex>} when it applied. */
    public static final String SEAM_PROPERTY = "cc.magic.seam";
    /** The receipt prefix that means the patch applied. */
    public static final String SEAM_APPLIED_PREFIX = "applied:";

    private static volatile boolean readerArmed;
    private static volatile boolean upClaimable;
    /** -1 = undecided; else the frozen HELLO bits. Guarded by the class lock (HELLO-rate, never per frame). */
    private static int memo = -1;

    private MagicCapabilities() {}

    /**
     * The HELLO flag bits this game claims: decided at the first call, equal on every call after it.
     *
     */
    public static synchronized int helloFlagBits() {
        if (memo < 0) {
            memo = (upClaimable ? FLAG_MAGIC_UP : 0) | (seamApplied() ? FLAG_MAGIC_REPLICA : 0)
                    | (pilotApplied() ? FLAG_PILOT_REPLICA : 0);
        }
        return memo;
    }

    /** True while the MagicLib-linked reader may run: its probe passed and it never hit a LinkageError. */
    public static boolean readerArmed() {
        return readerArmed;
    }

    /**
     * True when this process's receipt says the load-time patch applied ({@link #SEAM_PROPERTY} starts with
     * {@link #SEAM_APPLIED_PREFIX}); absent, {@code off}, {@code armed} or {@code disarmed:*} are false.
     *
     */
    public static boolean seamApplied() {
        String v = System.getProperty(SEAM_PROPERTY, "");
        return v != null && v.startsWith(SEAM_APPLIED_PREFIX);
    }

    /**
     * True when this process's pilot-replica receipt ({@link ReplicaReceipt#SYSPROP}) starts with
     * {@link ReplicaReceipt#APPLIED}: the agent arm rewrote at least one mod class. Absent, {@code off},
     * {@code armed}, {@code disarmed:*} and {@code latched:*} are false. Both names are compile-time constants in a
     * game-free class (the engine-typed holder is never imported here - the wire-parity stand compiles this
     * class standalone), so this read never loads the holder class (the HELLO caller stays as light as
     * {@link #seamApplied()}).
     */
    public static boolean pilotApplied() {
        String v = System.getProperty(ReplicaReceipt.SYSPROP, "");
        return v != null && v.startsWith(ReplicaReceipt.APPLIED);
    }

    /** The probe's outcome (application load, once): arms the reader and the {@code 0x08} claim, or neither. */
    static void probeResult(boolean passed) {
        readerArmed = passed;
        upClaimable = passed;
    }

    /** A reader LinkageError: the reader stops for this process and the {@code 0x08} claim is withdrawn. */
    static void readerLost() {
        readerArmed = false;
        upClaimable = false;
    }

    /** One subsystem's getter threw in the reader: that slot reads 0 that frame and the {@code 0x08} claim is withdrawn. */
    static void upClaimWithdrawn() {
        upClaimable = false;
    }

    /**
     * Process-state reset for the client rows only (they drive several probe outcomes in one JVM); never called by
     * product code.
     *
     */
    static synchronized void resetForRows() {
        readerArmed = false;
        upClaimable = false;
        memo = -1;
    }
}
