package cc.starsector.client.replica;

import java.util.function.IntPredicate;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;

import cc.starsector.client.HeldInputLatch;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.server.runtime.PilotControlNames;

/**
 * The pilot's OWN input sample for this frame, as the key and button level twins of {@link ReplicaPilotCall} answer
 * it outside every pilot window, so the rewritten mod key sites on the pilot's own hull hear exactly what the serve
 * hears for those same sites. WHY: the serve answers them from the wire levels the client's sampler sends, and the
 * sampler sends EMPTY levels while a text surface owns the keyboard (the chat box, the picker, a dialog, the codex, an
 * unfocused window) and drops a key or button held across that gate's falling edge until its own release. Answering
 * the raw input library here instead let a line typed into the open chat box ("ww") dash the pilot's own hull on this
 * screen alone while the serve and every other screen stayed still, until the own-velocity reconcile pulled it back.
 * With this holder a typed line never moves the hull on any screen, the pilot's own included.
 *
 * <p>LAW, per answer. Live = a sample published since the last {@link #reset}, read on the publishing thread, under
 * the combat engine that was current at that publish.
 * <ul>
 * <li>Not live: the real call, exactly the twin's answer before this holder existed (outside combat, before the
 * combat's first sample, another thread, a later combat whose plugin never published: an abandoned window's sample
 * never gates another combat's keys).</li>
 * <li>Live and the frame wrote EMPTY levels: false.</li>
 * <li>Live and the key or button is latched: false (a latched key leaves the latch in the sampler's own pass, on the
 * first frame it reads released).</li>
 * <li>Live, button 0, and the fire swallow holds: false.</li>
 * <li>Live and a code the wire cannot carry (a key outside 1..255, a button outside 0..7): false, as the serve
 * answers it.</li>
 * <li>Else the LIVE level: the same input-library state the sampler read this frame. Never a stored packed sample: a
 * mod that advances before the sampler in a frame would then hear the previous frame's levels, one frame late; the
 * live level adds no latency whatever the engine's advance order is.</li>
 * </ul>
 *
 * <p>Cost per answer ahead of the real call: one thread compare, one engine compare, the gate flag and one latch
 * lookup; no allocation and no logging on the answer path. The REMOTE replicas' in-window answers (the mark path of
 * {@link ReplicaPilotWindow}) never come here.
 *
 * <p>The own key-edge log, armed by {@code -Dcc.pilot.keyEdgeLog=true} (default OFF: the per-frame cost is one
 * constant test): one row per control bit that changed since the previous sample, at most {@link #EDGE_ROW_CAP} per
 * process, then one cap line and silence. Render thread only (plain statics, the shape of {@link ReplicaPilotWindow});
 * a {@link #reset} from another thread is best effort, and the engine compare ends a stale sample either way.
 *
 *
 *
 */
public final class OwnInputSample {

    /** The own key-edge log's arm, read once at class load. */
    public static final boolean KEY_EDGE_LOG = Boolean.getBoolean("cc.pilot.keyEdgeLog");

    /** OWN key-edge rows per process before the one cap line. */
    public static final int EDGE_ROW_CAP = 4096;

    private static final IntPredicate LWJGL_KEYS = new LwjglLevel(false);
    private static final IntPredicate LWJGL_BUTTONS = new LwjglLevel(true);

    private static IntPredicate keySource = LWJGL_KEYS;
    private static IntPredicate buttonSource = LWJGL_BUTTONS;

    /** The publishing (render) thread; null = not live. */
    private static Thread bound;
    /** The combat engine current at the last publish. */
    private static CombatEngineAPI engineAt;
    private static boolean emptyLevels;
    private static HeldInputLatch latch;
    private static boolean swallowFire;

    /** The previous sample's control bits (u16), for the key-edge log. */
    private static int priorControls;
    private static int edgeRows;

    private OwnInputSample() {
    }

