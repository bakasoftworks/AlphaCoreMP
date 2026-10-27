package cc.starsector.client;

import cc.starsector.client.net.ClientGameplayEdgeEvent;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpawnType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PRESENTATION seam of {@link PredictReconcileCombatPlugin} (the
 * trigger-2 extraction): the code that makes a server-authoritative fact VISIBLE in the local engine —
 * a foe discharge becomes a real local projectile + muzzle FX ({@link #presentFoeDischarge}), and a
 * server-decided kill becomes the engine's OWN death sequence ({@link #presentServerDeath}). Everything
 * here is presentation-only and fail-soft: authority stays with the server, and a local FX failure never
 * breaks the watch.
 *
 * <p>The PLUGIN keeps everything stateful about WHEN to present — shooter/entity resolution, one-shot
 * kill flags, axis-C cap clears ({@code capFoeDamage}/{@code RemoteEntity.capActive}), meters, and the
 * {@code reconcilePosition} pose snap. This class owns only the presentation counters (getters feed
 * the edge-event lane's {@code logEventReplayMeter} ({@code EdgeEventLane}, carved out of the plugin); the {@code predict_event_rebase_*} wire names are unchanged).
 * Render-thread only, like the plugin state it was extracted from.
 */
final class AuthoritativePresenter {

    /** Massive HE used to drive the engine's OWN death sequence on a server-authoritative kill. */
    private static final float SERVER_KILL_DAMAGE = 1_000_000f;

    /**
     * Vanilla PhaseAnchorScript's battle-global one-shot dive key.
     * Planted PERSISTENTLY at the first {@link #presentServerDeath} (and by the F4e dive lane at its
     * first presented dive — {@code PhaseDivePresentLane.beginDive}) so the LOCAL {@code phase_anchor}
     * hullmod can never emergency-dive a body on its own. Unfenced, the client-side listener absorbed
     * an authoritative blow (HP→1, emergency dive) and left an undying cloaked ghost hull — and, on
     * the OWN ship, a held retreat bit that evicted the refield hull.
     * The fence planted iff absent + finally-removed around the HE only — so the HE's OWN
     * death-blast secondaries landed NEXT frame on the ≈0-hull corpse with the key already gone, the
     * intercept re-latched {@code emergencyDive} on the corpse, and the cloak-activate sfx looped
     * forever at its position. Persistent is safe by
     * construction: a genuine dive is always server-decided and presented through the F4e lane as
     * DEPARTED — never through the local vanilla intercept — so the permanent plant can never
     * suppress a legitimate dive, and it mirrors vanilla's own permanent plant
     * (one dive per battle). Package-visible so the dive lane shares the
     * one key definition. The fighter twin stays unfenced (no fighter carries the hullmod).
     */
    static final String PHASE_ANCHOR_CAN_DIVE_KEY = "phaseAnchor_canDive";

    private final CombatEngineAPI engine;
    /** Per-counter log cap (the plugin's {@code EVENT_REPLAY_LOG_MAX}, passed at construction). */
    private final int logMax;
    /**
     * The plugin's god-mode mirror, handed in by
     * {@link #setGodMirror} right after construction (the 2-arg ctor stays for selftests); null =
     * no mirror. {@link #presentServerDeath} releases it FIRST — the kill blow is armor-subject and
     * a mirrored armor mult 0 would absorb it (a ghost body surviving its server death).
     */
    private GodMirrorLane godMirror;

    // Foe-fire presentation (presentFoeDischarge): spawned vs skipped foe discharges.
    private int foeFirePresented;      // predict_event_rebase_foe_fire_presented
    private int foeFirePresentSkipped; // predict_event_rebase_foe_fire_present_skipped
    // Discharges skipped because the mount resolved to a BEAM weapon —
    // structural, both roles (beams are OWNED by the beam relay lane; the projectile-only spawn
    // door casts the weapon spec to the projectile spec class and would THROW
    // inside the engine). Counted apart so graders can subtract the structural class.
    private int beamMountSkips;        // predict_event_rebase_beam_mount_skips
    // Meter (b) — foe discharges skipped for want of a live
    // local body (9 bare rows for entity 511, no counter), total + a BOUNDED per-entity
    // count (primitive slots, no boxing: wire entity ids are long; the 65th distinct id counts into
    // Other). Exported on the B3 eventReplay-meter line.
    private static final int FOE_FIRE_SKIP_NO_BODY_KEYS = 64;
    private int foeFireSkipNoBody;      // predict_foe_fire_skip_no_body
    private int foeFireSkipNoBodyOther; // the +other:<n> cell of predict_foe_fire_skip_no_body_top
    private int foeFireSkipNoBodyUsed;
    private final long[] foeFireSkipNoBodyIds = new long[FOE_FIRE_SKIP_NO_BODY_KEYS];
    private final int[] foeFireSkipNoBodyCounts = new int[FOE_FIRE_SKIP_NO_BODY_KEYS];
    // Catch-up: presentations advanced downrange because the event aged
    // behind the applied snapshot anchor (ageTicks > 0 at present time).
    private int foeFireCatchupAdvanced; // predict_event_rebase_foe_fire_catchup_advanced
    // Ammo decrement-on-present (see decrementPresentedAmmo).
    private int foeFireAmmoDecremented; // predict_event_rebase_foe_fire_ammo_decremented
    private int foeFireAmmoRearmed;     // predict_event_rebase_foe_fire_ammo_rearmed (dry mount presented again)
    private int foeFireLoadedCycles;    // predict_event_rebase_foe_fire_loaded_cycles (single-barrel dim+fade replays)
    // Rig instrument: samples each replayed cycle's brightness/cooldown/ammo → verdict rows.
    // Sound+flash (presentFoeFireFx): FX pieces landed vs skipped per presentation.
    private int foeFireFxFlash;        // predict_event_rebase_foe_fire_fx_flash
    private int foeFireFxSound;        // predict_event_rebase_foe_fire_fx_sound
    private int foeFireFxSkipped;      // predict_event_rebase_foe_fire_fx_skipped
    // Composable spawns fly the VANILLA composition —
    // ray angle + full shooter velocity, composed by the engine's own factory. bearing_applied
    // now counts ONLY the ray classes' wire-bearing spawns + the
    // discriminator fail-soft; fallbacks split by cause (NaN wire bearing / engine returned
    // null). composed + applied + fallback_nan_wire + fallback_spawn_null == presented.
    private int foeFireComposed;                 // predict_event_rebase_foe_fire_composed
    private int foeFireBearingApplied;           // predict_event_rebase_foe_fire_bearing_applied
    private int foeFireBearingFallbackNanWire;   // predict_event_rebase_foe_fire_bearing_fallback_nan_wire
    private int foeFireBearingFallbackSpawnNull; // predict_event_rebase_foe_fire_bearing_fallback_spawn_null
    // cross-check: max |composed flight direction − wire flightBearingDeg| over composed
    // presents (wrapped to ±180). Zero-spread weapons should sit near 0 (residual = the m2-class
    // shooter-velocity estimate error); spread-class weapons legitimately raise it by their
    // per-shot jitter arc — interpretation lives in the evidence file, the meter just reports.
    private float foeFireComposeXcheckMaxAbsDeg; // predict_event_rebase_foe_fire_compose_xcheck_max_abs_deg
    // The launch source of each foe present (own-adopt pays
    // none, frozen). from_wire = launched AT the wire aimFacingDeg (a missile's server launch
    // facing, else the mount angle); local = a NaN wire aim fell back to weapon.getCurrAngle().
    // Catches the NaN-fallback class only — a finite wire aim reads from_wire on ANY server.
    private int foeFireLaunchFromWire;           // predict_event_rebase_foe_fire_launch_from_wire
    private int foeFireLaunchLocal;              // predict_event_rebase_foe_fire_launch_local
    // GPEE v6: composed spawns whose velocity was then overridden to the wire's
    // full server vector |v0|·û(bearing) — speed_applied ⊆ composed. The max-|Δ| meter is the
    // magnitude twin of the direction xcheck: |local composed speed − wire |v0|| at present
    // time, i.e. the ch-B shooter-velocity-estimate error, now measured per present.
    private int foeFireSpeedApplied;           // predict_event_rebase_foe_fire_speed_applied
    private float foeFireSpeedXcheckMaxAbsUps; // predict_event_rebase_foe_fire_speed_xcheck_max_abs_ups
    // Foe rays spawned WITH the wire-speed inherited term (wireSpawnVelocity non-null AND the spawn
    // took) — the kit's own meter of the ray wire-speed fix; bar: <= bearing_applied, equal on a
    // current-schema wire where every wire-bearing present is a ray with a usable birth speed.
    //
    private int foeFireRayWireSpeed;           // predict_event_rebase_foe_fire_ray_wire_speed
    // GPEE v8 (shot-fidelity-budget): presents whose spawn point was overridden to the
    // wire's server-true muzzle — NOT class-gated (composed, wire-bearing AND ray arms all
    // take it; the spawn point is factory-input on every arm). xcheck = dist(local fire
    // point, wire muzzle) pre-override — the muzzle re-derivation error the lagged shooter
    // replica injected until v8, now measured per present.
    private int foeFireMuzzleApplied;         // predict_event_rebase_foe_fire_muzzle_applied
    private float foeFireMuzzleXcheckMaxAbsU; // predict_event_rebase_foe_fire_muzzle_xcheck_max_abs_u
    // OWN-role adopted presentations share the spawn core below
    // but keep their own capped log lines — the foe counters above stay foe-only (frozen rule:
    // "foe counters/log untouched"; the plugin owns adopt_count/adopt_skip_unresolved off the
    // return value, so the presenter adds no own-role counters of its own).
    private int ownAdoptLogLines;
    // guided-missile seeker handoff: applied vs no-candidate, plus the
    // one-shot-per-AI-class premise log ("the factory DID attach a seeker") for the re-watch.
    private int seekerHandoffApplied;
    private int seekerHandoffNoTarget;
    private int seekerHandoffLogLines;
    private final Set<String> seekerAiClassLogged = new HashSet<String>();

    /**
     * Dedicated raisable cap for the
     * two spec-named per-shot forensic families ONLY (the plugin's eventReplay intake line + this
     * presenter's rows, which carry {@code foe-fire present}): a test run's cap-80 truncated the
     * AFTER picture ~t2800, so the edge-shot re-test was not ray-cross-greppable. Watches raise it
     * via {@code -Dcc.predict.shotLogMax}; unset/malformed/nonpositive fail open to the shipped
     * {@link PredictReconcileCombatPlugin#EVENT_REPLAY_LOG_MAX} (the {@code cc.watch.markerKey}
     * idiom) — shipped behavior byte-identical. The AUTONOMOUS/weaponless lines deliberately STAY
     * on the shared literal (mint a dedicated cap, never move the
     * shared one). Lives HERE, not in the plugin — PRCP sits at its anchor budget.
     *
     */
    static final int SHOT_LOG_MAX = resolveShotLogMax();


    /** The LIVE cap both families compare against: the boot-resolved {@link #SHOT_LOG_MAX}, or the
     *  debug-watch lift ({@link PilotWatchLog#debugWatchShotLogLift}) when larger (an explicit {@code -Dcc.predict.shotLogMax} above it still wins). */
    static int shotLogMax() {
        int lifted = PilotWatchLog.debugWatchShotLogLift();
        return lifted > SHOT_LOG_MAX ? lifted : SHOT_LOG_MAX;
    }

    private static int resolveShotLogMax() {
        String raw = System.getProperty("cc.predict.shotLogMax");
        if (raw == null || raw.trim().isEmpty()) {
            return PredictReconcileCombatPlugin.EVENT_REPLAY_LOG_MAX;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : PredictReconcileCombatPlugin.EVENT_REPLAY_LOG_MAX;
        } catch (NumberFormatException e) {
            return PredictReconcileCombatPlugin.EVENT_REPLAY_LOG_MAX;
        }
    }

    /** The per-counter cap the presenter's rows compare against: the ctor value (a selftest's
     *  explicit small cap stays exact), overridden ONLY by the debug-watch lift. */
    private int effectiveLogMax() {
        int lifted = PilotWatchLog.debugWatchShotLogLift();
        return lifted > logMax ? lifted : logMax;
    }
    AuthoritativePresenter(CombatEngineAPI engine, int logMax) {
        this.engine = engine;
        this.logMax = logMax;
    }

    void setGodMirror(GodMirrorLane godMirror) {
        this.godMirror = godMirror;
    }

    int foeFirePresented() {
        return foeFirePresented;
    }

    int foeFirePresentSkipped() {
        return foeFirePresentSkipped;
    }

    int beamMountSkips() {
        return beamMountSkips;
    }

    /** Meter (b) record: total + the bounded per-id slot.
     *  A NEW slot (first skip per id, ≤ the slot cap) logs
     *  ONE row with the event's sim tick, so the bar reads without a script. */
    private void noteFoeFireSkipNoBody(long entityId, long simTick) {
        foeFireSkipNoBody++;
        for (int i = 0; i < foeFireSkipNoBodyUsed; i++) {
            if (foeFireSkipNoBodyIds[i] == entityId) {
                foeFireSkipNoBodyCounts[i]++;
                return;
            }
        }
        if (foeFireSkipNoBodyUsed < FOE_FIRE_SKIP_NO_BODY_KEYS) {
            foeFireSkipNoBodyIds[foeFireSkipNoBodyUsed] = entityId;
            foeFireSkipNoBodyCounts[foeFireSkipNoBodyUsed] = 1;
            foeFireSkipNoBodyUsed++;
            PilotWatchLog.info("predict foe-fire skip no-body: entity=" + entityId + " tick=" + simTick
                    + " (first skip)");
        } else {
            foeFireSkipNoBodyOther++;
        }
    }

    int foeFireSkipNoBody() {
        return foeFireSkipNoBody;
    }

    /** The top-5 {@code id:n} cells (count desc, first-seen
     *  order on ties) + {@code +other:<n>} when nonzero, e.g. {@code [511:9,331:4]}. Built ONLY at
     *  meter time (the one allocation site of meter (b)). */
    String foeFireSkipNoBodyTop() {
        StringBuilder sb = new StringBuilder("[");
        boolean[] taken = new boolean[foeFireSkipNoBodyUsed];
        for (int k = 0; k < 5; k++) {
            int best = -1;
            for (int i = 0; i < foeFireSkipNoBodyUsed; i++) {
                if (!taken[i] && (best < 0 || foeFireSkipNoBodyCounts[i] > foeFireSkipNoBodyCounts[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            taken[best] = true;
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(foeFireSkipNoBodyIds[best]).append(':').append(foeFireSkipNoBodyCounts[best]);
        }
        if (foeFireSkipNoBodyOther > 0) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("+other:").append(foeFireSkipNoBodyOther);
        }
        return sb.append(']').toString();
    }

    int foeFireFxFlash() {
        return foeFireFxFlash;
    }

    int foeFireFxSound() {
        return foeFireFxSound;
    }

    int foeFireFxSkipped() {
        return foeFireFxSkipped;
    }

    int foeFireCatchupAdvanced() {
        return foeFireCatchupAdvanced;
    }

    int foeFireAmmoDecremented() {
        return foeFireAmmoDecremented;
    }

    int foeFireAmmoRearmed() {
        return foeFireAmmoRearmed;
    }

    int foeFireLoadedCycles() {
        return foeFireLoadedCycles;
    }

    /**
     * The presented shot replays the ENGINE's own loaded-
     * missile cycle on the replica mount through public API writes (client-side derivation, no
     * wire change; {@code [[prefer-client-side]]}, {@code [[prefer-engine-native-seams]]}).
     * {@code CombatEngine.spawnProjectile} mutates NOTHING on the weapon;
     * vanilla's per-shot cycle lives in the weapon class:
     *
     * <ul>
     * <li>SHOT: the fired barrel's loaded-sprite brightness is written 0;
     *     ammo is deducted by the fire-state machine.</li>
     * <li>RELOAD: every render frame walks the barrels in the lit window
     *     {@code [nextBarrel, nextBarrel+min(ammo,barrels))}: cooldown state IDLE ⇒ brightness 1;
     *     state COOLDOWN and brightness &lt; 1 ⇒ once elapsed ≥ refire − fade the brightness
     *     ramps {@code 1 − (refire − elapsed)/fade} with {@code fade = min(refireDelay, 0.5 s)}
     * — the vanilla look: "fades back in when the next bomb is loaded".</li>
     * <li>DRY: ammo 0 skips the whole loaded-sprite render — "stays gone".</li>
     * </ul>
     * The barrel brightness is a private inner-class field (no API, no B-legal write); the two
     * API writes that reach the same state are {@code setAmmo(n)} — dims every barrel index ≥ n
     * — and {@code setRemainingCooldownTo(t)} — forces the cooldown tracker
     * into COOLDOWN with elapsed = refire − t, which
     * the replica's own weapon advance then counts down (hold-fire only blocks
     * ship-issued fire, never the weapon advance). So for a single-barrel loaded-missile mount
     * (the Flash's {@code phasecl_bomber}: 1 barrel, 6 ammo, refire 1.5 s) the exact vanilla
     * picture is {@code setAmmo(0)} (engine dims barrel 0) → {@code setAmmo(ammo−1)} (count
     * restored; dims nothing below the count) → {@code setRemainingCooldownTo(refire)} (engine
     * fades the sprite back in over the last 0.5 s of the 1.5 s reload; at the 6th shot the count
     * hits 0 and the sprite stays gone). Multi-barrel racks keep the plain decrement: the replica's
     * barrel index never advances (it never fires), so the lit window is always
     * {@code [0, ammo)} and {@code setAmmo(ammo−1)} darkens the top index — one missile vanishes
     * per shot, as vanilla, just not the fired slot; a pod with ammo ≥ barrels shows no per-shot
     * fade (residue). The decrement alone only ever dimmed at dry —
     * "doesn't go away until they are dry". A present on a DRY mount means the
     * server re-armed it unseen (carrier rearm): re-arm to max and spend one (counted). Own-adopt
     * spawns are excluded (own ammo = the server-mirrored own-lock scalar,
     * {@code FireLaneCoordinator}). Fail-soft: any API throw leaves the mount untouched.
     */
    private void decrementPresentedAmmo(WeaponAPI weapon, ShipAPI shooter, long tick) {
        try {
            if (!weapon.usesAmmo()) {
                return;
            }
            int ammo = weapon.getAmmo();
            int next;
            if (ammo <= 0) {
                next = Math.max(0, weapon.getMaxAmmo() - 1);
                foeFireAmmoRearmed++;
            } else {
                next = ammo - 1;
            }
            boolean singleLoadedBarrel = loadedMissileBarrels(weapon) == 1;
            if (singleLoadedBarrel) {
                weapon.setAmmo(0); // engine's own per-shot dim of barrel 0
            }
            weapon.setAmmo(next);
            if (singleLoadedBarrel && next > 0) {
                // engine's own fade-in over the last min(refire, 0.5 s) of the reload
                weapon.setRemainingCooldownTo(weapon.getCooldown());
                foeFireLoadedCycles++;
            }
            foeFireAmmoDecremented++;
        } catch (Throwable t) {
            // fail-soft: an unreadable/unsettable mount keeps its engine default
        }
    }


    /**
     * The engine's loaded-missile barrel count for a RENDER_LOADED_MISSILES mount
     * (hardpoint ⇒ hardpoint offsets, turret ⇒ turret offsets,
     * HIDDEN ⇒ the hardpoint offset list — the Flash's centerline slot is HIDDEN); 0 when the
     * spec does not render loaded missiles (the hint is not API-readable, so the MISSILE type is
     * the gate: every vanilla missile launcher renders its loaded missiles, and a non-rendering
     * MISSILE mount merely pays a cooldown write that draws nothing).
     */
    private static int loadedMissileBarrels(WeaponAPI weapon) {
        WeaponSpecAPI spec = weapon.getSpec();
        WeaponSlotAPI slot = weapon.getSlot();
        if (spec == null || slot == null || weapon.getType() != WeaponAPI.WeaponType.MISSILE) {
            return 0;
        }
        List<Vector2f> offsets = slot.isTurret() ? spec.getTurretFireOffsets()
                : spec.getHardpointFireOffsets(); // hardpoint AND hidden
        return offsets == null ? 0 : offsets.size();
    }

    int foeFireComposed() {
        return foeFireComposed;
    }

    float foeFireComposeXcheckMaxAbsDeg() {
        return foeFireComposeXcheckMaxAbsDeg;
    }

    int foeFireLaunchFromWire() {
        return foeFireLaunchFromWire;
    }

    int foeFireLaunchLocal() {
        return foeFireLaunchLocal;
    }

    int foeFireSpeedApplied() {
        return foeFireSpeedApplied;
    }

    float foeFireSpeedXcheckMaxAbsUps() {
        return foeFireSpeedXcheckMaxAbsUps;
    }

    int foeFireRayWireSpeed() {
        return foeFireRayWireSpeed;
    }

    int foeFireMuzzleApplied() {
        return foeFireMuzzleApplied;
    }

    float foeFireMuzzleXcheckMaxAbsU() {
        return foeFireMuzzleXcheckMaxAbsU;
    }

    int foeFireBearingApplied() {
        return foeFireBearingApplied;
    }

    int foeFireBearingFallbackNanWire() {
        return foeFireBearingFallbackNanWire;
    }

    int foeFireBearingFallbackSpawnNull() {
        return foeFireBearingFallbackSpawnNull;
    }

    /**
     * FOE-FIRE PRESENTATION: spawn the foe's authoritative discharge in the LOCAL
     * engine so the player SEES (and can dodge) the projectile whose damage arrives via the hull mirror.
     * Faithful
     * spawn: the engine's own {@link CombatEngineAPI#spawnProjectile} from the event's
     * mount + authoritative aim — the REAL projectile (spec damage, collision, despawn), no FX fake.
     * The muzzle point is the LOCAL foe body's fire point (the projectile stays visually attached to
     * the reconciled ship). Composable spawn types (BALLISTIC/PLASMA/MISSILE) spawn at the fired RAY
     * ({@code aimFacingDeg}) with the shooter body's full velocity as inherited velocity — the
     * engine factory's own vanilla composition {@code shooterVel + û(ray)·specSpeed}
     * (the wire's {@code flightBearingDeg} is the composition cross-check). That wire aim IS
     * the launch facing (a missile's server projectile facing incl. its barrel offset,
     * else the mount angle) — the spawn adds no barrel offset of its own. The MovingRay
     * classes a post-spawn velocity rebase can never steer keep the wire-bearing spawn
     * so the factory flies the ray exactly down the server
     * bolt's line, at the wire's birth speed through the factory's own inherited-velocity argument
     * ({@link #wireSpawnVelocity}; zero inherit when that speed is unusable); a NaN wire bearing falls back to the server's weapon angle at discharge
     * ({@code aimFacingDeg}; NaN in turn → local weapon angle) + shooter velocity.
     * The {@code shooter} is the plugin-resolved local body for the event's entity (1v1 foe or
     * bots-shape registry entry); the null/alive SKIP semantics live HERE. No double-spawn:
     * {@code InertShipAI} alone did NOT stop local fire — the engine leaves weapon-group AUTOFIRE
     * plugins live on AI ships (seen as a local-only Hellbore shot), so the
     * plugin additionally holds the engine's own fire gate per frame on every replay-driven body
     * ({@code setHoldFireOneFrame}); this spawn path bypasses the weapon fire cycle entirely and is
     * unaffected. The own-fire scan filters {@code getSource() == ownShip}, so presented spawns
     * cannot pollute the meter. Local damage from the spawned projectile is PREDICTION:
     * the per-frame own-hull mirror plus the plugin's {@code OwnDamageCap} keep the server
     * authoritative over hull and death. Fail-soft per event: any unresolved piece counts +
     * capped-logs a SKIP, never a fake. Returns the spawned projectile ({@code null} on SKIP) so the
     * plugin can mark it presenter-spawned — the autonomous-discharge meter's exclusion set.
     * {@code ageTicks} = the behind depth {@code T_s − T} at present time; a positive age
     * advances the spawned projectile downrange by {@code age·dt} along its composed velocity
     * (catch-up, clamped at 60 ticks).
     */
    CombatEntityAPI presentFoeDischarge(ClientGameplayEdgeEvent event, ShipAPI shooter, long ageTicks) {
        return presentDischarge(event, shooter, ageTicks, false);
    }

    /**
     * ADOPT a server_only OWN discharge:
     * the identical faithful spawn core as {@link #presentFoeDischarge} (vanilla-inherit composed
     * spawn / ray-class wire-bearing spawn, catch-up advance, muzzle FX) with {@code shooter} = the
     * own ship — save that an adopted ray keeps ZERO inherit (the wire-speed term is foe-only). The PLUGIN consumes the return: non-null ⇒ {@code adopt_count} + the
     * {@code presentedOwnProj} exclusion set (so the scan never re-counts or — critically —
     * re-DECLARES the server's own shot back up the PDCL lane); null ⇒
     * {@code adopt_skip_unresolved}. Foe counters/logs untouched.
     */
    CombatEntityAPI presentOwnAdoptedDischarge(ClientGameplayEdgeEvent event, ShipAPI shooter, long ageTicks) {
        return presentDischarge(event, shooter, ageTicks, true);
    }

    /** The shared role-parameterized spawn core ({@code ownAdopt} routes counters + log labels). */
    private CombatEntityAPI presentDischarge(ClientGameplayEdgeEvent event, ShipAPI shooter,
            long ageTicks, boolean ownAdopt) {
        String skip;
        try {
            if (engine == null || shooter == null || !shooter.isAlive()) {
                skip = "no live local body for entity " + event.entityId();
                if (!ownAdopt) {
                    noteFoeFireSkipNoBody(event.entityId(), event.simTick()); // Meter (b)
                }
            } else {
                List<WeaponAPI> weapons = shooter.getAllWeapons();
                // mountIndex is the server's ShipAPI.getAllWeapons() ordinal (WeaponMountView contract);
                // both engines load the same variant, so the same index resolves the same mount.
                WeaponAPI weapon = weapons != null && event.mountIndex() < weapons.size()
                        ? weapons.get(event.mountIndex()) : null;
                if (weapon == null) {
                    skip = "mount " + event.mountIndex() + " unresolved (mounts="
                            + (weapons == null ? "null" : Integer.toString(weapons.size())) + ")";
                } else if (weapon.isBeam()) {
                    // A beam mount through the projectile door — spawn would
                    // CCE; beam presentation is
                    // OWNED by the beam relay lane, so this discharge is a mismatch upstream.
                    beamMountSkips++;
                    skip = "beam mount " + event.mountIndex() + " (" + weapon.getId()
                            + ") — beam relay lane owns beams";
                } else {
                    boolean aimUnbound = Float.isNaN(event.aimFacingDeg());
                    float aim = aimUnbound ? weapon.getCurrAngle() : event.aimFacingDeg();
                    // D-BARREL = the FX fire
                    // point on the lagging local sprite only (nearest local fire point, BarrelMatchLaw;
                    // −1 ⇒ fire point 0); the launch facing is the wire's, no barrel offset added.
                    int barrelRaw = inferBarrelRaw(weapon, event);
                    int barrel = Math.max(0, barrelRaw);
                    Vector2f point = weapon.getFirePoint(barrel);
                    if (point == null) {
                        point = weapon.getLocation();
                    }
                    // GPEE v8 spawn-truth: a usable wire muzzle
                    // replaces the LOCAL fire point as the spawn point on EVERY arm below
                    // (composed, wire-bearing, ray — the point is factory input on all three,
                    // so no class gate): the local point re-derives the muzzle from a lagged
                    // shooter replica, the wire pair is the position the server's projectile
                    // was actually born at. NaN/implausible pair ⇒ spawnPoint == point, the
                    // pre-v8 path byte-identical. Muzzle FX below deliberately KEEPS the local
                    // point (the flash is the weapon's, not the bolt's).
                    boolean muzzleUsed = event.muzzleUsable();
                    Vector2f spawnPoint = muzzleUsed
                            ? new Vector2f(event.muzzleX(), event.muzzleY()) : point;
                    float muzzleXcheckU = muzzleUsed && point != null
                            ? (float) Math.hypot(point.x - event.muzzleX(),
                                    point.y - event.muzzleY())
                            : Float.NaN;
                    // The VANILLA-INHERIT spawn (vehicle (ii),
                    // zero-wire local composition; law measured headless by measure-van-inherit.ps1,
                    // verdict VANILLA_FULL_INHERIT_CONFIRMED): vanilla flies every composable spawn
                    // at v0 = shooterVel(FULL) + û(launchRay)·specSpeed, facing the RAY — and the
                    // engine's factory performs exactly that composition from the spawn-angle +
                    // inheritedVelocity params (public overload ≡ the live fire path;
                    // facing = spawn angle in every composable
                    // factory). So composable types (BALLISTIC/PLASMA/MISSILE — see
                    // composesInheritedVelocity) spawn AT the ray (aim) WITH the local shooter
                    // body's velocity: flight magnitude+direction, facing, and thrust axis are all
                    // factory-native vanilla. The velocity source is the wire-reconciled local body
                    // at present time (≤ age_max≈12 ticks after fire — an inert ballistic's server
                    // velocity doesn't change over that window, and the catch-up advance below now
                    // extrapolates the composed vector: the "heals for free"). The wire's
                    // flightBearingDeg — formerly the spawn angle with ZERO inherit, i.e.
                    // spec-speed flight = the hit/miss magnitude desync — becomes the
                    // composition CROSS-CHECK meter. Known residue: a spread-class composable
                    // weapon composes from the un-jittered ray (the wire carries no per-shot jitter
                    // angle), so its fan collimates toward the aim line — the jitter residue
                    // class, rider if felt; the defect family (hammer/reaper rockets) has zero
                    // spread. Ray classes (BEAM/BALLISTIC_AS_BEAM → MovingRay: velocity
                    // rayExtender-internal, (0,0) at the spawn instant, setFacing does not steer)
                    // KEEP the wire-bearing spawn — a foe ray's inherited
                    // argument is the wire-speed term (wireSpawnVelocity), zero for own-adopt and an
                    // unusable wire speed — the factory
                    // flies the ray down the wire bearing, which is the server bolt's true
                    // jittered line ONLY since the server birth_facing tier (before it
                    // the wire carried the un-jittered mount ray for exactly this class and the
                    // victim's screen flew collimated bolts); a discriminator
                    // read failure lands there too (fail toward the proven path). NaN wire bearing
                    // (beam/unbound/legacy frame) keeps the mount-ray aim spawn + shooter velocity
                    // — counted nan_wire, never faked. Muzzle FX rides the wire aim (the launch
                    // facing) at the local fire point (the flash is the weapon's).
                    float wireBearingDeg = event.flightBearingDeg();
                    boolean wireFinite = Float.isFinite(wireBearingDeg);
                    boolean composed = wireFinite && composesInheritedVelocity(weapon);
                    CombatEntityAPI spawned;
                    Vector2f rayInherit = null; // non-null = a foe ray took the wire-speed term
                    float raySpecUps = Float.NaN;
                    if (composed) {
                        Vector2f shipVel = shooter.getVelocity();
                        // Defensive copy — never hand the engine a live alias of the ship's
                        // velocity vector; a null read composes as zero (fail-soft).
                        Vector2f inherited = shipVel == null ? new Vector2f()
                                : new Vector2f(shipVel.x, shipVel.y);
                        spawned = engine.spawnProjectile(shooter, weapon, weapon.getId(),
                                spawnPoint, aim, inherited);
                    } else if (wireFinite) {
                        // A FOE ray flies at the wire's birth
                        // speed — the factory's inherited-velocity argument is the lever the engine's
                        // ray honours (wireSpawnVelocity); a null term (legacy NaN speed, a non-ray
                        // spec, a failed read) and every own-adopt keep the proven zero-inherit spawn.
                        // Spawn-time only: no hold, no extra frame.
                        if (!ownAdopt) {
                            raySpecUps = raySpecSpeed(weapon, shooter);
                            rayInherit = wireSpawnVelocity(wireBearingDeg, event.birthSpeed(), raySpecUps);
                        }
                        spawned = engine.spawnProjectile(shooter, weapon, weapon.getId(), spawnPoint,
                                wireBearingDeg, rayInherit != null ? rayInherit : new Vector2f());
                    } else {
                        spawned = engine.spawnProjectile(shooter, weapon, weapon.getId(),
                                spawnPoint, aim, shooter.getVelocity());
                    }
                    boolean muzzleApplied = muzzleUsed && spawned != null;
                    if (!ownAdopt && spawned != null) {
                        if (aimUnbound) foeFireLaunchLocal++; else foeFireLaunchFromWire++;
                    }
                    String bearingCause;
                    if (!wireFinite) {
                        bearingCause = "nan_wire";
                    } else if (spawned == null) {
                        bearingCause = "spawn_null";
                    } else {
                        bearingCause = null;
                    }
                    // cross-check: composed flight direction vs the wire's true line, wrapped
                    // to ±180 (NaN when unreadable). Foe counters only; the
                    // capped log line below carries the per-present delta for both roles.
                    float xcheckDeg = Float.NaN;
                    if (composed && spawned != null) {
                        Vector2f v0 = spawned.getVelocity();
                        if (v0 != null && (v0.x != 0f || v0.y != 0f)) {
                            float dirDeg = (float) Math.toDegrees(Math.atan2(v0.y, v0.x));
                            xcheckDeg = ((dirDeg - wireBearingDeg) % 360f + 540f) % 360f - 180f;
                        }
                    }
                    // GPEE v6: a usable wire |v0| replaces the
                    // composed estimate with the full SERVER vector |v0|·û(flightBearingDeg) —
                    // the magnitude half of the marker-#3 class. Ordered AFTER both
                    // xchecks read the PRE-override vector (the meters keep measuring the
                    // local estimate error) and BEFORE the catch-up advance (which then
                    // extrapolates the server vector). NaN/implausible wire speed ⇒ the
                    // composed spawn stands unchanged; ray classes are not overridden here (their
                    // entity velocity is rayExtender-internal — a foe ray took the wire speed at
                    // spawn, through the inherited argument above).
                    boolean speedApplied = false;
                    float speedXcheckUps = Float.NaN;
                    if (composed && spawned != null && event.birthSpeedUsable()) {
                        Vector2f v0 = spawned.getVelocity();
                        if (v0 != null) {
                            float wireSpeed = event.birthSpeed();
                            speedXcheckUps = Math.abs((float) Math.hypot(v0.x, v0.y) - wireSpeed);
                            double bearingRad = Math.toRadians(wireBearingDeg);
                            v0.set(wireSpeed * (float) Math.cos(bearingRad),
                                    wireSpeed * (float) Math.sin(bearingRad));
                            speedApplied = true;
                        }
                    }
                    if (!ownAdopt) {
                        foeFirePresented++;
                        if (rayInherit != null && spawned != null) {
                            foeFireRayWireSpeed++;
                        }
                        if (!wireFinite) {
                            foeFireBearingFallbackNanWire++;
                        } else if (spawned == null) {
                            foeFireBearingFallbackSpawnNull++;
                        } else if (composed) {
                            foeFireComposed++;
                            if (Float.isFinite(xcheckDeg)
                                    && Math.abs(xcheckDeg) > foeFireComposeXcheckMaxAbsDeg) {
                                foeFireComposeXcheckMaxAbsDeg = Math.abs(xcheckDeg);
                            }
                            if (speedApplied) {
                                foeFireSpeedApplied++;
                                if (Float.isFinite(speedXcheckUps)
                                        && speedXcheckUps > foeFireSpeedXcheckMaxAbsUps) {
                                    foeFireSpeedXcheckMaxAbsUps = speedXcheckUps;
                                }
                            }
                        } else {
                            foeFireBearingApplied++;
                        }
                        // v8 muzzle counters ride OUTSIDE the bearing-arm chain (the muzzle
                        // override is arm-independent); foe-only.
                        if (muzzleApplied) {
                            foeFireMuzzleApplied++;
                            if (Float.isFinite(muzzleXcheckU)
                                    && muzzleXcheckU > foeFireMuzzleXcheckMaxAbsU) {
                                foeFireMuzzleXcheckMaxAbsU = muzzleXcheckU;
                            }
                        }
                    }
                    // Pointing law, wire-bearing arm only (a wire-bearing spawn points
                    // down the FLIGHT line; the fired-ray pointing is vanilla's). Composed spawns
                    // face the ray by construction (factory facing = spawn angle = aim), and
                    // zero-velocity spawns are the MovingRay classes (setFacing does not steer;
                    // their render is the ray itself) — so this write can only reach the
                    // discriminator fail-soft class, where it preserves the behavior exactly.
                    if (!composed && wireFinite && spawned != null) {
                        Vector2f sv = spawned.getVelocity();
                        if (sv != null && (sv.x != 0f || sv.y != 0f)) {
                            spawned.setFacing(aim);
                        }
                    }
                    // Catch-up advance (armed by the
                    // age_max=12 real-hairpin measurement): the discharge happened
                    // (T_s−T)·dt seconds before the applied anchor, so advance the spawned projectile
                    // downrange along its own composed velocity — the same straight-line extrapolation
                    // the foe dead-reckon uses for pose. dt = the fixed 60 t/s step. Clamped
                    // at 60 ticks so a pathological stream stall cannot teleport a bolt off-screen; the
                    // muzzle FX below deliberately stays at the fire point (instantaneous flash).
                    if (spawned != null && ageTicks > 0) {
                        Vector2f vel = spawned.getVelocity();
                        Vector2f loc = spawned.getLocation();
                        if (vel != null && loc != null) {
                            // Clamp ≈1 s = the server rate (bit-exact at the 60 default).
                            long rate = ServerTickRate.ticksPerSecond();
                            float catchupSec = Math.min(ageTicks, rate) * (1f / (float) rate);
                            loc.set(loc.x + vel.x * catchupSec, loc.y + vel.y * catchupSec);
                            if (!ownAdopt) {
                                foeFireCatchupAdvanced++;
                            }
                        }
                    }
                    if (ownAdopt ? ++ownAdoptLogLines <= effectiveLogMax() : foeFirePresented <= effectiveLogMax()) {
                        // class = the spawned type's simple name — the ray-vs-ballistic
                        // discriminator the FIX-DESIGN sampled line carries (MovingRay = the
                        // vulcan/beam classes the old rebase could never steer).
                        PilotWatchLog.info("predict " + (ownAdopt ? "own-adopt" : "foe-fire")
                                + " present #" + (ownAdopt ? ownAdoptLogLines : foeFirePresented)
                                + ": entity=" + event.entityId() + " mount=" + event.mountIndex()
                                + " weapon=" + weapon.getId() + " aim=" + aim
                                + (aimUnbound ? " (local fallback)" : "")
                                + " bearingWire=" + event.flightBearingDeg() + " tick=" + event.simTick()
                                + " age=" + ageTicks
                                + " flight=" + (bearingCause != null ? "ray"
                                        : composed ? "composed" : "wire")
                                + (bearingCause == null ? "" : " cause=" + bearingCause)
                                + (composed && bearingCause == null ? " xcheck=" + xcheckDeg : "")
                                + " speedWire=" + event.birthSpeed()
                                + (speedApplied ? " speedApplied sxcheck=" + speedXcheckUps : "")
                                + (rayInherit != null && spawned != null
                                        ? " rayWireSpeed raySpec=" + raySpecUps : "")
                                + " muzzleWire=" + event.muzzleX() + "/" + event.muzzleY()
                                + (muzzleApplied ? " muzzleApplied mxcheck=" + muzzleXcheckU : "")
                                + " barrel=" + barrelRaw + " launch=" + (aimUnbound ? "local" : "wire")
                                + " class=" + (spawned == null
                                        ? "null" : spawned.getClass().getSimpleName()));
                    }
                    // A guided mint the missile lane will puppet needs no
                    // seeker handoff — the no-op AI replaces the factory seeker in the SAME stack
                    // (enroll runs at the present-site tail, no engine advance between), so the
                    // handoff's target write would be dead weight on a pose-driven twin.
                    if (!(MissilePoseLane.puppetsGuided() && spawned instanceof MissileAPI
                            && ((MissileAPI) spawned).isGuided())) {
                        handOffSeekerTarget(spawned, shooter, wireFinite ? wireBearingDeg : aim,
                                weapon.getId());
                    }
                    // vanilla spawns the flash at the barrel's launch angle = the wire
                    // facing.
                    presentFireFx(shooter, weapon, point, aim, ownAdopt);
                    if (!ownAdopt && spawned != null) {
                        decrementPresentedAmmo(weapon, shooter, event.simTick());
                        // Our CcWrap* effects derive their arc from this presented
                        // shot (spawnProjectile never runs onFire); vanilla effects are never relayed.
                        ReplicaPresentedFireRelay.onPresented(spawned, weapon, shooter, engine);
                    }
                    return spawned;
                }
            }
        } catch (Throwable t) {
            skip = "spawn threw " + t;
        }
        if (ownAdopt) {
            // The plugin counts adopt_skip_unresolved off the null return; the reason logs here.
            if (++ownAdoptLogLines <= effectiveLogMax()) {
                PilotWatchLog.info("predict own-adopt present SKIP: " + skip + " (" + event + ")");
            }
            return null;
        }
        foeFirePresentSkipped++;
        if (foeFirePresentSkipped <= effectiveLogMax()) {
            PilotWatchLog.info("predict foe-fire present SKIP #" + foeFirePresentSkipped
                    + ": " + skip + " (" + event + ")");
        }
        return null;
    }

    /**
     * Pre-spawn discriminator:
     * TRUE when the engine's spawn factory composes {@code inheritedVelocity +
     * û(spawnAngle)·specSpeed} into the constructor velocity — BALLISTIC/PLASMA (speed =
     * {@code getMoveSpeed}) via {@link ProjectileSpecAPI#getSpawnType()},
     * and MISSILE (speed = {@code getLaunchSpeed}) via the spec being a
     * {@link MissileSpecAPI} (that spec class carries no spawn type — instanceof IS the
     * discriminator, mirroring the engine's own dispatch). FALSE = the MovingRay classes
     * (BEAM/BALLISTIC_AS_BEAM: velocity {@code (0,0)} at the spawn instant)
     * and every unknown/failed read — fail toward the proven wire-bearing
     * spawn, never toward an unverified composition.
     */
    private static boolean composesInheritedVelocity(WeaponAPI weapon) {
        try {
            Object spec = weapon.getSpec() == null ? null : weapon.getSpec().getProjectileSpec();
            if (spec instanceof MissileSpecAPI) {
                return true;
            }
            if (spec instanceof ProjectileSpecAPI) {
                ProjectileSpawnType type = ((ProjectileSpecAPI) spec).getSpawnType();
                return type == ProjectileSpawnType.BALLISTIC || type == ProjectileSpawnType.PLASMA;
            }
        } catch (Throwable t) {
            // fall through — unknown spec shape reads as non-composable (the proven path)
        }
        return false;
    }

    /**
     * The inherited-velocity argument that flies a ray-class
     * (MovingRay) spawn at the server bolt's measured birth velocity {@code speedWire·û(bearingDeg)}.
     *
     * <p>WHY: the engine's ray extender moves the ray's head (and its tail, once the pulse is laid)
     * by {@code û(spawnAngle)·moveSpeed + inherited} every frame, and reports that sum as the ray's
     * velocity,
     * where {@code moveSpeed} is the projectile spec's speed under the shooter's
     * stats. Vanilla's own fire path hands the factory the
     * SHOOTER's velocity
     * — that is how the server's ray flies faster than its spec speed — and the
     * wire's (flightBearingDeg, birthSpeed) pair IS that composed vector, measured at the server's birth
     * (shooter velocity + speed along the ray's jittered birth facing). The kit spawns AT the wire
     * bearing, so the one term that makes the head fly exactly {@code speedWire} along it is
     * {@code û(bearing)·(speedWire − specSpeed)}: an existing wire field re-sourced through the engine's
     * own spawn argument, never the lagging replica's velocity. A {@code speedWire} below the spec speed
     * (a shooter receding from its own shot) yields the BACKWARD term; equal speeds yield the zero vector,
     * today's spawn exactly. The extender's range clock accrues {@code moveSpeed} alone,
     *so the bolt keeps the server twin's lifetime and its reach scales with
     * {@code speedWire} as the server's does.
     *
     * @return the vector, or {@code null} = keep the zero-inherit spawn: a non-finite bearing; a NaN
     *     (legacy frame) / non-finite / non-positive / above-the-decode-ceiling {@code speedWire}; a
     *     non-finite or non-positive spec speed — fail toward the proven path, never a guessed speed.
     */
    static Vector2f wireSpawnVelocity(float bearingDeg, float speedWire, float specSpeed) {
        if (!Float.isFinite(bearingDeg) || !Float.isFinite(speedWire) || !(speedWire > 0f)
                || speedWire > ClientGameplayEdgeEvent.MAX_PLAUSIBLE_BIRTH_SPEED_UPS
                || !Float.isFinite(specSpeed) || !(specSpeed > 0f)) {
            return null;
        }
        double rad = Math.toRadians(bearingDeg);
        double delta = (double) speedWire - (double) specSpeed;
        return new Vector2f((float) (delta * Math.cos(rad)), (float) (delta * Math.sin(rad)));
    }

    /** One log line, ever, when {@link #raySpecSpeed}'s spec read throws (render thread only). */
    private static boolean raySpecReadThrewLogged;

    /**
     * The speed the engine's ray factory will fly this weapon's ray
     * at: the projectile spec's {@code getMoveSpeed(shooter stats, weapon)}, the very call the MovingRay
     * constructor makes,
     * so {@link #wireSpawnVelocity}'s spec term cancels the
     * factory's own exactly. Only the ray spawn types (BEAM / BALLISTIC_AS_BEAM: the factory's MovingRay
     * branch) read a speed; a missile spec, a composed type and
     * any failed read return {@code NaN}, which keeps the zero-inherit spawn — so the composed classes and
     * the discriminator's fail-soft class never take the ray term.
     */
    static float raySpecSpeed(WeaponAPI weapon, ShipAPI shooter) {
        try {
            Object spec = weapon == null || weapon.getSpec() == null ? null : weapon.getSpec().getProjectileSpec();
            if (spec instanceof MissileSpecAPI || !(spec instanceof ProjectileSpecAPI)) {
                return Float.NaN;
            }
            ProjectileSpecAPI proj = (ProjectileSpecAPI) spec;
            ProjectileSpawnType type = proj.getSpawnType();
            if (type == ProjectileSpawnType.BEAM || type == ProjectileSpawnType.BALLISTIC_AS_BEAM) {
                return proj.getMoveSpeed(shooter == null ? null : shooter.getMutableStats(), weapon);
            }
        } catch (Throwable t) {
            if (!raySpecReadThrewLogged) {
                raySpecReadThrewLogged = true;
                PilotWatchLog.info("predict foe-fire ray spec-speed read threw " + t.getClass().getSimpleName()
                        + " (first only); the ray keeps the zero-inherit spawn");
            }
        }
        return Float.NaN;
    }

    /**
     * The adopted-guided-missile SEEKER HANDOFF. {@code spawnProjectile}
     * routes the engine's OWN factory dispatch
     * (a single mint), which always attaches the
     * spec-typed seeker AI — the desync defect was the seeker's target-selection INPUTS, read from
     * the local shooter at spawn ({@code getShipTarget()}/{@code getMouseTarget()}: null or stale on
     * a replica ⇒ flies straight / auto-acquires near a wrong point). Hand the seeker a live LOCAL
     * engine target explicitly via the public {@link GuidedMissileAI#setTarget}: the engine impls
     * STORE it and re-acquire only when it invalidates, so the handoff sticks. Choice: the shooter's
     * own hostile ship target when live (the own-adopt case = the player's selected target), else
     * the nearest live enemy to the spawn bearing (mirrors
     * the factory's own auto-acquire). Only live local-engine ships are handed over — the engine's
     * {@code setTarget} casts to its own type (CCE guard). One-shot per AI class: log class +
     * pre-handoff target = the premise evidence for the re-watch. Fail-soft: a handoff failure
     * never costs the presentation that already happened.
     */
    private void handOffSeekerTarget(CombatEntityAPI spawned, ShipAPI shooter, float bearingDeg,
            String weaponId) {
        try {
            if (!(spawned instanceof MissileAPI)) {
                return;
            }
            MissileAPI missile = (MissileAPI) spawned;
            if (!missile.isGuided() || !(missile.getMissileAI() instanceof GuidedMissileAI)) {
                return;
            }
            GuidedMissileAI ai = (GuidedMissileAI) missile.getMissileAI();
            CombatEntityAPI before = ai.getTarget();
            ShipAPI target = chooseSeekerTarget(shooter, bearingDeg);
            String cause;
            if (target != null) {
                ai.setTarget(target);
                seekerHandoffApplied++;
                cause = target == shooter.getShipTarget() ? "ship_target" : "bearing_nearest";
            } else {
                seekerHandoffNoTarget++;
                cause = "no_candidate";
            }
            String aiClass = missile.getMissileAI().getClass().getSimpleName();
            boolean firstOfClass = seekerAiClassLogged.add(aiClass);
            if (firstOfClass || ++seekerHandoffLogLines <= effectiveLogMax()) {
                PilotWatchLog.info("predict seeker-handoff" + (firstOfClass ? " FIRST-OF-CLASS" : "")
                        + ": weapon=" + weaponId + " ai=" + aiClass
                        + " preTarget=" + (before == null ? "null" : before.getClass().getSimpleName())
                        + " target=" + (target == null ? "null" : target.getHullSpec().getHullId())
                        + " cause=" + cause
                        + " applied=" + seekerHandoffApplied + " noTarget=" + seekerHandoffNoTarget);
            }
        } catch (Throwable t) {
            if (++seekerHandoffLogLines <= effectiveLogMax()) {
                PilotWatchLog.info("predict seeker-handoff SKIP: threw " + t
                        + " (weapon=" + weaponId + ")");
            }
        }
    }

    /**
     * Seeker target choice: the shooter's own hostile live ship target first, else the
     * live enemy ship nearest the spawn bearing — within a 45° cone prefer CLOSEST (the factory
     * auto-acquire shape), otherwise smallest angular delta. Sides via {@code getOwner()} on LIVE
     * ships only (a corpse reads owner 100 — the poison class; every candidate here is
     * alive-gated first).
     */
    private ShipAPI chooseSeekerTarget(ShipAPI shooter, float bearingDeg) {
        ShipAPI selected = shooter.getShipTarget();
        if (selected != null && selected.isAlive() && !selected.isHulk()
                && engine.isEntityInPlay(selected) && selected.getOwner() != shooter.getOwner()) {
            return selected;
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) {
            return null;
        }
        float bearing = Float.isFinite(bearingDeg) ? bearingDeg : shooter.getFacing();
        Vector2f from = shooter.getLocation();
        ShipAPI bestInCone = null;
        float bestInConeDist = Float.MAX_VALUE;
        ShipAPI bestByAngle = null;
        float bestAngle = Float.MAX_VALUE;
        for (ShipAPI candidate : ships) {
            if (candidate == shooter || candidate == null || !candidate.isAlive()
                    || candidate.isHulk() || candidate.getOwner() == shooter.getOwner()) {
                continue;
            }
            float dx = candidate.getLocation().x - from.x;
            float dy = candidate.getLocation().y - from.y;
            float angleTo = (float) Math.toDegrees(Math.atan2(dy, dx));
            float delta = Math.abs(((angleTo - bearing) % 360f + 540f) % 360f - 180f);
            float dist = dx * dx + dy * dy;
            if (delta <= 45f && dist < bestInConeDist) {
                bestInCone = candidate;
                bestInConeDist = dist;
            }
            if (delta < bestAngle) {
                bestByAngle = candidate;
                bestAngle = delta;
            }
        }
        return bestInCone != null ? bestInCone : bestByAngle;
    }

    /**
     * D-BARREL: the barrel ordinal the muzzle FX use — {@link BarrelMatchLaw}
     * over {@code |weapon.getFirePoint(k) − wire muzzle|} for the LOCAL spec's slot-typed
     * fire-offset count (hardpoint → hardpoint
     * offsets, turret → turret offsets, hidden → the HARDPOINT list's size; {@code getFirePoint(k)}
     * is a bare {@code list.get(k)} — out of range THROWS, so the
     * count is never guessed). No usable wire muzzle, unknown, or any throw ⇒
     * {@link BarrelMatchLaw#BARREL_UNKNOWN} (−1): the call site clamps it to 0 for the FX
     * point (fail-soft presentation, the wire muzzle stays the BOLT's spawn point regardless).
     * The FX point only — never the launch angle, which is the wire facing.
     */
    private static int inferBarrelRaw(WeaponAPI weapon, ClientGameplayEdgeEvent event) {
        if (!event.muzzleUsable()) {
            return BarrelMatchLaw.BARREL_UNKNOWN;
        }
        try {
            WeaponSlotAPI slot = weapon.getSlot();
            WeaponSpecAPI spec = weapon.getSpec();
            if (slot == null || spec == null) {
                return BarrelMatchLaw.BARREL_UNKNOWN;
            }
            int count = barrelCount(weapon);
            double[] dist = new double[count];
            for (int k = 0; k < count; k++) {
                Vector2f firePoint = weapon.getFirePoint(k);
                dist[k] = firePoint == null
                        ? Double.NaN
                        : Math.hypot(firePoint.x - event.muzzleX(), firePoint.y - event.muzzleY());
            }
            return BarrelMatchLaw.matchBarrel(dist);
        } catch (RuntimeException t) {
            return BarrelMatchLaw.BARREL_UNKNOWN;
        }
    }

    /**
     * The slot-typed fire-offset count = the barrels the engine alternates over
     * (hardpoint → hardpoint offsets, turret → turret offsets,
     * hidden → the HARDPOINT list's size), capped at {@link BarrelMatchLaw#MAX_BARREL_INDEX}+1;
     * 0 = unreadable (null slot/spec/list or any throw — fail-soft, the callers treat 0 as unknown).
     */
    private static int barrelCount(WeaponAPI weapon) {
        try {
            WeaponSlotAPI slot = weapon.getSlot();
            WeaponSpecAPI spec = weapon.getSpec();
            if (slot == null || spec == null) {
                return 0;
            }
            List<Vector2f> offsets = slot.isHardpoint() || slot.isHidden()
                    ? spec.getHardpointFireOffsets()
                    : slot.isTurret() ? spec.getTurretFireOffsets() : null;
            return offsets == null ? 0 : Math.min(offsets.size(), BarrelMatchLaw.MAX_BARREL_INDEX + 1);
        } catch (RuntimeException t) {
            return 0;
        }
    }

    /**
     * The audio/visual half of {@link #presentFoeDischarge}.
     * Same
     * faithfulness class as the projectile spawn: presentation-side effects of the authoritative
     * discharge via the engine's OWN spec-driven paths — the weapon spec's {@code muzzleFlashSpec}
     * through {@link CombatEngineAPI#spawnMuzzleFlashOrSmoke} at the presented muzzle point, and the
     * spec's own fire sound id ({@link FireSoundAdapter}: the .wpn {@code fireSoundTwo}, per-shot)
     * played there with the shooter's velocity. Pitch 1.0 / volume 1.0 matches the engine's own
     * per-shot fire call (its own fire path plays pitch 1.0, volume {@code 0.25 + 0.75*fireLevel};
     * a discharged projectile is a full-power shot). No local fire cycle runs — that remains the
     * double-spawn/ammo/flux guard; force-firing for free FX stays REJECTED. Each FX
     * piece fails soft independently (counted + capped-logged) and never blocks the projectile
     * presentation, which has already happened when this runs.
     */
    private void presentFireFx(ShipAPI shooter, WeaponAPI weapon, Vector2f point, float aim,
            boolean ownAdopt) {
        try {
            engine.spawnMuzzleFlashOrSmoke(shooter, point, weapon.getSpec(), aim);
            if (!ownAdopt) {
                foeFireFxFlash++;
            }
        } catch (Throwable t) {
            fireFxSkip("flash threw " + t, weapon, ownAdopt);
        }
        try {
            String soundId = FireSoundAdapter.fireSoundId(weapon);
            if (soundId == null) {
                fireFxSkip("no fire sound id on spec", weapon, ownAdopt);
            } else {
                com.fs.starfarer.api.Global.getSoundPlayer()
                        .playSound(soundId, 1f, 1f, point, shooter.getVelocity());
                if (!ownAdopt) {
                    foeFireFxSound++;
                    if (foeFireFxSound <= effectiveLogMax()) {
                        PilotWatchLog.info("predict foe-fire FX sound #" + foeFireFxSound
                                + ": " + soundId + " weapon=" + weapon.getId());
                    }
                }
            }
        } catch (Throwable t) {
            fireFxSkip("sound threw " + t, weapon, ownAdopt);
        }
    }

    /** Fail-soft counter + capped log for a skipped {@link #presentFireFx} piece
     *  (own-adopt role: log-only under the own-adopt cap — foe counters stay foe-only). */
    private void fireFxSkip(String why, WeaponAPI weapon, boolean ownAdopt) {
        if (ownAdopt) {
            if (++ownAdoptLogLines <= effectiveLogMax()) {
                PilotWatchLog.info("predict own-adopt FX SKIP: " + why
                        + " (weapon=" + weapon.getId() + ")");
            }
            return;
        }
        foeFireFxSkipped++;
        if (foeFireFxSkipped <= effectiveLogMax()) {
            PilotWatchLog.info("predict foe-fire FX SKIP #" + foeFireFxSkipped
                    + ": " + why + " (weapon=" + weapon.getId() + ")");
        }
    }

    /**
     * The shared server-decided REAL-death core (the proven death path, extracted from
     * the plugin's {@code killFoe}/{@code killRemote}): face the ship to its authoritative heading
     * ({@code facingOrNaN}; NaN = no authoritative facing → leave the local one), force a hulk, then
     * apply massive shield-bypassing HE so the engine runs its real explosion + drifting hulk. The
     * CALLER owns the one-shot guard, the axis-C cap clear (so this blow is NOT capped), the pose
     * snap, and its try/catch — this core stays a straight-line presentation write.
     */
    void presentServerDeath(ShipAPI ship, float facingOrNaN) {
        // FIRST, before the dweller branch and the blow — see the godMirror field.
        if (godMirror != null) {
            godMirror.releaseForKill(ship);
        }
        if (DwellerHullLaw.isDweller(ship)) {
            // vanilla pins a dweller's hulk chance to 0
            // — forcing 1f here minted the invisible collidable hulk. Instant-remove twin instead.
            presentServerFighterDeath(ship, facingOrNaN);
            return;
        }
        if (Float.isFinite(facingOrNaN)) {
            ship.setFacing(facingOrNaN);
        }
        ship.setCollisionClass(CollisionClass.SHIP);
        ship.setHulkChanceOverride(1f);
        // Null customData: skip the fence, never the death (this seam is fail-soft by contract).
        // The plant is PERSISTENT (put, never removed) — the key-constant javadoc carries the why.
        Map<String, Object> customData = engine.getCustomData();
        if (customData != null) {
            customData.put(PHASE_ANCHOR_CAN_DIVE_KEY, Boolean.TRUE);
        }
        engine.applyDamage(ship, ship.getLocation(), SERVER_KILL_DAMAGE,
                DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
    }

    /**
     * The FIGHTER twin of {@link #presentServerDeath} — the server keeps no
     * fighter corpse, so {@code setHulkChanceOverride(0f)} forces the engine's instant-remove
     * death branch instead of the drifting hulk (zero lingering-divergence surface); NO collision
     * flip (the hull-spec FIGHTER class stays authoritative). Engine-native fighter death FX are
     * already small (no flare, no self-blast at FIGHTER hull size, half particle count). Same
     * caller contract: one-shot guard, axis-C cap clear, pose snap and try/catch stay caller-owned.
     */
    void presentServerFighterDeath(ShipAPI ship, float facingOrNaN) {
        // A DWELLER seat's kill reaches this twin
        // DIRECTLY (FoeStateLaneCoordinator, not via presentServerDeath), so the god release must
        // live here too — a mirrored body absorbs its own HE blow (armor 0 / hull 1e-6) and lingers
        // as a live ghost until MRST. Idempotent: the presentServerDeath route counts once.
        if (godMirror != null) {
            godMirror.releaseForKill(ship);
        }
        if (Float.isFinite(facingOrNaN)) {
            ship.setFacing(facingOrNaN);
        }
        ship.setHulkChanceOverride(0f);
        if (ConstructionPresentLane.isConstructionCarrier(ship)) {
            //
            // Vanilla consumes a Threat construction carrier SILENTLY at T2
            // (swarm despawnSound nulled, hitpoints 0 +
            // debris off + 100 ENERGY with playSound=false). The HE blow below played a hit
            // sound/flash and, for a carrier the construction lane never paired (11 of 41),
            // the swarm's threat_swarm_destroyed despawn cue too. Mirror the
            // vanilla kill for this one body; every other fighter keeps the HE twin.
            //
            // Vanilla's 100-ENERGY blow was silent as a HIT, but the engine's DEATH routine it
            // triggers plays `explosion_ship`
            // (hull-size keyed) whenever explosionScale > 0 — vanilla's carrier is silent only
            // because ConstructionSwarmSystemScript zeroed explosionScale at spawn.
            // SwarmSpriteGate now mirrors that at mint; this line is the belt for a carrier the
            // gate never treated (unreadable spec). playSound=false never reaches the death routine's sound.
            // Proof row (rig-readable): scale 0.0 here = the mint-time gate treated this carrier.
            PilotWatchLog.info("predict fabpop-v2 carrier kill: explosionScale(before)="
                    + ship.getExplosionScale() + " hulkChance=" + ship.getHulkChanceOverride()
                    + " doNotRender=" + ship.isDoNotRender()
                    + " on " + Integer.toHexString(System.identityHashCode(ship)));
            ship.setExplosionScale(0f); // as ConstructionSwarmSystemScript does at spawn
            RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
            if (swarm != null) {
                swarm.getParams().despawnSound = null; //
            }
            ship.setHitpoints(0f);      //
            ship.setSpawnDebris(false); //
            engine.applyDamage(ship, ship.getLocation(), 100f, DamageType.ENERGY, 0f, true, false,
                    ship, false);       // the 9-arg overload, playSound=false
            return;
        }
        engine.applyDamage(ship, ship.getLocation(), SERVER_KILL_DAMAGE,
                DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
    }
}
