package cc.starsector.server.runtime;

import cc.starsector.server.match.FactionWaveTables;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.match.WaveSchedule.WaveRow;
import cc.starsector.server.runtime.CustomVariantLane.Registrar;
import cc.starsector.server.runtime.CustomVariantLane.VariantFan;
import cc.starsector.server.runtime.CustomVariantLane.Verdict;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The wave loadout inflater LANE — the product-side
 * producer in front of the VDEF carriage ({@code CatalogPromotionLane} shape:
 * register → fan → spool; this lane spools a body whole BEFORE its one fan, {@code admit} — then the
 * RAPP row names the id). Per staged wave row (the goal = the
 * authored stock variant id) it asks the probe-api unit's {@code WaveLoadoutAutofit} (vanilla's
 * inflation, by NAME — {@link #AUTOFIT_CLASS}) for the inflated variant as {@code .variant} JSON,
 * parses it with the catalog reader, runs the SAME engine pass every custom variant runs
 * ({@code RealEngineVariantRegistrar}: ctor resolve, slot/{@code weaponFits}, vents/caps, bays,
 * hullmod applicability, OP ≥ 0, register a clone — the boot-scan posture, no player tier: the
 * input is the server's own generator over vanilla data, not peer text), encodes ONE VDEF frame,
 * fans it to every live seat and spools it for late joiners; the row then carries the inflated id
 * for the spawn + the RAPP row while its authored goal id keeps every boot-closure consumer
 * ({@code FortressServeBootChecks}, the station budget, the officer table) on the id set known at
 * boot.
 *
 * <p>Determinism: ONE {@code wave.seed} per serve (random non-zero by default, LOGGED on the
 * boot row and every {@code wave_inflate}/{@code wave_spawn} row); the per-member stream keys on the
 * authored SLOT name ({@code "CC WAVE w.i"} — vanilla keys on the ship name, our stable member
 * identity is the slot), so (seed, goal, slot) → the same loadout on a re-serve, and a slot re-fielded
 * by an ENDLESS cycle or a wrapped round REUSES its registration (the cache below) — a persistent
 * vanilla fleet keeps its loadouts too, and the registry/spool stay bounded by the schedule's slot
 * count ({@link #REGISTER_CAP}, under the client's VDEF budget {@code MAX_VARIANTS_TOTAL}).
 *
 * <p>Residency: the whole compiled chain is
 * pre-inflated at install (sim thread, BEFORE the port publishes — boot-class cost, no seat can see
 * it). A {@code /wave <cat>} retarget or {@code /wave inflate on} press does NOT walk the chain on
 * its own frame any more (that press was the match's rank-1 worst frame, 24.5 ms of
 * pre-lane for 29 rows, and a raised cap would have made it ~72 ms): it ARMS a warm walk
 * ({@link #armWarm}) that {@link #warmStep} advances once per serve tick under
 * {@link #WARM_BUDGET_NANOS} (≥ 1 row per tick so it always finishes), wave order first — the
 * press frame carries only the opener's rows, which the reset stages on that same tick through the
 * miss law: {@link #inflate} at stage time is a cache read, and a miss (a row the walk has not
 * reached, a category the boot walk did not cover) pays on that tick and logs its ms.
 *
 * <p>Parking: a retarget PARKS every live body the retired chain never fielded
 * ({@link #retarget}) — out of the late-joiner spool and the live count, while the engine keeps the
 * registration (an engine put is never undone; the id is deterministic, so a switch back REVIVES
 * the parked frame without an engine pass) — and clears the {@code skip:cap} memos so the freed room
 * is retried. Fielded ids stay live for the serve (a live hull, a RAPP row and a late joiner all
 * name them).
 *
 * <p>Vanilla's exemptions are honored at BOTH grains: per hull by the
 * {@code no_autofit} tag law the engine half copies from vanilla's {@code DefaultFleetInflater}
 * ({@code skip:no_autofit}), per category by {@code WaveSchedule.VANILLA_UNINFLATED_CATEGORIES}
 * ({@code skip:vanilla_no_inflater} — fleets vanilla builds without an inflater), plus the boss
 * ({@code skip:boss}) and fighter-size rows always ({@code skip:fighter}). A skipped row is the
 * authored stock variant, byte-for-byte. D-mods + the D-hull twin (Phase 2) ride the same
 * body as ordinary perma hullmods / the {@code hullId} — {@code wave.dmods=false} turns the pass off.
 *
 * <p>Station modules (Phase 2b): a module-bearing hull
 * (the derelict finale mothership, 1 + 6) is ONE row that mints a frame LIST — the generator returns
 * the parent's JSON with its refit modules' own bodies under {@link #MODULE_BODIES_KEY}
 * ({@link #splitBodies}); the lane passes, registers, fans and spools every module body BEFORE the
 * parent (both engines resolve a module id lazily through the variant registry the spawn reads —
 * {@code HullVariantSpec.getModuleVariant}), every body counts against the cap, and a
 * park / revive moves the whole list in that same order. One module rejected = the whole row fails
 * soft (the stock station is fieldable; the passed module specs stay in the engine registry unreferenced).
 *
 * <p>Fail-SOFT, never concealed: any fault (engine throw, a rejected pass, the cap) keeps the row on
 * its stock goal id and logs ONE {@code wave_inflate ... result=fail:<why>} row per (goal, slot) —
 * the goal variant is always fieldable, and a serve never dies for variety.
 *
 * <p>Security (server-generated wave VDEFs):
 * no wire input — the generator reads vanilla data; the minted id is
 * {@link #ID_PREFIX} + the goal id + 8 hex of the member stream (lawful charset by construction,
 * ≤ 64 chars asserted); the engine pass is the same validator a peer submission gets.
 */
public final class WaveLoadoutInflater {

    /** The probe-api unit's typed twin (compiled against starfarer.api.jar, referenced by NAME). */
    public static final String AUTOFIT_CLASS = "cc.starsector.server.autofit.WaveLoadoutAutofit";
    /** Minted id shape: {@code cc_wave_<goal>_<hex8>}; never the ledger's {@code cc-v} nor {@code wendigo}. */
    public static final String ID_PREFIX = "cc_wave_";
    /**
     * LIVE registrations per serve (the late-joiner spool + the live count): the wave term of the ONE
     * admission budget — {@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL} 960 minus the boot
     * catalog cap 256 minus the customs' {@code GLOBAL_CAP} 64 = 640, written as a literal so the
     * budget row ({@code WaveInflateContractRows.budgetRows}) FAILS when any term moves. Sized for
     * one DP-scaled chain (~100 rows) plus every id an endless serve's category switches fielded;
     * a retired chain's never-fielded bodies are parked, not counted ({@link #retarget}). Was 128
     * (sized on "~60 slots"), exhausted by one chain + one switch.
     */
    public static final int REGISTER_CAP = 640;
    /**
     * The per-tick sim-thread budget of the warm walk ({@link #warmStep}). Measured before this
     * residency: 0.59 ms per row warm (29 rows),
     * 0.95 ms per row with first-fit warm-up (boot, 102 rows in 95.4 ms). Two milliseconds = 2–3 rows
     * per tick, a ~100-row chain warm in ~40 ticks (under a second at 60 Hz), and no serve frame
     * grows by more than this plus the one row the floor guarantees. The wall clock decides the ROW COUNT per
     * tick only: every row's engine pass runs inside {@code CombatRngRearm.isolate}, so the count no
     * longer reaches the combat streams (it did — the fighter-wave re-sim divergence).
     */
    public static final long WARM_BUDGET_NANOS = 2_000_000L;
    public static final int FAIL_DETAIL_MAX_CHARS = 160;
    /**
     * The char bound of the {@code weapon_ids=} field on the
     * {@code result=ok} row. A real capital's full fit is ~600 chars; the codec's admission
     * ({@code MAX_WEAPON_GROUPS} 7 x {@code MAX_WEAPONS_PER_GROUP} 32 mounts, ids to 64 chars)
     * would otherwise write a ~22 KB log row. Overflow is DISCLOSED on the row, never silent.
     */
    public static final int WEAPON_IDS_MAX_CHARS = 600;

    /**
     * The resolved knobs ({@code wave.inflate} / {@code wave.quality} / {@code wave.seed} / {@code wave.smods}
     * / {@code wave.dmods}). {@code dmods} (Phase 2): vanilla's quality-driven d-mods + the
     * D-hull twin on every inflated non-station row (default ON = vanilla-faithful; {@code false} = the
     * fit without the d-mod pass, an operator's "pristine waves" dial — server-settable).
     */
    public record Knobs(boolean enabled, float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods,
            float qualityPerWave) {
        /** The Phase-2a shape: the default per-wave quality ramp — the probe keeps it. */
        public Knobs(boolean enabled, float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods) {
            this(enabled, qualityOrNaN, seed, averageSModsOrNeg, dmods, DEFAULT_QUALITY_PER_WAVE);
        }

        /** The Phase-1 shape: d-mods ON (vanilla's default) — the contract rows and the probe keep it. */
        public Knobs(boolean enabled, float qualityOrNaN, long seed, int averageSModsOrNeg) {
            this(enabled, qualityOrNaN, seed, averageSModsOrNeg, true);
        }
    }

    /**
     * The {@code wave.qualityPerWave} default
     * (so that at most the first wave carries a handful of D-mods) — fleet quality
     * climbs by this per curve wave past {@link WaveSchedule#FIRST_CURVE_WAVE}, so wave 2 fields the
     * faction's core-market density and later (stronger) waves run cleaner; vanilla's own d-mod law
     * reads it (averageDmods = 5·(1 − quality): +0.05 quality ≈ −0.25 d-mods per hull). Server-settable;
     * {@code 0} = flat.
     */
    public static final float DEFAULT_QUALITY_PER_WAVE = 0.05f;

    /** The engine half, by name (a fixture stands in for the contract rows). */
    public interface EngineSide {
        String skipReasonOrNull(String goalVariantId);

        String inflateToVariantJson(String goalVariantId, String memberKey, String factionId,
                float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods, String newVariantId);

        float defaultQuality(String factionId);

        int knownWeaponCount(String factionId);
    }

    private final EngineSide engine;
    private final Registrar registrar;
    private final VariantFan fanOrNull;
    private final IdTables tables;
    private final Knobs knobs;
    private final int registerCap;
    private volatile boolean enabled;
    /** (goal|slot) → the inflated id (registered + spooled) — sim thread only. */
    private final Map<String, String> inflatedIds = new HashMap<>();
    /** (goal|slot) → the fail/skip token already logged — never re-run the engine for it. */
    private final Map<String, String> settled = new HashMap<>();
    /** Reader thread (the admission spool) reads; the sim thread appends/removes. */
    private final CopyOnWriteArrayList<byte[]> spool = new CopyOnWriteArrayList<>();
    /** (goal|slot) → the LIVE body (id + frame + definition) — what a retarget can park; sim thread only. */
    private final Map<String, Parked> bodies = new HashMap<>();
    /** A body the engine holds that no chain fields right now (parked at a retarget) — sim thread only. */
    private final Map<String, Parked> parked = new HashMap<>();
    /** (goal|slot) keys the STAGE hook handed an inflated id to — never parked. */
    private final Set<String> fielded = new HashSet<>();
    private boolean firstFitDone;
    private int registered;
    private int revivedTotal;
    /** The armed warm walk (null = idle): the flattened chain snapshot + the cursor. */
    private WarmWalk warmOrNull;

    /**
     * One row's live (or parked) body set: the encoded VDEF frames in ADMISSION order — the module
     * bodies first, the parent LAST — plus the parent's definition (the row's fielded id).
     */
    private record Parked(String id, List<byte[]> frames, VariantDefinition def) {
        byte[] parentFrame() {
            return frames.get(frames.size() - 1);
        }

        int moduleCount() {
            return frames.size() - 1;
        }

        int moduleBytes() {
            int n = 0;
            for (int i = 0; i < frames.size() - 1; i++) {
                n += frames.get(i).length;
            }
            return n;
        }
    }

    /**
     * The generator's ONE JSON, split: the parent {@code .variant} body + its station modules' own
     * bodies (module index order). Mirrors {@code WaveLoadoutAutofit.MODULE_BODIES_KEY} (probe-api,
     * reached by name only — the probe's leg F pins the two literals equal).
     */
    public static final String MODULE_BODIES_KEY = "moduleBodies";

    public record InflatedBodies(VariantDefinition parent, List<VariantDefinition> modules) {
    }

    /**
     * Split the generator's JSON into the parent definition + its module bodies; a JSON fault or an
     * unlawful body surfaces as {@link IllegalArgumentException} (the row's fail-soft path).
     */
    public static InflatedBodies splitBodies(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray bodies = o.optJSONArray(MODULE_BODIES_KEY);
            o.remove(MODULE_BODIES_KEY);
            VariantDefinition parent = VariantCatalogStore.parseVariantChecked(o);
            List<VariantDefinition> modules = new ArrayList<VariantDefinition>();
            if (bodies != null) {
                for (int i = 0; i < bodies.length(); i++) {
                    modules.add(VariantCatalogStore.parseVariantChecked(bodies.getJSONObject(i)));
                }
            }
            return new InflatedBodies(parent, List.copyOf(modules));
        } catch (JSONException notVariantJson) {
            throw new IllegalArgumentException("not a lawful .variant: " + notVariantJson.getMessage(),
                    notVariantJson);
        }
    }

    /**
     * The module shape law: the parent's {@code modules} map and the module bodies name the SAME id
     * set, one body per mount, every id under {@code <parentId>_m}. Null = lawful; else the detail
     * of a {@code fail:module_shape} row (never a half-station).
     */
    public static String moduleShapeFaultOrNull(String parentId, InflatedBodies split) {
        List<VariantDefinitionFrameCodec.ModuleMount> mounts = split.parent().modules();
        if (mounts.size() != split.modules().size()) {
            return mounts.size() + " module mounts vs " + split.modules().size() + " module bodies";
        }
        Set<String> bodyIds = new HashSet<String>();
        for (VariantDefinition module : split.modules()) {
            if (!module.variantId().startsWith(parentId + "_m")) {
                return "module body id " + module.variantId() + " is not under " + parentId + "_m";
            }
            if (!module.modules().isEmpty()) {
                return "module body " + module.variantId() + " nests modules";
            }
            if (!bodyIds.add(module.variantId())) {
                return "duplicate module body id " + module.variantId();
            }
        }
        for (VariantDefinitionFrameCodec.ModuleMount mount : mounts) {
            if (!bodyIds.contains(mount.moduleVariantId())) {
                return "mount " + mount.slotId() + " names " + mount.moduleVariantId() + " but no such body";
            }
        }
        return null;
    }

    private static final class WarmWalk {
        final String category;
        final List<WaveRow> rows;
        int next;
        int ticks;
        int revivedAtStart;
        int registeredAtStart;
        long nanos;
        /** The isolation counters at the arm — the completion receipt prints the walk's deltas. */
        long isolationsAtStart;
        long isolationsMovedAtStart;

        WarmWalk(String category, List<WaveRow> rows) {
            this.category = category;
            this.rows = rows;
        }
    }

    public WaveLoadoutInflater(EngineSide engine, Registrar registrar, VariantFan fanOrNull, IdTables tables,
            Knobs knobs) {
        this(engine, registrar, fanOrNull, tables, knobs, REGISTER_CAP);
    }

    /** The contract rows' seam: a small {@code registerCap} exercises the cap/park/revive paths cheaply. */
    public WaveLoadoutInflater(EngineSide engine, Registrar registrar, VariantFan fanOrNull, IdTables tables,
            Knobs knobs, int registerCap) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.fanOrNull = fanOrNull;
        this.tables = Objects.requireNonNull(tables, "tables");
        this.knobs = Objects.requireNonNull(knobs, "knobs");
        if (registerCap < 1) {
            throw new IllegalArgumentException("registerCap must be >= 1: " + registerCap);
        }
        this.registerCap = registerCap;
        this.enabled = knobs.enabled();
    }

    // ------------------------------------------------------------------ knobs (game-free, contract rows)

    /**
     * The serve's knob read. A BLANK {@code wave.seed} falls back to a random non-zero seed, except
     * on a pinned serve, where the RNG re-arm hands a seed derived from the pin's master seed so the
     * minted wave loadouts repeat run to run; an explicit {@code wave.seed} wins either way. Unpinned,
     * {@link CombatRngRearm#forcedWaveSeedOrZero()} is 0 and the blank seed randomizes as before.
     *
     */
    public static Knobs resolveKnobs() {
        long forced = CombatRngRearm.forcedWaveSeedOrZero();
        return parseKnobs(
                ServerConfig.resolve("cc.wave.inflate", "CC_WAVE_INFLATE", "wave.inflate", ""),
                ServerConfig.resolve("cc.wave.quality", "CC_WAVE_QUALITY", "wave.quality", ""),
                ServerConfig.resolve("cc.wave.seed", "CC_WAVE_SEED", "wave.seed", ""),
                ServerConfig.resolve("cc.wave.smods", "CC_WAVE_SMODS", "wave.smods", ""),
                ServerConfig.resolve("cc.wave.dmods", "CC_WAVE_DMODS", "wave.dmods", ""),
                ServerConfig.resolve("cc.wave.qualityPerWave", "CC_WAVE_QUALITY_PER_WAVE", "wave.qualityPerWave", ""),
                forced != 0L ? forced : randomNonZeroSeed());
    }

    /** The Phase-1 arity ({@code wave.dmods} blank = ON) — the contract rows' knob laws keep it. */
    public static Knobs parseKnobs(String inflate, String quality, String seed, String smods, long fallbackSeed) {
        return parseKnobs(inflate, quality, seed, smods, "", fallbackSeed);
    }

    /**
     * The quality ONE row inflates at — the knob's flat value (else the engine's
     * per-faction core-market stand-in, {@code WaveLoadoutAutofit.defaultQuality}) plus the
     * {@code wave.qualityPerWave} ramp over the curve waves past {@link WaveSchedule#FIRST_CURVE_WAVE}
     * (a slot outside the {@code "CC WAVE w.i"} scheme gets no ramp), capped at the knob law's 2. Logged
     * on every {@code result=ok} row as {@code quality=} — a flight's density is graded from it.
     */
    float effectiveQuality(String factionId, String slot) {
        float base = Float.isNaN(knobs.qualityOrNaN()) ? engine.defaultQuality(factionId) : knobs.qualityOrNaN();
        return effectiveQuality(base, knobs.qualityPerWave(), slot);
    }

    /** The ramp arithmetic alone (the contract rows pin it). */
    public static float effectiveQuality(float base, float perWave, String slot) {
        int wave = WaveSchedule.waveIndexOf(slot);
        int steps = wave < 0 ? 0 : Math.max(0, wave - WaveSchedule.FIRST_CURVE_WAVE);
        return Math.min(2f, base + perWave * steps);
    }

    /** The Phase-2a arity ({@code wave.qualityPerWave} blank = {@link #DEFAULT_QUALITY_PER_WAVE}). */
    public static Knobs parseKnobs(String inflate, String quality, String seed, String smods, String dmods,
            long fallbackSeed) {
        return parseKnobs(inflate, quality, seed, smods, dmods, "", fallbackSeed);
    }

    /** The parse law: a SET knob is never silently inert — a bad value THROWS at serve entry. */
    public static Knobs parseKnobs(String inflate, String quality, String seed, String smods, String dmods,
            String qualityPerWave, long fallbackSeed) {
        boolean enabled = parseSwitch("wave.inflate", inflate, true);
        boolean dm = parseSwitch("wave.dmods", dmods, true);
        float perWave = DEFAULT_QUALITY_PER_WAVE;
        if (qualityPerWave != null && !qualityPerWave.isBlank()) {
            try {
                perWave = Float.parseFloat(qualityPerWave.trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("wave.qualityPerWave must be a number 0..1 (blank = "
                        + DEFAULT_QUALITY_PER_WAVE + " per curve wave); got '" + qualityPerWave + "'");
            }
            if (!Float.isFinite(perWave) || perWave < 0f || perWave > 1f) {
                throw new IllegalArgumentException("wave.qualityPerWave must be a number 0..1 (blank = "
                        + DEFAULT_QUALITY_PER_WAVE + " per curve wave); got '" + qualityPerWave + "'");
            }
        }
        float q = Float.NaN;
        if (quality != null && !quality.isBlank()) {
            try {
                q = Float.parseFloat(quality.trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("wave.quality must be a number 0..2 (blank = the faction's core-market stand-in); got '" + quality + "'");
            }
            if (!Float.isFinite(q) || q < 0f || q > 2f) {
                throw new IllegalArgumentException("wave.quality must be a number 0..2 (blank = the faction's core-market stand-in); got '" + quality + "'");
            }
        }
        long s = fallbackSeed;
        if (seed != null && !seed.isBlank()) {
            try {
                s = Long.parseLong(seed.trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("wave.seed must be a non-zero long (blank = random per serve); got '" + seed + "'");
            }
            if (s == 0L) {
                throw new IllegalArgumentException("wave.seed 0 is the engine's shared-RNG trap (Misc.getRandom); pick any other value");
            }
        }
        if (s == 0L) {
            throw new IllegalArgumentException("fallback seed must be non-zero");
        }
        int sm = -1;
        if (smods != null && !smods.isBlank()) {
            try {
                sm = Integer.parseInt(smods.trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("wave.smods must be an integer 0..3 (blank = none, vanilla's default); got '" + smods + "'");
            }
            if (sm < 0 || sm > 3) {
                throw new IllegalArgumentException("wave.smods must be an integer 0..3 (blank = none, vanilla's default); got '" + smods + "'");
            }
        }
        return new Knobs(enabled, q, s, sm, dm, perWave);
    }

    /** {@code true|on} / {@code false|off} (ASCII-folded, blank = the default); anything else THROWS. */
    private static boolean parseSwitch(String knob, String raw, boolean blankDefault) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return blankDefault;
        }
        if (v.equals("true") || v.equals("on")) {
            return true;
        }
        if (v.equals("false") || v.equals("off")) {
            return false;
        }
        throw new IllegalArgumentException(knob + " must be true/false (default " + blankDefault + "); got '" + raw + "'");
    }

    public static long randomNonZeroSeed() {
        long s;
        do {
            s = ThreadLocalRandom.current().nextLong();
        } while (s == 0L);
        return s;
    }

    /** The minted id: {@code cc_wave_<goal>_<hex8 of the member stream seed>}; the id law (≤ 64 chars) asserted. */
    public static String inflatedIdFor(String goalVariantId, String slotName, long seed) {
        long stream = seed * slotName.hashCode();
        int folded = (int) (stream ^ (stream >>> 32));
        String id = ID_PREFIX + goalVariantId + "_" + String.format(java.util.Locale.ROOT, "%08x", folded);
        if (id.length() > VariantDefinitionFrameCodec.ID_MAX_CHARS) {
            throw new IllegalArgumentException("inflated id past the wire id cap: " + id);
        }
        return id;
    }

    static String key(String goalVariantId, String slotName) {
        return goalVariantId + "|" + slotName;
    }

    // ------------------------------------------------------------------ the lane

    public boolean enabled() {
        return enabled;
    }

    /** The {@code /wave inflate on|off} dial: OFF cancels an armed warm walk; a switch never un-registers
     * — a live id stays live (the serve re-arms the walk on ON, {@link #armWarm}). */
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            warmOrNull = null;
        }
    }

    public Knobs knobs() {
        return knobs;
    }

    /** LIVE registrations (spooled for late joiners); parked bodies are not counted. */
    public int registeredCount() {
        return registered;
    }

    public int parkedCount() {
        return parked.size();
    }

    public int registerCap() {
        return registerCap;
    }

    /** Rows the armed warm walk has not reached yet (0 = idle / done). */
    public int warmRowsLeft() {
        WarmWalk w = warmOrNull;
        return w == null ? 0 : w.rows.size() - w.next;
    }

    /** The late-joiner spool: every registered wave VDEF, mint order (reader thread reads a snapshot). */
    public List<byte[]> frames() {
        return List.copyOf(spool);
    }

    /**
     * The (goal, slot) verdict this lane settled: {@code "ok"} for a registered id, the logged
     * {@code skip:*}/{@code fail:*} token, or null when never asked — the proof main + contract
     * rows read it (sim thread only).
     */
    public String settledResultOrNull(String goalVariantId, String slotName) {
        String k = key(goalVariantId, slotName);
        if (inflatedIds.containsKey(k)) {
            return "ok";
        }
        return settled.get(k);
    }

    /**
     * BOOT: walk every row of the compiled chain through {@link #inflate} inline (sim thread, before
     * the port publishes — the one walk that may take a whole frame). The verb paths arm a budgeted
     * walk instead ({@link #armWarm}); the contract rows call this directly for the inline shape.
     */
    public void preInflate(WaveSchedule schedule) {
        if (!enabled) {
            return;
        }
        long start = System.nanoTime();
        int rows = 0;
        String category = schedule.category();
        for (List<WaveRow> wave : schedule.rowsByWave()) {
            for (WaveRow row : wave) {
                inflate(row, category);
                rows++;
            }
        }
        System.out.println("npeer-serve wave_inflate_prewalk category=" + category + " rows=" + rows
                + " registered=" + registered + " ms="
                + String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1_000_000.0d)
                + " mode=boot");
    }

    /**
     * Verb path ({@code /wave <cat>}, {@code /wave inflate on}): snapshot the chain's rows in wave
     * order and let {@link #warmStep} pay them over the next ticks. Replaces an armed walk (the
     * newer press wins; rows already live are cache hits either way). No engine call here.
     */
    public int armWarm(WaveSchedule schedule) {
        if (!enabled) {
            return 0;
        }
        List<WaveRow> rows = new ArrayList<>();
        for (List<WaveRow> wave : schedule.rowsByWave()) {
            rows.addAll(wave);
        }
        WarmWalk walk = new WarmWalk(schedule.category(), rows);
        walk.revivedAtStart = revivedTotal;
        walk.registeredAtStart = registered;
        walk.isolationsAtStart = CombatRngRearm.isolationCount();
        walk.isolationsMovedAtStart = CombatRngRearm.isolationMovedCount();
        warmOrNull = walk;
        System.out.println("npeer-serve wave_inflate_warm_armed category=" + walk.category + " rows=" + rows.size()
                + " budget_ms=" + String.format(java.util.Locale.ROOT, "%.1f", WARM_BUDGET_NANOS / 1_000_000.0d)
                + " registered=" + registered + " parked=" + parked.size());
        return rows.size();
    }

    /**
     * One serve tick of the armed walk (sim thread, before the wave drain): rows in order until
     * {@link #WARM_BUDGET_NANOS} is spent, at least one per call so a slow engine still finishes.
     * Returns the rows advanced this tick (0 = idle). The completion row is the verb twin of the boot
     * {@code wave_inflate_prewalk} row ({@code mode=warm}, plus the ticks it took).
     */
    public int warmStep() {
        WarmWalk w = warmOrNull;
        if (w == null || !enabled) {
            return 0;
        }
        long start = System.nanoTime();
        int done = 0;
        while (w.next < w.rows.size()) {
            if (done > 0 && System.nanoTime() - start >= WARM_BUDGET_NANOS) {
                break;
            }
            inflate(w.rows.get(w.next), w.category);
            w.next++;
            done++;
        }
        w.ticks++;
        w.nanos += System.nanoTime() - start;
        if (w.next >= w.rows.size()) {
            warmOrNull = null;
            System.out.println("npeer-serve wave_inflate_prewalk category=" + w.category + " rows=" + w.rows.size()
                    + " registered=" + registered + " revived=" + (revivedTotal - w.revivedAtStart)
                    + " minted=" + (registered - w.registeredAtStart - (revivedTotal - w.revivedAtStart))
                    + " parked=" + parked.size() + " ticks=" + w.ticks + " ms="
                    + String.format(java.util.Locale.ROOT, "%.1f", w.nanos / 1_000_000.0d) + " mode=warm");
            if (CombatRngRearm.isArmed()) {
                // The walk's isolation receipt — rows = engine passes bracketed, moved = those
                // whose draws the restore undid (a pinned serve's log is read for this line; nothing unpinned).
                System.out.println("npeer-serve wave_inflate_rng_isolated category=" + w.category
                        + " rows=" + (CombatRngRearm.isolationCount() - w.isolationsAtStart)
                        + " moved=" + (CombatRngRearm.isolationMovedCount() - w.isolationsMovedAtStart));
            }
        }
        return done;
    }

    /**
     * The {@code /wave <cat>} switch: park every LIVE body the retired chain never fielded and the
     * new chain does not name (the engine keeps it; the spool and the live count drop it), clear the
     * {@code skip:cap} memos (room may have freed), then arm the warm walk over the new chain. One
     * {@code wave_inflate_park} row per switch; a switch back revives the parked frames without an
     * engine pass ({@link #inflate}).
     */
    public void retarget(WaveSchedule schedule) {
        Set<String> wanted = new HashSet<>();
        for (List<WaveRow> wave : schedule.rowsByWave()) {
            for (WaveRow row : wave) {
                wanted.add(key(row.variantId(), row.shipName()));
            }
        }
        int parkedNow = 0;
        java.util.Iterator<Map.Entry<String, String>> live = inflatedIds.entrySet().iterator();
        while (live.hasNext()) {
            Map.Entry<String, String> e = live.next();
            String k = e.getKey();
            if (fielded.contains(k) || wanted.contains(k)) {
                continue;
            }
            Parked body = bodies.remove(k);
            if (body == null) {
                continue; // never minted by this lane (defensive; every live id has a body)
            }
            for (byte[] frame : body.frames()) {
                spool.remove(frame);
            }
            parked.put(k, body);
            live.remove();
            registered -= body.frames().size();
            parkedNow++;
        }
        int memosCleared = 0;
        java.util.Iterator<Map.Entry<String, String>> memo = settled.entrySet().iterator();
        while (memo.hasNext()) {
            if ("skip:cap".equals(memo.next().getValue())) {
                memo.remove();
                memosCleared++;
            }
        }
        System.out.println("npeer-serve wave_inflate_park category=" + schedule.category() + " parked_now=" + parkedNow
                + " parked=" + parked.size() + " registered=" + registered + " fielded=" + fielded.size()
                + " cap_memos_cleared=" + memosCleared);
        armWarm(schedule);
    }

    /**
     * The schedule's stage hook ({@code WaveSchedule.RowInflater}): {@link #inflate} plus the FIELDED
     * mark — a staged id is never parked for the rest of the serve.
     */
    public WaveRow stage(WaveRow row, String category) {
        WaveRow staged = inflate(row, category);
        if (staged.inflatedVariantId() != null) {
            fielded.add(key(row.variantId(), row.shipName()));
        }
        return staged;
    }

    /**
     * The cache read + miss path: the row with its inflated id, or the row itself (disabled / boss /
     * skip / fail — each logged once per (goal, slot)). A parked body revives here (no engine call).
     */
    public WaveRow inflate(WaveRow row, String category) {
        if (!enabled || row.inflatedVariantId() != null) {
            return row;
        }
        String goal = row.variantId();
        String slot = row.shipName();
        String k = key(goal, slot);
        String cached = inflatedIds.get(k);
        if (cached != null) {
            return row.withInflated(cached);
        }
        if (settled.containsKey(k)) {
            return row;
        }
        if (WaveSchedule.isBossOnlyCategory(category) || FactionWaveTables.ZIGGURAT_BOSS_VARIANT_ID.equals(goal)) {
            return settle(k, row, "skip:boss", null);
        }
        if (WaveSchedule.isVanillaUninflatedCategory(category)) {
            // Never randomize what vanilla fields verbatim (threat, dweller, omega
            // fleets are built inflater-less — WaveSchedule.VANILLA_UNINFLATED_CATEGORIES provenance).
            return settle(k, row, "skip:vanilla_no_inflater", null);
        }
        if (registered >= registerCap) {
            return settle(k, row, "skip:cap", null);
        }
        // A revive obeys the SAME body-count law
        // as the mint below (registered + need > cap). WHY: the pre-check above only proves one free
        // slot, and a k-body station revived near the cap overshot REGISTER_CAP by up to k-1 frames —
        // all spooled whole to every newcomer, into the CTRL margin. A refused revive stays PARKED (a
        // parked fit is not spent, unlike a mint past the cap); retarget clears the skip:cap memo, so
        // the revive is retried once frames free up.
        Parked revive = parked.get(k);
        if (revive != null) {
            int need = revive.frames().size();
            if (registered + need > registerCap) {
                return settle(k, row, "skip:cap", need + " bodies (revive), registered=" + registered
                        + " cap=" + registerCap);
            }
            parked.remove(k);
            admit(revive);
            revivedTotal += revive.frames().size();
            inflatedIds.put(k, revive.id());
            bodies.put(k, revive);
            String revivedFaction = WaveSchedule.vanillaFactionIdFor(category);
            logOk(goal, slot, revive.id(), revivedFaction, revive, -1, 0L, 0L, true, List.of(),
                    effectiveQuality(revivedFaction, slot));
            return row.withInflated(revive.id());
        }
        String factionId = WaveSchedule.vanillaFactionIdFor(category); // persean_league → persean (probed)
        // The engine pass builds vanilla CAMPAIGN objects (a fleet, a member, the fit's
        // ship) whose constructors draw Math.random() and mint ids from the SAME streams the combat sim draws
        // from. After the frame-0 re-arm a row runs at a tick the wall clock decides (warmStep's budget after a
        // mid-match /wave switch; a stage miss inline), so its draws shifted every combat consumer's sequence
        // run-to-run — the reel's fighter-wave d2 divergence (census: the tallies differ 3 ticks after the
        // press). The window restores every stream after the row; vanilla combat never inflates a fleet
        // mid-battle, so the draws have no gameplay meaning. Unpinned = the row runs bare, as before.
        return CombatRngRearm.isolate(() -> inflateMiss(k, row, goal, slot, factionId));
    }

    /** The miss path proper: the engine fit + the strict pass + the admission (inside the isolation window). */
    private WaveRow inflateMiss(String k, WaveRow row, String goal, String slot, String factionId) {
        try {
            String skip = engine.skipReasonOrNull(goal);
            if (skip != null) {
                return settle(k, row, "skip:" + skip, null);
            }
            String id = inflatedIdFor(goal, slot, knobs.seed());
            float quality = effectiveQuality(factionId, slot);
            long fitStart = System.nanoTime();
            String json = engine.inflateToVariantJson(goal, slot, factionId, quality, knobs.seed(),
                    knobs.averageSModsOrNeg(), knobs.dmods(), id);
            long fitNanos = System.nanoTime() - fitStart;
            InflatedBodies split = splitBodies(json);
            VariantDefinition def = split.parent();
            if (!id.equals(def.variantId())) {
                return settle(k, row, "fail:id_mismatch", def.variantId());
            }
            String shapeFault = moduleShapeFaultOrNull(id, split);
            if (shapeFault != null) {
                return settle(k, row, "fail:module_shape", shapeFault);
            }
            int need = 1 + split.modules().size();
            if (registered + need > registerCap) {
                // a multi-body row (station) past the cap: the fit is spent, nothing is registered; the
                // memo clears at the next retarget like any skip:cap
                return settle(k, row, "skip:cap", need + " bodies, registered=" + registered + " cap=" + registerCap);
            }
            long passStart = System.nanoTime();
            // Phase 2b ordering law: every MODULE body passes + registers first, the parent last.
            List<VariantDefinition> admitted = new ArrayList<VariantDefinition>(need);
            List<String> dropped = new ArrayList<String>();
            for (VariantDefinition module : split.modules()) {
                Pass p = pass(module);
                if (!p.verdict().isAccepted()) {
                    return settle(k, row, "fail:" + p.verdict().reasonToken(),
                            "module " + module.variantId() + ": " + p.verdict().detail());
                }
                admitted.add(p.def());
                dropped.addAll(p.dropped());
            }
            Pass p = pass(def);
            long passNanos = System.nanoTime() - passStart;
            if (!p.verdict().isAccepted()) {
                return settle(k, row, "fail:" + p.verdict().reasonToken(), p.verdict().detail());
            }
            def = p.def();
            dropped.addAll(p.dropped());
            admitted.add(def);
            List<byte[]> frames = new ArrayList<byte[]>(admitted.size());
            for (VariantDefinition body : admitted) {
                frames.add(VariantDefinitionFrameCodec.encode(body, tables));
            }
            Parked body = new Parked(id, List.copyOf(frames), def);
            admit(body);
            inflatedIds.put(k, id);
            bodies.put(k, body);
            logOk(goal, slot, id, factionId, body, p.verdict().unusedOp(), fitNanos, passNanos, false, dropped,
                    quality);
            firstFitDone = true;
            return row.withInflated(id);
        } catch (IOException | RuntimeException fault) {
            return settle(k, row, "fail:" + fault.getClass().getSimpleName(), fault.getMessage());
        }
    }

    /** One body's registrar pass: the def that passed (mods stripped or not), its verdict, the drops. */
    private record Pass(VariantDefinition def, Verdict verdict, List<String> dropped) {
    }

    /**
     * The strict engine pass over one body. Vanilla's SHIPLESS doFit can
     * add a hullmod the hull cannot carry (a shield hullmod on a shield_shunt Venture — inert in-game,
     * vanilla deploys it anyway). The pass stays strict: the GENERATOR drops the mod the verdict names
     * and passes again, once per named mod, bounded by the fit's own mod count; a verdict naming a mod
     * the def no longer carries falls through as the fail-soft row. The freed OP stays unspent (=
     * vanilla's inert spend); the ok row carries {@code dropped_hullmods=}.
     */
    private Pass pass(VariantDefinition def) {
        Verdict verdict = registrar.register(def);
        List<String> dropped = new ArrayList<String>();
        for (int guard = def.hullMods().size(); guard > 0 && !verdict.isAccepted()
                && DoorRejectReasons.CUSTOM_HULLMOD_INAPPLICABLE.equals(verdict.reasonToken()); guard--) {
            String modId = inapplicableModIdOrNull(verdict.detail());
            if (modId == null || !def.hullMods().contains(modId)) {
                break;
            }
            def = withoutHullMod(def, modId);
            dropped.add(modId);
            verdict = registrar.register(def);
        }
        return new Pass(def, verdict, dropped);
    }

    /**
     * Spool the body WHOLE, then fan it
     * ONCE as one unit (modules first, the parent LAST), then count it. WHY (security scan row 5): the
     * old per-frame spool + fan let a newcomer registered between two frames' live-set reads get the
     * parent at once while a module reached it only at the tail of its catalog snapshot (the kit
     * refuses that parent), or never re-sent the parent at all. Now every catalog snapshot taken after
     * the one {@code addAll} (copy-on-write: whole or nothing) holds the whole body, and the door fan
     * ({@link VariantFan#fanVariantDefinitions}, overridden in {@link #installOrNull}) reads the live
     * set once per body. A newcomer admitted between the {@code addAll} and the fan's live-set read
     * gets the body twice — idempotent on the kit's overwrite put, per the scan report.
     */
    private void admit(Parked body) {
        spool.addAll(body.frames());
        if (fanOrNull != null) {
            fanOrNull.fanVariantDefinitions(body.frames());
        }
        registered += body.frames().size();
    }

    /**
     * The mod id a {@code custom_hullmod_inapplicable} detail names — both
     * {@code RealEngineVariantRegistrar} #9 shapes, {@code "<id>: <why>"} and
     * {@code "<id> cannot validate detached (…)"}; null on a blank detail.
     */
    public static String inapplicableModIdOrNull(String detailOrNull) {
        if (detailOrNull == null) {
            return null;
        }
        int cut = detailOrNull.length();
        for (int i = 0; i < detailOrNull.length(); i++) {
            char c = detailOrNull.charAt(i);
            if (c == ':' || c == ' ') {
                cut = i;
                break;
            }
        }
        String id = detailOrNull.substring(0, cut).trim();
        return id.isEmpty() ? null : id;
    }

    /** The engine's D-hull twin id suffix ({@code Misc.getDHullId}: {@code <hull>_default_D}). */
    public static final String D_HULL_SUFFIX = "_default_D";

    /**
     * The d-mods a wave body carries = its perma mods that are not s-mods (game-free: the generator's
     * ONLY perma writers are {@code addDMods} → {@code addPermaMod(id, false)} and the s-mod pass →
     * {@code addPermaMod(id, true)}, so the difference is exactly the d-mod set).
     */
    public static int dModCount(VariantDefinition def) {
        int n = 0;
        for (String perma : def.permaMods()) {
            if (!def.sMods().contains(perma)) {
                n++;
            }
        }
        return n;
    }

    /** The same definition minus one hullmod (hullMods, plus the perma / s-mod lists that may echo it). */
    public static VariantDefinition withoutHullMod(VariantDefinition def, String modId) {
        return new VariantDefinition(def.variantId(), def.hullId(), def.displayName(), def.goalVariant(),
                def.fluxVents(), def.fluxCapacitors(), minus(def.hullMods(), modId), minus(def.permaMods(), modId),
                minus(def.sMods(), modId), def.sModdedBuiltIns(), def.suppressedMods(), def.wings(), def.tags(),
                def.weaponGroups(), def.modules());
    }

    private static List<String> minus(List<String> ids, String modId) {
        List<String> out = new ArrayList<String>(ids.size());
        for (String id : ids) {
            if (!id.equals(modId)) {
                out.add(id);
            }
        }
        return out;
    }

    /** The ONE {@code result=ok} row shape (a mint and a revive share it; the rig greps {@code result=ok }). */
    private void logOk(String goal, String slot, String id, String factionId, Parked body,
            int unusedOp, long fitNanos, long passNanos, boolean revived, List<String> droppedHullMods,
            float quality) {
        VariantDefinition def = body.def();
        int weapons = 0;
        for (VariantDefinitionFrameCodec.WeaponGroup g : def.weaponGroups()) {
            weapons += g.weapons().size();
        }
        System.out.println("npeer-serve wave_inflate result=ok goal=" + goal + " slot=" + slot.replace(' ', '_')
                + " id=" + id + " faction=" + factionId + " seed=" + knobs.seed()
                + " quality=" + String.format(java.util.Locale.ROOT, "%.2f", quality)
                + " weapons=" + weapons + " wings=" + def.wings().size() + " smods=" + def.sMods().size()
                + " dmods=" + dModCount(def) + " dhull=" + def.hullId().endsWith(D_HULL_SUFFIX)
                + " vents=" + def.fluxVents() + " caps=" + def.fluxCapacitors()
                + " unused_op=" + unusedOp + " frame_bytes=" + body.parentFrame().length
                + " modules=" + body.moduleCount() + " module_bytes=" + body.moduleBytes()
                + " fit_ms=" + ms(fitNanos) + " pass_ms=" + ms(passNanos)
                + " first=" + (!revived && !firstFitDone) + " revived=" + revived + " registered=" + registered
                + " dropped_hullmods=" + (droppedHullMods.isEmpty() ? "none" : String.join("|", droppedHullMods))
                + " weapon_ids=" + weaponIds(def));
    }

    /**
     * So a fit can be graded from the log alone (it used to take a seeded replay), the fitted
     * mounts as ONE greppable field — {@code weapon_ids=<slot>:<weaponId>|…} in weapon-GROUP order
     * (group 0 first, mounts in the variant's own order), the same shape
     * {@code RealEngineWaveInflateProbeMain.describe} prints. {@code weapons=} upstream stays the
     * authoritative COUNT: when the list is bounded away the field ends {@code |+<n>_more}, so
     * count > listed is the tell and no grade is ever silently short.
     *
     * <p>Row-grammar law: slot ids are interior-space-ALLOWED
     * ({@code VariantDefinitionFrameCodec.requireSlotId}: 1..32 printable ASCII, no edge spaces), and
     * a raw space would split the {@code key=value} row every rig greps — spaces become {@code _},
     * the same normalization the {@code slot=} field already applies. Weapon ids are
     * printable-no-space by the id law. Bounded by {@link #WEAPON_IDS_MAX_CHARS}. Cost: one bounded
     * StringBuilder per REGISTERED body (never per tick, never per row that skips/fails), on the
     * same call that already builds this row — the frame-loop residency budget is unchanged.
     */
    static String weaponIds(VariantDefinition def) {
        StringBuilder b = new StringBuilder();
        int listed = 0;
        int total = 0;
        for (VariantDefinitionFrameCodec.WeaponGroup g : def.weaponGroups()) {
            for (VariantDefinitionFrameCodec.WeaponMount m : g.weapons()) {
                total++;
                if (b.length() >= WEAPON_IDS_MAX_CHARS) {
                    continue;
                }
                if (b.length() > 0) {
                    b.append('|');
                }
                b.append(m.slotId().replace(' ', '_')).append(':').append(m.weaponId());
                listed++;
            }
        }
        if (total == 0) {
            return "-";
        }
        if (listed < total) {
            b.append("|+").append(total - listed).append("_more");
        }
        return b.toString();
    }

    /** Log the (goal, slot) verdict ONCE, memo it, hand the caller its own row back (stock goal id). */
    private WaveRow settle(String k, WaveRow row, String result, String detail) {
        settled.put(k, result);
        String d = detail == null ? "" : detail.replace('\n', ' ');
        if (d.length() > FAIL_DETAIL_MAX_CHARS) {
            d = d.substring(0, FAIL_DETAIL_MAX_CHARS) + "...";
        }
        System.out.println("npeer-serve wave_inflate result=" + result + " goal=" + row.variantId()
                + " slot=" + row.shipName().replace(' ', '_') + " seed=" + knobs.seed()
                + (d.isEmpty() ? "" : " detail=" + PeerTextSafe.quote(d)));
        return row;
    }

    // ------------------------------------------------------------------ install (the serve's composition root)

    /**
     * The wave lane's door fan — ONE
     * live-session read per body, then every frame of that body in list order to each session.
     * {@code liveSessions()} returns a fresh list built under the door lock (a snapshot, not a live
     * view), and {@code sendCtrlToSession} keeps per-session order (the CTRL spool is bypassed only
     * when empty; the CTRL channel delivers in sequence order), so each session that is fanned at all
     * gets the modules before the parent. A single frame is a one-frame body.
     */
    private static final class DoorBodyFan implements VariantFan {
        private final PilotIntentDatagramServer door;

        DoorBodyFan(PilotIntentDatagramServer door) {
            this.door = Objects.requireNonNull(door, "door");
        }

        @Override
        public void fanVariantDefinition(byte[] vdefFrame) {
            fanVariantDefinitions(List.of(vdefFrame));
        }

        @Override
        public void fanVariantDefinitions(List<byte[]> vdefFrames) {
            List<PilotIntentDatagramServer.PeerSession> live = door.liveSessions();
            for (PilotIntentDatagramServer.PeerSession session : live) {
                for (byte[] frame : vdefFrames) {
                    door.sendCtrlToSession(session, frame);
                }
            }
        }
    }

    /**
     * Build the lane over the probe-api engine half + a fresh registrar; null when the engine half is
     * absent (a game-free serve) or the id tables are not installed — with the reason on the boot row.
     */
    public static WaveLoadoutInflater installOrNull(PilotIntentDatagramServer door, WaveSchedule schedule,
            CustomVariantConfig customConfig, Knobs knobs) {
        Objects.requireNonNull(schedule, "schedule");
        EngineSide engine = probeApiOrNull();
        IdTables tables = IdTables.installed();
        if (engine == null || tables == null) {
            System.out.println("npeer-serve wave_inflate_boot enabled=false reason="
                    + (engine == null ? "autofit_class_unavailable" : "id_tables_not_installed")
                    + " seed=" + knobs.seed());
            return null;
        }
        String stamp = "wave-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        cc.starsector.server.bridge.RealEngineVariantRegistrar registrar =
                new cc.starsector.server.bridge.RealEngineVariantRegistrar(customConfig, stamp);
        VariantFan fan = new DoorBodyFan(door);
        WaveLoadoutInflater lane = new WaveLoadoutInflater(engine, registrar, fan, tables, knobs);
        String category = schedule.category();
        String factionId = WaveSchedule.vanillaFactionIdFor(category);
        String quality;
        int known;
        try {
            quality = Float.isNaN(knobs.qualityOrNaN())
                    ? "core(" + engine.defaultQuality(factionId) + ")" : String.valueOf(knobs.qualityOrNaN());
            known = engine.knownWeaponCount(factionId);
        } catch (RuntimeException noFaction) {
            quality = "n/a";
            known = -1;
        }
        System.out.println("npeer-serve wave_inflate_boot enabled=" + knobs.enabled() + " category=" + category
                + " faction=" + factionId + " vanilla_inflated="
                + !(WaveSchedule.isBossOnlyCategory(category) || WaveSchedule.isVanillaUninflatedCategory(category))
                + " quality=" + quality + " quality_per_wave=" + knobs.qualityPerWave()
                + " seed=" + knobs.seed() + " smods="
                + (knobs.averageSModsOrNeg() < 0 ? "none" : String.valueOf(knobs.averageSModsOrNeg()))
                + " dmods=" + knobs.dmods()
                + " known_weapons=" + known + " cap=" + REGISTER_CAP
                + " budget=" + VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL
                + " warm_budget_ms=" + String.format(java.util.Locale.ROOT, "%.1f", WARM_BUDGET_NANOS / 1_000_000.0d)
                + " (vanilla fleet inflation over the wave rows; re-serve with this seed = same loadouts)");
        schedule.setRowInflater(lane::stage);
        door.setWaveVariantCatalog(lane::frames);
        lane.preInflate(schedule);
        return lane;
    }

    /** The probe-api statics by name (the unit lands in server/classes beside us; absent on a bare cp). */
    static EngineSide probeApiOrNull() {
        try {
            ClassLoader loader = WaveLoadoutInflater.class.getClassLoader();
            Class<?> c = Class.forName(AUTOFIT_CLASS, true, loader);
            Method skip = c.getMethod("skipReasonOrNull", String.class);
            Method inflate = c.getMethod("inflateToVariantJson", String.class, String.class, String.class,
                    float.class, long.class, int.class, boolean.class, String.class);
            Method quality = c.getMethod("defaultQuality", String.class);
            Method known = c.getMethod("knownWeaponCount", String.class);
            return new EngineSide() {
                @Override
                public String skipReasonOrNull(String goalVariantId) {
                    return (String) call(skip, goalVariantId);
                }

                @Override
                public String inflateToVariantJson(String goalVariantId, String memberKey, String factionId,
                        float qualityOrNaN, long seed, int averageSModsOrNeg, boolean dmods, String newVariantId) {
                    return (String) call(inflate, goalVariantId, memberKey, factionId, qualityOrNaN, seed,
                            averageSModsOrNeg, dmods, newVariantId);
                }

                @Override
                public float defaultQuality(String factionId) {
                    return (Float) call(quality, factionId);
                }

                @Override
                public int knownWeaponCount(String factionId) {
                    return (Integer) call(known, factionId);
                }
            };
        } catch (Throwable absent) {
            System.out.println("npeer-serve wave_inflate engine_half=absent " + absent.getClass().getSimpleName()
                    + ": " + absent.getMessage());
            return null;
        }
    }

    private static Object call(Method m, Object... args) {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException(denied);
        }
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0d);
    }

    /** Log-line quoting for engine-authored detail (never peer text here; the cut idiom kept). */
    static final class PeerTextSafe {
        static String quote(String s) {
            return cc.starsector.server.ops.PeerText.quote(s);
        }
    }
}
