package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The devastator-fuse both-sides MEASURE (meter lane: no spec mutation, no fix in
 * the measure). The 3rd owner observation is per-shell fuse-burst divergence between windows; the
 * recon pinned the diverging quantity to the CONSTRUCTOR-rolled shell lifetime
 * — effective range = {@code base·(1−0.75·Math.random())}
 * ({@code shotRangeVariance:0.75}, {@code devastator_shot.proj}), lifetime = range/moveSpeed —
 * with {@code ProximityFuseAI} fully deterministic downstream (fade-onset → {@code explode()} →
 * weaponless {@code DamagingExplosion}). The wire carries bearing only and the presenter re-enters
 * the same factory, so each JVM rolls independently BY CONSTRUCTION; this meter puts the
 * distribution on both windows' logs so the offline pair can prove it (and price the carry-cells
 * fuse-cell fix, which is NOT this batch).
 *
 * <p><b>What it does:</b> a passive per-frame scan filtered to {@link #SPEC_ID}. Per shell it
 * records role (own vs presenter-spawned), the pairing key (firing entityId, mount ordinal,
 * discharge tick, bearing ≈0.1° checksum), first/last-seen loc + engine {@code getElapsed()} +
 * first velocity, and the presenter catch-up {@code ageTicks}. On disappearance from
 * {@code getProjectiles()} it classifies {@code didDamage()} ⇒ {@code HIT} (excluded from the fuse
 * distribution — PD kills land here too) else {@code FUSE_BURST}, cross-checked against a
 * weaponless explosion ({@code getWeapon()==null} + {@code getExplosionSpecIfExplosion()!=null})
 * within {@value #XCHECK_RADIUS_SU} su of the last-seen point on the disappearance frame.
 * <b>Flight TIME (last-seen {@code getElapsed()}) is the primary diff quantity</b> — the rolled
 * lifetime is range/spec.moveSpeed, independent of actual speed, so time isolates the fuse RNG
 * from the known zero-inherited-velocity presenter speed residual; distance mixes both. Expected
 * signature: both windows ~uniform in [0.25·R, R]/v but per-shell UNCORRELATED. Offline pairing
 * (across the two windows' logs) keys on (entityId, mount, nearest dischargeTick, bearing);
 * removal is queue-deferred so ±1 frame tolerance applies.
 *
 * <p>Render-thread only (tick + tag both run inside {@code advance()}), fail-soft throughout,
 * per-role line caps (counters continue past them — the per-kind-cap lesson). Force-loaded at
 * application load ({@link #describe()}) per the classloader-B pre-warm pattern.
 */
final class FuseBurstMeter {

    /** The measured spec — the owner-observed diverging burst weapon. */
    static final String SPEC_ID = "devastator_shot";
    /** Per-ROLE per-shell line cap (counters keep counting past it). */
    private static final int LINE_MAX = 120;
    /** Weaponless-explosion cross-check radius around the shell's last-seen location (su). */
    private static final float XCHECK_RADIUS_SU = 55f;

    /** One tracked shell: pairing key + first/last-seen record (see class javadoc). */
    private static final class Shell {
        final DamagingProjectileAPI proj;
        final String role;        // own | presented | other
        final long entityId;      // pairing: firing entity (own shipId / GPEE entityId; -1 unknown)
        final int mountIndex;     // pairing: getAllWeapons() ordinal (-1 unresolved)
        final long dischargeTick; // pairing: own = snapTick at first sight; presented = GPEE simTick
        final float bearingDeg;   // pairing checksum: own = first-seen velocity heading; presented = wire bearing
        final long ageTicks;      // presenter catch-up depth at spawn (0 for own/other)
        final float firstElapsed;
        final float firstX, firstY, firstVx, firstVy;
        long lastFrame;
        long lastSnapTick;
        float lastElapsed;
        float lastX, lastY;

        Shell(DamagingProjectileAPI proj, String role, long entityId, int mountIndex,
                long dischargeTick, float bearingDeg, long ageTicks, float firstElapsed,
                float firstX, float firstY, float firstVx, float firstVy) {
            this.proj = proj;
            this.role = role;
            this.entityId = entityId;
            this.mountIndex = mountIndex;
            this.dischargeTick = dischargeTick;
            this.bearingDeg = bearingDeg;
            this.ageTicks = ageTicks;
            this.firstElapsed = firstElapsed;
            this.firstX = firstX;
            this.firstY = firstY;
            this.firstVx = firstVx;
            this.firstVy = firstVy;
        }
    }

    private final Map<DamagingProjectileAPI, Shell> tracked =
            new IdentityHashMap<DamagingProjectileAPI, Shell>();

    private int ownSeen;        // predict_fuse_meter_own_seen
    private int presentedSeen;  // predict_fuse_meter_presented_seen
    private int otherSeen;      // predict_fuse_meter_other_seen (untagged non-own source; expect 0 in 1v1)
    private int hits;           // predict_fuse_meter_hits (didDamage — excluded from the fuse distribution)
    private int bursts;         // predict_fuse_meter_bursts (the fuse population)
    private int burstXchecked;  // bursts with a weaponless explosion inside the radius on the vanish frame
    private int droppedAtReset; // tracked shells discarded at a round boundary (no outcome)
    private int ownLines, presentedLines, otherLines;
    // Burst flight-time aggregates per role for the summary line (distribution detail = per-shell lines).
    private float ownBurstMin = Float.NaN, ownBurstMax = Float.NaN, ownBurstSum;
    private int ownBursts;
    private float presBurstMin = Float.NaN, presBurstMax = Float.NaN, presBurstSum;
    private int presBursts;

    /** App-load force-touch (classloader-B pre-warm): loads this class + {@link Shell} so the
     *  first mid-combat use is never a class load. Returns the config string for the log line. */
    static String describe() {
        Shell warm = new Shell(null, "own", 0L, -1, 0L, Float.NaN, 0L, 0f, 0f, 0f, 0f, 0f);
        warm.lastFrame = 0L;
        return "specId=" + SPEC_ID + " lineMax=" + LINE_MAX + "/role xcheckRadius=" + XCHECK_RADIUS_SU
                + " (passive per-shell flight-time measure)";
    }

    /**
     * Register a presenter-spawned devastator at its spawn instant, carrying the wire pairing key
     * (GPEE entityId/mount/simTick/bearing) + the catch-up {@code ageTicks} — richer than anything
     * the scan could reconstruct. Non-devastator spawns fall through untagged (cheap spec check).
     */
    void tagPresented(Object spawned, ClientGameplayEdgeEvent event, long ageTicks,
            long frame, long snapTick) {
        try {
            if (!(spawned instanceof DamagingProjectileAPI)) {
                return;
            }
            DamagingProjectileAPI p = (DamagingProjectileAPI) spawned;
            if (!SPEC_ID.equals(p.getProjectileSpecId())) {
                return;
            }
            Vector2f loc = p.getLocation();
            Vector2f vel = p.getVelocity();
            Shell s = new Shell(p, "presented", event.entityId(), event.mountIndex(),
                    event.simTick(), event.flightBearingDeg(), ageTicks, p.getElapsed(),
                    loc != null ? loc.x : Float.NaN, loc != null ? loc.y : Float.NaN,
                    vel != null ? vel.x : Float.NaN, vel != null ? vel.y : Float.NaN);
            refreshLastSeen(s, p, frame, snapTick);
            tracked.put(p, s);
            presentedSeen++;
        } catch (Throwable ignore) {
            // meter lane: a tag failure must never break the presentation that already happened
        }
    }

    /**
     * The per-frame scan: first-sight own/other shells, refresh every tracked shell's last-seen
     * record, then close out shells that vanished from the live list (lastFrame != this frame).
     * {@code ownEntityId} = the session shipId (the own shell's pairing entity).
     */
    void tick(CombatEngineAPI engine, ShipAPI ownShip, long ownEntityId, long snapTick, long frame) {
        if (engine == null) {
            return;
        }
        try {
            List<DamagingProjectileAPI> live = engine.getProjectiles();
            for (DamagingProjectileAPI p : live) {
                if (!SPEC_ID.equals(p.getProjectileSpecId())) {
                    continue;
                }
                Shell s = tracked.get(p);
                if (s == null) {
                    boolean own = ownShip != null && p.getSource() == ownShip;
                    int mount = -1;
                    if (own) {
                        try {
                            WeaponAPI w = p.getWeapon();
                            if (w != null) {
                                mount = ownShip.getAllWeapons().indexOf(w);
                            }
                        } catch (Throwable ignore) {
                            mount = -1;
                        }
                    }
                    Vector2f loc = p.getLocation();
                    Vector2f vel = p.getVelocity();
                    float bearing = vel != null && (vel.x != 0f || vel.y != 0f)
                            ? (float) Math.toDegrees(Math.atan2(vel.y, vel.x)) : Float.NaN;
                    s = new Shell(p, own ? "own" : "other", own ? ownEntityId : -1L, mount,
                            snapTick, bearing, 0L, p.getElapsed(),
                            loc != null ? loc.x : Float.NaN, loc != null ? loc.y : Float.NaN,
                            vel != null ? vel.x : Float.NaN, vel != null ? vel.y : Float.NaN);
                    tracked.put(p, s);
                    if (own) {
                        ownSeen++;
                    } else {
                        otherSeen++;
                    }
                }
                refreshLastSeen(s, p, frame, snapTick);
            }
            if (!tracked.isEmpty()) {
                for (Iterator<Shell> it = tracked.values().iterator(); it.hasNext();) {
                    Shell s = it.next();
                    if (s.lastFrame == frame) {
                        continue;
                    }
                    it.remove();
                    closeShell(s, live);
                }
            }
        } catch (Throwable ignore) {
            // meter lane: never break the watch on a scan hiccup
        }
    }

    private static void refreshLastSeen(Shell s, DamagingProjectileAPI p, long frame, long snapTick) {
        s.lastFrame = frame;
        s.lastSnapTick = snapTick;
        try {
            s.lastElapsed = p.getElapsed();
            Vector2f loc = p.getLocation();
            if (loc != null) {
                s.lastX = loc.x;
                s.lastY = loc.y;
            }
        } catch (Throwable ignore) {
            // keep the previous frame's record
        }
    }

    /** Disappearance: classify + capped per-shell line. {@code live} = this frame's projectile
     *  list, scanned for the weaponless-explosion burst cross-check. */
    private void closeShell(Shell s, List<DamagingProjectileAPI> live) {
        boolean hit = false;
        try {
            hit = s.proj != null && s.proj.didDamage();
        } catch (Throwable ignore) {
            // classify as burst; the offline pair sees the flight time either way
        }
        String xcheck = "";
        if (hit) {
            hits++;
        } else {
            bursts++;
            boolean confirmed = false;
            try {
                for (DamagingProjectileAPI q : live) {
                    if (q.getWeapon() == null && q.getExplosionSpecIfExplosion() != null) {
                        Vector2f loc = q.getLocation();
                        if (loc != null) {
                            float dx = loc.x - s.lastX;
                            float dy = loc.y - s.lastY;
                            if (dx * dx + dy * dy <= XCHECK_RADIUS_SU * XCHECK_RADIUS_SU) {
                                confirmed = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
                // cross-check is optional corroboration only
            }
            if (confirmed) {
                burstXchecked++;
            }
            xcheck = " xburst=" + confirmed;
            if ("own".equals(s.role)) {
                ownBursts++;
                ownBurstSum += s.lastElapsed;
                if (Float.isNaN(ownBurstMin) || s.lastElapsed < ownBurstMin) ownBurstMin = s.lastElapsed;
                if (Float.isNaN(ownBurstMax) || s.lastElapsed > ownBurstMax) ownBurstMax = s.lastElapsed;
            } else if ("presented".equals(s.role)) {
                presBursts++;
                presBurstSum += s.lastElapsed;
                if (Float.isNaN(presBurstMin) || s.lastElapsed < presBurstMin) presBurstMin = s.lastElapsed;
                if (Float.isNaN(presBurstMax) || s.lastElapsed > presBurstMax) presBurstMax = s.lastElapsed;
            }
        }
        int n;
        if ("own".equals(s.role)) {
            n = ++ownLines;
        } else if ("presented".equals(s.role)) {
            n = ++presentedLines;
        } else {
            n = ++otherLines;
        }
        if (n <= LINE_MAX) {
            PilotWatchLog.info("predict fuse-meter " + s.role + " #" + n
                    + ": entity=" + s.entityId + " mount=" + s.mountIndex
                    + " declTick=" + s.dischargeTick + " bearing=" + s.bearingDeg
                    + " age=" + s.ageTicks + " flightSec=" + s.lastElapsed
                    + " firstElapsed=" + s.firstElapsed
                    + " first=" + s.firstX + "," + s.firstY
                    + " vel=" + s.firstVx + "," + s.firstVy
                    + " last=" + s.lastX + "," + s.lastY
                    + " lastTick=" + s.lastSnapTick
                    + " outcome=" + (hit ? "HIT" : "FUSE_BURST") + xcheck
                    + (n == LINE_MAX ? " [line cap reached; counters continue]" : ""));
        }
    }

    /** Round boundary (MRST): the rebuilt world clears the projectile list — drop tracked shells
     *  without an outcome rather than mis-classifying them all as bursts next frame. */
    void reset() {
        droppedAtReset += tracked.size();
        tracked.clear();
    }

    /** Summary counters (cadence + teardown). Silent until the first devastator is seen. */
    void logSummary(String where) {
        if (ownSeen + presentedSeen + otherSeen == 0) {
            return;
        }
        PilotWatchLog.info("predict fuse-meter summary [" + where + "]: own=" + ownSeen
                + " presented=" + presentedSeen + " other=" + otherSeen
                + " hits=" + hits + " bursts=" + bursts + " xburst=" + burstXchecked
                + " droppedAtReset=" + droppedAtReset + " inFlight=" + tracked.size()
                + " ownBurstFlightSec[min/mean/max]=" + fmtStats(ownBurstMin, ownBurstSum, ownBursts, ownBurstMax)
                + " presentedBurstFlightSec[min/mean/max]=" + fmtStats(presBurstMin, presBurstSum, presBursts, presBurstMax));
    }

    private static String fmtStats(float min, float sum, int count, float max) {
        if (count == 0) {
            return "n/a";
        }
        return min + "/" + (sum / count) + "/" + max + " (n=" + count + ")";
    }
}
