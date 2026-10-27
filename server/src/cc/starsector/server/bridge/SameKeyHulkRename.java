package cc.starsector.server.bridge;

import java.lang.reflect.Method;
import java.util.List;

/**
 * A newly deployed hull never shares its {@code Ship.toString()} key
 * with a HULK already in the engine.
 *
 * <p>WHY: vanilla ship-system scripts key engine customData by {@code ship.toString()} =
 * {@code variant + " " + name + " [" + hull + "]"} (see
 * {@code MoteControlScript.getSharedData}). Our seats respawn under the SAME
 * seat name ({@code DescriptorLayout.seatShipName}), so a respawn on the same variant shares its
 * mote list with its own previous life's hulk — whose system script keeps running
 * ({@code runScriptWhileIdle}) and whose hulk branch
 * {@code flameOut()}s + clears the shared list every launch interval, killing
 * the successor's motes at birth (seen in play as "motes fade a few ship-lengths
 * off the launchers"). Vanilla never mints a
 * same-key pair (no respawn), so the engine carries no guard.
 *
 * <p>LAW: at the deploy seam ({@code RealEngineDemoRoster.midDeploySeat} / {@code midDeployCore}),
 * ONE walk of the engine ship list renames every HULK whose key equals the new hull's to
 * {@code "<name> (hulk <engine id>)"}: the hulk's script re-keys to a fresh empty entry on its
 * next advance and the successor's list is its own. The hulk's own death-time flameOut ran
 * untouched (vanilla-exact: the key changes only at the NEXT deploy, seconds later). A LIVE
 * same-key ship (a vacated hull still flying under the seat name) is COUNTED in the row, never
 * renamed — an unobserved class with an owner-visible name; the row is its tell. Client mirror =
 * {@code SeatNameHullBarLane.applySeatName} (each engine keys its own customData map).
 *
 * <p>Cost: one reflective walk per deploy (respawns are seconds apart), zero per tick, zero wire.
 * Reflection = public API methods resolved off the live instances' loader (the server tree is
 * game-jar-free); fail-soft-loud — a reflective failure prints one row and never aborts the
 * deploy. {@link #sweep(List, Object, ShipView, String)} is the pure core the contract row drives.
 */
public final class SameKeyHulkRename {
    private SameKeyHulkRename() {
    }

    /** The four ship reads/writes the sweep needs; production = {@link ReflectiveView}. */
    public interface ShipView {
        boolean isHulk(Object ship) throws Exception;

        String name(Object ship) throws Exception;

        void setName(Object ship, String name) throws Exception;

        String id(Object ship) throws Exception;
    }

    /** Outcome of one sweep: hulks renamed + live same-key ships left alone. */
    public record Sweep(int renamed, int liveSameKey) {
    }

    /**
     * Rename every HULK in {@code ships} (other than {@code newShip}) whose {@code toString()}
     * equals the new hull's. Skips ships with a null name (station modules: {@code getName()}
     * is null under a parent station). Idempotent by construction — a renamed hulk's key never
     * matches again.
     */
    public static Sweep sweep(List<?> ships, Object newShip, ShipView view, String site)
            throws Exception {
        String key = String.valueOf(newShip);
        int renamed = 0;
        int live = 0;
        StringBuilder ids = new StringBuilder();
        for (Object ship : ships) {
            if (ship == null || ship == newShip || !key.equals(String.valueOf(ship))) {
                continue;
            }
            if (!view.isHulk(ship)) {
                live++;
                continue;
            }
            String name = view.name(ship);
            if (name == null) {
                continue;
            }
            String id = view.id(ship);
            view.setName(ship, name + " (hulk " + (id == null ? "?" : id) + ")");
            renamed++;
            ids.append(ids.length() == 0 ? "" : ",").append(id);
        }
        if (renamed > 0 || live > 0) {
            System.out.println("same-key-hulk site=" + site + " key='" + key
                    + "' hulks_renamed=" + renamed + " ids=" + ids
                    + " live_same_key=" + live);
        }
        return new Sweep(renamed, live);
    }

    /**
     * Production entry: walk {@code engine.getShips()} for {@code newShip}. Any failure prints
     * one {@code same-key-hulk FAILED} row and returns null (the deploy proceeds).
     */
    public static Sweep sweepEngine(Object engine, Object newShip, String site) {
        try {
            ClassLoader loader = newShip.getClass().getClassLoader();
            Method getShips = apiOrRuntime(loader, "com.fs.starfarer.api.combat.CombatEngineAPI",
                    engine.getClass()).getMethod("getShips");
            List<?> ships = (List<?>) getShips.invoke(engine);
            if (ships == null) {
                return new Sweep(0, 0);
            }
            return sweep(ships, newShip, new ReflectiveView(loader, newShip.getClass()), site);
        } catch (Throwable t) {
            System.out.println("same-key-hulk FAILED site=" + site + " error=" + t
                    + (t.getCause() != null ? " cause=" + t.getCause() : ""));
            return null;
        }
    }

    private static Class<?> apiOrRuntime(ClassLoader loader, String apiClass, Class<?> runtime) {
        try {
            return Class.forName(apiClass, false, loader);
        } catch (ClassNotFoundException e) {
            return runtime;
        }
    }

    /** Public {@code ShipAPI} methods off the live loader (runtime class when the API is absent). */
    private static final class ReflectiveView implements ShipView {
        private final Method isHulk;
        private final Method getName;
        private final Method setName;
        private final Method getId;

        ReflectiveView(ClassLoader loader, Class<?> runtimeShipClass) throws NoSuchMethodException {
            Class<?> shipApi = apiOrRuntime(loader, "com.fs.starfarer.api.combat.ShipAPI",
                    runtimeShipClass);
            isHulk = shipApi.getMethod("isHulk");
            getName = shipApi.getMethod("getName");
            setName = shipApi.getMethod("setName", String.class);
            getId = shipApi.getMethod("getId");
        }

        @Override
        public boolean isHulk(Object ship) throws Exception {
            return Boolean.TRUE.equals(isHulk.invoke(ship));
        }

        @Override
        public String name(Object ship) throws Exception {
            Object n = getName.invoke(ship);
            return n == null ? null : n.toString();
        }

        @Override
        public void setName(Object ship, String name) throws Exception {
            setName.invoke(ship, name);
        }

        @Override
        public String id(Object ship) throws Exception {
            Object id = getId.invoke(ship);
            return id == null ? null : id.toString();
        }
    }
}
