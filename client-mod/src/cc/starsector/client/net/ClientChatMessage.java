package cc.starsector.client.net;

import java.util.Objects;

/**
 * One decoded {@code CHTD} chat relay off the live server stream (in-match text chat;
 * {@link ChatReader} is the decoder): the sender's seat id, the display name the SERVER stamped
 * (announce name or its {@code "seat-"+seatId} fallback — never empty, self-contained with no
 * {@code SNMS}-ordering dependency), and the law-conforming chat line. The predict combat
 * plugin's net-thread consumer queues these for the render thread's HUD
 * {@code addMessage} drain; the sender renders its own line only on this echo (= delivery
 * confirmation, one display path).
 *
 * <p>Field laws mirror the server codec's decode guards ({@code ChatFrameCodec.ChatDown}):
 * {@code seatId ≥ 0}, {@code name} non-empty under the seat-name contract
 * ({@link SeatNameWriter#validateSeatName}), {@code text} under the chat-text law
 * ({@link ChatWriter#validateChatText}). Constructing one with a violating field throws
 * {@link IllegalArgumentException}; {@link ChatReader} wraps that fail-closed into its
 * {@code IOException} like the sibling readers.
 */
public final class ClientChatMessage {

    private final int seatId;
    private final String name;
    private final String text;

    public ClientChatMessage(int seatId, String name, String text) {
        if (seatId < 0) {
            throw new IllegalArgumentException("seatId must be >= 0; got " + seatId);
        }
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                    "chat name must be non-empty (the server always stamps a fallback)");
        }
        SeatNameWriter.validateSeatName(name);
        ChatWriter.validateChatText(text);
        this.seatId = seatId;
        this.name = name;
        this.text = text;
    }

    /** The sender's seat id (the server's authoritative attribution). */
    public int seatId() {
        return seatId;
    }

    /** The server-stamped display name — announce name or {@code "seat-"+seatId}; never empty. */
    public String name() {
        return name;
    }

    /** The chat line ({@link ChatWriter#validateChatText}-conforming; never blank). */
    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientChatMessage)) {
            return false;
        }
        ClientChatMessage other = (ClientChatMessage) o;
        return seatId == other.seatId && name.equals(other.name) && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seatId, name, text);
    }

    @Override
    public String toString() {
        return "ClientChatMessage{seatId=" + seatId + ", name='" + name + "', text='" + text + "'}";
    }
}
