package cc.starsector.client;

import com.fs.starfarer.api.AnimationAPI;
import com.fs.starfarer.api.SoundAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.io.InputStream;
import java.util.Arrays;

/**
 * The presented-fire {@code onFire} relay. A wire-presented
 * replica shot is minted by {@code AuthoritativePresenter} through
 * {@code CombatEngineAPI.spawnProjectile}, which never runs the weapon's {@code OnFireEffectPlugin}
 * (only the weapon's own fire does); {@code onHit}
 * DOES run on a local hit (projectile, missile and ray alike).
 * So an effect whose visual keys off {@code onFire} never
 * presented on a replica: the "no EMP arc" class, and a mod weapon whose whole fire look is an
 * onFire smoke-and-debris blast showing flash, sound and projectile only.
 *
 * <p><b>Two entries, never a flag a caller can forget.</b> {@link #onPresentedShot} is the
 * presenter's SHOT site (a foe discharge presented on a treated replica): it runs, in the engine's
 * own order, the projectile spec's onFire plugin (ONE cached per weapon), the weapon effect
 * plugin's onFire (the engine's own instance), one barrel animation cycle, and the
 * previous-fire-sound stop; each plugin CLASS passes {@link PresentedFireRelayLaw#admit} first,
 * because the kit's damage doors do NOT cover an onFire body (a script damage call carries no
 * damaging param, a weaponless blast is only counted, a weaponed child leaks a frame, a
 * removeEntity deletes the presented shot, a wing mint is a ghost body). {@link #onPresented} is
 * every OTHER caller (the presence-adopt missile mint): today's wrapper-only relay, unchanged.
 *
 * <p><b>Only on a treated replica.</b> Every entry refuses a shooter that is not
 * {@code CcReplicaEffectGate.isTreatedReplica}. Own-adopt shots never reach it (the presenter
 * gates the call on {@code !ownAdopt}): the own ship's weapons fire natively and run the vanilla
 * effect, so no double arc; an own server_only discharge (presented, not fired) shows flash,
 * sound and projectile but no onFire FX on its pilot's screen, a residue named here.
 *
 * <p><b>Residue the relay cannot reach</b> (no public call): barrel recoil, spread buildup, the
 * missile-rack hide, the chargeup sound (no charge start rides the wire), the fade of a previous
 * fire sound (counted, {@code fx_sound_fade_residue}), and a mod plugin that reaches a sim door
 * through a helper class (the admission reads the plugin class's own constant pool only).
 *
 */
final class ReplicaPresentedFireRelay {

    /** Marker for a {@code CcWrap*} wrapper whose replica branch wants the presented-fire relay
     *  (the kit's admission opt-in: a kit wrapper without it is refused on the shot entry too). */
    interface PresentedFireAware {
    }

    /** Cap on the shot entry's deny + throw rows per combat (each row names a class, a weapon id, a
     *  throwable class: bounded tokens; the meters keep counting past it). */
    private static final int ROW_MAX = 16;
    /** A class file over this is refused as unreadable (no unbounded read on the combat thread). */
    private static final int MAX_CLASS_BYTES = 1 << 20;

    /** The shot entry's decisions, per-weapon state and meters. Combat thread only. */
    private static final PresentedFireRelayLaw LAW = new PresentedFireRelayLaw();
    /** The one reusable engine face (no per-shot allocation); its fields are cleared after every shot. */
    private static final Face FACE = new Face();

    /** Both entries, cumulative per process (the older relay's tokens, kept by name). */
    private static long relayed;
    private static long threw;
    private static int rows;
    private static boolean wrapperThrowLogged;

    private ReplicaPresentedFireRelay() {
    }

