package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;

import cc.starsector.client.net.ClientDespawnReason;
import cc.starsector.client.net.ClientEntityDespawn;
import cc.starsector.client.net.ClientMatchOverRecord;

/**
 * Drives the engine's NATIVE end-of-combat presentation from the SERVER's
 * serve-end {@code MOVR} verdict — "server verdict drives the native screen, local guesser
 * muted". The engine's own auto-end checker is held out of victory state whole-match by the
 * {@code setDoNotEndCombat(true)} at plugin init (registry shapes never restore it);
 * when the session latches a {@code MOVR}, this lane calls {@code endCombat(delay, side)} —
 * which self-clears that flag and runs the native whiteout → results-dialog ladder
 * — mapping "the winner is MY side" to
 * {@code FleetSide.PLAYER} (the side-0 victory ladder) so the local viewer sees Victory exactly
 * when their own side won. A -1 winner (roster-0, operator end) never equals an own side, so a
 * drawn defense shows the defeat ladder, never a victory banner.
 *
 * <p>Results roster — the native destroyed-ships screen under-counted:
 * the lane also owns the server-classified DESTROYED results roster, knob
 * {@code cc.predict.resultsRoster}. The native tally only credits a replica that was minted,
 * killed via credited local damage AND found deployed at death
 * — never-minted polling-gap deaths, silent {@code removeEntity}
 * reaps and registry purges all miss it (census kills=173 → 2 shown). The EDSP
 * despawn lane is relevance-safe (one event per death from the full authoritative roster,
 * minted or not), so {@link #noteDespawn} queues every non-own DESTROYED id (net thread), and
 * the drive joins identity from the id-contract registries handed in at construction
 * ({@code variantById}: descriptor walk + RAPP; {@code remoteEntities}: rows for
 * every non-own id at init) and injects synthetic members into the debrief's own data: the
 * mission roster panel iterates {@code cfm.getFleet().Object()} and labels via
 * {@code getFinalStatus} set-membership, so each member
 * is appended to that live list + the live {@code getDestroyed()} set (engine-typed cast — the API's
 * {@code getDestroyedCopy} is a dead copy; {@code java.lang.reflect} is B-denied, direct
 * compile-time engine-typed reference is the HulkKeepAliveAdapter-precedent legal lane). NEVER reserves
 * ({@code getFinalStatus} checks reserves BEFORE destroyed). Vanilla-faithful filters: fighters
 * skipped (the engine's own {@code !isFighter()} gate keeps them off the list);
 * DEPARTED never credited (retreat ≠ kill, the codec's own law); already-credited members
 * deduped by (owner, variant) multiset against the live destroyed sets. Fail-soft per entry
 * AND around the whole sweep — a roster miss degrades to today's under-count, never blocks the
 * end drive.
 *
 * <p>The Merlon idiom: presentation-only, fail-silent ({@code dead} latch — never break the
 * render frame), all output through {@link PilotWatchLog}. Drive-once ({@code driven} latch)
 * mirrors the session's first-latch-wins record. {@link #resetRound()} is the
 * defensive never-re-field assert: a round flip after the drive is a server-side contract
 * break — logged loud, never re-driven.
 */
final class MatchOverLane {

    /** Inject the server-classified DESTROYED ledger into the native
     * debrief, compiled ON (E3 hardwire; the
     * {@code cc.predict.resultsRoster} on|off knob-resolved shape — the older
     * native-credit-only tally as the A/B lever — is in git). */
    static final boolean ROSTER_ON = true;

    /** Whiteout beat between the drive and the native results dialog — the dialog itself is
     * Continue-gated with the engine paused (no auto-return timer), so teardown keeps
     * unlimited time regardless of this value. */
    private static final float END_DELAY_SECONDS = 1f;

    private boolean driven;

    /** The plugin's banner-suppression refresh stops the frame the verdict drives. */
    boolean hasDriven() {
        return driven;
    }
    private boolean dead;

