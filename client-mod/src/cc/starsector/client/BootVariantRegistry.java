package cc.starsector.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.loading.B;
import com.fs.starfarer.loading.specs.HullVariantSpec;

import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;

/**
 * The two laws that keep a server's {@code VDEF}
 * catalog from rewriting what THIS game shipped, and from outliving the session that delivered it.
 *
 * <p><b>Law A — the boot keyset.</b> The engine fills its variant registry ({@code B.Object}, the
 * process-global {@code id → HullVariantSpec} map) EAGERLY during resource load
 * BEFORE the {@code onApplicationLoad}
 * plugin loop — vanilla core + every enabled mod's {@code data/variants} + the mission
 * designs. {@link #snapshotAtApplicationLoad} copies that keyset ONCE, at the top of the plugin's
 * {@code onApplicationLoad}. A delivered definition whose id is a boot id is NEVER put: the server
 * re-delivers every vanilla id at admission ({@code spoolVariantCatalogCatchUp}), so an EQUAL one is a
 * QUIET skip (counted, resolvable — the shipped spec answers) and a DIFFERING one is a LOUD refusal
 * (a hostile-server signal: the same id with other guns/mods/hull; the shipped spec stays). Before
 * this law the fold's overwrite-put clobbered a stock spec silently.
 *
 * <p><b>Law B — retire at teardown, evict at the next fold.</b> Every id the fold DID put is
 * tracked; when the served session closes at combat teardown, {@link #retireFolded} moves the
 * tracker to the RETIRED set — nothing leaves the engine's map yet, because the teardown runs
 * while the engine's {@code CombatState} is still traversing the end-of-match UI, which draws
 * every deployed hull through {@code HullVariantSpec.getSizeWithModules} → {@code getModuleVariant}
 * → a by-id resolve with NO null guard: evicting a station parent's {@code _m0.._m5} module specs
 * 19 ms ahead of that walk was a Fatal on every window. The evict
 * happens at {@link #evictRetired}, which the fold calls AHEAD of every definition it gates
 * (evict → fold order by construction: a re-delivered id leaves the map before its fresh spec is
 * put) — the first fold of the next session runs with the old match's UI gone and no body of the
 * new match deployed under a retired id. The remover is the engine's own stock-safe
 * {@code B.new(String)} (removes iff the live spec is neither stock nor
 * empty-hull — a folded spec has {@code source == null}, so it goes; a shipped spec never does).
 * {@code new} is a Java keyword, so the call is a {@code MethodHandles.lookup().findStatic} — the
 * {@code MissileSplitLane} idiom; {@code java.lang.invoke} is not on the classloader-B deny list.
 * Law B ships only WITH Law A: with boot ids never folded, no boot id is ever in the tracker, and the
 * remover's own guard is the second layer, not the first. Between matches the map holds at most one
 * session's specs (bounded by that server's catalog); a vanilla mission in between never folds, so
 * it never evicts either. {@link #evictFolded} (the immediate form) stays for the title drill.
 *
 * <p>EQUAL is decided on the fields the server's catalog carries faithfully from the same
 * {@code .variant} file the engine parsed (both specs come out of the engine's own
 * {@code HullVariantSpec(JSONObject)} ctor): hull id, vents, capacitors, hull mods, perma mods,
 * s-mods, wings, fitted slot→weapon, and station modules — the last only when the delivered
 * definition declares any (the engine fills {@code <hull>_Hull} module maps AFTER load;
 * a module-less delivery of such an id is not a redefinition). Display
 * name, goal flag and tags are not load-bearing and are not compared. Proof = the title drill
 * (its {@code bootlaw} rows) — never a game-free
 * selftest: the registry is empty off the engine.
 */
public final class BootVariantRegistry {

    /** The fold verdict for one delivered definition (Law A). */
    public enum Verdict {
        /** Not a boot id: put it (and track it for Law B). */
        FOLD,
        /** A boot id whose delivered definition equals the shipped spec: skip the put, quietly. */
        EQUAL,
        /** A boot id whose delivered definition differs from the shipped spec: refuse, loudly. */
        DIFFERING
    }

