package ooo.klae.connex.backend.dto;

/**
 * One channel/reason pair a contact is suppressed under. Deliberately carries no address, note,
 * author, or timestamp: those stay behind the consent-management surface.
 *
 * @param channel the delivery channel
 * @param reason the suppression reason
 */
public record SuppressionChannelStateRow(
        String channel,
        String reason) {
}
