package cc.starsector.server.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * The foe-autofire disambiguator at the live seam: a per-frame,
 * read-only sampler over ONE foe autofire weapon (the first {@code irpulse} mount of the first
 * live wave glimmer), decomposing the engine autofire plugin's fire gates so the next bastion
 * watch measures WHICH gate makes AUTOFIRE-group foe weapons flicker mid-chargeup instead of
 * completing a discharge (candidates: fog / friendly-fire block / flux; force-fire stays
 * REJECTED under measure-first).
 *
 * <p>Gate anchors (on the engine autofire plugin,
 * member names in the constants below): the state-enum field
 * (ordinal 0=IDLE, 1=HAS_TARGET, 2=FIRING); the target field;
 * the FF line-block and FF path-block flags (either true forces
 * {@code shouldFire()} false); the ammo-conserve flag (gates the FIRING transition);
 * flux gate {@code weapon.getFluxCostToFire() > ship.getFluxAvailable()} (called directly);
 * fog gate {@code getFogOfWar(shipOwner).isVisible(target)} with a null-fog pass and a drone exemption;
 * slew/arc/range check = a private method (pure — its helpers are getters); hold
 * flags {@code ship.isHoldFire()/isHoldFireOneFrame()} applied by WeaponGroup.advanceAuto.
 * The target-valid check is NOT invoked (it clears the target on an expired read —
 * side effect); its checks are decomposed read-only.
 *
 * <p>Like every serve-loop measure instrument the product compile sees no game jars, so ALL
 * engine access is runtime reflection (the RealEngineAutofireFlyInProbeMain rig pattern);
 * plugin/group getters are public API methods resolved by name off the live instances.
 *
 * <p>Cadence = the client-mod adopt-line idiom: edge-triggered vector
 * lines (gate-vector change only) under a hard line cap, an uncapped counter set, and a periodic
 * heartbeat summary. All reads fail-soft: a sampling throw never reaches the serve loop;
 * repeated failures disarm the sampler permanently.
 *
 * <p><b>af_v2</b> — the AI-LAYER writer instrument, discriminating the
 * contradiction (measured 1–20-tick autofire ON-windows vs the statically-required ≥0.4 s
 * interval pacing). The plugin (exonerated earlier) sits BELOW the writer: the sole live autofire
 * toggler for an AI ship is the AttackAIModule's manager ({@code AutofireManagerV2} for ships,
 * V1 for fighters/drones), decision-paced by an {@code IntervalTracker(0.4F,0.7F)}.
 * Per frame this samples the armed ship's manager chain ({@code Ship.getShipAI()} →
 * {@code BasicShipAI.getAttackAI()} → private manager field → its tracker; tracker accessors are
 * DoNotObfuscate-stable): the elapse flag paces decisions; {@code getRemaining()} deltas across
 * plain accumulate steps measure the EXACT dt stream the AI receives (vs the ~16.9 ms engine
 * tick); remaining JUMPING UP with no observed elapse = an elapse consumed between samples
 * (multiple AI advances per serve frame). Verdict table: elapses ≥~24 ticks apart AND af flips
 * only at elapse ticks ⇒ vanilla policy on divergent INPUTS (flux band — hence fluxLevel/shieldOn
 * in the vector); elapses too frequent / dt inflated ⇒ AI-dt-inflation lane; flips between
 * elapses ⇒ a writer outside the static map.
 */
final class FoeAutofireGateSampler {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String PLUGIN_CLASS = "com.fs.starfarer.combat.ai.private";
    private static final String ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    /** U+00F4 (o-circumflex) + digits — the plugin's current-target entity field. */
    private static final String TARGET_FIELD = (char) 0xF4 + "00000";
    /** {@code for} — instantaneous friendly-in-line-of-fire block. */
    private static final String FF_LINE_FIELD = "for";
    /** U+00D2 (O-grave) + capital O + digits — projectile-travel friendly-fire block. */
    private static final String FF_PATH_FIELD = (char) 0xD2 + "O0000";
    /** U+00D5 (O-tilde) + capital O + digits — ammo-conserve commit flag. */
    private static final String CONSERVE_FIELD = (char) 0xD5 + "O0000";
    /** State-machine enum field (type: the plugin's nested state enum). */
    private static final String STATE_FIELD = "int";
    /** Private slew/arc/range check {@code boolean super(float)} — pure, safe to invoke. */
    private static final String SLEW_METHOD = "super";
    private static final String[] STATE_NAMES = {"IDLE", "TGT", "FIRE"};

