package cc.starsector.server.platform;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.TreatedOuterWrapperCombatStepBoundary;
import cc.starsector.server.bridge.CrDeploySeam;
import cc.starsector.server.bridge.TerrainStatSeam;
import cc.starsector.server.runtime.ClientIntentGate;
import cc.starsector.server.runtime.ClientManeuverGate;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Product-owned headless combat platform context for guarded render/UI-adjacent
 * combat bring-up.
 *
 * <p>The scout still resolves version-specific classes, methods, and fields by
 * descriptor, then hands those handles to this adapter. This class owns the
 * mutation policy: seed exactly the known headless combat dependencies, verify
 * readback, fail closed on any mismatch, and restore all touched state in reverse
 * order. It also owns the temporary P4 force-fire step wrapper used with the
 * accepted {@code advanceInner(...)} combat loop.
 */
public final class HeadlessCombatContext implements AutoCloseable {

    public static final String PRODUCT_ADAPTER = "cc.starsector.server.platform.HeadlessCombatContext";
    public static final String BACKGROUND_SPRITE_TREATMENT =
            "adr_0012_option_a_headless_background_sprite_seed";
    public static final String COMBAT_READINESS_PLUGIN_TREATMENT =
            "adr_0012_option_a_headless_combat_readiness_plugin_seed";
    public static final String HULL_BUILTIN_MODS_TREATMENT =
            "adr_0012_option_a_headless_hull_builtin_mod_suppression";
    public static final String VARIANT_FX_DRONE_TAG_TREATMENT =
            "adr_0012_option_a_headless_variant_fx_drone_tag_seed";
    public static final String MOD_PLUGIN_PROXY_TREATMENT = "adr_0012_option_a_headless_mod_plugin_proxy";
    public static final String TEXTURED_LINE_TEXTURE_TREATMENT =
            "adr_0012_option_a_headless_textured_line_texture_cache_seed";
    public static final String COMBAT_UI_PROXY_TREATMENT = "adr_0012_option_a_headless_combat_ui_proxy";
    public static final String DEBRIS_VIEWPORT_TREATMENT = "adr_0012_option_a_headless_combat_viewport_seed";
    public static final String COLLISION_SOUND_SPEC_TREATMENT =
            "adr_0012_option_a_headless_collision_sound_spec_seed";
    public static final String ROSTER_INSTALL_TREATMENT =
            "adr_0012_option_a_headless_combat_roster_install";
    /**
     * Engine-own deploy lane: per side an
     * engine-minted {@code CombatFleetManager}, per ship a real {@code FleetMember} spawned
     * through the engine's own {@code spawnFleetMember -> deploy -> instantiateForCombat ->
     * addObject} path, so deployment bookkeeping (the end-of-combat judge's input) is real.
     * Sibling of {@link #ROSTER_INSTALL_TREATMENT}, behind its own config; the direct lane stays.
     */
    public static final String ENGINE_DEPLOY_ROSTER_TREATMENT =
            "task_1067_engine_own_deploy_lane_roster_install";
    /**
     * Fallback combat-ready deploy CR baked onto each engine-deployed ship when no
     * {@code MatchDescriptor} sets {@code rules.deployCr}. A bare fresh member's maxCR is only 0.2,
     * well under the AI's auto-withdraw threshold; 0.7 is a healthy combat-ready CR
     * (clear of the malfunction/withdraw band) — a faithful in-game value a well-supplied ship
     * deploys at, set via the engine's own ShipAPI field. Value choice.
     * When a descriptor IS active the validated
     * {@code MatchDescriptor.deployCr()} ({@code (0,1]}) overrides this via
     * {@link Builder#engineDeployRosterDeployCr(float)}, so the deployed CR becomes descriptor-driven
     * while this no-descriptor default path stays unchanged.
     */
    public static final float DEFAULT_DEPLOY_CR = 0.7f;
    public static final String KEYBOARD_KEYDOWN_TREATMENT =
            "adr_0012_option_a_headless_combat_advance_keyboard_keydown_zero_state";
    public static final String FORCE_FIRE_DRIVER_TREATMENT =
            "adr_0018_advance_inner_product_force_fire_driver";
    public static final String COMMAND_AUTHORITY_MANEUVER_DRIVER_TREATMENT =
            "hook_0004_command_authority_maneuver_driver";
    public static final String DETERMINISTIC_COMBAT_DAMAGE_RNG_TREATMENT =
            "adr_0019_deterministic_combat_damage_rng_global_math_seed";
    public static final String RENDER_NULL_SPRITE_TEXTURE_TREATMENT =
            "adr_0012_option_a_headless_render_null_sprite_texture_stub";
    /**
     * Descriptor recorded (in place of installing the L0 render-null treatment) when the headless
     * graphics substrate is present. With the substrate
     * the engine's own {@code ResourceLoaderState.init(Map)} populates real textures, so
     * {@code com.fs.graphics.Sprite.getTexture()} is non-null and the per-spawn L0
     * {@code SpriteTextureStubTransformer} is dead weight (a system-bearing ship was built with
     * NO L0 stub under the substrate). The product spawn path therefore runs the substrate, not L0; the
     * L0 render-null stub is demoted to the documented NO-SUBSTRATE FALLBACK. This descriptor records
     * that the product owns the boundary identity but defers it to the substrate.
     */
    public static final String RENDER_NULL_SPRITE_TEXTURE_SUBSTRATE_SUPERSEDED =
            "adr_0030_headless_graphics_substrate_supersedes_l0_render_null_stub";
    /**
     * Sentinel path the null-object texture stub must carry. The in-combat
     * weapon-entity ctor that NPEs on a headless null {@code com.fs.graphics.Sprite.getTexture()}
     * reads the texture's path string and feeds it straight into {@code replaceAll("00\\.png","")}
     * (a barrel-animation sprite-sheet builder); a stub carrying this exact value makes that
     * replace yield {@code ""} and the format loop benign, while a null path would only move the
     * NPE. Descriptor-grade match for the agent bypass {@code com.fs.graphics.Object(0,0,"00.png")}.
     */
    public static final String RENDER_NULL_SPRITE_TEXTURE_STUB_PATH = "00.png";
    public static final String SCRIPTSTORE_CONTENT_LOADER_TREATMENT =
            "adr_0012_option_a_headless_scriptstore_content_loader_prime";
    /**
     * Charset the in-memory Janino content loader is constructed with. The proven
     * scout prime builds {@code new org.codehaus.janino.JavaSourceClassLoader(parent, {core},
     * "UTF-8")}; the product owns the same recipe.
     */
    public static final String SCRIPTSTORE_CONTENT_LOADER_CHARSET = "UTF-8";
    public static final String TEXTURED_LINE_LINEAR_KEY = "fs.common/graphics/particleline32ln.png";
    public static final String TEXTURED_LINE_SQUARE_KEY = "fs.common/graphics/particleline32sq.png";

    private final String boundaryLabel;
    private final StaticObjectFieldConfig backgroundSpriteConfig;
    private final CombatReadinessPluginConfig combatReadinessPluginConfig;
    private final HullBuiltinModsConfig hullBuiltinModsConfig;
    private final VariantTagConfig variantTagConfig;
    private final ModManagerConfig modManagerConfig;
    private final TextureConfig textureConfig;
    private final CombatUiConfig combatUiConfig;
    private final DebrisViewportConfig debrisViewportConfig;
    private final CollisionSoundSpecConfig collisionSoundSpecConfig;
    private final RosterInstallConfig rosterInstallConfig;
    private final EngineDeployRosterConfig engineDeployRosterConfig;
    private final List<Object> engineDeployedMembers = new ArrayList<>();
    private final List<Object> engineDeployedShips = new ArrayList<>();
    private final KeyboardKeyDownConfig keyboardKeyDownConfig;
    private final RenderNullTextureConfig renderNullTextureConfig;
    private final boolean graphicsSubstratePresent;
    private final ScriptStoreContentLoaderConfig scriptStoreContentLoaderConfig;
    private final EngineDrivenFireDriver fireDriver;
    private final EngineDrivenManeuverDriver maneuverDriver;
    private final List<RollbackAction> rollbackActions = new ArrayList<>();
    private final List<String> boundaryDescriptors = new ArrayList<>();
    private boolean installed;
    private boolean closed;
    private int installedTreatmentCount;

    private HeadlessCombatContext(Builder b) {
        this.boundaryLabel = b.boundaryLabel == null ? "" : b.boundaryLabel;
        this.backgroundSpriteConfig = b.backgroundSpriteConfig;
        this.combatReadinessPluginConfig = b.combatReadinessPluginConfig;
        this.hullBuiltinModsConfig = b.hullBuiltinModsConfig;
        this.variantTagConfig = b.variantTagConfig;
        this.modManagerConfig = b.modManagerConfig;
        this.textureConfig = b.textureConfig;
        this.combatUiConfig = b.combatUiConfig;
        this.debrisViewportConfig = b.debrisViewportConfig;
        this.collisionSoundSpecConfig = b.collisionSoundSpecConfig;
        this.rosterInstallConfig = b.rosterInstallConfig;
        this.engineDeployRosterConfig = b.engineDeployRosterConfig;
        this.keyboardKeyDownConfig = b.keyboardKeyDownConfig;
        this.renderNullTextureConfig = b.renderNullTextureConfig;
        this.graphicsSubstratePresent = b.graphicsSubstratePresent;
        this.scriptStoreContentLoaderConfig = b.scriptStoreContentLoaderConfig;
        this.fireDriver = b.fireDriver;
        this.maneuverDriver = b.maneuverDriver;
        if (configuredTreatmentCount() == 0) {
            throw new IllegalArgumentException("HeadlessCombatContext must configure at least one treatment");
        }
        collectDescriptors();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Pin the JVM-global {@link java.lang.Math#random()} stream that the engine's
     * combat damage path draws from. Earlier work mapped the real engine's
     * combat-resolution randomness to {@code java.lang.Math.random()} call sites in
     * {@code com.fs.starfarer.combat.entities.Ship.applyDamageInner} and
     * {@code Ship.randomDamage}; the engine never routes combat damage through a
     * seedable per-combat {@link java.util.Random}, so cross-run deterministic replay
     * requires reseeding the shared {@code java.lang.Math$RandomNumberGeneratorHolder
     * .randomNumberGenerator} instance.
     *
     * <p>Fail-closed: reflecting the holder field requires
     * {@code --add-opens java.base/java.lang=ALL-UNNAMED}; without it this throws
     * {@link HeadlessCombatContextException} rather than silently leaving the engine
     * nondeterministic. This is a whole-JVM side effect shared with every other
     * {@code Math.random()} caller and has no clean per-combat scope; treat it as a
     * process-global determinism pin, not a scoped seed. There is deliberately no
     * stateful rollback (the prior global seed is not readable); callers that want to
     * restore entropy use {@link #restoreGlobalCombatDamageRngEntropy()}.
     *
     * @return the boundary descriptor for telemetry.
     */
    public static String pinGlobalCombatDamageRng(long seed) {
        return reseedGlobalMathRandom(seed, "failed to pin java.lang.Math global combat-damage RNG"
                + " (requires --add-opens java.base/java.lang=ALL-UNNAMED)");
    }

    /**
     * Restore non-deterministic entropy to the JVM-global {@code Math.random()} stream
     * after a {@link #pinGlobalCombatDamageRng(long)} pin, so the process does not leave
     * a fixed global seed installed for unrelated subsystems.
     */
    public static void restoreGlobalCombatDamageRngEntropy() {
        long entropy = System.nanoTime() ^ (System.identityHashCode(new Object()) * 0x9E3779B97F4A7C15L);
        reseedGlobalMathRandom(entropy, "failed to restore java.lang.Math global RNG entropy");
    }

    /**
     * Read the JVM-global {@link java.lang.Math#random()} generator's internal 48-bit
     * LCG seed state. Because the global generator is a single shared
     * {@link java.util.Random}, this value is a deterministic function of the start seed
     * and the number of draws consumed: two runs pinned to the same seed that consume the
     * global stream an identical number of times end with an identical state. Comparing
     * end-of-run states across two independent pinned runs therefore tests whether the
     * global combat RNG was consumed identically — distinguishing a residual divergence
     * fed by the global stream (states differ) from one independent of it (states match).
     *
     * <p>Fail-closed: reflecting {@code java.util.Random.seed} requires
     * {@code --add-opens java.base/java.util=ALL-UNNAMED}; without it this throws.
     *
     * @return the global generator's internal seed state.
     */
    public static long readGlobalCombatDamageRngState() {
        try {
            Class<?> holder = Class.forName("java.lang.Math$RandomNumberGeneratorHolder");
            Field field = holder.getDeclaredField("randomNumberGenerator");
            field.setAccessible(true);
            Object rng = field.get(null);
            if (!(rng instanceof java.util.Random)) {
                throw new HeadlessCombatContextException(
                        "java.lang.Math global RNG field did not contain a java.util.Random");
            }
            Field seedField = java.util.Random.class.getDeclaredField("seed");
            seedField.setAccessible(true);
            Object seedObj = seedField.get(rng);
            if (!(seedObj instanceof java.util.concurrent.atomic.AtomicLong)) {
                throw new HeadlessCombatContextException(
                        "java.util.Random.seed was not an AtomicLong");
            }
            return ((java.util.concurrent.atomic.AtomicLong) seedObj).get();
        } catch (HeadlessCombatContextException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HeadlessCombatContextException(
                    "failed to read java.lang.Math global RNG internal state"
                    + " (requires --add-opens java.base/java.util=ALL-UNNAMED)", e);
        }
    }

    private static String reseedGlobalMathRandom(long seed, String failureMessage) {
        try {
            Class<?> holder = Class.forName("java.lang.Math$RandomNumberGeneratorHolder");
            Field field = holder.getDeclaredField("randomNumberGenerator");
            field.setAccessible(true);
            Object rng = field.get(null);
            if (!(rng instanceof java.util.Random)) {
                throw new HeadlessCombatContextException(
                        "java.lang.Math global RNG field did not contain a java.util.Random");
            }
            ((java.util.Random) rng).setSeed(seed);
            return DETERMINISTIC_COMBAT_DAMAGE_RNG_TREATMENT;
        } catch (HeadlessCombatContextException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HeadlessCombatContextException(failureMessage, e);
        }
    }

    public HeadlessCombatContext install() {
        if (installed) {
            throw new IllegalStateException("HeadlessCombatContext is already installed");
        }
        try {
            if (backgroundSpriteConfig != null) {
                seedBackgroundSprite();
            }
            if (combatReadinessPluginConfig != null) {
                seedCombatReadinessPlugin();
            }
            if (hullBuiltinModsConfig != null) {
                suppressHullBuiltinMods();
            }
            if (variantTagConfig != null) {
                seedVariantTag();
            }
            if (modManagerConfig != null) {
                seedModManagerProxy();
            }
            if (textureConfig != null) {
                seedTexturedLineTextureCache();
            }
            if (combatUiConfig != null) {
                seedCombatUiProxy();
            }
            if (debrisViewportConfig != null) {
                seedDebrisViewport();
            }
            if (collisionSoundSpecConfig != null) {
                seedCollisionSoundSpecs();
            }
            if (rosterInstallConfig != null) {
                installCombatRoster();
            }
            if (engineDeployRosterConfig != null) {
                installEngineDeployRoster();
            }
            if (keyboardKeyDownConfig != null) {
                seedKeyboardKeyDownZeroState();
            }
            if (renderNullTextureConfig != null) {
                if (graphicsSubstratePresent) {
                    recordRenderNullSupersededBySubstrate();
                } else {
                    seedRenderNullSpriteTextureStub();
                }
            }
            if (scriptStoreContentLoaderConfig != null) {
                seedScriptStoreContentLoaderPrime();
            }
            if (fireDriver != null) {
                installedTreatmentCount++;
            }
            if (maneuverDriver != null) {
                installedTreatmentCount++;
            }
            installed = true;
            return this;
        } catch (RuntimeException e) {
            try {
                close();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    public boolean installed() {
        return installed && !closed;
    }

    public int installedTreatmentCount() {
        return installedTreatmentCount;
    }

    public List<String> boundaryDescriptors() {
        return Collections.unmodifiableList(boundaryDescriptors);
    }

    public String boundaryLabel() {
        return boundaryLabel;
    }

    public EngineDrivenFireTelemetry engineDrivenFireTelemetry() {
        if (fireDriver == null) {
            return EngineDrivenFireTelemetry.EMPTY;
        }
        return fireDriver.telemetry();
    }

    public CombatStepBoundary engineDrivenDamageStep(CombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new CombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
            }

            @Override
            public boolean inputCouplingBypassed() {
                return delegate.inputCouplingBypassed();
            }
        };
    }

    public TreatedOuterWrapperCombatStepBoundary engineDrivenDamageTreatedOuterWrapperStep(
            TreatedOuterWrapperCombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new TreatedOuterWrapperCombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
            }

            @Override
            public boolean keyboardTreatmentInstalled() {
                return delegate.keyboardTreatmentInstalled();
            }
        };
    }

    /**
     * Like {@link #engineDrivenDamageStep(CombatStepBoundary)} but the per-frame driver
     * issues only the server-authoritative target + {@code FIRE} command, without the
     * artificial force-fire injection. The engine owns whether weapons fire and resolve
     * damage over real bytes (the command-authority probe).
     */
    public CombatStepBoundary engineCommandAuthorityDamageStep(CombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new CombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareCommandAuthorityFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
            }

            @Override
            public boolean inputCouplingBypassed() {
                return delegate.inputCouplingBypassed();
            }
        };
    }

    /**
     * Closed-round-trip variant of {@link #engineCommandAuthorityDamageStep}:
     * the per-frame command-authority {@code FIRE}
     * (no force-fire) is issued <em>only while {@code gate.fireActive()}</em> — i.e.
     * only because a remote client sent a FIRE intent over the socket that the
     * bidirectional publisher read into the gate. With a hold/no intent the gate is
     * inactive, no FIRE is issued, and no beam results, so the effect that crosses
     * back to the client is attributable to the client's own command, not a server
     * script. Each fired tick is counted on the gate for the round-trip receipt.
     */
    public CombatStepBoundary engineClientIntentCommandAuthorityDamageStep(
            CombatStepBoundary delegate, ClientIntentGate gate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(gate, "gate");
        return new CombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                if (gate.fireActive()) {
                    fireDriver.prepareCommandAuthorityFireFrame();
                    gate.recordFireApplied();
                }
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
            }

