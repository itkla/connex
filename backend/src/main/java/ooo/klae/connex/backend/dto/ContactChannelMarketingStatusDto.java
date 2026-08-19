package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One delivery channel's marketing exclusion state for a contact.
 *
 * <p>{@code state} is the single token a client badges from, ordered most restrictive first:
 * {@code do_not_contact}, then {@code opted_out}, then null when the channel is still contactable.
 * The individual flags stay available so a client can explain the state without re-deriving it.
 *
 * @param channel the delivery channel
 * @param state {@code do_not_contact}, {@code opted_out}, or null when the channel is contactable
 * @param optedOut whether the contact unsubscribed, complained, bounced, or was suppressed manually
 * @param doNotContact whether the workspace recorded an explicit do-not-contact suppression
 * @param consentRevoked whether the contact explicitly revoked marketing consent on this channel
 * @param addressable whether the contact currently has an address this channel could reach
 * @param since when the earliest exclusion on this channel was recorded, or null
 */
public record ContactChannelMarketingStatusDto(
        String channel,
        String state,
        boolean optedOut,
        boolean doNotContact,
        boolean consentRevoked,
        boolean addressable,
        LocalDateTime since) {
}