    private static final String P = "npeer-serve af_gate";
    private static final int LINE_MAX = 400;
    private static final int SUM_EVERY = 600;
    private static final int ARM_SCAN_EVERY = 60;
    private static final int FAIL_DISARM_AT = 50;

    // af_v2 anchors
    /** U+00D5 (O-tilde) + capital O + digits — AttackAIModule's private autofire-manager field. */
    private static final String MANAGER_FIELD = (char) 0xD5 + "O0000";
    private static final String BASIC_AI_CLASS = "com.fs.starfarer.combat.ai.BasicShipAI";
    private static final String MGR_V2_CLASS =
            "com.fs.starfarer.combat.ai.attack.AutofireManagerV2";
    private static final String MGR_V1_CLASS = "com.fs.starfarer.combat.ai.attack.ooOO";
    /** V2 holds its IntervalTracker in a field literally named {@code Object}; V1 in {@code int}. */
    private static final String MGR_V2_TRACKER_FIELD = "Object";
    private static final String MGR_V1_TRACKER_FIELD = "int";
    private static final int V2_ELAPSE_LINE_MAX = 250;
    private static final int V2_EVENT_LINE_MAX = 150;

    // engine rig (lazy; resolved once)
    private Object engine;
    private Method getShips;
    private Method getFogOfWar;
    private Method fogIsVisible;
    private Class<?> entityApiClass;
    private Class<?> shipApiClass;

    // armed mount (all engine/API instances held as Object — no game jars at compile time)
    private Object ship;
    private Object group;
    private Object weapon;
    private Object plugin;
    private Field stateField;
    private Field targetField;
    private Field ffLineField;
    private Field ffPathField;
    private Field conserveField;
    private Method slewMethod;
    private Method pluginShouldFire;
    private Method shipIsHulk;
    private Method shipGetOwner;
    private Method shipIsHoldFire;
    private Method shipIsHoldFireOneFrame;
    private Method shipIsDrone;
    private Method shipWasRemoved;
    private Method shipGetFluxAvailable;
    private Method groupIsAutofiring;
    private Method weaponGetChargeLevel;
    private Method weaponGetCooldownRemaining;
    private Method weaponIsFiring;
    private Method weaponGetFluxCostToFire;
    private Method tgtWasRemoved;
    private Method tgtIsExpired;
    private Method tgtGetId;
    private Method tgtGetOwner;
    private Method tgtIsHulk;
    private Class<?> tgtMethodClass;

    // cadence + counters (cumulative across re-arms)
    private boolean dead;
    private int failures;
    private int armScanCountdown;
    private int arms;
    private int lines;
    private String prevKey;
    private float prevCooldown;
    private long frames;
    private long framesIdle;
    private long framesTgt;
    private long framesFire;
    private long framesShouldFire;
    private long framesTgtNull;
    private long framesFogHidden;
    private long framesFfLine;
    private long framesFfPath;
    private long framesFluxBlocked;
    private long framesConserveOff;
    private long framesSlewOff;
    private long framesHold;
    private long framesFiring;
    private long discharges;
    private float chargeMax;

    // af_v2 rig (re-resolved per arm; fail-soft — a v2 miss never blocks the v1 gate lane)
    private boolean v2Armed;
    private Object tracker;
    private Method trkElapsed;
    private Method trkRemaining;
    private Method trkDuration;
    private Method shipGetFluxLevel;
    private Object shield;
    private Method shieldIsOn;
    private String mgrLabel = "unarmed";
    private boolean prevAfV2;
    private boolean havePrevAfV2;
    private boolean prevElapsedFlag;
    private float prevRemaining;
    private boolean havePrevRemaining;
    private long lastElapseTick = -1;
    private int v2ElapseLines;
    private int v2EventLines;
    private boolean v2FailLogged;
    // af_v2 counters (cumulative across re-arms; per-arm state resets in armV2)
    private long elapses;
    private long elapsesHidden;
    private long afOnFlips;
    private long afOffFlips;
    private long gapMinTicks = -1;
    private long gapMaxTicks;
    private double dtSumSec;
    private long dtFrames;
    private float dtMaxSec;

