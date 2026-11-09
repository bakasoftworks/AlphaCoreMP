package cc.starsector.client.join;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import cc.starsector.client.KitUserDirs;

import static cc.starsector.client.join.JoinGuiSelftest.check;

/**
 * The user-dir rows of the {@code JoinGui --selftest} run: the kit's per-user directory ladder off Windows
 * ({@link KitUserDirs}, driven with an INJECTED environment, so every branch runs on any build host), the settings
 * folder that ladder feeds ({@link JoinSettings#settingsDirOffWindows}), and every join writer's SECOND write on real
 * files under the run's temp root (a settings save twice, a {@code cid-master} mint twice, an append writer twice, a
 * plan write over a leftover {@code .tmp}), plus the owner-only modes (0700 dirs, 0600 files) where
 * {@link KitFileModes#posix()} holds. On a filesystem without POSIX modes (Windows) a mode row prints
 * {@code SKIP-posix <row>} and asserts nothing: Windows writers are the plain calls they always were.
 *
 * <p>WHY second writes: a writer that mints once ({@code CREATE_NEW}) and a writer that rewrites must never trade
 * options — a settings save opened {@code CREATE_NEW} freezes the remembered host and password at their first values,
 * and an append writer opened that way keeps one line. Each row's message names the regression that flips it.
 * Pure test code, never on a sim or wire path; run from {@link JoinGuiSelftest#run()} like its other sibling batteries.
 *
 *
 */
final class JoinUserDirsSelftest {

    private static final String DIR_0700 = "rwx------";
    private static final String FILE_0600 = "rw-------";

    private JoinUserDirsSelftest() {
    }

    static void run(Path tmp) throws IOException {
        ladderRows();
        settingsDirRows();
        windowNameRows();
        Path root = Files.createDirectories(tmp.resolve("userdirs"));
        saveTwiceRows(root);
        mintTwiceRows(root);
        appendTwiceRows(root);
        planTmpRows(root);
    }

    /** The ladder twins: each variable set + absolute / relative / unset, and HOME unusable ⇒ every method null. */
    private static void ladderRows() {
        Map<String, String> all = env("XDG_RUNTIME_DIR", "/run/user/1000", "XDG_DATA_HOME", "/data/pilot",
                "HOME", "/home/pilot");
        check(Paths.get("/run/user/1000/cc-starsector").equals(KitUserDirs.runtimeDir(all))
                && Paths.get("/data/pilot/cc-starsector").equals(KitUserDirs.dataDir(all)),
                "userdirs ladder-set: an absolute XDG_RUNTIME_DIR / XDG_DATA_HOME each win their base (flips if the"
                        + " runtime base ignores XDG_RUNTIME_DIR - the credential handoff would outlive the session in"
                        + " the persistent dir - or the data base ignores XDG_DATA_HOME)");
        Map<String, String> relative = env("XDG_RUNTIME_DIR", "run/user/1000", "XDG_DATA_HOME", "data/pilot",
                "HOME", "/home/pilot");
        Path homeShare = Paths.get("/home/pilot", ".local", "share", "cc-starsector");
        check(homeShare.equals(KitUserDirs.runtimeDir(relative)) && homeShare.equals(KitUserDirs.dataDir(relative)),
                "userdirs ladder-relative: a relative XDG value is ignored and HOME's .local/share serves both (flips if"
                        + " a relative value is trusted - the files would land under the game's working directory)");
        Map<String, String> homeOnly = env("XDG_RUNTIME_DIR", "", "HOME", "/home/pilot");
        Map<String, String> dataOnly = env("XDG_DATA_HOME", "/data/pilot", "HOME", "/home/pilot");
        check(homeShare.equals(KitUserDirs.runtimeDir(homeOnly)) && homeShare.equals(KitUserDirs.dataDir(homeOnly))
                && Paths.get("/data/pilot/cc-starsector").equals(KitUserDirs.runtimeDir(dataOnly))
                && Paths.get("/home/pilot/.local/share").equals(KitUserDirs.dataBase(homeOnly)),
                "userdirs ladder-unset: an unset or empty XDG_RUNTIME_DIR falls to the DATA base, which defaults to"
                        + " HOME/.local/share (flips if the runtime base skips XDG_DATA_HOME and goes straight to HOME,"
                        + " or the default leaves .local/share - the shell launcher would resolve another folder)");
        for (Map<String, String> unusable : Arrays.asList(env(), env("HOME", ""), env("HOME", "home/pilot"))) {
            check(KitUserDirs.runtimeBase(unusable) == null && KitUserDirs.dataBase(unusable) == null
                    && KitUserDirs.runtimeDir(unusable) == null && KitUserDirs.dataDir(unusable) == null,
                    "userdirs ladder-no-home: HOME unset, empty or relative (no XDG override) makes every ladder method"
                            + " null (flips if it falls back to user.home or the working directory - the shell launcher"
                            + " refuses there, so the two resolvers would fork)");
        }
    }

