package cc.starsector.server.probes;

import cc.starsector.server.transform.PilotShipWindow;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The typed target of the redirected {@code CombatEngineAPI.getPlayerShip()} call sites (HOOK-0055): the same
 * stack shape as the interface call it replaces (one reference in, one {@link ShipAPI} out), so a rewritten
 * class keeps its frames and maxs. Inside an open {@link PilotShipWindow} on the sim thread the answer is the
 * human-flown hull being advanced; everywhere else the engine's own getter answers, shuttle fabrication
 * included. Lives in the probe-api unit because it must be compiled against the game's API types, which the
 * product unit never links (reflection only); it lands in the server classes beside the holder, on the
 * system class path every mod loader delegates to. Main-written. The same class is the target of the redirected
 * LWJGL keyboard and mouse call sites and of the one {@code SettingsAPI.getControlStringForEnumName} interface call
 * (HOOK-0056, the ten twins below), so a rewritten class's pool gains one class reference whichever family it uses.
 */
public final class PilotShipCall {

    private PilotShipCall() {
    }

    public static ShipAPI playerShip(CombatEngineAPI engine) {
        Object answer = PilotShipWindow.answerOrNull();
        return answer instanceof ShipAPI ? (ShipAPI) answer : engine.getPlayerShip();
    }

    // The LWJGL keyboard / mouse twins (HOOK-0056). Each has the SAME descriptor as the static LWJGL method whose call
    // sites a candidate class gets redirected here, so a rewritten class keeps its frames and maxs. Inside an open
    // window on the sim thread the answer is that hull's pilot's held keys and buttons; everywhere else a key or
    // button reads up, a poll does nothing, the event queue is empty and the pointer is outside the window. The serve
    // has no display: the LWJGL originals throw there (no created device) or read a frozen zero state, so a twin
    // never reaches them.

    public static boolean isKeyDown(int key) {
        return PilotShipWindow.keyDown(key);
    }

    public static boolean isButtonDown(int button) {
        return PilotShipWindow.buttonDown(button);
    }

    public static boolean isInsideWindow() {
        return false;
    }

    public static void setCursorPosition(int x, int y) {
    }

    public static void pollKeyboard() {
    }

    public static void pollMouse() {
    }

    public static boolean nextKeyboard() {
        return false;
    }

    public static boolean nextMouse() {
        return false;
    }

    public static int numKeyboardEvents() {
        return 0;
    }

    /**
     * The tenth twin (HOOK-0056): the receiver-first shape of the redirected
     * {@code SettingsAPI.getControlStringForEnumName(name)} interface call. Inside an open window on the sim thread
     * whose hull's pilot sent a non-zero keymap, a control name among the 16 answers the pilot's OWN binding as the
     * engine would name it (null = unbound, the engine's own answer for an unbound control; the host's key is never
     * substituted); everywhere else — outside a window, a seat that sent no map, a name not among the 16 — the
     * engine's own answer, its throw for an unknown name kept. Main-written
     */
    public static String controlStringForEnumName(SettingsAPI settings, String name) {
        int entry = PilotShipWindow.controlEntryOrMinusOne(name);
        if (entry < 0) {
            return settings.getControlStringForEnumName(name);
        }
        return entry == 0 ? null : PilotShipWindow.controlNameOrNull(entry);
    }
}
