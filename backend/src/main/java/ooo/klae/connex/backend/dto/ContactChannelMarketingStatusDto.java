package ooo.klae.connex.backend.dto;

/**
 * One delivery channel's marketing exclusion state for a contact.
 *
 * <p>{@code state} is the single token a client badges from, ordered most restrictive first:
 * {@code do_not_contact}, then {@code opted_out}, then null when the channel is still contactable.
 * The individual flags stay available so a client can explain the state without re-deriving it;
 * {@code optedOut} is true whenever the channel is excluded for any marketing reason, so
 * {@code !optedOut} always means "safe to contact".
 *
 * <p>Deliberately carries no timestamp, address, note, or author: when an administrator recorded a
 * suppression says nothing about contactability and stays behind the consent-management surface.
 *
 * @param channel the delivery channel
 * @param state {@code do_not_contact}, {@code opted_out}, or null when the channel is contactable
 * @param optedOut whether the channel is excluded for any marketing reason
 * @param doNotContact whether the workspace recorded an explicit do-not-contact suppression
 * @param consentRevoked whether the contact explicitly revoked marketing consent on this channel
 * @param addressable whether the contact currently has an address this channel could reach
 */
public record ContactChannelMarketingStatusDto(
        String channel,
        String state,
        boolean optedOut,
        boolean doNotContact,
        boolean consentRevoked,
        boolean addressable) {
}