    /**
     * The presenter's SHOT entry: runs between the spawn and the catch-up advance, so an onFire that
     * reads or scales the shot's place or velocity sees it where the engine's own fire would (spawn,
     * then onFire, then flight). Never throws: a plugin throw is fenced per weapon by the law, and
     * anything outside the fence is counted into the throw token with one capped row. Timed once
     * at relay 1 and once at relay {@link PresentedFireRelayLaw#WARM_RELAY} (first sight of a mod
     * class reads its bytes, so the first row carries the admission cost), never per shot.
     *
     */
    static void onPresentedShot(CombatEntityAPI spawned, WeaponAPI weapon, ShipAPI shooter, CombatEngineAPI engine) {
        long ordinal = LAW.nextRelay();
        boolean timed = PresentedFireRelayLaw.timedRelay(ordinal);
        long t0 = timed ? System.nanoTime() : 0L;
        boolean counted = false;
        try {
            if (!(spawned instanceof DamagingProjectileAPI) || weapon == null
                    || !CcReplicaEffectGate.isTreatedReplica(shooter)) {
                return;
            }
            LAW.countRelay();
            counted = true;
            PresentedFireRelayLaw.State st = LAW.state(weapon);
            FACE.bind((DamagingProjectileAPI) spawned, weapon, engine);
            int site;
            try {
                site = LAW.shot(st, FACE);
            } finally {
                FACE.clear();
            }
            if (LAW.lastShotRan()) {
                relayed++;
            }
            if (site > PresentedFireRelayLaw.SITE_NONE) {
                threw++;
                row("THREW (relay off for this weapon this round) weapon=" + weapon.getId()
                        + " plugin=" + throwerName(site, st, weapon)
                        + " threw=" + LAW.lastThrown().getClass().getSimpleName());
            }
        } catch (Throwable outsideFence) {
            threw++;
            row("FAULT outside the per-weapon fence threw=" + outsideFence.getClass().getSimpleName());
        } finally {
            if (timed && counted) {
                long ns = System.nanoTime() - t0;
                PilotWatchLog.info("predict presented-fire relay ns " + (ordinal == 1L ? "first=" : "warm=") + ns);
            }
        }
    }

    /**
     * After the presenter played the shot's fire sound ({@code sound} = its handle, null when none
     * played): the law keeps it when the weapon spec stops the previous fire sound, so the next
     * present of the same weapon stops it first (the engine's own fire does the same).
     *
     */
    static void onFireSoundPlayed(WeaponAPI weapon, SoundAPI sound) {
        PresentedFireRelayLaw.State st = sound == null ? null : LAW.existing(weapon);
        if (st == null) {
            return;
        }
        try {
            WeaponSpecAPI spec = weapon.getSpec();
            if (spec != null) {
                LAW.keepSound(st, sound, spec.isStopPreviousFireSound(), spec.getFadePreviousFireSound());
            }
        } catch (Throwable t) {
            LAW.fault(st, t);
            threw++;
            row("THREW (relay off for this weapon this round) weapon=" + idOf(weapon)
                    + " plugin=SoundKeep threw=" + t.getClass().getSimpleName());
        }
    }

    private static String idOf(WeaponAPI weapon) {
        try {
            return weapon.getId();
        } catch (Throwable unread) {
            return "unread"; // the row still lands; the fault itself is already counted
        }
    }

    /** The combat boundary: every weapon state, class verdict, shot-entry meter and row cap starts
     *  over. */
    static void reset() {
        LAW.reset();
        FACE.clear();
        rows = 0;
    }

    /** The wrapper-only entry for every caller but the presenter's shot site (the presence-adopt
     *  missile mint): only an opted-in kit wrapper's onFire, as before the shot entry existed.
     * */
    static void onPresented(CombatEntityAPI spawned, WeaponAPI weapon, ShipAPI shooter, CombatEngineAPI engine) {
        try {
            if (!(spawned instanceof DamagingProjectileAPI) || weapon == null) {
                return;
            }
            EveryFrameWeaponEffectPlugin fx = weapon.getEffectPlugin();
            if (!(fx instanceof PresentedFireAware) || !(fx instanceof OnFireEffectPlugin)) {
                return;
            }
            if (!CcReplicaEffectGate.isTreatedReplica(shooter)) {
                return;
            }
            ((OnFireEffectPlugin) fx).onFire((DamagingProjectileAPI) spawned, weapon, engine);
            relayed++;
        } catch (Throwable t) {
            threw++;
            if (!wrapperThrowLogged) {
                wrapperThrowLogged = true;
                PilotWatchLog.error("predict replica presented-fire relay threw (visual only; shot stands)", t);
            }
        }
    }

    /** Rides the eventReplay meter line: the two older relay tokens (both entries, per process) then
     *  the shot entry's meters (per combat). */
    static String meterTokens() {
        return " predict_presented_fire_relayed=" + relayed + " predict_presented_fire_relay_throws=" + threw
                + LAW.meterTokens();
    }

    /** One bounded row, capped per combat. */
    private static void row(String what) {
        if (rows < ROW_MAX) {
            rows++;
            PilotWatchLog.info("predict presented-fire relay " + what
                    + (rows == ROW_MAX ? " [row cap reached; meters continue]" : ""));
        }
    }

