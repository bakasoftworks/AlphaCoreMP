package cc.starsector.client;

import com.fs.starfarer.api.combat.MissileAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cc.starsector.client.net.ServerMissileState;

/**
 * The SKIP-SET end-confession
 * link/removal collaborator
 * ({@link MissilePoseLane}-owned, the FFC backref shape).
 *
 * <p>Why it exists (a defect pinned in a live match): the
 * enroll skip-set — engine-slotted unguided ordnance (reaper/annihilator class:
 * {@code !isGuided() && !isFlare() && !engineLess()}) — flies PURE-LOCAL per the
 * ballistic law: composed at the discharge confession, never puppeted, never pose-linked.
 * So when the SERVER's copy hit a ship, the victim seat saw its locally-composed copy sail
 * through and expire while hull damage landed silently via the ledger (phantom damage), and
 * GPEE v7's MISSILE_END confession for it was thrown away twinless
 * ({@link SourcedMissileEndLane#onEnd} {@code t == null} branch). This lane gives the
 * confession a consumer: it remembers the composed bolts pair-keyed, links them to their
 * wire ids off the snapshot missile records (same FIFO/ascending-id claim law as the twin
 * matcher), and at the confessed end force-removes a still-flying local bolt +
 * presents the impact at the confessed pose (victim snap incl. the own ship) — or, when the
 * local sim already ended its copy (agreeing seats), consumes quietly so the
 * double-present class stays suppressed.
 *
 * <p>Damage-less presentation throughout (the safety property) — hull truth stays on
 * the wire mirror; removal uses {@code removeEntity} (despawn, no warhead — the
 * {@link MissilePoseLane#retire} precedent). Render-thread only, fail-soft; bounded state:
 * unlinked registrations expire on the {@link MissilePoseLane#LINK_WINDOW_TICKS} window,
 * linked tracks are pruned when both the local body and the wire record are gone (a lost
 * end datagram degrades to today's fly-to-expiry, never a leak), and the consumed-id
 * freshness ledger mirrors the per-round discipline of MissilePoseLane's consumed-id guard.
 */
final class BallisticEndLinkLane {

    /** One composed skip-set bolt awaiting/holding its wire-id link. */
    private static final class Track {
        final MissileAPI missile;
        final long pairKey;
        final long spawnTick;
        final String specId;
        long wireId = -1L;
        long lastSeenTick = Long.MIN_VALUE;
        /** one-shot latch: the served FIZZLING edge already reached this bolt. */
        boolean fizzled;
        /** The DUD verdict last written by {@link #relayFizzle}; a fresh
         *  FIZZLING record whose bit differs re-applies the arming (the bit moves with the arming clock). */
        boolean dudApplied;

        Track(MissileAPI missile, long pairKey, long spawnTick, String specId) {
            this.missile = missile;
            this.pairKey = pairKey;
            this.spawnTick = spawnTick;
            this.specId = specId;
        }
    }

    /** One buffered twinless confessed end awaiting a late link/twin-adopt:
     *  the snap/ctrl reorder race delivers the end BEFORE the record that would have linked
     *  it — the retry window turns that race from a drop into a heal. */
    private static final class PendingEnd {
        final cc.starsector.client.net.ClientGameplayEdgeEvent event;
        final long bufferedTick;
        /** Dictionary spec from a late record sighting (null = never crossed) — feeds the
         *  structural present's prox/canister/conversion exclusions; bare-pose when null. */
        String specId;
        /** Wire owner from the same late sighting (default 1 = foe, the commissioned
         *  victim-seat class) — steers the impact snap toward the right side's hulls. */
        int owner = 1;

        PendingEnd(cc.starsector.client.net.ClientGameplayEdgeEvent event, long bufferedTick) {
            this.event = event;
            this.bufferedTick = bufferedTick;
        }
    }

    /** Wire-input bound for {@link #pendingEnds} (security standing rule: bounded allocation
     *  on every wire-fed map): far above any honest same-window twinless-end volume. */
    private static final int PENDING_END_CAP = 256;

    private final MissilePoseLane mpl;

    /** CL-2 pending twinless ends by wire id (retry each fresh maintain frame; expiry =
     *  {@link MissilePoseLane#LINK_WINDOW_TICKS} — the same reorder-race window the link
     *  machinery already trusts). */
    private final Map<Long, PendingEnd> pendingEnds = new HashMap<Long, PendingEnd>();

    /** Registrations without a wire id yet (FIFO by spawnTick at the link pass). */
    private final List<Track> unlinked = new ArrayList<Track>();
    /** Linked tracks by wire id — the confession's O(1) resolve. */
    private final Map<Long, Track> byWireId = new HashMap<Long, Track>();
    /** Wire ids ever claimed here (per-round; the mint never recycles):
     *  a post-consume stale record must never re-claim a different local bolt. */
    private final Set<Long> consumedIds = new HashSet<Long>();

