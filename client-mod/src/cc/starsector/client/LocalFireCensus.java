package cc.starsector.client;

import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * The LOCAL-FIRE CENSUS: instrumentation ONLY for the chronic
 * {@code autonomous_discharge} axis-6 bar. Nothing here changes what the sim does; every method is
 * a counter/log hook and every body is {@code try}-wrapped so a diagnostic can never break a watch.
 *
 * <p>Why a collaborator and not more {@code PredictReconcileCombatPlugin} lines: the NEW-LANE LAW
 * — a new telemetry concern is born as its own class, so the PRCP relay surface stays
 * thin (1 field + 9 call sites) and the 9500-nb backstop margin, paid for earlier, is not spent.
 *
 * <p><b>What it measures</b>, against the TWO real classes:
 * <ul>
 * <li><b>The A/B split keyed STRUCTURALLY, not on tick equality.</b>
 *     the earlier analysis killed same-tick co-index as the primary key: {@code lastSnapshotSimTick} is
 *     the drain's own clock and at 540 fps many render frames share one tick, so a same-tick bucket
 *     co-indexes generously. The primary key here is instead the discharging weapon's own
 *     {@code AIHints.SYSTEM} flag — a ship-system script's launcher is hint-tagged SYSTEM in
 *     {@code weapon_data.csv} ({@code flarelauncher2} {@code hints=SYSTEM}), so
 *     "was this minted by a system script" is answered by the object itself, not by a coincidence.
 *     The per-entity press co-index is kept ALONGSIDE as an explicitly-labelled UPPER BOUND
 *     ({@code press_coidx}) — and it is keyed per SOURCE SHIP, not globally, so at least the
 *     generous-tick bias is confined to that ship's own press.</li>
 * <li><b>The {@code presentedFoeProj} residual, with the earlier analysis's
 *     unwinnable-falsifier fixed.</b> {@code MissilePoseLane}'s two adds
 *     are followed by {@code engine.removeEntity(...)}, so those entries can NEVER be consumed by
 *     the {@code advance()} scan and the raw residual is structurally non-zero at zero leak. They
 *     are counted apart as {@code ephemeral}; the gradeable bar is {@code residual - ephemeral}.</li>
 * <li><b>The beam-relay mount census.</b> {@code maintainBeamPresentation} force-fires
 *     on a wire-FIRING mount whose LOCAL weapon is a beam; an index/variant mismatch would mint a
 *     weapon-backed damager on a replay-driven hull — an {@code autonomous_discharge} by
 *     construction. Both arms are censused: relayed (local beam) and skipped (local non-beam).</li>
 * <li><b>The ungraded-lane lesson.</b> The foe-system lane pressed 218 times and
 *     graded zero frames, so a duplicated activation could not be reported and the axis still read
 *     GREEN. Presses and graded frames now ride the same meter line the grader already parses.</li>
 * </ul>
 *
 * <p><b>Log cap:</b> its own {@link #AUTONOMOUS_LOG_MAX}, deliberately NOT the plugin's
 * {@code EVENT_REPLAY_LOG_MAX} — the earlier analysis showed that literal is shared by four log families
 * (both AUTONOMOUS lines, the eventReplay intake, and {@code AuthoritativePresenter} via its ctor
 * arg), so bumping it would move all of them. The legacy capped line at PRCP is left exactly as it
 * was: deleting or re-wording it retro-blinds every 07-24 reel on re-grade.
 */
final class LocalFireCensus {

    /** this lane's OWN cap; the shared EVENT_REPLAY_LOG_MAX stays 80. */
    private static final int AUTONOMOUS_LOG_MAX = 400;
    /** Distinct weapon ids kept in the histogram (a run fields far fewer; the cap is a runaway guard). */
    private static final int WEAPON_HISTOGRAM_MAX = 24;
    /** Beam-relay census rows logged per run (distinct ship+mount pairs; counters keep counting). */
    private static final int BEAM_CENSUS_LOG_MAX = 64;
    /**
     * Press co-index window, in SNAPSHOT ticks. Not a discriminator on its own:
     * it is reported as an UPPER BOUND on the press-attributable class.
     */
    private static final int PRESS_COIDX_TICKS = 8;

    private final PredictReconcileCombatPlugin plugin;

    // (a)/(c) — the discharge classification, keyed on the weapon's own SYSTEM hint.
    private int autoSystemHinted;
    private int autoNonSystem;
    private int autoSpecUnknown;
    private int autoPressCoidx;
    private int autoMissile;
    private int autoFlare;
    private int autoLogLines;
    private final java.util.LinkedHashMap<String, int[]> autoWeaponIds =
            new java.util.LinkedHashMap<String, int[]>();

    // (b) — presentedFoeProj accounting.
    private int presentedAdds;
    private int presentedAddsEphemeral;
    /** The ROUND twin of {@code presentedAddsEphemeral} —
     *  the residual it offsets is round-scoped ({@code presentedFoeProj} + {@code projSweptPresented},
     *  both cleared at the round reset), so the whole-match count over-subtracts from round 2. Cleared
     *  in {@link #resetRound}; the whole-match token stays for older reels. */
    private int presentedAddsEphemeralRound;
    private int presentedConsumed;

    // (c) second half — per-source-ship last system press tick (identity-keyed, round-cleared).
    private final java.util.IdentityHashMap<Object, Long> lastSystemPressTick =
            new java.util.IdentityHashMap<Object, Long>();

    // (d) — beam-relay per-mount census.
    private final java.util.HashSet<String> beamCensusSeen = new java.util.HashSet<String>();
    private int beamMountsRelayed;
    private int beamMountsNonBeam;
    private int beamCensusLogLines;

    LocalFireCensus(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * PRCP {@code advance()} scan hook, replacing the bare {@code presentedFoeProj.remove(p)} test:
     * same semantics (destructive consume-on-first-sight), plus the successful-consumption count
     * the census spec asked for. Returns true when {@code p} was a presenter spawn.
     */
    boolean consumePresented(Object p) {
        boolean hit = plugin.presentedFoeProj.remove(p);
        if (hit) {
            presentedConsumed++;
        }
        return hit;
    }

    /** Every {@code presentedFoeProj.add(...)}, both the PRCP arm and MissilePoseLane's two. */
    void onPresentedAdd() {
        presentedAdds++;
    }

    /**
     * An add whose entity is then removed from the engine, so its set entry can NEVER be consumed by
     * the {@code advance()} scan and lingers to the round reset.
     *
     * <p><b>Corrects the earlier analysis:</b> it reads the two MissilePoseLane removals as
     * unconditional ("call {@code removeEntity} immediately after {@code add}"). They are
     * CONDITIONAL — one fires only when the spawn is NOT a {@code MissileAPI}
     * (ballistic adopt-probe), the other only when the split child IS one (double-present guard).
     * So the ephemeral share is run-shape-dependent and cannot be derived from the add count; it has
     * to be counted at the removal itself, which is what this hook does. The residual bar is
     * therefore {@code residual - ephemeral}, with {@code ephemeral} MEASURED rather than assumed.
     */
    void onPresentedEphemeral() {
        presentedAddsEphemeral++;
        presentedAddsEphemeralRound++;
    }

    /** Both {@code ShipAPI.useSystem()} arms (apply press and cancel twin), stamped per source ship. */
    void onSystemPress(ShipAPI pressed, long simTick) {
        try {
            if (pressed != null) {
                lastSystemPressTick.put(pressed, Long.valueOf(simTick));
            }
        } catch (Throwable ignore) {
            // diagnostic only — never break the lane
        }
    }

    /**
     * The counted object, at the one site that increments {@code replayShipAutonomousDischarges}.
     * {@code seq} is that legacy counter's post-increment value, so the census line and the legacy
     * line correlate by number.
     *
     * <p>Falsifier this exists to settle: if the counted objects print
     * {@code flarelauncher*}/{@code flare_fighter} with {@code sysHint=true}, class A is confirmed;
     * if they print bolt specs with {@code sysHint=false}, it is not.
     */
    void onAutonomousDischarge(DamagingProjectileAPI p, long snapTick, int seq) {
        try {
            WeaponAPI weapon = p.getWeapon(); // non-null on this branch (the caller's null arm split it off)
            String weaponId = null;
            int burst = -1;
            boolean sysHint = false;
            boolean specSeen = false;
            if (weapon != null) {
                weaponId = weapon.getId();
                WeaponSpecAPI spec = weapon.getSpec();
                if (spec != null) {
                    specSeen = true;
                    burst = spec.getBurstSize();
                    java.util.EnumSet<WeaponAPI.AIHints> hints = spec.getAIHints();
                    sysHint = hints != null && hints.contains(WeaponAPI.AIHints.SYSTEM);
                    if (weaponId == null) {
                        weaponId = spec.getWeaponId();
                    }
                }
            }
            boolean missile = p instanceof MissileAPI;
            boolean flare = missile && (((MissileAPI) p).isFlare() || ((MissileAPI) p).isDecoyFlare());
            if (!specSeen) {
                autoSpecUnknown++;
            } else if (sysHint) {
                autoSystemHinted++;
            } else {
                autoNonSystem++;
            }
            if (missile) {
                autoMissile++;
            }
            if (flare) {
                autoFlare++;
            }
            // UPPER BOUND: tick equality is cheap, so this is reported, never graded.
            long pressAge = -1L;
            Long pressed = lastSystemPressTick.get(p.getSource());
            if (pressed != null) {
                pressAge = snapTick - pressed.longValue();
                if (pressAge >= 0L && pressAge <= PRESS_COIDX_TICKS) {
                    autoPressCoidx++;
                }
            }
            String histKey = weaponId == null ? "(null)" : weaponId;
            int[] slot = autoWeaponIds.get(histKey);
            if (slot == null && autoWeaponIds.size() < WEAPON_HISTOGRAM_MAX) {
                slot = new int[1];
                autoWeaponIds.put(histKey, slot);
            }
            if (slot != null) {
                slot[0]++;
            }
            if (autoLogLines < AUTONOMOUS_LOG_MAX) {
                autoLogLines++;
                PilotWatchLog.info("predict census autodis #" + seq
                        + " src=" + Integer.toHexString(System.identityHashCode(p.getSource()))
                        + " weapon=" + histKey
                        + " burst=" + burst
                        + " sysHint=" + sysHint
                        + " missile=" + missile
                        + " flare=" + flare
                        + " projClass=" + p.getClass().getSimpleName()
                        + " snapTick=" + snapTick
                        + " pressAge=" + pressAge
                        + (autoLogLines == AUTONOMOUS_LOG_MAX ? " [census log cap; counters continue]" : ""));
            }
        } catch (Throwable ignore) {
            // diagnostic only — never break the lane
        }
    }

    /**
     * One row per distinct (replay-driven ship, wire mount) the beam relay visits.
     * {@code relayed} = the local weapon at that index IS a beam and got the force-fire assert;
     * {@code !relayed} = wire says FIRING but the local mount is not a beam, i.e. exactly the
     * index/variant-mismatch shape that would mint a weapon-backed damager on a replica.
     */
    void onBeamRelayMount(ShipAPI ship, int mountIndex, WeaponAPI weapon, boolean relayed) {
        try {
            String key = Integer.toHexString(System.identityHashCode(ship)) + "#" + mountIndex
                    + (relayed ? "+" : "-");
            if (!beamCensusSeen.add(key)) {
                return;
            }
            if (relayed) {
                beamMountsRelayed++;
            } else {
                beamMountsNonBeam++;
            }
            if (beamCensusLogLines < BEAM_CENSUS_LOG_MAX) {
                beamCensusLogLines++;
                PilotWatchLog.info("predict census beam-mount " + (relayed ? "RELAYED" : "SKIPPED_NONBEAM")
                        + " ship=" + Integer.toHexString(System.identityHashCode(ship))
                        + " mount=" + mountIndex
                        + " weapon=" + (weapon == null ? "(null)" : weapon.getId())
                        + " type=" + (weapon == null ? "(null)" : String.valueOf(weapon.getType()))
                        + " isBeam=" + (weapon != null && weapon.isBeam())
                        + (beamCensusLogLines == BEAM_CENSUS_LOG_MAX ? " [census log cap; counters continue]" : ""));
            }
        } catch (Throwable ignore) {
            // diagnostic only — never break the lane
        }
    }

    /**
     * The census tokens, appended to the {@code predict B3 eventReplay-meter} line the axis-6 grader
     * already parses ({@code watch-verdict.ps1} reads that segment's key/value map, and
     * {@code Get-WvKv} is a name-agnostic whitespace tokenizer, so new keys ride for free).
     *
     * <p>Identity the grader checks: {@code system_hinted + nonsystem + spec_unknown ==} the legacy
     * total. Bar: {@code nonsystem == 0} (class B, the measured weapon-cycle hold-fire leak);
     * {@code system_hinted} is reported with its expected band, NOT graded to zero:
     * a literal {@code == 0} there would mint a standing AMBER until roll (II)'s press gate lands.
     */
    String meterTokens(int foeSystemApplyPresses, int foeSystemCancelPresses, int foeSystemGradedFrames) {
        int residual;
        try {
            // The set is swept every ~5 s now; the residual the watch-verdict
            // bar grades (residual - ephemeral - sourceless_adopts in 0..2) keeps its round-scoped
            // meaning by adding back the swept never-consumed entries.
            residual = plugin.presentedFoeProj.size() + plugin.projSweptPresented;
        } catch (Throwable t) {
            residual = -1;
        }
        return " predict_event_rebase_replay_ship_discharge_system_hinted=" + autoSystemHinted
                + " predict_event_rebase_replay_ship_discharge_nonsystem=" + autoNonSystem
                + " predict_event_rebase_replay_ship_discharge_spec_unknown=" + autoSpecUnknown
                + " predict_event_rebase_replay_ship_discharge_press_coidx=" + autoPressCoidx
                + " predict_event_rebase_replay_ship_discharge_missile=" + autoMissile
                + " predict_event_rebase_replay_ship_discharge_flare=" + autoFlare
                + " predict_event_rebase_presented_foe_proj_adds=" + presentedAdds
                + " predict_event_rebase_presented_foe_proj_ephemeral=" + presentedAddsEphemeral
                + " predict_event_rebase_presented_foe_proj_ephemeral_round=" + presentedAddsEphemeralRound
                + " predict_event_rebase_presented_foe_proj_consumed=" + presentedConsumed
                + " predict_event_rebase_presented_foe_proj_residual=" + residual
                + " predict_event_rebase_presented_foe_proj_residual_specs=" + residualSpecCensus()
                + " predict_event_rebase_beam_relay_mounts_relayed=" + beamMountsRelayed
                + " predict_event_rebase_beam_relay_mounts_nonbeam=" + beamMountsNonBeam
                + " predict_event_rebase_foe_system_apply_presses=" + foeSystemApplyPresses
                + " predict_event_rebase_foe_system_cancel_presses=" + foeSystemCancelPresses
                + " predict_event_rebase_foe_system_graded_frames=" + foeSystemGradedFrames
                // The press-GATE fragment (the gate owns its own tokens) plus
                // the presence-adopt launch-FX trio, both read through the plugin backref exactly
                // as the residual above is — zero new PRCP relay lines for either.
                + gateTokens()
                + modGateTokens()
                + feedGateTokens()
                + adoptFxTokens()
                + " predict_event_rebase_replay_ship_discharge_weapons=" + weaponHistogram();
    }

    /**
     * self-attribution for the residual: spec census of the entries still in
     * {@code presentedFoeProj} at flush, so the axis-6 diff bar's excess class names itself
     * from the run instead of needing a reel forensics pass (the triage exhausted every
     * offline seam: per-id consumption is client-runtime-only state). Grouped
     * {@code spec:count} pairs joined with {@code |}, count-descending, capped at 8 groups
     * (bounded output; the set itself is bounded by the run's presented adds). Spec ids are
     * engine content ids, sanitized to the single-token log law anyway; {@code -} = empty,
     * {@code ERR} = census threw (never breaks the flush).
     */
    private String residualSpecCensus() {
        try {
            java.util.Map<String, Integer> bySpec = new java.util.TreeMap<String, Integer>();
            for (Object o : plugin.presentedFoeProj) {
                String spec;
                if (o instanceof DamagingProjectileAPI) {
                    spec = ((DamagingProjectileAPI) o).getProjectileSpecId();
                    if (spec == null) {
                        spec = "null_spec";
                    }
                } else {
                    spec = o == null ? "null" : o.getClass().getSimpleName();
                }
                StringBuilder clean = new StringBuilder(spec.length());
                for (int i = 0; i < spec.length() && i < 48; i++) {
                    char ch = spec.charAt(i);
                    clean.append((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                            || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.' || ch == '-'
                            ? ch : '_');
                }
                String key = clean.length() == 0 ? "empty" : clean.toString();
                Integer n = bySpec.get(key);
                bySpec.put(key, n == null ? 1 : n + 1);
            }
            if (bySpec.isEmpty()) {
                return "-";
            }
            java.util.List<java.util.Map.Entry<String, Integer>> ordered =
                    new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(bySpec.entrySet());
            java.util.Collections.sort(ordered,
                    new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
                        @Override
                        public int compare(java.util.Map.Entry<String, Integer> a,
                                java.util.Map.Entry<String, Integer> b) {
                            return b.getValue().compareTo(a.getValue());
                        }
                    });
            StringBuilder out = new StringBuilder();
            int groups = 0;
            for (java.util.Map.Entry<String, Integer> e : ordered) {
                if (groups == 8) {
                    out.append("|more");
                    break;
                }
                if (groups > 0) {
                    out.append('|');
                }
                out.append(e.getKey()).append(':').append(e.getValue());
                groups++;
            }
            return out.toString();
        } catch (Throwable t) {
            return "ERR";
        }
    }

    /** The {@link SpawnSystemGate} fragment; {@code -1} values if it throws. */
    private String gateTokens() {
        try {
            return plugin.spawnGate.meterTokens();
        } catch (Throwable t) {
            return " predict_event_rebase_foe_system_spawn_press_gate=unknown"
                    + " predict_event_rebase_gate_skipped_presses=-1"
                    + " predict_event_rebase_gate_skipped_seeds=-1"
                    + " predict_event_rebase_foe_system_gated_frames=-1"
                    + " predict_event_rebase_gate_skipped_teleport_seeds=-1";
        }
    }

    /** The {@link SpawnHullmodGate} fragment; {@code -1} values if it throws. */
    private String modGateTokens() {
        try {
            return plugin.spawnModGate.meterTokens();
        } catch (Throwable t) {
            return " predict_event_rebase_foe_spawn_hullmod_gate=unknown"
                    + " predict_event_rebase_hullmod_suppressed_bodies=-1"
                    + " predict_event_rebase_hullmod_suppressed_mods=-1"
                    + " predict_event_rebase_hullmod_verify_failures=-1"
                    + " predict_event_rebase_hullmod_gate_throws=-1";
        }
    }

    /** The {@link DeployFeedGate} fragment; {@code -1} values if it throws. */
    private String feedGateTokens() {
        try {
            return plugin.feedGate.meterTokens();
        } catch (Throwable t) {
            return " predict_event_rebase_deploy_feed_mute=unknown"
                    + " predict_event_rebase_feed_muted_spawns=-1"
                    + " predict_event_rebase_feed_passed_spawns=-1"
                    + " predict_event_rebase_feed_restore_failures=-1"
                    + " predict_event_rebase_feed_gate_throws=-1";
        }
    }

    /**
     * The presence-adopt launch-FX trio, read off {@link MissilePoseLane}'s own
     * counters (the lane owns the FX; the census owns the grader-parsed line). Bar once the gate is
     * live: {@code flash == sound} on every recent flare adopt, the {@code presentFireFx} identity.
     */
    private String adoptFxTokens() {
        int flash;
        int sound;
        int skipped;
        try {
            flash = plugin.missileLane.adoptFxFlash();
            sound = plugin.missileLane.adoptFxSound();
            skipped = plugin.missileLane.adoptFxSkipped();
        } catch (Throwable t) {
            flash = -1;
            sound = -1;
            skipped = -1;
        }
        return " predict_event_rebase_adopt_fx_flash=" + flash
                + " predict_event_rebase_adopt_fx_sound=" + sound
                + " predict_event_rebase_adopt_fx_skipped=" + skipped;
    }

    /** {@code id:n,id:n} — single-token, comma-separated so the whitespace tokenizer keeps it whole. */
    private String weaponHistogram() {
        if (autoWeaponIds.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, int[]> e : autoWeaponIds.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue()[0]);
        }
        return sb.toString();
    }

    /** Per-round clear, beside the plugin's own {@code presentedFoeProj.clear()}. */
    void resetRound() {
        lastSystemPressTick.clear();
        beamCensusSeen.clear();
        presentedAddsEphemeralRound = 0;
    }
}