    /** The identity joins (PRCP-owned, read-only here; populated at init, round-scoped). */
    private final java.util.Map<Long, String> variantById;
    private final java.util.Map<Long, PredictReconcileCombatPlugin.RemoteEntity> remoteEntities;
    /** Ledger: non-own DESTROYED ids, net-thread fed / render-thread drained at the drive. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> destroyedIds =
            new java.util.concurrent.ConcurrentLinkedQueue<Long>();

    MatchOverLane(java.util.Map<Long, String> variantById,
            java.util.Map<Long, PredictReconcileCombatPlugin.RemoteEntity> remoteEntities) {
        this.variantById = variantById;
        this.remoteEntities = remoteEntities;
    }

    /**
     * Net-thread feed from the PRCP {@code EDSP} sink (non-own despawns only — the sink's
     * own-id branch never relays): queue DESTROYED ids for the results-roster drive. DEPARTED
     * is dropped here — a departure must never score as a kill.
     */
    void noteDespawn(ClientEntityDespawn despawn) {
        if (ROSTER_ON && despawn.reason() == ClientDespawnReason.DESTROYED) {
            destroyedIds.add(Long.valueOf(despawn.entityId()));
        }
    }

    /**
     * Per active (unpaused) frame: drive the native end once the session has latched a verdict.
     * {@code record} = the session's latched {@code MOVR} (null until fanned); {@code ownSide} =
     * the bind-side authority (server 0/1 numbering; a pre-capture/poisoned value simply never
     * matches the winner, so the safe default is the defeat ladder — never a false victory).
     * The bind-side authority doubles as the engine owner of the own side (the
     * spawn-owner law), which is what the roster sweep needs for its manager mapping.
     * {@code ownAbsSide} = the own TEAM's live ABSOLUTE wire side ({@code /switchteams}:
     * {@code winnerSide()} is the absolute FleetSide ordinal, so the won-compare must use the
     * team the seat is on NOW, not the local engine owner — a switched seat wins with its new team).
     */
    void advance(ClientMatchOverRecord record, int ownSide, int ownAbsSide) {
        if (dead || driven || record == null) {
            return;
        }
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) {
                return;
            }
            // Before the end drive, so the lazily-built results object (CombatEngine
            // .getResults, cached at first dialog open) sees the injected members. Own try —
            // a roster failure must never cost the native end itself.
            try {
                ensureDestroyedRoster(engine, ownSide);
            } catch (Throwable t) {
                PilotWatchLog.info("cc-results-roster DEAD: " + t);
            }
            boolean won = record.winnerSide() == ownAbsSide; // Absolute vs absolute
            engine.endCombat(END_DELAY_SECONDS, won ? FleetSide.PLAYER : FleetSide.ENEMY);
            driven = true;
            PilotWatchLog.info("cc-match-over drive outcome=" + record.outcome()
                    + " winner=" + record.winnerSide() + " ownSide=" + ownSide
                    + " ownAbsSide=" + ownAbsSide
                    + " tick=" + record.simTick()
                    + " result=" + (won ? "VICTORY" : "DEFEAT"));
        } catch (Throwable t) {
            dead = true; // presentation only — never break the frame
            PilotWatchLog.info("cc-match-over DEAD: " + t);
            // The dead-latch rides into the FALLBACK section.
            FallbackAnnounce.announceOnce("s4-match-over-dead", "advance threw "
                    + t.getClass().getSimpleName() + " -> lane dead, native end only");
        }
    }

    /**
     * Injection sweep (drive-once, render thread): join each queued DESTROYED id to
     * {variant, side}, filter (fighters, unknown ids), dedupe against members the engine
     * already credited, then append a synthetic {@code FleetMember} to the owning manager's
     * live fleet list + destroyed set. Per-entry fail-soft; one summary log line.
     */
    private void ensureDestroyedRoster(CombatEngineAPI engine, int ownSide) {
        int queued = destroyedIds.size();
        if (!ROSTER_ON || queued == 0) {
            PilotWatchLog.info("cc-results-roster injected=0 queued=" + queued
                    + (ROSTER_ON ? "" : " (knob off)"));
            return;
        }
        int injected = 0;
        int dedup = 0;
        int fighter = 0;
        int unknown = 0;
        int failed = 0;
        com.fs.starfarer.combat.CombatFleetManager[] mgrs =
                new com.fs.starfarer.combat.CombatFleetManager[2];
        // (owner + "|" + variantId) -> count of members ALREADY in a live destroyed set: the
        // engine's own tally is the dedupe truth (whatever path credited them).
        java.util.Map<String, Integer> credited = new java.util.HashMap<String, Integer>();
        for (int owner = 0; owner <= 1; owner++) {
            mgrs[owner] =
                    (com.fs.starfarer.combat.CombatFleetManager) engine.getFleetManager(owner);
            for (FleetMemberAPI m : mgrs[owner].getDestroyed()) {
                ShipVariantAPI v = m.getVariant();
                String key = owner + "|" + (v != null ? v.getHullVariantId() : "?");
                Integer have = credited.get(key);
                credited.put(key, Integer.valueOf(have == null ? 1 : have.intValue() + 1));
            }
        }
        for (Long id = destroyedIds.poll(); id != null; id = destroyedIds.poll()) {
            String variantId = variantById.get(id);
            if (variantId == null) {
                unknown++; // no id-contract row (unexpected server id) — skip, counted
                continue;
            }
            try {
                ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
                if (variant != null && variant.getHullSpec() != null
                        && variant.getHullSpec().getHullSize() == ShipAPI.HullSize.FIGHTER) {
                    fighter++; // vanilla-faithful: the fleet manager's !isFighter() gate — ships only
                    continue;
                }
                // The registry row is read AFTER the fighter gate — a killed
                // fighter's entry is REAPED from remoteEntities one frame after its kill (its
                // variant row stays), so it must classify as `fighter`, never as `unknown`.
                PredictReconcileCombatPlugin.RemoteEntity re = remoteEntities.get(id);
                if (re == null) {
                    unknown++; // a non-fighter id with no registry row (unexpected server id)
                    continue;
                }
                int owner = re.side == 0 ? ownSide : 1 - ownSide;
                String key = owner + "|" + variantId; // wire-scrub: not a sink (map key)
                Integer have = credited.get(key);
                if (have != null && have.intValue() > 0) {
                    credited.put(key, Integer.valueOf(have.intValue() - 1));
                    dedup++; // the engine already shows one of these — don't double-row it
                    continue;
                }
                FleetMemberAPI member = Global.getFactory()
                        .createFleetMember(FleetMemberType.SHIP, variantId);
                member.setOwner(owner); // factory hardcodes 0 — REQUIRED
                // descriptor-lane entities carry no fanned name (RosterRow has no
                // shipName field). The debrief
                // row carries the name the hull ALREADY wore in this window — the bound
                // replica's own engine name (the game's vanilla-like generator named it at
                // spawn; the player sees it on hover/target all match) — never a minted
                // "WAVE 1.n" label (the twin naming + its prefix are RETIRED).
                // Order: fanned wire name (wave spawns), else the replica's in-game name, else
                // the hull display name.
                String name = re.appendShipName;
                if (name == null && re.ship != null) {
                    String inGame = re.ship.getName();
                    if (inGame != null && !inGame.isEmpty()) {
                        name = inGame;
                    }
                }
                if (name == null && variant != null && variant.getHullSpec() != null) {
                    name = variant.getHullSpec().getHullName();
                }
                if (name != null && !name.isEmpty()) {
                    member.setShipName(name);
                }
                com.fs.starfarer.campaign.fleet.FleetMember fm =
                        (com.fs.starfarer.campaign.fleet.FleetMember) member;
                mgrs[owner].getFleet().Object().add(fm); // the debrief roster iterates this
                mgrs[owner].getDestroyed().add(fm);      // live set => getFinalStatus DESTROYED
                injected++;
            } catch (Throwable t) {
                failed++; // per-entry fail-soft: one bad variant never costs the rest
            }
        }
        PilotWatchLog.info("cc-results-roster injected=" + injected + " dedup=" + dedup
                + " fighter=" + fighter + " unknown=" + unknown + " failed=" + failed
                + " queued=" + queued);
    }

    /** Defensive never-re-field assert (a no-op until the drive has run); the
     * ledger is round-scoped like its identity registries — clear with the round. */
    void resetRound() {
        destroyedIds.clear();
        if (driven) {
            PilotWatchLog.error("cc-match-over DEFECT: round flip AFTER the match-over drive —"
                    + " the server re-fielded past a latched matchOver (never-re-field"
                    + " violated); not re-driving", null);
        }
    }

}