    /** Per-frame entry — called post-advance from the serve loop; never throws. */
    void sample(long tick) {
        if (dead) {
            return;
        }
        try {
            if (plugin == null) {
                maybeArm(tick);
                if (plugin == null) {
                    return;
                }
            }
            sampleArmed(tick);
        } catch (Throwable t) {
            failures++;
            if (failures == 1) {
                System.out.println(P + " sample_fail tick=" + tick + " err=" + t);
            }
            if (failures >= FAIL_DISARM_AT) {
                dead = true;
                System.out.println(P + " disarmed reason=failures tick=" + tick);
            }
        }
    }

    private void maybeArm(long tick) throws Exception {
        if (--armScanCountdown > 0) {
            return;
        }
        armScanCountdown = ARM_SCAN_EVERY;
        if (engine == null) {
            Class<?> engineClass = Class.forName(ENGINE_CLASS);
            engine = engineClass.getMethod("getInstance").invoke(null);
            getShips = engineClass.getMethod("getShips");
            getFogOfWar = engineClass.getMethod("getFogOfWar", int.class);
            entityApiClass = Class.forName(ENTITY_API);
            shipApiClass = Class.forName(SHIP_API);
            if (engine == null) {
                return;
            }
        }
        for (Object candidate : (List<?>) getShips.invoke(engine)) {
            Class<?> shipClass = candidate.getClass();
            if (((Number) shipClass.getMethod("getOwner").invoke(candidate)).intValue() != 1
                    || (Boolean) shipClass.getMethod("isHulk").invoke(candidate)) {
                continue;
            }
            Object variant = shipClass.getMethod("getVariant").invoke(candidate);
            String variantId = variant == null ? null
                    : (String) variant.getClass().getMethod("getHullVariantId").invoke(variant);
            if (variantId == null
                    || !variantId.toLowerCase(Locale.ROOT).contains("glimmer")) {
                continue;
            }
            for (Object g : (List<?>) shipClass.getMethod("getWeaponGroupsCopy")
                    .invoke(candidate)) {
                // Index-parallel by construction (WeaponGroup.getAutofirePlugin is an
                // indexOf over the same two lists).
                List<?> weapons = (List<?>) g.getClass().getMethod("getWeaponsCopy").invoke(g);
                List<?> plugins = (List<?>) g.getClass().getMethod("getAIPlugins").invoke(g);
                for (int i = 0; i < weapons.size() && i < plugins.size(); i++) {
                    Object w = weapons.get(i);
                    String weaponId = (String) w.getClass().getMethod("getId").invoke(w);
                    if (weaponId == null
                            || !weaponId.toLowerCase(Locale.ROOT).contains("irpulse")) {
                        continue;
                    }
                    Object p = plugins.get(i);
                    if (p == null) {
                        continue;
                    }
                    arm(candidate, g, w, p, tick, variantId, weaponId);
                    return;
                }
            }
        }
    }

    private void arm(Object s, Object g, Object w, Object p, long tick,
            String variantId, String weaponId) throws Exception {
        if (!PLUGIN_CLASS.equals(p.getClass().getName())) {
            // Itself a verdict: the mount is not running the default plugin.
            System.out.println(P + " arm_unexpected_class tick=" + tick
                    + " class=" + p.getClass().getName());
            dead = true;
            return;
        }
        Class<?> pluginClass = p.getClass();
        stateField = pluginClass.getDeclaredField(STATE_FIELD);
        targetField = pluginClass.getDeclaredField(TARGET_FIELD);
        ffLineField = pluginClass.getDeclaredField(FF_LINE_FIELD);
        ffPathField = pluginClass.getDeclaredField(FF_PATH_FIELD);
        conserveField = pluginClass.getDeclaredField(CONSERVE_FIELD);
        stateField.setAccessible(true);
        targetField.setAccessible(true);
        ffLineField.setAccessible(true);
        ffPathField.setAccessible(true);
        conserveField.setAccessible(true);
        slewMethod = pluginClass.getDeclaredMethod(SLEW_METHOD, float.class);
        slewMethod.setAccessible(true);
        pluginShouldFire = pluginClass.getMethod("shouldFire");
        Class<?> shipClass = s.getClass();
        shipIsHulk = shipClass.getMethod("isHulk");
        shipGetOwner = shipClass.getMethod("getOwner");
        shipIsHoldFire = shipClass.getMethod("isHoldFire");
        shipIsHoldFireOneFrame = shipClass.getMethod("isHoldFireOneFrame");
        shipIsDrone = shipClass.getMethod("isDrone");
        shipWasRemoved = shipClass.getMethod("wasRemoved");
        shipGetFluxAvailable = shipClass.getMethod("getFluxAvailable");
        groupIsAutofiring = g.getClass().getMethod("isAutofiring");
        Class<?> weaponClass = w.getClass();
        weaponGetChargeLevel = weaponClass.getMethod("getChargeLevel");
        weaponGetCooldownRemaining = weaponClass.getMethod("getCooldownRemaining");
        weaponIsFiring = weaponClass.getMethod("isFiring");
        weaponGetFluxCostToFire = weaponClass.getMethod("getFluxCostToFire");
        ship = s;
        group = g;
        weapon = w;
        plugin = p;
        prevKey = null;
        prevCooldown = 0f;
        arms++;
        System.out.println(P + " armed tick=" + tick + " arm=" + arms
                + " ship=" + safeId(s) + " variant=" + variantId + " weapon=" + weaponId
                + " af_on=" + groupIsAutofiring.invoke(g));
        armV2(s, tick);
    }

