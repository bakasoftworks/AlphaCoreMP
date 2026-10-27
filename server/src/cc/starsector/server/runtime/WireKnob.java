package cc.starsector.server.runtime;

import java.util.Locale;

/**
 * The FIXED census of client-behavior knobs the server delivers over the wire inside the
 * {@code PSTB} posture block (lane-posture design P4: "a knob joins the wire block the first time
 * a lane needs it to vary" — this is that first time: the
 * A/B choice on the Acausal Disruptor lead). Shared by BOTH JVMs: the server codec
 * ({@code PostureFrameCodec}) encodes by {@link #id()}, the client mirror
 * ({@code ClientPostureBlockReader}) decodes by the SAME table, the client holder
 * ({@code ClientWireKnobs}) applies by it, and the {@code /knob} admin verb names knobs by
 * {@link #verbName()}. Bundled into the mod jar like {@code ClientPostureRegistry} (B-legal by
 * construction: no I/O, no reflection, no registry static init).
 *
 * <p>Wire shape (schema 2): one {@code (u8 id, u8 value)} slot per knob inside a fixed
 * {@link #KNOB_SLOTS}-slot buffer; ids are 1-based ({@code 0} = never valid, the padding byte),
 * values are indexes into {@link #choices()}. Every violation is fail-closed at decode (unknown
 * id, value past the table, duplicate or unsorted ids, non-zero padding past {@code knobCount}).
 * The server ALWAYS encodes the whole census (no deviation bitset — six rows today; an earlier
 * design critique's "only knobs that genuinely vary" holds by the census being this small), so a block
 * is the complete server-chosen set and the client holder needs no absent-knob semantics.
 *
 * <p>Adding a knob = ONE enum row here (ids strictly ascending, never reused) + its client
 * consumer + a {@code /knob} verb choice — or, for a DERIVED row ({@link #isDerived()}), the one
 * serve call that sets it ({@code WireKnobState.setDerived}): a derived row has no config key and
 * the verb only reads it. The frame width is pinned by {@link #KNOB_SLOTS}; a new
 * row does not re-version the schema, but it IS a kit re-roll: the client reader refuses unknown
 * ids ({@code ClientPostureBlockReader.knobPairsError}'s {@code WireKnob.byId(id)} null check
 * — the refusal {@code "unknown knob id"}),
 * and the armed door's fingerprint gate is what
 * protects old kits.
 *
 *
 *
 */
public enum WireKnob {
    /**
     * How the foe cloak lanes treat an overload the LOCAL engine began on a
     * twin AHEAD of the wire (the owner's own Acausal Disruptor executes locally ~3 ticks before
     * the server's overload flag lands). {@code lead} (A, the default): the fluxflags lane keeps
     * the locally-begun overload for a bounded grace (one-way latency + 2 ticks, capped) so the
     * twin stays coherently overloaded AND decloaked through the lead; {@code wait} (B): the lane
     * stops the local overload at once and forces the cloak back to the wire's state the same
     * frame, until the wire confirms. Both ride the one seam and flip
     * mid-flight through the admin verb so both can be felt.
     */
    FOE_CLOAK_LEAD(1, "cc.predict.foeCloakLead", "cloaklead", new String[] {"lead", "wait"}, 0),

    /**
     * Battle TERRAIN on the serve: the combat terrain the match
     * is fought in — the PvP-relevant STAT terrains only (corona / pulsar =
     * the peak-CR and CR-loss mults, abyss = the speed + missile mults; nebula is cosmetic in
     * vanilla combat and hyperspace mode is a build-time renderer, both = slice D). The mults are
     * a deterministic function of (this word, {@link #TERRAIN_LEVEL}, the ship's own
     * {@code corona_resistance}) so BOTH JVMs derive them from the knob (no per-tick
     * wire); {@code /terrain} sets word + level in one receipt, {@code /knob terrain} one at a time.
     */
    TERRAIN(2, "cc.match.terrain", "terrain", new String[] {"none", "corona", "pulsar", "abyss"}, 0),