    private static volatile Set<String> boot = Collections.emptySet();
    private static volatile boolean snapshotTaken;
    private static final Set<String> folded = Collections.synchronizedSet(new LinkedHashSet<String>());
    /**
     * The most DISTINCT ids one served session may fold into
     * {@link #folded} — four times the server's VDEF admission budget of 960
     * ({@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL}: boot catalog 256 + accepted customs 64 +
     * live wave bodies 640, every VDEF producer summed under it). That budget bounds one admission
     * burst, not a session: a wave category switch parks the retired chain's never-fielded bodies
     * uncounted and registers fresh ids, so a long serve's distinct ids can pass 960; four full
     * catalogs of distinct ids stay foldable. Past the cap a NEW id is skipped ({@link #foldCapSkip}); a boot
     * id or a re-delivered fold is never refused. A session that serves more distinct ids than this
     * leaves those hulls unresolved on this seat — counted and confessed, never silent.
     */
    public static final int MAX_FOLDED_PER_SESSION = 4 * 960;
    private static final AtomicInteger capSkipCount = new AtomicInteger();
    /** Law B: ids a CLOSED session put, still registered, evicted by the next fold ({@link #evictRetired}). */
    private static final Set<String> retired = Collections.synchronizedSet(new LinkedHashSet<String>());
    /** Ids a tracked fold MOUNTED as a station module — what the module
     *  law's "a mounted module body never grows modules" arm reads ({@link #isModuleBody}). Emptied only when an
     *  eviction pass leaves BOTH trackers empty (no tracked parent is registered any more); a parent re-folded
     *  without the module leaves its entry behind, which refuses more, never less. Bounded by the registered ids. */
    private static final Set<String> moduleBodies = Collections.synchronizedSet(new HashSet<String>());
    private static volatile int equalSkips;
    private static volatile int refusals;
    private static volatile int evictions;
    private static volatile Verdict lastVerdict;
    private static volatile String lastDetail = "";

    private BootVariantRegistry() {
    }

    /** Law A snapshot — the FIRST statement of {@code CcClientModPlugin.onApplicationLoad}; once per process. */
    public static void snapshotAtApplicationLoad() {
        if (snapshotTaken) {
            return;
        }
        Set<String> ids;
        try {
            ids = new HashSet<String>(B.o00000());
        } catch (RuntimeException fault) {
            PilotWatchLog.error("boot-variant registry: keyset snapshot FAILED — every delivered id folds unguarded", fault);
            ids = new HashSet<String>();
        }
        boot = Collections.unmodifiableSet(ids);
        snapshotTaken = true;
        PilotWatchLog.info("boot-variant registry: " + describe());
    }

    public static String describe() {
        return "boot=" + boot.size() + " snapshot=" + snapshotTaken + " folded=" + folded.size()
                + " retired=" + retired.size() + " equalSkips=" + equalSkips + " refusals=" + refusals
                + " evictions=" + evictions + " capSkips=" + capSkipCount.get();
    }

    public static boolean isBootId(String variantId) {
        return boot.contains(variantId);
    }

    public static int bootCount() {
        return boot.size();
    }

    public static int foldedCount() {
        return folded.size();
    }

    /** wq P24: a snapshot of the tracked (folded) ids in fold order — the peer-builds listing. */
    public static List<String> foldedIds() {
        synchronized (folded) {
            return new ArrayList<String>(folded);
        }
    }

    public static Verdict lastVerdict() {
        return lastVerdict;
    }

    /**
     * Law A gate for one delivered definition whose candidate spec the fold already built. Never
     * throws; never puts. {@link Verdict#FOLD} = the caller puts and then calls {@link #trackFolded}.
     */
    public static Verdict gate(String variantId, ShipVariantAPI candidate) {
        if (!boot.contains(variantId)) {
            lastVerdict = Verdict.FOLD;
            lastDetail = "";
            return Verdict.FOLD;
        }
        boolean withModules = !candidate.getStationModules().isEmpty();   // ONE flag for both sides
        String shipped = registeredSignature(variantId, withModules);
        String delivered = signature(candidate, withModules);
        if (shipped == null) {
            // the boot id vanished from the live map (never expected: only the engine's own
            // map-clear method does that) — treat as a fold so the definition stays resolvable
            lastVerdict = Verdict.FOLD;
            lastDetail = "boot id absent from the live registry";
            return Verdict.FOLD;
        }
        if (shipped.equals(delivered)) {
            equalSkips++;
            lastVerdict = Verdict.EQUAL;
            lastDetail = "";
            return Verdict.EQUAL;
        }
        refusals++;
        lastVerdict = Verdict.DIFFERING;
        lastDetail = "shipped{" + shipped + "} delivered{" + delivered + "}";
        return Verdict.DIFFERING;
    }

