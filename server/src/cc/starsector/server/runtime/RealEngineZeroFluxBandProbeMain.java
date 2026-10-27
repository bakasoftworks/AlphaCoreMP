package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zero-flux boost headless PROOF: on the live engine at the server's
 * fixed dt, one fresh ENGAGED carrier with the elite zero-flux branch armed
 * ({@code allowZeroFluxAtAnyLevel} +1 = elite Helmsmanship L3), three consecutive windows on the
 * SAME hull — A: pre-snap OFF, B: pre-snap ON, C: pre-snap OFF — sampling
 * {@code ShipAPI.isEngineBoostActive()} and {@code getCurrFlux()} every tick. Expected at 1/60:
 * A ≈ no boost + a positive add every ~3 ticks (the artefact), B = boost within ~1.1 s
 * and ≥95 % thereafter with ZERO adds, C = boost lost again. At 1/540 (control) A already boosts —
 * the vanilla high-fps behavior a player's client shows.
 *
 * <p>Same product boot + step boundary as the serve loop ({@code RealEngineCombatBindingFactory
 * .create()} + {@code EngineBackedCombatStepBoundary.step}); the ONLY lever is the boundary's
 * probe-only {@code withEngagedCarrierBandSnap}. Roster AI stripped (kill-free window, the
 * all-skills-probe idiom); the carrier's own AI stripped so nothing else moves its flux; fighter
 * launch adds (a launch event = {@code increaseFlux}) land in window A by design. A MEASUREMENT
 * main, never wired into the serve. Exit: 0 = ZF_BAND_FIXED; 2 = a spawn/install call threw;
 * 1 = infrastructure or verdict mismatch.
 *
 * <p>Add-source pin ({@code --flux-stack true}, wrapper {@code -FluxStack}): window A only,
 * the trace agent's {@code FluxAddStackCapture} gate is flipped ON (property) so every positive
 * {@code increaseFlux} on THIS hull prints its caller stack + a per-signature census at A's end;
 * plus a {@code bays} row every 100 ticks (per-bay wing members / returning, shield, system, and
 * whether the elite timer was reset that tick) and the tick the per-frame reset phase ends.
 */
public final class RealEngineZeroFluxBandProbeMain {

    // ---- PINNED 0.98a-RC8 identities, byte-identical to RealEngineAllSkillsProbeMain. ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    private static final String P = "zf-band-probe";
    private static final float ADD_DELTA_MIN = 10f;

    private RealEngineZeroFluxBandProbeMain() {
    }

    /** One window's tallies. */
    private static final class Window {
        final String name;
        final boolean snapOn;
        int ticks;
        int boostTicks;
        int lastHalfTicks;
        int lastHalfBoostTicks;
        int adds;
        int addsAfterSettle;
        int firstBoostTick = -1;
        float minCurr = Float.MAX_VALUE;
        float maxCurr = -Float.MAX_VALUE;
        long preSnaps;

        Window(String name, boolean snapOn) {
            this.name = name;
            this.snapOn = snapOn;
        }

        float lastHalfFraction() {
            return lastHalfTicks == 0 ? 0f : lastHalfBoostTicks / (float) lastHalfTicks;
        }
    }

