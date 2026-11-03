package cc.starsector.client.replica;

import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.SoundAPI;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.mission.FleetSide;

import cc.starsector.server.runtime.PilotControlNames;

/**
 * The twins the client's load-time agent swaps into the mod classes the shared verdict rewrites (each a public
 * static whose name and descriptor are the agent's client twin table, so a missing one refuses the class and it
 * loads as shipped), plus the api-entry wrap's {@link #enter} / {@link #exit} and the dealt-damage entry
 * {@link #enterDealt}. Outside every pilot window ({@link ReplicaPilotWindow}) each twin is the REAL call after one
 * compare, save {@link #isKeyDown} and {@link #isButtonDown}: while the combat's input sampler publishes, those answer
 * the pilot's own frame sample ({@link OwnInputSample}: false while a text surface owns the keyboard, a key held across
 * that gate dropped until its release, else the real call), so a mod on the pilot's own hull hears what the serve
 * hears for it. Inside a window
 * opened for a replica its pilot flies, each answers what that pilot's own game answers:
 *
 * <ul>
 * <li>Input: {@link #isKeyDown} is the OR of the pilot's held control bits whose binding on THIS machine is that
 * key; {@link #isButtonDown} the same over mouse-bound controls; the event and pointer twins are inert (no poll, no
 * event, no cursor move, the pointer outside the window). WHY this viewer's bindings: the pilot's controls arrive as
 * sixteen control bits, never as key codes (raw keys typed under another window must never reach other players), and
 * a mod on this machine learned which key means "accelerate" from this machine's settings, so control i answers
 * the key this viewer bound to control i. A control bound to nothing, to a modifier combination or to the wheel
 * answers false. The by-name binding read stays the real call: it is this viewer's own binding, which is what the
 * key reads above are matched against.</li>
 * <li>The player ship is the window's root (the hull the pilot flies), as the pilot's own game answers.</li>
 * <li>The viewer's screen: the status and battle-control writers, the combat UI writers, the viewport writers, the
 * UI sound and music calls DROP ({@link #playUISound} answers null); the battle's end and the camera are this
 * viewer's own, and the pilot's own game already shows the pilot their HUD.</li>
 * <li>The UI autopilot read answers TRUE: the pilot's own game's answer while a human flies the ship (the engine's
 * getter reads true exactly when the player's ship is under manual control, despite its name).</li>
 * <li>AI: {@code getAI} / {@code getShipAI} answer null for the window's ROOT only. WHY the root only: on the
 * pilot's screen the flown hull has no AI driving it while its station modules keep theirs; every replica carries a
 * hold-AI here, so the root's AI read would otherwise take the AI branch beside the pilot's keys.</li>
 * </ul>
 *
 * <p>Never a guess at the pilot's state: the twins answer the wire's sixteen control bits through this viewer's
 * bindings and nothing else. No allocation and no logging on any twin path; the binding table is built once per
 * match at the first in-window read behind {@link BindingSource} (the settings are readable then), from the same
 * resolver the HELLO keymap uses.
 *
 *
 */
public final class ReplicaPilotCall {

    /** The controls a pilot frame carries (bit i = control i of the shared control-name list). */
    static final int CONTROLS = PilotControlNames.CONTROLS;

    /**
     * The viewer's own keymap: per control-name index, the bound key code (1..0xDF), {@code 0xF0 + button} for a
     * mouse-bound control, 0 when unbound or unexpressible. A short or null map leaves the rest unbound.
     */
    public interface BindingSource {
        byte[] keymap();
    }

    private static final BindingSource ENGINE_BINDINGS = new EngineBindings();
    private static BindingSource bindingSource = ENGINE_BINDINGS;
    private static int[] bindings;

    private ReplicaPilotCall() {
    }

    /** Replaces the binding source (a game-free test injects one); null restores the engine's own settings. */
    public static void setBindingSource(BindingSource source) {
        bindingSource = source != null ? source : ENGINE_BINDINGS;
        bindings = null;
    }

    /** The next in-window read rebuilds the table (combat start; a rebind between matches is honoured). */
    static void forgetBindings() {
        bindings = null;
    }

    private static int[] bindings() {
        int[] b = bindings;
        if (b == null) {
            b = buildBindings();
            bindings = b;
        }
        return b;
    }

    private static int[] buildBindings() {
        int[] out = new int[CONTROLS];
        try {
            byte[] km = bindingSource.keymap();
            for (int i = 0; km != null && i < CONTROLS && i < km.length; i++) {
                int v = km[i] & 0xFF;
                out[i] = PilotControlNames.lawful(v) ? v : 0;
            }
        } catch (RuntimeException unreadable) {
            ReplicaPilotWindow.countError(); // every control unbound: in-window key reads answer false
        }
        return out;
    }

