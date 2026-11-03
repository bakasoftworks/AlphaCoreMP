package cc.starsector.client.replica;

/**
 * The pilot-replica agent receipt's two names, in a GAME-FREE home (no engine, API or LWJGL import) so the
 * game-free side ({@code magic.MagicCapabilities}, read by the HELLO flag {@code 0x20}) can name them without
 * linking {@link ReplicaPilotWindow}, which is engine-typed. WHY a class of its own: the wire-parity
 * stand compiles the mod's game-free set standalone; the
 * import of {@code ReplicaPilotWindow} from {@code MagicCapabilities} turned every verifier
 * kind RED at javac, unseen until the next pre-publish chain (the stand runs pre-publish only - the CHAIN LAW).
 *
 * {@link ReplicaPilotWindow#RECEIPT_SYSPROP} / {@link ReplicaPilotWindow#RECEIPT_APPLIED} alias these, so every
 * existing reader keeps its name. Provenance: the receipt's shape = the K lanes.
 */
public final class ReplicaReceipt {
    /** The sysprop the agent arm writes its receipt to ({@code off | armed | applied:<...> | latched:<reason>}). */
    public static final String SYSPROP = "cc.pilot.replica";
    /** The receipt prefix that means the arm rewrote at least one mod class. */
    public static final String APPLIED = "applied:";

    private ReplicaReceipt() {
    }
}
