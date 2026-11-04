package cc.starsector.server.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import cc.starsector.server.ops.InstalledModScan.InstalledMod;
import cc.starsector.server.runtime.ModSet;

/**
 * The Mods-page row model and its pure helpers: which mods the NEXT serve loads. One {@link ModRow}
 * per mod the {@code mods.roots} folders hold, plus every id the props rows name that no folder
 * holds ({@link #NOT_INSTALLED_SUFFIX}: the launcher fails closed on it, so it is shown, never
 * dropped); the {@code Use} choices are {@link #MOD_CHOICES}. The web console's Mods page (Add
 * folder, Remove, Use, Rescan in {@link OpsConsole}) reads {@link #modRows}, {@link #joinChoices},
 * {@link #classifyFolder}, {@link #rootToken} and {@link #rootsWithout}; nothing here touches a
 * file but the folder probe of {@link #classifyFolder}.
 *
 * <p>Rows: {@code mods.enabled} = the ids set {@code required} (the serve loads them, every joiner
 * must run them), {@code mods.optional} = the ids set {@code optional} (joiners MAY run them;
 * presentation-only, the serve never loads them), {@code mods.roots} = the searched folders (a
 * PRESENT row is the truth even when blank, an ABSENT row = {@link InstalledModScan#DEFAULT_ROOTS}).
 * {@code cc_client} is always on and never listed ({@link ModSet#EXCLUDED_ID}).
 *
 * <p>WHY statics only: the web console is the one operator shell; the class keeps its name so the
 * row model's callers stay put.
 *
 *
 * <p>The Needs column, which makes it obvious when one mod is needed for
 * another. {@link #declaredDeps} reads each row's declared dependencies once (the launch's own
 * reader); {@link #dependsCell} composes the row's cell from them, pure: what it needs, what needs it, and the
 * consequence at the next start (an unticked mod a required one needs loads anyway; a missing dependency, of a
 * required mod or of a library one pulls in, or an optional mod caught in a dependency law refuses the start).
 * Presentation only: the launch rules stay in {@link PackageModSync}.
 */
public final class OpsModsPanel {

    private OpsModsPanel() {
    }

    static final String MOD_OFF = "off";
    /** = {@code mods.enabled}: the serve loads it, every joiner must run it. */
    static final String MOD_REQUIRED = "required";
    /** = {@code mods.optional}: joiners MAY run it (presentation-only, never loaded by the serve). */
    static final String MOD_OPTIONAL = "optional";
    static final List<String> MOD_CHOICES = List.of(MOD_OFF, MOD_REQUIRED, MOD_OPTIONAL);
    /** Suffix on a props-named id no searched folder holds (the launcher fails closed on it). */
    static final String NOT_INSTALLED_SUFFIX = " (not installed)";
    static final String ADD_HINT = "Pick a mod's own folder (it holds mod_info.json) or a folder"
            + " that contains mods";
    /** One Mods-page row: the id, its declared name / version (first folder's), the {@code Use}
     * choice and every folder holding the id (empty = "(not installed)"; two = ambiguous). */
    record ModRow(String id, String name, String version, String choice, List<InstalledMod> folders) {
        boolean installed() {
            return !folders.isEmpty();
        }

        ModRow withChoice(String c) {
            return new ModRow(id, name, version, c, folders);
        }
    }

    // ---- pure helpers (selftest-pinned) ----

