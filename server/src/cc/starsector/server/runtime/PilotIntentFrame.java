package cc.starsector.server.runtime;

/**
 * One client's per-tick faithful PILOT intent for the live two-client 1v1 demo
 * — the wire-carriable union of {@link PilotManeuverIntent} (movement keys +
 * aim) and {@link PilotFireIntent} (fire button + aim) for a single owned ship on a single server
 * tick.
 *
 * <p>The faithful pilot lanes ({@code PilotManeuverIntent}/{@code PilotFireIntent}) were proven
 * headless in-process but had NO socket representation: nothing carried
 * them over a real connection the way {@link LiveClientIntent} carries the OLD unfaithful command
 * lane. This frame is that missing wire shape for the faithful lane — {@link PilotIntentWireCodec}
 * serializes it to fixed-width bytes that cross a real off-loopback socket
 * ({@link PilotIntentSocketBridge}), and the server applies the carried input on its tick. Live
 * MOVEMENT is the predict-replay lane: {@code applyPilotIntent} replays the raw {@link #heldControls}
 * movement-ordinal bitmask through the engine's own {@code giveCommand}, verbatim, with
 * no key-to-command interpretation (see the {@link #HELD_CONTROLS_MASK} javadoc). Live FIRE is
 * {@link #toFireIntent()}. The {@link #toManeuverIntent()}-&gt;{@code BasicEngineAI} path is the
 * DORMANT legacy/puppet movement lane (no live callers of {@code toManeuverIntent()} repo-wide).
 * So the live per-tick motion/fire is the engine's, driven by a remote client's own aim + keys +
 * fire button delivered over the network — not a server script and not the unfaithful public-enum
 * lane.
 *
 * <p><b>{@code ownShipFacingDeg}.</b> The own ship's REAL visual
 * heading the client's local engine computed this frame ({@code ownShip.getFacing()}), carried
 * SEPARATELY from {@code cursorFacingDeg}. Native Starsector flight is A/D-driven: the cursor aims
 * WEAPONS, it does NOT steer the hull (the falsified fix (A) wrongly equated the two and cursor-locked
 * the ship). The predict-replay server pins its own-ship copy to
 * THIS heading ({@code setFacing}) so translation thrust (ACCELERATE/STRAFE) integrates in the same
 * reference frame the human actually flew, while {@code cursorFacingDeg} still drives the weapon-aim lane.
 * The legacy/puppet lanes that have no separate hull heading just pass {@code cursorFacingDeg} here (the
 * field is don't-care for them); only the predict-replay branch consumes it.
 *
 * <p><b>{@code aimWorldX}/{@code aimWorldY} (v3).</b> The player's aim
 * WORLD POINT this frame — the client's own-ship {@code getMouseTarget()} (engine-computed world
 * coords; the same live value the parity walker reads). Carried per frame like the facing pin because
 * the engine's internal {@code Ship.advance} weapon-group loop re-slews every manual weapon group
 * toward {@code Ship.mouseTarget} EVERY frame; before v3 the server never wrote that field, so its
 * mounts tracked world origin (0,0) and the authoritative shot left the player's aim line by up to
 * ~12°. The server sets its owned ship's live
 * {@code mouseTarget} vector in place from this point each tick (vanilla idiom — the same in-place
 * {@code .set} the player/AI paths use), and the engine slews its own mounts (arc limits, turn
 * rates — engine-driven, no {@code setCurrAngle} hack). Producers with no real cursor (synthetic
 * ladders/ghosts) pass their aim bearing projected to a point, or (0,0) when aim is don't-care.
 *
 * <p><b>{@code autofireGroupsMask} (v4).</b> Bit {@code g} set = the client's
 * own-ship weapon group {@code g} {@code isAutofiring()} this frame — the engine's OWN toggle state
 * (the R-key click-toggle the client engine already accepted), mirrored so the server can diff it
 * against its hull per group and issue the engine's own {@code giveCommand(TOGGLE_AUTOFIRE, null, g)}
 * on mismatch (engine-faithful; deploy defaults agree by construction so the first frames diff to
 * nothing). Sentinel {@code -1} = ABSENT (producer does not sample autofire state); the server SKIPS
 * the diff entirely — a literal 0 from a legacy/synthetic producer (ghost replay, ladders, v3
 * recordings) would instead TOGGLE OFF the deploy-default autofire groups on a real hull. Use
 * {@link #of} for such producers.
 *
 * <p><b>{@code targetWireId} (v5).</b> The client's own R-key ship-target designation as a
 * WIRE id — {@code long} because the entire id space is long (this record's {@code shipId}, the client
 * {@code remoteEntities} keys, GPEE {@code entityId()}); truncating to int would invite id-fork bugs to
 * save 4 bytes the budget does not need. {@link #TARGET_ABSENT} ({@code -1}, the {@link #of}
 * default) = producer never samples targets — the server skips designation entirely (pre-v5 behavior);
 * {@link #TARGET_NONE} ({@code -2}) = live producer sampled and the client's own engine has NO target —
 * the server clears via {@code setShipTarget(null)} iff its hull's target differs. Replaces the server's
 * own-pick {@code setShipTarget} substitution with the pilot's real designation.
 *
 * <p><b>{@code fireEdgeSeq} (v5).</b> 1-wire-byte wrapping (0..255) rising-edge counter of
 * the client's own sampled {@code fireHeld} — cumulative input STATE, so it survives datagram gaps and
 * latest-wins conflation where a level-sampled tap is simply lost.
 * The server's held fire lane arms a depth-1 bounded press latch on any observed advance (wrap-safe
 * {@code !=}); {@code 0} (the {@link #of} default) never advances, so legacy producers stay
 * behavior-identical.
 *
 * <p><b>{@code activeTubeOrdinal} (v6).</b> The selected weapon group's ACTIVE-TUBE
 * pointer as per-tick intent STATE — the active weapon's index in the group's
 * {@code getWeaponsCopy()} order iff that group is ALTERNATING, else
 * {@link #TUBE_ORDINAL_ABSENT} ({@code -1}, also the {@link #of} default). Carried because the
 * engine advances an alternating group's tube pointer on select-command RISING EDGES
 * ({@code WeaponGroup.selectNextActive}), while this wire carries selection STATE — a re-select of
 * the already-selected group is a wire-invisible client-only {@code +1} (the seat-1
 * off-by-one class). The pointer is a deterministic function of input
 * edges the mod cannot observe (raw select keys = input-layer crossing), so ordinal STATE is the
 * faithful carriage; the server conforms read-compare-{@code setCurrentActive} at FIRE-QUIESCENT
 * ticks only — the engine's own combat-AI idiom verbatim
 * — which self-heals the whole divergence class (missed edges, one-side refusals,
 * ammo-skip forks) at the next quiescent tick. No knob: unconditional faithful carriage; legacy
 * producers ride the {@code -1} sentinel (the {@code autofireGroupsMask} ABSENT precedent).
 */
