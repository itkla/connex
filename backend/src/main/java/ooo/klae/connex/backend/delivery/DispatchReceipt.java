package ooo.klae.connex.backend.delivery;

/**
 * The provider's response to a single dispatch attempt.
 *
 * <p>{@code provenBeforeEgress} is the egress boundary: it is true only when the adapter can prove
 * that no request bytes left this instance — a missing deadline, an unusable endpoint or credential,
 * a payload that would not encode, or a transport failure the adapter classified as definite (DNS,
 * TCP, TLS, or pre-submission SMTP). It is deliberately false for every other outcome, including a
 * provider status-code rejection and any unclassified adapter exception, so an attempt that may have
 * reached the provider is never treated as if it had not.
 * Post-egress rejections retain the 24-hour reservation, trading possible cap poisoning from forced
 * provider rejection for conservative duplicate prevention; follow-up #1705 tracks this trade-off.
 *
 * @param status the dispatch outcome
 * @param providerMessageId the provider-assigned message id, or null when the provider assigns none
 * @param detail a short, non-sensitive note describing the outcome
 * @param provenBeforeEgress whether the adapter proved no request bytes left this instance
 */
public record DispatchReceipt(
        DispatchStatus status, String providerMessageId, String detail, boolean provenBeforeEgress) {

    /**
     * Builds a sent receipt.
     * @param providerMessageId the provider-assigned message id, or null
     * @param detail a short note
     * @return a sent receipt
     */
    public static DispatchReceipt sent(String providerMessageId, String detail) {
        return new DispatchReceipt(DispatchStatus.SENT, providerMessageId, detail, false);
    }

    /**
     * Builds a rejected receipt whose egress boundary is unproven, so callers must assume the
     * request may have reached the provider.
     * @param detail a short note describing the rejection
     * @return a rejected receipt
     */
    public static DispatchReceipt rejected(String detail) {
        return new DispatchReceipt(DispatchStatus.REJECTED, null, detail, false);
    }

    /**
     * Builds a rejected receipt for an attempt the adapter proved never reached provider egress.
     * @param detail a short note describing the rejection
     * @return a rejected receipt marked as proven before egress
     */
    public static DispatchReceipt rejectedBeforeEgress(String detail) {
        return new DispatchReceipt(DispatchStatus.REJECTED, null, detail, true);
    }

    /**
     * Builds a receipt for an attempt whose provider-side acceptance cannot be determined safely.
     * @param detail a short note describing why reconciliation is required
     * @return an ambiguous receipt
     */
    public static DispatchReceipt ambiguous(String detail) {
        return new DispatchReceipt(DispatchStatus.AMBIGUOUS, null, detail, false);
    }
}