    /** The engine's own bindings, through the shared resolver (no lambdas: no bootstrap on the first frame). */
    private static final class EngineBindings
            implements BindingSource, Function<String, String>, ToIntFunction<String> {
        @Override
        public byte[] keymap() {
            return PilotControlNames.resolveKeymap(this, this);
        }

        @Override
        public String apply(String controlName) {
            return Global.getSettings().getControlStringForEnumName(controlName);
        }

        @Override
        public int applyAsInt(String keyName) {
            return Keyboard.getKeyIndex(keyName);
        }
    }

    /** Whether the pilot holds a control bound here to {@code want} (a key code or {@code 0xF0 + button}). */
    private static boolean held(int controls, int want) {
        if (controls == 0) {
            return false;
        }
        int[] b = bindings();
        for (int i = 0; i < CONTROLS; i++) {
            if (((controls >>> i) & 1) != 0 && b[i] == want) {
                return true;
            }
        }
        return false;
    }

    /** True when inside a pilot window (the call is then dropped and counted); false = make the real call. */
    private static boolean screenDropped() {
        if (ReplicaPilotWindow.pilotSlot() < 0) {
            return false;
        }
        ReplicaPilotWindow.countScreenDrop();
        return true;
    }

    // ---- the wrap's entry and exit ----

    public static void enter(Object param) {
        ReplicaPilotWindow.enter(param);
    }

    public static void exit() {
        ReplicaPilotWindow.exit();
    }

    public static void enterDealt(Object param) {
        ReplicaPilotWindow.enterDealt(param);
    }

    // ---- the player ship ----

    public static ShipAPI playerShip(CombatEngineAPI engine) {
        int s = ReplicaPilotWindow.pilotSlot();
        return s < 0 ? engine.getPlayerShip() : (ShipAPI) ReplicaPilotWindow.rootAt(s);
    }

    // ---- input ----

    public static boolean isKeyDown(int code) {
        int s = ReplicaPilotWindow.pilotSlot();
        if (s < 0) {
            return OwnInputSample.keyDown(code);
        }
        ReplicaPilotWindow.countKeyRead();
        return code >= 1 && code <= PilotControlNames.KEY_MAX && held(ReplicaPilotWindow.controlsAt(s), code);
    }

    public static boolean isButtonDown(int button) {
        int s = ReplicaPilotWindow.pilotSlot();
        if (s < 0) {
            return OwnInputSample.buttonDown(button);
        }
        ReplicaPilotWindow.countKeyRead();
        return button >= 0 && button < PilotControlNames.MOUSE_BUTTONS
                && held(ReplicaPilotWindow.controlsAt(s), PilotControlNames.MOUSE_BASE + button);
    }

    public static void pollKeyboard() {
        if (ReplicaPilotWindow.pilotSlot() < 0) {
            Keyboard.poll();
        }
    }

    public static boolean nextKeyboard() {
        return ReplicaPilotWindow.pilotSlot() < 0 && Keyboard.next();
    }

    public static int numKeyboardEvents() {
        return ReplicaPilotWindow.pilotSlot() < 0 ? Keyboard.getNumKeyboardEvents() : 0;
    }

    public static void pollMouse() {
        if (ReplicaPilotWindow.pilotSlot() < 0) {
            Mouse.poll();
        }
    }

    public static boolean nextMouse() {
        return ReplicaPilotWindow.pilotSlot() < 0 && Mouse.next();
    }

    public static void setCursorPosition(int x, int y) {
        if (ReplicaPilotWindow.pilotSlot() < 0) {
            Mouse.setCursorPosition(x, y);
        }
    }

    public static boolean isInsideWindow() {
        return ReplicaPilotWindow.pilotSlot() < 0 && Mouse.isInsideWindow();
    }

    public static String controlStringForEnumName(SettingsAPI settings, String enumName) {
        return settings.getControlStringForEnumName(enumName);
    }

    // ---- autopilot and AI ----

    public static boolean isUIAutopilotOn(CombatEngineAPI engine) {
        return ReplicaPilotWindow.pilotSlot() >= 0 || engine.isUIAutopilotOn();
    }

    public static Object getAI(ShipAPI ship) {
        return isWindowRoot(ship) ? null : ship.getAI();
    }

    public static Object getAI(CombatEntityAPI entity) {
        return isWindowRoot(entity) ? null : entity.getAI();
    }

    public static ShipAIPlugin getShipAI(ShipAPI ship) {
        return isWindowRoot(ship) ? null : ship.getShipAI();
    }

    private static boolean isWindowRoot(Object receiver) {
        int s = ReplicaPilotWindow.pilotSlot();
        if (s < 0) {
            return false;
        }
        ReplicaPilotWindow.countAiRead();
        return receiver != null && receiver == ReplicaPilotWindow.rootAt(s);
    }

    // ---- status and battle control ----

