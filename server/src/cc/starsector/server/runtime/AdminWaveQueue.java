package cc.starsector.server.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import cc.starsector.server.match.WaveSchedule;

/**
 * The door-owned hand-off of admin {@code /wave skip|pause} verbs to
 * the serve loop. The reader thread deposits an {@link OperatorCommandFile.Command} per
 * admin-authorized verb; the serve loop drains ONCE per tick at the operator poll and feeds the
 * result through the SAME {@code switch} the house operator file rides (one semantic for the
 * F11/F12 hotkeys, the GUI, the operator file and the chat verb — zero duplication).
 *
 * <p>Laws: bounded (cap {@value #CAP} on RECEIPTED deposits, drop-oldest-receipted — a wave verb
 * is idempotent-ish and stale ones are the least useful; the dropped count is reported for the
 * status line; the silent seat-vacate clears are never capped, see {@link #offer}), own monitor
 * (never the door lock — the serve loop must not contend with the reader for a queue pop),
 * arguments cross only door-validated (the door already gated on the session's admin bit and
 * canonicalized any id, so nothing here is peer-controlled beyond an allowlisted choice) — ONE
 * exception, under a DEBUG WATCH only: non-admin seats deposit receipted {@code /teleport}s
 * through this same cap, so two or more seats bursting their chat bucket in one tick can evict
 * an admin's receipted entry (the admin hears the drop receipt; ops-serve never widens).
 * The
 * deposit reason is logged by the door ({@code admin_wave}), the effect by the serve loop
 * ({@code operator_skip_wave} / {@code operator_pause_wave} — the same rows the operator file
 * produces).
 *
 * <p>Return leg: a chat-deposited wave verb carries its
 * {@code Command.receiptSessionId}; the serve loop's switch mails the drained verb's REAL
 * effect back to that session as the chat reply ({@code sendAdminReceipt}) — the door sends
 * no inline reply for these verbs, so the ONE line the admin sees is the sim-thread truth
 * (resulting state + effect timing, the D5 reply law). The receipt TEXTS live here as pure
 * builders so contract rows pin them beside the queue they ride.
 */
public final class AdminWaveQueue {

    /** RECEIPTED deposits held between two serve-loop drains (one tick); more = drop the oldest
     * receipted one. Silent ({@code receiptSessionId == 0}) deposits do not count against it. */
    public static final int CAP = 8;

    /** Refusal receipts — the serve has no wave lane (non-wave shape) or no skip hook; the
     * serve-log refusal row keeps the precise machine reason, the receipt states the outcome. */
    public static final String SKIP_UNAVAILABLE = "wave skip unavailable on this serve (no wave lane)";
    public static final String PAUSE_UNAVAILABLE = "wave pause unavailable on this serve (no wave lane)";
    public static final String CATEGORY_UNAVAILABLE = "wave category unavailable on this serve (no wave lane)";

    /** D5 receipt: the resulting pause state (owner #1 — ON/OFF must be in the reply). */
    public static String pauseReceipt(boolean pausedNow) {
        return pausedNow ? "wave pause ON - wave timer frozen" : "wave pause OFF - wave timer running";
    }

    /** Receipt nit: the kill-branch sweep delay is
     * FORMATTED from {@link WaveSchedule#SKIP_HULK_SWEEP_DELAY_SEC}, never a hard-coded "~1 s"
     * beside the constant. Integral values print bare ("1"), so the pinned text
     * (AdminKickCommandContractRows) stays byte-identical at the current 1.0f. */
    private static final String SKIP_SWEEP_DELAY_TEXT =
            WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC == (long) WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC
                    ? Long.toString((long) WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC)
                    : Float.toString(WaveSchedule.SKIP_HULK_SWEEP_DELAY_SEC);

    /** D5 receipt: what the skip actually did (mirrors {@code operator_skip_wave}'s branches).
     * The kill branch names the corpse sweep + clear hold now gating the next
     * wave, and the new hold branch covers killed=0 while the hold counts (dead ShardSpawner
     * parents sit invulnerable through their child delay — the force leg refuses there, and a
     * "nothing to skip" reply would read as a dead button, the F11-hammer). */
    public static String skipReceipt(int killed, boolean forcedNext, boolean clearHoldCounting) {
        if (killed > 0) {
            return "wave skip: killed " + killed
                    + " - corpses sweep in ~" + SKIP_SWEEP_DELAY_TEXT
                    + " s, next wave after the clear hold";
        }
        if (forcedNext) {
            return "wave skip: field empty - next wave forced due";
        }
        if (clearHoldCounting) {
            return "wave skip: field already clearing - next wave rides the clear hold";
        }
        return "wave skip: nothing to skip (no wave staged or left)";
    }

