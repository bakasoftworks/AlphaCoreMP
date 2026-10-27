package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.WaveSchedule;
import java.io.IOException;
import static cc.starsector.server.runtime.PersistentPilotIntentCombatServer.*;

/**
 * The admin wave-shape verb surface: the {@code /wave} + {@code /endless} family
 * ({@code skip} / {@code pause} / {@code <category> [reset]} / {@code set N} / {@code endless} /
 * {@code inflate}) and the adhoc {@code /spawnfoe} + {@code /spawnfriend} arm.
 *
 * <p>Carved from
 * {@code PilotServeSession.handleOperatorCommand}; bodies verbatim; the {@code CustomVariantOps}
 * idiom — one helper owns the semantics so a WARN-tier file stays thin. ZERO behaviour change:
 * every row still prints, every receipt still mails, and each {@code return} below ends the
 * command exactly as it did inside the switch (the switch was that method's only statement, so
 * "leave the lane" == "next command"). Package-private: no consumer outside this package.
 *
 * <p>Every input is final for the serve and assigned before {@code mintMatchScope()} returns, so
 * the lane exists before any press can reach it ({@code handleOperatorCommand} is only reached
 * from {@code serveFrame}). The one mutable slot pair is the skip verb's deferred corpse sweep,
 * which {@code PilotServeSession} resets per round and drains per tick — hence the public
 * {@link SweepTicks} holder rather than two private longs.
 */
final class AdminWaveVerbs {

    /** The lane's serve-scope inputs (the params-object law: an 11-arg ctor lands with a
     * holder). {@code waveScheduleOrNull} / {@code waveInflaterOrNull} carry their nullity in
     * the name — every arm re-checks them exactly as the old case bodies did. */
    record Inputs(PilotServeConfig config, NPeerHooks hooks, MatchDescriptor descriptor,
            double fortressDpTotal, double[] fortressWaveScale, WaveSchedule waveScheduleOrNull,
            WaveLoadoutInflater waveInflaterOrNull, AdhocFoeSpawner adhocFoes, float mapHalf,
            int effectiveTickRate, boolean waveClearRoundEndKnob) {
    }

    /** The skip verb's deferred corpse-sweep slots (-1 = unarmed).
     * Round-scoped: {@code mintRound} re-seeds both, the wave bracket in {@code serveFrame}
     * drains them. */
    static final class SweepTicks {
        long due = -1L;
        long followUp = -1L;
    }

    final SweepTicks sweep = new SweepTicks();

    private final PilotServeConfig config;
    private final NPeerHooks hooks;
    private final MatchDescriptor npeerDescriptor;
    private final double fortressDpTotal;
    private final double[] fortressWaveScale;
    private final WaveSchedule waveSchedule;
    private final WaveLoadoutInflater waveInflaterOrNull;
    private final AdhocFoeSpawner adhocFoes;
    private final float mapHalf;
    private final int effectiveTickRate;
    private final boolean waveClearRoundEndKnob;

    AdminWaveVerbs(Inputs in) {
        this.config = in.config();
        this.hooks = in.hooks();
        this.npeerDescriptor = in.descriptor();
        this.fortressDpTotal = in.fortressDpTotal();
        this.fortressWaveScale = in.fortressWaveScale();
        this.waveSchedule = in.waveScheduleOrNull();
        this.waveInflaterOrNull = in.waveInflaterOrNull();
        this.adhocFoes = in.adhocFoes();
        this.mapHalf = in.mapHalf();
        this.effectiveTickRate = in.effectiveTickRate();
        this.waveClearRoundEndKnob = in.waveClearRoundEndKnob();
    }

