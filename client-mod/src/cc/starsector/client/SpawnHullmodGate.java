package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

/**
 * The entity-SPAWNING HULLMOD gate: {@link SpawnSystemGate}'s sibling, and the arm
 * that gate structurally cannot reach. The system gate stops a replica from PRESSING a spawn-family
 * ship system, because "the local press adds only duplicates". A builtin HULLMOD never routes
 * through {@code useSystem()} / {@code forceState}, so there is no press to skip — its effect is
 * advanced by the engine off the ship's own variant, every frame, forever.
 *
 * <p>The live case is C1 (work-queue row (35)): the served Bastion's {@code station3} carries the
 * builtin {@code stealth_minefield_lt}, whose
 * {@code StealthMinefield.advanceInCombat} mints mines through a bare
 * {@code engine.spawnProjectile(source, null, "minelayer1", ...)}. The client's REPLICA of that
 * station carries the same builtin, so the client's engine mints its OWN mines — and every decision
 * in that path is unseeded RNG: the {@code IntervalUtil(0.5f,1.5f)} tick, a per-candidate 25%
 * accept roll, the radius/angle jitter, a fresh {@code new Random().nextInt(6)+5} batch size, the
 * per-mine spawn delay, {@code setFlightTime(Math.random())}, and a random launch-velocity kick in
 * the missile factory. The mines then detonate into a {@code DamagingExplosion} that takes the
 * ordinary collision-damage path — nothing in it consults authority. A client-local mine therefore
 * subtracts hull the server never sees, which is the mechanism behind the owner's row-(37)
 * {@code fh_walk} hypothesis. Suppression must happen at MINT; reaping the mine afterwards is
 * strictly worse (the RNG is already drawn, the sound already played).
 *
 * <p><b>The lever</b> is vanilla's own, and it is NOT {@code removeMod}: for a builtin,
 * {@code HullVariantSpec.removeMod} returns early and silently does nothing — a fix that passes
 * review and fails in the field. {@code addSuppressedMod(id)} adds to {@code suppressedMods} and
 * THEN calls {@code removeMod}, which now clears the builtin guard; {@code addMod}/
 * {@code clearHullMods} refuse to resurrect a suppressed id. The engine re-reads
 * {@code getVariant().getAllMods()} every frame, so the effect stops on the
 * NEXT frame — order versus spawn does not matter. {@code .variant} JSON has carried a
 * {@code suppressedMods} array all along; this is a first-class feature, not a trick.
 *
 * <p><b>Why {@link ShipAPI#cloneVariant()} first</b>: a stock {@code HullVariantSpec} can be the
 * shared spec-store instance, and mutating that would suppress mines for every ship of that hull in
 * the JVM. Cloning is the engine's own per-ship escape and is only paid on a body that actually
 * carries a minting mod (the {@link #MINT_MODS} membership test runs first, so every ordinary
 * replica exits before any allocation). The mod deliberately never touches
 * {@code getBuiltInMods()} — that shared-spec hack was removed once already
 * from {@code CcClientModPlugin}.
 *
 * <p><b>Scope</b>: replica bodies only, via the one ledgered treatment site
 * {@code PredictReconcileCombatPlugin.neutralizeRemoteBody} — which covers the spawn path, the
 * station-child path, and every {@link StationAdoptBindLane} adopt/repair/sweep caller including
 * the fail-closed {@code sweepInert}. The player's OWN ship is never treated there and keeps
 * vanilla behavior. Threading: render-thread only, like every sibling collaborator. Every body is
 * {@code try}-wrapped and fails OPEN — a body this gate cannot read behaves exactly as pre-gate.
 */
final class SpawnHullmodGate {

    /**
     * The gate, compiled ON (the {@link SpawnSystemGate} rationale verbatim:
     * shipping it off would ship the measured bug). E3 hardwire;
     * the {@code cc.predict.foeSpawnHullmodGate} on|off knob-resolved shape (legacy
     * mint-through as the A/B lever) is in git.
     */
    static final boolean FOE_SPAWN_HULLMOD_GATE = true;

