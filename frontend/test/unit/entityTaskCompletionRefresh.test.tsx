/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import EntityNotificationBanner from '@/app/components/notifications/EntityNotificationBanner';
import type { Notification } from '@/app/lib/types';

const requests = vi.hoisted(() => ({ complete: vi.fn(), refresh: vi.fn(), unread: vi.fn(), error: vi.fn() }));
vi.mock('@/app/lib/api', () => ({ completeTask: requests.complete, dismissNotification: vi.fn(), getContextNotifications: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: requests.refresh }) }));
vi.mock('next-intl', () => ({ useLocale: () => 'en', useTranslations: () => (key: string) => key }));
vi.mock('@/app/hooks/useNotifications', () => ({ useNotifications: () => ({ recipientId: 1, refreshUnread: requests.unread }) }));
vi.mock('@/app/components/notifications/notificationEvents', () => ({ onNotificationStateChanged: () => () => {}, emitNotificationStateChanged: vi.fn() }));
vi.mock('@/app/lib/toast', () => ({ toastError: requests.error }));

const due: Notification = {
    id: 5, type: 'task.due', category: 'task', severity: 'info', templateVersion: 1, title: 'Follow up',
    sourceType: 'task', sourceId: 9, contextType: 'person', contextId: 42,
    triggeredAt: '2026-11-07T12:00:00Z', createdAt: '2026-11-07T12:00:00Z', updatedAt: '2026-11-07T12:00:00Z',
};

function banner() {
    return <EntityNotificationBanner initialNotifications={[due]} contextType="person" contextId={42} initialStateVersion={1} />;
}

beforeEach(() => { vi.clearAllMocks(); requests.unread.mockResolvedValue(undefined); });
afterEach(cleanup);

describe('record task completion refresh', () => {
    it('refreshes the record only after task completion succeeds, without waiting for unread counts', async () => {
        let complete: (() => void) | undefined;
        let counts: (() => void) | undefined;
        requests.complete.mockImplementationOnce(() => new Promise<void>((resolve) => { complete = resolve; }));
        requests.unread.mockImplementationOnce(() => new Promise<void>((resolve) => { counts = resolve; }));
        render(banner());
        fireEvent.click(screen.getByRole('button', { name: 'completeTask' }));
        expect(requests.complete).toHaveBeenCalledWith(9);
        expect(requests.refresh).not.toHaveBeenCalled();
        await act(async () => {
            if (!complete) throw new Error('Task completion was not started');
            complete();
        });
        expect(requests.refresh).toHaveBeenCalledOnce();
        expect(screen.queryByRole('alert')).toBeNull();
        await act(async () => { counts?.(); });
    });

    it('keeps the notification and current record when task completion fails', async () => {
        requests.complete.mockRejectedValueOnce(new Error('Refused'));
        render(banner());
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'completeTask' })); });
        expect(requests.refresh).not.toHaveBeenCalled();
        expect(requests.unread).not.toHaveBeenCalled();
        expect(requests.error).toHaveBeenCalledWith('completeError');
        expect(screen.getByRole('alert')).toBeTruthy();
    });
});
