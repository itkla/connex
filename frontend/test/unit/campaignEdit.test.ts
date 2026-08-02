import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const CAMPAIGN_DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';
const FORM_DIALOG = 'app/components/marketing/campaigns/CampaignFormDialog.tsx';
const BROWSER = 'app/components/marketing/campaigns/CampaignsBrowser.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function dialogNamespace(locale: 'en' | 'ja'): { [key: string]: MessageValue } {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
    if (!isMessageTree(parsed) || !isMessageTree(parsed.CampaignsNewDialog)) {
        throw new Error(`messages/${locale}/campaigns.json has no CampaignsNewDialog namespace`);
    }
    return parsed.CampaignsNewDialog;
}

describe('a campaign can be edited after it is created', () => {
    it('calls the update endpoint that previously had no caller', () => {
        const detail = source(CAMPAIGN_DETAIL);

        expect(detail).toContain('updateCampaign(');
        expect(detail).toContain('onClick={openEdit}');
    });

    it('drives one dialog from both the create and the edit surface', () => {
        expect(source(BROWSER)).toContain('mode="create"');
        expect(source(CAMPAIGN_DETAIL)).toContain('mode="edit"');
        expect(source(FORM_DIALOG)).toContain('const editing = mode === "edit";');
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
        const dialog = source(FORM_DIALOG);

        expect(dialog).toContain('STATUSES.map');
        expect(dialog).toContain('status: value as CampaignStatus');
    });

    it('localizes the edit copy in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const namespace = dialogNamespace(locale);

            for (const key of ['editTitle', 'editDescription', 'editSubmit', 'editSaving']) {
                expect(typeof namespace[key]).toBe('string');
                expect(namespace[key]).toBeTruthy();
            }
            expect(namespace.editTitle).not.toBe(namespace.title);
            expect(namespace.editSubmit).not.toBe(namespace.submit);
        }
    });
});
