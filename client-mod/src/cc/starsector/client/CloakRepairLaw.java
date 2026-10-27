package cc.starsector.client;

/**
 * Foe cloak KEYFRAME-REPAIR lag law: a fresh keyframe never rewinds a cloak the local engine
 * already advanced across a tier boundary.
 *
 * <p>WHY: the
 * {@link FoeCloakLane#cloakAction} table returned {@code KF_REPAIR} on EVERY fresh keyframe whose
 * tier state differed from the twin's, and the repair site does {@code forceState(auth, p)}. The
 * v10 tier rides the entity keyframe (E=15, hold-last between), so on a tier boundary (IN→ACTIVE,
 * ACTIVE→OUT) the RTT-old keyframe still names the state the local engine just LEFT: local ACTIVE,
 * wire IN ⇒ {@code forceState(IN)} re-plays the IN progress; the next keyframe says ACTIVE ⇒
 * {@code forceState(ACTIVE)} — a visible re-cloak strobe on every boundary jitter. Model:
 * {@link FoeVentWindowLaw} ("seen in window" memory, the local tail never restarted).
 *
 * <p>The law: if the wire's differing state is one the LOCAL cloak HELD at some point inside the
 * last {@link #LAG_WINDOW_TICKS}, the wire is merely behind the local engine — SKIP (counted; the
 * foe-cloak disagree meter is untouched, so the divergence stays visible). If the local never
 * held it recently the divergence is real (local started late / the server cancelled) — repair on
 * that first keyframe, exactly as before (no latency: a skip is lag detection, never a wait).
 *
 * <p>Memory = a chronological ring (oldest first, cap {@link #RING_CAP}) of local state
 * TRANSITIONS {@code (entry tick, state ordinal)}; an entry's state was held from its tick until
 * the NEXT entry's tick (the newest entry = the current state, held until now). "Held inside the
 * window" is judged on the EXIT tick, not the entry tick — a long ACTIVE entered 300 t ago and
 * left 5 t ago is still the state an RTT-old keyframe names (the ACTIVE→OUT half of the strobe).
 * Client-side by the client-derivation rule (zero wire). Caller: {@code FoeCloakLane.maintainFoeCloak}.
 * Smoke: {@code CloakRepairLawSmoke}.
 */
final class CloakRepairLaw {

    /**
     * The RTT tolerance cap, in server sim ticks — the same 1 s bound the fire lanes pair across
     * ({@code FireLaneCoordinator.dischargeMatchTolTicks()} = round(1.0 * ticksPerSecond) = 60 at
     * the default rate). A keyframe older than this vs the local
     * transition is no longer "the wire is behind", it is a divergence.
     */
    static final long LAG_WINDOW_TICKS = 60L;

    /** Transition ring capacity; a cloak cycle is IDLE→IN→ACTIVE→OUT→COOLDOWN→IDLE (5), 8 covers one plus slack. */
    static final int RING_CAP = 8;

    private CloakRepairLaw() {
    }

    /**
     * The repair verdict (replaces the bare {@code tierFresh && tierStateDiffers} of
     * {@link FoeCloakLane#cloakAction}'s first row).
     */
    static boolean repairDue(boolean tierFresh, boolean tierStateDiffers,
            boolean wireStateSeenLocallyInWindow) {
        return tierFresh && tierStateDiffers && !wireStateSeenLocallyInWindow;
    }

    /**
     * True when the local cloak HELD {@code wireState} at some tick in
     * {@code [nowTick - LAG_WINDOW_TICKS, nowTick]}. {@code states}/{@code ticks} are the first
     * {@code n} entries of the chronological ring (oldest first); entry i was held until
     * {@code ticks[i+1]}, the newest until {@code nowTick}.
     *
     */
    static boolean seenInWindow(int[] states, long[] ticks, int n, int wireState, long nowTick) {
        for (int i = 0; i < n; i++) {
            if (states[i] != wireState) {
                continue;
            }
            long exit = i + 1 < n ? ticks[i + 1] : nowTick;
            if (nowTick - exit <= LAG_WINDOW_TICKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Append a transition to the chronological ring when {@code state} differs from the newest
     * entry (a steady frame writes nothing); a full ring evicts its OLDEST entry by shift.
     * Returns the new count.
     */
    static int record(int[] states, long[] ticks, int n, int state, long tick) {
        if (n > 0 && states[n - 1] == state) {
            return n;
        }
        if (n == states.length) {
            System.arraycopy(states, 1, states, 0, n - 1);
            System.arraycopy(ticks, 1, ticks, 0, n - 1);
            n--;
        }
        states[n] = state;
        ticks[n] = tick;
        return n + 1;
    }
}
