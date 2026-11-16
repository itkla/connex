/** @vitest-environment jsdom */
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { describe, expect, it, vi } from 'vitest';
import DayTimeline from '@/app/components/calendar/DayTimeline';
import calendarMessages from '@/messages/en/calendar.json';
import { toMysqlDateTime } from '@/app/lib/utils';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

describe('calendar civil slot creation', () => {
    it('preserves a Tokyo 02:30 slot during the browser’s New York DST gap', async () => {
        vi.stubEnv('TZ', 'America/New_York');
        const container = document.createElement('div');
        const root = createRoot(container);
        const onSlotCreate = vi.fn<(value: string) => void>();
        try {
            await act(async () => root.render(
                <NextIntlClientProvider locale="en" timeZone="Asia/Tokyo" messages={calendarMessages}>
                    <DayTimeline day={new Date(2026, 2, 8)} today={new Date(2026, 2, 9)} events={[]}
                        locale="en" onOpenEvent={() => undefined} onSlotCreate={onSlotCreate} />
                </NextIntlClientProvider>,
            ));
            const target = container.querySelector('button[tabindex="-1"]');
            if (!(target instanceof HTMLButtonElement)) throw new Error('Calendar slot target missing');
            await act(async () => target.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1, clientY: 140 })));
            expect(onSlotCreate).toHaveBeenCalledWith('2026-03-08T02:30');
            expect(toMysqlDateTime('2026-03-08T02:30', 'Asia/Tokyo')).toBe('2026-03-07 17:30:00');
        } finally {
            await act(async () => root.unmount());
            vi.unstubAllEnvs();
        }
    });
});
