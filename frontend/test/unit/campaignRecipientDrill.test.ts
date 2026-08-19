import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { canReadRecipients, resolveCampaignAccess } from '@/app/lib/campaignAccess';
import {
    ENGAGEMENT_COUNTERS,
    recipientFilterFor,
    type EngagementCounter,
} from '@/app/components/marketing/campaigns/recipientFilters';
import { resolveShippedRoute } from '@/app/lib/routeManifest';

const ENGAGEMENT = 'app/components/marketing/campaigns/CampaignEngagement.tsx';
const DIALOG = 'app/components/marketing/campaigns/CampaignRecipientsDialog.tsx';
const DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';

/** Mirrors the campaign permissions the built-in `member` role holds. */
const MEMBER_PERMISSIONS: readonly string[] = ['CAMPAIGN_VIEW'];

/** A campaign manager without consent access — the case the drill-through must not be offered to. */
const MANAGER_WITHOUT_CONSENT: readonly string[] = ['CAMPAIGN_VIEW', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND'];

/** Mirrors the campaign-relevant additions in the built-in `admin` role. */
const ADMIN_PERMISSIONS: readonly string[] = [...MANAGER_WITHOUT_CONSENT, 'CONSENT_MANAGE'];

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

describe('a campaign engagement count opens the contacts behind it', () => {
    it('selects each status-derived counter by delivery status', () => {
        const statusDerived: EngagementCounter[] = [
            'dispatched',
            'delivered',
            'bounced',
            'complained',
            'skipped',
            'failed',
        ];

        for (const counter of statusDerived) {
            expect(recipientFilterFor(counter)).toEqual({ status: [counter] });
        }
    });

    it('selects the unsubscribe counter by event, because an unsubscribe is never a delivery status', () => {
        expect(recipientFilterFor('unsubscribed')).toEqual({ event: 'unsubscribed' });
    });

    it('draws the recipients counter from every delivery, filtering nothing away', () => {
        expect(recipientFilterFor('recipients')).toEqual({});
    });

    it('maps every counter it renders, so no tile can open a population it did not count', () => {
        for (const counter of ENGAGEMENT_COUNTERS) {
            const filter = recipientFilterFor(counter);
            expect(Object.keys(filter).length).toBeLessThanOrEqual(1);
        }
        expect(new Set(ENGAGEMENT_COUNTERS).size).toBe(ENGAGEMENT_COUNTERS.length);
    });

    it('offers the drill-through only to a reader the server would serve the roster to', () => {
        expect(canReadRecipients(resolveCampaignAccess(ADMIN_PERMISSIONS))).toBe(true);
        expect(canReadRecipients(resolveCampaignAccess(MEMBER_PERMISSIONS))).toBe(false);
        expect(canReadRecipients(resolveCampaignAccess(MANAGER_WITHOUT_CONSENT))).toBe(false);
        expect(canReadRecipients(resolveCampaignAccess([]))).toBe(false);
    });

    it('passes that answer from the detail page into the counters', () => {
        const detail = source(DETAIL);

        expect(detail).toContain('canReadRecipients={canReadRecipients(access)}');
        expect(source(ENGAGEMENT)).toContain('onDrill={canReadRecipients ? setOpenCounter : null}');
    });

    it('leaves an unmeasured or empty count as a plain figure, so no tile promises a list it cannot show', () => {
        expect(source(ENGAGEMENT)).toContain(
            'const drillable = onDrill !== null && !tile.unavailable && tile.value > 0;',
        );
    });

    it('lands every named recipient on the contact record the delivery reached', () => {
        const dialog = source(DIALOG);

        expect(dialog).toContain('href={`/records/contacts/${recipient.personId}`}');
        expect(resolveShippedRoute('/records/contacts/42')).toBe('/records/contacts/[id]');
    });

    it('names a delivery whose contact link was cleared instead of linking nowhere', () => {
        const dialog = source(DIALOG);

        expect(dialog).toContain('recipient.personId != null ?');
        expect(dialog).toContain("t('contactRemoved')");
    });

    it('asks the server for exactly the population the counter selected', () => {
        expect(source(DIALOG)).toContain('...recipientFilterFor(counter), page, size: PAGE_SIZE');
        expect(source('app/lib/api.ts')).toContain('`/api/campaigns/${id}/recipients${buildQuery(params)}`');
    });
});
