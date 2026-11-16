/** @vitest-environment jsdom */
import { act, type PropsWithChildren, type ButtonHTMLAttributes } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DataSubjectRequest } from '@/app/lib/types';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({ update: vi.fn(), create: vi.fn() }));
vi.mock('next-intl', () => ({ useTimeZone: () => 'America/New_York', useTranslations: () => (key: string) => key }));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    updateDataSubjectRequest: api.update,
    createDataSubjectRequest: api.create,
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ workspaces: [], activeWorkspace: null }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/hooks/usePasskeyStepUpError', () => ({ usePasskeyStepUpErrorHandler: () => vi.fn() }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));
vi.mock('@/components/ui/dialog', () => {
    const Content = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return { Dialog: Content, DialogContent: Content, DialogDescription: Content, DialogFooter: Content,
        DialogHeader: Content, DialogTitle: Content, DialogClose: Content };
});
vi.mock('@/components/ui/select', () => {
    const Content = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return { Select: Content, SelectContent: Content, SelectTrigger: Content, SelectValue: Content, SelectItem: Content };
});
vi.mock('@/components/ui/button', () => ({ Button: ({ children, type, disabled }: ButtonHTMLAttributes<HTMLButtonElement>) =>
    <button type={type} disabled={disabled}>{children}</button> }));
vi.mock('@/components/ui/dialog-status-cover', () => ({
    DialogStatusCover: () => null, resolveDialogStatus: () => 'idle', fieldInputClass: '', fieldErrorClass: '', fieldLeadIconClass: '',
}));

import DataRequestDialog from '@/app/components/organization/DataRequestDialog';

afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

describe('data request timestamp preservation', () => {
    it('resubmits all five original instants after an unrelated edit in the repeated DST hour', async () => {
        vi.useFakeTimers();
        const originals = {
            receivedAt: '2026-11-01T06:30:11', identityVerifiedAt: '2026-11-01T06:31:12',
            dueAt: '2026-11-01T06:32:13', respondedAt: '2026-11-01T06:33:14', closedAt: '2026-11-01T06:34:15',
        };
        const request: DataSubjectRequest = {
            id: 41, orgId: 3, snapshotToken: 'snapshot', requestType: 'disclosure', status: 'received',
            requesterName: 'Requester', subjectName: 'Subject', ...originals,
            createdAt: originals.receivedAt, updatedAt: originals.closedAt,
        };
        api.update.mockResolvedValue(request);
        const container = document.createElement('div');
        const root = createRoot(container);
        try {
            await act(async () => root.render(<DataRequestDialog open onOpenChange={() => undefined} orgId={3}
                editing={request} onSaved={() => undefined} />));
            container.querySelectorAll<HTMLInputElement>('input[type="datetime-local"]').forEach((input, index) => {
                expect(input.value).toBe(`2026-11-01T01:${30 + index}`);
            });
            expect(container.querySelectorAll('input[type="datetime-local"]')).toHaveLength(5);
            const summary = container.querySelector('#dsr-summary');
            if (!(summary instanceof HTMLTextAreaElement)) throw new Error('Request summary missing');
            const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
            if (!setter) throw new Error('Textarea setter missing');
            await act(async () => { setter.call(summary, 'Updated summary'); summary.dispatchEvent(new Event('input', { bubbles: true })); });
            const form = container.querySelector('form');
            if (!form) throw new Error('Request form missing');
            await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
            expect(api.update).toHaveBeenCalledWith(3, 41, expect.objectContaining({
                summary: 'Updated summary', ...originals,
            }));
        } finally {
            await act(async () => root.unmount());
            vi.clearAllTimers();
        }
    });
});