    /**
     * The af_v2 arm: resolve the AI-layer autofire WRITER — {@code Ship.getShipAI()} →
     * {@code BasicShipAI.getAttackAI()} → the private manager field (V2 for ships, V1 for
     * fighters/drones) → its {@code IntervalTracker} (DoNotObfuscate — stable method names).
     * Every miss short-circuits with its own labeled line (each is itself a verdict datum:
     * a non-BasicShipAI or unknown manager class falsifies the static map).
     */
    private void armV2(Object s, long tick) {
        v2Armed = false;
        tracker = null;
        havePrevAfV2 = false;
        havePrevRemaining = false;
        prevElapsedFlag = false;
        lastElapseTick = -1;
        try {
            Object ai = s.getClass().getMethod("getShipAI").invoke(s);
            if (ai == null || !BASIC_AI_CLASS.equals(ai.getClass().getName())) {
                mgrLabel = ai == null ? "no_ai" : "ai:" + ai.getClass().getName();
                System.out.println(P + " af2_no_basic_ai tick=" + tick + " ai=" + mgrLabel);
                return;
            }
            Object attack = ai.getClass().getMethod("getAttackAI").invoke(ai);
            if (attack == null) {
                mgrLabel = "no_attack_module";
                System.out.println(P + " af2_no_attack_module tick=" + tick);
                return;
            }
            Field mgrField = attack.getClass().getDeclaredField(MANAGER_FIELD);
            mgrField.setAccessible(true);
            Object mgr = mgrField.get(attack);
            String mgrClass = mgr == null ? null : mgr.getClass().getName();
            String trackerFieldName = MGR_V2_CLASS.equals(mgrClass) ? MGR_V2_TRACKER_FIELD
                    : MGR_V1_CLASS.equals(mgrClass) ? MGR_V1_TRACKER_FIELD : null;
            mgrLabel = mgrClass == null ? "null_mgr"
                    : mgrClass.substring(mgrClass.lastIndexOf('.') + 1);
            if (trackerFieldName == null) {
                System.out.println(P + " af2_unknown_mgr tick=" + tick + " mgr=" + mgrClass);
                return;
            }
            Field trackerField = mgr.getClass().getDeclaredField(trackerFieldName);
            trackerField.setAccessible(true);
            tracker = trackerField.get(mgr);
            if (tracker == null) {
                System.out.println(P + " af2_null_tracker tick=" + tick + " mgr=" + mgrLabel);
                return;
            }
            Class<?> trackerClass = tracker.getClass();
            trkElapsed = trackerClass.getMethod("intervalElapsed");
            trkRemaining = trackerClass.getMethod("getRemaining");
            trkDuration = trackerClass.getMethod("getIntervalDuration");
            Class<?> shipClass = s.getClass();
            shipGetFluxLevel = shipClass.getMethod("getFluxLevel");
            shield = shipClass.getMethod("getShield").invoke(s);
            shieldIsOn = shield == null ? null : shield.getClass().getMethod("isOn");
            boolean drone = (Boolean) shipIsDrone.invoke(s);
            boolean fighter = (Boolean) shipClass.getMethod("isFighter").invoke(s);
            v2Armed = true;
            System.out.println(P + " af2_armed tick=" + tick + " mgr=" + mgrLabel
                    + " drone=" + bit(drone) + " fighter=" + bit(fighter)
                    + " dur=" + f2(((Number) trkDuration.invoke(tracker)).floatValue())
                    + " rem=" + f2(((Number) trkRemaining.invoke(tracker)).floatValue()));
        } catch (Throwable t) {
            mgrLabel = "arm_fail";
            System.out.println(P + " af2_arm_fail tick=" + tick + " err=" + t);
        }
    }

