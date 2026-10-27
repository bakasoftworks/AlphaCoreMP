package cc.starsector.server.runtime;

/**
 * The door
 * counters of {@link PilotIntentDatagramServer}. NOT thread-safe — every read and write happens
 * under the door's {@code lock} (its getters keep {@code synchronized (lock)}; the fan lambdas bump
 * inside {@code fanCtrlDown}'s locked block). Boot lifetime, no reset, no logic.
 */
final class PidsDoorCounters {
    long declarationsDropped; // per-session pending PDCL FIFO full — round line pdcl_fifo_drop=, row PF1
    long pinnedIntentDropsTotal; // N-mode cross-seat spoof drops
    long pinnedDeclarationDropsTotal; // PDCL twin
    long awsdNacksReceived; // valid SNNK arrivals
    long awsdNacksDropped; // per-session pending set at cap — flood bound
    long ownerMarksReceived; // MARK rows printed
    long ownerMarksRateDropped; // MARK inside the per-seat 1 s window
    long chatRelayed; // CHTU sends fanned as CHTD
    long chatRateDropped; // per-session bucket empty — policy drop, session survives
    long chatUnseatedDropped; // unbound-seat sender — null-authority smoke door only
    /** CHTU sends from a stale sender — BYE'd, failed or
     * seat-vacated ({@code PidsChatVerbs#holdsSeatLocked} false) — dropped at the door BEFORE the rate
     * bucket: no verb, no fan, no reply (a non-live session's at the CTRL delivery head, undecoded); ALSO a {@code /module <slot>} whose sender fails the post-write re-check
     * (the claim is undone). Reader: {@code PilotIntentDatagramServer#chatStaleSessionDropped}
     * (the {@code PilotDatagramEndpointSmoke} BY rows). Delete trigger: never — a security drop counter. */
    long chatStaleSessionDropped;
    /** Scan row 1 AUTH-1 + parse-bounds-5 — CTRL frames (every kind
     * but CHTU, which the same head counts into the chat counter above) and SNAP PINT frames from a BYE'd / failed / closed session,
     * dropped at the delivery head before any decode; and RESOURCE-LOGSINK-1 — PSTE echoes inside the per-session
     * {@code MARK_MIN_INTERVAL_MILLIS} window (decoded, not printed). Round-line tokens {@code ctrl_stale_drop=}
     * / {@code snap_stale_drop=} / {@code posture_rate_drop=} (the door group's style); reader = {@code UpDoorLivenessRateRows}. */
    long ctrlStaleSessionDropped;
    long snapStaleSessionDropped;
    long postureEchoRateDropped;
    long variantSubmitsReceived; // W15 v0 VSUB frames decoded + past the rate gate
    long variantSubmitsRateDropped; // VSUB bucket empty — answered custom_rate_limited
    long variantSubmitsUnseatedDropped; // unbound-seat sender — the chat precedent
    long variantReceiptsSent; // VACK unicasts, both verdicts
    long chatCommandsHandled; // W7 B1a: '/' lines dispatched + replied, never fanned; + a LED command line answered, never relayed
    long seatNamesFanned; // SNMR fans: stored-SNAM triggers
    long hulkFracturesFanned; // HKFR fans: serve-loop drains
    long hulkPosesFanned; // HKPS fans: serve-loop drains
    long asteroidPosesFanned; // ASTP fans: serve-loop observe //
    long pilotAimsFanned; // AIMP fans: serve-loop drains
    long hulkCutGeometryFanned; // HKCG fans: serve-loop drains
    long rosterAppendsFanned; // RAPP fans: serve-loop wave drains
    long moduleMountsFanned; // MMNT fans: serve-loop mount/dismount drains
    long commandTransfersFanned; // XFER fans: serve-loop transfer lane
    long matchOversFanned; // MOVR fan: at most one per serve
    long listingsEmitted; // SS382 L3: no-arg listing LSTD mints, latest-wins
    long listingFramesDripped; // SS382 L3: LSTD frames handed to the CTRL spool
}
