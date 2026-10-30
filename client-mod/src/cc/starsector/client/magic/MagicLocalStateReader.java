package cc.starsector.client.magic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.PilotWatchLog;

/**
 * The report half of the MagicLib seam: every frame the kit's tail-registered ability lane asks
 * {@link #bitsFor(ShipAPI)} which of the OWN ship's MagicLib subsystems are on, and ORs the answer into the same
 * held ability bits a contract-v1 adopter publishes. Bit K = sorted slot K ({@link MagicSlots}) is IN or ACTIVE,
 * never {@code isOn()} (that includes OUT, so the bit would trail the wind-down). An adopter's own published value
 * carries the identical bits (same slot rule, same predicate), so the OR is idempotent. The lane runs after
 * MagicLib's manager in the same frame, so the bits are this frame's: no added latency.
 *
 * <p><b>Cached per own-ship tenure (a rule, not an option):</b> the slot-ordered subsystem array is built once
 * when the own ship changes, from the ship's frozen slot table; a frame then costs N {@code getState()} getters and
 * allocates nothing. An uncached re-sort after a mid-life add would renumber the slots and fire the wrong ability on
 * the server. The cache holds the last own ship until the next tenure replaces it.
 *
 * <p><b>Guards:</b> the caller checks {@link MagicCapabilities#readerArmed()} first, so this MagicLib-linked class
 * is never loaded on a game without MagicLib. {@link #probe()} runs once at application load (MagicLib enabled, after
 * the forced define) and resolves every MagicLib member this package names WITHOUT initializing a MagicLib class (a
 * method-handle lookup links, it does not run a static initializer), so a MagicLib whose API moved fails here, not
 * mid-combat, and the {@code 0x08} claim stays clear. A LinkageError in a frame latches the reader off for the process
 * (one row); one subsystem whose own {@code getState()} throws clears that slot's bit for the frame (counted, one row
 * per process); a slot-table freeze that throws leaves the tenure empty (one row). Every catch withdraws the
 * {@code 0x08} claim for a HELLO not yet sent ({@link MagicCapabilities}).
 *
 * <p><b>Value domain:</b> {@link #bitsFor} returns 0..0xFF (slot bits 0..7); the caller shifts them onto the held
 * ability bits. Single-threaded: the game thread.
 *
 */
public final class MagicLocalStateReader {

    private static final MagicSubsystem[] EMPTY = new MagicSubsystem[0];
    /** Tenure rows logged per process, at most. */
    static final int TENURE_ROWS_MAX = 16;

    private static ShipAPI tenureShip;
    private static MagicSubsystem[] tenureOrder = EMPTY;
    private static boolean latchedOff;

    /** Own-ship tenures begun; 0..Integer.MAX_VALUE. */
    static int tenures;
    /** Frames where one subsystem's getter threw (that slot read 0); 0..Integer.MAX_VALUE. */
    static int slotErrors;
    /** Tenures whose slot-table freeze threw (the tenure reads 0); 0..Integer.MAX_VALUE. */
    static int freezeErrors;
    /** LinkageErrors (the reader latches off on the first); 0..Integer.MAX_VALUE. */
    static int linkErrors;
    private static boolean slotErrorLogged;
    private static boolean freezeErrorLogged;

    private MagicLocalStateReader() {
    }

    /**
     * The own ship's MagicLib slot bits for this frame (0..0xFF). Never throws.
     *
     */
    public static int bitsFor(ShipAPI own) {
        if (latchedOff || own == null) {
            return 0;
        }
        try {
            if (own != tenureShip) {
                beginTenure(own);
            }
            return bitsOf(tenureOrder);
        } catch (LinkageError e) {
            linkErrors++;
            latchedOff = true;
            tenureShip = null;
            tenureOrder = EMPTY;
            MagicCapabilities.readerLost();
            PilotWatchLog.warn("magic-up OFF: reader link failed " + e.getClass().getSimpleName()
                    + " -> no MagicLib ability states from this game (latched for the process)");
            return 0;
        } catch (Throwable t) {
            freezeErrors++;
            MagicCapabilities.upClaimWithdrawn();
            if (!freezeErrorLogged) {
                freezeErrorLogged = true;
                PilotWatchLog.warn("magic-up tenure freeze threw " + t.getClass().getSimpleName()
                        + " -> this ship reports no MagicLib states (counted, logged once)");
            }
            return 0;
        }
    }

    /**
     * Start a new own-ship tenure: the tenure is recorded FIRST and empty, so a throwing freeze leaves it empty (and
     * never retried per frame; {@link MagicSlots} stores an empty table on that path too). A table MagicLib cannot
     * freeze yet (no map for the hull) defers the tenure instead: it re-begins on the next frame.
     *
     */
    private static void beginTenure(ShipAPI own) {
        tenureShip = own;
        tenureOrder = EMPTY;
        Map<MagicSubsystem, Integer> table = MagicSlots.tableFor(own);
        if (table == null) {
            // MagicLib has no map for this hull yet (nothing frozen): report 0 this frame and re-begin the tenure
            // next frame, so a late-registered subsystem is numbered, never frozen out.
            tenureShip = null;
            return;
        }
        tenures++;
        tenureOrder = orderOf(table);
        if (tenures <= TENURE_ROWS_MAX) {
            PilotWatchLog.info("magic-up tenure=" + tenures + " subsystems=" + table.size()
                    + " slots_reported=" + reportedSlots(tenureOrder));
        }
    }

