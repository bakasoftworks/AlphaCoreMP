package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restarts the engine's own interval timers at a pinned round's frame 0 — the moment a fresh vanilla
 * battle would have constructed them.
 *
 * <p><b>WHY.</b> Vanilla builds one {@code CombatEngine} per battle, so every interval timer the
 * engine owns starts a battle with {@code elapsed = 0} and a duration rolled at construction. This server
 * builds the engine ONCE at boot and re-uses it: by a round's frame 0 each timer has been advanced by every
 * pre-round frame, and its duration was rolled from the UNPINNED stream before any RNG re-arm. Two
 * re-simulations of one reel therefore re-rolled a timer at different sim ticks (one extra
 * {@code Math.random()} draw in one leg), shifting the whole global stream. The engine declares TWO such
 * timers today: the corner-cheese check's {@code IntervalUtil(0.5f, 0.9f)} (the pins-ON proof's first
 * desync, tick 9 vs 28) and the combat-over check's {@code IntervalTracker(1f, 2f)}
 * (the second, tick 25 / 49 once the first was closed). The restart below is each
 * type's own API — {@code setInterval(min, max)} re-rolls the duration exactly as the constructor does (from
 * the now-pinned stream) and the clock is reset — so a pinned round starts every engine timer the way a
 * vanilla battle does.
 *
 * <p><b>Shape.</b> Timers are found by TYPE NAME among the holder class's declared instance fields (never
 * by a field identifier, which a game update renames). Each timer TYPE is driven through its own API:
 * the bounds come from public {@code getMinInterval()} / {@code getMaxInterval()} when the type has them
 * ({@code IntervalUtil}), else from its {@code minInterval} / {@code maxInterval} fields
 * ({@code IntervalTracker} is a {@code DoNotObfuscate} class, so those field names stay stable across game builds — a
 * game update that renamed them fails the resolve, logged once, never silently); the clock is reset through
 * {@code setElapsed(0)} when the type has it ({@code IntervalUtil}; {@code IntervalTracker.setInterval}
 * zeroes its own clock and elapsed flag). Fields and accessors are resolved once per (holder class, type
 * set) and cached. Reflective failure never propagates into the frame: it is logged once and the count
 * reported as {@code -1}, so a serve keeps running and the proof shows the seam again instead of a dead
 * serve. Called once per round at frame 0, never per tick.
 *
 * <p><b>Resolve at ARM time, never on the sim thread.</b>
 * Before {@link #prelink} existed, the first {@link #restart} — round 0's frame 0, on the sim thread — was also the reflective
 * resolve (a field walk + {@code getMethod} / {@code getDeclaredField} per timer type), the one frame the
 * pacer anchors on. {@link #prelink} runs that resolve on the boot thread beside the RNG re-arm's own
 * handles; a frame-0 restart is then cache hits only. A resolve failure at prelink THROWS (the pin's
 * fail-closed law: a game update that renames a timer API refuses the pinned boot, never a silent
 * {@code timers=-1} round); {@link #resolveCount} lets the contract pin "one resolve at arm, none at frame 0".
 */
public final class EngineIntervalTimers {

    /** The API's interval timer, loaded by the game's loader (matched by name): public bound getters. */
    public static final String INTERVAL_UTIL = "com.fs.starfarer.api.util.IntervalUtil";
    /** The engine-internal interval timer ({@code DoNotObfuscate}): bounds are private fields, no getters. */
    public static final String INTERVAL_TRACKER = "com.fs.starfarer.util.IntervalTracker";
    /** The engine's timer types — every declared engine field of either type is restarted at frame 0. */
    public static final String[] ENGINE_TIMER_TYPES = { INTERVAL_UTIL, INTERVAL_TRACKER };

    /** One timer type's accessors: the re-roll, the bounds (getters OR fields), the optional clock reset. */
    private static final class Accessors {
        final Method setInterval;
        final Method getMin;
        final Method getMax;
        final Field minField;
        final Field maxField;
        final Method setElapsed;

        Accessors(Class<?> type) throws ReflectiveOperationException {
            setInterval = type.getMethod("setInterval", float.class, float.class);
            Method min = publicOrNull(type, "getMinInterval");
            Method max = publicOrNull(type, "getMaxInterval");
            if (min != null && max != null) {
                getMin = min;
                getMax = max;
                minField = null;
                maxField = null;
            } else {
                getMin = null;
                getMax = null;
                minField = type.getDeclaredField("minInterval");
                maxField = type.getDeclaredField("maxInterval");
                minField.setAccessible(true);
                maxField.setAccessible(true);
            }
            setElapsed = publicOrNull(type, "setElapsed", float.class);
        }

        float min(Object timer) throws ReflectiveOperationException {
            return getMin != null ? (Float) getMin.invoke(timer) : minField.getFloat(timer);
        }

        float max(Object timer) throws ReflectiveOperationException {
            return getMax != null ? (Float) getMax.invoke(timer) : maxField.getFloat(timer);
        }

        private static Method publicOrNull(Class<?> type, String name, Class<?>... params) {
            try {
                return type.getMethod(name, params);
            } catch (NoSuchMethodException absent) {
                return null;
            }
        }
    }

    /** One resolved timer field of the holder, with its type's accessors. */
    private static final class Slot {
        final Field field;
        final Accessors api;

        Slot(Field field, Accessors api) {
            this.field = field;
            this.api = api;
        }
    }

    private static Class<?> cachedHolder;
    private static String[] cachedTypes;
    private static Slot[] cachedSlots;
    private static boolean failureLogged;
    private static long resolves;

    private EngineIntervalTimers() {
    }

    /**
     * Boot-thread resolve of {@code holderClass}'s timer slots for {@code timerClassNames}: the
     * same cache {@link #restart} reads, filled at ARM time so round 0's frame 0 never resolves on the sim
     * thread. Returns the number of slots found (null-valued fields included; the restart skips those).
     *
     * @throws ReflectiveOperationException a listed type without the timer API, or an unreadable field —
     *         the caller refuses the pinned boot (fail-closed), never a silent {@code -1} at frame 0
     */
    public static synchronized int prelink(Class<?> holderClass, String... timerClassNames)
            throws ReflectiveOperationException {
        if (holderClass == null) {
            throw new IllegalArgumentException("interval timers: null holder class");
        }
        resolve(holderClass, timerClassNames);
        return cachedSlots.length;
    }

    /** Reflective resolves run so far in this JVM (a cache hit is not one) — the boot row's "none at frame 0" pin. */
    public static synchronized long resolveCount() {
        return resolves;
    }

    /**
     * Restarts every declared instance field of {@code holder} whose type is one of {@code timerClassNames}:
     * duration re-rolled via {@code setInterval(min, max)} with the timer's own bounds, clock reset to 0.
     * A null-valued field is skipped. Returns the number of timers restarted, or {@code -1} on a reflective
     * failure (logged once per JVM) — a field of a listed type that lacks the timer API counts as one.
     */
    public static synchronized int restart(Object holder, String... timerClassNames) {
        if (holder == null) {
            throw new IllegalArgumentException("interval timers: null holder");
        }
        try {
            resolve(holder.getClass(), timerClassNames);
            int restarted = 0;
            for (Slot s : cachedSlots) {
                Object timer = s.field.get(holder);
                if (timer == null) {
                    continue;
                }
                float min = s.api.min(timer);
                float max = s.api.max(timer);
                s.api.setInterval.invoke(timer, min, max);
                if (s.api.setElapsed != null) {
                    s.api.setElapsed.invoke(timer, 0f);
                }
                restarted++;
            }
            return restarted;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                System.out.println("rng_interval_restart FAILED holder=" + holder.getClass().getName() + " err="
                        + e.getClass().getSimpleName());
            }
            return -1;
        }
    }

    private static void resolve(Class<?> holderClass, String[] timerClassNames) throws ReflectiveOperationException {
        if (cachedHolder == holderClass && Arrays.equals(cachedTypes, timerClassNames)) {
            return;
        }
        List<String> wanted = Arrays.asList(timerClassNames);
        Map<Class<?>, Accessors> apiByType = new HashMap<>();
        List<Slot> found = new ArrayList<>();
        for (Field f : holderClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !wanted.contains(f.getType().getName())) {
                continue;
            }
            Accessors api = apiByType.get(f.getType());
            if (api == null) {
                api = new Accessors(f.getType());
                apiByType.put(f.getType(), api);
            }
            f.setAccessible(true);
            found.add(new Slot(f, api));
        }
        cachedSlots = found.toArray(new Slot[0]);
        cachedHolder = holderClass;
        cachedTypes = timerClassNames.clone();
        resolves++;
    }
}