    /** The settings folder off Windows = the ladder's DATA dir; no usable HOME refuses instead of guessing. */
    private static void settingsDirRows() {
        Map<String, String> withRuntime = env("XDG_RUNTIME_DIR", "/run/user/1000", "HOME", "/home/pilot");
        check(Paths.get("/home/pilot", ".local", "share", "cc-starsector").equals(
                JoinSettings.settingsDirOffWindows(withRuntime))
                && Paths.get("/data/pilot/cc-starsector").equals(JoinSettings.settingsDirOffWindows(
                        env("XDG_DATA_HOME", "/data/pilot", "HOME", "/home/pilot"))),
                "userdirs settings-dir: off Windows the settings and cid-master live in the PERSISTENT data dir (flips"
                        + " if they follow user.home's old cc-starsector folder or the runtime dir - the remembered"
                        + " settings and the per-host client id would vanish at logout)");
        for (Map<String, String> unusable : Arrays.asList(env(), env("HOME", "home/pilot"))) {
            boolean refused = false;
            try {
                JoinSettings.settingsDirOffWindows(unusable);
            } catch (IllegalStateException expected) {
                refused = true;
            }
            check(refused, "userdirs settings-no-home: no usable HOME refuses the settings folder (flips if it falls"
                    + " back to user.home or the working directory - the remembered password lands where the launcher"
                    + " never looks)");
        }
        // pure path arithmetic on this host's real environment (nothing is read or written); a refusal on either side
        // is a value here, so an inverted OS test fails THIS row by name instead of escaping as a bare throw
        Path expected = null;
        try {
            expected = KitUserDirs.isWindows()
                    ? JoinSettings.settingsBase(System.getenv("LOCALAPPDATA"), System.getenv("APPDATA"),
                            System.getProperty("user.home")).resolve(JoinSettings.DIR_NAME).resolve(JoinSettings.FILE_NAME)
                    : JoinSettings.settingsDirOffWindows(System.getenv()).resolve(JoinSettings.FILE_NAME);
        } catch (IllegalStateException noHome) {
            expected = null;   // a non-Windows host with no usable HOME: defaultPath must refuse too
        }
        Path actual;
        try {
            actual = JoinSettings.defaultPath();
        } catch (IllegalStateException refused) {
            actual = null;
        }
        check(expected == null ? actual == null : expected.equals(actual),
                "userdirs settings-dispatch: Windows keeps the LOCALAPPDATA ladder and only other systems take the XDG"
                        + " one (flips if the OS test is inverted - a Windows window would refuse for want of HOME)");
    }

    /**
     * The second window's settings-file name, pure path arithmetic (nothing is read or written): a lawful name = that
     * file BESIDE the default one; absent or blank = the default file and no refusal; every other value = the default
     * file AND refused (one log row) - the allow-list is the only admission, so no charset-shaped name gets through.
     *
     */
    private static void windowNameRows() {
        Path def = Paths.get(KitUserDirs.isWindows() ? "D:\\Lad" : "/lad").resolve(JoinSettings.DIR_NAME)
                .resolve(JoinSettings.FILE_NAME);
        for (String lawful : Arrays.asList("join-settings-2.properties", "join-settings-9.properties")) {
            Path got = JoinSettings.windowPath(def, lawful);
            check(def.resolveSibling(lawful).equals(got) && def.getParent().equals(got.getParent())
                    && !JoinSettings.nameRefused(lawful),
                    "userdirs window-name '" + lawful + "': a lawful name is this window's own file beside the default"
                            + " one (flips if it is refused or resolved anywhere else - the second window would share"
                            + " the first window's file again, or lose the shared cid-master folder)");
        }
        for (String unnamed : Arrays.asList(null, "", "   ")) {
            check(def.equals(JoinSettings.windowPath(def, unnamed)) && !JoinSettings.nameRefused(unnamed),
                    "userdirs window-name-absent " + (unnamed == null ? "(unset)" : "'" + unnamed + "'") + ": no name"
                            + " = the default file, never refused (flips if a blank value is refused - every ordinary"
                            + " window would log a refusal - or resolves a sibling)");
        }
        String long65 = "join-settings-2" + "x".repeat(39) + ".properties";   // 15 + 39 + 11 = 65 characters
        for (String bad : Arrays.asList("../x.properties", "a\\b.properties", "a/b.properties", "C:x.properties",
                "NUL.properties", "voice.properties", "join-settings.properties", "join-settings-2.properties ",
                "JOIN-SETTINGS-2.PROPERTIES", long65)) {
            check(def.equals(JoinSettings.windowPath(def, bad)) && JoinSettings.nameRefused(bad),
                    "userdirs window-name-refused '" + bad + "': not on the allow-list = the default file and one"
                            + " refusal row (flips if the allow-list loosens to a charset or a trim/case fold - a"
                            + " Launch save, which rewrites its file whole, could then be aimed at a device name, a"
                            + " foreign file or a folder outside the settings one)");
        }
    }

