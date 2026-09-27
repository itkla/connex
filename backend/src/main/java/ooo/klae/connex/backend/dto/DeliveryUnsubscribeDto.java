package ooo.klae.connex.backend.dto;

/**
 * Public confirmation payload for an unsubscribe link. Carries no personal data beyond the masked
 * address and never exposes ids from other tenants.
 * @param flowId the non-authorizing digest of the browser grant this payload was rendered from,
 *               which the confirmation must echo so a grant swapped by another tab cannot decide it
 * @param channel the channel being unsubscribed
 * @param address the masked recipient address
 * @param unsubscribed whether the address is already suppressed
 */
public record DeliveryUnsubscribeDto(
        String flowId, String channel, String address, boolean unsubscribed) {
}
