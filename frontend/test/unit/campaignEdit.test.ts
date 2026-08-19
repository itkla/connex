import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
    campaignBuilderPath,
    campaignInstantCreatePayload,
} from '@/app/components/marketing/campaigns/campaignInstantCreate';

const CAMPAIGN_DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';
const EDIT_SHEET = 'app/components/marketing/campaigns/EditCampaignSheet.tsx';
const NEW_DIALOG = 'app/components/marketing/campaigns/NewCampaignDialog.tsx';
const BROWSER = 'app/components/marketing/campaigns/CampaignsBrowser.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function namespace(locale: 'en' | 'ja', name: string): { [key: string]: MessageValue } {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
    if (!isMessageTree(parsed) || !isMessageTree(parsed[name])) {
        throw new Error(`messages/${locale}/campaigns.json has no ${name} namespace`);
    }
    return parsed[name];
}

describe('a campaign is created by naming it and edited in the builder', () => {
    it('sends only the two facts the instant-create prompt asks for', () => {
        expect(campaignInstantCreatePayload('  Spring re-engagement  ', ' nurture ')).toEqual({
            name: 'Spring re-engagement',
            type: 'nurture',
            status: 'draft',
        });
    });

    it('lands the new campaign in its full-page builder', () => {
        expect(campaignBuilderPath(42)).toBe('/marketing/campaigns/42');
        expect(source(NEW_DIALOG)).toContain('router.push(campaignBuilderPath(created.id))');
    });

    it('offers the list surface no create form beyond the instant-create prompt', () => {
        const browser = source(BROWSER);

        expect(browser).toContain('<NewCampaignDialog open={open} onOpenChange={setOpen} />');
        expect(browser).not.toContain('CampaignPayload');
        expect(browser).not.toContain('createCampaign');
    });

    it('calls the update endpoint from a right drawer, not a centered dialog', () => {
        const detail = source(CAMPAIGN_DETAIL);

        expect(detail).toContain('updateCampaign(');
        expect(detail).toContain('onClick={openEdit}');
        expect(detail).toContain('<EditCampaignSheet');
        expect(source(EDIT_SHEET)).toContain('QuickEditSheetShell');
    });

    it('carries fields the form never shows, because the update is a full replace', () => {
        const detail = source(CAMPAIGN_DETAIL);
        const seed = detail.slice(
            detail.indexOf('function toPayload'),
            detail.indexOf('\n}', detail.indexOf('function toPayload')),
        );

        expect(seed).toContain('ownerUserId: campaign.ownerUserId');
        expect(seed).toContain('parentCampaignId: campaign.parentCampaignId');
        expect(seed).toContain('status: campaign.status');
    });

    it('lets status be changed after creation, not only at creation', () => {
        const sheet = source(EDIT_SHEET);

        expect(sheet).toContain('STATUSES.map');
        expect(sheet).toContain('status: value as CampaignStatus');
    });

    it('localizes both surfaces in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const create = namespace(locale, 'CampaignsNewDialog');
            const edit = namespace(locale, 'CampaignsEditSheet');

            for (const key of ['title', 'description', 'name', 'type', 'typeHint', 'submit', 'creating']) {
                expect(typeof create[key]).toBe('string');
                expect(create[key]).toBeTruthy();
            }
            for (const key of ['title', 'description', 'submit', 'saving', 'statusLockedHint']) {
                expect(typeof edit[key]).toBe('string');
                expect(edit[key]).toBeTruthy();
            }
            expect(edit.title).not.toBe(create.title);
            expect(edit.submit).not.toBe(create.submit);
        }
    });
});
