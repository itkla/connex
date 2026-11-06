/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import Link from 'next/link';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TemplateBuilder from '@/app/components/library/documents/TemplateBuilder';
import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import { useNotificationWorkspaceActions } from '@/app/components/notifications/useNotificationWorkspaceActions';
import { blankReportConfig } from '@/app/components/reports/reportConfig';
import { requestGuardedNavigation, useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { useWorkspace, WorkspaceProvider } from '@/app/hooks/useWorkspace';
import { bindRenderedWorkspace, releaseRenderedWorkspace, renderedWorkspaceId } from '@/app/lib/renderedWorkspace';
import type { DocumentTemplate, Notification, ReportDefinition, Workspace } from '@/app/lib/types';
import common from '@/messages/en/common.json';
import documents from '@/messages/en/document-templates.json';
import reports from '@/messages/en/reports.json';
import jaCommon from '@/messages/ja/common.json';
import jaDocuments from '@/messages/ja/document-templates.json';
import jaReports from '@/messages/ja/reports.json';

const mocks = vi.hoisted(() => ({
    push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn(), createReport: vi.fn(), updateReport: vi.fn(),
    createTemplate: vi.fn(), updateTemplate: vi.fn(), showError: vi.fn(),
    switchWorkspace: vi.fn(),
}));
vi.mock('next/navigation', () => ({ useRouter: () => mocks, usePathname: () => window.location.pathname, useSearchParams: () => new URLSearchParams(window.location.search) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => mocks.showError }));
vi.mock('@/app/lib/toast', () => ({ toastError: vi.fn(), toastSuccess: vi.fn() }));
vi.mock('@/app/lib/api', () => ({
    createReport: mocks.createReport, updateReport: mocks.updateReport,
    createDocumentTemplate: mocks.createTemplate, updateDocumentTemplate: mocks.updateTemplate,
    switchWorkspace: mocks.switchWorkspace, createWorkspace: vi.fn(), readAuthoritativeWorkspaceSelection: vi.fn(),
    WorkspaceSelectionUnavailableError: class extends Error {},
}));
vi.mock('motion/react', () => ({ useReducedMotion: () => true }));
vi.mock('@/app/components/layout/SortableGrid', () => ({ default: () => null }));
vi.mock('@/app/components/library/documents/editor/DocumentBodyEditor', () => ({
    default: ({ value, onChange, editable }: { value: string | null; onChange: (value: string) => void; editable: boolean }) => (
        <textarea aria-label="Template body" value={value ?? ''} disabled={!editable} onChange={(event) => onChange(event.target.value)} />
    ),
}));

const report: ReportDefinition = {
    id: 42, name: 'Sales', description: null, cadence: 'monthly', templateKey: null,
    config: blankReportConfig(), createdBy: 1, createdAt: '', updatedAt: '',
};
const template: DocumentTemplate = {
    id: 17, name: 'Quote', type: 'quote', locale: 'en', title: 'Quotation', active: true,
    intro: 'Existing introduction', createdAt: '', updatedAt: '',
};

const workspaces: Workspace[] = [1, 2].map((id) => ({
    id, name: `Workspace ${id}`, slug: `workspace-${id}`, timezone: null, identityVersion: 0, role: 'member',
    orgId: id, orgName: `Organization ${id}`, orgIdentityVersion: 0, orgRole: null,
}));

function mountGuardedWorkspace() {
    return renderHook(() => ({
        workspace: useWorkspace(),
        notifications: useNotificationWorkspaceActions(),
        guard: useUnsavedChangesGuard({ isDirty: true, onClose: vi.fn(), navigation: { push: mocks.push, renderedHref: '/', onDiscard: vi.fn() } }),
    }), { wrapper: ({ children }) => <WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={1}>{children}</WorkspaceProvider> });
}

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: (media: string) => ({
        media, matches: false, addEventListener() {}, removeEventListener() {},
    }) });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/'); });

function mount(kind: 'report' | 'template', existing = false, locale: 'en' | 'ja' = 'en') {
    const messages = locale === 'en' ? { ...common, ...documents, ...reports } : { ...jaCommon, ...jaDocuments, ...jaReports };
    return render(<NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
        <Link href="/records/deals">Deals</Link>
        {kind === 'report'
            ? <ReportBuilderBoard initialReport={existing ? report : undefined} pipelines={[]} owners={[]} ownersFailed={false} tags={[]} canReadGoals />
            : <TemplateBuilder template={existing ? template : null} />}
    </NextIntlClientProvider>);
}

