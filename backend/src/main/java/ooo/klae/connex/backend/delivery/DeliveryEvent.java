package ooo.klae.connex.backend.delivery;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A normalized, provider-agnostic delivery event translated from a provider webhook payload. The
 * {@code providerMessageId} ties the event back to the {@code campaign_delivery} row the provider was
 * given a receipt for, and {@code providerEventId} is the provider's own stable id for this event,
 * used to make webhook replay idempotent.
 * @param providerMessageId the provider-assigned message id the event concerns
 * @param providerEventId the provider's stable id for this event, or null when the provider assigns none
 * @param type the normalized event class
 * @param occurredAt when the provider reports the event occurred, or null when unknown
 * @param detail a short, non-sensitive note describing the event
 */
public record DeliveryEvent(
        String providerMessageId,
        String providerEventId,
        DeliveryEventType type,
        LocalDateTime occurredAt,
        String detail) {

    public DeliveryEvent {
        Objects.requireNonNull(type, "type");
    }
}
