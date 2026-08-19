package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One channel/reason pair a contact is suppressed under, with the earliest time it was recorded.
 * Deliberately carries no address, note, or author: those stay behind the consent-management
 * surface.
 *
 * @param channel the delivery channel
 * @param reason the suppression reason
 * @param since the earliest time this channel/reason pair was recorded
 */
public record SuppressionChannelStateRow(
        String channel,
        String reason,
        LocalDateTime since) {
}
