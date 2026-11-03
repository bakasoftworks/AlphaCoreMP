package cc.starsector.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import cc.starsector.client.net.ServerWeaponMountState;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * The replica-loadout FORK check: once per replica tenure, at the bind, each weapon the server
 * reports on the hull's mounts is compared with the weapon the local replica actually fielded at
 * the same mount ordinal. Why: a mod hullmod that rolls slot weapons inside fleet-member creation
 * rolls on each engine's own Random, so a replica built from the same stock variant can carry a
 * different weapon than the authority's body, and nothing after the spawn re-fits a live hull; the
 * difference is then a silent desync (fire, damage and the beam / projectile split all read the
 * local mount). The check makes it loud; it does not repair it (the server's realized-variant
 * definition does).
 *
 * <p>Inputs. Server truth = the per-mount tier on the minting pose
 * ({@link ServerEntityPose#weaponMounts()}: the server's {@code getAllWeapons()} ordinal plus an
 * interned weapon content id) resolved through the content dictionary the caller folds
 * ({@code contentCatalog}, wire text: every token is sanitized before it reaches a log row). The
 * mount record carries no slot id, so the match is by ordinal, the same index every remote
 * presenter reads; the fork row adds the local slot id so an ordinal shift reads as such.
 *
 * <p>Bounds (frame residency is earned: 12 mounts, once per tenure). A tenure whose server ids are
 * not all resolvable yet (no mount record this frame, {@code NO_CONTENT}, or not announced yet) and
 * shows no difference so far waits on one small pending list and is re-read on later FRESH frames,
 * at most {@link #RECHECK_FRAMES}; past that, or when the tenure ends first, ONE {@code unresolved}
 * row — never silent. Render thread only. Meters are match-scoped (the owning plugin's life).
 *
 *
 */
public final class ReplicaLoadoutCheck {

    /** {@link #verdict}: the server's weapon equals the replica's. */
    public static final int EQUAL = 0;
    /** {@link #verdict}: the server's weapon is known and the replica's differs (or is missing). */
    public static final int FORK = 1;
    /** {@link #verdict}: the server's weapon id is not resolvable yet; nothing can be judged. */
    public static final int UNRESOLVED = 2;
    /** Fresh frames a pending tenure is re-read before its one {@code unresolved} row (~2-4 s). */
    public static final int RECHECK_FRAMES = 120;
    /** Pending tenures held at once; past it a new unresolved tenure takes its row immediately. */
    static final int MAX_PENDING = 64;
    /** {@link #check}: the tenure reached its verdict (equal or fork). */
    private static final int DONE = -1;

    /**
     * The pure per-mount verdict. {@code expected} = the server's weapon id through the content
     * dictionary, {@code actual} = the replica's weapon id at the same ordinal (null = no weapon
     * there), {@code resolved} = the server id resolved. Unresolved wins over everything: an
     * unknown server id is never judged equal or forked.
     */
    public static int verdict(String expected, String actual, boolean resolved) {
        if (!resolved || expected == null) {
            return UNRESOLVED;
        }
        return expected.equals(actual) ? EQUAL : FORK;
    }

    private static final class Pending {
        final PredictReconcileCombatPlugin.RemoteEntity re;
        final ShipAPI ship;
        final String variantId;
        int frames;
        int open;

        Pending(PredictReconcileCombatPlugin.RemoteEntity re, ShipAPI ship, String variantId, int open) {
            this.re = re;
            this.ship = ship;
            this.variantId = variantId;
            this.open = open;
        }
    }

    private final Map<Integer, String> catalog;
    private final ArrayList<Pending> pending = new ArrayList<>(4);
    private int checked;
    private int forks;
    private int unresolved;
    private int errors;

    ReplicaLoadoutCheck(Map<Integer, String> catalog) {
        this.catalog = catalog;
    }

    /** At the replica bind ({@code re.ship} just set to {@code ship}); {@code pose} = the minting pose. */
    void onBound(PredictReconcileCombatPlugin.RemoteEntity re, ShipAPI ship, ServerEntityPose pose,
            String variantId) {
        int open = check(re.entityId, ship, pose, variantId);
        if (open == DONE) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            unresolvedRow(re.entityId, variantId, open, 0, "pending list full");
            return;
        }
        pending.add(new Pending(re, ship, variantId, open));
    }

    /** Per FRESH frame, after the dictionary fold and the binds; a no-op while nothing waits. */
    void recheck(ServerSnapshotFrame snapshot) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            if (p.re.ship != p.ship) {
                pending.remove(i);
                unresolvedRow(p.re.entityId, p.variantId, p.open, p.frames, "tenure ended");
                continue;
            }
            ServerEntityPose pose = snapshot.entityById(p.re.entityId);
            if (pose != null) {
                int open = check(p.re.entityId, p.ship, pose, p.variantId);
                if (open == DONE) {
                    pending.remove(i);
                    continue;
                }
                p.open = open;
            }
            if (++p.frames >= RECHECK_FRAMES) {
                pending.remove(i);
                unresolvedRow(p.re.entityId, p.variantId, p.open, p.frames, "recheck bound spent");
            }
        }
    }

    /** The meter tokens the plugin's bind summary row carries. */
    String meters() {
        return "replica_loadout_checked=" + checked + " replica_loadout_forks=" + forks
                + " replica_loadout_unresolved=" + unresolved + " replica_loadout_pending=" + pending.size()
                + " replica_loadout_errors=" + errors;
    }

    /** {@link #DONE} at a verdict, else the count of mounts still unresolvable (0 = no mount record). */
    private int check(long entityId, ShipAPI ship, ServerEntityPose pose, String variantId) {
        try {
            List<ServerWeaponMountState> mounts = pose.weaponMounts();
            if (mounts.isEmpty()) {
                return 0;
            }
            List<WeaponAPI> weapons = ship.getAllWeapons();
            int open = 0;
            int differing = 0;
            int firstMount = -1;
            String firstReplica = null;
            String firstServer = null;
            WeaponAPI firstWeapon = null;
            for (int k = 0; k < mounts.size(); k++) {
                ServerWeaponMountState m = mounts.get(k);
                int idx = m.mountIndex();
                String expected = m.weaponContentId() == 0 ? null : catalog.get(Integer.valueOf(m.weaponContentId()));
                WeaponAPI w = weapons != null && idx >= 0 && idx < weapons.size() ? weapons.get(idx) : null;
                String actual = w != null ? w.getId() : null;
                int v = verdict(expected, actual, expected != null);
                if (v == UNRESOLVED) {
                    open++;
                } else if (v == FORK && differing++ == 0) {
                    firstMount = idx;
                    firstReplica = actual;
                    firstServer = expected;
                    firstWeapon = w;
                }
            }
            if (differing == 0 && open > 0) {
                return open;
            }
            checked++;
            if (differing > 0) {
                forks++;
                String slot = firstWeapon != null && firstWeapon.getSlot() != null
                        ? firstWeapon.getSlot().getId() : null;
                String fork = "entity " + entityId + " mount " + firstMount + " replica="
                        + FallbackAnnounce.sanitize(firstReplica, 64) + " != server="
                        + FallbackAnnounce.sanitize(firstServer, 64) + " variant="
                        + FallbackAnnounce.sanitize(variantId, 64) + " (slot="
                        + FallbackAnnounce.sanitize(slot, 64) + " differing=" + differing + "/" + mounts.size()
                        + " open=" + open + "; forks=" + forks + ")";
                PilotWatchLog.error("predict replica-loadout FORK: " + fork, null);
                FallbackAnnounce.announceOnce("replica-loadout-fork", fork);
            }
            return DONE;
        } catch (RuntimeException e) {
            // an engine read threw: counted, the first one logged; the tenure is closed (no retry storm)
            if (errors++ == 0) {
                PilotWatchLog.error("predict replica-loadout: check threw at entity " + entityId
                        + " (counted as replica_loadout_errors; later throws only count)", e);
            }
            return DONE;
        }
    }

    private void unresolvedRow(long entityId, String variantId, int open, int frames, String why) {
        unresolved++;
        PilotWatchLog.warn("predict replica-loadout unresolved: entity " + entityId + " variant="
                + FallbackAnnounce.sanitize(variantId, 64) + " open_mounts="
                + (open == 0 ? "no-mount-record" : Integer.toString(open)) + " after " + frames
                + " fresh frames (" + why + "; unresolved=" + unresolved + ")");
    }
}
