package cc.starsector.server.runtime;

import cc.starsector.server.bridge.RealEngineDemoRoster;
import cc.starsector.server.bridge.RealEngineVariantFacts;
import cc.starsector.server.bridge.RealEngineVariantRegistrar;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import org.json.JSONObject;

/**
 * W15 v0: the composition root for the custom-variant lane, kept out of
 * {@code PilotServeSession} (WARN-tier file) — ONE call at door wiring when
 * {@code custom.submissions=true}: engine facts snapshot (sim thread, once) → owner override
 * file (unknown id = boot THROW) → pure {@link VariantPolicy} + boot census row →
 * {@link SubmittedVariantLedger} → {@link RealEngineVariantRegistrar} → {@link CustomVariantLane}
 * installed on the door. The VDEF fan = every live session via the door's public CTRL send (the
 * runtime catalog for late joiners is step 3). OFF = never called = the pre-lane door verbatim.
 */
public final class CustomVariantWiring {

    /** Per-file guard for the operator policy file (the local-parser law). */
    public static final int MAX_POLICY_FILE_BYTES = 262144;

    private CustomVariantWiring() {
    }

    /** Build + install the lane; THROWS on a policy-file fault (an owner typo never serves silently). The engine-bound
     *  composition: the engine's variant facts, the engine registrar and the engine's own resolvability test. */
    public static CustomVariantLane install(PilotIntentDatagramServer door, CustomVariantConfig config,
            PickableVariants pickableOrNull) {
        VariantPolicy.Catalog catalog = RealEngineVariantFacts.snapshot();
        String stamp = serveStamp();
        RealEngineVariantRegistrar registrar = new RealEngineVariantRegistrar(config, stamp);
        return compose(door, config, pickableOrNull, catalog, registrar, RealEngineDemoRoster::variantResolvable,
                stamp, registrar.persistDirOrNull());
    }

    /**
     * The same lane over CALLER-supplied engine values - a facts catalog, a
     * registrar and a resolvability test - so a serve with no engine behind it (the fake world of the serve rows)
     * boots customs ON through the REAL wiring: the policy, the ledger, the catalog lane, the persist writer, the
     * kept verbs and the receipt book are this class's, only the three engine reads are the caller's. The persist
     * dir is the one the engine registrar derives ({@code <persistDir>/<stamp>}). THROWS as the 3-arg install does.
     */
    public static CustomVariantLane install(PilotIntentDatagramServer door, CustomVariantConfig config,
            PickableVariants pickableOrNull, VariantPolicy.Catalog catalog, CustomVariantLane.Registrar registrar,
            Predicate<String> resolvable) {
        String stamp = serveStamp();
        return compose(door, config, pickableOrNull, catalog, registrar, resolvable, stamp, persistDirFor(config, stamp));
    }

    /** One serve's persist sub-dir name: the boot's wall-clock second. */
    private static String serveStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /** {@code <persistDir>/<stamp>}, null when the dir is blank - the engine registrar's own derivation. */
    private static File persistDirFor(CustomVariantConfig config, String stamp) {
        String dir = config.persistDir();
        return dir == null || dir.isBlank() ? null : new File(dir, stamp == null ? "serve" : stamp);
    }