    public static void maintainStatusForPlayerShip(CombatEngineAPI engine, Object key, String spriteName,
            String title, String data, boolean isDebuff) {
        if (!screenDropped()) {
            engine.maintainStatusForPlayerShip(key, spriteName, title, data, isDebuff);
        }
    }

    public static void setPaused(CombatEngineAPI engine, boolean paused) {
        if (!screenDropped()) {
            engine.setPaused(paused);
        }
    }

    public static void endCombat(CombatEngineAPI engine, float delay) {
        if (!screenDropped()) {
            engine.endCombat(delay);
        }
    }

    public static void endCombat(CombatEngineAPI engine, float delay, FleetSide winner) {
        if (!screenDropped()) {
            engine.endCombat(delay, winner);
        }
    }

    public static void setDoNotEndCombat(CombatEngineAPI engine, boolean doNotEnd) {
        if (!screenDropped()) {
            engine.setDoNotEndCombat(doNotEnd);
        }
    }

    // ---- combat UI writers ----

    public static void addMessage(CombatUIAPI ui, int priority, Object[] parts) {
        if (!screenDropped()) {
            ui.addMessage(priority, parts);
        }
    }

    public static void setDisablePlayerShipControlOneFrame(CombatUIAPI ui, boolean disable) {
        if (!screenDropped()) {
            ui.setDisablePlayerShipControlOneFrame(disable);
        }
    }

    public static void setShipInfoFanOutBrightness(CombatUIAPI ui, float brightness) {
        if (!screenDropped()) {
            ui.setShipInfoFanOutBrightness(brightness);
        }
    }

    public static void hideShipInfo(CombatUIAPI ui) {
        if (!screenDropped()) {
            ui.hideShipInfo();
        }
    }

    public static void setStrafeToggledOn(CombatUIAPI ui, boolean on) {
        if (!screenDropped()) {
            ui.setStrafeToggledOn(on);
        }
    }

    // ---- viewport writers ----

    public static void set(ViewportAPI viewport, float llx, float lly, float width, float height) {
        if (!screenDropped()) {
            viewport.set(llx, lly, width, height);
        }
    }

    public static void setViewMult(ViewportAPI viewport, float mult) {
        if (!screenDropped()) {
            viewport.setViewMult(mult);
        }
    }

    public static void setExternalControl(ViewportAPI viewport, boolean external) {
        if (!screenDropped()) {
            viewport.setExternalControl(external);
        }
    }

    public static void setCenter(ViewportAPI viewport, Vector2f center) {
        if (!screenDropped()) {
            viewport.setCenter(center);
        }
    }

    public static void setAlphaMult(ViewportAPI viewport, float alphaMult) {
        if (!screenDropped()) {
            viewport.setAlphaMult(alphaMult);
        }
    }

    public static void setEverythingNearViewport(ViewportAPI viewport, boolean near) {
        if (!screenDropped()) {
            viewport.setEverythingNearViewport(near);
        }
    }

    // ---- UI sound and music ----

    public static SoundAPI playUISound(SoundPlayerAPI player, String id, float pitch, float volume) {
        return screenDropped() ? null : player.playUISound(id, pitch, volume);
    }

    public static void playUILoop(SoundPlayerAPI player, String id, float pitch, float volume) {
        if (!screenDropped()) {
            player.playUILoop(id, pitch, volume);
        }
    }

    public static void playMusic(SoundPlayerAPI player, int fadeOut, int fadeIn, String musicId) {
        if (!screenDropped()) {
            player.playMusic(fadeOut, fadeIn, musicId);
        }
    }

    public static void playCustomMusic(SoundPlayerAPI player, int fadeOut, int fadeIn, String musicId) {
        if (!screenDropped()) {
            player.playCustomMusic(fadeOut, fadeIn, musicId);
        }
    }

    public static void playCustomMusic(SoundPlayerAPI player, int fadeOut, int fadeIn, String musicId,
            boolean looping) {
        if (!screenDropped()) {
            player.playCustomMusic(fadeOut, fadeIn, musicId, looping);
        }
    }

    public static void pauseMusic(SoundPlayerAPI player) {
        if (!screenDropped()) {
            player.pauseMusic();
        }
    }

    public static void pauseCustomMusic(SoundPlayerAPI player) {
        if (!screenDropped()) {
            player.pauseCustomMusic();
        }
    }

    public static void resumeCustomMusic(SoundPlayerAPI player) {
        if (!screenDropped()) {
            player.resumeCustomMusic();
        }
    }

    public static void restartCurrentMusic(SoundPlayerAPI player) {
        if (!screenDropped()) {
            player.restartCurrentMusic();
        }
    }

    public static void setSuspendDefaultMusicPlayback(SoundPlayerAPI player, boolean suspend) {
        if (!screenDropped()) {
            player.setSuspendDefaultMusicPlayback(suspend);
        }
    }
}