    /**
     * The table rows: one per id the scan found ({@link ModSet#EXCLUDED_ID} never listed; a
     * folder-count over one = the launcher's ambiguity, shown in the Folder cell), sorted by
     * display name (case-insensitive, then id), then every id {@code mods.enabled} /
     * {@code mods.optional} name that the scan lacks, id-sorted, "(not installed)". Prefill: in
     * {@code mods.enabled} ⇒ required, else in {@code mods.optional} ⇒ optional, else off — an id
     * in BOTH rows (a state the serve refuses) shows as required, the one the host can change.
     */
    static List<ModRow> modRows(List<InstalledMod> installed, List<String> propsLines) {
        List<String> enabled = splitIds(OpsMatchRows.modsEnabledFieldPrefill(propsLines));
        List<String> optional = splitIds(OpsMatchRows.modsOptionalFieldPrefill(propsLines));
        Map<String, List<InstalledMod>> byId = new TreeMap<>();
        for (InstalledMod m : installed) {
            if (!ModSet.EXCLUDED_ID.equals(m.id())) {
                byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(m);
            }
        }
        List<ModRow> out = new ArrayList<>();
        for (Map.Entry<String, List<InstalledMod>> e : byId.entrySet()) {
            InstalledMod first = e.getValue().get(0);
            out.add(new ModRow(e.getKey(), first.name().trim(), first.version().trim(),
                    choiceFor(e.getKey(), enabled, optional), List.copyOf(e.getValue())));
        }
        out.sort((a, b) -> {
            int c = displayName(a).compareToIgnoreCase(displayName(b));
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        TreeSet<String> missing = new TreeSet<>();
        for (String id : enabled) {
            if (!byId.containsKey(id)) {
                missing.add(id);
            }
        }
        for (String id : optional) {
            if (!byId.containsKey(id)) {
                missing.add(id);
            }
        }
        missing.remove(ModSet.EXCLUDED_ID);
        for (String id : missing) {
            out.add(new ModRow(id, "", "", choiceFor(id, enabled, optional), List.of()));
        }
        return out;
    }

    static String displayName(ModRow r) {
        return r.name().isEmpty() ? r.id() : r.name();
    }

    private static String choiceFor(String id, List<String> enabled, List<String> optional) {
        return enabled.contains(id) ? MOD_REQUIRED : optional.contains(id) ? MOD_OPTIONAL : MOD_OFF;
    }

    /** A props list split the launcher's way ({@code ,}/{@code ;}, trimmed, empties dropped). */
    static List<String> splitIds(String csv) {
        List<String> out = new ArrayList<>();
        for (String t : csv.split("[,;]")) {
            if (!t.trim().isEmpty() && !out.contains(t.trim())) {
                out.add(t.trim());
            }
        }
        return out;
    }

    /** The ids whose choice equals {@code want}, in row order, comma-joined (pure). */
    static String joinChoices(List<ModRow> rows, String want) {
        List<String> out = new ArrayList<>();
        for (ModRow r : rows) {
            if (want.equals(r.choice())) {
                out.add(r.id());
            }
        }
        return String.join(",", out);
    }

    // ---- the Needs column: declared dependencies and what the next start does with them ----

    //
    /** The Needs cell's flag on an unticked row the next start loads anyway (a required mod needs it). */
    static final String DEPENDS_ON = "on";
    //
    /** The Needs cell's flag on a row the next start refuses over (one of the launch's dependency laws). */
    static final String DEPENDS_REFUSE = "refuse";

    //
    /**
     * One row's Needs cell: {@code text} = the host's sentence ({@code ""} = nothing to say), {@code flag} =
     * {@code ""}, {@link #DEPENDS_ON} or {@link #DEPENDS_REFUSE} (the page's emphasis class on the cell). WHY two
     * fields: the page shows the text and styles the cell from the flag, never by parsing the text.
     */
    record DependsCell(String text, String flag) {
    }

    //
    /**
     * Each row's DECLARED dependencies, keyed by id in row order. An installed row's = the {@code dependencies[].id}
     * of its FIRST folder's {@code mod_info.json} (the folder the Folder cell shows), read by the launch's own reader
     * {@link PackageModSync#declaredDependencies} (the engine's {@code #} comment strip, lawful-checked ids, its read
     * cap, its lenient regex), minus
     * {@link ModSet#EXCLUDED_ID} (always enabled, never a row); a not-installed row maps to an empty list. WHY the
     * launch's reader: the Needs column ({@link #dependsCell}) must say what the next start will do, and the start
     * pulls dependencies in with exactly this read. The column's one file read;
     * everything downstream is pure over this map.
     */
    static Map<String, List<String>> declaredDeps(List<ModRow> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ModRow r : rows) {
            List<String> deps = new ArrayList<>();
            if (r.installed()) {
                for (String dep : PackageModSync.declaredDependencies(
                        r.folders().get(0).folder().resolve(InstalledModScan.MOD_INFO))) {
                    if (!ModSet.EXCLUDED_ID.equals(dep)) {
                        deps.add(dep);
                    }
                }
            }
            out.put(r.id(), List.copyOf(deps));
        }
        return out;
    }

    //
    /**
     * The Needs cell of row {@code r} (pure over {@code rows} and their {@link #declaredDeps}). Lines, joined with
     * {@code "; "}: (a) the consequence at the next start, when any - a row a required mod pulls in ({@link #pulledVia})
     * with a direct dependency no searched folder holds refuses the start, that dependency's id named on THIS row
     * (checked first: the launch pulls the library in, then cannot find what it needs); else such a row
     * that is unticked loads anyway ({@link #DEPENDS_ON}); one that is optional refuses the start (the launch's
     * "optional and also required" law); a required row with a direct dependency no searched folder holds refuses
     * the start (missing mod); an optional row with a direct dependency the start neither requires nor pulls in
     * refuses the start (an optional mod's libraries must be required) - the launch's laws;
     *(b) {@code needs <names>} when the row declares any dependency;
     * (c) {@code needed by <names>}: every listed row that DIRECTLY declares this id, in row order. Names are the
     * Mod cell's own: an installed row's display name, else {@code <id> (not installed)}. WHY: a host who unticks
     * a library a required mod needs would otherwise see it loaded at the next start with no word on the page.
     */
    static DependsCell dependsCell(ModRow r, List<ModRow> rows, Map<String, List<String>> deps) {
        Map<String, String> pulled = pulledVia(rows, deps);
        Set<String> required = new HashSet<>();
        for (ModRow x : rows) {
            if (MOD_REQUIRED.equals(x.choice())) {
                required.add(x.id());
            }
        }
        List<String> mine = directDeps(r.id(), deps);
        List<String> lines = new ArrayList<>();
        String flag = "";
        String via = pulled.get(r.id());
        // A library the start pulls in is resolved and its own
        // dependencies pulled in after it, so one of them no searched folder holds refuses the start; checked
        // first, the launch reaches that refusal before its optional-mod laws, and an on-anyway line would lie
        String missing = null;
        for (int i = 0; via != null && missing == null && i < mine.size(); i++) {
            if (!installedRow(mine.get(i), rows)) {
                missing = mine.get(i);
            }
        }
        if (missing != null) {
            flag = DEPENDS_REFUSE;
            lines.add("START REFUSES: " + via + " needs it, and it needs " + missing + ", which is not installed");
        } else if (via != null && MOD_OFF.equals(r.choice())) {
            flag = DEPENDS_ON;
            lines.add("ON ANYWAY: " + via + " needs it (it loads at the next start)");
        } else if (via != null && MOD_OPTIONAL.equals(r.choice())) {
            flag = DEPENDS_REFUSE;
            lines.add("START REFUSES: " + via + " needs it required - set it to required or untick it");
        } else if (MOD_REQUIRED.equals(r.choice())) {
            for (String dep : mine) {
                if (!installedRow(dep, rows)) {
                    flag = DEPENDS_REFUSE;
                    lines.add("START REFUSES: needs " + dep + ", which is not installed");
                    break;
                }
            }
        } else if (MOD_OPTIONAL.equals(r.choice())) {
            for (String dep : mine) {
                if (!required.contains(dep) && !pulled.containsKey(dep)) {
                    String name = depName(dep, rows);
                    flag = DEPENDS_REFUSE;
                    lines.add("START REFUSES: needs " + name + " required - set " + name + " to required");
                    break;
                }
            }
        }
        if (!mine.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String dep : mine) {
                names.add(depName(dep, rows));
            }
            lines.add("needs " + String.join(", ", names));
        }
        List<String> consumers = new ArrayList<>();
        for (ModRow x : rows) {
            if (directDeps(x.id(), deps).contains(r.id())) {
                consumers.add(depName(x.id(), rows));
            }
        }
        if (!consumers.isEmpty()) {
            lines.add("needed by " + String.join(", ", consumers));
        }
        return new DependsCell(String.join("; ", lines), flag);
    }