    /** The composition body both installs share; {@code persistDirOrNull} = where the persist writer opens. */
    private static CustomVariantLane compose(PilotIntentDatagramServer door, CustomVariantConfig config,
            PickableVariants pickableOrNull, VariantPolicy.Catalog catalog, CustomVariantLane.Registrar registrar,
            Predicate<String> resolvable, String stamp, File persistDirOrNull) {
        VariantPolicy.Overrides overrides = readOverrides(config.policyFile(), catalog);
        VariantPolicy policy = new VariantPolicy(catalog, config.tier(), overrides, config.dModsAllowed());
        System.out.println(policy.bootLine());
        SubmittedVariantLedger ledger = new SubmittedVariantLedger(config.perSeat(), IdTables.installed());
        CustomVariantLane.VariantFan fan = frame -> {
            for (PilotIntentDatagramServer.PeerSession session : door.liveSessions()) {
                door.sendCtrlToSession(session, frame);
            }
        };
        CustomVariantLane lane = new CustomVariantLane(policy, ledger, registrar, door::sendVariantReceipt, fan);
        door.setVariantSubmissionLane(lane);
        // The custom-export inbox (resolved like persistDir — the serve's cwd
        // is the game's starsector-core, so a raw relative path would land inside the install).
        File exportDir = new File(config.exportDir());
        // ONE catalog lane, two sources — it
        // opens whenever customs are on. DEV = the live promote / demote, its poller only when
        // the arm set the INFRA knob; KEPT = the host's kept ships, <exportDir>/kept (the same resolved inbox the
        // export writer uses), read once at boot, each body re-checked by this serve's policy. Standing picks of a
        // demoted (hidden) base id, or of a kept stem that left the listing, clear through the ban twin's door verb.
        // Every decided DEV verdict (promoted / updated / refused / shipped = roll-bound)
        // and a kept ship's added / changed line reach the seats as ONE server chat line (seat 0 + "server").
        Path keptDir = exportDir.toPath().toAbsolutePath().resolve(CatalogPromotionLane.KEPT_DIR_NAME);
        // ONE policy function for both kept gates — the catalog lane's
        // kept read and the writer's keep-from-export read.
        java.util.function.Function<VariantDefinitionFrameCodec.VariantDefinition, String> keptPolicy = v -> {
            VariantPolicy.Refusal r = policy.check(v);
            return r == null ? null : r.token();
        };
        CatalogPromotionLane promo = CatalogPromotionLane.openOrNull(pickableOrNull, door.bootVariantCatalogSize(),
                registrar, resolvable, ledger.tables(), fan,
                ids -> door.clearSeatVariantOverridesNaming(ids, "demoted", "was withdrawn from the server catalog"),
                line -> door.fanServerChat(0, line), keptDir, keptPolicy);
        // Step 3(b): late joiners fold the accepted customs (spooled after the boot catalog), then the promoted bodies.
        door.setRuntimeVariantCatalog(promo == null ? lane::acceptedVariantFrames : () -> {
            java.util.List<byte[]> frames = new java.util.ArrayList<>(lane.acceptedVariantFrames());
            frames.addAll(promo.frames());
            return frames;
        });
        if (promo != null) {
            lane.setTickHook(promo::tick); // both drainTick sites = the sim thread
            promo.start();
        }
        // Step 3(c): /ship + HELLO picks resolve against the author's OWN customs; a pick held
        // for an in-flight submission seeds the moment the lane ACCEPTs it.
        door.variantPicks().attachCustomSource(lane);
        lane.setAcceptListener(door.variantPicks()::onCustomAccepted);
        // A REJECT releases a held pick at once (never the 30 s wait).
        lane.setRejectListener(door.variantPicks()::onCustomRejected);
        // A ban clears every seat's seeded next-respawn pick naming it
        // (never wait for round wrap) — the door twin logs, FALLBACK-announces and chats the seat.
        lane.setBanListener(door::clearSeatVariantOverridesNaming);
        // Step 3(e): the off-thread persistence writer on the registrar's resolved
        // <persistDir>/<stamp> (null = persistDir blank); fail-soft — the lane serves without it.
        CustomVariantPersistSink sink = CustomVariantPersistSink.openOrNull(persistDirOrNull);
        lane.setPersistSink(sink);
        // The export inbox resolved above (one File, shared with the KEPT source's dir).
        lane.setExportDir(exportDir);
        // The kept verbs write through the SAME resolved dirs; the writer
        // re-scans the catalog lane after each kept write / move, and re-checks a kept export by the same policy.
        lane.setKeptSources(keptDir, exportDir.toPath().toAbsolutePath(), promo);
        // The host ship verbs' final verdicts — the verb arm books the caller
        // (through the lane), the writer hands each job's outcome to the catalog lane, and the catalog lane's tick (the
        // sim thread) settles or times out the booking through the door's admin-receipt send.
        HostReceiptBook receipts = new HostReceiptBook(door::sendAdminReceipt);
        lane.setReceiptBook(receipts);
        if (promo != null) {
            promo.setReceiptBook(receipts);
        }
        if (sink != null && promo != null) {
            sink.setKeptRescan(promo::scanKept);
            sink.setKeptOutcomes(promo::noteKeptOutcome);
        }
        if (sink != null) {
            sink.setPolicyRefusal(keptPolicy);
        }
        System.out.println("custom-variant lane_installed per_seat=" + config.perSeat()
                + " global_cap=" + SubmittedVariantLedger.GLOBAL_CAP + " persist=" + config.persistDir()
                + "/" + stamp + " persist_sink=" + (lane.persistSinkOrNull() == null ? "off" : "on")
                + " export=" + config.exportDir() + " kept=" + (promo == null ? "off" : keptDir)
                + " kept_verbs=" + (sink != null && promo != null ? "on" : "off"));
        return lane;
    }

    /** The owner override file, or {@link VariantPolicy.Overrides#none()} when blank. */
    static VariantPolicy.Overrides readOverrides(String path, VariantPolicy.Catalog catalog) {
        if (path == null || path.isBlank()) {
            return VariantPolicy.Overrides.none();
        }
        File file = new File(path);
        try {
            if (!file.isFile()) {
                throw new IllegalArgumentException("custom.policyFile does not exist: " + file);
            }
            if (file.length() > MAX_POLICY_FILE_BYTES) {
                throw new IllegalArgumentException("custom.policyFile exceeds " + MAX_POLICY_FILE_BYTES + " B");
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            VariantPolicy.Overrides overrides = VariantPolicy.Overrides.parse(new JSONObject(text), catalog);
            System.out.println("custom-variant policy_file loaded=" + file.getName());
            return overrides;
        } catch (java.io.IOException io) {
            throw new IllegalStateException("custom.policyFile unreadable: " + file + ": " + io.getMessage(), io);
        } catch (org.json.JSONException notJson) {
            throw new IllegalArgumentException("custom.policyFile is not JSON: " + notJson.getMessage(), notJson);
        }
    }
}
