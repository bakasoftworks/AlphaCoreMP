package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.protocol.ClientRelevanceView;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ReliabilityLane;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import cc.starsector.server.protocol.WorldSnapshotPublicationPolicy;
import cc.starsector.server.runtime.LiveWorldSnapshotSocketPublisher.GovernedReceipt;
import cc.starsector.server.runtime.LiveWorldSnapshotSocketPublisher.Receipt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Single assembled boot entrypoint for a dedicated headless combat server.
 *
 * <p>The project already had every piece of a live server proven individually —
 * the fixed-step inner-step cadence ({@code FixedStepServerLoop} /
 * {@link EngineBackedCombatStepBoundary}), the live per-entity {@code AWSS}
 * source ({@link EngineBackedLiveWorldSnapshotSource}), the registry-governed
 * publish path ({@link WorldSnapshotPublicationPolicy} +
 * {@link LiveWorldSnapshotSocketPublisher#publishGovernedOffLoopback}, closed on
 * the real engine), and a separate-JVM
 * snapshot client. But that <em>governed</em> assembly only ever existed as
 * ad-hoc wiring inside the reverse-engineering scout's generated helper; there was
 * no reusable {@code server/src} surface that boots the whole thing as one
 * callable process. An earlier task committed a capture+verifier <em>artifact</em>
 * of an assembled stream, but not a bootable server. This class is that missing
 * boot entrypoint.
 *
 * <p>{@link #boot(BootConfig, EngineCombatBinding)} owns the assembly the scout
 * used to inline: it builds the engine-backed world/step adapters, admits the
 * engine stream against the {@link SnapshotStreamIdentity} contract (fail-closed
 * <em>before</em> opening any socket), builds the live AWSS source at the
 * configured fixed step, resolves the per-peer {@link WorldSnapshotPublicationPolicy}
 * from the {@link NetworkMessageRegistry}, publishes the live engine frames
 * <em>through</em> that policy to a separate-JVM client, and validates the served
 * receipt against the stream contract (fail-closed <em>after</em> the run). The
 * bytes the peer receives are therefore governed by the registry's negotiated
 * schema and relevance metadata, not by the raw source frame.
 *
 * <p>The class carries no game/engine identity itself; the caller supplies the
 * already-promoted {@link EngineCombatBinding} (the scout for the real
 * engine, a fake reflective binding for the game-free contract). The
 * {@link #boot(BootConfig, LiveWorldSnapshotSource)} overload accepts an
 * already-built source so a scenario-specific source — e.g. the stable-roster
 * lethal source that drives a roster collapse to ship death — can be booted
 * through the same governed path.
 */
public final class DedicatedCombatServerBoot {

    private DedicatedCombatServerBoot() {
    }

    /**
     * Immutable boot configuration. {@code peerMaxSupportedSchemaVersion} is the
     * highest world-snapshot schema the connecting peer can decode; the registry
     * negotiates the served schema down to it. {@code relevanceView} (nullable)
     * scopes each frame to one peer's interest set; pass {@code null} for an
     * unscoped, full-world peer. {@code loopback} selects the self-contained
     * loopback transport; {@code false} requires a real off-loopback IPv4 socket.
     */
    public record BootConfig(
            SnapshotStreamIdentity streamIdentity,
            int expectedFrames,
            float fixedDt,
            int peerMaxSupportedSchemaVersion,
            ClientRelevanceView relevanceView,
            boolean loopback,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) {

        public BootConfig {
            Objects.requireNonNull(streamIdentity, "streamIdentity");
            Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
            Objects.requireNonNull(clientLogPath, "clientLogPath");
            if (expectedFrames < 1) {
                throw new IllegalArgumentException("expectedFrames must be >= 1; got " + expectedFrames);
            }
            if (!Float.isFinite(fixedDt) || fixedDt <= 0.0f) {
                throw new IllegalArgumentException("fixedDt must be a positive finite step; got " + fixedDt);
            }
            if (peerMaxSupportedSchemaVersion < 0) {
                throw new IllegalArgumentException(
                        "peerMaxSupportedSchemaVersion must be >= 0; got " + peerMaxSupportedSchemaVersion);
            }
        }
    }

    /**
     * The assembled boot outcome: the registry-governed publish receipt plus
     * convenience reads of the governed decision (served schema, reliability lane,
     * relevance scoping) and the crossed-stream facts (frame count, source vs
     * served first-frame ship count, state change, socket binding).
     */
    public record BootReceipt(GovernedReceipt governed) {

        public BootReceipt {
            Objects.requireNonNull(governed, "governed");
        }

        public Receipt publish() {
            return governed.publish();
        }

        /** World-snapshot schema the registry negotiated and the server actually served this peer. */
        public int servedSchemaVersion() {
            return governed.servedSchemaVersion();
        }

        /** Registry-granted reliability lane the snapshot stream was published on. */
        public ReliabilityLane reliabilityLane() {
            return governed.reliabilityLane();
        }

        /** True when each frame was scoped to a per-peer relevance view. */
        public boolean relevanceScoped() {
            return governed.relevanceScoped();
        }

        public int framesServed() {
            return governed.publish().framesPublished();
        }

        /** Ship records the live source produced on the first frame, before governance. */
        public int sourceShipCountFirstFrame() {
            return governed.publish().sourceEntityCountFirstFrame();
        }

        /** Ship records actually served to the peer on the first frame, after governance. */
        public int servedShipCountFirstFrame() {
            return governed.publish().entityCountFirstFrame();
        }

        public boolean stateChanges() {
            return governed.publish().stateChanges();
        }

        public boolean loopback() {
            return governed.publish().loopback();
        }

        public String socketHost() {
            return governed.publish().socketHost();
        }

        public int socketPort() {
            return governed.publish().socketPort();
        }
    }

    /**
     * Boot the dedicated server against a live engine binding: assemble the
     * engine-backed step/world adapters, admit the engine stream against the
     * configured {@link SnapshotStreamIdentity}, build the live AWSS source at the
     * configured fixed step, and serve it through the registry-governed publish path.
     */
    public static BootReceipt boot(BootConfig config, EngineCombatBinding binding) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(binding, "binding");
        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        requireAdmittedEngineStream(config, world, step);
        int shipCount = world.roster().shipCount();
        EngineBackedLiveWorldSnapshotSource source =
                new EngineBackedLiveWorldSnapshotSource(
                        step, binding, config.expectedFrames(), shipCount, config.fixedDt());
        return boot(config, source);
    }

    /**
     * Boot the dedicated server against an already-built live snapshot source
     * (e.g. a scenario-specific stable-roster lethal source). Resolves the per-peer
     * policy from the registry, publishes the source through it, and validates the
     * served receipt against the stream contract.
     */
    public static BootReceipt boot(BootConfig config, LiveWorldSnapshotSource source) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(source, "source");
        if (source.expectedFrames() != config.expectedFrames()) {
            throw new IllegalArgumentException(
                    "live snapshot source expectedFrames " + source.expectedFrames()
                            + " does not match boot config expectedFrames " + config.expectedFrames());
        }
        WorldSnapshotPublicationPolicy policy = WorldSnapshotPublicationPolicy.forPeer(
                NetworkMessageRegistry.authoritativeCombat(),
                config.peerMaxSupportedSchemaVersion(),
                config.relevanceView());
        GovernedReceipt governed = config.loopback()
                ? LiveWorldSnapshotSocketPublisher.publishGovernedLoopback(
                        source,
                        policy,
                        config.receiverArtifactPath(),
                        config.clientReceiptPath(),
                        config.clientLogPath())
                : LiveWorldSnapshotSocketPublisher.publishGovernedOffLoopback(
                        source,
                        policy,
                        config.receiverArtifactPath(),
                        config.clientReceiptPath(),
                        config.clientLogPath());
        requireServedStreamMatchesAdmittedStream(governed, config);
        return new BootReceipt(governed);
    }

    private static void requireAdmittedEngineStream(
            BootConfig config, EngineBackedCombatWorldState world, EngineBackedCombatStepBoundary step) {
        if (!step.directInnerStepReady()) {
            throw new IllegalStateException("engine-backed direct inner step is not ready for boot");
        }
        SnapshotStreamIdentity id = config.streamIdentity();
        int shipCount = world.roster().shipCount();
        if (id.hasShipCountRequirement() && id.requiredShipCount() != shipCount) {
            throw new IllegalStateException("boot stream requires " + id.requiredShipCount()
                    + " ships, but engine roster has " + shipCount);
        }
        if (id.hasFrameRequirement() && id.requiredFrames() != config.expectedFrames()) {
            throw new IllegalArgumentException("boot stream requires " + id.requiredFrames()
                    + " frames, but expectedFrames=" + config.expectedFrames());
        }
    }

    private static void requireServedStreamMatchesAdmittedStream(GovernedReceipt governed, BootConfig config) {
        Receipt receipt = governed.publish();
        if (receipt.framesPublished() != config.expectedFrames()) {
            throw new IllegalStateException("dedicated server boot frame count mismatch: expected "
                    + config.expectedFrames() + ", served " + receipt.framesPublished());
        }
        SnapshotStreamIdentity id = config.streamIdentity();
        // Roster contract is checked against the SOURCE (pre-governance) first-frame ship
        // count: schema projection never drops a ship, but a relevance-scoped peer is served
        // fewer ships than the engine produced, so the served count is the wrong basis here.
        if (id.hasShipCountRequirement()
                && receipt.sourceEntityCountFirstFrame() != id.requiredShipCount()) {
            throw new IllegalStateException("dedicated server boot stream requires " + id.requiredShipCount()
                    + " ships, but source frame has " + receipt.sourceEntityCountFirstFrame());
        }
        if (id.requiresStateChanges() && !receipt.stateChanges()) {
            throw new IllegalStateException("dedicated server boot stream did not change state");
        }
    }
}
