package cc.starsector.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The game-free decisions of the presented-fire onFire relay; {@link ReplicaPresentedFireRelay} is the thin engine
 * face. A wire-presented replica shot is minted by the engine's spawn factory, which never runs the weapon's onFire,
 * so a weapon whose look lives in onFire (a recoil-blast of smoke and debris, a trail plugin, a fire-time arc) showed
 * only its flash, sound and projectile on the other window. The relay runs the engine's OWN plugin instances on the
 * presented shot, at present time, in the engine's order: zero added latency, no forced fire, no cost charged, no
 * painted stand-in. This class owns every decision about that, with no engine types (weapons, plugins and sound
 * handles ride as plain objects), so the selftest drives it with stubs.
 *
 * <p><b>The admission.</b> A replica's truth is the server's, and an onFire body run here is outside the kit's damage
 * doors: a script's direct damage call carries no damaging param (the arc door classifies nothing), a weaponless
 * explosion is only counted, a weaponed child projectile leaks for a frame and trips the autonomous-discharge bar, a
 * removeEntity deletes the presented shot, and a drone or wing mint is a ghost body the own autofire would shoot at.
 * So a plugin CLASS runs only when it is known to stay visual, decided at FIRST SIGHT and cached per class
 * ({@link #admit}): the kit's own wrappers when they opt in (the marker the wrapper author sets); a vanilla class
 * only from the audited {@link #VANILLA_ALLOW} table (every other vanilla class is refused: the DEM launcher, the
 * vortex launcher's wing, the canister flak split, the swarm launcher's wing, the reality disruptor and the VPD
 * driver mint or delete bodies); a mod class only when its own constant pool names none of {@link #SIM_DOOR_NAMES}
 * ({@link #poolSimDoorName}, a plain Utf8 walk), unreadable bytes refused. Residue, named once: a mod plugin that
 * reaches a sim door through a helper class or an inherited super body passes the walk (the pool is its own).
 *
 * <p><b>Per weapon</b> ({@link State}, weak-keyed): the ONE projectile-spec plugin (the spec hands a fresh instance
 * per read, the engine keeps one per weapon from its construction, so the relay caches one at first sight), the off
 * flag (THE FENCE: any relay step that throws turns that weapon's relay off for the round, so it falls back to the
 * flash, sound and projectile it showed before), and the kept fire-sound handle (only when the weapon spec stops the
 * previous fire sound; the stop runs before the next present of the same weapon, as the engine's own fire does).
 *
 * <p><b>The budget</b> ({@link #pay}): a host announces the shots, so nothing else bounds how many relayed onFire
 * bodies a frame or a second runs; every relayed onFire CALL pays a token bucket at the price of its class
 * ({@link #VANILLA_PRICE}). See {@link #CALLS_PER_SECOND} for the unit, the load it serves and what it leaves unbounded.
 *
 *
 * <p>Threads: the combat thread only (the presenter's present core). No lock.
 *
 */
public final class PresentedFireRelayLaw {

    /** {@link #admit} verdicts: admitted; refused (cached); refused at its first sight (the caller logs one row). */
    public static final int ADMIT = 1;
    public static final int DENY = 0;
    public static final int DENY_NEW = -1;

    /** {@link #shot} outcomes: the step that threw, {@link #SITE_NONE} for a clean shot, {@link #SITE_OFF} when the
     *  weapon's relay is already off (nothing ran). */
    public static final int SITE_OFF = -1;
    public static final int SITE_NONE = 0;
    public static final int SITE_SOUND = 1;
    public static final int SITE_SPEC = 2;
    public static final int SITE_PLUGIN = 3;
    public static final int SITE_ANIM = 4;

    /** The relay ordinal timed against the first (the warm-cost row): the first-vs-warm idiom of the input poll. */
    public static final long WARM_RELAY = 100L;

    /** The vanilla bucket (every vanilla plugin lives under it) and the kit's own package. */
    static final String VANILLA_PREFIX = "com.fs.";
    static final String KIT_PREFIX = "cc.starsector.";

    /**
     * THE RELAY'S BUDGET, in tokens: one token = one relayed onFire CALL
     * actually made, after the admission, on either relay entry (a shot whose spec plugin and effect plugin both run
     * pays twice; a weapon with neither pays nothing); the previous-sound stop and the one-cycle barrel animation
     * allocate nothing and are not priced. WHY: a host announces the shots this window presents, so a hostile host's
     * shot flood ran onFire bodies without limit (one cryoblaster call builds 30 trail sprites). THE BUCKET
     * ({@link #pay}) holds at most {@link #CALLS_BURST} tokens and refills at this rate per ENGINE second, with the
     * arc belt's clock rules: a lower read = a new engine = a full bucket, a clock that is not a number refills
     * nothing, and the combat boundary ({@link #reset}) refills.
     *
     * <p>THE LAWFUL LOAD: the cryoflux transducer and the pseudoparticle jet fire 13 to 20 shots a second a mount, and
     * Lidar Array and Temporal Shell multiply a rate by 3. The served size is a 400 DP fight: its lawful peak is about
     * 770 calls a second, about 2,300 with every Temporal Shell synced, a margin of 1.7x under this refill; past it,
     * overlapping shells lose onFire effects. A predicted window never pauses, so a lawful burst is a HITCH, and a
     * burst of one second of refill covers it.
     *
     * <p>THE HOSTILE CEILING: the engine clock can run 2x wall time, so a flood is held to twice this rate a wall
     * second, each call at its class's price. NOT bounded by this bucket: the engine's own onHit on a presented shot,
     * the projectile mint itself, and the frame time of the live set.
     */
    public static final int CALLS_PER_SECOND = 4_000;
    /** The bucket's size in tokens: the most one frame may spend (one second of refill). */
    public static final int CALLS_BURST = 4_000;
    /** The price, in tokens a call, of every class the column below names no other price for. */
    public static final int PRICE_CALL = 1;
    /** The price of a class that is ALWAYS called: it takes nothing and is never refused. */
    public static final int PRICE_FREE = 0;
    /** The one allowed class that draws an arc inside its own onFire: it pays the arc belt too ({@link ArcCharge}). */
    public static final String SHOCK_REPEATER = "com.fs.starfarer.api.impl.combat.ShockRepeaterOnFireEffect";

    /**
     * THE PRICE COLUMN: the audited vanilla onFire classes allowed on a replica shot (beside the every-frame swap's
     * audited-dormant list), each with its price in budget tokens a CALL ({@link #price}).
     * <ul>
     * <li>{@link #PRICE_FREE}, always called (a price over the tokens in hand is refused, and the tokens never go
     * under zero): the squall and voidblaster bodies (a damage-dealt listener the engine consults on a hit this window
     * predicts anyway) and the neutron torpedo (it slows its own projectile, velocity x0.33). A refusal would change
     * STATE (a refused neutron torpedo flies 3x too fast on this window; the listener installs late) and none of the
     * three allocates per call.</li>
     * <li>8, the cryoblaster: 30 trail sprites a call that live about 1 s and draw every frame, on screen or off. At 8
     * the hostile ceiling is 500 calls an engine second (about 15,000 live sprites) instead of 4,000 (about 120,000);
     * lawful = 1 shot a second a mount.</li>
     * <li>4, the heavy adjudicator: 60 to 90 particles a call; lawful = 3 a second for 20 shots a mount.</li>
     * <li>1 plus the arc belt, the shock repeater: its fire-time arc is an EMP arc dealt by the replica (the arc door
     * zeroes it), drawn inside vanilla's onFire and so outside the belt; the caller charges the belt for one arc of
     * the weapon's range first ({@link ArcCharge}).</li>
     * <li>1, the rift torpedo and the cryoflux transducer. Neither is visual only: the first also sets the missile's
     * EMP resistance and an ECCM override, the second links each shot to the previous one.</li>
     * </ul>
     * Every other class, the kit's own and a mod's, pays {@link #PRICE_CALL}: a mod call's cost is unknowable, so only
     * the COUNT is bounded. Every other vanilla class is refused (see the class doc). Every price is at most
     * {@link #CALLS_BURST} (a price over the burst could never be admitted).
     *
     */
    public static final Map<String, Integer> VANILLA_PRICE;

    static {
        Map<String, Integer> p = new LinkedHashMap<String, Integer>();
        p.put("com.fs.starfarer.api.impl.combat.CryoblasterEffect", Integer.valueOf(8));
        p.put("com.fs.starfarer.api.impl.combat.HeavyAdjudicatorEffect", Integer.valueOf(4));
        p.put("com.fs.starfarer.api.impl.combat.RiftTorpedoEffect", Integer.valueOf(PRICE_CALL));
        p.put("com.fs.starfarer.api.impl.combat.CryofluxTransducerEffect", Integer.valueOf(PRICE_CALL));
        p.put("com.fs.starfarer.api.impl.combat.SquallOnFireEffect", Integer.valueOf(PRICE_FREE));
        p.put("com.fs.starfarer.api.impl.combat.threat.VoidblasterEffect", Integer.valueOf(PRICE_FREE));
        p.put("com.fs.starfarer.api.impl.combat.threat.NeutronTorpedoOnFireEffect", Integer.valueOf(PRICE_FREE));
        p.put(SHOCK_REPEATER, Integer.valueOf(PRICE_CALL));
        VANILLA_PRICE = Collections.unmodifiableMap(p);
    }

    /** The audited vanilla onFire classes allowed on a replica shot: the price column's names. */
    public static final Set<String> VANILLA_ALLOW = VANILLA_PRICE.keySet();

    /** The engine calls a mod plugin may not name: each mints, deletes, damages or re-drives a body the server owns. */
    static final String[] SIM_DOOR_NAMES = {
        "removeEntity", "spawnProjectile", "spawnShipOrWing", "createFXDrone", "addEntity", "addPlugin",
        "addLayeredRenderingPlugin", "applyDamage", "spawnDamagingExplosion", "setMissileAI", "setShipAI"};

    /** {@link #poolSimDoorName}'s answer for bytes that are no readable class file (refused). */
    public static final String UNREADABLE = "unreadable";

    private static final byte[][] SIM_DOOR_BYTES = asciiOf(SIM_DOOR_NAMES);

    /** Reads a plugin class's own bytes, only at its first sight; a null or a throw = unreadable. */
    public interface ClassBytes {
        byte[] read() throws Exception;
    }

    /** One presented shot's engine-facing steps, run by {@link #shot} in the engine's order. */
    public interface Shot {
        /** Stop the previous fire sound of this weapon (kept only under the spec's stop flag). */
        void stopSound(Object handle);

        /** Read the projectile spec's onFire plugin: called once per weapon, the answer cached (null = none). */
        Object readSpecPlugin();

        /** Admit, pay ({@link PresentedFireRelayLaw#pay}) and run the cached spec plugin's onFire; true when it ran. */
        boolean runSpec(Object specPlugin);

        /** Admit, pay ({@link PresentedFireRelayLaw#pay}) and run the weapon's own effect plugin's onFire; true when it ran. */
        boolean runPlugin();

        /** Start one barrel animation cycle; true when one was started. */
        boolean startAnimation();
    }

    /**
     * The arc belt's charge for the one arc an allowed class draws inside its own onFire ({@link #SHOCK_REPEATER}'s):
     * the engine face pays the belt for one arc of the weapon's range; true = paid, false = the belt refused (the
     * belt's own census counts it).
     */
    public interface ArcCharge {
        boolean chargeArc();
    }

    /** The relay's state for one replica weapon. */
    public static final class State {
        boolean specRead;
        Object specPlugin;
        boolean off;
        Object sound;

        public boolean off() {
            return off;
        }

        public Object specPlugin() {
            return specPlugin;
        }

        public Object sound() {
            return sound;
        }
    }

    /** Weak keys: a swept weapon drops out; {@link #reset} clears what a plugin holding its own weapon would pin. */
    private final Map<Object, State> states = new WeakHashMap<Object, State>();
    /** Class name to verdict ({@link #ADMIT} / {@link #DENY}); names only, so nothing is pinned. */
    private final Map<String, Integer> verdicts = new HashMap<String, Integer>();

    private long relays;
    private long specCalls;
    private long pluginCalls;
    private long anims;
    private long threw;
    private long offWeapons;
    private long denied;
    private long fadeResidue;
    private String lastDenyWhy;
    private Throwable lastThrown;
    private boolean lastShotRan;
    /** The budget's bucket (0 to {@link #CALLS_BURST}), the engine clock it last refilled at (+inf = unread), its
     *  refusal census and the one-row latch. */
    private float tokens = CALLS_BURST;
    private float budgetClock = Float.POSITIVE_INFINITY;
    private long refusedCalls;
    private boolean refusalRowTaken;

    /** The weapon's state, created at its first relayed shot. */
    public State state(Object weapon) {
        State st = states.get(weapon);
        if (st == null) {
            st = new State();
            states.put(weapon, st);
        }
        return st;
    }

    /** The weapon's state if a shot of it was relayed this round, else null. */
    public State existing(Object weapon) {
        return weapon == null ? null : states.get(weapon);
    }

    /**
     * Run one presented shot's steps in the engine's order: the previous fire sound stopped, the projectile spec's
     * onFire, the weapon effect plugin's onFire, the barrel animation. An OFF weapon runs nothing. Any step that
     * throws turns the weapon OFF (counted once as a throw and once as a weapon turned off) and the later steps do not
     * run for that shot; the thrown object is kept for the caller's one bounded row ({@link #lastThrown}).
     */
    public int shot(State st, Shot s) {
        lastThrown = null;
        lastShotRan = false;
        if (st.off) {
            return SITE_OFF;
        }
        int site = SITE_SOUND;
        try {
            Object previous = st.sound;
            st.sound = null;
            if (previous != null) {
                s.stopSound(previous);
            }
            site = SITE_SPEC;
            if (!st.specRead) {
                st.specRead = true;
                st.specPlugin = s.readSpecPlugin();
            }
            if (st.specPlugin != null && s.runSpec(st.specPlugin)) {
                specCalls++;
                lastShotRan = true;
            }
            site = SITE_PLUGIN;
            if (s.runPlugin()) {
                pluginCalls++;
                lastShotRan = true;
            }
            site = SITE_ANIM;
            if (s.startAnimation()) {
                anims++;
            }
            return SITE_NONE;
        } catch (Throwable t) {
            fault(st, t);
            return site;
        }
    }

    /**
     * After the presented fire sound played: keep its handle when the spec stops the previous fire sound (the next
     * shot's {@link Shot#stopSound} receives it); a spec that FADES the previous sound instead is counted as residue
     * (no per-frame volume ramp here; the count is an upper bound of the fades the engine would run). An OFF weapon
     * keeps nothing.
     */
    public void keepSound(State st, Object handle, boolean stopPrevious, float fadePreviousSeconds) {
        if (st == null || st.off || handle == null) {
            return;
        }
        if (stopPrevious) {
            st.sound = handle;
        } else if (fadePreviousSeconds > 0f) {
            fadeResidue++;
        }
    }

    /** A relay step outside {@link #shot} threw (the sound keep): the same fence. */
    public void fault(State st, Throwable t) {
        if (st != null && !st.off) {
            st.off = true;
            offWeapons++;
        }
        threw++;
        lastThrown = t;
    }

    /**
     * The admission, decided at a class's first sight and cached per class name: a kit class when it opts in, a
     * vanilla class when {@link #VANILLA_ALLOW} names it, any other class when its own pool names no sim door.
     * Returns {@link #ADMIT}, {@link #DENY} (refused before), or {@link #DENY_NEW} (refused now, counted once;
     * {@link #lastDenyWhy} names why).
     */
    public int admit(String className, boolean kitOptIn, ClassBytes bytes) {
        if (className == null) {
            return DENY;
        }
        Integer cached = verdicts.get(className);
        if (cached != null) {
            return cached.intValue();
        }
        String why = denyReason(className, kitOptIn, bytes);
        if (why == null) {
            verdicts.put(className, Integer.valueOf(ADMIT));
            return ADMIT;
        }
        verdicts.put(className, Integer.valueOf(DENY));
        denied++;
        lastDenyWhy = why;
        return DENY_NEW;
    }

    /** Why a class is refused, or null when it is admitted. Pure. */
    static String denyReason(String className, boolean kitOptIn, ClassBytes bytes) {
        if (className.startsWith(KIT_PREFIX)) {
            return kitOptIn ? null : "kit-not-opted-in";
        }
        if (className.startsWith(VANILLA_PREFIX)) {
            return VANILLA_ALLOW.contains(className) ? null : "vanilla-not-allowed";
        }
        byte[] b;
        try {
            b = bytes == null ? null : bytes.read();
        } catch (Throwable unreadable) {
            return UNREADABLE; // counted by the caller's deny meter, its one row names this reason
        }
        String hit = poolSimDoorName(b);
        if (hit == null) {
            return null;
        }
        return UNREADABLE.equals(hit) ? UNREADABLE : "pool-" + hit;
    }

    /**
     * The first of {@link #SIM_DOOR_NAMES} a class file's constant pool holds as a Utf8 entry, null when it holds
     * none, {@link #UNREADABLE} when the bytes are no class file or the pool is truncated or carries an unknown tag.
     * A plain walk of the pool (the class file layout's fixed entry sizes), no class loaded, no parser library.
     */
    public static String poolSimDoorName(byte[] b) {
        if (b == null || b.length < 10 || (b[0] & 0xff) != 0xCA || (b[1] & 0xff) != 0xFE
                || (b[2] & 0xff) != 0xBA || (b[3] & 0xff) != 0xBE) {
            return UNREADABLE;
        }
        int count = u2(b, 8);
        int p = 10;
        for (int i = 1; i < count; i++) {
            if (p >= b.length) {
                return UNREADABLE;
            }
            int tag = b[p] & 0xff;
            switch (tag) {
                case 1: {
                    if (p + 3 > b.length) {
                        return UNREADABLE;
                    }
                    int len = u2(b, p + 1);
                    int start = p + 3;
                    if (start + len > b.length) {
                        return UNREADABLE;
                    }
                    String hit = matchName(b, start, len);
                    if (hit != null) {
                        return hit;
                    }
                    p = start + len;
                    break;
                }
                case 3: case 4:
                    p += 5;
                    break;
                case 5: case 6:
                    p += 9;
                    i++; // a long or double takes two pool slots
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    p += 3;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    p += 5;
                    break;
                case 15:
                    p += 4;
                    break;
                default:
                    return UNREADABLE;
            }
        }
        return p <= b.length ? null : UNREADABLE;
    }

    /**
     * THE BUDGET's gate for one onFire call of an ADMITTED class, asked by
     * either relay entry after the admission and before the call. {@code now} = the engine clock in seconds
     * ({@code getTotalElapsedTime(false)}: 0 and up; a value that is not a number refills nothing). True = call it
     * (its price is taken); false = REFUSED: the onFire is not called, the shot stands (its projectile, flash and
     * fire sound are the presenter's; the sound stop and the animation still run) and the weapon's relay stays ON
     * (the fence is for throws). A price over the tokens in hand is counted ({@link #refusedCalls}; the first a
     * combat asks for one row, {@link #takeRefusalRow}). {@link #SHOCK_REPEATER} charges {@code arc} only once its
     * token is in hand (an empty bucket spends no belt points); a charge the belt refuses, or a null {@code arc},
     * refuses the call with the token KEPT, counted by the belt's census, not this one.
     */
    public boolean pay(String className, float now, ArcCharge arc) {
        if (now < budgetClock) { // the first read, or a new engine
            tokens = CALLS_BURST;
            budgetClock = now;
        } else if (now > budgetClock) {
            tokens = Math.min(CALLS_BURST, tokens + (now - budgetClock) * CALLS_PER_SECOND);
            budgetClock = now;
        } // a clock that is not a number refills nothing
        int price = price(className);
        if (price > tokens) {
            refusedCalls++;
            return false;
        }
        if (SHOCK_REPEATER.equals(className) && (arc == null || !arc.chargeArc())) {
            return false;
        }
        tokens -= price;
        return true;
    }

    /** A class's price in budget tokens a call: the price column's, else {@link #PRICE_CALL}. Pure. */
    public static int price(String className) {
        Integer p = className == null ? null : VANILLA_PRICE.get(className);
        return p == null ? PRICE_CALL : p.intValue();
    }

    /** True exactly once a combat, after its first refused call: the caller writes its ONE error row then. */
    public boolean takeRefusalRow() {
        if (refusedCalls == 0L || refusalRowTaken) {
            return false;
        }
        refusalRowTaken = true;
        return true;
    }

    /** The tokens in hand as of the last {@link #pay} (no refill is applied by this read). */
    public float tokens() {
        return tokens;
    }

    /** Calls the budget refused this combat (a price over the tokens in hand; the belt's refusals are its own). */
    public long refusedCalls() {
        return refusedCalls;
    }

    /** The ordinal the next relay would get (the caller times relay 1 and relay {@link #WARM_RELAY}). */
    public long nextRelay() {
        return relays + 1L;
    }

    /** A shot passed the relay's guards. */
    public void countRelay() {
        relays++;
    }

    public static boolean timedRelay(long ordinal) {
        return ordinal == 1L || ordinal == WARM_RELAY;
    }

    /** Did the last {@link #shot} run an onFire (spec or plugin)? */
    public boolean lastShotRan() {
        return lastShotRan;
    }

    public Throwable lastThrown() {
        return lastThrown;
    }

    public String lastDenyWhy() {
        return lastDenyWhy;
    }

    /** The combat boundary: every weapon state, every class verdict and every counter starts over; the budget's
     *  bucket is full again and its one refusal row re-armed. */
    public void reset() {
        tokens = CALLS_BURST;
        budgetClock = Float.POSITIVE_INFINITY;
        refusedCalls = 0L;
        refusalRowTaken = false;
        states.clear();
        verdicts.clear();
        relays = 0L;
        specCalls = 0L;
        pluginCalls = 0L;
        anims = 0L;
        threw = 0L;
        offWeapons = 0L;
        denied = 0L;
        fadeResidue = 0L;
        lastDenyWhy = null;
        lastThrown = null;
        lastShotRan = false;
    }

    /** The relay meters, composed onto the eventReplay meter line. */
    public String meterTokens() {
        return " fx_onfire_spec=" + specCalls
                + " fx_onfire_plugin=" + pluginCalls
                + " fx_anim=" + anims
                + " onfire_threw=" + threw
                + " onfire_off=" + offWeapons
                + " onfire_denied=" + denied
                + " fx_sound_fade_residue=" + fadeResidue
                + " onfire_refused=" + refusedCalls;
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static String matchName(byte[] b, int start, int len) {
        for (int k = 0; k < SIM_DOOR_BYTES.length; k++) {
            byte[] n = SIM_DOOR_BYTES[k];
            if (n.length != len) {
                continue;
            }
            boolean equal = true;
            for (int j = 0; j < len; j++) {
                if (b[start + j] != n[j]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return SIM_DOOR_NAMES[k];
            }
        }
        return null;
    }

    private static byte[][] asciiOf(String[] names) {
        byte[][] out = new byte[names.length][];
        for (int k = 0; k < names.length; k++) {
            String n = names[k];
            byte[] b = new byte[n.length()];
            for (int j = 0; j < b.length; j++) {
                b[j] = (byte) n.charAt(j);
            }
            out[k] = b;
        }
        return out;
    }
}