    private void sampleArmed(long tick) throws Exception {
        boolean hulk = (Boolean) shipIsHulk.invoke(ship);
        if (hulk || (Boolean) shipWasRemoved.invoke(ship)) {
            System.out.println(P + " rearm reason=" + (hulk ? "hulk" : "removed")
                    + " tick=" + tick + summaryBody());
            plugin = null;
            armScanCountdown = 0;
            return;
        }
        int shipOwner = ((Number) shipGetOwner.invoke(ship)).intValue();
        boolean autofireOn = (Boolean) groupIsAutofiring.invoke(group);
        Enum<?> stateValue = (Enum<?>) stateField.get(plugin);
        int state = stateValue == null ? -1 : stateValue.ordinal();
        String stateName = state >= 0 && state < STATE_NAMES.length
                ? STATE_NAMES[state] : "?" + state;
        boolean shouldFire = (Boolean) pluginShouldFire.invoke(plugin);
        Object target = targetField.get(plugin);
        boolean targetNull = target == null;
        boolean targetHulk = false;
        boolean targetRemoved = false;
        boolean targetExpired = false;
        boolean targetOwnerBad = false;
        String targetLabel = "null";
        String fogState = "n/a";
        boolean fogHidden = false;
        if (!targetNull) {
            resolveTargetMethods(target.getClass());
            int targetOwner = tgtGetOwner == null ? -1
                    : ((Number) tgtGetOwner.invoke(target)).intValue();
            targetOwnerBad = targetOwner == 100 || targetOwner == shipOwner;
            targetHulk = tgtIsHulk != null && (Boolean) tgtIsHulk.invoke(target);
            targetRemoved = tgtWasRemoved != null && (Boolean) tgtWasRemoved.invoke(target);
            targetExpired = tgtIsExpired != null && (Boolean) tgtIsExpired.invoke(target);
            targetLabel = (shipApiClass.isInstance(target) ? "S" : "E") + targetOwner + ":"
                    + (tgtGetId != null ? String.valueOf(tgtGetId.invoke(target))
                            : Integer.toHexString(System.identityHashCode(target)));
            Object fog = getFogOfWar.invoke(engine, shipOwner);
            if (fog == null) {
                fogState = "null";
            } else {
                if (fogIsVisible == null) {
                    fogIsVisible = fog.getClass().getMethod("isVisible", entityApiClass);
                }
                boolean visible = (Boolean) fogIsVisible.invoke(fog, target);
                boolean drone = (Boolean) shipIsDrone.invoke(ship);
                // The engine's own gate form: hidden blocks only when fog exists, target is not
                // visible, and the firer is not a drone.
                fogHidden = !visible && !drone;
                fogState = visible ? "vis" : (drone ? "hid_drone_exempt" : "HID");
            }
        }
        boolean ffLine = ffLineField.getBoolean(plugin);
        boolean ffPath = ffPathField.getBoolean(plugin);
        boolean conserve = conserveField.getBoolean(plugin);
        float fluxCost = ((Number) weaponGetFluxCostToFire.invoke(weapon)).floatValue();
        float fluxAvail = ((Number) shipGetFluxAvailable.invoke(ship)).floatValue();
        boolean fluxBlocked = fluxCost > fluxAvail;
        boolean slewed = (Boolean) slewMethod.invoke(plugin, 0.0f);
        boolean hold = (Boolean) shipIsHoldFire.invoke(ship);
        boolean holdOneFrame = (Boolean) shipIsHoldFireOneFrame.invoke(ship);
        boolean firing = (Boolean) weaponIsFiring.invoke(weapon);
        float charge = ((Number) weaponGetChargeLevel.invoke(weapon)).floatValue();
        float cooldown = ((Number) weaponGetCooldownRemaining.invoke(weapon)).floatValue();

        frames++;
        if (state == 0) {
            framesIdle++;
        } else if (state == 1) {
            framesTgt++;
        } else if (state == 2) {
            framesFire++;
        }
        if (shouldFire) {
            framesShouldFire++;
        }
        if (targetNull) {
            framesTgtNull++;
        }
        if (fogHidden) {
            framesFogHidden++;
        }
        if (ffLine) {
            framesFfLine++;
        }
        if (ffPath) {
            framesFfPath++;
        }
        if (fluxBlocked) {
            framesFluxBlocked++;
        }
        if (!conserve) {
            framesConserveOff++;
        }
        if (!targetNull && !slewed) {
            framesSlewOff++;
        }
        if (hold || holdOneFrame) {
            framesHold++;
        }
        if (firing) {
            framesFiring++;
        }
        if (charge > chargeMax) {
            chargeMax = charge;
        }
        // A completed discharge starts the cooldown clock (census: cooldown 0.0 always).
        if (cooldown > 0.01f && prevCooldown <= 0.01f) {
            discharges++;
        }
        prevCooldown = cooldown;

        String key = stateName + '|' + shouldFire + '|' + autofireOn + '|' + targetLabel + '|'
                + fogState + '|' + ffLine + '|' + ffPath + '|' + fluxBlocked + '|' + conserve
                + '|' + slewed + '|' + hold + '|' + holdOneFrame + '|' + firing + '|'
                + targetHulk + '|' + targetRemoved + '|' + targetExpired + '|' + targetOwnerBad;
        if (!key.equals(prevKey) && lines < LINE_MAX) {
            lines++;
            System.out.println(P + " s tick=" + tick + " st=" + stateName + " sf=" + bit(shouldFire)
                    + " af=" + bit(autofireOn) + " tgt=" + targetLabel + " fog=" + fogState
                    + " ff_line=" + bit(ffLine) + " ff_path=" + bit(ffPath)
                    + " flux=" + (fluxBlocked ? "BLK" : "ok") + " cons=" + bit(conserve)
                    + " slew=" + bit(slewed) + " hold=" + bit(hold) + " hold1=" + bit(holdOneFrame)
                    + " fir=" + bit(firing)
                    + " t_hulk=" + bit(targetHulk) + " t_rm=" + bit(targetRemoved)
                    + " t_exp=" + bit(targetExpired) + " t_own=" + bit(targetOwnerBad)
                    + " chg=" + f2(charge) + " cd=" + f2(cooldown)
                    + " cost=" + f1(fluxCost) + " avail=" + f1(fluxAvail)
                    + (lines == LINE_MAX ? " (line cap reached)" : ""));
        }
        prevKey = key;
        sampleV2(tick, autofireOn, stateName);
        if (frames % SUM_EVERY == 0) {
            System.out.println(P + " sum tick=" + tick + summaryBody());
        }
    }

