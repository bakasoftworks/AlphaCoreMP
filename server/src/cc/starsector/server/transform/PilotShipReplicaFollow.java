package cc.starsector.server.transform;

import cc.starsector.server.adapter.ShipManeuverCommandView;
import cc.starsector.server.runtime.ServerConfig;

/**
 * The serve pilot mark's flags bits 1-2: bit 1 CONTROLS = the mark's {@code [4]} (the pilot's applied held controls)
 * is present and meaningful; bit 2 REPLICA_FOLLOW = the serve's player-ship change is live for this hull, so other
 * screens may run the hull's own scripts as the pilot's. ONE pure gate ({@link #flags}) the mark's writer calls on
 * every applied frame, plus the one reader of the match-wide terms it folds ({@link #readServeTerms}).
 *
 * <p>WHY bit 2 and not the seat alone: a seat &gt;= 1 also holds while the serve's change is off, refused, latched or
 * unarmed. The serve then runs the hull's scripts on the AI branch, and a viewer keyed on the seat would run the
 * player branch: a new desync. So bit 2 carries every term the serve's own branch depends on, and a latched,
 * refused or unarmed serve answers 0, which sends the viewers down the AI branch with it.
 *
 * <p>WHY bit 1 is not just "the change is on": a held-controls stream nobody reads is withheld. It flows while bit
 * 2 holds (the viewers' rewritten scripts read it, so {@code [4]} = the controls the hull's scripts asked about
 * inside the serve's window) or while the serve loaded a class that reads the mark itself (an adopter: its reads
 * happen OUTSIDE any window, nothing is ever asked, so {@code [4]} = the full u16 - a masked slot would read 0 and
 * carry nothing); otherwise {@code [4]} reads 0 and bit 1 is clear.
 *
 * <p>WHY every frame: the window can latch mid-match. A flag folded once per hull tenure would keep telling viewers to
 * follow a pilot the serve itself stopped following. The per-frame form costs a few ANDs over terms the writer
 * resolved at the tenure edge plus the latch's one volatile read; no allocation, no reflection.
 *
 * <p>Bit 0 (FOLLOW) belongs to the MagicLib seam's predicate; this gate never sets or clears it.
 *
 */
public final class PilotShipReplicaFollow {

    public static final int CONTROLS = ShipManeuverCommandView.PILOT_FLAG_CONTROLS;
    public static final int REPLICA_FOLLOW = ShipManeuverCommandView.PILOT_FLAG_REPLICA_FOLLOW;
    /** The flags bits this gate owns. */
    public static final int GATE_BITS = CONTROLS | REPLICA_FOLLOW;

    /** The host row "other players' screens follow each pilot's keys": sysprop &gt; env &gt; config file &gt; on. */
    public static final String REPLICAS_SYSPROP = "cc.pilotship.replicas";
    public static final String REPLICAS_ENV = "CC_PILOTSHIP_REPLICAS";
    public static final String REPLICAS_FILE_KEY = "pilotship.replicas";
    /** The premain applier whose count row carries the adopter census (the serve unit compiles first, cannot link it). */
    static final String APPLIER_CLASS = "cc.starsector.server.premain.PilotShipApplier";

    /** {@link #readServeTerms} bits: one per match-wide term of the gate. */
    public static final int T_ENABLED = 1;
    public static final int T_REPLICAS = 2;
    public static final int T_RECEIPT_APPLIED = 4;
    public static final int T_DOOR_ARMED = 8;
    public static final int T_EXCLUDE_EMPTY = 16;
    public static final int T_ADOPTER = 32;

    private static volatile boolean adopterSeen;
    private static java.lang.reflect.Method countsRow;
    private static boolean countsRowAbsent;
    private static boolean termFaultLogged;

    private PilotShipReplicaFollow() {
    }

    /**
     * The gate. {@code enabled} = the host's player-ship row is on; {@code applied} = the seat's frames are applied;
     * {@code replicasOn} = the host row {@link #REPLICAS_FILE_KEY}; {@code receiptApplied} = the window receipt reads
     * {@code applied:}; {@code doorArmed} = the match door pins both sides' mod files equal; {@code excludeEmpty} = no
     * host exclusion keeps a class unchanged on the serve only; {@code latched} = the window latched off;
     * {@code adopterHit} = the serve loaded a class that reads the mark itself. Returns bits 1-2 only.
     */
    public static int flags(boolean enabled, boolean applied, boolean replicasOn, boolean receiptApplied,
            boolean doorArmed, boolean excludeEmpty, boolean latched, boolean adopterHit) {
        if (!enabled || !applied) {
            return 0;
        }
        if (replicasOn && receiptApplied && doorArmed && excludeEmpty && !latched) {
            return GATE_BITS;
        }
        return adopterHit ? CONTROLS : 0;
    }

