package cc.starsector.server.runtime;

import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Confessing-gun stage S1: the per-round shadow-agreement fold between the engine's OWN
 * fire push (confessed births drained from {@link FireBirthSink}) and the poll-and-guess
 * {@link GameplayEdgeEventDetector} discharge events — observe-only, S1 touches emission
 * nowhere. Grain = (networkShipId, mountIndex, tick): births translate engine ship ref →
 * position id ({@code EngineCombatBinding.positionShipIdByEngineShip()}) → network id (the SAME
 * {@code lastEntityIdByPositionShipId} map the detector re-keys by); an unmapped birth is
 * {@code unresolved} — it mirrors the detector's whole-view skip, so dead/hulk ships never
 * count against agreement. Multiple births per key+tick (multi-pellet volleys) collapse to one
 * presence for matching; the raw count rides {@code onfire_births}.
 *
 * <p>Coverage exclusion: only weapon specs with a projectile spec carry the
 * onFire seam (65 of 118 have none — beams/missiles), so their real discharges are
 * detector-visible but can never confess. A detector event whose mount's {@code weaponId()}
 * is NOT in the sweep's covered set classifies {@code uncovered} and never enters matching;
 * {@code det_only} is thereby the COVERED-mount overcount signal, asserted ZERO by the
 * standing verifier. A weaponId lookup miss (mount view absent for an emitted event — not an
 * expected shape, the events come from the same tier read) deliberately stays IN matching so
 * it surfaces loud as {@code det_only}, never silently absorbed into {@code uncovered}.
 *
 * <p>Classification (1-tick pend window, both directions): the EXPECTED
 * agreement shape is {@code skew1_bf} — birth at T, detector event at T+1 — because
 * {@code executeAdds} drains projectile adds after the object-advance pass: the birth's onFire
 * dispatches mid-advance at T while the detector's cooldown-rise edge reads post-step at T+1
 * (the {@code FRESH_SPAWN_ELAPSED_MAX_SECONDS} timing, measured in-repo; the first shadow read
 * matched ALL 33 pairs at bf-skew, zero same-tick). Same-tick presence on both sides =
 * {@code match} (kept, not expected dominant); event at T resolved by a birth at T+1 =
 * {@code skew1_df} (expected ≈0 — nonzero is information, not failure); a birth never matched
 * = {@code cb_only} (the EXPECTED detector-blind classes: burst-internal refire below
 * {@code COOLDOWN_RISE_EPSILON}, MIRV mid-flight children — plus any genuine detector miss;
 * NOT asserted zero); a covered event never matched = {@code det_only} — detector overcount,
 * the double-emission class, the loud regression signal. Swallowed trailing
 * halves of the B3(2) collapse emit no event and so have no comparator effect.
 *
 * <p>Fail-soft law (mirrors the detector's own): a tick whose observe is gated OFF or whose
 * mounts tier read fail-softed folds through {@link #foldUnobserved} — its births AND any
 * pending window entries move to {@code unobserved}, never a classification (a gate flap must
 * not fabricate divergence). Single-threaded, per-round instance beside the detector.
 */
public final class OnFireShadowComparator {

    /** (networkShipId, mountIndex) — shared with {@link OnFireTruthMerger}, which
     *  keys admission/suppression on the same grain this shadow matches on. */
    record Key(long entityId, int mountIndex) {}

    private static final int DETAIL_CAP_PER_ROUND = 16;

    /** Pend entries all carry the PREVIOUS fold's tick by construction (expired every fold). */
    private final List<Key> pendingBirths = new ArrayList<>();
    private final List<Key> pendingEvents = new ArrayList<>();
    private long lastFoldTick = Long.MIN_VALUE;

    private long births;
    private long matches;
    private long skew1BirthFirst;
    private long skew1DetFirst;
    private long cbOnly;
    private long detOnly;
    private long uncovered;
    private long unresolved;
    private long unobserved;
    private int detailLines;

    /**
     * Fold one OBSERVED tick: the detector's emitted edges for {@code simTick}, the SAME mounts
     * tier the detector read (the coverage lookup source), the births drained for it, the two
     * translation maps ({@code positionShipIdByEngineShip} may be null when no births drained —
     * it is only read per birth; a null map or null {@code entityIdByPositionShipId} entry makes
     * that birth {@code unresolved}), and the sweep's covered weapon-spec-id set.
     */
    public void fold(long simTick, List<GameplayEdgeEvent> tickEdges,
            List<? extends WeaponMountView> weaponMounts,
            List<FireBirthSink.FireBirth> drainedBirths,
            Map<Object, Long> positionShipIdByEngineShip,
            Map<Long, Long> entityIdByPositionShipId,
            Set<String> coveredWeaponIds, int round) {
        rollWindow(simTick);
        Set<Key> birthKeys = new LinkedHashSet<>();
        for (FireBirthSink.FireBirth birth : drainedBirths) {
            births++;
            Long positionId = birth.ship() == null || positionShipIdByEngineShip == null
                    ? null : positionShipIdByEngineShip.get(birth.ship());
            Long networkId = positionId == null || entityIdByPositionShipId == null
                    ? null : entityIdByPositionShipId.get(positionId);
            if (networkId == null || birth.mountIndex() < 0) {
                unresolved++;
                continue;
            }
            birthKeys.add(new Key(networkId, birth.mountIndex()));
        }
        Set<Key> eventKeys = new LinkedHashSet<>();
        Map<Key, String> weaponIdByMount = null;
        for (GameplayEdgeEvent event : tickEdges) {
            if (event.kind() != GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                continue;
            }
            Key key = new Key(event.entityId(), event.mountIndex());
            if (weaponIdByMount == null) {
                weaponIdByMount = mountLookup(weaponMounts, entityIdByPositionShipId);
            }
            String weaponId = weaponIdByMount.get(key);
            if (weaponId != null && coveredWeaponIds != null
                    && !coveredWeaponIds.contains(weaponId)) {
                uncovered++;
                continue;
            }
            eventKeys.add(key);
        }
        // Same-tick agreement first, then the ±1 window against the previous tick's pendings,
        // then the window closes on whatever the previous tick still holds.
        for (var it = birthKeys.iterator(); it.hasNext(); ) {
            if (eventKeys.remove(it.next())) {
                matches++;
                it.remove();
            }
        }
        for (var it = eventKeys.iterator(); it.hasNext(); ) {
            if (pendingBirths.remove(it.next())) {
                skew1BirthFirst++;
                it.remove();
            }
        }
        for (var it = birthKeys.iterator(); it.hasNext(); ) {
            if (pendingEvents.remove(it.next())) {
                skew1DetFirst++;
                it.remove();
            }
        }
        expireWindow(simTick, round);
        pendingBirths.addAll(birthKeys);
        pendingEvents.addAll(eventKeys);
        lastFoldTick = simTick;
    }

    /** The per-tick (networkShipId, mountIndex) → weaponId coverage lookup, built lazily on the
     *  first discharge event of a tick from the SAME tier read + translation map the detector
     *  used (null map = identity — the detector's own game-free-smoke posture). Shared with
     *  {@link OnFireTruthMerger} — one coverage-lookup law, one site. */
    static Map<Key, String> mountLookup(List<? extends WeaponMountView> weaponMounts,
            Map<Long, Long> entityIdByPositionShipId) {
        if (weaponMounts == null) {
            return Map.of();
        }
        Map<Key, String> byMount = new HashMap<>();
        for (WeaponMountView mount : weaponMounts) {
            Long networkId = entityIdByPositionShipId == null
                    ? Long.valueOf(mount.shipId())
                    : entityIdByPositionShipId.get(mount.shipId());
            if (networkId == null) {
                continue;
            }
            byMount.put(new Key(networkId.longValue(), mount.mountIndex()), mount.weaponId());
        }
        return byMount;
    }

    /** Fold one UNOBSERVED tick (observe gated off / mounts tier null): births and any open
     *  window entries become {@code unobserved} — never a classification. */
    public void foldUnobserved(long simTick, List<FireBirthSink.FireBirth> drainedBirths) {
        births += drainedBirths.size();
        unobserved += drainedBirths.size();
        unobserved += pendingBirths.size() + pendingEvents.size();
        pendingBirths.clear();
        pendingEvents.clear();
        lastFoldTick = simTick;
    }

    /** Close the round: whatever the window still holds classifies (a covered event never
     *  matched is det_only even at round end — see the class javadoc's timing note). */
    public void closeRound(int round) {
        expireWindow(lastFoldTick == Long.MIN_VALUE ? 0L : lastFoldTick + 1, round);
    }

    /** Defensive: a fold gap (never expected — the loop folds every tick) voids the window
     *  to {@code unobserved} instead of fabricating divergence across the gap. */
    private void rollWindow(long simTick) {
        if (lastFoldTick == Long.MIN_VALUE || simTick == lastFoldTick + 1) {
            return;
        }
        unobserved += pendingBirths.size() + pendingEvents.size();
        pendingBirths.clear();
        pendingEvents.clear();
    }

    private void expireWindow(long simTick, int round) {
        for (Key key : pendingBirths) {
            cbOnly++;
            detail("cb_only", key, simTick - 1, round);
        }
        for (Key key : pendingEvents) {
            detOnly++;
            detail("det_only", key, simTick - 1, round);
        }
        pendingBirths.clear();
        pendingEvents.clear();
    }

    private void detail(String kind, Key key, long simTick, int round) {
        if (detailLines >= DETAIL_CAP_PER_ROUND) {
            return;
        }
        detailLines++;
        System.out.println("onfire-shadow divergence kind=" + kind
                + " entity=" + key.entityId() + " mount=" + key.mountIndex()
                + " tick=" + simTick + " round=" + round
                + (detailLines == DETAIL_CAP_PER_ROUND ? " (detail cap reached this round)" : ""));
    }

    public long births() {
        return births;
    }

    public long matches() {
        return matches;
    }

    /** Total ±1 pend-resolved agreements (both directions — the pre-amendment field). */
    public long skew1() {
        return skew1BirthFirst + skew1DetFirst;
    }

    /** Birth at T agreed by the detector at T+1 — the EXPECTED agreement shape (Finding A). */
    public long skew1BirthFirst() {
        return skew1BirthFirst;
    }

    /** Event at T agreed by a birth at T+1 — expected ≈0; nonzero is information. */
    public long skew1DetFirst() {
        return skew1DetFirst;
    }

    public long cbOnly() {
        return cbOnly;
    }

    public long detOnly() {
        return detOnly;
    }

    /** Detector events on mounts whose weapon spec carries no emitter seam (Finding B). */
    public long uncovered() {
        return uncovered;
    }

    public long unresolved() {
        return unresolved;
    }

    public long unobserved() {
        return unobserved;
    }
}
