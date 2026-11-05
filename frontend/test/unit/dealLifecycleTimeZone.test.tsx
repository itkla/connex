import { type PropsWithChildren } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const profile = vi.hoisted<{ zone: string | undefined }>(() => ({ zone: 'UTC' }));
vi.mock('next-intl/server', () => ({
    getTimeZone: async () => profile.zone,
    getLocale: async () => 'en',
    getTranslations: async () => (key: string) => key,
}));
vi.mock('@/app/lib/requestClock', () => ({ requestNow: () => Date.parse('2025-03-03T12:00:00Z') }));
vi.mock('@/app/components/activity/notes/NoteContent', () => ({ default: () => null }));
vi.mock('@/components/ui/tooltip', () => {
    const Content = ({ children }: PropsWithChildren) => <>{children}</>;
    return { Tooltip: Content, TooltipTrigger: Content, TooltipContent: Content };
});

import DealLifecycleProgress from '@/app/components/records/deals/DealLifecycleProgress';

beforeEach(() => { profile.zone = 'UTC'; });

describe('deal lifecycle close-date display', () => {
    it.each([
        { zone: 'Asia/Tokyo', workspace: 'America/New_York', expected: 'February 28, 2025' },
        { zone: 'Asia/Tokyo', workspace: 'Asia/Tokyo', expected: 'March 1, 2025' },
        { zone: 'America/New_York', workspace: 'Asia/Tokyo', expected: 'March 1, 2025' },
        { zone: undefined, workspace: 'UTC+9', expected: 'March 1, 2025' },
        { zone: 'invalid', workspace: 'invalid', expected: 'February 28, 2025' },
    ])('uses reporting workspace $workspace despite profile $zone for imported instants', async ({ zone, workspace, expected }) => {
        profile.zone = zone;
        const html = renderToStaticMarkup(await DealLifecycleProgress({
            stages: [], timezone: workspace, currentStageId: null, outcome: 'won',
            createdAt: '2025-01-01 00:00:00', closedAt: '2025-02-28 15:00:00',
        }));
        expect(html).toContain(`>${expected}</span>`);
    });

    it('preserves a calendar-only close date west of UTC', async () => {
        profile.zone = 'America/Los_Angeles';
        const html = renderToStaticMarkup(await DealLifecycleProgress({
            stages: [], timezone: 'Asia/Tokyo', currentStageId: null, outcome: 'won',
            createdAt: '2025-01-01 00:00:00', closedAt: '2025-03-01',
        }));
        expect(html).toContain('>March 1, 2025</span>');
    });

    it('keeps creation instants in the profile zone alongside the reporting close day', async () => {
        profile.zone = 'America/New_York';
        const html = renderToStaticMarkup(await DealLifecycleProgress({
            stages: [], timezone: 'Asia/Tokyo', currentStageId: null, outcome: 'won',
            createdAt: '2025-01-01 00:00:00', closedAt: '2025-02-28 15:00:00',
        }));
        expect(html).toContain('>December 31, 2024</span>');
        expect(html).toContain('>March 1, 2025</span>');
    });

    it('compares the displayed close day with its calendar-only expected day east of UTC', async () => {
        profile.zone = 'America/New_York';
        const html = renderToStaticMarkup(await DealLifecycleProgress({
            stages: [], timezone: 'Pacific/Kiritimati', currentStageId: null, outcome: 'won',
            createdAt: '2025-01-01 00:00:00', closedAt: '2025-02-28 10:00:00', expectedCloseDate: '2025-03-01',
        }));
        expect(html).toContain('>March 1, 2025</span>');
        expect(html).toContain('>onTime</span>');
    });
});
