package cc.starsector.client.magic;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.PilotWatchLog;

/**
 * The slot law shared by the server hook ({@link CcMagicSeam}) and the client report
 * ({@link MagicLocalStateReader}): slot K of a MagicLib subsystem = its index in MagicLib's OWN sorted list for the
 * ship, {@code INSTANCE.sortSubsystems(getSubsystemsForShipCopy(ship))} (order descending, then display text), never
 * MagicLib's key index (that one follows the local keyboard table, which differs per machine).
 *
 * <p><b>Frozen per ship.</b> The first computation on a ship freezes the numbering in an {@link IdentityHashMap}
 * stored in that ship's own customData under {@link #SLOTS_KEY}; every later lookup reads the frozen table, so a
 * subsystem added mid-life never renumbers the others (it is simply absent: -1 = the mod's own code runs). Identity,
 * never equals/hashCode: every instance is a third-party subclass whose equality is the mod's. The table dies with
 * the ship. A freeze whose MagicLib call throws stores an EMPTY table before the throw propagates, so the failure is
 * reported once by the caller and never retried per frame.
 *
 * <p><b>Value domain:</b> a slot is 0..7 ({@link #SLOT_COUNT} = the wire's eight ability bits) or -1 (absent from
 * the table, or the ninth and later subsystem: one {@code slot_unsupported} row per process, counted in
 * {@link #unsupported}). The pure core ({@link #freeze}, {@link #slotIn}) takes lists and instances only, so it runs
 * against compile stubs with no MagicLib on the path; {@link #slotOf} is the thin wrapper that calls MagicLib.
 * Single-threaded by use: the server's sim thread, the client's game thread.
 *
 */
public final class MagicSlots {

    /** The ship customData key holding the frozen {@code IdentityHashMap<MagicSubsystem, Integer>}. */
    public static final String SLOTS_KEY = "cc_client.magicSlots";
    /** Slots the wire carries: the AWSD ability bits 0..7. */
    public static final int SLOT_COUNT = 8;

    /** Lookups that hit a frozen slot of 8 or more (-1 returned); 0..Integer.MAX_VALUE. */
    static int unsupported;
    /** Lookups deferred because MagicLib had no map for the ship yet (nothing frozen, -1 returned); 0..Integer.MAX_VALUE. */
    static int deferred;
    private static boolean unsupportedLogged;

    private MagicSlots() {
    }

    /**
     * The frozen slot of {@code s} on {@code ship}: 0..7, or -1 (not in the frozen table, or slot 8 and up). The
     * first call on a ship freezes the table through MagicLib's own sort. Throws only what MagicLib's copy or sort
     * threw on that first call (the caller's catch reports it; the empty table is already stored).
     *
     */
    public static int slotOf(ShipAPI ship, MagicSubsystem s) {
        Map<MagicSubsystem, Integer> table = tableFor(ship);
        return table == null ? -1 : slotIn(table, s);
    }

    /**
     * The ship's frozen table, freezing it on first sight — or null while MagicLib has NO map for the ship yet (its
     * manager hands back a null copy before the first subsystem registers): nothing is stored then and the next call
     * asks again, so a table never freezes EMPTY for the ship's life. Once a copy exists, the {@code finally} stores an
     * empty table when MagicLib's sort throws, so the freeze law holds on failure too (one attempt per ship).
     * The null-copy deferral.
     */
    @SuppressWarnings("unchecked")
    static IdentityHashMap<MagicSubsystem, Integer> tableFor(ShipAPI ship) {
        Object t = ship.getCustomData().get(SLOTS_KEY);
        if (t instanceof IdentityHashMap) {
            return (IdentityHashMap<MagicSubsystem, Integer>) t;
        }
        IdentityHashMap<MagicSubsystem, Integer> frozen = null;
        boolean deferredNow = false;
        try {
            // The copy call sits INSIDE the try: a copy that THROWS (a real MagicLib failure; the compile stubs by
            // their own law) stores the EMPTY table too — one attempt per ship, never a per-frame retry.
            Collection<MagicSubsystem> copy = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
            if (copy == null) {
                deferredNow = true;
                deferred++;
                return null;
            }
            frozen = freeze(MagicSubsystemsManager.INSTANCE.sortSubsystems(copy));
        } finally {
            if (!deferredNow) {
                if (frozen == null) {
                    frozen = new IdentityHashMap<>();
                }
                ship.setCustomData(SLOTS_KEY, frozen);
            }
        }
        return frozen;
    }

    /**
     * PURE twin of {@link #tableFor}'s store step for the client rows (the compile stubs throw on the manager calls):
     * a null sorted copy stores nothing and returns null (counted in {@link #deferred}); a collection is frozen and
     * stored under {@link #SLOTS_KEY}.
     */
    static IdentityHashMap<MagicSubsystem, Integer> freezeOrDefer(ShipAPI ship,
            Collection<? extends MagicSubsystem> sortedOrNull) {
        if (sortedOrNull == null) {
            deferred++;
            return null;
        }
        IdentityHashMap<MagicSubsystem, Integer> frozen = freeze(sortedOrNull);
        ship.setCustomData(SLOTS_KEY, frozen);
        return frozen;
    }

    /**
     * PURE: number a sorted collection 0..n-1 by identity (all of it, so a ninth subsystem is reported as unsupported
     * rather than silently unknown). A null collection or element freezes nothing for that position.
     *
     */
    public static IdentityHashMap<MagicSubsystem, Integer> freeze(Collection<? extends MagicSubsystem> sorted) {
        IdentityHashMap<MagicSubsystem, Integer> t = new IdentityHashMap<>();
        if (sorted == null) {
            return t;
        }
        int k = 0;
        for (MagicSubsystem s : sorted) {
            if (s != null && !t.containsKey(s)) {
                t.put(s, Integer.valueOf(k));
            }
            k++;
        }
        return t;
    }

    /**
     * PURE: the slot of {@code s} in a frozen table, -1 when absent; a frozen slot of {@link #SLOT_COUNT} or more is
     * -1 plus one {@code slot_unsupported} row per process (counted every time).
     *
     */
    public static int slotIn(Map<MagicSubsystem, Integer> table, MagicSubsystem s) {
        Integer k = table.get(s);
        if (k == null) {
            return -1;
        }
        int slot = k.intValue();
        if (slot >= SLOT_COUNT || slot < 0) {
            unsupported++;
            if (!unsupportedLogged) {
                unsupportedLogged = true;
                PilotWatchLog.warn("magic-seam slot_unsupported slot=" + slot + " max=" + (SLOT_COUNT - 1)
                        + " class=" + s.getClass().getSimpleName()
                        + " -> the mod's own code runs for it (counted, logged once)");
            }
            return -1;
        }
        return slot;
    }

    /** Off-frame meter text: bounded ints only. */
    public static String describe() {
        return "slot_unsupported=" + unsupported + " slot_deferred=" + deferred;
    }
}
