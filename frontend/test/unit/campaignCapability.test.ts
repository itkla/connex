import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const DETAIL_PAGE = 'app/(app)/marketing/campaigns/[id]/page.tsx';
const DELIVERY_PANEL = 'app/components/marketing/campaigns/CampaignDelivery.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

describe('campaignDelivery is a first-class instance capability', () => {
    it('reaches the campaign surface as an explicit resolved-or-unavailable result', () => {
        const page = source(DETAIL_PAGE);

        expect(page).toContain('getCapabilities(');
        expect(page).toContain('toResult(getCapabilities(init))');
        expect(page).toContain('deliveryAvailability={capabilityAvailability(');
    });
});

describe('the campaign delivery surface reflects whether delivery is available', () => {
    it('keeps the queue control disabled while delivery is unavailable', () => {
        expect(source(DELIVERY_PANEL)).toContain('disabled={busy || deliveryUnavailable}');
    });
});