    /** Settings save twice: the second save REWRITES the first, whole; the file is 0600 in a 0700 folder. */
    private static void saveTwiceRows(Path root) throws IOException {
        Path file = root.resolve("settings").resolve(JoinSettings.FILE_NAME);
        new JoinSettings("first-long-host.example", "51820", "First Pilot With A Long Name", "first-password-long-1",
                "", true, "", "first-long-host.example:51820").save(file);
        boolean threw = false;
        try {
            new JoinSettings("h2.example", "7", "P2", "pw-2pass", "", true, "", "h2.example:7").save(file);
        } catch (IOException e) {
            threw = true;
        }
        JoinSettings back = JoinSettings.load(file);
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        check(!threw && "h2.example".equals(back.host) && "P2".equals(back.name)
                && "pw-2pass".equals(back.rememberedPasswordFor("h2.example", "7"))
                && !text.contains("first-password-long-1") && text.indexOf("host=") == text.lastIndexOf("host="),
                "userdirs save-twice: the second settings save replaces the first whole (flips if save opens CREATE_NEW -"
                        + " the remembered host and password freeze at their first values - or loses TRUNCATE_EXISTING"
                        + " or appends - the first save's password stays in the file)");
        posixRow("userdirs save-modes", () -> {
            Path older = root.resolve("settings").resolve("older.properties");
            Files.write(older, "host=o\n".getBytes(StandardCharsets.US_ASCII));
            Files.setPosixFilePermissions(older, PosixFilePermissions.fromString("rw-r--r--"));
            new JoinSettings("o.example", "1", "", "", "", true, "", "").save(older);
            check(DIR_0700.equals(mode(file.getParent())) && FILE_0600.equals(mode(file))
                    && FILE_0600.equals(mode(older)),
                    "userdirs save-modes: a new settings file is 0600 in a 0700 folder and an older 0644 one is narrowed"
                            + " on its next save (flips if the save drops the owner-only attribute or never narrows an"
                            + " existing file - the remembered password readable by another account)");
        });
    }

    /** cid-master mint twice: the second mint REFUSES to overwrite and returns the first master. */
    private static void mintTwiceRows(Path root) throws IOException {
        Path master = root.resolve("mint").resolve(JoinSettings.CID_MASTER_FILE);
        byte[] first = JoinSettings.mintMaster(master);
        byte[] onDisk = Files.readAllBytes(master);
        byte[] second;
        try {
            second = JoinSettings.mintMaster(master);
        } catch (IOException refusedLoudly) {
            second = null;   // a mint that THROWS on the existing file also fails the row: the loser must re-read
        }
        check(second != null && Arrays.equals(first, second) && Arrays.equals(onDisk, Files.readAllBytes(master)),
                "userdirs mint-twice: a second cid-master mint over an existing file keeps and returns the FIRST master"
                        + " (flips if the mint loses CREATE_NEW - a second window's mint replaces the master and every"
                        + " server sees a new client id from this install)");
        posixRow("userdirs mint-modes", () -> check(DIR_0700.equals(mode(master.getParent()))
                && FILE_0600.equals(mode(master)),
                "userdirs mint-modes: the cid-master is 0600 in a 0700 folder (flips if the mint drops the owner-only"
                        + " attribute - another account derives this install's per-host client ids)"));
    }