    private int endBuffered;      // predict_missile_ballistic_end_buffered (CL-2 intake)
    private int endLateTwin;      // predict_missile_ballistic_end_late_twin (twin claimed late)
    private int endLateLink;      // predict_missile_ballistic_end_late_link (track linked late)
    private int endStructuralFx;  // predict_missile_ballistic_end_structural_fx (expiry present)
    private int endDupSuppressed; // predict_missile_ballistic_end_dup_suppressed (double-present bar)
    private int endExpiredDrop;   // predict_missile_ballistic_end_expired_drop (honest drop)
    private int registered;       // predict_missile_ballistic_end_reg
    private int linked;           // predict_missile_ballistic_end_linked
    private int linkExpired;      // predict_missile_ballistic_end_link_expired
    private int crossSpecRefused; // predict_missile_ballistic_end_cross_spec
    private int endRemoved;       // predict_missile_ballistic_end_removed (alive → forced out)
    private int endFlameout;      // predict_missile_ballistic_end_flameout (alive, unnamed FIZZLING → flameOut + dud, fades in play)
    private int flameOutFailures; // predict_missile_ballistic_end_flameout_fail (flameOutDud threw → removed;
                                  //   Also a relayFizzle throw — the bolt keeps its own clock + coin for that frame
                                  //   and stays UN-relayed so the END fallback still flames it out)
    // the bolt relay of the served FIZZLING edge + its DUD share.
    private int boltFizzleRelays; // predict_missile_ballistic_fizzle_relayed (served FIZZLING edges)
    private int boltDudRelays;    // predict_missile_ballistic_dud_relayed (the share whose record said DUD)
    private int boltDudFlips;     // predict_missile_ballistic_dud_flips (the bit moved after the
                                  //   relay latch — arming clock — and was re-applied)
    private int endDeathPuff;     // predict_missile_ballistic_end_death_puff
    private int endLocalGone;     // predict_missile_ballistic_end_local_gone (quiet consume)
    private int impactFx;         // predict_missile_ballistic_end_impact_fx
    private int trackLost;        // predict_missile_ballistic_end_lost (end frame never came)
    private int endOwnSourcedSkip;// predict_missile_ballistic_end_own_sourced_skip
    /** The own-sourced EXPIRY skips whose END
     *  was INTACT-class with a named victim (the server's hit the local body must present). */
    private int endOwnSourcedSkipVictim; // predict_missile_ballistic_end_own_sourced_skip_victim
    // The END-intake own-victim presenter.
    private int endOwnVictimPresented; // predict_missile_ballistic_end_own_victim_presented (handled at intake)
    private int endOwnVictimNoBody;    // predict_missile_ballistic_end_own_victim_no_body (own victim end, no own body found)
    private int ownVictimFindFailures; // predict_missile_ballistic_end_own_victim_find_fail (findOwnLocalBody throws)
    // Two or more own bodies of the END's spec within reach = AMBIGUOUS — the
    // end buffers as residue (never a guessed body, never a salvo-stealing cascade); metered.
    private int endOwnVictimAmbiguous; // predict_missile_ballistic_end_own_victim_ambiguous
    private boolean lastFindAmbiguous;
    // The salvo-sibling refusals — residue,
    // never a guess (the END buffers and the expiry skip counts it as before).
    private int endOwnVictimNoSpec;        // predict_missile_ballistic_end_own_victim_no_spec (null wire spec: fail CLOSED)
    private int endOwnVictimRecentLocalHit; // predict_missile_ballistic_end_own_victim_recent_local_hit (same spec landed on the victim locally)
    private int ownLocalHitNotes;          // predict_missile_ballistic_end_own_local_hit_noted (ring writes)
    private int ownLocalHitNoteFailures;   // predict_missile_ballistic_end_own_local_hit_note_fail (feed throws)
    // The round-robin write evicted an entry still
    // inside the widest refusal window (matchTolTicks) — a sibling END may present (never silent).
    private int ownLocalHitRingOverwriteLive; // predict_missile_ballistic_end_own_local_hit_ring_overwrite_live

    /** The recent OWN local-hit ring bound
     *  (a code constant, not a knob): own missile hits recorded at the engine's damage seam, keyed
     *  victim (+ its parent station) + spec. Round-robin overwrite past the bound. */
    static final int OWN_HIT_RING_CAP = 64;
    /** The FLOOR of the ring's prune age (in
     *  snapshot sim ticks). The fixed 120 alone
     *  is NOT above the widest refusal window ({@link DischargeReconcileLaw#matchTolTicks} = 1 s of
     *  ticks, 240 at 240 tps) — the live prune bound is {@link #ownHitPruneTicks}, the max of both. */
    static final int OWN_HIT_RING_PRUNE_TICKS = MissilePoseLane.LINK_WINDOW_TICKS;

    /** The ring's prune age, pure (smoke P7d):
     *  never below the widest refusal window {@code matchTolTicks}, never below the 120 t floor. */
    static long ownHitPruneTicks(long matchTolTicks) {
        return Math.max(OWN_HIT_RING_PRUNE_TICKS, matchTolTicks);
    }
    private final Object[] ownHitTarget = new Object[OWN_HIT_RING_CAP];
    private final Object[] ownHitParent = new Object[OWN_HIT_RING_CAP];
    private final String[] ownHitSpec = new String[OWN_HIT_RING_CAP];
    private final long[] ownHitTick = new long[OWN_HIT_RING_CAP];
    private int ownHitNext;

    /** Wire ids whose record
     *  carried the SESSION ship as source, by last-seen tick. The seat's own AUTOFIRE / engine-native
     *  fire is never enrolled ({@code FireLaneCoordinator.executeAdopt} (e): own PREDICTED missiles
     *  keep zero-RTT local flight, no twin, no skip-set track), so its wire END arrives twinless and
     *  linkless and fell into the CL-2 buffer — 121 t later the structural expiry PAINTED a second
     *  impact at the server's pose (16/16 own phase charges, spec unknown by then: the record was
     *  gone before the retry ever sighted it). The local body IS that end's presentation (its own
     *  fuse / contact / fade), so these ids are skipped.
     *  The skip is decided at EXPIRY now (not intake), so the map is pruned on
     *  {@link #OWN_SOURCED_PRUNE_TICKS} (3 windows) to still hold the sighting then; wire ids never
     *  recycle within a round. Bounded by the live own-sourced id count × that. */
    private final Map<Long, Long> ownSourcedSeen = new HashMap<Long, Long>();
    /** The wire record's spec per own-sourced id (the content catalogue's
     *  id, recorded once per id in {@link #noteOwnSourced}) — the own-body finder requires a spec
     *  match, so a sabot's END can never present through a harpoon's body. Absent/null = unknown =
     *  fails CLOSED for the own-victim presenter ({@link #crossSpec} for the
     *  skip-set link still fails open). */
    private final Map<Long, String> ownSourcedSpec = new HashMap<Long, String>();

    /** {@link #pendingIntake} verdicts (pure; smoke ProxChargeDoorLawSmoke P7). */
    static final int INTAKE_BUFFER = 0;
    static final int INTAKE_DROP_FULL = 1;
    // INTAKE_OWN_SOURCED (2) retired — the
    // own-sourced verdict is an EXPIRY verdict now (expiryVerdict / EXPIRY_OWN_SOURCED_SKIP).

    BallisticEndLinkLane(MissilePoseLane mpl) {
        this.mpl = mpl;
    }

    /** Enroll-skip registration (the ballistic exit — {@code MissilePoseLane.enroll}):
     *  the composed bolt is in hand exactly once, here. */
    void register(MissileAPI missile, long sourceEntityId, int sourceMountIndex,
            long spawnTick, String specId) {
        unlinked.add(new Track(missile, MissilePoseLane.pairKey(sourceEntityId, sourceMountIndex),
                spawnTick, specId));
        registered++;
    }

