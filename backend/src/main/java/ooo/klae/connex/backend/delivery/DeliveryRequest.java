package ooo.klae.connex.backend.delivery;

/**
 * One recipient's dispatch request against a resolved provider.
 * @param channel the delivery channel
 * @param address the recipient address on that channel
 * @param content the rendered message
 * @param personId the recipient person id, or null when not linked to a person
 * @param dedupeKey a stable per-recipient key for provider-side idempotency, or null
 */
public record DeliveryRequest(
        DeliveryChannel channel,
        String address,
        RenderedMessage content,
        Integer personId,
        String dedupeKey) {
}