    /**
     * The six wave-shape arms, dispatched from {@code handleOperatorCommand}'s switch. Bodies
     * verbatim; {@code lastSimTick} is the caller's field, passed in so the {@code + 1} reads
     * (this tick) exactly as it did on the sim thread.
     */
    void handle(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            int round, int frame, long lastSimTick) throws IOException {
        switch (command.verb()) {
            // Owner fast-testing verb: kill the live wave
            // field through the engine's own damage lane (deaths ride the
            // normal wire machinery; next capture's clear observation then
            // advances the schedule by itself), and when the field is
            // ALREADY empty with waves left — the pre-w2 window where the
            // clear leg is structurally dead — force the next
            // wave due instead. Refusals log + ignore (operator file
            // content is fail-soft, the freeze law).
            case SKIP_WAVE -> {
                if (waveSchedule == null || hooks.waveSkip() == null) {
                    System.out.println("npeer-serve skip_wave_refused reason="
                            + (waveSchedule == null
                                    ? "no_wave_schedule" : "no_hook")
                            + " round=" + round + " frame=" + frame
                            + " (skip-wave is a wave-shape serve verb)");
                    waveReceipt(door, command, AdminWaveQueue.SKIP_UNAVAILABLE);
                    return;
                }
                // A wave mid-spread is skipped WHOLE — drop the
                // unreleased tail BEFORE the kill sweep so this tick's
                // drain cannot trickle it into the emptied field.
                int dropped = waveSchedule.dropPendingRows();
                int killed = hooks.waveSkip()
                        .killSideHulls(waveSchedule.waveSide());
                boolean forced = false;
                if (killed == 0 && !waveSchedule.drained()) {
                    // Refuses while the clear hold counts — a
                    // hulk-only field (invulnerable ShardSpawner parents)
                    // reads killed=0 and pre-fix force-spawned THROUGH
                    // the hold (the F11-hammer runaway).
                    forced = waveSchedule.forceNextDue();
                }
                if (killed > 0) {
                    // Arm the deferred corpse sweep — fires
                    // before the 2-3 s ShardSpawner child delay, after
                    // the death fan sighted the corpses (constant's
                    // javadoc carries both bounds).
                    long freshSweepDue = lastSimTick + 1
                            + Math.round(WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC
                                    * effectiveTickRate);
                    if (sweep.due < 0L) { // The carved holder, was waveSkipHulkSweepDueTick
                        sweep.due = freshSweepDue;
                    } else {
                        // Keep the earlier due tick;
                        // this press's corpses ride the follow-up slot.
                        sweep.followUp = freshSweepDue;
                    }
                }
                System.out.println("npeer-serve operator_skip_wave killed="
                        + killed + " dropped_pending=" + dropped
                        + " forced_next=" + forced
                        + " drained=" + waveSchedule.drained()
                        + " hold_armed=" + waveSchedule.clearHoldArmed()
                        + " sweep_due_tick=" + sweep.due
                        + " round=" + round + " frame=" + frame
                        + (killed > 0
                                ? " (deaths ride this tick's advance; corpse"
                                        + " sweep + clear hold gate the next wave)"
                                : forced
                                        ? " (empty field: next wave forced"
                                                + " due at this tick's drain)"
                                        : waveSchedule.clearHoldArmedOrPending()
                                                ? " (field clearing: the hold"
                                                        + " owns the window, force refused)"
                                                : " (nothing to skip: field empty and"
                                                        + " schedule drained/unarmed)"));
                waveReceipt(door, command,
                        AdminWaveQueue.skipReceipt(killed, forced,
                                waveSchedule.clearHoldArmedOrPending()));
            }
            // F12 wave-pause toggle (clock frozen).
            case PAUSE_WAVE -> {
                if (waveSchedule == null) {
                    System.out.println("npeer-serve pause_wave_refused"
                            + " reason=no_wave_schedule round=" + round
                            + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.PAUSE_UNAVAILABLE);
                    return;
                }
                boolean pausedNow = waveSchedule.togglePaused();
                System.out.println("npeer-serve operator_pause_wave paused="
                        + pausedNow + " round=" + round
                        + " frame=" + frame);
                waveReceipt(door, command, AdminWaveQueue.pauseReceipt(pausedNow));
            }
            // "/wave <kind>" retargets the next wave:
            // apply IMMEDIATELY — the unspawned tail
            // becomes the new faction at its existing trigger
            // (WaveSchedule.retargetNextWave; the round-boundary
            // park is gone; the roster bound folds the heaviest category at
            // boot, so no clamp is needed here). Both mint sites validate
            // the category; the contains-guard keeps a future unvalidated
            // source from ever reaching forShape's throwing parse mid-serve
            // (fail-soft lane, the freeze law). A same-category switch is
            // an identical-list graft — same honest receipt.
            case SET_WAVE_CATEGORY -> {
                // Text = "<cat>" (STAY, the graft alone) or
                // "<cat> reset" (RESET = graft + restartChain). Both mint sites
                // validate; the guards below keep a future unvalidated source
                // out of forShape's throwing parse (fail-soft lane).
                String[] words = command.text().split(" ", -1);
                String category = words[0];
                boolean reset = words.length == 2 && "reset".equals(words[1]);
                if (waveSchedule == null || words.length > 2
                        || (words.length == 2 && !reset)
                        || !WaveSchedule.WAVE_CATEGORIES.contains(category)) {
                    System.out.println("npeer-serve wave_category_refused"
                            + " reason=" + (waveSchedule == null
                                    ? "no_wave_schedule" : "unknown_category")
                            + " round=" + round + " frame=" + frame);
                    // unknown_category is file-only (chat grammar validates)
                    // — the no-lane text fits every receipt-bearing arrival.
                    waveReceipt(door, command,
                            AdminWaveQueue.CATEGORY_UNAVAILABLE);
                    return;
                }
                String wasCategory = waveSchedule.category();
                int wavesLeft = waveSchedule.retargetNextWave(
                        WaveSchedule.forShape(npeerDescriptor.shape(),
                                (int) Math.round(1.0 / config.fixedDt()),
                                fortressDpTotal, fortressWaveScale,
                                hooks.fortressDp(), category));
                if (reset) {
                    // RESET: index 0 is timer-armed, so the new opener fields
                    // at the next drain on top of whatever lives (hulls are
                    // never un-spawned — /wave skip clears them).
                    wavesLeft = waveSchedule.restartChain();
                }
                if (waveInflaterOrNull != null) {
                    // The switch PARKS the retired chain's never-fielded
                    // bodies and ARMS a per-tick budgeted warm walk over the new chain (warmStep in
                    // the wave bracket below) — the press frame no longer walks the whole chain on
                    // the sim thread (that press was the match's worst frame). The
                    // opener a RESET stages this same tick pays its own rows at stage (the miss law).
                    waveInflaterOrNull.retarget(waveSchedule);
                }
                System.out.println("npeer-serve operator_wave_category"
                        + " category=" + waveSchedule.category()
                        + " was=" + wasCategory
                        + " reset=" + reset
                        + " waves_left=" + wavesLeft
                        + " scheduled_hulls=" + waveSchedule.totalHullCount()
                        + " round=" + round + " frame=" + frame
                        // Operator numbering = slot names — wave 1 is the
                        // descriptor's t=0 field (never scheduled), so the opener is wave 2.
                        + (reset ? " (chain restarted at the opener - wave 2; wave 1 = the"
                                + " descriptor's t=0 field, never scheduled)"
                                : wavesLeft == 0
                                ? " (chain drained - shows from the next round)"
                                : " (unspawned tail grafted in place)"));
                // Admiral: the per-category admiral
                // posture follows the switch (Shrouded = fight to the last).
                if (hooks.waveSpawn() != null) {
                    hooks.waveSpawn().wavePosture(waveSchedule.category());
                }
                waveReceipt(door, command, reset
                        ? AdminWaveQueue.categoryResetReceipt(category, wavesLeft)
                        : AdminWaveQueue.categoryReceipt(category, wavesLeft));
            }
            // /wave set N — operator-numbered wave N spawns
            // next (WaveSchedule.setNextWave: the opener on its elapsed timer,
            // any later wave on the clear leg). Past the chain = refused with
            // a receipt, never clamped.
            case SET_WAVE_NUMBER -> {
                String digit = command.text();
                boolean wellFormed = digit.length() == 1
                        && digit.charAt(0) >= '1' && digit.charAt(0) <= '9';
                if (waveSchedule == null || !wellFormed) {
                    System.out.println("npeer-serve wave_set_refused reason="
                            + (waveSchedule == null ? "no_wave_schedule" : "malformed")
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.SET_UNAVAILABLE);
                    return;
                }
                int wanted = digit.charAt(0) - '0';
                int lastWave = waveSchedule.waveCount() + 1;
                try {
                    int next = waveSchedule.setNextWave(wanted);
                    System.out.println("npeer-serve operator_wave_set wanted=" + wanted
                            + " next=" + next + " category=" + waveSchedule.category()
                            + " last_wave=" + lastWave
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command,
                            AdminWaveQueue.setReceipt(next, waveSchedule.category()));
                } catch (IllegalArgumentException pastTheChain) {
                    System.out.println("npeer-serve wave_set_refused reason=past_last_wave"
                            + " wanted=" + wanted + " last_wave=" + lastWave
                            + " category=" + waveSchedule.category()
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.setRefusedReceipt(
                            wanted, lastWave, waveSchedule.category()));
                }
            }
            // /endless [on|off] — the F1 dial, live. "show" =
            // the state receipt; ON beside the per-clear reset lane is the
            // boot-time contradiction throw made a refusal (the dial holds).
            case SET_ENDLESS -> {
                if (waveSchedule == null) {
                    System.out.println("npeer-serve endless_refused reason=no_wave_schedule"
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.ENDLESS_UNAVAILABLE);
                    return;
                }
                String word = command.text();
                if ("show".equals(word)) {
                    waveReceipt(door, command,
                            AdminWaveQueue.endlessReceipt(waveSchedule.endless(), false));
                    return;
                }
                boolean on = "on".equals(word);
                if (on && waveClearRoundEndKnob) {
                    System.out.println("npeer-serve endless_refused"
                            + " reason=wave_clear_round_end_lane"
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.ENDLESS_REFUSED_RESET_LANE);
                    return;
                }
                boolean was = waveSchedule.endless();
                waveSchedule.setEndless(on);
                // The `matchEndless` mirror field is gone (a resolve()-local now) —
                // the schedule has been the only reader of this state.
                System.out.println("npeer-serve operator_endless endless=" + on
                        + " was=" + was + " round=" + round + " frame=" + frame);
                waveReceipt(door, command, AdminWaveQueue.endlessReceipt(on, true));
            }
            // /wave inflate [on|off] — the wave loadout-variety dial,
            // live. "show" = the state receipt; ON walks the live chain through the
            // engine NOW (the operator's own press pays the boot-class cost, the
            // stage ticks read the cache); OFF leaves fielded ids registered (a
            // switch never un-registers) and stages stock rows from here on.
            case SET_WAVE_INFLATE -> {
                if (waveInflaterOrNull == null || waveSchedule == null) {
                    System.out.println("npeer-serve wave_inflate_refused reason="
                            + (waveSchedule == null ? "no_wave_schedule" : "no_lane")
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.WAVE_INFLATE_UNAVAILABLE);
                    return;
                }
                String word = command.text();
                if ("show".equals(word)) {
                    // The HOUSE twin's `show` has no chat receipt to land in
                    // (receiptSessionId 0) — the state row is its only witness (rig-graded).
                    System.out.println("npeer-serve wave_inflate_state enabled="
                            + waveInflaterOrNull.enabled() + " registered="
                            + waveInflaterOrNull.registeredCount()
                            + " parked=" + waveInflaterOrNull.parkedCount()
                            + " warm_rows_left=" + waveInflaterOrNull.warmRowsLeft()
                            + " cap=" + waveInflaterOrNull.registerCap()
                            + " round=" + round + " frame=" + frame);
                    waveReceipt(door, command, AdminWaveQueue.waveInflateReceipt(
                            waveInflaterOrNull.enabled(), waveInflaterOrNull.registeredCount(), false));
                    return;
                }
                boolean on = "on".equals(word);
                boolean was = waveInflaterOrNull.enabled();
                waveInflaterOrNull.setEnabled(on);
                if (on && !was) {
                    // A budgeted warm walk, never an inline chain walk.
                    waveInflaterOrNull.armWarm(waveSchedule);
                }
                System.out.println("npeer-serve operator_wave_inflate enabled=" + on
                        + " was=" + was + " registered=" + waveInflaterOrNull.registeredCount()
                        + " round=" + round + " frame=" + frame);
                waveReceipt(door, command, AdminWaveQueue.waveInflateReceipt(
                        on, waveInflaterOrNull.registeredCount(), true));
            }
            // No `default` arm — the caller's switch fans exactly the six labels
            // above here, and an unreachable throw would be a new fatal path (the freeze law).
        }
    }

