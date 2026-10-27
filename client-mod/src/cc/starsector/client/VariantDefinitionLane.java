package cc.starsector.client;

import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.VariantModuleResolveLaw;

import com.fs.starfarer.loading.B;
import com.fs.starfarer.loading.specs.HullVariantSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * The VDEF variant-definition client fold
 * lane: render-thread fold of server-delivered ship-variant definitions into the engine's OWN
 * variant registry, so RAPP rows / respawn re-points / wave adds can name variants that ship
 * with no kit-local {@code .variant} file. NEW-LANE-LAW collaborator of
 * {@link PredictReconcileCombatPlugin} (the {@link RosterAppendLane} shape): the plugin owns
 * the drive call — just AHEAD of {@code rapp.drain()}, so a definition delivered this frame is
 * registry-resolvable before any RAPP row (or this frame's spawn-on-sight mint) names it.
 *
 * <p><b>Thread contract.</b> The net-thread queue lives in the session (the phase-dive
 * internal-route idiom — {@code takeVariantDefinitions()} is the poll surface); {@link #drain}
 * folds on the render thread (verified safe mid-session). NO match-reset
 * clear: definitions are round-AGNOSTIC engine registry entries and the fold is idempotent
 * (overwrite-put), so a burst queued across a reset boundary must still fold — dropping it
 * would lose the catalog until re-admission.
 *
 * <p><b>Fold law (fidelity by construction):</b> rebuild the {@code org.json.JSONObject}
 * in WIRE order (both ends' HashMap-backed JSONObjects then iterate identically — ALTERNATING
 * weapon-group fire order is combat-observable) → the engine's own
 * {@code new HullVariantSpec(JSONObject)} (the same parse path as disk load)
 * → register via the engine-typed variant-store put with overwrite on
 * (overwrite makes re-delivery idempotent).
 * TRAP:
 * NEVER the 3-arg {@code SpecStore} put — it writes the generic per-class map that the
 * HullVariantSpec getter BYPASSES (the getter special-cases to the variant store) and throws on dupes.
 *
 * <p><b>SKIP-ENTRY tier (D5):</b> malformed bytes died at the reader (DROP-FRAME); a
 * well-formed definition THIS client cannot fold (unresolvable hull/weapon/wing id on a
 * content-mismatched install) is skipped LOUD — {@link FallbackAnnounce} + log, never a crash,
 * never a silent retry. A {@code wendigo*} variant id is refused outright: the engine's by-id
 * getter rewrites {@code wendigo*}→{@code grendel*}, so a spec
 * registered under one would be unreachable through the engine's own resolve path (the server
 * never mints one).
 *
 * <p><b>Boot laws ({@link BootVariantRegistry}):</b> an id the engine
 * registered at boot is NEVER put — equal delivery = quiet skip, differing = loud refusal; every id
 * the fold DID put is tracked and evicted through the engine's stock-safe remover when the served
 * session closes at combat teardown (the "NO match-reset clear" above still holds: eviction is a
 * session-close event, never a round event).
 */
final class VariantDefinitionLane {

    private final PredictReconcileCombatPlugin plugin;
    private int folded;
    private int skipped;
    /** The transport's queue-cap drop count as of the last drain (a change = a new log row). */
    private int droppedSeen;
    /**
     * The most definitions one frame folds — the server's whole
     * VDEF admission budget of 960 ({@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL}, the sum
     * every VDEF producer stays under), so a take holding one lawful admission burst folds in one frame,
     * ahead of the RAPP rows that follow it on the wire: a definition left for the next frame is
     * unresolvable to a same-frame mint. Only a take past the server's budget (at most the inbox's
     * 64-record margin) waits a frame; a NEW id past the session cap costs a few set reads, never a
     * parse ({@link BootVariantRegistry#foldCapSkip}).
     */
    static final int FOLD_BUDGET_PER_FRAME = 960;
    /** The last inbox take and the index of its next unfolded definition. The inbox is taken again
     *  only once this is spent, so at most one take (the inbox cap) ever waits here. */
    private List<ClientVariantDefinition> carry = Collections.emptyList();
    private int carryAt;

    VariantDefinitionLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** Render-thread fold — called just AHEAD of {@code rapp.drain()} so a definition delivered
     *  this frame (within {@link #FOLD_BUDGET_PER_FRAME}) is resolvable before any RAPP row names it.
     *  Past the budget the rest of the take waits in
     *  {@link #carry} for the next frame and everything newer waits in the session's bounded inbox
     *  (its overflow row below); the lane never drops a definition. */
    void drain() {
        if (carryAt >= carry.size()) {
            carry = plugin.session.takeVariantDefinitions();
            carryAt = 0;
        }
        int end = Math.min(carry.size(), carryAt + FOLD_BUDGET_PER_FRAME);
        while (carryAt < end) {
            fold(carry.get(carryAt++));
        }
        int dropped = plugin.session.variantDefinitionFramesDropped();
        if (dropped != droppedSeen) {
            // The queue-cap overflow was a write-only counter —
            // a joiner whose admission burst overran it fielded stock hulls with nothing to grep.
            // One row per change (the first drop, then each growth), never per frame.
            PilotWatchLog.info("predict variant-fold: VDEF queue OVERFLOW dropped=" + dropped
                    + " cap=" + PredictReconcileDatagramSession.VARIANT_DEFINITION_QUEUE_CAP
                    + " (this seat's catalog is INCOMPLETE - server bodies past the cap resolve as"
                    + " unknown ids; the admission burst outgrew the shared VDEF budget)");
            droppedSeen = dropped;
        }
    }

    private void fold(ClientVariantDefinition def) {
        if (foldIntoRegistry(def, "predict variant-fold")) {
            folded++;
            // A definition this process PUT (never a boot id, never a refusal) is ALSO
            // kept on disk for the joiner editor's "Server Builds" card — the fold is evicted at
            // combat teardown (Law B) and the W7c B3 kit strip ships no file for it, so without
            // the cache 16 of 23 published rows greyed post-match. ENQUEUE
            // only — the writer thread does the I/O off-frame.
            if (BootVariantRegistry.isFolded(def.variantId())) {
                try {
                    cc.starsector.client.editor.ServerBuildCache.remember(def.variantId(),
                            toEngineJson(def).toString());
                } catch (Exception uncached) { // generic on purpose: a JSONException catch type makes the verifier load
                                               // org.json at class-init — headless smokes without json.jar died there
                    PilotWatchLog.info("predict variant-fold: cache skip " + def.variantId()
                            + " (" + uncached.getClass().getSimpleName() + ")");
                }
            }
        } else {
            skipped++;
        }
    }

    /**
     * The registry-only fold, free of lane state — shared by the in-combat {@link #drain} and
     * the D-FIRSTSHIP pre-mission resolve (a join pick whose {@code .variant}
     * the W7c B3 kit strip did not ship is folded from the admission {@code VDEF} burst on the
     * mission-build thread — the menu's game thread, the same thread the engine loaded its own
     * specs on; the in-combat drain later re-folds it, idempotent by the overwrite put).
     * Returns whether the definition is now registry-resolvable; every failure is LOUD here
     * (SKIP-ENTRY tier), never a throw.
     */
    static boolean foldIntoRegistry(ClientVariantDefinition def, String logTag) {
        // Law B step 2: the PREVIOUS session's retired specs leave the engine map here,
        // ahead of this definition's gate/put (evict → fold order; a no-op when nothing is retired).
        // Never at teardown — the engine's end-of-match UI still resolves them there.
        BootVariantRegistry.evictRetired(logTag);
        if (def.variantId().startsWith("wendigo")) {
            FallbackAnnounce.announceOnce("vdef-skip-" + def.variantId(),
                    "server variant '" + def.variantId()
                            + "' REFUSED: wendigo* ids are engine-rewritten to grendel*");
            PilotWatchLog.info(logTag + ": SKIP-ENTRY " + def.variantId()
                    + " (wendigo* id is rewritten by the engine's by-id resolve)");
            return false;
        }
        // The per-session distinct-fold cap, asked BEFORE the
        // parse so each NEW id past it costs a few set reads: skipped (never put, tracked or cached),
        // counted in BootVariantRegistry.foldCapSkips(), confessed once on the process's first skip.
        int capSkip = BootVariantRegistry.foldCapSkip(def.variantId());
        if (capSkip > 0) {
            if (capSkip == 1) {
                FallbackAnnounce.announceOnce("vdef-fold-cap", "server variant '" + def.variantId()
                        + "' SKIPPED: past this session's cap of " + BootVariantRegistry.MAX_FOLDED_PER_SESSION
                        + " distinct folds; later new ids stay unresolved (capSkips)");
            }
            return false;
        }
        try {
            HullVariantSpec spec = new HullVariantSpec(toEngineJson(def));
            // Law A (BootVariantRegistry): a boot id is NEVER put — the server re-delivers
            // every vanilla id at admission, so an equal one is a quiet skip (the shipped spec answers)
            // and a differing one is a loud refusal (hostile-server signal; the shipped spec stays).
            BootVariantRegistry.Verdict verdict = BootVariantRegistry.gate(def.variantId(), spec);
            if (verdict == BootVariantRegistry.Verdict.EQUAL) {
                return true;
            }
            if (verdict == BootVariantRegistry.Verdict.DIFFERING) {
                FallbackAnnounce.announceOnce("vdef-boot-" + def.variantId(),
                        "server variant '" + def.variantId()
                                + "' REFUSED: it redefines a variant this game shipped (the shipped one stays)");
                PilotWatchLog.info(logTag + ": REFUSED " + def.variantId()
                        + " redefines a boot variant (hostile-server signal) " + BootVariantRegistry.lastDetail());
                return false;
            }
            // A FOLD-verdict spec whose station module does
            // not resolve (or names itself, or itself nests modules) is SKIP-ENTRY, never
            // put — see VariantModuleResolveLaw. This call PRECEDES the put below: a build gate
            // (run by client-mod/build.ps1) pins that order.
            String bad = VariantModuleResolveLaw.unresolvableModuleOrNull(def,
                    BootVariantRegistry::isRegistered, BootVariantRegistry::nestsModules,
                    BootVariantRegistry::isModuleBody);
            if (bad != null) {
                String why = bad.equals(def.variantId())
                        ? (BootVariantRegistry.isModuleBody(bad) ? "a mounted module body may not carry modules"
                                : "self-reference")
                        : BootVariantRegistry.isRegistered(bad) ? "nests modules" : "unresolvable";
                FallbackAnnounce.announceOnce("vdef-skip-" + def.variantId(),
                        "server variant '" + def.variantId() + "' REFUSED: station module '" + bad
                                + "' is refused (" + why + "; hostile-server signal)");
                PilotWatchLog.info(logTag + ": SKIP-ENTRY " + def.variantId() + " module=" + bad + " " + why);
                return false;
            }
            B.o00000(spec, true);
            BootVariantRegistry.trackFolded(def.variantId());
            for (ClientVariantDefinition.ModuleMount mount : def.modules()) {
                BootVariantRegistry.trackModuleBody(mount.moduleVariantId()); // The law's 4th predicate
            }
            PilotWatchLog.info(logTag + ": " + def.variantId()
                    + " hull=" + def.hullId()
                    + " groups=" + def.weaponGroups().size()
                    + " mods=" + def.hullMods().size()
                    + " modules=" + def.modules().size()
                    + " FOLDED into the engine variant registry");
            return true;
        } catch (Exception unresolvable) {
            // D5 SKIP-ENTRY: the frame was well-formed (reader laws held) but this client
            // cannot fold it — typically an unresolvable hull/weapon/wing id on a
            // content-mismatched install. Skip THIS variant loud; never kill the lane.
            FallbackAnnounce.announceOnce("vdef-skip-" + def.variantId(),
                    "server variant '" + def.variantId() + "' (hull " + def.hullId()
                            + ") could not fold: " + unresolvable.getMessage());
            PilotWatchLog.error(logTag + ": SKIP-ENTRY " + def.variantId()
                    + " hull=" + def.hullId(), unresolvable);
            return false;
        }
    }

    /** Rebuild the engine-shape variant JSON in WIRE order (the D3 order law: the server
     *  encoder walked its parsed JSONObject's {@code getNames()} order; re-inserting in wire
     *  order makes both ends' iteration equal by construction). Key set = the D6 schema — the
     *  exporter's 12 keys + {@code sModdedBuiltIns}/{@code suppressedMods}/{@code tags}; all
     *  keys always present ({@code weaponGroups} is REQUIRED by the ctor, the rest opt-read). */
    private static JSONObject toEngineJson(ClientVariantDefinition def) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("variantId", def.variantId());
        json.put("hullId", def.hullId());
        json.put("displayName", def.displayName());
        json.put("goalVariant", def.goalVariant());
        json.put("fluxVents", def.fluxVents());
        json.put("fluxCapacitors", def.fluxCapacitors());
        json.put("hullMods", toArray(def.hullMods()));
        json.put("permaMods", toArray(def.permaMods()));
        json.put("sMods", toArray(def.sMods()));
        json.put("sModdedBuiltIns", toArray(def.sModdedBuiltIns()));
        json.put("suppressedMods", toArray(def.suppressedMods()));
        json.put("wings", toArray(def.wings()));
        json.put("tags", toArray(def.tags()));
        JSONArray groups = new JSONArray();
        for (ClientVariantDefinition.WeaponGroup group : def.weaponGroups()) {
            JSONObject groupJson = new JSONObject();
            groupJson.put("autofire", group.autofire());
            groupJson.put("mode", group.alternating() ? "ALTERNATING" : "LINKED");
            JSONObject weapons = new JSONObject();
            for (ClientVariantDefinition.WeaponMount mount : group.weapons()) {
                weapons.put(mount.slotId(), mount.weaponId());
            }
            groupJson.put("weapons", weapons);
            groups.put(groupJson);
        }
        json.put("weaponGroups", groups);
        JSONObject modules = new JSONObject();
        for (ClientVariantDefinition.ModuleMount mount : def.modules()) {
            modules.put(mount.slotId(), mount.moduleVariantId());
        }
        json.put("modules", modules);
        return json;
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }
}