    /** The detail of the last {@link #gate} verdict (bounded, log-safe: ids only). */
    public static String lastDetail() {
        return lastDetail.length() > 600 ? lastDetail.substring(0, 600) + "…" : lastDetail;
    }

    /** Law B tracker — the caller put this (non-boot) id into the registry. A re-delivered id the
     *  engine's stock guard refused to evict (still retired) is a live fold again from here. */
    public static void trackFolded(String variantId) {
        retired.remove(variantId);
        folded.add(variantId);
    }

    /**
     * The distinct-fold cap's gate, asked BEFORE the fold
     * parses. 0 = the fold may proceed (the tracked set is below {@link #MAX_FOLDED_PER_SESSION}, the
     * id is a boot id the {@link #gate} answers, or this session already folded it: a re-delivery is
     * an overwrite, never growth). Otherwise the id is refused and the return is the process's
     * running skip count (1 = the first skip, the caller's one log row). The admitted path below the
     * cap is one size compare, no allocation.
     */
    public static int foldCapSkip(String variantId) {
        if (folded.size() < MAX_FOLDED_PER_SESSION || boot.contains(variantId) || folded.contains(variantId)) {
            return 0;
        }
        return capSkipCount.incrementAndGet();
    }

    /** The folds {@link #foldCapSkip} refused since process start (also the {@link #describe} token {@code capSkips}). */
    public static int foldCapSkips() {
        return capSkipCount.get();
    }

    /** The caller put a spec that mounts {@code moduleVariantId} as a station module ({@link #moduleBodies}). */
    public static void trackModuleBody(String moduleVariantId) {
        moduleBodies.add(moduleVariantId);
    }

    /** {@code VariantModuleResolveLaw}'s 4th predicate: a tracked fold mounts this id as a station module. */
    public static boolean isModuleBody(String variantId) {
        return moduleBodies.contains(variantId);
    }

    /** True when THIS process PUT the id (a fold, never a boot id) — the server-build
     *  cache persists only these (a boot id resolves from the shipped spec; a refusal never lands). */
    public static boolean isFolded(String variantId) {
        return folded.contains(variantId);
    }

    /**
     * Law B step 1 — the served session CLOSED at combat teardown: every tracked id becomes retired
     * (still registered, still resolvable by the engine's live end-of-match walk). Returns the count.
     */
    public static int retireFolded(String logTag) {
        List<String> ids;
        synchronized (folded) {
            ids = new ArrayList<String>(folded);
            folded.clear();
        }
        retired.addAll(ids);
        PilotWatchLog.info(logTag + ": boot-variant registry — retired " + ids.size()
                + " folded variant spec(s), evicted at the next fold (never under a live combat engine); "
                + describe());
        return ids.size();
    }

    /**
     * Law B step 2 — evict the retired ids; the fold calls this AHEAD of every definition it gates
     * (a no-op while nothing is retired — the per-definition cost is one size read). Ids the engine's
     * stock guard refuses stay retired for the next call. Returns the number that left the live map.
     */
    public static int evictRetired(String logTag) {
        if (retired.isEmpty()) {
            return 0;
        }
        return evict(retired, logTag + " (retired)");
    }

    /**
     * Law B, immediate form (the title drill; never the teardown — see the class javadoc) — evict every
     * tracked id through the engine's stock-safe remover; returns the number that left the live map.
     * Never throws: a handle failure logs once and keeps the tracker (the next call retries).
     */
    public static int evictFolded(String logTag) {
        return evict(folded, logTag);
    }

