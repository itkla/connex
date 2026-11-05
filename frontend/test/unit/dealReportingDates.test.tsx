/** @vitest-environment jsdom */
import { renderToStaticMarkup } from 'react-dom/server';
import type { PropsWithChildren } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { describe, expect, it, vi } from 'vitest';
import type { Deal } from '@/app/lib/types';
import dealMessages from '@/messages/en/deals.json';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/components/records/RecordActionMenu', () => ({
    RecordContextMenu: ({ children }: PropsWithChildren) => children,
    RecordActionMenuTrigger: () => null,
}));
vi.mock('@/app/components/records/deals/DealRiskPill', () => ({ default: () => null }));

import DealCard from '@/app/components/records/deals/DealCard';
import DealListRow from '@/app/components/records/deals/DealListRow';
import DealKanbanCard from '@/app/components/records/deals/DealKanbanCard';

const imported: Deal = {
    id: 41, name: 'Historical win', value: 300, actualValue: 300, currency: 'USD',
    pipeline: 1, stage: 2, position: 0, company: null, won: true,
    closedAt: '2025-02-28 15:00:00', createdAt: '2025-02-01 00:00:00', updatedAt: '2025-03-01 00:00:00',
};

describe('deal list close dates match the reporting calendar', () => {
    it.each(['Asia/Tokyo', 'America/New_York'])(
        'shows the imported March 1 close under %s profile in every list layout', (profile) => {
            for (const view of [
                <DealCard key="card" deal={imported} timezone="Asia/Tokyo" />,
                <DealListRow key="list" deal={imported} timezone="Asia/Tokyo" />,
                <DealKanbanCard key="board" deal={imported} timezone="Asia/Tokyo" />,
            ]) {
                const markup = renderToStaticMarkup(
                    <NextIntlClientProvider locale="en" timeZone={profile} messages={dealMessages}>
                        {view}
                    </NextIntlClientProvider>,
                );
                const document = new DOMParser().parseFromString(markup, 'text/html');
                expect(document.body.textContent).toContain('Closed March 1, 2025');
                expect(document.body.textContent).not.toContain('February 28, 2025');
            }
        },
    );
});
