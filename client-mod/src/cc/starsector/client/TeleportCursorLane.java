package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

/**
 * The client half of {@code /teleport}.
 *
 * UX: typing {@code /teleport}
 * ARMS this lane (a local toggle, the {@code /settings} intercept shape — the line never reaches
 * the server); while armed, a fresh {@code T} press with the chat box closed converts the mouse
 * cursor to WORLD coordinates and sends {@code /teleport @x,y} down the ordinary CHTU chat lane
 * (no new wire surface; the server re-checks admin, re-parses and map-clamps the pose, then sets
 * the hull's location authoritatively — the SNAP reconcile snaps the client). {@code /teleport}
 * again disarms. Presentation-side only: the arm is gated on {@code MatchConfigFromWire.seatAdmin()}
 * at the {@link ChatLane} intercept ({@code /teleport} is ADMIN-ONLY in every match since the
 * server dropped its debug-watch carve-out), but nothing here grants
 * anything — the door does. <b>Debug watch = PRE-ARMED for an ADMIN seat</b>:
 * on WELCOME bit1 + the seat's admin bit the arm latches ON by itself ({@link #preArmForDebugWatch})
 * — T works from the first key event, no verb typed; the first bare {@code /teleport} then
 * DISARMS, and that disarm sticks for the session. A non-admin debug seat never arms.
 *
 * <p><b>Cursor → world, two transforms</b>: with the
 * tactical map open ({@code CombatUIAPI.isShowingCommandUI()}; {@code CombatState} is
 * the impl, so the engine's {@code getCombatUI()} IS the {@code CombatState}), the warroom
 * ({@code CombatState.getWarroom()}) owns a map
 * display ({@code getMapDisplay()}) whose public
 * method {@code (float screenX, float screenY) → Vector2f world} is the
 * engine's own map-screen → world inverse: {@code (s - screenDim/2 - pan) / scale} with
 * {@code scale = 0.075 / zoom} and {@code pan} the drag offset — the SAME transform the engine's
 * map click handler feeds. The package name {@code new} is a Java keyword,
 * so those two types are NOT denotable in source — the chain rides {@code var} (inferred from
 * the class files, --release 17) and the method is spelled by Unicode escape; engine-typed adapter
 * code is B-legal and the build's b-deny gate is the proof. Map closed: the normal
 * view's {@link ViewportAPI#convertScreenXToWorldX} pair (the {@code /spawnfoe} cursor-pose
 * recipe). Fail-soft everywhere: any throw or a non-finite/off-grammar coordinate = null (the
 * caller tells the player locally; nothing is sent).
 *
 * <p>Render thread only (the chat feed's own thread; {@code Mouse} is read here exactly as the
 * aim path reads it). Key id pinned from LWJGL 2's {@code Keyboard.KEY_T} (= 0x14).
 */
final class TeleportCursorLane {

    /** LWJGL 2 {@code Keyboard.KEY_T} (0x14). Chosen because the warroom binds no {@code T} of
     * its own and the ship
     * verbs are engine-POLLED, so a consumed T press costs nothing while armed. */
    static final int KEY_T = 20;

    static final String ARMED_TEXT = "armed - press T with the cursor over the map (TAB) or the"
            + " field; /teleport again to disarm";
    static final String DISARMED_TEXT = "off";
    static final String NO_CURSOR_TEXT = "no cursor position (no engine or viewport)";

    private boolean armed;
    private int sent;
    /** The debug-watch pre-arm has fired (one-shot; a later disarm sticks). */
    private boolean debugPreArmed;

    /** Armed? Settles the debug-watch pre-arm FIRST (one live volatile read of WELCOME bit1 + the
     * seat's admin bit), so an ADMIN seat of a DEBUG WATCH reads armed from its first key event
     * with no chat verb typed. */
    boolean isArmed() {
        if (preArmForDebugWatch(MatchConfigFromWire.clientDebug() && MatchConfigFromWire.seatAdmin())) {
            PilotWatchLog.info("predict teleport pre-armed (debug watch): " + ARMED_TEXT);
        }
        return armed;
    }

