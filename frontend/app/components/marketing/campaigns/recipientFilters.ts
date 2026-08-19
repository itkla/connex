import type { CampaignRecipientsPageParams } from '@/app/lib/types';

/** The engagement counters a campaign reports, in the order the tiles render them. */
export const ENGAGEMENT_COUNTERS = [
    'recipients',
    'dispatched',
    'delivered',
    'bounced',
    'complained',
    'unsubscribed',
    'skipped',
    'failed',
] as const;

/** One engagement counter a reader can drill through. */
export type EngagementCounter = (typeof ENGAGEMENT_COUNTERS)[number];

/**
 * The recipient population behind one engagement counter.
 *
 * Named counters are read from a delivery's current status, so they select by `status`; an
 * unsubscribe is never a delivery status and exists only as a lifecycle event, so it selects by
 * `event` instead. `recipients` is every delivery the campaign materialized and therefore selects
 * nothing. Mirrors the server's `CampaignRecipientService`, so a tile always opens exactly the set
 * it counted.
 *
 * @param counter - the counter whose population to select
 */
export function recipientFilterFor(counter: EngagementCounter): CampaignRecipientsPageParams {
    if (counter === 'recipients') return {};
    if (counter === 'unsubscribed') return { event: 'unsubscribed' };
    return { status: [counter] };
}
