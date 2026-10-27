package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import cc.starsector.server.runtime.SimTickRate;

/**
 * The headless FLARE GLOW census: what the server ENGINE's own engine glow
 * does on every live decoy flare, EVERY tick, rolled up to one row per sim second. It exists to
 * settle the second-pose-bit question by measurement before any wire bit is spent on it: the design minted a second
 * pose bit from the glow's visual flag on the theory that an unforced flare's accelerate-show is
 * refused (so the COMMAND flag over-lights the replica). A first-hand re-read found no refusal
 * source — the only decelerate-show callers are the strafe / reverse / decelerate command handlers,
 * none of which {@code SeekerFlareAI}'s
 * no-target branch issues, and the glow's lock-out timer starts OPEN
 * — so the theory predicts {@code glow == cmd}. This probe reads both, plus the flame
 * LEVEL that actually drives the rendered length (dart only past 0.8).
 *
 * <p>Per flare per tick: {@code cmd} = {@code getEngineController().isAccelerating()} (the ACCELERATE
 * command flag — today's {@code POSE_FLAG_ACCELERATING} source); {@code glow} = the engine glow's
 * public boolean getter of its "level rising" flag; {@code level}
 * = the first engine slot's glow level (slot state, public field — a get-or-create getter
 * whose entry the engine's own advance has already made this frame, so the read writes nothing);
 * {@code dec} = any non-ACCELERATE thrust flag (reverse / decelerate / strafe — each one a
 * decelerate-show caller). Buckets are keyed {@code <specId>/<branch>} (the AI's class name rides the
 * key's first row as {@code ai=}); branch:
 * {@code T} = the AI reports a target ({@code getTarget() != null}), {@code N} =
 * no target, {@code F} = an AI with no {@code getTarget} (plain FlareAI), {@code B} = the first 1.0 s
 * of flight (SeekerFlareAI's dark 0.5-1.0 s birth window), {@code Z} = fizzling.
 *
 * <p>Armed ONLY by {@code -Dcc.probe.flareGlow=true} (or env {@code CC_PROBE_FLARE_GLOW=true};
 * registered in {@code PostureRegistry}). OFF = one static boolean test per tick, zero
 * engine reads. Armed = a per-tick walk of the missile list on the sim thread — an INSTRUMENT for
 * headless rigs, never a product posture. Fail-soft: any reflective failure disarms the
 * probe for the session and logs once; it never mutates engine state. Engine-internal member names are built
 * from char codes (never a typed escape) and are guarded by the engine-jar pin. Same static
 * {@code sample(List, long)} shape and call site as {@link MoteCensusProbe}.
 */
public final class FlareGlowProbe {
    public static final String PROP = "cc.probe.flareGlow";
    public static final String ENV = "CC_PROBE_FLARE_GLOW";
    public static final boolean ARMED = resolveArmed(System.getProperty(PROP), System.getenv(ENV));
    static final int ROW_CAP = 1200;
    /** The glow level past which the rendered flame grows beyond the idle dot. */
    static final float HOT_LEVEL = 0.8f;
    /** Engine-internal name shared by the glow-flag getter and the slot-state getter: capital O-acute followed by five zeros. */
    private static final String OBF_O_ACUTE = (char) 0xD3 + "00000";
    private static final char[] BRANCHES = {'B', 'N', 'T', 'F', 'Z'};

    private static final CappedProbeSink SINK = new CappedProbeSink("flare-glow-probe", PROP, ROW_CAP);
    private static int rowEveryTicks; // 0 = not yet resolved off SimTickRate (first armed tick)
    private static Api api;
    /** One second's buckets, keyed {@code <specId>/<branch>} (no spaces — a row reader splits on them). */
    private static final Map<String, Bucket> BUCKETS = new TreeMap<>();
    /** Bucket keys whose AI class already rode a row ({@code ai=<SimpleName>} prints once per key). */
    private static final Set<String> AI_NAMED = new HashSet<>();
    private static int flaresPeak;

    private FlareGlowProbe() {
    }

    /** The shared arm law ({@link CappedProbeSink#resolveArmed}): {@code true} (either tier) only. */
    public static boolean resolveArmed(String sysprop, String env) {
        return CappedProbeSink.resolveArmed(sysprop, env);
    }

