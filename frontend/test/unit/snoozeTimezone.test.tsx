/** @vitest-environment jsdom */
import { act, type PropsWithChildren, type ButtonHTMLAttributes } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RadarSignal } from '@/app/lib/types';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
vi.mock('next-intl', () => ({ useTimeZone: () => 'Asia/Tokyo', useTranslations: () => (key: string) => key }));
vi.mock('@/components/ui/dialog', () => {
    const Content = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return { Dialog: Content, DialogContent: Content, DialogDescription: Content,
        DialogFooter: Content, DialogHeader: Content, DialogTitle: Content };
});
vi.mock('@/components/ui/responsive-dialog', () => {
    const Content = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return { ResponsiveDialog: Content, ResponsiveDialogContent: Content,
        ResponsiveDialogDescription: Content, ResponsiveDialogTitle: Content };
});
vi.mock('@/components/ui/button', () => ({ Button: ({ children, type, disabled, onClick }: ButtonHTMLAttributes<HTMLButtonElement>) =>
    <button type={type} disabled={disabled} onClick={onClick}>{children}</button> }));

import { SnoozeCustomDialog } from '@/app/components/notifications/SnoozeMenu';
import RadarSnoozeDialog from '@/app/components/radar/RadarSnoozeDialog';

afterEach(() => { vi.useRealTimers(); vi.unstubAllEnvs(); });

const signal: RadarSignal = {
    id: 1, family: 'relationship_decay', subject: { type: 'person', id: 7, label: 'Contact' },
    priority: 'high', state: 'active', version: 'v1', evidenceAsOf: '2026-11-05T00:00:00Z',
    stale: false, evidence: [], rank: { position: 1, rule: 'priority', factors: [] },
};

describe('profile-zone snooze controls', () => {
    it.each(['notification', 'radar'] as const)('converts %s civil input once in the profile zone', async (surface) => {
        vi.stubEnv('TZ', 'America/Los_Angeles');
        vi.useFakeTimers();
        const now = Date.parse('2026-11-05T00:00:00Z');
        vi.setSystemTime(now);
        const container = document.createElement('div');
        const root = createRoot(container);
        const snooze = vi.fn();
        try {
            await act(async () => root.render(surface === 'notification'
                ? <SnoozeCustomDialog open now={now} onOpenChange={() => undefined} onSnooze={snooze} />
                : <RadarSnoozeDialog signal={signal} open busy={false} onOpenChange={() => undefined} onSnooze={snooze} />));
            const input = container.querySelector('input[type="datetime-local"]');
            if (!(input instanceof HTMLInputElement)) throw new Error('Snooze input missing');
            expect(input.min).toBe('2026-11-05T09:01');
            const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
            if (!setter) throw new Error('Input setter missing');
            await act(async () => { setter.call(input, '2026-11-05T23:30'); input.dispatchEvent(new Event('input', { bubbles: true })); });
            if (surface === 'notification') {
                const submit = [...container.querySelectorAll('button')].find((button) => button.textContent === 'snooze');
                if (!submit) throw new Error('Snooze button missing');
                await act(async () => submit.click());
                expect(snooze).toHaveBeenCalledWith({ until: '2026-11-05T14:30:00.000Z', timezone: 'Asia/Tokyo' });
            } else {
                const form = container.querySelector('form');
                if (!form) throw new Error('Snooze form missing');
                await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
                expect(snooze).toHaveBeenCalledWith('2026-11-05T14:30:00.000Z');
            }
        } finally {
            await act(async () => root.unmount());
        }
    });
});
