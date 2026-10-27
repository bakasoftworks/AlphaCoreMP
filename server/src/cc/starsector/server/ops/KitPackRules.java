package cc.starsector.server.ops;

import cc.starsector.server.match.MatchShapes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The joiner-kit packer's PURE rules (no I/O): every number, entry name and text the kit packer
 * ({@link KitPacker}, {@link KitZip}) obeys, in ONE home so the Java packer and the pwsh roll it
 * replaces cannot fork. WHY Java owns
 * the texts: the SAME
 * packer re-packs the release kit on a package host ({@code zip:} mode) and builds the kit in the
 * repo roll and the exported source ({@code dir:} mode), so the bytes a joiner's {@code join-cc.bat}
 * vguard reads ({@code kit-manifest.txt}) and the README come from one function, byte-exact to
 * the manifest and README steps of the pwsh kit packer they were ported from.
 *
 * <p>Value domains: every size is a non-negative byte count ({@code long}); counts are non-negative
 * {@code int}s; the stamp is the LOCAL-time {@code yyyyMMdd-HHmmss} token the roll mints
 * ({@code Get-Date -Format 'yyyyMMdd-HHmmss'}); a shape slug is {@code [a-z0-9-]{1,40}}.
 *
 *
 * The variant keep-list's row grammar and its R13 text.
 */
public final class KitPackRules {

    private KitPackRules() {
    }

    private static final long MIB = 1024L * 1024L;

    // ---------------------------------------------------------------- the numbers

