package cc.starsector.client;

import java.awt.Color;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.DisintegratorEffect;
import com.fs.starfarer.api.impl.combat.threat.DevouringSwarmMissileEffect;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * The PRESENTATION half of a
 * devouring-swarm hit on a treated replica's shot, with the sim half left to the server.
 *
 * <p><b>Why:</b> vanilla's devouring hit registers ONE {@link DisintegratorEffect} that carries both
 * the sim (11 armor/hull-eating ticks that write the victim's cells and hitpoints, with their
 * floaties) and the look (particles each tick, the {@code devouring_swarm_loop} sound, and the
 * "carry-back": a damaging tick may add ONE member to the FIRER's swarm at the eaten spot, which
 * then drifts back into its cloud). {@code CcWrapDevouringSwarmMissileEffect} shut the whole object
 * on replicas (the writes are server-authoritative and arrive on the wire as the hull/armor folds) —
 * dropping the look with it: the swarm visibly stopped carrying anything back.
 * Vanilla has NO hull/armor heal on a devouring hit: the carry-back IS swarm members =
 * fragment ammo growth + eating FX, never a hull write on the source.
 *
 * <p>This subclass keeps the parent's machinery (tick clock, particles, render, loop fade, expiry)
 * and supplies the devouring hook values, the tick ({@link #dealDamage}) and the carry-back
 * ({@link #damageDealt}) — rewritten from the functional spec. The tick
 * WRITES NOTHING to the victim: never {@code setArmorValue}, {@code setHitpoints} or
 * {@code applyDamage} — the door re-applied the wire's post-armor split through the engine's
 * armor formula and double-split it; cells + hull are
 * wire-mirrored. It READS the mirrored grid for the tick's armor/hull NUMBERS,
 * printed through the engine's floaty call behind {@code Misc.shouldShowDamageFloaty}, and
 * rolls the carry-back on every tick that yielded (the server's cells are unknown here; the mirrored
 * grid stands in). The fighter exclusion is kept: attack-swarm wings carry nothing back. The firer's
 * swarm write is LOCAL (member counts never ride the wire), 0 wire bytes.
 */
final class ReplicaDevourPresenter extends DisintegratorEffect {

    static final String TAG = "devourpresent";
    /** (D-THREATHARVEST) — census tag of the WIRE-seam mint: a sourced MISSILE_END
     *  (INTACT = consumed on a hull) on a devouring carrier whose puppet twin never collided locally
     *  (the twin is retired same-tick by {@code SourcedMissileEndLane.consumeTwin} before the engine
     *  can walk it into the victim — a measured 29-of-600 gap). */
    static final String TAG_ENDHOOK = "devourpresent_endhook";
    /** per-projectile once-latch in the projectile's own customData (identity-tied,
     *  dies with the body, no map to leak): the local {@code onHit} mint and the end-hook mint never
     *  both present the same carrier. */
    static final String LATCH_KEY = "cc.devourPresented";
    static final float CARRY_BACK_SKIP_CHANCE = 0.25F; // a yielding tick adds no carry-back member with this probability
    /** Census tag of a tick that PRINTED vanilla's floaty (the gate's `visual` column). */
    static final String TAG_TICK = "devourpresent_tick";
    /** per-match cap on the per-tick debrief rows (11 rows per hit; the meter tokens carry the totals). */
    static final int TICK_ROW_CAP = 240;
    static int presented;
    static int presentedEndHook; // Of `presented`, minted at the wire end seam
    static int membersCarried;
    static int ticksNoYield;     // Ticks with no yield (no cell under the point / type mult <= 0 / no firer or grid)
    static int ticksPrinted;     // Yielding ticks that printed vanilla's armor/hull number
    static int ticksHidden;      // Yielding ticks Misc.shouldShowDamageFloaty hid (or the API threw)
    static float hullTickSum;    // Σ hull number (its > 1 print gate) over the printed ticks (the server-shape number)
    static float armorPrintedSum; // Σ armor printed over the same ticks
    private static int tickRows;

    private final ShipAPI source;
    private final RoilingSwarmEffect sourceSwarm;

    private ReplicaDevourPresenter(DamagingProjectileAPI proj, ShipAPI target, Vector2f offset,
            ShipAPI source, RoilingSwarmEffect sourceSwarm) {
        super(proj, target, offset);
        this.source = source;
        this.sourceSwarm = sourceSwarm;
    }

    /**
     * Register the presenter for a replica devouring hit (the vanilla {@code onHit} branch
     * shape: non-shield, damaging, ship target). Returns false when nothing was minted.
     */
    static boolean present(CombatEngineAPI engine, DamagingProjectileAPI projectile, ShipAPI target, Vector2f point) {
        return present(engine, projectile, target, point, TAG);
    }

    /** The wire-seam mint ({@code DevourEndLane.presentDevourEnd}, carved out of SML): the same
     *  presenter object at the confessed end pose snapped to the victim's surface, its own census tag.
     *  The carrier body is still in play when this runs (retire follows), so {@code getDamageAmount()}
     *  / {@code getSource()} / {@code getLocation()} are live; the object keeps the reference after the
     *  removal exactly as vanilla's disintegrator keeps its consumed missile. */
    static boolean presentAtEnd(CombatEngineAPI engine, DamagingProjectileAPI projectile, ShipAPI target, Vector2f point) {
        boolean minted = present(engine, projectile, target, point, TAG_ENDHOOK);
        if (minted) {
            presentedEndHook++;
        }
        return minted;
    }

    /** Has either seam already minted a presenter for this carrier? */
    static boolean alreadyPresented(DamagingProjectileAPI projectile) {
        try {
            Map<String, Object> data = projectile == null ? null : projectile.getCustomData();
            return data != null && Boolean.TRUE.equals(data.get(LATCH_KEY));
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * The shared mint of {@link #present(CombatEngineAPI, DamagingProjectileAPI, ShipAPI, Vector2f)} and
     * {@link #presentAtEnd}, rewritten from the functional spec:
     * once per carrier ({@link #LATCH_KEY}), the hit point is kept as an
     * offset in the target's own frame ({@link #hullFrameOffset}; the parent re-applies the facing every
     * frame, so the effect rides the hull), and the presenter registers as a layered rendering plugin
     * placed at the projectile. {@code tag} = the census row the mint counts under. TRUE = minted.
     *
     *
     */
    private static boolean present(CombatEngineAPI engine, DamagingProjectileAPI projectile, ShipAPI target,
            Vector2f point, String tag) {
        if (engine == null || projectile == null || target == null || point == null
                || alreadyPresented(projectile)) {
            return false;
        }
        Vector2f centre = target.getLocation();
        float[] local = hullFrameOffset(point.x - centre.x, point.y - centre.y, target.getFacing());
        ShipAPI firer = projectile.getSource();
        RoilingSwarmEffect firerSwarm = firer == null ? null : RoilingSwarmEffect.getSwarmFor(firer);
        CombatEntityAPI body = engine.addLayeredRenderingPlugin(new ReplicaDevourPresenter(projectile, target,
                new Vector2f(local[0], local[1]), firer, firerSwarm));
        if (body != null) {
            body.getLocation().set(projectile.getLocation());
        }
        projectile.setCustomData(LATCH_KEY, Boolean.TRUE);
        presented++;
        CcReplicaEffectGate.visualMinted(tag);
        return true;
    }

    /**
     * A world-frame delta ({@code dx}, {@code dy}) in the frame of a hull facing {@code facingDeg} degrees:
     * the delta rotated by MINUS the facing (counter-clockwise positive — the parent turns the stored offset
     * back by plus the facing each frame). Pure; {@code ApiRwERows} drives it.
     *
     *
     */
    static float[] hullFrameOffset(float dx, float dy, float facingDeg) {
        double r = Math.toRadians(-facingDeg);
        float c = (float) Math.cos(r);
        float s = (float) Math.sin(r);
        return new float[] {dx * c - dy * s, dx * s + dy * c};
    }

    /**
     * The devouring hook values the parent reads, written from the functional spec's hook table:
     * the projectile's own damage spread over the swarm's tick count, hull
     * allowed, five particles a tick, the devouring loop, and the particle shape ({@link #addParticle}).
     *
     *
     */
    @Override
    protected float getTotalDamage() {
        return proj.getDamageAmount();
    }

    @Override
    protected int getNumTicks() {
        return DevouringSwarmMissileEffect.NUM_TICKS;
    }

    @Override
    protected boolean canDamageHull() {
        return true;
    }

    @Override
    protected int getNumParticlesPerTick() {
        return 5;
    }

    @Override
    protected String getSoundLoopId() {
        return "devouring_swarm_loop";
    }

    /** One particle of the parent's own type: base size 25, a 3-5 s life, end size x1, a faint violet,
     *  scattered within 10 su of its anchor. */
    @Override
    protected void addParticle() {
        ParticleData p = new ParticleData(25F, 3F + 2F * (float) Math.random(), 1F);
        p.color = new Color(125, 100, 200, 25);
        p.offset = Misc.getPointWithinRadius(p.offset, 10F);
        particles.add(p);
    }

    /**
     * One presenter tick (the parent's clock calls it once per tick and counts it), rewritten from the
     * functional spec. It replaces the parent's damaging tick and WRITES
     * NOTHING to the victim: the tick's particles; the read-only {@link #yieldAt yield} over the victim's
     * mirrored grid at the effect's current point (per-tick damage = total / ticks, scaled by the parent's
     * public damage-type multiplier); the armor / hull NUMBERS ({@link #printNumbers}); one capped debrief
     * row; then the carry-back ({@link #damageDealt}). A tick with no yield counts {@link #ticksNoYield}
     * and stops after its particles. Census: every tick counts {@code replicaFrame} first; a tick with
     * an effect point AND a victim counts {@code doorShut} (the armour / hull write a replica never
     * makes) before the yield is read — a flight grade pairs it with the mint count.
     *
     *
     */
    @Override
    protected void dealDamage() {
        CcReplicaEffectGate.replicaFrame(TAG);
        for (int i = 0; i < getNumParticlesPerTick(); i++) {
            addParticle();
        }
        Vector2f at = entity == null ? null : entity.getLocation();
        if (at != null && target != null) {
            CcReplicaEffectGate.doorShut(TAG);
        }
        ArmorGridAPI grid = target == null ? null : target.getArmorGrid();
        float[] yield = null;
        if (at != null && grid != null && source != null) {
            yield = yieldAt(grid.getGrid(), grid.getCellAtLocation(at), getTotalDamage() / getNumTicks(),
                    DisintegratorEffect.getDamageTypeMult(source, target));
        }
        if (yield == null) {
            ticksNoYield++;
            return;
        }
        float armor = yield[0];
        float hull = canDamageHull() ? yield[1] : 0F;
        boolean shown = printNumbers(at, armor, hull);
        if (tickRows < TICK_ROW_CAP) {
            tickRows++;
            PilotWatchLog.info("predict devourpresent tick #" + tickRows + ": victim=remote:"
                    + CcReplicaEffectGate.boundRemoteEntityId(target) + " tick=" + (ticks + 1) + "/" + getNumTicks()
                    + " hullTick=" + fmt1(hull) + " armorTick=" + fmt1(armor) + " shown=" + shown);
        }
        damageDealt(new Vector2f(at), armor, hull);
    }

    /**
     * The tick's NUMBERS through the engine's own floaty call, behind {@code Misc.shouldShowDamageFloaty}
     * (firer, victim): the armor sum at the point when above 0, the hull sum 20 su above it when above 1.
     * TRUE = the gate let the tick print (counts {@link #ticksPrinted} + the two printed sums + the
     * {@link #TAG_TICK} census); a gate that hides it, or an API that throws, counts {@link #ticksHidden}.
     * Presentation only — the victim's cells and hull are the wire's.
     *
     *
     */
    private boolean printNumbers(Vector2f at, float armor, float hull) {
        boolean shown;
        try {
            shown = Misc.shouldShowDamageFloaty(source, target);
            if (shown) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (armor > 0F) {
                    engine.addFloatingDamageText(new Vector2f(at), armor, Misc.FLOATY_ARMOR_DAMAGE_COLOR, target, source);
                }
                if (hull > 1F) {
                    engine.addFloatingDamageText(new Vector2f(at.x, at.y + 20F), hull, Misc.FLOATY_HULL_DAMAGE_COLOR,
                            target, source);
                }
            }
        } catch (Throwable fail) {
            shown = false; // counted on ticksHidden below (the meter's "or the API threw" half)
        }
        if (!shown) {
            ticksHidden++;
            return false;
        }
        ticksPrinted++;
        armorPrintedSum += armor > 0F ? armor : 0F;
        hullTickSum += hull > 1F ? hull : 0F;
        CcReplicaEffectGate.visualMinted(TAG_TICK);
        return true;
    }

    /**
     * The tick's YIELD, pure ({@code ApiRwERows} drives it): over the 5 x 5 cells centred on {@code cell}
     * minus its 4 corners (21 cells, clipped to {@code grid}), a cell's share = {@code perTick} x
     * {@link #windowWeight} x {@code typeMult}; its armor part = min(share, the cell's armor), its hull
     * part the excess. Returns {armorSum, hullSum}, or null when nothing yields (no grid; no cell = off the
     * grid; a type multiplier not above 0). Reads {@code grid} ({@code [x][y]}, the cell's own order);
     * writes nothing.
     *
     *
     */
    static float[] yieldAt(float[][] grid, int[] cell, float perTick, float typeMult) {
        if (grid == null || cell == null || cell.length < 2 || !(typeMult > 0F)) {
            return null;
        }
        float armor = 0F;
        float hull = 0F;
        for (int dx = -2; dx <= 2; dx++) {
            int x = cell[0] + dx;
            if (x < 0 || x >= grid.length || grid[x] == null) {
                continue;
            }
            for (int dy = -2; dy <= 2; dy++) {
                int y = cell[1] + dy;
                float weight = windowWeight(dx, dy);
                if (weight <= 0F || y < 0 || y >= grid[x].length) {
                    continue;
                }
                float share = perTick * weight * typeMult;
                float fromArmor = Math.min(share, grid[x][y]);
                armor += fromArmor;
                hull += share - fromArmor;
            }
        }
        return new float[] {armor, hull};
    }

    /** A window cell's weight by its offset from the centre: 1/15 in the inner 3 x 3, 1/30 on the rest of
     *  the 5 x 5, 0 at the 5 x 5's 4 corners and outside it (the 21 weights sum to 1). */
    static float windowWeight(int dx, int dy) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        if (ax > 2 || ay > 2 || (ax == 2 && ay == 2)) {
            return 0F;
        }
        return ax <= 1 && ay <= 1 ? 1F / 15F : 1F / 30F;
    }

    private static String fmt1(float v) {
        return String.valueOf(Math.round(v * 10.0F) / 10.0F);
    }

    /**
     * The carry-back, rewritten from the functional spec: a tick that yielded
     * adds ONE member to the living, non-fighter firer's swarm at {@code loc}, fading in over 0.3 s (it then
     * drifts home with its cloud), unless the {@link #CARRY_BACK_SKIP_CHANCE} roll skips it
     * ({@link #carriesBack}). LOCAL only: member counts never ride the wire; nothing here touches the victim.
     *
     *
     */
    @Override
    protected void damageDealt(Vector2f loc, float armorDamage, float hullDamage) {
        boolean alive = source != null && source.isAlive();
        boolean fighter = source != null && source.isFighter();
        if (!carriesBack(alive, fighter, sourceSwarm != null, (float) Math.random(), armorDamage, hullDamage)) {
            return;
        }
        RoilingSwarmEffect.SwarmMember member = sourceSwarm.addMember();
        if (member != null && member.loc != null) {
            member.loc.set(loc);
        }
        if (member != null && member.fader != null) {
            member.fader.setDurationIn(0.3F);
        }
        membersCarried++;
    }

    /**
     * The carry-back verdict, pure ({@code ApiRwERows} drives it): a living, non-fighter firer with a
     * swarm, a {@code roll} in [0, 1) at or above {@link #CARRY_BACK_SKIP_CHANCE}, and a tick that yielded
     * (either sum above 0). Over many yielding ticks the member rate is 1 minus the skip chance (0.75).
     *
     *
     */
    static boolean carriesBack(boolean firerAlive, boolean firerFighter, boolean firerHasSwarm, float roll,
            float armorSum, float hullSum) {
        return firerAlive && !firerFighter && firerHasSwarm && roll >= CARRY_BACK_SKIP_CHANCE
                && (armorSum > 0F || hullSum > 0F);
    }
}
