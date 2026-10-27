package cc.starsector.client.voice;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure push-to-talk key law. Default keys:
 * proximity = SPACE (LWJGL 57), global = B (48); sysprops {@code -Dcc.voice.keyProx} /
 * {@code -Dcc.voice.keyGlobal} (LWJGL ints) override, read ONCE at construction.
 *
 * <p>WHY a game-free class: the render thread feeds key events in, the capture thread reads
 * {@link #mode()} / {@link #burstId()} / {@link #takeBurstStart()} — three atomics, no locks, and
 * the whole consume/transition table is unit-tested ({@code VoiceClientLawSelftest}).
 *
 * <p>Laws:
 * <ul>
 * <li>Space-consume: while the match session is live AND the chat box is closed AND no picker is
 *     open, the mod CONSUMES Space press AND release (the F11 event-handled precedent) so the
 *     engine never toggles vanilla pause. B is never consumed (nothing in the engine polls it).</li>
 * <li>Presses are honoured only under that same gate (chat open ⇒ Space/B are typing); releases
 *     are ALWAYS honoured so a key that went down before the chat opened cannot stick.</li>
 * <li>Latest press wins: pressing the second key while the first is held switches the mode and
 *     starts a fresh burst; releasing the ACTIVE key while the other is still held hands the mode
 *     back to that key as a fresh burst (its proximity flag differs, so listeners must reset);
 *     releasing the inactive key is a no-op.</li>
 * <li>{@code burstId} is u8, increments on every burst start (wraps 255 → 0); the first frame of
 *     a burst carries {@code FLAG_BURST_START}, delivered through {@link #takeBurstStart()} exactly
 *     once per burst.</li>
 * <li>A repeated press of a key already held is a no-op (LWJGL key-repeat safe).</li>
 * <li>Lost key-up: the render thread polls the physical key state while a mode
 *     is engaged ({@link #pollHeld}); a held key the keyboard reports UP is released as if its
 *     key-up had arrived, so a swallowed up event (alt-tab, focus loss) never sticks the mic.</li>
 * <li>If both sysprops resolve to the same code, that key is proximity and global is unreachable
 *     (documented, never a crash).</li>
 * </ul>
 */
public final class VoicePtt {
    /** LWJGL {@code KEY_SPACE}. */
    public static final int DEFAULT_KEY_PROX = 57;
    /** LWJGL {@code KEY_B}. */
    public static final int DEFAULT_KEY_GLOBAL = 48;

    public static final int MODE_OFF = 0;
    public static final int MODE_GLOBAL = 1;
    public static final int MODE_PROXIMITY = 2;

    private final int keyProx;
    private final int keyGlobal;
    private final AtomicInteger mode = new AtomicInteger(MODE_OFF);
    private final AtomicInteger burstId = new AtomicInteger(0);
    private final AtomicBoolean burstStartPending = new AtomicBoolean(false);
    private boolean proxHeld;
    private boolean globalHeld;
    /** The probe injector's synthetic global hold (no physical key behind it). */
    private boolean probeHeld;

    public VoicePtt(int keyProx, int keyGlobal) {
        this.keyProx = keyProx;
        this.keyGlobal = keyGlobal;
    }

    /** Keys from {@code cc.voice.keyProx} / {@code cc.voice.keyGlobal} (ints; malformed ⇒ default). */
    public static VoicePtt fromSysprops() {
        return new VoicePtt(intProp("cc.voice.keyProx", DEFAULT_KEY_PROX),
                intProp("cc.voice.keyGlobal", DEFAULT_KEY_GLOBAL));
    }

    /** Public so the presence lane's liveness poll binds the SAME keys (F1). */
    public static int intProp(String key, int dflt) {
        String raw = System.getProperty(key);
        if (raw == null) {
            return dflt;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 && v < 256 ? v : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public int keyProx() {
        return keyProx;
    }

    public int keyGlobal() {
        return keyGlobal;
    }

    /** 0 off / 1 global / 2 proximity — read by the capture thread. */
    public int mode() {
        return mode.get();
    }

    /** Current burst id (u8). */
    public int burstId() {
        return burstId.get() & 0xFF;
    }

    /** True exactly once per burst: the capture side calls this on the frame it is about to send. */
    public boolean takeBurstStart() {
        return burstStartPending.getAndSet(false);
    }

    /** Peek without consuming (selftest / HUD). */
    public boolean burstStartPending() {
        return burstStartPending.get();
    }

    /**
     * Render-thread key event. Returns true when the event must be CONSUMED (Space only, under
     * the live-and-not-typing gate); B is never consumed.
     */
    public synchronized boolean onKey(int keyCode, boolean down, boolean sessionLive,
            boolean chatOpen, boolean pickerOpen) {
        boolean isProx = keyCode == keyProx;
        boolean isGlobal = !isProx && keyCode == keyGlobal;
        if (!isProx && !isGlobal) {
            return false;
        }
        boolean gate = sessionLive && !chatOpen && !pickerOpen;
        if (down) {
            if (!gate) {
                return false;
            }
            if (isProx) {
                if (!proxHeld) {
                    proxHeld = true;
                    startBurst(MODE_PROXIMITY);
                }
            } else {
                if (!globalHeld) {
                    globalHeld = true;
                    startBurst(MODE_GLOBAL);
                }
            }
        } else {
            if (isProx && proxHeld) {
                proxHeld = false;
                if (mode.get() == MODE_PROXIMITY) {
                    if (globalHeld) {
                        startBurst(MODE_GLOBAL);
                    } else {
                        stop();
                    }
                }
            } else if (isGlobal && globalHeld) {
                globalHeld = false;
                if (mode.get() == MODE_GLOBAL) {
                    if (proxHeld) {
                        startBurst(MODE_PROXIMITY);
                    } else {
                        stop();
                    }
                }
            }
        }
        return isProx && gate;
    }

    /**
     * Render-thread liveness poll: a key this law still holds that the physical
     * keyboard no longer reports DOWN lost its key-up event (alt-tab / focus loss / ESC menu swallow
     * the LWJGL up event) — it is released here exactly as a late key-up would be (hand-back to the
     * other held key included), so the mic can never stay open past the physical release. A key
     * still down, or one this law does not hold, is untouched. Cheap: call only while
     * {@link #mode()} is not 0.
     */
    public synchronized void pollHeld(boolean proxDown, boolean globalDown) {
        if (proxHeld && !proxDown) {
            onKey(keyProx, false, true, false, false);
        }
        if (globalHeld && !globalDown) {
            onKey(keyGlobal, false, true, false, false);
        }
    }

    /**
     * The probe injector ({@code ProbeToneSource}) holds GLOBAL synthetically —
     * engage = a fresh burst (burstId + FLAG_BURST_START, the encoder/decoder reset pair), release
     * = stop. Immune to {@link #pollHeld} (there is no physical key to report UP) and idempotent
     * per frame; a physical key is never affected. Capture thread (inside the source's read).
     */
    public synchronized void probeHold(boolean on) {
        if (on && !probeHeld) {
            probeHeld = true;
            startBurst(MODE_GLOBAL);
        } else if (!on && probeHeld) {
            probeHeld = false;
            if (mode.get() == MODE_GLOBAL && !globalHeld) {
                stop();
            }
        }
    }

    /** Session end / teardown: drop every held key and stop talking. */
    public synchronized void releaseAll() {
        proxHeld = false;
        globalHeld = false;
        probeHeld = false;
        stop();
    }

    private void startBurst(int newMode) {
        burstId.set((burstId.get() + 1) & 0xFF);
        burstStartPending.set(true);
        mode.set(newMode);
    }

    private void stop() {
        mode.set(MODE_OFF);
        burstStartPending.set(false);
    }
}
