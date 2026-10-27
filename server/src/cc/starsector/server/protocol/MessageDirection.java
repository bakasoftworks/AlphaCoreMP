package cc.starsector.server.protocol;

/** Product-owned direction metadata for network-facing messages. */
public enum MessageDirection {
    CLIENT_REQUEST,
    SERVER_UPDATE,
    SERVER_OWNER_ONLY_UPDATE,
    SERVER_BROADCAST
}