    /**
     * Hullmod ids whose effect MINTS combat entities with no weapon, slot or system behind it — the
     * population this gate exists for. Pinned by id rather than classified at runtime because,
     * unlike a {@code .system}, a hullmod exposes no spec field that names its effect: the CSV
     * {@code effectClass} column is loader-side only, and {@code HullModEffect} has no
     * "does this spawn" surface. Both vanilla minefield rows share ONE engine-wide
     * {@code "minefield_data_key"} source, so both must go or the other simply claims the key.
     * A new id belongs here the moment a replica is seen minting from one.
     *
     * <p><b>{@code shard_spawner}</b> — the OMEGA arm, and the
     * first entry that mints whole SHIPS rather than ordnance. Vanilla
     * {@code ShardSpawner.advanceInCombat} runs off
     * the hull builtin carried by {@code facet}/{@code tesseract}/{@code shard_left}/
     * {@code shard_right}; while the body is {@code isHulk()} and in
     * play it rolls a 2–3 s delay, then unseeded-RNG-picks the split (type, count,
     * angle, facing) and mints each child through {@code fleetManager.spawnShipOrWing}.
     * The server runs that path on ITS hulk and the children reach every client
     * as growth RAPP rows; the client's OWN engine ran it again on the replica hulk, so every
     * client saw a second, private litter — ghosts the station never shot, F11 never killed
     * ("field is empty" was true server-side) and no two windows agreed on. Recursive by design:
     * a server-born child carries the same builtin and takes this treatment at its own RAPP birth.
     * The suppression drops nothing the client needs — the round-not-over hold and the hulk
     * invulnerability are server concerns, and the local break-prob roll is already pinned to
     * zero by {@code FractureFamilyCoordinator.suppressLocalBreakProb}.
     */
    private static final java.util.Set<String> MINT_MODS = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                    "stealth_minefield", "stealth_minefield_lt", "shard_spawner")));

    /** Per-run gate log cap (the sibling-lane discipline; counters continue past it). */
    private static final int GATE_LOG_MAX = 40;

    /**
     * The owning plugin — the backref every sibling collaborator takes as its sole constructor
     * argument ({@link SpawnSystemGate}/{@link LocalFireCensus}). Held for symmetry and for the
     * cross-lane reads a later stroke will want.
     */
    @SuppressWarnings("unused")
    private final PredictReconcileCombatPlugin plugin;

    private int suppressedBodies;  // replica bodies that took at least one suppression
    private int suppressedMods;    // (body, mod) pairs suppressed
    private int verifyFailures;    // suppressions whose read-back did NOT clear the mod (the trap)
    private int gateThrows;        // bodies whose suppression threw (failed OPEN)
    private int gateLogLines;      // run-lifetime log budget

    SpawnHullmodGate(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Suppress every minting builtin on ONE replica body. Called from the first-treatment
     * branch of {@code neutralizeRemoteBody}, so it runs once per body; it is nonetheless idempotent
     * (an already-suppressed id is skipped without a re-clone), because
     * {@code forgetRemoteBodyTreatment} can re-open that branch for the same body on a band drop.
     *
     * <p>Returns true when this call suppressed anything (the caller's log gate). Fail-soft and fail
     * OPEN per body: suppression is a presentation-side treatment and must never break a bind path.
     */
    boolean suppress(ShipAPI ship) {
        if (!FOE_SPAWN_HULLMOD_GATE || ship == null) {
            return false;
        }
        boolean any = false;
        try {
            ShipVariantAPI variant = ship.getVariant();
            if (variant == null) {
                return false;
            }
            for (String modId : MINT_MODS) {
                // The membership test runs FIRST so an ordinary replica — the overwhelming majority
                // — leaves before any clone or set allocation.
                if (!variant.hasHullMod(modId) || variant.getSuppressedMods().contains(modId)) {
                    continue;
                }
                if (!any) {
                    // Once per body, and only for a body that actually mints: a stock variant may be
                    // the shared spec-store instance, and suppressing on THAT would disable mines
                    // JVM-wide (including a co-hosted server sim).
                    ship.cloneVariant();
                    variant = ship.getVariant();
                }
                variant.addSuppressedMod(modId);
                suppressedMods++;
                any = true;
                // Positive control on the spot: addSuppressedMod is the only call that clears a
                // BUILTIN (bare removeMod is a silent no-op), so read the outcome back rather than
                // trusting the write. A false here means the lever moved and the mines are live.
                boolean cleared = !variant.hasHullMod(modId)
                        && variant.getSuppressedMods().contains(modId);
                if (!cleared) {
                    verifyFailures++;
                    // "mod still live" must survive the log cap.
                    FallbackAnnounce.announceOnce("s5-hullmod-unverified#" + modId,
                            "suppression unverified -> replica may mint local ordnance");
                }
                log((cleared ? "SUPPRESS " : "SUPPRESS-UNVERIFIED ") + modId
                        + " on " + Integer.toHexString(System.identityHashCode(ship))
                        + " variant=" + safeVariantId(variant)
                        + (cleared ? " (builtin cleared from the effect-advance list)"
                                : " -- THE MOD IS STILL LIVE: this replica will mint local ordnance"));
            }
            if (any) {
                suppressedBodies++;
            }
        } catch (Throwable t) {
            gateThrows++;
            // fail OPEN: an unreadable body behaves exactly as it did pre-gate. Loud, because the
            // consequence is a replica minting unsynced local damage. 1290 (83) CL-11: the error
            // line alone never rides the verdict's FALLBACK grep — confess the degraded mode too.
            FallbackAnnounce.announceOnce("s5-hullmod-gate-threw", "suppress threw "
                    + t.getClass().getSimpleName() + " -> replica may mint local ordnance");
            PilotWatchLog.error("predict replica hullmod-gate: suppression FAILED on "
                    + Integer.toHexString(System.identityHashCode(ship))
                    + " (the body may still mint local ordnance)", t);
        }
        return any;
    }

    private String safeVariantId(ShipVariantAPI variant) {
        try {
            String id = variant.getHullVariantId();
            return id == null ? "(null)" : id;
        } catch (Throwable ignore) {
            return "(unreadable)";
        }
    }

    /** Capped gate line (the {@link SpawnSystemGate} budget shape; counters continue past the cap). */
    private void log(String body) {
        if (gateLogLines >= GATE_LOG_MAX) {
            return;
        }
        gateLogLines++;
        PilotWatchLog.info("predict replica hullmod-gate " + body
                + (gateLogLines == GATE_LOG_MAX ? " [gate log cap; counters continue]" : ""));
    }

    /**
     * The gate's fragment of the {@code predict B3 eventReplay-meter} line, appended beside
     * {@link SpawnSystemGate#meterTokens} by {@link LocalFireCensus} (one line, one grader; the
     * {@code Get-WvKv} tokenizer is name-agnostic, so new keys ride for free). Key names are PINNED
     * — the grader parses them by name. The bar for a served-station watch: {@code _bodies>=1} with
     * {@code _verify_failures=0} and {@code _throws=0}; a zero {@code _bodies} on a Bastion run means
     * the gate never saw the station, not that there was nothing to suppress.
     */
    String meterTokens() {
        return " predict_event_rebase_foe_spawn_hullmod_gate=" + (FOE_SPAWN_HULLMOD_GATE ? "on" : "off")
                + " predict_event_rebase_hullmod_suppressed_bodies=" + suppressedBodies
                + " predict_event_rebase_hullmod_suppressed_mods=" + suppressedMods
                + " predict_event_rebase_hullmod_verify_failures=" + verifyFailures
                + " predict_event_rebase_hullmod_gate_throws=" + gateThrows;
    }
}
