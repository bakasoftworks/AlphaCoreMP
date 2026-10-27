package cc.starsector.server.runtime;

import cc.starsector.server.ops.OpsStatusFile;
import cc.starsector.server.ops.PeerText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The host's ship list on the serve - the hidden-ships file
 * beside the loaded config ({@value #FILE_NAME}), its ONE off-frame writer, and the six ship verbs
 * ({@code ship-hide} / {@code ship-show} from the console, {@code /shipremove} / {@code /shiprestore} /
 * {@code /shiplist} / {@code /shipkeep} from an admin's chat and their house twins) with their receipts.
 *
 * <p>Why its own block, beside the bans one and never inside the customs wiring: hiding a ship that comes
 * with the server must work on a serve whose custom submissions are OFF, so nothing here needs the custom
 * lane. The kept-ship half of the verbs (a kept stem's remove / restore, a keep) is reached ONLY through
 * {@link KeptSide}; {@link KeptSide#NONE} answers "customs are off" and lists nothing.
 *
 * <p>Threads: {@link #boot} (the file READ) runs on the serve thread before the door opens; every other
 * entry runs on the serve's sim thread (the operator drain) and is memory-only - a hide or show hands the
 * set's render to {@link HiddenShipsPersistSink#persist} (an enqueue) and never touches the file itself.
 * {@link #stats} reads only volatile / self-locked state, so the status compose may call it.
 *
 * <p>Failure posture: a DAMAGED file (unreadable, over the size cap, bad UTF-8) is a memory-only serve
 * with one loud row and a {@code ships.last} that says hides are not saved - never a refused boot (a
 * hidden list is not a security list), and the sink is never opened, so the host's file is never
 * overwritten. A file-less serve (no config loaded) is memory-only too.
 *
 * <p>The listing floor: no verb may leave {@code /ship} empty (the join probe, {@code /ship} and the
 * joiner default all need one listed ship). The floor reads the live listing MINUS the kept stems whose
 * remove is queued but not yet applied (the writer moves the files, a rescan drops the stem later), so two
 * removes inside one writer round trip cannot both pass. The same floor holds at boot.
 */
final class HiddenShipsLane {

    /** The per-server hidden-ships file, beside the loaded config. */
    static final String FILE_NAME = "cc-server.hidden-ships";
    /** The receipt / list line bound (the chat text law). */
    static final int LINE_MAX_CHARS = ChatFrameCodec.TEXT_MAX_CHARS;
    /** Per-id boot rows printed before the rest collapse into one count. */
    static final int MAX_BOOT_ID_ROWS = 16;

    // ---- receipt heads (the client keys on '<head><id as typed>: ') ----
    static final String HEAD_HIDE = "ship-hide ";
    static final String HEAD_SHOW = "ship-show ";
    static final String HEAD_REMOVE = "shipremove ";
    static final String HEAD_RESTORE = "shiprestore ";
    static final String HEAD_KEEP = "shipkeep ";
    static final String HEAD_LIST = "shiplist ";

    // ---- verdict tails + refusals (each refusal <= 43 chars: a 64-char id still fits 120) ----
    static final String REMOVED = "removed - it leaves /ship for every player";
    static final String PUT_BACK = "put back - on /ship for every player";
    static final String HIDDEN = "hidden - it leaves /ship for every player";
    static final String SHOWN = "shown - back on /ship for every player";
    static final String QUEUED_REMOVING = "removing";
    static final String QUEUED_RESTORING = "restoring";
    static final String NOT_A_SHIP = "not a ship on this server";
    static final String ALREADY_REMOVED = "already removed";
    static final String LAST_LISTED = "the last listed ship stays";
    static final String DEFAULT_SHIP = "the default ship - change it in the console";
    static final String NOTHING_TO_PUT_BACK = "nothing to put back";
    static final String CUSTOMS_OFF = "customs are off on this server";
    static final String NOT_SHIPPED = "not a ship that comes with the server";
    static final String ALREADY_HIDDEN = "already hidden";
    static final String SET_DEFAULT_FIRST = "set another default first (set-joiner-default)";
    static final String NOT_HIDDEN = "not hidden";
    static String listFull() {
        return "list full (" + HiddenShipSet.MAX_ENTRIES + ")";
    }
    /** The supersede's QUEUED tail ({@code CustomVariantLane.keep}'s host wording) - the keep took the job. */
    static final String QUEUED_SAVING = "saving";
    /** A supersede refusal names the ship it could not replace: {@code cannot replace '<name>' - <phrase>} (a refusal
     *  body never opens with a quote: the editor's success test). */
    static final String CANNOT_REPLACE_HEAD = "cannot replace '";
    static final String REPLACE_DEFAULT = "it is the joiner default";

    /**
     * {@code /shipkeep <id> replaces <X>} rides the verb's ONE text as {@code "<id> replaces <X>"} (the parsers
     * {@code ChatCommands.shipKeepReplaces} / the operator file's {@code ship-keep} arm build exactly that shape);
     * this is the one splitter every reader of a keep text uses, so the receipt head stays {@code shipkeep <id>: }
     * - the editor keys its pending slot on that prefix. Answer: {@code [id, X]}, or {@code [text, null]} for a
     * plain keep (any other shape is the text itself: never built by a parser, so the head echoes it as typed).
     *
     */
    static String[] splitKeep(String text) {
        if (text != null && text.indexOf(' ') > 0) {
            String[] parts = text.split(" ", -1);
            if (parts.length == 3 && ChatCommands.SHIPKEEP_REPLACES_WORD.equals(parts[1])) {
                return new String[] { parts[0], parts[2] };
            }
        }
        return new String[] { text, null };
    }

    /** The per-seat line a hide's pick clear mails (the door's {@code custom '<name>' <phrase> - ...} shape). */
    static final String HIDDEN_PICK_PHRASE = "was removed from /ship by the host";
    /** The room line a show fans: {@code '<name>' is back on /ship} (the ship's display name, never its id). */
    static final String SHOWN_ROOM_PHRASE = "is back on /ship";
    /** {@code ships.last} while the boot read failed. */
    static final String UNREADABLE_LAST = "hides are not saved until the hidden ships file is fixed or removed";
    static final String NOT_SAVED_NOTE = "not saved - fix or remove the hidden ships file: ";
    /**
     * The QUEUED tail the console's {@code custom-forget} answers when the writer took the job. The floor's
     * pending set learns a console forget from it; if that wording ever changes, a console forget simply
     * stops counting as pending (the floor then reads the live listing alone), never a wrong refusal.
     */
    static final String CUSTOM_FORGET_QUEUED_TAIL = ": forgetting - the customs line shows the result";

    /** One kept-ship half of the verbs (the custom store). Sim thread only, no I/O. */
    record HostParts(List<String> kept, List<String> removed) {
        HostParts {
            kept = List.copyOf(Objects.requireNonNull(kept, "kept"));
            removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
        }
    }

    /**
     * The custom store's side of the ship verbs, sim thread only. {@code hostParts} = the kept stems on
     * {@code /ship} and the removed kept files a restore can take back, each sorted by ASCII fold; the two
     * lookups answer the canonical stem / the removed token whose fold equals the id's, else null; the three
     * verbs answer their receipt line under their own head ({@code shipkeep <id>: } / {@code shipremove
     * <id>: } / {@code shiprestore <id>: }, the id as typed): a QUEUED line when the writer took the job.
     */
    interface KeptSide {
        HostParts hostParts();

        String keptStemOrNull(String id);

        String removedStemOrNull(String id);

        String shipKeep(OperatorCommandFile.Command c);

        String shipRemoveKept(OperatorCommandFile.Command c, String keptStem);

        String shipRestoreKept(OperatorCommandFile.Command c, String removedStem);

        /** The put-back that re-keeps {@code removedStem} WITH the
         *  {@code supersedes} link {@code supersedesOrNull} (null = none, the plain put-back); a side with no link to
         *  write answers the plain put-back. */
        default String shipRestoreKept(OperatorCommandFile.Command c, String removedStem, String supersedesOrNull) {
            return shipRestoreKept(c, removedStem);
        }

        /** No custom store on this serve: nothing kept, nothing removed, every keep refused. */
        KeptSide NONE = new KeptSide() {
            @Override
            public HostParts hostParts() {
                return new HostParts(List.of(), List.of());
            }

            @Override
            public String keptStemOrNull(String id) {
                return null;
            }

            @Override
            public String removedStemOrNull(String id) {
                return null;
            }

            @Override
            public String shipKeep(OperatorCommandFile.Command c) {
                return HEAD_KEEP + splitKeep(c.text())[0] + ": " + CUSTOMS_OFF;
            }

            @Override
            public String shipRemoveKept(OperatorCommandFile.Command c, String keptStem) {
                return HEAD_REMOVE + c.text() + ": " + CUSTOMS_OFF;
            }

            @Override
            public String shipRestoreKept(OperatorCommandFile.Command c, String removedStem) {
                return HEAD_RESTORE + c.text() + ": " + CUSTOMS_OFF;
            }
        };
    }

    /** One verb's outcome: the receipt line, the save token the serve row prints, and the {@code ships.last} text
     *  (the receipt, the count line for a list, or the receipt behind the not-saved note while the file is damaged). */
    record Verdict(String receipt, String persist, String last) {
        Verdict(String receipt, String persist) {
            this(receipt, persist, receipt);
        }
    }

    private Path file;
    private HiddenShipSet set = new HiddenShipSet();
    private HiddenShipsPersistSink sink;
    private boolean loadFailed;
    private volatile String last = "-";
    private volatile long persistFailedAtClose;
    /** Kept stems whose remove was QUEUED, still in {@link HostParts#kept} or the listing. Sim thread only. */
    private final Set<String> pendingForgets = new LinkedHashSet<>();
    private KeptSide keptSide = KeptSide.NONE;
    /** Hides / shows a re-simulation applied in memory and kept off disk. */
    private long replayUnpersisted;
    private boolean statusSkipLogged;

    // ------------------------------------------------------------------ wiring

    void keptSide(KeptSide side) {
        this.keptSide = Objects.requireNonNull(side, "side");
    }

    long replayUnpersisted() {
        return replayUnpersisted;
    }

    /** The hidden set (its ids keep the file's spelling; only shipped ids take effect). */
    HiddenShipSet set() {
        return set;
    }

    /** The serve thread, before the door opens: {@code <config dir>/cc-server.hidden-ships} through
     *  {@link HiddenShipSet#load}; no config file = memory-only; a damaged file = memory-only + loud. */
    void boot() {
        Path dir = ServerConfig.configFileDirOrNull();
        if (dir == null) {
            file = null;
            set = new HiddenShipSet();
            System.out.println("npeer-serve hidden_ships_file state=memory-only (no config file loaded;"
                    + " hides last for this serve only)");
            return;
        }
        file = dir.resolve(FILE_NAME).toAbsolutePath();
        try {
            set = HiddenShipSet.load(file, line -> System.out.println("npeer-serve " + line));
        } catch (RuntimeException damaged) {
            // path = operator config + the SimpleName, never the message (it can quote host bytes)
            loadFailed = true;
            set = new HiddenShipSet();
            last = UNREADABLE_LAST;
            System.out.println("npeer-serve hidden_ships_file state=unreadable path=" + file
                    + " err=" + damaged.getClass().getSimpleName()
                    + " (memory-only: hides are not saved and the file is never overwritten until it is fixed"
                    + " or removed)");
        }
    }

    /** Right after the door opens, never on a re-simulation: the off-frame writer (none when memory-only). */
    void openSink() {
        sink = file == null || loadFailed ? null
                : HiddenShipsPersistSink.open(file, line -> System.out.println("npeer-serve " + line));
    }

    /** Teardown: drain a pending rewrite (at most 2 s), then stop; the failure count stays readable. */
    void closeSink() {
        HiddenShipsPersistSink s = sink;
        if (s != null) {
            s.close();
            persistFailedAtClose = s.persistFailed();
            sink = null;
        }
    }

    /**
     * The overlay APPLY at boot, after the joiner default is read and before the port publishes (a hide
     * applied before that read would leave the default unpickable and a later show would never restore it).
     * Only shipped ids take effect, in their canonical spelling; any other lawful id stays in the set with
     * no effect (one row each). If the file would empty the listing, the first such ship (by fold) stays
     * listed and leaves the set. A hidden joiner default is tolerated, loud.
     * <p>{@code keptListed} = the kept ships the custom store's boot read accepted (empty = customs off or a
     * re-simulation, where none ever lists). They reach the listing only at the sim thread's first catalog apply,
     * after this runs, so the floor counts them here: one that does not share a shipped ship's name keeps the
     * listing non-empty, and the floor un-hides nothing (a kept twin of a shipped name never lists on its own).
     *
     */
    void applyBoot(PilotIntentDatagramServer door, PickableVariants pickableOrNull, List<String> keptListed) {
        if (pickableOrNull == null) {
            if (set.size() > 0) {
                System.out.println("npeer-serve hidden_ships_apply state=no_ship_list kept=" + set.size()
                        + " (this serve has no /ship list: kept, no effect)");
            }
            return;
        }
        List<String> canonical = new ArrayList<>();
        int noEffect = 0;
        for (String id : set.ids()) {
            String c = pickableOrNull.baseCanonicalOrNull(id);
            if (c != null) {
                canonical.add(c);
                continue;
            }
            noEffect++;
            if (noEffect <= MAX_BOOT_ID_ROWS) {
                System.out.println("npeer-serve hidden_ships_id_no_effect id=" + PeerText.quote(id)
                        + " (not a shipped ship on this serve: kept, no effect)");
            }
        }
        if (noEffect > MAX_BOOT_ID_ROWS) {
            System.out.println("npeer-serve hidden_ships_id_no_effect_more count=" + (noEffect - MAX_BOOT_ID_ROWS));
        }
        int keptCounted = 0;
        for (String stem : keptListed) {
            if (pickableOrNull.baseCanonicalOrNull(stem) == null) {
                keptCounted++;
            }
        }
        if (!keptListed.isEmpty()) {
            System.out.println("npeer-serve hidden_ships_boot kept_listed=" + keptCounted
                    + " kept_read=" + keptListed.size() + " (kept ships the boot floor counts as listed)");
        }
        if (!canonical.isEmpty() && keptCounted == 0 && listingAfter(pickableOrNull, canonical).isEmpty()) {
            canonical.sort(FOLD_ORDER);
            String keep = canonical.remove(0);
            set.remove(keep);
            last = "the hidden ships file would hide every ship - '" + keep + "' stays listed";
            System.out.println("npeer-serve hidden_ships_floor kept_listed=" + keep
                    + " (the file would hide every ship; the first stays listed and leaves the list at the next save)");
        }
        String joinerDefault = door.joinerDefaultVariantOrNull();
        if (joinerDefault != null) {
            for (String c : canonical) {
                if (HiddenShipSet.fold(c).equals(HiddenShipSet.fold(joinerDefault))) {
                    System.out.println("npeer-serve hidden_ships_joiner_default_hidden id=" + c
                            + " (tolerated: it stays the joiner default; set another default to retire it)");
                }
            }
        }
        if (canonical.isEmpty() && noEffect == 0) {
            return; // nothing hidden: the listing stays the scan's (no recompute, no row)
        }
        PickableVariants.Applied applied = pickableOrNull.setHostHidden(canonical);
        if (!applied.newlyHidden().isEmpty()) {
            door.clearSeatVariantOverridesNaming(applied.newlyHidden(), "hidden", HIDDEN_PICK_PHRASE);
        }
        System.out.println("npeer-serve hidden_ships_applied hidden=" + canonical.size() + " no_effect=" + noEffect
                + " listed=" + pickableOrNull.ids().size() + " persist=" + (loadFailed ? "unreadable_file"
                        : file == null ? "memory_only" : "file"));
    }

    // ------------------------------------------------------------------ the verbs (sim thread)

    /** One drained ship verb: classify, apply, answer. The caller mails the receipt and prints the row. */
    Verdict handle(PilotIntentDatagramServer door, PickableVariants pickableOrNull, OperatorCommandFile.Command command) {
        String id = command.text();
        Verdict v = switch (command.verb()) {
            case SHIP_HIDE -> hideBase(door, pickableOrNull, HEAD_HIDE + id + ": ", baseOrNull(pickableOrNull, id),
                    NOT_SHIPPED, ALREADY_HIDDEN, SET_DEFAULT_FIRST, HIDDEN);
            case SHIP_SHOW -> {
                String base = baseOrNull(pickableOrNull, id);
                String head = HEAD_SHOW + id + ": ";
                yield base == null ? refused(head, NOT_SHIPPED)
                        : showWhole(door, pickableOrNull, head, base, promoOrNull(door), NOT_HIDDEN, SHOWN);
            }
            case SHIP_REMOVE -> remove(door, pickableOrNull, command);
            case SHIP_RESTORE -> restore(door, pickableOrNull, command);
            case SHIP_KEEP -> keep(door, pickableOrNull, command);
            case SHIP_LIST -> list(door, pickableOrNull, command);
            default -> throw new IllegalArgumentException("not a ship verb: " + command.verb());
        };
        last = v.last();
        return v;
    }

    /** Base WINS: a shipped id hides even when a kept stem shares its fold; else a kept stem is taken off. */
    private Verdict remove(PilotIntentDatagramServer door, PickableVariants pickableOrNull,
            OperatorCommandFile.Command command) {
        String head = HEAD_REMOVE + command.text() + ": ";
        String base = baseOrNull(pickableOrNull, command.text());
        if (base != null) {
            return hideBase(door, pickableOrNull, head, base, NOT_A_SHIP, ALREADY_REMOVED, DEFAULT_SHIP, REMOVED);
        }
        String stem = pickableOrNull == null ? null : keptSide.keptStemOrNull(command.text());
        if (stem == null) {
            return refused(head, NOT_A_SHIP);
        }
        if (floorRefuses(pickableOrNull, stem)) {
            return refused(head, LAST_LISTED);
        }
        String answer = keptSide.shipRemoveKept(command, stem);
        if ((head + QUEUED_REMOVING).equals(answer)) {
            pendingForgets.add(stem);
        }
        return new Verdict(answer, "custom_store");
    }

    /**
     * {@code /shipkeep <id>} = the kept half's keep, as before. {@code /shipkeep <id> replaces <X>} = THE SUPERSEDE:
     *
     * the host's changed copy
     * takes X's place on {@code /ship}. X = a listed or hidden shipped ship (base wins, as Remove) or a kept stem;
     * neither = a refusal naming X. The refusals run BEFORE the keep and keep nothing: a shipped X that is the live
     * joiner default (change it first, as Remove), a hidden-set cap with X not yet hidden; an already-hidden X is no
     * refusal (it stays hidden, the settle skips the hide) - and when a kept copy already took X's place, the new copy
     * replaces THAT copy instead (the old copy is forgotten, the new one links X, one loud {@code supersede_retarget}
     * row); a plain ship-hide of X (no linked copy kept) still links the
     * new copy to X and forgets nothing. THE FLOOR NEVER REFUSES a supersede: X leaves as the copy
     * arrives, so the listing never empties. A customs-off serve answers so before X is
     * resolved. Then the kept half keeps the copy (its {@code .meta} gains {@code supersedes=<X>}) and the catalog
     * lane is told to run {@link #applySupersede} once the copy LISTS - an identical body ("already kept"), a refusal
     * or a copy that never lists drops it and hides nothing (ruling viii). A supersede of a COPY (load the copy,
     * change, Promote) supersedes the copy: its own X stays hidden and the new copy's link points at the copy.
     * A REMOVED kept X (off {@code /ship}, yet a host's stale ship list still
     * offers it) is replaced through the kept copy that stands in its place now - the copy linked to X's root
     * ({@link #removedRootOf}) - exactly as a hidden X is (same retarget row, plus {@code removed=<X>}); no kept copy
     * stands there (X removed by hand, or its stand-in gone too) = a plain keep that ADDS, with one loud
     * {@code supersede_not_armed} row, never a refusal: nothing on {@code /ship} carries X's place.
     */
    private Verdict keep(PilotIntentDatagramServer door, PickableVariants pickableOrNull,
            OperatorCommandFile.Command command) {
        String[] split = splitKeep(command.text());
        String id = split[0];
        String replaced = split[1];
        if (replaced == null || keptSide == KeptSide.NONE) {
            return new Verdict(keptSide.shipKeep(command), "custom_store");
        }
        String head = HEAD_KEEP + id + ": ";
        String base = baseOrNull(pickableOrNull, replaced);
        CatalogPromotionLane promo = promoOrNull(door);
        // A hidden shipped X that a kept copy already took the place of - the new
        // copy replaces THAT copy (the old copy is forgotten, the new one links X below), so a host who loaded X from a
        // stale list and changed it still ends with ONE changed X on /ship, never a second copy beside the first.
        // With two copies already linked to X, the first in the scan's order is the one replaced.
        // A REMOVED kept X retargets the same way, through the root it stood for
        // (removedRootOf) - else a host who loaded it from a stale list and changed it ADDS beside its stand-in.
        String removed = base != null || pickableOrNull == null ? null : keptSide.removedStemOrNull(replaced);
        String offRoot = base != null ? (set.contains(base) ? base : null)
                : removed == null ? null : removedRootOf(promo, removed);
        String linkedCopy = offRoot != null && promo != null ? promo.copySupersedingOrNull(offRoot) : null;
        String viaCopy = linkedCopy == null ? null : keptSide.keptStemOrNull(linkedCopy);
        String retargetRoot = viaCopy == null ? null : offRoot;
        if (viaCopy != null) {
            base = null;
        }
        if (removed != null && viaCopy == null) {
            return addForRemoved(command, head, id, removed);
        }
        String keptStem = viaCopy != null ? viaCopy
                : base != null || pickableOrNull == null ? null : keptSide.keptStemOrNull(replaced);
        String target = base != null ? base : keptStem;
        if (target == null) {
            return refused(head, CANNOT_REPLACE_HEAD + shipName(pickableOrNull, replaced) + "' - " + NOT_A_SHIP);
        }
        if (base != null) {
            String refusal = replaceRefusalOrNull(door, base);
            if (refusal != null) {
                return refused(head, CANNOT_REPLACE_HEAD + shipName(pickableOrNull, base) + "' - " + refusal);
            }
        }
        // the kept half sees the CANONICAL X: the copy's .meta link must match the stem / id a later scan reads.
        // A supersede OF a kept copy links the new copy to the ROOT
        // the old copy took the place of (the old copy's own .meta link) while the hook below still forgets the old
        // copy: X keeps ONE put-back path ("/shiprestore X" forgets the newest copy) through any number of
        // re-promotes. The link follows the root only while that root is off /ship (a hidden shipped id or a removed
        // kept stem) - a root the host already put back is a ship of its own again, and the link names the copy
        String link = target;
        if (base == null && promo != null) {
            String root = promo.supersededByOrNull(target);
            if (root != null && (set.contains(root) || keptSide.removedStemOrNull(root) != null)) {
                link = root;
            }
        }
        OperatorCommandFile.Command canonical = new OperatorCommandFile.Command(command.verb(), command.seatId(),
                command.simSpeed(), command.autoOn(), id + " " + ChatCommands.SHIPKEEP_REPLACES_WORD + " " + link,
                command.receiptSessionId());
        String answer = keptSide.shipKeep(canonical);
        if (!(head + QUEUED_SAVING).equals(answer)) {
            return new Verdict(answer, "custom_store"); // the keep refused (customs off, replay, unknown id, cap, ...)
        }
        if (retargetRoot != null) {
            System.out.println("npeer-serve supersede_retarget id=" + id + " replaced=" + retargetRoot + " via_copy="
                    + viaCopy + (removed != null ? " removed=" + removed : ""));
        }
        boolean keptX = base == null;
        PickableVariants pickable = pickableOrNull;
        boolean armed = promo != null && promo.expectSupersede(id, target, keptX,
                copy -> applySupersede(door, pickable, target, keptX, copy));
        if (!armed) {
            // the keep still runs and ADDS the copy beside X (the host can Remove X by hand); loud, never silent
            System.out.println("npeer-serve supersede_not_armed id=" + id + " replaced=" + target
                    + " reason=" + (promo == null ? "no_catalog_lane" : "supersedes_cap"));
        }
        return new Verdict(answer, "custom_store");
    }

    /** The supersede's refusals on a shipped X (null = go on): an already-hidden X refuses nothing; the live joiner
     *  default and the hidden-set cap (checked WITHOUT adding) refuse by phrase. The floor is NOT here (ruling). */
    private String replaceRefusalOrNull(PilotIntentDatagramServer door, String base) {
        if (set.contains(base)) {
            return null;
        }
        String joinerDefault = door.joinerDefaultVariantOrNull();
        if (joinerDefault != null && HiddenShipSet.fold(joinerDefault).equals(HiddenShipSet.fold(base))) {
            return REPLACE_DEFAULT;
        }
        if (set.size() >= HiddenShipSet.MAX_ENTRIES) {
            return listFull();
        }
        return null;
    }

    /**
     * The supersede's settle hook - the catalog lane calls it on the sim thread once the copy LISTS (never before:
     * a refused or identical copy leaves X exactly as it was). ORDER: the standing picks on X MOVE to the copy
     * FIRST (no pick-clear line: the hide below then finds no pick naming X; a seat that picked X flies the copy at
     * its next respawn), then X leaves {@code /ship} - a shipped X hides exactly as {@code ship-hide} does (the set,
     * the view, the persist), a kept X is forgotten through the kept half (QUEUED = pending for the floor, as Remove).
     * Returns whether X left (false = the receipt says "added" and a loud row names why). A kept X that leaves takes
     * its own {@code .meta} (and its {@code supersedes} link) into the inbox, where {@link #removedRootOf} reads it.
     */
    private boolean applySupersede(PilotIntentDatagramServer door, PickableVariants pickable, String replaced,
            boolean replacedKept, String copy) {
        int moved = door.moveSeatVariantOverrides(replaced, copy);
        if (!replacedKept) {
            if (set.contains(replaced)) {
                System.out.println("npeer-serve supersede_applied replaced=" + replaced + " copy=" + copy
                        + " picks_moved=" + moved + " (already hidden: the set is unchanged)");
                return true;
            }
            HiddenShipSet.AddResult added = set.add(replaced);
            if (added != HiddenShipSet.AddResult.ADDED) {
                System.out.println("npeer-serve supersede_hide_refused replaced=" + replaced + " copy=" + copy
                        + " reason=" + added);
                return false;
            }
            PickableVariants.Applied applied = pickable.setHostHidden(effectiveIds(pickable));
            if (!applied.newlyHidden().isEmpty()) {
                door.clearSeatVariantOverridesNaming(applied.newlyHidden(), "hidden", HIDDEN_PICK_PHRASE);
            }
            String persist = persist(door);
            System.out.println("npeer-serve supersede_applied replaced=" + replaced + " copy=" + copy
                    + " picks_moved=" + moved + " persist=" + persist);
            return true;
        }
        boolean forgotten = forgetKept(replaced, "supersede");
        System.out.println("npeer-serve supersede_applied replaced=" + replaced + " copy=" + copy
                + " picks_moved=" + moved + " kept_forget=" + (forgotten ? "queued" : "refused"));
        return forgotten;
    }

    /**
     * The ROOT a removed kept stem stood for - the {@code supersedes} link
     * its own {@code .meta} carried when it was kept, which a forget moves into the inbox beside the body
     * ({@link CatalogPromotionLane#removedSupersededByOrNull}) - while that root is still off {@code /ship} (hidden,
     * or a removed kept stem), else the stem itself (no link, no catalog lane, or a root the host put back: a ship of
     * its own again, as the link rule). WHY: a copy of a copy links the ROOT, never the copy it replaced, so only the
     * removed copy's own link says which copy stands in its place now.
     * The link is read from the inbox sidecar, so it survives a restart; a
     * stale ship list that outlives the serve still finds the stand-in.
     * The link is bounded only by the inbox's {@code kept-…} names, which list on
     * their own cap ({@link CatalogPromotionLane#INBOX_LIST_CAP} kept names, apart from the exports), so exports never
     * push a removed ship's link out.
     */
    private String removedRootOf(CatalogPromotionLane promoOrNull, String removed) {
        String root = promoOrNull == null ? null : promoOrNull.removedSupersededByOrNull(removed);
        return root != null && (set.contains(root) || keptSide.removedStemOrNull(root) != null) ? root : removed;
    }

    /**
     * A keep that names a removed kept X no kept copy stands in for (X removed
     * by hand, or its stand-in gone too) drops the replace and ADDS the copy: nothing on {@code /ship} carries X's
     * place, so a refusal would only stop the host putting the changed ship back. One loud row names why.
     */
    private Verdict addForRemoved(OperatorCommandFile.Command command, String head, String id, String removed) {
        String answer = keptSide.shipKeep(new OperatorCommandFile.Command(command.verb(), command.seatId(),
                command.simSpeed(), command.autoOn(), id, command.receiptSessionId()));
        if ((head + QUEUED_SAVING).equals(answer)) {
            System.out.println("npeer-serve supersede_not_armed id=" + id + " replaced=" + removed
                    + " reason=no_linked_copy");
        }
        return new Verdict(answer, "custom_store");
    }

    /** A kept stem leaves through the kept half with no booking (receipt session 0); a QUEUED answer makes it
     *  pending for the floor exactly as a host's Remove does. False = the kept half refused (one row). */
    private boolean forgetKept(String stem, String why) {
        OperatorCommandFile.Command remove = new OperatorCommandFile.Command(OperatorCommandFile.Verb.SHIP_REMOVE,
                0, 0f, false, stem);
        String answer = keptSide.shipRemoveKept(remove, stem);
        if ((HEAD_REMOVE + stem + ": " + QUEUED_REMOVING).equals(answer)) {
            pendingForgets.add(stem);
            return true;
        }
        System.out.println("npeer-serve kept_forget_refused stem=" + stem + " why=" + why
                + " answer=" + PeerText.quote(answer));
        return false;
    }

    /** The catalog lane behind the door's custom lane (the serve installs it on {@code variantSubmissionLane}), or
     *  null = no customs on this serve (a rig door, customs off): the supersede then keeps nothing special. */
    private static CatalogPromotionLane promoOrNull(PilotIntentDatagramServer door) {
        PilotIntentDatagramServer.VariantSubmissionLane lane = door == null ? null : door.variantSubmissionLane;
        return lane instanceof CustomVariantLane custom ? custom.promoOrNull() : null;
    }

    /**
     * {@code /shiprestore X}. A superseded X comes back WHOLE: the copy that took
     * its place (the kept scan's {@code supersedes} link, {@link CatalogPromotionLane#copySupersedingOrNull}) is
     * forgotten and X is listed again - "'<name>' put back - the changed copy is gone"; the standing picks on the copy
     * move back to X first (a shipped X is shown BEFORE the move, or the seed would refuse a hidden id). A kept X is
     * put back through the kept half first (so the listing never empties), then the copy leaves; its final body is
     * the catalog lane's and reads the copy-gone tail from {@link CatalogPromotionLane#noteCopyGone}. No link = a
     * plain put-back, as before.
     * A removed copy of a copy finds its stand-in through the ROOT its own
     * {@code .meta} names ({@link #removedRootOf}) and comes back carrying that link, so the root keeps ONE put-back
     * path; a root already back on {@code /ship} is a ship of its own, so the copy then comes back with no link.
     */
    private Verdict restore(PilotIntentDatagramServer door, PickableVariants pickableOrNull,
            OperatorCommandFile.Command command) {
        String head = HEAD_RESTORE + command.text() + ": ";
        String base = baseOrNull(pickableOrNull, command.text());
        CatalogPromotionLane promo = promoOrNull(door);
        if (base != null) {
            return showWhole(door, pickableOrNull, head, base, promo, NOTHING_TO_PUT_BACK, PUT_BACK);
        }
        String removed = pickableOrNull == null ? null : keptSide.removedStemOrNull(command.text());
        if (removed == null) {
            return refused(head, NOTHING_TO_PUT_BACK);
        }
        return restoreRemoved(door, command, promo, removed);
    }

    /**
     * A hidden shipped X comes back WHOLE under {@code head} - ONE path for
     * {@code /shiprestore} and the Show (the host's README calls Show the same as Put back): the copy a Promote kept
     * in X's place ({@link CatalogPromotionLane#copySupersedingOrNull}) is forgotten, its standing picks move back to
     * X first ("'<name>' put back - the changed copy is gone" under the caller's head), so X and its changed copy
     * never both list. No copy = the plain show ({@code notHidden} / {@code done} = the caller's own wording).
     */
    private Verdict showWhole(PilotIntentDatagramServer door, PickableVariants pickableOrNull, String head, String base,
            CatalogPromotionLane promo, String notHidden, String done) {
        String copy = promo != null && set.contains(base) ? promo.copySupersedingOrNull(base) : null;
        Verdict shown = showBase(door, pickableOrNull, head, base, notHidden, done);
        if (copy == null) {
            return shown; // (a copy exists only for a hidden X, and showBase refuses only an unhidden one)
        }
        door.moveSeatVariantOverrides(copy, base);
        if (!forgetKept(copy, "put-back")) {
            return shown; // X is back; the copy stayed (loud above) - the receipt says so by omission
        }
        String line = head + HostReceiptBook.named(shipName(pickableOrNull, base), HostReceiptBook.PUT_BACK_COPY_GONE);
        return new Verdict(line, shown.persist(), "unreadable_file".equals(shown.persist()) ? NOT_SAVED_NOTE + line : line);
    }

    /**
     * The put back of the REMOVED kept ship {@code removed} - ONE path for
     * {@code /shiprestore} and the console's Keep of that inbox name ({@link #customKeepExportOrNull}). The kept half
     * answers under its own put-back head whatever verb asked, so the QUEUED answer reads
     * {@code shiprestore <id as typed>: restoring} for both.
     */
    private Verdict restoreRemoved(PilotIntentDatagramServer door, OperatorCommandFile.Command command,
            CatalogPromotionLane promo, String removed) {
        String root = removedRootOf(promo, removed);
        // the link rides only while its root is off /ship (the keep's link rule): a link naming a root the host put
        // back would let a later put-back of that root forget this ship
        String link = HiddenShipSet.fold(root).equals(HiddenShipSet.fold(removed)) ? null : root;
        String answer = keptSide.shipRestoreKept(command, removed, link);
        boolean queued = (HEAD_RESTORE + command.text() + ": " + QUEUED_RESTORING).equals(answer);
        String copy = promo != null && queued ? promo.copySupersedingOrNull(root) : null;
        if (copy != null) {
            door.moveSeatVariantOverrides(copy, removed);
            if (forgetKept(copy, "put-back")) {
                promo.noteCopyGone(removed);
            }
        }
        return new Verdict(answer, "custom_store");
    }

    /**
     * The console's Keep of an inbox name ({@code custom-keep-export <name>})
     * that a kept ship the host REMOVED holds is that ship's Put back ({@link #restoreRemoved}): the copy standing in
     * its place leaves and the ship comes back WITH its link - never a plain twin beside its stand-in. Null = not
     * that case (another verb, a name no removed ship holds, no ship list): the caller runs the plain keep-export.
     * Sim thread (the operator drain), like every verb here.
     */
    Verdict customKeepExportOrNull(PilotIntentDatagramServer door, PickableVariants pickableOrNull,
            OperatorCommandFile.Command command) {
        if (command.verb() != OperatorCommandFile.Verb.CUSTOM_KEEP_EXPORT || pickableOrNull == null) {
            return null;
        }
        String removed = keptSide.removedStemOrNull(command.text());
        if (removed == null) {
            return null;
        }
        Verdict v = restoreRemoved(door, command, promoOrNull(door), removed);
        last = v.last();
        return v;
    }

    /**
     * A hide of one shipped id ({@code base} = its canonical spelling, null = not shipped): already hidden,
     * the floor, the live joiner default and the set cap refuse in that order; else the view changes FIRST,
     * then the standing picks naming it clear (one line to each such seat), then the save is ENQUEUED.
     */
    private Verdict hideBase(PilotIntentDatagramServer door, PickableVariants pickable, String head, String base,
            String notShipped, String already, String isDefault, String done) {
        if (base == null) {
            return refused(head, notShipped);
        }
        if (set.contains(base)) {
            return refused(head, already);
        }
        if (floorRefuses(pickable, base)) {
            return refused(head, LAST_LISTED);
        }
        String joinerDefault = door.joinerDefaultVariantOrNull();
        if (joinerDefault != null && HiddenShipSet.fold(joinerDefault).equals(HiddenShipSet.fold(base))) {
            return refused(head, isDefault);
        }
        HiddenShipSet.AddResult added = set.add(base);
        if (added == HiddenShipSet.AddResult.CAP) {
            return refused(head, listFull());
        }
        if (added != HiddenShipSet.AddResult.ADDED) {
            return refused(head, added == HiddenShipSet.AddResult.ALREADY ? already : notShipped);
        }
        PickableVariants.Applied applied = pickable.setHostHidden(effectiveIds(pickable));
        if (!applied.newlyHidden().isEmpty()) {
            door.clearSeatVariantOverridesNaming(applied.newlyHidden(), "hidden", HIDDEN_PICK_PHRASE);
        }
        return saved(door, head + HostReceiptBook.named(shipName(pickable, base), done));
    }

    /** A shipped ship's name as a receipt or room line carries it - its
     *  remembered display name, else the canonical id, bounded ({@link CatalogPromotionLane#chatShipName}). */
    static String shipName(PickableVariants pickable, String canonical) {
        return CatalogPromotionLane.chatShipName(pickable == null ? null : pickable.displayNameOrNull(canonical), canonical);
    }

    /** A show of one shipped id: not in the set refuses; else the view changes, then ONE room line to every
     *  seat for the ship that re-entered the listing (none when another layer still hides it), then the save. */
    private Verdict showBase(PilotIntentDatagramServer door, PickableVariants pickable, String head, String base,
            String notHidden, String done) {
        if (!set.contains(base)) {
            return refused(head, notHidden);
        }
        set.remove(base);
        PickableVariants.Applied applied = pickable.setHostHidden(effectiveIds(pickable));
        for (String shown : applied.newlyShown()) {
            if (HiddenShipSet.fold(shown).equals(HiddenShipSet.fold(base))) {
                // the door's own room fan (seat 0 = every seat, author "server") - the same send the kept-ship
                // notices ride, live with customs on or off
                door.fanServerChat(0, CatalogPromotionLane.catalogLine(shipName(pickable, shown), SHOWN_ROOM_PHRASE, true));
            }
        }
        return saved(door, head + HostReceiptBook.named(shipName(pickable, base), done));
    }

    /** The listing a caller would see: one count line, then each group packed to the chat bound, then the end. */
    private Verdict list(PilotIntentDatagramServer door, PickableVariants pickableOrNull,
            OperatorCommandFile.Command command) {
        List<String> lines = listLines(pickableOrNull, set, keptSide.hostParts());
        // every line but the last is delivered here; the last ('shiplist end') is the arm's receipt
        for (int i = 0; i < lines.size() - 1; i++) {
            if (command.receiptSessionId() != 0L) {
                door.sendAdminReceipt(command.receiptSessionId(), lines.get(i));
            } else {
                System.out.println("npeer-serve operator_ship_list " + lines.get(i));
            }
        }
        return new Verdict(lines.get(lines.size() - 1), "unchanged", lines.get(0));
    }

    /** The /shiplist lines (pure: listed = the shipped ids on /ship, hidden = the set's shipped ids, kept /
     *  removed = the custom store's), each group sorted by fold but kept, which reads newest first
     *  ({@link ListingEmitter#keptNewestFirst}); an empty group still prints its head. */
    static List<String> listLines(PickableVariants pickableOrNull, HiddenShipSet hidden, HostParts parts) {
        List<String> listed = new ArrayList<>();
        List<String> hiddenIds = new ArrayList<>();
        if (pickableOrNull != null) {
            for (String id : pickableOrNull.ids()) {
                if (pickableOrNull.baseCanonicalOrNull(id) != null) {
                    listed.add(id);
                }
            }
            for (String id : hidden.ids()) {
                String c = pickableOrNull.baseCanonicalOrNull(id);
                if (c != null) {
                    hiddenIds.add(c);
                }
            }
        }
        List<String> kept = new ArrayList<>(parts.kept());
        List<String> removed = new ArrayList<>(parts.removed());
        listed.sort(FOLD_ORDER);
        hiddenIds.sort(FOLD_ORDER);
        kept.sort(FOLD_ORDER);
        // The kept group reads newest first, like /ship's kept rows;
        // a copy with no known keep time follows the timed ones in fold order
        kept = ListingEmitter.keptNewestFirst(kept, pickableOrNull == null ? null : pickableOrNull::keptAtMsOrNull);
        removed.sort(FOLD_ORDER);
        List<String> out = new ArrayList<>();
        out.add(HEAD_LIST + listed.size() + " listed, " + hiddenIds.size() + " hidden, " + kept.size() + " kept, "
                + removed.size() + " removed");
        out.addAll(packGroup(HEAD_LIST + "listed:", listed));
        out.addAll(packGroup(HEAD_LIST + "hidden:", hiddenIds));
        out.addAll(packGroup(HEAD_LIST + "kept:", kept));
        out.addAll(packGroup(HEAD_LIST + "removed:", removed));
        out.add(HEAD_LIST + "end");
        return out;
    }

    /** Ids joined {@code ", "} after {@code head}, a new line with the same head whenever the next id would
     *  pass {@link #LINE_MAX_CHARS}; no trailing space (the chat law refuses untrimmed text). */
    static List<String> packGroup(String head, List<String> ids) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder(head);
        boolean any = false;
        for (String id : ids) {
            if (id == null || id.isEmpty() || head.length() + 1 + id.length() > LINE_MAX_CHARS) {
                continue; // a stem past the id law never reaches a list line
            }
            if (any && line.length() + 2 + id.length() > LINE_MAX_CHARS) {
                out.add(line.toString());
                line = new StringBuilder(head);
                any = false;
            }
            line.append(any ? ", " : " ").append(id);
            any = true;
        }
        out.add(line.toString());
        return out;
    }

    /**
     * The console's {@code custom-forget} through the same floor: the refusal line (its own head), or null
     * when the forget may run. Sim thread.
     */
    String customForgetFloorOrNull(PickableVariants pickableOrNull, String stem) {
        if (pickableOrNull == null || stem == null || !floorRefuses(pickableOrNull, stem)) {
            return null;
        }
        String line = "custom-forget " + stem + ": " + LAST_LISTED;
        last = line;
        return line;
    }

    /** After the console's {@code custom-forget} ran: a QUEUED answer makes its stem pending for the floor. */
    void noteCustomForget(String stem, String receipt) {
        if (stem != null && receipt != null && receipt.endsWith(CUSTOM_FORGET_QUEUED_TAIL)) {
            pendingForgets.add(stem);
        }
    }

    /**
     * The floor: true when the listing after taking {@code removingId} off would be empty = the live listing
     * MINUS the pending forgets (pruned first: a stem gone from both the store's kept stems and the listing is
     * no longer pending) MINUS {@code removingId}, all by ASCII fold.
     */
    boolean floorRefuses(PickableVariants pickable, String removingId) {
        if (pickable == null) {
            return true;
        }
        prunePending(pickable);
        Set<String> gone = new java.util.HashSet<>();
        for (String p : pendingForgets) {
            gone.add(HiddenShipSet.fold(p));
        }
        gone.add(HiddenShipSet.fold(removingId));
        for (String id : pickable.ids()) {
            if (!gone.contains(HiddenShipSet.fold(id))) {
                return false;
            }
        }
        return true;
    }

    /** The pending forgets right now (pruned against {@code pickableOrNull}), for a row. Sim thread. */
    List<String> pendingForgets(PickableVariants pickableOrNull) {
        prunePending(pickableOrNull);
        return List.copyOf(pendingForgets);
    }

    /**
     * Drops a pending forget only once its fold left BOTH the kept stems AND the listing's non-shipped ids (null =
     * kept alone): the writer re-reads kept BEFORE the sim's apply delists the ship, so a still-listed stem stays
     * pending or a second remove could empty /ship.
     */
    private void prunePending(PickableVariants pickableOrNull) {
        if (pendingForgets.isEmpty()) {
            return;
        }
        Set<String> present = new java.util.HashSet<>();
        for (String k : keptSide.hostParts().kept()) {
            present.add(HiddenShipSet.fold(k));
        }
        if (pickableOrNull != null) {
            pickableOrNull.ids().stream().filter(id -> pickableOrNull.baseCanonicalOrNull(id) == null)
                    .forEach(id -> present.add(HiddenShipSet.fold(id)));
        }
        pendingForgets.removeIf(p -> !present.contains(HiddenShipSet.fold(p)));
    }

    // ------------------------------------------------------------------ status

    /**
     * The {@code ships.*} status rows: every shipped id (listed or host-hidden) and the hidden ones, both
     * canonical and sorted by fold; an id outside the console's verb grammar is skipped (it still lists on
     * {@code /ship} but no console verb could name it), loud once per serve.
     */
    OpsStatusFile.ShipStats stats(PickableVariants pickableOrNull) {
        TreeMap<String, String> base = new TreeMap<>();
        TreeMap<String, String> hidden = new TreeMap<>();
        int skipped = 0;
        if (pickableOrNull != null) {
            for (String id : pickableOrNull.ids()) {
                if (pickableOrNull.baseCanonicalOrNull(id) != null) {
                    base.put(HiddenShipSet.fold(id), id);
                }
            }
            for (String id : set.ids()) {
                String c = pickableOrNull.baseCanonicalOrNull(id);
                if (c != null) {
                    base.put(HiddenShipSet.fold(c), c);
                    hidden.put(HiddenShipSet.fold(c), c);
                }
            }
        }
        List<String> baseIds = new ArrayList<>();
        for (String id : base.values()) {
            if (OperatorCommandFile.joinerDefaultTokenLawful(id)) {
                baseIds.add(id);
            } else {
                skipped++;
            }
        }
        List<String> hiddenIds = new ArrayList<>();
        for (String id : hidden.values()) {
            if (OperatorCommandFile.joinerDefaultTokenLawful(id)) {
                hiddenIds.add(id);
            }
        }
        if (skipped > 0 && !statusSkipLogged) {
            statusSkipLogged = true;
            System.out.println("npeer-serve hidden_ships_status_skipped count=" + skipped
                    + " (a shipped id outside the console's verb grammar: it lists on /ship, the console cannot hide it)");
        }
        HiddenShipsPersistSink s = sink;
        return new OpsStatusFile.ShipStats(baseIds, hiddenIds, set.overCap(), last,
                s == null ? persistFailedAtClose : s.persistFailed());
    }

    // ------------------------------------------------------------------ helpers

    /** Case-insensitive (ASCII) listing order, the raw spelling breaking a tie. */
    static final Comparator<String> FOLD_ORDER = Comparator.comparing(HiddenShipSet::fold)
            .thenComparing(Comparator.naturalOrder());

    private static String baseOrNull(PickableVariants pickableOrNull, String id) {
        return pickableOrNull == null ? null : pickableOrNull.baseCanonicalOrNull(id);
    }

    /** The set's ids that name a shipped ship, canonical spelling (what the host layer takes). */
    private List<String> effectiveIds(PickableVariants pickable) {
        List<String> out = new ArrayList<>();
        for (String id : set.ids()) {
            String c = pickable.baseCanonicalOrNull(id);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** The listing with {@code taking} off it (fold compare). */
    private static List<String> listingAfter(PickableVariants pickable, List<String> taking) {
        Set<String> folds = new java.util.HashSet<>();
        for (String t : taking) {
            folds.add(HiddenShipSet.fold(t));
        }
        List<String> out = new ArrayList<>();
        for (String id : pickable.ids()) {
            if (!folds.contains(HiddenShipSet.fold(id))) {
                out.add(id);
            }
        }
        return out;
    }

    private static Verdict refused(String head, String phrase) {
        return new Verdict(head + phrase, "unchanged");
    }

    /** Hand the render to the off-frame writer (never I/O here). A re-simulation applies in memory and writes
     *  nothing; a damaged file or a file-less serve keeps the change in memory only. */
    private String persist(PilotIntentDatagramServer door) {
        if (door.isReplay()) {
            replayUnpersisted++;
            set.render(); // the discarded render clears the dirty bit
            return "replay";
        }
        if (loadFailed) {
            return "unreadable_file";
        }
        HiddenShipsPersistSink s = sink;
        if (s == null) {
            return "memory_only";
        }
        s.persist(set.render());
        return "queued";
    }

    /** A changed set's verdict: the save ENQUEUED (or skipped, named); the receipt keeps its verdict while
     *  {@code ships.last} carries the not-saved note in front of it while the file is damaged. */
    private Verdict saved(PilotIntentDatagramServer door, String line) {
        String persist = persist(door);
        return new Verdict(line, persist, "unreadable_file".equals(persist) ? NOT_SAVED_NOTE + line : line);
    }
}
