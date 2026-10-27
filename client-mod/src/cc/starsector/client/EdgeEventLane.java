package cc.starsector.client;

import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

import cc.starsector.client.PredictReconcileCombatPlugin.RemoteEntity;
import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * P8 leg 2 — the GPEE EDGE-EVENT lane carved VERBATIM out of
 * {@link PredictReconcileCombatPlugin} (R6: zero behaviour change): the net-thread intake
 * ({@link #enqueueEdgeEvent}, the {@code receiveSnapshot} sink), the despawn fold, the
 * per-snapshot drain that holds ahead-of-snapshot events and feeds aim-rebase, the per-event
 * measure/route/present demux ({@link #presentEdgeEvent}:
 * WEAPON_DISCHARGE own = match/adopt, foe = mint; VENT/SYSTEM windows fold; SYSTEM_JUMP relays
 * to {@link TeleportJumpLane}; MISSILE_*, ARC_HIT, PALADIN_DET, AUTOFIRE_TARGET route to their
 * lanes), the own-window MID-FLIGHT meter (streaks + the capped per-streak log), the own-discharge
 * declaration push ({@link #declareFireDischarge}, the PDCL queue the net thread drains) and the
 * replay-meter flush ({@link #logEventReplayMeter}, the {@code predict_event_*} watch line). The
 * bodies are the plugin's, unchanged but for the {@code plugin.} /
 * {@code PredictReconcileCombatPlugin.} qualification of the state they read: every field stays
 * in the plugin (the teardown flush, the diagnostic dump, the match reset and the net thread read
 * them; the event queue and the pending-declaration deque are cross-thread monitors), package-private
 * for this lane. Fail-soft per event (a presentation throw is counted, never propagated), the
 * declaration push fail-closed by design (a full deque drops the newest, counted). B-legal: engine
 * API + the plugin. Own file per the NEW-LANE LAW ({@code deferred-refactors.md}).
 */
final class EdgeEventLane {
    private final PredictReconcileCombatPlugin plugin;

    EdgeEventLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }


    /** net-thread side of the eventReplay intake — queue one decoded GPEE event. */
    void enqueueEdgeEvent(ClientGameplayEdgeEvent event) {
        synchronized (plugin.edgeEventQueue) {
            plugin.edgeEventQueue.addLast(event);
        }
    }

    /**
     * net-thread intake of the server-sent {@code EDSP} despawn lane (the
     * ignore→consume flip). The kill EDGE stays roster absence ({@link OwnSeatLifecycleLane#killOwn}/{@link OwnSeatLifecycleLane#killFoe});
     * this lane carries the REASON (DESTROYED vs DEPARTED) the client must never infer: kill and
     * roster-drop are same-frame, so the wire hull is always stale (step-0 GOTCHA; server
     * authority). The server writes the EDSP ahead of its tick's AWSS, so the volatile reason is
     * published BEFORE the render thread can see the drop-frame snapshot. Despawns are rare
     * (≤ roster size per round) — every one is logged.
     */
    void onDespawnEvent(cc.starsector.client.net.ClientEntityDespawn despawn) {
        plugin.despawnConsumed++;
        // Every consumed despawn feeds the fracture mint's death-tick source
        // (net-thread put; render-thread remove-take in mintFractureFamily, same lock).
        plugin.fractures.noteDespawnTick(despawn.entityId(), despawn.simTick());
        boolean own = plugin.session != null && despawn.entityId() == plugin.session.shipId();
        if (own) {
            plugin.ownDespawnReason = despawn.reason();
            plugin.ownDespawnTick = despawn.simTick();
        } else {
            // Record the REMOTE reason (net-thread put; render-thread
            // remove-take in killRemote). Before this the reason was decoded then DISCARDED
            // for non-own entities — the fighter despawn route needs it.
            synchronized (plugin.despawnReasonByEntity) {
                plugin.despawnReasonByEntity.put(Long.valueOf(despawn.entityId()), despawn.reason());
            }
            plugin.matchOverLane.noteDespawn(despawn); // results-roster ledger feed
        }
        PilotWatchLog.info("predict despawn consumed: entity=" + despawn.entityId()
                + " tick=" + despawn.simTick() + " reason=" + despawn.reason()
                + (own ? " (OWN)" : ""));
    }

    /**
     * Drain the gameplay-edge-event queue on the render thread and MEASURE each event
     * (the STEP-16 "measured detector first" half). Per event: the age against the
     * applied snapshot anchor ({@code snapshotTick − eventTick}), own/foe routing by the session's
     * piloted ship id, and for own-ship window kinds whether the authoritative edge agrees with the
     * LOCAL engine's window state at application time — a disagreement is exactly the (U)-class
     * window skew the spec names. An event that outran the applied snapshot (the
     * ahead case; measured ±1 tick at hairpin topology) is HELD in a pending-ahead
     * deque and re-offered at each drain until {@code T_s ≥ T} — never presented against an anchor
     * it outran. The behind case (measured depth ≤ a few ticks) keeps drain-on-arrival + the
     * current-pose muzzle anchor, pose-coherent within a frame at that depth. Pre-registered
     * boundary: multi-tick BEHIND depth measured at real WAN ⇒ then add the (T_s−T)·dt downrange
     * catch-up advance on presentation, not before.
     */
    void drainEdgeEvents(long snapshotTick) {
        plugin.afTargets.onSnapshotTick(snapshotTick); // Expiry clock, BEFORE any kind-7 intake
        plugin.fireLanes.expireDischargePairs(snapshotTick); // Flush discharge stamps the tolerance window passed by
        // B2(a) hold: re-offer held ahead events first (rotation preserves arrival order; neither
        // queue is tick-sorted across entities/kinds — hold-and-reoffer, never break the drain on it).
        for (int i = plugin.pendingAheadEvents.size(); i > 0; i--) {
            ClientGameplayEdgeEvent held = plugin.pendingAheadEvents.pollFirst();
            if (held.simTick() > snapshotTick) {
                plugin.pendingAheadEvents.addLast(held);
            } else {
                presentEdgeEvent(held, snapshotTick);
            }
        }
        while (true) {
            ClientGameplayEdgeEvent event;
            synchronized (plugin.edgeEventQueue) {
                event = plugin.edgeEventQueue.pollFirst();
            }
            if (event == null) {
                break;
            }
            plugin.eventReplayReceived++;
            long aheadTicks = event.simTick() - snapshotTick;
            if (aheadTicks > 0) {
                plugin.eventReplayAheadOfSnapshot++;
                if (aheadTicks > plugin.eventReplayAheadMaxTicks) {
                    plugin.eventReplayAheadMaxTicks = aheadTicks;
                }
                plugin.pendingAheadEvents.addLast(event);
                continue;
            }
            presentEdgeEvent(event, snapshotTick);
        }
        // Treat this frame's expiries at the drain site, post-scan same frame
        // — the mid-scan expire calls only QUEUED them.
        plugin.fireLanes.processDischargeTreatments(snapshotTick);
    }

    /** The per-event measure/route/present half of {@link #drainEdgeEvents} (an
     *  extraction, so a held ahead event presents through the identical path once its tick is due). */
    private void presentEdgeEvent(ClientGameplayEdgeEvent event, long snapshotTick) {
        boolean own = plugin.session != null && event.entityId() == plugin.session.shipId();
        long ageTicks = snapshotTick - event.simTick();
        if (ageTicks > plugin.eventReplayAgeMaxTicks) {
            plugin.eventReplayAgeMaxTicks = ageTicks; // B2(a) behind-depth meter (≥0 by construction now)
        }
        String localWindow = "n/a";
        if (event.kind() == ClientGameplayEdgeEvent.Kind.MISSILE_SPLIT) {
            // Route FIRST like SYSTEM_JUMP (the window folds below would
            // mis-fold kind 5; the trap). entityId is a MISSILE network id (≥1e9), never
            // a ship seat — resolution is lane-internal to the twin registry, not remoteEntities.
            plugin.missileLane.onSplit(event);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.MISSILE_END) {
            // Route FIRST like kind 5 (same trap — the window
            // folds below would mis-fold kind 6). entityId is a MISSILE network id; resolution
            // + the WireEnd store + every meter live in the lane.
            plugin.missileLane.onMissileEnd(event);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.AUTOFIRE_TARGET) {
            // Route FIRST like kinds 5/6 (the window folds below would
            // mis-fold kind 7; the trap). Leg 2: OWN rows feed the coordinator the
            // designation-bracket wrappers query (non-own rows = other seats' lanes, counted
            // only — each client applies exactly its own seat's picks).
            plugin.eventReplayAutofireTarget++;
            if (own) {
                plugin.afTargets.onAutofireTarget(event);
            }
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.PALADIN_DET) {
            // Route FIRST like kinds 5/6/7 (the window folds below would
            // mis-fold kind 8; the trap). entityId is the SOURCE ship's wireId — the
            // presentation keys off the det pose floats alone (own and foe dets both present
            // through this one wire lane; the server emits every det once).
            plugin.eventReplayPaladinDet++;
            plugin.paladinDetLane.present(event);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.ARC_HIT) {
            // Route FIRST like kinds 5/6/7/8 (the window folds below would
            // mis-fold kind 9; the trap). entityId is the SOURCE ship's wireId,
            // targetWireId the VICTIM's; the lane skips own-source events (vanilla onFire
            // ran locally) and OTHER-flavor arcs (local wrappers present those).
            plugin.eventReplayArcHit++;
            plugin.arcHitLane.present(event, own);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.CRIT_MALFUNCTION) {
            // Route FIRST like kinds 5-9 (the
            // window-fold trap); entityId = the VICTIM (so `own` = the own hull was hit).
            plugin.eventReplayCritMalfunction++;
            plugin.critMalfunctionLane.present(event, own);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.SYSTEM_JUMP) {
            // Route FIRST — the own/foe window folds below would mis-fold kind 4 into
            // mfServerOpen/sysServerOpen as a bogus window edge (the window-fold trap).
            presentSystemJump(event, own);
        } else if (event.kind() == ClientGameplayEdgeEvent.Kind.WEAPON_DISCHARGE) {
            if (own) {
                plugin.eventReplayOwnDischarge++;
                plugin.fireLanes.matchAuthDischarge(event);
            } else {
                plugin.eventReplayFoeDischarge++;
                // The foe's fire must be SEEN, not just counted. This plugin resolves
                // the local body for the event's entity (1v1 foe / bots-shape registry row); the
                // null/alive SKIP semantics live in the presenter.
                ShipAPI shooter = plugin.foeShip;
                if (plugin.registryShape) {
                    RemoteEntity re = plugin.remoteEntities.get(event.entityId());
                    shooter = re != null ? re.ship : null;
                }
                // A SAME-SIDE replay discharge feeds the snap-and-hold aim
                // lane (record BEFORE presentation — the gap meter reads this frame's native
                // mount angle; the presenter never writes mount angles).
                if (shooter != null && shooter.getOwner() == plugin.sideAuthority()) {
                    plugin.sameSideAim.record(event, shooter);
                }
                // Source fix: mark the presented spawn so the scan's autonomous-
                // discharge meter can exclude it (replayed fire is the ONLY legitimate foe fire).
                // ageTicks rides along for the downrange catch-up advance
                // (the pre-registered boundary, armed by a measured age_max=12 at the real hairpin).
                Object spawned = plugin.presenter.presentFoeDischarge(event, shooter, ageTicks);
                if (spawned != null) {
                    plugin.presentedFoeProj.add(spawned);
            plugin.localFire.onPresentedAdd(); // the durable arm (consumed by the advance() scan)
                    // Register a presented devastator at its spawn instant, keys
                    // from the wire (entityId/mount/simTick/bearing) + the catch-up age.
                    plugin.fuseMeter.tagPresented(spawned, event, ageTicks, plugin.advFrames, snapshotTick);
                    plugin.dmgWitness.tagPresented(spawned, event, ageTicks); // the damage-witness tag
                    // mtrack registers BEFORE enroll — the class read
                    // needs the factory AI the enroll below may replace.
                    plugin.mtrack.register(spawned, event.entityId(), event.mountIndex(),
                            event.simTick(), "foe", null);
                    // guided/flare mints become pose puppets — classified
                    // from the factory AI, no engine advance between mint and the no-op install.
                    plugin.missileLane.enroll(spawned, event.entityId(), event.mountIndex(), event.simTick());
                }
            }
        } else if (own) {
            plugin.eventReplayOwnWindowEdges++;
            Boolean local = localWindowState(event.kind());
            if (local != null) {
                localWindow = local.toString();
                if (local == event.opensWindow()) {
                    plugin.eventReplayOwnWindowAgree++;
                } else {
                    plugin.eventReplayOwnWindowDisagree++;
                }
            }
            // The latest own edge IS the derived server window state between edges
            // (durable exactly-once lane). B2(a): an ahead edge is HELD until due, so the fold
            // lands exactly at T_s ≥ T — never early anymore.
            plugin.mfServerOpen[event.kind() == ClientGameplayEdgeEvent.Kind.SYSTEM_WINDOW
                    ? PredictReconcileCombatPlugin.MF_SYS : PredictReconcileCombatPlugin.MF_VENT] = Boolean.valueOf(event.opensWindow());
        } else {
            plugin.eventReplayFoeWindowEdges++;
            // Fold the foe's SYSTEM_WINDOW ON/OFF edge into the derived authoritative
            // window-open state (the foe analog of mfServerOpen) — the replica meters/applies it.
            if (event.kind() == ClientGameplayEdgeEvent.Kind.SYSTEM_WINDOW) {
                Boolean open = Boolean.valueOf(event.opensWindow());
                ShipAPI activator = null;
                // An applied ON stamps the window KEY
                // (its simTick — unique per entity per window); an OFF leaves the key alone.
                if (plugin.registryShape) {
                    RemoteEntity re = plugin.remoteEntities.get(event.entityId());
                    if (re != null) {
                        re.sysServerOpen = open;
                        if (open.booleanValue()) {
                            re.sysOpenTick = event.simTick();
                        }
                        activator = re.ship;
                    }
                } else {
                    plugin.foeSysServerOpen = open;
                    if (open.booleanValue()) {
                        plugin.foeSysOpenTick = event.simTick();
                    }
                    activator = plugin.foeShip;
                }
                if (open.booleanValue()) {
                    // An overseer's lash open edge ⇒ phase glow on the
                    // nearest same-owner attack swarm (declines for every other system).
                    plugin.energyLashFx.onSystemOpen(activator, plugin.engine);
                    // Schema v12: a mote_control ON edge carries its target
                    // (finite x/y on the float slots) ⇒ the attractor arc + lock jitter; the lock
                    // resolves through the replica registry, an unresolvable/absent one arcs to
                    // the point (the fidelity fallback). A plain ON edge (NaN) declines inside.
                    float moteX = event.aimFacingDeg();
                    float moteY = event.flightBearingDeg();
                    if (Float.isFinite(moteX) && Float.isFinite(moteY)) {
                        ShipAPI lockShip = null;
                        long lockWire = event.targetWireId();
                        if (lockWire > 0L) {
                            if (plugin.registryShape) {
                                RemoteEntity lockRe = plugin.remoteEntities.get(Long.valueOf(lockWire));
                                lockShip = lockRe != null ? lockRe.ship : null;
                            } else if (plugin.session != null && lockWire == plugin.session.shipId()) {
                                lockShip = plugin.engine.getPlayerShip();
                            }
                        }
                        plugin.moteAttractorFx.present(activator, lockShip, moteX, moteY, plugin.engine);
                    }
                }
            }
        }
        if (plugin.eventReplayLogLines < AuthoritativePresenter.shotLogMax()) { // live cap: debug watch lifts it
            plugin.eventReplayLogLines++;
            PilotWatchLog.info("predict B3 eventReplay #" + plugin.eventReplayLogLines + ": " + event
                    + " own=" + own + " snapTick=" + snapshotTick + " ageTicks=" + ageTicks
                    + " localWindow=" + localWindow);
        }
    }

    /**
     * Present a {@code SYSTEM_JUMP} marker (GPEE wire v3). K16:
     * the fold's policy — fences, windows, heal debts, meters, the capped log — lives in
     * {@link TeleportJumpLane#presentSystemJump}; this touchpoint resolves the local body + the
     * per-entity state (plugin context: {@link PredictReconcileCombatPlugin#remoteEntities} / {@link PredictReconcileCombatPlugin#foeShip}), which the
     * lane counts as unbound when null, never guesses.
     */
    private void presentSystemJump(ClientGameplayEdgeEvent event, boolean own) {
        ShipAPI body = null;
        TeleportJumpLane.JumpState state = null;
        if (!own) {
            if (plugin.registryShape) {
                RemoteEntity re = plugin.remoteEntities.get(event.entityId());
                body = re != null ? re.ship : null;
                state = re != null ? re.jump : null;
            } else {
                body = plugin.foeShip;
            }
        }
        plugin.teleportLane.presentSystemJump(event, own, body, state);
    }

    /**
     * The own ship's LOCAL window state for an event kind — the engine's own accepted booleans —
     * or {@code null} when unreadable. The bit-8 read ({@code isVenting()}) matches
     * {@link PredictReconcileCombatPlugin#captureOwnCommands} exactly; the SYSTEM read deliberately stays {@code isActive()}
     * (= IN|ACTIVE|OUT, the presentation-window state the authoritative edges are compared against)
     * while a later fix moved the bit-7 capture sample to {@code isOn()} (= IN|ACTIVE, the cancel-instant
     * falling edge).
     */
    private Boolean localWindowState(ClientGameplayEdgeEvent.Kind kind) {
        ShipAPI own = plugin.ownShip != null ? plugin.ownShip : (plugin.engine != null ? plugin.engine.getPlayerShip() : null);
        if (own == null) {
            return null;
        }
        if (kind == ClientGameplayEdgeEvent.Kind.VENT_WINDOW) {
            FluxTrackerAPI flux = own.getFluxTracker();
            return flux != null ? flux.isVenting() : null;
        }
        ShipSystemAPI sys = own.getSystem();
        return sys != null ? sys.isActive() : null;
    }

    /**
     * Sample MID-FLIGHT own-window state once per fresh snapshot tick (the step-1
     * MEASURE the Backlog row names; {@link #drainEdgeEvents} sees only edge instants). Per kind:
     * derived server state (latest own edge; null = no edge this round ⇒ counted unknown, never
     * guessed) vs the local engine's own accepted read. A disagreement opens/extends a skew streak
     * on the sim-tick clock; agree (or a direction flip) closes it into the counters + a capped
     * per-streak log line. Local unreadable (bind gap / death frame) skips the sample honestly.
     */
    void sampleMidflightWindows(long snapTick) {
        plugin.mfLastSampleTick = snapTick;
        for (int k = 0; k < 2; k++) {
            Boolean server = plugin.mfServerOpen[k];
            Boolean local = localWindowState(k == PredictReconcileCombatPlugin.MF_SYS
                    ? ClientGameplayEdgeEvent.Kind.SYSTEM_WINDOW
                    : ClientGameplayEdgeEvent.Kind.VENT_WINDOW);
            if (server == null) {
                plugin.mfUnknownFrames[k]++;
                continue;
            }
            if (local == null) {
                continue;
            }
            plugin.mfFrames[k]++;
            if (server.booleanValue() != local.booleanValue()) {
                boolean srvOn = server.booleanValue();
                if (srvOn) {
                    plugin.mfDisagreeSrvOn[k]++;
                } else {
                    plugin.mfDisagreeSrvOff[k]++;
                }
                if (plugin.mfStreakStartTick[k] == Long.MIN_VALUE) {
                    plugin.mfStreakStartTick[k] = snapTick;
                    plugin.mfStreakSrvOn[k] = srvOn;
                } else if (plugin.mfStreakSrvOn[k] != srvOn) {
                    closeMidflightStreak(k, snapTick, "direction-flip");
                    plugin.mfStreakStartTick[k] = snapTick;
                    plugin.mfStreakSrvOn[k] = srvOn;
                }
            } else if (plugin.mfStreakStartTick[k] != Long.MIN_VALUE) {
                closeMidflightStreak(k, snapTick, "agree");
            }
        }
    }

    /** Close kind {@code k}'s open skew streak at {@code endTick} into the counters (capped log). */
    void closeMidflightStreak(int k, long endTick, String how) {
        long ticks = endTick - plugin.mfStreakStartTick[k];
        plugin.mfStreaks[k]++;
        if (ticks > PredictReconcileCombatPlugin.mfLagTicks()) {
            plugin.mfStreaksOverLag[k]++;
        }
        if (ticks > plugin.mfMaxStreakTicks[k]) {
            plugin.mfMaxStreakTicks[k] = ticks;
        }
        if (plugin.mfStreakLogLines < PredictReconcileCombatPlugin.MF_STREAK_LOG_MAX) {
            plugin.mfStreakLogLines++;
            PilotWatchLog.info("predict MF own-window MIDFLIGHT skew: kind=" + PredictReconcileCombatPlugin.MF_KIND_NAME[k]
                    + " dir=" + (plugin.mfStreakSrvOn[k] ? "server-ON-local-OFF" : "server-OFF-local-ON")
                    + " startTick=" + plugin.mfStreakStartTick[k] + " endTick=" + endTick
                    + " ticks=" + ticks + " (~" + PredictReconcileCombatPlugin.fmt1(ticks / (double) ServerTickRate.ticksPerSecond())
                    + " s) end=" + how
                    + (plugin.mfStreakLogLines == PredictReconcileCombatPlugin.MF_STREAK_LOG_MAX
                            ? " [log cap reached; counters continue]" : ""));
        }
        plugin.mfStreakStartTick[k] = Long.MIN_VALUE;
    }

    /** Kind {@code k}'s still-open skew streak length at the last sampled tick (0 = none) — the
     *  212641 R2 "window still OPEN per the server at the kill edge" shape, surfaced at flush. */
    private long openMidflightStreakTicks(int k) {
        if (plugin.mfStreakStartTick[k] == Long.MIN_VALUE || plugin.mfLastSampleTick == Long.MIN_VALUE) {
            return 0L;
        }
        return plugin.mfLastSampleTick - plugin.mfStreakStartTick[k];
    }

    /**
     * Client send half, render-thread capture: queue one declared discharge at the
     * projectile-scan instant — {@code declSeq++} + the last-known authoritative sim tick + the aim
     * facing AT the discharge. Values are sanitized here (tick clamped to 0 pre-first-snapshot,
     * non-finite aim to 0) because the server's PDCL decode is fail-closed — one invalid field would
     * kill the whole session's socket. Overflow drops the OLDEST entry: if the net loop is not
     * draining, everything queued is already older than the server lane's ε-timeout.
     */
    void declareFireDischarge(DamagingProjectileAPI p) {
        // An AUTOFIRING group's discharge is engine behavior the
        // autofire-mask mirror reproduces server-side, not input — UNLESS the group is the SELECTED
        // one: selection takes manual control (engine truth: isAutofiring() reads only the autofire
        // tracker, selection-independent), so a selected group's
        // discharges ARE input and must be declared or the server's mirror weapons stay cold
        // (flux/ammo diverge; its own autofire later re-fires them on ITS clock = unpaired
        // server_only). Weaponless damagers never reach here anymore — the scan excludes the
        // engine-own explosion class before discharge accounting; a REAL weapon
        // that still fails identity resolve falls through to the legacy declare (mountIndex -1).
        int mount = -1;
        WeaponAPI mountWeapon = null; // The resolved weapon, for the degenerate-speed compose
        boolean systemSlot = false; // A ship-SYSTEM weapon's shot rides the system press, not the fire level
        try {
            WeaponAPI w = p != null ? p.getWeapon() : null;
            if (w != null && plugin.ownShip != null) {
                systemSlot = w.getSlot() != null && w.getSlot().isSystemSlot();
                WeaponGroupAPI grp = plugin.ownShip.getWeaponGroupFor(w);
                if (grp != null && grp.isAutofiring() && grp != plugin.ownShip.getSelectedGroupAPI()) {
                    plugin.fireDeclAutofireSkipped++;
                    plugin.noteOwnFireVerdict(UnwiredOwnFireLaw.Verdict.WIRED_AUTOFIRE, w); //
                    return;
                }
                // The discharging weapon's getAllWeapons() ordinal — the same
                // cross-side order contract the AWSS v4 weapon lane live-proved.
                mount = plugin.ownShip.getAllWeapons().indexOf(w);
                mountWeapon = w;
            }
        } catch (Throwable ignore) {
            // identity resolve is best-effort; mount stays -1 (init) = the legacy serve path
            FallbackAnnounce.announceOnce("f1-mount-legacy",
                    "weapon/group resolve threw; mount=-1 legacy pair-any");
        }
        // The fall-through = a SELECTED/manual-group (or unresolved-weapon)
        // own discharge. It is declared below, but the server ignores PDCL by default — so classify
        // it against the held-fire level sample + whether a ship-AI drives the own hull: held within
        // the grace = the held lane carried it; otherwise it is UNWIRED (autopilot's AI, or a source
        // mouse-0 never sees) and the plugin logs/announces/counts it. Diagnostics only; never blocks
        // the declare.
        // The recency is the DERIVED level at this instant (mouse-0 OR the AI's
        // selected group non-idle — AutopilotFireLane.heldRecency), so an engagement's first AI shot,
        // born in the engine frame AFTER its capture, files as the held lane's (the next sample + the
        // server's edge latch carry it) instead of one false UNWIRED_AUTOPILOT per engagement.
        try {
            plugin.noteOwnFireVerdict(UnwiredOwnFireLaw.classify(systemSlot, false, plugin.autopilotFire.heldRecency(),
                    plugin.ownAiDriven()), mountWeapon);
        } catch (Throwable ignore) {
            // the detector must never break the declaration it rides beside
        }
        long tick = Math.max(0L, plugin.lastSnapshotSimTick);
        float aim = plugin.aimDeg;
        long aimBits = Float.floatToIntBits(Float.isFinite(aim) ? aim : 0f) & 0xffffffffL;
        // PDCL v2: freeze the aim WORLD POINT at the same discharge instant, so the served
        // shot goes to the player's TRUE point even if the aim moves while the declaration crosses the
        // wire. Same sanitize discipline: the server decode is fail-closed on non-finite fields.
        float wx = plugin.aimWorldX;
        float wy = plugin.aimWorldY;
        long wxBits = Float.floatToIntBits(Float.isFinite(wx) ? wx : 0f) & 0xffffffffL;
        long wyBits = Float.floatToIntBits(Float.isFinite(wy) ? wy : 0f) & 0xffffffffL;
        // PDCL v4: capture the COMMITTED projectile's own ray — spawn point +
        // bearing + speed — so the server can serve exactly this shot (named-mount force-fire +
        // newborn re-pose) instead of guessing via a group FIRE press. Ray law (mirrors the
        // server record, fail-closed decode): all four finite WITH a real mount, else ALL-NaN —
        // any unreadable/non-finite piece or mount<0 degrades the whole ray to the NaN
        // sentinel (press-path serve), never a mixed frame.
        // PDCL v5: the ray is declared in the
        // SHIP frame — bearing = the FIRED ray, speed = the launch speed RELATIVE to the ship —
        // never the travel-frame velocity bearing. The engine births every projectile at
        // v0 = shipVel + launch·û(ray); for a 50 u/s Salamander off a 280 u/s Aurora the
        // travel bearing sat up to 93° off the forward hardpoint, busted the server's 15°
        // adopt-arc law and ghosted the missile (two rejects demoted the group to held).
        // Subtracting the own ship's velocity in the SAME frame recovers the engine's own
        // launch vector exactly (bolts declare the fired ray + the stat-modified muzzle speed);
        // the server re-poses the served newborn as serverShipVel + speed·û(ray).
        float mx = Float.NaN, my = Float.NaN, mb = Float.NaN, ms = Float.NaN;
        if (mount >= 0) {
            try {
                org.lwjgl.util.vector.Vector2f loc = p.getLocation();
                org.lwjgl.util.vector.Vector2f vel = p.getVelocity();
                if (loc != null && vel != null) {
                    float brg = Float.NaN;
                    float spd = Float.NaN;
                    org.lwjgl.util.vector.Vector2f shipVel = plugin.ownShip != null ? plugin.ownShip.getVelocity() : null;
                    if (vel.length() > 0.01f && shipVel != null) {
                        float rx = vel.x - shipVel.x;
                        float ry = vel.y - shipVel.y;
                        float rel = (float) Math.hypot(rx, ry);
                        if (rel > 0.01f) {
                            brg = (float) Math.toDegrees(Math.atan2(ry, rx));
                            spd = rel;
                        }
                    }
                    if (!Float.isFinite(spd) && mountWeapon != null) {
                        // this scan can race the engine's
                        // spawn-frame v0 compose and read the newborn's velocity as ~0 (002900
                        // census: 56/166 decls carried speed=0.0). Never commit the raw 0: in the
                        // ship frame the engine's birth law is exactly launch·û(facing), so
                        // declare the newborn's facing + the mount's launch speed — the
                        // stat-modified muzzle speed for bolts; for a missile weapon the spec's
                        // LAUNCH speed (WeaponAPI.getProjectileSpeed() is the missile's MAX
                        // speed — a Salamander launches at 50, tops out at 400). A non-finite
                        // read leaves spd NaN ⇒ the whole-ray degrade (press path).
                        brg = p.getFacing();
                        spd = mountWeapon.getProjectileSpeed();
                        try {
                            Object projSpec = mountWeapon.getSpec() != null
                                    ? mountWeapon.getSpec().getProjectileSpec() : null;
                            if (projSpec instanceof com.fs.starfarer.api.loading.MissileSpecAPI missileSpec) {
                                float launch = missileSpec.getLaunchSpeed();
                                if (Float.isFinite(launch) && launch >= 0f) {
                                    spd = launch;
                                }
                            }
                        } catch (Throwable ignore) {
                            // launch-speed seam unreadable: the max-speed read above stands
                        }
                    }
                    if (Float.isFinite(loc.x) && Float.isFinite(loc.y)
                            && Float.isFinite(brg) && Float.isFinite(spd)) {
                        mx = loc.x;
                        my = loc.y;
                        mb = brg;
                        ms = spd;
                    }
                }
            } catch (Throwable ignore) {
                mx = Float.NaN; my = Float.NaN; mb = Float.NaN; ms = Float.NaN; // whole-ray degrade
            }
        }
        long mxBits = Float.floatToIntBits(mx) & 0xffffffffL;
        long myBits = Float.floatToIntBits(my) & 0xffffffffL;
        long mbBits = Float.floatToIntBits(mb) & 0xffffffffL;
        long msBits = Float.floatToIntBits(ms) & 0xffffffffL;
        synchronized (plugin.pendingFireDecls) {
            if (plugin.pendingFireDecls.size() >= PredictReconcileCombatPlugin.FIRE_DECL_PENDING_MAX) {
                plugin.pendingFireDecls.pollFirst();
                plugin.fireDeclDropped++;
            }
            plugin.pendingFireDecls.addLast(new long[] {plugin.fireDeclSeq++, tick, aimBits, wxBits, wyBits, mount,
                    mxBits, myBits, mbBits, msBits});
        }
    }

    /**
     * The eventReplay parity counters for the WATCH log, one greppable line per meter
     * edge (emitted beside {@link PredictReconcileCombatPlugin#logSpeedMeter} only when the flag is ON, so existing log shapes
     * are untouched). These are the {@code predict_event_rebase_*} counters; an
     * {@code _applied} counter joins them when the measured re-base treatment is built.
     * + live_fallback_ray/_other, _nospread_missile(+_settled),
     * strict_nsm_* tokens (existing token names unchanged).
     */
    void logEventReplayMeter(String where) {
        if (!PredictReconcileCombatPlugin.EVENT_REPLAY_ENABLED || plugin.presenter == null) {
            // presenter is minted in init() before any advance(), so null only means a
            // harness-driven shell (FoePoseSeenLatchSmoke keeps it null by design — reachable
            // here since the default-ON flip): skip the WHOLE meter line rather than
            // emit fabricated presenter counts.
            return;
        }
        // The starvation tripwire (checked here so it costs nothing on the frame path and still fires
        // within one meter block of the condition). One-shot; a run with no alarm row grepped
        // is a run whose foe GPEE lane demonstrably fed.
        if (!plugin.foeDischargeStarvationWarned && plugin.eventReplayFoeDischarge == 0
                && plugin.missileLane.presenceAdopts() >= PredictReconcileCombatPlugin.FOE_DISCHARGE_STARVATION_ADOPTS) {
            plugin.foeDischargeStarvationWarned = true;
            PilotWatchLog.info("predict STARVATION-ALARM foe_discharge_starvation_alarm=1:"
                    + " sourced adopts=" + plugin.missileLane.presenceAdopts()
                    + " while predict_event_rebase_foe_discharge=0 [" + where + "]"
                    + " - foe WEAPON_DISCHARGE GPEE lane starved (one-shot)");
        }
        PilotWatchLog.info("predict B3 eventReplay-meter [" + where + "]:"
                + " predict_event_rebase_received=" + plugin.eventReplayReceived
                + " predict_event_rebase_own_discharge=" + plugin.eventReplayOwnDischarge
                + " predict_event_rebase_foe_discharge=" + plugin.eventReplayFoeDischarge
                // How many foe discharges became visible local projectiles vs skipped.
                + " predict_event_rebase_foe_fire_presented=" + plugin.presenter.foeFirePresented()
                + " predict_event_rebase_foe_fire_present_skipped=" + plugin.presenter.foeFirePresentSkipped()
                // Structural beam-mount skips (both roles) — subtract from
                // the skip totals before treating skips as defects.
                + " predict_event_rebase_beam_mount_skips=" + plugin.presenter.beamMountSkips()
                // Meter (b) — foe discharges skipped with no live
                // local body (bar: 0 for an entity whose server despawn is later), + the top-5 ids.
                + " predict_foe_fire_skip_no_body=" + plugin.presenter.foeFireSkipNoBody()
                + " predict_foe_fire_skip_no_body_top=" + plugin.presenter.foeFireSkipNoBodyTop()
                // Sound+flash rider: FX pieces per presentation (bar: flash==sound==presented, skipped=0).
                + " predict_event_rebase_foe_fire_fx_flash=" + plugin.presenter.foeFireFxFlash()
                + " predict_event_rebase_foe_fire_fx_sound=" + plugin.presenter.foeFireFxSound()
                + " predict_event_rebase_foe_fire_fx_skipped=" + plugin.presenter.foeFireFxSkipped()
                // Source fix: distinct WEAPON-BACKED replay-ship-source projectiles NOT
                // presenter-spawned (bar: 0 while the per-frame hold-fire suppression is live). The
                // nonweapon counter is the engine-own death-explosion class — expected ==
                // client-side death mirrors of replay-driven ships; split so the bar holds 0 across kills.
                + " predict_event_rebase_replay_ship_autonomous_discharge=" + plugin.replayShipAutonomousDischarges
                + " predict_event_rebase_replay_ship_nonweapon_damager=" + plugin.replayShipNonWeaponDamagers
                + " predict_event_rebase_replay_ship_weaponless_spawn=" + plugin.replayShipWeaponlessSpawns
                + plugin.leakRemover.meterToken() // Removed == the census total while cc.predict.leakRemove is ON
                + SubstepHoldAI.meterToken() // The per-sub-step hold's count-only meter
                // The census tokens ride THIS line because it is the segment the
                // axis-6 grader parses (watch-verdict.ps1 Get-WvKv is a name-agnostic tokenizer).
                // Graded bar = nonsystem==0 (class B, the weapon-cycle leak) + the split
                // identity; system_hinted (class A) is reported with its band, never graded to 0
                // while the press gate is undecided.
                + plugin.localFire.meterTokens(plugin.foeSystemApplyPresses, plugin.foeSystemCancelPresses,
                        plugin.foeSystemLane.agree + plugin.foeSystemLane.disagree)
                + " predict_event_rebase_own_window_edges=" + plugin.eventReplayOwnWindowEdges
                + " predict_event_rebase_foe_window_edges=" + plugin.eventReplayFoeWindowEdges
                + " predict_event_rebase_autofire_target=" + plugin.eventReplayAutofireTarget
                // Appended-fields law: the coordinator's apply/skip/expiry
                // meters — the must-hold verify bar is applies > 0 (lane-liveness).
                + " predict_event_rebase_autofire_target_applies=" + plugin.afTargets.applies
                + " predict_event_rebase_autofire_target_skip_unknown=" + plugin.afTargets.skipUnknown
                + " predict_event_rebase_autofire_target_stale_expired=" + plugin.afTargets.staleExpired
                // Appended-fields law: the kind-8 lane — rx + the presenter's
                // apply/meter liveness split, plus the reader-side overlap-law census (PROJECTILE
                // NSE rows are decoded past, never presented; content-0 = the script-det class,
                // which proves the GPEE kind-8 lane is the ONE det presenter).
                + " predict_event_rebase_paladin_det=" + plugin.eventReplayPaladinDet
                + " predict_event_rebase_paladin_det_presented=" + plugin.paladinDetLane.presented()
                + " predict_event_rebase_arc_hit=" + plugin.eventReplayArcHit
                + " predict_event_rebase_arc_hit_presented=" + plugin.arcHitLane.presented()
                + " predict_event_rebase_arc_hit_lash_own=" + ArcHitFxLane.lashHitsOwn()
                // Remote lash hits + the disruption paints (at the rise / late)
                + " predict_event_rebase_arc_hit_lash_remote=" + plugin.arcHitLane.lashHitsRemote()
                + " predict_event_rebase_arc_hit_lash_remote_overloads=" + plugin.arcHitLane.lashRemoteOverloads()
                + "/" + plugin.arcHitLane.lashRemoteOverloadsLate()
                + " predict_event_rebase_arc_hit_lash_remote_unphased=" + plugin.arcHitLane.lashRemoteUnphasedSkips() // gate refusals
                + " predict_event_rebase_arc_hit_double_mint_suspect=" + ArcHitFxLane.doubleMintSuspect() // the lane (iii) meter, wired at the build
                + " predict_event_rebase_arc_hit_skip=" + plugin.arcHitLane.skippedOwn()
                + "/" + plugin.arcHitLane.skippedOther() + "/" + plugin.arcHitLane.skippedUnresolved()
                + "/" + plugin.arcHitLane.skippedAbsurd() // Own/other/unresolved/absurd
                // The kind-10 crit lane — rx, presented,
                // skips unresolved/module/lethal/off
                + " predict_event_rebase_crit_malfunction=" + plugin.eventReplayCritMalfunction
                + " predict_event_rebase_crit_presented=" + plugin.critMalfunctionLane.presented()
                + " predict_event_rebase_crit_skip=" + plugin.critMalfunctionLane.skippedUnresolved()
                + "/" + plugin.critMalfunctionLane.skippedModule() + "/" + plugin.critMalfunctionLane.skippedLethal()
                + "/" + plugin.critMalfunctionLane.skippedOff()
                // Hull writes refused by vanilla's lethal guard / intake throws
                + " predict_event_rebase_crit_hull_guarded=" + plugin.critMalfunctionLane.hullGuarded()
                + " predict_event_rebase_crit_failures=" + plugin.critMalfunctionLane.failures()
                + " awss_projectile_rows=" + cc.starsector.client.net.AwssSnapshotReader.projectileRowsDecodedPast()
                + " awss_projectile_content0_rows=" + cc.starsector.client.net.AwssSnapshotReader.projectileContent0RowsDecodedPast()
                + " predict_event_rebase_own_window_agree=" + plugin.eventReplayOwnWindowAgree
                + " predict_event_rebase_own_window_disagree=" + plugin.eventReplayOwnWindowDisagree
                // The own-ship discharge agree/disagree counter (pairing on the snapshot-
                // tick clock; *_only = expired unmatched — server_only is the miss class a player sees).
                + " predict_event_rebase_own_discharge_agree=" + plugin.fireLanes.dischargeAgree
                + " predict_event_rebase_own_discharge_server_only=" + plugin.fireLanes.dischargeServerOnly
                + " predict_event_rebase_own_discharge_client_only=" + plugin.fireLanes.dischargeClientOnly
                + " predict_event_rebase_own_discharge_pending="
                + (plugin.fireLanes.pendingAuthDischarges.size() + plugin.fireLanes.pendingLocalDischarges.size())
                // Route A: every agree-pair rebases the predicted projectile onto the wire aim
                // (bar: applied + skip_zero_vel + skip_gone + skip_guided == agree; skip_unbound = 0;
                // max_abs_deg ≤ ~6° — the measured bound. skip_zero_vel is EXPECTED on
                // MovingRay-class own fire (vulcan/beam);
                // skip_guided is EXPECTED on guided-missile own fire — mid-guidance heading is not
                // the launch line).
                + " predict_event_rebase_own_discharge_aim_rebase_applied=" + plugin.fireLanes.aimRebase.dischargeAimRebased
                // The ray-paired-at-birth share of applied (a sub-count, the identity holds).
                + " predict_event_rebase_own_discharge_aim_rebase_applied_ray_birth=" + plugin.fireLanes.aimRebase.dischargeAimRebasedRayBirth
                + " predict_event_rebase_own_discharge_aim_rebase_skip_unbound=" + plugin.fireLanes.aimRebase.dischargeAimRebaseSkipUnbound
                + " predict_event_rebase_own_discharge_aim_rebase_skip_gone=" + plugin.fireLanes.aimRebase.dischargeAimRebaseSkipGone
                + " predict_event_rebase_own_discharge_aim_rebase_skip_zero_vel=" + plugin.fireLanes.aimRebase.dischargeAimRebaseSkipZeroVel
                + " predict_event_rebase_own_discharge_aim_rebase_skip_guided=" + plugin.fireLanes.aimRebase.dischargeAimRebaseSkipGuided
                + " predict_event_rebase_own_discharge_aim_rebase_skip_flare=" + plugin.fireLanes.aimRebase.dischargeAimRebaseSkipFlare
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDeg
                // GPEE v6: applied rebases whose speed came from the wire |v0|
                // (bar: == applied on a current-schema wire) + the max |wire−local| magnitude
                // (INFO: the own-side speed-estimate error the v6 field exists to kill).
                + " predict_event_rebase_own_discharge_speed_rebase_applied=" + plugin.fireLanes.aimRebase.dischargeSpeedRebased
                + " predict_event_rebase_own_discharge_speed_rebase_max_abs_ups=" + plugin.fireLanes.aimRebase.dischargeSpeedRebaseMaxAbsUps
                // MISSILE rebases rotate the live vector by the birth-line Δ and
                // never write the wire birth speed (the −117.65° true-twin stall) — so the
                // "speed_rebase_applied == applied" bar now reads applied − skipped_aged_missile − zero-vel.
                + " predict_event_rebase_own_discharge_speed_rebase_skipped_aged_missile=" + plugin.fireLanes.aimRebase.dischargeSpeedRebaseSkippedAgedMissile
                + " predict_event_rebase_own_discharge_aim_rebase_birth_heading_live_fallback=" + plugin.fireLanes.aimRebase.dischargeAimRebaseBirthHeadingLiveFallback
                // The fallback split — the token above == _ray + _other
                // (_ray = BALLISTIC_AS_BEAM/BEAM body, heading == birth heading, benign; _other is
                // named by the capped "predict v16 aim-rebase live-fallback" rows).
                + " predict_event_rebase_own_discharge_aim_rebase_birth_heading_live_fallback_ray=" + plugin.fireLanes.aimRebase.dischargeAimRebaseBirthHeadingLiveFallbackRay
                + " predict_event_rebase_own_discharge_aim_rebase_birth_heading_live_fallback_other=" + plugin.fireLanes.aimRebase.dischargeAimRebaseBirthHeadingLiveFallbackOther
                // The max split by spread class — the ≤6° bar
                // reads _nospread (spread-class = projectile spec maxSpread > 6°, whose per-JVM
                // independent per-shell rolls put the |Δ| floor above the bar on a perfectly-served
                // shot); _spread is the excluded class's own max (INFO).
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_nospread=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegNospread
                // Rider: the GRADED nospread max excludes
                // the 2 s lifecycle settle window (kill/MRST/respawn edges); raw _nospread above
                // demotes to INFO, excluded samples counted.
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_nospread_settled=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegNospreadSettled
                // MissileAPI bodies fold OUT of the two nospread maxes
                // above into their own pair (INFO until a data-set bar; low-launch rockets make
                // birth-vs-birth degrees ill-conditioned — 175812 seat-1 16.27° was all missile).
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_nospread_missile=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegNospreadMissile
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_nospread_missile_settled=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegNospreadMissileSettled
                + " predict_event_rebase_own_discharge_aim_rebase_nospread_kill_edge_excluded=" + plugin.fireLanes.aimRebase.dischargeAimRebaseNospreadKillEdgeExcluded
                // Samples taken while the own HULL pose
                // itself diverges >15° from the last wire facing are hull-pose divergence
                // (re-field fly-in class), not aim error — the
                // own-pose lanes grade that state; excluded here + counted, never silent.
                + " predict_event_rebase_own_discharge_aim_rebase_nospread_hullgap_excluded=" + plugin.fireLanes.aimRebase.dischargeAimRebaseNospreadHullGapExcluded
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_spread=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegSpread
                // Fold: the would-be |Δ| on skipped guided pairs (INFO: guidance-turn
                // magnitude, expected large — never part of the _nospread bar).
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_guided=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegGuided
                // The max |Δ| on AUTOFIRING-group pairs (INFO: the
                // server's autofire mirror slews on ITS OWN clock — the 74.72° class is
                // timing noise, not serve error; folded off the graded _nospread bar, rebase
                // still applied) + how many applied pairs folded.
                + " predict_event_rebase_own_discharge_aim_rebase_max_abs_deg_autofire=" + plugin.fireLanes.aimRebase.dischargeAimRebaseMaxAbsDegAutofire
                + " predict_event_rebase_own_discharge_aim_rebase_autofire_folded=" + plugin.fireLanes.aimRebase.dischargeAimRebaseAutofireFolded
                // Under cc.predict.pairClass=strict: folds where autofire was involved
                // at fire time or on the wire though the rebase-time group read said manual — the
                // toggle-transition leak class (013437 s2 nospread_settled=158.62). Expect ~0-5
                // on an autofire-heavy run; every count is a sample the strict bar no longer eats.
                + " predict_event_rebase_own_discharge_aim_rebase_autofire_folded_pairclass=" + plugin.fireLanes.aimRebase.dischargeAimRebasePairClassFolded
                // STRICT-pair per-shot buckets (mount-true + |Δtick|≤5 —
                // the offline strict re-pair gate) — the autofire-TARGET lane's acceptance
                // meter; the cumulative autofire max above is mispairing-artifact-
                // dominated and CANNOT grade that lane. hist edges deg = 1/2/5/10/20/45/tail.
                + " predict_event_rebase_own_discharge_aim_rebase_strict_af_pairs=" + AimRebaseLane.StrictShotBuckets.sum(plugin.fireLanes.aimRebase.runStrict.af)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_af_hist=" + AimRebaseLane.StrictShotBuckets.hist(plugin.fireLanes.aimRebase.runStrict.af)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_af_max=" + plugin.fireLanes.aimRebase.runStrict.afMax
                + " predict_event_rebase_own_discharge_aim_rebase_strict_af_loose=" + plugin.fireLanes.aimRebase.runStrict.afLoose
                + " predict_event_rebase_own_discharge_aim_rebase_strict_ns_pairs=" + AimRebaseLane.StrictShotBuckets.sum(plugin.fireLanes.aimRebase.runStrict.ns)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_ns_hist=" + AimRebaseLane.StrictShotBuckets.hist(plugin.fireLanes.aimRebase.runStrict.ns)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_ns_max=" + plugin.fireLanes.aimRebase.runStrict.nsMax
                + " predict_event_rebase_own_discharge_aim_rebase_strict_ns_loose=" + plugin.fireLanes.aimRebase.runStrict.nsLoose
                // The settled nospread MISSILE strict buckets (out of ns).
                + " predict_event_rebase_own_discharge_aim_rebase_strict_nsm_pairs=" + AimRebaseLane.StrictShotBuckets.sum(plugin.fireLanes.aimRebase.runStrict.nsm)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_nsm_hist=" + AimRebaseLane.StrictShotBuckets.hist(plugin.fireLanes.aimRebase.runStrict.nsm)
                + " predict_event_rebase_own_discharge_aim_rebase_strict_nsm_max=" + plugin.fireLanes.aimRebase.runStrict.nsmMax
                + " predict_event_rebase_own_discharge_aim_rebase_strict_nsm_loose=" + plugin.fireLanes.aimRebase.runStrict.nsmLoose
                // Rebases that fell back to the raw ray because the v2 true
                // flight bearing was NaN (bar: 0 on a current-schema wire).
                + " predict_event_rebase_own_discharge_bearing_unbound=" + plugin.fireLanes.aimRebase.dischargeBearingUnbound
                // in-window candidates the mount gate rejected (once per pending
                // entry) — each a cross-mount mispair the tick-only matcher would have minted.
                + " predict_event_rebase_own_discharge_aim_rebase_mount_mismatch=" + plugin.fireLanes.dischargeMountMismatch
                // Pairings formed with the mount gate inert (either side −1 = the
                // legacy pair-any sentinel) — bar: 0; mismatch=0 alone can't certify mount-true pairing.
                + " predict_event_rebase_own_discharge_aim_rebase_pair_any_fallback=" + plugin.fireLanes.dischargePairAnyFallback
                // Total formed pairs — pair_any's RATE denominator.
                + " predict_event_rebase_own_discharge_aim_rebase_pairs_formed=" + plugin.fireLanes.dischargePairsFormed
                // Formed pairs wider than the mount's OBSERVED shot period — the
                // geometric rebase skipped (a mispaired rebase snaps a live bolt onto the wrong
                // shot's bearing mid-flight). Expected: melee-only, small vs agree.
                + " predict_event_rebase_own_discharge_aim_rebase_crossshot_suspect=" + plugin.fireLanes.aimRebase.dischargeCrossshotSuspect
                // The client confessing-gun decl lane — birth/foreign/
                // presented_skip are plugin counters (round-persistent like the pairing
                // cumulatives), prebind/thread_miss/cb_errors/deleg_errors are the sink's
                // cumulative statics (bars: presented_skip 0, cb_errors 0, deleg_errors 0;
                // thread_miss 0 by the engine-advance-thread recon — >0 = fail-soft to scan).
                + " predict_onfire_client_birth=" + plugin.fireDeclBirthSeen
                + " predict_onfire_client_foreign=" + plugin.fireDeclBirthForeign
                + " predict_onfire_client_prebind=" + ClientFireBirthSink.prebind()
                + " predict_onfire_client_thread_miss=" + ClientFireBirthSink.threadMiss()
                + " predict_onfire_client_presented_skip=" + plugin.fireDeclBirthPresentedSkip
                + " predict_onfire_client_cb_errors=" + ClientFireBirthSink.cbErrors()
                + " predict_onfire_client_deleg_errors=" + ClientFireBirthSink.delegErrors()
                + " predict_event_rebase_ahead=" + plugin.eventReplayAheadOfSnapshot
                // Depth meters — max ahead depth at hold, max behind age at
                // present (hairpin baseline ±1 tick; multi-tick BEHIND at real WAN arms the catch-up).
                + " predict_event_rebase_ahead_max=" + plugin.eventReplayAheadMaxTicks
                + " predict_event_rebase_age_max=" + plugin.eventReplayAgeMaxTicks
                // Foe presentations advanced downrange by the catch-up (age>0
                // at present time; hairpin baseline expects a small share, near-zero on LAN).
                + " predict_event_rebase_foe_fire_catchup_advanced=" + plugin.presenter.foeFireCatchupAdvanced()
                // decrement-on-present: ammo rounds spent on remote mounts + dry re-arms.
                + " predict_event_rebase_foe_fire_ammo_decremented=" + plugin.presenter.foeFireAmmoDecremented()
                + " predict_event_rebase_foe_fire_ammo_rearmed=" + plugin.presenter.foeFireAmmoRearmed()
                + " predict_event_rebase_foe_fire_loaded_cycles=" + plugin.presenter.foeFireLoadedCycles() //
                // Composed = vanilla-inherit spawns (ray angle + shooter velocity,
                // factory composition; xcheck = max |composed dir − wire flightBearing|, ±180-wrapped
                // — near 0 for zero-spread weapons, jitter-arc for spread classes). bearing_applied
                // = ray-class wire-bearing spawns + discriminator fail-soft;
                // bar: both fallbacks ≈ 0 on projectile weapons; composed + applied + nan_wire
                // + spawn_null == presented.
                + " predict_event_rebase_foe_fire_composed=" + plugin.presenter.foeFireComposed()
                + " predict_event_rebase_foe_fire_compose_xcheck_max_abs_deg=" + plugin.presenter.foeFireComposeXcheckMaxAbsDeg()
                // The launch source — from_wire = launched at
                // the wire facing, local = the NaN-aim fallback (bar 0; a finite aim reads from_wire).
                + " predict_event_rebase_foe_fire_launch_from_wire=" + plugin.presenter.foeFireLaunchFromWire()
                + " predict_event_rebase_foe_fire_launch_local=" + plugin.presenter.foeFireLaunchLocal()
                // GPEE v6: composed spawns overridden to the full wire vector
                // |v0|·û(bearing) (bar: == composed on a current-schema wire) + the max
                // |local composed speed − wire |v0|| (INFO: the ch-B vel-estimate error).
                + " predict_event_rebase_foe_fire_speed_applied=" + plugin.presenter.foeFireSpeedApplied()
                + " predict_event_rebase_foe_fire_speed_xcheck_max_abs_ups=" + plugin.presenter.foeFireSpeedXcheckMaxAbsUps()
                // GPEE v8 (shot-fidelity-budget): presents spawned AT the wire muzzle —
                // arm-independent (bar: == presented minus NaN-muzzle frames on a
                // current-schema wire) + max dist(local fire point, wire muzzle) (INFO: the
                // lagged-replica muzzle re-derivation error the override retires).
                + " predict_event_rebase_foe_fire_muzzle_applied=" + plugin.presenter.foeFireMuzzleApplied()
                + " predict_event_rebase_foe_fire_muzzle_xcheck_max_abs_u=" + plugin.presenter.foeFireMuzzleXcheckMaxAbsU()
                + " predict_event_rebase_foe_fire_bearing_applied=" + plugin.presenter.foeFireBearingApplied()
                + " predict_event_rebase_foe_fire_bearing_fallback_nan_wire=" + plugin.presenter.foeFireBearingFallbackNanWire()
                + " predict_event_rebase_foe_fire_bearing_fallback_spawn_null=" + plugin.presenter.foeFireBearingFallbackSpawnNull()
                // Foe rays spawned WITH the wire-speed inherited term (bar: <= bearing_applied; equal on
                // a current-schema wire where every wire-bearing present is a ray with a usable birth
                // speed).
                + " predict_event_rebase_foe_fire_ray_wire_speed=" + plugin.presenter.foeFireRayWireSpeed()
                // Consumed (the reason lane) vs ignored (now only paths without a
                // despawn consumer — expected 0 here); resets counted plugin-side since the
                // 3-consumer receive took over the session-internal count.
                + " predict_event_rebase_despawn_consumed=" + plugin.despawnConsumed
                + " predict_event_rebase_despawn_ignored="
                + (plugin.session != null ? plugin.session.despawnFramesIgnored() : 0)
                + " predict_event_rebase_match_reset_ignored=" + plugin.matchResetsIgnored
                // Round boundaries the render thread rebuilt for (multi-round respawn).
                + " predict_event_rebase_match_reset_applied=" + plugin.matchResetsApplied);
        // The mid-flight half of the own-window meter (the line above sees edges only).
        // srvon_localoff = the player-felt "server ON, client ate it"; over_lag = streaks past the
        // ~0.5 s edge-lag bound; open_streak_ticks > 0 = a skew UNRESOLVED at this flush.
        PilotWatchLog.info("predict MF own-window midflight-meter [" + where + "]:"
                + " mf_sys_frames=" + plugin.mfFrames[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_unknown=" + plugin.mfUnknownFrames[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_srvon_localoff=" + plugin.mfDisagreeSrvOn[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_srvoff_localon=" + plugin.mfDisagreeSrvOff[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_streaks=" + plugin.mfStreaks[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_streaks_over_lag=" + plugin.mfStreaksOverLag[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_max_streak_ticks=" + plugin.mfMaxStreakTicks[PredictReconcileCombatPlugin.MF_SYS]
                + " mf_sys_open_streak_ticks=" + openMidflightStreakTicks(PredictReconcileCombatPlugin.MF_SYS)
                + " mf_vent_frames=" + plugin.mfFrames[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_unknown=" + plugin.mfUnknownFrames[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_srvon_localoff=" + plugin.mfDisagreeSrvOn[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_srvoff_localon=" + plugin.mfDisagreeSrvOff[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_streaks=" + plugin.mfStreaks[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_streaks_over_lag=" + plugin.mfStreaksOverLag[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_max_streak_ticks=" + plugin.mfMaxStreakTicks[PredictReconcileCombatPlugin.MF_VENT]
                + " mf_vent_open_streak_ticks=" + openMidflightStreakTicks(PredictReconcileCombatPlugin.MF_VENT));
    }
}
