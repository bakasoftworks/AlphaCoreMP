package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Server-side producer that derives explicit {@code MISSILE_SPLIT}
 * {@link GameplayEdgeEvent}s from the authoritative snapshot stream
 * (the vanilla second-stage presentation commission).
 *
 * <p>{@link EntityDespawnEventDetector} idiom exactly — a stateful, single-threaded
 * pure fold over per-tick frames, fed the server's <em>full</em> authoritative
 * snapshots before any per-peer relevance scoping — but over the NON-SHIP
 * <b>missile</b> records (kind {@code MISSILE}, the v13a stable-id
 * partition at {@link CombatEntityView#MISSILE_NETWORK_ID_BASE}) instead of the
 * ship roster. The engine has no split callback the server can reach
 * (stable ids are {@code retainAll}-pruned silently), so the
 * split moment is recovered from the record diff. A stable missile id GONE this
 * frame, whose content spec is MIRV-behaviored ({@code isMirv()}, resolved through
 * {@link #isMirvContentId}), splits iff EITHER lane matches (the
 * C7 sabot widening; the lanes are the LAW and live here):
 *
 * <ul>
 * <li><b>missile lane (unchanged — hurricane/pilum/hydra):</b> at least one NEW
 * missile stable id BORN the same frame within {@link #SPLIT_BIRTH_RADIUS_SU} of
 * the parent's last position;</li>
 * <li><b>projectile lane (sabot-style BALLISTIC children, which carry no
 * stable id and can never satisfy the missile lane):</b>
 * at least one MIRV projectile-child birth this frame within the same radius
 * (the binding's {@code isFromMissile ∧ elapsed==0} side channel) AND the
 * vanished parent's end-cause triage reads {@link EndCause#INTACT} — the triage
 * veto that kills the feared false-FX class (parent A splits while parent B is
 * PD-killed or fizzles the same frame near A's children: B's triage is
 * {@code DESTROYED}/{@code FIZZLING}, never a split). An ABSENT triage entry
 * vetoes too (fail-soft: an unreadable post-mortem never mints split FX).</li>
 * </ul>
 *
 * <p>⇒ one {@code MISSILE_SPLIT} event at the current frame's {@code simTick},
 * carrying the parent id + last authoritative position (payload UNCHANGED — the
 * client sources everything else from its local twin; zero wire schema change).
 *
 * <p>Emission is loss-tolerant by design: a dropped event degrades the client to
 * today's absence-grace retire (no split FX) — the fail-open floor.
 */
public final class MissileSplitEventDetector {

    /**
     * End-cause triage of a missile delisted from the live engine list this frame,
     * read post-mortem off the retained entity (delist only drops
     * list membership). 3-way disjoint in the engine: a damage kill clamps hp to 0
     * before removal, a fizzle sets {@code isFizzling}, and a
     * split ({@code removeObject} in MirvAI) leaves hp untouched and never runs
     * while fading — so {@link #INTACT} is the split-compatible verdict.
     */
    public enum EndCause { DESTROYED, FIZZLING, INTACT }

    /**
     * The GPEE v15 contact: a delisted missile's LAST ship impact, resolved to wire terms —
     * {@code victimWireId} = the victim ship's wire id ({@code <= 0} = unresolved ⇒ the event names
     * NO ship), {@code shieldAbsorbed} = the server engine's own shield verdict
     * ({@link MissileImpactMemo}). Absent from the map = the server saw no ship impact.
     */
    public record Contact(long victimWireId, boolean shieldAbsorbed) {
    }

    /**
     * Radius (world su) around the vanished parent's last position within which a
     * same-frame missile birth counts as its child. MIRV children spawn AT the parent
     * ({@code MirvAI.checkSplit} spawns at the parent's location before one frame of
     * flight at combat speeds ≲ tens of su); 50 su holds them with margin while
     * excluding unrelated same-tick volley births elsewhere on the map.
     */
    public static final float SPLIT_BIRTH_RADIUS_SU = 50.0f;

    private final IntPredicate isMirvContentId;
    private Map<Long, NonShipEntitySnapshot> previousMissilesById = Map.of();
    private boolean sawFirstFrame = false;
    private long lastObservedTick = -1L;
    private final List<String> lastObserveLanes = new ArrayList<>();
    private final List<GameplayEdgeEvent> lastObserveEndEvents = new ArrayList<>();

    /**
     * @param isMirvContentId resolves a non-ship record's {@code contentId} to "is this
     *     spec MIRV-behaviored" — the caller composes registry key lookup + the
     *     engine-read cache (fail-soft FALSE on any unknown id/key).
     */
    public MissileSplitEventDetector(IntPredicate isMirvContentId) {
        this.isMirvContentId = Objects.requireNonNull(isMirvContentId, "isMirvContentId");
    }

    /**
     * Missile-lane-only fold (no side channels) — the shape before the projectile lane, kept for
     * callers/rows that exercise the hurricane lane alone.
     */
    public List<GameplayEdgeEvent> observe(AuthoritativeWorldSnapshot frame) {
        return observe(frame, List.of(), Map.of());
    }

    /**
     * Fold one authoritative frame into the detector, returning the split events newly
     * emitted by this frame (usually empty). Frames must be supplied in non-decreasing
     * {@code simTick} order.
     *
     * @param projectileChildBirthsXY (x,y) of every MIRV projectile-child BORN this
     *     frame — the binding's per-read {@code isFromMissile ∧ elapsed==0} PROJECTILE
     *     side channel
     * @param removedMissileEndCauses stable id → post-mortem {@link EndCause} for every
     *     missile delisted this frame — the binding's pre-{@code retainAll} triage side
     *     channel; a parent ABSENT here is vetoed on the projectile lane
     */
    public List<GameplayEdgeEvent> observe(AuthoritativeWorldSnapshot frame,
            List<float[]> projectileChildBirthsXY,
            Map<Long, EndCause> removedMissileEndCauses) {
        return observe(frame, projectileChildBirthsXY, removedMissileEndCauses, Map.of());
    }

    /**
     * The v15 fold: {@code removedMissileContacts} = stable id → the delisted
     * missile's last ship impact ({@link Contact}); the {@code MISSILE_END} emit names the victim
     * ({@code > 0} only) and refines an INTACT triage to {@code END_CAUSE_SHIELD_ABSORBED} when the
     * impact was shield-absorbed. DESTROYED/FIZZLING keep their code (the victim still rides).
     */
    public List<GameplayEdgeEvent> observe(AuthoritativeWorldSnapshot frame,
            List<float[]> projectileChildBirthsXY,
            Map<Long, EndCause> removedMissileEndCauses,
            Map<Long, Contact> removedMissileContacts) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(projectileChildBirthsXY, "projectileChildBirthsXY");
        Objects.requireNonNull(removedMissileEndCauses, "removedMissileEndCauses");
        Objects.requireNonNull(removedMissileContacts, "removedMissileContacts");
        if (sawFirstFrame && frame.simTick() < lastObservedTick) {
            throw new IllegalArgumentException(
                    "frames must be observed in non-decreasing simTick order; got " + frame.simTick()
                            + " after " + lastObservedTick);
        }
        lastObserveLanes.clear();
        lastObserveEndEvents.clear();
        Map<Long, NonShipEntitySnapshot> currentMissilesById = new LinkedHashMap<>();
        for (NonShipEntitySnapshot nonShip : frame.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.MISSILE
                    && nonShip.networkId() >= CombatEntityView.MISSILE_NETWORK_ID_BASE) {
                currentMissilesById.put(nonShip.networkId(), nonShip);
            }
        }
        List<GameplayEdgeEvent> emitted = new ArrayList<>();
        if (sawFirstFrame) {
            List<NonShipEntitySnapshot> births = null;
            float radiusSq = SPLIT_BIRTH_RADIUS_SU * SPLIT_BIRTH_RADIUS_SU;
            for (Map.Entry<Long, NonShipEntitySnapshot> previous : previousMissilesById.entrySet()) {
                long parentId = previous.getKey();
                if (currentMissilesById.containsKey(parentId)) {
                    continue;
                }
                NonShipEntitySnapshot parent = previous.getValue();
                // MISSILE_END (schema v5), widened in v7:
                // EVERY vanished missile with a post-mortem triage
                // entry — sourced included — gets its end-cause put on the wire. v5 confessed
                // the SOURCELESS mine/system-spawn family only (det-vs-shotdown truth);
                // the sourced gap left the TARGET's client unable to
                // tell "server missile IMPACTED at pose P" from "stream dropped it", so the
                // presented puppet overflew hits and outlived the wire entity (the
                // hit-on-one-screen-not-the-other episode). Ends ≤ births and
                // each end rides its own CTRL datagram — inside the 1200-byte datagram budget.
                // An ABSENT triage entry emits nothing (fail-soft: an unreadable post-mortem
                // never mints FX); rides the split lanes' own veto map, BEFORE the MIRV gate
                // below.
                EndCause cause = removedMissileEndCauses.get(parentId);
                if (cause != null) {
                    // The victim form — the last ship impact's wire id (> 0
                    // only, else ABSENT) + the shield refinement of an INTACT triage.
                    Contact contact = removedMissileContacts.get(parentId);
                    long victim = contact != null && contact.victimWireId() > 0L
                            ? contact.victimWireId() : GameplayEdgeEvent.TARGET_ABSENT;
                    int code = cause == EndCause.INTACT && contact != null && contact.shieldAbsorbed()
                            ? GameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED : endCauseWireCode(cause);
                    lastObserveEndEvents.add(GameplayEdgeEvent.missileEnd(
                            frame.simTick(), parentId, code, parent.posX(), parent.posY(), victim));
                }
                if (!isMirvContentId.test(parent.contentId())) {
                    continue;
                }
                if (births == null) {
                    births = new ArrayList<>();
                    for (NonShipEntitySnapshot candidate : currentMissilesById.values()) {
                        if (!previousMissilesById.containsKey(candidate.networkId())) {
                            births.add(candidate);
                        }
                    }
                }
                String lane = null;
                for (NonShipEntitySnapshot birth : births) {
                    float dx = birth.posX() - parent.posX();
                    float dy = birth.posY() - parent.posY();
                    if (dx * dx + dy * dy <= radiusSq) {
                        lane = "missile";
                        break;
                    }
                }
                if (lane == null && cause == EndCause.INTACT) {
                    for (float[] birth : projectileChildBirthsXY) {
                        float dx = birth[0] - parent.posX();
                        float dy = birth[1] - parent.posY();
                        if (dx * dx + dy * dy <= radiusSq) {
                            lane = "proj";
                            break;
                        }
                    }
                }
                if (lane != null) {
                    emitted.add(GameplayEdgeEvent.missileSplit(
                            frame.simTick(), parentId, parent.posX(), parent.posY()));
                    lastObserveLanes.add(lane);
                }
            }
        }
        previousMissilesById = currentMissilesById;
        sawFirstFrame = true;
        lastObservedTick = frame.simTick();
        return List.copyOf(emitted);
    }

    /**
     * Lane tags ({@code "missile"} / {@code "proj"}) parallel to the last
     * {@link #observe} call's emitted list — log-row attribution only, never wire.
     */
    public List<String> lanesForLastObserve() {
        return List.copyOf(lastObserveLanes);
    }

    /**
     * The {@code MISSILE_END} events (sourced included since v7)
     * minted by the last {@link #observe} call — one per vanished
     * missile whose post-mortem triage was readable; the caller concats them onto the
     * tick's down edges beside the returned split events. A MIRV parent emits BOTH
     * kinds (disjoint client routes; harmless).
     */
    public List<GameplayEdgeEvent> endEventsForLastObserve() {
        return List.copyOf(lastObserveEndEvents);
    }

    /** The stable {@code GameplayEdgeEvent.END_CAUSE_*} wire code for a triage verdict. */
    static int endCauseWireCode(EndCause cause) {
        return switch (cause) {
            case DESTROYED -> GameplayEdgeEvent.END_CAUSE_DESTROYED;
            case FIZZLING -> GameplayEdgeEvent.END_CAUSE_FIZZLING;
            case INTACT -> GameplayEdgeEvent.END_CAUSE_INTACT;
        };
    }
}