    /**
     * Per-fresh-frame link + prune (called from {@code MissilePoseLane.maintain} after the
     * adopt/absence/presence passes): oldest unclaimed registration per source pair claims the
     * oldest unclaimed matching-pair record in ascending networkId (birth order — the twin
     * matcher's claim law), refusing consumed ids, twin-claimed ids, and cross-spec
     * records (a MIRV child on its launcher's attribution).
     */
    void linkPass(Map<Long, ServerMissileState> byId, long tick) {
        noteOwnSourced(byId, tick); // Before any consumer of this frame can ask
        for (Iterator<Track> it = byWireId.values().iterator(); it.hasNext();) {
            Track tr = it.next();
            ServerMissileState rec = byId.get(tr.wireId);
            if (rec != null) {
                tr.lastSeenTick = tick;
                relayFizzle(tr, rec);
            } else if ((mpl.plugin.engine == null || !mpl.plugin.engine.isEntityInPlay(tr.missile))
                    && tick - tr.lastSeenTick > MissilePoseLane.LINK_WINDOW_TICKS) {
                // Local body gone AND record absent past the grace with no confession consumed:
                // the end datagram was lost (CTRL is fire-and-forget) — drop, count, move on.
                it.remove();
                trackLost++;
            }
        }
        for (Iterator<Track> it = unlinked.iterator(); it.hasNext();) {
            Track tr = it.next();
            if (tr.spawnTick < 0L || tick - tr.spawnTick > MissilePoseLane.LINK_WINDOW_TICKS) {
                it.remove(); // the link-window expiry law incl. the negative-sentinel guard
                linkExpired++;
            }
        }
        if (unlinked.isEmpty()) {
            return;
        }
        unlinked.sort((a, b) -> Long.compare(a.spawnTick, b.spawnTick));
        List<ServerMissileState> ordered = new ArrayList<ServerMissileState>(byId.values());
        ordered.sort((a, b) -> Long.compare(a.networkId(), b.networkId()));
        for (Iterator<Track> it = unlinked.iterator(); it.hasNext();) {
            Track tr = it.next();
            for (ServerMissileState rec : ordered) {
                long id = rec.networkId();
                if (!rec.hasSource() || byWireId.containsKey(id) || consumedIds.contains(id)
                        || mpl.twinByWireId.containsKey(id)) {
                    continue;
                }
                if (MissilePoseLane.pairKey(rec.sourceEntityId(), rec.sourceMountIndex())
                        != tr.pairKey) {
                    continue;
                }
                if (crossSpec(tr, rec)) {
                    crossSpecRefused++;
                    continue;
                }
                tr.wireId = id;
                tr.lastSeenTick = tick;
                byWireId.put(Long.valueOf(id), tr);
                consumedIds.add(Long.valueOf(id));
                it.remove();
                linked++;
                // A record already FIZZLING at the claim relays at once
                relayFizzle(tr, rec);
                break;
            }
        }
    }

    /** The bolt fizzle relay, one-shot per track: a pure-local
     *  bolt never pose-adopts, so its OWN (un-aged) clock and OWN birth coin decided its burnout and
     *  its arming — a live match had one local-only burnt Hammer hit whose server twin flew on and
     *  faded. On the served FIZZLING edge the bolt takes the SERVER twin's dud verdict
     *  ({@link MissileDudRelayLaw#armedOnRelay}) and then the engine's own {@code flameOut()} death arc
     *  (idempotent on a body whose own clock already fizzled: only its arming changes). Counted in
     *  meter mode; the engine writes ride {@link MissilePoseLane#MISSILE_POSE_APPLY}. A record with no
     *  FIZZLING bit is today's path; nothing is held or delayed. Two refinements: first,
     *  the wire bit is {@code !isArmed()} and moves with the arming clock, so a fresh record of a relayed
     *  bolt whose bit moved re-applies the arming ({@link MissileDudRelayLaw#rearmOnFresh}); second, a
     *  thrown relay leaves the bolt UN-relayed (retried on the next fresh frame, counted per throw), so
     *  the no-victim END fallback still runs its own {@code flameOut()}. */
    private void relayFizzle(Track tr, ServerMissileState rec) {
        if (!rec.fizzling()) {
            return;
        }
        if (tr.fizzled) {
            if (MissileDudRelayLaw.rearmOnFresh(true, true, rec.dud(), tr.dudApplied)) {
                if (MissilePoseLane.MISSILE_POSE_APPLY) {
                    try {
                        tr.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                    } catch (Throwable e) {
                        // NOT recorded as applied — retried on the next fresh frame
                        flameOutFailures++;
                        FallbackAnnounce.announceOnce("ballistic-fizzle-rearm-" + e.getClass().getSimpleName(),
                                "ballistic fizzle re-arm threw " + e.getClass().getSimpleName()
                                        + " -> the bolt keeps the verdict it had this frame");
                        return;
                    }
                }
                tr.dudApplied = rec.dud();
                boltDudFlips++;
            }
            return;
        }
        if (MissilePoseLane.MISSILE_POSE_APPLY) {
            try {
                tr.missile.setArmedWhileFizzling(MissileDudRelayLaw.armedOnRelay(true, rec.dud()));
                tr.missile.flameOut();
            } catch (Throwable e) {
                // NOT latched — the END fallback (consumeFlameOut) still flames it out
                flameOutFailures++;
                FallbackAnnounce.announceOnce("ballistic-fizzle-relay-" + e.getClass().getSimpleName(),
                        "ballistic fizzle relay threw " + e.getClass().getSimpleName()
                                + " -> the bolt keeps its own clock and coin this frame");
                return;
            }
        }
        tr.fizzled = true;
        tr.dudApplied = rec.dud();
        boltFizzleRelays++;
        if (rec.dud()) {
            boltDudRelays++;
        }
    }

    /** The twin matcher's spec-truth refusal, track-shaped: either side unknown
     *  fails OPEN to the pair-window law. */
    private boolean crossSpec(Track tr, ServerMissileState rec) {
        if (tr.specId == null || rec.contentId() < 1) {
            return false;
        }
        String wireSpec = mpl.plugin.contentCatalog.get(rec.contentId());
        return wireSpec != null && !wireSpec.equals(tr.specId);
    }