    public static void main(String[] args) throws Exception {
        int preFrames = 120;
        float windowSeconds = 4.0f;
        float fixedDt = SimTickRate.fixedDt();
        String variantId = "astral_Flash";
        boolean fluxStack = false;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--pre-frames": preFrames = Integer.parseInt(args[++i]); break;
                case "--window-seconds": windowSeconds = Float.parseFloat(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--variant": variantId = args[++i]; break;
                case "--flux-stack": fluxStack = Boolean.parseBoolean(args[++i]); break;
                default: break;
            }
        }
        System.out.println(P + "-begin pre_frames=" + preFrames + " window_seconds=" + windowSeconds
                + " fixed_dt=" + fixedDt + " variant=" + variantId + " flux_stack=" + fluxStack);

        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        ClassLoader loader = RealEngineZeroFluxBandProbeMain.class.getClassLoader();
        Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
        Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
        Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
        Class<?> shipStatsApiClass = Class.forName(SHIP_STATS_API, false, loader);
        Class<?> mutableStatClass = Class.forName(MUTABLE_STAT_CLASS, false, loader);
        Object engine = engineClass.getMethod("getInstance").invoke(null);
        if (engine == null) {
            throw new IllegalStateException("CombatEngine.getInstance() null after create()");
        }
        Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
        Object manager1 = getFleetManager.invoke(engine, 1);
        Method getObjects = engineClass.getMethod("getObjects");
        Method repoGetList = getObjects.invoke(engine).getClass().getMethod("getList", Class.class);
        Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
        Method shipGetShipAI = shipClass.getMethod("getShipAI");
        Method shipSetShipAI = findOneArg(shipClass, "setShipAI");
        Method shipIsEngineBoostActive = shipApiClass.getMethod("isEngineBoostActive");
        Method shipGetFluxTracker = shipApiClass.getMethod("getFluxTracker");
        Method shipSetPullBack = shipApiClass.getMethod("setPullBackFighters", boolean.class);
        Method shipIsPullBack = shipApiClass.getMethod("isPullBackFighters");
        Method shipHasLaunchBays = shipApiClass.getMethod("hasLaunchBays");
        Method shipGetMutableStats = shipApiClass.getMethod("getMutableStats");
        Method statsAllowZf = shipStatsApiClass.getMethod("getAllowZeroFluxAtAnyLevel");
        Method statsZfMin = shipStatsApiClass.getMethod("getZeroFluxMinimumFluxLevel");
        Method statsDiss = shipStatsApiClass.getMethod("getFluxDissipation");
        Method statModifyFlat = mutableStatClass.getMethod("modifyFlat", String.class, float.class);
        Method statGetModified = mutableStatClass.getMethod("getModifiedValue");

        long tick = 0;
        step.withEngagedCarrierBandSnap(false);
        for (int f = 0; f < preFrames; f++) {
            step.step(fixedDt, tick++);
        }
        // Kill-free window: strip roster AI (nothing shoots the carrier, nothing launches at it).
        List<?> rosterShips = (List<?>) repoGetList.invoke(getObjects.invoke(engine), shipClass);
        int stripped = 0;
        for (Object s : new ArrayList<Object>(rosterShips)) {
            if (shipGetShipAI.invoke(s) != null) {
                shipSetShipAI.invoke(s, new Object[] {null});
                stripped++;
            }
        }
        System.out.println(P + " roster_ships=" + rosterShips.size() + " ai_stripped=" + stripped);

        // ---- Fresh carrier on side 1 (side 0 would pick up any union commander). ----
        Object variant = Class.forName(VARIANT_REGISTRY_CLASS, true, loader)
                .getMethod(VARIANT_REGISTRY_METHOD, String.class).invoke(null, variantId);
        Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
        if (variant == null || !hullVariantClass.isInstance(variant)) {
            throw new IllegalStateException("variant '" + variantId + "' did not resolve — pass --variant");
        }
        Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
        Class<?> memberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
        Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
        Class<?> vector2fClass = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
        Object member = mintMember(fleetMemberClass, memberTypeClass, hullVariantClass, variant, 1, "CC ZF Band");
        Method spawn1 = manager1.getClass().getMethod("spawnFleetMember",
                fleetMemberApiClass, vector2fClass, float.class, float.class);
        Object ship;
        try {
            ship = spawn1.invoke(manager1, member, null, 180.0f, 4.0f);
        } catch (InvocationTargetException e) {
            bail("SPAWN_THREW", e);
            return;
        }
        step.step(fixedDt, tick++);  // engine's own executeAdds drain
        if (!(Boolean) isShipAlive.invoke(engine, ship)) {
            System.out.println(P + "-end verdict=FAILED reason=spawned carrier not alive");
            System.exit(1);
        }
        shipSetShipAI.invoke(ship, new Object[] {null});
        // First run: AUTOFIRE weapons fired at roster hulls in range — every shot is a positive
        // increaseFlux (weapon deductEnergy) that resets the elite timer, masking the verdict. Hold
        // fire + disable every mount + autofire off per group: the carrier's ONLY flux mover is the band.
        shipApiClass.getMethod("setHoldFire", boolean.class).invoke(ship, true);
        Class<?> weaponApi = Class.forName("com.fs.starfarer.api.combat.WeaponAPI", false, loader);
        Class<?> groupApi = Class.forName("com.fs.starfarer.api.combat.WeaponGroupAPI", false, loader);
        for (Object wp : (List<?>) shipApiClass.getMethod("getAllWeapons").invoke(ship)) {
            weaponApi.getMethod("disable").invoke(wp);
        }
        for (Object g : (List<?>) shipApiClass.getMethod("getWeaponGroupsCopy").invoke(ship)) {
            if ((Boolean) groupApi.getMethod("isAutofiring").invoke(g)) {
                groupApi.getMethod("toggleOff").invoke(g);
            }
        }
        Object stats = shipGetMutableStats.invoke(ship);
        statModifyFlat.invoke(statsAllowZf.invoke(stats), "zf-band-probe", 1.0f); // elite L3 branch
        shipSetPullBack.invoke(ship, false);                                       // ENGAGE
        Object ft = shipGetFluxTracker.invoke(ship);
        Method ftGetCurr = ft.getClass().getMethod("getCurrFlux");
        Method ftGetMax = ft.getClass().getMethod("getMaxFlux");
        float zfl = ((Number) statGetModified.invoke(statsZfMin.invoke(stats))).floatValue();
        float azf = ((Number) statGetModified.invoke(statsAllowZf.invoke(stats))).floatValue();
        float diss = ((Number) statGetModified.invoke(statsDiss.invoke(stats))).floatValue();
        float max = ((Number) ftGetMax.invoke(ft)).floatValue();
        System.out.println(String.format(Locale.ROOT,
                P + " carrier bays=%b pb=%b azf=%.2f zfl=%.3f diss=%.0f max=%.0f",
                shipHasLaunchBays.invoke(ship), shipIsPullBack.invoke(ship), azf, zfl, diss, max));
        if (zfl > 0.0f) {
            System.out.println(P + "-end verdict=PRECONDITION_FAILED zeroFluxMinimumFluxLevel=" + zfl
                    + " (the governing timer would not be the elite one)");
            System.exit(1);
        }