    private static String throwerName(int site, PresentedFireRelayLaw.State st, WeaponAPI weapon) {
        try {
            if (site == PresentedFireRelayLaw.SITE_SPEC) {
                Object p = st.specPlugin();
                return p == null ? "SpecRead" : p.getClass().getSimpleName();
            }
            if (site == PresentedFireRelayLaw.SITE_PLUGIN) {
                Object p = weapon.getEffectPlugin();
                return p == null ? "EffectPlugin" : p.getClass().getSimpleName();
            }
            return site == PresentedFireRelayLaw.SITE_ANIM ? "Animation" : "SoundStop";
        } catch (Throwable unread) {
            return "unread"; // the row still lands; the throw itself is already counted
        }
    }

    /**
     * The shot entry's engine face: the exact public calls, in the order the law runs them. A plain
     * reusable object (bound before a shot, cleared after) so a presented shot allocates nothing here.
     *
     */
    private static final class Face implements PresentedFireRelayLaw.Shot, PresentedFireRelayLaw.ClassBytes {
        private DamagingProjectileAPI projectile;
        private WeaponAPI weapon;
        private CombatEngineAPI engine;
        private Class<?> reading;

        void bind(DamagingProjectileAPI p, WeaponAPI w, CombatEngineAPI e) {
            projectile = p;
            weapon = w;
            engine = e;
        }

        void clear() {
            projectile = null;
            weapon = null;
            engine = null;
            reading = null;
        }

        @Override
        public void stopSound(Object handle) {
            SoundAPI s = (SoundAPI) handle;
            if (s.isPlaying()) {
                s.stop();
            }
        }

        /**
         * The projectile spec's onFire plugin, read the way the engine builds a weapon's own (a
         * projectile spec or a missile spec; the spec hands a FRESH instance per read). The kit's
         * app-load birth emitter sits by name on every projectile spec and wraps the original: the
         * relay asks for that original instead (the emitter would confess a replica shot as a local
         * fire birth and hide the original behind a kit class the admission refuses).
         */
        @Override
        public Object readSpecPlugin() {
            WeaponSpecAPI ws = weapon.getSpec();
            Object ps = ws == null ? null : ws.getProjectileSpec();
            String name;
            if (ps instanceof ProjectileSpecAPI) {
                name = ((ProjectileSpecAPI) ps).getOnFireClassName();
            } else if (ps instanceof MissileSpecAPI) {
                name = ((MissileSpecAPI) ps).getOnFireClassName();
            } else {
                return null;
            }
            if (name == null) {
                return null;
            }
            if (ClientOnFireAttachSweep.EMITTER.equals(name)) {
                return ClientFireBirthSink.newDelegateOrNull(ps);
            }
            return ps instanceof ProjectileSpecAPI
                    ? ((ProjectileSpecAPI) ps).getOnFireEffect()
                    : ((MissileSpecAPI) ps).getOnFireEffect();
        }

        @Override
        public boolean runSpec(Object specPlugin) {
            if (!(specPlugin instanceof OnFireEffectPlugin) || !admitted(specPlugin)) {
                return false;
            }
            ((OnFireEffectPlugin) specPlugin).onFire(projectile, weapon, engine);
            return true;
        }

        @Override
        public boolean runPlugin() {
            EveryFrameWeaponEffectPlugin fx = weapon.getEffectPlugin();
            if (!(fx instanceof OnFireEffectPlugin) || !admitted(fx)) {
                return false;
            }
            ((OnFireEffectPlugin) fx).onFire(projectile, weapon, engine);
            return true;
        }

        /**
         * One barrel animation cycle through the engine's own tracker: it advances while the frame
         * is off zero, so frame 1 runs the cycle once to its wrap. Only from rest (a cycle already
         * running is left to finish, never snapped back), never {@code play()} (that loops forever).
         */
        @Override
        public boolean startAnimation() {
            AnimationAPI anim = weapon.getAnimation();
            if (anim == null || anim.getNumFrames() <= 1 || anim.getFrame() != 0) {
                return false;
            }
            anim.setFrame(1);
            return true;
        }

        private boolean admitted(Object plugin) {
            Class<?> cls = plugin.getClass();
            reading = cls;
            int verdict;
            try {
                verdict = LAW.admit(cls.getName(), plugin instanceof PresentedFireAware, this);
            } finally {
                reading = null;
            }
            if (verdict == PresentedFireRelayLaw.DENY_NEW) {
                row("DENIED class=" + cls.getSimpleName() + " why=" + LAW.lastDenyWhy()
                        + " weapon=" + weapon.getId());
            }
            return verdict == PresentedFireRelayLaw.ADMIT;
        }

