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
 * Sent selects persisted confirmed submissions, regardless of later delivery or failure status.
 * Outcome counters select their current status; unsubscribes select their event.
 * `recipients` is every delivery the campaign prepared, including drafts and repeat contacts
 * across sends, and selects nothing.
 * Mirrors the server's `CampaignRecipientService`, so a tile opens exactly the set it counted.
 *
 * @param counter - the counter whose population to select
 */
export function recipientFilterFor(counter: EngagementCounter): CampaignRecipientsPageParams {
    if (counter === 'recipients') return {};
    if (counter === 'dispatched') return { submitted: true };
    if (counter === 'unsubscribed') return { event: 'unsubscribed' };
    return { status: [counter] };
}