        int windowTicks = Math.max(1, Math.round(windowSeconds / fixedDt));
        int settleTicks = Math.max(1, Math.round(0.5f / fixedDt));
        Window[] windows = {new Window("A-off", false), new Window("B-on", true), new Window("C-off", false)};
        float prevCurr = Float.NaN;
        List<java.lang.reflect.Field> timerFields = new ArrayList<java.lang.reflect.Field>();
        for (Window w : windows) {
            step.withEngagedCarrierBandSnap(w.snapOn);
            long snapsBefore = EngineBackedCombatStepBoundary.engagedCarrierPreSnapCount();
            boolean pinWindow = fluxStack && w == windows[0];
            if (pinWindow) {
                System.setProperty(FLUX_STACK_GATE, "on");
            }
            int lastResetTick = -1;
            for (int i = 0; i < windowTicks; i++) {
                step.step(fixedDt, tick++);
                float curr = ((Number) ftGetCurr.invoke(ft)).floatValue();
                boolean boost = (Boolean) shipIsEngineBoostActive.invoke(ship);
                if (w == windows[0]) {
                    // The tracker's timers are private engine floats; the one(s) reading exactly dt right
                    // after a band add (i == 2, still ramping) are the "time since last positive add"
                    // candidates. A tick where such a field reads dt again = a positive add that tick.
                    if (i == 2) {
                        timerFields = fieldsEqualTo(ft, fixedDt);
                    }
                    boolean reset = i >= 2 && anyEquals(timerFields, ft, fixedDt);
                    if (reset) {
                        lastResetTick = i;
                    }
                    if (i % 100 == 0 || i == windowTicks - 1) {
                        System.out.println(P + " bays window=" + w.name + " i=" + i + " timer_reset=" + reset
                                + " curr=" + String.format(Locale.ROOT, "%.1f", curr) + bayCensus(ship, shipApiClass));
                    }
                }
                w.ticks++;
                w.minCurr = Math.min(w.minCurr, curr);
                w.maxCurr = Math.max(w.maxCurr, curr);
                if (boost) {
                    w.boostTicks++;
                    if (w.firstBoostTick < 0) {
                        w.firstBoostTick = i;
                    }
                }
                if (i >= windowTicks / 2) {
                    w.lastHalfTicks++;
                    if (boost) {
                        w.lastHalfBoostTicks++;
                    }
                }
                if (!Float.isNaN(prevCurr) && curr > prevCurr + ADD_DELTA_MIN) {
                    w.adds++;
                    if (i >= settleTicks) {
                        w.addsAfterSettle++;
                    }
                }
                prevCurr = curr;
                if (i == 0 || i == 1 || i == 2 || i == 30 || i == 60 || i == 90 || i == 120 || i == 200) {
                    diag(w.name, i, ship, ft, shipApiClass);
                }
            }
            w.preSnaps = EngineBackedCombatStepBoundary.engagedCarrierPreSnapCount() - snapsBefore;
            if (w == windows[0]) {
                System.out.println(String.format(Locale.ROOT, P + " reset_phase window=%s timer_fields=%d"
                        + " last_reset_tick=%d last_reset_s=%.3f", w.name, timerFields.size(), lastResetTick,
                        lastResetTick < 0 ? -1f : lastResetTick * fixedDt));
            }
            if (pinWindow) {
                System.setProperty(FLUX_STACK_GATE, "off");
                dumpFluxStackCensus();
            }
            System.out.println(String.format(Locale.ROOT,
                    P + " window=%s snap=%b ticks=%d boost_frac=%.3f last_half_frac=%.3f first_boost_s=%s"
                            + " adds=%d adds_after_settle=%d pre_snaps=%d curr_min=%.1f curr_max=%.1f alive=%b",
                    w.name, w.snapOn, w.ticks, w.boostTicks / (float) w.ticks, w.lastHalfFraction(),
                    w.firstBoostTick < 0 ? "never" : String.format(Locale.ROOT, "%.3f", w.firstBoostTick * fixedDt),
                    w.adds, w.addsAfterSettle, w.preSnaps, w.minCurr, w.maxCurr,
                    isShipAlive.invoke(engine, ship)));
        }

