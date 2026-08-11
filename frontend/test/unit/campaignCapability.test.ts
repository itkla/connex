import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { DEFAULT_CAPABILITIES } from '@/app/lib/api';

const DETAIL_PAGE = 'app/(app)/marketing/campaigns/[id]/page.tsx';
const DELIVERY_PANEL = 'app/components/marketing/campaigns/CampaignDelivery.tsx';
const EXPORT_PANEL = 'app/components/marketing/campaigns/CampaignExportPanel.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

describe('campaignDelivery is a first-class instance capability', () => {
    it('ships in the fail-closed defaults so an unreachable lookup hides delivery', () => {
        expect(DEFAULT_CAPABILITIES.campaignDelivery).toBe(false);
    });

    it('reaches the campaign surface as an explicit resolved-or-unavailable result', () => {
        const page = source(DETAIL_PAGE);

        expect(page).toContain('getCapabilities(');
        expect(page).toContain('toResult(getCapabilities(init))');
        expect(page).toContain('deliveryAvailability={capabilityAvailability(');
    });
});

describe('the campaign delivery surface reflects whether delivery is available', () => {
    it('treats a delivery-disabled instance as unavailable before any request is made', () => {
        expect(source(DELIVERY_PANEL)).toContain(
            'const deliveryUnavailable = deliveryAvailability !== "enabled" || deliveryRefused;',
        );
    });

    it('keeps the queue control disabled while delivery is unavailable', () => {
        expect(source(DELIVERY_PANEL)).toContain('disabled={busy || deliveryUnavailable}');
    });

    it('gates audience export on the same capability that gates sending', () => {
        const panel = source(EXPORT_PANEL);

        expect(panel).toContain('const exportUnavailable = deliveryAvailability !== "enabled" || exportRefused;');
        expect(panel).toContain('exportUnavailable');
    });
});