    //
    /**
     * The ids the next start loads only because a REQUIRED row needs them, each mapped to the display name of the
     * required row that first reached it: breadth-first from every required id in row order through {@code deps},
     * transitively - the launch's own pull-in, whose queue starts with the enabled
     * ids, so a required id is never "pulled". An id {@code deps} does not key (an unlisted one) reaches nothing
     * further; {@link ModSet#EXCLUDED_ID} is skipped (always enabled). WHY the ROOT's name where the launch records
     * the direct consumer: the host's lever is the required mod they ticked.
     */
    static Map<String, String> pulledVia(List<ModRow> rows, Map<String, List<String>> deps) {
        List<String[]> queue = new ArrayList<>();
        for (ModRow x : rows) {
            if (MOD_REQUIRED.equals(x.choice())) {
                queue.add(new String[] {x.id(), depName(x.id(), rows)});
            }
        }
        int roots = queue.size();
        Set<String> seen = new HashSet<>();
        Map<String, String> pulled = new LinkedHashMap<>();
        for (int i = 0; i < queue.size(); i++) {
            String id = queue.get(i)[0];
            String via = queue.get(i)[1];
            if (ModSet.EXCLUDED_ID.equals(id) || !seen.add(id)) {
                continue;
            }
            if (i >= roots) {
                pulled.put(id, via);
            }
            for (String dep : directDeps(id, deps)) {
                queue.add(new String[] {dep, via});
            }
        }
        return pulled;
    }

