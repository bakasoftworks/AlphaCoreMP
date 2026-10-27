package cc.starsector.client;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import cc.starsector.client.net.ServerWeaponMountState;

/**
 * The charge-glow present-from-wire lane (knob {@code cc.predict.weaponGlow} =
 * {@code off|meter|apply}, default {@code apply}; a presentation-class sync gap; defect:
 * Gauss charge-up glow absent on the observer).
 *
 * <p><b>Channel identity (a re-land superseding the setGlowAmount landing that
 * an eye + screenshot pair overturned):</b> the engine has TWO glow channels and the
 * first landing drove the wrong one. {@code WeaponAPI.setGlowAmount} feeds the "special weapon
 * glow" block of the engine's weapon render, whose helper
 * renders a freshly-created {@code graphics/fx/hit_glow_small.png}
 * glow-ball AT EACH BARREL FIRE-OFFSET — the muzzle-tip balls seen in play
 * (natively that channel is the ENERGY high-flux muzzle glow).
 * The faithful charge heat-up is the SEPARATE animType-gated block: the spec's
 * slot-appropriate whole-sprite glow overlay ({@code turretGlowSprite}/{@code hardpointGlowSprite},
 * loaded with color = spec {@code glowColor}, blend (770,1) additive,
 * slot-aware center — hardpoint (w/2, h/4), turret (w/2, h/2)), rendered at the SLOT CENTER at
 * (currAngle−90) with alpha = pulseFader × chargeScalar × shipAlpha, drawn twice per frame with
 * uniform ±1px jitter when charge ≥ 0.7 ({@code JitterRenderer.renderWithJitter}, copies=2,
 * offset ∈ [−mag/2, +mag/2] per axis, mag 2.0 else 0.0). The pulse fader
 * (built over {@code chargeTime/2}) is brightness = 0.75 + 0.25·sin((rand + t)·π·(chargeTime/2)),
 * ticked ONLY on effective fire-intent frames (in the weapon's advance) and HOLDING its last value
 * through the post-fire fade. The charge scalar is the SAME scalar the wire already carries as
 * {@code chargeLevel} in both phases (ramp + cooldown fade).
 *
 *
 * <p><b>This lane therefore renders the glow sprite itself</b> from a layered rendering plugin,
 * reproducing the native block off wire state: charge = wire {@code chargeLevel} (held between
 * FRESH frames), fader tick = wire {@code FLAG_FIRING} (the server-side effective fire-intent —
 * suppressors like venting/ammo-out already ran authoritatively). {@code getGlowSpriteAPI()}
 * wraps the SAME native sprite instance the native block draws (delegating wrapper), so color,
 * additive blend, and slot-aware center from load apply unchanged; replicas never run a native
 * charge cycle, so nothing fights our per-frame angle/alpha writes.
 *
 * <p>Gates, each load-bearing and unchanged: never the OWN ship (own weapons fire
 * locally — the native charge cycle already glows; a wire feed would lag-fight it); never a BEAM
 * (the beam-relay force-fire makes the replica's native cycle real — feeding on top would
 * double-present); only mounts with a glow sprite ({@code getGlowSpriteAPI() != null} ⟺ the
 * native animType GLOW/GLOW_AND_FLASH gate — the sprite only loads under that flag). The wire
 * float is fail-closed clamped to [0,1] (NaN/negative ⇒ 0; counted, never silent — security
 * standing rule: wire-derived input); entries are bounded by the decode-capped carried-mount walk
 * and pruned when unmaintained (despawn) so the map cannot grow unboundedly.
 *
 * <p>Accepted ≈ residuals (identity [I] until owner-eyed, feel-watch adjudicates):
 * (i) draw layer = {@code ABOVE_SHIPS_LAYER} vs native's in-ship-pass draw — a higher-layer hull
 * (e.g. a fighter) crossing the mount gets the additive glow drawn over it, rare and subtle;
 * (ii) death fade = linear ~0.5 s decay once the hull stops being walked/alive vs native's
 * cooldown-curve fade (this REPLACES the accepted edge of freezing the last glow on the hulk);
 * (iii) fader phase is locally random per mount (native seeds {@code Math.random()} per weapon —
 * phase is not synchronized on any client, so this IS native behavior, listed for completeness).
 *
 * <p>Fed per carried mount from the weapon-state walk ({@code cc.predict.weaponState=off} parks
 * it too — the mount-tier decode is that lane's plumbing). Own-file per the
 * PredictReconcileCombatPlugin growth-anchor law (the whole-class watch-item
 * + the new-lane law): PRCP carries only the delegate touchpoints,
 * and this re-land changed ZERO PRCP bytes (the lane's external surface is unchanged).
 */
