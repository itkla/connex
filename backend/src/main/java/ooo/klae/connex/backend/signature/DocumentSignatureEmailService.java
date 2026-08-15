package ooo.klae.connex.backend.signature;

/** Delivers one opaque document-acceptance link without persisting or logging the raw token. */
public interface DocumentSignatureEmailService {
    /** Sends or locally records one recipient-link delivery attempt. */
    void send(
        int workspaceId,
        String recipientName,
        String recipientEmail,
        String documentTitle,
        String message,
        String acceptanceUrl);
}