            @Override
            public boolean inputCouplingBypassed() {
                return delegate.inputCouplingBypassed();
            }
        };
    }

    /**
     * MANEUVER sibling of {@link #engineClientIntentCommandAuthorityDamageStep}
     * (mirrors): the per-frame command-authority
     * movement command (public {@code ShipAPI.giveCommand(ShipCommand.ACCELERATE,
     * null, 0)} on the commanded ship) is issued <em>only while {@code gate.maneuverActive()}</em>
     * — i.e. only because a remote client sent a MANEUVER intent over the socket that the
     * bidirectional publisher read into the gate. With a hold/no intent the gate is inactive,
     * no command is issued, and the commanded ship only drifts, so the motion change that
     * crosses back to the client is attributable to the client's own command, not a server
     * script. No motion field is written directly; the engine's own movement integration
     * applies the acceleration. Each commanded tick is counted on the gate for the receipt.
     */
    public CombatStepBoundary engineClientIntentManeuverAuthorityStep(
            CombatStepBoundary delegate, ClientManeuverGate gate) {
        if (maneuverDriver == null) {
            throw new IllegalStateException("engine-driven maneuver driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(gate, "gate");
        return new CombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                if (gate.maneuverActive()) {
                    maneuverDriver.applyCommandAuthorityManeuver();
                    gate.recordManeuverApplied();
                }
                delegate.step(fixedDt, simTick);
            }

            @Override
            public boolean inputCouplingBypassed() {
                return delegate.inputCouplingBypassed();
            }
        };
    }

    /**
     * Treated-outer-wrapper variant of {@link #engineCommandAuthorityDamageStep}: the
     * server issues the {@code FIRE} command but the engine owns the firing decision; no
     * force-fire injection is applied.
     */
    public TreatedOuterWrapperCombatStepBoundary engineCommandAuthorityDamageTreatedOuterWrapperStep(
            TreatedOuterWrapperCombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new TreatedOuterWrapperCombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareCommandAuthorityFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
            }

            @Override
            public boolean keyboardTreatmentInstalled() {
                return delegate.keyboardTreatmentInstalled();
            }
        };
    }

    /**
     * Autonomous engine-ship-AI variant of {@link #engineDrivenDamageStep}: the per-frame
     * driver attaches a real engine {@code BasicShipAI} to a non-player ship and sets
     * opposing owners (once), then issues NO {@code FIRE} command and NO force-fire
     * injection. The engine's own ship AI decides whether to fire and resolve damage over
     * real bytes (the autonomous-firing probe). {@code forceFireFrames} stays 0 and
     * {@code fireCommandSuccesses} stays 0 in this mode; {@code weaponFiringFrames} reflects
     * the engine AI's own firing.
     */
    public CombatStepBoundary engineAutonomousAiDamageStep(CombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new CombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareAutonomousAiFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
                fireDriver.recordAutonomousFireIntent();
            }

            @Override
            public boolean inputCouplingBypassed() {
                return delegate.inputCouplingBypassed();
            }
        };
    }

    /**
     * Treated-outer-wrapper variant of {@link #engineAutonomousAiDamageStep}: the engine's
     * own ship AI owns the firing decision; no server FIRE command and no force-fire
     * injection are applied.
     */
    public TreatedOuterWrapperCombatStepBoundary engineAutonomousAiDamageTreatedOuterWrapperStep(
            TreatedOuterWrapperCombatStepBoundary delegate) {
        if (fireDriver == null) {
            throw new IllegalStateException("engine-driven fire driver is not configured");
        }
        Objects.requireNonNull(delegate, "delegate");
        return new TreatedOuterWrapperCombatStepBoundary() {
            @Override
            public CombatWorldState worldState() {
                return delegate.worldState();
            }

            @Override
            public float effectiveStepAmount(float fixedDt) {
                return delegate.effectiveStepAmount(fixedDt);
            }

            @Override
            public void step(float fixedDt, long simTick) {
                fireDriver.prepareAutonomousAiFireFrame();
                delegate.step(fixedDt, simTick);
                fireDriver.recordWeaponFiringFrame();
                fireDriver.recordAutonomousFireIntent();
            }

            @Override
            public boolean keyboardTreatmentInstalled() {
                return delegate.keyboardTreatmentInstalled();
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        for (int i = rollbackActions.size() - 1; i >= 0; i--) {
            RollbackAction action = rollbackActions.get(i);
            try {
                action.restore();
            } catch (RuntimeException e) {
                if (failure == null) {
                    String detail = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
                    failure = new HeadlessCombatContextException(
                            "headless combat context rollback failed: " + detail,
                            e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        rollbackActions.clear();
        closed = true;
        installed = false;
        if (failure != null) {
            throw failure;
        }
    }

    private int configuredTreatmentCount() {
        int count = 0;
        if (backgroundSpriteConfig != null) {
            count++;
        }
        if (combatReadinessPluginConfig != null) {
            count++;
        }
        if (hullBuiltinModsConfig != null) {
            count++;
        }
        if (variantTagConfig != null) {
            count++;
        }
        if (modManagerConfig != null) {
            count++;
        }
        if (textureConfig != null) {
            count++;
        }
        if (combatUiConfig != null) {
            count++;
        }
        if (debrisViewportConfig != null) {
            count++;
        }
        if (collisionSoundSpecConfig != null) {
            count++;
        }
        if (rosterInstallConfig != null) {
            count++;
        }
        if (engineDeployRosterConfig != null) {
            count++;
        }
        if (keyboardKeyDownConfig != null) {
            count++;
        }
        if (renderNullTextureConfig != null) {
            count++;
        }
        if (scriptStoreContentLoaderConfig != null) {
            count++;
        }
        if (fireDriver != null) {
            count++;
        }
        if (maneuverDriver != null) {
            count++;
        }
        return count;
    }

    private void collectDescriptors() {
        if (backgroundSpriteConfig != null) {
            boundaryDescriptors.add(BACKGROUND_SPRITE_TREATMENT + " "
                    + backgroundSpriteConfig.field.getDeclaringClass().getName()
                    + "." + backgroundSpriteConfig.field.getName()
                    + ":" + typeDescriptor(backgroundSpriteConfig.field.getType()));
        }
        if (combatReadinessPluginConfig != null) {
            boundaryDescriptors.add(COMBAT_READINESS_PLUGIN_TREATMENT + " "
                    + combatReadinessPluginConfig.settingsClass.getName()
                    + "." + combatReadinessPluginConfig.pluginMapField.getName()
                    + " key=" + combatReadinessPluginConfig.pluginKey
                    + " + " + combatReadinessPluginConfig.cacheField.getName()
                    + ":" + typeDescriptor(combatReadinessPluginConfig.combatReadinessPluginInterface)
                    + " + " + descriptor(combatReadinessPluginConfig.getter));
        }
        if (hullBuiltinModsConfig != null) {
            boundaryDescriptors.add(HULL_BUILTIN_MODS_TREATMENT + " "
                    + hullBuiltinModsConfig.hullSpec.getClass().getName()
                    + "." + hullBuiltinModsConfig.getBuiltInMods.getName()
                    + descriptor(hullBuiltinModsConfig.getBuiltInMods));
        }
        if (variantTagConfig != null) {
            boundaryDescriptors.add(VARIANT_FX_DRONE_TAG_TREATMENT + " "
                    + variantTagConfig.variantSpec.getClass().getName()
                    + "." + variantTagConfig.addTag.getName()
                    + descriptor(variantTagConfig.addTag)
                    + " tag=" + variantTagConfig.tag
                    + " + " + descriptor(variantTagConfig.hasTag));
        }
        if (modManagerConfig != null) {
            boundaryDescriptors.add(MOD_PLUGIN_PROXY_TREATMENT + " "
                    + modManagerConfig.modManagerClass.getName()
                    + "." + modManagerConfig.pluginsField.getName()
                    + " + " + descriptor(modManagerConfig.getEnabledModPlugins));
        }
        if (textureConfig != null) {
            boundaryDescriptors.add(TEXTURED_LINE_TEXTURE_TREATMENT + " "
                    + textureConfig.textureRegistryClass.getName()
                    + "." + textureConfig.textureMapGetter.getName()
                    + descriptor(textureConfig.textureMapGetter)
                    + " keys=" + TEXTURED_LINE_LINEAR_KEY + "," + TEXTURED_LINE_SQUARE_KEY);
        }
        if (combatUiConfig != null) {
            boundaryDescriptors.add(COMBAT_UI_PROXY_TREATMENT + " "
                    + combatUiConfig.setCombatUi.getDeclaringClass().getName()
                    + "." + combatUiConfig.setCombatUi.getName()
                    + descriptor(combatUiConfig.setCombatUi));
        }
        if (debrisViewportConfig != null) {
            boundaryDescriptors.add(DEBRIS_VIEWPORT_TREATMENT + " "
                    + debrisViewportConfig.debrisSystemClass.getName()
                    + ".<unique-instance-field:"
                    + debrisViewportConfig.viewportClass.getName() + ">");
        }
        if (collisionSoundSpecConfig != null) {
            boundaryDescriptors.add(COLLISION_SOUND_SPEC_TREATMENT + " "
                    + collisionSoundSpecConfig.soundSpecBootstrapClass.getName()
                    + "." + collisionSoundSpecConfig.initializeSoundSpecs.getName()
                    + descriptor(collisionSoundSpecConfig.initializeSoundSpecs)
                    + " -> " + collisionSoundSpecConfig.collisionShipsSpecField.getName()
                    + ":" + typeDescriptor(collisionSoundSpecConfig.soundSpecClass));
        }
        if (rosterInstallConfig != null) {
            boundaryDescriptors.add(ROSTER_INSTALL_TREATMENT + " "
                    + rosterInstallConfig.setObjects.getDeclaringClass().getName()
                    + "." + rosterInstallConfig.setObjects.getName()
                    + descriptor(rosterInstallConfig.setObjects)
                    + " + " + rosterInstallConfig.setFleetManagers.getName()
                    + descriptor(rosterInstallConfig.setFleetManagers)
                    + " + " + rosterInstallConfig.playerShipField.getName()
                    + ":" + typeDescriptor(rosterInstallConfig.playerShipField.getType())
                    + " + " + rosterInstallConfig.repositoryAdd.getDeclaringClass().getName()
                    + "." + rosterInstallConfig.repositoryAdd.getName()
                    + descriptor(rosterInstallConfig.repositoryAdd)
                    + " + " + rosterInstallConfig.setWasRemoved.getDeclaringClass().getName()
                    + "." + rosterInstallConfig.setWasRemoved.getName()
                    + descriptor(rosterInstallConfig.setWasRemoved));
        }
        if (engineDeployRosterConfig != null) {
            boundaryDescriptors.add(ENGINE_DEPLOY_ROSTER_TREATMENT + " "
                    + engineDeployRosterConfig.spawnFleetMember.getDeclaringClass().getName()
                    + "." + engineDeployRosterConfig.spawnFleetMember.getName()
                    + descriptor(engineDeployRosterConfig.spawnFleetMember)
                    + " + " + engineDeployRosterConfig.fleetMemberCtor.getDeclaringClass().getName()
                    + ".<init>(" + engineDeployRosterConfig.fleetMemberCtor.getParameterCount() + ")"
                    + " + " + engineDeployRosterConfig.getFleetManager.getName()
                    + descriptor(engineDeployRosterConfig.getFleetManager)
                    + " + " + engineDeployRosterConfig.setPreDeploy.getName()
                    + descriptor(engineDeployRosterConfig.setPreDeploy)
                    + " ships=" + engineDeployRosterConfig.specs.size());
        }
        if (keyboardKeyDownConfig != null) {
            boundaryDescriptors.add(KEYBOARD_KEYDOWN_TREATMENT + " "
                    + keyboardKeyDownConfig.keyboardClass.getName()
                    + "." + keyboardKeyDownConfig.isKeyDown.getName()
                    + descriptor(keyboardKeyDownConfig.isKeyDown)
                    + " + " + keyboardKeyDownConfig.createdField.getName()
                    + ":Z + " + keyboardKeyDownConfig.keyDownBufferField.getName()
                    + ":" + typeDescriptor(keyboardKeyDownConfig.keyDownBufferField.getType())
                    + " keys=" + keyCodesDescriptor(keyboardKeyDownConfig.keyCodes));
        }
        if (renderNullTextureConfig != null) {
            RenderNullTextureConfig c = renderNullTextureConfig;
            boundaryDescriptors.add(RENDER_NULL_SPRITE_TEXTURE_TREATMENT + " "
                    + c.spriteClass.getName() + "." + c.getTexture.getName() + descriptor(c.getTexture)
                    + " -> stub " + c.textureClass.getName() + ".<init>"
                    + constructorDescriptor(c.stubConstructor)
                    + " path=" + RENDER_NULL_SPRITE_TEXTURE_STUB_PATH
                    + " pathGetter=" + c.textureClass.getName() + "." + c.texturePathGetter.getName()
                    + descriptor(c.texturePathGetter));
        }
        if (scriptStoreContentLoaderConfig != null) {
            ScriptStoreContentLoaderConfig c = scriptStoreContentLoaderConfig;
            boundaryDescriptors.add(SCRIPTSTORE_CONTENT_LOADER_TREATMENT + " "
                    + c.scriptStoreClass.getName() + "." + c.janinoLoaderField.getName()
                    + ":" + typeDescriptor(c.javaSourceClassLoaderClass)
                    + " <- new " + c.javaSourceClassLoaderClass.getName() + ".<init>"
                    + constructorDescriptor(c.javaSourceClassLoaderConstructor)
                    + " sourceRoots=" + c.sourceRoots.length
                    + " charset=" + c.charset);
        }
        if (fireDriver != null) {
            boundaryDescriptors.add(FORCE_FIRE_DRIVER_TREATMENT + " "
                    + fireDriver.shipGiveCommand.getDeclaringClass().getName()
                    + "." + fireDriver.shipGiveCommand.getName()
                    + descriptor(fireDriver.shipGiveCommand)
                    + " + WeaponAPI.setForceFireOneFrame(Z)V");
        }
        if (maneuverDriver != null) {
            boundaryDescriptors.add(COMMAND_AUTHORITY_MANEUVER_DRIVER_TREATMENT + " "
                    + maneuverDriver.shipGiveCommand.getDeclaringClass().getName()
                    + "." + maneuverDriver.shipGiveCommand.getName()
                    + descriptor(maneuverDriver.shipGiveCommand)
                    + " <- ShipCommand.ACCELERATE");
        }
    }

    private void seedBackgroundSprite() {
        StaticObjectFieldConfig c = backgroundSpriteConfig;
        Object prior = getField(c.field, null, "background sprite field");
        if (prior != null) {
            throw new HeadlessCombatContextException("background sprite field was already seeded");
        }
        setField(c.field, null, c.seedValue, "background sprite field.seed");
        Object readback = getField(c.field, null, "background sprite field.readback");
        if (readback != c.seedValue) {
            throw new HeadlessCombatContextException("background sprite seed readback mismatch");
        }
        rollbackActions.add(() -> {
            setField(c.field, null, prior, "background sprite field.restore");
            Object restored = getField(c.field, null, "background sprite field.restore.readback");
            if (restored != prior) {
                throw new HeadlessCombatContextException("background sprite rollback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    /**
     * Recognize and validate the null {@code com.fs.graphics.Sprite.getTexture()} render-null
     * boundary as a product-owned treatment. The actual headless bypass is a
     * load-time {@code ClassFileTransformer} in the trace agent
     * ({@code cc.starsector.trace.SpriteTextureStubTransformer}) that rewrites
     * {@code getTexture()} (descriptor {@code ()Lcom/fs/graphics/Object;}) to return a GL-free
     * null-object stub {@code new com.fs.graphics.Object(0,0,"00.png")} instead of null — a
     * load-time rewrite the product layer cannot perform reflectively. What the product CAN and
     * should own is the descriptor-grade boundary identity and the stub recipe the bypass depends
     * on. This treatment does that without mutating any engine state: it constructs the canonical
     * stub via the real {@code com.fs.graphics.Object(int,int,String)} ctor — proving that
     * construction is GL-free headless ({@code glBindTexture} lives only
     * in the unused {@code bind()} methods) — and pins that the engine's path getter the NPE site
     * reads returns the {@link #RENDER_NULL_SPRITE_TEXTURE_STUB_PATH} sentinel. Fail-closed on any
     * mismatch; no rollback (no shared state is touched), so the boundary identity is recorded in
     * {@link #boundaryDescriptors()} for telemetry.
     */
    private void seedRenderNullSpriteTextureStub() {
        RenderNullTextureConfig c = renderNullTextureConfig;
        Object stub = c.newStub();
        if (!c.textureClass.isInstance(stub)) {
            throw new HeadlessCombatContextException(
                    "render-null texture stub was not an instance of " + c.textureClass.getName());
        }
        Object path = invoke(c.texturePathGetter, stub, "render-null texture stub.pathGetter");
        if (!RENDER_NULL_SPRITE_TEXTURE_STUB_PATH.equals(path)) {
            throw new HeadlessCombatContextException(
                    "render-null texture stub path mismatch: expected '"
                            + RENDER_NULL_SPRITE_TEXTURE_STUB_PATH + "' but got '" + path + "'");
        }
        installedTreatmentCount++;
    }

    /**
     * The headless graphics substrate is present, so the
     * product spawn path does <em>not</em> depend on the L0 {@code SpriteTextureStubTransformer}. The
     * substrate runs the engine's own {@code ResourceLoaderState.init(Map)}, which populates
     * real {@code com.fs.graphics.Object} textures, so {@code Sprite.getTexture()} is non-null and a
     * system-bearing ship builds with no stub (proven end-to-end). Rather than validate
     * the now-redundant L0 stub recipe, record that the substrate supersedes it (the product still owns
     * the descriptor-grade boundary identity via {@link #collectDescriptors()} and
     * {@link #newRenderNullTextureStub()}, kept only as the no-substrate fallback). No engine state is
     * touched and nothing is rolled back; this is a telemetry record, not an installed treatment.
     */
    private void recordRenderNullSupersededBySubstrate() {
        RenderNullTextureConfig c = renderNullTextureConfig;
        boundaryDescriptors.add(RENDER_NULL_SPRITE_TEXTURE_SUBSTRATE_SUPERSEDED + " "
                + c.spriteClass.getName() + "." + c.getTexture.getName() + descriptor(c.getTexture)
                + " (substrate supplies real textures; L0 stub demoted to no-substrate fallback)");
    }

    /**
     * Whether this context was built with the headless graphics substrate declared present. When
     * {@code true}, {@link #install()} skips the L0 render-null treatment (the substrate supplies real
     * textures) and records {@link #RENDER_NULL_SPRITE_TEXTURE_SUBSTRATE_SUPERSEDED} instead.
     */
    public boolean graphicsSubstratePresent() {
        return graphicsSubstratePresent;
    }

    /**
     * Construct a fresh product-owned GL-free null-object texture stub
     * ({@code com.fs.graphics.Object(0,0,"00.png")}) from the configured render-null treatment.
     * Lets a consumer apply the boundary reflectively (e.g. seed a null {@code Sprite.texture}
     * field) instead of via the agent's load-time transform; both yield a non-null
     * {@code getTexture()} carrying the sentinel path. The proven headless path remains the agent
     * transform — this is the same recipe, product-owned.
     *
     * @throws IllegalStateException if the render-null treatment was not configured.
     */
    public Object newRenderNullTextureStub() {
        if (renderNullTextureConfig == null) {
            throw new IllegalStateException("render-null sprite texture treatment is not configured");
        }
        return renderNullTextureConfig.newStub();
    }

    /**
     * Prime the headless ScriptStore content-loader so {@code Ship.<init>} can resolve hullmod
     * <em>effect</em> classes. Unlike the render-null treatment — a load-time
     * agent transform the product cannot do reflectively — this boundary <em>is</em> a reflective
     * live-state mutation, so the product owns the full proven recipe here, not only the descriptor.
     *
     * <p>The engine's {@code com.fs.starfarer.loading.scripts.ScriptStore} resolves every effect
     * class via {@code Class.forName(name, init, <static org.codehaus.janino.JavaSourceClassLoader>)};
     * that static field is {@code null} headless (the init that builds the game's
     * {@code CachingJavaSourceClassLoader} never runs), so every effect — including precompiled ones
     * on the classpath — throws {@code ClassNotFoundException} (the wall). The
     * isolation probe proved a plain in-memory {@code JavaSourceClassLoader(engineParent, {sourceRoot},
     * "UTF-8")} compiles source effects and delegates precompiled effects with no display/audio/input/
     * native init. This treatment installs exactly that loader into the static field and restores the
     * prior value on rollback.
     *
     * <p>Descriptor-grade and fail-closed: the field is pinned <em>by type</em>
     * ({@code org.codehaus.janino.JavaSourceClassLoader} — its field name is non-ASCII), the loader
     * is constructed from the field's own resolved ctor (so the janino {@code Class} identities match the
     * scout's child loader), and the prior field value must be {@code null} (the proven headless
     * invariant) or this refuses to overwrite. In-memory compile only — no on-disk cache is written into
     * the install.
     */
    private void seedScriptStoreContentLoaderPrime() {
        ScriptStoreContentLoaderConfig c = scriptStoreContentLoaderConfig;
        Object prior = getField(c.janinoLoaderField, null, "scriptstore content-loader field");
        if (prior != null) {
            throw new HeadlessCombatContextException(
                    "scriptstore content-loader field was already primed (expected null headless)");
        }
        Object loaderInstance = c.newContentLoader();
        if (!c.javaSourceClassLoaderClass.isInstance(loaderInstance)) {
            throw new HeadlessCombatContextException(
                    "scriptstore content-loader was not an instance of "
                            + c.javaSourceClassLoaderClass.getName());
        }
        setField(c.janinoLoaderField, null, loaderInstance, "scriptstore content-loader field.prime");
        Object readback = getField(c.janinoLoaderField, null, "scriptstore content-loader field.prime.readback");
        if (readback != loaderInstance) {
            throw new HeadlessCombatContextException("scriptstore content-loader prime readback mismatch");
        }
        rollbackActions.add(() -> {
            setField(c.janinoLoaderField, null, prior, "scriptstore content-loader field.restore");
            Object restored =
                    getField(c.janinoLoaderField, null, "scriptstore content-loader field.restore.readback");
            if (restored != prior) {
                throw new HeadlessCombatContextException("scriptstore content-loader rollback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    /**
     * Construct a fresh product-owned in-memory Janino content loader from the configured ScriptStore
     * content-loader treatment, without installing it into the static field. Lets a consumer apply the
     * boundary itself; the proven headless path is {@link #seedScriptStoreContentLoaderPrime()}
     * installing this loader into the static field for the run.
     *
     * @throws IllegalStateException if the ScriptStore content-loader treatment was not configured.
     */
    public Object newScriptStoreContentLoader() {
        if (scriptStoreContentLoaderConfig == null) {
            throw new IllegalStateException("scriptstore content-loader treatment is not configured");
        }
        return scriptStoreContentLoaderConfig.newContentLoader();
    }

    private void seedKeyboardKeyDownZeroState() {
        KeyboardKeyDownConfig c = keyboardKeyDownConfig;
        Object lock = getField(c.globalLockField, null, "keyboard global lock");
        if (lock == null) {
            throw new HeadlessCombatContextException("keyboard global lock was null");
        }
        synchronized (lock) {
            Object bufferObject = getField(c.keyDownBufferField, null, "keyboard keyDownBuffer");
            if (!(bufferObject instanceof ByteBuffer)) {
                throw new HeadlessCombatContextException("keyboard keyDownBuffer field did not contain a ByteBuffer");
            }
            ByteBuffer buffer = (ByteBuffer) bufferObject;
            if (buffer.capacity() == 0) {
                throw new HeadlessCombatContextException("keyboard keyDownBuffer was empty");
            }
            boolean priorCreated =
                    ((Boolean) getField(c.createdField, null, "keyboard created")).booleanValue();
            byte[] priorBytes = snapshot(buffer);

            zero(buffer);
            setField(c.createdField, null, Boolean.TRUE, "keyboard created.seed");
            boolean created =
                    ((Boolean) getField(c.createdField, null, "keyboard created.seed.readback")).booleanValue();
            if (!created || anyNonZero(buffer)) {
                throw new HeadlessCombatContextException("keyboard keyDown zero-state readback mismatch");
            }
            assertKeyboardKeysFalse(c, "keyboard isKeyDown seed readback");

            rollbackActions.add(() -> {
                Object rollbackLock = getField(c.globalLockField, null, "keyboard global lock.restore");
                if (rollbackLock == null) {
                    throw new HeadlessCombatContextException("keyboard global lock rollback target was null");
                }
                synchronized (rollbackLock) {
                    Object currentBufferObject =
                            getField(c.keyDownBufferField, null, "keyboard keyDownBuffer.restore.current");
                    if (!(currentBufferObject instanceof ByteBuffer)) {
                        throw new HeadlessCombatContextException(
                                "keyboard keyDownBuffer rollback target was not a ByteBuffer");
                    }
                    ByteBuffer currentBuffer = (ByteBuffer) currentBufferObject;
                    restore(currentBuffer, priorBytes);
                    setField(c.createdField, null, Boolean.valueOf(priorCreated), "keyboard created.restore");
                    boolean restoredCreated =
                            ((Boolean) getField(c.createdField, null, "keyboard created.restore.readback"))
                                    .booleanValue();
                    if (restoredCreated != priorCreated || !matches(currentBuffer, priorBytes)) {
                        throw new HeadlessCombatContextException("keyboard keyDown zero-state rollback mismatch");
                    }
                }
            });
            installedTreatmentCount++;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seedCombatReadinessPlugin() {
        CombatReadinessPluginConfig c = combatReadinessPluginConfig;
        Object mapObject = getField(c.pluginMapField, null, "settings plugin cache map");
        if (!(mapObject instanceof Map)) {
            throw new HeadlessCombatContextException("settings plugin cache field did not contain a Map");
        }
        Map pluginMap = (Map) mapObject;
        Map<?, ?> priorMapContents = new LinkedHashMap<>(pluginMap);
        Object priorCache = getField(c.cacheField, null, "combat readiness plugin cache");

        pluginMap.put(c.pluginKey, c.pluginInstance);
        setField(c.cacheField, null, c.pluginInstance, "combat readiness plugin cache");
        Object mapReadback = pluginMap.get(c.pluginKey);
        Object cacheReadback = getField(c.cacheField, null, "combat readiness plugin cache.readback");
        Object getterReadback = invoke(c.getter, null, "combat readiness plugin getter");
        if (mapReadback != c.pluginInstance
                || cacheReadback != c.pluginInstance
                || getterReadback != c.pluginInstance) {
            throw new HeadlessCombatContextException("combat readiness plugin seed readback mismatch");
        }

        rollbackActions.add(() -> {
            Object currentMapObject = getField(c.pluginMapField, null, "settings plugin cache map.restore.current");
            if (!(currentMapObject instanceof Map)) {
                throw new HeadlessCombatContextException("settings plugin cache rollback target was not a Map");
            }
            Map currentMap = (Map) currentMapObject;
            currentMap.clear();
            currentMap.putAll(priorMapContents);
            setField(c.cacheField, null, priorCache, "combat readiness plugin cache.restore");
            Object restoredMapObject = getField(c.pluginMapField, null, "settings plugin cache map.restore.readback");
            Object restoredCache = getField(c.cacheField, null, "combat readiness plugin cache.restore.readback");
            if (!(restoredMapObject instanceof Map)
                    || !((Map) restoredMapObject).equals(priorMapContents)
                    || restoredCache != priorCache) {
                throw new HeadlessCombatContextException("combat readiness plugin seed rollback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void suppressHullBuiltinMods() {
        HullBuiltinModsConfig c = hullBuiltinModsConfig;
        Object modsObject = invoke(c.getBuiltInMods, c.hullSpec, "hull spec built-in mods");
        if (!(modsObject instanceof List)) {
            throw new HeadlessCombatContextException("hull spec built-in mods getter did not return a List");
        }
        List mods = (List) modsObject;
        ArrayList priorMods = new ArrayList(mods);

        if (!mods.isEmpty()) {
            mods.clear();
        }
        if (!mods.isEmpty()) {
            throw new HeadlessCombatContextException("hull built-in mod suppression readback mismatch");
        }

        rollbackActions.add(() -> {
            Object currentModsObject =
                    invoke(c.getBuiltInMods, c.hullSpec, "hull spec built-in mods.restore.current");
            if (!(currentModsObject instanceof List)) {
                throw new HeadlessCombatContextException(
                        "hull built-in mod suppression rollback target was not a List");
            }
            List currentMods = (List) currentModsObject;
            currentMods.clear();
            currentMods.addAll(priorMods);
            if (!currentMods.equals(priorMods)) {
                throw new HeadlessCombatContextException(
                        "hull built-in mod suppression rollback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    private void seedVariantTag() {
        VariantTagConfig c = variantTagConfig;
        boolean hadTag = ((Boolean) invoke(c.hasTag, c.variantSpec, "variant tag precheck", c.tag))
                .booleanValue();
        if (!hadTag) {
            invoke(c.addTag, c.variantSpec, "variant tag seed", c.tag);
        }
        boolean tagPresent = ((Boolean) invoke(c.hasTag, c.variantSpec, "variant tag readback", c.tag))
                .booleanValue();
        if (!tagPresent) {
            throw new HeadlessCombatContextException("variant tag seed readback mismatch");
        }
        rollbackActions.add(() -> {
            if (!hadTag) {
                invoke(c.removeTag, c.variantSpec, "variant tag restore", c.tag);
            }
            boolean restoredTagPresent =
                    ((Boolean) invoke(c.hasTag, c.variantSpec, "variant tag restore readback", c.tag))
                            .booleanValue();
            if (restoredTagPresent != hadTag) {
                throw new HeadlessCombatContextException("variant tag rollback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void installCombatRoster() {
        RosterInstallConfig c = rosterInstallConfig;
        Object priorObjects = invoke(c.getObjects, c.engine, "CombatEngine.getObjects.prior");
        Object priorFleetManagers = invoke(c.getFleetManagers, c.engine, "CombatEngine.getFleetManagers.prior");
        List priorFleetManagersList = asList(priorFleetManagers, "CombatEngine.getFleetManagers.prior");
        ArrayList priorFleetManagerContents = new ArrayList(priorFleetManagersList);
        Object priorPlayerShip = getField(c.playerShipField, c.engine, "CombatEngine.playerShip.prior");
        boolean[] priorWasRemoved = new boolean[c.ships.size()];
        for (int i = 0; i < c.ships.size(); i++) {
            Object prior = invoke(c.wasRemoved, c.ships.get(i), "BaseEntity.wasRemoved.prior");
            if (!(prior instanceof Boolean)) {
                throw new HeadlessCombatContextException("wasRemoved prior readback was not a Boolean");
            }
            priorWasRemoved[i] = ((Boolean) prior).booleanValue();
        }
        Object priorRepositoryListObject =
                invoke(c.repositoryGetList, c.repository, "ObjectRepository.getList.prior", c.shipClass);
        if (!(priorRepositoryListObject instanceof List)) {
            throw new HeadlessCombatContextException("roster repository getList did not return a List");
        }
        List seededRepositoryList = (List) priorRepositoryListObject;
        ArrayList priorRepositoryContents = new ArrayList(seededRepositoryList);
        if (!priorRepositoryContents.isEmpty()) {
            throw new HeadlessCombatContextException("roster install requires a fresh empty repository");
        }
        ArrayList<Object> emptyFleetManagers = new ArrayList<>();

        rollbackActions.add(() -> {
            Object currentRepositoryListObject =
                    invoke(c.repositoryGetList, c.repository, "ObjectRepository.getList.restore.current", c.shipClass);
            if (!(currentRepositoryListObject instanceof List)) {
                throw new HeadlessCombatContextException("roster repository rollback target was not a List");
            }
            List currentRepositoryList = (List) currentRepositoryListObject;
            currentRepositoryList.clear();
            currentRepositoryList.addAll(priorRepositoryContents);
            setField(c.playerShipField, c.engine, priorPlayerShip, "CombatEngine.playerShip.restore");
            invoke(c.setFleetManagers, c.engine, "CombatEngine.setFleetManagers.restore", priorFleetManagers);
            invoke(c.setObjects, c.engine, "CombatEngine.setObjects.restore", priorObjects);
            Object restoredObjects = invoke(c.getObjects, c.engine, "CombatEngine.getObjects.restore.readback");
            Object restoredFleetManagers =
                    invoke(c.getFleetManagers, c.engine, "CombatEngine.getFleetManagers.restore.readback");
            Object restoredPlayerShip =
                    getField(c.playerShipField, c.engine, "CombatEngine.playerShip.restore.readback");
            Object restoredRepositoryListObject =
                    invoke(c.repositoryGetList, c.repository, "ObjectRepository.getList.restore.readback", c.shipClass);
            boolean shipsWasRemovedRestored = true;
            for (int i = 0; i < c.ships.size(); i++) {
                invoke(c.setWasRemoved, c.ships.get(i), "BaseEntity.setWasRemoved.restore",
                        Boolean.valueOf(priorWasRemoved[i]));
                Object restoredFlag =
                        invoke(c.wasRemoved, c.ships.get(i), "BaseEntity.wasRemoved.restore.readback");
                if (!(restoredFlag instanceof Boolean)
                        || ((Boolean) restoredFlag).booleanValue() != priorWasRemoved[i]) {
                    shipsWasRemovedRestored = false;
                }
            }
            boolean repositoryRestored = restoredRepositoryListObject instanceof List
                    && ((List) restoredRepositoryListObject).equals(priorRepositoryContents);
            boolean objectsRestored = restoredObjects == priorObjects;
            boolean fleetManagersRestored = restoredFleetManagers instanceof List
                    && ((List) restoredFleetManagers).equals(priorFleetManagerContents);
            boolean playerShipRestored = restoredPlayerShip == priorPlayerShip;
            if (!repositoryRestored || !objectsRestored || !fleetManagersRestored || !playerShipRestored
                    || !shipsWasRemovedRestored) {
                throw new HeadlessCombatContextException(
                        "combat roster install rollback mismatch"
                                + " repository=" + repositoryRestored
                                + " objects=" + objectsRestored
                                + " fleetManagers=" + fleetManagersRestored
                                + " playerShip=" + playerShipRestored
                                + " shipsWasRemoved=" + shipsWasRemovedRestored);
            }
        });

        boolean added = true;
        for (Object ship : c.ships) {
            Object addResult = invoke(c.repositoryAdd, c.repository, "ObjectRepository.add", ship);
            if (!(addResult instanceof Boolean) || !((Boolean) addResult).booleanValue()) {
                added = false;
            }
            // The drain's second write: without it the ship stays wasRemoved=true from
            // BaseEntity construction and is engine-dead to isShipAlive.
            invoke(c.setWasRemoved, ship, "BaseEntity.setWasRemoved", Boolean.FALSE);
        }
        invoke(c.setObjects, c.engine, "CombatEngine.setObjects", c.repository);
        invoke(c.setFleetManagers, c.engine, "CombatEngine.setFleetManagers", emptyFleetManagers);
        setField(c.playerShipField, c.engine, c.ships.get(0), "CombatEngine.playerShip");

        Object objectReadback = invoke(c.getObjects, c.engine, "CombatEngine.getObjects.readback");
        List shipListReadback = asList(
                invoke(c.repositoryGetList, objectReadback, "ObjectRepository.getList.readback", c.shipClass),
                "ObjectRepository.getList.readback");
        List fleetManagersReadback = asList(
                invoke(c.getFleetManagers, c.engine, "CombatEngine.getFleetManagers.readback"),
                "CombatEngine.getFleetManagers.readback");
        Object playerShipReadback =
                invoke(c.getPlayerShip, c.engine, "CombatEngine.getPlayerShip.readback");
        boolean shipListMatches = shipListReadback.size() == c.ships.size();
        if (shipListMatches) {
            for (int i = 0; i < c.ships.size(); i++) {
                if (shipListReadback.get(i) != c.ships.get(i)) {
                    shipListMatches = false;
                    break;
                }
            }
        }
        boolean shipsInPlay = true;
        for (Object ship : c.ships) {
            Object flag = invoke(c.wasRemoved, ship, "BaseEntity.wasRemoved.readback");
            if (!(flag instanceof Boolean) || ((Boolean) flag).booleanValue()) {
                shipsInPlay = false;
            }
        }
        if (!added
                || objectReadback != c.repository
                || !shipListMatches
                || !fleetManagersReadback.isEmpty()
                || playerShipReadback != c.ships.get(0)
                || !shipsInPlay) {
            throw new HeadlessCombatContextException(
                    "combat roster install readback mismatch (shipsInPlay=" + shipsInPlay + ")");
        }
        installedTreatmentCount++;
    }

    // The engine-own deploy lane. Real
    // FleetMembers spawned through the engine's own spawnFleetMember -> deploy ->
    // instantiateForCombat -> addObject path so the deployment ledgers the end-of-combat
    // judge reads are populated for real. The spawned ships ride the public addObject
    // pending-add queue until the next frame's drain performs the engine's own two writes.
    private void installEngineDeployRoster() {
        EngineDeployRosterConfig c = engineDeployRosterConfig;
        Object priorObjects = invoke(c.getObjects, c.engine, "CombatEngine.getObjects.prior");
        Object priorFleetManagers = invoke(c.getFleetManagers, c.engine, "CombatEngine.getFleetManagers.prior");
        List priorFleetManagersList = asList(priorFleetManagers, "CombatEngine.getFleetManagers.prior");
        ArrayList priorFleetManagerContents = new ArrayList(priorFleetManagersList);
        Object priorPlayerShip = getField(c.playerShipField, c.engine, "CombatEngine.playerShip.prior");
        Object priorPreDeployObject = invoke(c.isPreDeploy, c.engine, "CombatEngine.isPreDeploy.prior");
        if (!(priorPreDeployObject instanceof Boolean)) {
            throw new HeadlessCombatContextException("isPreDeploy prior readback was not a Boolean");
        }
        boolean priorPreDeploy = ((Boolean) priorPreDeployObject).booleanValue();
        Object freshListObject =
                invoke(c.repositoryGetList, c.repository, "ObjectRepository.getList.prior", c.shipClass);
        if (!(freshListObject instanceof List) || !((List) freshListObject).isEmpty()) {
            throw new HeadlessCombatContextException(
                    "engine-deploy roster install requires a fresh empty repository");
        }

        // addFleet registers each manager as an engine listener, and the manager's transfer
        // manager (combat.B) self-registers in its own ctor: both must come back out at
        // rollback via the engine's public removeListener or later probes notify stale
        // managers. Populated during install, drained LIFO here.
        ArrayList<Object> addedEngineListeners = new ArrayList<>();
        rollbackActions.add(() -> {
            invoke(c.setPreDeploy, c.engine, "CombatEngine.setPreDeploy.restore",
                    Boolean.valueOf(priorPreDeploy));
            setField(c.playerShipField, c.engine, priorPlayerShip, "CombatEngine.playerShip.restore");
            for (int i = addedEngineListeners.size() - 1; i >= 0; i--) {
                invoke(c.removeListener, c.engine, "CombatEngine.removeListener.restore",
                        addedEngineListeners.get(i));
            }
            invoke(c.setFleetManagers, c.engine, "CombatEngine.setFleetManagers.restore", priorFleetManagers);
            invoke(c.setObjects, c.engine, "CombatEngine.setObjects.restore", priorObjects);
            Object restoredObjects = invoke(c.getObjects, c.engine, "CombatEngine.getObjects.restore.readback");
            Object restoredFleetManagers =
                    invoke(c.getFleetManagers, c.engine, "CombatEngine.getFleetManagers.restore.readback");
            Object restoredPlayerShip =
                    getField(c.playerShipField, c.engine, "CombatEngine.playerShip.restore.readback");
            Object restoredPreDeploy =
                    invoke(c.isPreDeploy, c.engine, "CombatEngine.isPreDeploy.restore.readback");
            boolean objectsRestored = restoredObjects == priorObjects;
            boolean fleetManagersRestored = restoredFleetManagers instanceof List
                    && ((List) restoredFleetManagers).equals(priorFleetManagerContents);
            boolean playerShipRestored = restoredPlayerShip == priorPlayerShip;
            boolean preDeployRestored = restoredPreDeploy instanceof Boolean
                    && ((Boolean) restoredPreDeploy).booleanValue() == priorPreDeploy;
            if (!objectsRestored || !fleetManagersRestored || !playerShipRestored || !preDeployRestored) {
                throw new HeadlessCombatContextException(
                        "engine-deploy roster rollback mismatch"
                                + " objects=" + objectsRestored
                                + " fleetManagers=" + fleetManagersRestored
                                + " playerShip=" + playerShipRestored
                                + " preDeploy=" + preDeployRestored);
            }
        });

        // The deploy path's registrar hands each spawned ship to the public addObject queue,
        // whose drain writes into the engine's CURRENT repository: install the fresh one first
        // so the roster lands there and rollback restores the prior repository wholesale.
        invoke(c.setObjects, c.engine, "CombatEngine.setObjects", c.repository);

        // Note: getFleetManager(int)'s mint-on-miss uses the 1-arg
        // CombatFleetManager ctor, which chains the 3-arg ctor with skip=true and never mints
        // the transfer manager (combat.B) - the manager's advance() then NPEs on frame 0 (W5).
        // The engine's OWN per-side registration is addFleet(sideSpec, goal): the 2-arg ctor
        // (skip=false) mints new B(side), the manager lands on the engine's list, and both the
        // manager and B become engine listeners. Install a fresh project-owned list first so
        // addFleet appends there and rollback restores the prior list object untouched.
        invoke(c.setFleetManagers, c.engine, "CombatEngine.setFleetManagers.fresh",
                new ArrayList<Object>());
        // Sides to register: an explicit registerSides (the occupancy-gated deploy
        // passes the descriptor's sides so fly-ins find real managers even when a side has zero
        // hulls at install); unset = derive from the specs in encounter order, byte-identical.
        ArrayList<Integer> sidesToRegister = new ArrayList<>();
        if (c.registerSides != null) {
            for (int side : c.registerSides) {
                Integer boxed = Integer.valueOf(side);
                if (!sidesToRegister.contains(boxed)) {
                    sidesToRegister.add(boxed);
                }
            }
        } else {
            for (EngineDeployShipSpec spec : c.specs) {
                Integer boxed = Integer.valueOf(spec.owner);
                if (!sidesToRegister.contains(boxed)) {
                    sidesToRegister.add(boxed);
                }
            }
        }
        LinkedHashMap<Integer, Object> managersBySide = new LinkedHashMap<>();
        for (Integer side : sidesToRegister) {
            Object sideSpec;
            try {
                sideSpec = c.fleetSpecCtor.newInstance(side);
            } catch (ReflectiveOperationException e) {
                Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                        ? e.getCause() : e;
                throw new HeadlessCombatContextException(
                        "side-spec construction failed for side " + side + ": " + cause, cause);
            }
            invoke(c.addFleet, c.engine, "CombatEngine.addFleet", sideSpec, c.fleetGoalAttack);
            Object manager = invoke(c.getFleetManager, c.engine, "CombatEngine.getFleetManager.resolve", side);
            if (manager == null) {
                throw new HeadlessCombatContextException(
                        "getFleetManager(" + side + ") resolved null after addFleet");
            }
            Object managerAgain =
                    invoke(c.getFleetManager, c.engine, "CombatEngine.getFleetManager.readback", side);
            if (managerAgain != manager) {
                throw new HeadlessCombatContextException(
                        "getFleetManager(" + side
                                + ") is not identity-stable after addFleet (mint-on-miss, W4)");
            }
            Object transferManager =
                    invoke(c.getTransferManager, manager, "CombatFleetManager.getTransferManager.readback");
            if (transferManager == null) {
                throw new HeadlessCombatContextException(
                        "addFleet-registered manager for side " + side
                                + " carries a null transfer manager (W5)");
            }
            addedEngineListeners.add(transferManager);
            addedEngineListeners.add(manager);
            managersBySide.put(side, manager);
        }
        // POINT A: the wave side's engine admiral, installed right after
        // addFleet exactly where vanilla does (allyMode=false = the FULL
        // admiral); retreat posture on the engine's persistent BattleCreationContext. The
        // manager exists even with zero hulls at install (S3 registerSides), so an
        // occupancy-gated boot still gets its commander before the first wave lands.
        if (c.waveAdmiral != null) {
            installWaveAdmiral(c, managersBySide);
        }
        // All-skills commander (DECIDED:
        // manager-level side 0, BOTH ends): install BEFORE any spec spawns so every side-0
        // fleetData-less member picks it up inside instantiateForCombat — t=0 roster,
        // fly-ins, and re-deploys alike. No-op when the knob is OFF.
        Object sideZeroManager = managersBySide.get(Integer.valueOf(0));
        if (sideZeroManager != null) {
            AllSkillsAttach.maybeInstallSideZeroCommander(sideZeroManager);
        }
        // The twin of the kit's "allskills own_abs_side=<0|1>" echo.
        // The server's all-skills default lives on ABSOLUTE side 0 only, so the kit keys every
        // appended row's commander pair on its absolute side (never the local manager); the batched
        // WATCH grades the two lines together. One line per boot, off the frame loop.
        System.out.println("engine-deploy allskills own_abs_side=0 knob="
                + (AllSkillsAttach.enabled() ? "on" : "off")
                + " side0_manager=" + (sideZeroManager != null ? "present" : "absent"));

        // Deployment-CR handles. A freshly-constructed FleetMember starts at CR 0.0,
        // and the deploy lane bakes the 0-CR penalty into the ship's stats (
        // instantiateForCombat applies CR via the member's RepairTracker), so the ship AI
        // immediately withdraws — the served ships flew apart to their retreat edges instead of
        // engaging. A measured run showed the member's OWN RepairTracker.setCR(maxCR) to be
        // a no-op on a bare, fleet-less, crew-less FleetMember (getCR stayed 0.0; maxCR was only 0.2),
        // so that pre-deploy lever is abandoned. The faithful fix (the
        // headless ShipAPI CR setters already proven to work) is to lift CR on the DEPLOYED
        // ship right after spawnFleetMember. The write crosses the engine-native seam
        // ({@link CrDeploySeam}): the old setCRAtDeployment/setCurrentCR field
        // pair never ran RepairTracker.applyCRToStats, so the sim fought without cr_effect.
        // Only the getCurrentCR diagnostic read stays resolved here.
        Method shipGetCurrentCR;
        try {
            shipGetCurrentCR = c.shipClass.getMethod("getCurrentCR");
        } catch (NoSuchMethodException e) {
            throw new HeadlessCombatContextException(
                    "could not resolve the deployed-ship CR handles (public ShipAPI): " + e, e);
        }
        // FAITHFUL CREW FIX handles. The decisive root cause: a
        // bare `new FleetMember(...)` carries an EMPTY CrewComposition (getCrew()==0) → getCrewFraction()
        // ==0.0 → canBeDeployedForCombat()==false → FleetMember.instantiateForCombat (run INSIDE
        // spawnFleetMember, before the CR lift below) calls setControlsLocked(true) → CombatTaskManager
        // .getOrdersForFleetMember hands the ship a RETREAT order → the served ships flee (the
        // fly-apart). Stock settings.json noDeployCRPercent=0 makes every CR term
        // always-true, so the five prior CR lifts were structural no-ops; the live gate is
        // getCrewFraction() >= noDeployCrewPercent*0.01 (5%). The faithful fix is to crew the member to
        // its full complement via the engine's OWN CrewCompositionAPI BEFORE spawn — a real deployed ship
        // IS crewed; this drives the engine's own deployability mechanism, not a stat-hack or AI-override.
        // All public API
        // (FleetMemberAPI.getCrewComposition/getMinCrew/..., CrewCompositionAPI.setCrew), identity-clean.
        Class<?> fleetMemberClass = c.fleetMemberCtor.getDeclaringClass();
        Method memberGetCrewComposition;
        Method memberGetMinCrew;
        Method memberGetMaxCrew;
        Method memberGetCrewFraction;
        Method memberCanBeDeployed;
        Method memberIsMothballed;
        Method crewSetCrew;
        Method shipControlsLocked;
        try {
            memberGetCrewComposition = fleetMemberClass.getMethod("getCrewComposition");
            memberGetMinCrew = fleetMemberClass.getMethod("getMinCrew");
            memberGetMaxCrew = fleetMemberClass.getMethod("getMaxCrew");
            memberGetCrewFraction = fleetMemberClass.getMethod("getCrewFraction");
            memberCanBeDeployed = fleetMemberClass.getMethod("canBeDeployedForCombat");
            memberIsMothballed = fleetMemberClass.getMethod("isMothballed");
            crewSetCrew = memberGetCrewComposition.getReturnType().getMethod("setCrew", float.class);
            shipControlsLocked = c.shipClass.getMethod("controlsLocked");
        } catch (NoSuchMethodException e) {
            throw new HeadlessCombatContextException(
                    "could not resolve the FleetMember crew/deployability handles (public API): " + e, e);
        }
        // Default-OFF deploy-time diagnostic, sharing the -Dcc.server.crReadback gate: logs
        // each member's deployability before/after crewing plus the deployed ship's controlsLocked(), so
        // one headless measurement run (NOT a WATCH) confirms both the static inference and the
        // fix. Pure reads except the single faithful crew mutation; off by default.
        final boolean crReadback = cc.starsector.server.runtime.ServerConfig.resolveBoolean(
                "cc.server.crReadback", "CC_SERVER_CR_READBACK", "server.crReadback", false);
        // The combat-ready deployment CR baked onto each deployed ship, set via the engine's own
        // ShipAPI field. Descriptor-driven when a MatchDescriptor is active (the Builder's
        // .engineDeployRosterDeployCr threads the validated MatchDescriptor.deployCr()); otherwise
        // DEFAULT_DEPLOY_CR (0.7) — the no-descriptor default path is byte-unchanged. Rationale +
        // value choice: DEFAULT_DEPLOY_CR javadoc.
        final float deployCr = c.deployCr;

        // STATION deploy carve-outs, resolved ONCE here
        // beside every other handle and null unless this roster actually fields a station.
        boolean anyStationSpec = false;
        for (EngineDeployShipSpec spec : c.specs) {
            if (spec.station) {
                anyStationSpec = true;
                break;
            }
        }
        StationMemberCr stationCr = null;
        if (anyStationSpec) {
            // Station hazard: CombatFleetManager derefs
            // getContext().getFlankDeploymentDistance() UNGUARDED on the spawn path a station
            // takes. Headless survives only on the engine's OWN default BattleCreationContext
            // — assert it exists HERE, at the install seam, rather than
            // discovering it as an NPE inside the engine's spawn.
            Object battleContext;
            try {
                battleContext = c.engine.getClass().getMethod("getContext").invoke(c.engine);
            } catch (ReflectiveOperationException e) {
                throw new HeadlessCombatContextException(
                        "could not read CombatEngine.getContext() before a STATION deploy"
                                + " (the fleet manager derefs it unguarded on every spawn): " + e, e);
            }
            if (battleContext == null) {
                throw new HeadlessCombatContextException(
                        "STATION deploy refused: CombatEngine.getContext() is null, and"
                                + " CombatFleetManager derefs getFlankDeploymentDistance()"
                                + " on it unguarded for every spawn (the engine's own default"
                                + " BattleCreationContext is the only thing"
                                + " that makes this survivable headless)");
            }
            // Modules copy MEMBER CR at build time (measured by probe), so the post-spawn
            // ship-level lift below covers the BASE only — a station whose member deploys at
            // CR 0 mints a cluster of CR-0 modules. Set the member's own RepairTracker CR
            // pre-spawn: the engine's own combat-readiness field, not a stat-hack.
            Method memberGetRepairTracker;
            Method trackerSetCr;
            try {
                memberGetRepairTracker = fleetMemberClass.getMethod("getRepairTracker");
                trackerSetCr = memberGetRepairTracker.getReturnType().getMethod("setCR", float.class);
            } catch (NoSuchMethodException e) {
                throw new HeadlessCombatContextException(
                        "could not resolve the FleetMember RepairTracker CR handles required by"
                                + " a STATION deploy (public API): " + e, e);
            }
            stationCr = new StationMemberCr(memberGetRepairTracker, trackerSetCr, deployCr);
            System.out.println("engine-deploy station_seam specs=" + c.specs.size()
                    + " battle_context=" + battleContext.getClass().getName()
                    + " member_cr=" + deployCr);
        }

        if (c.flyIn == null) {
            // W1: the per-member deployer derefs getContext() UNGUARDED when !isPreDeploy() &&
            // !isStation(); pre-deploy gating via the public setter skips that block entirely.
            // CFM verdict: STATION specs need NO carve-out on this lane — they are
            // W1-exempt by the block's own !isStation() term, and the station path is
            // preDeploy-INSENSITIVE end to end (only 2 isPreDeploy call sites exist engine-wide,
            // one in CombatState and one in CFM, and the station path reads neither; the loc pin +
            // setFixedLocation, module auto-create, executeAdds same-frame,
            // the 10×0.2s warm-up all run under EITHER state). So preDeploy stays TRUE.
            invoke(c.setPreDeploy, c.engine, "CombatEngine.setPreDeploy", Boolean.TRUE);
            try {
                for (EngineDeployShipSpec spec : c.specs) {
                    String[] diagOut = new String[1];
                    Object member = mintCrewedMember(c, spec, memberGetCrewComposition,
                            memberGetMinCrew, memberGetMaxCrew, memberGetCrewFraction,
                            memberCanBeDeployed, memberIsMothballed, crewSetCrew, crReadback,
                            diagOut, stationCr);
                    Object manager = managersBySide.get(Integer.valueOf(spec.owner));
                    Object ship = invoke(c.spawnFleetMember, manager, "CombatFleetManager.spawnFleetMember",
                            member, spec.location, Float.valueOf(spec.facing), Float.valueOf(spec.burnDist));
                    if (ship == null || !c.shipClass.isInstance(ship)) {
                        throw new HeadlessCombatContextException(
                                "spawnFleetMember returned "
                                        + (ship == null ? "null" : ship.getClass().getName())
                                        + " for '" + spec.shipName + "'");
                    }
                    verifyAndTreatDeployedShip(c, spec, member, ship, manager,
                            shipGetCurrentCR, shipControlsLocked, deployCr, crReadback,
                            diagOut[0]);
                }
            } finally {
                invoke(c.setPreDeploy, c.engine, "CombatEngine.setPreDeploy.post-install",
                        Boolean.valueOf(priorPreDeploy));
            }
        } else {
            // Fly-in initial deploy:
            // the engine's OWN batched battle-start lane — per side
            // addToReserves -> setFirstDeployment(true) -> deployAll((mapHeight-standoff)*0.5)
            // -> setFirstDeployment(false) -> getShipFor readback, mirroring CombatState's
            // battle-start driver. Under firstDeployment the engine places side 0 in the southern
            // zone / side 1 northern (deploy(), center -/+ standoff/2), spreads X by
            // rank columns ({-2,2,-1,1,0}x1000 around map-center X), and the O0 mint turns ON
            // travel drive for the context burn — the vanilla fly-in. Spec
            // vec2/facing are engine-chosen here (oo defaults: loc null / burn -1 => context
            // burn). PreDeploy must read FALSE during deployAll: the burn/travel-drive block
            // is skipped under preDeploy — the W1 dodge would turn the fly-in back
            // into a teleport. Its getContext() deref is headless-safe: a never-set context is
            // the engine's own default BattleCreationContext (standoff=6000
            // burn=1.0).
            EngineDeployFlyInConfig f = c.flyIn;
            invoke(c.setPreDeploy, c.engine, "CombatEngine.setPreDeploy.fly-in", Boolean.FALSE);
            try {
                List<Object> mintedMembers = new ArrayList<>();
                List<String> mintedDiags = new ArrayList<>();
                for (EngineDeployShipSpec spec : c.specs) {
                    String[] diagOut = new String[1];
                    Object member = mintCrewedMember(c, spec, memberGetCrewComposition,
                            memberGetMinCrew, memberGetMaxCrew, memberGetCrewFraction,
                            memberCanBeDeployed, memberIsMothballed, crewSetCrew, crReadback,
                            diagOut, stationCr);
                    Object manager = managersBySide.get(Integer.valueOf(spec.owner));
                    // CFM verdict: STATION specs are routed AROUND the
                    // batched lane — vanilla itself spawns stations directly at MAP CENTER
                    // before deployAll ever sees them (the fleet manager's deployStationAndRelatedIfNeeded).
                    // Reserving a station would grid-pin it into a formation row
                    // AND contaminate the batch's row spacing / y-offset for the real fly-in
                    // hulls. Never addToReserves; the direct spawn runs after the deployAll
                    // blocks below.
                    if (!spec.station) {
                        invoke(f.addToReserves, manager, "CombatFleetManager.addToReserves", member);
                    }
                    mintedMembers.add(member);
                    mintedDiags.add(diagOut[0]);
                }
                Object map = invoke(f.getCombatMap, c.engine, "CombatEngine.getCombatMap");
                if (map == null) {
                    throw new HeadlessCombatContextException(
                            "fly-in deploy requires a non-null combat map (install order)");
                }
                float mapHeight =
                        ((Number) invoke(f.mapHeight, map, "CombatMap.height")).floatValue();
                float standoffBefore = ((Number) invoke(
                        f.getStandoffRange, c.engine, "CombatEngine.getStandoffRange")).floatValue();
                // Vanilla's t=0 mission deploy lands at
                // ±(mapHeight−4500)/2 (twin sidelog: hive at (0,5245) on the 15000 map = standoff
                // 10500), so this lane writes mapHeight−4500 UNCONDITIONALLY.
                // The former `standoff <= 0` guard (the battle-start driver's shape) was dead code here:
                // the engine's placeholder context carries the API BattleCreationContext default
                // 6000, which put side 1 at y=+3000 instead of
                // vanilla's +5250 (and fly-in seats at −3000 instead of −5250). Idempotent: the
                // context persists; each install rewrites the value for the LIVE map size. The
                // setter lives on the API BattleCreationContext.
                Object context = invoke(f.getContext, c.engine, "CombatEngine.getContext");
                if (context == null) {
                    throw new HeadlessCombatContextException(
                            "fly-in deploy requires a non-null BattleCreationContext (standoff write)");
                }
                invoke(f.contextSetStandoffRange, context,
                        "BattleCreationContext.setStandoffRange",
                        Float.valueOf(mapHeight - 4500.0f));
                float standoff = ((Number) invoke(f.getStandoffRange, c.engine,
                        "CombatEngine.getStandoffRange.readback")).floatValue();
                float deployArg = (mapHeight - standoff) * 0.5f;
                for (Map.Entry<Integer, Object> entry : managersBySide.entrySet()) {
                    Object manager = entry.getValue();
                    Object reservesObj =
                            invoke(f.getReserves, manager, "CombatFleetManager.getReserves");
                    if (!(reservesObj instanceof Collection)) {
                        throw new HeadlessCombatContextException(
                                "getReserves did not return a Collection for side " + entry.getKey());
                    }
                    if (((Collection<?>) reservesObj).isEmpty()) {
                        continue; // occupancy-gated side with zero hulls at install (S3)
                    }
                    invoke(f.setFirstDeployment, manager,
                            "CombatFleetManager.setFirstDeployment.on", Boolean.TRUE);
                    try {
                        invoke(f.deployAll, manager, "CombatFleetManager.deployAll",
                                Float.valueOf(deployArg));
                    } finally {
                        invoke(f.setFirstDeployment, manager,
                                "CombatFleetManager.setFirstDeployment.off", Boolean.FALSE);
                    }
                    Object drainedObj = invoke(f.getReserves, manager,
                            "CombatFleetManager.getReserves.drained");
                    if (!(drainedObj instanceof Collection)
                            || !((Collection<?>) drainedObj).isEmpty()) {
                        throw new HeadlessCombatContextException(
                                "fly-in deployAll left reserves undeployed on side "
                                        + entry.getKey() + " (undeployable member?)");
                    }
                }
                System.out.println("engine-deploy fly-in deployed specs=" + c.specs.size()
                        + " arg=" + deployArg + " standoff=" + standoff
                        + " standoff_before=" + standoffBefore
                        + " mapHeight=" + mapHeight);
                // The STATION direct-spawn pass, AFTER every side's
                // deployAll so the batched fly-in geometry is computed against a station-free
                // reserve list. Spec order is preserved in the ledger because the readback loop
                // below appends in spec order and the station spec is LAST by mint contract
                // (MatchDescriptors.bastionFortress) — the descriptor id-contract law depends
                // on that ordering.
                Object[] stationShipBySpecIndex = new Object[c.specs.size()];
                for (int i = 0; i < c.specs.size(); i++) {
                    EngineDeployShipSpec spec = c.specs.get(i);
                    if (!spec.station) {
                        continue;
                    }
                    Object manager = managersBySide.get(Integer.valueOf(spec.owner));
                    // Loc pin exact (setFixedLocation); burn is DEAD
                    // input for a station (the fleet manager's isStation short-circuit, the travel-drive
                    // gate false), so 0 facing / 0 burn are the honest arguments here.
                    Object ship = invoke(c.spawnFleetMember, manager,
                            "CombatFleetManager.spawnFleetMember.station",
                            mintedMembers.get(i), spec.location, Float.valueOf(0.0f),
                            Float.valueOf(0.0f));
                    if (ship == null || !c.shipClass.isInstance(ship)) {
                        throw new HeadlessCombatContextException(
                                "station spawnFleetMember returned "
                                        + (ship == null ? "null" : ship.getClass().getName())
                                        + " for '" + spec.shipName + "'");
                    }
                    stationShipBySpecIndex[i] = ship;
                    System.out.println("engine-deploy station_spawn '" + spec.shipName
                            + "' side=" + spec.owner + " (direct, around deployAll)");
                }
                for (int i = 0; i < c.specs.size(); i++) {
                    EngineDeployShipSpec spec = c.specs.get(i);
                    Object member = mintedMembers.get(i);
                    Object manager = managersBySide.get(Integer.valueOf(spec.owner));
                    Object ship = stationShipBySpecIndex[i] != null
                            ? stationShipBySpecIndex[i]
                            : invoke(f.getShipFor, manager, "CombatFleetManager.getShipFor", member);
                    if (ship == null || !c.shipClass.isInstance(ship)) {
                        throw new HeadlessCombatContextException(
                                "getShipFor returned "
                                        + (ship == null ? "null" : ship.getClass().getName())
                                        + " for '" + spec.shipName + "' after fly-in deployAll");
                    }
                    verifyAndTreatDeployedShip(c, spec, member, ship, manager,
                            shipGetCurrentCR, shipControlsLocked, deployCr, crReadback,
                            mintedDiags.get(i));
                }
            } finally {
                invoke(c.setPreDeploy, c.engine, "CombatEngine.setPreDeploy.post-install",
                        Boolean.valueOf(priorPreDeploy));
            }
        }

        // W3: never flag roster members flagship (unguarded getCombatUI().setPlayerShip);
        // the player seat is the engine's own field, set directly as the direct lane does.
        // Conditional on >=1 deployed ship: only the explicit-registerSides gated
        // path can install zero specs (N-boot pre-first-claim); legacy always deploys >=1, so the
        // legacy write + readback are behavior-identical.
        if (!engineDeployedShips.isEmpty()) {
            setField(c.playerShipField, c.engine, engineDeployedShips.get(0), "CombatEngine.playerShip");
            Object playerShipReadback = invoke(c.getPlayerShip, c.engine, "CombatEngine.getPlayerShip.readback");
            if (playerShipReadback != engineDeployedShips.get(0)) {
                throw new HeadlessCombatContextException("engine-deploy playerShip readback mismatch");
            }
        }
        // POINT B: vanilla runs every side's admiral preCombat() AFTER the
        // deploy pass — same order here, whichever deploy lane ran.
        if (installedWaveAdmiral != null) {
            invoke(c.waveAdmiral.preCombat, installedWaveAdmiral, "AdmiralAI.preCombat");
        }
        installedTreatmentCount++;
    }

    /** The engine admiral installed on the wave side by {@link #installWaveAdmiral}; null = none. */
    private Object installedWaveAdmiral;

    private void installWaveAdmiral(EngineDeployRosterConfig c,
            LinkedHashMap<Integer, Object> managersBySide) {
        EngineDeployWaveAdmiralConfig a = c.waveAdmiral;
        Object manager = managersBySide.get(Integer.valueOf(a.side));
        if (manager == null) {
            throw new HeadlessCombatContextException(
                    "wave admiral: side " + a.side + " was not registered by addFleet");
        }
        Object admiral;
        try {
            admiral = a.admiralCtor.newInstance(manager, Boolean.FALSE);
        } catch (ReflectiveOperationException e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new HeadlessCombatContextException(
                    "wave admiral: AdmiralAI construction failed for side " + a.side + ": " + cause,
                    cause);
        }
        invoke(a.setAdmiralAI, manager, "CombatFleetManager.setAdmiralAI", admiral);
        Object readback = invoke(a.getAdmiralAI, manager, "CombatFleetManager.getAdmiralAI.readback");
        if (readback != admiral) {
            throw new HeadlessCombatContextException(
                    "wave admiral: getAdmiralAI readback mismatch on side " + a.side);
        }
        installedWaveAdmiral = admiral;
        Object context = invoke(a.getContext, c.engine, "CombatEngine.getContext");
        if (context == null) {
            throw new HeadlessCombatContextException(
                    "wave admiral requires a non-null BattleCreationContext (retreat posture write)");
        }
        // Retreat stays suppressed for now — the engine's own lever
        // (setPreventFullRetreat); orphaned fighter wings still
        // retreat on their own FighterAI path, never gated by this flag.
        setBooleanField(a.aiRetreatAllowed, context, false, "BattleCreationContext.aiRetreatAllowed");
        setBooleanField(a.fightToTheLast, context, a.fightToTheLastInitial,
                "BattleCreationContext.fightToTheLast");
        System.out.println("engine-deploy wave_admiral installed side=" + a.side
                + " ally_mode=false ai_retreat_allowed=false"
                + " fight_to_the_last=" + a.fightToTheLastInitial
                + " admiral=" + admiral.getClass().getName());
    }

    /**
     * ADMIRAL: the per-category posture flip a mid-match {@code /wave}
     * switch drives — vanilla's Shrouded fleets fight to the last,
     * the rest do not; the admiral reads the flag at its next periodic decision
     * and latches full assault once true. Returns false
     * (no write) when no wave admiral was configured.
     */
    public boolean applyWaveAdmiralPosture(boolean fightToTheLast) {
        EngineDeployRosterConfig c = engineDeployRosterConfig;
        if (c == null || c.waveAdmiral == null || installedWaveAdmiral == null) {
            return false;
        }
        Object context = invoke(c.waveAdmiral.getContext, c.engine, "CombatEngine.getContext");
        if (context == null) {
            throw new HeadlessCombatContextException(
                    "wave admiral posture requires a non-null BattleCreationContext");
        }
        setBooleanField(c.waveAdmiral.fightToTheLast, context, fightToTheLast,
                "BattleCreationContext.fightToTheLast");
        return true;
    }

    private static void setBooleanField(Field field, Object target, boolean value, String label) {
        try {
            field.setBoolean(target, value);
            if (field.getBoolean(target) != value) {
                throw new HeadlessCombatContextException(label + " readback mismatch");
            }
        } catch (IllegalAccessException e) {
            throw new HeadlessCombatContextException(label + " write failed: " + e, e);
        }
    }

    /**
     * Ships spawned by the engine-own deploy lane, in spec order (empty unless
     * {@link Builder#engineDeployRosterInstall} configured the treatment).
     */
    public List<Object> engineDeployedShips() {
        return Collections.unmodifiableList(engineDeployedShips);
    }

    /** FleetMembers spawned by the engine-own deploy lane, in spec order. */
    public List<Object> engineDeployedMembers() {
        return Collections.unmodifiableList(engineDeployedMembers);
    }

    /**
     * Mints one spec's FleetMember — real ctor + setShipName (W2) + the faithful
     * crew-to-full-complement fix — shared verbatim by the teleport and fly-in deploy lanes.
     * {@code diagOut[0]} receives the cc.server.crReadback diagnostic prefix (null when off).
     */
    private Object mintCrewedMember(EngineDeployRosterConfig c, EngineDeployShipSpec spec,
            Method memberGetCrewComposition, Method memberGetMinCrew, Method memberGetMaxCrew,
            Method memberGetCrewFraction, Method memberCanBeDeployed, Method memberIsMothballed,
            Method crewSetCrew, boolean crReadback, String[] diagOut, StationMemberCr stationCr) {
        Object member;
        // CFM verdict: the (int, HullVariantSpec,
        // Type) ctor stores the handed variant BY REFERENCE, un-cloned — and the engine's own
        // station path always clone()s first. A later fix widens
        // the station-only clone to EVERY spec: engine build normalization can rewrite a handed
        // spec, so no lane gets the shared REGISTRY singleton. (The seat autofire STRIP was
        // reaped at a later parole — its attribution was overturned; the runtime
        // disarm at the fly-in site is the FIRE-only bar.)
        Object memberVariant;
        try {
            memberVariant = RegistryVariantClone.cloneVariant(
                    spec.variant, "mintCrewedMember '" + spec.shipName + "'");
        } catch (IllegalStateException e) {
            throw new HeadlessCombatContextException(e.getMessage(), e);
        }
        try {
            member = c.fleetMemberCtor.newInstance(
                    Integer.valueOf(spec.owner), memberVariant, c.fleetMemberTypeShip);
        } catch (ReflectiveOperationException e) {
            Throwable cause = (e instanceof InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            throw new HeadlessCombatContextException(
                    "FleetMember construction failed for '" + spec.shipName + "': " + cause, cause);
        }
        // W2: an explicit name keeps instantiateForCombat off the random-name factory
        // overload (ShipNameStore is empty in the minimal bootstrap).
        invoke(c.memberSetShipName, member, "FleetMember.setShipName", spec.shipName);

        // Faithful crew fix: crew the bare member to its full complement BEFORE spawn
        // so canBeDeployedForCombat() is true and instantiateForCombat never controls-locks it.
        // Headroom: combat-time minCrew reads ~25% above the bare member's, so bare-minCrew
        // crewing left getCrewFraction()=0.8 and CRPluginImpl's understrength malus dragged maxCR
        // to 0.6 — crew max(maxCrew,minCrew,1), the engine's own full-crew idiom
        // (FleetMember.readResolve addCrew(100000)).
        Object crewComp =
                invoke(memberGetCrewComposition, member, "FleetMemberAPI.getCrewComposition");
        float minCrew =
                ((Number) invoke(memberGetMinCrew, member, "FleetMemberAPI.getMinCrew")).floatValue();
        float maxCrew =
                ((Number) invoke(memberGetMaxCrew, member, "FleetMemberAPI.getMaxCrew")).floatValue();
        float fullCrew = Math.max(Math.max(maxCrew, minCrew), 1.0f);
        String deployDiag = null;
        if (crReadback) {
            deployDiag = "[cc-deploy-readback] ship='" + spec.shipName + "' owner=" + spec.owner
                    + " minCrew=" + minCrew + " maxCrew=" + maxCrew
                    + " mothballed=" + invoke(memberIsMothballed, member, "FleetMemberAPI.isMothballed")
                    + " fracBefore=" + invoke(memberGetCrewFraction, member, "getCrewFraction.before")
                    + " canDeployBefore=" + invoke(memberCanBeDeployed, member, "canDeploy.before");
        }
        invoke(crewSetCrew, crewComp, "CrewCompositionAPI.setCrew", Float.valueOf(fullCrew));
        if (crReadback) {
            deployDiag = deployDiag + " | fullCrew=" + fullCrew
                    + " fracAfter=" + invoke(memberGetCrewFraction, member, "getCrewFraction.after")
                    + " canDeployAfter=" + invoke(memberCanBeDeployed, member, "canDeploy.after");
        }
        // PRE-SPAWN member CR for station specs. The engine builds the
        // module cluster inside the station's own spawn (the fleet manager's auto-create) and each module
        // copies its parent's MEMBER CR at build — so the post-spawn ShipAPI lift in
        // verifyAndTreatDeployedShip reaches the BASE hull only, and a CR-0 member would mint
        // ~24 CR-0 modules. Set the member's own RepairTracker CR here, before spawn, through
        // the engine's own combat-readiness field.
        if (spec.station) {
            if (stationCr == null) {
                throw new HeadlessCombatContextException(
                        "STATION spec '" + spec.shipName + "' reached mintCrewedMember without"
                                + " the pre-spawn member-CR handles (install-seam resolve bug)");
            }
            Object repairTracker = invoke(stationCr.memberGetRepairTracker, member,
                    "FleetMemberAPI.getRepairTracker");
            if (repairTracker == null) {
                throw new HeadlessCombatContextException(
                        "FleetMemberAPI.getRepairTracker() is null for STATION spec '"
                                + spec.shipName + "' — the module cluster would build at CR 0");
            }
            invoke(stationCr.trackerSetCr, repairTracker, "RepairTrackerAPI.setCR",
                    Float.valueOf(stationCr.deployCr));
            System.out.println("engine-deploy station_member_cr '" + spec.shipName
                    + "' set=" + stationCr.deployCr + " (pre-spawn; modules copy member CR)");
        }
        // The variant's json-assigned officer attaches BEFORE spawn — the engine
        // applies the skills itself via instantiateForCombat → Ship ctor → applyPersonalToShip.
        // All-skills guard: a SEAT spec under the all-skills knob takes the all-skills captain
        // INSTEAD (REPLACES, never stacks); station/AI specs always stay on the curated lane
        // (guard (c): a station captain fans to child modules).
        if (spec.seat && AllSkillsAttach.enabled()) {
            AllSkillsAttach.attachAllSkillsCaptain(member, spec.shipName);
        } else if (spec.seatDescriptorVariantId != null) {
            // A descriptor SEAT hull takes the seat
            // lookup law (fielded row, else the seat's descriptor row — the officer
            // TRANSFER, midDeploySeat's twin) so a pick kept across the wrap keeps its officer.
            OfficerAttach.maybeAttachSeat(member, spec.seatFieldedVariantId,
                    spec.seatDescriptorVariantId);
        } else {
            OfficerAttach.maybeAttachByVariant(member, spec.variant);
        }
        // EVERY side-0 non-station member carries the fleet-context pair — grunts and
        // seats alike (a pair-less member's stats derive short-circuits the engine's threshold
        // throttle to full bonus, the defect). Station skip mirrors the shared walk law:
        // a station is never a campaign fleet member. Knob-gated no-op inside.
        if (spec.owner == 0 && !spec.station) {
            AllSkillsAttach.installFleetContext(member);
        }
        diagOut[0] = deployDiag;
        return member;
    }

    /**
     * The STATION pre-spawn member-CR handles, resolved once at the
     * install seam and null for every roster without a station spec.
     */
    private static final class StationMemberCr {
        private final Method memberGetRepairTracker;
        private final Method trackerSetCr;
        private final float deployCr;

        StationMemberCr(Method memberGetRepairTracker, Method trackerSetCr, float deployCr) {
            this.memberGetRepairTracker = memberGetRepairTracker;
            this.trackerSetCr = trackerSetCr;
            this.deployCr = deployCr;
        }
    }

    /**
     * Post-spawn treatment shared verbatim by both deploy lanes: deployed-ledger membership,
     * engine-aliveness, the CR lift, the crReadback diagnostic, and the
     * spec-order ledger append.
     */
    private void verifyAndTreatDeployedShip(EngineDeployRosterConfig c, EngineDeployShipSpec spec,
            Object member, Object ship, Object manager, Method shipGetCurrentCR,
            Method shipControlsLocked, float deployCr, boolean crReadback, String deployDiag) {
        List deployed = asList(
                invoke(c.getDeployedCopy, manager, "CombatFleetManager.getDeployedCopy"),
                "CombatFleetManager.getDeployedCopy");
        if (!deployed.contains(member)) {
            throw new HeadlessCombatContextException(
                    "deployed ledger does not contain the spawned member for '" + spec.shipName + "'");
        }
        // isShipAlive counts the pending-add list, so this holds before the first drain.
        Object alive = invoke(c.isShipAlive, c.engine, "CombatEngine.isShipAlive", ship);
        if (!Boolean.TRUE.equals(alive)) {
            throw new HeadlessCombatContextException(
                    "spawned ship not engine-alive at install for '" + spec.shipName + "'");
        }
        // B1: lift the deployed ship off the auto-withdraw CR-0.0 state to a
        // combat-ready deploy CR through the engine-native seam ({@link CrDeploySeam})
        // — fields AND cr_effect stats, the vanilla deploy law. Station module
        // children are separate live Ships whose pre-spawn member-CR copy set fields only, so
        // each child crosses the seam too.
        float crBefore =
                ((Number) invoke(shipGetCurrentCR, ship, "ShipAPI.getCurrentCR.before")).floatValue();
        int moduleCount = 0;
        try {
            CrDeploySeam.applyDeployCr(ship, deployCr);
            TerrainStatSeam.applyToShip(ship); // Terrain mults ride every deploy site
            List children = asList(
                    ship.getClass().getMethod("getChildModulesCopy").invoke(ship),
                    "ShipAPI.getChildModulesCopy");
            for (Object child : children) {
                CrDeploySeam.applyDeployCr(child, deployCr);
                TerrainStatSeam.applyToShip(child);
            }
            moduleCount = children.size();
        } catch (Exception e) {
            throw new HeadlessCombatContextException(
                    "CR deploy seam failed for '" + spec.shipName + "': " + e, e);
        }
        float crAfter =
                ((Number) invoke(shipGetCurrentCR, ship, "ShipAPI.getCurrentCR.after")).floatValue();
        System.out.println("engine-deploy roster CR '" + spec.shipName + "' before=" + crBefore
                + " set=" + deployCr + " after=" + crAfter
                + (moduleCount > 0 ? " modules=" + moduleCount : ""));
        if (crReadback) {
            // Success criterion: with the member crewed, the deployed ship must NOT be
            // controls-locked (expect false; was true on the bare member → retreat order).
            System.out.println(deployDiag
                    + " | spawned controlsLocked="
                    + invoke(shipControlsLocked, ship, "ShipAPI.controlsLocked")
                    + " shipCRbefore=" + crBefore + " shipCRafter=" + crAfter);
        }
        engineDeployedMembers.add(member);
        engineDeployedShips.add(ship);
    }

    private void seedModManagerProxy() {
        ModManagerConfig c = modManagerConfig;
        Object modManager = invoke(c.getInstance, null, "ModManager.getInstance");
        Object priorPlugins = getField(c.pluginsField, modManager, "ModManager.plugins");
        Object proxyPlugin = Proxy.newProxyInstance(
                c.modPluginInterface.getClassLoader(),
                new Class<?>[] { c.modPluginInterface },
                new DefaultProxyHandler("HeadlessModPluginProxy", boundaryLabel, false));
        ArrayList<Object> seededPlugins = new ArrayList<>();
        seededPlugins.add(proxyPlugin);
        setField(c.pluginsField, modManager, seededPlugins, "ModManager.plugins");
        Object readback = invoke(c.getEnabledModPlugins, modManager, "ModManager.getEnabledModPlugins");
        if (readback != seededPlugins
                || !(readback instanceof List<?>)
                || ((List<?>) readback).size() != 1
                || ((List<?>) readback).get(0) != proxyPlugin
                || !c.modPluginInterface.isInstance(proxyPlugin)) {
            throw new HeadlessCombatContextException("ModManager plugin proxy readback mismatch");
        }
        rollbackActions.add(() -> {
            setField(c.pluginsField, modManager, priorPlugins, "ModManager.plugins.restore");
            Object restored = getField(c.pluginsField, modManager, "ModManager.plugins.restore.readback");
            if (restored != priorPlugins) {
                throw new HeadlessCombatContextException("ModManager plugin proxy rollback readback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seedTexturedLineTextureCache() {
        TextureConfig c = textureConfig;
        Object textureMapObject = invoke(c.textureMapGetter, null, "texture registry map getter");
        if (!(textureMapObject instanceof Map)) {
            throw new HeadlessCombatContextException("texture registry getter did not return a Map");
        }
        Map textureMap = (Map) textureMapObject;
        boolean linearHadBefore = textureMap.containsKey(TEXTURED_LINE_LINEAR_KEY);
        boolean squareHadBefore = textureMap.containsKey(TEXTURED_LINE_SQUARE_KEY);
        Object linearBefore = textureMap.get(TEXTURED_LINE_LINEAR_KEY);
        Object squareBefore = textureMap.get(TEXTURED_LINE_SQUARE_KEY);
        try {
            Object linearTexture = c.textureObjectConstructor.newInstance(32, 32);
            Object squareTexture = c.textureObjectConstructor.newInstance(32, 32);
            textureMap.put(TEXTURED_LINE_LINEAR_KEY, linearTexture);
            textureMap.put(TEXTURED_LINE_SQUARE_KEY, squareTexture);
            if (textureMap.get(TEXTURED_LINE_LINEAR_KEY) != linearTexture
                    || textureMap.get(TEXTURED_LINE_SQUARE_KEY) != squareTexture) {
                throw new HeadlessCombatContextException("TexturedLine texture cache readback mismatch");
            }
            rollbackActions.add(() -> {
                if (linearHadBefore) {
                    textureMap.put(TEXTURED_LINE_LINEAR_KEY, linearBefore);
                } else {
                    textureMap.remove(TEXTURED_LINE_LINEAR_KEY);
                }
                if (squareHadBefore) {
                    textureMap.put(TEXTURED_LINE_SQUARE_KEY, squareBefore);
                } else {
                    textureMap.remove(TEXTURED_LINE_SQUARE_KEY);
                }
                boolean linearRestored = linearHadBefore
                        ? textureMap.containsKey(TEXTURED_LINE_LINEAR_KEY)
                            && textureMap.get(TEXTURED_LINE_LINEAR_KEY) == linearBefore
                        : !textureMap.containsKey(TEXTURED_LINE_LINEAR_KEY);
                boolean squareRestored = squareHadBefore
                        ? textureMap.containsKey(TEXTURED_LINE_SQUARE_KEY)
                            && textureMap.get(TEXTURED_LINE_SQUARE_KEY) == squareBefore
                        : !textureMap.containsKey(TEXTURED_LINE_SQUARE_KEY);
                if (!linearRestored || !squareRestored) {
                    throw new HeadlessCombatContextException("TexturedLine texture cache rollback mismatch");
                }
            });
            installedTreatmentCount++;
        } catch (ReflectiveOperationException e) {
            throw new HeadlessCombatContextException("TexturedLine texture cache seed failed", e);
        }
    }

    private void seedCombatUiProxy() {
        CombatUiConfig c = combatUiConfig;
        Object priorCombatUi = invoke(c.getCombatUi, c.engine, "CombatEngine.getCombatUI");
        if (priorCombatUi != null) {
            throw new HeadlessCombatContextException("CombatUI was already bound; refusing to overwrite");
        }
        Object messageWidget = null;
        InvocationHandler handler = new DefaultProxyHandler("HeadlessCombatUiProxy", boundaryLabel, true);
        if (c.rawMessageWidget != null) {
            messageWidget = seedMessageWidgetFields(c);
            selfCheckMessageWidgetStringPost(messageWidget);
            handler = new MessageWidgetProxyHandler((DefaultProxyHandler) handler, messageWidget);
        }
        Object proxy = Proxy.newProxyInstance(
                c.combatUiInterface.getClassLoader(),
                new Class<?>[] { c.combatUiInterface },
                handler);
        invoke(c.setCombatUi, c.engine, "CombatEngine.setCombatUI", proxy);
        Object readback = invoke(c.getCombatUi, c.engine, "CombatEngine.getCombatUI.readback");
        if (readback != proxy || !c.combatUiInterface.isInstance(readback)) {
            throw new HeadlessCombatContextException("CombatUI proxy readback mismatch");
        }
        if (messageWidget != null) {
            Method getMessageWidget;
            try {
                getMessageWidget = c.combatUiInterface.getMethod("getMessageWidget");
            } catch (NoSuchMethodException e) {
                throw new HeadlessCombatContextException("CombatUI interface has no getMessageWidget()", e);
            }
            Object widgetReadback = invoke(getMessageWidget, readback, "CombatUI.getMessageWidget.readback");
            if (widgetReadback != messageWidget) {
                throw new HeadlessCombatContextException("Message-widget proxy readback mismatch");
            }
        }
        rollbackActions.add(() -> {
            invoke(c.setCombatUi, c.engine, "CombatEngine.setCombatUI.restore", priorCombatUi);
            Object restored = invoke(c.getCombatUi, c.engine, "CombatEngine.getCombatUI.restore.readback");
            if (restored != priorCombatUi) {
                throw new HeadlessCombatContextException("CombatUI proxy rollback readback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    /** Font path the engine's own message-widget ctor hardcodes. The
     *  headless server's {@code ResourceLoaderState.init(Map)} drains the whole resource queue
     *  synchronously — fonts included — so
     *  this font is in the engine font cache before any seam installs. */
    private static final String MESSAGE_WIDGET_FONT_PATH = "graphics/fonts/insignia21LTaa.fnt";
    /** Verified runtime name of the widget's font-size float (the shipped jar has dotted
     *  keyword field names); the engine ctor's value is 25. The one other instance float is the
     *  layout y-extent (class default 475; real ctor derives screen-height − 250 — layout-only,
     *  never rendered headless). */
    private static final String MESSAGE_WIDGET_FONT_SIZE_FIELD = "super.interface$do";
    private static final float MESSAGE_WIDGET_FONT_SIZE = 25.0F;
    private static final float MESSAGE_WIDGET_LAYOUT_EXTENT = 475.0F;

    /**
     * Seed the Unsafe-allocated REAL message widget ({@code com.fs.starfarer.class.C} —
     * the concrete return type of {@code CombatUIAPI.getMessageWidget()}; its package name is a Java
     * keyword, so every touch is reflective) with exactly the state its message-ADD path reads.
     * {@code Ship.advance}'s combat-readiness callouts call
     * {@code getCombatUI().getMessageWidget()}'s message-add method UNGUARDED, so the engine requires a
     * real widget in combat; the prior null-returning proxy killed a recorded run at ~t9000. The real
     * ctor is deliberately NOT run: it registers the widget as an engine combat listener and fetches
     * a scanline UI sprite — presentation crossings the serve loop must not depend on. Fields are
     * matched by unique TYPE (descriptor-grade identity; names are dotted keywords): the entry List,
     * the add-path Fader (engine ctor values 0/0.5/5), the label font path, the message-sound flag
     * (true = ctor default; the sound statics on this exact path ran headless-safe in a measured run
     * pre-NPE), and the two layout floats by name. The scanline field
     * stays null: only the widget's render lane reads it, which the serve loop never drives.
     */
    private Object seedMessageWidgetFields(CombatUiConfig c) {
        Object widget = c.rawMessageWidget;
        Class<?> widgetClass = widget.getClass();
        Object entryList = new java.util.LinkedList<Object>();
        setField(uniqueInstanceField(widgetClass, List.class, "MessageWidget.entryList"),
                widget, entryList, "MessageWidget.entryList");
        Class<?> faderClass = c.faderConstructor.getDeclaringClass();
        Object fader;
        try {
            fader = c.faderConstructor.newInstance(
                    Float.valueOf(0.0F), Float.valueOf(0.5F), Float.valueOf(5.0F));
        } catch (ReflectiveOperationException e) {
            throw new HeadlessCombatContextException("MessageWidget fader construction failed", e);
        }
        setField(uniqueInstanceField(widgetClass, faderClass, "MessageWidget.fader"),
                widget, fader, "MessageWidget.fader");
        setField(uniqueInstanceField(widgetClass, String.class, "MessageWidget.fontPath"),
                widget, MESSAGE_WIDGET_FONT_PATH, "MessageWidget.fontPath");
        setField(uniqueInstanceField(widgetClass, boolean.class, "MessageWidget.soundFlag"),
                widget, Boolean.TRUE, "MessageWidget.soundFlag");
        int floatCount = 0;
        for (Field field : widgetClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())
                    || field.getType() != float.class) {
                continue;
            }
            floatCount++;
            float seed = MESSAGE_WIDGET_FONT_SIZE_FIELD.equals(field.getName())
                    ? MESSAGE_WIDGET_FONT_SIZE
                    : MESSAGE_WIDGET_LAYOUT_EXTENT;
            setField(field, widget, Float.valueOf(seed), "MessageWidget.float." + field.getName());
        }
        if (floatCount != 2) {
            throw new HeadlessCombatContextException(
                    "MessageWidget instance-float layout changed; count=" + floatCount);
        }
        Object listReadback = getField(
                uniqueInstanceField(widgetClass, List.class, "MessageWidget.entryList.readback"),
                widget, "MessageWidget.entryList.readback");
        Object faderReadback = getField(
                uniqueInstanceField(widgetClass, faderClass, "MessageWidget.fader.readback"),
                widget, "MessageWidget.fader.readback");
        if (listReadback != entryList || faderReadback != fader) {
            throw new HeadlessCombatContextException("MessageWidget field seed readback mismatch");
        }
        return widget;
    }

    /**
     * Install-time exercise of the widget's REAL message-add path with the flagship-branch arg shape
     * (Color + String): constructs a real engine message entry — text-label ctor, font-cache lookup,
     * metric sum — so a fonts/label regression fails the BOOT, not minute ~2.5 of an owner round.
     * The posted self-check entry is cleared afterwards, leaving pristine widget state.
     * The Ship-arg (fleet-member icon) shape needs a deployed ship and is exercised post-roster by
     * {@code RealEngineCombatBindingFactory}.
     */
    private void selfCheckMessageWidgetStringPost(Object widget) {
        Method post;
        try {
            post = widget.getClass().getMethod("o00000", Object[].class);
        } catch (NoSuchMethodException e) {
            throw new HeadlessCombatContextException("MessageWidget varargs post method missing", e);
        }
        Object entry = invoke(post, widget, "MessageWidget.selfPost", (Object) new Object[] {
                java.awt.Color.WHITE, "headless message-widget seed self-check" });
        if (entry == null) {
            throw new HeadlessCombatContextException("MessageWidget self-post returned null entry");
        }
        List<?> entries = asList(getField(
                uniqueInstanceField(widget.getClass(), List.class, "MessageWidget.entryList.selfPost"),
                widget, "MessageWidget.entryList.selfPost"), "MessageWidget.entryList.selfPost");
        if (entries.size() != 1) {
            throw new HeadlessCombatContextException(
                    "MessageWidget self-post entry count=" + entries.size());
        }
        resetSeededMessageWidgetEntries(widget);
    }

    /**
     * Clear the seeded message widget's presentation entries. The real client prunes entries in the
     * widget's render advance, which the headless serve loop never drives, so entries would
     * accumulate for process life; the factory clears after boot self-checks and per round reset.
     * Presentation-only state — combat math never reads it.
     */
    public static void resetSeededMessageWidgetEntries(Object messageWidget) {
        List<?> entries = asList(getField(
                uniqueInstanceField(messageWidget.getClass(), List.class, "MessageWidget.entryList.reset"),
                messageWidget, "MessageWidget.entryList.reset"), "MessageWidget.entryList.reset");
        entries.clear();
        if (!entries.isEmpty()) {
            throw new HeadlessCombatContextException("MessageWidget entry reset readback mismatch");
        }
    }

    private void seedDebrisViewport() {
        DebrisViewportConfig c = debrisViewportConfig;
        Object engineViewport = invoke(c.getViewport, c.engine, "CombatEngine.getViewport");
        Object debrisSystem = invoke(c.getDebrisSystem, c.engine, "CombatEngine.getDebrisSystem");
        if (!c.viewportClass.isInstance(engineViewport)) {
            throw new HeadlessCombatContextException("CombatEngine viewport is not bound");
        }
        if (!c.debrisSystemClass.isInstance(debrisSystem)) {
            throw new HeadlessCombatContextException("CombatEngine debris system is not bound");
        }
        Field viewportField = uniqueInstanceField(c.debrisSystemClass, c.viewportClass, "DebrisParticleSystem.viewport");
        Object priorDebrisViewport = getField(viewportField, debrisSystem, "DebrisParticleSystem.viewport");
        if (priorDebrisViewport != null) {
            throw new HeadlessCombatContextException("DebrisParticleSystem viewport was already bound");
        }
        boolean priorEverythingNearViewport =
                ((Boolean) invoke(c.isEverythingNearViewport, engineViewport, "CombatViewport.isEverythingNearViewport"))
                        .booleanValue();
        invoke(c.setEverythingNearViewport, engineViewport, "CombatViewport.setEverythingNearViewport", Boolean.TRUE);
        setField(viewportField, debrisSystem, engineViewport, "DebrisParticleSystem.viewport");
        Object readback = getField(viewportField, debrisSystem, "DebrisParticleSystem.viewport.readback");
        boolean everythingNearViewport =
                ((Boolean) invoke(c.isEverythingNearViewport, engineViewport, "CombatViewport.isEverythingNearViewport.readback"))
                        .booleanValue();
        if (readback != engineViewport || !everythingNearViewport) {
            throw new HeadlessCombatContextException("Debris viewport seed readback mismatch");
        }
        rollbackActions.add(() -> {
            setField(viewportField, debrisSystem, priorDebrisViewport, "DebrisParticleSystem.viewport.restore");
            invoke(c.setEverythingNearViewport,
                    engineViewport,
                    "CombatViewport.setEverythingNearViewport.restore",
                    Boolean.valueOf(priorEverythingNearViewport));
            Object restoredViewport = getField(viewportField, debrisSystem, "DebrisParticleSystem.viewport.restore.readback");
            boolean restoredEverything =
                    ((Boolean) invoke(c.isEverythingNearViewport,
                            engineViewport,
                            "CombatViewport.isEverythingNearViewport.restore.readback")).booleanValue()
                            == priorEverythingNearViewport;
            if (restoredViewport != priorDebrisViewport || !restoredEverything) {
                throw new HeadlessCombatContextException("Debris viewport rollback readback mismatch");
            }
        });
        installedTreatmentCount++;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seedCollisionSoundSpecs() {
        CollisionSoundSpecConfig c = collisionSoundSpecConfig;
        List<StaticFieldSnapshot> snapshots =
                snapshotStaticFields(c.soundSpecBootstrapClass, c.soundSpecClass);
        invoke(c.initializeSoundSpecs, null, "collision sound spec initializer");
        Object collisionShipsSpec =
                getField(c.collisionShipsSpecField, null, "collision_ships sound spec readback");
        if (!c.soundSpecClass.isInstance(collisionShipsSpec)) {
            throw new HeadlessCombatContextException("collision_ships sound spec readback mismatch");
        }
        rollbackActions.add(() -> {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                StaticFieldSnapshot snapshot = snapshots.get(i);
                if (snapshot.mapContents != null) {
                    Object current = getField(snapshot.field, null, snapshot.label + ".rollback.current");
                    if (current instanceof Map) {
                        Map currentMap = (Map) current;
                        currentMap.clear();
                        currentMap.putAll(snapshot.mapContents);
                    }
                }
                setField(snapshot.field, null, snapshot.value, snapshot.label + ".restore");
                Object restored = getField(snapshot.field, null, snapshot.label + ".restore.readback");
                if (restored != snapshot.value) {
                    throw new HeadlessCombatContextException(
                            snapshot.label + " rollback readback mismatch");
                }
            }
        });
        installedTreatmentCount++;
    }

    private static Field uniqueInstanceField(Class<?> owner, Class<?> fieldType, String label) {
        Field matched = null;
        int count = 0;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == fieldType) {
                matched = field;
                count++;
            }
        }
        if (count != 1 || matched == null) {
            throw new HeadlessCombatContextException(label + " was not uniquely identified; count=" + count);
        }
        matched.setAccessible(true);
        return matched;
    }

    private static List<StaticFieldSnapshot> snapshotStaticFields(Class<?> owner, Class<?> soundSpecClass) {
        List<StaticFieldSnapshot> snapshots = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            boolean soundSpecField = field.getType() == soundSpecClass;
            boolean mapField = Map.class.isAssignableFrom(field.getType());
            if (!soundSpecField && !mapField) {
                continue;
            }
            field.setAccessible(true);
            Object value = getField(field, null, owner.getName() + "." + field.getName() + ".snapshot");
            Map<?, ?> mapContents = value instanceof Map<?, ?>
                    ? new LinkedHashMap<>((Map<?, ?>) value)
                    : null;
            snapshots.add(new StaticFieldSnapshot(field, value, mapContents));
        }
        if (snapshots.isEmpty()) {
            throw new HeadlessCombatContextException(
                    owner.getName() + " did not expose static sound-spec/map fields");
        }
        return snapshots;
    }

    private static Object invoke(Method method, Object target, String label, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new HeadlessCombatContextException(label + " threw", cause);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new HeadlessCombatContextException(label + " invocation failed", e);
        }
    }

    private static byte[] snapshot(ByteBuffer buffer) {
        byte[] copy = new byte[buffer.capacity()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = buffer.get(i);
        }
        return copy;
    }

    private static void zero(ByteBuffer buffer) {
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) 0);
        }
    }

    private static boolean anyNonZero(ByteBuffer buffer) {
        for (int i = 0; i < buffer.capacity(); i++) {
            if (buffer.get(i) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void restore(ByteBuffer buffer, byte[] bytes) {
        if (buffer.capacity() != bytes.length) {
            throw new HeadlessCombatContextException("keyboard keyDownBuffer capacity changed");
        }
        for (int i = 0; i < bytes.length; i++) {
            buffer.put(i, bytes[i]);
        }
    }

    private static boolean matches(ByteBuffer buffer, byte[] bytes) {
        if (buffer.capacity() != bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (buffer.get(i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static void assertKeyboardKeysFalse(KeyboardKeyDownConfig c, String label) {
        for (int keyCode : c.keyCodes) {
            Object value = invoke(c.isKeyDown, null, label + "[" + keyCode + "]", Integer.valueOf(keyCode));
            if (!(value instanceof Boolean) || ((Boolean) value).booleanValue()) {
                throw new HeadlessCombatContextException(label + " returned true for key " + keyCode);
            }
        }
    }

    private static String keyCodesDescriptor(int[] keyCodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyCodes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(keyCodes[i]);
        }
        return sb.toString();
    }

    private static Object getField(Field field, Object target, String label) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new HeadlessCombatContextException(label + " read failed", e);
        }
    }

    private static void setField(Field field, Object target, Object value, String label) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new HeadlessCombatContextException(label + " write failed", e);
        }
    }

    private static List<?> asList(Object value, String label) {
        if (!(value instanceof List<?>)) {
            throw new HeadlessCombatContextException(label + " did not return a List");
        }
        return (List<?>) value;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (returnType == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0f);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0d);
        }
        if (returnType == Character.TYPE) {
            return Character.valueOf('\0');
        }
        throw new IllegalArgumentException("unsupported primitive type: " + returnType);
    }

    private static String descriptor(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Class<?> type : method.getParameterTypes()) {
            sb.append(typeDescriptor(type));
        }
        sb.append(')');
        sb.append(typeDescriptor(method.getReturnType()));
        return sb.toString();
    }

    private static String constructorDescriptor(Constructor<?> constructor) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Class<?> type : constructor.getParameterTypes()) {
            sb.append(typeDescriptor(type));
        }
        sb.append(")V");
        return sb.toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == Void.TYPE) {
            return "V";
        }
        if (type == Boolean.TYPE) {
            return "Z";
        }
        if (type == Byte.TYPE) {
            return "B";
        }
        if (type == Character.TYPE) {
            return "C";
        }
        if (type == Short.TYPE) {
            return "S";
        }
        if (type == Integer.TYPE) {
            return "I";
        }
        if (type == Long.TYPE) {
            return "J";
        }
        if (type == Float.TYPE) {
            return "F";
        }
        if (type == Double.TYPE) {
            return "D";
        }
        throw new IllegalArgumentException("unsupported primitive type: " + type);
    }

    private interface RollbackAction {
        void restore();
    }

    private static final class StaticFieldSnapshot {
        private final Field field;
        private final Object value;
        private final Map<?, ?> mapContents;
        private final String label;

        private StaticFieldSnapshot(Field field, Object value, Map<?, ?> mapContents) {
            this.field = field;
            this.value = value;
            this.mapContents = mapContents;
            this.label = field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    public static final class HeadlessCombatContextException extends RuntimeException {
        public HeadlessCombatContextException(String message) {
            super(message);
        }

        public HeadlessCombatContextException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class EngineDrivenFireTelemetry {
        private static final EngineDrivenFireTelemetry EMPTY =
                new EngineDrivenFireTelemetry(0, 0, 0, 0, "", "", "");

        private final int fireCommandAttempts;
        private final int fireCommandSuccesses;
        private final int forceFireFrames;
        private final int weaponFiringFrames;
        private final String lastErrorType;
        private final String lastErrorMessage;
        private final String autonomousAiDiagnostic;

        private EngineDrivenFireTelemetry(
                int fireCommandAttempts,
                int fireCommandSuccesses,
                int forceFireFrames,
                int weaponFiringFrames,
                String lastErrorType,
                String lastErrorMessage,
                String autonomousAiDiagnostic) {
            this.fireCommandAttempts = fireCommandAttempts;
            this.fireCommandSuccesses = fireCommandSuccesses;
            this.forceFireFrames = forceFireFrames;
            this.weaponFiringFrames = weaponFiringFrames;
            this.lastErrorType = lastErrorType == null ? "" : lastErrorType;
            this.lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
            this.autonomousAiDiagnostic = autonomousAiDiagnostic == null ? "" : autonomousAiDiagnostic;
        }

        public int fireCommandAttempts() {
            return fireCommandAttempts;
        }

        public int fireCommandSuccesses() {
            return fireCommandSuccesses;
        }

        public int forceFireFrames() {
            return forceFireFrames;
        }

        public int weaponFiringFrames() {
            return weaponFiringFrames;
        }

        public String lastErrorType() {
            return lastErrorType;
        }

        public String lastErrorMessage() {
            return lastErrorMessage;
        }

        /**
         * Compact {@code key=value;...} string describing the firer's autonomous
         * weapon-AI wiring observed on the first frame of the autonomous-AI scenario:
         * ship-AI presence, weapon-group count, autofiring
         * groups before/after the minimal autofire enable, per-weapon autofire-AI
         * plugin count, max weapon range, firer-to-target distance, and in-range. Empty
         * outside the autonomous-AI scenario.
         */
        public String autonomousAiDiagnostic() {
            return autonomousAiDiagnostic;
        }
    }

    private static final class DefaultProxyHandler implements InvocationHandler {
        private final String label;
        private final String boundaryLabel;
        private final boolean listReturnsEmpty;

        private DefaultProxyHandler(String label, String boundaryLabel, boolean listReturnsEmpty) {
            this.label = label;
            this.boundaryLabel = boundaryLabel == null ? "" : boundaryLabel;
            this.listReturnsEmpty = listReturnsEmpty;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (Object.class.equals(method.getDeclaringClass())) {
                return invokeObject(proxy, method, args);
            }
            if (listReturnsEmpty && List.class.isAssignableFrom(method.getReturnType())) {
                return Collections.emptyList();
            }
            return defaultValue(method.getReturnType());
        }

        private Object invokeObject(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("toString".equals(methodName)) {
                return label + "[" + boundaryLabel + "]";
            }
            if ("hashCode".equals(methodName)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(methodName)) {
                return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
            }
            throw new UnsupportedOperationException("Unexpected Object method: " + methodName);
        }
    }

    /**
     * Combat-UI proxy handler that answers {@code getMessageWidget()} with the seeded REAL engine
     * message widget ({@code Ship.advance} CR callouts dereference it unguarded) and
     * defers every other method to the engine-default {@link DefaultProxyHandler} returns.
     */
    private static final class MessageWidgetProxyHandler implements InvocationHandler {
        private final DefaultProxyHandler defaults;
        private final Object messageWidget;

        private MessageWidgetProxyHandler(DefaultProxyHandler defaults, Object messageWidget) {
            this.defaults = Objects.requireNonNull(defaults, "defaults");
            this.messageWidget = Objects.requireNonNull(messageWidget, "messageWidget");
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getMessageWidget".equals(method.getName()) && method.getParameterCount() == 0) {
                return messageWidget;
            }
            return defaults.invoke(proxy, method, args);
        }
    }

    private static final class EngineDrivenFireDriver {
        private final Object repository;
        private final Method repositoryGetList;
        private final Class<?> shipClass;
        private final Method shipSetShipTarget;
        private final Method shipGetLocation;
        private final Method shipGiveCommand;
        private final Object shipCommandFire;
        private final Method shipGetAllWeapons;
        private final Method weaponSetForceFireOneFrame;
        private final Method weaponSetRemainingCooldownTo;
        private final Method weaponUsesAmmo;
        private final Method weaponSetAmmo;
        private final Method weaponIsFiring;
        private Object lastSourceShip;
        private int fireCommandAttempts;
        private int fireCommandSuccesses;
        private int forceFireFrames;
        private int weaponFiringFrames;
        private String lastErrorType = "";
        private String lastErrorMessage = "";
        // Autonomous engine-ship-AI firing support. Configured only
        // when the autonomous-AI scenario is selected. When present, the driver attaches a
        // real engine BasicShipAI to a non-player ship, sets opposing owners, and issues NO
        // FIRE command and NO force-fire injection -- the engine's own ship AI decides to
        // fire on its own authority.
        private Method shipSetOwner;
        private Method shipSetShipAI;
        private java.lang.reflect.Constructor<?> basicShipAiConstructor;
        private int autonomousFirerOwner = 1;
        private int autonomousTargetOwner = 0;
        private boolean autonomousSetupDone;
        private boolean autonomousAiAttached;
        private Object autonomousFirerShip;
        // Autonomous weapon-AI wiring diagnostic + minimal enable.
        // All nullable: a missing handle degrades the diagnostic gracefully and never
        // fails the step (the diagnostic is observational and must not break the green
        // off-loopback transport path). None of these pull a trigger; they read the
        // firer's autofire wiring and, at most, enable autofire on weapon groups that
        // already own an AutofireAIPlugin, leaving the per-weapon fire decision to the
        // engine's own autofire AI.
        private Method shipGetWeaponGroupsCopy;
        private Method weaponGroupIsAutofiring;
        private Method weaponGroupToggleOn;
        private Method weaponGroupGetWeaponsCopy;
        private Method weaponGroupGetAutofirePlugin;
        private Method weaponGroupGetAIPlugins;
        private Method weaponGetRange;
        private Method shipGetShipAI;
        private String autonomousAiDiagnostic = "";
        // Per-frame autonomous fire-intent sampling. The autofire
        // plugin / weapon / target-ship handles below are resolved lazily from the runtime
        // objects the diagnostic already holds (public AutofireAIPlugin / WeaponAPI methods),
        // so no extra builder wiring is needed. These read the engine autofire AI's own
        // per-frame decision; they never command a shot.
        private Object autonomousTargetShip;
        private Object autonomousAutofirePlugin;
        private Object autonomousFirerWeapon;
        private Method autofirePluginShouldFire;
        private Method autofirePluginGetTargetShip;
        private Method weaponDistanceFromArc;
        private int autonomousShouldFireFrames;
        private int autonomousTargetShipFrames;
        private int autonomousInArcFrames;
        private int autonomousFireIntentSamples;
        private boolean autonomousLastShouldFire;
        private boolean autonomousLastTargetShip;
        private float autonomousLastDistFromArc = Float.NaN;
        // Name the concrete engine autofire-AI class and the firer's
        // ShipwideAIFlags state, so the false shouldFire() predicate can be tied to a
        // specific class and AI intent state (the shipped jar renames the API method,
        // so the impl class cannot be identified by static reading alone). Observational.
        private String autonomousAutofirePluginClass = "";
        private String autonomousShipAiClass = "";
        private String autonomousFirerAiFlags = "";

        private EngineDrivenFireDriver(
                Object repository,
                Method repositoryGetList,
                Class<?> shipClass,
                Method shipSetShipTarget,
                Method shipGetLocation,
                Method shipGiveCommand,
                Object shipCommandFire,
                Method shipGetAllWeapons,
                Method weaponSetForceFireOneFrame,
                Method weaponSetRemainingCooldownTo,
                Method weaponUsesAmmo,
                Method weaponSetAmmo,
                Method weaponIsFiring) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.repositoryGetList = Objects.requireNonNull(repositoryGetList, "repositoryGetList");
            this.shipClass = Objects.requireNonNull(shipClass, "shipClass");
            this.shipSetShipTarget = Objects.requireNonNull(shipSetShipTarget, "shipSetShipTarget");
            this.shipGetLocation = Objects.requireNonNull(shipGetLocation, "shipGetLocation");
            this.shipGiveCommand = Objects.requireNonNull(shipGiveCommand, "shipGiveCommand");
            this.shipCommandFire = Objects.requireNonNull(shipCommandFire, "shipCommandFire");
            this.shipGetAllWeapons = Objects.requireNonNull(shipGetAllWeapons, "shipGetAllWeapons");
            this.weaponSetForceFireOneFrame =
                    Objects.requireNonNull(weaponSetForceFireOneFrame, "weaponSetForceFireOneFrame");
            this.weaponSetRemainingCooldownTo =
                    Objects.requireNonNull(weaponSetRemainingCooldownTo, "weaponSetRemainingCooldownTo");
            this.weaponUsesAmmo = Objects.requireNonNull(weaponUsesAmmo, "weaponUsesAmmo");
            this.weaponSetAmmo = Objects.requireNonNull(weaponSetAmmo, "weaponSetAmmo");
            this.weaponIsFiring = Objects.requireNonNull(weaponIsFiring, "weaponIsFiring");
        }

        private void prepareFireFrame() {
            try {
                List<?> ships = asList(invoke(repositoryGetList, repository, "repository.getList", shipClass),
                        "repository.getList");
                if (ships.size() < 2) {
                    throw new HeadlessCombatContextException("engine-driven damage requires two ships");
                }
                Object sourceShip = ships.get(0);
                Object targetShip = ships.get(1);
                lastSourceShip = sourceShip;
                invoke(shipSetShipTarget, sourceShip, "Ship.setShipTarget", targetShip);
                fireCommandAttempts++;
                try {
                    Object targetLocation = invoke(shipGetLocation, targetShip, "Ship.getLocation");
                    invoke(shipGiveCommand, sourceShip, "Ship.giveCommand(FIRE)", shipCommandFire, targetLocation, 0);
                    fireCommandSuccesses++;
                } catch (RuntimeException e) {
                    recordError(e);
                }
                List<?> weapons = asList(invoke(shipGetAllWeapons, sourceShip, "Ship.getAllWeapons"),
                        "Ship.getAllWeapons");
                boolean forceFireApplied = false;
                for (Object weapon : weapons) {
                    if (weapon == null) {
                        continue;
                    }
                    invoke(weaponSetRemainingCooldownTo, weapon, "Weapon.setRemainingCooldownTo", Float.valueOf(0.0f));
                    Boolean usesAmmo = (Boolean) invoke(weaponUsesAmmo, weapon, "Weapon.usesAmmo");
                    if (usesAmmo.booleanValue()) {
                        invoke(weaponSetAmmo, weapon, "Weapon.setAmmo", Integer.valueOf(100));
                    }
                    invoke(weaponSetForceFireOneFrame,
                            weapon,
                            "Weapon.setForceFireOneFrame",
                            Boolean.TRUE);
                    forceFireApplied = true;
                }
                if (!forceFireApplied) {
                    throw new HeadlessCombatContextException("engine-driven damage source ship has no weapons");
                }
                forceFireFrames++;
            } catch (RuntimeException e) {
                recordError(e);
                throw e;
            }
        }

        /**
         * Command-authority fire frame: issues the same server-authoritative target +
         * {@code FIRE} command as {@link #prepareFireFrame()} but deliberately omits the
         * artificial force-fire injection (cooldown reset, ammo top-up,
         * {@code Weapon.setForceFireOneFrame}). Whether weapons actually fire and resolve
         * damage is left entirely to the engine's own weapon cooldown / firing AI over
         * real bytes. Used to test engine command-authority firing without the force-fire
         * cheat; {@code weaponFiringFrames} reveals whether the engine fired,
         * and {@code forceFireFrames} stays 0 in this mode.
         */
        private void prepareCommandAuthorityFireFrame() {
            try {
                List<?> ships = asList(invoke(repositoryGetList, repository, "repository.getList", shipClass),
                        "repository.getList");
                if (ships.size() < 2) {
                    throw new HeadlessCombatContextException("engine command-authority fire requires two ships");
                }
                Object sourceShip = ships.get(0);
                Object targetShip = ships.get(1);
                lastSourceShip = sourceShip;
                invoke(shipSetShipTarget, sourceShip, "Ship.setShipTarget", targetShip);
                fireCommandAttempts++;
                try {
                    Object targetLocation = invoke(shipGetLocation, targetShip, "Ship.getLocation");
                    invoke(shipGiveCommand, sourceShip, "Ship.giveCommand(FIRE)", shipCommandFire, targetLocation, 0);
                    fireCommandSuccesses++;
                } catch (RuntimeException e) {
                    recordError(e);
                }
                List<?> weapons = asList(invoke(shipGetAllWeapons, sourceShip, "Ship.getAllWeapons"),
                        "Ship.getAllWeapons");
                boolean hasWeapon = false;
                for (Object weapon : weapons) {
                    if (weapon != null) {
                        hasWeapon = true;
                        break;
                    }
                }
                if (!hasWeapon) {
                    throw new HeadlessCombatContextException(
                            "engine command-authority fire source ship has no weapons");
                }
                // No force-fire injection: cooldown reset, ammo top-up, and
                // Weapon.setForceFireOneFrame are intentionally skipped so the engine
                // owns the firing decision.
            } catch (RuntimeException e) {
                recordError(e);
                throw e;
            }
        }

        private void configureAutonomousAi(
                Method shipSetOwner,
                Method shipSetShipAI,
                java.lang.reflect.Constructor<?> basicShipAiConstructor,
                int firerOwner,
                int targetOwner,
                Method shipGetWeaponGroupsCopy,
                Method weaponGroupIsAutofiring,
                Method weaponGroupToggleOn,
                Method weaponGroupGetWeaponsCopy,
                Method weaponGroupGetAutofirePlugin,
                Method weaponGroupGetAIPlugins,
                Method weaponGetRange,
                Method shipGetShipAI) {
            this.shipSetOwner = Objects.requireNonNull(shipSetOwner, "shipSetOwner");
            this.shipSetShipAI = Objects.requireNonNull(shipSetShipAI, "shipSetShipAI");
            this.basicShipAiConstructor =
                    Objects.requireNonNull(basicShipAiConstructor, "basicShipAiConstructor");
            this.autonomousFirerOwner = firerOwner;
            this.autonomousTargetOwner = targetOwner;
            // Diagnostic/enable handles are nullable by design (see field comment).
            this.shipGetWeaponGroupsCopy = shipGetWeaponGroupsCopy;
            this.weaponGroupIsAutofiring = weaponGroupIsAutofiring;
            this.weaponGroupToggleOn = weaponGroupToggleOn;
            this.weaponGroupGetWeaponsCopy = weaponGroupGetWeaponsCopy;
            this.weaponGroupGetAutofirePlugin = weaponGroupGetAutofirePlugin;
            this.weaponGroupGetAIPlugins = weaponGroupGetAIPlugins;
            this.weaponGetRange = weaponGetRange;
            this.shipGetShipAI = shipGetShipAI;
        }

        /**
         * Autonomous engine-ship-AI fire frame. On the first frame this attaches
         * a real engine {@code BasicShipAI} to a non-player ship (repository index 1), sets
         * the firer hostile to the player-bound ship (index 0) via opposing owners, and
         * points its target at the player ship. It then issues NO {@code FIRE} command and
         * applies NO force-fire injection -- the engine's own ship AI decides whether and
         * when to fire during the subsequent advance. {@code weaponFiringFrames} reveals
         * whether the engine AI fired; {@code forceFireFrames} stays 0 in this mode.
         */
        private void prepareAutonomousAiFireFrame() {
            try {
                if (shipSetOwner == null || shipSetShipAI == null || basicShipAiConstructor == null) {
                    throw new HeadlessCombatContextException(
                            "autonomous engine-ship-AI fire support is not configured");
                }
                List<?> ships = asList(invoke(repositoryGetList, repository, "repository.getList", shipClass),
                        "repository.getList");
                if (ships.size() < 2) {
                    throw new HeadlessCombatContextException(
                            "autonomous engine-ship-AI fire requires two ships");
                }
                Object playerShip = ships.get(0);
                Object firer = ships.get(1);
                if (!autonomousSetupDone) {
                    invoke(shipSetOwner, playerShip, "Ship.setOwner(target)",
                            Integer.valueOf(autonomousTargetOwner));
                    invoke(shipSetOwner, firer, "Ship.setOwner(firer)",
                            Integer.valueOf(autonomousFirerOwner));
                    Object shipAi;
                    try {
                        shipAi = basicShipAiConstructor.newInstance(firer);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw new HeadlessCombatContextException(
                                "engine BasicShipAI construction threw",
                                e.getCause() == null ? e : e.getCause());
                    } catch (ReflectiveOperationException e) {
                        throw new HeadlessCombatContextException(
                                "engine BasicShipAI construction failed", e);
                    }
                    invoke(shipSetShipAI, firer, "Ship.setShipAI", shipAi);
                    autonomousFirerShip = firer;
                    autonomousAiAttached = true;
                    diagnoseAndWireAutonomousAutofire(playerShip, firer);
                    autonomousSetupDone = true;
                }
                // Re-assert the hostile target each frame; deliberately no giveCommand(FIRE)
                // and no force-fire injection. The engine ship AI owns the firing decision.
                invoke(shipSetShipTarget, autonomousFirerShip, "Ship.setShipTarget", playerShip);
                lastSourceShip = autonomousFirerShip;
                fireCommandAttempts++;
            } catch (RuntimeException e) {
                recordError(e);
                throw e;
            }
        }

        /**
         * Reads the firer's autonomous weapon-AI wiring once (first frame) and applies the
         * minimal enable the engine's AI needs to pull its own trigger.
         *
         * <p>This answers an open question -- does the engine's own ship AI fire
         * headless, and if not, what is unwired? -- by recording: whether a ship AI is
         * attached; weapon-group count; how many groups are autofiring before and after the
         * enable; how many per-weapon {@code AutofireAIPlugin}s exist; the max weapon range;
         * and the firer-to-target distance / in-range. The only mutation is
         * {@code WeaponGroupAPI.toggleOn()} on groups that already own an autofire plugin --
         * that enables the engine's existing autofire AI; it is NOT a fire command and NOT a
         * force-fire injection, so the per-frame decision to actually fire remains the
         * engine's. The whole method is fail-safe: any reflection failure is recorded into
         * the diagnostic string and never propagated, so the green transport path is
         * preserved.
         */
        private void diagnoseAndWireAutonomousAutofire(Object playerShip, Object firer) {
            try {
                String aiPresent = "unknown";
                if (shipGetShipAI != null) {
                    Object ai = shipGetShipAI.invoke(firer);
                    aiPresent = ai != null ? "present" : "null";
                    if (ai != null) {
                        autonomousShipAiClass = ai.getClass().getName();
                    }
                }
                int groupCount = 0;
                int autofiringBefore = 0;
                int autofiringAfter = 0;
                int weaponsInGroups = 0;
                int autofirePlugins = 0;
                int weaponsWithPlugin = 0;
                float maxRange = 0.0f;
                if (shipGetWeaponGroupsCopy != null) {
                    List<?> groups = (List<?>) shipGetWeaponGroupsCopy.invoke(firer);
                    groupCount = groups.size();
                    for (Object group : groups) {
                        boolean autofiring = weaponGroupIsAutofiring != null
                                && ((Boolean) weaponGroupIsAutofiring.invoke(group)).booleanValue();
                        if (autofiring) {
                            autofiringBefore++;
                        }
                        int pluginCount = 0;
                        if (weaponGroupGetAIPlugins != null) {
                            pluginCount = ((List<?>) weaponGroupGetAIPlugins.invoke(group)).size();
                            autofirePlugins += pluginCount;
                        }
                        if (weaponGroupGetWeaponsCopy != null) {
                            List<?> weapons = (List<?>) weaponGroupGetWeaponsCopy.invoke(group);
                            weaponsInGroups += weapons.size();
                            for (Object weapon : weapons) {
                                if (weaponGroupGetAutofirePlugin != null) {
                                    Object plugin = weaponGroupGetAutofirePlugin.invoke(group, weapon);
                                    if (plugin != null) {
                                        weaponsWithPlugin++;
                                        if (autonomousAutofirePlugin == null) {
                                            autonomousAutofirePlugin = plugin;
                                            autonomousFirerWeapon = weapon;
                                        }
                                    }
                                }
                                if (weaponGetRange != null) {
                                    float range = ((Number) weaponGetRange.invoke(weapon)).floatValue();
                                    if (range > maxRange) {
                                        maxRange = range;
                                    }
                                }
                            }
                        }
                        // Minimal wiring: enable autofire only on groups that already own an
                        // autofire AI plugin. No plugin -> nothing to enable (recorded as the
                        // blocker); the engine's own autofire AI still owns each fire decision.
                        if (!autofiring && pluginCount > 0 && weaponGroupToggleOn != null) {
                            weaponGroupToggleOn.invoke(group);
                        }
                    }
                    for (Object group : groups) {
                        if (weaponGroupIsAutofiring != null
                                && ((Boolean) weaponGroupIsAutofiring.invoke(group)).booleanValue()) {
                            autofiringAfter++;
                        }
                    }
                }
                float distance = Float.NaN;
                if (shipGetLocation != null) {
                    Object playerLoc = shipGetLocation.invoke(playerShip);
                    Object firerLoc = shipGetLocation.invoke(firer);
                    float px = playerLoc.getClass().getField("x").getFloat(playerLoc);
                    float py = playerLoc.getClass().getField("y").getFloat(playerLoc);
                    float fx = firerLoc.getClass().getField("x").getFloat(firerLoc);
                    float fy = firerLoc.getClass().getField("y").getFloat(firerLoc);
                    distance = (float) Math.hypot(px - fx, py - fy);
                    // Resolve the per-frame fire-intent handles lazily from runtime objects
                    // (public AutofireAIPlugin / WeaponAPI methods on their concrete classes).
                    autonomousTargetShip = playerShip;
                    if (autonomousAutofirePlugin != null) {
                        autonomousAutofirePluginClass = autonomousAutofirePlugin.getClass().getName();
                        autofirePluginShouldFire =
                                autonomousAutofirePlugin.getClass().getMethod("shouldFire");
                        autofirePluginGetTargetShip =
                                autonomousAutofirePlugin.getClass().getMethod("getTargetShip");
                    }
                    if (autonomousFirerWeapon != null) {
                        weaponDistanceFromArc = autonomousFirerWeapon.getClass()
                                .getMethod("distanceFromArc", playerLoc.getClass());
                    }
                }
                boolean inRange = !Float.isNaN(distance) && maxRange > 0.0f && distance <= maxRange;
                autonomousFirerAiFlags = readShipAiFlags(firer);
                autonomousAiDiagnostic = "ship_ai=" + aiPresent
                        + ";groups=" + groupCount
                        + ";autofiring_before=" + autofiringBefore
                        + ";autofiring_after=" + autofiringAfter
                        + ";weapons_in_groups=" + weaponsInGroups
                        + ";autofire_plugins=" + autofirePlugins
                        + ";weapons_with_plugin=" + weaponsWithPlugin
                        + ";max_range=" + maxRange
                        + ";distance=" + distance
                        + ";in_range=" + inRange
                        + ";ship_ai_class=" + autonomousShipAiClass
                        + ";autofire_plugin_class=" + autonomousAutofirePluginClass
                        + ";ai_flags_set=[" + autonomousFirerAiFlags + "]";
            } catch (Throwable t) {
                String detail = t.getMessage() == null ? "" : t.getMessage();
                autonomousAiDiagnostic = "diagnostic_error=" + t.getClass().getName() + ":" + detail;
            }
        }

        /**
         * Reads which {@code ShipwideAIFlags.AIFlags} are set on the firer, via
         * the public {@code ShipAPI.getAIFlags()} and {@code ShipwideAIFlags.hasFlag(AIFlags)}.
         * The autofire decision can consult ship-wide AI intent (e.g. an attack-run / conserve
         * state); an empty set is itself informative (the freshly-attached AI has not committed
         * to an attack). Fully observational and fail-soft: any reflection miss yields "" or
         * "error:...", never an exception. Returns a comma-joined list of set flag names.
         */
        private String readShipAiFlags(Object firer) {
            try {
                Method getAIFlags = firer.getClass().getMethod("getAIFlags");
                Object flags = getAIFlags.invoke(firer);
                if (flags == null) {
                    return "none";
                }
                // Derive the AIFlags enum class from the live ShipwideAIFlags object's own
                // hasFlag(AIFlags) parameter type, NOT Class.forName -- the game API classes
                // are loaded by the scout's child classloader, so Class.forName against this
                // class's loader misses them (observed: ClassNotFoundException, HSC-T905-INTENT).
                Method hasFlag = null;
                Class<?> aiFlagsEnum = null;
                for (Method candidate : flags.getClass().getMethods()) {
                    if ("hasFlag".equals(candidate.getName())
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0].isEnum()) {
                        hasFlag = candidate;
                        aiFlagsEnum = candidate.getParameterTypes()[0];
                        break;
                    }
                }
                if (hasFlag == null || aiFlagsEnum == null) {
                    return "no_hasflag";
                }
                Object[] values = aiFlagsEnum.getEnumConstants();
                if (values == null) {
                    return "enum_unavailable";
                }
                StringBuilder set = new StringBuilder();
                for (Object value : values) {
                    if (((Boolean) hasFlag.invoke(flags, value)).booleanValue()) {
                        if (set.length() > 0) {
                            set.append(',');
                        }
                        set.append(value);
                    }
                }
                return set.length() == 0 ? "none" : set.toString();
            } catch (Throwable t) {
                return "error:" + t.getClass().getSimpleName();
            }
        }

        /**
         * Samples the engine autofire AI's own per-frame decision for the firer's autofire
         * weapon: whether {@code AutofireAIPlugin.shouldFire()} is
         * true, whether it has acquired a target ship, and whether the target lies inside the
         * weapon's firing arc ({@code WeaponAPI.distanceFromArc <= 0}). This turns "the AI did
         * not fire" into an exact reason. Purely observational -- no fire command is issued.
         */
        private void recordAutonomousFireIntent() {
            if (autonomousAutofirePlugin == null) {
                return;
            }
            try {
                autonomousFireIntentSamples++;
                boolean shouldFire = autofirePluginShouldFire != null
                        && ((Boolean) autofirePluginShouldFire.invoke(autonomousAutofirePlugin)).booleanValue();
                if (shouldFire) {
                    autonomousShouldFireFrames++;
                }
                autonomousLastShouldFire = shouldFire;
                boolean hasTargetShip = autofirePluginGetTargetShip != null
                        && autofirePluginGetTargetShip.invoke(autonomousAutofirePlugin) != null;
                if (hasTargetShip) {
                    autonomousTargetShipFrames++;
                }
                autonomousLastTargetShip = hasTargetShip;
                if (weaponDistanceFromArc != null
                        && shipGetLocation != null
                        && autonomousTargetShip != null
                        && autonomousFirerWeapon != null) {
                    Object targetLoc = shipGetLocation.invoke(autonomousTargetShip);
                    float dist = ((Number) weaponDistanceFromArc.invoke(autonomousFirerWeapon, targetLoc)).floatValue();
                    autonomousLastDistFromArc = dist;
                    if (dist <= 0.0f) {
                        autonomousInArcFrames++;
                    }
                }
            } catch (Throwable t) {
                // Fire-intent sampling is observational; never break the green step.
            }
        }

        private void recordWeaponFiringFrame() {
            if (lastSourceShip == null) {
                return;
            }
            try {
                List<?> weapons = asList(invoke(shipGetAllWeapons, lastSourceShip, "Ship.getAllWeapons"),
                        "Ship.getAllWeapons");
                for (Object weapon : weapons) {
                    if (weapon != null
                            && ((Boolean) invoke(weaponIsFiring, weapon, "Weapon.isFiring")).booleanValue()) {
                        weaponFiringFrames++;
                        return;
                    }
                }
            } catch (RuntimeException e) {
                recordError(e);
                throw e;
            }
        }

        private void recordError(RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            lastErrorType = cause.getClass().getName();
            lastErrorMessage = cause.getMessage() == null ? "" : cause.getMessage();
        }

        private EngineDrivenFireTelemetry telemetry() {
            String diagnostic = autonomousAiDiagnostic;
            if (diagnostic != null && !diagnostic.isEmpty() && autonomousFireIntentSamples > 0) {
                diagnostic = diagnostic
                        + ";intent_samples=" + autonomousFireIntentSamples
                        + ";should_fire_frames=" + autonomousShouldFireFrames
                        + ";target_ship_frames=" + autonomousTargetShipFrames
                        + ";in_arc_frames=" + autonomousInArcFrames
                        + ";last_should_fire=" + autonomousLastShouldFire
                        + ";last_target_ship=" + autonomousLastTargetShip
                        + ";last_dist_from_arc=" + autonomousLastDistFromArc;
            }
            return new EngineDrivenFireTelemetry(
                    fireCommandAttempts,
                    fireCommandSuccesses,
                    forceFireFrames,
                    weaponFiringFrames,
                    lastErrorType,
                    lastErrorMessage,
                    diagnostic);
        }
    }

    /**
     * Minimal command-authority maneuver driver. Unlike the
     * fire driver it needs no weapon/force-fire machinery: it issues the public
     * {@code ShipAPI.giveCommand(ShipCommand.ACCELERATE, null, 0)} on the commanded ship
     * (repository index 0, the player-bound ship the roster install binds) and lets the
     * engine's own movement integration apply the acceleration. The {@code ACCELERATE}
     * command takes no parameter object, so {@code param} is {@code null} and the group arg
     * is {@code 0} — the exact shape proven on the real engine by {@code EngineCombatBinding}.
     */
    private static final class EngineDrivenManeuverDriver {
        private final Object repository;
        private final Method repositoryGetList;
        private final Class<?> shipClass;
        private final Method shipGiveCommand;
        private final Object shipCommandAccelerate;

        private EngineDrivenManeuverDriver(
                Object repository,
                Method repositoryGetList,
                Class<?> shipClass,
                Method shipGiveCommand,
                Object shipCommandAccelerate) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.repositoryGetList = Objects.requireNonNull(repositoryGetList, "repositoryGetList");
            this.shipClass = Objects.requireNonNull(shipClass, "shipClass");
            this.shipGiveCommand = Objects.requireNonNull(shipGiveCommand, "shipGiveCommand");
            this.shipCommandAccelerate =
                    Objects.requireNonNull(shipCommandAccelerate, "shipCommandAccelerate");
        }

        private void applyCommandAuthorityManeuver() {
            List<?> ships = asList(invoke(repositoryGetList, repository, "repository.getList", shipClass),
                    "repository.getList");
            if (ships.isEmpty()) {
                throw new HeadlessCombatContextException(
                        "engine command-authority maneuver requires at least one ship");
            }
            Object commandedShip = ships.get(0);
            invoke(shipGiveCommand, commandedShip, "Ship.giveCommand(ACCELERATE)",
                    shipCommandAccelerate, null, 0);
        }
    }

    private static final class CombatReadinessPluginConfig {
        private final Class<?> settingsClass;
        private final String pluginKey;
        private final Field pluginMapField;
        private final Field cacheField;
        private final Method getter;
        private final Class<?> combatReadinessPluginInterface;
        private final Object pluginInstance;

        private CombatReadinessPluginConfig(
                Class<?> settingsClass,
                String pluginKey,
                Field pluginMapField,
                Field cacheField,
                Method getter,
                Class<?> combatReadinessPluginInterface,
                Object pluginInstance) {
            this.settingsClass = Objects.requireNonNull(settingsClass, "settingsClass");
            this.pluginKey = Objects.requireNonNull(pluginKey, "pluginKey");
            this.pluginMapField = Objects.requireNonNull(pluginMapField, "pluginMapField");
            this.cacheField = Objects.requireNonNull(cacheField, "cacheField");
            this.getter = Objects.requireNonNull(getter, "getter");
            this.combatReadinessPluginInterface =
                    Objects.requireNonNull(combatReadinessPluginInterface, "combatReadinessPluginInterface");
            this.pluginInstance = Objects.requireNonNull(pluginInstance, "pluginInstance");
            if (this.pluginKey.isEmpty()) {
                throw new IllegalArgumentException("pluginKey must be non-empty");
            }
            if (!Modifier.isStatic(this.pluginMapField.getModifiers())
                    || !Map.class.isAssignableFrom(this.pluginMapField.getType())) {
                throw new IllegalArgumentException("pluginMapField must be a static Map field");
            }
            if (!Modifier.isStatic(this.cacheField.getModifiers())
                    || !this.combatReadinessPluginInterface.isAssignableFrom(this.cacheField.getType())) {
                throw new IllegalArgumentException(
                        "cacheField must be a static combat readiness plugin field");
            }
            if (!Modifier.isStatic(this.getter.getModifiers())
                    || this.getter.getParameterTypes().length != 0
                    || !this.combatReadinessPluginInterface.isAssignableFrom(this.getter.getReturnType())) {
                throw new IllegalArgumentException(
                        "getter must be a static no-arg combat readiness plugin getter");
            }
            if (!this.combatReadinessPluginInterface.isInstance(this.pluginInstance)) {
                throw new IllegalArgumentException(
                        "pluginInstance must implement combatReadinessPluginInterface");
            }
            this.pluginMapField.setAccessible(true);
            this.cacheField.setAccessible(true);
            this.getter.setAccessible(true);
        }
    }

    private static final class StaticObjectFieldConfig {
        private final Field field;
        private final Object seedValue;

        private StaticObjectFieldConfig(Field field, Object seedValue) {
            this.field = Objects.requireNonNull(field, "field");
            this.seedValue = Objects.requireNonNull(seedValue, "seedValue");
            if (!Modifier.isStatic(this.field.getModifiers())) {
                throw new IllegalArgumentException("field must be static");
            }
            if (this.field.getType().isPrimitive()) {
                throw new IllegalArgumentException("field must be an object field");
            }
            if (!this.field.getType().isInstance(this.seedValue)) {
                throw new IllegalArgumentException("seedValue must be assignable to field type");
            }
            this.field.setAccessible(true);
        }
    }

    private static final class HullBuiltinModsConfig {
        private final Object hullSpec;
        private final Method getBuiltInMods;

        private HullBuiltinModsConfig(Object hullSpec, Method getBuiltInMods) {
            this.hullSpec = Objects.requireNonNull(hullSpec, "hullSpec");
            this.getBuiltInMods = Objects.requireNonNull(getBuiltInMods, "getBuiltInMods");
            if (this.getBuiltInMods.getParameterTypes().length != 0
                    || !List.class.isAssignableFrom(this.getBuiltInMods.getReturnType())) {
                throw new IllegalArgumentException("getBuiltInMods must be a no-arg List getter");
            }
            this.getBuiltInMods.setAccessible(true);
        }
    }

    private static final class VariantTagConfig {
        private final Object variantSpec;
        private final Method hasTag;
        private final Method addTag;
        private final Method removeTag;
        private final String tag;

        private VariantTagConfig(
                Object variantSpec,
                Method hasTag,
                Method addTag,
                Method removeTag,
                String tag) {
            this.variantSpec = Objects.requireNonNull(variantSpec, "variantSpec");
            this.hasTag = Objects.requireNonNull(hasTag, "hasTag");
            this.addTag = Objects.requireNonNull(addTag, "addTag");
            this.removeTag = Objects.requireNonNull(removeTag, "removeTag");
            this.tag = Objects.requireNonNull(tag, "tag");
            if (this.tag.isEmpty()) {
                throw new IllegalArgumentException("tag must be non-empty");
            }
            if (this.hasTag.getParameterTypes().length != 1
                    || this.hasTag.getParameterTypes()[0] != String.class
                    || this.hasTag.getReturnType() != Boolean.TYPE) {
                throw new IllegalArgumentException("hasTag must be boolean(String)");
            }
            if (this.addTag.getParameterTypes().length != 1
                    || this.addTag.getParameterTypes()[0] != String.class) {
                throw new IllegalArgumentException("addTag must accept String");
            }
            if (this.removeTag.getParameterTypes().length != 1
                    || this.removeTag.getParameterTypes()[0] != String.class) {
                throw new IllegalArgumentException("removeTag must accept String");
            }
            this.hasTag.setAccessible(true);
            this.addTag.setAccessible(true);
            this.removeTag.setAccessible(true);
        }
    }

    private static final class ModManagerConfig {
        private final Class<?> modManagerClass;
        private final Method getInstance;
        private final Method getEnabledModPlugins;
        private final Field pluginsField;
        private final Class<?> modPluginInterface;

        private ModManagerConfig(
                Class<?> modManagerClass,
                Method getInstance,
                Method getEnabledModPlugins,
                Field pluginsField,
                Class<?> modPluginInterface) {
            this.modManagerClass = Objects.requireNonNull(modManagerClass, "modManagerClass");
            this.getInstance = Objects.requireNonNull(getInstance, "getInstance");
            this.getEnabledModPlugins = Objects.requireNonNull(getEnabledModPlugins, "getEnabledModPlugins");
            this.pluginsField = Objects.requireNonNull(pluginsField, "pluginsField");
            this.modPluginInterface = Objects.requireNonNull(modPluginInterface, "modPluginInterface");
            this.pluginsField.setAccessible(true);
        }
    }

    private static final class TextureConfig {
        private final Class<?> textureRegistryClass;
        private final Method textureMapGetter;
        private final Constructor<?> textureObjectConstructor;

        private TextureConfig(
                Class<?> textureRegistryClass,
                Method textureMapGetter,
                Constructor<?> textureObjectConstructor) {
            this.textureRegistryClass = Objects.requireNonNull(textureRegistryClass, "textureRegistryClass");
            this.textureMapGetter = Objects.requireNonNull(textureMapGetter, "textureMapGetter");
            this.textureObjectConstructor =
                    Objects.requireNonNull(textureObjectConstructor, "textureObjectConstructor");
            this.textureMapGetter.setAccessible(true);
            this.textureObjectConstructor.setAccessible(true);
        }
    }

    private static final class RenderNullTextureConfig {
        private final Class<?> spriteClass;
        private final Method getTexture;
        private final Class<?> textureClass;
        private final Constructor<?> stubConstructor;
        private final Method texturePathGetter;

        private RenderNullTextureConfig(
                Class<?> spriteClass,
                Method getTexture,
                Class<?> textureClass,
                Constructor<?> stubConstructor,
                Method texturePathGetter) {
            this.spriteClass = Objects.requireNonNull(spriteClass, "spriteClass");
            this.getTexture = Objects.requireNonNull(getTexture, "getTexture");
            this.textureClass = Objects.requireNonNull(textureClass, "textureClass");
            this.stubConstructor = Objects.requireNonNull(stubConstructor, "stubConstructor");
            this.texturePathGetter = Objects.requireNonNull(texturePathGetter, "texturePathGetter");
            // Descriptor-grade pin of the render-null boundary the agent bypass rewrites:
            // Sprite.getTexture()Ltexture; -> stub texture(int,int,String) whose path getter
            // returns the "00.png" sentinel the NPE site requires.
            if (this.getTexture.getDeclaringClass() != this.spriteClass) {
                throw new IllegalArgumentException("getTexture must be declared on spriteClass");
            }
            if (this.getTexture.getParameterTypes().length != 0
                    || this.getTexture.getReturnType() != this.textureClass) {
                throw new IllegalArgumentException("getTexture must be a no-arg getter returning textureClass");
            }
            if (this.stubConstructor.getDeclaringClass() != this.textureClass) {
                throw new IllegalArgumentException("stubConstructor must be declared on textureClass");
            }
            Class<?>[] params = this.stubConstructor.getParameterTypes();
            if (params.length != 3
                    || params[0] != Integer.TYPE
                    || params[1] != Integer.TYPE
                    || params[2] != String.class) {
                throw new IllegalArgumentException("stubConstructor must be (int,int,String)");
            }
            if (this.texturePathGetter.getDeclaringClass() != this.textureClass) {
                throw new IllegalArgumentException("texturePathGetter must be declared on textureClass");
            }
            if (this.texturePathGetter.getParameterTypes().length != 0
                    || this.texturePathGetter.getReturnType() != String.class) {
                throw new IllegalArgumentException("texturePathGetter must be a no-arg String getter");
            }
            this.getTexture.setAccessible(true);
            this.stubConstructor.setAccessible(true);
            this.texturePathGetter.setAccessible(true);
        }

        private Object newStub() {
            try {
                return stubConstructor.newInstance(
                        Integer.valueOf(0), Integer.valueOf(0), RENDER_NULL_SPRITE_TEXTURE_STUB_PATH);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new HeadlessCombatContextException("render-null texture stub construction threw", cause);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                throw new HeadlessCombatContextException("render-null texture stub construction failed", e);
            }
        }
    }

    /**
     * Descriptor-grade pin of the headless ScriptStore content-loader boundary:
     * the static {@code org.codehaus.janino.JavaSourceClassLoader} field on the engine's
     * {@code com.fs.starfarer.loading.scripts.ScriptStore}, the loader ctor
     * {@code (ClassLoader,File[],String)}, the engine parent loader, and the source root(s). The field
     * is pinned <em>by type</em> (its field name is non-ASCII), matching the proven scout prime.
     */
    private static final class ScriptStoreContentLoaderConfig {
        private final Class<?> scriptStoreClass;
        private final Field janinoLoaderField;
        private final Class<?> javaSourceClassLoaderClass;
        private final Constructor<?> javaSourceClassLoaderConstructor;
        private final ClassLoader engineParent;
        private final java.io.File[] sourceRoots;
        private final String charset;

        private ScriptStoreContentLoaderConfig(
                Class<?> scriptStoreClass,
                Field janinoLoaderField,
                Class<?> javaSourceClassLoaderClass,
                Constructor<?> javaSourceClassLoaderConstructor,
                ClassLoader engineParent,
                java.io.File[] sourceRoots,
                String charset) {
            this.scriptStoreClass = Objects.requireNonNull(scriptStoreClass, "scriptStoreClass");
            this.janinoLoaderField = Objects.requireNonNull(janinoLoaderField, "janinoLoaderField");
            this.javaSourceClassLoaderClass =
                    Objects.requireNonNull(javaSourceClassLoaderClass, "javaSourceClassLoaderClass");
            this.javaSourceClassLoaderConstructor =
                    Objects.requireNonNull(javaSourceClassLoaderConstructor, "javaSourceClassLoaderConstructor");
            this.engineParent = Objects.requireNonNull(engineParent, "engineParent");
            Objects.requireNonNull(sourceRoots, "sourceRoots");
            this.charset = Objects.requireNonNull(charset, "charset");
            if (!Modifier.isStatic(this.janinoLoaderField.getModifiers())) {
                throw new IllegalArgumentException("janinoLoaderField must be static");
            }
            if (this.janinoLoaderField.getDeclaringClass() != this.scriptStoreClass) {
                throw new IllegalArgumentException("janinoLoaderField must be declared on scriptStoreClass");
            }
            if (this.janinoLoaderField.getType() != this.javaSourceClassLoaderClass) {
                throw new IllegalArgumentException(
                        "janinoLoaderField type must be javaSourceClassLoaderClass");
            }
            if (this.javaSourceClassLoaderConstructor.getDeclaringClass() != this.javaSourceClassLoaderClass) {
                throw new IllegalArgumentException(
                        "javaSourceClassLoaderConstructor must be declared on javaSourceClassLoaderClass");
            }
            Class<?>[] params = this.javaSourceClassLoaderConstructor.getParameterTypes();
            if (params.length != 3
                    || params[0] != ClassLoader.class
                    || params[1] != java.io.File[].class
                    || params[2] != String.class) {
                throw new IllegalArgumentException(
                        "javaSourceClassLoaderConstructor must be (ClassLoader,File[],String)");
            }
            if (sourceRoots.length == 0) {
                throw new IllegalArgumentException("sourceRoots must be non-empty");
            }
            java.io.File[] copy = new java.io.File[sourceRoots.length];
            for (int i = 0; i < sourceRoots.length; i++) {
                java.io.File root = Objects.requireNonNull(sourceRoots[i], "sourceRoots entry");
                if (!root.isDirectory()) {
                    throw new IllegalArgumentException("sourceRoots entry is not a directory: " + root);
                }
                copy[i] = root;
            }
            this.sourceRoots = copy;
            if (this.charset.isEmpty()) {
                throw new IllegalArgumentException("charset must be non-empty");
            }
            this.janinoLoaderField.setAccessible(true);
            this.javaSourceClassLoaderConstructor.setAccessible(true);
        }

        private Object newContentLoader() {
            try {
                return javaSourceClassLoaderConstructor.newInstance(
                        engineParent, sourceRoots.clone(), charset);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new HeadlessCombatContextException("scriptstore content-loader construction threw", cause);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                throw new HeadlessCombatContextException("scriptstore content-loader construction failed", e);
            }
        }
    }

    private static final class CombatUiConfig {
        private final Object engine;
        private final Class<?> combatUiInterface;
        private final Method getCombatUi;
        private final Method setCombatUi;
        private final Object rawMessageWidget;
        private final Constructor<?> faderConstructor;

        private CombatUiConfig(Object engine, Class<?> combatUiInterface, Method getCombatUi, Method setCombatUi,
                Object rawMessageWidget, Constructor<?> faderConstructor) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.combatUiInterface = Objects.requireNonNull(combatUiInterface, "combatUiInterface");
            this.getCombatUi = Objects.requireNonNull(getCombatUi, "getCombatUi");
            this.setCombatUi = Objects.requireNonNull(setCombatUi, "setCombatUi");
            if ((rawMessageWidget == null) != (faderConstructor == null)) {
                throw new IllegalArgumentException(
                        "rawMessageWidget and faderConstructor must be provided together");
            }
            this.rawMessageWidget = rawMessageWidget;
            this.faderConstructor = faderConstructor;
        }
    }

    private static final class DebrisViewportConfig {
        private final Object engine;
        private final Class<?> viewportClass;
        private final Class<?> debrisSystemClass;
        private final Method getViewport;
        private final Method getDebrisSystem;
        private final Method setEverythingNearViewport;
        private final Method isEverythingNearViewport;

        private DebrisViewportConfig(
                Object engine,
                Class<?> viewportClass,
                Class<?> debrisSystemClass,
                Method getViewport,
                Method getDebrisSystem,
                Method setEverythingNearViewport,
                Method isEverythingNearViewport) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.viewportClass = Objects.requireNonNull(viewportClass, "viewportClass");
            this.debrisSystemClass = Objects.requireNonNull(debrisSystemClass, "debrisSystemClass");
            this.getViewport = Objects.requireNonNull(getViewport, "getViewport");
            this.getDebrisSystem = Objects.requireNonNull(getDebrisSystem, "getDebrisSystem");
            this.setEverythingNearViewport =
                    Objects.requireNonNull(setEverythingNearViewport, "setEverythingNearViewport");
            this.isEverythingNearViewport =
                    Objects.requireNonNull(isEverythingNearViewport, "isEverythingNearViewport");
        }
    }

    private static final class CollisionSoundSpecConfig {
        private final Class<?> soundSpecBootstrapClass;
        private final Class<?> soundSpecClass;
        private final Method initializeSoundSpecs;
        private final Field collisionShipsSpecField;

        private CollisionSoundSpecConfig(
                Class<?> soundSpecBootstrapClass,
                Class<?> soundSpecClass,
                Method initializeSoundSpecs,
                Field collisionShipsSpecField) {
            this.soundSpecBootstrapClass =
                    Objects.requireNonNull(soundSpecBootstrapClass, "soundSpecBootstrapClass");
            this.soundSpecClass = Objects.requireNonNull(soundSpecClass, "soundSpecClass");
            this.initializeSoundSpecs = Objects.requireNonNull(initializeSoundSpecs, "initializeSoundSpecs");
            this.collisionShipsSpecField =
                    Objects.requireNonNull(collisionShipsSpecField, "collisionShipsSpecField");
            if (!Modifier.isStatic(this.initializeSoundSpecs.getModifiers())
                    || this.initializeSoundSpecs.getParameterTypes().length != 0
                    || this.initializeSoundSpecs.getReturnType() != Void.TYPE) {
                throw new IllegalArgumentException("collision sound spec initializer must be static void/no-arg");
            }
            if (!Modifier.isStatic(this.collisionShipsSpecField.getModifiers())
                    || this.collisionShipsSpecField.getType() != this.soundSpecClass) {
                throw new IllegalArgumentException("collision sound spec field must be static soundSpecClass");
            }
            this.initializeSoundSpecs.setAccessible(true);
            this.collisionShipsSpecField.setAccessible(true);
        }
    }

    private static final class RosterInstallConfig {
        private final Object engine;
        private final Object repository;
        private final List<?> ships;
        private final Class<?> shipClass;
        private final Method repositoryAdd;
        private final Method repositoryGetList;
        private final Method getObjects;
        private final Method setObjects;
        private final Method getFleetManagers;
        private final Method setFleetManagers;
        private final Method getPlayerShip;
        private final Field playerShipField;
        private final Method setWasRemoved;
        private final Method wasRemoved;

        private RosterInstallConfig(
                Object engine,
                Object repository,
                List<?> ships,
                Class<?> shipClass,
                Method repositoryAdd,
                Method repositoryGetList,
                Method getObjects,
                Method setObjects,
                Method getFleetManagers,
                Method setFleetManagers,
                Method getPlayerShip,
                Field playerShipField,
                Method setWasRemoved,
                Method wasRemoved) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.repository = Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(ships, "ships");
            this.shipClass = Objects.requireNonNull(shipClass, "shipClass");
            this.repositoryAdd = Objects.requireNonNull(repositoryAdd, "repositoryAdd");
            this.repositoryGetList = Objects.requireNonNull(repositoryGetList, "repositoryGetList");
            this.getObjects = Objects.requireNonNull(getObjects, "getObjects");
            this.setObjects = Objects.requireNonNull(setObjects, "setObjects");
            this.getFleetManagers = Objects.requireNonNull(getFleetManagers, "getFleetManagers");
            this.setFleetManagers = Objects.requireNonNull(setFleetManagers, "setFleetManagers");
            this.getPlayerShip = Objects.requireNonNull(getPlayerShip, "getPlayerShip");
            this.playerShipField = Objects.requireNonNull(playerShipField, "playerShipField");
            if (ships.isEmpty()) {
                throw new IllegalArgumentException("ships must be non-empty");
            }
            ArrayList<Object> copy = new ArrayList<>(ships.size());
            for (Object ship : ships) {
                if (!this.shipClass.isInstance(ship)) {
                    throw new IllegalArgumentException("every ship must be an instance of shipClass");
                }
                copy.add(ship);
            }
            this.ships = List.copyOf(copy);
            validateMethod(this.repositoryAdd, 1, "repositoryAdd");
            if (this.repositoryAdd.getReturnType() != Boolean.TYPE
                    && this.repositoryAdd.getReturnType() != Boolean.class) {
                throw new IllegalArgumentException("repositoryAdd must return boolean");
            }
            validateMethod(this.repositoryGetList, 1, "repositoryGetList");
            if (!List.class.isAssignableFrom(this.repositoryGetList.getReturnType())) {
                throw new IllegalArgumentException("repositoryGetList must return List");
            }
            validateMethod(this.getObjects, 0, "getObjects");
            validateMethod(this.setObjects, 1, "setObjects");
            validateMethod(this.getFleetManagers, 0, "getFleetManagers");
            if (!List.class.isAssignableFrom(this.getFleetManagers.getReturnType())) {
                throw new IllegalArgumentException("getFleetManagers must return List");
            }
            validateMethod(this.setFleetManagers, 1, "setFleetManagers");
            if (!List.class.isAssignableFrom(this.setFleetManagers.getParameterTypes()[0])) {
                throw new IllegalArgumentException("setFleetManagers must accept List");
            }
            validateMethod(this.getPlayerShip, 0, "getPlayerShip");
            if (Modifier.isStatic(this.playerShipField.getModifiers())
                    || !this.playerShipField.getType().isAssignableFrom(this.shipClass)) {
                throw new IllegalArgumentException("playerShipField must be an instance ship field");
            }
            this.setWasRemoved = Objects.requireNonNull(setWasRemoved, "setWasRemoved");
            this.wasRemoved = Objects.requireNonNull(wasRemoved, "wasRemoved");
            validateMethod(this.setWasRemoved, 1, "setWasRemoved");
            if (this.setWasRemoved.getParameterTypes()[0] != Boolean.TYPE) {
                throw new IllegalArgumentException("setWasRemoved must accept a primitive boolean");
            }
            validateMethod(this.wasRemoved, 0, "wasRemoved");
            if (this.wasRemoved.getReturnType() != Boolean.TYPE
                    && this.wasRemoved.getReturnType() != Boolean.class) {
                throw new IllegalArgumentException("wasRemoved must return boolean");
            }
            this.setWasRemoved.setAccessible(true);
            this.wasRemoved.setAccessible(true);
            this.repositoryAdd.setAccessible(true);
            this.repositoryGetList.setAccessible(true);
            this.getObjects.setAccessible(true);
            this.setObjects.setAccessible(true);
            this.getFleetManagers.setAccessible(true);
            this.setFleetManagers.setAccessible(true);
            this.getPlayerShip.setAccessible(true);
            this.playerShipField.setAccessible(true);
        }

        private static void validateMethod(Method method, int parameterCount, String label) {
            if (method.getParameterTypes().length != parameterCount) {
                throw new IllegalArgumentException(label + " must have " + parameterCount + " parameter(s)");
            }
        }
    }

    /**
     * One ship of an engine-own deploy roster: the owner side, the real
     * {@code HullVariantSpec} handle, an explicit ship name (W2: keeps the engine off the
     * random-name factory overload), an explicit {@code Vector2f} location + facing
     * (preserves roster determinism through the one-element {@code DeploymentSpec}), and
     * the burn distance handed to {@code spawnFleetMember}.
     */
    public static final class EngineDeployShipSpec {
        final int owner;
        final Object variant;
        final String shipName;
        final Object location;
        final float facing;
        final float burnDist;
        /**
         * this spec's variant reads {@code ShipVariantAPI.isStation()} —
         * the engine's OWN station detection seam (there is no {@code FleetMemberType.STATION};
         * {@code isStation} reads the variant's hull-spec STATION hint, so the member still
         * mints as {@code FleetMemberType.SHIP}). A station spec takes three carve-outs in the
         * deploy lanes: a pre-spawn member-level CR set (the modules copy MEMBER CR at build,
         * so the post-spawn ship-level lift covers the base only), a cloned variant handed to
         * the FleetMember ctor (which stores the registry variant BY REFERENCE — the engine's
         * own station path always clones), and a direct {@code spawnFleetMember} on the fly-in
         * lane instead of {@code addToReserves}/{@code deployAll}.
         */
        final boolean station;
        /**
         * W4 all-skills seat marker: true iff this spec
         * fields a SEAT row (descriptor {@code PilotKind.SEAT}). The deploy lanes key the all-skills
         * guard's mutual exclusion on it — seat+knob ⇒ all-skills captain
         * ({@code AllSkillsAttach}), else the curated {@code OfficerAttach} row. Station and AI
         * specs stay false by construction (guard (c) rides on that).
         */
        final boolean seat;
        /**
         * For a descriptor SEAT spec, the variant id this
         * spec FIELDS (the seat's pick carried across the wrap, else its descriptor row) and the
         * seat's DESCRIPTOR row id — the officer attach takes the seat lookup law
         * ({@link OfficerAttach#maybeAttachSeat}, the client-parity officer transfer) with exactly
         * the ids the reset fly-in announces. Both null for AI / station / legacy specs (the
         * variant-keyed {@link OfficerAttach#maybeAttachByVariant} lane).
         */
        final String seatFieldedVariantId;
        final String seatDescriptorVariantId;

        /** Back-compat mint: a non-station hull (every pre-1286 call site). */
        public EngineDeployShipSpec(
                int owner, Object variant, String shipName, Object location, float facing, float burnDist) {
            this(owner, variant, shipName, location, facing, burnDist, false, false);
        }

        /** Back-compat mint: pre-W4 station call shape (never a seat). */
        public EngineDeployShipSpec(
                int owner, Object variant, String shipName, Object location, float facing,
                float burnDist, boolean station) {
            this(owner, variant, shipName, location, facing, burnDist, station, false);
        }

        public EngineDeployShipSpec(
                int owner, Object variant, String shipName, Object location, float facing,
                float burnDist, boolean station, boolean seat) {
            this(owner, variant, shipName, location, facing, burnDist, station, seat, null, null);
        }

        /**
         * The SEAT mint carrying the fielded + descriptor
         * variant ids for the seat officer lookup. The pair is both-or-neither and only legal on
         * a seat spec (fail-fast: a half pair would silently route the curated lane).
         */
        public EngineDeployShipSpec(
                int owner, Object variant, String shipName, Object location, float facing,
                float burnDist, boolean station, boolean seat, String seatFieldedVariantId,
                String seatDescriptorVariantId) {
            if (station && seat) {
                throw new IllegalArgumentException(
                        "a spec cannot be both station and seat ('" + shipName + "')");
            }
            if ((seatFieldedVariantId == null) != (seatDescriptorVariantId == null)
                    || (seatDescriptorVariantId != null && !seat)) {
                throw new IllegalArgumentException("seat variant ids are both-or-neither and"
                        + " seat-only ('" + shipName + "' seat=" + seat + " fielded="
                        + seatFieldedVariantId + " descriptor=" + seatDescriptorVariantId + ")");
            }
            this.seatFieldedVariantId = seatFieldedVariantId;
            this.seatDescriptorVariantId = seatDescriptorVariantId;
            this.seat = seat;
            this.owner = owner;
            this.variant = Objects.requireNonNull(variant, "variant");
            this.shipName = Objects.requireNonNull(shipName, "shipName");
            if (shipName.trim().isEmpty()) {
                throw new IllegalArgumentException("shipName must be non-blank (W2: random-name overload)");
            }
            this.location = Objects.requireNonNull(location, "location");
            this.facing = facing;
            this.burnDist = burnDist;
            this.station = station;
        }

        /** Whether this spec deploys a STATION (see {@link #station}). */
        public boolean isStation() {
            return station;
        }

        /**
         * This spec's explicit ship name — read by the deploy lanes' own readback diagnostics
         * ({@code RealEngineDemoRoster}'s station census / repo-order assert name the offending
         * hull; the fields stay package-private).
         */
        public String shipName() {
            return shipName;
        }
    }

    private static final class EngineDeployRosterConfig {
        private final Object engine;
        private final Object repository;
        private final List<EngineDeployShipSpec> specs;
        private final Class<?> shipClass;
        private final Method repositoryGetList;
        private final Method getObjects;
        private final Method setObjects;
        private final Method getFleetManagers;
        private final Method setFleetManagers;
        private final Method getFleetManager;
        private final Method addFleet;
        private final Constructor<?> fleetSpecCtor;
        private final Object fleetGoalAttack;
        private final Method getTransferManager;
        private final Method removeListener;
        private final Constructor<?> fleetMemberCtor;
        private final Object fleetMemberTypeShip;
        private final Method memberSetShipName;
        private final Method spawnFleetMember;
        private final Method getDeployedCopy;
        private final Method isShipAlive;
        private final Method setPreDeploy;
        private final Method isPreDeploy;
        private final Method getPlayerShip;
        private final Field playerShipField;
        private final float deployCr;
        private final int[] registerSides; // nullable: derive sides from specs (legacy)
        // Nullable: engine-native batched deployAll fly-in; null = teleport lane.
        private final EngineDeployFlyInConfig flyIn;
        // Nullable: the wave-side engine admiral; null = no admiral.
        private final EngineDeployWaveAdmiralConfig waveAdmiral;

        private EngineDeployRosterConfig(
                Object engine,
                Object repository,
                List<EngineDeployShipSpec> specs,
                Class<?> shipClass,
                Method repositoryGetList,
                Method getObjects,
                Method setObjects,
                Method getFleetManagers,
                Method setFleetManagers,
                Method getFleetManager,
                Method addFleet,
                Constructor<?> fleetSpecCtor,
                Object fleetGoalAttack,
                Method getTransferManager,
                Method removeListener,
                Constructor<?> fleetMemberCtor,
                Object fleetMemberTypeShip,
                Method memberSetShipName,
                Method spawnFleetMember,
                Method getDeployedCopy,
                Method isShipAlive,
                Method setPreDeploy,
                Method isPreDeploy,
                Method getPlayerShip,
                Field playerShipField,
                float deployCr,
                int[] registerSides,
                EngineDeployFlyInConfig flyIn,
                EngineDeployWaveAdmiralConfig waveAdmiral) {
            this.flyIn = flyIn;
            this.waveAdmiral = waveAdmiral;
            this.engine = Objects.requireNonNull(engine, "engine");
            this.repository = Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(specs, "specs");
            // A zero-spec install is legal ONLY under an explicit registerSides
            // (the occupancy-gated N-boot: managers must exist for fly-ins before any hull does).
            // Unset registerSides keeps the legacy fail-closed shape byte-identical.
            if (specs.isEmpty() && registerSides == null) {
                throw new IllegalArgumentException("specs must be non-empty");
            }
            if (registerSides != null) {
                if (registerSides.length == 0) {
                    throw new IllegalArgumentException("registerSides must be non-empty when set");
                }
                for (EngineDeployShipSpec spec : specs) {
                    boolean covered = false;
                    for (int side : registerSides) {
                        if (side == spec.owner) {
                            covered = true;
                            break;
                        }
                    }
                    if (!covered) {
                        throw new IllegalArgumentException("spec '" + spec.shipName + "' owner "
                                + spec.owner + " is not covered by registerSides");
                    }
                }
            }
            this.registerSides = registerSides == null ? null : registerSides.clone();
            this.specs = List.copyOf(specs);
            this.shipClass = Objects.requireNonNull(shipClass, "shipClass");
            this.repositoryGetList = Objects.requireNonNull(repositoryGetList, "repositoryGetList");
            this.getObjects = Objects.requireNonNull(getObjects, "getObjects");
            this.setObjects = Objects.requireNonNull(setObjects, "setObjects");
            this.getFleetManagers = Objects.requireNonNull(getFleetManagers, "getFleetManagers");
            this.setFleetManagers = Objects.requireNonNull(setFleetManagers, "setFleetManagers");
            this.getFleetManager = Objects.requireNonNull(getFleetManager, "getFleetManager");
            this.addFleet = Objects.requireNonNull(addFleet, "addFleet");
            this.fleetSpecCtor = Objects.requireNonNull(fleetSpecCtor, "fleetSpecCtor");
            this.fleetGoalAttack = Objects.requireNonNull(fleetGoalAttack, "fleetGoalAttack");
            this.getTransferManager = Objects.requireNonNull(getTransferManager, "getTransferManager");
            this.removeListener = Objects.requireNonNull(removeListener, "removeListener");
            this.fleetMemberCtor = Objects.requireNonNull(fleetMemberCtor, "fleetMemberCtor");
            this.fleetMemberTypeShip = Objects.requireNonNull(fleetMemberTypeShip, "fleetMemberTypeShip");
            this.memberSetShipName = Objects.requireNonNull(memberSetShipName, "memberSetShipName");
            this.spawnFleetMember = Objects.requireNonNull(spawnFleetMember, "spawnFleetMember");
            this.getDeployedCopy = Objects.requireNonNull(getDeployedCopy, "getDeployedCopy");
            this.isShipAlive = Objects.requireNonNull(isShipAlive, "isShipAlive");
            this.setPreDeploy = Objects.requireNonNull(setPreDeploy, "setPreDeploy");
            this.isPreDeploy = Objects.requireNonNull(isPreDeploy, "isPreDeploy");
            this.getPlayerShip = Objects.requireNonNull(getPlayerShip, "getPlayerShip");
            this.playerShipField = Objects.requireNonNull(playerShipField, "playerShipField");
            // Fail-closed, mirroring MatchDescriptor's own (0,1] cap: the deployed CR is never an
            // out-of-band value, whether it came from DEFAULT_DEPLOY_CR or a threaded descriptor.
            if (!(deployCr > 0f) || deployCr > 1f) {
                throw new IllegalArgumentException("deployCr must be in (0, 1], got: " + deployCr);
            }
            this.deployCr = deployCr;
            validateMethod(this.repositoryGetList, 1, "repositoryGetList");
            if (!List.class.isAssignableFrom(this.repositoryGetList.getReturnType())) {
                throw new IllegalArgumentException("repositoryGetList must return List");
            }
            validateMethod(this.getObjects, 0, "getObjects");
            validateMethod(this.setObjects, 1, "setObjects");
            validateMethod(this.getFleetManagers, 0, "getFleetManagers");
            if (!List.class.isAssignableFrom(this.getFleetManagers.getReturnType())) {
                throw new IllegalArgumentException("getFleetManagers must return List");
            }
            validateMethod(this.setFleetManagers, 1, "setFleetManagers");
            if (!List.class.isAssignableFrom(this.setFleetManagers.getParameterTypes()[0])) {
                throw new IllegalArgumentException("setFleetManagers must accept List");
            }
            validateMethod(this.getFleetManager, 1, "getFleetManager");
            if (this.getFleetManager.getParameterTypes()[0] != Integer.TYPE) {
                throw new IllegalArgumentException("getFleetManager must accept a primitive int side");
            }
            validateMethod(this.addFleet, 2, "addFleet");
            if (!this.addFleet.getParameterTypes()[0]
                    .isAssignableFrom(this.fleetSpecCtor.getDeclaringClass())) {
                throw new IllegalArgumentException(
                        "addFleet's side-spec parameter must accept fleetSpecCtor's class");
            }
            if (!this.addFleet.getParameterTypes()[1].isInstance(this.fleetGoalAttack)) {
                throw new IllegalArgumentException(
                        "fleetGoalAttack must be an instance of addFleet's goal parameter");
            }
            if (this.fleetSpecCtor.getParameterCount() != 1
                    || this.fleetSpecCtor.getParameterTypes()[0] != Integer.TYPE) {
                throw new IllegalArgumentException("fleetSpecCtor must be (int side)");
            }
            validateMethod(this.getTransferManager, 0, "getTransferManager");
            if (this.getTransferManager.getReturnType() == Void.TYPE
                    || this.getTransferManager.getReturnType().isPrimitive()) {
                throw new IllegalArgumentException("getTransferManager must return an object");
            }
            validateMethod(this.removeListener, 1, "removeListener");
            if (this.fleetMemberCtor.getParameterCount() != 3
                    || this.fleetMemberCtor.getParameterTypes()[0] != Integer.TYPE) {
                throw new IllegalArgumentException(
                        "fleetMemberCtor must be (int owner, HullVariantSpec, FleetMemberType)");
            }
            if (!this.fleetMemberCtor.getParameterTypes()[2].isInstance(this.fleetMemberTypeShip)) {
                throw new IllegalArgumentException(
                        "fleetMemberTypeShip must be an instance of the ctor's FleetMemberType parameter");
            }
            validateMethod(this.memberSetShipName, 1, "memberSetShipName");
            if (this.memberSetShipName.getParameterTypes()[0] != String.class) {
                throw new IllegalArgumentException("memberSetShipName must accept a String");
            }
            validateMethod(this.spawnFleetMember, 4, "spawnFleetMember");
            validateMethod(this.getDeployedCopy, 0, "getDeployedCopy");
            if (!List.class.isAssignableFrom(this.getDeployedCopy.getReturnType())) {
                throw new IllegalArgumentException("getDeployedCopy must return List");
            }
            validateMethod(this.isShipAlive, 1, "isShipAlive");
            if (this.isShipAlive.getReturnType() != Boolean.TYPE
                    && this.isShipAlive.getReturnType() != Boolean.class) {
                throw new IllegalArgumentException("isShipAlive must return boolean");
            }
            validateMethod(this.setPreDeploy, 1, "setPreDeploy");
            if (this.setPreDeploy.getParameterTypes()[0] != Boolean.TYPE) {
                throw new IllegalArgumentException("setPreDeploy must accept a primitive boolean");
            }
            validateMethod(this.isPreDeploy, 0, "isPreDeploy");
            if (this.isPreDeploy.getReturnType() != Boolean.TYPE
                    && this.isPreDeploy.getReturnType() != Boolean.class) {
                throw new IllegalArgumentException("isPreDeploy must return boolean");
            }
            validateMethod(this.getPlayerShip, 0, "getPlayerShip");
            if (Modifier.isStatic(this.playerShipField.getModifiers())
                    || !this.playerShipField.getType().isAssignableFrom(this.shipClass)) {
                throw new IllegalArgumentException("playerShipField must be an instance ship field");
            }
            this.repositoryGetList.setAccessible(true);
            this.getObjects.setAccessible(true);
            this.setObjects.setAccessible(true);
            this.getFleetManagers.setAccessible(true);
            this.setFleetManagers.setAccessible(true);
            this.getFleetManager.setAccessible(true);
            this.addFleet.setAccessible(true);
            this.fleetSpecCtor.setAccessible(true);
            this.getTransferManager.setAccessible(true);
            this.removeListener.setAccessible(true);
            this.fleetMemberCtor.setAccessible(true);
            this.memberSetShipName.setAccessible(true);
            this.spawnFleetMember.setAccessible(true);
            this.getDeployedCopy.setAccessible(true);
            this.isShipAlive.setAccessible(true);
            this.setPreDeploy.setAccessible(true);
            this.isPreDeploy.setAccessible(true);
            this.getPlayerShip.setAccessible(true);
            this.playerShipField.setAccessible(true);
        }

        private static void validateMethod(Method method, int parameterCount, String label) {
            if (method.getParameterTypes().length != parameterCount) {
                throw new IllegalArgumentException(label + " must have " + parameterCount + " parameter(s)");
            }
        }
    }

    /**
     * Reflective handles for the engine-native batched fly-in initial deploy
     * (a mirror of the engine's battle-start driver). Optional — a null config keeps the
     * per-ship spawnFleetMember teleport lane byte-identical. {@code mapHeight} is the combat
     * map's height accessor (height = maxY-minY); {@code contextSetStandoffRange}
     * lives on the API {@code BattleCreationContext} ({@code getContext}'s return type).
     */
    /**
     * ADMIRAL: the wave side's engine-native fleet commander.
     *
     * Vanilla installs {@code new AdmiralAI(manager, allyMode)} per side right
     * after {@code addFleet} and calls {@code preCombat()} after
     * {@code deployAll}; retreats ride the engine's own
     * {@code BattleCreationContext.aiRetreatAllowed}, the
     * per-category "traits" delta = {@code fightToTheLast}. All
     * handles reflective (server/src compiles against no game jar).
     */
    private static final class EngineDeployWaveAdmiralConfig {
        private final int side;
        private final Constructor<?> admiralCtor;
        private final Method setAdmiralAI;
        private final Method getAdmiralAI;
        private final Method preCombat;
        private final Method getContext;
        private final Field aiRetreatAllowed;
        private final Field fightToTheLast;
        private final boolean fightToTheLastInitial;

        private EngineDeployWaveAdmiralConfig(
                int side,
                Constructor<?> admiralCtor,
                Method setAdmiralAI,
                Method getAdmiralAI,
                Method preCombat,
                Method getContext,
                Field aiRetreatAllowed,
                Field fightToTheLast,
                boolean fightToTheLastInitial) {
            this.side = side;
            this.admiralCtor = Objects.requireNonNull(admiralCtor, "admiralCtor");
            this.setAdmiralAI = Objects.requireNonNull(setAdmiralAI, "setAdmiralAI");
            this.getAdmiralAI = Objects.requireNonNull(getAdmiralAI, "getAdmiralAI");
            this.preCombat = Objects.requireNonNull(preCombat, "preCombat");
            this.getContext = Objects.requireNonNull(getContext, "getContext");
            this.aiRetreatAllowed = Objects.requireNonNull(aiRetreatAllowed, "aiRetreatAllowed");
            this.fightToTheLast = Objects.requireNonNull(fightToTheLast, "fightToTheLast");
            this.fightToTheLastInitial = fightToTheLastInitial;
            if (this.admiralCtor.getParameterCount() != 2
                    || this.admiralCtor.getParameterTypes()[1] != Boolean.TYPE) {
                throw new IllegalArgumentException(
                        "admiralCtor must be AdmiralAI(CombatFleetManager, boolean)");
            }
            EngineDeployRosterConfig.validateMethod(this.setAdmiralAI, 1, "setAdmiralAI");
            EngineDeployRosterConfig.validateMethod(this.getAdmiralAI, 0, "getAdmiralAI");
            EngineDeployRosterConfig.validateMethod(this.preCombat, 0, "preCombat");
            EngineDeployRosterConfig.validateMethod(this.getContext, 0, "getContext");
            if (this.aiRetreatAllowed.getType() != Boolean.TYPE
                    || this.fightToTheLast.getType() != Boolean.TYPE) {
                throw new IllegalArgumentException(
                        "aiRetreatAllowed / fightToTheLast must be primitive boolean fields");
            }
        }
    }

    private static final class EngineDeployFlyInConfig {
        private final Method addToReserves;
        private final Method setFirstDeployment;
        private final Method deployAll;
        private final Method getShipFor;
        private final Method getReserves;
        private final Method getCombatMap;
        private final Method mapHeight;
        private final Method getStandoffRange;
        private final Method getContext;
        private final Method contextSetStandoffRange;

        private EngineDeployFlyInConfig(
                Method addToReserves,
                Method setFirstDeployment,
                Method deployAll,
                Method getShipFor,
                Method getReserves,
                Method getCombatMap,
                Method mapHeight,
                Method getStandoffRange,
                Method getContext,
                Method contextSetStandoffRange) {
            this.addToReserves = Objects.requireNonNull(addToReserves, "addToReserves");
            this.setFirstDeployment = Objects.requireNonNull(setFirstDeployment, "setFirstDeployment");
            this.deployAll = Objects.requireNonNull(deployAll, "deployAll");
            this.getShipFor = Objects.requireNonNull(getShipFor, "getShipFor");
            this.getReserves = Objects.requireNonNull(getReserves, "getReserves");
            this.getCombatMap = Objects.requireNonNull(getCombatMap, "getCombatMap");
            this.mapHeight = Objects.requireNonNull(mapHeight, "mapHeight");
            this.getStandoffRange = Objects.requireNonNull(getStandoffRange, "getStandoffRange");
            this.getContext = Objects.requireNonNull(getContext, "getContext");
            this.contextSetStandoffRange =
                    Objects.requireNonNull(contextSetStandoffRange, "contextSetStandoffRange");
            EngineDeployRosterConfig.validateMethod(this.addToReserves, 1, "addToReserves");
            EngineDeployRosterConfig.validateMethod(this.setFirstDeployment, 1, "setFirstDeployment");
            if (this.setFirstDeployment.getParameterTypes()[0] != Boolean.TYPE) {
                throw new IllegalArgumentException("setFirstDeployment must accept a primitive boolean");
            }
            EngineDeployRosterConfig.validateMethod(this.deployAll, 1, "deployAll");
            if (this.deployAll.getParameterTypes()[0] != Float.TYPE) {
                throw new IllegalArgumentException("deployAll must accept a primitive float");
            }
            EngineDeployRosterConfig.validateMethod(this.getShipFor, 1, "getShipFor");
            EngineDeployRosterConfig.validateMethod(this.getReserves, 0, "getReserves");
            if (!Collection.class.isAssignableFrom(this.getReserves.getReturnType())) {
                throw new IllegalArgumentException("getReserves must return a Collection");
            }
            EngineDeployRosterConfig.validateMethod(this.getCombatMap, 0, "getCombatMap");
            EngineDeployRosterConfig.validateMethod(this.mapHeight, 0, "mapHeight");
            if (this.mapHeight.getReturnType() != Float.TYPE) {
                throw new IllegalArgumentException("mapHeight must return a primitive float");
            }
            EngineDeployRosterConfig.validateMethod(this.getStandoffRange, 0, "getStandoffRange");
            if (this.getStandoffRange.getReturnType() != Float.TYPE) {
                throw new IllegalArgumentException("getStandoffRange must return a primitive float");
            }
            EngineDeployRosterConfig.validateMethod(this.getContext, 0, "getContext");
            if (this.getContext.getReturnType().isPrimitive()) {
                throw new IllegalArgumentException("getContext must return an object");
            }
            EngineDeployRosterConfig.validateMethod(this.contextSetStandoffRange, 1, "contextSetStandoffRange");
            if (this.contextSetStandoffRange.getParameterTypes()[0] != Float.TYPE) {
                throw new IllegalArgumentException("contextSetStandoffRange must accept a primitive float");
            }
            this.addToReserves.setAccessible(true);
            this.setFirstDeployment.setAccessible(true);
            this.deployAll.setAccessible(true);
            this.getShipFor.setAccessible(true);
            this.getReserves.setAccessible(true);
            this.getCombatMap.setAccessible(true);
            this.mapHeight.setAccessible(true);
            this.getStandoffRange.setAccessible(true);
            this.getContext.setAccessible(true);
            this.contextSetStandoffRange.setAccessible(true);
        }
    }

    private static final class KeyboardKeyDownConfig {
        private final Class<?> keyboardClass;
        private final Field globalLockField;
        private final Field createdField;
        private final Field keyDownBufferField;
        private final Method isKeyDown;
        private final int[] keyCodes;

        private KeyboardKeyDownConfig(
                Class<?> keyboardClass,
                Field globalLockField,
                Field createdField,
                Field keyDownBufferField,
                Method isKeyDown,
                int[] keyCodes) {
            this.keyboardClass = Objects.requireNonNull(keyboardClass, "keyboardClass");
            this.globalLockField = Objects.requireNonNull(globalLockField, "globalLockField");
            this.createdField = Objects.requireNonNull(createdField, "createdField");
            this.keyDownBufferField = Objects.requireNonNull(keyDownBufferField, "keyDownBufferField");
            this.isKeyDown = Objects.requireNonNull(isKeyDown, "isKeyDown");
            Objects.requireNonNull(keyCodes, "keyCodes");
            if (keyCodes.length == 0) {
                throw new IllegalArgumentException("keyCodes must be non-empty");
            }
            if (!Modifier.isStatic(this.globalLockField.getModifiers())) {
                throw new IllegalArgumentException("globalLockField must be static");
            }
            if (!Modifier.isStatic(this.createdField.getModifiers())
                    || this.createdField.getType() != Boolean.TYPE) {
                throw new IllegalArgumentException("createdField must be a static boolean field");
            }
            if (!Modifier.isStatic(this.keyDownBufferField.getModifiers())
                    || !ByteBuffer.class.isAssignableFrom(this.keyDownBufferField.getType())) {
                throw new IllegalArgumentException("keyDownBufferField must be a static ByteBuffer field");
            }
            if (!Modifier.isStatic(this.isKeyDown.getModifiers())
                    || this.isKeyDown.getParameterTypes().length != 1
                    || this.isKeyDown.getParameterTypes()[0] != Integer.TYPE
                    || this.isKeyDown.getReturnType() != Boolean.TYPE) {
                throw new IllegalArgumentException("isKeyDown must be static boolean(int)");
            }
            this.keyCodes = new int[keyCodes.length];
            for (int i = 0; i < keyCodes.length; i++) {
                if (keyCodes[i] < 0) {
                    throw new IllegalArgumentException("keyCodes must be non-negative");
                }
                this.keyCodes[i] = keyCodes[i];
            }
            this.globalLockField.setAccessible(true);
            this.createdField.setAccessible(true);
            this.keyDownBufferField.setAccessible(true);
            this.isKeyDown.setAccessible(true);
        }
    }

    public static final class Builder {
        private String boundaryLabel = "";
        private StaticObjectFieldConfig backgroundSpriteConfig;
        private CombatReadinessPluginConfig combatReadinessPluginConfig;
        private HullBuiltinModsConfig hullBuiltinModsConfig;
        private VariantTagConfig variantTagConfig;
        private ModManagerConfig modManagerConfig;
        private TextureConfig textureConfig;
        private CombatUiConfig combatUiConfig;
        private DebrisViewportConfig debrisViewportConfig;
        private CollisionSoundSpecConfig collisionSoundSpecConfig;
        private RosterInstallConfig rosterInstallConfig;
        private EngineDeployRosterConfig engineDeployRosterConfig;
        private float engineDeployCr = DEFAULT_DEPLOY_CR;
        private int[] engineDeployRegisterSides;
        private EngineDeployFlyInConfig engineDeployFlyIn;
        private EngineDeployWaveAdmiralConfig engineDeployWaveAdmiral;
        private KeyboardKeyDownConfig keyboardKeyDownConfig;
        private RenderNullTextureConfig renderNullTextureConfig;
        private boolean graphicsSubstratePresent;
        private ScriptStoreContentLoaderConfig scriptStoreContentLoaderConfig;
        private EngineDrivenFireDriver fireDriver;
        private EngineDrivenManeuverDriver maneuverDriver;

        public Builder boundaryLabel(String value) {
            this.boundaryLabel = value;
            return this;
        }

        public Builder backgroundSpriteSeed(Field field, Object seedValue) {
            this.backgroundSpriteConfig = new StaticObjectFieldConfig(field, seedValue);
            return this;
        }

        public Builder combatReadinessPluginSeed(
                Class<?> settingsClass,
                String pluginKey,
                Field pluginMapField,
                Field cacheField,
                Method getter,
                Class<?> combatReadinessPluginInterface,
                Object pluginInstance) {
            this.combatReadinessPluginConfig = new CombatReadinessPluginConfig(
                    settingsClass,
                    pluginKey,
                    pluginMapField,
                    cacheField,
                    getter,
                    combatReadinessPluginInterface,
                    pluginInstance);
            return this;
        }

        public Builder hullBuiltinModsSuppression(
                Object hullSpec,
                Method getBuiltInMods) {
            this.hullBuiltinModsConfig = new HullBuiltinModsConfig(hullSpec, getBuiltInMods);
            return this;
        }

        public Builder variantFxDroneTagSeed(
                Object variantSpec,
                Method hasTag,
                Method addTag,
                Method removeTag) {
            this.variantTagConfig =
                    new VariantTagConfig(variantSpec, hasTag, addTag, removeTag, "fx_drone");
            return this;
        }

        public Builder modManagerPluginProxy(
                Class<?> modManagerClass,
                Method getInstance,
                Method getEnabledModPlugins,
                Field pluginsField,
                Class<?> modPluginInterface) {
            this.modManagerConfig = new ModManagerConfig(
                    modManagerClass,
                    getInstance,
                    getEnabledModPlugins,
                    pluginsField,
                    modPluginInterface);
            return this;
        }

        public Builder texturedLineTextureCache(
                Class<?> textureRegistryClass,
                Method textureMapGetter,
                Constructor<?> textureObjectConstructor) {
            this.textureConfig = new TextureConfig(
                    textureRegistryClass,
                    textureMapGetter,
                    textureObjectConstructor);
            return this;
        }

        /** Combat-UI proxy without the message-widget seed (fake-engine contract tests). */
        public Builder combatUiProxy(
                Object engine,
                Class<?> combatUiInterface,
                Method getCombatUi,
                Method setCombatUi) {
            return combatUiProxy(engine, combatUiInterface, getCombatUi, setCombatUi, null, null);
        }

        public Builder combatUiProxy(
                Object engine,
                Class<?> combatUiInterface,
                Method getCombatUi,
                Method setCombatUi,
                Object rawMessageWidget,
                Constructor<?> faderConstructor) {
            this.combatUiConfig = new CombatUiConfig(engine, combatUiInterface, getCombatUi, setCombatUi,
                    rawMessageWidget, faderConstructor);
            return this;
        }

        public Builder debrisViewportSeed(
                Object engine,
                Class<?> viewportClass,
                Class<?> debrisSystemClass,
                Method getViewport,
                Method getDebrisSystem,
                Method setEverythingNearViewport,
                Method isEverythingNearViewport) {
            this.debrisViewportConfig = new DebrisViewportConfig(
                    engine,
                    viewportClass,
                    debrisSystemClass,
                    getViewport,
                    getDebrisSystem,
                    setEverythingNearViewport,
                    isEverythingNearViewport);
            return this;
        }

        public Builder collisionSoundSpecSeed(
                Class<?> soundSpecBootstrapClass,
                Class<?> soundSpecClass,
                Method initializeSoundSpecs,
                Field collisionShipsSpecField) {
            this.collisionSoundSpecConfig = new CollisionSoundSpecConfig(
                    soundSpecBootstrapClass,
                    soundSpecClass,
                    initializeSoundSpecs,
                    collisionShipsSpecField);
            return this;
        }

        /**
         * The engine's own add path (public {@code addObject}
         * queue → private per-frame drain) performs TWO writes per ship — {@code repo.add} AND
         * {@code BaseEntity.setWasRemoved(false)}. {@code BaseEntity} construction starts
         * {@code wasRemoved=true}, and {@code CombatEngine.isShipAlive} requires
         * {@code !wasRemoved()} (outside the pending-add/launch-bay lists), so a roster installed
         * with only the {@code repo.add} half is engine-dead to every {@code isShipAlive}
         * consumer (foe-bind scans, stable-roster snapshots, kill detection). This install
         * completes the contract with the engine's own PUBLIC setter — byte-for-byte the drain's
         * missing write, no behavior fakes; prior per-ship {@code wasRemoved} values are captured
         * and restored on rollback.
         */
        public Builder combatRosterInstall(
                Object engine,
                Object repository,
                List<?> ships,
                Class<?> shipClass,
                Method repositoryAdd,
                Method repositoryGetList,
                Method getObjects,
                Method setObjects,
                Method getFleetManagers,
                Method setFleetManagers,
                Method getPlayerShip,
                Field playerShipField,
                Method setWasRemoved,
                Method wasRemoved) {
            this.rosterInstallConfig = new RosterInstallConfig(
                    engine,
                    repository,
                    ships,
                    shipClass,
                    repositoryAdd,
                    repositoryGetList,
                    getObjects,
                    setObjects,
                    getFleetManagers,
                    setFleetManagers,
                    getPlayerShip,
                    playerShipField,
                    setWasRemoved,
                    wasRemoved);
            return this;
        }

        /**
         * Optional override for the engine-deploy roster's combat-ready CR:
         * thread the validated {@code MatchDescriptor.deployCr()} ({@code (0,1]}) so the
         * deployed CR is descriptor-driven. Unset = {@link #DEFAULT_DEPLOY_CR} (0.7), leaving the
         * no-descriptor default path byte-unchanged. Validated here (fail-closed) to match the
         * descriptor's own cap; must be called before {@link #engineDeployRosterInstall}.
         */
        public Builder engineDeployRosterDeployCr(float deployCr) {
            if (!(deployCr > 0f) || deployCr > 1f) {
                throw new IllegalArgumentException(
                        "engineDeployRosterDeployCr must be in (0, 1], got: " + deployCr);
            }
            this.engineDeployCr = deployCr;
            return this;
        }

        /**
         * Occupancy-gated deploy enabler: sides to {@code addFleet}-register at
         * install even when the spec list carries zero ships on that side, so a later mid-round
         * fly-in ({@code RealEngineDemoRoster.midDeploySeat}) always finds a REAL addFleet-registered
         * manager + transfer manager (W4/W5: {@code getFleetManager}'s mint-on-miss never mints the
         * transfer manager — frame-0 NPE). Unset = derive the sides from the specs exactly as before,
         * byte-identical; with it set, a zero-spec install is legal (the N-boot pre-first-claim
         * battlefield) and the W3 player-seat write is skipped until a ship deploys. Must be called
         * before {@link #engineDeployRosterInstall}.
         */
        public Builder engineDeployRosterRegisterSides(int... sides) {
            Objects.requireNonNull(sides, "sides");
            this.engineDeployRegisterSides = sides.clone();
            return this;
        }

        /**
         * Fly-in initial deploy: opt the engine-deploy
         * roster into the engine's OWN batched battle-start lane — per side
         * {@code addToReserves} → {@code setFirstDeployment(true)} →
         * {@code deployAll((mapHeight−standoff)×0.5)} → {@code setFirstDeployment(false)} →
         * {@code getShipFor} readback (mirroring the engine's battle-start driver: southern/northern
         * first-deployment placement, rank-column X spread, travel-drive burn-in — the vanilla
         * fly-in presentation). Unset = the per-ship {@code spawnFleetMember} teleport lane,
         * byte-identical. Gated by {@code cc.server.deployFlyIn} (default OFF, strict bool —
         * the caller resolves the knob); must be called before {@link #engineDeployRosterInstall}.
         */
        /**
         * ADMIRAL: install the engine's own {@code AdmiralAI} on {@code side}
         * (the wave side) right after {@code addFleet}, write the engine context's
         * {@code aiRetreatAllowed=false} + {@code fightToTheLast=fightToTheLastInitial}, and run
         * {@code preCombat()} after the deploy pass (vanilla's order). Gated by
         * {@code cc.server.waveAdmiral} (the caller resolves the knob; default ON); must be
         * called before {@link #engineDeployRosterInstall}.
         */
        public Builder engineDeployRosterWaveAdmiral(
                int side,
                Constructor<?> admiralCtor,
                Method setAdmiralAI,
                Method getAdmiralAI,
                Method preCombat,
                Method getContext,
                Field aiRetreatAllowed,
                Field fightToTheLast,
                boolean fightToTheLastInitial) {
            this.engineDeployWaveAdmiral = new EngineDeployWaveAdmiralConfig(
                    side, admiralCtor, setAdmiralAI, getAdmiralAI, preCombat, getContext,
                    aiRetreatAllowed, fightToTheLast, fightToTheLastInitial);
            return this;
        }

        public Builder engineDeployRosterFlyIn(
                Method addToReserves,
                Method setFirstDeployment,
                Method deployAll,
                Method getShipFor,
                Method getReserves,
                Method getCombatMap,
                Method mapHeight,
                Method getStandoffRange,
                Method getContext,
                Method contextSetStandoffRange) {
            this.engineDeployFlyIn = new EngineDeployFlyInConfig(
                    addToReserves, setFirstDeployment, deployAll, getShipFor, getReserves,
                    getCombatMap, mapHeight, getStandoffRange, getContext, contextSetStandoffRange);
            return this;
        }

        /**
         * The engine-own deploy lane.
         * Per side an engine-minted {@code CombatFleetManager} registered via the public
         * {@code setFleetManagers} (W4: {@code getFleetManager(int)} mints UNSTORED on a list
         * miss), per ship a real {@code FleetMember(owner, variant, SHIP)} +
         * {@code setShipName} spawned through the engine's own
         * {@code spawnFleetMember -> deploy -> instantiateForCombat -> addObject} path under
         * pre-deploy gating (W1), so the deployment ledgers the end-of-combat judge reads are
         * REAL. Sibling of {@link #combatRosterInstall}; the direct lane stays.
         */
        public Builder engineDeployRosterInstall(
                Object engine,
                Object repository,
                List<EngineDeployShipSpec> specs,
                Class<?> shipClass,
                Method repositoryGetList,
                Method getObjects,
                Method setObjects,
                Method getFleetManagers,
                Method setFleetManagers,
                Method getFleetManager,
                Method addFleet,
                Constructor<?> fleetSpecCtor,
                Object fleetGoalAttack,
                Method getTransferManager,
                Method removeListener,
                Constructor<?> fleetMemberCtor,
                Object fleetMemberTypeShip,
                Method memberSetShipName,
                Method spawnFleetMember,
                Method getDeployedCopy,
                Method isShipAlive,
                Method setPreDeploy,
                Method isPreDeploy,
                Method getPlayerShip,
                Field playerShipField) {
            this.engineDeployRosterConfig = new EngineDeployRosterConfig(
                    engine,
                    repository,
                    specs,
                    shipClass,
                    repositoryGetList,
                    getObjects,
                    setObjects,
                    getFleetManagers,
                    setFleetManagers,
                    getFleetManager,
                    addFleet,
                    fleetSpecCtor,
                    fleetGoalAttack,
                    getTransferManager,
                    removeListener,
                    fleetMemberCtor,
                    fleetMemberTypeShip,
                    memberSetShipName,
                    spawnFleetMember,
                    getDeployedCopy,
                    isShipAlive,
                    setPreDeploy,
                    isPreDeploy,
                    getPlayerShip,
                    playerShipField,
                    this.engineDeployCr,
                    this.engineDeployRegisterSides,
                    this.engineDeployFlyIn,
                    this.engineDeployWaveAdmiral);
            return this;
        }

        public Builder keyboardKeyDownZeroState(
                Class<?> keyboardClass,
                Field globalLockField,
                Field createdField,
                Field keyDownBufferField,
                Method isKeyDown,
                int... keyCodes) {
            this.keyboardKeyDownConfig = new KeyboardKeyDownConfig(
                    keyboardClass,
                    globalLockField,
                    createdField,
                    keyDownBufferField,
                    isKeyDown,
                    keyCodes);
            return this;
        }

        /**
         * Recognize the null {@code com.fs.graphics.Sprite.getTexture()} render-null boundary
         * as a product-owned treatment: pins the descriptor-grade identity
         * ({@code Sprite.getTexture()Ltexture;} -> stub {@code texture(int,int,String)} carrying
         * the {@code "00.png"} path the NPE site requires) and validates the GL-free stub recipe.
         * The headless bypass itself is the trace agent's load-time transform; this gives the
         * product the same boundary identity + stub recipe rather than only the agent.
         */
        public Builder renderNullSpriteTextureStub(
                Class<?> spriteClass,
                Method getTexture,
                Class<?> textureClass,
                Constructor<?> stubConstructor,
                Method texturePathGetter) {
            this.renderNullTextureConfig = new RenderNullTextureConfig(
                    spriteClass,
                    getTexture,
                    textureClass,
                    stubConstructor,
                    texturePathGetter);
            return this;
        }

        /**
         * Declare whether the headless graphics substrate is present.
         * When {@code true}, {@link #install()} <em>skips</em> the L0 render-null
         * treatment: the substrate runs the engine's own {@code ResourceLoaderState.init(Map)} so
         * {@code Sprite.getTexture()} returns real, loader-populated textures (a
         * system-bearing ship was built with no L0 stub under the substrate). The product spawn path runs the
         * substrate, not the per-spawn {@code SpriteTextureStubTransformer}; the L0 stub is demoted to the
         * documented NO-SUBSTRATE FALLBACK (used only when this is left {@code false}). The descriptor-grade
         * boundary identity and the stub recipe ({@link #newRenderNullTextureStub()}) are still owned for
         * the fallback path; only the install-time dependency on L0 is gated off.
         */
        public Builder graphicsSubstratePresent(boolean present) {
            this.graphicsSubstratePresent = present;
            return this;
        }

        /**
         * Promote the proven headless ScriptStore content-loader prime as a
         * product-owned treatment: pins the descriptor-grade identity of the static
         * {@code org.codehaus.janino.JavaSourceClassLoader} field on
         * {@code com.fs.starfarer.loading.scripts.ScriptStore} (located by type) and owns the in-memory
         * loader recipe so {@code Ship.<init>} resolves hullmod effect classes headless. The handles are
         * resolved by the scout from the real engine classes, same pattern as every other treatment;
         * {@code engineParent} is the engine/library class loader and {@code sourceRoots} are the
         * {@code data/scripts} source root(s) (the {@code starsector-core} dir). Charset is
         * {@link #SCRIPTSTORE_CONTENT_LOADER_CHARSET}.
         */
        public Builder scriptStoreContentLoaderPrime(
                Class<?> scriptStoreClass,
                Field janinoLoaderField,
                Class<?> javaSourceClassLoaderClass,
                Constructor<?> javaSourceClassLoaderConstructor,
                ClassLoader engineParent,
                java.io.File[] sourceRoots) {
            this.scriptStoreContentLoaderConfig = new ScriptStoreContentLoaderConfig(
                    scriptStoreClass,
                    janinoLoaderField,
                    javaSourceClassLoaderClass,
                    javaSourceClassLoaderConstructor,
                    engineParent,
                    sourceRoots,
                    SCRIPTSTORE_CONTENT_LOADER_CHARSET);
            return this;
        }

        public Builder engineDrivenFireDriver(
                Object repository,
                Method repositoryGetList,
                Class<?> shipClass,
                Method shipSetShipTarget,
                Method shipGetLocation,
                Method shipGiveCommand,
                Object shipCommandFire,
                Method shipGetAllWeapons,
                Method weaponSetForceFireOneFrame,
                Method weaponSetRemainingCooldownTo,
                Method weaponUsesAmmo,
                Method weaponSetAmmo,
                Method weaponIsFiring) {
            this.fireDriver = new EngineDrivenFireDriver(
                    repository,
                    repositoryGetList,
                    shipClass,
                    shipSetShipTarget,
                    shipGetLocation,
                    shipGiveCommand,
                    shipCommandFire,
                    shipGetAllWeapons,
                    weaponSetForceFireOneFrame,
                    weaponSetRemainingCooldownTo,
                    weaponUsesAmmo,
                    weaponSetAmmo,
                    weaponIsFiring);
            return this;
        }

        /**
         * Configure the command-authority maneuver driver:
         * the minimal reflective handles to issue {@code ShipAPI.giveCommand(ACCELERATE,
         * null, 0)} on the commanded ship. Independent of the fire driver — a maneuver
         * round-trip gate needs only this. {@code shipCommandAccelerate} is the resolved
         * {@code com.fs.starfarer.api.combat.ShipCommand.ACCELERATE} enum constant.
         */
        public Builder engineManeuverDriver(
                Object repository,
                Method repositoryGetList,
                Class<?> shipClass,
                Method shipGiveCommand,
                Object shipCommandAccelerate) {
            this.maneuverDriver = new EngineDrivenManeuverDriver(
                    repository,
                    repositoryGetList,
                    shipClass,
                    shipGiveCommand,
                    shipCommandAccelerate);
            return this;
        }

        /**
         * Optional autonomous engine-ship-AI firing support. Must be
         * called after {@link #engineDrivenFireDriver}. Supplies the reflective handles the
         * driver needs to attach a real engine {@code BasicShipAI} to a non-player ship and
         * set opposing owners so the engine's own ship AI fires without any server FIRE
         * command. Firer owner is 1 (hostile), target/player owner is 0.
         */
        public Builder engineAutonomousAiFireSupport(
                Method shipSetOwner,
                Method shipSetShipAI,
                java.lang.reflect.Constructor<?> basicShipAiConstructor,
                Method shipGetWeaponGroupsCopy,
                Method weaponGroupIsAutofiring,
                Method weaponGroupToggleOn,
                Method weaponGroupGetWeaponsCopy,
                Method weaponGroupGetAutofirePlugin,
                Method weaponGroupGetAIPlugins,
                Method weaponGetRange,
                Method shipGetShipAI) {
            if (this.fireDriver == null) {
                throw new IllegalStateException(
                        "engineDrivenFireDriver(...) must be configured before engineAutonomousAiFireSupport(...)");
            }
            this.fireDriver.configureAutonomousAi(
                    shipSetOwner, shipSetShipAI, basicShipAiConstructor, 1, 0,
                    shipGetWeaponGroupsCopy,
                    weaponGroupIsAutofiring,
                    weaponGroupToggleOn,
                    weaponGroupGetWeaponsCopy,
                    weaponGroupGetAutofirePlugin,
                    weaponGroupGetAIPlugins,
                    weaponGetRange,
                    shipGetShipAI);
            return this;
        }

        public HeadlessCombatContext build() {
            return new HeadlessCombatContext(this);
        }

        public HeadlessCombatContext install() {
            return build().install();
        }
    }
}