    /**
     * The terrain's strength in percent — corona/pulsar intensity {@code I} or the
     * abyssal depth {@code d} ({@code 100} = the full vanilla effect at the star's edge / the deep
     * abyss; default, so {@code /terrain corona} alone is a whole corona and the admin dials DOWN).
     */
    TERRAIN_LEVEL(3, "cc.match.terrainLevel", "terrainlevel", new String[] {"0", "25", "50", "75", "100"}, 4),

    /**
     * The server-authoritative drifting asteroid field's body
     * count — {@code none} (the standing default; the keeper expires every body) or a held
     * population of 20 / 100 / 400 the {@code AsteroidFieldServeLane} seeds per round and
     * re-targets on a flip (fewer = expire the excess, more = spawn on the upwind line). Ring
     * asteroids are OUT of v1 (the API cannot mark a body as ring, so a client ring
     * body would deal bump damage vanilla rings never do). The client does NOT read this value
     * for presentation — bodies arrive as {@code ASTP} rows; the knob rides the block so the
     * seat's sidelog names the population in force ({@code /knob asteroids 100} mid-match).
     */
    ASTEROIDS(4, "cc.match.asteroids", "asteroids", new String[] {"none", "20", "100", "400"}, 0),

    /**
     * The AIMP remote-aim fan interval, in server sim
     * ticks. WHY: the client glides the other player's turret aim point between two fan samples,
     * and the glide must last exactly ONE fan interval. The client used to GUESS that interval
     * from jittery arrival times (a learned estimate that could lock too small, so every sample
     * snapped, or be re-seeded to twice the fan); the server knows it exactly, so it sends it
     * (when the wire truth lands, the guess is deleted).
     * Value law: the value IS the tick count, {@code 1..255}; {@code 0} = the fan is OFF or
     * unknown (and a fan past 255 ticks, which the u8 slot cannot carry), so the client applies
     * the latest sample raw. DERIVED: set by the serve from the resolved {@code aim.fanEveryTicks}
     * ({@link #aimFanWireValue}), never by config or {@code /knob} (the verb reads it only). The
     * key is a log / dump label, not a config key.
     */
    AIM_FAN_TICKS(5, "cc.server.aimFanWireTicks", "aimfan", tickWords(), 0, true),

    /**
     * The served SNAPSHOT interval, in server sim ticks: the
     * serve fans a world snapshot on the ticks where {@code simTick % net.downEveryTicks == 0}. WHY:
     * the client glides the other player's beam end point between two wire records
     * ({@code WireTermAutofireShim}) and the glide must last exactly ONE served interval.
     * The client must not infer that interval from what it RECEIVED: a lost snapshot looks exactly
     * like a slower stream, so a seen spacing stretches the glide across the loss. The server knows
     * the interval, so it sends it (when the wire truth lands, the guess is deleted).
     * Value law: the value IS the tick count, {@code 1..255}; {@code 0} = unknown (and a stride past
     * 255 ticks, which the u8 slot cannot carry), so the client applies every change raw. DERIVED: set
     * by the serve from the resolved {@code net.downEveryTicks} ({@link #tickWireValue}), never by
     * config or {@code /knob} (the verb reads it only). The key is a log / dump label, not a config key.
     */
    SNAP_STRIDE_TICKS(6, "cc.server.snapStrideWireTicks", "snapstride", tickWords(), 0, true);

    /** Fixed slot count of the schema-2 knob buffer — 2 bytes per slot (id, value). */
    public static final int KNOB_SLOTS = 8;

    private final int id;
    private final String key;
    private final String verbName;
    private final String[] choices;
    private final int defaultChoice;
    private final boolean derived;

    WireKnob(int id, String key, String verbName, String[] choices, int defaultChoice) {
        this(id, key, verbName, choices, defaultChoice, false);
    }

