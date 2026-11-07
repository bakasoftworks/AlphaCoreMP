package cc.starsector.server.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * One hot engine getter read through a typed {@link MethodHandle}. The capture walks call a few
 * dozen getters per ship or per mount every tick; {@link Method#invoke} pays a varargs array, a box,
 * the caller's {@code Number} check and a megamorphic accessor hop on each call, while
 * {@code invokeExact} on the handle's exact erased signature returns the primitive directly. A box
 * round trip never changes a float's bits, so every view and every wire byte is the same either way.
 *
 * <p>Availability never drops below the Method path: the wrapper keeps its {@link Method}, and a
 * getter whose conversion throws, or whose bind-time self-check does not end in the expected
 * null-receiver {@link NullPointerException}, reads through {@code Method.invoke} exactly as the
 * binding always did, with the same exception texts (one {@code UNCONVERTED} line names the site).
 * Every throwable out of a handle is re-wrapped as {@link InvocationTargetException}, as
 * {@code Method.invoke} wraps a throw from its target, so each caller's
 * {@code ReflectiveOperationException | RuntimeException} arm keeps its meaning: a failing required
 * read still nulls its whole tier for that tick, an optional read still degrades one scalar.
 *
 * <p>The JDK links each {@code invokeExact} call site on its first execution and customizes a handle
 * on its 128th call (each a one-time class spin); {@link #warm} pays both at boot, before any served
 * tick reads through the handles. Every typed read below holds ONE {@code invokeExact} site per
 * signature, so the warm links every site the walks use.
 *
 */
final class TypedGetter {

    /** The exact erased signature a getter is invoked on (the receiver erased to Object). */
    enum Sig {
        FLOAT(MethodType.methodType(float.class, Object.class)),
        INT(MethodType.methodType(int.class, Object.class)),
        BOOLEAN(MethodType.methodType(boolean.class, Object.class)),
        OBJECT(MethodType.methodType(Object.class, Object.class)),
        FLOAT_OF_FLOAT(MethodType.methodType(float.class, Object.class, float.class)),
        OBJECT_OF_OBJECT(MethodType.methodType(Object.class, Object.class, Object.class));

        final MethodType type;

        Sig(MethodType type) {
            this.type = type;
        }
    }

    /** The engine object a getter is called on: the boot warm finds one live receiver per role. */
    enum Role { SHIP, ARMOR_GRID, HULL_SPEC, ENGINE_CONTROLLER, ENGINE, STATS, STAT_BONUS, WEAPON, WEAPON_GROUP }

    /** Calls per converted getter in the boot warm: the JDK customizes a handle on its 128th call. */
    static final int CUSTOMIZE_CALLS = 128;

    final Method method;
    /** The converted handle, typed {@link Sig#type}; null = this site reads through {@link #method}. */
    final MethodHandle handle;
    /** The site name; on the Method path it also heads the exception text, exactly as before. */
    final String label;
    final Sig sig;
    final Role role;
    /** The second argument's role ({@link Sig#OBJECT_OF_OBJECT} only), else null. */
    final Role argRole;

    private TypedGetter(Method method, MethodHandle handle, String label, Sig sig, Role role, Role argRole) {
        this.method = method;
        this.handle = handle;
        this.label = label;
        this.sig = sig;
        this.role = role;
        this.argRole = argRole;
    }

    /** {@link #of(Method, String, Sig, Role, Role, List)} for a getter without an object argument. */
    static TypedGetter of(Method m, String label, Sig sig, Role role, List<TypedGetter> sites) {
        return of(m, label, sig, role, null, sites);
    }

    /**
     * The site's wrapper, appended to {@code sites} (the census order): null exactly when {@code m}
     * is null (the site is unbound, as before), else converted or on the Method path.
     */
    static TypedGetter of(Method m, String label, Sig sig, Role role, Role argRole, List<TypedGetter> sites) {
        TypedGetter g = m == null ? null : convert(m, label, sig, role, argRole);
        sites.add(g);
        return g;
    }

    /**
     * Converts {@code m} to {@code sig}: {@code asType} accepts only lossless adaptations (a
     * {@code double} or {@code long} return is refused), and a refusal or any other throwable short
     * of a {@link VirtualMachineError} leaves the site on its Method path.
     */
    static TypedGetter convert(Method m, String label, Sig sig, Role role, Role argRole) {
        MethodHandle h;
        try {
            h = MethodHandles.lookup().unreflect(m).asType(sig.type);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable refused) {
            return unconverted(m, label, sig, role, argRole, refused.getClass().getSimpleName());
        }
        return checked(m, h, label, sig, role, argRole);
    }

    /**
     * Keeps {@code h} only when the self-check passes: one call of the site's own typed read with a
     * null receiver must end in an {@link InvocationTargetException} caused by
     * {@link NullPointerException} (the call shape links). A {@code WrongMethodTypeException}, or any
     * other outcome, is drift: the site falls back to its Method. The call also counts toward the
     * handle's customization.
     */
    static TypedGetter checked(Method m, MethodHandle h, String label, Sig sig, Role role, Role argRole) {
        TypedGetter g = new TypedGetter(m, h, label, sig, role, argRole);
        String drift = g.selfCheckDrift();
        return drift == null ? g : unconverted(m, label, sig, role, argRole, drift);
    }

    private static TypedGetter unconverted(Method m, String label, Sig sig, Role role, Role argRole, String why) {
        System.out.println("engine-combat-binding typed-getter UNCONVERTED site=" + label + " want=" + sig.type
                + " why=" + why + " (the site reads through its Method)");
        return new TypedGetter(m, null, label, sig, role, argRole);
    }

    /** Null when the null-receiver call ends in the expected NullPointerException, else the outcome's name. */
    private String selfCheckDrift() {
        try {
            callBoxed(null, null);
            return "no-throw";
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof NullPointerException) {
                return null;
            }
            return cause == null ? "null-cause" : cause.getClass().getSimpleName();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable other) {
            return other.getClass().getSimpleName();
        }
    }

    float getFloat(Object o) throws ReflectiveOperationException {
        if (handle == null) {
            return number(method.invoke(o)).floatValue();
        }
        try {
            return (float) handle.invokeExact(o);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    float getFloat(Object o, float a) throws ReflectiveOperationException {
        if (handle == null) {
            return number(method.invoke(o, a)).floatValue();
        }
        try {
            return (float) handle.invokeExact(o, a);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    /** {@link #getFloat(Object)} with the non-finite refusal the required float reads always had. */
    float finiteFloat(Object o) throws ReflectiveOperationException {
        return finite(getFloat(o));
    }

    float finiteFloat(Object o, float a) throws ReflectiveOperationException {
        return finite(getFloat(o, a));
    }

    int getInt(Object o) throws ReflectiveOperationException {
        if (handle == null) {
            return number(method.invoke(o)).intValue();
        }
        try {
            return (int) handle.invokeExact(o);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    /** A required boolean: on the Method path a non-Boolean value throws (its tier reads null, as before). */
    boolean getBoolean(Object o) throws ReflectiveOperationException {
        if (handle == null) {
            Object value = method.invoke(o);
            if (value instanceof Boolean b) {
                return b;
            }
            throw new EngineCombatBinding.EngineBindingException(
                    label + " did not return a Boolean; got " + typeName(value), null);
        }
        return invokeBoolean(o);
    }

    /** A lenient boolean: on the Method path anything but {@code Boolean.TRUE} reads false, as before. */
    boolean isTrue(Object o) throws ReflectiveOperationException {
        return handle == null ? Boolean.TRUE.equals(method.invoke(o)) : invokeBoolean(o);
    }

    private boolean invokeBoolean(Object o) throws InvocationTargetException {
        try {
            return (boolean) handle.invokeExact(o);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    Object getObject(Object o) throws ReflectiveOperationException {
        if (handle == null) {
            return method.invoke(o);
        }
        try {
            return (Object) handle.invokeExact(o);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    Object getObject(Object o, Object a) throws ReflectiveOperationException {
        if (handle == null) {
            return method.invoke(o, a);
        }
        try {
            return (Object) handle.invokeExact(o, a);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    /**
     * The site's own typed read, boxed: the self-check, the boot warm and the rows drive the same
     * call sites the walks use. A {@link Sig#FLOAT_OF_FLOAT} argument that is not a Float reads 0.
     */
    Object callBoxed(Object o, Object a) throws ReflectiveOperationException {
        return switch (sig) {
            case FLOAT -> getFloat(o);
            case INT -> getInt(o);
            case BOOLEAN -> getBoolean(o);
            case OBJECT -> getObject(o);
            case FLOAT_OF_FLOAT -> getFloat(o, a instanceof Float f ? f : 0f);
            case OBJECT_OF_OBJECT -> getObject(o, a);
        };
    }

    private Number number(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        throw new EngineCombatBinding.EngineBindingException(
                label + " did not return a Number; got " + typeName(value), null);
    }

    private float finite(float f) {
        if (!Float.isFinite(f)) {
            throw new EngineCombatBinding.EngineBindingException(label + " returned non-finite value: " + f, null);
        }
        return f;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /**
     * The bind census: {@code converted=<n>/<sites> unbound=<u> unconverted=<k>}, n + u + k = sites;
     * a healthy real-engine bind reads every site converted.
     */
    static String censusLine(List<TypedGetter> sites) {
        int converted = 0;
        int unbound = 0;
        for (TypedGetter g : sites) {
            if (g == null) {
                unbound++;
            } else if (g.handle != null) {
                converted++;
            }
        }
        return "engine-combat-binding typed-getters converted=" + converted + "/" + sites.size()
                + " unbound=" + unbound + " unconverted=" + (sites.size() - converted - unbound);
    }

    /**
     * The boot warm: drives every converted getter {@code calls} times through its own typed read on
     * the live receiver of its role, so the call-site linkage and the handle's customization spin
     * land before any served tick. Unbound and Method-path sites are skipped (nothing to link). A
     * converted getter whose role (or argument role) has no receiver, or whose read throws, is a
     * miss and never throws out. Returns {@code customized=<n> miss=<m>}, n + m = the converted count.
     */
    static String warm(List<TypedGetter> sites, Object[] receivers, int calls) {
        int customized = 0;
        int miss = 0;
        for (TypedGetter g : sites) {
            if (g == null || g.handle == null) {
                continue;
            }
            Object receiver = receivers[g.role.ordinal()];
            Object arg = g.argRole == null ? null : receivers[g.argRole.ordinal()];
            if (receiver == null || (g.argRole != null && arg == null)) {
                miss++;
                continue;
            }
            try {
                for (int i = 0; i < calls; i++) {
                    g.callBoxed(receiver, arg);
                }
                customized++;
            } catch (ReflectiveOperationException | RuntimeException failed) {
                miss++;
            }
        }
        return "customized=" + customized + " miss=" + miss;
    }

    /** Fills {@code role}'s slot with {@code value} unless the slot is taken or the value is null. */
    static void offer(Object[] receivers, Role role, Object value) {
        if (value != null && receivers[role.ordinal()] == null) {
            receivers[role.ordinal()] = value;
        }
    }

    /** A warm-time receiver read: null for an unbound site, a null receiver or a read that throws (a later miss). */
    static Object readOrNull(TypedGetter g, Object receiver, Object arg) {
        if (g == null || receiver == null) {
            return null;
        }
        try {
            return g.callBoxed(receiver, arg);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
    }

    /** The first non-null element of a List value, else null. */
    static Object firstNonNull(Object listValue) {
        if (listValue instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    return item;
                }
            }
        }
        return null;
    }

    static boolean allFilled(Object[] receivers) {
        for (Object r : receivers) {
            if (r == null) {
                return false;
            }
        }
        return true;
    }
}
