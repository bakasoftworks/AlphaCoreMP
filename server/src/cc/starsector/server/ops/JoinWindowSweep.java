package cc.starsector.server.ops;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The debug watch's join windows, closed when the watch's serve ends. The package console's debug watch opens one or
 * two {@code AlphaCoreMP join} console windows ({@link OpsJoinSelf#pressWatch}); after its game quits the kit's
 * launcher holds each window at its rejoin prompt, so nothing closes them on their own. This class closes exactly the
 * windows THIS console's debug-watch press opened, once, on the lease daemon's END leg ({@link #endLeg}) - which
 * every serve end reaches (End match, the F10 end, either Kill: the launcher exits, so the launch's exit code is set).
 * Never on a console stop (a live serve keeps its players' games), never on a holder-moved exit (a newer Start
 * replaced that launch, and the daemon returns before its END leg), and never a hand-pressed Join window's cmd (its
 * rejoin prompt is the host's own choice: {@link OpsJoinSelf#press} records no handle; the backstop below can still
 * end a kit-runtime game that window started from the same kit folder during the watch).
 *
 * <p>What it may end, and nothing else: (a) a recorded window handle - the outer {@code cmd /c start /wait} the press
 * spawned, which lives exactly as long as its window - while it is alive with the SAME start instant as captured at
 * the spawn (a reused pid is counted {@code gone}, never touched), and those of that handle's descendants whose
 * EXECUTABLE is one the window itself runs ({@link #ourExecutable}: the listed console tools of
 * {@code %SystemRoot%\System32}, the debrief's Windows PowerShell, the game's own launcher, anything under the game's
 * {@code jre\} or the kit folder), deepest first, the handle itself last. Any other descendant - the host's browser
 * that the join page cold-started, an editor, a process the OS will not describe - is neither ended nor walked into:
 * its whole subtree is left alone and counted once as skipped; (b) the backstop: a process whose EXECUTABLE lies under
 * that window's own kit folder ({@code <game>\mods\CC_Client\}, where the kit's bundled {@code jre-cc} runtime lives)
 * and that started at or after the window's spawn - the kit launcher starts the game detached, so a game whose parent
 * chain broke is outside the walk. Never a match by process name alone outside {@code System32} (a Linux host:
 * {@code /bin}, {@code /usr/bin}), never a path outside those folders. No request byte reaches the sweep; its ONE
 * stdout row carries counts only:
 * {@code cc-console watch sweep closed=<n> gone=<m> detached=<k>}, with {@code skipped=<s>} appended last only when a
 * subtree was left alone (a zero is omitted, so a tree of the window's own processes reads the same row as before),
 * plus at most one {@code cc-console watch sweep fault=<SimpleName>}.
 *
 * <p>A Linux host ({@link Os#posix}): the recorded handle is the kit launcher's {@code /bin/bash}, which ends on its
 * own once its game closes (the rejoin prompt's read fails on the closed stdin), so a live handle at the END means a
 * window still before Launch or a game still running. The walk's allow-list is {@link #ourPosixExecutable}, a STRING
 * test over the kernel's executable path: the kit folder, the game's own {@code jre_linux/} (the join window's UI, the
 * game and the launcher's helper JVMs) and the listed shell tools ({@link #POSIX_TOOLS}) directly in {@code /bin} or
 * {@code /usr/bin}; {@code %SystemRoot%} is never read. The backstop keeps its rule and matches nothing there (the
 * Linux kit runs no executable from its own folder), so a game JVM reparented to init after its bash died is left
 * alone - never a kill by install path alone. Every process the sweep ends gets the normal termination request
 * (SIGTERM) first, then {@link #JVM_EXIT_BOUND_MS} to exit, and the forcible kill (SIGKILL) only if it outlives that
 * ({@link #termThenKill}): the kit's TERM trap removes its password handoff file, plan files and temp folder, which a
 * SIGKILL would leave behind, and a JVM runs its shutdown hooks.
 *
 * <p>PURE over its seams ({@link Proc}, {@link Os}) so the rows run on fakes; {@link #LIVE} = the real
 * {@link ProcessHandle} ones. Thread: the lease daemon's - the sweep may wait up to {@link #JVM_EXIT_BOUND_MS} per JVM
 * (a Linux host: per ended process that ignores its request), and nothing here rides a frame loop.
 *
 */
final class JoinWindowSweep {

    private JoinWindowSweep() {
    }

    /**
     * A live JVM in a window's tree (the game, the join window's UI) gets a normal termination request and this long
     * to exit before the forcible one, short enough that an END leg never parks its daemon for long. On Windows the
     * request is already the forcible kill ({@code destroy()} = TerminateProcess; {@code supportsNormalTermination()}
     * is false there, so the wait ends at once): a game still running at the serve's end (measured on a real match:
     * it quits about 30 s after END on its own) is terminated without its shutdown hooks, and its client-log tail can
     * be lost. On a Linux host EVERY process the sweep ends gets the request (SIGTERM) and this bound
     * ({@link #termThenKill}), so a JVM's shutdown hooks run there.
     */
    static final long JVM_EXIT_BOUND_MS = 3_000L;
    /** A window's tree is about a dozen processes (its cmd and conhost, the join UI, the game, the prompt); the walk
     *  stops here so a pathological tree never turns an END leg into an unbounded scan. */
    static final int WALK_MAX = 64;
    /** The stdout row's prefix (counts follow; never a path or a pid). */
    static final String ROW = "cc-console watch sweep";
    /**
     * The console tools of {@code %SystemRoot%\System32} a join window itself runs, by file name (lower case): the
     * window's cmd and its console host, the rejoin prompt ({@code choice}), the launcher's sleeps ({@code ping}), its
     * game-alive probe ({@code tasklist | find}), its text gates ({@code findstr}) and its jar-hash read
     * ({@code certutil}). The name counts only for a file directly in that folder.
     */
    static final Set<String> SYSTEM32_TOOLS = Set.of("cmd.exe", "conhost.exe", "choice.exe", "ping.exe",
            "tasklist.exe", "find.exe", "findstr.exe", "certutil.exe");
    /**
     * A Linux host's twin of {@link #SYSTEM32_TOOLS}: the external commands the kit's Linux launcher runs as live
     * children, by file name (case-sensitive, as Linux names are). The name counts only for a file directly in
     * {@code /bin} or {@code /usr/bin} (usrmerge links one to the other; the kernel reports the resolved one). bash =
     * the launcher itself, its subshells and command substitutions, and any bash script, which reads as its
     * interpreter (glibc's ldd; an xdg-open where sh is bash - the browser it starts is still judged on its own); sh;
     * the kit check (md5sum, or busybox's); the window-up and early-death waits (sleep); the stale-file sweep (find);
     * the folder checks (stat, mkdir, chmod); the file housekeeping (rm, rmdir, mv); the log stamps (date); the random
     * hex (od, tr); the kit path (dirname); the Java starts (env, which then becomes the JVM; timeout, the sound
     * probe's bound and that JVM's parent); the session report (mktemp, uname, cp, wc, cut, grep, head, tail, ldd,
     * aplay, ping, tar and the gzip its {@code -z} runs).
     */
    static final Set<String> POSIX_TOOLS = Set.of("bash", "sh", "md5sum", "busybox", "sleep", "find", "stat", "mkdir",
            "chmod", "rm", "rmdir", "mv", "date", "od", "tr", "dirname", "env", "timeout", "mktemp", "uname", "cp",
            "wc", "cut", "grep", "head", "tail", "ldd", "aplay", "ping", "tar", "gzip");

    /** One process as the sweep reads it. {@code startOrNull} / {@code commandOrNull} = null when the OS will not say
     *  (another user's process, or one already gone). */
    interface Proc {
        long pid();

        Instant startOrNull();

        /** The executable's full path, or null. */
        String commandOrNull();

        boolean isAlive();

        /** The live direct children (the real one lists no process that started before this one: a reused pid). */
        List<Proc> children();
    }

    /** The box: the process list (the backstop's), the kill, the bounded wait. */
    interface Os {
        List<Proc> all();

        /** {@code force} false = a normal termination request, true = the forcible one. */
        void kill(Proc p, boolean force);

        /** True when {@code p} exited within {@code boundMs}. */
        boolean awaitExit(Proc p, long boundMs);

        /** The box's Windows folder ({@code %SystemRoot%}; blank = {@code C:\Windows}, the rule the window spawn's
         *  argv uses): where the walk's allowed console tools live. Never read on a Linux host.
         * */
        String systemRoot();

        /** True on a Linux host: the walk takes {@link JoinWindowSweep#ourPosixExecutable}, {@link #systemRoot} is
         *  never read, and every ended process gets the normal request first ({@link JoinWindowSweep#termThenKill}).
         * */
        boolean posix();
    }

    /**
     * A window the debug watch's press opened: the outer cmd's handle (a Linux host: the kit launcher's bash), its
     * start instant as captured at the spawn (the pid-reuse guard, and the backstop's floor) and the kit folder it ran
     * in. {@code toString} names the pid only.
     */
    record Window(Proc handle, Instant start, Path kitDir) {
        Window {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(kitDir, "kitDir");
        }

        @Override
        public String toString() {
            return "Window[pid=" + handle.pid() + "]";
        }
    }

    /** One sweep's counts: windows closed, recorded windows already gone (or a reused pid), detached kit processes
     *  the backstop ended, and foreign or undescribed subtrees the walk left alone (one per subtree, never per
     *  process: the walk never reads below its top). The row omits a zero {@code skipped}.
     * */
    record Tally(int closed, int gone, int detached, int skipped) {
        String row() {
            return ROW + " closed=" + closed + " gone=" + gone + " detached=" + detached
                    + (skipped > 0 ? " skipped=" + skipped : "");
        }
    }

    /** One window's walk: its own descendants, deepest first, and the count of subtrees it left alone.
     * */
    record Walk(List<Proc> tree, int skipped) {
    }

    /**
     * ONE debug-watch launch's windows: its press records into it, its END leg sweeps it once ({@link #sweepOnce} takes
     * and clears the set, so a second END finds nothing). The console makes one per debug-watch Start, so a newer
     * Start's windows never mix with an older one's. Thread: any (the press daemon records, the lease daemon sweeps).
     */
    static final class WindowSet {
        private final Os os;
        private final Consumer<String> rows;
        private final List<Window> windows = new ArrayList<>();

        WindowSet(Os os, Consumer<String> rows) {
            this.os = Objects.requireNonNull(os, "os");
            this.rows = Objects.requireNonNull(rows, "rows");
        }

        void record(List<Window> opened) {
            synchronized (windows) {
                windows.addAll(opened);
            }
        }

        int size() {
            synchronized (windows) {
                return windows.size();
            }
        }

        Tally sweepOnce() {
            List<Window> mine;
            synchronized (windows) {
                mine = List.copyOf(windows);
                windows.clear();
            }
            return sweep(mine, os, rows);
        }
    }

    /** The lease daemon's END leg for one debug-watch launch: {@code scrub} (the join settings' password rows), then
     *  the window sweep - in a {@code finally}, so a throwing scrub still closes the windows (the daemon's own catch
     *  then prints the scrub's fault row). */
    static Runnable endLeg(Runnable scrub, WindowSet windows) {
        Objects.requireNonNull(scrub, "scrub");
        Objects.requireNonNull(windows, "windows");
        return () -> {
            try {
                scrub.run();
            } finally {
                windows.sweepOnce();
            }
        };
    }

    /** The sweep itself (see the class javadoc); prints the count row (and at most one fault row) to {@code rows}. An
     *  empty set reads no process list. A Linux host chooses the same set, then ends it in that order through
     *  {@link #termThenKill}. */
    static Tally sweep(List<Window> windows, Os os, Consumer<String> rows) {
        int closed = 0;
        int gone = 0;
        int detached = 0;
        int skipped = 0;
        String fault = null;
        Set<Long> ended = new HashSet<>();
        boolean posix = os.posix();
        List<Proc> posixBatchOrNull = posix ? new ArrayList<>() : null;
        for (Window w : windows) {
            Proc h = w.handle();
            boolean mine;
            try {
                mine = h.isAlive() && w.start().equals(h.startOrNull());
            } catch (RuntimeException unreadable) {
                mine = false;
                fault = first(fault, unreadable);
            }
            if (!mine) {
                gone++;
                continue;
            }
            Walk walk;
            try {
                Predicate<Proc> ours;
                if (posix) {
                    ours = p -> ourPosixExecutable(p.commandOrNull(), w.kitDir());
                } else {
                    Path system32 = Paths.get(os.systemRoot()).resolve("System32").toAbsolutePath().normalize();
                    ours = p -> ourExecutable(p.commandOrNull(), system32, w.kitDir());
                }
                walk = deepestFirst(h, ours);
            } catch (RuntimeException unreadable) { // the window itself still closes below
                walk = new Walk(List.of(), 0);
                fault = first(fault, unreadable);
            }
            skipped += walk.skipped();
            for (Proc d : walk.tree()) {
                fault = first(fault, endOrQueue(d, os, posixBatchOrNull));
                ended.add(d.pid());
            }
            fault = first(fault, endOrQueue(h, os, posixBatchOrNull));
            ended.add(h.pid());
            closed++;
        }
        if (!windows.isEmpty()) {
            List<Proc> all;
            try {
                all = os.all();
            } catch (RuntimeException unreadable) {
                all = List.of();
                fault = first(fault, unreadable);
            }
            for (Proc p : all) {
                try {
                    if (!ended.contains(p.pid()) && detachedOfAny(p, windows)) {
                        fault = first(fault, endOrQueue(p, os, posixBatchOrNull));
                        ended.add(p.pid());
                        detached++;
                    }
                } catch (RuntimeException unreadable) { // one odd process never stops the rest
                    fault = first(fault, unreadable);
                }
            }
        }
        if (posixBatchOrNull != null) {
            fault = first(fault, termThenKill(posixBatchOrNull, os));
        }
        Tally t = new Tally(closed, gone, detached, skipped);
        rows.accept(t.row());
        if (fault != null) {
            rows.accept(ROW + " fault=" + fault);
        }
        return t;
    }

    /** {@code root}'s descendants that pass {@code ours}, deepest level first (so every child precedes its parent),
     *  {@code root} excluded, at most {@link #WALK_MAX}. A child that fails {@code ours} is neither listed nor walked
     *  into: its whole subtree is left alone (a process the window did not start owns whatever it started), counted
     *  once in {@link Walk#skipped}. */
    static Walk deepestFirst(Proc root, Predicate<Proc> ours) {
        List<Proc> levelOrder = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        seen.add(root.pid());
        int skipped = 0;
        Deque<Proc> queue = new ArrayDeque<>(root.children());
        while (!queue.isEmpty() && levelOrder.size() < WALK_MAX) {
            Proc p = queue.removeFirst();
            if (!seen.add(p.pid())) {
                continue;
            }
            if (!ours.test(p)) {
                skipped++;
                continue;
            }
            levelOrder.add(p);
            queue.addAll(p.children());
        }
        Collections.reverse(levelOrder);
        return new Walk(levelOrder, skipped);
    }

    /**
     * The walk's allow-list on a Windows host (a Linux host's = {@link #ourPosixExecutable}): true when
     * {@code command} is an executable a join window itself runs - one of
     * {@link #SYSTEM32_TOOLS} directly in {@code system32}; the debrief's Windows PowerShell
     * ({@code System32\WindowsPowerShell\v1.0\powershell.exe}, where the launcher's bare {@code powershell} resolves
     * on a stock Windows); the game's own launcher {@code <game>\starsector.exe} (the kit's game start without its
     * bundled runtime); or a file under the game's {@code jre\} (the join window's UI, that launcher's game JVM) or
     * under {@code kitDir} (the bundled-runtime game). The game folder is {@code kitDir}'s grandparent only when its parent is {@code mods}
     * ({@link OpsJoinSelf#cwd}'s shape), else that half is off. Paths compare whole-name and, on Windows, case-blind.
     * A null, blank or unparsable command = unknown = false (never ended on a guess).
     *
     */
    static boolean ourExecutable(String command, Path system32, Path kitDir) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Path exe;
        try {
            exe = Paths.get(command).toAbsolutePath().normalize();
        } catch (InvalidPathException unparsable) { // the caller counts it skipped
            return false;
        }
        Path name = exe.getFileName();
        if (name != null && system32.equals(exe.getParent())
                && SYSTEM32_TOOLS.contains(name.toString().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (exe.equals(system32.resolve("WindowsPowerShell").resolve("v1.0").resolve("powershell.exe"))) {
            return true;
        }
        if (underKit(command, kitDir)) {
            return true;
        }
        Path mods = kitDir.toAbsolutePath().normalize().getParent();
        Path game = mods == null || mods.getFileName() == null
                || !mods.getFileName().toString().equalsIgnoreCase("mods") ? null : mods.getParent();
        return game != null && (exe.equals(game.resolve(OpsJoinSelf.GAME_EXE))
                || underKit(command, game.resolve("jre")));
    }

    /**
     * A Linux host's walk allow-list (the twin of {@link #ourExecutable}): true when {@code command} - the kernel's
     * resolved executable path - is a plain absolute path (a leading {@code /}; no empty, {@code .} or {@code ..}
     * name, so no prefix below can be climbed out of) and lies (a) under {@code kitDir}, (b) under the game's own
     * {@code jre_linux/}, or (c) directly in {@code /bin} or {@code /usr/bin} with a file name in
     * {@link #POSIX_TOOLS}. The game folder is {@code kitDir}'s grandparent only when its parent is named
     * {@code mods} ({@link OpsJoinSelf#cwd}'s shape), else (b) is off. Every JVM the kit's Linux launcher starts (the
     * join window's UI, the game, its helper probes) is its one java, from one of two sources: the java command
     * starsector.sh names when that ends in {@code jre_linux/bin/java} (an absolute one as written, a relative one
     * under the game folder), or, when starsector.sh cannot be read, the game folder's {@code jre_linux/bin/java};
     * only the game's own {@code jre_linux/} is allowed, so a starsector.sh java anywhere else leaves its JVMs alone.
     * Anything else - a browser under {@code /usr/lib}, {@code /snap} or {@code /opt}, a command off the list, a
     * Windows path - is not ours. A STRING test (both folders as their {@code /}-joined names, never a {@code /} name
     * parsed as a path), so a Windows box computes the same answer; case-sensitive. A null or blank command =
     * unknown = false.
     */
    static boolean ourPosixExecutable(String command, Path kitDir) {
        if (!plainAbsolute(command)) {
            return false;
        }
        int slash = command.lastIndexOf('/');
        String dir = command.substring(0, slash);
        if ((dir.equals("/bin") || dir.equals("/usr/bin")) && POSIX_TOOLS.contains(command.substring(slash + 1))) {
            return true;
        }
        Path kit = kitDir.toAbsolutePath().normalize();
        if (command.startsWith(slashJoined(kit) + "/")) {
            return true;
        }
        Path mods = kit.getParent();
        Path game = mods == null || mods.getFileName() == null || !mods.getFileName().toString().equals("mods")
                ? null : mods.getParent();
        return game != null && command.startsWith(slashJoined(game) + "/jre_linux/");
    }

    /** True for a {@code /}-led path whose every name is non-empty and neither {@code .} nor {@code ..} (null = false).
     * */
    private static boolean plainAbsolute(String command) {
        if (command == null || command.length() < 2 || command.charAt(0) != '/') {
            return false;
        }
        for (String name : command.substring(1).split("/", -1)) {
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** {@code p}'s names, each after a {@code /}: a Linux path's own text, and on a Windows box the same text with
     *  the drive dropped (how a fake Linux folder reads there). */
    private static String slashJoined(Path p) {
        StringBuilder b = new StringBuilder();
        for (Path name : p) {
            b.append('/').append(name);
        }
        return b.length() == 0 ? "/" : b.toString();
    }

    /** True when {@code p} is a backstop match of ANY recorded window: started at or after that window's spawn, its
     *  executable under that window's kit folder. */
    static boolean detachedOfAny(Proc p, List<Window> windows) {
        Instant start = p.startOrNull();
        String command = p.commandOrNull();
        if (start == null || command == null) {
            return false;
        }
        for (Window w : windows) {
            if (!start.isBefore(w.start()) && underKit(command, w.kitDir())) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code command} names a file strictly inside {@code kitDir} - a whole-name path prefix (a sibling
     *  {@code CC_Client2} folder is not inside {@code CC_Client}; on Windows the names compare case-blind). An
     *  unparsable command THROWS (the sweep counts it into its fault row). {@link #ourExecutable} applies the same
     *  test to the game's {@code jre\} folder. */
    static boolean underKit(String command, Path kitDir) {
        Path exe = Paths.get(command).toAbsolutePath().normalize();
        Path kit = kitDir.toAbsolutePath().normalize();
        return exe.getNameCount() > kit.getNameCount() && exe.startsWith(kit);
    }

    /** A JVM by its executable's file name (java / javaw, any case, with or without {@code .exe}) - this only picks
     *  a Windows host's gentle-then-forcible order inside an already-chosen set, never what is ended. */
    static boolean isJvm(Proc p) {
        String c = p.commandOrNull();
        if (c == null) {
            return false;
        }
        String name = c.substring(Math.max(c.lastIndexOf('\\'), c.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
        return name.equals("java.exe") || name.equals("javaw.exe") || name.equals("java") || name.equals("javaw");
    }

    /** A Windows host ends {@code p} now ({@link #end}); a Linux host's {@code posixBatchOrNull} only queues it for
     *  {@link #termThenKill}, in the sweep's order. Returns null, or the fault's SimpleName.
     * */
    private static String endOrQueue(Proc p, Os os, List<Proc> posixBatchOrNull) {
        if (posixBatchOrNull != null) {
            posixBatchOrNull.add(p);
            return null;
        }
        return end(p, os);
    }

    /**
     * A Linux host's ending of the chosen set, in its order (each window's tree deepest first, its handle after it,
     * then the backstop's): the normal termination request (SIGTERM) to every live one FIRST, then each gets
     * {@link #JVM_EXIT_BOUND_MS} to exit and the forcible kill (SIGKILL) only if it outlives that; one already gone
     * gets nothing. Every request goes out before any wait so the kit's bash gets its TERM while it still waits on its
     * game - a wait the TERM trap cuts short at once, so the trap's cleanup runs - never only after the game died,
     * when the session report's commands it then runs in the foreground would hold the trap back, possibly past the
     * bound. Each wait is the full bound, so only a process that ignores the request parks the daemon. Returns null,
     * or the first fault's SimpleName (the sweep's fault row).
     */
    private static String termThenKill(List<Proc> batch, Os os) {
        String fault = null;
        List<Proc> asked = new ArrayList<>();
        for (Proc p : batch) {
            try {
                if (p.isAlive()) {
                    os.kill(p, false);
                    asked.add(p);
                }
            } catch (RuntimeException failed) { // into the sweep's one fault row; the rest still get theirs
                fault = first(fault, failed);
            }
        }
        for (Proc p : asked) {
            try {
                if (!os.awaitExit(p, JVM_EXIT_BOUND_MS)) {
                    os.kill(p, true);
                }
            } catch (RuntimeException failed) { // the same fault row
                fault = first(fault, failed);
            }
        }
        return fault;
    }

    /** Ends one process on a Windows host: a live JVM gets the normal request and {@link #JVM_EXIT_BOUND_MS} first,
     *  anything else (or a JVM past the bound) the forcible kill. Returns null, or the fault's SimpleName (the sweep's
     *  fault row). */
    private static String end(Proc p, Os os) {
        try {
            if (isJvm(p) && p.isAlive()) {
                os.kill(p, false);
                if (os.awaitExit(p, JVM_EXIT_BOUND_MS)) {
                    return null;
                }
            }
            os.kill(p, true);
            return null;
        } catch (RuntimeException failed) {
            return failed.getClass().getSimpleName();
        }
    }

    private static String first(String kept, RuntimeException failed) {
        return kept != null ? kept : failed.getClass().getSimpleName();
    }

    private static String first(String kept, String next) {
        return kept != null ? kept : next;
    }

    /** {@code h} as a {@link Proc} over the real process table. */
    static Proc live(ProcessHandle h) {
        return new LiveProc(Objects.requireNonNull(h, "h"));
    }

    /** The real process, read through {@link ProcessHandle} (its own start-time check guards a reused pid). */
    private record LiveProc(ProcessHandle h) implements Proc {
        @Override
        public long pid() {
            return h.pid();
        }

        @Override
        public Instant startOrNull() {
            return h.info().startInstant().orElse(null);
        }

        @Override
        public String commandOrNull() {
            return h.info().command().orElse(null);
        }

        @Override
        public boolean isAlive() {
            return h.isAlive();
        }

        @Override
        public List<Proc> children() {
            return h.children().map(JoinWindowSweep::live).toList();
        }
    }

    /** The real box. A {@link Proc} that is not a live one (a fake reaching the real box) throws
     *  {@link ClassCastException} into the sweep's fault row, never a kill. */
    static final Os LIVE = new Os() {
        /** Read once, when this class loads, never per sweep: a console's platform does not change under it.
         * */
        private final boolean posix = ServerOpsCore.posixHost(System.getProperty("os.name"));

        @Override
        public boolean posix() {
            return posix;
        }

        @Override
        public List<Proc> all() {
            return ProcessHandle.allProcesses().map(JoinWindowSweep::live).toList();
        }

        @Override
        public void kill(Proc p, boolean force) {
            ProcessHandle h = ((LiveProc) p).h();
            if (force) {
                h.destroyForcibly();
            } else {
                h.destroy();
            }
        }

        /** {@code %SystemRoot%}, blank = {@code C:\Windows}. */
        @Override
        public String systemRoot() {
            String root = System.getenv("SystemRoot");
            return root == null || root.isBlank() ? "C:\\Windows" : root;
        }

        /** A late or failed exit answers false: the caller's forcible kill is that catch's accounting. */
        @Override
        public boolean awaitExit(Proc p, long boundMs) {
            try {
                ((LiveProc) p).h().onExit().get(boundMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException | ExecutionException late) {
                return false;
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    };
}
