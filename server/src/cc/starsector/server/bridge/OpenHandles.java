package cc.starsector.server.bridge;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;

/**
 * Pre-resolved reflective handles skip the per-invoke access check. {@code Method.invoke} and
 * {@code Field.getFloat} on a handle whose {@code override} flag is false run {@code checkAccess}
 * on every call; its one-caller cache missed on the ship-tier readers often enough that the
 * rig JFR put 140 of 2,528 sim-thread samples (5.6 % of in-frame time) at
 * {@code AccessibleObject.slowVerifyAccess} — on PUBLIC getters a check that can only answer yes.
 * {@code setAccessible(true)} ONCE per handle sets {@code override}, and the check is skipped
 * entirely. Never gated on {@code canAccess} (the old idiom): that gate left exactly the public
 * handles checked. A public member of a public class in an exported package always accepts
 * {@code setAccessible}; a handle the module system refuses (a {@code java.base} internal) throws
 * {@link InaccessibleObjectException} and is left checked: its invoke then obeys the JDK's own access
 * rule, as before (unreachable here: every engine class sits in the unnamed module). An opened handle
 * invoked on a null receiver throws the accessor's plain NPE, not the helpful one; every reader
 * null-checks its receiver first.
 */
final class OpenHandles {
    private OpenHandles() { }

    /** The handle itself, with the per-invoke access check turned off when the module system allows it. */
    static <T extends AccessibleObject> T open(T handle) {
        if (handle != null) {
            try {
                handle.setAccessible(true);
            } catch (InaccessibleObjectException refused) {
                // a java.base internal: this handle stays checked per invoke, exactly as before
            }
        }
        return handle;
    }

    /** {@link #open} over every Method / Field / Constructor field the holder's own class declares (null-safe). */
    static void openAll(Object holder) {
        for (Field f : holder.getClass().getDeclaredFields()) {
            if (AccessibleObject.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    open((AccessibleObject) f.get(holder));
                } catch (InaccessibleObjectException | IllegalAccessException refused) {
                    // the holder's own field refused a read: that handle stays checked
                }
            }
        }
    }
}