    private static int evict(Set<String> tracker, String logTag) {
        List<String> ids;
        synchronized (tracker) {
            ids = new ArrayList<String>(tracker);
        }
        if (ids.isEmpty()) {
            return 0;
        }
        MethodHandle remover;
        try {
            remover = MethodHandles.lookup().findStatic(B.class, "new",
                    MethodType.methodType(void.class, String.class));
        } catch (Throwable t) {
            PilotWatchLog.error(logTag + ": boot-variant registry — B.new(String) handle unavailable; "
                    + ids.size() + " folded spec(s) retained", t);
            return 0;
        }
        int gone = 0, kept = 0;
        for (String id : ids) {
            try {
                remover.invoke(id);
            } catch (Throwable t) {
                PilotWatchLog.info(logTag + ": boot-variant registry — evict '" + id + "' threw "
                        + t.getClass().getSimpleName());
            }
            if (isRegistered(id)) {
                kept++;
            } else {
                gone++;
                tracker.remove(id);
            }
        }
        evictions += gone;
        if (folded.isEmpty() && retired.isEmpty()) {
            moduleBodies.clear();   // no tracked parent is registered any more
        }
        PilotWatchLog.info(logTag + ": boot-variant registry — evicted " + gone + " folded variant spec(s)"
                + (kept > 0 ? ", " + kept + " refused by the engine's stock guard (still tracked)" : "")
                + "; " + describe());
        return gone;
    }

    /** Live-map membership without the by-id getter (which consults the campaign engine on a miss). The engine's
     *  own {@code containsKey}, O(1): the keyset getter this used before copies the
     *  whole map into a new list per call, and the module law asks at least twice per module of every delivered
     *  definition on the render thread. */
    public static boolean isRegistered(String variantId) {
        try {
            return B.Ô00000(variantId);
        } catch (RuntimeException fault) {
            return false;
        }
    }

    /**
     * Fold: whether a REGISTERED spec itself carries station
     * modules — the {@code VariantModuleResolveLaw} shape predicate (a module body never nests
     * modules; a hit closes a cycle through the FOLD overwrite put). Unregistered = false (the
     * registered test already refused it); a registry fault = TRUE, fail-closed like the law's
     * other arms (an unreadable spec is never put under).
     */
    public static boolean nestsModules(String variantId) {
        if (!isRegistered(variantId)) {
            return false;
        }
        try {
            HullVariantSpec spec = B.o00000(variantId);
            return spec == null || (spec.getStationModules() != null && !spec.getStationModules().isEmpty());
        } catch (RuntimeException fault) {
            return true;
        }
    }

    private static String registeredSignature(String variantId, boolean withModules) {
        if (!isRegistered(variantId)) {
            return null;
        }
        try {
            HullVariantSpec spec = B.o00000(variantId);
            return spec == null ? null : signature(spec, withModules);
        } catch (RuntimeException fault) {
            return null;
        }
    }

