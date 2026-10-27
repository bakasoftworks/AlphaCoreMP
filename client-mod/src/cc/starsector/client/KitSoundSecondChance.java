package cc.starsector.client;

/**
 * Kit sound second chance ("Fix B"): when the engine's one-shot sound init
 * failed at startup, retry it ONCE at application load with an ABSOLUTE {@code org.lwjgl.librarypath}.
 * <p>
 * WHY: LWJGL 2.9.3 tries {@code org.lwjgl.librarypath\OpenAL64.dll} FIRST and the game never sets
 * that property, so on a box whose DLL search excludes the working directory every candidate the
 * engine tries is relative or missing (the joiner: LoadLibrary 126 on a present dll, while
 * lwjgl64.dll — loaded by absolute path — is fine). join-cc.bat now sets the property on every
 * launch (Fix A); this class is the in-process backstop for a kit started some other way, and the
 * ONE sidelog row that says which state the game's sound was in (the debrief zip ships it).
 * No {@code java.io.File} here — B-denied; the separator rides the system property.
 */
final class KitSoundSecondChance {
    private KitSoundSecondChance() { }

    static void run() {
        try {
            String lp = System.getProperty("org.lwjgl.librarypath");
            if (SoundReinitAdapter.isCreated()) {
                PilotWatchLog.info("onApplicationLoad: sound second chance: skipped (game sound is up) librarypath="
                        + (lp == null ? "unset" : lp));
                return;
            }
            if (!SoundReinitAdapter.isEnabled()) {
                // The engine never attempted init (sound OFF in the launcher/settings) or switched sound off
                // itself after an init error (its own alert + proceed-without-sound path): nothing to retry,
                // and no per-frame listener call runs while the flag is off — no crash class here.
                PilotWatchLog.info("onApplicationLoad: sound second chance: skipped (sound is off in the game's settings - "
                        + "no init to retry) librarypath=" + (lp == null ? "unset" : lp));
                return;
            }
            String set = "";
            if (lp == null || lp.isEmpty()) {
                String sep = System.getProperty("file.separator");
                lp = System.getProperty("user.dir") + sep + "native" + sep + "windows";
                try {
                    System.setProperty("org.lwjgl.librarypath", lp);
                    set = " (set here)";
                } catch (Throwable t) {
                    set = " (set FAILED " + t.getClass().getSimpleName() + ")";
                }
            }
            long t0 = System.nanoTime();
            String verdict;
            try {
                SoundReinitAdapter.reinit();
                verdict = SoundReinitAdapter.isCreated() ? "reinit OK (device+listener; sfx/music may stay muted - the engine's sound flag stays off)" : "reinit FAIL (create returned without a device)";
            } catch (Throwable t) {
                verdict = "reinit FAIL " + t.getClass().getName() + ": " + FallbackAnnounce.sanitize(String.valueOf(t.getMessage()), 200);
            }
            PilotWatchLog.info("onApplicationLoad: sound second chance: " + verdict + " "
                    + ((System.nanoTime() - t0) / 1000000L) + " ms librarypath=" + lp + set);
        } catch (Throwable t) {
            PilotWatchLog.info("onApplicationLoad: sound second chance: adapter FAIL " + t.getClass().getName()
                    + ": " + FallbackAnnounce.sanitize(String.valueOf(t.getMessage()), 200));
        }
    }
}
