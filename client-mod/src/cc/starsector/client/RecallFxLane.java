package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

import java.util.List;

/**
 * The fighter-recall presentation lane, knob {@code cc.predict.recallFx}
 * = {@code on|off} (default {@code on}): a fighter replica's {@code DEPARTED} despawn (carrier
 * recall/retreat) holds the body ~{@link #RECALL_FX_SECONDS} and plays vanilla's own recall
 * presentation on it — the {@code RecallDeviceStats.apply} per-fighter recipe verbatim
 * ({@code system_phase_skimmer} at the fighter, blue jitter, {@code setPhased(true)},
 * the State.IN alpha ramp {@code 1 - 0.5*level}) — then removes it, the removal vanilla's
 * {@code wing.land} amounts to on a replica. Vanilla's own script no-ops on replicas (they are
 * not wing-linked), so without this the recall is a silent pop-out.
 * Anchored on the LOCAL body loc — the wire entity is already absent, there is no pose to
 * reconcile against; local drift for 0.6 s IS the presentation. Zero wire change: FX plus the
 * body's fire hold ({@link #holdFire} — the body is no longer replay-driven, so this lane holds it).
 * {@code off} = the earlier silent remove, byte-for-byte. Invalid values THROW at class load.
 *
 * <p>NEW-LANE LAW collaborator: PRCP keeps touchpoints only —
 * the enroll relay at the fighter kill lane, an advance relay, a reset relay. Render-thread only.
 */
final class RecallFxLane {

    /** The knob; {@code off} keeps the caller's silent-remove path byte-for-byte. */
    static final boolean ON = parseOnOff(System.getProperty("cc.predict.recallFx", "on"));

    /** The DEPARTED hold-and-animate window (owner-spec'd ~0.6 s). */
    private static final float RECALL_FX_SECONDS = 0.6f;
    /** RecallDeviceStats KEY_JITTER / JITTER_COLOR twins — per-lane key object. */
    private static final Object JITTER_KEY = new Object();
    private static final java.awt.Color JITTER_COLOR = new java.awt.Color(100, 165, 255, 155);

    /** One DEPARTED fighter mid recall animation: body ref + elapsed clock + its weapons, listed
     *  once at enroll so the per-frame hold allocates nothing. */
    private static final class Departing {
        final ShipAPI ship;
        final List<WeaponAPI> weapons;
        float elapsed;
        /** True once {@link #holdFire}'s groups-off loop ran to its end (a throw mid-loop leaves it false). */
        boolean groupsOff;

        Departing(ShipAPI ship) {
            this.ship = ship;
            this.weapons = ship.getAllWeapons();
        }
    }