    /**
     * The kind-6 confession consumer for a twinless end ({@link SourcedMissileEndLane#onEnd}
     * delegates here after the store hygiene take). TRUE iff a linked track consumed it:
     * a still-flying local bolt presents the impact (INTACT ends, shared geometry recipe —
     * victim snap incl. the own ship) or its native shoot-down death (DESTROYED ends,
     * {@link SourcedMissileEndLane#presentDeathPuff} — 1293), and leaves the same
     * tick (the prompt-removal law: the wire said it no longer exists; the removeEntity
     * fallback stays behind an in-play guard for the under-kill case) — EXCEPT the FIZZLING +
     * no-victim range-out ({@link #consumeVerdict} → {@link #flameOutDud}): that
     * dud stays IN PLAY, unarmed, and fades over the spec's flameoutTime (the tail is
     * measured as {@code dmg-shot-out outTick} − the CONSUME tick on {@code local=flameout});
     * a local-gone bolt consumes QUIETLY (the local sim already presented its own end —
     * painting a second impact would be the double-present).
     */
    boolean onConfessedEnd(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        Track tr = byWireId.remove(Long.valueOf(event.entityId()));
        if (tr == null) {
            return false;
        }
        boolean alive = mpl.plugin.engine != null && mpl.plugin.engine.isEntityInPlay(tr.missile);
        // The alive-body act is a pure verdict — a FIZZLING end the server pinned on
        // NO victim is vanilla's range-out (flameOut + dud, the body fades in play); every other alive
        // end takes the presenters and leaves this tick as before. `present=` on the row names what
        // showed (the door taken / flameout / none) — a player's "disappeared weirdly" class is graded
        // from this token.
        int verdict = consumeVerdict(alive, event.endCauseCode(), event.endVictimWireId());
        String present;
        boolean flamed = false;
        if (verdict == CONSUME_FLAMEOUT && MissilePoseLane.MISSILE_POSE_APPLY) {
            flamed = flameOutDud(tr.missile, MissileDudRelayLaw.consumeFlameOut(tr.fizzled));
        }
        if (flamed) {
            endFlameout++;
            present = consumeToken(verdict, true, null, false);
        } else if (alive) {
            FoeImpactDoorLaw.Door door = mpl.sourcedEnd.presentImpactFxDoor(tr.specId,
                    SourcedMissileEndLane.ownerOf(tr.missile), tr.wireId, event, "ballistic", tr.missile);
            if (door != null) {
                impactFx++;
            }
            boolean puff = mpl.sourcedEnd.presentDeathPuff(tr.specId, tr.missile, tr.wireId, event,
                    "ballistic");
            if (puff) {
                endDeathPuff++;
            }
            if (MissilePoseLane.MISSILE_POSE_APPLY
                    && mpl.plugin.engine.isEntityInPlay(tr.missile)) {
                mpl.plugin.engine.removeEntity(tr.missile);
            }
            endRemoved++;
            present = consumeToken(verdict, false, door, puff);
        } else {
            endLocalGone++;
            present = consumeToken(verdict, false, null, false);
        }
        mpl.lane.log("predict v16 ballistic-end CONSUME: id=" + tr.wireId
                + " spec=" + tr.specId + " cause=" + event.endCauseCode()
                + " tick=" + event.simTick()
                + " at=" + (int) event.endX() + "," + (int) event.endY()
                + " local=" + (flamed ? "flameout" : alive ? "removed" : "gone")
                + " present=" + present
                + (MissilePoseLane.MISSILE_POSE_APPLY ? "" : " (meter)"));
        return true;
    }

    /** {@link #consumeVerdict} codes. */
    static final int CONSUME_QUIET = 0;    // local body already gone: the local sim presented its own end
    static final int CONSUME_FLAMEOUT = 1; // alive + FIZZLING + no server victim: vanilla's range-out, in play
    static final int CONSUME_DOOR = 2;     // alive, every other end: the presenters, then leave this tick

