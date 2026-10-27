package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Headless REAL-ENGINE MEASUREMENT that the AWSS schema-v6 engine-state tier (per-ship
 * flameout / per-engine disabled) actually folds + crosses the wire on the
 * real engine now that {@link RealEngineCombatBindingFactory} binds the six read handles
 * (the build half). The faithful close that build left OWED: "BUILT + game-free GREEN
 * proves compile + no-regression but NOT that the six handles resolve and the v6 tier
 * actually folds + crosses the wire on the real engine".
 *
 * <p>A measurement, NOT a WATCH: it
 * drives the REAL engine headless with no socket, no client, no renderer, so no operator is
 * needed. It mirrors the scout receipt {@code predict_feed_carries_engine_state_tier}
 * ({@code engine_state_count=2}) but exercises {@code server/src} — the same
 * publication path the product pilot serve loop uses, not the scout tooling.
 *
 * <p><b>The exact serve-lane path.</b> The pilot loop captures the AWSS with
 * {@link EngineBackedLiveWorldSnapshotSource#publishNextTick()} (step THEN capture +
 * fold-tiers, incl. {@code foldEngineStateTier}) then encodes it with
 * {@link AuthoritativeWorldSnapshotCodec#writeFrame} directly — there is NO
 * {@code WorldSnapshotPublicationPolicy.govern()} in the pilot / predict lane
 * ({@code PilotIntentSocketServer.exchangeSnapshot} writes the full snapshot, because
 * "the predict lane has no schema negotiation so it should carry"). So this main:
 * <ol>
 *   <li>brings up the real engine + the SAME {@code stableRoster} live source the pilot
 *       loop builds;</li>
 *   <li>per sampled tick: {@code publishNextTick()} → {@code writeFrame(bytes, snap)} (the
 *       exact serve-lane encode) → {@code readSnapshots(bytes)} (the client-side decode);</li>
 *   <li>asserts the decoded frame carries the v6 engine-state tier with one record per ship,
 *       and — re-encoding the decoded frame — that those bytes are identical (the tier
 *       round-trips byte-faithfully through the wire codec).</li>
 * </ol>
 * It also reads {@link EngineCombatBinding#shipEngineStatesOrNull()} raw, to prove the six
 * handles resolved on RC8 (a null return would mean the factory binding silently missed).
 *
 * <p><b>Faithful advance.</b> Each step goes through the same {@link EngineBackedCombatStepBoundary}
 * the serve loop uses; nothing about combat is reimplemented or faked. The only
 * non-faithful surface is the measurement scaffold (the encode/decode round-trip + the
 * per-frame log), which has no engine effect.
 *
 *
 */
public final class RealEngineEngineStateMeasureMain {

    private RealEngineEngineStateMeasureMain() {
    }

    public static void main(String[] args) throws Exception {
        int warmupFrames = 30;
        int sampleFrames = 20;
        int shipCount = 2;
        float fixedDt = SimTickRate.fixedDt(); // canonical DEFAULT_DT (same value the serve loop uses)
        String label = "engine-state-measure";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--warmup": warmupFrames = Integer.parseInt(args[++i]); break;
                case "--sample-frames": sampleFrames = Integer.parseInt(args[++i]); break;
                case "--ship-count": shipCount = Integer.parseInt(args[++i]); break;
                case "--fixed-dt": fixedDt = Float.parseFloat(args[++i]); break;
                case "--label": label = args[++i]; break;
                default: break;
            }
        }

        System.out.println("engine-state-measure-begin label=" + label
                + " warmup=" + warmupFrames
                + " sample_frames=" + sampleFrames
                + " ship_count=" + shipCount
                + " fixed_dt=" + fixedDt);

        // ---- Bring up the real engine (L3 substrate + content init + 2-Mudskipper roster) ----
        EngineCombatBinding binding = RealEngineCombatBindingFactory.create();
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        // ---- The SAME live source the pilot serve loop builds (stable-roster, network ids assigned). ----
        EngineBackedLiveWorldSnapshotSource source = EngineBackedLiveWorldSnapshotSource.stableRoster(
                step, binding, warmupFrames + sampleFrames, shipCount, fixedDt,
                null /*contentRegistry*/, new ShipNetworkIdRegistry(), Map.of(), null /*authoredProjectiles*/);

        // ---- Pre-step raw binding probe: a null return means RealEngineCombatBindingFactory silently
        // failed to bind the six handles (the exact gap the build half closed). Before the engine is stepped
        // its ship list is empty, so this is non-null-but-empty: it proves the HANDLES resolved (the
        // build half) but not yet that there are ships to read — the running-sim count comes next. ----
        List<?> preStepViews = binding.shipEngineStatesOrNull();
        System.out.println("engine-state-measure raw_binding_prestep shipEngineStatesOrNull_nonNull="
                + (preStepViews != null) + " prestep_view_count="
                + (preStepViews == null ? -1 : preStepViews.size()));

        // ---- Warm-up: advance so the deployed ships are settled in combat before sampling. ----
        for (int f = 0; f < warmupFrames; f++) {
            source.publishNextTick();
        }

        // ---- Running-sim raw binding probe (the instant the fold reads it): now the ship list is
        // populated, so this is the authoritative "six handles resolved AND return per-ship state"
        // proof — a non-null list of `shipCount` views. ----
        List<?> rawViews = binding.shipEngineStatesOrNull();
        int rawCount = rawViews == null ? -1 : rawViews.size();
        System.out.println("engine-state-measure raw_binding shipEngineStatesOrNull_nonNull="
                + (rawViews != null) + " raw_view_count=" + rawCount);

        // ---- Sample loop: capture -> serve-lane encode -> client-side decode -> re-encode. ----
        int minCount = Integer.MAX_VALUE;
        int maxCount = Integer.MIN_VALUE;
        boolean anyNonActive = false;
        boolean anyNonZeroMask = false;
        boolean allRoundtripOk = true;
        boolean allEntityIdsValid = true;
        int framesSampled = 0;

        for (int s = 0; s < sampleFrames; s++) {
            AuthoritativeWorldSnapshot snap = source.publishNextTick();
            if (snap == null) {
                throw new IllegalStateException("live source returned null snapshot at sample " + s);
            }

            // (1) serve-lane encode — the exact call PilotIntentSocketServer.exchangeSnapshot makes.
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            int frameBytes = AuthoritativeWorldSnapshotCodec.writeFrame(encoded, snap);
            byte[] wire = encoded.toByteArray();

            // (2) client-side decode — parse the wire bytes back to a fresh snapshot.
            List<AuthoritativeWorldSnapshot> decodedList =
                    AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(wire));
            if (decodedList.size() != 1) {
                throw new IllegalStateException("expected 1 decoded frame, got " + decodedList.size()
                        + " at sample " + s);
            }
            AuthoritativeWorldSnapshot decoded = decodedList.get(0);

            // (3) re-encode the decoded frame and confirm the bytes are identical (v6 tier round-trips).
            ByteArrayOutputStream reEncoded = new ByteArrayOutputStream();
            AuthoritativeWorldSnapshotCodec.writeFrame(reEncoded, decoded);
            boolean roundtripOk = Arrays.equals(wire, reEncoded.toByteArray());
            allRoundtripOk &= roundtripOk;

            // ---- read the decoded v6 engine-state tier ----
            List<ShipEngineStateSnapshot> states = decoded.engineStates();
            int count = decoded.engineStateCount();
            minCount = Math.min(minCount, count);
            maxCount = Math.max(maxCount, count);

            StringBuilder ids = new StringBuilder();
            StringBuilder ctrls = new StringBuilder();
            StringBuilder dis = new StringBuilder();
            StringBuilder perm = new StringBuilder();
            for (int i = 0; i < states.size(); i++) {
                ShipEngineStateSnapshot st = states.get(i);
                if (st.entityId() <= 0L) {
                    allEntityIdsValid = false;
                }
                if (st.controllerState() != ShipEngineStateSnapshot.CONTROLLER_ACTIVE) {
                    anyNonActive = true;
                }
                if (st.disabledMask() != 0 || st.permDisabledMask() != 0) {
                    anyNonZeroMask = true;
                }
                if (i > 0) {
                    ids.append(',');
                    ctrls.append(',');
                    dis.append(',');
                    perm.append(',');
                }
                ids.append(st.entityId());
                ctrls.append(st.controllerState());
                dis.append(st.disabledMask());
                perm.append(st.permDisabledMask());
            }

            System.out.println("engine-state-measure sample=" + s
                    + " tick=" + decoded.simTick()
                    + " engine_state_count=" + count
                    + " entityIds=[" + ids + "]"
                    + " controllerStates=[" + ctrls + "]"
                    + " disabledMasks=[" + dis + "]"
                    + " permMasks=[" + perm + "]"
                    + " frame_bytes=" + frameBytes
                    + " roundtrip_ok=" + roundtripOk);
            framesSampled++;
        }

        boolean tierCrossedEverFrame = framesSampled > 0
                && minCount == shipCount && maxCount == shipCount;

        System.out.println("engine-state-measure-end label=" + label
                + " frames_sampled=" + framesSampled
                + " raw_binding_nonNull=" + (rawViews != null)
                + " raw_view_count=" + rawCount
                + " min_count=" + (framesSampled == 0 ? -1 : minCount)
                + " max_count=" + (framesSampled == 0 ? -1 : maxCount)
                + " expected_count=" + shipCount
                + " tier_crossed_every_frame=" + tierCrossedEverFrame
                + " all_entity_ids_valid=" + allEntityIdsValid
                + " all_roundtrip_byte_identical=" + allRoundtripOk
                + " any_non_active=" + anyNonActive
                + " any_non_zero_mask=" + anyNonZeroMask);
    }
}
