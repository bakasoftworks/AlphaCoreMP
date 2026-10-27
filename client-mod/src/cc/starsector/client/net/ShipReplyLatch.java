package cc.starsector.client.net;

/**
 * The editor's Fly on a ROSTER build sends {@code /ship <id>} on the
 * live CTRL chat lane and shows the SERVER's own reply inline on the card row. The client keeps
 * no chat line anywhere a UI could read (the HUD drain is destructive), so this latch sits at the
 * ONE receive choke ({@code PredictReconcileDatagramSession} CHAT routing, net thread): armed by
 * the fly route with the id it sent, it takes the FIRST server-stamped line whose head is one of
 * the {@code /ship} verb's fixed reply templates and hands it to the waiting HTTP thread.
 *
 * <p>Laws: the reply is player-adjacent DATA (the server already bounds its echo; the route
 * sanitizes again before it reaches the SPA); only the author {@code server} is read, and only on
 * the requester's OWN seat id (the real reply is
 * unicast stamped with the requester's seat, while the server's {@code "<name> joined"} broadcast
 * is author {@code server} with a PEER-chosen head on the JOINER's seat — the seat check closes
 * that spoof; an unknown own seat, -1, takes nothing) — a player
 * who names themselves "server" can at most paint one bounded line on one card row of the
 * clicking player's own editor, never a verb; the wait is bounded (the server's per-key chat
 * bucket, carried across a same-key reconnect, drops a 5th line in a second SILENTLY, and a non-exact id answers with a listing and
 * zero lines — the caller's timeout is the truth then, never "wait for the line"). One latch per
 * JVM: a second Fly re-arms (a fresh generation) and the earlier waiter returns null. B-clean:
 * java.lang only.
 */
public final class ShipReplyLatch {
    /** The server-stamped author the {@code /ship} reply carries. */
    static final String SERVER_AUTHOR = "server";
    /** The {@code /ship} verb's reply heads (ChatCommandDispatch + SeatVariantPicks), fixed text. */
    private static final String[] REPLY_HEADS = {
            "next respawn: ", "unknown variant '", "no variants match '", "/ship picking unavailable",
            "usage: /ship", "variant '"
    };

    private static final Object LOCK = new Object();
    private static long generation;
    private static boolean armed;
    private static int armedSeat = -1;
    private static String reply;

    private ShipReplyLatch() {
    }

    /** Arm for the next matching server line stamped with {@code ownSeatId} (-1 = own seat unknown: takes
     * no line); returns the generation the caller waits on. */
    public static long arm(int ownSeatId) {
        synchronized (LOCK) {
            generation++;
            armed = true;
            armedSeat = ownSeatId;
            reply = null;
            LOCK.notifyAll(); // an older waiter wakes and sees its generation gone
            return generation;
        }
    }

    /** Net thread: one relayed chat line; taken when armed, server-stamped, on the armed own seat, and
     * template-headed. */
    public static void offer(ClientChatMessage chat) {
        if (chat == null) {
            return;
        }
        String text = chat.text();
        if (text == null || !SERVER_AUTHOR.equals(chat.name()) || !templateHeaded(text)) {
            return;
        }
        synchronized (LOCK) {
            if (!armed || chat.seatId() != armedSeat) { // seatId >= 0, so -1 never matches
                return;
            }
            armed = false;
            reply = text;
            LOCK.notifyAll();
        }
    }

    /** The waiting thread: the taken line for {@code gen}, or null after {@code timeoutMs} / a re-arm. */
    public static String await(long gen, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (LOCK) {
            while (generation == gen && reply == null) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0L) {
                    armed = false; // the window closed; a late line is the HUD's alone
                    return null;
                }
                try {
                    LOCK.wait(leftMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    armed = false;
                    return null;
                }
            }
            if (generation != gen) {
                return null;
            }
            String r = reply;
            reply = null;
            return r;
        }
    }

    static boolean templateHeaded(String text) {
        for (String head : REPLY_HEADS) {
            if (text.startsWith(head)) {
                return true;
            }
        }
        return false;
    }
}