        /** The admitting class's own bytes through its own loader (null = unreadable = refused). */
        @Override
        public byte[] read() throws Exception {
            Class<?> cls = reading;
            if (cls == null) {
                return null;
            }
            String resource = cls.getName().replace('.', '/') + ".class";
            ClassLoader loader = cls.getClassLoader();
            InputStream in = loader == null
                    ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource);
            if (in == null) {
                return null;
            }
            try {
                byte[] buf = new byte[8192];
                int n = 0;
                while (true) {
                    if (n == buf.length) {
                        if (buf.length >= MAX_CLASS_BYTES) {
                            return null;
                        }
                        buf = Arrays.copyOf(buf, buf.length * 2);
                    }
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                return Arrays.copyOf(buf, n);
            } finally {
                in.close();
            }
        }
    }

    // ---- engine faces of FragmentCarrierPresentLaw for the CcWrap*Fragment wrappers ----

    /** Is this shot's SOURCE a treated replica right now? Birth-time input only — later reads go
     *  through {@link #latched}/{@link #onHitReplica} ({@link ReplicaEffectLatchLaw}). */
    static boolean replicaShot(DamagingProjectileAPI projectile) {
        ShipAPI source = projectile == null ? null : projectile.getSource();
        return CcReplicaEffectGate.isTreatedReplica(source);
    }

    /** Decide replica-ness ONCE at the shot's birth and write it onto the shot
     *  ({@link ReplicaEffectLatchLaw#BOLT_KEY}); returns the decision. */
    static boolean latchAtFire(DamagingProjectileAPI projectile) {
        boolean replica = replicaShot(projectile);
        if (replica && projectile != null) {
            projectile.setCustomData(ReplicaEffectLatchLaw.BOLT_KEY, Boolean.TRUE);
        }
        return replica;
    }

    /** Was this shot born a replica shot (the birth latch), regardless of what the ledger says now? */
    static boolean latched(DamagingProjectileAPI projectile) {
        if (projectile == null) {
            return false;
        }
        java.util.Map<String, Object> data = projectile.getCustomData();
        return data != null && ReplicaEffectLatchLaw.boltReplica(data.get(ReplicaEffectLatchLaw.BOLT_KEY));
    }

    /** The {@code onHit} decision on a fresh-per-projectile instance: latch first,
     *  source treatment as the fallback, never an instance field. */
    static boolean onHitReplica(DamagingProjectileAPI projectile) {
        // A WEAPONLESS body is never a local weapon fire — only a
        // spawnProjectile mint with a null weapon (our sourceless presence-adopt) makes one, and
        // vanilla's onHit scripts deref getWeapon() (the null deref was a
        // Fatal). Replica branch, unconditionally.
        if (projectile != null && projectile.getWeapon() == null) {
            return true;
        }
        return ReplicaEffectLatchLaw.onHitReplica(latched(projectile), replicaShot(projectile));
    }

    /**
     * The fragment wrappers' replica-branch admission: counts the frame; {@code true} iff the
     * source swarm can seed a fragment ({@link FragmentCarrierPresentLaw#presentable}). A
     * no-swarm / empty-swarm carrier is left as-is (counted doorShut — the vanilla body would
     * have {@code removeEntity}'d the wire-minted missile).
     */
    static boolean beginReplicaShot(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        CcReplicaEffectGate.replicaFrame(FragmentCarrierPresentLaw.TAG);
        ShipAPI source = projectile == null ? null : projectile.getSource();
        if (source == null || weapon == null || engine == null) {
            return false;
        }
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(source);
        boolean ok = FragmentCarrierPresentLaw.presentable(true, swarm != null,
                swarm == null ? 0 : swarm.getNumActiveMembers());
        if (!ok) {
            CcReplicaEffectGate.doorShut(FragmentCarrierPresentLaw.TAG);
        }
        return ok;
    }

    /** Fallback pick when vanilla's range-filtered pick found nothing: any active member. */
    static RoilingSwarmEffect.SwarmMember anyMember(RoilingSwarmEffect swarm) {
        if (swarm == null) {
            return null;
        }
        WeightedRandomPicker<RoilingSwarmEffect.SwarmMember> picker = swarm.getPicker(true, true);
        return picker == null || picker.isEmpty() ? null : picker.pick();
    }
}