    /** One second's roll-up for one spec + AI branch. */
    private static final class Bucket {
        int samples, cmd, glow, cmdNoGlow, glowNoCmd, hot, dec;
        double levelSum;
        float levelMax;
        String aiName = "";

        void add(boolean c, boolean g, boolean d, float level) {
            samples++;
            if (d) {
                dec++;
            }
            if (c) {
                cmd++;
            }
            if (g) {
                glow++;
            }
            if (c && !g) {
                cmdNoGlow++;
            }
            if (g && !c) {
                glowNoCmd++;
            }
            if (level > HOT_LEVEL) {
                hot++;
            }
            levelSum += level;
            levelMax = Math.max(levelMax, level);
        }

    }

    /** Public API handles bound off the first ship's loader; the engine-internal glow handles bind on the first flare. */
    private static final class Api {
        final Method globalGetCombatEngine, engineGetMissiles;
        final Method missileIsFlare, missileIsFizzling, missileGetFlightTime, missileGetEngineController;
        final Method missileGetMissileAI, missileGetProjectileSpecId, controllerIsAccelerating;
        /** The controller's non-ACCELERATE thrust flags — every one of them is a decelerate-show caller. */
        final Method[] controllerDecelFlags;
        Method controllerGetEngineGlow, entityGetEngineLocations, glowFlag, glowSlotState;
        Field slotLevel;

        Api(Object ship) throws Exception {
            ClassLoader loader = ship.getClass().getClassLoader();
            Class<?> global = Class.forName("com.fs.starfarer.api.Global", false, loader);
            Class<?> engineApi = Class.forName("com.fs.starfarer.api.combat.CombatEngineAPI", false, loader);
            Class<?> missileApi = Class.forName("com.fs.starfarer.api.combat.MissileAPI", false, loader);
            Class<?> controllerApi =
                    Class.forName("com.fs.starfarer.api.combat.ShipEngineControllerAPI", false, loader);
            globalGetCombatEngine = global.getMethod("getCombatEngine");
            engineGetMissiles = engineApi.getMethod("getMissiles");
            missileIsFlare = missileApi.getMethod("isFlare");
            missileIsFizzling = missileApi.getMethod("isFizzling");
            missileGetFlightTime = missileApi.getMethod("getFlightTime");
            missileGetEngineController = missileApi.getMethod("getEngineController");
            missileGetMissileAI = missileApi.getMethod("getMissileAI");
            missileGetProjectileSpecId = missileApi.getMethod("getProjectileSpecId");
            controllerIsAccelerating = controllerApi.getMethod("isAccelerating");
            controllerDecelFlags = new Method[] {
                controllerApi.getMethod("isAcceleratingBackwards"), controllerApi.getMethod("isDecelerating"),
                controllerApi.getMethod("isStrafingLeft"), controllerApi.getMethod("isStrafingRight")};
        }

