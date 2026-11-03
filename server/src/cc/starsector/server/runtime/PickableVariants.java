package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.json.JSONObject;

/**
 * The server-side {@code /ship} pickable-variant allowlist —
 * a scan of each enabled mod's {@code data/variants} folder (its own files, then one subfolder level, the depth the
 * engine itself loads), first root wins; the caller keeps a non-player folder (the kit's own {@code npc/}) off the
 * list by its PATH. The scan is intersected with an INJECTED pick predicate (the engine-registry check
 * stays the caller's; this class is pure runtime, game-free and contract-testable). Fail-closed per file: a candidate
 * that fails the stem law or the predicate is SKIPPED with a loud line, never listed (the allowlist law:
 * an unresolvable id never reaches a player).
 *
 * <p>Security: ids here come from LOCAL disk files (a file's own {@code variantId}, else its
 * filename), never the wire; the wire
 * {@code /ship} arg only SELECTS among them via {@link #canonicalIdOrNull} (ASCII fold compare),
 * so the stored/consumed value is always a server-authored canonical id. Stem law: 1..64 chars,
 * printable ASCII 0x21..0x7e (no spaces) — anything else is skipped loud.
 *
 * <p>ONE volatile overlay {promoted, hidden} over the boot base.
 * Every holder shares this one instance (the door, the pick surface, the chat dispatch), so an
 * {@link #applyOverlay} swap reaches {@code /ship} SET + LIST, the probe listing, the HELLO pick and
 * {@code /share} at once; each reader takes ONE immutable snapshot per call.
 */
public final class PickableVariants {

    /** Longest stem admitted as a candidate id (listing lines + replies stay law-bounded). */
    public static final int ID_MAX_CHARS = 64;

    /** One immutable listing: fold(id) -> canonical id + the ids in listing order ({@link #ids}) + the folds
     * the overlay and the host-hidden layer HIDE (a threading guard: a pick store re-checks {@link #isHidden} under
     * its own monitor, so a demote racing a resolve-then-store never survives). */
    private record View(Map<String, String> canonicalByFold, List<String> ids, Set<String> hiddenFolds) {
    }

    /** The overlay's result: the BASE ids this apply newly hid (the caller clears their standing
     * picks, Q1) and the promoted ids now listed.
     * {@code newlyShown} = the ids hidden before the call and listed
     * after it, canonical spelling, listing order (a host Show answers its own room line from it). */
    public record Applied(List<String> newlyHidden, List<String> listedPromoted, List<String> newlyShown) {
    }

    /** The boot base (scan / fromIds), never mutated — every overlay recomputes from it. */
    private final View base;
    /** The live listing = base + promoted - hidden - host-hidden; one volatile write per recompute
     * ({@link #applyOverlay} or {@link #setHostHidden}). */
    private volatile View view;
    /**
     * The host-hidden layer — the ASCII folds of the BASE ids the host
     * took off {@code /ship}, joined into every recompute's {@code hiddenFolds}. A layer of its own (never part of
     * the overlay's {@code hidden} argument) so the catalog lane's whole-overlay replace can never un-hide a host
     * hide, and a host set can never touch a promoted or minted id (only base folds land here). Guarded by
     * {@code this}.
     */
    private final Set<String> hostHiddenFolds = new HashSet<String>();
    /** The last {@link #applyOverlay} inputs, so a host-layer recompute
     * keeps the current overlay (a host set never un-promotes or un-hides an overlay id). Guarded by {@code this}. */
    private List<String> overlayPromoted = Collections.emptyList();
    private List<String> overlayHidden = Collections.emptyList();
    /**
     * The SPAWN view = the boot base filtered through the operator-token
     * grammar ({@link OperatorCommandFile#joinerDefaultTokenLawful}: 1..64 chars of {@code [A-Za-z0-9._:-]}). A
     * {@code /spawnfoe} or {@code /spawnfriend} pick becomes an operator deposit that a match recording replays by
     * re-parsing it under that grammar, so one id outside it would refuse the whole recording; the full view keeps
     * every id ({@code /debugships} replays its pick verbatim, never re-parsed). The grammar admits both ASCII cases
     * and the fold lowers A-Z only, so an id and its fold are lawful together: the two views never disagree on a
     * fold. Built once at construction from the base; the overlay never reaches it (the only overlaid instance is
     * the curated {@code /ship} scan, whose readers never ask for the spawn view).
     */
    private final View spawn;
    /**
     * Id -> the ship's display name, keyed by the canonical id AND its
     * ASCII fold. DISPLAY ONLY: a name never reaches a path, a command or any lookup but this map; the listing
     * emitter sanitizes it to the seat-name law before it rides a ship row. Filled by {@link #scan} from each file's
     * {@code displayName} and by {@link #rememberName} at a promotion's registration. Guarded by {@code this}.
     */
    private final Map<String, String> displayNames = new HashMap<String, String>();
    /**
     * Id -> the ship's HULL id, keyed like {@link #displayNames} (the
     * canonical id AND its ASCII fold). WHY it is held here: {@code /ship <text>} filters on the server before any
     * client sees a row, so a player who types the hull ("retri") must find the ships built on it here. Search and
     * display only, never a lookup key or a path; the listing emitter bounds it before it rides a ship row. Filled by
     * {@link #scan} from each file's {@code hullId} and by {@link #rememberHull} at a promotion's registration.
     * Guarded by {@code this}.
     */
    private final Map<String, String> hullIds = new HashMap<String, String>();
    /**
     * Kept copy id -> the time it was kept, epoch milliseconds (&gt; 0),
     * keyed like {@link #displayNames} (the canonical id AND its ASCII fold). WHY it is held here: every ship list
     * paints the server's row order, and a host who keeps a ship many times needs the newest copy first to tell the
     * copies apart. ORDER only, never a lookup key, a path or a wire field. Filled by {@link #rememberKeptAt} at a
     * kept copy's registration; like the name and hull maps it is never pruned (an id no longer listed is never
     * asked for, and a re-keep of the same id overwrites). Guarded by {@code this}.
     */
    private final Map<String, Long> keptAtMs = new HashMap<String, Long>();