    //
    /** {@code id}'s declared dependencies from {@code deps} ({@code []} when unkeyed), minus {@link ModSet#EXCLUDED_ID}. */
    private static List<String> directDeps(String id, Map<String, List<String>> deps) {
        List<String> out = new ArrayList<>();
        for (String dep : deps.getOrDefault(id, List.of())) {
            if (!ModSet.EXCLUDED_ID.equals(dep)) {
                out.add(dep);
            }
        }
        return out;
    }

    //
    /** Whether {@code id} is a listed row that a searched folder holds. */
    private static boolean installedRow(String id, List<ModRow> rows) {
        for (ModRow x : rows) {
            if (x.id().equals(id) && x.installed()) {
                return true;
            }
        }
        return false;
    }

    //
    /** An id as the Needs cell prints it = its Mod cell: a listed installed row's {@link #displayName}, else
     * {@code <id>} + {@link #NOT_INSTALLED_SUFFIX} (listed or not, the Mod cell's own not-installed text). */
    private static String depName(String id, List<ModRow> rows) {
        for (ModRow x : rows) {
            if (x.id().equals(id) && x.installed()) {
                return displayName(x);
            }
        }
        return id + NOT_INSTALLED_SUFFIX;
    }

    /** What a picked folder is: {@code "mod"} (holds {@code mod_info.json}), {@code "folder"}
     * (an immediate subfolder does), else null (nothing to add). */
    static String classifyFolder(Path f) {
        if (f == null || !Files.isDirectory(f)) {
            return null;
        }
        if (Files.isRegularFile(f.resolve(InstalledModScan.MOD_INFO))) {
            return "mod";
        }
        return InstalledModScan.scanRoots(f.getParent() == null ? f : f.getParent(),
                List.of(f.toString())).isEmpty() ? null : "folder";
    }

