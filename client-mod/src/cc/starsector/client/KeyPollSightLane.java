package cc.starsector.client;

import com.fs.starfarer.api.input.InputEventAPI;

/**
 * F9 no-row forensics — D-F9NOROW. DEBUG matches only,
 * bounded, observe-never-consume.
 *
 * <p>WHY: a test flight took an operator F9 and wrote NO {@code owner-marker} row, NO
 * floaty and NO wire-MARK, while the plugin's poll site is byte-for-byte the one that registered
 * markers on every earlier flight (the last one: 3 markers). A silent miss has three
 * shapes and the sidelog could not tell them apart; this lane makes the NEXT press self-explaining:
 * <ol>
 *   <li>the key never reaches the plugin's event list ⇒ no {@code first-sight value=67} row;</li>
 *   <li>something upstream CONSUMED the key-down ⇒ {@code consumed key-down #n} rows (the value is
 *       unreadable: the engine throws on consumed reads);</li>
 *   <li>it arrives unconsumed and the marker branch itself fails ⇒ {@code first-sight value=67}
 *       with no {@code owner-marker} row.</li>
 * </ol>
 *
 * <p>Engine law: {@code isKeyboardEvent()},
 * {@code isKeyDownEvent()} and {@code isConsumed()} carry NO consumed-guard; {@code getEventValue()},
 * {@code isRepeat()} and {@code getEventType()} throw {@code "Attempt to access data of a consumed
 * InputEvent"}. Repeats are the distinct {@code KEY_REPEAT} type, so {@code isKeyDownEvent()} already
 * excludes them.
 *
 * <p>Bounds: at most {@value #FIRST_SIGHT_MAX} distinct key values get a first-sight row per process;
 * consumed key-downs log at #1 and every {@value #CONSUMED_LOG_EVERY}-th; zero rows (and zero
 * allocation) outside a debug-flagged match. Own file per NEW-LANE-LAW (PRCP is past its WarnNb).
 */
final class KeyPollSightLane {

    /** Distinct key values that get a first-sight row (per process). */
    static final int FIRST_SIGHT_MAX = 32;
    /** Consumed key-down rows: the first, then every N-th. */
    static final int CONSUMED_LOG_EVERY = 32;

    private final int[] seen = new int[FIRST_SIGHT_MAX];
    private int seenCount;
    int consumedKeyDowns;

    /**
     * Observe one event at the plugin's key-poll site. Never consumes, never throws, no-op outside
     * a debug match.
     */
    void see(InputEventAPI event, boolean debugMatch, long tick) {
        if (!debugMatch || event == null) {
            return;
        }
        try {
            if (!event.isKeyDownEvent()) { // guard-free on consumed events
                return;
            }
            if (event.isConsumed()) {
                consumedKeyDowns++;
                if (consumedKeyDowns == 1 || consumedKeyDowns % CONSUMED_LOG_EVERY == 0) {
                    PilotWatchLog.info("key-poll consumed key-down #" + consumedKeyDowns
                            + " tick=" + tick);
                }
                return;
            }
            final int value = event.getEventValue();
            for (int i = 0; i < seenCount; i++) {
                if (seen[i] == value) {
                    return;
                }
            }
            if (seenCount < FIRST_SIGHT_MAX) {
                seen[seenCount++] = value;
                PilotWatchLog.info("key-poll first-sight value=" + value + " tick=" + tick);
            }
        } catch (Throwable t) {
            // fail-soft: a diagnostic never breaks the frame
        }
    }
}
