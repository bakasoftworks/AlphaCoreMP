package cc.starsector.client.magic;

import java.util.Map;

import org.magiclib.subsystems.MagicSubsystem;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import cc.starsector.client.PilotWatchLog;

/**
 * The MagicLib pilot hook. A load-time patch of MagicLib's {@code MagicSubsystem.advanceInternal} calls
 * {@link #decide} at the head of the AI branch, the one place MagicLib asks "should this ship's ability activate"
 * for a ship that is not the local player's. On a server no seat's ship is ever the local player's, so without this
 * hook MagicLib would run the mod's own AI for every human's ability while that human's own game obeys the key.
 *
 * <p><b>The answer:</b> -1 = run the original AI call (its bytes are untouched and run OUTSIDE this hook, so a mod's
 * own exception propagates exactly as before); 0 / 1 = the activation key value MagicLib then feeds into its own
 * gate ({@code canActivate}, the state machine), which still applies every one of its checks.
 *
 * <p><b>The order</b> (cheapest refusal first; the unarmed path is one map lookup):
 * <ol>
 *   <li>the pilot mark: one {@code get} of {@link #MARK_KEY} in the ship's customData. Not an {@code int[]}, shorter
 *       than {@link #MARK_LENGTH_FOLLOW}, or FOLLOW clear in {@code mark[3]} = -1. FOLLOW is set only where the
 *       host enabled the seam, the server's patch applied, the door is armed and the seat reported its own MagicLib
 *       states, so an unarmed seat keeps today's behaviour;</li>
 *   <li>the engine's own player ship = -1 (a belt: that ship takes MagicLib's native human branch);</li>
 *   <li>an adopter class ({@link MagicAdopters}: the subsystem already reads the mark itself) = -1, and -1 also
 *       until the adopter set was minted;</li>
 *   <li>the frozen slot K ({@link MagicSlots}): absent or 8 and up = -1;</li>
 *   <li>DK = bit K set ? state == READY : (a toggle AND state == ACTIVE). A set bit presses once while READY; a
 *       cleared bit cancels an ACTIVE toggle once. "key = bit" would be WRONG: a held bit on an ACTIVE toggle would
 *       switch it off.</li>
 * </ol>
 * Everything sits in one {@code catch (Throwable)}: a throw answers -1 (the mod's AI, today's behaviour), is counted
 * in {@link #errors} and logged once per process.
 *
 * <p><b>Loading:</b> this class lives in the kit jar, which shares the game's one script-jar loader with MagicLib on
 * both the server and a game, so its MagicLib types resolve with no reflection. Its static initializer holds
 * constants only (the counters are default-zero ints), and the kit plugin pre-touches it at application load, so the
 * first armed call pays no class load on the sim thread. {@link #ABI} is read from this class's bytes by the patch's
 * resource probe before any patch is emitted; a change to {@code decide}'s descriptor or meaning bumps it.
 *
 * <p><b>Meters:</b> plain ints bumped only on the ARMED path (the sim thread; never on the unarmed path), read off the
 * frame by {@link #describe()}; 0..Integer.MAX_VALUE each.
 *
 */
public final class CcMagicSeam {

    /** The hook ABI the patch's resource probe requires (a {@code static final int} ConstantValue). */
    public static final int ABI = 1;
    /** The pilot mark key: {@code int[]{seat, mask, role, flags}}, append-only (contract-v1 readers take length 2). */
    public static final String MARK_KEY = "cc_client.pilot";
    /** The shortest mark that carries the flags word. */
    public static final int MARK_LENGTH_FOLLOW = 4;
    /** {@code mark[3]} bit 0: FOLLOW the pilot's reported ability states. */
    public static final int FLAG_FOLLOW = 0x01;
    /** The answer that runs MagicLib's original AI call. */
    public static final int RUN_ORIGINAL = -1;

    static int armedCalls;
    static int ownBelt;
    static int noAdopterSet;
    static int adopterDefers;
    static int slotMisses;
    static int keyDown;
    static int keyUp;
    static int errors;
    private static boolean errorLogged;

    private CcMagicSeam() {
    }

    /**
     * The hook MagicLib's patched AI branch calls; see the class javadoc for the answer and the order.
     * {@code ship} is passed explicitly by the patch (MagicLib's {@code protected final} field has no getter).
     *
     */
    public static int decide(MagicSubsystem s, ShipAPI ship) {
        try {
            Map<String, Object> data = ship.getCustomData();
            Object m = data != null ? data.get(MARK_KEY) : null;
            if (!(m instanceof int[])) {
                return RUN_ORIGINAL;
            }
            int[] mark = (int[]) m;
            if (mark.length < MARK_LENGTH_FOLLOW || (mark[3] & FLAG_FOLLOW) == 0) {
                return RUN_ORIGINAL;
            }
            CombatEngineAPI engine = Global.getCombatEngine();
            return armed(s, ship, mark[1], engine != null ? engine.getPlayerShip() : null);
        } catch (Throwable t) {
            return failed(t);
        }
    }

    /**
     * The armed tail of {@link #decide} (FOLLOW is set): the belt, the adopter defer, the slot, then DK.
     * {@code mask} bit K = the pilot's slot-K ability state (bits 0..7; higher bits are never read). May throw what
     * the subsystem's own getters or MagicLib's sort throw; {@link #decide} is the catch.
     *
     */
    static int armed(MagicSubsystem s, ShipAPI ship, int mask, ShipAPI playerShip) {
        armedCalls++;
        if (ship == playerShip) {
            ownBelt++;
            return RUN_ORIGINAL;
        }
        MagicAdopters adopters = MagicAdopters.current();
        if (adopters == null) {
            noAdopterSet++;
            return RUN_ORIGINAL;
        }
        if (adopters.isAdopter(s.getClass())) {
            adopterDefers++;
            return RUN_ORIGINAL;
        }
        int k = MagicSlots.slotOf(ship, s);
        if (k < 0) {
            slotMisses++;
            return RUN_ORIGINAL;
        }
        MagicSubsystem.State st = s.getState();
        boolean key = ((mask >>> k) & 1) != 0
                ? st == MagicSubsystem.State.READY
                : s.isToggle() && st == MagicSubsystem.State.ACTIVE;
        if (key) {
            keyDown++;
            return 1;
        }
        keyUp++;
        return 0;
    }

    private static int failed(Throwable t) {
        errors++;
        if (!errorLogged) {
            errorLogged = true;
            PilotWatchLog.warn("magic-seam decide threw " + t.getClass().getSimpleName()
                    + " -> the mod's own AI call runs (counted, logged once)");
        }
        return RUN_ORIGINAL;
    }

    /**
     * Off-frame meter text (bounded ints), also the application-load pre-touch: calling it loads and initializes
     * this class outside any combat frame.
     *
     */
    public static String describe() {
        return "abi=" + ABI + " armed=" + armedCalls + " own_belt=" + ownBelt + " no_adopter_set=" + noAdopterSet
                + " adopter_defer=" + adopterDefers + " slot_miss=" + slotMisses + " key_down=" + keyDown
                + " key_up=" + keyUp + " errors=" + errors + " " + MagicSlots.describe();
    }
}
