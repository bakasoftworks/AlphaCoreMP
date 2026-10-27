package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client half of the autofire-TARGET sync lane: per-mount
 * intake of {@code AUTOFIRE_TARGET} gameplay edge events (GPEE v9, kind 7) for the OWN
 * ship, served to the {@link AutofireTargetAiPlugin} designation-bracket wrappers that
 * {@link CcClientModPlugin#pickWeaponAutofireAI} installs on every weapon.
 *
 * <p>Why: the client's LOCAL autofire pick diverges from
 * the server's on the same engaged cluster — strict pairs &gt;45° were 15/15 DIFFERENT
 * ship — so own autofire guns visibly aim at a neighbor of the ship the authoritative sim
 * is shooting. This lane substitutes the server's pick at the vanilla plugin's own
 * designation seam (see the wrapper class doc); fire/hold logic stays vanilla.
 *
 * <p>Laws: fail-closed resolve — a targetWireId that does not resolve in
 * {@code plugin.remoteEntities} keeps the LOCAL pick and counts
 * ({@code skip_unknown}); the resolved {@link ShipAPI} is NEVER cached (re-resolved per
 * apply, so a {@code respawnRemote} body swap can't serve a stale hull). A held pick
 * expires {@link #staleTicks()} after its last (re-)emit — the server keepalives every
 * ~60 ticks, so expiry ≈ 2–3 lost keepalives — and on clock anomaly (snapshot tick behind
 * the pick's tick) expiry fails OPEN to the vanilla pick (the {@code OwnFireLockLaw}
 * rewound-clock law). Mount ordinal = the ship's {@code getAllWeapons()} index — the SAME
 * space the server's {@code AutofireTargetLane} emits (identical across
 * repoints). Single-threaded: all calls ride the combat/render thread (intake from the
 * plugin's drain, queries from weapon advance inside the same engine frame).
 */
final class AutofireTargetCoordinator {

    private final PredictReconcileCombatPlugin plugin;

    private static final class MountPick {
        long targetWireId; // 0 until first set — never a legal wire id, so the first set counts as an apply
        long lastSeenTick;
    }

    /** Own-ship mount ordinal → the server's held pick (absent = no live pick, vanilla rules). */
    private final Map<Integer, MountPick> pickByMount = new HashMap<Integer, MountPick>();

    /** Latest applied snapshot tick (the expiry clock) — fed once per drain, before any intake. */
    private long nowTick = Long.MIN_VALUE;

    // B3 meter fields (cumulative like the sibling eventReplay counters; emitted on the
    // predict_event_rebase_ meter line — appended-fields law).
    int applies;      // predict_event_rebase_autofire_target_applies (pick-SET transitions accepted)
    int skipUnknown;  // ..._skip_unknown (unresolvable targetWireId — local pick kept)
    int staleExpired; // ..._stale_expired (keepalive silence / clock anomaly — reverted to local)

    /** Per-ship-identity mount-ordinal cache (fresh hulls are fresh objects; dropped with the picks). */
    private ShipAPI ordinalShip;
    private IdentityHashMap<WeaponAPI, Integer> ordinalByWeapon;

    AutofireTargetCoordinator(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Expiry clock feed — first line of the edge-event lane's {@code drainEdgeEvents} ({@code EdgeEventLane}), so intake below
     *  always sees a live clock (picks only arrive via that drain). */
    void onSnapshotTick(long snapshotTick) {
        nowTick = snapshotTick;
    }

    /**
     * Intake one OWN-ship {@code AUTOFIRE_TARGET} event (the route site already discriminated
     * {@code entityId == session.shipId()}; other seats' lanes are ignored there).
     * {@link ClientGameplayEdgeEvent#TARGET_NONE} clears the mount's pick (revert to local);
     * an unresolvable id is skipped fail-closed; a resolvable one stores/refreshes the pick.
     */
    void onAutofireTarget(ClientGameplayEdgeEvent event) {
        int mount = event.mountIndex();
        long id = event.targetWireId();
        if (id == ClientGameplayEdgeEvent.TARGET_NONE) {
            pickByMount.remove(Integer.valueOf(mount));
            return;
        }
        PredictReconcileCombatPlugin.RemoteEntity re =
                plugin.remoteEntities.get(Long.valueOf(id));
        if (re == null || re.ship == null) {
            skipUnknown++; // fail-closed: keep the local pick; the ~60-tick keepalive re-offers
            return;
        }
        MountPick pick = pickByMount.get(Integer.valueOf(mount));
        if (pick == null) {
            pick = new MountPick();
            pickByMount.put(Integer.valueOf(mount), pick);
        }
        if (pick.targetWireId != id) {
            applies++;
        }
        pick.targetWireId = id;
        pick.lastSeenTick = event.simTick();
    }

    /**
     * The wrapper's per-advance query: the server-picked target SHIP for {@code (ship, weapon)},
     * or {@code null} = no live pick (wrapper passes through to the vanilla delegate verbatim).
     * Non-own ships, dead sessions, unknown mounts, expired picks and unresolvable ids all
     * return {@code null} — every failure mode lands on vanilla behavior.
     */
    ShipAPI livePickFor(ShipAPI ship, WeaponAPI weapon) {
        if (pickByMount.isEmpty() || ship == null || ship != plugin.ownShip
                || plugin.session == null) {
            return null;
        }
        Integer mount = mountOrdinal(ship, weapon);
        if (mount == null) {
            return null;
        }
        MountPick pick = pickByMount.get(mount);
        if (pick == null) {
            return null;
        }
        if (nowTick < pick.lastSeenTick || nowTick - pick.lastSeenTick > staleTicks()) {
            pickByMount.remove(mount);
            staleExpired++;
            return null;
        }
        PredictReconcileCombatPlugin.RemoteEntity re =
                plugin.remoteEntities.get(Long.valueOf(pick.targetWireId));
        return re == null ? null : re.ship; // resolve per apply — never cached (repoint-safe)
    }

    /**
     * The own-burst-gate row's read door, called ONCE per frame from
     * the wrapper's {@code advance()}: the OWN ship's mount ordinal of {@code weapon} under
     * {@link #livePickFor}'s own + live-session gate, or -1 = not metered (non-own: one identity compare).
     */
    int ownMountOrdinal(ShipAPI ship, WeaponAPI weapon) {
        if (ship == null || ship != plugin.ownShip || plugin.session == null) {
            return -1;
        }
        Integer mount = mountOrdinal(ship, weapon);
        return mount == null ? -1 : mount.intValue();
    }

    /** The same row's meter (the fire lanes' {@code burstPhase}) and clock ({@code lastSnapshotSimTick}). */
    OwnBurstPhaseMeter burstMeter() {
        return plugin.fireLanes.burstPhase;
    }

    long snapshotTick() {
        return plugin.lastSnapshotSimTick;
    }

    /**
     * Drop every held pick + the ordinal cache. Two seams call this: {@code finishMatchReset}
     * (round-local state) and {@code respawnOwn} (fresh hull at the SAME wire id — the
     * {@code OwnFireLockLaw} sub-4 hazard; the fresh hull's weapons re-enter the pick hook, so
     * wrappers re-mint free). Meters deliberately survive (cumulative, sibling-counter law).
     */
    void dropPicks() {
        pickByMount.clear();
        ordinalShip = null;
        ordinalByWeapon = null;
    }

    /** Keepalive-silence bound: 2.5 × the WELCOME-seeded tick rate (~150 ticks @60) — the
     *  {@code DischargeReconcileLaw} rate idiom; tolerates 1–2 dropped keepalives. */
    private static long staleTicks() {
        return Math.max(1L, Math.round(2.5 * ServerTickRate.ticksPerSecond()));
    }

    /** Mount ordinal by {@code getAllWeapons()} index, cached per ship IDENTITY (the server
     *  lane's IdentityHashMap idiom) — unknown weapon (module/fighter path) = {@code null}. */
    private Integer mountOrdinal(ShipAPI ship, WeaponAPI weapon) {
        if (ordinalShip != ship || ordinalByWeapon == null) {
            IdentityHashMap<WeaponAPI, Integer> fresh =
                    new IdentityHashMap<WeaponAPI, Integer>();
            List<WeaponAPI> all = ship.getAllWeapons();
            if (all != null) {
                for (int i = 0; i < all.size(); i++) {
                    fresh.put(all.get(i), Integer.valueOf(i));
                }
            }
            ordinalByWeapon = fresh;
            ordinalShip = ship;
        }
        return ordinalByWeapon.get(weapon);
    }
}
