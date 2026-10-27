package cc.starsector.server.runtime;

import cc.starsector.server.match.PasswordSeatLedger;

/**
 * The OPTIONAL half of {@link PilotIntentDatagramServer#openMultiPeer} — everything past
 * {@code maxSessions} — as one value (the carve plan's item (2), the
 * PIDS row;
 * the eight telescoping overloads collapsed to one widest entry + this record; the door's
 * behaviour is byte-identical, every narrow overload's null/false default is {@link #NONE} with
 * the named fields set). The four test-facing overloads (4/5/7/8-arg) stay as one-line delegates;
 * the serve ({@code PilotServeSession}) builds this record directly.
 *
 * @param requiredDoorFingerprint D4.7 content-fingerprint check, armed when non-null (null = off).
 * @param matchPassword PASSWORD mode: the ONE shared credential every HELLO must
 *        present in its credential slot. Paired with {@code passwordLedger}: both non-null
 *        together — a half-configured pair is fail-closed at the door; both null = token mode.
 *        The seat authority stays the ONE occupancy truth: the resolved seat's own descriptor
 *        token drives {@code SeatAdmission#authorize}/vacate exactly as token mode does.
 * @param passwordLedger resolves each client-instance id ({@code hello.clientId()}) to a
 *        descriptor seat ({@code desiredShipId} 0 = first-free); see {@code matchPassword}.
 * @param pickableVariants W7 B1b {@code /ship} pickable allowlist (null = picking unavailable).
 * @param variantCatalog W7c B2 variant catalog (null = no admission burst).
 * @param moduleSeats W2b E3 module-seats arbiter (null = {@code /module} unavailable). W3 L1b-ii
 *        twin gate: the arbiter mints on EVERY serve (transfer verbs + vacate drain) while
 *        MODULE_* dispatch arms only on {@code moduleVerbsArmed} — the serve computes it, the
 *        door just gates; the narrow overloads pass {@code false} = the pre-W3 equivalence for
 *        a null arbiter, so every existing caller keeps byte-identical behaviour.
 * @param moduleVerbsArmed see {@code moduleSeats}.
 * @param transferTargets the {@code /transfer} census (null = the knob-off
 *        "unavailable" shape).
 * @param bootBans the P27 boot read
 *        ({@link BanSet#load}), installed by {@code openMultiPeer} BEFORE the reader thread starts
 *        so the first HELLO already meets it (the old post-open install left a
 *        sub-ms HELLO window on the empty set, with no sweep on install). Null = keep the door's
 *        empty default ({@code new BanSet()}) — a rig that calls
 *        {@link PilotIntentDatagramServer#installBans} later stays valid.
 * @param chatSourceHold the {@code moderation.chatSourceHold}
 *        arm handed to the door's {@link DoorSourceHold} (the serve resolves it loud,
 *        {@code PilotServeResolver}); null = {@link DoorSourceHold.Policy#OFF} (the pre-W16 law — the
 *        serve's compiled default is {@code MATCH} since W16; a null here is a rig's OFF).
 * @param sourceSeatCap the per-address LIVE-SEAT allowance ({@link SourceSeatCap}; the serve resolves
 *        {@code moderation.maxSeatsPerSource} loud, compiled default 1, with the operator's
 *        {@code moderation.sourceCapExempt} addresses already exempt); the door binds it to this box's
 *        addresses at open. Null = {@link SourceSeatCap#OFF} (every rig and narrow overload keeps today's
 *        admission byte-for-byte).
 * @param matchDoc the match document the door serves inside a password-proven PROBE reply whose presented
 *        {@code match=} term differs from its id ({@link ProbeMatchDoc}). Password mode only: a token-mode door
 *        serves nothing. Null = no document served (every rig and narrow overload).
 * @param pvp the served shape is the PvP shape: the password door then places joiners by the EFFECTIVE team (a
 *        seat may play for the other side), counts each team against its side's seat count, and remembers a
 *        player's team for the rejoin ({@link SeatTeamTable}). False = every other shape (and every rig and narrow
 *        overload): team picks follow the descriptor seat sides only.
 */
public record PidsOpenParams(String requiredDoorFingerprint, String matchPassword,
        PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
        VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
        boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans,
        DoorSourceHold.Policy chatSourceHold, SourceSeatCap sourceSeatCap, ProbeMatchDoc matchDoc, boolean pvp) {

    /** Every optional OFF: the 4-arg door (fingerprint off, token mode, no picks/catalog/modules/transfers/boot bans). */
    public static final PidsOpenParams NONE = new PidsOpenParams(null, null, null, null, null, null, false, null, null);

    /** Canonical — a null arm is {@code OFF}; a null allowance is {@link SourceSeatCap#OFF}. */
    public PidsOpenParams {
        chatSourceHold = chatSourceHold == null ? DoorSourceHold.Policy.OFF : chatSourceHold;
        sourceSeatCap = sourceSeatCap == null ? SourceSeatCap.OFF : sourceSeatCap;
    }

    /** The twelve-component form: not the PvP shape, so every construction site that predates the flag keeps
     *  today's door byte-for-byte. */
    public PidsOpenParams(String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans,
            DoorSourceHold.Policy chatSourceHold, SourceSeatCap sourceSeatCap, ProbeMatchDoc matchDoc) {
        this(requiredDoorFingerprint, matchPassword, passwordLedger, pickableVariants, variantCatalog,
                moduleSeats, moduleVerbsArmed, transferTargets, bootBans, chatSourceHold, sourceSeatCap, matchDoc, false);
    }

    /** The eleven-component form: no match document served, so every construction site that predates it keeps
     *  today's door byte-for-byte. */
    public PidsOpenParams(String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans,
            DoorSourceHold.Policy chatSourceHold, SourceSeatCap sourceSeatCap) {
        this(requiredDoorFingerprint, matchPassword, passwordLedger, pickableVariants, variantCatalog,
                moduleSeats, moduleVerbsArmed, transferTargets, bootBans, chatSourceHold, sourceSeatCap, null);
    }

    /** The ten-component form — the seat allowance {@code OFF},
     *  so every construction site that predates it keeps today's door byte-for-byte. */
    public PidsOpenParams(String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans,
            DoorSourceHold.Policy chatSourceHold) {
        this(requiredDoorFingerprint, matchPassword, passwordLedger, pickableVariants, variantCatalog,
                moduleSeats, moduleVerbsArmed, transferTargets, bootBans, chatSourceHold, SourceSeatCap.OFF);
    }

    /** The legacy nine-component form — the hold
     *  arm {@code OFF}, so every existing construction site keeps today's door byte-for-byte. */
    public PidsOpenParams(String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans) {
        this(requiredDoorFingerprint, matchPassword, passwordLedger, pickableVariants, variantCatalog,
                moduleSeats, moduleVerbsArmed, transferTargets, bootBans, DoorSourceHold.Policy.OFF);
    }
}
