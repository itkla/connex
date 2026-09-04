package ooo.klae.connex.backend.delivery;

/**
 * The provider's response to a single dispatch attempt.
 * @param status the dispatch outcome
 * @param providerMessageId the provider-assigned message id, or null when the provider assigns none
 * @param detail a short, non-sensitive note describing the outcome
 */
public record DispatchReceipt(DispatchStatus status, String providerMessageId, String detail) {

    /**
     * Builds a sent receipt.
     * @param providerMessageId the provider-assigned message id, or null
     * @param detail a short note
     * @return a sent receipt
     */
    public static DispatchReceipt sent(String providerMessageId, String detail) {
        return new DispatchReceipt(DispatchStatus.SENT, providerMessageId, detail);
    }

    /**
     * Builds a rejected receipt.
     * @param detail a short note describing the rejection
     * @return a rejected receipt
     */
    public static DispatchReceipt rejected(String detail) {
        return new DispatchReceipt(DispatchStatus.REJECTED, null, detail);
    }

    /**
     * Builds a receipt for an attempt whose provider-side acceptance cannot be determined safely.
     * @param detail a short note describing why reconciliation is required
     * @return an ambiguous receipt
     */
    public static DispatchReceipt ambiguous(String detail) {
        return new DispatchReceipt(DispatchStatus.AMBIGUOUS, null, detail);
    }
}
