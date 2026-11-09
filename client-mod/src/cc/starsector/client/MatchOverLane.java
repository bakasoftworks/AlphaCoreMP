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

    /**
     * The most debrief rows one drive injects. The ledger is keyed
     * by DESTROYED id per round and fed behind the kit's despawn intake cap, so it can hold up to
     * {@link RosterAppendLane#MAX_ROUND_ENTITIES} ids, and every injected row is a live fleet member in the
     * engine's own roster. The axis is debrief rows per round, never the roster size: growth spawns let a
     * lawful round field more ships than it started with. Measured lawful load: 44 and 23 rows in a
     * one-round match each (about 372x headroom) and about 602 rows an hour, so a three-hour round fills
     * about a ninth of it. Past the cap the drive injects nothing more and counts every remaining id as
     * {@code refused=} on its summary row, never silently.
     *
     */
    static final int MATCHOVER_INJECT_MAX = 16_384;

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
     * live fleet list + destroyed set — at most {@link #MATCHOVER_INJECT_MAX} rows, the rest
     * counted refused ({@link #drainLedger}). Per-entry fail-soft; one summary log line.
     */
    private void ensureDestroyedRoster(CombatEngineAPI engine, int ownSide) {
        int queued = destroyedIds.size();
        if (!ROSTER_ON || queued == 0) {
            PilotWatchLog.info("cc-results-roster injected=0 queued=" + queued
                    + (ROSTER_ON ? "" : " (knob off)"));
            return;
        }
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
        drainLedger(id -> injectOne(id, mgrs, credited, ownSide), queued);
    }

    /** One queued id of the injection sweep (the drain's
     *  per-entry body, moved out unchanged so the capped drain runs game-free): join, filter, dedupe,
     *  inject; returns the id's outcome. A throw is counted failed by the drain. Render thread. */
    private int injectOne(Long id, com.fs.starfarer.combat.CombatFleetManager[] mgrs,
            java.util.Map<String, Integer> credited, int ownSide) {
        String variantId = variantById.get(id);
        if (variantId == null) {
            return UNKNOWN; // no id-contract row (unexpected server id) — skip, counted
        }
        ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
        if (variant != null && variant.getHullSpec() != null
                && variant.getHullSpec().getHullSize() == ShipAPI.HullSize.FIGHTER) {
            return FIGHTER; // vanilla-faithful: the fleet manager's !isFighter() gate — ships only
        }
        // The registry row is read AFTER the fighter gate — a killed
        // fighter's entry is REAPED from remoteEntities one frame after its kill (its
        // variant row stays), so it must classify as `fighter`, never as `unknown`.
        PredictReconcileCombatPlugin.RemoteEntity re = remoteEntities.get(id);
        if (re == null) {
            return UNKNOWN; // a non-fighter id with no registry row (unexpected server id)
        }
        int owner = re.side == 0 ? ownSide : 1 - ownSide;
        String key = owner + "|" + variantId; // wire-scrub: not a sink (map key)
        Integer have = credited.get(key);
        if (have != null && have.intValue() > 0) {
            credited.put(key, Integer.valueOf(have.intValue() - 1));
            return DEDUP; // the engine already shows one of these — don't double-row it
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
        return INJECTED;
    }

    /** One queued id's outcome at the drive; each is a counter index of {@link #drainLedger}'s result
     *  and a token of the summary row. */
    static final int INJECTED = 0;
    static final int DEDUP = 1;
    static final int FIGHTER = 2;
    static final int UNKNOWN = 3;
    static final int FAILED = 4;
    static final int REFUSED = 5;

    /** Classifies one queued DESTROYED id and injects its debrief row when it earns one; returns
     *  one of the outcome codes above (never {@link #REFUSED}, which only the drain assigns). */
    interface RowInjector {
        int inject(Long id);
    }

    /**
     * Drains the ledger through {@code injector} under
     * {@link #MATCHOVER_INJECT_MAX}. The cap counts INJECTED rows only (a fighter, a duplicate or an
     * unknown id costs no row); once it is reached every remaining id is polled unclassified and counted
     * refused (fighters and duplicates among them are not sorted out — the cap spares exactly that work),
     * so the ledger always empties. A throwing entry counts failed and never stops the drain. Writes the
     * drive's one summary row (its {@code refused=} token always printed) and, on a refusal, the one-shot
     * announce. Returns the counters by outcome code. Render thread (ids keep arriving from the net
     * thread while it drains, as before the cap).
     */
    int[] drainLedger(RowInjector injector, int queued) {
        int[] n = new int[REFUSED + 1];
        for (Long id = destroyedIds.poll(); id != null; id = destroyedIds.poll()) {
            if (n[INJECTED] >= MATCHOVER_INJECT_MAX) {
                n[REFUSED]++;
                continue;
            }
            int outcome;
            try {
                outcome = injector.inject(id);
            } catch (Throwable t) {
                outcome = FAILED; // per-entry fail-soft: one bad variant never costs the rest
            }
            n[outcome]++;
        }
        PilotWatchLog.info(summaryRow(n, queued));
        if (n[REFUSED] > 0) {
            FallbackAnnounce.announceOnce("matchover-inject-cap", "debrief row cap ("
                    + MATCHOVER_INJECT_MAX + " rows a drive) reached: refused=" + n[REFUSED]
                    + " queued=" + queued + " (not injected, counted)");
        }
        return n;
    }

    /** The drive's one summary row, counters by outcome code; {@code refused=} is printed even at
     *  zero, so a capped drive is never silent and every drive states it. */
    static String summaryRow(int[] n, int queued) {
        return "cc-results-roster injected=" + n[INJECTED] + " dedup=" + n[DEDUP]
                + " fighter=" + n[FIGHTER] + " unknown=" + n[UNKNOWN] + " failed=" + n[FAILED]
                + " refused=" + n[REFUSED] + " queued=" + queued;
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

    /**
     * The per-SESSION entry cap of the plugin's content
     * catalog (wire content id to engine spec id) — the session's other server-fed id join, kept beside
     * the results-roster ledger's cap. The server mints one id per installed spec (about 590 on a vanilla
     * install, one registry per serve) and the catalog outlives round resets, so it is capped per session
     * at {@link RosterAppendLane#MAX_ROUND_ENTITIES} entries (about 110x). A NEW id past the cap is
     * refused (not stored) and counted ONCE per distinct id: the dictionary re-announces in cycles, so a
     * per-announcement count would grow without bound. The remembered refused ids are bounded by the same
     * constant; a new refused id past THAT bound counts as untracked (repeats included, the totals row says
     * so). An existing key's re-announce still updates (nothing grows). Every catalog reader already
     * treats an absent id as unresolved, so a refused id costs only its presented spec. The session's
     * first refusal writes ONE error row plus the one-shot announce; {@link #teardown} writes the session
     * totals and re-arms. Content ids are signed 32-bit wire values, either sign; spec ids (wire text) are
     * never logged. Render thread only, the catalog's own thread.
     */
    static final class ContentCatalogCap {

        private final java.util.Set<Integer> refusedIds = new java.util.HashSet<Integer>();
        private int refusedUntracked;

        /** Merges one fresh frame's announced entries into {@code catalog} under the cap — the
         *  catalog's only writer on the live path. */
        void admit(java.util.Map<Integer, String> catalog, java.util.Map<Integer, String> announced) {
            for (java.util.Map.Entry<Integer, String> e : announced.entrySet()) {
                Integer id = e.getKey();
                if (catalog.size() < RosterAppendLane.MAX_ROUND_ENTITIES || catalog.containsKey(id)) {
                    catalog.put(id, e.getValue());
                } else if (refusedIds.size() < RosterAppendLane.MAX_ROUND_ENTITIES) {
                    if (refusedIds.add(id) && refusedIds.size() == 1) {
                        String detail = "content catalog cap (" + RosterAppendLane.MAX_ROUND_ENTITIES
                                + " entries this session) reached: refused content id=" + id
                                + " (not stored; later refusals are counted once per id)";
                        PilotWatchLog.error("predict " + detail, null);
                        FallbackAnnounce.announceOnce("content-catalog-cap", detail);
                    }
                } else if (!refusedIds.contains(id)) {
                    refusedUntracked++;
                }
            }
        }

        /** Distinct ids refused this session, each counted once. */
        int refusedIds() {
            return refusedIds.size();
        }

        /** Refused announcements of ids the full refused-id set could not remember (repeats included). */
        int refusedUntracked() {
            return refusedUntracked;
        }

        /** Session teardown: ONE totals row (written at zero too, so every session states its catalog
         *  size), then the refusal state re-arms for the next session. */
        void teardown(int catalogSize) {
            PilotWatchLog.info("predict content-catalog teardown: entries=" + catalogSize + " cap="
                    + RosterAppendLane.MAX_ROUND_ENTITIES + " refused_ids=" + refusedIds.size()
                    + " refused_untracked=" + refusedUntracked);
            refusedIds.clear();
            refusedUntracked = 0;
        }
    }

}
