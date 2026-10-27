package cc.starsector.server.protocol;

/** Product-owned reliability lane metadata for network-facing messages. */
public enum ReliabilityLane {
    CONTROL_SETUP,
    DURABLE_GAMEPLAY_EVENT,
    REPLACEABLE_SNAPSHOT
}