final class WeaponGlowLane {

    /** Resolved {@code cc.predict.weaponGlow} mode — the PRCP banner line reads this. */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.weaponGlow", System.getProperty("cc.predict.weaponGlow", "apply"));
    private static final boolean METER = !"off".equals(MODE);
    private static final boolean APPLY = "apply".equals(MODE);

    /** Native fader shape: brightness = PULSE_BASE + PULSE_AMP·sin(phase·π·chargeTime/2). */
    private static final float PULSE_BASE = 0.75F;
    private static final float PULSE_AMP = 0.25F;
    /** Native jitter law: ≥0.7 charge ⇒ ±1px, always 2 additive copies. */
    private static final float JITTER_CHARGE_THRESHOLD = 0.7F;
    private static final float JITTER_MAG = 2.0F;
    private static final int JITTER_COPIES = 2;
    /** Accepted-≈ residual (ii): linear decay per second once the hull is dead/unwalked. */
    private static final float DEATH_FADE_PER_SEC = 2.0F;
    /** Prune an entry unmaintained this long (despawned hull; FRESH walks refresh continuously). */
    private static final float STALE_PRUNE_SEC = 5.0F;
    /** Native cull idiom: collision radius + 500 slack. */
    private static final float CULL_SLACK = 500.0F;

    /** The weapon-state walk's event log (shared: this lane rides that walk). */
    private final PredictReconcileCombatPlugin.PredictLane weaponLane;

    /** Live glow state per replica mount; maintained on FRESH frames, rendered every frame. */
    private final Map<WeaponAPI, GlowEntry> entries = new HashMap<>();
    private boolean rendererRegistered;

    private int chargedMountFrames; // replica mount-frames past the gates with wire charge > 0 (meter+apply)
    private int sets;               // apply: per-mount wire applications into the render map (zero writes carry the fade down)
    private int clamped;            // wire floats outside [0,1] (NaN/neg ⇒ 0; >1 ⇒ 1) — fail-closed, counted never silent
    private int draws;              // apply: glow sprite draw calls actually issued (proves the render ran)
    private boolean activeLogged;   // one-shot ACTIVE line (the beam-relay idiom)

    WeaponGlowLane(PredictReconcileCombatPlugin.PredictLane weaponLane) {
        this.weaponLane = weaponLane;
    }

    /** Per carried mount on FRESH frames — gates + clamp in the class doc. */
    void maintain(WeaponAPI weapon, ServerWeaponMountState mount, String who, long simTick) {
        if (!METER || "own".equals(who)
                || weapon.isBeam() || weapon.getGlowSpriteAPI() == null) {
            return;
        }
        float wire = mount.chargeLevel();
        float glow = wire;
        if (!(glow >= 0.0F)) { // NaN lands here too — fail-closed to 0
            glow = 0.0F;
        } else if (glow > 1.0F) {
            glow = 1.0F;
        }
        if (glow != wire) {
            clamped++;
        }
        if (glow > 0.0F) {
            chargedMountFrames++;
        }
        if (!APPLY) {
            return;
        }
        GlowEntry entry = entries.get(weapon);
        if (entry == null) {
            if (glow <= 0.0F && !mount.firing()) {
                return; // nothing to present yet — don't grow the map for idle mounts
            }
            entry = new GlowEntry(weapon);
            entries.put(weapon, entry);
            registerRenderer();
        }
        entry.charge = glow;
        entry.firing = mount.firing();
        entry.staleSec = 0.0F;
        sets++;
        if (!activeLogged && glow > 0.0F) {
            activeLogged = true;
            weaponLane.log("predict v4 weapon-glow ACTIVE (sprite-heatup): " + who
                    + " mount=" + mount.mountIndex() + " charge=" + glow + " @tick=" + simTick);
        }
    }

