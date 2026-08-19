import type { ContactChannelMarketingState, ContactMarketingStatus } from '@/app/lib/types';

/** The channel a contact's email address belongs to. */
export const EMAIL_CHANNEL = 'email';

/**
 * What one delivery channel excludes a contact from, or null when the channel is still contactable.
 *
 * A privacy hold is deliberately not consulted. It is a record-level restriction the contact asked
 * for and the record already states it in its own words; folding it into a marketing badge would
 * either hide the restriction behind a marketing word or claim a restriction where a recipient
 * merely unsubscribed. The two states are reported separately, exactly as the server reports them.
 *
 * @param status - the contact's marketing status, or null when it could not be read
 * @param channel - the delivery channel to report on
 */
export function channelMarketingExclusion(
    status: ContactMarketingStatus | null | undefined,
    channel: string,
): ContactChannelMarketingState | null {
    if (!status) return null;
    return status.channels.find((entry) => entry.channel === channel)?.state ?? null;
}
