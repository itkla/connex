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
