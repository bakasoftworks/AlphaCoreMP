package cc.starsector.server.bridge;

import cc.starsector.server.adapter.ShipManeuverCommandView;

/**
 * Immutable {@link ShipManeuverCommandView} snapshot read from the live engine bridge:
 * the 7 {@code ShipEngineControllerAPI} maneuver booleans packed in canonical wire-bit
 * order, with the concrete {@code ShipAPI} kept behind the binding. A plain value
 * holder (like {@link EngineBackedShipEngineStateView}).
 */
public final class EngineBackedShipManeuverCommandView implements ShipManeuverCommandView {

    private final long shipId;
    private final int maneuverMask;
    private final int pilotSeat;
    private final int pilotMask;
    private final int pilotFlags;

    /**
     * The pilot triple
     * is the hull's mark as the serve's mod reads it this tick
     * ({@link ShipManeuverCommandView#PILOT_SEAT_NONE}, 0, 0 when unmarked); the binding already
     * mapped any malformed mark to (0, 0, 0), so an out-of-range value here is a programming
     * error and throws.
     */
    public EngineBackedShipManeuverCommandView(long shipId, int maneuverMask, int pilotSeat, int pilotMask,
            int pilotFlags) {
        if (pilotSeat < 0 || pilotSeat > 0xFF) {
            throw new IllegalArgumentException("pilotSeat must be 0..255; got " + pilotSeat);
        }
        if ((pilotMask & ~PILOT_MASK_BITS) != 0 || (pilotFlags & ~0xFF) != 0) {
            throw new IllegalArgumentException("pilotMask / pilotFlags must fit one byte; got 0x"
                    + Integer.toHexString(pilotMask) + "/0x" + Integer.toHexString(pilotFlags));
        }
        if (pilotSeat == PILOT_SEAT_NONE && (pilotMask != 0 || pilotFlags != 0)) {
            throw new IllegalArgumentException("pilotSeat 0 (no mark) must carry pilotMask 0 and pilotFlags 0; got 0x"
                    + Integer.toHexString(pilotMask) + "/0x" + Integer.toHexString(pilotFlags));
        }
        this.shipId = shipId;
        this.maneuverMask = maneuverMask;
        this.pilotSeat = pilotSeat;
        this.pilotMask = pilotMask;
        this.pilotFlags = pilotFlags;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int maneuverMask() {
        return maneuverMask;
    }

    @Override
    public int pilotSeat() {
        return pilotSeat;
    }

    @Override
    public int pilotMask() {
        return pilotMask;
    }

    @Override
    public int pilotFlags() {
        return pilotFlags;
    }
}
