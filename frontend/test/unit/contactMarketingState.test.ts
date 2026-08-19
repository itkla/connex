import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
    buildTimeline,
    entryAuthorId,
    entryId,
    TIMELINE_CAMPAIGN_TOUCH_LIMIT,
} from '@/app/components/me/timelineEntries';
import {
    channelMarketingExclusion,
    EMAIL_CHANNEL,
} from '@/app/components/records/contacts/marketingStatus';
import type {
    Activity,
    ContactChannelMarketingStatus,
    ContactLifecycleHistoryEntry,
    ContactMarketingStatus,
    Note,
    PersonCampaignTouch,
    RecordComment,
    Task,
} from '@/app/lib/types';

const CONTACT_PAGE = 'app/(app)/records/contacts/[id]/page.tsx';
const TIMELINE_ROW = 'app/components/me/TimelineRow.tsx';
const BADGE = 'app/components/records/contacts/MarketingExclusionBadge.tsx';

const EMPTY_TIMELINE = {
    tasks: [] as Task[],
    activities: [] as Activity[],
    notes: [] as Note[],
    lifecycleHistory: [] as ContactLifecycleHistoryEntry[],
    comments: [] as RecordComment[],
    campaignTouches: [] as PersonCampaignTouch[],
};

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

function channel(overrides: Partial<ContactChannelMarketingStatus> = {}): ContactChannelMarketingStatus {
    return {
        channel: EMAIL_CHANNEL,
        optedOut: false,
        doNotContact: false,
        consentRevoked: false,
        addressable: true,
        ...overrides,
    };
}

function status(overrides: Partial<ContactMarketingStatus> = {}): ContactMarketingStatus {
    return { personId: 1, privacyHold: false, channels: [channel()], ...overrides };
}

function touch(overrides: Partial<PersonCampaignTouch> = {}): PersonCampaignTouch {
    return {
        deliveryId: 1,
        campaignId: 10,
        campaignName: 'Spring outreach',
        sendId: 100,
        channel: 'email',
        status: 'delivered',
        createdAt: '2026-03-01T09:00:00Z',
        ...overrides,
    };
}

describe('a contact says whether they may still be marketed to', () => {
    it('says nothing about a channel that is still contactable', () => {
        expect(channelMarketingExclusion(status(), EMAIL_CHANNEL)).toBeNull();
    });

    it('states an opt-out', () => {
        const opted = status({
            channels: [channel({ state: 'opted_out', optedOut: true, consentRevoked: true })],
        });

        expect(channelMarketingExclusion(opted, EMAIL_CHANNEL)).toBe('opted_out');
    });

    it('states a do-not-contact, which outranks an opt-out on the same channel', () => {
        const blocked = status({
            channels: [channel({ state: 'do_not_contact', optedOut: true, doNotContact: true, consentRevoked: true })],
        });

        expect(channelMarketingExclusion(blocked, EMAIL_CHANNEL)).toBe('do_not_contact');
    });

    it('never turns a privacy hold into a marketing badge, because they are different restrictions', () => {
        const held = status({ privacyHold: true, suspendedAt: '2026-02-01T00:00:00Z' });

        expect(channelMarketingExclusion(held, EMAIL_CHANNEL)).toBeNull();
        expect(source(BADGE)).not.toContain('privacyHold');
        expect(source(CONTACT_PAGE)).toContain('contact.suspendedAt ?');
        expect(source(CONTACT_PAGE)).toContain('t("processingSuspended")');
    });

    it('reports only the channel it was asked about, so an SMS exclusion never speaks for an email address', () => {
        const smsOnly = status({
            channels: [channel({ channel: 'sms', state: 'opted_out', optedOut: true })],
        });

        expect(channelMarketingExclusion(smsOnly, EMAIL_CHANNEL)).toBeNull();
        expect(channelMarketingExclusion(smsOnly, 'sms')).toBe('opted_out');
    });

    it('stays quiet when the state could not be read at all', () => {
        expect(channelMarketingExclusion(null, EMAIL_CHANNEL)).toBeNull();
    });

    it('reads the state on the contact page and hangs it off the email row', () => {
        const page = source(CONTACT_PAGE);

        expect(page).toContain('getPersonMarketingStatus(id, init).catch(() => null)');
        expect(page).toContain('channelMarketingExclusion(marketingStatus, EMAIL_CHANNEL)');
    });
});