    /** Flip the arm; returns the local HUD line describing the new state. The pre-arm settles
     * first, so on a debug watch the owner's first bare {@code /teleport} DISARMS (never a phantom
     * flip back off when the key handler settles the pre-arm a frame later). */
    String toggle() {
        preArmForDebugWatch(MatchConfigFromWire.clientDebug() && MatchConfigFromWire.seatAdmin());
        armed = !armed;
        return armed ? ARMED_TEXT : DISARMED_TEXT;
    }

    /**
     * The SECOND latch.
     * The
     * server verb gate and the {@link ChatLane} toggle intercept were both open on a debug watch,
     * but {@link #armed} itself started false and only the chat verb flipped it. This pure seam
     * latches the arm ON exactly once when {@code debugWatch} reads true - the callers pass
     * {@code clientDebug() && seatAdmin()}, since the verb is admin-only in every match;
     * a later {@link #toggle} disarm sticks (never re-armed behind the player's
     * back); on a non-debug match, or for a non-admin seat, it is a no-op and the arm stays the
     * chat verb's. Game-free — {@code TeleportPreArmSmoke} drives it from the build loop.
     *
     * @return true on the one call that fired the pre-arm (the caller logs once)
     */
    boolean preArmForDebugWatch(boolean debugWatch) {
        if (debugPreArmed || !debugWatch) {
            return false;
        }
        debugPreArmed = true;
        armed = true;
        return true;
    }

    /**
     * The mouse cursor's world position as the server grammar's {@code @x,y} token (the
     * {@code ChatLane.withSpawnFoeCursorPose} shape: {@code %.1f}, {@code Locale.ROOT}, inside
     * the ≤ 6-integer-digit law), or null when unavailable. Logs the transform used.
     */
    String cursorPoseOrNull(CombatEngineAPI engine) {
        if (engine == null) {
            return null;
        }
        try {
            float mx = Mouse.getX();
            float my = Mouse.getY();
            String source = "map";
            Vector2f world = mapCursorWorldOrNull(engine, mx, my);
            if (world == null) {
                ViewportAPI vp = engine.getViewport();
                if (vp == null) {
                    return null;
                }
                world = new Vector2f(vp.convertScreenXToWorldX(mx), vp.convertScreenYToWorldY(my));
                source = "view";
            }
            if (!Float.isFinite(world.x) || !Float.isFinite(world.y)
                    || Math.abs(world.x) >= 999999f || Math.abs(world.y) >= 999999f) {
                return null;
            }
            String pose = String.format(java.util.Locale.ROOT, "@%.1f,%.1f", world.x, world.y);
            sent++;
            PilotWatchLog.info("predict teleport #" + sent + " cursor " + source + " screen="
                    + (int) mx + "," + (int) my + " pose=" + pose);
            return pose;
        } catch (Throwable t) {
            PilotWatchLog.info("predict teleport: cursor pose FAILED (" + t + ")");
            return null;
        }
    }

    /**
     * The tactical-map inverse (javadoc above), or null when the map is not showing / the
     * warroom chain is unavailable. Isolated so a {@code NoSuchMethodError}/{@code
     * ClassCastException} from an engine drift lands in the caller's fail-soft catch.
     */
    private static Vector2f mapCursorWorldOrNull(CombatEngineAPI engine, float mx, float my) {
        CombatUIAPI ui = engine.getCombatUI();
        if (ui == null || !ui.isShowingCommandUI()
                || !(ui instanceof com.fs.starfarer.combat.CombatState)) {
            return null;
        }
        var warroom = ((com.fs.starfarer.combat.CombatState) ui).getWarroom();
        if (warroom == null) {
            return null;
        }
        var map = warroom.getMapDisplay();
        if (map == null) {
            return null;
        }
        // The map-screen → world inverse — the name via the Unicode escape
        // Ó (O with acute) so the identifier survives any javac file encoding.
        return map.Ó00000(mx, my);
    }
}