    WireKnob(int id, String key, String verbName, String[] choices, int defaultChoice, boolean derived) {
        this.id = id;
        this.key = key;
        this.verbName = verbName;
        this.choices = choices;
        this.defaultChoice = defaultChoice;
        this.derived = derived;
    }

    /** The numeric words {@code "0".."255"}: one per
     *  value a u8 slot carries, so a tick count is its own wire value. */
    private static String[] tickWords() {
        String[] words = new String[256];
        for (int i = 0; i < words.length; i++) {
            words[i] = Integer.toString(i);
        }
        return words;
    }

    /** True for a DERIVED row: no config key, the serve sets it, the {@code /knob} verb reads it
     *  only ({@link #AIM_FAN_TICKS}, {@link #SNAP_STRIDE_TICKS}). */
    public boolean isDerived() {
        return derived;
    }

    /** The wire value of a DERIVED tick-count row
     *  ({@link #AIM_FAN_TICKS}, {@link #SNAP_STRIDE_TICKS}) for a resolved count of {@code ticks}
     *  (any int): {@code 1..255} → itself, anything else ({@code 0} = OFF, a negative, a count past
     *  the u8 slot) → {@code 0} = unknown, the client applies raw. */
    public static int tickWireValue(int ticks) {
        return ticks >= 1 && ticks <= 255 ? ticks : 0;
    }

    /** The {@link #AIM_FAN_TICKS} wire value for a
     *  resolved fan of {@code ticks}: {@link #tickWireValue} (the name is pinned by row AF1). */
    public static int aimFanWireValue(int ticks) {
        return tickWireValue(ticks);
    }

    /** The compiled-default choice index (the boot value when no config row names one; the
     * client holder's pre-spool value). Formerly implicitly choice 0 for every knob. */
    public int defaultChoice() {
        return defaultChoice;
    }

    /** The 1-based wire id (the {@code u8} slot id; 0 is the padding sentinel, never a knob). */
    public int id() {
        return id;
    }

    /** The registry-style canonical key (sidelog dump rows, serve log rows). */
    public String key() {
        return key;
    }

    /** The folded ASCII word the {@code /knob} admin verb names this knob by. */
    public String verbName() {
        return verbName;
    }

    /** The FIXED ordered choice words; the wire value is an index into this table. */
    public String[] choices() {
        return choices.clone();
    }

    /** Number of legal wire values ({@code 0..count-1}). */
    public int choiceCount() {
        return choices.length;
    }

    /** A numeric choice word ({@link #TERRAIN_LEVEL}'s {@code 0..100}) as an int; {@code 0} for a
     *  value past the table or a non-numeric word (fail-closed: no terrain effect). */
    public int choicePercent(int value) {
        if (value < 0 || value >= choices.length) {
            return 0;
        }
        try {
            return Integer.parseInt(choices[value]);
        } catch (NumberFormatException notNumeric) {
            return 0;
        }
    }

    /** The choice word for a wire value, or {@code "?"} past the table (log rows only). */
    public String choiceName(int value) {
        return value >= 0 && value < choices.length ? choices[value] : "?";
    }

    /** The wire value for a folded choice word, or {@code -1} when it is not one (fail-closed). */
    public int choiceIndex(String word) {
        if (word == null) {
            return -1;
        }
        String folded = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(folded)) {
                return i;
            }
        }
        return -1;
    }

    /** The knob with this wire id, or {@code null} (an unknown id is a fail-closed decode). */
    public static WireKnob byId(int id) {
        for (WireKnob k : values()) {
            if (k.id == id) {
                return k;
            }
        }
        return null;
    }

    /** The knob the {@code /knob} verb names by this folded word, or {@code null}. */
    public static WireKnob byVerbName(String word) {
        if (word == null) {
            return null;
        }
        String folded = word.toLowerCase(Locale.ROOT);
        for (WireKnob k : values()) {
            if (k.verbName.equals(folded)) {
                return k;
            }
        }
        return null;
    }
}
