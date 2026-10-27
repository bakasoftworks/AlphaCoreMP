package cc.starsector.client.voice;

import java.util.function.Consumer;

import com.fs.starfarer.api.SettingsAPI;

/**
 * The ONE game-API touch of the voice settings: read/write
 * {@code saves/common/cc_client_settings.data} through the engine's common-file API — the only
 * classloader-B-legal file path (no {@code java.io.File}; the B-deny gate would refuse it). Kept
 * out of {@link VoiceClientSettings} so that value stays game-free and shareable with the joiner
 * window (whose JRE has no API jar). Every failure = one log line, never a throw: the settings
 * are a nicety, the match is not.
 *
 * <p>Security: the file name is FIXED — never wire- or user-derived; the
 * text written is {@link VoiceClientSettings#serialize} (printable ASCII by construction); the
 * text read passes the bounded parser.
 */
public final class VoiceSettingsStore {
    private VoiceSettingsStore() {
    }

    /**
     * Read the common file, then apply {@code -Dcc.voice.*} sysprops. Any failure (missing file,
     * IO error, sandbox refusal) ⇒ defaults + sysprops and one log line.
     */
    public static VoiceClientSettings load(SettingsAPI settings, Consumer<String> log) {
        VoiceClientSettings base = VoiceClientSettings.DEFAULTS;
        String text = null;
        try {
            if (settings != null && settings.fileExistsInCommon(VoiceClientSettings.FILE_NAME)) {
                text = settings.readTextFileFromCommon(VoiceClientSettings.FILE_NAME);
            }
        } catch (Exception e) {
            say(log, "voice settings: read of " + VoiceClientSettings.DISK_FILE_NAME + " failed ("
                    + e.getClass().getSimpleName() + "), using defaults");
        }
        if (text != null) {
            base = VoiceClientSettings.load(text);
        }
        return VoiceClientSettings.withSysprops(base, System::getProperty);
    }

    /** Write {@code s.serialize()} to the common file; false + one log line on any failure. */
    public static boolean save(SettingsAPI settings, VoiceClientSettings s, Consumer<String> log) {
        if (settings == null || s == null) {
            return false;
        }
        try {
            settings.writeTextFileToCommon(VoiceClientSettings.FILE_NAME, s.serialize());
            return true;
        } catch (Exception e) {
            say(log, "voice settings: write of " + VoiceClientSettings.DISK_FILE_NAME + " failed ("
                    + e.getClass().getSimpleName() + "), picks apply this match only");
            return false;
        }
    }

    private static void say(Consumer<String> log, String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