    /** The category-switch receipt: the category is STICKY ({@code retargetNextWave}
     * grafts ALL remaining waves — the mid-match switch is intended), so the old
     * "next wave will be" undersold it and stated no way back (an operator couldn't stop
     * the omega spam). Pinned: AdminKickCommandContractRows + run-cat-serve S-REPLY grep. */
    public static String categoryReceipt(String category, int wavesLeft) {
        return "waves from now on: " + category + " (" + wavesLeft
                + " left) - /wave remnant switches back";
    }

    /** Refusal receipts for the two new verbs (the CATEGORY_UNAVAILABLE law:
     * the serve has no wave lane; the serve-log row keeps the machine reason). */
    public static final String SET_UNAVAILABLE = "wave set unavailable on this serve (no wave lane)";
    public static final String ENDLESS_UNAVAILABLE = "endless unavailable on this serve (no wave lane)";
    /** {@code /endless on} beside the per-clear reset lane — the boot-time
     * contradiction throw ({@code cc.match.endless=true} vs {@code cc.server.waveClearRoundEnd=true})
     * made live as a refusal; the dial stays where it was. */
    public static final String ENDLESS_REFUSED_RESET_LANE = "endless on refused: this serve runs the"
            + " per-clear reset lane (cc.server.waveClearRoundEnd=true)";

    /** The RESET variant of the category receipt — the graft plus
     * {@code WaveSchedule.restartChain}: the new faction's opener is due at the very next drain
     * (index 0 is timer-armed and that timer elapsed long ago), on top of whatever lives.
     *
     * <p>Why reset goes to wave 2, not wave 1: operator numbering
     * is the SLOT name — wave 1 IS the descriptor's t=0 field, the ships already on the map at match
     * start, never scheduled ({@code WaveSchedule.setNextWave} javadoc) — so the chain's first row
     * is wave 2 and a reset restarts at the first SCHEDULED wave. The receipt now says so itself.
     * The way-back tail is spent to buy it: {@code ChatFrameCodec.TEXT_MAX_CHARS} is 120 and
     * {@code validateChatText} THROWS over it — at the longest category ({@code sindrian_diktat})
     * this text is 110 chars, the tail-keeping variant was 138. The non-reset
     * {@link #categoryReceipt} still names {@code /wave remnant}. */
    public static String categoryResetReceipt(String category, int waves) {
        return "waves from now on: " + category + " - restarted at the opener, wave 2 (" + waves
                + " waves; wave 1 = the match-start field)";
    }

    /** {@code /wave set N} landed — the operator-numbered wave now next and WHEN
     * ({@code WaveSchedule.setNextWave}: the opener rides its elapsed timer, any later wave the
     * clear leg — the field must clear and hold first). */
    public static String setReceipt(int wave, String category) {
        return wave == 2
                ? "wave set: " + category + " wave 2 (the opener) spawns at the next timer check"
                : "wave set: " + category + " wave " + wave + " spawns once the field clears and holds";
    }

    /** {@code /wave set N} past the chain's last wave — refused, never clamped. */
    public static String setRefusedReceipt(int wave, int lastWave, String category) {
        return "wave set refused: " + category + " has no wave " + wave + " (last = " + lastWave + ")";
    }

    /** The endless-dial state (bare {@code /endless}) or the state just set. */
    public static String endlessReceipt(boolean endless, boolean justSet) {
        return "endless " + (endless ? "ON" : "OFF") + (justSet ? " (set)" : "")
                + (endless ? " - the chain wraps after its last clear"
                        : " - the last clear ends the round");
    }

    /** {@code /wave inflate} on a serve with no wave lane or no engine half
     * (the boot row names the reason) — refused, the CATEGORY_UNAVAILABLE law. */
    public static final String WAVE_INFLATE_UNAVAILABLE =
            "wave inflate unavailable on this serve (no wave lane or no engine half)";

