package cc.starsector.client.luna;

import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * The title-screen drain point for a served-settings restore: a restore mark ({@link LunaSyncState#markRestorePending})
 * or a snapshot applied under another engine ({@link LunaSyncState#restoreIfForeign}).
 *
 * <p>WHY a settings.json plugin: the game wraps every {@code "plugins"} entry of the merged settings.json into
 * EVERY combat engine's plugin map, the title screen's own live engine included, so this plugin's
 * {@code advance} runs on the game thread on the first title frame after a match exit. A restore mark an
 * ESC exit or an off-thread watchdog left behind is thereby drained within one title frame, before LunaLib's
 * settings panel can be opened and its Save All write the served values to the player's own settings.
 *
 * <p>WHY the engine compare as well as the mark: an ESC exit from a connect-fail hold-open window raises NO mark
 * (no session ever started, so no teardown and no watchdog runs), and the served values would stay live on the
 * title screen until the next match's init. Each engine is a fresh object (the game rebuilds its combat engine for
 * every battle and hands every plugin of one battle that same engine at init), so a snapshot recorded under an
 * engine that is not this plugin's own belongs to a match that is over, and is restored here. In the match's own
 * engine the predict plugin's init (a mission plugin, initialised before the settings.json plugins) has already
 * restored any earlier snapshot and applied under that same engine, so this plugin finds its own engine there and
 * touches nothing.
 *
 * <p>Frame cost (nothing rides the frame loop without earning it): two volatile reads (the mark, then the snapshot
 * slot) and at most one reference compare per frame; the restore itself runs only on the frame a mark or a foreign
 * snapshot is waiting.
 *
 *
 */
public class LunaSyncDrainPlugin extends BaseEveryFrameCombatPlugin {

    /** The engine this plugin instance was added to; game thread only (init and advance). */
    private CombatEngineAPI engine;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (LunaSyncState.restorePending()) {
            LunaSyncState.drainOnGameThread();
        } else {
            LunaSyncState.restoreIfForeign(engine);
        }
    }
}
