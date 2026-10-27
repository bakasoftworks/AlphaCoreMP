package cc.starsector.server.protocol;

/** Authority classification for a registered network message payload. */
public enum ProtocolDataClass {
    STATIC_SETUP,
    AUTHORITY_INPUT,
    DURABLE_EVENT,
    REPLACEABLE_SNAPSHOT,
    OWNER_PRIVATE_STATE
}
