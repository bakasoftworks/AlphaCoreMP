package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipManeuverCommandView;
import cc.starsector.server.transform.TransformRegistry;

/**
 * Whether the serve follows a seated pilot's own MagicLib ability decision on that pilot's hull — the pilot mark's
 * FOLLOW flag (flags bit 0) — and, when it does not, the one reason why. In host words: "MagicLib abilities follow the
 * pilot" only when ALL of these hold: the host left the MagicLib seam on, the serve's subsystem patch applied, the
 * match door is armed (only the armed door pins MagicLib's jars and data, and the kit, on both sides, so slot K names
 * the same ability on the serve and the pilot's game — a skew would fire the WRONG ability), and the seat's own game
 * said in its HELLO that it reports its MagicLib states (bit 0x08). Otherwise the hull keeps the mod's own AI for its
 * subsystems: never a dead ability.
 *
 * <p>WHY {@code off} is its own token: an {@code off} receipt is the HOST's choice (the seam row turned off), while
 * every other non-applied receipt ({@code armed}, {@code disarmed:<reason>}, absent) is the serve's own verdict about
 * the MagicLib build it met. A host reading "not followed" must be able to tell "I turned it off" from "it could not
 * arm", so the two never share a reason.
 *
 * <p>The ONE home of this predicate: the pilot mark's writer and the ops status file both call {@link #whyOrNull};
 * nobody re-states it. Reasons are checked in a fixed order ({@code host_off}, {@code not_applied},
 * {@code door_unarmed}, {@code no_up}) so one seat always reports exactly one, stable reason. Inputs are bounded:
 * the receipt is the premain's own sysprop (never player text), the door fact a boolean, the HELLO bits an int.
 *
 */
public final class MagicSeamFollow {

    /** The host turned the MagicLib seam off (the receipt reads {@code off}). */
    public static final String WHY_HOST_OFF = "host_off";
    /** The serve's subsystem patch did not apply: armed but never seen, disarmed for a reason, or no premain. */
    public static final String WHY_NOT_APPLIED = "not_applied";
    /** The match door is not armed, so the two sides' MagicLib builds are not pinned equal. */
    public static final String WHY_DOOR_UNARMED = "door_unarmed";
    /** The seat's HELLO did not carry bit 0x08: its game does not report its MagicLib states. */
    public static final String WHY_NO_UP = "no_up";

    private MagicSeamFollow() {
    }

    /**
     * {@code null} = FOLLOW (the serve follows the pilot's MagicLib decision on the seat's hull), else exactly one of
     * {@link #WHY_HOST_OFF} | {@link #WHY_NOT_APPLIED} | {@link #WHY_DOOR_UNARMED} | {@link #WHY_NO_UP}, checked in
     * that order. {@code receiptOrNull} = the {@code cc.magic.seam} sysprop value (null = no premain decided);
     * {@code doorArmed} = the match door's fingerprint is set; {@code helloMagicBits} = the seat's HELLO flag bits.
     */
    public static String whyOrNull(String receiptOrNull, boolean doorArmed, int helloMagicBits) {
        if (TransformRegistry.MAGIC_SEAM_RECEIPT_OFF.equals(receiptOrNull)) {
            return WHY_HOST_OFF;
        }
        if (receiptOrNull == null || !receiptOrNull.startsWith(TransformRegistry.MAGIC_SEAM_RECEIPT_APPLIED_PREFIX)) {
            return WHY_NOT_APPLIED;
        }
        if (!doorArmed) {
            return WHY_DOOR_UNARMED;
        }
        if ((helloMagicBits & Handshake13.FLAG_MAGIC_UP) == 0) {
            return WHY_NO_UP;
        }
        return null;
    }

    /** The mark's flags byte for that verdict: {@link ShipManeuverCommandView#PILOT_FLAG_FOLLOW} on FOLLOW, else 0. */
    public static int markFlags(String receiptOrNull, boolean doorArmed, int helloMagicBits) {
        return whyOrNull(receiptOrNull, doorArmed, helloMagicBits) == null ? ShipManeuverCommandView.PILOT_FLAG_FOLLOW
                : 0;
    }

    /** The live receipt: the premain's {@code cc.magic.seam} sysprop, or null when no premain decided. */
    public static String receiptOrNull() {
        return System.getProperty(TransformRegistry.MAGIC_SEAM_RECEIPT_SYSPROP);
    }

    /**
     * Whether MagicLib is evidently in this match for that seat — the gate on the host's "not followed" row, so a
     * match without MagicLib prints nothing: the serve met MagicLib's subsystem class (the receipt is
     * {@code applied:} or {@code disarmed:}) or the seat's own game reports MagicLib states (HELLO bit 0x08).
     */
    public static boolean magicLibEvident(String receiptOrNull, int helloMagicBits) {
        if ((helloMagicBits & Handshake13.FLAG_MAGIC_UP) != 0) {
            return true;
        }
        return receiptOrNull != null
                && (receiptOrNull.startsWith(TransformRegistry.MAGIC_SEAM_RECEIPT_APPLIED_PREFIX)
                        || receiptOrNull.startsWith(TransformRegistry.MAGIC_SEAM_RECEIPT_DISARMED_PREFIX));
    }
}
