package cc.starsector.server.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The round-start travel-drive watch: the one-shot that pairs a mod's travel-drive switch-off with the
 * engine's own zeroing verb for the hulls the battle-start FLY-IN lane deployed.
 *
 * <p>WHY a watch and not a call at the spawn: the round's create deploys the fly-in roster while the engine is
 * still PAUSED (the create unpauses at its tail); a
 * mod's stats script skips paused frames (Arma Armatura's {@code apply} returns early while paused),
 * so the drive comes back from the deploy still running and the strand
 * (a {@code deactivate()} that leaves the private remaining-burn timer above zero; the engine then blocks the
 * primary system every frame) forms on the first UNPAUSED frames, after every spawn-time
 * site has run. The mid-round sites ({@link RealEngineDemoRoster#midDeploySeat}, the wave spawn) run
 * unpaused, where the switch-off happens inside the deploy's own pre-advances and the spawn-time call sees
 * it.
 *
 * <p>Shape: {@link #arm} replaces the pending set with the lane's fly-in hulls (the teleport lane arms none);
 * the serve loop's step decorator calls {@link #tick} POST-STEP, after the engine's own advance ran the
 * mods' scripts. Per pending hull and tick: ONE cached drive-state read. A drive still running (IN /
 * ACTIVE / OUT) stays pending; the first frame it is not, the hull retires: the timer above zero = the strand
 * → the same guard + verb + ONE row as the spawn sites ({@link RealEngineDemoRoster#clearStrandedTravelDrive});
 * the timer at zero = the engine's own exit (arrival / collision / flameout each zero it)
 * → silent. The one engine exit that leaves the timer ABOVE zero is the
 * flux-starve exit (OUT → IDLE when the drive's flux raise fails under overload, in the engine's own system code)
 * — unreachable on a vanilla fly-in (the drive sets noShield / noFiring), and if a
 * mod ever reaches it the watch clears it as a strand, which is the right call. A hull still running past
 * {@link #BOUND_S} retires with one {@code expired} row (a drive a mod keeps on is not a strand). ONE
 * {@code watch done} row per round when the last hull retires, carrying the counts and the poll's measured cost;
 * a re-arm before the last retire replaces the pending set, so that round's done row is never printed (S3-3). A
 * helper {@code clearStranded() == false} ({@code clear skipped}) counts as neither cleared nor failed (S3-3).
 *
 * <p>per-deploy, bounded to the burn (a few seconds of a round); the still-running path allocates ONE
 * {@code Iterator} per tick and nothing per hull (the row strings exist only on a retire), no I/O
 * but the rows, an empty watch is one {@code isEmpty()} per tick; the cost rides the done row ({@code poll_us} = the
 * WHOLE of every tick incl. the rows printed and the helper's first-use reflection — a total, not a steady-state
 * poll; S3-3). Never throws out of {@link #tick}: a failed read retires that hull with one
 * {@code read failed} row (a drive quirk must never stall a round). Sim-thread only (the roster deploy and the
 * step decorator); the lock exists for the rows' snapshots. Only the serve loop's step decorator ticks it — the
 * legacy {@code DedicatedCombatServerMain} steps (not packaged) never do (S3-5).
 */
public final class FlyInTravelDriveWatch {

    /** The site label on every row this watch prints (the spawn sites print theirs). */
    public static final String SITE = "roundStartFlyIn";

    /**
     * SERVE seconds (the step boundary's fixed dt summed — {@code fixedDt} × timeMult × combatSpeedMult, the
     * {@code EngineBackedCombatStepBoundary} law; not sim seconds; {@code after_s} on the expired
     * row is the same unit) a hull may keep its drive running before the watch stops waiting for it: the battle-start
     * burn is 2.0-2.5 s plus the drive's charge-down, so a drive still on here is held on by something else.
     */
    public static final float BOUND_S = 15f;

    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIP_SYSTEM_API = "com.fs.starfarer.api.combat.ShipSystemAPI";
    private static final String TIMER_FIELD = "remainingDriveDur";

    /** What the watch reads from one armed hull. Public only so the game-free rows can drive the table. */
    public interface Hull {
        String label();

        /** The drive's state name (IDLE / IN / ACTIVE / OUT / COOLDOWN), or null for a hull without a drive. */
        String driveState() throws Exception;

        /** The engine's private remaining-burn timer. */
        float remainingDriveDur() throws Exception;

        /** The guard + verb + one row ({@link RealEngineDemoRoster#clearStrandedTravelDrive}). */
        boolean clearStranded();
    }

    private static final class Entry {
        final Hull hull;
        float elapsedS;

        Entry(Hull hull) {
            this.hull = hull;
        }
    }

    /** The engine-backed hull: cached handles, one state read per tick. */
    private static final class EngineHull implements Hull {
        final ClassLoader loader;
        final Class<?> shipClass;
        final Object ship;
        final String label;
        final Method getTravelDrive;
        final Method getState;
        final Field timer;

        EngineHull(ClassLoader loader, Class<?> shipClass, Object ship, String label, Method getTravelDrive,
                Method getState, Field timer) {
            this.loader = loader;
            this.shipClass = shipClass;
            this.ship = ship;
            this.label = label;
            this.getTravelDrive = getTravelDrive;
            this.getState = getState;
            this.timer = timer;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String driveState() throws Exception {
            Object drive = getTravelDrive.invoke(ship);
            if (drive == null) {
                return null;
            }
            Object state = getState.invoke(drive);
            return state instanceof Enum<?> e ? e.name() : String.valueOf(state);
        }

        @Override
        public float remainingDriveDur() throws Exception {
            return timer.getFloat(ship);
        }

        @Override
        public boolean clearStranded() {
            return RealEngineDemoRoster.clearStrandedTravelDrive(loader, shipClass, ship, SITE, label);
        }
    }

    private static final List<Entry> pending = new ArrayList<>();
    private static int armed;
    private static int cleared;
    private static int expired;
    private static int failed;
    private static int ticks;
    private static long pollNanos;

    private FlyInTravelDriveWatch() {
    }

    /**
     * Arms this round's fly-in hulls (replaces any earlier set; an empty list = nothing to watch). Fail-soft: a
     * handle that does not resolve arms nothing and prints one {@code arm failed} row, because a drive quirk must
     * never fail a deploy.
     */
    public static void arm(ClassLoader loader, Class<?> shipClass, List<Object> ships, List<String> labels) {
        List<Hull> hulls = new ArrayList<>(ships.size());
        if (!ships.isEmpty()) {
            try {
                Class<?> shipApi = Class.forName(SHIP_API, false, loader);
                Class<?> systemApi = Class.forName(SHIP_SYSTEM_API, false, loader);
                Method getTravelDrive = shipApi.getMethod("getTravelDrive");
                Method getState = systemApi.getMethod("getState");
                // private on the engine's Ship with no getter (the probes and rows read the same field)
                Field timer = shipClass.getDeclaredField(TIMER_FIELD);
                timer.setAccessible(true);
                for (int i = 0; i < ships.size(); i++) {
                    String label = i < labels.size() ? labels.get(i) : ("fly-in#" + i);
                    hulls.add(new EngineHull(loader, shipClass, ships.get(i), label, getTravelDrive, getState,
                            timer));
                }
            } catch (Throwable t) {
                System.out.println("[roster] travel-drive watch arm failed site=" + SITE + " hulls=" + ships.size()
                        + " reason=" + unwrap(t).getClass().getSimpleName());
                hulls.clear();
            }
        }
        armHulls(hulls);
    }

    /** The table entry: arms the given hulls (replaces the pending set) and resets the round's counters. */
    public static synchronized void armHulls(List<Hull> hulls) {
        pending.clear();
        for (Hull h : hulls) {
            pending.add(new Entry(h));
        }
        armed = hulls.size();
        cleared = 0;
        expired = 0;
        failed = 0;
        ticks = 0;
        pollNanos = 0L;
    }

    /** The POST-STEP poll; {@code dtSeconds} = the step's fixed sim dt. */
    public static synchronized void tick(float dtSeconds) {
        if (pending.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        ticks++;
        Iterator<Entry> it = pending.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            e.elapsedS += dtSeconds;
            try {
                String state = e.hull.driveState();
                // an unknown state counts as running, as the helper's guard does: never a clear on a guess
                boolean running = state != null && !"IDLE".equals(state) && !"COOLDOWN".equals(state);
                if (running) {
                    if (e.elapsedS <= BOUND_S) {
                        continue;
                    }
                    expired++;
                    System.out.println("[roster] travel-drive watch expired site=" + SITE + " ship=" + e.hull.label()
                            + " drive=" + state + " after_s=" + Math.round(e.elapsedS));
                } else if (state != null && e.hull.remainingDriveDur() > 0f) {
                    if (e.hull.clearStranded()) {
                        cleared++;
                    }
                }
            } catch (Throwable t) {
                failed++;
                System.out.println("[roster] travel-drive watch read failed site=" + SITE + " ship=" + e.hull.label()
                        + " reason=" + unwrap(t).getClass().getSimpleName());
            }
            it.remove();
        }
        pollNanos += System.nanoTime() - t0;
        if (pending.isEmpty()) {
            System.out.println("[roster] travel-drive watch done site=" + SITE + " armed=" + armed + " cleared="
                    + cleared + " expired=" + expired + " failed=" + failed + " ticks=" + ticks + " poll_us="
                    + (pollNanos / 1000L));
        }
    }

    public static synchronized int pendingCount() {
        return pending.size();
    }

    /** The engine hulls still pending (snapshot, in arm order); the rows pick their target here. */
    public static synchronized List<Object> pendingShips() {
        List<Object> out = new ArrayList<>(pending.size());
        for (Entry e : pending) {
            if (e.hull instanceof EngineHull eh) {
                out.add(eh.ship);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The pending hulls' labels (snapshot, in arm order). */
    public static synchronized List<String> pendingLabels() {
        List<String> out = new ArrayList<>(pending.size());
        for (Entry e : pending) {
            out.add(e.hull.label());
        }
        return Collections.unmodifiableList(out);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }
}
