package cc.starsector.client;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.voice.VoiceClientSettings;

/**
 * P8 leg 1 — the own-ship READOUT samplers carved VERBATIM out of
 * {@link PredictReconcileCombatPlugin} (R6: zero behaviour change): the LOCAL-vs-SERVER speed
 * + flux rows and their meters (max deltas, the under-fire / burst vel-gap samples, the shield /
 * overload / vent / cloak agreement riders and the own-cloak APPLY force — all sampled on FRESH
 * own-pose frames, meter-samples-then-pin ahead of {@code maintainOwnFlux}), the flux-delta
 * tick-pin, the local ship-system sample, the sim-rate + ping/tick lines handed to
 * {@link HudReadoutLane}, and the left-HUD row helper. The bodies are the plugin's, unchanged
 * but for the {@code plugin.} / {@code PredictReconcileCombatPlugin.} qualification of the state
 * they read: every field stays in the plugin (its teardown meter flush and the diagnostic dump
 * read them, several are cross-thread), package-private for this lane. Presentation-side and
 * fail-soft throughout: a status hiccup never disturbs the reconcile or the watch. B-legal:
 * engine API + the plugin. Own file per the NEW-LANE LAW ({@code deferred-refactors.md}).
 */
final class OwnReadoutLane {
    private final PredictReconcileCombatPlugin plugin;

    OwnReadoutLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }


    /**
     * The own-speed gap instrument (gaps ③ + ②). Show the server-AUTHORITATIVE own-ship speed
     * (|velX,velY| from the AWSS pose — already decoded, previously unused for the own ship) beside the
     * LOCAL-predicted speed on the player HUD status panel, so a pilot can SEE the true speed, otherwise
     * impossible to know, and record the running max |local-server| delta for the WATCH log. A
     * persistent delta is the signature of a movement mechanic the server does NOT model (gap ② — the
     * zero-flux / flux->speed bonus, or the ship-system boost): the client's real local engine applies it,
     * the authoritative server does not, so the two speeds diverge and the reconcile fights. The SERVER line
     * is highlighted (red "debuff") while diverged so the moment of divergence is obvious in the WATCH.
     * Presentation-side; fail-soft — a status hiccup must never disturb the reconcile or the watch.
     */
    void maintainSpeedReadout(ShipAPI own, ServerEntityPose ownPose, boolean freshSnapshot, boolean stale) {
        try {
            Vector2f v = own.getVelocity();
            float localSpeed = (float) Math.sqrt(v.x * v.x + v.y * v.y);
            if (!Float.isFinite(localSpeed)) {
                return;
            }
            plugin.lastLocalSpeed = localSpeed;
            // Recompute the SERVER speed + the running max delta ONLY on a fresh authoritative frame (the
            // stale-pose guard): between net ticks (and after teardown) the AWSS pose is frozen, so re-deriving
            // it every render frame would record a spurious growing delta as the local ship coasts to rest.
            if (freshSnapshot) {
                float serverSpeed = (float) Math.sqrt(ownPose.velX() * ownPose.velX()
                        + ownPose.velY() * ownPose.velY());
                if (Float.isFinite(serverSpeed)) {
                    plugin.lastServerSpeed = serverSpeed;
                    float d = Math.abs(localSpeed - serverSpeed);
                    if (d > plugin.maxSpeedDeltaSeen) {
                        plugin.maxSpeedDeltaSeen = d;
                    }
                }
                // Under-fire latch + vector vel-gap sample (field block doc).
                long tickNow = plugin.lastSnapshotSimTick;
                float hp = own.getHitpoints();
                if (Float.isFinite(hp)) {
                    if (hp < plugin.lastOwnHullHp) {
                        plugin.underFireUntilTick = tickNow + PredictReconcileCombatPlugin.UNDER_FIRE_LATCH_TICKS;
                    }
                    plugin.lastOwnHullHp = hp;
                }
                if (tickNow + PredictReconcileCombatPlugin.UNDER_FIRE_LATCH_TICKS < plugin.underFireUntilTick) {
                    plugin.underFireUntilTick = Long.MIN_VALUE; // sim clock went backwards (round restart)
                }
                if (tickNow <= plugin.underFireUntilTick) {
                    float gx = ownPose.velX() - v.x;
                    float gy = ownPose.velY() - v.y;
                    float gap = (float) Math.sqrt(gx * gx + gy * gy);
                    if (Float.isFinite(gap) && gap > plugin.underFireVelGapMax) {
                        plugin.underFireVelGapMax = gap;
                    }
                }
                // burst-window twin of the sample above (latch advanced pre-rebase in
                // updateOwnBurstLatch, so a rebase frame samples the post-rebase gap like under-fire).
                if (tickNow <= plugin.burstUntilTick) {
                    float bx = ownPose.velX() - v.x;
                    float by = ownPose.velY() - v.y;
                    float bgap = (float) Math.sqrt(bx * bx + by * by);
                    if (Float.isFinite(bgap) && bgap > plugin.burstVelGapMax) {
                        plugin.burstVelGapMax = bgap;
                    }
                }
            }
            // HUD rows render in PUBLIC matches too — the
            // speed/flux/sim-rate/net rows can all help players identify desync.
            // The LOCAL SYS row is GONE,
            // so NO row is debug-gated anymore. Meters/sidelog lanes
            // above were always unconditional.
            float delta = Math.abs(plugin.lastLocalSpeed - plugin.lastServerSpeed);
            hudRow(VoiceClientSettings.HUD_CLIENT_SPEED, "cc_speed_local", PredictReconcileCombatPlugin.SPEED_ICON,
                    "LOCAL SPEED", PredictReconcileCombatPlugin.fmtSpeed(plugin.speedDeflicker.local(localSpeed)), false);
            hudRow(VoiceClientSettings.HUD_SERVER_SPEED, "cc_speed_server", PredictReconcileCombatPlugin.SPEED_ICON,
                    stale ? "SERVER SPEED (STALE)" : "SERVER SPEED",
                    PredictReconcileCombatPlugin.fmtSpeed(plugin.speedDeflicker.server(plugin.lastServerSpeed)), !stale && delta > PredictReconcileCombatPlugin.SPEED_DIVERGE_HILITE);
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the watch on a status hiccup
        }
    }

    /**
     * Show the server-AUTHORITATIVE own-ship flux (decoded onto the AWSS pose, schema
     * v3+) beside the LOCAL-engine flux on the HUD, mirroring the two SPEED lines. This is the instrument
     * that tests the "not flux-related" claim directly: if the local engine sits at a stable flux
     * level while the speed reads 110 and the server reads 140, the zero-flux boost is NOT the cause. A SERVER
     * frame with no flux tier (v1) shows "n/a (v1)". SERVER values + the max flux-level delta update only on a
     * fresh frame (stale-pose guard); the SERVER line is highlighted when the flux LEVELS diverge. Fail-soft.
     */
    void maintainFluxReadout(ShipAPI own, ServerEntityPose ownPose, boolean freshSnapshot, boolean stale) {
        try {
            FluxTrackerAPI ft = own.getFluxTracker();
            if (ft == null) {
                return;
            }
            // Rider: zero-capacity hulls read getFluxLevel() = 0/0 = NaN —
            // pin level 0 (zero capacity = zero flux pressure, the server-side
            // EngineCombatBinding split) instead of aborting, which skipped EVERY
            // downstream rider (shield agreement, overload/vent, flux_gap meter, cloak).
            // A genuinely-broken read (NaN at maxFlux > 0) still returns fail-soft.
            float localLevel = ft.getMaxFlux() > 0f ? ft.getFluxLevel() : 0f;
            if (!Float.isFinite(localLevel)) {
                return;
            }
            plugin.lastLocalFluxLevel = localLevel;
            plugin.lastLocalCurrFlux = ft.getCurrFlux();
            plugin.lastLocalMaxFlux = ft.getMaxFlux();
            if (freshSnapshot && ownPose.hasFlux()) {
                // own-ship shield agreement rider: the server's authoritative own-hull
                // shieldOn (fluxFlags) vs the LOCAL engine's — the bit-10 UP lane's end-to-end
                // check ("is the boss shield REAL serverside"), counted per fresh own-pose frame.
                if (ownPose.hasFluxFlags()) {
                    boolean authShieldOn = (ownPose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_SHIELD_ON) != 0;
                    ShieldAPI ownSh = own.getShield();
                    boolean localShieldOn = ownSh != null && ownSh.isOn();
                    if (localShieldOn == authShieldOn) {
                        plugin.ownShieldAgree++;
                    } else {
                        plugin.ownShieldDisagree++;
                    }
                    // Own overload/vent agreement riders (the BUG-B meters),
                    // beside the shield rider above; sampled BEFORE maintainOwnFlux pins/slaves
                    // (meter-samples-then-pin — the counters stay honest misprediction meters).
                    if (PredictKnobConfig.OWN_FLUX_METER) {
                        boolean authOvl = (ownPose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_OVERLOADED) != 0;
                        boolean authVent = (ownPose.fluxFlags() & PredictReconcileCombatPlugin.FLUX_FLAG_VENTING) != 0;
                        if (ft.isOverloaded() == authOvl) {
                            plugin.ownOverloadAgree++;
                        } else {
                            plugin.ownOverloadDisagree++;
                        }
                        if (ft.isVenting() == authVent) {
                            plugin.ownVentAgree++;
                        } else {
                            plugin.ownVentDisagree++;
                        }
                    }
                    // mask-guard mirror: the latest carried own fluxFlags (fresh frames).
                    plugin.lastOwnAuthFluxFlags = ownPose.fluxFlags();
                }
                plugin.lastServerFluxLevel = ownPose.fluxLevel();
                plugin.lastServerCurrFlux = ownPose.currFlux();
                plugin.lastServerMaxFlux = ownPose.maxFlux();
                float d = Math.abs(localLevel - plugin.lastServerFluxLevel);
                // The flux_gap meter (the-second-gunner slice 1): accumulate every fresh
                // snapshot sample so the flush line carries max AND mean — the twin-autofire-gunner
                // divergence, graded on the B3 confirming watch (one number decides the reconcile sequel).
                plugin.fluxGapSamples++;
                plugin.fluxGapSum += d;
                if (d > plugin.maxFluxDeltaSeen) {
                    plugin.maxFluxDeltaSeen = d;
                    pinFluxDeltaIncrease(d);
                }
            }
            // Own-ship cloak agreement rider: the server's authoritative own-hull
            // cloak state (the schema-v10 phase-cloak tier) vs the LOCAL engine's isOn() — the bit-11
            // UP lane's end-to-end check, counted per fresh own-pose frame beside the shield rider
            // above. Operand isOn (IN|ACTIVE), the foe-lane decode reused; tier ABSENT = skip.
            // Under cc.predict.ownCloak=apply a PERSISTENT ON-ness
            // disagreement forces the own cloak to the authoritative state (knob javadoc at the
            // OWN_CLOAK_MODE decl) — collapsing a spurious local cloak cycle unapply()s the
            // vanilla PhaseCloakStats mults (the 0.0115 crush). Sub-state skew with agreeing
            // ON-ness is RTT physics, never forced.
            if (freshSnapshot && ownPose.hasPhaseCloakState() && !"off".equals(PredictKnobConfig.OWN_CLOAK_MODE)) {
                ShipSystemAPI ownCloak = own.getPhaseCloak();
                if (ownCloak != null) {
                    ShipSystemAPI.SystemState authCloak = PredictReconcileCombatPlugin.systemStateForCode(ownPose.cloakStateCode());
                    if (authCloak != null) {
                        boolean authCloakOn = authCloak == ShipSystemAPI.SystemState.IN
                                || authCloak == ShipSystemAPI.SystemState.ACTIVE;
                        if (ownCloak.isOn() == authCloakOn) {
                            plugin.ownCloakAgree++;
                            plugin.ownCloakDisagreeStreak = 0;
                            plugin.phaseDive.ownCloakAgreed(own);
                        } else {
                            plugin.ownCloakDisagree++;
                            plugin.ownCloakDisagreeStreak++;
                            if (authCloakOn) {
                                plugin.ownCloakDisagreeAuthOn++;
                            } else {
                                plugin.ownCloakDisagreeLocalOn++;
                            }
                            boolean phaseOnBacked = !authCloakOn || ownPose.isPhasedBit();
                            if (plugin.phaseDive.guardOwnCloakDisagree(own, plugin.ownCloakDisagreeStreak,
                                    authCloakOn, PredictKnobConfig.OWN_CLOAK_APPLY && phaseOnBacked,
                                    PredictKnobConfig.OWN_CLOAK_FORCE_PATIENCE_FRAMES,
                                    plugin.lastSnapshotSimTick)) {
                                // The dive
                                // playout owns a diving hull's cloak (forced IN) — never forced here;
                                // the streak resets so it cannot grow across the dive (meters count on).
                                // The guard call records the sample + the would-be streak first.
                                plugin.ownCloakDisagreeStreak = 0;
                            } else if (PredictKnobConfig.OWN_CLOAK_APPLY && phaseOnBacked
                                    && plugin.ownCloakDisagreeStreak >= PredictKnobConfig.OWN_CLOAK_FORCE_PATIENCE_FRAMES) {
                                float progress = FoeStateLaneCoordinator.seedProgressFor(authCloak,
                                        ownPose.cloakEffectLevel(), ownPose.cloakCooldownRemaining(),
                                        ownCloak);
                                ownCloak.forceState(authCloak, progress);
                                plugin.ownCloakForces++;
                                plugin.ownCloakDisagreeStreak = 0;
                                if (plugin.ownCloakForceLines < PredictKnobConfig.OWN_CLOAK_FORCE_LOG_MAX) {
                                    plugin.ownCloakForceLines++;
                                    PilotWatchLog.info("predict v10 own-cloak APPLY forceState("
                                            + authCloak + ",p=" + PredictReconcileCombatPlugin.fmt2(progress) + ") @tick="
                                            + plugin.lastSnapshotSimTick
                                            + " (persistent ON-ness disagreement)"
                                            + (plugin.ownCloakForceLines == PredictKnobConfig.OWN_CLOAK_FORCE_LOG_MAX
                                                    ? " [line cap reached]" : ""));
                                }
                            }
                        }
                    }
                }
            }
            // Flux rows are PUBLIC (desync-visibility)
            // — every meter/agreement lane above stays on as before.
            hudRow(VoiceClientSettings.HUD_CLIENT_FLUX, "cc_flux_local", PredictReconcileCombatPlugin.FLUX_ICON,
                    "LOCAL FLUX", PredictReconcileCombatPlugin.fmtFlux(localLevel, plugin.lastLocalCurrFlux, plugin.lastLocalMaxFlux), false);
            if (plugin.lastServerFluxLevel >= 0f) {
                float fluxDelta = Math.abs(plugin.lastLocalFluxLevel - plugin.lastServerFluxLevel);
                hudRow(VoiceClientSettings.HUD_SERVER_FLUX, "cc_flux_server", PredictReconcileCombatPlugin.FLUX_ICON,
                        stale ? "SERVER FLUX (STALE)" : "SERVER FLUX",
                        PredictReconcileCombatPlugin.fmtFlux(plugin.lastServerFluxLevel, plugin.lastServerCurrFlux, plugin.lastServerMaxFlux),
                        !stale && fluxDelta > PredictReconcileCombatPlugin.FLUX_DIVERGE_HILITE);
            } else {
                hudRow(VoiceClientSettings.HUD_SERVER_FLUX, "cc_flux_server", PredictReconcileCombatPlugin.FLUX_ICON,
                        "SERVER FLUX", "n/a (v1)", false);
            }
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the watch on a status hiccup
        }
    }

    /**
     * tick-pin each meaningful increase of the running max flux-level delta. Before this
     * line the teardown meter named WHAT the max was (192340: 27%) but never WHEN — the transient was
     * structurally unpinnable without an offline ccwr decode. Caller is the fresh-snapshot branch of
     * {@link #maintainFluxReadout}, so {@code lastSnapshotSimTick} IS the tick the delta was measured at.
     * {@link PredictKnobConfig#FLUX_PIN_MIN_STEP} keeps startup creep from burning the {@link PredictKnobConfig#FLUX_PIN_LOG_MAX} cap.
     */
    private void pinFluxDeltaIncrease(float newMax) {
        if (plugin.fluxPinLines >= PredictKnobConfig.FLUX_PIN_LOG_MAX || newMax < plugin.lastFluxDeltaPinned + PredictKnobConfig.FLUX_PIN_MIN_STEP) {
            return;
        }
        plugin.fluxPinLines++;
        plugin.lastFluxDeltaPinned = newMax;
        PilotWatchLog.info("predict flux fluxDelta-pin #" + plugin.fluxPinLines + ": maxFluxDelta="
                + Math.round(newMax * 100f) + "% simTick=" + plugin.lastSnapshotSimTick
                + " localFlux=" + PredictReconcileCombatPlugin.fmtFlux(plugin.lastLocalFluxLevel, plugin.lastLocalCurrFlux, plugin.lastLocalMaxFlux)
                + " serverFlux=" + PredictReconcileCombatPlugin.fmtFlux(plugin.lastServerFluxLevel, plugin.lastServerCurrFlux, plugin.lastServerMaxFlux)
                + (plugin.fluxPinLines == PredictKnobConfig.FLUX_PIN_LOG_MAX ? " [pin cap reached; running max continues]" : ""));
    }

    /**
     * Sample the LOCAL own-ship system id + state (e.g. {@code maneuvering_jets IN}).
     * The HUD row is GONE ("just take away the ship system status coz thats
     * not useful") — the sample feeds ONLY the diagnostic dump's {@code localSys=} sidelog token, which still
     * tells a debrief whether the speed-boosting ship system was engaged on the predicting client.
     */
    void maintainSystemReadout(ShipAPI own) {
        try {
            ShipSystemAPI sys = own.getSystem();
            String state;
            if (sys == null) {
                state = "none";
            } else {
                String id = sys.getId();
                Object st = sys.getState();
                state = (id == null ? "?" : id) + " " + (st == null ? "?" : st.toString());
            }
            plugin.lastLocalSysState = state; // unconditional — the diagnostic dump's localSys= token reads it
        } catch (Throwable ignore) {
            // sampling is presentation-side; never break the watch on a status hiccup
        }
    }

    /**
     * The TIMESTAMPED sim-rate HUD line — show the two combat clocks' rates live so a tester can
     * SEE a server/client clock divergence as a number, the moment it diverges. Rates are
     * TRAILING-WINDOW off the I.3 slope buffer, never cumulative-anchor — a mid-round adopt hands the readout
     * a server clock ~1100 sim-s ahead of a wall anchor set at mission attach, and the cumulative form
     * rendered that offset as a phantom "SIM RATE ~69x" for the whole round. C/S =
     * {@code ΔclientSim/Δwall} / {@code ΔserverSim/Δwall}; the {@code (x…)} ratio = {@code ΔserverSim/ΔclientSim},
     * highlighted (red) past {@link PredictReconcileCombatPlugin#SIM_RATE_DIVERGE_HILITE}; {@code measuring…} until the buffer spans half
     * the window. A stale feed truthfully decays S toward 0 under the STALE label (never a false hilite —
     * the ratio falls with it and the hilite is {@code !stale}-gated anyway).
     */
    void maintainSimRateReadout(boolean stale) {
        try {
            // The sim-rate row is PUBLIC (a clock divergence is
            // desync a tester can name).
            if (plugin.combatWallStartNanos == Long.MIN_VALUE) {
                return;
            }
            double[] d = plugin.slopeWindowDeltas();
            String value;
            boolean diverged = false;
            if (d == null) {
                value = "measuring…";
            } else {
                double svrOverCli = d[2] > 0.05d ? d[1] / d[2] : 1.0d;
                diverged = !stale && svrOverCli > PredictReconcileCombatPlugin.SIM_RATE_DIVERGE_HILITE;
                value = "C " + PredictReconcileCombatPlugin.fmt2(d[2] / d[0]) + " / S " + PredictReconcileCombatPlugin.fmt2(d[1] / d[0]) + "  (x" + PredictReconcileCombatPlugin.fmt2(svrOverCli)
                        + ") now " + plugin.clockServo.servoNowRate() + " servo " + plugin.clockServo.servoState()
                        + " fps " + plugin.clockServo.fpsState();
            }
            // The row lives in the bottom-right block now.
            plugin.hudReadout.setSimLine((stale ? "sim rate (stale) " : "sim rate ") + value, diverged);
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the watch on a status hiccup
        }
    }

    /**
     * The PING + SERVER TICK HUD lines (design: {@link PredictReconcileCombatPlugin#NET_ICON}
     * doc). Ping {@code NaN} renders {@code n/a} (TCP always; UDP only before the first echo —
     * sub-second on a live lane). Tick/net render {@code measuring…} until the trailing window
     * holds ≥ 2 s of samples; a negative tick delta (a mid-window sim-tick re-anchor) also resets
     * to {@code measuring…} rather than showing a negative rate. Ping values are never
     * highlighted red (no measured basis for a "bad ping" threshold yet; measure first);
     * the ONE highlighted state is RECONNECTING (a session-death
     * boundary event, not a threshold call) — the "reconnecting" presentation state,
     * with the arm/re-admit edges logged here (the session itself is log-silent by design).
     */
    void maintainNetReadout(boolean stale) {
        try {
            boolean reconnecting = plugin.session != null && plugin.session.reconnecting();
            if (reconnecting != plugin.reconnectingShown) {
                plugin.reconnectingShown = reconnecting;
                PilotWatchLog.info(reconnecting
                        ? "predict reconnect: session death detected -> standing re-HELLO armed"
                                + " (B4 freeze: ships free-run on prediction until re-admit"
                                + " or match-end BYE)"
                        : "predict reconnect: re-HELLO loop disarmed (re-admitted or match end)");
            }
            // Ping/tick rows are PUBLIC —
            // the reconnect edge-log above was always a sidelog lane, unaffected.
            double pingMs = plugin.session != null ? plugin.session.netPingMillis() : Double.NaN;
            String ping = Double.isNaN(pingMs) ? "n/a" : Math.round(pingMs) + " ms";
            // The two left-HUD rows (cc_net_ping/cc_net_tick)
            // moved to the bottom-right in the voice-rtt idiom — ONE composed line, drawn by
            // HudReadoutLane (toggle hud.netRow); the sampler stays here (cross-thread fields).
            plugin.hudReadout.setNetLine(reconnecting ? "ping reconnecting…"
                    : "ping " + ping + (stale ? " (stale)" : "") + " | " + tickRateSummary());
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the watch on a status hiccup
        }
    }

    /** "60.1 tick/s | 20.0 upd/s" (server ticks + snapshot updates applied here)
     *  over the trailing window, else "measuring…". Plain {@code |} separator, no bullet.
     * */
    String tickRateSummary() {
        if (plugin.netRateSamples.size() < 2) {
            return "measuring…";
        }
        double[] oldest = plugin.netRateSamples.peekFirst();
        double[] newest = plugin.netRateSamples.peekLast();
        double dWall = newest[0] - oldest[0];
        double dTick = newest[1] - oldest[1];
        if (dWall < 2.0d || dTick < 0d) {
            return "measuring…";
        }
        return PredictReconcileCombatPlugin.fmt1(dTick / dWall) + " tick/s | "
                + PredictReconcileCombatPlugin.fmt1((newest[2] - oldest[2]) / dWall) + " upd/s";
    }

    /** A left-HUD status row of ours behind its {@code /settings} toggle ({@code bit} =
     *  a {@code VoiceClientSettings.HUD_*}); an un-asserted key fades ~1.25 s later and the
     *  vanilla column repacks — collapse for free, no void. */
    private void hudRow(int bit, String key, String icon, String title, String value, boolean debuff) {
        if (plugin.hudReadout.prefs().hudRow(bit)) {
            plugin.engine.maintainStatusForPlayerShip(key, icon, title, value, debuff);
        }
    }

    /**
     * The NO-FEED CONTROL HUD line — just the local engine clock rate vs wall (there is no server
     * clock to show). Lets the owner read the stock-load pacing deficit live off the mission HUD. Windowed
     * like the fed line: C = {@code ΔclientSim/Δwall} over the I.3 slope buffer.
     */
    void maintainNoFeedSimRateReadout() {
        try {
            // The NO-FEED variant is PUBLIC like its fed twin
            // (a dead feed is exactly the desync story a tester should be able to read).
            // The VANILLA purity branch below renders only in a self-configured
            // serverless control window (rig shape, unreachable from the kit's descriptor).
            if (plugin.combatWallStartNanos == Long.MIN_VALUE) {
                return;
            }
            double[] d = plugin.slopeWindowDeltas();
            // TRUE-VANILLA: the compare window says its purity on the HUD — the
            // contamination was CAUGHT off this row, so the re-record's row must state the fix.
            // servoNowRate() is dropped there (the law never folds in a vanilla window: forever "…").
            String value = d == null
                    ? "measuring…"
                    : plugin.vanillaWindow
                            ? "C " + PredictReconcileCombatPlugin.fmt2(d[2] / d[0]) + " (pure — sim lanes silent) fps "
                                    + plugin.clockServo.fpsState()
                            : "C " + PredictReconcileCombatPlugin.fmt2(d[2] / d[0]) + " now " + plugin.clockServo.servoNowRate()
                                    + " (no feed) servo " + plugin.clockServo.servoState()
                                    + " fps " + plugin.clockServo.fpsState();
            plugin.hudReadout.setSimLine((plugin.vanillaWindow ? "sim rate (vanilla) " : "sim rate (no feed) ")
                    + value, false); // bottom-right block, like the fed twin
        } catch (Throwable ignore) {
            // HUD readout is presentation-side; never break the run on a status hiccup
        }
    }
}
