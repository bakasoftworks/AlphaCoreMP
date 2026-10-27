package cc.starsector.server.protocol;

/**
 * Phase of a {@link GameplayEdgeEvent}, constrained per
 * {@link GameplayEdgeEventKind}:
 * {@code WEAPON_DISCHARGE} is {@link #ONE_SHOT}; {@code VENT_WINDOW} uses
 * {@link #START}/{@link #END}; {@code SYSTEM_WINDOW} uses {@link #ON}/{@link #OFF}.
 */
public enum GameplayEdgeEventPhase {
    ONE_SHOT(1),
    START(2),
    END(3),
    ON(4),
    OFF(5);

    private final int wireCode;

    GameplayEdgeEventPhase(int wireCode) {
        this.wireCode = wireCode;
    }

    /** Stable on-wire code for this phase (never the ordinal). */
    public int wireCode() {
        return wireCode;
    }

    /** Resolve a phase from its {@link #wireCode()}; fail-closed on an unknown code. */
    public static GameplayEdgeEventPhase fromWireCode(int wireCode) {
        for (GameplayEdgeEventPhase phase : values()) {
            if (phase.wireCode == wireCode) {
                return phase;
            }
        }
        throw new IllegalArgumentException("unknown gameplay edge event phase wire code: " + wireCode);
    }
}