    /** An append writer twice through the mode helper (the join-window log's options): two lines, 0600. */
    private static void appendTwiceRows(Path root) throws IOException {
        Path log = root.resolve("logdir").resolve("join-window.log");
        KitFileModes.createDirs(log.getParent());
        if (KitFileModes.posix()) {
            Files.write(log, new byte[0]);
            Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));
        }
        for (String line : new String[] {"one", "two"}) {
            try (OutputStream out = Channels.newOutputStream(KitFileModes.open(log, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, StandardOpenOption.WRITE))) {
                out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            }
        }
        check("one\ntwo\n".equals(new String(Files.readAllBytes(log), StandardCharsets.US_ASCII)),
                "userdirs append-twice: the mode helper keeps the caller's APPEND, so a log written twice holds two"
                        + " lines (flips if KitFileModes.open swaps the caller's options for its own - an append log"
                        + " would keep one line, or refuse its second)");
        posixRow("userdirs append-modes", () -> check(FILE_0600.equals(mode(log)),
                "userdirs append-modes: an older 0644 append file is narrowed to 0600 on its next open (flips if the"
                        + " helper narrows only the files it creates)"));
    }

    /** The plan writer: a leftover .tmp neither blocks nor feeds the next write; the plan lands 0600; a link planted
     *  at the .tmp name is removed, never followed. */
    private static void planTmpRows(Path root) throws IOException {
        Path plan = root.resolve("plans").resolve("plan-7.txt");
        Files.createDirectories(plan.getParent());
        Files.write(JoinCore.planTmp(plan), "password=stale-leftover-1\r\n".getBytes(StandardCharsets.US_ASCII));
        boolean threw = false;
        try {
            JoinCore.writePlanAtomically(plan, "host=h\r\n");
        } catch (IOException e) {
            threw = true;
        }
        check(!threw && "host=h\r\n".equals(new String(Files.readAllBytes(plan), StandardCharsets.US_ASCII))
                && !Files.exists(JoinCore.planTmp(plan), LinkOption.NOFOLLOW_LINKS),
                "userdirs plan-leftover-tmp: a leftover plan .tmp (a write killed before its move) neither blocks the"
                        + " next plan write nor rides into it (flips if the exclusive create stops unlinking the"
                        + " leftover first - that Launch fails - or the leftover's bytes survive)");
        posixRow("userdirs plan-modes", () -> {
            Path older = root.resolve("plans").resolve("plan-8.txt");
            Files.write(older, "host=old\r\n".getBytes(StandardCharsets.US_ASCII));
            Files.setPosixFilePermissions(older, PosixFilePermissions.fromString("rw-r--r--"));
            JoinCore.writePlanAtomically(older, "host=new\r\n");
            check(FILE_0600.equals(mode(plan)) && FILE_0600.equals(mode(older)),
                    "userdirs plan-modes: a plan lands 0600, also over an older 0644 plan (flips if the .tmp is created"
                            + " without the owner-only attribute - the password and the cid readable by another account"
                            + " for the whole launch pass)");
            Path victim = root.resolve("plans").resolve("victim.txt");
            Files.write(victim, "keep\n".getBytes(StandardCharsets.US_ASCII));
            Path linked = root.resolve("plans").resolve("plan-9.txt");
            Files.createSymbolicLink(JoinCore.planTmp(linked), victim);
            JoinCore.writePlanAtomically(linked, "host=h9\r\n");
            check("keep\n".equals(new String(Files.readAllBytes(victim), StandardCharsets.US_ASCII))
                    && "host=h9\r\n".equals(new String(Files.readAllBytes(linked), StandardCharsets.US_ASCII))
                    && !Files.isSymbolicLink(linked),
                    "userdirs plan-planted-link: a link planted at the plan's .tmp name is removed, never written"
                            + " through (flips if the writer goes back to a truncating open that follows it - the"
                            + " link's target is overwritten with the password)");
        });
    }

    /**
     * A mode row: run where the filesystem has POSIX modes, else one {@code SKIP-posix} line and no assertion. A body
     * that ran to its end (every check in it throws on a failure) prints ONE {@code ROW-PASS <row>} line: the Linux kit
     * check requires each mode row's own line, so a deleted or renamed row reads as missing, never as a quiet pass.
     *
     */
    private static void posixRow(String row, IoRow body) throws IOException {
        if (!KitFileModes.posix()) {
            System.out.println("SKIP-posix " + row + ": no POSIX file modes on this filesystem");
            return;
        }
        body.run();
        System.out.println("ROW-PASS " + row);
    }

    private interface IoRow {
        void run() throws IOException;
    }

    private static String mode(Path p) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