function unloadPrevented() {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
}

describe('full-page editor navigation', () => {
    it.each([1, 2, 'global'] as const)('guards notification opens in workspace %s before any side effects', async (workspaceId) => {
        mocks.switchWorkspace.mockResolvedValue(undefined);
        const notification: Notification = {
            id: 71, type: workspaceId === 'global' ? 'workspace.join' : 'task.assigned',
            workspaceId: workspaceId === 'global' ? undefined : workspaceId,
            category: 'tasks', severity: 'info', templateVersion: 1, title: 'Task',
            actionUrl: '/activity/tasks?task=41', triggeredAt: '', createdAt: '', updatedAt: '',
        };
        const beforeOpen = vi.fn(async () => {});
        const { result } = mountGuardedWorkspace();
        let opening: Promise<boolean> | undefined;
        act(() => { opening = result.current.notifications.openInNotificationWorkspace(notification, notification.actionUrl, beforeOpen); });
        expect(result.current.guard.confirm.open).toBe(true);
        expect(mocks.switchWorkspace).not.toHaveBeenCalled();
        expect(renderedWorkspaceId()).toBe(1);
        expect(beforeOpen).not.toHaveBeenCalled();
        expect(mocks.push).not.toHaveBeenCalled();
        await act(async () => { result.current.guard.confirm.onKeepEditing(); expect(await opening).toBe(true); });
        expect(beforeOpen).not.toHaveBeenCalled();
        expect(mocks.switchWorkspace).not.toHaveBeenCalled();
        act(() => { opening = result.current.notifications.openInNotificationWorkspace(notification, notification.actionUrl, beforeOpen); });
        await act(async () => { result.current.guard.confirm.onDiscard(); expect(await opening).toBe(true); });
        expect(beforeOpen).toHaveBeenCalledOnce();
        if (workspaceId === 2) {
            expect(mocks.switchWorkspace).toHaveBeenCalledExactlyOnceWith(2);
            expect(mocks.replace).toHaveBeenCalledWith(notification.actionUrl);
            expect(renderedWorkspaceId()).toBe(2);
        } else {
            expect(mocks.switchWorkspace).not.toHaveBeenCalled();
            expect(mocks.push).toHaveBeenCalledWith(notification.actionUrl);
        }
    });

    it('guards cross-workspace notification actions while allowing actions that stay in the editor workspace', async () => {
        mocks.switchWorkspace.mockResolvedValue(undefined);
        const notification: Notification = {
            id: 72, workspaceId: 2, type: 'task.assigned', category: 'tasks', severity: 'info',
            templateVersion: 1, title: 'Task', triggeredAt: '', createdAt: '', updatedAt: '',
        };
        const action = vi.fn(async () => {});
        const { result } = mountGuardedWorkspace();
        let executing: Promise<boolean> | undefined;
        act(() => { executing = result.current.notifications.executeInNotificationWorkspace(notification, action); });
        expect(result.current.guard.confirm.open).toBe(true);
        expect(action).not.toHaveBeenCalled();
        expect(mocks.switchWorkspace).not.toHaveBeenCalled();
        await act(async () => { result.current.guard.confirm.onKeepEditing(); expect(await executing).toBe(true); });
        expect(action).not.toHaveBeenCalled();
        act(() => { executing = result.current.notifications.executeInNotificationWorkspace(notification, action); });
        await act(async () => { result.current.guard.confirm.onDiscard(); expect(await executing).toBe(true); });
        expect(action).toHaveBeenCalledOnce();
        expect(renderedWorkspaceId()).toBe(2);
        expect(mocks.replace).toHaveBeenCalledWith('/dashboard');
        await act(async () => { expect(await result.current.notifications.executeInNotificationWorkspace(notification, action)).toBe(true); });
        expect(action).toHaveBeenCalledTimes(2);
        expect(result.current.guard.confirm.open).toBe(false);
    });

    it.each(['report', 'template'] as const)('discards a pending new %s so its later response cannot retain the draft or duplicate it', async (kind) => {
        const pending = Promise.withResolvers<ReportDefinition | DocumentTemplate>();
        mocks.createReport.mockReturnValue(pending.promise);
        mocks.createTemplate.mockReturnValue(pending.promise);
        const path = kind === 'report' ? '/insights/reports/new' : '/library/documents/new';
        window.history.replaceState({ __NA: true }, '', path);
        mount(kind);
        const oldInput = screen.getByLabelText('Name');
        fireEvent.change(oldInput, { target: { value: 'Discarded creation' } });
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        act(() => requestGuardedNavigation(() => mocks.push(`${path}?mode=other`)));
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        const fresh = await screen.findByLabelText('Name');
        expect(fresh).not.toBe(oldInput);
        expect(fresh).toHaveProperty('value', '');
        await act(async () => pending.resolve(kind === 'report' ? report : template));
        expect(fresh).toHaveProperty('value', '');
        expect(fresh.matches(':disabled')).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        expect(kind === 'report' ? mocks.createReport : mocks.createTemplate).toHaveBeenCalledOnce();
        expect(unloadPrevented()).toBe(false);
    });
    it('guards workspace selection before switching or publishing a new workspace', async () => {
        mocks.switchWorkspace.mockResolvedValue(undefined);
        const { result } = mountGuardedWorkspace();
        let canceled: Promise<void> | undefined;
        act(() => { canceled = result.current.workspace.switchTo(2); });
        expect(mocks.switchWorkspace).not.toHaveBeenCalled();
        expect(result.current.workspace.switching).toBe(false);
        expect(renderedWorkspaceId()).toBe(1);
        await act(async () => { result.current.guard.confirm.onKeepEditing(); await canceled; });
        expect(mocks.switchWorkspace).not.toHaveBeenCalled();
        expect(mocks.replace).not.toHaveBeenCalled();
        let confirmed: Promise<void> | undefined;
        act(() => { confirmed = result.current.workspace.switchTo(2); });
        await act(async () => { result.current.guard.confirm.onDiscard(); await confirmed; });
        expect(mocks.switchWorkspace).toHaveBeenCalledExactlyOnceWith(2);
        expect(renderedWorkspaceId()).toBe(2);
        expect(mocks.replace).toHaveBeenCalledWith('/dashboard');
    });

    it('rearms the editor guard when a confirmed workspace switch fails', async () => {
        const failure = new Error('Switch refused');
        mocks.switchWorkspace.mockRejectedValue(failure);
        const { result } = mountGuardedWorkspace();
        let switching: Promise<void> | undefined;
        act(() => { switching = result.current.workspace.switchTo(2); });
        if (!switching) throw new Error('Switch was not requested');
        const refused = switching.catch((error: unknown) => error);
        await act(async () => { result.current.guard.confirm.onDiscard(); expect(await refused).toBe(failure); });
        expect(renderedWorkspaceId()).toBe(1);
        act(() => result.current.guard.requestClose());
        expect(result.current.guard.confirm.open).toBe(true);
    });
    it('uses the latest dismissal callback after a caller changes its requested exit', () => {
        const close = vi.fn();
        const back = vi.fn();
        const { result, rerender } = renderHook(({ onClose }) => useUnsavedChangesGuard({ isDirty: true, onClose }), {
            initialProps: { onClose: close },
        });
        act(() => result.current.requestClose());
        rerender({ onClose: back });
        act(() => result.current.confirm.onDiscard());
        expect(back).toHaveBeenCalledOnce();
        expect(close).not.toHaveBeenCalled();
    });

    it('retains an explicit navigation destination when the dismissal callback changes', () => {
        const destination = vi.fn();
        const close = vi.fn();
        const { result, rerender } = renderHook(({ onClose }) => useUnsavedChangesGuard({ isDirty: true, onClose, navigation: { push: mocks.push, renderedHref: '/', onDiscard: vi.fn() } }), {
            initialProps: { onClose: vi.fn() },
        });
        act(() => requestGuardedNavigation(destination));
        rerender({ onClose: close });
        act(() => result.current.confirm.onDiscard());
        expect(destination).toHaveBeenCalledOnce();
        expect(close).not.toHaveBeenCalled();
    });
    it.each(['report', 'template'] as const)('keeps a dirty %s and restores the triggering control after Keep editing', async (kind) => {
        mount(kind);
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Unsaved work' } });
        const back = screen.getByRole('button', { name: kind === 'report' ? 'Cancel' : 'Documents' });
        back.focus();
        fireEvent.click(back);
        await screen.findByRole('dialog', { name: common.ConfirmDiscard.title });
        expect(mocks.push).not.toHaveBeenCalled();
        expect(unloadPrevented()).toBe(true);
        fireEvent.click(screen.getByRole('button', { name: common.ConfirmDiscard.keepEditing }));
        await waitFor(() => expect(document.activeElement).toBe(back));
        expect(input).toHaveProperty('value', 'Unsaved work');
        fireEvent.click(back);
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        expect(mocks.push).toHaveBeenCalledWith(kind === 'report' ? '/insights/reports' : '/library/documents');
        expect(await screen.findByLabelText('Name')).toHaveProperty('value', '');
        expect(unloadPrevented()).toBe(false);
    });

    it.each(['report', 'template'] as const)('allows an untouched existing %s to return without prompting', (kind) => {
        mount(kind, true);
        expect(unloadPrevented()).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? 'Cancel' : 'Documents' }));
        expect(mocks.push).toHaveBeenCalledWith(kind === 'report' ? '/insights/reports/42' : '/library/documents');
        expect(mocks.back).not.toHaveBeenCalled();
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('detects report configuration changes without a name edit and keeps failed saves protected', async () => {
        mount('report', true);
        fireEvent.click(screen.getByRole('button', { name: reports.Reports.builder.addWidget }));
        expect(unloadPrevented()).toBe(true);
        mocks.updateReport.mockRejectedValue(new Error('Unavailable'));
        fireEvent.click(screen.getByRole('button', { name: reports.Reports.builder.save }));
        await waitFor(() => expect(mocks.showError).toHaveBeenCalled());
        expect(unloadPrevented()).toBe(true);
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(await screen.findByRole('dialog')).toBeTruthy();
    });

    it('restores clean state when report fields are reverted without reseeding widget IDs', () => {
        mount('report');
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Temporary' } });
        expect(unloadPrevented()).toBe(true);
        fireEvent.change(input, { target: { value: '' } });
        expect(unloadPrevented()).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(mocks.push).toHaveBeenCalledWith('/insights/reports');
    });

    it('detects template body-only edits and leaves a failed save protected', async () => {
        mount('template', true);
        fireEvent.change(screen.getByLabelText('Template body'), { target: { value: '{"type":"doc","content":[{"type":"paragraph"}]}' } });
        expect(unloadPrevented()).toBe(true);
        mocks.updateTemplate.mockRejectedValue(new Error('Unavailable'));
        fireEvent.click(screen.getByRole('button', { name: documents.DocumentTemplateBuilder.save }));
        await waitFor(() => expect(mocks.showError).toHaveBeenCalled());
        expect(unloadPrevented()).toBe(true);
    });

    it.each(['report', 'template'] as const)('bypasses the %s guard only after a successful save', async (kind) => {
        mount(kind, true);
        fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Updated' } });
        mocks.updateReport.mockResolvedValue(report);
        mocks.updateTemplate.mockResolvedValue(template);
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        await waitFor(() => expect(mocks.push).toHaveBeenCalledWith(kind === 'report' ? '/insights/reports/42' : '/library/documents'));
        expect(screen.queryByRole('dialog')).toBeNull();
        expect(unloadPrevented()).toBe(false);
        fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Edited again after saving' } });
        expect(unloadPrevented()).toBe(true);
    });

    it.each(['report', 'template'] as const)('resets a discarded existing %s after query-only history and isolates its pending save', async (kind) => {
        const pending = Promise.withResolvers<ReportDefinition | DocumentTemplate>();
        mocks.updateReport.mockReturnValue(pending.promise);
        mocks.updateTemplate.mockReturnValue(pending.promise);
        const path = kind === 'report' ? '/insights/reports/42/edit' : '/library/documents/17';
        window.history.replaceState({ __NA: true }, '', `${path}?mode=first`);
        mount(kind, true);
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Submitted' } });
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        mocks.replace.mockImplementationOnce((href: string) => window.history.replaceState({ __NA: true }, '', href));
        window.history.replaceState(null, '', `${path}?mode=second`);
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: null })); });
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        expect(window.location.search).toBe('?mode=second');
        const fresh = await screen.findByLabelText('Name');
        expect(fresh).not.toBe(input);
        expect(fresh).toHaveProperty('value', kind === 'report' ? report.name : template.name);
        expect(unloadPrevented()).toBe(false);
        await act(async () => pending.resolve(kind === 'report' ? report : template));
        expect(mocks.push).not.toHaveBeenCalled();
        expect(mocks.refresh).toHaveBeenCalledOnce();
        expect(unloadPrevented()).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? 'Cancel' : 'Documents' }));
        expect(screen.queryByRole('dialog')).toBeNull();
        fireEvent.change(fresh, { target: { value: 'New unsaved content' } });
        expect(unloadPrevented()).toBe(true);
    });

    it.each(['report', 'template'] as const)('does not apply a %s save baseline to another workspace', async (kind) => {
        const owner = {};
        bindRenderedWorkspace(owner, 1);
        try {
            const pending = Promise.withResolvers<ReportDefinition | DocumentTemplate>();
            mocks.updateReport.mockReturnValue(pending.promise);
            mocks.updateTemplate.mockReturnValue(pending.promise);
            mount(kind, true);
            fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Submitted' } });
            fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
            bindRenderedWorkspace(owner, 2);
            await act(async () => pending.resolve(kind === 'report' ? report : template));
            expect(mocks.push).not.toHaveBeenCalled();
            expect(unloadPrevented()).toBe(true);
        } finally {
            releaseRenderedWorkspace(owner);
        }
    });

    it.each(['report', 'template'] as const)('does not apply a %s save baseline after the draft identity changes', async (kind) => {
        const pending = Promise.withResolvers<ReportDefinition | DocumentTemplate>();
        mocks.updateReport.mockReturnValue(pending.promise);
        mocks.updateTemplate.mockReturnValue(pending.promise);
        const view = mount(kind, true);
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Submitted' } });
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        view.rerender(<NextIntlClientProvider locale="en" messages={{ ...common, ...documents, ...reports }} timeZone="UTC">
            <Link href="/records/deals">Deals</Link>
            {kind === 'report'
                ? <ReportBuilderBoard initialReport={{ ...report, id: 43 }} pipelines={[]} owners={[]} ownersFailed={false} tags={[]} canReadGoals />
                : <TemplateBuilder template={{ ...template, id: 18 }} />}
        </NextIntlClientProvider>);
        expect(input).toHaveProperty('isConnected', true);
        await act(async () => pending.resolve(kind === 'report' ? report : template));
        expect(mocks.push).not.toHaveBeenCalled();
        expect(unloadPrevented()).toBe(true);
    });

    it.each(['report', 'template'] as const)('keeps a pending %s save guarded and does not redirect after a confirmed departure', async (kind) => {
        let finish: (value: ReportDefinition | DocumentTemplate) => void = () => { throw new Error('Save has not started'); };
        const pending = new Promise<ReportDefinition | DocumentTemplate>((resolve) => { finish = resolve; });
        mocks.updateReport.mockReturnValue(pending);
        mocks.updateTemplate.mockReturnValue(pending);
        mount(kind, true);
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Submitted' } });
        fireEvent.click(screen.getByRole('button', { name: kind === 'report' ? reports.Reports.builder.save : documents.DocumentTemplateBuilder.save }));
        expect(input.matches(':disabled')).toBe(true);
        expect(unloadPrevented()).toBe(true);
        fireEvent.click(screen.getByRole('link', { name: 'Deals' }));
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        await act(async () => finish(kind === 'report' ? report : template));
        expect(mocks.push).toHaveBeenCalledExactlyOnceWith('/records/deals');
        expect(unloadPrevented()).toBe(false);
        fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Edited after the response' } });
        expect(unloadPrevented()).toBe(true);
    });

    it('protects a sidebar link and does not reuse a cancelled destination', async () => {
        mount('report');
        fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Unsaved' } });
        expect(fireEvent.click(screen.getByRole('link', { name: 'Deals' }))).toBe(false);
        expect(mocks.push).not.toHaveBeenCalled();
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.keepEditing }));
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        expect(mocks.push).toHaveBeenCalledExactlyOnceWith('/insights/reports');
    });

    it.each(['en', 'ja'] as const)('uses the shared bilingual discard choices in %s', async (locale) => {
        mount('report', false, locale);
        const catalogue = locale === 'en' ? common : jaCommon;
        fireEvent.change(screen.getByLabelText(locale === 'en' ? reports.Reports.builder.name : jaReports.Reports.builder.name), { target: { value: 'Draft' } });
        fireEvent.click(screen.getByRole('button', { name: locale === 'en' ? reports.Reports.common.cancel : jaReports.Reports.common.cancel }));
        expect(await screen.findByRole('dialog', { name: catalogue.ConfirmDiscard.title })).toBeTruthy();
        fireEvent.click(screen.getByRole('button', { name: catalogue.ConfirmDiscard.keepEditing }));
        expect(unloadPrevented()).toBe(true);
    });

    it('protects shell imperative navigation and unregisters on unmount', async () => {
        mount('report');
        fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Unsaved' } });
        const navigate = vi.fn();
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.keepEditing }));
        act(() => requestGuardedNavigation(navigate));
        expect(navigate).not.toHaveBeenCalled();
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        expect(navigate).toHaveBeenCalledOnce();
        cleanup();
        requestGuardedNavigation(navigate);
        expect(navigate).toHaveBeenCalledTimes(2);
    });

});