    /** The per-frame form over the packed {@link #readServeTerms} bits (the T_* constants). */
    public static int flags(int terms, boolean applied, boolean latched) {
        return flags((terms & T_ENABLED) != 0, applied, (terms & T_REPLICAS) != 0, (terms & T_RECEIPT_APPLIED) != 0,
                (terms & T_DOOR_ARMED) != 0, (terms & T_EXCLUDE_EMPTY) != 0, latched, (terms & T_ADOPTER) != 0);
    }

    public static boolean controlsPresent(int flags) {
        return (flags & CONTROLS) != 0;
    }

    /** The window receipt's live class (armed, {@code applied:*}, {@code latched:*}) = the premain turned it on. */
    public static boolean receiptLive(String receiptOrNull) {
        return TransformRegistry.PILOT_SHIP_RECEIPT_ARMED.equals(receiptOrNull) || receiptApplied(receiptOrNull)
                || (receiptOrNull != null && receiptOrNull.startsWith(TransformRegistry.PILOT_SHIP_RECEIPT_LATCHED_PREFIX));
    }

    public static boolean receiptApplied(String receiptOrNull) {
        return receiptOrNull != null && receiptOrNull.startsWith(TransformRegistry.PILOT_SHIP_RECEIPT_APPLIED_PREFIX);
    }

    /**
     * The match-wide terms, read at a mark's tenure edge (never per frame): {@code enabled} from the window receipt's
     * live class (what the premain decided from the host row); the replicas row, the door row and the exclusion row
     * through the runtime's own tiered reader; the adopter census. The door reads armed exactly when its row reads
     * {@code true}, the session's own boot rule (any other non-false value refuses the boot). The exclusion row is a
     * file row the premain reads; a value in the reader's other tiers can only clear bit 2, never set it. Never
     * throws: a fault reads 0 (bits 1-2 clear) and logs once.
     */
    public static int readServeTerms() {
        try {
            String receipt = System.getProperty(TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP);
            int t = (receiptLive(receipt) ? T_ENABLED : 0) | (receiptApplied(receipt) ? T_RECEIPT_APPLIED : 0);
            t |= ServerConfig.resolveBoolean(REPLICAS_SYSPROP, REPLICAS_ENV, REPLICAS_FILE_KEY, true) ? T_REPLICAS : 0;
            t |= "true".equals(ServerConfig.resolve("cc.net.doorFingerprint", "CC_NET_DOOR_FINGERPRINT",
                    "net.doorFingerprint", "").trim()) ? T_DOOR_ARMED : 0;
            t |= ServerConfig.resolve("cc.pilotship.exclude", null, TransformRegistry.PILOT_SHIP_EXCLUDE_FILE_KEY, "")
                    .replace(',', ' ').isBlank() ? T_EXCLUDE_EMPTY : 0;
            return t | (adopterHit() ? T_ADOPTER : 0);
        } catch (RuntimeException fault) {
            logTermFaultOnce("terms " + fault.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Whether the premain classified any loaded class as an adopter (its pool names the mark's key). Sticky once true
     * (a loaded class never unloads from the census); read reflectively off the premain's count row. A JVM without the
     * premain on the system loader answers false once and never looks again.
     */
    static boolean adopterHit() {
        if (adopterSeen || countsRowAbsent) {
            return adopterSeen;
        }
        try {
            if (countsRow == null) {
                countsRow = Class.forName(APPLIER_CLASS, false, ClassLoader.getSystemClassLoader()).getMethod("countsRow");
            }
            adopterSeen = adopterCount((String) countsRow.invoke(null)) > 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            countsRowAbsent = true;
            logTermFaultOnce("adopter_census " + absent.getClass().getSimpleName());
        }
        return adopterSeen;
    }

    /** The {@code adopter=<n>} bucket of the premain's count row; -1 when the row is null or carries no such bucket. */
    static int adopterCount(String countsRowOrNull) {
        String key = " adopter=";
        int at = countsRowOrNull == null ? -1 : countsRowOrNull.indexOf(key);
        if (at < 0) {
            return -1;
        }
        int n = 0;
        for (int i = at + key.length(); i < countsRowOrNull.length() && n < 100_000_000; i++) {
            char c = countsRowOrNull.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    private static void logTermFaultOnce(String token) {
        if (!termFaultLogged) {
            termFaultLogged = true;
            System.out.println("pilotship replica terms fault (one-shot; that term reads off): " + token);
        }
    }
}
