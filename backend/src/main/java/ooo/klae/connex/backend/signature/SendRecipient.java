package ooo.klae.connex.backend.signature;

/** Frozen recipient identity supplied to a document-signature provider. */
public record SendRecipient(
        int recipientId,
        String name,
        String email,
        String role,
        int recipientOrder) {
}
