import type { CampaignRecipientsPageParams } from '@/app/lib/types';

/**
 * The engagement counters a campaign reports. `failed` has no campaign-level tile — it is reported
 * only in the per-send breakdown — but it is still a population a reader can be sent to, so it maps
 * like the rest rather than being a hole in the switch.
 */
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