    // Adhoc foe spawn — text = the normalized
    // "<variant> <count>" BOTH depositors mint (operator parseLine
    // validates grammar; the door additionally canonicalized the
    // id against the boot catalogue). ENQUEUE ONLY — the (c2c)
    // drain spawns on the wave stagger's leftover budget inside
    // the pending-add-before-advance window. Refusals log +
    // ignore (the freeze law); no waveSpawn hook = no spawn lane.
    // Refusals + queue-full drops also mail the
    // depositing chat session an IMMEDIATE receipt (previously
    // silent to the admin); the spawn outcome itself mails at
    // entry exhaustion from the (c2c) drain.
    // SPAWN_FRIEND = this arm
    // VERBATIM with side 0 (the players' side) — the verb IS the
    // side; the row names + receipt nouns say which lane fired.
    void spawn(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            int round, int frame) {
        boolean spawnFriendly = command.verb()
                == OperatorCommandFile.Verb.SPAWN_FRIEND;
        int spawnSide = spawnFriendly ? AdhocFoeSpawner.SIDE_FRIEND
                : AdhocFoeSpawner.SIDE_FOE;
        String spawnRow = spawnFriendly ? "spawn_friend" : "spawn_foe";
        String spawnNoun = spawnFriendly ? "friendly" : "foe";
        if (hooks.waveSpawn() == null) {
            waveReceipt(door, command,
                    AdhocFoeSpawner.SPAWN_UNAVAILABLE);
            System.out.println("npeer-serve " + spawnRow + "_refused"
                    + " reason=no_hook text=" + command.text()
                    + " round=" + round + " frame=" + frame);
            return;
        }
        String[] spawnParts = command.text().split(" ");
        int spawnCount = -1;
        // Optional LAST token `@x,y` — the
        // grammar re-validates (digits-only, bounded), then the
        // MAP clamp: outside ±(map half + 1000) refuses loud (a
        // hull placed past the border would be an instant
        // out-of-bounds withdraw, not a foe).
        // Text = "<variant> <count>[ officer][ @x,y]"
        // (both depositors' ONE shape) — the pose is peeled first,
        // then the FIXED lowercase `officer` keyword, then exactly
        // two tokens must remain.
        float[] spawnPose = null;
        String spawnPoseText = "-";
        boolean spawnPoseMalformed = false;
        int spawnEnd = spawnParts.length;
        if (spawnEnd >= 3 && spawnParts[spawnEnd - 1].startsWith("@")) {
            spawnPose = ChatCommands.parseSpawnPoseOrNull(
                    spawnParts[spawnEnd - 1]);
            if (spawnPose == null) {
                spawnPoseMalformed = true;
            } else if (!AdhocFoeSpawner.poseInsideMap(spawnPose,
                    mapHalf)) {
                waveReceipt(door, command,
                        spawnNoun + " spawn refused (pose outside map)");
                System.out.println("npeer-serve " + spawnRow + "_refused"
                        + " reason=pose_outside_map text="
                        + command.text() + " map_half=" + mapHalf
                        + " round=" + round + " frame=" + frame);
                return;
            } else {
                spawnPoseText = spawnParts[spawnEnd - 1].substring(1);
            }
            spawnEnd--;
        }
        boolean spawnOfficer = false;
        if (spawnEnd >= 3 && ChatCommands.isOfficerKeyword(spawnParts[spawnEnd - 1])) { // a trailing officer keyword
            spawnOfficer = true;
            spawnEnd--;
        }
        if (spawnEnd == 2 && !spawnPoseMalformed) {
            try {
                int parsedCount = Integer.parseInt(spawnParts[1]);
                spawnCount = parsedCount >= 1 && parsedCount
                        <= ChatCommands.SPAWNFOE_MAX_COUNT
                                ? parsedCount : -1;
            } catch (NumberFormatException malformed) {
                spawnCount = -1;
            }
        }
        if (spawnCount < 1) {
            // Defensive only — both depositors validated upstream
            // (the receipt keeps a code drift honest to chat).
            waveReceipt(door, command,
                    spawnNoun + " spawn refused (malformed)");
            System.out.println("npeer-serve " + spawnRow + "_refused"
                    + " reason=malformed_text text=" + command.text()
                    + " round=" + round + " frame=" + frame);
            return;
        }
        int spawnDropped = adhocFoes.enqueue(spawnParts[0],
                spawnCount, command.receiptSessionId(), spawnPose,
                spawnSide, spawnOfficer);
        if (spawnDropped > 0) {
            waveReceipt(door, command,
                    AdhocFoeSpawner.queueFullReceipt(spawnParts[0],
                            spawnCount - spawnDropped, spawnCount,
                            spawnFriendly));
        }
        System.out.println("npeer-serve operator_" + spawnRow
                + " variant=" + spawnParts[0] + " count=" + spawnCount
                + " side=" + spawnSide + " officer=" + spawnOfficer
                + " pose=" + spawnPoseText
                + " queued=" + adhocFoes.pendingCount()
                + " round=" + round + " frame=" + frame);
    }
}