    /** The cap on one {@code .variant} file read for its display name. */
    static final int NAME_READ_MAX_BYTES = 64 * 1024;

    /** The variant file suffix the walk admits (case-sensitive, as the scan always matched it). */
    private static final String VARIANT_SUFFIX = ".variant";

    private PickableVariants(View base) {
        this.base = base;
        this.view = base;
        this.spawn = spawnViewOf(base);
    }

    /**
     * The base's ids the operator-token grammar admits, listing order and
     * fold keys kept; one loud line per id it drops (registry ids are local data, never wire text; the shown id is
     * folded printable and bounded all the same). Nothing dropped = the base itself, no second map.
     */
    private static View spawnViewOf(View base) {
        Map<String, String> canonicalByFold = new LinkedHashMap<String, String>();
        List<String> ids = new ArrayList<String>(base.ids().size());
        for (String id : base.ids()) {
            if (!OperatorCommandFile.joinerDefaultTokenLawful(id)) {
                String shown = WireTextLaw.foldUnprintable(id);
                System.out.println("pickable-variants skip_out_of_grammar id="
                        + (shown.length() > ID_MAX_CHARS ? shown.substring(0, ID_MAX_CHARS) : shown));
                continue;
            }
            canonicalByFold.put(ChatCommands.asciiLower(id), id);
            ids.add(id);
        }
        if (ids.size() == base.ids().size()) {
            return base;
        }
        return new View(Collections.unmodifiableMap(canonicalByFold), Collections.unmodifiableList(ids),
                Collections.emptySet());
    }

    /** The no-allowlist instance (no root found / legacy door): {@code /ship} replies unavailable. */
    public static PickableVariants empty() {
        return new PickableVariants(new View(Collections.emptyMap(), Collections.emptyList(), Collections.emptySet()));
    }

    /**
     * Scan ONE {@code data/variants} root under the
     * {@link #scan(List, Predicate, Set)} law with no folder excluded (the root's own files, then one subfolder level;
     * each file keyed by its own {@code variantId}, else its stem), kept only when {@code resolvable} accepts the id.
     * A missing/unlistable root is LOUD-empty, never a throw:
     * {@code /ship} is a player nicety and must not fail a serve (the door replies unavailable).
     */
    public static PickableVariants scan(File root, Predicate<String> resolvable) {
        Objects.requireNonNull(root, "root");
        return scan(List.of(root), resolvable);
    }

    /** The kit's own mod id ({@code mod_info.json} "id"; its display NAME is "AlphaCoreMP"). */
    public static final String KIT_MOD_ID = "cc_client";

