package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * The per-FRAME {@code ShipAPI.getAllWeapons()} cache for the
 * render-thread replica lanes.
 *
 * <p><b>Why:</b> the engine's {@code Ship.getAllWeapons()} allocates a
 * fresh {@code ArrayList} and walks the ship's whole module list on EVERY call; the replica lanes
 * called it ~6× per replica per frame (weapon-state, autofire, selection, beam presentation, beam
 * aim, the same-side hold walk) — a JFR profile read it at 7–10 % of the plugin advance plus
 * the {@code ArrayList.grow} churn clustered under it.
 *
 * <p><b>Law:</b> one fetch per ship per plugin frame. The cache is stamped by {@link #nextFrame}
 * (called once at the top of the plugin's {@code advanceBody}); a hit returns the SAME list the
 * first caller got this frame, so the wire {@code mountIndex} ↔ ordinal contract is the engine's
 * own walk order, unchanged. Weapons never join/leave a Ship during combat, but the cache still
 * re-fetches every frame on purpose — zero staleness surface for a sub-2 % lever. Consumers
 * iterate only; nobody mutates the returned list.
 *
 * <p>Render-thread confined (identity map, no locking). Bounded: past {@link #MAX_SHIPS} the map
 * is dropped whole and rebuilt lazily (the engine retains every ever-deployed Ship, so a dead
 * body's entry would otherwise sit until match reset — {@link #reset} clears it there too).
 */
final class WeaponListCache {

    /** Bounded-allocation cap on cached ships (replicas + fighters + modules; a bastion match
     *  is well under this — past it the whole map is dropped and rebuilt lazily). */
    static final int MAX_SHIPS = 1024;

    private static final class Entry {
        List<WeaponAPI> weapons;
        long frame = -1L;
    }

    private final IdentityHashMap<ShipAPI, Entry> byShip = new IdentityHashMap<>();
    private long frame;
    private long fetches;
    private long hits;

    /** Once per plugin frame, BEFORE any consumer runs (the advanceBody head). */
    void nextFrame() {
        frame++;
    }

    /** The ship's {@code getAllWeapons()} list for THIS frame ({@code null} ⇔ the engine
     *  returned null; never throws). */
    List<WeaponAPI> weapons(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        Entry e = byShip.get(ship);
        if (e == null) {
            if (byShip.size() >= MAX_SHIPS) {
                byShip.clear();
            }
            e = new Entry();
            byShip.put(ship, e);
        }
        if (e.frame != frame) {
            e.weapons = ship.getAllWeapons();
            e.frame = frame;
            fetches++;
        } else {
            hits++;
        }
        return e.weapons;
    }

    /** Round wrap: ship identities re-mint — drop every entry (cumulative counters persist). */
    void reset() {
        byShip.clear();
    }

    /** Census term: {@code fetches/hits/ships}. */
    String census() {
        return fetches + "/" + hits + "/" + byShip.size();
    }
}
