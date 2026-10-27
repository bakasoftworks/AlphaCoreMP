package cc.starsector.server.adapter;

/**
 * Project-owned boundary around one AI decision step.
 *
 * <p>Records which AI owner consumed which ship state at which simulation step,
 * and which command intent was emitted, without treating AI as the direct owner
 * of movement integration. AI reads server-owned motion through
 * {@link ShipMotionView}; AI output flows into {@link ShipCommandSink} as intent,
 * not as direct transform writes.
 *
 * <p>Derived from the AiAdvanceBoundary contract.
 * Product code keys the boundary on server tick and stable entity identity;
 * trace candidate/phase markers are validation-only and are not part of this API.
 */
public interface AiAdvanceBoundary {

    /** Stable AI owner identity. */
    long aiOwnerId();

    /** Stable ship identity the AI is advancing. */
    long shipId();

    /** Server tick of this advance. */
    long simTick();

    /** Fixed step amount for this advance, in seconds. */
    float stepAmount();

    /** Motion state the AI read for this decision. */
    ShipMotionView input();

    /** Facing observed before the advance. */
    float preFacing();

    /** Angular velocity observed before the advance. */
    float preAngularVelocity();

    /**
     * Invoked for one AI advance: the AI reads {@link #input()} and emits intent
     * into {@code sink}. Implementations must not mutate transforms directly.
     */
    void advance(ShipCommandSink sink);
}
