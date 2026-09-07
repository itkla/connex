package ooo.klae.connex.backend.delivery;

/**
 * One recipient's dispatch request against a resolved provider.
 * @param channel the delivery channel
 * @param address the recipient address on that channel
 * @param content the rendered message
 * @param personId the recipient person id, or null when not linked to a person
 * @param dedupeKey a stable per-recipient key for provider-side idempotency, or null
 * @param providerDeadlineNanos the absolute {@link System#nanoTime()} deadline for provider work,
 *        or null only for callers that do not own a recoverable delivery lease
 */
public record DeliveryRequest(
        DeliveryChannel channel,
        String address,
        RenderedMessage content,
        Integer personId,
        String dedupeKey,
        Long providerDeadlineNanos) {

    /**
     * Builds a request without a recoverable delivery lease.
     * @param channel the delivery channel
     * @param address the recipient address
     * @param content the rendered content
     * @param personId the recipient person id, or null
     * @param dedupeKey the stable provider idempotency key, or null
     */
    public DeliveryRequest(
            DeliveryChannel channel,
            String address,
            RenderedMessage content,
            Integer personId,
            String dedupeKey) {
        this(channel, address, content, personId, dedupeKey, null);
    }
}
