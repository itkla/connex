import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const DELIVERY_PANEL = 'app/components/marketing/campaigns/CampaignDelivery.tsx';
const TYPES = 'app/lib/types.ts';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function sendsNamespace(locale: 'en' | 'ja'): { [key: string]: MessageValue } {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
    if (!isMessageTree(parsed) || !isMessageTree(parsed.CampaignSends)) {
        throw new Error(`messages/${locale}/campaigns.json has no CampaignSends namespace`);
    }
    return parsed.CampaignSends;
}

/**
 * The dispatch worker claims queued sends on status alone, so a stored `scheduledAt` never delays
 * anything. The control that promised otherwise is gone rather than merely disabled.
 */
describe('campaigns promise no send scheduling the backend does not perform', () => {
    it('offers no schedule picker in the create-send form', () => {
        const panel = source(DELIVERY_PANEL);

        expect(panel).not.toContain('datetime-local');
        expect(panel).not.toContain('send-scheduled');
        expect(panel).not.toContain('sendScheduledAt');
    });

    it('sends no scheduled time when creating a send', () => {
        expect(source(DELIVERY_PANEL)).not.toContain('scheduledAt');
    });

    it('keeps a scheduled time out of the send request type', () => {
        const types = source(TYPES);
        const payload = types.slice(
            types.indexOf('export type CampaignSendPayload'),
            types.indexOf('}', types.indexOf('export type CampaignSendPayload')),
        );

        expect(payload).toContain('snapshotVersion');
        expect(payload).not.toContain('scheduledAt');
    });

    it('states when a queued send actually goes out, in both locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const namespace = sendsNamespace(locale);

            expect(typeof namespace.queueHint).toBe('string');
            expect(namespace.queueHint).toBeTruthy();
            expect(namespace.scheduledAt).toBeUndefined();
        }
    });
});
