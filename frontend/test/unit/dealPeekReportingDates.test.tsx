/** @vitest-environment jsdom */
import { act, type PropsWithChildren } from 'react';
import { createRoot } from 'react-dom/client';
import { within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { describe, expect, it, vi } from 'vitest';
import { NowProvider } from '@/app/hooks/useNow';
import type { Activity, Deal, DealSummary } from '@/app/lib/types';
import records from '@/messages/en/records.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const profile = vi.hoisted(() => ({ timezone: 'Asia/Tokyo' }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/app/hooks/useActions', () => ({
    useActionRecord: () => undefined,
    useActions: () => ({
        context: { workspace: { timezone: 'Asia/Tokyo' }, user: { timezone: profile.timezone } },
        run: vi.fn(), getAction: () => undefined, isAvailableForRecord: () => false,
    }),
}));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useRecentRecords', () => ({ useRecentRecords: () => ({ record: vi.fn() }) }));
vi.mock('@/app/hooks/useCommentIndicators', () => ({ useCommentIndicators: () => new Map() }));
vi.mock('@/app/components/records/deals/dealRisk', () => ({ useRiskText: () => ({ factorText: () => '' }) }));
vi.mock('@/components/ui/drawer', () => {
    const Content = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return { Drawer: Content, DrawerClose: Content, DrawerContent: Content,
        DrawerDescription: Content, DrawerHeader: Content, DrawerTitle: Content };
});
vi.mock('@/app/lib/api', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/app/lib/api')>();
    return { ...actual,
        getDealById: async () => imported,
        getDealSummary: async () => summary,
        getTasksForDeal: async () => [],
        getActivitiesForDeal: async () => activities,
        getDealRisk: async () => null,
    };
});

import RecordPeekDrawer from '@/app/components/records/RecordPeekDrawer';

const imported: Deal = {
    id: 41, name: 'Historical win', value: 300, actualValue: 300, currency: 'USD',
    pipeline: 1, stage: 2, position: 0, company: null, won: true,
    closedAt: '2025-02-28 15:00:00', createdAt: '2025-02-01 00:00:00', updatedAt: '2025-03-01 00:00:00',
};
const summary: DealSummary = {
    id: 41, name: 'Historical win', value: 300, actualValue: 300, currency: 'USD', status: 'won',
};
const activities: Activity[] = [{
    id: 8, type: 'Other', subject: 'Historic activity', createdById: 7, dealId: 41,
    timestamp: '2025-02-28 15:00:00',
}];

describe('deal peek reporting close dates', () => {
    it.each([
        { timezone: 'Asia/Tokyo', activityDay: 'Mar 1' },
        { timezone: 'America/New_York', activityDay: 'Feb 28' },
    ])('keeps the reporting close day and profile activity day under $timezone', async ({ timezone, activityDay }) => {
        profile.timezone = timezone;
        const container = document.createElement('div');
        document.body.appendChild(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(
                <NextIntlClientProvider locale="en" timeZone={timezone} messages={records}>
                    <NowProvider value={Date.parse('2025-06-01T00:00:00Z')}>
                        <RecordPeekDrawer target={{ type: 'deal', id: 41 }} browserType="deal"
                            onClose={vi.fn()} onPrev={vi.fn()} onNext={vi.fn()}
                            hasPrev={false} hasNext={false} position={null} />
                    </NowProvider>
                </NextIntlClientProvider>,
            ));
            const closeTerm = within(container).getByText('Closed at', { selector: 'dt', exact: true });
            expect(closeTerm.parentElement?.querySelector('dd')?.textContent).toBe('March 1, 2025');
            const activity = within(container).getByText('Historic activity').closest('li');
            if (!activity) throw new Error('Activity missing from deal peek');
            expect(within(activity).getByText(`Other · ${activityDay}`, { exact: true })).toBeDefined();
        } finally {
            await act(async () => root.unmount());
            container.remove();
        }
    });
});
