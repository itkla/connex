package ooo.klae.connex.backend.signature;

/** Delivers one opaque document-acceptance link without persisting or logging the raw token. */
public interface DocumentSignatureEmailService {
    /**
     * Sends or locally records one recipient-link delivery attempt.
     *
     * @param locale the frozen document's locale. The recipient is external, so Connex holds no
     *     language preference for them; the language the document itself was written in is the only
     *     honest signal available.
     */
    void send(
        int workspaceId,
        String recipientName,
        String recipientEmail,
        String documentTitle,
        String message,
        String acceptanceUrl,
        String locale);
}
