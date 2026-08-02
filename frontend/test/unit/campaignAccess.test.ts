import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { ApiError, DEFAULT_CAPABILITIES } from '@/app/lib/api';
import { NO_NAV_ACCESS, resolveNavAccess } from '@/app/lib/navAccess';
import { loadCollection } from '@/app/lib/recordAccess';

const CAMPAIGN_DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';
const CAMPAIGN_LIST = 'app/(app)/marketing/campaigns/page.tsx';
const SIDEBAR = 'app/components/Sidebar.tsx';
const NAV_BRIDGE = 'app/components/actions/NavActionsBridge.tsx';
const SEED_ACTIONS = 'app/lib/actions/seedActions.ts';

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

const MANAGE_GATE = '{canManage && (';

/** The source of every `{canManage && ( … )}` block, balanced on parentheses. */
function manageGatedBlocks(text: string): string[] {
    const blocks: string[] = [];
    for (let from = 0; ; ) {
        const start = text.indexOf(MANAGE_GATE, from);
        if (start === -1) return blocks;
        let depth = 0;
        let index = start + MANAGE_GATE.length - 1;
        for (; index < text.length; index += 1) {
            if (text[index] === '(') depth += 1;
            else if (text[index] === ')' && (depth -= 1) === 0) break;
        }
        blocks.push(text.slice(start, index + 1));
        from = index + 1;
    }
}

function occurrences(haystack: string, needle: string): number {
    return haystack.split(needle).length - 1;
}

/**
 * Every control below calls an endpoint the backend guards with `CAMPAIGN_MANAGE`, which the
 * built-in `member` role does not hold. Rendering them for a member is a guaranteed 403, so each
 * must appear only inside a `canManage` gate — not merely somewhere after one.
 */
describe('campaign detail offers a member no control that will 403', () => {
    const detail = source(CAMPAIGN_DETAIL);
    const gated = manageGatedBlocks(detail);

    function onlyRenderedWhenManaging(trigger: string): void {
        const total = occurrences(detail, trigger);
        const inGate = gated.reduce((count, block) => count + occurrences(block, trigger), 0);

        expect(total).toBeGreaterThan(0);
        expect(inGate).toBe(total);
    }

    it('gates the delete control rather than letting a member press it', () => {
        onlyRenderedWhenManaging('setConfirmDelete(true)');
    });

    it('gates saving the audience, which also requires campaign management', () => {
        onlyRenderedWhenManaging('onClick={saveAudience}');
    });

    it('gates freezing a snapshot, which also requires campaign management', () => {
        onlyRenderedWhenManaging('onClick={freezeSnapshot}');
    });

    it('gates editing the campaign, which also requires campaign management', () => {
        onlyRenderedWhenManaging('onClick={openEdit}');
    });

    it('still offers estimating, which only requires campaign view', () => {
        const total = occurrences(detail, 'onClick={runEstimate}');
        const inGate = gated.reduce(
            (count, block) => count + occurrences(block, 'onClick={runEstimate}'),
            0,
        );

        expect(total).toBe(1);
        expect(inGate).toBe(0);
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

describe('/marketing/campaigns never manufactures an empty campaign list', () => {
    it('reports a refused campaign fetch as forbidden rather than as empty', async () => {
        const access = await loadCollection(async () => {
            throw new ApiError('Requires the CAMPAIGN_VIEW permission in this workspace', 403);
        });

        expect(access).toEqual({ kind: 'forbidden' });
    });

    it('does not swallow a refused fetch into an empty list', () => {
        const page = source(CAMPAIGN_LIST);

        expect(page).not.toMatch(/\.catch\s*\(/);
        expect(page).toContain('loadCollection(');
        expect(page).not.toContain('getCampaignsFromCookie');
    });

    it('returns the denial state for a viewer who lacks campaign access', () => {
        const page = source(CAMPAIGN_LIST);

        expect(page).toMatch(/access\.kind === "forbidden"/);
        expect(page).toContain('<AccessDeniedPage');
        expect(page.indexOf('forbidden')).toBeLessThan(page.indexOf('<CampaignsBrowser'));
    });

    it('localizes the denial copy in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const title = message(locale, 'CampaignsPage', 'deniedTitle');
            const body = message(locale, 'CampaignsPage', 'deniedBody');

            expect(title).toBeTruthy();
            expect(body).toBeTruthy();
            expect(body).not.toBe(message(locale, 'CampaignsPage', 'emptyHint'));
        }
    });
});

describe('the campaigns nav is gated on the permission it needs', () => {
    it('offers campaigns to a viewer holding CAMPAIGN_VIEW', () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['CAMPAIGN_VIEW']).campaigns).toBe(true);
    });

    it('hides campaigns from a custom role built without CAMPAIGN_VIEW', () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['GOAL_READ']).campaigns).toBe(false);
    });

    it('stays visible on an instance that cannot dispatch, since planning still works', () => {
        expect(DEFAULT_CAPABILITIES.campaignDelivery).toBe(false);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['CAMPAIGN_VIEW']).campaigns).toBe(true);
    });

    it('fails closed when permissions could not be resolved', () => {
        expect(NO_NAV_ACCESS.campaigns).toBe(false);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, []).campaigns).toBe(false);
    });

    it('gates the sidebar section and the palette on the same resolved access', () => {
        expect(source(SIDEBAR)).toContain('...(navAccess.campaigns ? [marketingSection] : [])');
        expect(source(NAV_BRIDGE)).toContain('if (navAccess.campaigns) {');
        expect(source(NAV_BRIDGE)).toContain('navAccess.campaigns');
        expect(source(SEED_ACTIONS)).not.toContain('/marketing/campaigns');
    });
});