    /** One layered plugin per combat (this lane is per-PRCP-instance, PRCP is per-combat). */
    private void registerRenderer() {
        if (rendererRegistered) {
            return;
        }
        rendererRegistered = true;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            engine.addLayeredRenderingPlugin(new GlowRenderer());
        }
    }

    /** Spliced onto the weapon-meter flush line (the canister meterTokens idiom). */
    String meterTokens() {
        return " weapon_glow_mode=" + MODE
                + " weapon_glow_charged_mount_frames=" + chargedMountFrames
                + " weapon_glow_sets=" + sets
                + " weapon_glow_clamped=" + clamped
                + " weapon_glow_draws=" + draws;
    }

    /** Per-mount render state; the sprite wrapper is cached (bounded allocation, shared native instance). */
    private static final class GlowEntry {
        final WeaponAPI weapon;
        final SpriteAPI sprite;
        /** Fader phase seconds; native seeds Math.random() per weapon — presentation-only RNG. */
        float faderPhase = (float) Math.random();
        /** Native fader starts dark (field init 0.0) and only lights on the first fire-intent tick. */
        float brightness;
        float charge;
        boolean firing;
        float staleSec;

        GlowEntry(WeaponAPI weapon) {
            this.weapon = weapon;
            this.sprite = weapon.getGlowSpriteAPI();
        }
    }

    /**
     * The render half: ticks the native fader law in advance (dt-continuous, like the native
     * weapon advance) and draws the glow sprite at the slot center in render. Fail-soft — a
     * presentation lane must never take the frame down.
     */
    private final class GlowRenderer implements CombatLayeredRenderingPlugin {

        @Override
        public void init(CombatEntityAPI entity) {
        }

        @Override
        public void cleanup() {
        }

        @Override
        public boolean isExpired() {
            return false; // per-combat plugin; the engine discards it with the combat
        }

        @Override
        public void advance(float amount) {
            try {
                Iterator<GlowEntry> it = entries.values().iterator();
                while (it.hasNext()) {
                    GlowEntry e = it.next();
                    e.staleSec += amount;
                    ShipAPI ship = e.weapon.getShip();
                    boolean walked = e.staleSec < STALE_PRUNE_SEC;
                    if (ship == null || !ship.isAlive() || !walked) {
                        e.charge -= DEATH_FADE_PER_SEC * amount; // accepted-≈ residual (ii)
                        e.firing = false;
                        if (e.charge <= 0.0F) {
                            it.remove();
                            continue;
                        }
                    }
                    if (e.firing) { // native: fader ticks on fire-intent frames only, holds otherwise
                        e.faderPhase += amount;
                        float halfCharge = e.weapon.getSpec().getChargeTime() / 2.0F;
                        e.brightness = PULSE_BASE
                                + PULSE_AMP * (float) Math.sin(e.faderPhase * Math.PI * halfCharge);
                    }
                }
            } catch (RuntimeException e) {
                weaponLane.log("predict v4 weapon-glow advance FAILED (fail-soft): " + e);
            }
        }

        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER); // accepted-≈ residual (i)
        }

        @Override
        public float getRenderRadius() {
            return 100000.0F; // plugin entity sits at origin; per-mount culling happens in render
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            try {
                for (GlowEntry e : entries.values()) {
                    if (e.charge <= 0.0F || e.brightness <= 0.0F || e.sprite == null) {
                        continue;
                    }
                    ShipAPI ship = e.weapon.getShip();
                    if (ship == null) {
                        continue;
                    }
                    Vector2f loc = e.weapon.getSlot().computePosition(ship);
                    if (loc == null
                            || !viewport.isNearViewport(loc, ship.getCollisionRadius() + CULL_SLACK)) {
                        continue;
                    }
                    e.sprite.setAngle(e.weapon.getCurrAngle() - 90.0F);
                    e.sprite.setAlphaMult(e.brightness * e.charge * ship.getCombinedAlphaMult());
                    float mag = e.charge >= JITTER_CHARGE_THRESHOLD ? JITTER_MAG : 0.0F;
                    for (int i = 0; i < JITTER_COPIES; i++) {
                        float dx = (float) Math.random() * mag - mag / 2.0F;
                        float dy = (float) Math.random() * mag - mag / 2.0F;
                        e.sprite.renderAtCenter(loc.x + dx, loc.y + dy);
                        draws++;
                    }
                }
            } catch (RuntimeException e) {
                weaponLane.log("predict v4 weapon-glow render FAILED (fail-soft): " + e);
            }
        }
    }
}