    /** The wave loadout-variety dial's state (bare {@code /wave inflate}) or the
     * state just set; {@code registered} = the wave VDEFs this serve has minted so far. */
    public static String waveInflateReceipt(boolean on, int registered, boolean justSet) {
        return "wave inflate " + (on ? "ON" : "OFF") + (justSet ? " (set)" : "")
                + (on ? " - wave hulls field vanilla-autofitted loadouts"
                        : " - wave hulls field their stock variants")
                + " (" + registered + " minted)";
    }

    private final ArrayDeque<OperatorCommandFile.Command> pending =
            new ArrayDeque<OperatorCommandFile.Command>(CAP);
    private long dropped;
    /** Receipted entries currently in {@link #pending}
     * (the {@link #CAP} counter); reset by {@link #drain}. */
    private int receipted;

    /** Reader thread — the widened form: a fully-built command ({@code SPAWN_FOE}
     * carries {@code "<variant> <count>"} in text; the id is already door-canonicalized against
     * the boot catalogue, so nothing peer-controlled crosses beyond an allowlisted id).
     * Returns the EVICTED command on a cap overflow (else null) — D5 review: a dropped chat
     * deposit would otherwise lose its receipt silently; the door mails the eviction notice.
     *
     * <p>kind-aware. A SILENT deposit
     * ({@code receiptSessionId == 0} — the door's seat-vacate clears
     * {@code AdminSeatDebugLane.silentGodModeOff} / {@code SeatTeamLane.silentClear}) is NEVER
     * capped, never evicted and never evicts: it is de-duplicated by (verb, seatId) among the
     * silent entries (the older is removed, the new appended — both are idempotent clears, so
     * the latest intent is kept), which bounds the silent tail at 2 x seats. Receipted entries
     * cap at {@link #CAP} with the OLDEST RECEIPTED evicted ({@link #dropped} counts only those).
     * One deque: deposit order is preserved across both kinds.
     *
     * <p>Why structural (no door-side belt): the silent clears are deposited by
     * {@code queueGodModeOffLocked} UNDER the door {@code lock}; an eviction there mailed its
     * receipt under that lock, i.e. {@code lock} → reliability core {@code ctrlSend} → (reader
     * thread: core → delivery callback) → {@code lock} — the ABBA inversion (recon r4).
     * A silent offer now returns null by construction, so the door never mails under the lock
     * from this path. */
    public synchronized OperatorCommandFile.Command offer(OperatorCommandFile.Command command) {
        if (command.receiptSessionId() == 0L) {
            pending.removeIf(p -> p.receiptSessionId() == 0L && p.verb() == command.verb()
                    && p.seatId() == command.seatId());
            pending.addLast(command);
            return null;
        }
        OperatorCommandFile.Command evicted = null;
        if (receipted >= CAP) {
            evicted = removeOldestReceipted();
            if (evicted != null) {
                dropped++;
                receipted--;
            }
        }
        pending.addLast(command);
        receipted++;
        return evicted;
    }

    /** Unlink the oldest receipted entry (silent ones
     * are skipped and keep their slot); null when none is held. Caller holds the monitor. */
    private OperatorCommandFile.Command removeOldestReceipted() {
        java.util.Iterator<OperatorCommandFile.Command> it = pending.iterator();
        while (it.hasNext()) {
            OperatorCommandFile.Command c = it.next();
            if (c.receiptSessionId() != 0L) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    /** Serve loop, once per tick: everything deposited since the last drain, deposit order.
     * Also resets the receipted-cap count. */
    public synchronized OperatorCommandFile.ParseResult drain() {
        if (pending.isEmpty()) {
            return OperatorCommandFile.ParseResult.EMPTY;
        }
        List<OperatorCommandFile.Command> commands =
                new ArrayList<OperatorCommandFile.Command>(pending);
        pending.clear();
        receipted = 0;
        return new OperatorCommandFile.ParseResult(List.copyOf(commands), List.of());
    }

    /** Receipted deposits lost to the cap since boot (status-line counter; silent deposits are
     * never dropped). */
    public synchronized long dropped() {
        return dropped;
    }
}
