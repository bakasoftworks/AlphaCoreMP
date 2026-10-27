package cc.starsector.server.bridge;

import java.lang.reflect.Field;

/**
 * Reads the engine {@code Ship}'s private {@code wasDestroyed} flag off a
 * RETAINED piece ref at the fracture detector's vanish observation — the wire discriminator
 * between {@code EDGE_KIND_PIECE_REMOVED_DESTROYED} (the engine VAPORIZED the piece:
 * the ≤75-collision-radius overkill branch — the ONLY
 * {@code wasDestroyed = true} write site in the engine) and plain
 * {@code EDGE_KIND_PIECE_REMOVED} (everything else — off-camera hulk fade leaves the flag
 * {@code false}: the fade path into {@code isExpired()} never
 * touches it).
 *
 * <p>The read MUST happen at vanish time on the retained ref, never stamped onto sightings:
 * the engine sets the flag and removes the piece inside the same damage application, so the
 * piece's LAST sighting always precedes the write. Fail-soft {@code false} on any failure
 * (kind 2 = today's silent presentation — the safe direction). Plain
 * {@code java.lang.reflect} like every server-side engine read (the loader-B MethodHandles
 * constraint is client-mod-only); hierarchy-walking {@code getDeclaredField} with a per-class
 * cache (every piece ref is the same concrete engine class in practice).
 */
public final class HulkDestroyedFlagReader {

    private static volatile Class<?> cachedClass;
    private static volatile Field cachedField;

    private HulkDestroyedFlagReader() {}

    /** {@code true} iff {@code ref}'s {@code wasDestroyed} reads {@code true}; fail-soft
     * {@code false} on null, a missing field, or any reflective failure. */
    public static boolean wasDestroyed(Object ref) {
        if (ref == null) {
            return false;
        }
        try {
            Class<?> type = ref.getClass();
            Field field = cachedField;
            if (field == null || cachedClass != type) {
                field = findField(type);
                if (field == null) {
                    return false;
                }
                cachedField = field;
                cachedClass = type;
            }
            return field.getBoolean(ref);
        } catch (Throwable ignore) {
            return false; // kind 2 (quiet) is the safe failure direction
        }
    }

    private static Field findField(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("wasDestroyed");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException walkUp) {
                // keep walking the hierarchy
            } catch (Throwable ignore) {
                return null;
            }
        }
        return null;
    }
}
