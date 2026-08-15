package ooo.klae.connex.backend.beans;

import java.util.Objects;

/** Workspace-scoped idempotency claim and replay result for one send or resend request. */
public record DocumentDeliveryRequest(
        String operation,
        byte[] requestFingerprint,
        int documentId,
        Integer deliveryId,
        Integer recipientId,
        int createdByUserId) {

    /** Protects the persisted fingerprint from mutation by mapper consumers. */
    public DocumentDeliveryRequest {
        Objects.requireNonNull(operation, "operation");
        requestFingerprint = Objects.requireNonNull(
            requestFingerprint, "requestFingerprint").clone();
        if (requestFingerprint.length != 32) {
            throw new IllegalArgumentException("Document-delivery fingerprint must be SHA-256");
        }
    }

    @Override
    public byte[] requestFingerprint() {
        return requestFingerprint.clone();
    }
}