    /**
     * PURE: the frozen table as a slot-indexed array (index K = the slot-K instance; slots 8 and up are left out,
     * a gap is null). Length = the highest reported slot + 1.
     *
     */
    static MagicSubsystem[] orderOf(Map<MagicSubsystem, Integer> table) {
        int n = 0;
        for (Integer k : table.values()) {
            if (k != null && k.intValue() >= 0 && k.intValue() < MagicSlots.SLOT_COUNT && k.intValue() >= n) {
                n = k.intValue() + 1;
            }
        }
        if (n == 0) {
            return EMPTY;
        }
        MagicSubsystem[] order = new MagicSubsystem[n];
        for (Map.Entry<MagicSubsystem, Integer> e : table.entrySet()) {
            Integer k = e.getValue();
            if (k != null && k.intValue() >= 0 && k.intValue() < MagicSlots.SLOT_COUNT) {
                order[k.intValue()] = e.getKey();
            }
        }
        return order;
    }

    /**
     * PURE per-frame core: bit K set when {@code order[K]} is IN or ACTIVE. One subsystem's throwing getter clears
     * only its own bit (counted, one row per process); a LinkageError propagates to {@link #bitsFor}'s latch.
     * Allocates nothing.
     *
     */
    static int bitsOf(MagicSubsystem[] order) {
        int bits = 0;
        for (int k = 0; k < order.length; k++) {
            MagicSubsystem s = order[k];
            if (s == null) {
                continue;
            }
            try {
                MagicSubsystem.State st = s.getState();
                if (st == MagicSubsystem.State.IN || st == MagicSubsystem.State.ACTIVE) {
                    bits |= 1 << k;
                }
            } catch (LinkageError e) {
                throw e;
            } catch (Throwable t) {
                slotErrors++;
                MagicCapabilities.upClaimWithdrawn();
                if (!slotErrorLogged) {
                    slotErrorLogged = true;
                    PilotWatchLog.warn("magic-up slot=" + k + " getState threw " + t.getClass().getSimpleName()
                            + " -> that slot reads 0 (counted, logged once)");
                }
            }
        }
        return bits;
    }

    private static int reportedSlots(MagicSubsystem[] order) {
        int n = 0;
        for (MagicSubsystem s : order) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * The guarded API probe, once at application load, only when MagicLib is enabled: resolve every MagicLib member
     * the hook, the slot law and this reader name (method-handle lookups: they link, they never run a MagicLib static
     * initializer), then arm the reader and the {@code 0x08} claim, or neither. Returns {@code ok} or the failure's
     * SimpleName (a bounded row token). Never throws.
     *
     */
    public static String probe() {
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            l.findVirtual(MagicSubsystem.class, "getState", MethodType.methodType(MagicSubsystem.State.class));
            l.findVirtual(MagicSubsystem.class, "isToggle", MethodType.methodType(boolean.class));
            l.findStaticGetter(MagicSubsystem.State.class, "READY", MagicSubsystem.State.class);
            l.findStaticGetter(MagicSubsystem.State.class, "IN", MagicSubsystem.State.class);
            l.findStaticGetter(MagicSubsystem.State.class, "ACTIVE", MagicSubsystem.State.class);
            l.findStaticGetter(MagicSubsystemsManager.class, "INSTANCE", MagicSubsystemsManager.class);
            l.findStatic(MagicSubsystemsManager.class, "getSubsystemsForShipCopy",
                    MethodType.methodType(List.class, ShipAPI.class));
            l.findVirtual(MagicSubsystemsManager.class, "sortSubsystems",
                    MethodType.methodType(Collection.class, Collection.class));
            MagicCapabilities.probeResult(true);
            return "ok";
        } catch (Throwable t) {
            MagicCapabilities.probeResult(false);
            return t.getClass().getSimpleName();
        }
    }

    /** Off-frame meter text: bounded ints only. */
    public static String describe() {
        return "tenures=" + tenures + " slot_errors=" + slotErrors + " freeze_errors=" + freezeErrors
                + " link_errors=" + linkErrors + " latched_off=" + latchedOff;
    }

    /**
     * Process-state reset for the client rows only (one JVM drives several tenures and failures); never called by
     * product code.
     *
     */
    static void resetForRows() {
        tenureShip = null;
        tenureOrder = EMPTY;
        latchedOff = false;
        tenures = 0;
        slotErrors = 0;
        freezeErrors = 0;
        linkErrors = 0;
        slotErrorLogged = false;
        freezeErrorLogged = false;
    }
}
