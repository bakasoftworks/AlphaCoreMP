package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * The {@code AIMP} per-piloted-seat aim-point fan's server half: a
 * per-round, per-tick lane over the serve loop's staged PINT frames that composes the shared
 * change-gated batched frames of each cadence-due tick ({@code cc.server.aimFanEveryTicks}, ~8 Hz).
 * Source is {@code PilotIntentStep}'s staged latest-wins frames — the aim already arrives per
 * tick per piloted seat ({@code aimWorldX/Y}, module seats identical); this lane only fans the
 * freshest per-seat point DOWN so every client can drive the replica's native mouseTarget slew.
 *
 * <p>Emit law (the {@link AutofireTargetLane} idiom + the pose lane's quantize-to-grid step):
 * quantize to the 1/8-u wire grid, include a seat's row only when the quantized point CHANGED
 * since last sent, plus a slow keepalive re-emit ({@code keepaliveEveryTicks}, ~1 s) bounding
 * UDP-loss staleness while a value is held. Seats whose staged intent went stale (no fresh
 * deposit for &gt; keepalive age — a disconnected pilot's frame stays staged) and seats whose
 * aim is non-finite/implausible are SKIPPED + counted, never guessed. Hull loss drops the seat's
 * staged frame ONCE at the despawn drain AND the serve loop refuses to re-stage while the seat
 * holds no mounted hull ({@code PilotServeSession}'s (j) take gate on
 * {@code MatchSeatAuthority.isMounted} — the dead pilot's client keeps
 * depositing), so the source map only holds seats with a live hull.
 *
 * <p>Fail-soft: a throw never reaches the serve loop, and {@link #FAIL_DISARM_AT} consecutive
 * failures disarm the lane for the round (the {@code FoeAutofireGateSampler} law).
 *
 * <p>Wire cost: a due tick of n rows is ceil(n / 64) frames,
 * all sent the same tick, each ≤ 1045 B (16 B header + 1 + 16 B per row + 4 B CRC) — inside the
 * {@link PilotAimFrameCodec#MAX_FRAME_BYTES} datagram budget by construction.
 * 32 seats all moving ≈ 533 B in one frame ≈ 4.2 KB/s/peer at 8 Hz (at ≤ 64 rows the wire is the
 * one-frame form byte for byte); 255 seats ≈ 4 frames ≈ 4.2 KB per due tick ≈ 33 KB/s/peer.
 * Seats are never rotated across ticks: a rotation would make every seat's aim staler by the
 * rotation's length. Interest-gating is the later lever.
 */
final class PilotAimFanLane {

    /** Failed compose() calls before the lane disarms for the round (fail-soft law). */
    static final int FAIL_DISARM_AT = 8;

    private static final String P = "npeer-serve pilot_aim_lane";

    private static final class SeatState {
        PilotIntentFrame lastSeenFrame;
        long lastFreshTick = Long.MIN_VALUE;
        int lastSentQx = Integer.MIN_VALUE;
        int lastSentQy = Integer.MIN_VALUE;
        long lastEmitTick = Long.MIN_VALUE;
    }

    private final int emitEveryTicks;
    private final int keepaliveEveryTicks;
    private final Map<Long, SeatState> stateByShipWireId = new HashMap<>();
    private long lastComposeTick = Long.MIN_VALUE;
    private boolean dead;
    private int failures;
    private int framesComposed;
    private int rowsEmitted;
    private int keepalives;
    private int staleSkips;
    private int implausibleSkips;
    private int dueTicksComposed;
    private int lastTickRowCount;

    PilotAimFanLane(int emitEveryTicks, int keepaliveEveryTicks) {
        if (emitEveryTicks < 1 || keepaliveEveryTicks < 1) {
            throw new IllegalArgumentException("cadences must be >= 1 tick: emit="
                    + emitEveryTicks + " keepalive=" + keepaliveEveryTicks);
        }
        this.emitEveryTicks = emitEveryTicks;
        this.keepaliveEveryTicks = keepaliveEveryTicks;
    }

    /**
     * per-tick poll; never throws. Freshness bookkeeping
     * runs EVERY tick (a staged frame instance change = a fresh deposit); the cadence gates
     * emission only. When this tick is cadence-due AND at least one row is due (changed or
     * keepalive), returns every due row, in ascending ship wire id order, chunked into
     * ceil(rows / {@link PilotAimFrameCodec#MAX_ROWS}) encoded AIMP frames for this same tick's
     * fan — each row in exactly one frame, none deferred to a later tick; else an empty list.
     */
    List<byte[]> composeDueFramesOrEmpty(long simTick, SortedMap<Long, PilotIntentFrame> stagedByShipId) {
        if (dead || stagedByShipId == null || stagedByShipId.isEmpty()) {
            return List.of();
        }
        try {
            for (Map.Entry<Long, PilotIntentFrame> staged : stagedByShipId.entrySet()) {
                SeatState state = stateByShipWireId
                        .computeIfAbsent(staged.getKey(), k -> new SeatState());
                if (state.lastSeenFrame != staged.getValue()) {
                    state.lastSeenFrame = staged.getValue();
                    state.lastFreshTick = simTick;
                }
            }
            if (lastComposeTick != Long.MIN_VALUE
                    && simTick - lastComposeTick < emitEveryTicks) {
                return List.of();
            }
            lastComposeTick = simTick;
            stateByShipWireId.keySet().retainAll(stagedByShipId.keySet());
            List<PilotAimFrameCodec.PilotAimRow> rows = null;
            for (Map.Entry<Long, PilotIntentFrame> staged : stagedByShipId.entrySet()) {
                SeatState state = stateByShipWireId.get(staged.getKey());
                if (state == null || simTick - state.lastFreshTick > keepaliveEveryTicks) {
                    staleSkips++;
                    continue;
                }
                float aimX = staged.getValue().aimWorldX();
                float aimY = staged.getValue().aimWorldY();
                if (!Float.isFinite(aimX) || !Float.isFinite(aimY)
                        || Math.abs(aimX) > PilotAimFrameCodec.MAX_PLAUSIBLE_AIM_ABS_U
                        || Math.abs(aimY) > PilotAimFrameCodec.MAX_PLAUSIBLE_AIM_ABS_U) {
                    implausibleSkips++;
                    continue;
                }
                int qx = PilotAimFrameCodec.quantize(aimX);
                int qy = PilotAimFrameCodec.quantize(aimY);
                boolean changed = qx != state.lastSentQx || qy != state.lastSentQy;
                boolean keepaliveDue = !changed
                        && simTick - state.lastEmitTick >= keepaliveEveryTicks;
                if (!changed && !keepaliveDue) {
                    continue;
                }
                if (rows == null) {
                    rows = new ArrayList<>(8);
                }
                rows.add(new PilotAimFrameCodec.PilotAimRow(staged.getKey(), qx, qy));
                if (changed) {
                    rowsEmitted++;
                } else {
                    keepalives++;
                }
                state.lastSentQx = qx;
                state.lastSentQy = qy;
                state.lastEmitTick = simTick;
            }
            failures = 0;
            if (rows == null) {
                return List.of();
            }
            int n = rows.size();
            int perFrame = PilotAimFrameCodec.MAX_ROWS;
            List<byte[]> frames = new ArrayList<>((n + perFrame - 1) / perFrame);
            for (int from = 0; from < n; from += perFrame) {
                frames.add(PilotAimFrameCodec.encode(rows.subList(from, Math.min(n, from + perFrame))));
            }
            lastTickRowCount = n;
            framesComposed += frames.size();
            dueTicksComposed++;
            return frames;
        } catch (Throwable t) {
            failures++;
            if (failures == 1) {
                System.out.println(P + " compose_fail tick=" + simTick + " err=" + t);
            }
            if (failures >= FAIL_DISARM_AT) {
                dead = true;
                System.out.println(P + " disarmed reason=failures tick=" + simTick);
            }
            return List.of();
        }
    }

    /** Rows in the most recent due tick that composed, across all of its frames (the first-fan operator line). */
    int lastTickRowCount() {
        return lastTickRowCount;
    }

    /** Cumulative composed frames this round, every frame of every due tick (the {@code aimp_frames=} meter). */
    int framesComposed() {
        return framesComposed;
    }

    /** Cumulative due ticks this round that composed at least one frame. */
    int dueTicksComposed() {
        return dueTicksComposed;
    }

    /** Cumulative changed-point row emits this round ({@code aimp_rows=}). */
    int rowsEmitted() {
        return rowsEmitted;
    }

    /** Cumulative keepalive re-emits this round ({@code aimp_keepalive=}). */
    int keepaliveCount() {
        return keepalives;
    }

    /** Cumulative stale-intent seat skips at due ticks ({@code aimp_skip_stale=}). */
    int staleSkipCount() {
        return staleSkips;
    }

    /** Cumulative non-finite/implausible aim skips ({@code aimp_skip_implausible=}). */
    int implausibleSkipCount() {
        return implausibleSkips;
    }
}
