/** @vitest-environment jsdom */
import { act, type PropsWithChildren } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Task } from '@/app/lib/types';
import { NowProvider } from '@/app/hooks/useNow';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: () => undefined }) }));
vi.mock('next-intl', () => ({ useTimeZone: () => 'Asia/Tokyo', useTranslations: () => (key: string) => key }));
vi.mock('@/components/ui/checkbox', () => ({ Checkbox: () => null }));
vi.mock('@/app/components/activity/notes/NoteContent', () => ({ default: ({ children }: PropsWithChildren) => <span>{children}</span> }));
import UpcomingTasks from '@/app/components/dashboard/UpcomingTasks';

afterEach(() => { vi.useRealTimers(); });

describe('task urgency after profile midnight', () => {
    it('refreshes the clock when navigating while the layout clock stays unchanged', async () => {
        vi.useFakeTimers();
        const layoutTime = Date.parse('2026-11-04T14:59:00Z');
        vi.setSystemTime(layoutTime);
        const task: Task = { id: 1, description: 'Follow up', completed: false, status: 'todo', position: 0,
            assignedToId: 1, dueDate: '2026-11-04', createdAt: '2026-11-01T00:00:00', updatedAt: '2026-11-01T00:00:00' };
        const container = document.createElement('div');
        const root = createRoot(container);
        try {
            await act(async () => root.render(<NowProvider value={layoutTime}><UpcomingTasks key="before" tasks={[task]} locale="en" /></NowProvider>));
            expect(container.querySelector('.text-destructive')).toBeNull();
            vi.setSystemTime(Date.parse('2026-11-04T15:01:00Z'));
            await act(async () => root.render(<NowProvider value={layoutTime}><UpcomingTasks key="after" tasks={[task]} locale="en" /></NowProvider>));
            expect(container.querySelector('.text-destructive')?.textContent).toBe('Nov 4');
        } finally {
            await act(async () => root.unmount());
        }
    });
});
