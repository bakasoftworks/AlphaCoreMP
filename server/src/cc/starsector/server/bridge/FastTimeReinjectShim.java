package cc.starsector.server.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * The fast-time reinject shim: the {@link InvocationHandler}
 * behind a {@code java.lang.reflect.Proxy} implementing the game's {@code ShipAIPlugin}
 * (interface class resolved from {@code Ship.setShipAI}'s parameter type — no name guessing, no
 * api-jar compile dependency; server-side reflection is allowed, the no-reflect rule is
 * CLIENT-mod-only).
 *
 * <p><b>The law it exists for:</b> a phased hull runs extra fast-time sim
 * passes ({@code timeMult}≈3), and per extra pass the engine re-supplies controls only via (a)
 * {@code ai.advance} when AI≠null or (b) playerShip
 * capture (with a FILTER) and reinject.
 * Fly-in seat hulls are never playerShip, so without this shim every phased seat starves at
 * pass-1-only control coverage (~1/3 ship-sim). {@code advance(float)} therefore re-issues the
 * ship's recorded pass-1 command set — {@link EngineCombatBinding#fastTimeReinject} — ONLY while
 * the engine {@code isInFastTimeAdvance()} (TRUE exactly during passes 2+):
 * pass 1 stays {@code applyPilotIntent}'s, passes 2+ are
 * the shim's — the engine's own single-issuer split. The reinject set is a strict conservative
 * SUBSET of the engine's own capture filter (the engine also captures
 * SELECT_GROUP, TOGGLE_AUTOFIRE, USE_SELECTED_GROUP, raise-flux and turn; subset is
 * the SAFE direction): movement ordinals, the fire handle with aim (USE_SELECTED_GROUP on the real
 * engine — one of the engine's own captured classes, so a {@code noFiring}
 * system lock refuses the reinject as it refuses pass 1), VENT, USE_SYSTEM;
 * NEVER {@code TOGGLE_SHIELD_OR_PHASE_CLOAK} / hold-fire / pull-back-fighters (toggle-strobe
 * protection — this record cannot even express them).
 *
 * <p><b>Every other interface method is a primitive-default stub</b> by
 * {@link Method#getReturnType()} (false/0/null/void), with {@code equals}/{@code hashCode}/
 * {@code toString} handled by proxy identity — and ONE load-bearing exception:
 *
 * <p><b>{@code getAIFlags()} must NOT return null.</b>
 * {@code Ship.getAIFlags()} delegates to an engine AI helper:
 * with a {@code ShipAIPlugin}-typed AI installed it returns THE
 * PLUGIN's {@code getAIFlags()} verbatim, while a NULL-AI ship falls through to a synthesized
 * per-ship {@code new ShipwideAIFlags()} cached in engine customData ({@code "nonAI_SAIF_"+id}).
 * {@code Ship.advance}'s peak-CR tracker dereferences {@code this.getAIFlags().hasFlag(...)}
 * UNGUARDED on an interval that elapses within seconds on any
 * frigate — a null-returning stub would NPE the engine advance on every shim hull. The shim
 * therefore synthesizes exactly what the engine's own null-AI fallback synthesizes: a lazily
 * constructed, cached no-arg instance of the method's declared return type
 * ({@code ShipwideAIFlags} has a public no-arg ctor — the engine's own idiom). Any
 * synthesis failure falls back to null (fail-soft; the install log is the signal).
 *
 * <p>Sim-thread-only, like the rest of the binding's apply surface. The record is overwritten by
 * {@code applyPilotIntent} each tick the seat applies a frame and cleared at the top of every
 * serve tick ({@link EngineCombatBinding#beginReinjectTick}), so a seat with no applied frame
 * this tick reinjects nothing — exactly what its pass 1 issued.
 */
final class FastTimeReinjectShim implements InvocationHandler {

    private final EngineCombatBinding binding;
    private final Object ship;

    // The recorded pass-1 command set (the engine-filter classes only; see class javadoc).
    private boolean hasRecord;
    private int movementBitsMask;
    private boolean systemPressed;
    private boolean ventPressed;
    private boolean firePressed;
    private float aimWorldX;
    private float aimWorldY;

    // Lazily synthesized getAIFlags() instance (the engine's own nonAI_SAIF_ fallback shape).
    private Object aiFlagsStub;
    private boolean aiFlagsStubFailed;

    FastTimeReinjectShim(EngineCombatBinding binding, Object ship) {
        this.binding = binding;
        this.ship = ship;
    }

    /** Overwrite the recorded pass-1 command set (called once per applied tick, pre-advance). */
    void record(int movementBitsMask, boolean systemPressed, boolean ventPressed,
            boolean firePressed, float aimWorldX, float aimWorldY) {
        this.hasRecord = true;
        this.movementBitsMask = movementBitsMask;
        this.systemPressed = systemPressed;
        this.ventPressed = ventPressed;
        this.firePressed = firePressed;
        this.aimWorldX = aimWorldX;
        this.aimWorldY = aimWorldY;
    }

    /** Clear the record (top of every serve tick — no applied frame means no reinject). */
    void clear() {
        this.hasRecord = false;
        this.movementBitsMask = 0;
        this.systemPressed = false;
        this.ventPressed = false;
        this.firePressed = false;
    }

    boolean hasRecord() {
        return hasRecord;
    }

    int movementBitsMask() {
        return movementBitsMask;
    }

    boolean systemPressed() {
        return systemPressed;
    }

    boolean ventPressed() {
        return ventPressed;
    }

    boolean firePressed() {
        return firePressed;
    }

    float aimWorldX() {
        return aimWorldX;
    }

    float aimWorldY() {
        return aimWorldY;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        int params = method.getParameterCount();
        if ("advance".equals(name) && params == 1) {
            // The engine calls this INSIDE its advance, once per fast-time pass
            // per shim hull — the only project code inside the adv= bracket, so it gets its own
            // per-tick nanos (ftreinj=) to be ruled in or out of an engine-internal spike.
            long startNanos = System.nanoTime();
            binding.fastTimeReinject(ship, this);
            binding.noteReinjectNanos(System.nanoTime() - startNanos);
            return null;
        }
        if ("equals".equals(name) && params == 1) {
            return proxy == args[0];
        }
        if ("hashCode".equals(name) && params == 0) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(name) && params == 0) {
            return "FastTimeReinjectShim@" + Integer.toHexString(System.identityHashCode(proxy));
        }
        if ("getAIFlags".equals(name) && params == 0 && !method.getReturnType().isPrimitive()) {
            return aiFlagsStub(method.getReturnType());
        }
        return primitiveDefault(method.getReturnType());
    }

    /** The engine's own null-AI fallback shape: a cached {@code new ShipwideAIFlags()} (see class javadoc). */
    private Object aiFlagsStub(Class<?> returnType) {
        if (aiFlagsStub == null && !aiFlagsStubFailed) {
            try {
                aiFlagsStub = returnType.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                aiFlagsStubFailed = true; // fail-soft: null return; the peak-CR NPE risk is logged by use
            }
        }
        return aiFlagsStub;
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        return 0; // int
    }
}