    /** The load-bearing shape of a spec as one sorted, single-line string (ids only). */
    static String signature(ShipVariantAPI v, boolean withModules) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("hull=").append(v.getHullSpec() == null ? "?" : v.getHullSpec().getHullId());
        sb.append(" vents=").append(v.getNumFluxVents()).append(" caps=").append(v.getNumFluxCapacitors());
        sb.append(" mods=").append(new TreeSet<String>(v.getHullMods()));
        sb.append(" perma=").append(new TreeSet<String>(v.getPermaMods()));
        sb.append(" smods=").append(new TreeSet<String>(v.getSMods()));
        List<String> wings = new ArrayList<String>(v.getWings());
        Collections.sort(wings);
        sb.append(" wings=").append(wings);
        Map<String, String> weapons = new TreeMap<String, String>();
        for (String slot : v.getFittedWeaponSlots()) {
            weapons.put(slot, String.valueOf(v.getWeaponId(slot)));
        }
        sb.append(" weapons=").append(weapons);
        if (withModules) {
            sb.append(" modules=").append(new TreeMap<String, String>(v.getStationModules()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the title drill's rows

    /**
     * Driven ONLY by {@code local/drills/editor-title-drill} (its {@code bootlaw?op=} route, by
     * method handle) — the in-game proof of both laws. {@code sweep} folds the definition of EVERY
     * boot id that has a {@code data/variants[/npc]/<id>.variant} file (the server catalog's scope)
     * through the client's mirror parser and the real fold: all must come back EQUAL.
     */
    public static String drillReport(String op) {
        try {
            if ("boot".equals(op)) {
                return describe() + " wolf_Assault=" + boot.contains("wolf_Assault");
            }
            if ("sweep".equals(op)) {
                return sweep();
            }
            if ("differ".equals(op)) {
                JSONObject json = loadVariantJson("wolf_Assault");
                if (json == null) {
                    return "error=no wolf_Assault file";
                }
                String before = registeredSignature("wolf_Assault", true);
                json.put("fluxVents", json.optInt("fluxVents", 0) + 1);
                boolean folded = VariantDefinitionLane.foldIntoRegistry(ClientVariantJson.parse(json.toString()), "boot-law drill");
                return "verdict=" + lastVerdict + " folded=" + folded + " registered=" + isRegistered("wolf_Assault")
                        + " unchanged=" + (before != null && before.equals(registeredSignature("wolf_Assault", true)))
                        + " detail=" + lastDetail();
            }
            if ("custom".equals(op)) {
                JSONObject json = loadVariantJson("wolf_Assault");
                if (json == null) {
                    return "error=no wolf_Assault file";
                }
                json.put("variantId", "cc_drill_wolf");
                boolean folded = VariantDefinitionLane.foldIntoRegistry(ClientVariantJson.parse(json.toString()), "boot-law drill");
                return "verdict=" + lastVerdict + " folded=" + folded + " registered=" + isRegistered("cc_drill_wolf")
                        + " tracked=" + BootVariantRegistry.folded.contains("cc_drill_wolf");
            }
            if ("stockguard".equals(op)) {
                MethodHandle remover = MethodHandles.lookup().findStatic(B.class, "new",
                        MethodType.methodType(void.class, String.class));
                remover.invoke("wolf_Assault");
                return "wolf_Assault=" + isRegistered("wolf_Assault");
            }
            if ("evict".equals(op)) {
                int gone = evictFolded("boot-law drill");
                return "evicted=" + gone + " cc_drill_wolf=" + isRegistered("cc_drill_wolf")
                        + " wolf_Assault=" + isRegistered("wolf_Assault") + " " + describe();
            }
            if ("retire".equals(op)) {
                // Law B's two steps — a retire keeps the spec registered (the engine's
                // end-of-match walk still resolves it); the NEXT fold evicts it ahead of its own put.
                JSONObject json = loadVariantJson("wolf_Assault");
                if (json == null) {
                    return "error=no wolf_Assault file";
                }
                json.put("variantId", "cc_drill_wolf");
                boolean foldedA = VariantDefinitionLane.foldIntoRegistry(ClientVariantJson.parse(json.toString()), "boot-law drill");
                int retiredNow = retireFolded("boot-law drill");
                boolean registeredAfterRetire = isRegistered("cc_drill_wolf");
                int foldedAfterRetire = folded.size();
                json.put("variantId", "cc_drill_wolf_next");
                boolean foldedB = VariantDefinitionLane.foldIntoRegistry(ClientVariantJson.parse(json.toString()), "boot-law drill");
                String report = "foldedA=" + foldedA + " retired=" + retiredNow
                        + " registered-after-retire=" + registeredAfterRetire
                        + " folded-after-retire=" + foldedAfterRetire + " foldedB=" + foldedB
                        + " cc_drill_wolf=" + isRegistered("cc_drill_wolf")
                        + " cc_drill_wolf_next=" + isRegistered("cc_drill_wolf_next")
                        + " retiredLeft=" + retired.size();
                int cleaned = evictFolded("boot-law drill");
                return report + " cleanup=" + cleaned + " " + describe();
            }
            if ("peerbuilds".equals(op)) {
                // wq P24: fold two server-shaped
                // customs (cc-v<serial>-<slug>, the minted id shape), hand the editor half to PeerBuilds
                // (list / API resolve / draft / save / dup-refuse / rename / delete), evict, re-list.
                String[][] two = {
                        { "wolf_Assault", "cc-v901-drill-wolf", "Drill Wolf" },
                        { "hound_Standard", "cc-v902-drill-hound", "Drill Hound" } };
                int foldedNow = 0;
                for (String[] p : two) {
                    JSONObject json = loadVariantJson(p[0]);
                    if (json == null) {
                        return "error=no " + p[0] + " file";
                    }
                    json.put("variantId", p[1]);
                    json.put("displayName", p[2]);
                    if (VariantDefinitionLane.foldIntoRegistry(ClientVariantJson.parse(json.toString()), "boot-law drill")) {
                        foldedNow++;
                    }
                }
                String editor = cc.starsector.client.editor.PeerBuilds.drillReport(two[0][1], two[1][1]);
                int gone = evictFolded("boot-law drill");
                return "peerbuilds folded=" + foldedNow + " " + editor + " evicted=" + gone
                        + " evicted-listed=" + cc.starsector.client.editor.PeerBuilds.residentCount(200);
            }
            return "error=unknown op";
        } catch (Throwable t) {
            return "error=" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
    }

    private static String sweep() {
        List<String> ids = new ArrayList<String>(boot);
        Collections.sort(ids);
        int files = 0, equal = 0, differ = 0, parseFail = 0, foldFail = 0, other = 0;
        List<String> differIds = new ArrayList<String>(), parseFailIds = new ArrayList<String>(), foldFailIds = new ArrayList<String>();
        for (String id : ids) {
            JSONObject json = loadVariantJson(id);
            if (json == null) {
                continue;
            }
            files++;
            ClientVariantDefinition def;
            try {
                def = ClientVariantJson.parse(json.toString());
            } catch (RuntimeException fault) {
                parseFail++;
                if (parseFailIds.size() < 8) {
                    parseFailIds.add(id + ":" + fault.getClass().getSimpleName());
                }
                continue;
            }
            if (!id.equals(def.variantId())) {
                other++;   // a file named for another id (the engine keys by the body's variantId, not the name)
                continue;
            }
            boolean ok = VariantDefinitionLane.foldIntoRegistry(def, "boot-law sweep");
            Verdict v = lastVerdict;
            if (ok && v == Verdict.EQUAL) {
                equal++;
            } else if (v == Verdict.DIFFERING) {
                differ++;
                if (differIds.size() < 8) {
                    differIds.add(id + " " + lastDetail());
                }
            } else {
                foldFail++;
                if (foldFailIds.size() < 8) {
                    foldFailIds.add(id + ":" + v + ":" + ok);
                }
            }
        }
        return "boot=" + ids.size() + " files=" + files + " equal=" + equal + " differ=" + differ + " parseFail=" + parseFail
                + " foldFail=" + foldFail + " renamed=" + other + " differIds=" + differIds + " parseFailIds=" + parseFailIds
                + " foldFailIds=" + foldFailIds;
    }

    /**
     * The engine's own loader for {@code data/variants/<id>.variant}: the root and {@code npc/} (the server
     * catalog's scope) first, then vanilla's per-hull subfolders ({@code wolf/wolf_Assault.variant} — the hull
     * id and its {@code _}-stripped prefixes) and the family folders; null when no path answers.
     */
    private static JSONObject loadVariantJson(String id) {
        List<String> folders = new ArrayList<String>();
        folders.add("");
        folders.add("npc/");
        String hull = null;
        try {
            if (isRegistered(id)) {
                HullVariantSpec spec = B.o00000(id);
                hull = spec == null || spec.getHullSpec() == null ? null : spec.getHullSpec().getHullId();
            }
        } catch (RuntimeException fault) {
            hull = null;
        }
        for (String h = hull; h != null && !h.isEmpty(); h = h.lastIndexOf('_') > 0 ? h.substring(0, h.lastIndexOf('_')) : null) {
            folders.add(h + "/");
        }
        for (String family : new String[] { "fighters", "stations", "remnant", "derelict", "drones", "omega", "threat", "dweller" }) {
            folders.add(family + "/");
        }
        for (String folder : folders) {
            try {
                JSONObject json = Global.getSettings().loadJSON("data/variants/" + folder + id + ".variant");
                if (json != null) {
                    return json;
                }
            } catch (Exception missing) {
                // not this path
            }
        }
        return null;
    }
}