    /** The release kit zip read cap: the real kit is 39.75 MB zipped; past 256 MiB it is not one. */
    public static final long MAX_INPUT_BYTES = 256L * MIB;
    /** Entries in one kit zip: the real kit has 236 (4 of them directories); past 4096 it is not a kit. */
    public static final int MAX_ENTRIES = 4096;
    /** The inflated total held in memory for one re-pack (the real kit inflates to 56.75 MB). */
    public static final long MAX_INFLATED_BYTES = 512L * MIB;
    /** One entry's inflated size (the largest real entry is the jlink'd JRE's modules image). */
    public static final long MAX_ENTRY_BYTES = 128L * MIB;
    /** The per-entry inflated : compressed ratio past which an entry is a zip bomb, not kit content. */
    public static final int MAX_RATIO = 200;
    /** The data surface ({@code mod_info.json} + {@code data/**}) digested for {@code data=}, held in memory. */
    public static final long MAX_SURFACE_BYTES = 32L * MIB;
    /** A match descriptor read cap = the ONE cap every descriptor read shares. */
    public static final int MAX_MATCH_TXT_BYTES = MatchShapes.MAX_DESCRIPTOR_BYTES;
    /** A {@code .fingerprint} sidecar read cap (the real sidecar is ~1.7 KB; file= rows grow with the data tree). */
    public static final int MAX_SIDECAR_BYTES = 1024 * 1024;
    /** The free-space headroom on the kits volume above {@link #KITS_PER_PACK} x the input size (R9). */
    public static final long FREE_HEADROOM_BYTES = 64L * MIB;
    /** Kits one pack writes (the "count" of the free-space rule; seat kits, the only count above 1, are ruled out). */
    public static final int KITS_PER_PACK = 1;
    /** The shape slug in a kit file name: a library stem ({@code match-<name>}) or {@code match}. */
    public static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,40}");
    /** Files the vanilla census walk visits under the game's {@code data/variants/**} (0.98a ships far fewer). */
    public static final int MAX_VANILLA_FILES = 20000;
    /** The pack ceiling in seconds: under the console page's 35 s POST abort ({@code OpsConsoleJsCore}). */
    public static final int CEILING_S = 30;
    /** The MANIFEST {@code pack.kit=} name grammar: one plain file name, never a path. */
    public static final Pattern PACK_KIT = Pattern.compile("[A-Za-z0-9._-]{1,80}\\.zip");
    /** The kit stamp grammar = the roll's {@code yyyyMMdd-HHmmss} (passes the bat's vguard charset). */
    public static final Pattern STAMP = Pattern.compile("[0-9]{8}-[0-9]{6}");
    /** Unresolved roster ids an R7 text (and stale keep-list ids an R13 text) names at most. */
    public static final int MAX_CENSUS_IDS_SHOWN = 6;
    /**
     * One id row of the {@code dir:} pack's variant keep-list file ({@code keepVariants=}): a variant id is a file
     * name stem, so the row grammar is a plain name (no path, no separator, never a placeholder char); anything else
     * is a usage refusal before the pack reads the stage.
     *
     */
    public static final Pattern KEEP_VARIANT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    /** The chars of a refused entry name an R4 text shows at most. */
    public static final int MAX_ENTRY_CHARS_SHOWN = 80;
    /** Every kit zip entry's time: {@code 2026-01-01T00:00:00} (UTC wall clock) = the jar pin of
     *  {@code client-mod/build.ps1} ({@code jar --date}), so two packs of the same entries are byte-equal. */
    public static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    // ---------------------------------------------------------------- the kit's entry names

    /** The kit's mod folder (the zip's top-level folder a joiner copies into {@code mods\}). */
    public static final String MOD_DIR = "CC_Client";
    /**
     * The match file a PRE-WIRE kit carried. A kit carries none now (the match rides the server's own
     * sidecar and the wire), so the packer composes no such entry and drops it from a base kit; the
     * package-folder reader refuses a kit that still holds one.
     *
     */
    public static final String MATCH_ENTRY = MOD_DIR + "/match.txt";
    /**
     * The kit's file list: the packer-minted fingerprint sidecar (its {@code file=} rows = the client's digest
     * surface, its {@code fingerprint=} row = the kit's CONTENT composite, no match term), the
     * {@code <descriptor>.fingerprint} the packer writes beside the SOURCE descriptor for the door with that one
     * term stripped (the two lists otherwise byte-equal). Named for the kit, not the match: the list belongs to
     * the kit's files while the match comes from the server; not {@code kit.fingerprint}, the manifest's own row
     * name.
     *
     */
    public static final String SIDECAR_NAME = "kit.files";
    /** The kit entry of {@link #SIDECAR_NAME}. */
    public static final String SIDECAR_ENTRY = MOD_DIR + "/" + SIDECAR_NAME;
    /** The pack-stamped manifest {@code join-cc.bat} hard-stops against (its vguard). */
    public static final String MANIFEST_ENTRY = MOD_DIR + "/kit-manifest.txt";
    /** The joiner README at the zip root (Markdown: GitHub and editors render it; it reads as plain text too). */
    // The kit README ships as README.md
    public static final String README_ENTRY = "README.md";
    /** The client-mod jar whose md5 is the composite's {@code jar=} and the manifest's {@code jarMd5=}. */
    public static final String JAR_ENTRY = MOD_DIR + "/jars/cc-client-mod.jar";
    /** The bundled JRE's shipped stamp ({@code implementorVersion=} feeds the README's JRE block). */
    public static final String JRE_STAMP_ENTRY = MOD_DIR + "/jre-cc/cc-kit-jre.txt";

    private static final String SURFACE_MOD_INFO = "mod_info.json";
    private static final String SURFACE_DATA_PREFIX = "data/";
    private static final String SURFACE_EXCLUDED = "data/missions/mission_list.csv";

    /**
     * The mint's digest-surface key of a kit entry, or null when the entry is outside the surface:
     * {@code CC_Client/mod_info.json} and every file under {@code CC_Client/data/} EXCEPT
     * {@code CC_Client/data/missions/mission_list.csv}, keyed relative to {@code CC_Client/} =
     * {@code ContentFingerprintTree.readContentSurface}'s walk (the rule the {@code data=} component and
     * the sidecar's {@code file=} rows were minted under). The README, {@code kit-manifest.txt},
     * {@code match.txt}, the sidecar, {@code jars/} and {@code jre-cc/} are OUT by construction.
     */
    public static String surfaceKeyOrNull(String entryName) {
        if (entryName == null || !entryName.startsWith(MOD_DIR + "/")) {
            return null;
        }
        String rel = entryName.substring(MOD_DIR.length() + 1);
        if (rel.equals(SURFACE_MOD_INFO)) {
            return rel;
        }
        return rel.startsWith(SURFACE_DATA_PREFIX) && !rel.equals(SURFACE_EXCLUDED) ? rel : null;
    }

    /**
     * The leak law: a match password, an admin password, the door credentials
     * file and every {@code -tokens} twin NEVER enter a kit. Matched on the last path segment,
     * ASCII-case-folded (a Windows extraction does not tell {@code X.PASSWORD} from {@code x.password}).
     */
    public static boolean isLeakName(String path) {
        if (path == null) {
            return false;
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return leaf.endsWith(".password") || leaf.endsWith(".adminpassword")
                || leaf.equals("cc-door-credentials.properties") || leaf.contains("-tokens");
    }

    // ---------------------------------------------------------------- names

    /** A fresh kit stamp in LOCAL time, the roll's {@code Get-Date -Format 'yyyyMMdd-HHmmss'}. */
    public static String freshStamp(Clock clock) {
        return LocalDateTime.now(clock).format(STAMP_FORMAT);
    }

    /** {@code cc-joiner-kit-<stamp>-<slug>.zip}; THROWS on a stamp or slug outside its grammar. */
    public static String kitFileName(String stamp, String slug) {
        if (stamp == null || !STAMP.matcher(stamp).matches()) {
            throw new IllegalArgumentException("kit file name: the stamp is not yyyyMMdd-HHmmss");
        }
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("kit file name: the shape slug is not [a-z0-9-]{1,40}");
        }
        return "cc-joiner-kit-" + stamp + "-" + slug + ".zip";
    }

    /** The slug of a descriptor file name ({@code match-bastion-duel.txt} = {@code match-bastion-duel}), or null. */
    public static String slugOf(String descriptorFileName) {
        if (descriptorFileName == null || !descriptorFileName.endsWith(".txt")) {
            return null;
        }
        String stem = descriptorFileName.substring(0, descriptorFileName.length() - ".txt".length());
        return SLUG.matcher(stem).matches() ? stem : null;
    }

    // ---------------------------------------------------------------- kit-manifest.txt

    private static final String CRLF = "\r\n";
    private static final Pattern MD5_HEX = Pattern.compile("[0-9a-fA-F]{32}");

    /**
     * {@code kit-manifest.txt}, byte-exact to {@code package-joiner-kit.ps1:398-408}: five rows
     * ({@code kitStamp}, {@code jarMd5} UPPERCASE as {@code Get-FileHash} prints it, {@code modVersion},
     * {@code gameVersion}, {@code source=kit}), each ending CRLF including the last (Set-Content of an
     * array, ASCII). WHY exact: {@code join-cc.bat:57-82} reads kitStamp / jarMd5 through its vguard
     * (charset {@code [0-9A-Za-z._:-]}) and compares jarMd5 to the jar's certutil md5. THROWS on a
     * jarMd5 not 32 hex, a stamp outside {@link #STAMP}, or a version holding a control / non-ASCII char.
     */
    public static String manifestText(String jarMd5, String stamp, String modVersion, String gameVersion) {
        if (jarMd5 == null || !MD5_HEX.matcher(jarMd5).matches()) {
            throw new IllegalArgumentException("kit manifest: jarMd5 is not 32 hex");
        }
        if (stamp == null || !STAMP.matcher(stamp).matches()) {
            throw new IllegalArgumentException("kit manifest: the stamp is not yyyyMMdd-HHmmss");
        }
        requirePrintable("modVersion", modVersion);
        requirePrintable("gameVersion", gameVersion);
        return "kitStamp=" + stamp + CRLF
                + "jarMd5=" + jarMd5.toUpperCase(Locale.ROOT) + CRLF
                + "modVersion=" + modVersion + CRLF
                + "gameVersion=" + gameVersion + CRLF
                + "source=kit" + CRLF;
    }

    private static final Pattern MOD_INFO_VERSION = Pattern.compile("\"version\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern MOD_INFO_GAME = Pattern.compile("\"gameVersion\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * The manifest's {@code modVersion} ({@code major.minor.patch}) and {@code gameVersion} out of
     * {@code mod_info.json}'s text, the roll's {@code ConvertFrom-Json} reads; null
     * when either is absent or not printable ASCII (the caller refuses the kit as damaged).
     */
    public static String[] modInfoVersions(String modInfoJson) {
        if (modInfoJson == null) {
            return null;
        }
        Matcher v = MOD_INFO_VERSION.matcher(modInfoJson);
        Matcher g = MOD_INFO_GAME.matcher(modInfoJson);
        if (!v.find() || !g.find()) {
            return null;
        }
        String major = versionPart(v.group(1), "major");
        String minor = versionPart(v.group(1), "minor");
        String patch = versionPart(v.group(1), "patch");
        String game = g.group(1);
        if (major == null || minor == null || patch == null || !printable(game)) {
            return null;
        }
        return new String[] {major + "." + minor + "." + patch, game};
    }

    private static String versionPart(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([0-9A-Za-z._+-]{1,32})\"?").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- README.md

    /**
     * The pack's token posture as the README's caller names it, chosen as the roll does: a
     * password-armed descriptor (its {@code .password} sibling exists) wins, else a redacted bake,
     * else a kept-token bake; {@code CLASSIC} = no baked descriptor (only the rules twin reaches it).
     * Every value composes the SAME README (one fetched-match text): the join window
     * fetches the match from the server, so no posture changes what a player is told.
     */
    public enum SessionBlock {
        PASSWORD_ARMED,
        REDACTED_NO_PASSWORD,
        KEEP_TOKENS,
        CLASSIC
    }

    /**
     * The kit README's ONE disclaimer sentence (one line, no newline), kept as a literal of its OWN: it is joined at
     * run time ({@code String.valueOf} is no constant expression), so javac never folds it into the whole-README
     * constants. WHY: the release-text gate reads a shipped class literal by literal and allows this one sentence by
     * its exact words, in the shipped class and in the kit's README.md; a whole README folded around it could never
     * be allowed that way. The goldens pin the composed README bytes.
     */
    private static final String DISCLAIMER = String.valueOf(
            "AlphaCoreMP is an unofficial fan-made mod, not affiliated with or endorsed by the developers of Starsector.");
    // The README text re-split from the vetted kit-final draft
    // re-cut to the vetted Markdown kit README text
    /**
     * The README from its title through "## If something goes wrong" (the sound note sits there, so a kit with no
     * bundled runtime keeps it), cut verbatim from the vetted Markdown text (line 3 = {@link #DISCLAIMER}).
     * The match is ONE text whatever the pack's token posture ("## What you need", "## Next time", "## When the host
     * changes the match"): a player needs only the connect code (the host's address, port and password); the join
     * window fetches the match from the server at join, so a kit is code a player gets once, never a match to keep
     * current. Markdown, one paragraph or list item per line (a text block cannot wrap them). No trailing newline:
     * {@link #readmeText} adds the line ends between the blocks.
     */
    private static final String README_HEAD = """
            # AlphaCoreMP - Starsector multiplayer joiner kit

            """ + DISCLAIMER + "\n\n" + """
            This kit lets you play multiplayer Starsector on a server another player (the host) runs. [Set up the kit](#set-up-the-kit-once) once, then [Join a match](#join-a-match) to play.

            ## TL;DR

            For players who know their way around - everything here is covered in full below.

            1. You need your own Starsector 0.98a-RC8 and the host's connect code (`address:port:password`).
            2. Extract this zip and copy its `CC_Client` folder into `mods\\` in your Starsector folder (the one with `starsector.exe`).
            3. Double-click `mods\\CC_Client\\join-cc.bat` (choose Run, or "More info" then "Run anyway", if Windows warns). The join window opens in your browser: paste the connect code, pick a ship and a team if you like, click "Launch".
            4. In the game launcher click "Play Starsector", then choose Missions, "Multiplayer Test", and press Play.
            5. In the match: Enter opens chat, `/help` lists the keys, and with "Enable microphone" on you hold "B" to talk to everyone or "Space" to talk to players near you. F9 marks a bug in the logs. When the game closes, the black window offers R to rejoin.
            6. For a new kit: delete `mods\\CC_Client`, copy in the new one, run `join-cc.bat` again.

            ## Contents

            - [What you need](#what-you-need), [Set up the kit (once)](#set-up-the-kit-once), [Join a match](#join-a-match), [Next time](#next-time), [In the match](#in-the-match), [After the game closes](#after-the-game-closes), [When the host changes the match](#when-the-host-changes-the-match), [Your own ship builds (optional)](#your-own-ship-builds-optional), [If something goes wrong](#if-something-goes-wrong)
            - [This kit brings its own Java](#this-kit-brings-its-own-java), [Updating the kit](#updating-the-kit), [The session report](#the-session-report), [What the report carries](#what-the-report-carries), [What it changes on your PC](#what-it-changes-on-your-pc), [To remove the kit](#to-remove-the-kit), [About](#about)

            ## What you need

            Your own purchased Starsector 0.98a-RC8 (the host's version; this kit has no game) and the host's connect code (`address:port:password`). Its last part is the match password the host announces for everyone. That is all you need: the join window fetches the match from the server by itself.

            ## Set up the kit (once)

            Extract this zip and copy its `CC_Client` folder into `mods\\` in your Starsector folder (with `starsector.exe`), usually:

            ```
            C:\\Program Files (x86)\\Fractal Softworks\\Starsector
            ```

            Windows usually copies it without asking. If it shows an administrator prompt, choose Continue.

            Never run `join-cc.bat` inside the zip window: it must be in `mods\\CC_Client`.

            ## Join a match

            1. Double-click `mods\\CC_Client\\join-cc.bat`. Because the kit came from the internet, Windows may show a warning first: in an "Open File - Security Warning" box choose Run; in a blue "Windows protected your PC" box click "More info", then "Run anyway". Do this only for a kit whose source you trust. Leave its black console window open while you play.
            2. Your browser opens the join window.
            3. Paste the connect code into its top box. If Windows Firewall asks about Java, choose "Allow access" (it may ask twice: "Zulu Platform x64 Architecture" for the join window, then "OpenJDK Platform binary" for the game). After a Cancel, joining usually still works; if not, allow that Java under "Allow an app through Windows Firewall".
            4. Once the server answers: keep Ship at "(server default)" or pick one; in a two-team match pick South, North or let the server pick; Mods should say "all present", else get each flagged mod where the host did, put it in `mods\\` and press "Refresh".
            5. Click "Launch".
            6. In the Starsector launcher click "Mods...": nothing to tick (`join-cc.bat` starts the game with exactly the host's mod list; your own list stays for a normal start). If AlphaCoreMP shows YELLOW, tell the host before playing. Then click "Play Starsector".
            7. Choose Missions, "Multiplayer Test": the text under it shows whether the server is reachable (select it again to refresh).
            8. When the host says GO, press Play: that connects you and claims your seat (nothing before Play holds a seat).

            ## Next time

            Run `join-cc.bat`, click "Launch": entries are remembered (the password only for that address and port).

            ## In the match

            - Enter opens chat and `/help` lists commands and keys. Your mic is used only with "Enable microphone" (join window) or "Mic" (`/settings`) ON. Hold "B" for global voice chat and "Space" for local (proximity) voice chat. Voice settings are under `/settings`.
            - Spotted a potential bug? Press F9: the logs mark the moment, your ship shows "BUG MARKED IN LOG #1" (then #2 ...), the server gets only the number and time. Send the host the report zip from your Desktop (marks are in its `cc-client.log`) and say what you saw.

            ## After the game closes

            Match over, crash or disconnect: the black window saves the session report (if ticked) and offers R (rejoin) or Q (quit).

            ## When the host changes the match

            The game says so. Close it and press R in the black window (the "join console") to join the new match.

            ## Your own ship builds (optional)

            At the title screen, "Open editor" in the join window opens a ship editor. Its Fly button makes a build into your next ship (in a match: your next respawn) if the host allows custom ships. The ship editor has many keyboard and mouse shortcuts: press "?" in the editor to list them, and try your scroll wheel.

            ## If something goes wrong

            Read the message (KIT VERSION CHECK FAILED, NOT CONNECTED under the mission, a NOTE line): most say what to do - and tell the host what you saw.

            - Kit outdated or not the host's kit: see [Updating the kit](#updating-the-kit).
            - Wrong match password: paste the code again or type it as announced.
            - NO MATCH YET: the host's server must be running; run `join-cc.bat` again and press "Refresh".
            - "Launch failed: Could not save the server's match as `match.txt`": your Windows account likely cannot write to the Starsector folder; a Starsector install outside `Program Files` (e.g. `C:\\Games\\Starsector`) avoids this.
            - Tab gone or "join window not answering": run `join-cc.bat` again and use the tab it reopens; close the new black window ("Launch cancelled"), keep the first.
            - No page in the browser: a plain window opens after about 45 seconds.
            - "Error in sound initialization" only via `join-cc.bat`: the kit checks and repairs the launch itself; the report zip carries the result - send it to the host.""";

    /** The bundled-runtime section's heading (present only when the kit carries {@code jre-cc}). */
    private static final String JRE_HEADING = "## This kit brings its own Java";

    /** The bundled-runtime paragraph up to its version token (the backticks are Markdown code spans). */
    private static final String JRE_LINE_HEAD = "The kit's own Java runtime is `CC_Client\\jre-cc` (Eclipse Temurin ";

    /**
     * The bundled-runtime paragraph after its version and its ", "; ONE line, no trailing newline ({@link #readmeText}
     * adds it).
     */
    private static final String JRE_TAIL = "about 50 MB): `join-cc.bat` runs the game on it, a normal start does not. If Windows or your antivirus blocks"
            + " `jre-cc\\bin\\javaw.exe`, `join-cc.bat` falls back to your normal launcher with a NOTE (the game still joins); voice chat latency may"
            + " then be worse than on the kit's Java 25. If the game hangs while loading, rename `kit-jvm.args` (for example to `kit-jvm.args.off`)"
            + " and start again: the game then runs on the Java that ships with Starsector and voice chat uses its slower path. Tell the host it happened.";

    /** "## Updating the kit" through "## About"; no trailing newline ({@link #readmeText} appends the ONE LF). */
    private static final String README_TAIL = """
            ## Updating the kit

            For a new kit, delete `mods\\CC_Client`, copy in the new zip's `CC_Client`, run `join-cc.bat`. Remembered entries stay.

            ## The session report

            `cc-debrief-<date>-<time>-<number>.zip`, saved to your Desktop when the game closes. Send it to the host if they ask; it is a plain zip - open and read it first. To skip it, untick "Save a session report zip on the Desktop" in the join window (then no `cc-client.log` is written).

            ## What the report carries

            - `starsector.log`, this mod's `cc-client.log`, the Java settings (`vmparams`, this kit's `kit-jvm.args`), your mod list (folder names under `mods\\`, `enabled_mods.json`).
            - A manifest: Windows and Java version, game folder, how the game was started, a ping to the host, and the name and location of each non-Microsoft program loaded into the game.
            - A sound-check log: whether a sound device was found (not its name), this PC's text encodings, the folders the check ran from (your profile folder as `%USERPROFILE%`).
            - Only if the sound check failed: your antivirus's name, whether Windows' loader and security policies are on or off, and COUNTS of security rules, exclusions and recent security events, never their contents.

            No password and no other file of yours. Both logs are copied unchanged: `cc-client.log` names the server, the other pilots and when you played; a folder path in either can show your Windows user name.

            ## What it changes on your PC

            ### The Windows registry

            On some PCs `join-cc.bat` starts the game through Windows Explorer; for those seconds it may put its launch settings in your user environment (`HKCU\\Environment`): `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `CC_KIT_JOPT`, `CC_PREDICT_TRANSPORT`, `CC_MATCH_DESCRIPTOR`, `CC_KIT_ENVSYNC`, plus `EnvApplied` under `HKCU\\Software\\CC_Client` and, if you had your own `JAVA_TOOL_OPTIONS`, a copy (`JtoPrev`, `JtoPrevType`) to put it back. They hold the server address, your pilot name and a file id (a launch file's name), no password. They go once the game has started (failing that: when the game closes or at the next run), and your `JAVA_TOOL_OPTIONS` is put back. An `_JAVA_OPTIONS` of your own is left alone (that start then skips the host's mod list). The empty `HKCU\\Software\\CC_Client` key stays behind.

            ### Files it keeps

            - `%LOCALAPPDATA%\\cc-starsector\\`: `join-settings.properties` (address, port, pilot name, ship, report tick, the match password in plain text; delete it to make the window forget), `cid-master` (player id), `join-window.log` (no password), an address file in `join\\`, short-lived launch files (two hold the password and player id) and a sound-library copy.
            - `mods\\CC_Client\\match.txt` (the match, saved on Launch).
            - `saves\\common\\`: `cc_mods` (links to your mods, not copies, plus one for this kit and an `enabled_mods.json`), `cc_variants` (your ship builds, cached server builds and lists), `cc_client_settings.data` (voice and HUD settings, mic name), `cc_editor_endpoint.data` (how the join window finds the ship editor).
            - `starsector-core\\`: `cc-client.log`, `cc-client.log.1` and, after a crash, `hs_err_pid*.log` and `replay_pid*.log` (both: server address, port, pilot name, file id; no password; never in the report).
            - `%USERPROFILE%\\`: `.cc-starsector-window-*.properties` (the game window's position when you play in a window; its name can show your pilot name).
            - Temp: `cc-sound-check-<number>.log`, the report's work folder (both deleted when the game closes). Desktop: the report zips.

            ## To remove the kit

            Delete `mods\\CC_Client` and the files kept above.

            Delete `saves\\common\\cc_mods` itself in File Explorer: that should remove only the links and leave your own mod folders in `mods\\` as they are. Never open a link inside `cc_mods` and delete what you see there - that is your real mod folder.

            ## About

            AlphaCoreMP's own code is free to use under the MIT License (see `CC_Client\\LICENSE.txt`); the third-party parts it bundles keep their own licenses (see `CC_Client\\THIRD-PARTY-NOTICES.txt`). This software is provided as is, without warranty of any kind. Use it at your own risk.""";

    private static final Pattern TEMURIN_PREFIX = Pattern.compile("^Temurin-", Pattern.CASE_INSENSITIVE);

    /**
     * The kit {@code README.md}: Markdown, ASCII, LF only (no CR anywhere), ending in exactly ONE LF (the .md
     * convention; the repo's .gitattributes pins {@code *.md} LF, and the source export's {@code readme/kit-README.md}
     * copy must byte-equal this text). The kit-rules goldens pin the bytes ({@code variant} is checked, never read:
     * every posture gets the same text). The JRE block ("## This kit brings its own Java" + its one-line paragraph)
     * sits after "## If something goes wrong", before "## Updating the kit", with one blank line either side.
     * {@code jreVersionOrNull} = the bundled runtime's {@code implementorVersion=} value (its {@code Temurin-}
     * prefix dropped case-insensitively, as PowerShell's {@code -replace} does); null = no bundled runtime, no
     * JRE block (one blank line between the two sections). THROWS on a version holding a control or non-ASCII
     * char (the roll's ASCII encoder would write {@code ?}).
     * The Markdown re-cut; the old one trailing CRLF is gone.
     */
    public static String readmeText(SessionBlock variant, String jreVersionOrNull) {
        if (variant == null) {
            throw new IllegalArgumentException("kit README: the session block must not be null");
        }
        String jreBlock = "";
        if (jreVersionOrNull != null) {
            if (!jreVersionOrNull.isEmpty()) {
                requirePrintable("the bundled runtime version", jreVersionOrNull);
            }
            jreBlock = "\n" + JRE_HEADING + "\n\n" + JRE_LINE_HEAD
                    + TEMURIN_PREFIX.matcher(jreVersionOrNull).replaceFirst("") + ", " + JRE_TAIL + "\n";
        }
        return README_HEAD + "\n" + jreBlock + "\n" + README_TAIL + "\n";
    }

    // ---------------------------------------------------------------- texts (the selftest's templates pin each one)

    /**
     * The retired per-shape kit verb's label: no shell offers the verb any more (a kit is code a player gets once;
     * the join window fetches the match from the server), and the console page's absence check reads this name so
     * the control cannot come back unseen.
     */
    public static final String LABEL_MAKE_KIT = "Make joiner kit";
    /** Done's restart sentence: appended when the package's sidecar changed while a server may hold the old one. */
    public static final String TEXT_RESTART = "The match's fingerprint file was updated: restart the server if it is running.";
    /** R8: a roster id is neither in the kit nor checkable without the game's own ship list. */
    public static final String TEXT_R8 = "Set the Game folder first: the kit check reads the game's own ship list.";
    /**
     * R12: the descriptor's REDACTED form (what the door serves a joiner) would still carry a live seat
     * token, or a zip pack was asked to keep tokens. The kit itself carries no match file.
     *
     */
    public static final String TEXT_R12 = "The match file would show joiners a real seat code - nothing was written.";

    /**
     * Done: {@code kitDisplay} = {@code kits\<file>}; {@code restart} appends {@link #TEXT_RESTART}. A player needs a
     * kit once: the join window fetches whichever match the server runs, so the text never ties the kit to a shape.
     */
    public static String textDone(String kitDisplay, boolean restart) {
        return "Joiner kit made: " + kitDisplay + " - a player needs it once; the join window fetches every match"
                + " from the server." + (restart ? " " + TEXT_RESTART : "");
    }

    /** R1: the release kit named by {@code pack.kit=} is not in the kits folder. */
    public static String textR1(String name, String kitsFolder) {
        return "No joiner kit to start from: put " + name + " (the kit this server came with; MANIFEST.txt names it"
                + " in pack.kit=) into " + kitsFolder + ", then run the kit pack again.";
    }

    /** R2: another client build or server version (jar / hs / package fingerprint disagree). */
    public static String textR2(String name) {
        return name + " is not the kit this server came with (another client build or server version) - use the kit"
                + " MANIFEST.txt names in pack.kit=.";
    }

    /** R3: the kit's own parts disagree ({@code part} = a bounded token: data, zip, an entry name). */
    public static String textR3(String name, String part) {
        return name + " is damaged or was changed (" + part + ") - download it again.";
    }

    /** R4: an entry the reader refuses (grammar, leak law, size, ratio); the entry shown cut to 80 chars. */
    public static String textR4(String name, String entry) {
        String shown = entry == null ? "" : entry;
        if (shown.length() > MAX_ENTRY_CHARS_SHOWN) {
            shown = shown.substring(0, MAX_ENTRY_CHARS_SHOWN);
        }
        return name + " holds an entry this server will not unpack (" + shown + ") - download it again.";
    }

    /** R5: over an aggregate size cap ({@code mb} = the cap in MiB). */
    public static String textR5(String name, long mb) {
        return name + " unpacks to more than " + mb + " MB - it is not a joiner kit.";
    }

    /** R6: the picked descriptor cannot be read ({@code reason} = a bounded phrase, never file content). */
    public static String textR6(String shape, String reason) {
        return "The match file " + shape + " cannot be read: " + reason + ".";
    }

    /** R7: roster ids neither the kit nor the game has (at most {@link #MAX_CENSUS_IDS_SHOWN} named). */
    public static String textR7(String shape, List<String> ids) {
        List<String> shown = ids.size() > MAX_CENSUS_IDS_SHOWN ? ids.subList(0, MAX_CENSUS_IDS_SHOWN) : ids;
        return "The match " + shape + " names ships neither the joiner kit nor the game has: " + String.join(", ", shown)
                + " - choose another match shape.";
    }

    /**
     * R13: the {@code dir:} pack's variant keep-list does not fit the stage, in two shapes. {@code staleIds} non-empty =
     * ids the keep-list names that no staged custom variant file carries (at most {@link #MAX_CENSUS_IDS_SHOWN}
     * named): the list was minted against another mod tree. Empty = the keep-list plus the match's roster resolve to
     * no staged custom variant at all: a kit stripped of every one would field none of the mod's ships. Either way
     * the fix is the same: the home roll re-mints the list from the match library and the mod tree.
     *
     */
    public static String textR13(List<String> staleIds) {
        if (staleIds == null || staleIds.isEmpty()) {
            return "The kit's variant keep-list resolves to no shipped variant - nothing was written; the home roll"
                    + " re-mints the keep-list.";
        }
        List<String> shown = staleIds.size() > MAX_CENSUS_IDS_SHOWN ? staleIds.subList(0, MAX_CENSUS_IDS_SHOWN) : staleIds;
        return "The kit's variant keep-list names variants this mod does not carry: " + String.join(", ", shown)
                + " - the keep-list is stale; the home roll re-mints it.";
    }

    /** R9: the kits volume lacks {@code mb} MiB. */
    public static String textR9(long mb) {
        return "Not enough free disk space for the kit (" + mb + " MB needed).";
    }

    /** R11: the pack passed its ceiling and committed nothing. */
    public static String textR11(int seconds) {
        return "Making the joiner kit took longer than " + seconds + " seconds - nothing was written. Run the kit pack again.";
    }

    /**
     * The write-failure text: the zip or the package sidecar
     * could not be written ({@code reason} = a bounded token: exists, io, folder, fingerprint, request, internal).
     */
    public static String textWrite(String reason) {
        return "The joiner kit could not be written (" + reason + ") - run the kit pack again.";
    }

    /** The check-pass text: {@link KitPacker#check} passed every step and wrote nothing. */
    public static String textChecked(String shape) {
        return "The joiner kit for " + shape + " passes every check (nothing was written).";
    }

    private static void requirePrintable(String what, String value) {
        if (!printable(value)) {
            throw new IllegalArgumentException("kit rules: " + what + " is empty or holds a control / non-ASCII char");
        }
    }

    private static boolean printable(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
