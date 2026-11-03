package cc.starsector.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
     * The audited vanilla onFire classes allowed on a replica shot, beside the every-frame swap's audited-dormant list.
     * Visual only: the cryoblaster, heavy adjudicator, rift torpedo and cryoflux transducer bodies (particles, flashes,
     * a trail plugin). Faithful listeners: the squall and voidblaster bodies (a damage-dealt listener the engine
     * consults on a hit this window predicts anyway). The neutron torpedo slows its own projectile (velocity x0.33).
     * The shock repeater's fire-time arc is an EMP arc dealt by the replica, which the arc door zeroes. Everything
     * else vanilla is refused (see the class doc).
     */
    public static final Set<String> VANILLA_ALLOW = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "com.fs.starfarer.api.impl.combat.CryoblasterEffect",
            "com.fs.starfarer.api.impl.combat.HeavyAdjudicatorEffect",
            "com.fs.starfarer.api.impl.combat.RiftTorpedoEffect",
            "com.fs.starfarer.api.impl.combat.CryofluxTransducerEffect",
            "com.fs.starfarer.api.impl.combat.SquallOnFireEffect",
            "com.fs.starfarer.api.impl.combat.threat.VoidblasterEffect",
            "com.fs.starfarer.api.impl.combat.threat.NeutronTorpedoOnFireEffect",
            "com.fs.starfarer.api.impl.combat.ShockRepeaterOnFireEffect")));

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

        /** Admit and run the cached spec plugin's onFire; true when it ran. */
        boolean runSpec(Object specPlugin);

        /** Admit and run the weapon's own effect plugin's onFire; true when it ran. */
        boolean runPlugin();

        /** Start one barrel animation cycle; true when one was started. */
        boolean startAnimation();
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

    /** The combat boundary: every weapon state, every class verdict and every counter starts over. */
    public void reset() {
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
                + " fx_sound_fade_residue=" + fadeResidue;
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
