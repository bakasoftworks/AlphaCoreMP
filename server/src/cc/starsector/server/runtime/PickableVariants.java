package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 * a ROOT-only scan of one mod {@code data/variants} folder (subfolders drop naturally, so the
 * {@code npc/} split is enforced by construction — W7d) intersected with an INJECTED
 * resolvability predicate (the engine-registry check stays the caller's; this class is pure
 * runtime, game-free and contract-testable). Fail-closed per file: a candidate that fails the
 * stem law or the resolver is SKIPPED with a loud line, never listed (the allowlist law:
 * an unresolvable id never reaches a player).
 *
 * <p>Security: ids here come from LOCAL disk filenames, never the wire; the wire
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

    /** One immutable listing: fold(id) -> canonical id + the case-insensitive-sorted ids + the folds
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
     * Scan ONE {@code data/variants} root: direct {@code *.variant} children only ({@code npc/}
     * and every other subfolder drop — they are directories), stem (filename minus extension) =
     * candidate id (stem==variantId verified for every root file,
     * then again when the folder grew — re-check the invariant when adding a variant), kept only
     * when {@code resolvable} accepts it. A missing/unlistable root is LOUD-empty, never a throw:
     * {@code /ship} is a player nicety and must not fail a serve (the door replies unavailable).
     */
    public static PickableVariants scan(File root, Predicate<String> resolvable) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(resolvable, "resolvable");
        File[] children = root.listFiles();
        if (children == null) {
            System.out.println("pickable-variants scan_root_unlistable root=" + root);
            return empty();
        }
        List<String> stems = new ArrayList<String>();
        for (File child : children) {
            if (!child.isFile()) {
                continue; // npc/ + any other subfolder: not pickable by construction (W7d)
            }
            String name = child.getName();
            if (!name.endsWith(".variant")) {
                continue;
            }
            String stem = name.substring(0, name.length() - ".variant".length());
            if (!stemLawful(stem)) {
                System.out.println("pickable-variants skip_bad_stem file=" + name);
                continue;
            }
            if (isReservedCustomId(stem)) {
                // The server-minted id space is reserved —
                // a kit file wearing it would collide with a live custom after pick folding.
                System.out.println("pickable-variants skip_reserved_prefix file=" + name);
                continue;
            }
            if (!resolvable.test(stem)) {
                System.out.println("pickable-variants skip_unresolvable id=" + stem);
                continue;
            }
            stems.add(stem);
        }
        View scanned = fold(stems);
        System.out.println("pickable-variants scan root=" + root + " pickable=" + scanned.ids().size());
        PickableVariants scannedVariants = new PickableVariants(scanned);
        // Each LISTED stem's displayName, read from its own local file
        // under a byte cap; an unreadable or unparsable file = no name, counted into ONE line per scan.
        // The same single parse also yields the hullId; a file without
        // one is counted into ONE hull_unread line, never a line per file.
        int unread = 0;
        int hullUnread = 0;
        for (String id : scanned.ids()) {
            String[] labels = readNameAndHullOrNull(new File(root, id + ".variant"));
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
     * hullId}}, each null when absent or blank; the whole result is null when the file is over
     * {@link #NAME_READ_MAX_BYTES}, unreadable or not JSON. WHY one parse: the scan wants both labels and the file
     * read is the cost. Never throws.
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
            return new String[] {name.trim().isEmpty() ? null : name, hull.trim().isEmpty() ? null : hull};
        } catch (Exception unreadable) {
            return null;
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
        return new PickableVariants(fold(stems));
    }

    /** The one sort + fold-collision law the builders and the overlay share (first-sorted wins, loud). */
    private static View fold(List<String> stems) {
        List<String> sorted = new ArrayList<String>(stems);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> canonicalByFold = new LinkedHashMap<String, String>();
        List<String> ids = new ArrayList<String>(sorted.size());
        for (String stem : sorted) {
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
                merged.add(id);
                listedPromoted.add(id);
            }
        }
        View folded = fold(merged);
        listedPromoted.retainAll(folded.ids()); // a fold duplicate among the promoted drops loud in fold()
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

    /** Canonical ids, case-insensitive-sorted (the listing order). */
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
