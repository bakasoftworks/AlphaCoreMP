package cc.starsector.server.runtime;

import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-round WEAPON_DISCHARGE emission-source merge —
 * COVERED mounts (the sweep's weapon-spec set, {@link FireBirthSink#coveredWeaponIds()}) emit
 * from confessed onFire births (the TRUE fire tick, the exact per-shot flight bearing, and
 * burst-internal discharges the poll detector is structurally blind to below
 * {@code COOLDOWN_RISE_EPSILON}); the {@link GameplayEdgeEventDetector} stays truth for
 * UNCOVERED mounts (beams/missiles — no onFire seam) and for every
 * non-discharge kind. The {@link OnFireShadowComparator} keeps consuming the RAW detector
 * stream (pre-merge) — the S1 shadow net stays a net, the strongest no-regression property
 * this source switch buys.
 *
 * <p>Suppression is TWIN-CHECKED (supersedes the S2 unwindowed rule): a
 * covered detector discharge suppresses ONLY when a birth for its (entity, mount) was ADMITTED
 * at T or T-1. Births lead detector events by exactly one tick (a measured finding:
 * {@code executeAdds} drains projectile adds after the object-advance pass), so a real twin
 * lives in this call's admitted set or the previous observed tick's — never further back. A
 * covered discharge with NO admitted twin PASSES THROUGH on its own detector-read bearing and
 * counts {@code detFallback}: an unconfessed covered weapon (a latch gap — the boot-order
 * race class) degrades to the pre-S2 polled lane instead of silent loss (51 real glimmer
 * ionpulser shots suppressed twin-blind) — EXCEPT a no-spawn discharge on a shooter the
 * engine holds unable to fire ({@link #phaseBlocksFire}: refused, counted {@code detPhased},
 * never {@code detFallback}). A weaponId lookup MISS likewise passes through
 * ({@code lookupMiss} counts loud — the same never-lose-an-event law). Merge runs ONLY on
 * ticks the detector observed (caller-gated on {@code edgeObserveRan && mountsTier != null});
 * an unobserved tick stays identity — a gate flap must not fabricate a half-truth stream (its
 * gap also voids the T-1 window: a pre-gap birth never suppresses a post-gap discharge).
 *
 * <p>Admission collapses births per (networkId, mountIndex) per tick, FIRST birth wins —
 * today's shot-collapsed wire semantics; per-projectile density is the CARRY tier's decision,
 * not S2's ({@link FireBirthSink#drainUpTo} returns record order, so "first" is the true first
 * dispatch). Ship translation mirrors the comparator's: engine ref → position id → network id;
 * unmapped at either hop (dead/dropped hulls never edge) or a negative mount ordinal drops the
 * birth ({@code srcDropped}). A NaN flight bearing rides the event (the client falls back;
 * {@code srcNanBearing} counts — fail-soft, never a faked value).
 *
 * <p>Single-threaded, per-round instance beside the comparator; a disabled instance (knob off)
 * or an empty covered set (the boot sweep never latched) is identity passthrough, zero work.
 */
public final class OnFireTruthMerger {

    private final boolean enabled;

    private long srcBirth;
    private long suppressed;
    private long lookupMiss;
    private long srcDropped;
    private long srcUncovered;
    private long srcNanBearing;
    private long detFallback;
    private long detPhased;

    // The T-1 half of the suppression twin window: the previous OBSERVED merge tick and its
    // admitted (entity, mount) keys — consulted only when that tick was exactly simTick-1.
    private long prevAdmittedTick = Long.MIN_VALUE;
    private Set<OnFireShadowComparator.Key> prevAdmitted = Set.of();

    // The PREVIOUS observed tick's published flux + cloak tiers. The engine gates frame T's fire on
    // POST-STEP T-1 phase state: a ship's weapon groups advance BEFORE its system/cloak advance
    // writes setPhased, so a refusal reading post-step T would mask a real fire on the cloak-ON
    // tick and serve the phantom on the cloak-OFF tick. Consulted only when that tick was exactly
    // simTick-1 (else this tick's tiers — the better guess across a gate flap); the same class of
    // state as prevAdmitted, so re-sim determinism holds. References to the snapshot's own
    // immutable lists — no copy, no engine read.
    //
    //
    private long prevTiersTick = Long.MIN_VALUE;
    private List<ShipFluxSnapshot> prevFlux;
    private List<ShipPhaseCloakStateSnapshot> prevCloak;

    // A confession-lane miss must NEVER be silent
    // — first fallback per (entity, mount) per round prints a receipt naming the weapon, on
    // top of the per-round counter (the first-per-family log law).
    private final Set<OnFireShadowComparator.Key> detFallbackLogged = new HashSet<>();

    /** The bearing-source tag of one effective discharge event this tick
     *  — {@code tier} ∈ birth_direct / birth_facing / birth_composed / det_fallback / det_uncovered /
     *  det_lookup_miss, {@code weaponId} the mount's spec id (null on a lookup miss). Read by
     *  {@link GameplayEdgeEventWatchSink} only; never on the wire. */
    public record SourceTag(String tier, String weaponId) {}

    // Per-tick tags keyed like the suppression window; cleared at every merge call (an
    // identity tick leaves it empty ⇒ the sink writes det_identity).
    private final Map<OnFireShadowComparator.Key, SourceTag> lastTags = new HashMap<>();

    /** {@code enabled} = the resolved {@code cc.server.onFireTruth} knob (false = every merge
     *  is identity — the legacy detector source). */
    public OnFireTruthMerger(boolean enabled) {
        this.enabled = enabled;
    }

    /** The source tag of the LAST merge's effective discharge on ({@code entityId},
     *  {@code mountIndex}), or null (identity tick / no such discharge). */
    public SourceTag tagOf(long entityId, int mountIndex) {
        return lastTags.get(new OnFireShadowComparator.Key(entityId, mountIndex));
    }

    /** Whether the LAST merge output's event at (entity, mount) is
     *  the detector's own — a {@code det_*} pass-through, or no tag = the identity path (the
     *  {@code GameplayEdgeEventWatchSink} {@code det_identity} reading) — not an admitted birth.
     *  Ask only for an event IN that output (a suppressed key may carry no tag). */
    public boolean detectorServed(long entityId, int mountIndex) {
        SourceTag tag = tagOf(entityId, mountIndex);
        return tag == null || tag.tier().startsWith("det_");
    }

    /**
     * Merge one OBSERVED tick: the detector's emitted edges, the SAME mounts tier + translation
     * maps the detector and shadow fold read (coverage lookup + birth ship translation share
     * S1's exact sites), and the births drained for {@code simTick}. Returns the effective
     * event list for the decl/wire consumers — {@code tickEdges} itself (identity) when
     * disabled, the covered set is empty, or the tier is null; otherwise the suppressed
     * passthrough plus admitted birth events, re-sorted by the detector's canonical key.
     * This overload carries no phase truth, so it never refuses a fallback.
     */
    public List<GameplayEdgeEvent> merge(long simTick, List<GameplayEdgeEvent> tickEdges,
            List<? extends WeaponMountView> mountsTier,
            List<FireBirthSink.FireBirth> tickBirths,
            Map<Object, Long> positionShipIdByEngineShip,
            Map<Long, Long> entityIdByPositionShipId,
            Set<String> coveredWeaponIds) {
        return merge(simTick, tickEdges, mountsTier, tickBirths, positionShipIdByEngineShip,
                entityIdByPositionShipId, coveredWeaponIds, null, null);
    }

    /** {@link #merge(long, List, List, List, Map, Map, Set)}
     *  plus THIS tick's published flux + phase-cloak tiers (network-id keyed, the snapshot the
     *  detector observed beside), held one tick and read as the PREVIOUS observed tick's truth —
     *  the engine's fire gate ran before that frame's cloak advance — only
     *  on a no-twin covered discharge, to refuse a fire the engine could not have made
     *  ({@link #phaseBlocksFire}); null = no phase truth, no refusal. */
    public List<GameplayEdgeEvent> merge(long simTick, List<GameplayEdgeEvent> tickEdges,
            List<? extends WeaponMountView> mountsTier,
            List<FireBirthSink.FireBirth> tickBirths,
            Map<Object, Long> positionShipIdByEngineShip,
            Map<Long, Long> entityIdByPositionShipId,
            Set<String> coveredWeaponIds,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipPhaseCloakStateSnapshot> phaseCloakStates) {
        lastTags.clear();
        if (!enabled || coveredWeaponIds == null || coveredWeaponIds.isEmpty()
                || mountsTier == null) {
            return tickEdges;
        }
        List<GameplayEdgeEvent> merged = new ArrayList<>(tickEdges.size() + tickBirths.size());
        // Births FIRST: this tick's admitted key set is the T half of the
        // suppression twin window below. Coverage FIRST (rejects MIRV children via the
        // launcher's uncovered weaponId), then the comparator's exact ship
        // translation, then the per-tick collapse.
        Set<OnFireShadowComparator.Key> admitted = new LinkedHashSet<>();
        for (FireBirthSink.FireBirth birth : tickBirths) {
            if (birth.weaponId() == null || !coveredWeaponIds.contains(birth.weaponId())) {
                srcUncovered++;
                continue;
            }
            Long positionId = birth.ship() == null || positionShipIdByEngineShip == null
                    ? null : positionShipIdByEngineShip.get(birth.ship());
            Long networkId = positionId == null || entityIdByPositionShipId == null
                    ? null : entityIdByPositionShipId.get(positionId);
            if (networkId == null || birth.mountIndex() < 0) {
                srcDropped++;
                continue;
            }
            OnFireShadowComparator.Key birthKey =
                    new OnFireShadowComparator.Key(networkId.longValue(), birth.mountIndex());
            if (!admitted.add(birthKey)) {
                continue;
            }
            if (Float.isNaN(birth.flightBearingDeg())) {
                srcNanBearing++;
            }
            lastTags.put(birthKey, new SourceTag(birth.tier().label(), birth.weaponId()));
            merged.add(GameplayEdgeEvent.weaponDischarge(simTick, networkId.longValue(),
                    birth.mountIndex(), birth.aimFacingDeg(), birth.flightBearingDeg(),
                    birth.birthSpeed(), birth.muzzleX(), birth.muzzleY()));
            srcBirth++;
        }
        // The refusal's phase truth = post-step T-1 (the engine's own gate frame, see prevTiersTick);
        // this tick's tiers being null still means "no phase truth, no refusal" (the 7-arg overload).
        List<ShipFluxSnapshot> gateFlux = fluxStates;
        List<ShipPhaseCloakStateSnapshot> gateCloak = phaseCloakStates;
        if (fluxStates != null && phaseCloakStates != null && prevTiersTick == simTick - 1
                && prevFlux != null && prevCloak != null) {
            gateFlux = prevFlux;
            gateCloak = prevCloak;
        }
        Map<OnFireShadowComparator.Key, String> weaponIdByMount = null;
        for (GameplayEdgeEvent event : tickEdges) {
            if (event.kind() != GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                merged.add(event);
                continue;
            }
            if (weaponIdByMount == null) {
                weaponIdByMount = OnFireShadowComparator.mountLookup(
                        mountsTier, entityIdByPositionShipId);
            }
            OnFireShadowComparator.Key key =
                    new OnFireShadowComparator.Key(event.entityId(), event.mountIndex());
            String weaponId = weaponIdByMount.get(key);
            if (weaponId == null) {
                lookupMiss++;
                lastTags.put(key, new SourceTag("det_lookup_miss", null));
                merged.add(event);
            } else if (!coveredWeaponIds.contains(weaponId)) {
                lastTags.put(key, new SourceTag("det_uncovered", weaponId));
                merged.add(event);
            } else if (admitted.contains(key)
                    || (prevAdmittedTick == simTick - 1 && prevAdmitted.contains(key))) {
                suppressed++;
            } else if (phaseBlocksFire(event, gateFlux, gateCloak)) {
                detPhased++;
            } else {
                // Covered but never confessed at T/T-1: the polled event rides on its own
                // detector-read bearing rather than vanishing (twin-blind suppression
                // silently lost every unconfessed covered shot).
                detFallback++;
                if (detFallbackLogged.add(key)) {
                    System.out.println("onfire-det-fallback entity=" + event.entityId()
                            + " mount=" + event.mountIndex() + " weapon=" + weaponId
                            + " tick=" + simTick
                            + " missile_spec=" + FireBirthSink.isMissileSpecCovered(weaponId)
                            + " (covered mount discharging with no"
                            + " confessed birth — polled event served, latch gap suspected)");
                }
                lastTags.put(key, new SourceTag("det_fallback", weaponId));
                merged.add(event);
            }
        }
        prevAdmittedTick = simTick;
        prevAdmitted = admitted;
        prevTiersTick = simTick;
        prevFlux = fluxStates;
        prevCloak = phaseCloakStates;
        merged.sort(GameplayEdgeEventDetector.CANONICAL_ORDER);
        return List.copyOf(merged);
    }

    /**
     * Whether a no-twin covered discharge is a fire the
     * engine could not have made — the shooter phased, its cloak not charging down, and no
     * spawn witnessed (NaN muzzle). WHY: while a ship is phased and its cloak is in any state
     * but OUT, the projectile weapon's fire call returns before any spawn; and on the first
     * phased frame of a burst in progress the weapon's advance ENDS the burst, restoring the
     * chargedown length from the burst delay to the full refire delay, so the polled cooldown
     * RISES with no shot fired — the phase-in phantom the poll detector would otherwise serve.
     *
     *
     *
     * Reads only already-built published tiers (flux {@code phased} = the engine's own
     * {@code isPhased()}; cloak {@code stateCode}) — the caller hands the PREVIOUS observed tick's,
     * the frame the engine's fire gate actually read — no engine read. A
     * ship absent from either tier, a cloak in OUT (the engine lets it fire), or a finite muzzle
     * all serve as before.
     */
    static boolean phaseBlocksFire(GameplayEdgeEvent event, List<ShipFluxSnapshot> fluxStates,
            List<ShipPhaseCloakStateSnapshot> phaseCloakStates) {
        if (fluxStates == null || phaseCloakStates == null || !Float.isNaN(event.muzzleX())) {
            return false;
        }
        boolean phased = false;
        for (ShipFluxSnapshot flux : fluxStates) {
            if (flux.entityId() == event.entityId()) {
                phased = flux.phased();
                break;
            }
        }
        if (phased) {
            for (ShipPhaseCloakStateSnapshot cloak : phaseCloakStates) {
                if (cloak.entityId() == event.entityId()) {
                    return cloak.stateCode() != ShipPhaseCloakStateSnapshot.STATE_CHARGEDOWN;
                }
            }
        }
        return false;
    }

    /** no-twin covered discharges REFUSED because
     *  {@link #phaseBlocksFire} held (per-round; never also counted in {@link #detFallback()}). */
    public long detPhased() {
        return detPhased;
    }

    /** Admitted birth events, POST-collapse — steady-state equals {@link #suppressed()}
     *  (one admitted birth at T, one suppressed detector event at T+1). */
    public long srcBirth() {
        return srcBirth;
    }

    /** Covered-mount detector discharges dropped in favor of their birth-sourced twin
     *  (admitted at T or T-1). */
    public long suppressed() {
        return suppressed;
    }

    /** Covered-mount detector discharges served WITHOUT an admitted twin at T/T-1 — each is a
     *  real shot the confession lane missed (a latch gap); 0 is the healthy read since the twin window,
     *  nonzero is information, never a silent absorb. */
    public long detFallback() {
        return detFallback;
    }

    /** Discharge events whose mount had no weaponId in the tier lookup — passed through
     *  (never lose an event); nonzero is information, not a silent absorb. */
    public long lookupMiss() {
        return lookupMiss;
    }

    /** Covered births dropped at ship translation (unmapped hop) or a negative mount ordinal
     *  — mirrors the detector's unmapped-view skip. */
    public long srcDropped() {
        return srcDropped;
    }

    /** Births on uncovered weaponIds (incl. MIRV children via launcher id) — never emitted. */
    public long srcUncovered() {
        return srcUncovered;
    }

    /** Admitted events that carried a NaN flight bearing (emitted; client falls back). */
    public long srcNanBearing() {
        return srcNanBearing;
    }
}