    /**
     * The af_v2 per-frame read (post-advance). The tracker's elapse flag is true for exactly
     * the one sample after the advance that elapsed it (the NEXT advance calls nextInterval);
     * a plain accumulate step decrements {@code getRemaining()} by exactly the advance dt, so
     * the delta across two flag-false samples IS the AI's dt for that frame. Remaining rising
     * with no elapse flag observed = the tracker was reset between samples (an elapse consumed
     * by an extra AI advance) — counted separately as {@code el_hid}. Lines: every observed
     * elapse (cap 250) + every af flip / hidden elapse (own cap 150, so late-combat flips are
     * never crowded out); counters uncapped.
     */
    private void sampleV2(long tick, boolean autofireOn, String stateName) {
        if (!v2Armed) {
            return;
        }
        try {
            boolean elapsedFlag = (Boolean) trkElapsed.invoke(tracker);
            float remaining = ((Number) trkRemaining.invoke(tracker)).floatValue();
            float duration = ((Number) trkDuration.invoke(tracker)).floatValue();
            float fluxLevel = ((Number) shipGetFluxLevel.invoke(ship)).floatValue();
            boolean shieldOn = shieldIsOn != null && (Boolean) shieldIsOn.invoke(shield);
            boolean elapseSeen = elapsedFlag && !prevElapsedFlag;
            boolean elapseHidden = havePrevRemaining && !prevElapsedFlag && !elapsedFlag
                    && remaining > prevRemaining + 0.001f;
            if (havePrevRemaining && !prevElapsedFlag && !elapsedFlag && !elapseHidden) {
                float dt = prevRemaining - remaining;
                if (dt > 0f) {
                    dtSumSec += dt;
                    dtFrames++;
                    if (dt > dtMaxSec) {
                        dtMaxSec = dt;
                    }
                }
            }
            boolean flip = havePrevAfV2 && autofireOn != prevAfV2;
            if (flip) {
                if (autofireOn) {
                    afOnFlips++;
                } else {
                    afOffFlips++;
                }
            }
            // On elapse lines this is the inter-elapse gap (the pacing measurement); on pure
            // flip lines, ticks since the last decision (a flip NOT at an elapse = foreign writer).
            long sinceElapse = lastElapseTick >= 0 ? tick - lastElapseTick : -1;
            if (elapseSeen || elapseHidden) {
                if (elapseHidden) {
                    elapsesHidden++;
                } else {
                    elapses++;
                }
                if (sinceElapse >= 0) {
                    if (gapMinTicks < 0 || sinceElapse < gapMinTicks) {
                        gapMinTicks = sinceElapse;
                    }
                    if (sinceElapse > gapMaxTicks) {
                        gapMaxTicks = sinceElapse;
                    }
                }
                lastElapseTick = tick;
            }
            boolean event = flip || elapseHidden;
            boolean emit = false;
            if (event && v2EventLines < V2_EVENT_LINE_MAX) {
                v2EventLines++;
                emit = true;
            } else if (!event && elapseSeen && v2ElapseLines < V2_ELAPSE_LINE_MAX) {
                v2ElapseLines++;
                emit = true;
            }
            if (emit) {
                System.out.println(P + " af_v2 tick=" + tick
                        + " ev=" + (flip ? (elapseSeen || elapseHidden ? "flip+elapse" : "flip")
                                : elapseHidden ? "elapse_hidden" : "elapse")
                        + " af=" + bit(autofireOn) + " st=" + stateName
                        + " since_el=" + sinceElapse
                        + " rem=" + f2(remaining) + " dur=" + f2(duration)
                        + " flux_lvl=" + f2(fluxLevel) + " shield=" + bit(shieldOn));
            }
            prevAfV2 = autofireOn;
            havePrevAfV2 = true;
            prevElapsedFlag = elapsedFlag;
            prevRemaining = remaining;
            havePrevRemaining = true;
        } catch (Throwable t) {
            v2Armed = false;
            if (!v2FailLogged) {
                v2FailLogged = true;
                System.out.println(P + " af2_fail tick=" + tick + " err=" + t);
            }
        }
    }