    /**
     * The kit's root FIRST, by mod ID. WHY: the launcher sorts enabled mods by
     * display NAME ({@code ModManager} sorts {@code availableMods} on {@code getName()}), so the kit's root led
     * the walk only because "AlphaCoreMP" happens to sort first — a mod whose name sorts earlier would have become
     * root 0 = the wire catalog's root and the dup-stem winner. {@code ids.get(i)} is the mod id of
     * {@code roots.get(i)}; no kit id among them = the list as given plus ONE loud line (root 0 is then NOT the
     * kit). Pure, game-free, contract-tested (PICK-ROOTS-KIT).
     */
    public static List<File> kitRootFirst(List<String> ids, List<File> roots) {
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(roots, "roots");
        if (ids.size() != roots.size()) {
            throw new IllegalArgumentException("ids/roots size mismatch: " + ids.size() + " vs " + roots.size());
        }
        int kit = ids.indexOf(KIT_MOD_ID);
        if (kit < 0) {
            System.out.println("pickable-variants kit_root_missing id=" + KIT_MOD_ID + " roots=" + roots.size());
            return new ArrayList<File>(roots);
        }
        List<File> ordered = new ArrayList<File>(roots.size());
        ordered.add(roots.get(kit));
        for (int i = 0; i < roots.size(); i++) {
            if (i != kit) {
                ordered.add(roots.get(i));
            }
        }
        return ordered;
    }

    /**
     * The multi-root scan with no folder excluded — every holder that has no
     * non-player folder to name; {@link #scan(List, Predicate, Set)} carries the law.
     */
    public static PickableVariants scan(List<File> roots, Predicate<String> pickable) {
        return scan(roots, pickable, Set.of());
    }