    /** Bodies mid-animation (enrolled at the kill lane, ticked per frame, swept at round wrap). */
    private final java.util.ArrayList<Departing> departing = new java.util.ArrayList<Departing>();
    /** The same bodies by identity — {@link #isDeparting}'s read; empty on the common frame. */
    private final java.util.Set<ShipAPI> bodies =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ShipAPI, Boolean>());

    /**
     * Enroll a DEPARTED fighter body: first-frame FX now (the firstTime sound + phase-out),
     * the per-frame ramp and the removal in {@link #advance}. Returns {@code false} (knob off /
     * dead body) ⇒ the caller keeps the silent-remove path.
     */
    boolean beginDeparture(ShipAPI ship) {
        if (!ON || ship == null || !ship.isAlive()) {
            return false;
        }
        Global.getSoundPlayer().playSound("system_phase_skimmer", 1f, 0.5f,
                ship.getLocation(), ship.getVelocity());
        ship.setPhased(true);
        Departing df = new Departing(ship);
        departing.add(df);
        bodies.add(ship);
        try {
            holdFire(df);
        } catch (Throwable t) {
            // enrolled already: advance retries the whole hold next frame (the groups-off loop too,
            // while df.groupsOff is false), and its fail-soft arm removes the body if the hold
            // throws again — the kill lane's own flow must not break here
            FallbackAnnounce.announceOnce("recallfx-enroll-hold-threw", "departing-fighter fire hold threw "
                    + t.getClass().getSimpleName() + " at enroll -> retried next frame");
        }
        return true;
    }

    /**
     * The departing body's fire hold. The kill lane takes the body out
     * of the replay-driven set ({@code FoeStateLaneCoordinator.killFighterRemote}), which ended its
     * per-frame hold ({@code ReplicaBodyLane.reassertReplayDrivenHold}) while the body lived on here:
     * its mirrored autofire-ON groups then fired local-only shots no lane owned (in a live run,
     * dmg-apply #227 = a server hit applied a second time, #232 = a hit with no server twin).
     * Vanilla never fires from a recalled fighter — {@code Ship.beginLandingAnimation} turns every
     * group off — so that idiom runs until its loop COMPLETES once ({@code
     * df.groupsOff}: at enroll, else on the next {@link #advance} frame; it
     * persists across fast-time sub-steps, which clear the one-frame flags after pass 1; the
     * foe-autofire mirror stops writing a killed entry), and the replay-driven hold's own flags
     * re-assert per frame: the ship-level hold, per-weapon no-fire (beams included — the wire
     * entity is gone, no relay owns them), {@code stopFiring} on a committed burst, which ignores
     * both flags. A shot that still slips is counted and removed by the autonomous-discharge
     * detector ({@link #isDeparting}).
     */
    private static void holdFire(Departing df) {
        df.ship.setHoldFireOneFrame(true);
        if (!df.groupsOff) {
            List<WeaponGroupAPI> groups = df.ship.getWeaponGroupsCopy();
            if (groups != null) {
                for (int g = 0; g < groups.size(); g++) {
                    WeaponGroupAPI wg = groups.get(g);
                    if (wg != null && wg.isAutofiring()) {
                        wg.toggleOff();
                    }
                }
            }
            df.groupsOff = true;
        }
        if (df.weapons == null) {
            return;
        }
        for (int w = 0; w < df.weapons.size(); w++) {
            WeaponAPI weapon = df.weapons.get(w);
            if (weapon == null) {
                continue;
            }
            weapon.setForceNoFireOneFrame(true);
            if (weapon.isFiring()) {
                weapon.stopFiring();
            }
        }
    }

    /**
     * True while {@code source} is a body mid recall animation: PRCP's projectile scan treats it as
     * still replay-driven, so a shot the hold let slip trips the graded
     * {@code ..._replay_ship_autonomous_discharge} bar and is leak-removed like any other.
     */
    boolean isDeparting(Object source) {
        return !bodies.isEmpty() && bodies.contains(source);
    }

    /**
     * Tick the animations — per live frame, the RecallDeviceStats per-fighter body verbatim
     * — jitter at {@code level} with range {@code 5 + level*collisionRadius} (10 copies),
     * {@code setPhased(true)} while alive, alpha {@code 1 - 0.5*level}; at window end the removal.
     * Fail-soft per entry: any throw removes the body immediately (the DEPARTED contract — no
     * corpse may outlive the animation).
     */
    void advance(CombatEngineAPI engine, float amount) {
        if (departing.isEmpty() || engine == null) {
            return;
        }
        for (java.util.Iterator<Departing> it = departing.iterator(); it.hasNext();) {
            Departing df = it.next();
            try {
                df.elapsed += amount;
                if (df.ship == null || df.elapsed >= RECALL_FX_SECONDS) {
                    if (df.ship != null) {
                        engine.removeEntity(df.ship);
                    }
                    bodies.remove(df.ship);
                    it.remove();
                    continue;
                }
                holdFire(df);
                float level = df.elapsed / RECALL_FX_SECONDS;
                df.ship.setJitter(JITTER_KEY, JITTER_COLOR, level, 10, 0f,
                        5f + level * df.ship.getCollisionRadius());
                if (df.ship.isAlive()) {
                    df.ship.setPhased(true);
                }
                df.ship.setExtraAlphaMult(1f - level * 0.5f);
            } catch (Throwable ignore) {
                try {
                    if (df.ship != null) {
                        engine.removeEntity(df.ship);
                    }
                } catch (Throwable ignore2) {
                    // the engine refused the remove too — drop the entry, never break the frame
                }
                bodies.remove(df.ship);
                it.remove();
            }
        }
    }

    /**
     * Round wrap: mid-animation bodies die with the round (the MRST body sweep usually got them
     * already — this is the fail-soft catch for un-swept stragglers).
     */
    void resetRound(CombatEngineAPI engine) {
        for (Departing df : departing) {
            try {
                if (engine != null && df.ship != null) {
                    engine.removeEntity(df.ship);
                }
            } catch (Throwable ignore) {
                // reset-time sweep; never break the round wrap
            }
        }
        departing.clear();
        bodies.clear();
    }

    private static boolean parseOnOff(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(v)) {
            return true;
        }
        if ("off".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("cc.predict.recallFx must be on|off, got: " + raw);
    }
}