        Window a = windows[0];
        Window b = windows[1];
        Window c = windows[2];
        boolean aNoBoost = a.lastHalfFraction() < 0.10f && a.adds >= 10;
        boolean bBoost = b.firstBoostTick >= 0 && b.firstBoostTick * fixedDt <= 1.5f
                && b.lastHalfFraction() >= 0.95f && b.addsAfterSettle == 0 && b.preSnaps > 0;
        boolean cNoBoost = c.lastHalfFraction() < 0.10f;
        boolean fixed = aNoBoost && bBoost && cNoBoost;
        // At a high-fps dt (control run) the artefact is absent by vanilla law: the band never adds
        // (its 3·dt·diss add is far under the detector), the fix is INERT (0 pre-snaps — the engine
        // snaps itself) and the boost holds once the ~2.2 s post-engage add phase (vanilla
        // at every fps) has passed — window C, not necessarily A's last half.
        boolean highFpsControl = !fixed && a.adds == 0 && b.preSnaps == 0 && c.lastHalfFraction() >= 0.95f;
        String verdict = fixed ? "ZF_BAND_FIXED" : highFpsControl ? "HIGH_FPS_CONTROL_BOOSTS" : "FAILED";
        System.out.println(P + "-end verdict=" + verdict + " a_no_boost=" + aNoBoost + " b_boost=" + bBoost
                + " c_no_boost=" + cNoBoost + " fixed_dt=" + fixedDt);
        System.exit(fixed || highFpsControl ? 0 : 1);
    }

    /** Property the trace agent's {@code FluxAddStackCapture} reads per call (the probe never links it). */
    private static final String FLUX_STACK_GATE = "cc.trace.fluxAddStack.gate";

    private static void dumpFluxStackCensus() {
        try {
            Class.forName("cc.starsector.trace.FluxAddStackCapture").getMethod("dump").invoke(null);
        } catch (Throwable t) {
            System.out.println(P + " flux_stack=unavailable (" + t.getClass().getSimpleName()
                    + ": run the wrapper with -FluxStack so the trace agent rides the JVM)");
        }
    }

    private static List<java.lang.reflect.Field> fieldsEqualTo(Object ft, float value) {
        List<java.lang.reflect.Field> out = new ArrayList<java.lang.reflect.Field>();
        for (java.lang.reflect.Field f : ft.getClass().getDeclaredFields()) {
            if (f.getType() == float.class) {
                try {
                    f.setAccessible(true);
                    if (f.getFloat(ft) == value) {
                        out.add(f);
                    }
                } catch (Throwable ignored) {
                    // census only
                }
            }
        }
        return out;
    }

    private static boolean anyEquals(List<java.lang.reflect.Field> fields, Object ft, float value) {
        for (java.lang.reflect.Field f : fields) {
            try {
                if (f.getFloat(ft) == value) {
                    return true;
                }
            } catch (Throwable ignored) {
                // census only
            }
        }
        return false;
    }

    /** Per-bay wing state + shield/system flags — public API only; a read failure prints inline. */
    private static String bayCensus(Object ship, Class<?> shipApiClass) {
        StringBuilder sb = new StringBuilder();
        try {
            Object shield = shipApiClass.getMethod("getShield").invoke(ship);
            sb.append(" shield_on=").append(shield != null && (Boolean) shield.getClass().getMethod("isOn").invoke(shield));
            Object system = shipApiClass.getMethod("getSystem").invoke(ship);
            sb.append(" sys_active=").append(system != null && (Boolean) system.getClass().getMethod("isActive").invoke(system));
            // Ship.advance drives THREE F systems (system / travelDrive / phaseCloak); the deployment
            // fly-in travel drive is the pinned per-frame raiseFlux source (first pin run).
            Object travel = shipApiClass.getMethod("getTravelDrive").invoke(ship);
            sb.append(" travel_state=").append(travel == null ? "none"
                    : travel.getClass().getMethod("getState").invoke(travel));
            List<?> bays = (List<?>) shipApiClass.getMethod("getLaunchBaysCopy").invoke(ship);
            int b = 0;
            for (Object bay : bays) {
                Object wing = bay.getClass().getMethod("getWing").invoke(bay);
                sb.append(" bay").append(b++).append('=');
                if (wing == null) {
                    sb.append("none");
                    continue;
                }
                Object spec = wing.getClass().getMethod("getSpec").invoke(wing);
                int members = ((List<?>) wing.getClass().getMethod("getWingMembers").invoke(wing)).size();
                int num = ((Number) spec.getClass().getMethod("getNumFighters").invoke(spec)).intValue();
                int returning = ((List<?>) wing.getClass().getMethod("getReturning").invoke(wing)).size();
                sb.append(members).append('/').append(num).append('r').append(returning);
            }
        } catch (Throwable t) {
            sb.append(" bays_err=").append(t);
        }
        return sb.toString();
    }

    private static Object mintMember(Class<?> fleetMemberClass, Class<?> memberTypeClass,
            Class<?> hullVariantClass, Object variant, int owner, String name) throws Exception {
        Object member = fleetMemberClass
                .getConstructor(int.class, hullVariantClass, memberTypeClass)
                .newInstance(owner, variant, memberTypeClass.getField("SHIP").get(null));
        fleetMemberClass.getMethod("setShipName", String.class).invoke(member, name);
        // Faithful crew fix: full complement BEFORE spawn so nothing controls-locks.
        Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
        float minCrew = ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
        crewComp.getClass().getMethod("setCrew", float.class).invoke(crewComp, Math.max(minCrew, 1.0f));
        return member;
    }

    /**
     * Diagnostic row (the first run: B-on held the band with zero adds, boost still never): the
     * tracker's attempted-increase flag, controlsLocked, CR, every float field of the engine tracker
     * (the timers are private engine fields — a census, not a named read), firing weapons.
     */
    private static void diag(String window, int i, Object ship, Object ft, Class<?> shipApiClass) {
        StringBuilder sb = new StringBuilder(P + " diag window=" + window + " i=" + i);
        try {
            sb.append(" att=").append(ft.getClass().getMethod("isAttemptedFluxIncreaseThisFrame").invoke(ft));
            sb.append(" locked=").append(shipApiClass.getMethod("controlsLocked").invoke(ship));
            sb.append(" cr=").append(shipApiClass.getMethod("getCurrentCR").invoke(ship));
            sb.append(" hulk=").append(shipApiClass.getMethod("isHulk").invoke(ship));
            sb.append(" boost=").append(shipApiClass.getMethod("isEngineBoostActive").invoke(ship));
            for (java.lang.reflect.Field f : ft.getClass().getDeclaredFields()) {
                if (f.getType() == float.class || f.getType() == boolean.class) {
                    f.setAccessible(true);
                    sb.append(' ').append(f.getName()).append('=').append(f.get(ft));
                }
            }
            List<?> weapons = (List<?>) shipApiClass.getMethod("getAllWeapons").invoke(ship);
            int firing = 0;
            for (Object wp : weapons) {
                if ((Boolean) wp.getClass().getMethod("isFiring").invoke(wp)) {
                    firing++;
                }
            }
            sb.append(" weapons=").append(weapons.size()).append(" firing=").append(firing);
        } catch (Throwable t) {
            sb.append(" diag_err=").append(t);
        }
        System.out.println(sb);
    }

    private static Method findOneArg(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("no one-arg " + name + " on " + type.getName());
    }

    /** Boundary capture: the throwing edge is the finding, not an infra failure. */
    private static void bail(String verdict, InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        System.out.println(P + "-end verdict=" + verdict
                + " boundary=" + cause.getClass().getName() + ": " + cause.getMessage());
        cause.printStackTrace(System.out);
        System.exit(2);
    }
}
