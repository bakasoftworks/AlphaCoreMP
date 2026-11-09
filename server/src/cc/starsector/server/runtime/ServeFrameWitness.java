package cc.starsector.server.runtime;

/**
 * The serve frame's meter stamps, carried from the {@code serveFrame*} phase method that takes each one to the
 * later phases that read it ({@link PilotServeSession}). Each field is named exactly as the local it carries: the
 * writing phase stores its local at its end, a reading phase copies the field back into a local of the same name
 * at its top, so the phase bodies read and write the same names they always did.
 *
 * <p>WHY: the frame used to be one method too large for the JIT to compile (past HotSpot's 8,000-byte huge-method
 * limit), so it ran interpreted for the whole serve; split into phases, each compiles on its own, and these stamps
 * are the values that cross a phase boundary. One instance per serve session, allocated with it, so the frame
 * allocates nothing for them; primitives only, so it holds no reference. No per-frame reset: every field has one
 * unconditional writer that runs before any reader in the same frame.
 */
final class ServeFrameWitness {
    // serveFramePace: the pacer, the work start and the frame-top drain
    long frameDueNanos;
    long frameWorkStartNanos;
    boolean frameInDebt;
    long emitWaitNanos;
    long wprevTick;
    long wprevWakeNanos;
    long wprevBusyNanos;
    long witnessIoFrameStart;
    long witnessCcwrFrameStart;
    // serveFrameSessions, serveFrameOperator, serveFrameSeat: the pre sub-bracket ends
    long preSessEndNanos;
    long preOpEndNanos;
    long preSeatEndNanos;
    // serveFrameIntent: the intent bracket and the pre bracket's close
    long witnessIntentNanos;
    long witnessIoIntentNanos;
    long witnessPreEndNanos;
    long witnessIoPreEnd;
    // serveFrameStep
    long witnessStepEndNanos;
    long witnessIoStepEnd;
    // the det sub-brackets (serveFrameDetGrowth through serveFrameDetFans) and the det_edges split
    long detGrowthEnd;
    long detReadersEnd;
    long edgesMountsEnd;
    long edgesObserveEnd;
    long edgesBirthsEnd;
    long edgesMergeEnd;
    long detEdgesEnd;
    long detDespawnEnd;
    long detSeatEnd;
    long witnessDetectEndNanos;
    // serveFrameCompose: the comp and send brackets
    long witnessIoDetectEnd;
    long witnessComposeEndNanos;
    long witnessIoComposeEnd;
    long witnessSendEndNanos;
    long witnessIoSendEnd;
    // serveFrameBookkeep: the frame's work
    long frameWorkNanos;
}
