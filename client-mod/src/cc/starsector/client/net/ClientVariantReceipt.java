package cc.starsector.client.net;

import java.util.Objects;

import cc.starsector.server.runtime.DoorRejectReasons;

/**
 * One decoded {@code VACK} custom-variant receipt (W15 v0; server twin
 * {@code VariantReceiptFrameCodec.VariantReceipt}): the echoed {@code submitSeq}, the verdict,
 * the {@code DoorRejectReasons.custom()} token on a REJECT (empty on ACCEPT) and the variant id —
 * the server-minted CANONICAL {@code cc-v<serial>-<slug>} id on ACCEPT, the echoed client-local
 * id on REJECT. Laws re-checked here so a decoded record is always well-formed; the reader maps
 * any violation to its fail-closed {@code IOException}.
 */
public record ClientVariantReceipt(int submitSeq, boolean accepted, String reasonToken,
        String variantId) {
    public ClientVariantReceipt {
        if (submitSeq < 0 || submitSeq > VariantSubmitWriter.SUBMIT_SEQ_MAX) {
            throw new IllegalArgumentException("submitSeq outside [0,"
                    + VariantSubmitWriter.SUBMIT_SEQ_MAX + "]: " + submitSeq);
        }
        Objects.requireNonNull(reasonToken, "reasonToken");
        Objects.requireNonNull(variantId, "variantId");
        if (accepted) {
            if (!reasonToken.isEmpty()) {
                throw new IllegalArgumentException("an ACCEPTED receipt carries no reason, got '"
                        + reasonToken + "'");
            }
        } else if (DoorRejectReasons.customCode(reasonToken) == 0) {
            throw new IllegalArgumentException("a REJECTED receipt needs a"
                    + " DoorRejectReasons.custom() token, got '" + reasonToken + "'");
        }
        if (!ClientVariantDefinition.isIdLawful(variantId)) {
            throw new IllegalArgumentException("variantId violates the id law (1.."
                    + ClientVariantDefinition.ID_MAX_CHARS + " printable ASCII, no spaces): '"
                    + variantId + "'");
        }
    }

    /** The wire reason code: 0 on ACCEPT, else the 1-based index into {@code custom()}. */
    public int code() {
        return accepted ? 0 : DoorRejectReasons.customCode(reasonToken);
    }
}
