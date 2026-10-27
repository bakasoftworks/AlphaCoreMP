package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * W12 L2 — the sim-thread voice lane, constructed per round
 * in the serve block beside the transfer lane. Each tick it resolves every seat's voice ANCHOR
 * (the wire-visible body the seat emits and hears proximity from) and writes its position into
 * the {@link VoicePositionSlot} back buffer, then publishes. This is the whole on-frame cost of
 * voice: ≤ N binary searches over the tick's entity list, no allocation.
 *
 * <p><b>Anchor rule:</b> {@code anchor(seat) = podLane.hasPod(seat) ? podLane.podWireIdOrZero(seat)
 * : seatWireIdBySeatId.get(seat)}. The seat's reserved wire id is FIXED for the whole match
 * ({@code PersistentPilotIntentCombatServer} seeds it once, single writer) and the stable-id
 * registry re-points it onto the fresh hull at respawn / {@code /transfer} commit / module mount,
 * so no seat → hull map is needed here; only the transfer-pod transit window carries a different
 * body. A dead seat's wreck root keeps that same wire id until the remount ("player stays in the
 * wreck", owner).
 *
 * <p><b>Ghost spot:</b> when the anchor is absent from {@code snap.entities()}
 * (hull left the list, not yet spawned), the seat's LAST published x/y carry forward with
 * {@code present=false} — the relay still ranges proximity against that spot (a dead player keeps
 * hearing the fight where they fell) but the client reads {@code present} for the HUD glyph.
 * VERIFIED: a destroyed hull LEAVES {@code entities()} at death — the
 * builder skips every {@code !alive()} lifecycle ({@code EngineBackedLiveWorldSnapshotSource}'s
 * {@code buildStableRosterSnapshot}; a hulk reads {@code isAlive()==false}), so the ghost spot IS the only post-death
 * source here. The authoritative hulk pose does exist on the HKPS lane under the same root wire
 * id ({@code HulkFractureDetector.Family.rootEntityId}) — a later refinement may consult it.
 */
public final class VoiceAnchorLane {

    /** The pod-transit override source (the {@link TransferPodLane} pair; contract rows fake it). */
    public interface PodAnchor {
        boolean hasPod(int seatId);

        long podWireIdOrZero(int seatId);
    }

    private final VoicePositionSlot.View view;
    private final int[] seatIds;
    private final long[] fixedWireIds;
    private final PodAnchor podLane; // nullable (transfer disabled on this serve)

    public VoiceAnchorLane(VoicePositionSlot slot, Map<Integer, Long> seatWireIdBySeatId,
            TransferPodLane podLane) {
        this(slot, seatWireIdBySeatId, podLane == null ? null : new PodAnchor() {
            @Override
            public boolean hasPod(int seatId) {
                return podLane.hasPod(seatId);
            }

            @Override
            public long podWireIdOrZero(int seatId) {
                return podLane.podWireIdOrZero(seatId);
            }
        });
    }

    public VoiceAnchorLane(VoicePositionSlot slot, Map<Integer, Long> seatWireIdBySeatId,
            PodAnchor podLane) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(seatWireIdBySeatId, "seatWireIdBySeatId");
        int[] seats = seatWireIdBySeatId.keySet().stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(seats);
        this.seatIds = seats;
        this.fixedWireIds = new long[seats.length];
        for (int i = 0; i < seats.length; i++) {
            Long wire = seatWireIdBySeatId.get(seats[i]);
            this.fixedWireIds[i] = wire == null ? 0L : wire;
        }
        this.podLane = podLane;
        this.view = slot.bind(seats);
    }

    /** Sim thread, once per tick after the snapshot publishes. No allocation. */
    public void onTick(AuthoritativeWorldSnapshot snap) {
        List<AuthoritativeEntitySnapshot> entities = snap.entities();
        int back = view.beginWrite();
        int front = view.front();
        for (int slot = 0; slot < seatIds.length; slot++) {
            int seat = seatIds[slot];
            long anchor = (podLane != null && podLane.hasPod(seat))
                    ? podLane.podWireIdOrZero(seat) : fixedWireIds[slot];
            int idx = anchor <= 0L ? -1 : indexOfEntity(entities, anchor);
            if (idx >= 0) {
                AuthoritativeEntitySnapshot e = entities.get(idx);
                view.set(back, slot, anchor, e.locX(), e.locY(), true);
            } else {
                // Ghost spot: carry the last published position forward, mark absent.
                view.set(back, slot, anchor, view.xOf(front, slot), view.yOf(front, slot), false);
            }
        }
        view.publish();
    }

    /** Binary search over the ascending-unique {@code entityId} list (the snapshot's own law). */
    static int indexOfEntity(List<AuthoritativeEntitySnapshot> entities, long entityId) {
        int lo = 0;
        int hi = entities.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long id = entities.get(mid).entityId();
            if (id < entityId) {
                lo = mid + 1;
            } else if (id > entityId) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** The view this lane writes (tests read it back). */
    public VoicePositionSlot.View view() {
        return view;
    }
}