public record PilotIntentFrame(
        long shipId,
        int frameSeq,
        float cursorFacingDeg,
        int heldControls,
        boolean fireHeld,
        int selectedWeaponGroup,
        float ownShipFacingDeg,
        float aimWorldX,
        float aimWorldY,
        int autofireGroupsMask,
        long targetWireId,
        int fireEdgeSeq,
        int activeTubeOrdinal,
        long keyCodes,
        int mouseButtons,
        int controlKeys) {

    /**
     * Widest valid {@link #heldControls} bit range (bits 0..20: the 7 movement bits 0..6 + the {@link #USE_SHIP_SYSTEM}
     * bit 7 + the {@link #VENT_FLUX} bit 8 + the {@link #RETREATING} bit 9 + the {@link #SHIELD_ON} bit 10 + the
     * {@link #PHASE_CLOAK} bit 11 + the {@link #PULL_BACK_FIGHTERS} bit 12 + the eight mod-ability slots
     * {@link #ABILITY_SLOTS_MASK} bits 13..20). Bit 21 and above are refused by the record constructor, so a decode
     * of a frame carrying one fails (the serve drops that session as corrupt, never a crash). The low
     * 7 bits carry one of two
     * lane-specific MOVEMENT meanings, BOTH of which fit in bits 0..6:
     * <ul>
     *   <li><b>Legacy {@code BasicEngineAI} lane</b> — a {@link PilotManeuverIntent} 5-key bitset (bits 0..4,
     *       {@link PilotManeuverIntent#ALL_CONTROLS}); decoded via {@link #toManeuverIntent()}.</li>
     *   <li><b>Unified input-lane (predict-replay)</b> — a bitmask of the engine ship-command
     *       MOVEMENT ORDINALS (the {@code giveCommand} command enum) the client's native player
     *       control emitted on its own ship this frame (ordinals 0..6 = {@code TURN_LEFT,TURN_RIGHT,
     *       STRAFE_LEFT,STRAFE_RIGHT,ACCELERATE,ACCELERATE_BACKWARDS,DECELERATE}, proven bijective on real
     *       bytes). The server replays each set bit verbatim via
     *       the engine's own {@code giveCommand}, so there is NO hand key->command interpretation
     *       on either side (predictor==authority by construction).</li>
     * </ul>
     * Both lanes share this wire shape byte-for-byte; only the consuming code differs, so every off-engine
     * byte-identity proof still holds. The two never mix at runtime (different mission/gate/plugin).
     */
    public static final int HELD_CONTROLS_MASK = 0x1FFFFF; // bits 0..20 (7 movement ordinals + ship-system + vent-flux + retreating + shield + phase-cloak + pull-back-fighters + 8 ability slots)

    /**
     * The ship-system ACTIVATION bit (bit 7), carried on {@code heldControls} ABOVE the
     * 7 movement-ordinal bits (0..6), so the wire stays byte-identical (same precedent as the
     * {@link PilotManeuverIntent#STRAFE_MODIFIER}: a new SET of valid {@code heldControls} values, not a new
     * field — schema/CRC/frame-width unchanged). Bit 7 lies outside BOTH lane interpretations of bits 0..6 (the
     * legacy {@link PilotManeuverIntent} 5-key bitset and the predict-replay movement-ordinal mask), so it never
     * collides with a movement command.
     *
     * <p>The predict client (the only producer) sets it while its native-flown own ship's system
     * {@code isOn()} (= IN|ACTIVE exactly; previously
     * {@code isActive()} = IN|ACTIVE|OUT, which held the bit through the whole OUT chargedown so a vanilla
     * double-tap toggle CANCEL only reached the server at OUT-exit ≈ 2×out late). The engine's OWN
     * activation decision, read the same allowlist-clean way the 7 {@code ShipEngineControllerAPI} movement
     * booleans are; the rising edge still lands at chargeup (IN) start (the ACTIVE-only
     * {@code isStateActive()} used before dropped the 1 s chargeup, putting the server ~1 s late). The
     * server mirrors BOTH directions through the engine's faithful {@code ShipAPI.useSystem()}
     * (NOT a speed hack): while the bit is HELD and its own system is not engaged it re-presses each tick
     * guarded by {@code ShipSystemAPI.canBeActivated()} (held-driven activation — the engine's
     * own buffered-input pattern; {@code canBeActivated()} checks ONLY {@code requiresZeroFluxBoost}, never
     * cooldown — and a press during COOLDOWN silently
     * no-ops, so the held re-press rides out refusals); while the bit is LOW and the server's own system
     * still {@code isOn()} it presses ONCE per mismatched tick — the cancel mirror (vanilla double-tap
     * cancel: ACTIVE + canBeDeactivated → OUT; a non-toggle system
     * consumes presses only at IDLE, so held fixed-duration systems like the Mudskipper Mk II's
     * {@code maneuveringjets} are untouched by the low-bit branch). The bit only rises on activations the
     * client engine already ACCEPTED.
     */
    public static final int USE_SHIP_SYSTEM = 1 << 7; // 0x80

    /**
     * The flux-VENT bit (bit 8), carried on {@code heldControls} above
     * {@link #USE_SHIP_SYSTEM} by the same precedent: a new SET of valid {@code heldControls} values, not a new
     * field — the int was always 4 wire bytes, so schema/CRC/frame-width are unchanged and every off-engine
     * byte-identity proof still holds for frames that don't set it.
     *
     * <p>The predict client (the only producer) sets it while its native-flown own ship is VENTING
     * ({@code ShipAPI.getFluxTracker().isVenting()} — the engine's OWN accepted vent decision, read the same
     * allowlist-clean way as the bit-7 {@code isActive()}), so a V press the engine refused (overloaded)
     * is never carried. The server drives its authoritative own ship on the RISING edge via
     * {@code ShipAPI.giveCommand(ShipCommand.VENT_FLUX, null, 0)} — byte-verified
     * to enqueue the engine's own ship-command that
     * {@code Ship.advance}'s dispatch routes to {@code FluxTracker.ventFlux()}, which self-guards (no-ops if
     * overloaded / vent rate &le; 0 / already venting) and derives the vent duration from the SERVER's own flux —
     * authoritative, faithful, NOT a client-flux write. The deliberately rejected alternative,
     * server-side "flux reconcile" (copying the client's flux down), would invert authority and
     * fake the value while skipping the mechanism (venting is a state: weapons/shields offline, vulnerability).
     */
    public static final int VENT_FLUX = 1 << 8; // 0x100

    /**
     * The own-ship RETREATING bit (bit 9), carried on {@code heldControls} above
     * {@link #VENT_FLUX} by the same bit-7/bit-8 precedent: a new SET of valid {@code heldControls}
     * values, not a new field — zero wire bytes, schema/CRC/frame-width unchanged for frames that
     * don't set it.
     *
     * <p>The predict client (the only producer) sets it while its native-flown own ship
     * {@code isRetreating()} — the engine's OWN accepted retreat state, NOT the keypress: vanilla's
     * capture gate runs in the client's local engine (unpaused tap + reachability — engines on,
     * within range of the resolved goal edge, facing outward),
     * so the bit rises only on retreats the client engine
     * already accepted. Vanilla has NO key-cancel (a second tap fails the {@code travelDrive.isOn()}
     * guard); the only organic clear is vent-start, so a falling edge
     * mirrors an engine-side auto-cancel. The server drives its authoritative hull on the RISING edge
     * (engage the vanilla retreat travel drive + {@code setRetreating(true,false)} — the drive engage
     * is load-bearing: retreat flight is drive-powered with no held movement keys) and on the FALLING
     * edge clears drive-first (the engine refuses the clear while the drive is on).
     * Map-edge removal is then the ENGINE's own {@code CombatFleetManager.advance()} scan — never a
     * server call.
     */
    public static final int RETREATING = 1 << 9; // 0x200

    /**
     * The own-ship SHIELD-ON bit (bit 10), carried on {@code heldControls} above
     * {@link #RETREATING} by the same bit-7/8/9 precedent: a new SET of valid {@code heldControls}
     * values, not a new field — zero wire bytes, schema/CRC/frame-width unchanged for frames that
     * don't set it (no PINT bump: v5 bumped for FIELDS, not bits).
     *
     * <p>The predict client (the only producer) sets it while its native-flown own ship's shield is
     * RAISED ({@code ShipAPI.getShield().isOn()} — the engine's OWN shield state, so every vanilla
     * capture gate ran client-side first: the overload lockout, the 0.2 s buffered raise while
     * overloaded, and the AUTO_OMNI assist's output are all folded into the sampled state). The bit
     * is a <b>state mirror</b>, and the server drives its authoritative hull by the
     * unified state-gated press law, TYPE-INDEPENDENT — the engine pins every shield's charge
     * tracker to FRONT flip semantics (a command-present tick FLIPS
     * desired, command-free ticks KEEP state, raises start only from IDLE
     * — superseding the falsified OMNI/FRONT split): ONE
     * {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK, aimWorld, 0)} iff
     * {@code bit ? (!isOn() && getActiveArc()==0f) : isOn()} — raise only at idle, lower only
     * while on, never mid-retraction (the vanilla shield AI's own gating).
     * The engine's own {@code canUseShields}/hulk
     * checks still gate every dispatch. The carried
     * {@code aimWorldX/Y} point doubles as the aimed shield-facing channel (FRONT/OMNI unfold
     * toward it via the engine's own math). <b>PHASE GUARD</b> (load-bearing, server-side): hulls
     * with a phase cloak skip entirely — the SAME verb phase-TOGGLES there;
     * phase is its own future contract cell.
     */
    public static final int SHIELD_ON = 1 << 10; // 0x400

    /**
     * The own-ship PHASE-CLOAK bit (bit 11), carried on
     * {@code heldControls} above {@link #SHIELD_ON} by the same bit-7/8/9/10 precedent: a new SET
     * of valid {@code heldControls} values, not a new field — zero wire bytes, schema/CRC/
     * frame-width unchanged for frames that don't set it (no PINT bump: v5 bumped for FIELDS, not
     * bits; the int was always 4 wire bytes in BOTH trees).
     *
     * <p>The predict client (the only producer) sets it while its native-flown own ship's phase
     * cloak {@code isOn()} — IN|ACTIVE exactly, which flips at
     * toggle-ACCEPTANCE, so every vanilla capture gate (flux-capacity refusal, cooldown) already
     * ran in the client's own engine before the bit could rise. Deliberately NOT {@code isPhased}:
     * that lags {@code isOn} by ≤0.25 s through the whole OUT chargedown
     * (true while eff&gt;0.5) and would double-press down
     * edges. The bit is a <b>state mirror</b> like {@link #SHIELD_ON}; the server drives its
     * authoritative cloak hull by the mismatch-edge law ({@code applyCloakBit},
     * {@code EngineCombatBinding}): ONE
     * {@code giveCommand(TOGGLE_SHIELD_OR_PHASE_CLOAK, aimWorld, 0)} per tick while
     * {@code bit != cloak.isOn()} — the SAME verb the shield lane uses; the server-side phase
     * guard routes cloak hulls here and shield hulls there (verb partition, D-M2-4). A
     * toggle-during-OUT is REFUSED engine-side (cooldown=2),
     * so mismatch retries during the chargedown are latch-clear no-ops — no strobe
     * class exists. Ordered AFTER the bit-8 vent apply by the existing
     * {@code applyPilotIntent} call order: the client's bit falls via the vent-forced OUT the
     * same way the server's will (same-tick semantics aligned, D-M3-1).
     */
    public static final int PHASE_CLOAK = 1 << 11; // 0x800

    /**
     *
     *
     * The carrier FIGHTER-MODE bit (bit 12),
     * a held STATE mirror of the client's own engine-accepted {@code ShipAPI.isPullBackFighters()}
     * (Z = {@code ShipCommand.PULL_BACK_FIGHTERS}, a pure per-command toggle;
     * regroup = true, engage = false; every hull constructs at
     * {@code true}). The server converges by the mismatch-edge law ({@code applyPullBackBit},
     * {@code EngineCombatBinding}): ONE {@code giveCommand(PULL_BACK_FIGHTERS, null, 0)} per tick
     * while {@code bit != hull.isPullBackFighters()} — never a hold replay (a toggle re-issued per
     * held tick would strobe). Non-carrier hulls agree at the
     * shared engine default and issue nothing. No PINT bump: a new valid VALUE set (the bit-10/11
     * precedent); never fast-time reinjected (the shim excludes toggles by construction).
     */
    public static final int PULL_BACK_FIGHTERS = 1 << 12; // 0x1000

    /**
     * The eight MOD-ABILITY slot bits (bits 13..20; slot K = {@code ABILITY_SLOT_0 << K}), carried on
     * {@code heldControls} above {@link #PULL_BACK_FIGHTERS} by the same bit-7..12 precedent: a new SET of valid
     * {@code heldControls} values, not a new field — zero wire bytes, schema/CRC/frame-width unchanged.
     *
     * <p><b>Accepted-state law</b> (the {@link #USE_SHIP_SYSTEM} law, one owner removed): slot K is a LEVEL bit that
     * is set while a mod's own ability K is IN or ACTIVE on the client's native-flown own ship, as that MOD already
     * accepted it — the client never sets it from a keypress. The kit learns it two ways, ORed at send as
     * {@code (mask & 0xFF) << 13}: an adopting mod writes an {@code Integer} mask into its own ship's
     * {@code customData["cc_client.abilityLocal"]} each frame and the kit reads that key after the mod's plugins
     * advanced; for a NON-adopting MagicLib ability the kit's own reader ({@code MagicLocalStateReader.bitsFor})
     * reads the ability's state through MagicLib's public methods.
     *
     * <p><b>Mod-side meaning</b>: the serve NEVER applies these bits to the engine. It copies the slot mask into the
     * seat's OWN hull as {@code customData["cc_client.pilot"] = int[]{seatId, abilityMask, 0, flags}} (role 0 = the
     * serve's own mark; flags bit 0 = FOLLOW, {@code MagicSeamFollow}; {@code PilotCommandApplier} is the one write
     * site) on every applied intent
     * frame (before the engine advances, so a mod's every-frame plugin sees it the same tick), and removes that key
     * on every edge the seat leaves the hull; a mod's own subclass reads it to drive its ability for a human pilot,
     * and its ABSENCE means the hull is AI-flown. After a hull change the mask reads 0 until five consecutive applied
     * frames carried no ability bit (the post-re-key edge quarantine, the {@link #RETREATING} precedent).
     *
     * <p><b>Latch</b>: a one-frame pulse would be lost when several frames arrive inside one deposit-take window, so
     * the serve's ingest OR-latch stretches these bits exactly as it does bit 7 — the worst case is a cancel arriving
     * one take window (at most one tick) late, on the sender's OWN hull only.
     *
     * <p><b>Handshake gate</b>: a serve whose {@link #HELD_CONTROLS_MASK} stops at bit 12 refuses these bits at
     * decode and drops the session as corrupt, so the bits ride handshake version 15: an older kit meets the
     * {@code kit_outdated} rejection at HELLO instead.
     */
    public static final int ABILITY_SLOT_SHIFT = 13;
    public static final int ABILITY_SLOT_COUNT = 8;
    public static final int ABILITY_SLOT_0 = 1 << ABILITY_SLOT_SHIFT; // 0x2000
    public static final int ABILITY_SLOTS_MASK = 0xFF << ABILITY_SLOT_SHIFT; // 0x1FE000

    /**
     * The send-side fold of a mod's {@code customData["cc_client.abilityLocal"]} value into the
     * {@link #ABILITY_SLOTS_MASK} bits: an {@code Integer} contributes its low eight bits shifted to bit 13
     * (anything above bit 7 of the mod's mask is dropped, never smeared into bit 21 and up), and every other value
     * (null = the mod wrote nothing, or a non-{@code Integer} the mod mistyped) folds to 0, so a bad value can
     * never put an unaccepted bit on the wire. WHY here: this class ships in the client kit as well, so the one
     * pure fold is shared by the kit's reader and the server's contract rows; the caller counts the non-Integer
     * case itself. Pure, never throws.
     */
    public static int foldAbilityLocal(Object value) {
        return value instanceof Integer ? (((Integer) value) & 0xFF) << ABILITY_SLOT_SHIFT : 0;
    }

    /**
     * The {@link #targetWireId} sentinels (negatives are free — the {@code shipId >= 0}
     * invariant holds for every real wire id): {@link #TARGET_ABSENT} = legacy/synthetic producer
     * that never samples targets (the {@link #of} default — server skips designation entirely);
     * {@link #TARGET_NONE} = live producer sampled, client engine has NO target (server clears via
     * {@code setShipTarget(null)} iff its hull's target is non-null).
     */
    public static final long TARGET_ABSENT = -1L;
    public static final long TARGET_NONE = -2L;

    /**
     * The 7 MOVEMENT wire bits (0..6) of {@link #heldControls} in the LIVE predict-replay encoding — the
     * bit order the render client produces and {@code EngineCombatBinding.applyPilotIntent}'s
     * {@code replayTranslationBits} consumes (ground truth: scout {@code movementNames}; each bit is
     * replayed verbatim as the PUBLIC {@code ShipCommand} of the same name; the TURN bits are dropped
     * server-side because {@code ownShipFacingDeg} pins rotation). Synthetic producers MUST use these —
     * NOT {@link PilotManeuverIntent} bit values, whose legacy 5-key layout puts {@code STRAFE_LEFT} on
     * bit 2 == wire {@code DECELERATE} (the strafe-regression trap: a held "strafe" decoded as
     * a held DECELERATE, parking the ship at exactly zero displacement).
     */
    public static final int MOVE_ACCELERATE = 1 << 0;
    public static final int MOVE_ACCELERATE_BACKWARDS = 1 << 1;
    public static final int MOVE_DECELERATE = 1 << 2;
    public static final int MOVE_TURN_LEFT = 1 << 3;
    public static final int MOVE_TURN_RIGHT = 1 << 4;
    public static final int MOVE_STRAFE_LEFT = 1 << 5;
    public static final int MOVE_STRAFE_RIGHT = 1 << 6;

    /**
     * {@code selectedWeaponGroup} DESELECTED sentinel: the client engine's own selection
     * left the real group list ({@code getWeaponGroupsCopy().indexOf(getSelectedGroupAPI()) < 0} —
     * the state vanilla enters when the player selects an empty/nonexistent group: the engine's
     * {@code Ship} SELECT_GROUP dispatch nulls {@code selected} on any out-of-bounds
     * ordinal). Carried so the server can mirror the deselect via the
     * engine's own out-of-bounds {@code giveCommand(SELECT_GROUP)} — WITHOUT it the server hull's
     * selection stays pinned on the last in-bounds group, and the engine's playerShip
     * manual-control override ({@code WeaponGroup.advance}: playerShip + null AI + group
     * selected ⇒ autofire AIs {@code forceOff()}) never releases on the playerShip seat's hull —
     * the "seat-1 autofire dead server-side" bug. A new valid VALUE of the existing
     * int, not a new field — schema/CRC/frame-width unchanged (the heldControls bit-7/bit-8
     * precedent), so every off-engine byte-identity proof holds for frames that don't send it.
     * Distinct from the producers' internal {@code -1} (never sampled / legacy ABSENT), which
     * stays OFF the wire (sessions substitute the connect-time group for it, as before).
     */
    public static final int SELECTED_GROUP_DESELECTED = -2;

    /**
     * The {@link #activeTubeOrdinal} ABSENT sentinel ({@code -1}, the {@link #of}
     * default): the producer does not sample an alternating active tube this frame — either a
     * legacy/synthetic producer that never samples, or a live producer whose selected group is not
     * ALTERNATING (linked groups have no tube pointer; sampling {@code getActiveWeapon()} on them
     * would lazy-init engine state). The server's tube mirror skips entirely on this value
     * (fail-closed, the {@code autofireGroupsMask} {@code -1} precedent).
     */
    public static final int TUBE_ORDINAL_ABSENT = -1;

    /**
     * The pilot's RAW INPUT LEVELS (v7): {@link #keyCodes}, {@link #mouseButtons},
     * {@link #controlKeys}. WHY: a mod script that runs as part of the pilot's own ship's update (a hullmod, a ship
     * system, a weapon script) polls LWJGL {@code Keyboard.isKeyDown} / {@code Mouse.isButtonDown} for its hotkeys;
     * on the headless serve those statics answer nothing, so the pilot-ship call-site redirect rewrites that ship's
     * call sites to {@code PilotShipCall} twins which answer from THIS frame's levels (pushed into the pilot-ship
     * window on every applied frame). The serve NEVER applies these to the engine — they are answered only to the seat's own hull's
     * redirected scripts. Levels, never latched (the ingest OR-latch leaves them verbatim), never text.
     *
     * <p><b>{@code keyCodes}</b> = up to {@link #KEY_SLOTS} held LWJGL key codes packed little-end: byte {@code i}
     * (bits {@code 8i..8i+7}) = the {@code i}-th held code, {@code 1..255}, STRICTLY ASCENDING while non-zero, then
     * zero pad — so one held set has exactly one encoding ({@link #KEYS_EMPTY} = nothing held) and a frame costs no
     * allocation. A ninth key is dropped by the producer (counted there). <b>{@code mouseButtons}</b> = u8, bit
     * {@code b} = mouse button {@code b} held ({@link #MOUSE_BUTTONS_MASK}). <b>{@code controlKeys}</b> = u16, bit
     * {@code i} = the pilot's OWN binding for {@link #CONTROL_KEY_NAMES}{@code [i]} held ({@link #CONTROL_KEYS_MASK}) —
     * carried as a control NAME bit because the two sides' keymaps differ; each side resolves its own binding and an
     * unresolvable name is that bit unmapped, never a throw. The canonical constructor refuses a non-canonical
     * {@code keyCodes} or an out-of-range mask; the decoder maps that to the door's {@code pint_corrupt} drop.
     */
    public static final long KEYS_EMPTY = 0L;
    public static final int KEY_SLOTS = 8;
    public static final int MOUSE_BUTTONS_MASK = 0xFF;
    public static final int CONTROL_KEYS_MASK = 0xFFFF;
    /**
     * Index = {@link #controlKeys} bit. The names are the engine's own control-enum constants (measured from the
     * install jar; the record =); resolved per side via
     * {@code SettingsAPI.getControlStringForEnumName} then {@code Keyboard.getKeyIndex} (0 = unmapped, e.g. the
     * mouse-bound default of SHIP_FIRE).
     */
    public static final String[] CONTROL_KEY_NAMES = {
            "SHIP_ACCELERATE", "SHIP_ACCELERATE_BACKWARDS", "SHIP_DECELERATE", "SHIP_TURN_LEFT",
            "SHIP_TURN_RIGHT", "SHIP_STRAFE_KEY", "SHIP_STRAFE_LEFT_NOTURN", "SHIP_STRAFE_RIGHT_NOTURN",
            "SHIP_FIRE", "SHIP_SHIELDS", "SHIP_USE_SYSTEM", "SHIP_VENT_FLUX",
            "SHIP_PULL_BACK_FIGHTERS", "SHIP_HOLD_FIRE", "SHIP_TARGET_SHIP", "C2_TOGGLE_AUTOPILOT" };

    /**
     * The {@link #keyCodes} canonical-form law: byte 0 may be 0 (empty); after a zero byte every higher byte is zero;
     * each non-zero byte is strictly greater than the byte below it. Pure, never throws.
     */
    public static boolean isCanonicalKeyCodes(long packed) {
        int prev = 0;
        boolean pad = false;
        for (int slot = 0; slot < KEY_SLOTS; slot++) {
            int code = keyCode(packed, slot);
            if (code == 0) {
                pad = true;
            } else if (pad || code <= prev) {
                return false;
            } else {
                prev = code;
            }
        }
        return true;
    }

    /** The code in {@code slot} (0..7) of a packed key set, {@code 0..255}; a slot outside 0..7 answers 0, never throws. */
    public static int keyCode(long packed, int slot) {
        return slot < 0 || slot >= KEY_SLOTS ? 0 : (int) ((packed >>> (8 * slot)) & 0xFFL);
    }

    /**
     * {@code packed} with {@code slot}'s byte replaced by {@code code & 0xFF}; a slot outside 0..7 answers {@code packed}
     * unchanged. Does NOT re-sort: the producer fills slots in ascending code order so the result stays canonical.
     */
    public static long withKeyCode(long packed, int slot, int code) {
        if (slot < 0 || slot >= KEY_SLOTS) {
            return packed;
        }
        int shift = 8 * slot;
        return (packed & ~(0xFFL << shift)) | (((long) (code & 0xFF)) << shift);
    }

    public PilotIntentFrame {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (frameSeq < 0) {
            throw new IllegalArgumentException("frameSeq must be >= 0; got " + frameSeq);
        }
        if (!Float.isFinite(cursorFacingDeg)) {
            throw new IllegalArgumentException("cursorFacingDeg must be finite; got " + cursorFacingDeg);
        }
        if (!Float.isFinite(ownShipFacingDeg)) {
            throw new IllegalArgumentException("ownShipFacingDeg must be finite; got " + ownShipFacingDeg);
        }
        if (!Float.isFinite(aimWorldX)) {
            throw new IllegalArgumentException("aimWorldX must be finite; got " + aimWorldX);
        }
        if (!Float.isFinite(aimWorldY)) {
            throw new IllegalArgumentException("aimWorldY must be finite; got " + aimWorldY);
        }
        if ((heldControls & ~HELD_CONTROLS_MASK) != 0) {
            throw new IllegalArgumentException(
                    "heldControls has bits outside the valid range (0..20): " + heldControls);
        }
        if (selectedWeaponGroup < 0 && selectedWeaponGroup != SELECTED_GROUP_DESELECTED) {
            throw new IllegalArgumentException(
                    "selectedWeaponGroup must be >= 0 or SELECTED_GROUP_DESELECTED (-2); got "
                            + selectedWeaponGroup);
        }
        if (autofireGroupsMask < -1) {
            throw new IllegalArgumentException(
                    "autofireGroupsMask must be >= -1 (-1 = absent); got " + autofireGroupsMask);
        }
        if (targetWireId < 0L && targetWireId != TARGET_ABSENT && targetWireId != TARGET_NONE) {
            throw new IllegalArgumentException(
                    "targetWireId must be >= 0, TARGET_ABSENT (-1) or TARGET_NONE (-2); got "
                            + targetWireId);
        }
        if (fireEdgeSeq < 0 || fireEdgeSeq > 255) {
            throw new IllegalArgumentException(
                    "fireEdgeSeq must be in 0..255 (1 wire byte, wrapping); got " + fireEdgeSeq);
        }
        if (activeTubeOrdinal < TUBE_ORDINAL_ABSENT || activeTubeOrdinal > 127) {
            throw new IllegalArgumentException(
                    "activeTubeOrdinal must be in -1..127 (1 signed wire byte; -1 = absent); got "
                            + activeTubeOrdinal);
        }
        if (!isCanonicalKeyCodes(keyCodes)) {
            throw new IllegalArgumentException(
                    "keyCodes must be strictly ascending non-zero bytes then zero pad; got 0x"
                            + Long.toHexString(keyCodes));
        }
        if (mouseButtons < 0 || mouseButtons > MOUSE_BUTTONS_MASK) {
            throw new IllegalArgumentException("mouseButtons must be in 0..255 (u8); got " + mouseButtons);
        }
        if (controlKeys < 0 || controlKeys > CONTROL_KEYS_MASK) {
            throw new IllegalArgumentException("controlKeys must be in 0..65535 (u16); got " + controlKeys);
        }
    }

    /**
     * The pre-v7 13-component shape: every producer that samples no raw input levels (the
     * server's own sessions, ladders, ghosts, tests, v5/v6 recordings) constructs here and rides
     * {@link #KEYS_EMPTY}/0/0 — behavior-identical to before the v7 bump. Live kits sampling a real keyboard pass the
     * levels via the canonical constructor.
     */
    public PilotIntentFrame(
            long shipId,
            int frameSeq,
            float cursorFacingDeg,
            int heldControls,
            boolean fireHeld,
            int selectedWeaponGroup,
            float ownShipFacingDeg,
            float aimWorldX,
            float aimWorldY,
            int autofireGroupsMask,
            long targetWireId,
            int fireEdgeSeq,
            int activeTubeOrdinal) {
        this(shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld, selectedWeaponGroup, ownShipFacingDeg,
                aimWorldX, aimWorldY, autofireGroupsMask, targetWireId, fireEdgeSeq, activeTubeOrdinal,
                KEYS_EMPTY, 0, 0);
    }

    /** The code held in {@code slot} (0..7) this frame, {@code 0..255} (0 = empty slot); never throws. */
    public int keyCode(int slot) {
        return keyCode(keyCodes, slot);
    }

    /** How many key codes this frame holds (the non-zero prefix of the canonical pack, 0..8). */
    public int keyCount() {
        int n = 0;
        while (n < KEY_SLOTS && keyCode(keyCodes, n) != 0) {
            n++;
        }
        return n;
    }

    /** Whether LWJGL key {@code code} (1..255) is held this frame — a scan of the 8 bytes; any other code answers false. */
    public boolean hasKeyCode(int code) {
        if (code < 1 || code > 255) {
            return false;
        }
        for (int slot = 0; slot < KEY_SLOTS; slot++) {
            int c = keyCode(keyCodes, slot);
            if (c == code) {
                return true;
            }
            if (c == 0 || c > code) {
                return false;
            }
        }
        return false;
    }

    /**
     * Legacy/synthetic-producer factory — the pre-v4 9-arg shape with {@code autofireGroupsMask}
     * defaulted to {@code -1} (ABSENT: the server skips the autofire diff, keeping every producer
     * that does not sample live autofire state — ghost replay, ladders, tests, migrated v3
     * recordings — behavior-identical to before the v4 bump), {@code targetWireId} defaulted to
     * {@link #TARGET_ABSENT} and {@code fireEdgeSeq} to {@code 0} (v5: designation skipped,
     * edge latch never arms — legacy-inert by construction), and {@code activeTubeOrdinal} to
     * {@link #TUBE_ORDINAL_ABSENT} (v6: tube mirror skipped). Live sessions sampling
     * a real ship pass the real values via the canonical constructor.
     */
    public static PilotIntentFrame of(
            long shipId,
            int frameSeq,
            float cursorFacingDeg,
            int heldControls,
            boolean fireHeld,
            int selectedWeaponGroup,
            float ownShipFacingDeg,
            float aimWorldX,
            float aimWorldY) {
        return new PilotIntentFrame(shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld,
                selectedWeaponGroup, ownShipFacingDeg, aimWorldX, aimWorldY, -1, TARGET_ABSENT, 0,
                TUBE_ORDINAL_ABSENT);
    }

    /**
     * this frame with {@code heldControls} replaced (all other components
     * verbatim; the canonical constructor re-validates the mask). Exists for the ingest-side
     * edge-bit OR-latch: the endpoint's one-slot PINT deposit is latest-wins, so a sub-tick
     * edge-bit pulse (a 1-render-frame system press at high fps) dies unless the take folds
     * the window's ORed edge bits back onto the surviving frame. The v7 raw levels ride through verbatim
     * (the latch stretches edge BITS, never the key levels).
     */
    public PilotIntentFrame withHeldControls(int newHeldControls) {
        return new PilotIntentFrame(shipId, frameSeq, cursorFacingDeg, newHeldControls, fireHeld,
                selectedWeaponGroup, ownShipFacingDeg, aimWorldX, aimWorldY, autofireGroupsMask,
                targetWireId, fireEdgeSeq, activeTubeOrdinal, keyCodes, mouseButtons, controlKeys);
    }

    /** The faithful movement intent this frame carries (keys + aim) for the BasicEngineAI lane. */
    public PilotManeuverIntent toManeuverIntent() {
        return new PilotManeuverIntent(shipId, cursorFacingDeg, heldControls);
    }

    /** The faithful fire intent this frame carries (fire button + aim + selected group). */
    public PilotFireIntent toFireIntent() {
        return new PilotFireIntent(shipId, cursorFacingDeg, selectedWeaponGroup, fireHeld);
    }
}
