package cc.starsector.server.runtime;

import cc.starsector.server.runtime.CustomVariantLane.Registrar;
import cc.starsector.server.runtime.CustomVariantLane.VariantFan;
import cc.starsector.server.runtime.CustomVariantLane.Verdict;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The REALIZED loadout lane. WHY: a mod hullmod may pick a weapon by a
 * random roll inside {@code applyEffectsBeforeShipCreation}, which the engine runs inside the {@code FleetMember}
 * constructor. The server's member rolls once and every client window builds its own member from the same stock
 * variant id and rolls again, so a window can fly a different weapon than the server simulates. The server holds
 * the realized member before the seat's RAPP row is built, so the realized variant is carried on the EXISTING VDEF
 * carriage: the client builds the mod's own output, and the selector state the mod wrote rides as an
 * ordinary hullmod, so the client's copy of the hook sees it and does not roll. No new wire family, no new
 * client-trusted input.
 *
 * <p>The law, per fly-in ({@link #realize}) — a seat's, or an AI hull's under {@link #AI_SEAT}:
 * the member's variant and the registry's stock variant for the
 * fielded (goal) id are serialized by the probe-api twin ({@link #ENGINE_METHOD}, by NAME — the product unit stays
 * game-jar-free) and compared as CANONICAL text (keys sorted, the set-valued hullmod / tag lists sorted, the id
 * dropped). Equal = nothing (every vanilla hull: one compare). Different = the minted id
 * {@code cc_real_<goal>_<hex8>} ({@link #mintedIdFor}: the first 8 lowercase hex of SHA-256 over the canonical
 * text, so the same roll re-mints the same id and dedupes by content), the body = the twin's JSON under that id,
 * parsed by the catalog reader, encoded as ONE VDEF frame (the codec's admission), passed by the SAME engine
 * registrar the wave inflater runs, through its REALIZED entry ({@code Registrar.registerRealized}: the input is the
 * server's own engine state, not peer text, so a weapon a mod hook wrote into a built-in slot passes where the peer
 * tier refuses it; inside the RNG isolation window, so the pass's throwaway member never shifts a combat stream),
 * then spooled for late joiners and fanned to every live session (the inflater's register, fan, spool shape) —
 * and the RAPP row names the minted id. The mod's hook is not re-run on the server (the server's hull IS the
 * realized member); the registration keeps the minted id resolvable for the engine's own lookups.
 *
 * <p>Budget: {@link #REGISTER_CAP} distinct bodies per process, a term of the ONE VDEF admission budget
 * ({@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL}), carved from the wave term. Past the cap: no mint, the
 * goal id stays (each window rolls its own: the fork this lane exists to close), ONE loud {@code over_cap} row per
 * goal and a counted refusal. Fail-SOFT, never concealed: any fault keeps the goal id with one row per goal (a
 * compare fault) or per minted id (a mint fault), memoized so a refused body never re-runs the engine pass.
 *
 * <p>Coverage: every SEAT hull — the roster's {@code midDeploySeat} is the one deploy every join, respawn and
 * round-reset fly-in passes, and it hands its {@code SeatDeployResult} variant id to both fly-in composers — and,
 *every AI hull the roster's shared mid-round mint body ({@code midDeployCore}: the wave
 * drain, the {@code /spawnfoe} adhoc drain, the wave probe, the pod-scenario targets) fields under
 * {@link #AI_SEAT}: its {@code WaveDeployResult} carries the fielded id to every RAPP row composer, and the ONE
 * RAPP fan point ({@code PersistentPilotIntentCombatServer.fanRosterAppendChunks}) drains the pending realized
 * VDEFs first, so no lane can name a minted id before its body. An AI hull has no seat, so no sticky memory: each
 * spawn is its own roll (the way the campaign rolls each ship once at its creation) and equal rolls dedupe on the
 * content cache. A STATION is skipped (the client's station adopt lane keys its module band on the stock id).
 * NOT covered: hulls no RAPP row names (the battle-start AI rows and station the context's {@code deployAll}
 * fields, which every window builds from the match descriptor), a command transfer onto such a hull, a command
 * transfer onto / off a MINTED AI hull (the seat re-point names the GOAL — {@code SeatRebind} reads the engine's
 * own variant id, the stock clone's — so the transferring window re-fields from the goal: the fork returns for that
 * one hull, loud on the client's fork row; the fix when it bites = {@link #realizeSeatOrGoal} on the target member
 * at the re-point, the content cache returning the same minted id), the vacated
 * SOURCE of a command transfer off a seat's FIRST life of a rolled hull (its re-homed announce row,
 * {@link CommandTransferServeLane#sourceAnnounceRow}, names the hull's own engine variant id, which on that life is
 * the stock id — the member was built from the stock clone — so a window that mints that body on a rejoin rolls its
 * own; the client's replica-loadout fork row meters it; a STICKY life's hull is built from the minted body and
 * carries its id), and a module's own roll (the body names the stock module ids).
 *
 * <p>STICKY roll: one roll per seat per match, the way a campaign ship keeps the roll the mod
 * made once at its creation. Every life of a seat is a new {@code FleetMember}, so without this each join, respawn
 * and round-reset fly-in rolled again and the realized id changed under a live client (a fresh body at round open).
 * The lane remembers each seat's last minted id ({@link #stickyIdFor}); the roster builds the seat's next member
 * from that registered body while the seat's pick is still its goal, the mod's hook finds its selector state
 * already in the body and does not roll, and the compare lands on the content cache: the same id, no new body. A
 * pick change is a fresh roll. The memory is the lane's own, so it lives exactly as long as the process lane (one
 * per serve; the serve IS the match: an end-match ends it); a round reset never touches it.
 *
 * <p>Threads: {@link #realize} and {@link #flushToDoor} run on the sim thread (the roster's monitor / the serve
 * loop); {@link #frames} is read by the door's reader thread at admission (copy-on-write). The process lane's live
 * fan is DEFERRED: the roster mints before any door is in hand, so its frames queue in {@link PendingFan} (bounded
 * by {@link #REGISTER_CAP}: one frame per mint) and the ONE RAPP fan point
 * ({@code PersistentPilotIntentCombatServer.fanRosterAppendChunks}) drains them to every live session after its
 * rows are built and BEFORE it fans them (VDEF before RAPP on the one CTRL FIFO); the seat announce's own flush
 * ({@code SeatAnnounceTracker}) is an earlier drain of the same list, so a second drain is a no-op. Late joiners
 * read the spool through the door's server-generated catalog slot, composed after the wave bodies
 * ({@link #attachSpool}; the first drain attaches it).
 *
 * <p>LINK LAW: the fly-in / announce path of EVERY serve links this class ({@link #realizeSeatOrGoal},
 * {@link #stickyIdForSeat}, {@link #flushToDoor}, {@link #goalOf}) — a serve whose classpath carries no json.jar included (the NPeer contract
 * leg runs the serve over the server + test classes alone; the realized compare is engine-only work it never
 * reaches). So this class's OWN bytecode names no org.json type: the verifier loads every catch type at link time,
 * and a {@code catch (JSONException ...)} here was a {@code NoClassDefFoundError: org/json/JSONException} that
 * killed the serve thread at seat 1's fly-in (the RED suite, deterministic; green standalone, where
 * run-test-main adds json.jar). Every org.json reference lives in {@link Canonical} — its own class file, loaded by
 * the first compare — which rethrows the checked {@code JSONException} as an unchecked fault.
 *
 * <p>Security: no wire input (the server's own engine state); the minted id's charset is lawful by construction
 * (a registry id + {@code _} + lowercase hex) and its length is asserted under
 * {@code VariantDefinitionFrameCodec.ID_MAX_CHARS}; the engine pass is the validator a peer submission gets.
 */
public final class RealizedLoadoutLane {

    /** Minted id prefix; never the ledger's {@code cc-v}, the wave lane's {@code cc_wave_} nor {@code wendigo}. */
    public static final String ID_PREFIX = "cc_real_";
    /** The minted id's hex tail length (lowercase {@code [0-9a-f]}). */
    public static final int HEX_CHARS = 8;
    /**
     * Distinct realized bodies per process: the realized term of the ONE admission budget — boot 256 + customs 64
     * + waves 576 + realized 64 = {@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL} 960, carved from the wave
     * term (which was 640), written as a literal so the budget row FAILS when any term moves. NOT sized for every
     * roll space: the Valkazard Kai's swap rolls one of 600 bodies (6*5*5*4), so about 68 fresh Kai rolls in one
     * process (a seat's first Kai life or a pick change back to it; a sticky re-field is a cache hit, never a new
     * body) exhaust the 64, after which every further new Kai roll keeps the goal id and the fork returns, LOUD (one
     * {@code over_cap} row per goal and the counted {@link #overCapRefusals}).
     */
    public static final int REGISTER_CAP = 64;
    /**
     * The seat id an AI hull (wave / adhoc / probe / pod target) realizes under — below every
     * hull seat (&gt;= 1), so {@link #realize} keeps NO sticky memory for it: each AI spawn is its own roll, equal
     * rolls dedupe on the content cache, and a seat's sticky roll is never read or written by an AI mint.
     */
    public static final int AI_SEAT = 0;
    /** The probe-api twin's method name ({@code WaveLoadoutInflater.AUTOFIT_CLASS}). */
    public static final String ENGINE_METHOD = "realizedVariantJson";
    public static final int FAIL_DETAIL_MAX_CHARS = 160;
    static final String SHIP_VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String ID_KEY = "variantId";
    /** The set-valued {@code .variant} lists (the engine holds them as sets): sorted in the canonical text. */
    private static final Set<String> SET_KEYS = Set.of("hullMods", "permaMods", "sMods", "sModdedBuiltIns",
            "suppressedMods", "tags");

    /** The engine half: one {@code .variant} JSON text per engine variant object (a fixture stands in). */
    public interface EngineSide {
        String variantJson(Object variant, String id);
    }

    /**
     * One fly-in's verdict: {@code namedId} = the id the RAPP row names (the minted id, else the goal id);
     * {@code result} = {@code equal} / {@code minted} / {@code cached} / a {@code skip:} or {@code fail:} token.
     */
    public record Outcome(String namedId, boolean minted, boolean cached, String result, long compareNanos,
            long mintNanos) {
        static Outcome goal(String goalId, String result, long compareNanos, long mintNanos) {
            return new Outcome(goalId, false, false, result, compareNanos, mintNanos);
        }
    }

    private final EngineSide engine;
    private final Registrar registrar;
    private final VariantFan fanOrNull;
    private final IdTables tables;
    private final int registerCap;
    /** Minted id → its canonical text (the content cache; a hit with different text = a hash collision). */
    private final Map<String, String> canonicalByMintedId = new HashMap<>();
    /** Minted ids whose mint failed (logged once; never re-run). */
    private final Set<String> failedMintedIds = new HashSet<>();
    /** Goals whose compare fault (or too-long minted id) already printed its row. */
    private final Set<String> loggedGoals = new HashSet<>();
    /** Goals whose over-cap refusal already printed its ONE row. */
    private final Set<String> overCapGoals = new HashSet<>();
    private final CopyOnWriteArrayList<byte[]> spool = new CopyOnWriteArrayList<>();
    /**
     * Seat id (the RAPP row's seat, &gt;= 1 for a hull seat) → the minted id its
     * last fly-in named, written at every mint AND cache hit, dropped when that seat flies in under another goal (a
     * pick change = a fresh roll). The STICKY roll's memory (class javadoc); never cleared by a round reset.
     */
    private final Map<Integer, String> lastMintedBySeat = new HashMap<>();
    private int registered;
    private long overCapRefusals;
    private long faults;
    private boolean firstEqualLogged;

    public RealizedLoadoutLane(EngineSide engine, Registrar registrar, VariantFan fanOrNull, IdTables tables) {
        this(engine, registrar, fanOrNull, tables, REGISTER_CAP);
    }

    /** The contract rows' seam: a small {@code registerCap} exercises the cap path cheaply. */
    public RealizedLoadoutLane(EngineSide engine, Registrar registrar, VariantFan fanOrNull, IdTables tables,
            int registerCap) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.fanOrNull = fanOrNull;
        this.tables = Objects.requireNonNull(tables, "tables");
        if (registerCap < 1) {
            throw new IllegalArgumentException("registerCap must be >= 1: " + registerCap);
        }
        this.registerCap = registerCap;
    }

    // ------------------------------------------------------------------ the id shape (both trees, identical)

    /**
     * True iff {@code id} is a realized mint: {@code cc_real_} + a non-empty goal + {@code _} + exactly
     * {@link #HEX_CHARS} lowercase hex. Anything else (null included) is NOT realized — fail closed.
     */
    public static boolean isRealizedId(String id) {
        if (id == null || !id.startsWith(ID_PREFIX)) {
            return false;
        }
        int tail = HEX_CHARS + 1;
        if (id.length() < ID_PREFIX.length() + 1 + tail || id.charAt(id.length() - tail) != '_') {
            return false;
        }
        for (int i = id.length() - HEX_CHARS; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** The goal (stock) id a realized mint stands for; any other id (null included) is its own goal. */
    public static String goalOf(String id) {
        return isRealizedId(id) ? id.substring(ID_PREFIX.length(), id.length() - HEX_CHARS - 1) : id;
    }

    /** The minted id for {@code goalId} + the canonical text; THROWS past the wire id cap. */
    public static String mintedIdFor(String goalId, String canonicalText) {
        String id = ID_PREFIX + goalId + "_" + hex8(canonicalText);
        if (id.length() > VariantDefinitionFrameCodec.ID_MAX_CHARS) {
            throw new IllegalArgumentException("realized id past the wire id cap ("
                    + VariantDefinitionFrameCodec.ID_MAX_CHARS + "): " + id.length() + " chars");
        }
        return id;
    }

    /** The first {@link #HEX_CHARS} lowercase hex chars of SHA-256 over the UTF-8 text. */
    static String hex8(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(HEX_CHARS);
            for (int i = 0; i < HEX_CHARS / 2; i++) {
                b.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // ------------------------------------------------------------------ the canonical form

    /**
     * Each top-level field of a {@code .variant} JSON text as canonical text, keyed and sorted by name, the id field
     * dropped: objects by sorted key, arrays in order, except the {@link #SET_KEYS} lists, sorted (the engine holds
     * them as sets, so an iteration-order difference is not a loadout difference). Malformed text =
     * {@link IllegalArgumentException}; the org.json walk is {@link Canonical#fields} (the LINK LAW above).
     */
    public static TreeMap<String, String> canonicalFields(String variantJson) {
        return Canonical.fields(variantJson);
    }

    /** The canonical text the hash reads: the {@link #canonicalFields} joined as one sorted JSON object. */
    public static String canonicalText(Map<String, String> fields) {
        return Canonical.text(fields);
    }

    // ------------------------------------------------------------------ the lane

    /**
     * One fly-in (sim thread; a seat's, or an AI hull's under {@link #AI_SEAT}): compare, then mint or hand the goal
     * back. Never throws — every fault keeps the goal id ({@code seatId} / {@code wireId} ride the rows only).
     */
    public synchronized Outcome realize(int seatId, long wireId, String goalId, Object stockVariant,
            Object realizedVariant) {
        if (goalId == null || goalId.isEmpty() || stockVariant == null || realizedVariant == null) {
            return Outcome.goal(goalId, "skip:no_input", 0L, 0L);
        }
        if (isRealizedId(goalId)) {
            return Outcome.goal(goalId, "skip:already_realized", 0L, 0L);
        }
        boolean seatHull = seatId >= 1; // An AI hull (AI_SEAT) keeps no sticky memory
        String lastMinted = seatHull ? lastMintedBySeat.get(seatId) : null;
        if (lastMinted != null && !goalId.equals(goalOf(lastMinted))) {
            lastMintedBySeat.remove(seatId); // a pick change: the seat's next life under the old goal rolls fresh
        }
        long compareStart = System.nanoTime();
        TreeMap<String, String> stock;
        TreeMap<String, String> realized;
        try {
            stock = canonicalFields(engine.variantJson(stockVariant, goalId));
            realized = canonicalFields(engine.variantJson(realizedVariant, goalId));
        } catch (RuntimeException fault) { // Canonical rethrows the checked JSONException unchecked (the LINK LAW)
            long nanos = System.nanoTime() - compareStart;
            faults++;
            if (loggedGoals.add(goalId)) {
                failRow(seatId, wireId, goalId, "-", "fail:compare_" + fault.getClass().getSimpleName(),
                        fault.getMessage());
            }
            return Outcome.goal(goalId, "fail:compare", nanos, 0L);
        }
        long compareNanos = System.nanoTime() - compareStart;
        if (stock.equals(realized)) {
            if (!firstEqualLogged) {
                firstEqualLogged = true;
                System.out.println("npeer-serve realized_loadout first_compare seat=" + seatId + " wire_id=" + wireId
                        + " goal=" + goalId + " equal=1 compare_us=" + micros(compareNanos)
                        + " (the per-fly-in cost on a hull no mod rolled)");
            }
            return Outcome.goal(goalId, "equal", compareNanos, 0L);
        }
        long mintStart = System.nanoTime();
        String canonical = canonicalText(realized);
        String diff = diffKeys(stock, realized);
        String mintedId;
        try {
            mintedId = mintedIdFor(goalId, canonical);
        } catch (IllegalArgumentException tooLong) {
            faults++;
            if (loggedGoals.add(goalId)) {
                failRow(seatId, wireId, goalId, "-", "fail:id_too_long", tooLong.getMessage());
            }
            return Outcome.goal(goalId, "fail:id_too_long", compareNanos, 0L);
        }
        String known = canonicalByMintedId.get(mintedId);
        if (known != null) {
            if (known.equals(canonical)) {
                if (seatHull) {
                    lastMintedBySeat.put(seatId, mintedId);
                }
                long mintNanos = System.nanoTime() - mintStart;
                mintRow(seatId, wireId, goalId, mintedId, true, diff, compareNanos, mintNanos);
                return new Outcome(mintedId, true, true, "cached", compareNanos, mintNanos);
            }
            faults++;
            if (failedMintedIds.add(mintedId + "|collision")) {
                failRow(seatId, wireId, goalId, mintedId, "fail:hash_collision", "a different body holds this id");
            }
            return Outcome.goal(goalId, "fail:hash_collision", compareNanos, 0L);
        }
        if (failedMintedIds.contains(mintedId)) {
            return Outcome.goal(goalId, "fail:memo", compareNanos, 0L);
        }
        if (registered >= registerCap) {
            overCapRefusals++;
            if (overCapGoals.add(goalId)) {
                System.out.println("npeer-serve realized_loadout over_cap goal=" + goalId + " cap=" + registerCap
                        + " seat=" + seatId + " wire_id=" + wireId + " refusals=" + overCapRefusals
                        + " (no mint: every window builds its own roll for this hull, a loadout fork)");
            }
            return Outcome.goal(goalId, "skip:over_cap", compareNanos, 0L);
        }
        try {
            VariantDefinition def = Canonical.parseVariant(engine.variantJson(realizedVariant, mintedId));
            if (!mintedId.equals(def.variantId())) {
                return mintFail(seatId, wireId, goalId, mintedId, "fail:id_mismatch", def.variantId(), compareNanos);
            }
            byte[] frame = VariantDefinitionFrameCodec.encode(def, tables);
            Verdict verdict = CombatRngRearm.isolate(() -> registrar.registerRealized(def));
            if (!verdict.isAccepted()) {
                return mintFail(seatId, wireId, goalId, mintedId, "fail:" + verdict.reasonToken(), verdict.detail(),
                        compareNanos);
            }
            spool.add(frame);
            if (fanOrNull != null) {
                fanOrNull.fanVariantDefinition(frame);
            }
            registered++;
            canonicalByMintedId.put(mintedId, canonical);
            if (seatHull) {
                lastMintedBySeat.put(seatId, mintedId);
            }
            long mintNanos = System.nanoTime() - mintStart;
            mintRow(seatId, wireId, goalId, mintedId, false, diff, compareNanos, mintNanos);
            return new Outcome(mintedId, true, false, "minted", compareNanos, mintNanos);
        } catch (IOException | RuntimeException fault) {
            return mintFail(seatId, wireId, goalId, mintedId, "fail:" + fault.getClass().getSimpleName(),
                    fault.getMessage(), compareNanos);
        }
    }

    /** The late-joiner spool: every minted realized VDEF, mint order (a snapshot). */
    public List<byte[]> frames() {
        return List.copyOf(spool);
    }

    public synchronized int registeredCount() {
        return registered;
    }

    public synchronized long overCapRefusals() {
        return overCapRefusals;
    }

    public synchronized long faults() {
        return faults;
    }

    /**
     * The STICKY roll's read — the minted id {@code seatId}'s last fly-in
     * named when that id stands for {@code goalId} ({@link #goalOf}), else null (no mint yet, another goal, or a
     * pick change since). The id is registered (written only after an accepted pass or a cache hit).
     */
    public synchronized String stickyIdFor(int seatId, String goalId) {
        String last = seatId >= 1 ? lastMintedBySeat.get(seatId) : null; // Never for AI_SEAT
        return last != null && goalId != null && goalId.equals(goalOf(last)) ? last : null;
    }

    private Outcome mintFail(int seatId, long wireId, String goalId, String mintedId, String token, String detail,
            long compareNanos) {
        faults++;
        if (failedMintedIds.add(mintedId)) {
            failRow(seatId, wireId, goalId, mintedId, token, detail);
        }
        return Outcome.goal(goalId, token, compareNanos, 0L);
    }

    private void mintRow(int seatId, long wireId, String goalId, String mintedId, boolean cached, String diff,
            long compareNanos, long mintNanos) {
        System.out.println("npeer-serve realized_loadout seat=" + seatId + " wire_id=" + wireId + " goal=" + goalId
                + " minted=" + mintedId + " cached=" + (cached ? 1 : 0) + " diff=" + diff
                + " compare_us=" + micros(compareNanos) + " mint_us=" + micros(mintNanos)
                + " registered=" + registered + "/" + registerCap);
    }

    private static void failRow(int seatId, long wireId, String goalId, String mintedId, String token,
            String detail) {
        String d = detail == null ? "" : detail.replace('\n', ' ');
        if (d.length() > FAIL_DETAIL_MAX_CHARS) {
            d = d.substring(0, FAIL_DETAIL_MAX_CHARS) + "...";
        }
        System.out.println("npeer-serve realized_loadout result=" + token + " seat=" + seatId + " wire_id=" + wireId
                + " goal=" + goalId + " minted=" + mintedId + " (the goal id stays: every window builds its own roll)"
                + (d.isEmpty() ? "" : " detail=" + WaveLoadoutInflater.PeerTextSafe.quote(d)));
    }

    /** The top-level field names whose canonical values differ (the row's {@code diff=}; bounded by the shape). */
    static String diffKeys(Map<String, String> a, Map<String, String> b) {
        TreeSet<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        StringBuilder out = new StringBuilder();
        for (String k : keys) {
            if (!Objects.equals(a.get(k), b.get(k))) {
                out.append(out.length() == 0 ? "" : ",").append(k);
            }
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private static long micros(long nanos) {
        return nanos / 1_000L;
    }

    // ------------------------------------------------------------------ the process seam (the roster + the door)

    /**
     * The process lane's deferred live fan: frames queue at mint; {@code fanRosterAppendChunks} (the one RAPP fan
     * point) drains them to the door, the seat announce's flush an earlier drain of the same list.
     */
    private static final class PendingFan implements VariantFan {
        private final List<byte[]> pending = new ArrayList<>();

        @Override
        public synchronized void fanVariantDefinition(byte[] vdefFrame) {
            pending.add(vdefFrame);
        }

        synchronized List<byte[]> drain() {
            List<byte[]> out = List.copyOf(pending);
            pending.clear();
            return out;
        }
    }

    private static final Object PROCESS_LOCK = new Object();
    private static volatile RealizedLoadoutLane processLaneOrNull;
    private static PendingFan processPendingFan;
    private static boolean processTried;
    /** Doors whose server-generated catalog slot already carries the realized spool. */
    private static final Set<PilotIntentDatagramServer> ATTACHED_DOORS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The roster's fly-in seam (sim thread, the roster's monitor held): the id the hull's RAPP row names — a
     * minted realized id when the member's variant differs from the registry's stock variant, else {@code goalId}.
     * {@code seatId} = the hull seat (&gt;= 1) or {@link #AI_SEAT} for a wave / adhoc / probe / pod hull
     * ({@code wireId} is then {@code -1}: an AI hull is interned AFTER its mint, the drain's own spawn
     * row joins the ids). Builds the process lane on first use; an absent engine half or id table = the goal id,
     * always.
     */
    public static String realizeSeatOrGoal(int seatId, long wireId, String goalId, Object stockVariant,
            Object realizedVariant) {
        RealizedLoadoutLane lane = processLaneOrInstall();
        return lane == null ? goalId : lane.realize(seatId, wireId, goalId, stockVariant, realizedVariant).namedId();
    }

    /**
     * The roster's STICKY seam, read BEFORE the member is built — the process
     * lane's {@link #stickyIdFor}, or null with no process lane (before the first fly-in, or the lane off). Never
     * installs the lane.
     */
    public static String stickyIdForSeat(int seatId, String goalId) {
        RealizedLoadoutLane lane = processLaneOrNull;
        return lane == null ? null : lane.stickyIdFor(seatId, goalId);
    }

    /** The process lane's spool (empty before the first fly-in or with the lane off); reader-thread safe. */
    public static List<byte[]> processFrames() {
        RealizedLoadoutLane lane = processLaneOrNull;
        return lane == null ? List.of() : lane.frames();
    }

    /**
     * The drain (sim thread): send every queued realized frame to each live session of {@code door} and make sure
     * the door's late-joiner slot carries the spool. Called by {@code fanRosterAppendChunks}, the ONE RAPP fan point
     * (after its rows are built, BEFORE it fans them: no fanned row names a minted id ahead of its body), and earlier
     * by the seat announce ({@code SeatAnnounceTracker}) — a second drain is a no-op. No process lane = a no-op (a
     * game-free serve, a contract door). Returns the frames sent per session.
     */
    public static int flushToDoor(PilotIntentDatagramServer door) {
        RealizedLoadoutLane lane = processLaneOrNull;
        PendingFan pending;
        synchronized (PROCESS_LOCK) {
            pending = processPendingFan;
        }
        if (lane == null || pending == null || door == null) {
            return 0;
        }
        boolean fresh;
        synchronized (PROCESS_LOCK) {
            fresh = !ATTACHED_DOORS.contains(door);
        }
        if (fresh) {
            attachSpool(door, null);
        }
        List<byte[]> frames = pending.drain();
        if (frames.isEmpty()) {
            return 0;
        }
        List<PilotIntentDatagramServer.PeerSession> live = door.liveSessions();
        for (PilotIntentDatagramServer.PeerSession session : live) {
            for (byte[] frame : frames) {
                door.sendCtrlToSession(session, frame);
            }
        }
        System.out.println("npeer-serve realized_loadout fan frames=" + frames.size() + " sessions=" + live.size());
        return frames.size();
    }

    /**
     * Install the door's server-generated VDEF catalog slot as the wave bodies ({@code waveFramesOrNull}, null =
     * no wave lane) followed by the realized spool — every write to that slot goes through here, so neither
     * producer drops the other's frames from a newcomer's catch-up (both spool before any RAPP row naming them).
     */
    public static void attachSpool(PilotIntentDatagramServer door, Supplier<List<byte[]>> waveFramesOrNull) {
        Objects.requireNonNull(door, "door");
        synchronized (PROCESS_LOCK) {
            ATTACHED_DOORS.add(door);
        }
        door.setWaveVariantCatalog(() -> {
            List<byte[]> out = new ArrayList<>(waveFramesOrNull == null ? List.of() : waveFramesOrNull.get());
            out.addAll(processFrames());
            return out;
        });
    }

    private static RealizedLoadoutLane processLaneOrInstall() {
        synchronized (PROCESS_LOCK) {
            if (processTried) {
                return processLaneOrNull;
            }
            processTried = true;
            EngineSide engine = probeApiOrNull();
            IdTables tables = IdTables.installed();
            if (engine == null || tables == null) {
                System.out.println("npeer-serve realized_loadout_boot enabled=false reason="
                        + (engine == null ? "engine_half_absent" : "id_tables_not_installed"));
                return null;
            }
            CustomVariantConfig config;
            try {
                config = CustomVariantConfig.resolve();
            } catch (RuntimeException badKnob) {
                config = CustomVariantConfig.defaults();
                System.out.println("npeer-serve realized_loadout_boot config=defaults reason="
                        + badKnob.getClass().getSimpleName());
            }
            String stamp = "realized-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            PendingFan fan = new PendingFan();
            RealizedLoadoutLane lane = new RealizedLoadoutLane(engine,
                    new cc.starsector.server.bridge.RealEngineVariantRegistrar(config, stamp), fan, tables);
            processPendingFan = fan;
            processLaneOrNull = lane;
            System.out.println("npeer-serve realized_loadout_boot enabled=true cap=" + REGISTER_CAP + " budget="
                    + VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL
                    + " (a seat or AI hull whose member variant differs from stock flies a minted VDEF)");
            return lane;
        }
    }

    /** The probe-api twin by name (absent on a bare classpath: the game API does not link). */
    static EngineSide probeApiOrNull() {
        try {
            ClassLoader loader = RealizedLoadoutLane.class.getClassLoader();
            Class<?> variantApi = Class.forName(SHIP_VARIANT_API, false, loader);
            Class<?> c = Class.forName(WaveLoadoutInflater.AUTOFIT_CLASS, true, loader);
            Method m = c.getMethod(ENGINE_METHOD, variantApi, String.class);
            return (variant, id) -> (String) call(m, variant, id);
        } catch (Throwable absent) {
            System.out.println("npeer-serve realized_loadout engine_half=absent " + absent.getClass().getSimpleName()
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

    // ------------------------------------------------------------------ the org.json half (its OWN class file)

    /**
     * Every org.json reference of this unit, in its own class file (the LINK LAW in the class javadoc): loaded by
     * the first compare or mint, never by the fly-in / announce path that links the lane. The checked
     * {@code JSONException} never leaves this class — it is rethrown as an {@link IllegalArgumentException}, which
     * the lane's {@code RuntimeException} catches turn into a {@code fail:compare} / {@code fail:} row.
     */
    static final class Canonical {
        private Canonical() {
        }

        /** {@link RealizedLoadoutLane#canonicalFields}. */
        static TreeMap<String, String> fields(String variantJson) {
            try {
                JSONObject o = new JSONObject(variantJson);
                TreeMap<String, String> out = new TreeMap<>();
                Iterator<?> keys = o.keys();
                while (keys.hasNext()) {
                    String k = String.valueOf(keys.next());
                    if (ID_KEY.equals(k)) {
                        continue;
                    }
                    Object v = o.get(k);
                    StringBuilder b = new StringBuilder();
                    if (SET_KEYS.contains(k) && v instanceof JSONArray array) {
                        List<String> sorted = new ArrayList<>(array.length());
                        for (int i = 0; i < array.length(); i++) {
                            sorted.add(String.valueOf(array.get(i)));
                        }
                        Collections.sort(sorted);
                        b.append('[');
                        for (int i = 0; i < sorted.size(); i++) {
                            b.append(i == 0 ? "" : ",").append(JSONObject.quote(sorted.get(i)));
                        }
                        b.append(']');
                    } else {
                        append(b, v);
                    }
                    out.put(k, b.toString());
                }
                return out;
            } catch (JSONException malformed) {
                throw new IllegalArgumentException("variant json: " + malformed.getMessage(), malformed);
            }
        }

        /** {@link RealizedLoadoutLane#canonicalText}. */
        static String text(Map<String, String> fields) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
                b.append(first ? "" : ",").append(JSONObject.quote(e.getKey())).append(':').append(e.getValue());
                first = false;
            }
            return b.append('}').toString();
        }

        /** The catalog reader over the twin's text under the minted id (the codec's admission follows upstream). */
        static VariantDefinition parseVariant(String variantJson) {
            try {
                return VariantCatalogStore.parseVariantChecked(new JSONObject(variantJson));
            } catch (JSONException malformed) {
                throw new IllegalArgumentException("variant json: " + malformed.getMessage(), malformed);
            }
        }

        private static void append(StringBuilder b, Object v) throws JSONException {
            if (v instanceof JSONObject o) {
                TreeSet<String> keys = new TreeSet<>();
                Iterator<?> it = o.keys();
                while (it.hasNext()) {
                    keys.add(String.valueOf(it.next()));
                }
                b.append('{');
                boolean first = true;
                for (String k : keys) {
                    b.append(first ? "" : ",").append(JSONObject.quote(k)).append(':');
                    append(b, o.get(k));
                    first = false;
                }
                b.append('}');
            } else if (v instanceof JSONArray a) {
                b.append('[');
                for (int i = 0; i < a.length(); i++) {
                    b.append(i == 0 ? "" : ",");
                    append(b, a.get(i));
                }
                b.append(']');
            } else if (v instanceof String s) {
                b.append(JSONObject.quote(s));
            } else if (v == null || JSONObject.NULL.equals(v)) {
                b.append("null");
            } else {
                b.append(v); // Number / Boolean
            }
        }
    }
}