describe("a contact's timeline carries the campaigns that reached them", () => {
    it('files a campaign touch among the other histories, newest first', () => {
        const entries = buildTimeline({
            ...EMPTY_TIMELINE,
            activities: [
                { id: 11, subject: 'Kickoff call', timestamp: '2026-03-01T09:00:00Z' } as Activity,
                { id: 12, subject: 'Follow-up call', timestamp: '2026-03-05T09:00:00Z' } as Activity,
            ],
            campaignTouches: [touch({ deliveryId: 21, createdAt: '2026-03-03T09:00:00Z' })],
        });

        expect(entries.map((entry) => [entry.kind, entryId(entry)])).toEqual([
            ['activity', 12],
            ['campaign', 21],
            ['activity', 11],
        ]);
    });

    it('dates a touch by when it happened, not by when a late receipt changed its row', () => {
        const [entry] = buildTimeline({
            ...EMPTY_TIMELINE,
            campaignTouches: [touch({
                createdAt: '2026-01-05T09:00:00Z',
                updatedAt: '2026-08-19T09:00:00Z',
            })],
        });

        expect(entry.sortAt).toBe(Date.parse('2026-01-05T09:00:00Z'));
    });

    it('orders touches the way the server pages them, so the loaded window is a prefix of the display order', () => {
        const older = touch({ deliveryId: 31, createdAt: '2026-01-05T09:00:00Z', updatedAt: '2026-08-19T09:00:00Z' });
        const newer = touch({ deliveryId: 32, createdAt: '2026-03-01T09:00:00Z', updatedAt: '2026-03-01T09:00:00Z' });
        const entries = buildTimeline({ ...EMPTY_TIMELINE, campaignTouches: [older, newer] });

        expect(entries.map((entry) => entryId(entry))).toEqual([32, 31]);
    });

    it('attributes a touch to no member, because the workspace sent it rather than a person', () => {
        const [entry] = buildTimeline({ ...EMPTY_TIMELINE, campaignTouches: [touch()] });

        expect(entryAuthorId(entry)).toBeUndefined();
    });

    it('renders a touch read-only, linking to the campaign that explains it', () => {
        const row = source(TIMELINE_ROW);

        expect(row).toContain("campaign: 'chipCampaign'");
        expect(row).toContain('href={`/marketing/campaigns/${campaign.campaignId}`}');
        expect(row).toContain(
            "entry.kind === 'lifecycle' || entry.kind === 'comment' || entry.kind === 'campaign';",
        );
    });

    it('asks for the touches only when the reader may read campaigns, and stays quiet when the read fails', () => {
        const page = source(CONTACT_PAGE);

        expect(page).toContain('permissions.includes("CAMPAIGN_VIEW")');
        expect(page).toContain('.catch(() => [])');
        expect(page).toContain(`getPersonCampaignTouches(id, { size: TIMELINE_CAMPAIGN_TOUCH_LIMIT }, init)`);
        expect(TIMELINE_CAMPAIGN_TOUCH_LIMIT).toBeGreaterThan(0);
    });

    it('gates on the same permissions read the rest of the page uses, and does not wait for the batch to finish', () => {
        const page = source(CONTACT_PAGE);

        expect(page).toContain('const permissionsPromise = getEffectivePermissionsFromCookie(cookie);');
        expect(page.match(/getEffectivePermissionsFromCookie\(/g)).toHaveLength(1);
        expect(page, 'the touches read belongs in the batch, not after it')
            .not.toContain('= effectivePermissions.includes("CAMPAIGN_VIEW")');
    });

    it('carries a denied reader an empty chronology rather than a broken one', () => {
        expect(buildTimeline({ ...EMPTY_TIMELINE })).toEqual([]);
    });
});
