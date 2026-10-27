package cc.starsector.client;

import java.io.PrintWriter;

/**
 * The SM "passenger probe" (far-end-black-box B1's single live
 * unknown): can MOD code hold a file sink open across combat and keep
 * writing to it, the way log4j's pre-opened appender stream does? The answer prices the B1 black-box
 * ring's core premise before any of it is built.
 *
 * <p><b>Why {@code new PrintWriter(String)} specifically.</b> The script classloader {@code B} is a
 * STATIC NAME blocklist (measured on the real {@code B}): {@code java.io.FileWriter} /
 * {@code FileOutputStream} are denied to any B-parented mod class on <em>every</em> request (cold or
 * warm, any phase), but {@code java.io.PrintWriter} is whitelisted — and the
 * {@code PrintWriter(String fileName)} constructor opens the underlying {@code FileOutputStream}
 * inside bootstrap-loaded code the mod loader never sees. Whether that seam actually holds — the open
 * at application load, per-write during combat, and a FRESH open mid-combat — is exactly what this
 * probe measures; every outcome (including a denied open) is the datum. This source must reference NO
 * {@code java.io} name outside {@code B}'s whitelist ({@code PrintWriter} only; failures are caught as
 * {@code Throwable}, never as {@code FileNotFoundException} — that name is blocklisted).
 *
 * <p>Fail-safe: OFF unless {@code cc.predict.smProbePath} / {@code CC_PREDICT_SM_PROBE_PATH} names a
 * sink (ordinary launches never arm it); force-loaded at application load (classloader-B pre-warm
 * pattern); every step catches {@code Throwable}; a probe failure can never touch the
 * WATCH. Findings ride {@link PilotWatchLog} ({@code sm-probe:} lines) AND the sink file itself —
 * post-run, {@code phase=combat} lines in the file are the ground truth that combat-time writes
 * landed on disk.
 */
final class SmPassengerProbe {

    /** Combat write cadence in advance() frames (~10 s at 60 fps; ~18 lines per 180 s round). */
    private static final int WRITE_EVERY_TICKS = 600;

    private static volatile String path;          // null = probe OFF
    private static volatile PrintWriter sink;     // pre-opened at application load; null = OFF or open failed
    private static volatile String openOutcome = "off";
    private static volatile String freshOpenOutcome = "not-tried";
    private static int combatTicks;               // main combat thread only
    private static int combatWrites;
    private static int combatWriteErrors;
    private static boolean writeErrorLogged;

    private SmPassengerProbe() {
    }

    /**
     * Pre-open the sink at application load ({@link CcClientModPlugin#onApplicationLoad()}) — the
     * pre-open half of the B1 question. Resolves {@code cc.predict.smProbePath} then
     * {@code CC_PREDICT_SM_PROBE_PATH}; unset = OFF (one log line, nothing else).
     */
    static void openAtApplicationLoad() {
        try {
            String raw = System.getProperty("cc.predict.smProbePath");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PREDICT_SM_PROBE_PATH");
            }
            if (raw == null || raw.isBlank()) {
                PilotWatchLog.info("sm-probe: OFF (cc.predict.smProbePath / CC_PREDICT_SM_PROBE_PATH unset)");
                return;
            }
            path = raw.trim();
            try {
                PrintWriter opened = new PrintWriter(path);
                opened.println("sm-probe open phase=app-load epochMs=" + System.currentTimeMillis());
                opened.flush();
                openOutcome = opened.checkError() ? "open-ok-write-error" : "open-ok";
                sink = opened;
            } catch (Throwable t) {
                openOutcome = "open-DENIED " + t.getClass().getName() + ": " + t.getMessage();
            }
            PilotWatchLog.info("sm-probe: app-load open " + openOutcome + " path=" + path);
        } catch (Throwable t) {
            PilotWatchLog.error("sm-probe: openAtApplicationLoad failed (probe disabled, WATCH unaffected)", t);
        }
    }

    /**
     * Per-frame combat hook ({@link PredictReconcileCombatPlugin#advance}) — live combat frames only.
     * Writes to the pre-opened sink on the first frame and every {@value #WRITE_EVERY_TICKS} after;
     * the first frame also tries a FRESH open ({@code <path>.fresh}) for the contrast datum (does
     * ANYTHING still block a combat-time open once no blocklisted class load is involved?).
     */
    static void onCombatTick() {
        if (path == null) {
            return; // OFF — the common case; one null check per frame
        }
        try {
            combatTicks++;
            boolean first = combatTicks == 1;
            if (sink != null && (first || combatTicks % WRITE_EVERY_TICKS == 0)) {
                sink.println("sm-probe write phase=combat tick=" + combatTicks
                        + " epochMs=" + System.currentTimeMillis());
                sink.flush();
                if (sink.checkError()) {
                    combatWriteErrors++;
                    if (!writeErrorLogged) {
                        writeErrorLogged = true;
                        PilotWatchLog.info("sm-probe: combat write reported checkError=true at tick " + combatTicks);
                    }
                } else {
                    combatWrites++;
                }
            }
            if (first) {
                try {
                    PrintWriter fresh = new PrintWriter(path + ".fresh");
                    fresh.println("sm-probe fresh-open phase=combat tick=" + combatTicks
                            + " epochMs=" + System.currentTimeMillis());
                    fresh.flush();
                    freshOpenOutcome = fresh.checkError() ? "open-ok-write-error" : "open-ok";
                    fresh.close();
                } catch (Throwable t) {
                    freshOpenOutcome = "open-DENIED " + t.getClass().getName() + ": " + t.getMessage();
                }
                PilotWatchLog.info(describe());
            } else if (combatTicks % 3600 == 0) {
                PilotWatchLog.info(describe()); // ~once a minute; 3 lines in a 180 s round
            }
        } catch (Throwable t) {
            path = null; // disarm on any unexpected failure — the WATCH always outranks the probe
            PilotWatchLog.error("sm-probe: onCombatTick failed (probe disarmed, WATCH unaffected)", t);
        }
    }

    /** One greppable summary line for the debrief ({@code rg "sm-probe:" starsector.log}). */
    static String describe() {
        return "sm-probe: open=" + openOutcome + " combat_ticks=" + combatTicks
                + " combat_writes=" + combatWrites + " write_errors=" + combatWriteErrors
                + " fresh_open_in_combat=" + freshOpenOutcome + " path=" + path;
    }
}