        /** The engine-internal half, resolved off the first live flare (concrete controller + glow classes). */
        void bindGlow(Object missile, Object controller) throws Exception {
            controllerGetEngineGlow = controller.getClass().getMethod("getEngineGlow");
            entityGetEngineLocations = missile.getClass().getMethod("getEngineLocations");
            Class<?> glowClass = controllerGetEngineGlow.getReturnType();
            for (Method m : glowClass.getMethods()) {
                if (!OBF_O_ACUTE.equals(m.getName())) {
                    continue;
                }
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    glowFlag = m;
                } else if (m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive()) {
                    glowSlotState = m;
                }
            }
            if (glowFlag == null || glowSlotState == null) {
                throw new NoSuchMethodException("engine glow getters not found on " + glowClass.getName());
            }
            slotLevel = glowSlotState.getReturnType().getField("class");
        }
    }

    /** Every tick while armed: fold each live flare into its branch bucket; one row per sim second. */
    static void sample(List<?> ships, long simTick) {
        if (!ARMED || SINK.closed() || ships == null || ships.isEmpty()) {
            return;
        }
        try {
            if (rowEveryTicks == 0) {
                rowEveryTicks = Math.max(1, SimTickRate.ticksPerSecond());
            }
            if (api == null) {
                api = new Api(ships.get(0));
            }
            if (!SINK.armedLogged()) {
                SINK.logArmed(" rowEvery=" + rowEveryTicks + " hotLevel=" + HOT_LEVEL);
            }
            Object engine = api.globalGetCombatEngine.invoke(null);
            if (engine != null) {
                fold(new ArrayList<Object>((List<?>) api.engineGetMissiles.invoke(engine)));
            }
            if (simTick % rowEveryTicks == 0L) {
                flush(simTick);
            }
        } catch (Throwable t) {
            SINK.disarm(t);
        }
    }

    private static void fold(List<Object> missiles) throws Exception {
        int flares = 0;
        for (Object missile : missiles) {
            if (!((Boolean) api.missileIsFlare.invoke(missile)).booleanValue()) {
                continue;
            }
            Object controller = api.missileGetEngineController.invoke(missile);
            if (controller == null) {
                continue;
            }
            if (api.glowFlag == null) {
                api.bindGlow(missile, controller);
            }
            Object glow = api.controllerGetEngineGlow.invoke(controller);
            List<?> slots = (List<?>) api.entityGetEngineLocations.invoke(missile);
            if (glow == null || slots == null || slots.isEmpty()) {
                continue;
            }
            flares++;
            boolean cmd = ((Boolean) api.controllerIsAccelerating.invoke(controller)).booleanValue();
            boolean lit = ((Boolean) api.glowFlag.invoke(glow)).booleanValue();
            float level = api.slotLevel.getFloat(api.glowSlotState.invoke(glow, slots.get(0)));
            boolean dec = false;
            for (Method flag : api.controllerDecelFlags) {
                dec |= ((Boolean) flag.invoke(controller)).booleanValue();
            }
            Object ai = null;
            try {
                ai = api.missileGetMissileAI.invoke(missile);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // the cast in Missile.getMissileAI rejected the AI: the branch read lands in F
            }
            String key = String.valueOf(api.missileGetProjectileSpecId.invoke(missile)).replace(' ', '_')
                    + '/' + BRANCHES[branchOf(missile, ai)];
            Bucket b = BUCKETS.get(key);
            if (b == null) {
                b = new Bucket();
                BUCKETS.put(key, b);
            }
            if (ai != null && b.aiName.isEmpty() && !AI_NAMED.contains(key)) {
                b.aiName = ai.getClass().getSimpleName();
            }
            b.add(cmd, lit, dec, level);
        }
        flaresPeak = Math.max(flaresPeak, flares);
    }

    /** Index into {@link #BRANCHES}. */
    private static int branchOf(Object missile, Object ai) throws Exception {
        if (((Boolean) api.missileIsFizzling.invoke(missile)).booleanValue()) {
            return 4;
        }
        if (((Number) api.missileGetFlightTime.invoke(missile)).floatValue() < 1.0f) {
            return 0;
        }
        // Branch read is best-effort: an AI without a public getTarget (plain FlareAI), or one the
        // cast in Missile.getMissileAI rejected, lands in F instead of disarming the census.
        if (ai == null) {
            return 3;
        }
        try {
            return ai.getClass().getMethod("getTarget").invoke(ai) != null ? 2 : 1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 3;
        }
    }

    private static void flush(long simTick) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Bucket> e : BUCKETS.entrySet()) {
            Bucket b = e.getValue();
            total += b.samples;
            sb.append(String.format(Locale.ROOT,
                    " %s{n=%d cmd=%d glow=%d cmdNoGlow=%d glowNoCmd=%d dec=%d hot=%d lvlMean=%.2f lvlMax=%.2f%s}",
                    e.getKey(), b.samples, b.cmd, b.glow, b.cmdNoGlow, b.glowNoCmd, b.dec, b.hot,
                    b.levelSum / b.samples, b.levelMax, b.aiName.isEmpty() ? "" : " ai=" + b.aiName));
            if (!b.aiName.isEmpty()) {
                AI_NAMED.add(e.getKey());
            }
        }
        BUCKETS.clear();
        if (total > 0) {
            SINK.row("flare-glow tick=" + simTick + " flaresPeak=" + flaresPeak + " samples=" + total + sb);
        }
        flaresPeak = 0;
    }
}
