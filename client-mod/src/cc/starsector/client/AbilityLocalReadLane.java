package cc.starsector.client;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import cc.starsector.client.magic.MagicCapabilities;
import cc.starsector.client.magic.MagicLocalStateReader;
import cc.starsector.server.runtime.PilotIntentFrame;

/**
 * The kit half of mod-ability contract v1: every frame this lane reads the own ship's
 * {@code customData["cc_client.abilityLocal"]} (an {@code Integer} mask a mod's own subsystem writes on its own
 * ship, bit K = ability slot K is IN or ACTIVE on the local player ship) and publishes it, folded onto
 * {@code heldControls} bits 13..20 by {@link PilotIntentFrame#foldAbilityLocal}, into
 * {@link PredictReconcileCombatPlugin#abilityLocalBits}, which BOTH intent send sites OR into the frame's held bits.
 * The kit links the mod's subsystem library only from {@code cc.starsector.client.magic}, and only when MagicLib is
 * enabled (the reader below); the mod never links the kit: a contract-v1 mod and the kit meet only at this key.
 *
 * <p><b>The MagicLib report:</b> when MagicLib is enabled and its reader's application-load probe passed
 * ({@link MagicCapabilities#readerArmed()}), the same frame also ORs {@link MagicLocalStateReader#bitsFor} (bit K =
 * MagicLib's sorted slot K is IN or ACTIVE) onto the same bits, so a MagicLib ability that no mod wired to this key
 * still reaches the server. An adopter's own value carries the identical bits, so the OR is idempotent. The guard is
 * checked before the reader class is touched, so a game without MagicLib never loads it.
 *
 * <p><b>Why a tail-registered plugin, not a read inside the movement capture:</b> the mission's plugins (the
 * predict plugin among them) sit on the engine's plugin list BEFORE the settings.json plugins (the subsystem
 * library's manager, MagicLib's), and plugins advance after ships, so a read inside the predict plugin's capture
 * would see the value the mod wrote on the PREVIOUS frame. The predict plugin registers this lane from its own
 * {@code advance} (never {@code init}): an {@code addPlugin} made there appends at the list's tail, after the
 * library's manager in the same frame, so the read sees this frame's value. ONE lane per
 * engine: the engine customData key {@link #REGISTERED_KEY} is the guard, so a new engine (a new combat) gets its
 * own lane and a re-entered {@code advance} never stacks a second one.
 *
 * <p><b>Neutral gates:</b> {@link PredictReconcileCombatPlugin#abilityReadOwnOrNull()} returns null under every
 * neutral gate the movement capture honors (a recorded intent source, a command-transfer transit, a latched own
 * death), and null publishes 0, so an ability level never rides up through a window the seat is not flying.
 *
 * <p><b>Value domain:</b> the published bits are 0 or a subset of {@code PilotIntentFrame.ABILITY_SLOTS_MASK}
 * (0x1FE000, bits 13..20; never bit 21 and up). The mod's value is untrusted local data: a non-{@code Integer}
 * publishes 0 (counted in {@link #nonInteger}, logged once with its SimpleName), and any throw on the read
 * publishes 0 (counted in {@link #readErrors}, logged once). Own file per the predict plugin's growth-anchor law:
 * that plugin carries only the field, the accessor, the registration call and the two send-site ORs.
 *
 */
final class AbilityLocalReadLane extends BaseEveryFrameCombatPlugin {

    /** The engine customData key that marks this engine's lane as registered (one lane per engine). */
    static final String REGISTERED_KEY = "cc_client.abilityReader";
    /** The contract v1 key a mod's own subsystem writes on its own ship: an {@code Integer} slot mask. */
    static final String ABILITY_LOCAL_KEY = "cc_client.abilityLocal";

    private final PredictReconcileCombatPlugin predict;
    /** Frames whose value was present but not an {@code Integer} (published 0); 0..Integer.MAX_VALUE. */
    int nonInteger;
    /** Frames whose read threw (published 0); 0..Integer.MAX_VALUE. */
    int readErrors;
    private boolean nonIntegerLogged;
    private boolean readErrorLogged;

    private AbilityLocalReadLane(PredictReconcileCombatPlugin predict) {
        this.predict = predict;
    }

    /**
     * Register this engine's lane once, from the predict plugin's {@code advance} (the tail position is the point:
     * class javadoc). The first registration on an engine also zeroes the published bits, so a value the previous
     * engine's lane left behind never rides the first frames of a new combat. One map lookup per frame otherwise;
     * an engine whose customData map is null (never the game's) registers nothing.
     *
     */
    static void registerOnce(CombatEngineAPI engine, PredictReconcileCombatPlugin predict) {
        Map<String, Object> engineData = engine.getCustomData();
        if (engineData == null || engineData.containsKey(REGISTERED_KEY)) {
            return; // null = a stand-in engine with no customData (the selftest fakes): no lane, the bits stay 0
        }
        AbilityLocalReadLane lane = new AbilityLocalReadLane(predict);
        engineData.put(REGISTERED_KEY, lane);
        predict.abilityLocalBits = 0;
        engine.addPlugin(lane);
        PilotWatchLog.info("ability-local read: lane registered at the plugin list tail");
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        int bits = 0;
        try {
            ShipAPI own = predict.abilityReadOwnOrNull();
            if (own != null) {
                Map<String, Object> shipData = own.getCustomData();
                Object v = shipData != null ? shipData.get(ABILITY_LOCAL_KEY) : null;
                if (v != null && !(v instanceof Integer)) {
                    nonInteger++;
                    if (!nonIntegerLogged) {
                        nonIntegerLogged = true;
                        PilotWatchLog.warn("ability-local read: value is not an Integer type="
                                + v.getClass().getSimpleName() + " -> bits 0 (counted, logged once)");
                    }
                }
                bits = PilotIntentFrame.foldAbilityLocal(v);
                // The MagicLib report, guarded so the reader class never
                // loads without MagicLib; bitsFor never throws and returns slot bits 0..7 only.
                if (MagicCapabilities.readerArmed()) {
                    bits |= (MagicLocalStateReader.bitsFor(own) & 0xFF) << PilotIntentFrame.ABILITY_SLOT_SHIFT;
                }
            }
        } catch (Throwable t) {
            bits = 0;
            readErrors++;
            if (!readErrorLogged) {
                readErrorLogged = true;
                PilotWatchLog.warn("ability-local read: threw " + t.getClass().getSimpleName()
                        + " -> bits 0 (counted, logged once)");
            }
        }
        predict.abilityLocalBits = bits;
    }
}
