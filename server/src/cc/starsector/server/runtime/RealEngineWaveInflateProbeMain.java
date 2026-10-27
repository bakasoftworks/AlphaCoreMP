package cc.starsector.server.runtime;

import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.bridge.RealEngineVariantFacts;
import cc.starsector.server.bridge.RealEngineVariantRegistrar;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.match.WaveSchedule.WaveRow;
import cc.starsector.server.platform.RealEngineContentInit;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponGroup;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponMount;
import cc.starsector.server.runtime.WaveLoadoutInflater.EngineSide;
import cc.starsector.server.runtime.WaveLoadoutInflater.Knobs;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ENGINE proof of the wave loadout inflater, headless on the real
 * engine ({@code DetachedHullmodYieldProbeMain.boot} idiom): the product lane
 * ({@link WaveLoadoutInflater}) over the probe-api engine half ({@code WaveLoadoutAutofit} =
 * vanilla's {@code DefaultFleetInflater} transcribed into the stock {@code CoreAutofitPlugin.doFit})
 * + the REAL {@link RealEngineVariantRegistrar} pass + the boot id tables, exactly as a serve wires
 * them. Legs (each a PASS/FAIL check on the {@code -end} row; the runner grades the row):
 * <ul>
 * <li><b>A determinism</b> — the same (seed, goal, slot) twice → byte-identical variant JSON;
 * the two calls also yield {@code fit_ms_first} vs {@code fit_ms_warm} (the frame-loop residency datum).</li>
 * <li><b>B variety</b> — one goal on N slots → ≥ 2 distinct per-slot weapon maps (the requirement:
 * "three same-hull ships in one wave → different weapons").</li>
 * <li><b>C membership + ACCEPT</b> — every fitted non-built-in weapon ∈ the faction's known set,
 * every wing ∈ its known fighters; then the LANE stages the same rows through the real registrar:
 * every autofitted body is ACCEPTED by the same engine pass a peer submission gets (vents/caps,
 * {@code weaponFits}, bays, OP ≥ 0), the fan and the spool carry one frame each, a re-staged slot is
 * a cache hit (no second registration).</li>
 * <li><b>D d-mods + the D-hull twin</b> — quality 0 forces vanilla's average of 5: every slot
 * carries ≥ 1 perma d-mod on the {@code _default_D} twin and the registrar ACCEPTs it; the same slots with
 * {@code wave.dmods=false} carry none on the base hull.</li>
 * <li><b>E every category chain</b> — {@code bastion:fortress} × every {@code WAVE_CATEGORIES}
 * member through a fresh lane ({@code preInflate} = the serve's boot walk): NO {@code fail:} row
 * anywhere; the boss chain is {@code skip:boss} only; omega/threat/shrouded are
 * {@code skip:vanilla_no_inflater} only (vanilla fields those verbatim,
 * {@code WaveSchedule.VANILLA_UNINFLATED_CATEGORIES}); the remnant finale tesseracts are
 * {@code skip:no_autofit} (vanilla's hull tag); the derelict mothership inflates (Phase 2b);
 * every faction chain inflates ≥ 1 row.</li>
 * <li><b>F station modules</b> — the derelict finale
 * mothership through the lane: one body per stock module + the parent, every module weapon in
 * the faction's known set, no d-mod on a STATION hull, the fan/spool order = modules then the
 * parent, and the ENGINE resolves every registered parent module slot to our module body
 * ({@code getModuleVariant} through the registry — the ordering law's proof).</li>
 * </ul>
 * Leg (d) — the client fold of a wave VDEF — is the existing {@code -KitCustomVariant} smoke at roll.
 *
 * <p>A MEASUREMENT main, never wired into the serve. Exit: 0 = every check PASS; 1 = the proof ran
 * and a check FAILED (read the rows — fix the generator, never loosen the pass); 2 = boot failed /
 * the engine half is absent.
 */
public final class RealEngineWaveInflateProbeMain {

    private static final String P = "wave-inflate-probe";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String FORTRESS = "bastion:fortress";
    private static final int TICKS_PER_SECOND = 60;

    private static final List<String> checkNames = new ArrayList<>();
    private static final List<Boolean> checkResults = new ArrayList<>();

    private RealEngineWaveInflateProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        String goal = "enforcer_XIV_Elite";
        String category = "hegemony";
        long seed = 0x1383L;
        float quality = Float.NaN;
        int smods = -1;
        boolean dmods = true; // The wave.dmods knob (vanilla ON); leg D forces both states
        String persistDir = null;
        String dumpDir = null; // --dump-dir <dir>: every inflated body, one file per variant id (the rewrite oracle)
        List<String> slots = new ArrayList<>(List.of("CC WAVE 2.1", "CC WAVE 2.2", "CC WAVE 2.3"));
        List<String> categories = new ArrayList<>(WaveSchedule.WAVE_CATEGORIES);
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--goal": goal = args[++i]; break;
                case "--category": category = args[++i]; break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--quality": quality = Float.parseFloat(args[++i]); break;
                case "--smods": smods = Integer.parseInt(args[++i]); break;
                case "--dmods": dmods = Boolean.parseBoolean(args[++i]); break;
                case "--persist-dir": persistDir = args[++i]; break;
                case "--dump-dir": dumpDir = args[++i]; break;
                case "--slots": slots = new ArrayList<>(List.of(args[++i].split(","))); break;
                case "--categories": categories = new ArrayList<>(List.of(args[++i].split(","))); break;
                default: break;
            }
        }
        if (seed == 0L) {
            throw new IllegalArgumentException("--seed 0 is the shared-RNG trap (WaveLoadoutAutofit)");
        }
        System.out.println(P + "-begin goal=" + goal + " category=" + category + " seed=" + seed
                + " quality=" + (Float.isNaN(quality) ? "doctrine" : String.valueOf(quality))
                + " smods=" + (smods < 0 ? "none" : String.valueOf(smods)) + " dmods=" + dmods
                + " slots=" + slots.size() + " categories=" + String.join(",", categories));

        ClassLoader loader = RealEngineWaveInflateProbeMain.class.getClassLoader();
        try {
            boot(loader); // = the binding factory's create(): the bare sector boots there, BEFORE the combat-engine mint
        } catch (Throwable t) {
            Throwable c = root(t);
            System.out.println(P + "-end verdict=BOOT_FAILED error=" + c.getClass().getName()
                    + " message=" + q(String.valueOf(c.getMessage())));
            c.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        // The serve's ONE id-table mint (PilotIntentCombatServerMain: engine snapshot → install).
        IdTables tables = RealEngineVariantFacts.snapshot().idTables();
        IdTables.install(tables);
        System.out.println(P + " id_tables " + tables.census());

        EngineSide real = WaveLoadoutInflater.probeApiOrNull();
        if (real == null) {
            System.out.println(P + "-end verdict=ENGINE_HALF_ABSENT");
            System.exit(2);
            return;
        }
        // --dump-dir: every body this run inflates lands as <dir>/<variantId>.json, so two runs of the engine half
        // (the transcription it replaced vs the engine-call rewrite) are diffed byte for byte.
        final java.nio.file.Path dump = dumpDir == null ? null : java.nio.file.Path.of(dumpDir);
        if (dump != null) {
            java.nio.file.Files.createDirectories(dump);
        }
        EngineSide engine = dump == null ? real : new EngineSide() {
            @Override
            public String skipReasonOrNull(String goalVariantId) {
                return real.skipReasonOrNull(goalVariantId);
            }

            @Override
            public String inflateToVariantJson(String goalVariantId, String memberKey, String factionId,
                    float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods, String newVariantId) {
                String json = real.inflateToVariantJson(goalVariantId, memberKey, factionId, qualityOrNaN, seed,
                        averageSModsOrNeg, dmods, newVariantId);
                try {
                    java.nio.file.Files.writeString(
                            dump.resolve(newVariantId.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json"), json);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return json;
            }

            @Override
            public float defaultQuality(String factionId) {
                return real.defaultQuality(factionId);
            }

            @Override
            public int knownWeaponCount(String factionId) {
                return real.knownWeaponCount(factionId);
            }
        };
        Class<?> autofit = Class.forName(WaveLoadoutInflater.AUTOFIT_CLASS, true, loader);
        Method knownWeaponIds = autofit.getMethod("knownWeaponIds", String.class);
        Method knownFighterIds = autofit.getMethod("knownFighterIds", String.class);

        // ---------------------------------------------------------------- leg A: determinism + first vs warm
        String idA = WaveLoadoutInflater.inflatedIdFor(goal, slots.get(0), seed);
        // The engine half takes a FACTION id, not the wave
        // category — `--category persean_league` threw in legs A/B while leg E mapped correctly.
        String fid = WaveSchedule.vanillaFactionIdFor(category);
        long t0 = System.nanoTime();
        String json1 = engine.inflateToVariantJson(goal, slots.get(0), fid, quality, seed, smods, dmods, idA);
        long firstNanos = System.nanoTime() - t0;
        t0 = System.nanoTime();
        String json2 = engine.inflateToVariantJson(goal, slots.get(0), fid, quality, seed, smods, dmods, idA);
        long warmNanos = System.nanoTime() - t0;
        VariantDefinition defA = VariantCatalogStore.parseVariantText(json1);
        System.out.println(P + " leg=A goal=" + goal + " slot=" + slots.get(0).replace(' ', '_')
                + " id=" + idA + " hull=" + defA.hullId() + " weapons=" + weaponCount(defA)
                + " wings=" + defA.wings().size() + " hullmods=" + defA.hullMods().size()
                + " smods=" + defA.sMods().size() + " vents=" + defA.fluxVents() + " caps=" + defA.fluxCapacitors()
                + " json_bytes=" + json1.length() + " fit_ms_first=" + ms(firstNanos) + " fit_ms_warm=" + ms(warmNanos)
                + " identical=" + json1.equals(json2));
        check("A same (seed, goal, slot) twice -> identical JSON", json1.equals(json2));

        // ---------------------------------------------------------------- leg B: variety across slots
        Map<String, Map<String, String>> loadouts = new LinkedHashMap<>();
        Map<String, VariantDefinition> defs = new LinkedHashMap<>();
        for (String slot : slots) {
            String id = WaveLoadoutInflater.inflatedIdFor(goal, slot, seed);
            VariantDefinition def = VariantCatalogStore.parseVariantText(
                    engine.inflateToVariantJson(goal, slot, fid, quality, seed, smods, dmods, id));
            Map<String, String> map = slotWeapons(def);
            loadouts.put(slot, map);
            defs.put(slot, def);
            System.out.println(P + " leg=B slot=" + slot.replace(' ', '_') + " id=" + id
                    + " vents=" + def.fluxVents() + " caps=" + def.fluxCapacitors()
                    + " hullmods=" + String.join("|", def.hullMods()) + " wings=" + String.join("|", def.wings())
                    + " weapons=" + describe(map));
        }
        int distinct = new HashSet<>(loadouts.values()).size();
        System.out.println(P + " leg=B goal=" + goal + " slots=" + slots.size() + " distinct_loadouts=" + distinct);
        check("B one goal on " + slots.size() + " slots -> >= 2 distinct loadouts", distinct >= 2);

        // ---------------------------------------------------------------- leg C: membership + the lane's ACCEPT
        @SuppressWarnings("unchecked")
        Set<String> known = new HashSet<>((List<String>) knownWeaponIds.invoke(null, category));
        @SuppressWarnings("unchecked")
        Set<String> knownWings = new HashSet<>((List<String>) knownFighterIds.invoke(null, category));
        List<String> unknownWeapons = new ArrayList<>();
        List<String> unknownWings = new ArrayList<>();
        for (VariantDefinition def : defs.values()) {
            for (WeaponGroup g : def.weaponGroups()) {
                for (WeaponMount m : g.weapons()) {
                    boolean builtIn = tables.builtInWeaponOrNull(def.hullId(), m.slotId()) != null;
                    if (!builtIn && !known.contains(m.weaponId())) {
                        unknownWeapons.add(m.slotId() + ":" + m.weaponId());
                    }
                }
            }
            for (String wing : def.wings()) {
                if (!knownWings.contains(wing)) {
                    unknownWings.add(wing);
                }
            }
        }
        System.out.println(P + " leg=C faction=" + category + " known_weapons=" + known.size()
                + " known_wings=" + knownWings.size() + " unknown_fitted=" + String.join("|", unknownWeapons)
                + " unknown_wings=" + String.join("|", unknownWings));
        check("C every fitted non-built-in weapon in the faction's known set", unknownWeapons.isEmpty());
        check("C every fitted wing in the faction's known fighters", unknownWings.isEmpty());

        RealEngineVariantRegistrar registrar = new RealEngineVariantRegistrar(config(persistDir), "probe");
        List<byte[]> fanned = new ArrayList<>();
        WaveLoadoutInflater lane = new WaveLoadoutInflater(engine, registrar, fanned::add, tables,
                new Knobs(true, quality, seed, smods, dmods));
        int accepted = 0;
        for (String slot : slots) {
            WaveRow staged = lane.inflate(new WaveRow(1, goal, slot), category);
            boolean ok = staged.inflatedVariantId() != null;
            if (ok) {
                accepted++;
            }
            System.out.println(P + " leg=C slot=" + slot.replace(' ', '_') + " result="
                    + lane.settledResultOrNull(goal, slot) + " spawn_id=" + staged.spawnVariantId()
                    + " goal_kept=" + staged.variantId().equals(goal));
        }
        check("C registrar ACCEPTs every autofitted body (" + accepted + "/" + slots.size() + ")",
                accepted == slots.size());
        check("C fan + spool carry one frame per registration",
                fanned.size() == lane.registeredCount() && lane.frames().size() == lane.registeredCount());
        int before = lane.registeredCount();
        WaveRow again = lane.inflate(new WaveRow(1, goal, slots.get(0)), category);
        check("C a re-staged slot is a cache hit (same id, no second registration)",
                idA.equals(again.inflatedVariantId()) && lane.registeredCount() == before);
        System.out.println(P + " leg=C registered=" + lane.registeredCount() + " fanned=" + fanned.size()
                + " frame_bytes=" + (fanned.isEmpty() ? 0 : fanned.get(0).length)
                + " persist_dir=" + registrar.persistDirOrNull());

        // ---------------------------------------------------------------- leg D: d-mods + the D-hull twin
        // quality 0 forces vanilla's averageDmods = 1/qualityPerDMod = 5 → getNumDModsToAdd = 3..5 on every
        // non-station row; the same slots with the knob OFF carry none and keep the base hull. Both sets run
        // the real registrar (the engine pass over the D twin + the perma d-mods is the proof the lane needs).
        long dSeed = seed * 31L + 7L;
        if (dSeed == 0L) {
            dSeed = 1L;
        }
        List<byte[]> dFanned = new ArrayList<>();
        WaveLoadoutInflater dLane = new WaveLoadoutInflater(engine, registrar, dFanned::add, tables,
                new Knobs(true, 0.0f, dSeed, smods, true));
        WaveLoadoutInflater pristine = new WaveLoadoutInflater(engine, registrar, frame -> { }, tables,
                new Knobs(true, 0.0f, dSeed * 3L + 1L, smods, false));
        int dAccepted = 0, dWithMods = 0, dTwins = 0, pAccepted = 0, pWithMods = 0, pTwins = 0;
        for (String slot : slots) {
            String idD = WaveLoadoutInflater.inflatedIdFor(goal, slot, dSeed);
            VariantDefinition body = VariantCatalogStore.parseVariantText(
                    engine.inflateToVariantJson(goal, slot, fid, 0.0f, dSeed, smods, true, idD));
            WaveRow stagedD = dLane.inflate(new WaveRow(1, goal, slot), category);
            boolean okD = stagedD.inflatedVariantId() != null;
            dAccepted += okD ? 1 : 0;
            dWithMods += WaveLoadoutInflater.dModCount(body) > 0 ? 1 : 0;
            dTwins += body.hullId().endsWith(WaveLoadoutInflater.D_HULL_SUFFIX) ? 1 : 0;
            List<String> dm = new ArrayList<>();
            for (String perma : body.permaMods()) {
                if (!body.sMods().contains(perma)) {
                    dm.add(perma);
                }
            }
            System.out.println(P + " leg=D slot=" + slot.replace(' ', '_') + " id=" + idD + " hull=" + body.hullId()
                    + " dmods=" + dm.size() + " dmod_ids=" + String.join("|", dm)
                    + " hullmods=" + String.join("|", body.hullMods())
                    + " result=" + dLane.settledResultOrNull(goal, slot));
            WaveRow stagedP = pristine.inflate(new WaveRow(1, goal, slot), category);
            String idP = stagedP.inflatedVariantId();
            pAccepted += idP != null ? 1 : 0;
            if (idP != null) {
                VariantDefinition pBody = VariantCatalogStore.parseVariantText(
                        engine.inflateToVariantJson(goal, slot, fid, 0.0f, dSeed * 3L + 1L, smods, false, idP));
                pWithMods += WaveLoadoutInflater.dModCount(pBody) > 0 ? 1 : 0;
                pTwins += pBody.hullId().endsWith(WaveLoadoutInflater.D_HULL_SUFFIX) ? 1 : 0;
            }
        }
        System.out.println(P + " leg=D goal=" + goal + " slots=" + slots.size() + " quality=0 dmods_on: accepted="
                + dAccepted + " with_dmods=" + dWithMods + " d_twins=" + dTwins + " frame_bytes="
                + (dFanned.isEmpty() ? 0 : dFanned.get(0).length) + " dmods_off: accepted=" + pAccepted
                + " with_dmods=" + pWithMods + " d_twins=" + pTwins);
        check("D quality 0 + dmods ON: every slot carries >= 1 d-mod on the _default_D twin",
                dWithMods == slots.size() && dTwins == slots.size());
        check("D the registrar ACCEPTs every d-modded body (" + dAccepted + "/" + slots.size() + ")",
                dAccepted == slots.size());
        check("D dmods OFF: no d-mod, base hull, every body accepted",
                pWithMods == 0 && pTwins == 0 && pAccepted == slots.size());

        // ---------------------------------------------------------------- leg E: every category chain
        int totalRows = 0, totalOk = 0, totalSkip = 0, totalFail = 0;
        long chainNanos = 0L;
        for (String cat : categories) {
            WaveSchedule schedule = WaveSchedule.forShape(FORTRESS, TICKS_PER_SECOND, 0.0d, null, null, cat);
            long catSeed = seed * 1_000_003L + cat.hashCode();
            if (catSeed == 0L) {
                catSeed = 1L;
            }
            WaveLoadoutInflater catLane = new WaveLoadoutInflater(engine, registrar, frame -> { }, tables,
                    new Knobs(true, quality, catSeed, smods, dmods));
            long c0 = System.nanoTime();
            catLane.preInflate(schedule);
            long catNanos = System.nanoTime() - c0;
            chainNanos += catNanos;
            Map<String, Integer> tally = new TreeMap<>();
            Map<String, String> resultByRow = new LinkedHashMap<>();
            int rows = 0;
            for (List<WaveRow> wave : schedule.rowsByWave()) {
                for (WaveRow row : wave) {
                    WaveRow staged = catLane.inflate(row, cat);
                    String result = staged.inflatedVariantId() != null ? "ok"
                            : String.valueOf(catLane.settledResultOrNull(row.variantId(), row.shipName()));
                    tally.merge(result, 1, Integer::sum);
                    resultByRow.put(row.variantId() + "|" + row.shipName(), result);
                    rows++;
                }
            }
            int ok = tally.getOrDefault("ok", 0);
            int fail = 0, skip = 0;
            for (Map.Entry<String, Integer> e : tally.entrySet()) {
                if (e.getKey().startsWith("fail:")) {
                    fail += e.getValue();
                } else if (e.getKey().startsWith("skip:")) {
                    skip += e.getValue();
                }
            }
            totalRows += rows;
            totalOk += ok;
            totalSkip += skip;
            totalFail += fail;
            StringBuilder t = new StringBuilder();
            for (Map.Entry<String, Integer> e : tally.entrySet()) {
                t.append(t.length() == 0 ? "" : "|").append(e.getKey()).append('=').append(e.getValue());
            }
            System.out.println(P + " leg=E category=" + cat + " rows=" + rows + " ok=" + ok + " skip=" + skip
                    + " fail=" + fail + " registered=" + catLane.registeredCount() + " seed=" + catSeed
                    + " ms=" + ms(catNanos) + " tally=" + t);
            check("E " + cat + ": no fail: row (fail-soft never fired)", fail == 0);
            if (WaveSchedule.isBossOnlyCategory(cat)) {
                check("E " + cat + ": boss chain untouched (every row skip:boss)",
                        ok == 0 && tally.getOrDefault("skip:boss", 0) == rows);
            } else if (WaveSchedule.isVanillaUninflatedCategory(cat)) {
                check("E " + cat + ": vanilla fields it verbatim (every row skip:vanilla_no_inflater)",
                        ok == 0 && tally.getOrDefault("skip:vanilla_no_inflater", 0) == rows);
            } else {
                check("E " + cat + ": >= 1 row inflated", ok >= 1);
                List<WaveRow> finaleRows = WaveSchedule.finaleRows(cat);
                for (int i = 0; i < finaleRows.size(); i++) {
                    WaveRow finale = finaleRows.get(i);
                    String r = resultByRow.get(finale.variantId() + "|" + finale.shipName());
                    // remnant finale = omega tesseracts (vanilla no_autofit hull tag); derelict finale = the
                    // module-bearing mothership FIRST (Phase 2b: 1 + 6 bodies, leg F) then plain derelict escorts,
                    // which vanilla's defender fleets DO inflate (SalvageGenFromSeed → createFleet) — so do we.
                    String expect = "remnant".equals(cat) ? "skip:no_autofit" : "ok";
                    check("E " + cat + " finale[" + i + "] " + finale.variantId() + " = " + expect, expect.equals(r));
                }
            }
        }
        System.out.println(P + " leg=E categories=" + categories.size() + " rows=" + totalRows + " ok=" + totalOk
                + " skip=" + totalSkip + " fail=" + totalFail + " chain_ms=" + ms(chainNanos)
                + " registrar_total=" + registrar.registeredCount());

        // ---------------------------------------------------------------- leg F: station modules
        // The derelict finale mothership (STATION hint, six MODULE-hinted frigate modules) is ONE row that mints
        // 1 + 6 bodies: the module bodies pass + register + fan FIRST, the parent LAST; the engine then resolves
        // every parent module slot to OUR module body through the registry (HullVariantSpec.getModuleVariant →
        // SpecStore) — the ordering law proven on the real engine, not asserted.
        {
            String station = WaveSchedule.DERELICT_MOTHERSHIP_VARIANT_ID;
            String stationSlot = WaveSchedule.DERELICT_FINALE_SLOT_NAME;
            String stationFaction = WaveSchedule.vanillaFactionIdFor("derelict");
            long fSeed = seed * 17L + 3L;
            if (fSeed == 0L) {
                fSeed = 1L;
            }
            Object keyLiteral = autofit.getField("MODULE_BODIES_KEY").get(null);
            check("F the module-bodies key literal matches across the by-name seam",
                    WaveLoadoutInflater.MODULE_BODIES_KEY.equals(keyLiteral));
            Class<?> settingsApi = Class.forName("com.fs.starfarer.api.SettingsAPI", false, loader);
            Class<?> variantApi = Class.forName("com.fs.starfarer.api.combat.ShipVariantAPI", false, loader);
            Method getVariant = settingsApi.getMethod("getVariant", String.class);
            Method getStationModules = variantApi.getMethod("getStationModules");
            Method getModuleVariant = variantApi.getMethod("getModuleVariant", String.class);
            Method getHullVariantId = variantApi.getMethod("getHullVariantId");
            Object settingsF = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
            Object stockStation = getVariant.invoke(settingsF, station);
            int stockModules = ((Map<?, ?>) getStationModules.invoke(stockStation)).size();

            String idF = WaveLoadoutInflater.inflatedIdFor(station, stationSlot, fSeed);
            String jsonF = engine.inflateToVariantJson(station, stationSlot, stationFaction, quality, fSeed, smods, dmods, idF);
            WaveLoadoutInflater.InflatedBodies split = WaveLoadoutInflater.splitBodies(jsonF);
            check("F the generator returns one body per stock module (" + split.modules().size() + "/" + stockModules
                    + ") in a lawful shape", stockModules > 0 && split.modules().size() == stockModules
                    && WaveLoadoutInflater.moduleShapeFaultOrNull(idF, split) == null);
            @SuppressWarnings("unchecked")
            Set<String> knownF = new HashSet<>((List<String>) knownWeaponIds.invoke(null, stationFaction));
            List<String> unknownF = new ArrayList<>();
            int moduleWeapons = 0;
            int moduleDmods = 0;
            for (VariantDefinition m : split.modules()) {
                moduleDmods += WaveLoadoutInflater.dModCount(m);
                for (WeaponGroup g : m.weaponGroups()) {
                    for (WeaponMount w : g.weapons()) {
                        moduleWeapons++;
                        boolean builtIn = tables.builtInWeaponOrNull(m.hullId(), w.slotId()) != null;
                        if (!builtIn && !knownF.contains(w.weaponId())) {
                            unknownF.add(m.variantId() + ":" + w.slotId() + ":" + w.weaponId());
                        }
                    }
                }
            }
            check("F every module weapon in the faction's known set (" + moduleWeapons + " fitted)", unknownF.isEmpty());
            check("F a STATION hull and its modules carry no d-mod and no D twin",
                    WaveLoadoutInflater.dModCount(split.parent()) == 0 && moduleDmods == 0
                            && !split.parent().hullId().endsWith(WaveLoadoutInflater.D_HULL_SUFFIX));

            List<byte[]> fFanned = new ArrayList<>();
            WaveLoadoutInflater fLane = new WaveLoadoutInflater(engine, registrar, fFanned::add, tables,
                    new Knobs(true, quality, fSeed, smods, dmods));
            int regBefore = registrar.registeredCount();
            WaveRow stagedF = fLane.inflate(new WaveRow(9, station, stationSlot), "derelict");
            int need = 1 + split.modules().size();
            check("F the lane admits the whole station: row ok, " + need + " bodies registered / fanned / spooled",
                    idF.equals(stagedF.inflatedVariantId()) && registrar.registeredCount() == regBefore + need
                            && fFanned.size() == need && fLane.registeredCount() == need && fLane.frames().size() == need);
            List<String> fanOrder = new ArrayList<>();
            for (byte[] frame : fFanned) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
                in.skipBytes(4); // the VDEF magic the demux peels
                fanOrder.add(VariantDefinitionFrameCodec.readFrameAfterMagic(in, tables).variantId());
            }
            List<String> expectOrder = new ArrayList<>();
            for (VariantDefinition m : split.modules()) {
                expectOrder.add(m.variantId());
            }
            expectOrder.add(idF);
            check("F fan/spool order = every module body, then the parent LAST", fanOrder.equals(expectOrder));

            Object parentSpec = getVariant.invoke(settingsF, idF);
            Map<?, ?> engineModules = parentSpec == null ? Map.of() : (Map<?, ?>) getStationModules.invoke(parentSpec);
            int resolved = 0;
            List<String> resolvedIds = new ArrayList<>();
            for (Map.Entry<?, ?> e : engineModules.entrySet()) {
                Object mv = getModuleVariant.invoke(parentSpec, String.valueOf(e.getKey()));
                String mvId = mv == null ? null : (String) getHullVariantId.invoke(mv);
                if (mvId != null && mvId.equals(e.getValue())) {
                    resolved++;
                }
                resolvedIds.add(String.valueOf(e.getKey()).replace(' ', '_') + ":" + mvId);
            }
            check("F the ENGINE resolves every registered parent module slot to the registered module body ("
                    + resolved + "/" + engineModules.size() + ")",
                    parentSpec != null && engineModules.size() == stockModules && resolved == stockModules);
            System.out.println(P + " leg=F goal=" + station + " id=" + idF + " bodies=" + need + " module_weapons="
                    + moduleWeapons + " parent_frame_bytes=" + (fFanned.isEmpty() ? 0 : fFanned.get(fFanned.size() - 1).length)
                    + " fan_order=" + String.join("|", fanOrder) + " engine_modules=" + String.join("|", resolvedIds)
                    + " result=" + fLane.settledResultOrNull(station, stationSlot));
        }

        int pass = 0;
        for (int i = 0; i < checkNames.size(); i++) {
            boolean ok = checkResults.get(i);
            if (ok) {
                pass++;
            }
            System.out.println(P + " check " + (ok ? "PASS" : "FAIL") + " " + checkNames.get(i));
        }
        boolean allPass = pass == checkNames.size();
        System.out.println(P + "-end verdict=" + (allPass ? "PROOF_PASS" : "PROOF_FAIL") + " checks=" + pass + "/"
                + checkNames.size() + " fit_ms_first=" + ms(firstNanos) + " fit_ms_warm=" + ms(warmNanos)
                + " distinct_loadouts=" + distinct + " accepted=" + accepted + "/" + slots.size()
                + " categories=" + categories.size() + " rows=" + totalRows + " ok=" + totalOk + " skip=" + totalSkip
                + " fail=" + totalFail + " chain_ms=" + ms(chainNanos));
        System.out.flush();
        System.exit(allPass ? 0 : 1);
    }

    /** The proven product boot (GL substrate → content init → engine singleton), the yield probe's. */
    private static void boot(ClassLoader loader) throws Exception {
        Method getSettings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings");
        RealEngineCombatBindingFactory.create();
        Object settings = getSettings.invoke(null);
        System.out.println(P + " boot stage=combat_binding settings=" + (settings != null)
                + " content_init=" + RealEngineContentInit.initialized());
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null after boot");
        }
    }

    /** The compiled defaults with the persist dir pointed at the probe's work dir (bodies on disk = evidence). */
    private static CustomVariantConfig config(String persistDirOrNull) {
        CustomVariantConfig d = CustomVariantConfig.defaults();
        return new CustomVariantConfig(d.submissions(), d.tier(), d.perSeat(), d.sModCap(), d.dModsAllowed(),
                d.policyFile(), persistDirOrNull == null ? d.persistDir() : persistDirOrNull, d.exportDir());
    }

    private static void check(String name, boolean pass) {
        checkNames.add(name);
        checkResults.add(pass);
    }

    private static Map<String, String> slotWeapons(VariantDefinition def) {
        Map<String, String> map = new TreeMap<>();
        for (WeaponGroup g : def.weaponGroups()) {
            for (WeaponMount m : g.weapons()) {
                map.put(m.slotId(), m.weaponId());
            }
        }
        return map;
    }

    private static int weaponCount(VariantDefinition def) {
        int n = 0;
        for (WeaponGroup g : def.weaponGroups()) {
            n += g.weapons().size();
        }
        return n;
    }

    private static String describe(Map<String, String> slotWeapons) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : slotWeapons.entrySet()) {
            b.append(b.length() == 0 ? "" : "|").append(e.getKey()).append(':').append(e.getValue());
        }
        return b.toString();
    }

    private static Throwable root(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0d);
    }

    /** Single-line quoted field: printable ASCII only, quotes folded — never parsed, only read. */
    private static String q(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c == '"' ? '\'' : (c < 0x20 || c > 0x7e) ? '?' : c);
        }
        return b.append('"').toString();
    }
}
