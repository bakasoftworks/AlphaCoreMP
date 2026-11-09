package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Package flight retention: when a PACKED server starts, the oldest {@code <pkg>/run/flight-<yyyyMMdd>-<HHmmss>}
 * folders beyond the host's {@code run.keepFlights} count are deleted. WHY: every start mints a new flight folder (its
 * logs, its receipt and, with recording on, its records), so a package folder that is started for months grew
 * without bound. The launcher sweeps only after every pre-launch check passed and the new flight exists; owner mode
 * (an unpacked folder) never sweeps - the launcher decides, this class never reads the mode.
 *
 * <p>The invariants, for a stranger's disk:
 * <ul>
 * <li>Candidates are only the DIRECT children of {@code run} named exactly {@code flight-[0-9]{8}-[0-9]{6}} that read,
 * without following links, as a plain directory. A symbolic link, a junction (a directory that is also "other" on
 * Windows), a file or any other entry is skipped: never followed, never deleted, never counted.</li>
 * <li>Never a candidate: the new flight, the newest other flight by name, and the flight {@code run/latest.txt} named
 * before this start rewrote it (the name order is wall-clock order, which a backward clock step makes lie).</li>
 * <li>The oldest by NAME go first, until the newest {@code keep} remain.</li>
 * <li>Inside a candidate the walk never follows a link. A first walk checks the whole tree: a link or other entry at
 * ANY depth, an unreadable entry, a folder deeper than {@link #MAX_WALK_DEPTH} or more than {@link #MAX_WALK_FILES}
 * entries keeps the candidate WHOLE and counts it failed. Only a clean tree is deleted, leaf-first, never renamed
 * (a renamed folder would leave the name pattern and never be retried); the delete walk skips a link that appeared
 * since the check, again without descending or deleting it.</li>
 * <li>A file that cannot be deleted (on Windows, a game window from that start still holding its log open) is
 * counted, the walk goes on, and the folder stays, half-empty; it is the oldest candidate again at the next start.</li>
 * </ul>
 * The run folder itself is resolved by the launcher as {@code <pkg>/run}; if a host made {@code run} a junction it is
 * followed (the sweep then runs in the target, which still holds only this program's flight folders).
 *
 *
 */
public final class ServeRetention {

    private ServeRetention() {
    }

    /** The row's blank / absent value: the newest 20 flight folders are kept. */
    public static final int KEEP_DEFAULT = 20;
    /** {@code run.keepFlights=0}: every flight folder is kept, nothing is swept. */
    public static final int KEEP_ALL = 0;
    /** The floor: the new flight plus the newest previous one (a 1 would contradict the never-the-previous rule). */
    public static final int KEEP_MIN = 2;
    /** The ceiling, a typo guard. */
    public static final int KEEP_MAX = 10_000;
    /** The row is ASCII digits only, at most this many, checked BEFORE {@link Integer#parseInt} (which also takes
     *  non-ASCII digits). */
    static final int MAX_ROW_DIGITS = 9;
    /** A refusal echoes at most this many chars of the host's value. */
    static final int MAX_ECHO_CHARS = 40;
    /** A flight folder's exact name, the launcher's own stamp; anything else is never a candidate. */
    static final Pattern FLIGHT_NAME = Pattern.compile("flight-[0-9]{8}-[0-9]{6}");
    /** Depth bound below a flight folder (a flight is a handful of levels deep: lives, settings, a game window's own
     *  folders); a deeper tree is kept whole. */
    static final int MAX_WALK_DEPTH = 64;
    /** Entries (files and folders) visited per flight folder; a fuller folder is kept whole. */
    static final int MAX_WALK_FILES = 100_000;
    /** Entries of {@code run} listed per start; a fuller {@code run} is not swept (part of a listing cannot name the
     *  oldest). */
    static final int MAX_LISTED = 100_000;

    /** The console sentence for a folder whose delete failed part-way. */
    static final String IN_USE_TEXT = "a file in it is still open - close the game window still using it;"
            + " the next start tries again";
    /** The console sentence for a folder kept whole by the check walk. */
    static final String UNSAFE_TEXT = "it holds a link to another folder, an unreadable entry or too many entries,"
            + " so it is left for you to remove by hand if it is not needed";

    /**
     * The {@code run.keepFlights} row: trimmed; blank or absent = {@value #KEEP_DEFAULT}; else ASCII digits only (no
     * sign, at most {@value #MAX_ROW_DIGITS}) reading {@value #KEEP_ALL} (keep all) or {@value #KEEP_MIN}..{@value
     * #KEEP_MAX}. Anything else throws {@link IllegalArgumentException} whose message is the refusal's tail (the
     * launcher prefixes the row name). Returns 0 or 2..10000.
     */
    public static int parseKeep(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return KEEP_DEFAULT;
        }
        boolean digits = v.length() <= MAX_ROW_DIGITS;
        for (int i = 0; digits && i < v.length(); i++) {
            char c = v.charAt(i);
            digits = c >= '0' && c <= '9';
        }
        if (!digits) {
            throw new IllegalArgumentException(keepText(v));
        }
        int n = Integer.parseInt(v);
        if (n != KEEP_ALL && (n < KEEP_MIN || n > KEEP_MAX)) {
            throw new IllegalArgumentException(keepText(v));
        }
        return n;
    }

    /** The row refusal's tail, echoing the trimmed host value bounded. */
    static String keepText(String value) {
        return "must be blank (the newest " + KEEP_DEFAULT + " flight folders are kept), 0 (all are kept) or a whole"
                + " number from " + KEEP_MIN + " to " + KEEP_MAX + ", got '" + echo(value) + "'";
    }

    /**
     * The flight folders a sweep removes, oldest first. Of {@code flights} (names in {@code run}; {@code newFlight} is
     * counted even when absent), the oldest by name beyond the newest {@code keep}, minus the never-set: {@code
     * newFlight}, the newest other name, and {@code pointed} (the flight {@code latest.txt} named before this start;
     * null or a non-flight name = none). A protected name inside the oldest range is skipped, never replaced by a
     * younger one. A name that is not exactly a flight name is ignored. {@code keep} = {@value #KEEP_ALL} (plans
     * nothing) or {@value #KEEP_MIN}..{@value #KEEP_MAX}; anything else throws.
     */
    static List<String> plan(Collection<String> flights, String newFlight, String pointed, int keep) {
        if (keep != KEEP_ALL && (keep < KEEP_MIN || keep > KEEP_MAX)) {
            throw new IllegalArgumentException("keep " + keep + " is not 0 or " + KEEP_MIN + ".." + KEEP_MAX);
        }
        if (keep == KEEP_ALL) {
            return List.of();
        }
        TreeSet<String> names = flightNames(flights, newFlight);
        Set<String> never = new HashSet<>();
        never.add(newFlight);
        never.add(pointed);
        for (String n : names.descendingSet()) {
            if (!n.equals(newFlight)) {
                never.add(n);
                break;
            }
        }
        int excess = names.size() - keep;
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (excess-- <= 0) {
                break;
            }
            if (!never.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private static TreeSet<String> flightNames(Collection<String> flights, String newFlight) {
        TreeSet<String> names = new TreeSet<>();
        for (String n : flights) {
            if (n != null && FLIGHT_NAME.matcher(n).matches()) {
                names.add(n);
            }
        }
        if (newFlight != null && FLIGHT_NAME.matcher(newFlight).matches()) {
            names.add(newFlight);
        }
        return names;
    }

    /**
     * One sweep's outcome. {@code kept} = flight folders left in {@code run} (the new one included), {@code removed}
     * = folders deleted whole, {@code failed} = planned folders still there (kept whole by the check walk, or
     * half-deleted), {@code freedBytes} = the bytes of the files deleted (unlinked: on Linux a file a process still
     * holds open frees its space only when it is closed), {@code firstFailed} = the oldest failed folder's name
     * (null when none), {@code firstFailedInUse} = that folder failed at a delete (else the check walk kept it).
     */
    public record Result(int kept, int removed, int failed, long freedBytes, String firstFailed,
            boolean firstFailedInUse) {

        /** The receipt row, a machine token: {@code retention kept=<k> removed=<n> failed=<f> freed_mib=<m>}
         *  ({@code m} = freed bytes / MiB, rounded down). */
        public String receiptRow() {
            return "retention kept=" + kept + " removed=" + removed + " failed=" + failed + " freed_mib="
                    + (freedBytes >> 20);
        }

        /** The host lines: none when nothing was removed and nothing failed (a clean start stays quiet). */
        public List<String> consoleLines(Object run, int keep) {
            List<String> lines = new ArrayList<>(2);
            if (removed > 0) {
                lines.add("serve-launcher: removed " + removed + " old flight folder" + (removed == 1 ? "" : "s")
                        + " from " + run + " (" + (freedBytes >> 20) + " MiB); the newest " + keep + " are kept"
                        + " (run.keepFlights in launcher.properties; 0 keeps all)");
            }
            if (failed > 0) {
                lines.add("serve-launcher warning: " + failed + " old flight folder" + (failed == 1 ? "" : "s")
                        + " could not be removed (the first: " + firstFailed + "): "
                        + (firstFailedInUse ? IN_USE_TEXT : UNSAFE_TEXT));
            }
            return lines;
        }
    }

    /**
     * Sweeps {@code run}: lists it, plans ({@link #plan}) and removes each planned folder. {@code newFlight} = this
     * start's flight folder name, {@code pointed} = what {@code latest.txt} named before this start (null = none),
     * {@code keep} as {@link #plan} takes it. Throws {@link IOException} only when {@code run} cannot be listed or
     * holds more than {@link #MAX_LISTED} entries - then nothing was deleted.
     */
    public static Result sweep(Path run, String newFlight, String pointed, int keep) throws IOException {
        return sweep(run, newFlight, pointed, keep, MAX_WALK_DEPTH, MAX_WALK_FILES, MAX_LISTED);
    }

    /** {@link #sweep(Path, String, String, int)} with its bounds as arguments (the selftest's small bounds). */
    static Result sweep(Path run, String newFlight, String pointed, int keep, int maxDepth, int maxEntries,
            int maxListed) throws IOException {
        List<String> flights = new ArrayList<>();
        int listed = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(run)) {
            for (Path p : s) {
                if (++listed > maxListed) {
                    throw new IOException("more than " + maxListed + " entries");
                }
                String name = p.getFileName().toString();
                if (FLIGHT_NAME.matcher(name).matches() && !linkOrNotFolder(p)) {
                    flights.add(name);
                }
            }
        }
        List<String> doomed = plan(flights, newFlight, pointed, keep);
        int removed = 0;
        int failed = 0;
        long freed = 0L;
        String firstFailed = null;
        boolean inUse = false;
        for (String name : doomed) {
            Removal r = remove(run.resolve(name), maxDepth, maxEntries);
            freed += r.freedBytes();
            if (r.gone()) {
                removed++;
            } else {
                failed++;
                if (firstFailed == null) {
                    firstFailed = name;
                    inUse = r.inUse();
                }
            }
        }
        int kept = flightNames(flights, newFlight).size() - removed;
        return new Result(kept, removed, failed, freed, firstFailed, inUse);
    }

    /**
     * A top-level entry that is a link, a junction, a file or other, read without following links. An entry whose
     * attributes cannot be read stays a candidate name: the check walk then reads it unreadable and keeps it whole,
     * counted failed (so it is seen, never silently skipped, never deleted).
     */
    private static boolean linkOrNotFolder(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return !a.isDirectory() || a.isSymbolicLink() || a.isOther();
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** One folder's removal: {@code gone} = no longer there; {@code inUse} = a delete failed part-way. */
    record Removal(boolean gone, long freedBytes, boolean inUse) {
    }

    /**
     * Removes the flight folder {@code dir}: the check walk first (nothing is deleted unless the whole tree is plain
     * folders and files within the bounds), then the leaf-first delete walk. Never follows, descends or deletes a link.
     */
    static Removal remove(Path dir, int maxDepth, int maxEntries) {
        if (!checked(dir, maxDepth, maxEntries)) {
            return new Removal(false, 0L, false);
        }
        long[] freed = {0L};
        boolean[] failed = {false};
        boolean[] inUse = {false};
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    if (a.isOther() || a.isSymbolicLink()) {
                        failed[0] = true; // a link made since the check: never descended, never deleted
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isOther() || a.isSymbolicLink() || a.isDirectory()) {
                        failed[0] = true; // a link, or a folder at the depth bound: left where it is
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Files.delete(f);
                        freed[0] += a.size();
                    } catch (IOException | RuntimeException held) {
                        failed[0] = true;
                        inUse[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    failed[0] = true;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    if (e != null) {
                        failed[0] = true;
                    }
                    try {
                        Files.delete(d);
                    } catch (IOException | RuntimeException notEmpty) {
                        failed[0] = true; // a child above could not go: this folder stays
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException unwalkable) {
            failed[0] = true;
        }
        boolean gone = !failed[0] && !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        return new Removal(gone, freed[0], !gone && inUse[0]);
    }

    /** The check walk: true = the whole tree is plain folders and files, readable, within both bounds. */
    private static boolean checked(Path dir, int maxDepth, int maxEntries) {
        boolean[] clean = {true};
        int[] entries = {0};
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    if (a.isOther() || a.isSymbolicLink() || ++entries[0] > maxEntries) {
                        clean[0] = false;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    // a link or other entry; a folder reported here sits at the depth bound
                    if (a.isOther() || a.isSymbolicLink() || a.isDirectory() || ++entries[0] > maxEntries) {
                        clean[0] = false;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    clean[0] = false;
                    return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException | RuntimeException unwalkable) {
            clean[0] = false;
        }
        return clean[0];
    }

    /** A host-typed value as a refusal echoes it: printable ASCII ({@code ?} for anything else), cut at
     *  {@value #MAX_ECHO_CHARS} + "...". */
    private static String echo(String value) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_ECHO_CHARS) + 3);
        for (int i = 0; i < value.length() && i < MAX_ECHO_CHARS; i++) {
            char c = value.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        if (value.length() > MAX_ECHO_CHARS) {
            sb.append("...");
        }
        return sb.toString();
    }
}