    /**
     * Replaces the level sources (a game-free test injects both: the input library throws with no display); a null
     * restores that source's input-library call.
     */
    public static void setLevelSource(IntPredicate keyDown, IntPredicate buttonDown) {
        keySource = keyDown != null ? keyDown : LWJGL_KEYS;
        buttonSource = buttonDown != null ? buttonDown : LWJGL_BUTTONS;
    }

    /**
     * Once per frame, right after the sampler ran: {@code empty} = this frame wrote EMPTY levels; {@code heldLatch} =
     * the sampler's own latch (read here, never written); {@code swallowFireUntilRelease} = button 0 was cut from this
     * frame's button level.
     */
    public static void publish(boolean empty, HeldInputLatch heldLatch, boolean swallowFireUntilRelease) {
        bound = Thread.currentThread();
        engineAt = Global.getCombatEngine();
        emptyLevels = empty;
        latch = heldLatch;
        swallowFire = swallowFireUntilRelease;
    }

    /** Combat start and teardown: not live (the twins make the real call) and the key-edge baseline back to 0. */
    public static void reset() {
        bound = null;
        engineAt = null;
        emptyLevels = false;
        latch = null;
        swallowFire = false;
        priorControls = 0;
    }

    /** The own answer for {@code Keyboard.isKeyDown(code)} outside every pilot window (the class LAW). */
    public static boolean keyDown(int code) {
        if (Thread.currentThread() != bound || Global.getCombatEngine() != engineAt) {
            return keySource.test(code);
        }
        if (emptyLevels || code < 1 || code >= Keyboard.KEYBOARD_SIZE) {
            return false;
        }
        HeldInputLatch l = latch;
        return (l == null || !l.isLatched(code)) && keySource.test(code);
    }

    /** The own answer for {@code Mouse.isButtonDown(button)} outside every pilot window (the class LAW). */
    public static boolean buttonDown(int button) {
        if (Thread.currentThread() != bound || Global.getCombatEngine() != engineAt) {
            return buttonSource.test(button);
        }
        if (emptyLevels || button < 0 || button >= PilotControlNames.MOUSE_BUTTONS || button == 0 && swallowFire) {
            return false;
        }
        HeldInputLatch l = latch;
        return (l == null || (l.latchedButtons() >>> button & 1) == 0) && buttonSource.test(button);
    }

    /**
     * The armed key-edge compare, beside the publish: one row per control bit of {@code controls} (this frame's
     * sampled u16, 0 on an EMPTY frame) that differs from the previous sample's, rise = 0 to 1, fall = 1 to 0.
     */
    public static void logOwnKeyEdges(int frame, int controls, boolean empty) {
        int now = controls & 0xFFFF;
        int changed = now ^ priorControls;
        priorControls = now;
        while (changed != 0 && edgeRows <= EDGE_ROW_CAP) {
            int bit = Integer.numberOfTrailingZeros(changed);
            changed &= changed - 1;
            if (edgeRows++ < EDGE_ROW_CAP) {
                PilotWatchLog.info(ownEdgeRow(frame, bit, (now >>> bit & 1) != 0, now, empty));
            } else {
                PilotWatchLog.info("key-edge ROW CAP reached (" + EDGE_ROW_CAP + "); further rows suppressed");
            }
        }
    }

    /** One OWN key-edge row; bit i = index i of the shared control-name list, controls = 4 lowercase hex digits. */
    public static String ownEdgeRow(int frame, int bit, boolean rise, int controls, boolean empty) {
        String hex = Integer.toHexString(controls & 0xFFFF);
        return "predict own key-edge frame=" + frame + " bit=" + bit + (rise ? " rise" : " fall") + " controls=0x"
                + "0000".substring(hex.length()) + hex + " gated=" + (empty ? 1 : 0);
    }

    /** The input library's own level (no lambdas: no bootstrap on the first frame). */
    private static final class LwjglLevel implements IntPredicate {
        private final boolean mouse;

        LwjglLevel(boolean mouse) {
            this.mouse = mouse;
        }

        @Override
        public boolean test(int value) {
            return mouse ? Mouse.isButtonDown(value) : Keyboard.isKeyDown(value);
        }
    }
}
