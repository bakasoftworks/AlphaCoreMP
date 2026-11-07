package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * A public zero-arg getter resolved ONCE per concrete receiver class. The per-tick point-entity
 * reads (projectiles, missiles, beams, their source ships and weapons) meet several distinct
 * engine-internal classes in one list, and an uncached {@link Class#getMethod} per element walks
 * the class hierarchy and returns a FRESH Method copy whose one-caller access cache starts empty,
 * so every invoke also paid the slow access check. This returns the very Method
 * {@code getMethod} finds, resolved once, its access check turned off ({@link OpenHandles}).
 *
 * <p>A class lacking the member caches the miss, and every later call throws a FRESH
 * {@link NoSuchMethodException} carrying the JDK's own message, so a caller's catch sees the same
 * type and text an uncached lookup throws. Errors and runtime exceptions from the lookup are never
 * cached ({@link ClassValue} re-runs {@link #computeValue} after a throw), exactly as an uncached
 * lookup re-throws them on every call.
 *
 * <p>The Methods it returns are opened ({@link OpenHandles}), so no invoke pays an access check and
 * the former one-caller rule (the JDK's per-handle caller cache) no longer binds.
 *
 *
 *
 */
final class PerClassGetter extends ClassValue<Object> {
    private final String name;

    PerClassGetter(String name) {
        this.name = name;
    }

    /** The resolved Method, or the miss's message (a String) when the class has no such getter. */
    @Override
    protected Object computeValue(Class<?> type) {
        try {
            return OpenHandles.open(type.getMethod(name));
        } catch (NoSuchMethodException miss) {
            return miss.getMessage();
        }
    }

    /** The public zero-arg getter of {@code type}; a cached miss re-throws the JDK-shaped exception. */
    Method of(Class<?> type) throws NoSuchMethodException {
        Object resolved = get(type);
        if (resolved instanceof Method method) {
            return method;
        }
        throw new NoSuchMethodException((String) resolved);
    }

    /**
     * The public {@code x}/{@code y} field pair of a vector class (lwjgl {@code Vector2f} on the
     * live engine), resolved once per class for the same reason as the getters: a per-call
     * {@link Class#getField} copy re-ran the slow access check inside {@code getFloat}. A class
     * lacking a field caches the miss and re-throws {@link NoSuchFieldException} naming the FIRST
     * missing field (x is resolved before y), the same type and text the uncached pair threw.
     * Both fields are opened too, so no {@code getFloat} pays an access check.
     *
     */
    static final class XyFields extends ClassValue<Object> {
        @Override
        protected Object computeValue(Class<?> type) {
            try {
                return new Field[] { OpenHandles.open(type.getField("x")), OpenHandles.open(type.getField("y")) };
            } catch (NoSuchFieldException miss) {
                return miss.getMessage();
            }
        }

        /** {@code [x, y]} of {@code type}; a cached miss re-throws the JDK-shaped exception. */
        Field[] of(Class<?> type) throws NoSuchFieldException {
            Object resolved = get(type);
            if (resolved instanceof Field[] pair) {
                return pair;
            }
            throw new NoSuchFieldException((String) resolved);
        }
    }
}