    /**
     * Scan EVERY enabled {@code data/variants} root, in the order given (root 0
     * = the kit's own, placed first BY MOD ID through {@link #kitRootFirst} — the launcher's own order is by display
     * name).
     *
     * <p>WALK: per root, the root's own {@code *.variant} files, then each IMMEDIATE subfolder's own files, the
     * subfolders in case-insensitive name order — ONE level, never deeper, because the engine itself loads a variants
     * root and one level below it, so a deeper file never reaches the registry the predicate asks. A hidden or
     * dot-named subfolder is skipped (the engine skips those too). A subfolder in {@code excludedFolders} is skipped
     * WHOLE, compared by canonical path and never by name: a mod may keep player ships in a folder of any name, so only
     * the caller knows which one folder (the kit's own {@code npc/}) holds variants no player may pick.
     *
     * <p>KEY: each candidate file is parsed ONCE. Its id is the file's own {@code variantId} when that passes the stem
     * law (the engine registers a variant by its {@code variantId}, so a stem that differs from it would never resolve),
     * else the file stem: the engine strips comments before its own parse and this parse may refuse a file the engine
     * reads, so an unparsable file still lists under the name it most likely carries, and those stem fallbacks are
     * counted into ONE {@code id_from_stem} line. On that id, in order: the stem law, the reserved custom prefix, the
     * cross-root duplicate (a fold an EARLIER root already listed is skipped loud, so the kit's own files outrank a
     * mod's and a later mod can never re-point a listed id), then the predicate — one loud line per refusal. The
     * winning file's display name and hull come from that same parse.
     *
     * <p>ORDER: per root, that root's listed ids case-insensitively sorted (a same-root fold collision keeps the first
     * sorted, loud), the roots appended in rank order, so the kit's own ships lead every list. One {@code scan} line
     * per listed root, then ONE {@code scan_total} line; no listable root at all = {@link #empty()}.
     */
    public static PickableVariants scan(List<File> roots, Predicate<String> pickable, Set<File> excludedFolders) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(pickable, "pickable");
        Objects.requireNonNull(excludedFolders, "excludedFolders");
        int[] canonicalFallbacks = new int[1];
        Set<File> excluded = new HashSet<File>();
        for (File folder : excludedFolders) {
            excluded.add(canonicalOrAbsolute(Objects.requireNonNull(folder, "excludedFolder"), canonicalFallbacks));
        }
        List<String> baseIds = new ArrayList<String>();
        Map<String, String[]> labelsById = new HashMap<String, String[]>();
        Set<String> earlierFolds = new HashSet<String>();
        int listedRoots = 0;
        int idFromStem = 0;
        for (File root : roots) {
            Objects.requireNonNull(root, "root");
            File[] children = root.listFiles();
            if (children == null) {
                System.out.println("pickable-variants scan_root_unlistable root=" + root);
                continue;
            }
            listedRoots++;
            List<String> rootIds = new ArrayList<String>();
            Set<String> rootFolds = new HashSet<String>();
            for (File file : variantFilesOf(children, excluded, canonicalFallbacks)) {
                String name = file.getName();
                String[] labels = readNameAndHullOrNull(file);
                String parsedId = labels == null ? null : labels[2];
                boolean keyedByFile = parsedId != null && stemLawful(parsedId);
                if (!keyedByFile) {
                    idFromStem++;
                }
                String id = keyedByFile ? parsedId : name.substring(0, name.length() - VARIANT_SUFFIX.length());
                if (!stemLawful(id)) {
                    System.out.println("pickable-variants skip_bad_stem file=" + name);
                    continue;
                }
                if (isReservedCustomId(id)) {
                    // The server-minted id space is reserved —
                    // a kit file wearing it would collide with a live custom after pick folding.
                    System.out.println("pickable-variants skip_reserved_prefix file=" + name);
                    continue;
                }
                String fold = ChatCommands.asciiLower(id);
                if (earlierFolds.contains(fold)) {
                    System.out.println("pickable-variants skip_dup_stem file=" + name + " root=" + root);
                    continue;
                }
                if (!pickable.test(id)) {
                    System.out.println("pickable-variants skip_refused id=" + id);
                    continue;
                }
                rootIds.add(id);
                rootFolds.add(fold);
                if (!labelsById.containsKey(id)) {
                    labelsById.put(id, labels); // a same-root repeat of one id: the first file walked labels it
                }
            }
            rootIds.sort(String.CASE_INSENSITIVE_ORDER);
            baseIds.addAll(rootIds);
            // Distinct folds = what foldOrdered() lists from this root alone (a same-root case collision lists once).
            System.out.println("pickable-variants scan root=" + root + " pickable=" + rootFolds.size());
            earlierFolds.addAll(rootFolds);
        }
        if (canonicalFallbacks[0] > 0) {
            System.out.println("pickable-variants canonical_path_fallback n=" + canonicalFallbacks[0]);
        }
        if (listedRoots == 0) {
            return empty();
        }
        View scanned = foldOrdered(baseIds);
        System.out.println("pickable-variants scan_total roots=" + roots.size()
                + " pickable=" + scanned.ids().size());
        PickableVariants scannedVariants = new PickableVariants(scanned);
        if (idFromStem > 0) {
            System.out.println("pickable-variants id_from_stem n=" + idFromStem);
        }
        // Each LISTED id's displayName, from its own local file under a byte
        // cap; an unreadable or unparsable file = no name, counted into ONE line per scan.
        // The same single parse also yields the hullId; a file without
        // one is counted into ONE hull_unread line, never a line per file.
        int unread = 0;
        int hullUnread = 0;
        for (String id : scanned.ids()) {
            String[] labels = labelsById.get(id);
            String name = labels == null ? null : labels[0];
            String hull = labels == null ? null : labels[1];
            if (hull == null) {
                hullUnread++;
            } else {
                scannedVariants.rememberHull(id, hull);
            }
            if (name == null) {
                unread++;
                continue;
            }
            scannedVariants.rememberName(id, name);
        }
        if (unread > 0) {
            System.out.println("pickable-variants name_unread n=" + unread);
        }
        if (hullUnread > 0) {
            System.out.println("pickable-variants hull_unread n=" + hullUnread);
        }
        return scannedVariants;
    }

    /**
     * One local {@code .variant} file's {@code displayName}, or null when the
     * file is larger than {@link #NAME_READ_MAX_BYTES}, unreadable, not JSON, or carries no non-blank name.
     * Never throws.
     */
    static String readDisplayNameOrNull(File file) {
        String[] labels = readNameAndHullOrNull(file);
        return labels == null ? null : labels[0];
    }

    /**
     * ONE parse of a local {@code .variant} file into {@code {displayName,
     * hullId, variantId}}, each null when absent or blank ({@code variantId} also when it is not a JSON string, so a
     * number or an object never becomes an id); the whole result is null when the file is over
     * {@link #NAME_READ_MAX_BYTES}, unreadable or not JSON. WHY one parse: the scan keys the file by its id and labels
     * it from the same read, and the file read is the cost. Never throws: every failure reads as null, which the scan
     * counts ({@code id_from_stem}, {@code name_unread}, {@code hull_unread}).
     */
    static String[] readNameAndHullOrNull(File file) {
        try {
            if (file.length() > NAME_READ_MAX_BYTES) {
                return null;
            }
            byte[] body = Files.readAllBytes(file.toPath());
            if (body.length > NAME_READ_MAX_BYTES) {
                return null;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String name = json.optString("displayName", "");
            String hull = json.optString("hullId", "");
            Object variant = json.opt("variantId");
            String variantId = variant instanceof String && !((String) variant).trim().isEmpty() ? (String) variant : null;
            return new String[] {name.trim().isEmpty() ? null : name, hull.trim().isEmpty() ? null : hull, variantId};
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * One root's candidate files in walk order — the root's own
     * {@code *.variant} files, then each immediate subfolder's own, subfolders AND the files inside each folder in
     * case-insensitive name order (so which file labels a same-root repeat of one id never hangs on the filesystem's
     * listing order). Never recursive: a subfolder's own subfolders are not walked (the engine loads one level). A
     * hidden or dot-named subfolder is skipped silently, as the engine skips it; an excluded one (by canonical path)
     * and an unlistable one each print ONE loud line.
     */
    private static List<File> variantFilesOf(File[] rootChildren, Set<File> excluded, int[] canonicalFallbacks) {
        List<File> files = new ArrayList<File>();
        List<File> folders = new ArrayList<File>();
        for (File child : byName(rootChildren)) {
            if (child.isFile()) {
                if (child.getName().endsWith(VARIANT_SUFFIX)) {
                    files.add(child);
                }
            } else if (child.isDirectory()) {
                folders.add(child);
            }
        }
        for (File folder : folders) {
            if (folder.isHidden() || folder.getName().startsWith(".")) {
                continue;
            }
            if (!excluded.isEmpty() && excluded.contains(canonicalOrAbsolute(folder, canonicalFallbacks))) {
                System.out.println("pickable-variants skip_excluded_folder dir=" + folder);
                continue;
            }
            File[] inner = folder.listFiles();
            if (inner == null) {
                System.out.println("pickable-variants scan_folder_unlistable dir=" + folder);
                continue;
            }
            for (File file : byName(inner)) {
                if (file.isFile() && file.getName().endsWith(VARIANT_SUFFIX)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /** A copy of {@code files} in case-insensitive name order (stable). */
    private static File[] byName(File[] files) {
        File[] sorted = files.clone();
        Arrays.sort(sorted, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    /**
     * {@code folder}'s canonical form for the exclusion compare (links and
     * {@code ..} resolved, so two spellings of one folder match and two same-named folders under different roots never
     * do); an I/O failure falls back to the absolute form, counted into ONE {@code canonical_path_fallback} line per
     * scan.
     */
    private static File canonicalOrAbsolute(File folder, int[] fallbacks) {
        try {
            return folder.getCanonicalFile();
        } catch (IOException unresolvable) {
            fallbacks[0]++;
            return folder.getAbsoluteFile();
        }
    }

    /**
     * The display name remembered for {@code id}, looked up by the canonical
     * spelling first, then by its ASCII fold; null when none is known. Display only.
     */
    public synchronized String displayNameOrNull(String id) {
        if (id == null) {
            return null;
        }
        String name = displayNames.get(id);
        return name != null ? name : displayNames.get(ChatCommands.asciiLower(id));
    }

    /**
     * Remember {@code id}'s display name under the id AND its ASCII fold. A
     * null id or a null/blank name is a no-op. One map put each; no I/O.
     */
    public synchronized void rememberName(String id, String displayNameOrNull) {
        if (id == null || displayNameOrNull == null || displayNameOrNull.trim().isEmpty()) {
            return;
        }
        displayNames.put(id, displayNameOrNull);
        displayNames.put(ChatCommands.asciiLower(id), displayNameOrNull);
    }

    /**
     * The hull id remembered for {@code id}, by the canonical spelling
     * first, then by its ASCII fold; null when none is known. Search and display only.
     */
    public synchronized String hullIdOrNull(String id) {
        if (id == null) {
            return null;
        }
        String hull = hullIds.get(id);
        return hull != null ? hull : hullIds.get(ChatCommands.asciiLower(id));
    }

    /**
     * Remember {@code id}'s hull id under the id AND its ASCII fold, the
     * twin of {@link #rememberName}: every registration site that names a ship also remembers its hull, so the
     * {@code /ship} filter and the ship rows know it. A null id or a null/blank hull is a no-op; no I/O.
     */
    public synchronized void rememberHull(String id, String hullIdOrNull) {
        if (id == null || hullIdOrNull == null || hullIdOrNull.trim().isEmpty()) {
            return;
        }
        hullIds.put(id, hullIdOrNull);
        hullIds.put(ChatCommands.asciiLower(id), hullIdOrNull);
    }

    /**
     * The time {@code id} was kept (epoch ms, &gt; 0), by the canonical
     * spelling first, then by its ASCII fold; null when the id is not a kept copy or its time is unknown. The ship
     * lists' newest-first order reads it ({@link ListingEmitter#keptNewestFirst}).
     */
    public synchronized Long keptAtMsOrNull(String id) {
        if (id == null) {
            return null;
        }
        Long at = keptAtMs.get(id);
        return at != null ? at : keptAtMs.get(ChatCommands.asciiLower(id));
    }

    /**
     * Remember when kept copy {@code id} was kept, under the id AND its
     * ASCII fold, the twin of {@link #rememberName}. A null id or a time &lt;= 0 (no lawful epoch time) is a no-op;
     * no I/O.
     */
    public synchronized void rememberKeptAt(String id, long ms) {
        if (id == null || ms <= 0L) {
            return;
        }
        keptAtMs.put(id, ms);
        keptAtMs.put(ChatCommands.asciiLower(id), ms);
    }

    /**
     * Build an allowlist from ids ALREADY resolved by the caller — the {@link #scan}
     * law minus the file walk (stem law → case-insensitive sort → fold-collision dedup,
     * first-sorted wins loud). The {@code /debugships} debug catalogue rides this with the
     * engine-registry keyset: every id came FROM the registry, so resolvability holds by
     * construction (no predicate; the fly-in's {@code override_unresolvable} fallback backstops
     * a registry id that later deploys strangely — that risk is the admin's own).
     */
    public static PickableVariants fromIds(Collection<String> rawIds) {
        Objects.requireNonNull(rawIds, "rawIds");
        List<String> stems = new ArrayList<String>(rawIds.size());
        for (String raw : rawIds) {
            if (raw == null || !stemLawful(raw)) {
                System.out.println("pickable-variants skip_bad_stem id=" + raw);
                continue;
            }
            if (isReservedCustomId(raw)) {
                System.out.println("pickable-variants skip_reserved_prefix id=" + raw); // W15 step 3(d)
                continue;
            }
            stems.add(raw);
        }
        return new PickableVariants(foldSorted(stems));
    }

    /**
     * The SORTING fold — case-insensitive sort, then {@link #foldOrdered}, so
     * the first-sorted spelling of a fold wins, loud. For a list with no order of its own: the {@link #fromIds}
     * registry keyset and the promoted segment of the overlay.
     */
    private static View foldSorted(List<String> stems) {
        List<String> sorted = new ArrayList<String>(stems);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return foldOrdered(sorted);
    }

    /**
     * The ORDER-KEEPING fold — fold-collision dedup in list order (the first
     * spelling of a fold wins, loud), never a sort. WHY a second form: the scan base is ordered by root rank, and
     * re-sorting it here (at boot or at every overlay recompute) would put a mod's ships ahead of the kit's again.
     */
    private static View foldOrdered(List<String> stems) {
        Map<String, String> canonicalByFold = new LinkedHashMap<String, String>();
        List<String> ids = new ArrayList<String>(stems.size());
        for (String stem : stems) {
            String prior = canonicalByFold.putIfAbsent(ChatCommands.asciiLower(stem), stem);
            if (prior != null) {
                System.out.println("pickable-variants skip_fold_collision id=" + stem
                        + " kept=" + prior);
                continue;
            }
            ids.add(stem);
        }
        return new View(Collections.unmodifiableMap(canonicalByFold), Collections.unmodifiableList(ids),
                Collections.emptySet());
    }

    /**
     * Replace the overlay WHOLE — poll-idempotent,
     * so an Undo is simply the next apply without the stem. {@code promoted} = ids the caller already
     * REGISTERED and proved resolvable, held to the {@link #fromIds} law (stem law, reserved prefix);
     * one whose fold collides with a BASE id is skipped loud (boot disk wins). {@code hidden} = ids
     * whose fold leaves the listing — base or promoted; hidden wins. Writers serialize here; a reader
     * sees the old snapshot or the new one, never a mix.
     * The host-hidden layer ({@link #setHostHidden}) is unioned into
     * {@code hidden} on every apply, so this whole-overlay replace never lists a host-hidden base id again.
     */
    public synchronized Applied applyOverlay(Collection<String> promoted, Collection<String> hidden) {
        Objects.requireNonNull(promoted, "promoted");
        Objects.requireNonNull(hidden, "hidden");
        overlayPromoted = Collections.unmodifiableList(new ArrayList<String>(promoted));
        overlayHidden = Collections.unmodifiableList(new ArrayList<String>(hidden));
        Recomputed r = recompute();
        System.out.println("pickable-variants overlay promoted=" + r.applied().listedPromoted().size()
                + " hidden=" + r.baseHidden() + " newly_hidden=" + r.applied().newlyHidden().size()
                + " pickable=" + r.pickable());
        return r.applied();
    }

    /**
     * Replace the host-hidden layer WHOLE with {@code ids} and recompute
     * the listing under the CURRENT overlay. Only ids the boot BASE holds ({@link #baseCanonicalOrNull} non-null)
     * land, stored as their canonical spelling's fold; a promoted, kept or minted id (or null) is ignored, since a
     * fold of one of those would delist a kept ship or refuse an own-custom pick. The returned {@code newlyHidden}
     * = base ids that left the listing, {@code newlyShown} = base ids that re-entered it, both canonical. One row
     * per call; no I/O.
     */
    public synchronized Applied setHostHidden(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        Set<String> folds = new HashSet<String>();
        for (String id : ids) {
            String canonical = baseCanonicalOrNull(id);
            if (canonical != null) {
                folds.add(ChatCommands.asciiLower(canonical));
            }
        }
        hostHiddenFolds.clear();
        hostHiddenFolds.addAll(folds);
        Recomputed r = recompute();
        System.out.println("pickable-variants host_hidden=" + hostHiddenFolds.size() + " newly_hidden="
                + r.applied().newlyHidden().size() + " newly_shown=" + r.applied().newlyShown().size()
                + " pickable=" + r.pickable());
        return r.applied();
    }

    /** One recompute's result — the caller's {@link Applied}, the base ids
     * the combined hidden set covers, and the new listing's size (each caller prints its own row from it). */
    private record Recomputed(Applied applied, int baseHidden, int pickable) {
    }

    /**
     * The ONE listing recompute both writers share — base + the last
     * overlay's promoted - (the last overlay's hidden folds UNION the host-hidden layer), published with one
     * volatile write. Callers hold {@code this}.
     * ORDER = the base's own order minus its hidden ids, then the kept
     * promoted ids case-insensitively sorted among themselves ({@link #foldSorted}), the whole through the
     * order-keeping {@link #foldOrdered}: an overlay apply or a host hide never re-sorts the base's root rank.
     */
    private Recomputed recompute() {
        List<String> promoted = overlayPromoted;
        Set<String> hiddenFolds = new HashSet<String>(hostHiddenFolds);
        for (String h : overlayHidden) {
            if (h != null) {
                hiddenFolds.add(ChatCommands.asciiLower(h));
            }
        }
        View prior = view;
        List<String> merged = new ArrayList<String>();
        List<String> newlyHidden = new ArrayList<String>();
        int baseHidden = 0;
        for (String id : base.ids()) {
            String fold = ChatCommands.asciiLower(id);
            if (!hiddenFolds.contains(fold)) {
                merged.add(id);
                continue;
            }
            baseHidden++;
            if (prior.canonicalByFold().containsKey(fold)) {
                newlyHidden.add(id);
            }
        }
        List<String> listedPromoted = new ArrayList<String>();
        for (String id : promoted) {
            if (id == null || !stemLawful(id) || isReservedCustomId(id)) {
                System.out.println("pickable-variants overlay_skip_bad_id id=" + PeerText.quote(String.valueOf(id)));
                continue;
            }
            String fold = ChatCommands.asciiLower(id);
            if (base.canonicalByFold().containsKey(fold)) {
                System.out.println("pickable-variants overlay_skip_base_collision id=" + PeerText.quote(id)
                        + " kept=" + base.canonicalByFold().get(fold));
                continue;
            }
            if (!hiddenFolds.contains(fold)) {
                listedPromoted.add(id);
            }
        }
        merged.addAll(foldSorted(listedPromoted).ids()); // a fold duplicate among the promoted drops loud here
        View folded = foldOrdered(merged);
        listedPromoted.retainAll(folded.ids());
        View next = new View(folded.canonicalByFold(), folded.ids(), Collections.unmodifiableSet(hiddenFolds));
        List<String> newlyShown = new ArrayList<String>();
        for (String id : next.ids()) {
            if (prior.hiddenFolds().contains(ChatCommands.asciiLower(id))) {
                newlyShown.add(id);
            }
        }
        view = next;
        return new Recomputed(new Applied(Collections.unmodifiableList(newlyHidden),
                Collections.unmodifiableList(listedPromoted), Collections.unmodifiableList(newlyShown)),
                baseHidden, next.ids().size());
    }

    /** Canonical ids in the listing order — the base first, by root rank (the
     * kit's own root leads), case-insensitive within a root (a {@link #fromIds} base is one sorted run); the promoted
     * ids after the base, case-insensitive among themselves. */
    public List<String> ids() {
        return view.ids();
    }

    /** True while the live overlay or the host-hidden layer HIDES this id's fold (a demote, Q1): the pick
     * stores re-check under their monitor right before the put. Null-safe (false). */
    public boolean isHidden(String id) {
        return id != null && view.hiddenFolds().contains(ChatCommands.asciiLower(id));
    }

    /** True when the boot BASE already lists this id's fold — the promote lane refuses such a stem
     * BEFORE any engine pass (a case-variant of a shipped stem would register + fan + spool for the
     * serve and still never list). */
    public boolean baseHoldsFold(String id) {
        return id != null && base.canonicalByFold().containsKey(ChatCommands.asciiLower(id));
    }

    /** The boot BASE's canonical id for this id's fold, null when the base lacks it —
     * the promote lane's exact-vs-case-variant split (the overlay's hides never move the base row). */
    public String baseCanonicalOrNull(String id) {
        return id == null ? null : base.canonicalByFold().get(ChatCommands.asciiLower(id));
    }

    /**
     * The canonical ids whose ASCII fold CONTAINS the folded {@code raw} —
     * the {@code /debugships <text>} substring-filter listing (no exact match ⇒ filter, D1).
     * Pure select-among-allowlist like {@link #canonicalIdOrNull}; listing order preserved.
     * An id also matches when the fold of its remembered display name
     * or hull id contains the folded {@code raw} ({@link #matchingIn}), so {@code /ship retri} finds the ships
     * built on that hull and {@code /ship alpha} the ones named so. An instance with no names or hulls (the
     * {@link #fromIds} catalogues) filters on the id alone, as before.
     */
    public List<String> idsMatching(String raw) {
        return matchingIn(view, raw);
    }

    /** Case-insensitive (ASCII fold) lookup: the canonical id, or null when not pickable. */
    public String canonicalIdOrNull(String raw) {
        return canonicalIn(view, raw);
    }

    /** The spawn view's canonical ids, listing order — the
     * {@code /spawnfoe} and {@code /spawnfriend} picker and the officer-table warm read this, never {@link #ids}. */
    public List<String> spawnIds() {
        return spawn.ids();
    }

    /** {@link #canonicalIdOrNull} over the spawn view — the same fold
     * and length law, so an id outside the operator-token grammar resolves to null here and still resolves there. */
    public String spawnCanonicalIdOrNull(String raw) {
        return canonicalIn(spawn, raw);
    }

    /** {@link #idsMatching} over the spawn view (the spawn verbs'
     * substring-filter listing never offers an id its deposit could not replay). */
    public List<String> spawnIdsMatching(String raw) {
        return matchingIn(spawn, raw);
    }

    /**
     * The ONE fold-substring filter both views share.
     * An id of the view matches when the ASCII fold of the id, of its
     * remembered display name or of its remembered hull id CONTAINS the folded {@code raw}. The result is still a
     * list of the view's own canonical ids in listing order, so a name or a hull only SELECTS among them and never
     * becomes a pick by itself. One monitor hold for the whole walk (the two maps are guarded by {@code this}).
     */
    private synchronized List<String> matchingIn(View v, String raw) {
        Objects.requireNonNull(raw, "raw");
        String fold = ChatCommands.asciiLower(raw);
        List<String> matches = new ArrayList<String>();
        for (String id : v.ids()) {
            if (ChatCommands.asciiLower(id).contains(fold) || foldContains(displayNameOrNull(id), fold)
                    || foldContains(hullIdOrNull(id), fold)) {
                matches.add(id);
            }
        }
        return matches;
    }

    /** true when {@code labelOrNull}'s ASCII fold contains {@code fold}. */
    private static boolean foldContains(String labelOrNull, String fold) {
        return labelOrNull != null && ChatCommands.asciiLower(labelOrNull).contains(fold);
    }

    /** The ONE bounded fold lookup both views share. */
    private static String canonicalIn(View v, String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() > ID_MAX_CHARS) {
            return null;
        }
        return v.canonicalByFold().get(ChatCommands.asciiLower(raw));
    }

    /** W15 v0 step 3(d): the {@link SubmittedVariantLedger#ID_PREFIX} space, CASE-FOLDED (the
     * pick lookup folds, so {@code CC-V1-x} would collide with a minted {@code cc-v1-x}). */
    public static boolean isReservedCustomId(String id) {
        return id != null && ChatCommands.asciiLower(id).startsWith(SubmittedVariantLedger.ID_PREFIX);
    }

    private static boolean stemLawful(String stem) {
        if (stem.isEmpty() || stem.length() > ID_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