    /** The {@code mods.roots} token for a folder: repo-relative when it lies under the repo root (the
     * default entries' own shape), else absolute - BOTH with {@code /} only. WHY no backslash even in
     * an absolute token: the packed launcher loads the config as {@link java.util.Properties}, which
     * eats every backslash and throws on a lowercase backslash-u part (a root under the host's Windows
     * profile folder lost the mod or refused the whole config); {@code /} survives that load and
     * {@link InstalledModScan#resolveRoot} accepts either slash. */
    static String rootToken(Path repoRoot, Path folder) {
        Path abs = folder.toAbsolutePath().normalize();
        Path base = repoRoot.toAbsolutePath().normalize();
        if (abs.startsWith(base) && !abs.equals(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString().replace('\\', '/');
    }

    /**
     * The roots after {@code victim} leaves: its root token is dropped when the root IS the mod's
     * folder; a folder-of-mods root is replaced, in place, by the tokens of its OTHER mods
     * ({@code all} = the current scan), so the row keeps naming exactly what the list shows.
     * Tokens that resolve elsewhere are kept verbatim. = {@link #rootsWithout(List, Path, InstalledMod, List, Path)}
     * with no kept root (owner mode).
     */
    static List<String> rootsWithout(List<String> roots, Path repoRoot, InstalledMod victim,
            List<InstalledMod> all) {
        return rootsWithout(roots, repoRoot, victim, all, null);
    }

    /**
     * {@link #rootsWithout(List, Path, InstalledMod, List)} with a KEPT root: {@code keptRoot} = a packed server's
     * {@code <pkg>/serve-mods} ({@link PackageModSync#MODS_DIR}), null in owner mode (= the 4-arg result, byte-equal).
     * A token resolving to {@code keptRoot} is never dropped or split (a mod inside stays listed, its id off). A
     * token resolving to a DIRECT child of {@code keptRoot} ({@code serve-mods/<name>}, the split an older Remove
     * wrote) is folded back: the first becomes {@code keptRoot}'s token ({@link #rootToken}) unless a token already
     * names {@code keptRoot}, the rest are dropped - so mods that split removed show again (their ids stay off). A
     * row naming neither keeps every other token under the 4-arg rule. WHY: the packed launcher copies every chosen
     * mod INTO {@code serve-mods} (PackageModSync), so un-searching it would hide what the next start serves; taking
     * a mod off the list is deleting its folder there (the host's). Pure; the callers pass a null row when the
     * result equals the absent default (an ABSENT row stays absent).
     *
     *
     */
    static List<String> rootsWithout(List<String> roots, Path repoRoot, InstalledMod victim,
            List<InstalledMod> all, Path keptRoot) {
        Path kept = keptRoot == null ? null : keptRoot.toAbsolutePath().normalize();
        boolean keptNamed = false;
        for (String token : kept == null ? List.<String>of() : roots) {
            Path r = InstalledModScan.resolveRoot(repoRoot, token);
            keptNamed |= r != null && r.toAbsolutePath().normalize().equals(kept);
        }
        boolean folded = false;
        Path victimRoot = victim.root().toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (String token : roots) {
            Path r = InstalledModScan.resolveRoot(repoRoot, token);
            Path ra = r == null || kept == null ? null : r.toAbsolutePath().normalize();
            if (ra != null && ra.equals(kept)) {
                out.add(token);
                continue;
            }
            if (ra != null && kept.equals(ra.getParent())) {
                if (!keptNamed && !folded) {
                    out.add(rootToken(repoRoot, kept));
                }
                folded = true;
                continue;
            }
            if (r == null || !r.toAbsolutePath().normalize().equals(victimRoot)) {
                out.add(token);
                continue;
            }
            if (victimRoot.equals(victim.folder().toAbsolutePath().normalize())) {
                continue; // the root was this one mod
            }
            for (InstalledMod m : all) {
                if (m.root().toAbsolutePath().normalize().equals(victimRoot)
                        && !m.folder().toAbsolutePath().normalize()
                                .equals(victim.folder().toAbsolutePath().normalize())) {
                    String t = rootToken(repoRoot, m.folder());
                    if (!out.contains(t)) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }
}