    private void resolveTargetMethods(Class<?> targetClass) {
        if (targetClass == tgtMethodClass) {
            return;
        }
        tgtMethodClass = targetClass;
        tgtWasRemoved = optionalMethod(targetClass, "wasRemoved");
        tgtIsExpired = optionalMethod(targetClass, "isExpired");
        tgtGetId = optionalMethod(targetClass, "getId");
        tgtGetOwner = optionalMethod(targetClass, "getOwner");
        tgtIsHulk = optionalMethod(targetClass, "isHulk");
    }

    private static Method optionalMethod(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String summaryBody() {
        return " frames=" + frames + " arms=" + arms
                + " idle=" + framesIdle + " has_tgt=" + framesTgt + " fire=" + framesFire
                + " sf=" + framesShouldFire + " tgt_null=" + framesTgtNull
                + " fog_hidden=" + framesFogHidden
                + " ff_line=" + framesFfLine + " ff_path=" + framesFfPath
                + " flux_blk=" + framesFluxBlocked + " cons_off=" + framesConserveOff
                + " slew_off=" + framesSlewOff + " hold=" + framesHold
                + " firing=" + framesFiring + " discharges=" + discharges
                + " chg_max=" + f2(chargeMax) + " lines=" + lines
                + " | af2 mgr=" + mgrLabel + " el=" + elapses + " el_hid=" + elapsesHidden
                + " gap=" + gapMinTicks + ".." + gapMaxTicks
                + " dt_ms=" + f2(dtFrames > 0 ? (float) (dtSumSec / dtFrames * 1000.0) : -1f)
                + " dt_max_ms=" + f2(dtMaxSec * 1000f)
                + " af_on=" + afOnFlips + " af_off=" + afOffFlips
                + " v2_lines=" + (v2ElapseLines + v2EventLines);
    }

    private static String safeId(Object entity) {
        try {
            return String.valueOf(entity.getClass().getMethod("getId").invoke(entity));
        } catch (Exception e) {
            return "id?";
        }
    }

    private static String bit(boolean b) {
        return b ? "1" : "0";
    }

    private static String f1(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
