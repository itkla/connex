import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const CAMPAIGN_DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Reads one leaf string from a locale catalog, so a missing or restructured key fails loudly. */
function message(locale: 'en' | 'ja', namespace: string, key: string): string {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
    if (!isMessageTree(parsed)) {
        throw new Error(`messages/${locale}/campaigns.json is not a JSON object`);
    }
    const scope = parsed[namespace];
    if (!isMessageTree(scope)) {
        throw new Error(`messages/${locale}/campaigns.json has no ${namespace} namespace`);
    }
    const value = scope[key];
    if (typeof value !== 'string') {
        throw new Error(`messages/${locale}/campaigns.json is missing ${namespace}.${key}`);
    }
    return value;
}

/**
 * Every control below calls an endpoint the backend guards with `CAMPAIGN_MANAGE`, which the
 * built-in `member` role does not hold. Rendering them for a member is a guaranteed 403.
 */
describe('campaign detail offers a member no control that will 403', () => {
    const detail = source(CAMPAIGN_DETAIL);

    it('gates the delete control rather than letting a member press it', () => {
        expect(detail).toMatch(/\{canManage && \(\s*<Button[\s\S]*?setConfirmDelete\(true\)/);
    });

    it('gates saving the audience, which also requires campaign management', () => {
        expect(detail).toMatch(/\{canManage && \(\s*<>\s*<SegmentBuilder/);
        expect(detail.indexOf('canManage && (')).toBeLessThan(detail.indexOf('onClick={saveAudience}'));
    });

    it('gates freezing a snapshot, which also requires campaign management', () => {
        expect(detail).toMatch(/\{canManage && \(\s*<div>\s*<Button[\s\S]*?onClick=\{freezeSnapshot\}/);
    });

    it('still offers estimating, which only requires campaign view', () => {
        expect(detail).toContain('onClick={runEstimate}');
        expect(detail).not.toMatch(/canManage[\s\S]{0,80}onClick=\{runEstimate\}/);
    });

    it('does not tell a member to save an audience they cannot save', () => {
        expect(detail).toContain('at("noAudienceHintReadOnly")');

        for (const locale of ['en', 'ja'] as const) {
            const readOnly = message(locale, 'CampaignAudience', 'noAudienceHintReadOnly');

            expect(readOnly).toBeTruthy();
            expect(readOnly).not.toBe(message(locale, 'CampaignAudience', 'noAudienceHint'));
        }
    });
});