    /** The alive-body act, pure: a FIZZLING end the server pinned on NO victim
     *  is a range-out the local body should run itself ({@link #CONSUME_FLAMEOUT}); a FIZZLING end WITH
     *  a victim is the armed-flameout contact ({@code SourcedMissileEndLane.fizzlingVictimAdmit}) and
     *  every INTACT/SHIELD/DESTROYED end is an impact or a kill — both {@link #CONSUME_DOOR}. */
    static int consumeVerdict(boolean alive, int cause, long victimWireId) {
        if (!alive) {
            return CONSUME_QUIET;
        }
        if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING && victimWireId <= 0L) {
            return CONSUME_FLAMEOUT;
        }
        return CONSUME_DOOR;
    }

    /** The CONSUME row's {@code present=} token, pure: {@code quiet} (local sim
     *  presented), {@code flameout}, {@code shield} (the own-shield PRESENT door), the door's own
     *  lower-case name for every other presented door, {@code none:<why>} when nothing showed. */
    static String consumeToken(int verdict, boolean flamed, FoeImpactDoorLaw.Door door, boolean deathPuff) {
        if (verdict == CONSUME_QUIET) {
            return "quiet";
        }
        if (flamed) {
            return "flameout";
        }
        if (verdict == CONSUME_FLAMEOUT) {
            return "none:armed-fizzle"; // the spec cannot be disarmed (or meter mode): today's removal
        }
        if (door == null) {
            return deathPuff ? "death_puff" : "none:no-door"; // a shown DESTROYED puff is named
        }
        if (door == FoeImpactDoorLaw.Door.PRESENT_OWN_SHIELD) {
            return "shield";
        }
        return door.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Vanilla's own range-out on a foe body whose SERVER twin fizzled with no
     *  victim: {@code flameOut()} (what the engine calls at flight time past max; the body's
     *  next advance runs the engine's flameout FX, fade and afterFlameout collision class)
     *  under the dud switch ({@code setArmedWhileFizzling(false)}: the fading body can no longer land
     *  a hit the server never dealt). A spec that cannot be disarmed ({@code dudProbabilityOnFlameout}
     *  0 — the setter is a no-op) keeps today's removal. TRUE = flamed out
     *  (the body stays in play, fading; nothing here references it again). A
     *  bolt the served FIZZLING edge already reached ({@link #relayFizzle}) is fizzling on the server's
     *  arming — {@code callFlameOut} is false and it is only DISARMED here (its twin faded, no victim). */
    private boolean flameOutDud(com.fs.starfarer.api.combat.MissileAPI m, boolean callFlameOut) {
        try {
            m.setArmedWhileFizzling(false);
            if (m.isArmedWhileFizzling() || m.isForceAlwaysArmed()) { // forceAlwaysArmed bypasses the dud
                return false;
            }
            if (callFlameOut) {
                m.flameOut();
            }
            return true;
        } catch (Throwable e) {
            // presentation lane: the removal below stays the honest fallback — counted + once per class (F4)
            flameOutFailures++;
            FallbackAnnounce.announceOnce("ballistic-flameout-" + e.getClass().getSimpleName(),
                    "flameOutDud threw " + e.getClass().getSimpleName() + " -> the body was removed instead");
            return false;
        }
    }

    /**
     * CL-2 intake — a twinless, linkless confessed end buffers here instead of
     * dropping ({@link SourcedMissileEndLane#onEnd} twinless tail). Overflow past the wire
     * bound refuses the NEW entry and keeps the honest drop announce (fail-closed on spam).
     */
    void bufferPending(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        long id = event.entityId();
        // The
        // intake verdict is ONLY the buffer bound now; the own-sourced verdict moved to EXPIRY
        // (retryPending) so the CL-2 heals (late twin / late link) still fire for own ids and a
        // record first sighted AFTER its END is still recognized own-sourced before any paint.
        // An OWN unenrolled INTACT end naming a
        // victim presents HERE, at intake, through the engine's own damage path — never at the 2 s
        // expiry (NEVER-ADD-LATENCY). Handled ends never buffer; the rest buffer as before.
        if (presentOwnVictimAtIntake(id, event)) {
            return;
        }
        int verdict = pendingIntake(pendingEnds.size() >= PENDING_END_CAP);
        if (verdict == INTAKE_DROP_FULL) {
            endExpiredDrop++;
            FallbackAnnounce.announceOnce("f3-twinless-end-dropped",
                    "confessed end dropped: pending buffer full (first id=" + id + ")");
            return;
        }
        pendingEnds.put(Long.valueOf(id), new PendingEnd(event, event.simTick()));
        endBuffered++;
    }

    /** {@link #ownVictimIntakeVerdict} codes. */
    static final int OWN_VICTIM_PRESENT = 0;
    static final int OWN_VICTIM_BUFFER_NOT_VICTIM_END = 1;
    static final int OWN_VICTIM_BUFFER_NOT_OWN = 2;
    static final int OWN_VICTIM_BUFFER_NO_BODY = 3;

    /**
     * The END-intake own-victim verdict, pure
     * (smoke ProxChargeDoorLawSmoke P7c). PRESENT only for an INTACT-class end naming a victim, whose
     * id was sighted own-sourced, with an own local body found; else the end buffers as before (a
     * not-victim end is never this arm's business; not-own = the CL-2 machinery's; no-body = the
     * metered expiry residue, the twinless law: counted, never applied).
     */
    static int ownVictimIntakeVerdict(boolean ownSourcedNow, boolean intact, boolean victimNamed,
            boolean bodyFound) {
        if (!intact || !victimNamed) {
            return OWN_VICTIM_BUFFER_NOT_VICTIM_END;
        }
        if (!ownSourcedNow) {
            return OWN_VICTIM_BUFFER_NOT_OWN;
        }
        return bodyFound ? OWN_VICTIM_PRESENT : OWN_VICTIM_BUFFER_NO_BODY;
    }

    /** The own-body finder's base reach (the
     *  impact lane's {@code IMPACT_SLACK_SU} class). */
    static final float OWN_BODY_SLACK_SU = 30f;
    /** The speed-scaled lead the finder adds —
     *  own fire flies zero-RTT local, so at END intake a body that missed locally sits ~one RTT of
     *  flight PAST the server's end pose; 0.25 s covers a 250 ms RTT. */
    static final float OWN_BODY_LEAD_SECONDS = 0.25f;

    /** The finder's reach for a body moving at
     *  {@code speed} su/s, pure (smoke P7c). Non-finite/negative speed falls back to the slack. */
    static float ownBodyReach(float speed) {
        return OWN_BODY_SLACK_SU + (speed > 0f && speed < Float.POSITIVE_INFINITY
                ? speed * OWN_BODY_LEAD_SECONDS : 0f);
    }

    /**
     * The END-intake presenter for the seat's
     * OWN unenrolled fire (no twin, no track: {@code FireLaneCoordinator.executeAdopt} (e)). Before
     * this, an INTACT own end naming a victim buffered and was only COUNTED at the 2 s expiry
     * ({@code _own_sourced_skip_victim}) — the server's hit reached the firer's seat as the silent hull
     * fold. It runs at intake only, one-off per END (never per tick; NEVER-ADD-LATENCY). The hit
     * rides {@link SourcedMissileEndLane#presentOwnVictimHitBody} → {@code applyWireAckedHit} (the
     * engine's own {@code applyDamage}) with the LOCAL body's own pre-armor
     * {@code getDamageAmount}/type/EMP, applied once — the no-re-apply rule for a post-armor wire amount is NOT triggered
     * (the END carries no amount). Handled ⇒ the id is consumed (expiry dup bar) and the body leaves
     * play (it must not land a second, local hit — the twin path's same trade). TRUE = handled.
     */
    private boolean presentOwnVictimAtIntake(long id,
            cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (!MissilePoseLane.MISSILE_POSE_APPLY || mpl.plugin.engine == null) {
            return false; // meter mode: the body is not ours to remove
        }
        boolean intact = event.endedIntact();
        boolean victimNamed = event.endVictimWireId() > 0L;
        boolean own = ownSourcedFor(ownSourcedSeen.get(Long.valueOf(id)), event.simTick());
        if (ownVictimIntakeVerdict(own, intact, victimNamed, true) != OWN_VICTIM_PRESENT) {
            return false;
        }
        // (1) no wire spec ⇒ no sibling link
        // (fail CLOSED — the spec match is the only thing tying a body to THIS id); (2) an own body of
        // the same spec already landed on this victim locally inside the RTT window ⇒ the local sim
        // presented this burst's hit itself, and the in-reach body now is its salvo SIBLING, so
        // refuse. Both are metered residue: the END buffers, the expiry skip counts it.
        String wireSpec = ownSourcedSpec.get(Long.valueOf(id));
        if (wireSpec == null) {
            endOwnVictimNoSpec++;
            return false;
        }
        if (recentOwnLocalHit(event, wireSpec)) {
            endOwnVictimRecentLocalHit++;
            return false;
        }
        MissileAPI body = findOwnLocalBody(event, wireSpec);
        if (ownVictimIntakeVerdict(own, intact, victimNamed, body != null) != OWN_VICTIM_PRESENT) {
            if (lastFindAmbiguous) {
                endOwnVictimAmbiguous++; // Siblings in reach — residue, never a guess
            } else {
                endOwnVictimNoBody++;
            }
            return false;
        }
        if (!mpl.sourcedEnd.presentOwnVictimHitBody(body, id, MissilePoseLane.specIdOf(body), event,
                "cls=ballistic-own")) {
            return false; // the ladder's own meters named why; the expiry skip counts the residue
        }
        consumedIds.add(Long.valueOf(id));
        if (mpl.plugin.engine.isEntityInPlay(body)) {
            mpl.plugin.engine.removeEntity(body);
        }
        endOwnVictimPresented++;
        return true;
    }

    /**
     * The nearest OWN in-play local missile
     * body ({@code getSource()} = the player ship) within {@link #ownBodyReach} of the END pose that
     * has not landed a hit ({@code !didDamage()}), is not a twin / skip-set track (those own their
     * ends), is not a mine/flare, and is not of the self-presenting families the impact recipe
     * excludes (prox charge, canister, conversion, unstable fragment, rift — their damage is a fuse /
     * burst, not the body's own {@code getDamageAmount}). Spec at intake is the body's own (the wire
     * record's content id is not held here). A throw ⇒ counted + announced once, null.
     */
    private MissileAPI findOwnLocalBody(cc.starsector.client.net.ClientGameplayEdgeEvent event,
            String wireSpecOrNull) {
        // Two guards: (a) the wire spec must MATCH the body's own (unknown either side fails
        // CLOSED — no spec, no sibling link); (b) EXACTLY ONE candidate in reach — a second sibling of
        // the same spec makes the pick a guess (and a salvo's earlier local hit would then present
        // twice through its sibling), so the end buffers as metered residue instead.
        lastFindAmbiguous = false;
        try {
            int candidates = 0;
            float ex = event.endX();
            float ey = event.endY();
            if (!(Math.abs(ex) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU)
                    || !(Math.abs(ey) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU)) {
                return null; // wire-value guard: NaN / absurd pose never matches a body
            }
            Object ownShip = mpl.plugin.engine.getPlayerShip();
            if (ownShip == null) {
                return null;
            }
            Set<MissileAPI> tracked = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<MissileAPI, Boolean>());
            for (Track tr : unlinked) {
                tracked.add(tr.missile);
            }
            for (Track tr : byWireId.values()) {
                tracked.add(tr.missile);
            }
            MissileAPI best = null;
            float bestD2 = Float.MAX_VALUE;
            for (MissileAPI m : mpl.plugin.engine.getMissiles()) {
                if (m == null || m.getSource() != ownShip || m.didDamage() || m.isMine() || m.isFlare()
                        || !mpl.plugin.engine.isEntityInPlay(m)
                        || mpl.twins.containsKey(m) || tracked.contains(m)) {
                    continue;
                }
                String spec = MissilePoseLane.specIdOf(m);
                if (MineBombEndFxLane.isProxChargeSpec(spec) || MineBombEndFxLane.isCanisterSpec(spec)
                        || DroneStrikeFxLane.isConversionSpec(spec)
                        || SourcedMissileEndLane.isUnstableFragmentSpec(spec)
                        || SourcedMissileEndLane.isAssayingRiftSpec(spec)) {
                    continue;
                }
                // Fails CLOSED now — an unknown
                // spec on EITHER side is no link (was: unknown fails open, so a catalogue miss let a
                // different launcher's missile present).
                if (wireSpecOrNull == null || spec == null || !wireSpecOrNull.equals(spec)) {
                    continue; // The END names a different (or unknown) spec than this body
                }
                float dx = m.getLocation().x - ex;
                float dy = m.getLocation().y - ey;
                float d2 = dx * dx + dy * dy;
                float reach = ownBodyReach(m.getVelocity() == null ? 0f : m.getVelocity().length());
                if (d2 <= reach * reach) {
                    candidates++;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = m;
                    }
                }
            }
            if (candidates > 1) {
                lastFindAmbiguous = true; // Siblings in reach — never a guessed body
                return null;
            }
            return best;
        } catch (Throwable e) {
            ownVictimFindFailures++;
            FallbackAnnounce.announceOnce("ballistic-own-victim",
                    "findOwnLocalBody threw " + e.getClass().getSimpleName()
                    + " (first id=" + event.entityId() + ") -> own victim end buffered unpresented");
            return null;
        }
    }

    /**
     * The recent-own-local-hit ring WRITE,
     * fed from the engine's own damage seam ({@code HullCapLane.noteOwnMissileHit}, carved out of the plugin;
     * one call per missile hit, never per frame).
     * the caller is ONE engine-level {@code DamageTakenModifier}
     * ({@code HullCapLane.OwnMissileHitFeed}), not the per-ship foe/remote caps, so
     * a hit on a foe MODULE (its own ShipAPI) or a cap-less remote now reaches the ring; ship
     * targets only, and a prox-fuse/explosion splash ({@code DamagingExplosion} param, no missile
     * identity) still does not. Only
     * the seat's OWN missiles ({@code getSource() == engine.getPlayerShip()}, the finder's own test)
     * are kept. {@code tick} = the plugin's last snapshot sim tick (the server-tick base the END's
     * {@code simTick} shares); an unset tick is skipped. Entries older than
     * {@link #ownHitPruneTicks} are dropped on each write (bounded: {@link #OWN_HIT_RING_CAP}).
     * The prune bound + the live window are read
     * ONCE per note (one {@code ServerTickRate} volatile read + a round — per missile hit, never per
     * frame; no allocation), and overwriting a slot still inside the refusal window is metered
     * ({@code _own_local_hit_ring_overwrite_live}).
     */
    void noteOwnLocalHit(MissileAPI missile, Object target, Object targetParent, long tick) {
        if (missile == null || target == null || tick == Long.MIN_VALUE || mpl.plugin.engine == null
                || missile.getSource() == null || missile.getSource() != mpl.plugin.engine.getPlayerShip()) {
            return;
        }
        String spec = MissilePoseLane.specIdOf(missile);
        if (spec == null) {
            return; // an unknown spec can never match an END (the fail-closed law above)
        }
        long liveTicks = DischargeReconcileLaw.matchTolTicks(); // Once per note
        long pruneTicks = ownHitPruneTicks(liveTicks);
        for (int i = 0; i < OWN_HIT_RING_CAP; i++) {
            if (ownHitTarget[i] != null && tick - ownHitTick[i] > pruneTicks) {
                clearOwnHitSlot(i);
            }
        }
        int slot = ownHitNext;
        ownHitNext = (ownHitNext + 1) % OWN_HIT_RING_CAP;
        if (ownHitTarget[slot] != null && tick - ownHitTick[slot] <= liveTicks) {
            ownLocalHitRingOverwriteLive++; // An in-window entry evicted — metered
        }
        ownHitTarget[slot] = target;
        ownHitParent[slot] = targetParent;
        ownHitSpec[slot] = spec;
        ownHitTick[slot] = tick;
        ownLocalHitNotes++;
    }

    /** The damage-seam feed threw (the caller
     *  swallows so the engine's hit still applies) — counted + announced once. */
    void noteOwnLocalHitFailed(Throwable t) {
        ownLocalHitNoteFailures++;
        FallbackAnnounce.announceOnce("own-local-hit-note",
                "noteOwnLocalHit threw " + t.getClass().getSimpleName()
                + " -> own local hit unrecorded (a sibling END may present)");
    }

    private void clearOwnHitSlot(int i) {
        ownHitTarget[i] = null;
        ownHitParent[i] = null;
        ownHitSpec[i] = null;
        ownHitTick[i] = 0L;
    }

    /** The ring predicate, pure (Objects so a
     *  smoke pins it engine-free): TRUE iff a recorded own hit of {@code spec} landed on
     *  {@code victim} (or a module whose parent station is {@code victim}) at a tick no older than
     *  {@code endTick - windowTicks}. A null victim/spec never matches. NO upper bound:
     *  at intake every ring entry is stamped ≤ the current snapshot
     *  tick, and an END that reaches the client late (its GPEE lost/resent behind the snapshot
     *  stream) must still find a local twin stamped AFTER {@code endTick} — refusing is the
     *  fail-closed side (a refused END = no double hull dip; the hull snapshot still lands the
     *  damage); pinned by smoke P7d. */
    static boolean ownHitMatches(Object hitTarget, Object hitParent, String hitSpec, long hitTick,
            Object victim, String spec, long endTick, long windowTicks) {
        if (hitTarget == null || victim == null || spec == null || !spec.equals(hitSpec)) {
            return false;
        }
        return (hitTarget == victim || hitParent == victim) && hitTick >= endTick - windowTicks;
    }

    /** Does the ring hold an own local hit
     *  of {@code spec} on the END's named victim inside the RTT window? Window =
     *  {@link DischargeReconcileLaw#adjudicationGraceTicks} (2xRTT + pad, clamped [floor, 1 s];
     *  unmeasured RTT ⇒ the full 1 s — the refusing side). A throw ⇒ counted + announced once and
     *  TRUE (fail CLOSED: residue, never a guessed present). */
    private boolean recentOwnLocalHit(cc.starsector.client.net.ClientGameplayEdgeEvent event,
            String spec) {
        try {
            Object victim = WireShipResolver.resolve(mpl.plugin, event.endVictimWireId());
            if (victim == null) {
                return false; // the presenter's own victim-gone rung names it
            }
            double ping = mpl.plugin.session != null ? mpl.plugin.session.netPingMillis() : Double.NaN;
            long window = DischargeReconcileLaw.adjudicationGraceTicks(ping);
            long endTick = event.simTick();
            for (int i = 0; i < OWN_HIT_RING_CAP; i++) {
                if (ownHitMatches(ownHitTarget[i], ownHitParent[i], ownHitSpec[i], ownHitTick[i],
                        victim, spec, endTick, window)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            ownVictimFindFailures++;
            FallbackAnnounce.announceOnce("ballistic-own-victim-ring",
                    "recentOwnLocalHit threw " + e.getClass().getSimpleName()
                    + " (first id=" + event.entityId() + ") -> own victim end buffered unpresented");
            return true;
        }
    }

    /** The CL-2 intake verdict, pure (smoke P7): a full buffer refuses (the honest
     *  drop announce), everything else buffers.
     *  1-arg — the own-sourced skip moved to {@link #expiryVerdict}. */
    static int pendingIntake(boolean bufferFull) {
        return bufferFull ? INTAKE_DROP_FULL : INTAKE_BUFFER;
    }

    /** {@link #expiryVerdict} codes. */
    static final int EXPIRY_OWN_SOURCED_SKIP = 0;
    static final int EXPIRY_STRUCTURAL = 1;
    static final int EXPIRY_DROP = 2;

    /**
     * The CL-2 EXPIRY verdict, pure (smoke P7b).
     * Runs
     * after the dup bar: an own-sourced end is skipped (the seat's own unenrolled body presents its
     * own fuse/contact/fade); else an INTACT-class end
     * tries the STRUCTURAL present and anything else drops honestly — exactly the tail from before the own-sourced skip.
     * {@code victimNamed} only sizes the skip (the caller's victim sub-meter), never the verdict.
     */
    static int expiryVerdict(boolean ownSourced, boolean endedIntact, boolean victimNamed) {
        if (ownSourced) {
            return EXPIRY_OWN_SOURCED_SKIP;
        }
        return endedIntact ? EXPIRY_STRUCTURAL : EXPIRY_DROP;
    }

    /** TRUE
     *  iff the id's record was seen own-sourced within {@link MissilePoseLane#LINK_WINDOW_TICKS} of
     *  the END tick on EITHER side (a sighting before OR after the END counts; {@code null} = never
     *  seen). Pure (smoke P7b). */
    static boolean ownSourcedFor(Long seenTick, long endTick) {
        return seenTick != null
                && Math.abs(endTick - seenTick.longValue()) <= MissilePoseLane.LINK_WINDOW_TICKS;
    }

    /** The own-sourced memory's prune horizon.
     *  WHY 3 windows, not 1: the verdict now runs at EXPIRY, which fires at the first frame with
     *  {@code tick > END + LINK_WINDOW} (later on a stalled frame), and a sighting up to one window
     *  BEFORE the END still counts ({@link #ownSourcedFor}) — so an entry must survive at least
     *  2 windows + 1 tick past its last sighting; the third window is frame-stall slack. Bounded by
     *  the live own-sourced id count x this horizon; wire ids never recycle within a round. */
    static final int OWN_SOURCED_PRUNE_TICKS = 3 * MissilePoseLane.LINK_WINDOW_TICKS;

    private int ownSourcedNoteFailures; // predict_missile_ballistic_end_own_sourced_note_fail

    /** The per-fresh-frame own-sourced record memory (called from
     *  {@link #linkPass}, the lane's existing fresh-frame hook): remember every record whose source
     *  is the session ship, prune ids unseen past {@link #OWN_SOURCED_PRUNE_TICKS}.
     *  fail-soft — a throw here
     *  must not abort {@code linkPass}; counted + announced once. */
    private void noteOwnSourced(Map<Long, ServerMissileState> byId, long tick) {
        try {
            long shipId = mpl.plugin.session != null ? mpl.plugin.session.shipId() : -1L;
            if (shipId > 0L) {
                for (ServerMissileState rec : byId.values()) {
                    if (rec.hasSource() && rec.sourceEntityId() == shipId) {
                        Long key = Long.valueOf(rec.networkId());
                        ownSourcedSeen.put(key, Long.valueOf(tick));
                        // The spec rides the same loop, one catalogue read
                        // per id (a map get; the loop already runs) — never per frame per id.
                        if (rec.contentId() >= 1 && !ownSourcedSpec.containsKey(key)) {
                            ownSourcedSpec.put(key, mpl.plugin.contentCatalog.get(rec.contentId()));
                        }
                    }
                }
            }
            for (Iterator<Map.Entry<Long, Long>> it = ownSourcedSeen.entrySet().iterator();
                    it.hasNext();) {
                Map.Entry<Long, Long> seen = it.next();
                if (tick - seen.getValue().longValue() > OWN_SOURCED_PRUNE_TICKS) {
                    ownSourcedSpec.remove(seen.getKey()); // pruned together
                    it.remove();
                }
            }
        } catch (Throwable e) {
            ownSourcedNoteFailures++;
            FallbackAnnounce.announceOnce("own-sourced-note-failed",
                    "noteOwnSourced threw " + e.getClass().getSimpleName()
                    + " -> own-sourced end memory stale this frame (link pass continues)");
        }
    }

    /**
     * CL-2 per-fresh-frame retry (after {@link #linkPass} — twins and tracks claim first):
     * a pending end whose id got twin-claimed re-dispatches through the sourced lane, a
     * late-linked track consumes normally, and an entry expiring past the window either
     * presents STRUCTURALLY (INTACT cause, damage-less, at the confessed wire pose — the
     * victim-seat phantom-damage fix when the record never crossed at all) or drops with
     * the honest announce. A record sighting meanwhile stashes dictionary spec + owner so
     * the structural present honors the prox/canister/conversion exclusions (FX
     * ownership); ids the twin machinery or this lane ever consumed suppress quietly —
     * their end presentation already had an owner (the dedupe bar).
     */
    void retryPending(Map<Long, ServerMissileState> byId, long tick) {
        if (pendingEnds.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<Long, PendingEnd>> it = pendingEnds.entrySet().iterator();
                it.hasNext();) {
            Map.Entry<Long, PendingEnd> entry = it.next();
            long id = entry.getKey().longValue();
            PendingEnd p = entry.getValue();
            ServerMissileState rec = byId.get(entry.getKey());
            if (rec != null) {
                p.owner = rec.owner();
                if (p.specId == null && rec.contentId() >= 1) {
                    p.specId = mpl.plugin.contentCatalog.get(rec.contentId());
                }
            }
            if (mpl.twinByWireId.containsKey(entry.getKey())) {
                it.remove();
                endLateTwin++;
                mpl.sourcedEnd.consumeLateTwin(p.event);
                continue;
            }
            if (byWireId.containsKey(entry.getKey())) {
                it.remove();
                if (onConfessedEnd(p.event)) {
                    endLateLink++;
                }
                continue;
            }
            if (tick - p.bufferedTick <= MissilePoseLane.LINK_WINDOW_TICKS) {
                continue;
            }
            it.remove();
            if (consumedIds.contains(entry.getKey()) || mpl.wireIdEverConsumed(id)) {
                endDupSuppressed++;
                continue;
            }
            //
            // The own-sourced verdict at EXPIRY (late sightings included) — after the dup bar, before
            // the STRUCTURAL paint; a sighting before OR after the END counts.
            boolean ownSourced = ownSourcedFor(ownSourcedSeen.get(entry.getKey()), p.event.simTick());
            boolean victimNamed = p.event.endVictimWireId() > 0L;
            int expiry = expiryVerdict(ownSourced, p.event.endedIntact(), victimNamed);
            if (expiry == EXPIRY_OWN_SOURCED_SKIP) {
                // The seat's own unenrolled body presents this end itself — counted,
                // named, never painted (the twinless law: counted, never applied).
                endOwnSourcedSkip++;
                // Intake presents the handled
                // victim ends now, so this sub-meter is the RESIDUE (late sighting / no own body).
                if (p.event.endedIntact() && victimNamed) {
                    endOwnSourcedSkipVictim++;
                }
                mpl.lane.log("predict v16 ballistic-end OWN-SOURCED skip: id=" + id
                        + " tick=" + p.event.simTick() + " cause=" + p.event.endCauseCode()
                        + " victim=" + p.event.endVictimWireId()
                        + " waited=" + (tick - p.bufferedTick) + "t"
                        + " (own unenrolled body presents its own end; no structural paint)");
                continue;
            }
            if (expiry == EXPIRY_STRUCTURAL // v15: INTACT-class (a shield-absorbed end still impacted)
                    && mpl.sourcedEnd.presentImpactFxOwned(p.specId, p.owner, id, p.event,
                            "structural", null /* body-less: no trail plugin to bloom (the missile param; null = PAINT_RESIDUE_BODYLESS) */)) {
                endStructuralFx++;
                mpl.lane.log("predict v16 ballistic-end STRUCTURAL: id=" + id
                        + " spec=" + FallbackAnnounce.sanitize(p.specId, 64) + " owner=" + p.owner
                        + " waited=" + (tick - p.bufferedTick) + "t");
                continue;
            }
            endExpiredDrop++;
            FallbackAnnounce.announceOnce("f3-twinless-end-dropped",
                    "confessed end dropped: no twin, no linked bolt inside the window (first id="
                            + id + " cause=" + p.event.endCauseCode() + ")");
        }
    }

    /** Round reset: identity state clears; counters stay cumulative (FoeHull discipline). */
    void resetForMatchReset() {
        unlinked.clear();
        byWireId.clear();
        consumedIds.clear();
        pendingEnds.clear();
        ownSourcedSeen.clear();
        ownSourcedSpec.clear(); //
        for (int i = 0; i < OWN_HIT_RING_CAP; i++) { // old-round hulls never match
            clearOwnHitSlot(i);
        }
        ownHitNext = 0;
    }

    /** Spliced into the MPL meter line beside the sourced-end token block (the split/own
     *  meterTokens discipline — the line stays append-consistent). */
    String meterTokens() {
        return " predict_missile_ballistic_end_reg=" + registered
                + " predict_missile_ballistic_end_linked=" + linked
                + " predict_missile_ballistic_end_link_expired=" + linkExpired
                + " predict_missile_ballistic_end_cross_spec=" + crossSpecRefused
                + " predict_missile_ballistic_end_removed=" + endRemoved
                + " predict_missile_ballistic_end_death_puff=" + endDeathPuff
                + " predict_missile_ballistic_end_local_gone=" + endLocalGone
                + " predict_missile_ballistic_end_impact_fx=" + impactFx
                + " predict_missile_ballistic_end_lost=" + trackLost
                + " predict_missile_ballistic_end_buffered=" + endBuffered
                + " predict_missile_ballistic_end_late_twin=" + endLateTwin
                + " predict_missile_ballistic_end_late_link=" + endLateLink
                + " predict_missile_ballistic_end_structural_fx=" + endStructuralFx
                + " predict_missile_ballistic_end_dup_suppressed=" + endDupSuppressed
                + " predict_missile_ballistic_end_expired_drop=" + endExpiredDrop
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_own_sourced_skip=" + endOwnSourcedSkip
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_own_sourced_skip_victim=" + endOwnSourcedSkipVictim
                + " predict_missile_ballistic_end_own_sourced_note_fail=" + ownSourcedNoteFailures
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_own_victim_presented=" + endOwnVictimPresented
                + " predict_missile_ballistic_end_own_victim_no_body=" + endOwnVictimNoBody
                + " predict_missile_ballistic_end_own_victim_find_fail=" + ownVictimFindFailures
                + " predict_missile_ballistic_end_own_victim_ambiguous=" + endOwnVictimAmbiguous //
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_own_victim_no_spec=" + endOwnVictimNoSpec
                + " predict_missile_ballistic_end_own_victim_recent_local_hit=" + endOwnVictimRecentLocalHit
                + " predict_missile_ballistic_end_own_local_hit_noted=" + ownLocalHitNotes
                + " predict_missile_ballistic_end_own_local_hit_note_fail=" + ownLocalHitNoteFailures
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_own_local_hit_ring_overwrite_live="
                + ownLocalHitRingOverwriteLive
                // Appended, append-consistent.
                + " predict_missile_ballistic_end_flameout=" + endFlameout
                + " predict_missile_ballistic_end_flameout_fail=" + flameOutFailures
                + " predict_missile_ballistic_fizzle_relayed=" + boltFizzleRelays
                + " predict_missile_ballistic_dud_relayed=" + boltDudRelays
                + " predict_missile_ballistic_dud_flips=" + boltDudFlips;
    }
}
