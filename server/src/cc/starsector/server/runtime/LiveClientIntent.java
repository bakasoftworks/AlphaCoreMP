package cc.starsector.server.runtime;

/**
 * One remote client's ship control intent for the OLD command-transport round-trip.
 *
 *
 * <p><b>Superseded lane.</b> This is the earlier command-transport proof: a single
 * {@code fire}/{@code weaponGroup} the server forwards into the engine. It is NOT the
 * live faithful pilot round-trip — the faithful pilot-input lane carries the human's own
 * aim + keys + fire button as {@link PilotFireIntent}/{@code PilotIntentFrame} (see
 * {@code PilotIntentFrame}'s class doc and {@code PilotFireIntent}, which classify
 * {@code LiveClientIntent} as the already-proven command-transport fire lane, deliberately
 * distinct from the faithful shape). The faithful lane supersedes this one.
 *
 * <p>This is the inbound half that the publish-only
 * {@link LiveWorldSnapshotSocketPublisher} never had: a separate-JVM client sends
 * one of these over the socket (encoded by {@link LiveClientIntentCodec}), the
 * server reads it before the publish loop and threads it into the live engine
 * step via the command-authority sink, so the per-tick
 * {@code AuthoritativeWorldSnapshot} that crosses back carries the effect of the
 * client's <em>own</em> command (e.g. the beam it asked the server to fire).
 *
 * <p>Held semantics: a single intent models the client holding the control for
 * the whole publish window. {@code fire=true} means "hold fire on my owned ship";
 * {@code fire=false} is the control case (hold fire / no command), under which the
 * server issues no FIRE and no effect crosses back.
 */
public record LiveClientIntent(long shipId, boolean fire, int weaponGroup) {

    public LiveClientIntent {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (weaponGroup < 0) {
            throw new IllegalArgumentException("weaponGroup must be >= 0; got " + weaponGroup);
        }
    }

    /** Client asks the server to hold FIRE on {@code shipId}'s weapon group. */
    public static LiveClientIntent fire(long shipId, int weaponGroup) {
        return new LiveClientIntent(shipId, true, weaponGroup);
    }

    /** Control intent: client holds fire (no command crosses into the engine). */
    public static LiveClientIntent hold(long shipId, int weaponGroup) {
        return new LiveClientIntent(shipId, false, weaponGroup);
    }
}
