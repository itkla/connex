/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import Link from 'next/link';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TemplateBuilder from '@/app/components/library/documents/TemplateBuilder';
import ReportBuilderBoard from '@/app/components/reports/ReportBuilderBoard';
import { blankReportConfig } from '@/app/components/reports/reportConfig';
import { requestGuardedNavigation, trackEditorNavigationHistory, useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { useWorkspace, WorkspaceProvider } from '@/app/hooks/useWorkspace';
import { renderedWorkspaceId } from '@/app/lib/renderedWorkspace';
import type { DocumentTemplate, ReportDefinition, Workspace } from '@/app/lib/types';
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
vi.mock('next/navigation', () => ({ useRouter: () => mocks }));
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
        guard: useUnsavedChangesGuard({ isDirty: true, onClose: vi.fn(), navigation: { push: mocks.push } }),
    }), { wrapper: ({ children }) => <WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={1}>{children}</WorkspaceProvider> });
}

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: (media: string) => ({
        media, matches: false, addEventListener() {}, removeEventListener() {},
    }) });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function mount(kind: 'report' | 'template', existing = false, locale: 'en' | 'ja' = 'en') {
    const messages = locale === 'en' ? { ...common, ...documents, ...reports } : { ...jaCommon, ...jaDocuments, ...jaReports };
    render(<NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
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
        const { result, rerender } = renderHook(({ onClose }) => useUnsavedChangesGuard({ isDirty: true, onClose, navigation: { push: mocks.push } }), {
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

    it.each(['previous-entry', 'next-entry'])('cancels browser traversal to %s without changing history, then replays only on discard', async (key) => {
        const navigation = new EventTarget();
        const traverseTo = vi.fn(() => ({ committed: Promise.resolve(), finished: Promise.resolve() }));
        Object.assign(navigation, { traverseTo });
        vi.stubGlobal('navigation', navigation);
        const historyLength = window.history.length;
        mount('report');
        const input = screen.getByLabelText('Name');
        fireEvent.change(input, { target: { value: 'Unsaved report' } });
        input.focus();
        const traverse = () => {
            const event = new Event('navigate', { cancelable: true });
            Object.defineProperties(event, {
                navigationType: { value: 'traverse' },
                destination: { value: { key, sameDocument: true, url: new URL('/insights/reports', window.location.href).href } },
            });
            act(() => { navigation.dispatchEvent(event); });
            expect(event.defaultPrevented).toBe(true);
        };
        traverse();
        expect(window.history.length).toBe(historyLength);
        expect(traverseTo).not.toHaveBeenCalled();
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.keepEditing }));
        await waitFor(() => expect(document.activeElement).toBe(input));
        expect(input).toHaveProperty('value', 'Unsaved report');
        traverse();
        fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
        expect(traverseTo).toHaveBeenCalledExactlyOnceWith(key);
    });

    it.each([-1, 1])('restores a legacy browser traversal by %s before asking, preserving Next history and focus', async (delta) => {
        vi.stubGlobal('navigation', undefined);
        const nativeReplace = window.history.replaceState.bind(window.history);
        const nextState = { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: ['app-tree'] };
        nativeReplace(nextState, '', '/insights/reports');
        const stopTracking = trackEditorNavigationHistory();
        try {
            expect(trackEditorNavigationHistory()).toBeUndefined();
            const listState: unknown = window.history.state;
            window.history.pushState(nextState, '', '/insights/reports/new');
            const editorState: unknown = window.history.state;
            window.history.pushState(nextState, '', '/records/deals');
            const dealsState: unknown = window.history.state;
            nativeReplace(editorState, '', '/insights/reports/new');
            window.dispatchEvent(new PopStateEvent('popstate', { state: editorState }));
            const historyLength = window.history.length;
            const go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
            mount('report');
            const input = screen.getByLabelText('Name');
            fireEvent.change(input, { target: { value: 'Legacy browser draft' } });
            input.focus();
            const attempt = () => {
                const state = delta === -1 ? listState : dealsState;
                nativeReplace(state, '', delta === -1 ? '/insights/reports' : '/records/deals');
                act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state })); });
                expect(go).toHaveBeenLastCalledWith(-delta);
                expect(screen.queryByRole('dialog')).toBeNull();
                act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state })); });
                expect(screen.queryByRole('dialog')).toBeNull();
                nativeReplace(editorState, '', '/insights/reports/new');
                act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: editorState })); });
            };
            attempt();
            fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.keepEditing }));
            await waitFor(() => expect(document.activeElement).toBe(input));
            expect(input).toHaveProperty('value', 'Legacy browser draft');
            expect(window.history.state).toMatchObject(nextState);
            expect(window.history.length).toBe(historyLength);
            attempt();
            fireEvent.click(await screen.findByRole('button', { name: common.ConfirmDiscard.discard }));
            expect(go).toHaveBeenLastCalledWith(delta);
            go.mockRestore();
        } finally {
            stopTracking?.();
            nativeReplace({}, '', '/');
        }
    });

    it('lets an unknown multi-entry legacy traversal reach the router without guessing a restoration', () => {
        vi.stubGlobal('navigation', undefined);
        const nativePush = window.history.pushState.bind(window.history);
        const nativeReplace = window.history.replaceState.bind(window.history);
        nativeReplace({}, '', '/docs/first');
        nativePush({}, '', '/docs/second');
        nativePush({ __NA: true }, '', '/insights/reports/new');
        const stopTracking = trackEditorNavigationHistory();
        const routerPop = vi.fn();
        window.addEventListener('popstate', routerPop);
        const go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
        try {
            mount('report');
            fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Unindexed departure' } });
            nativeReplace({}, '', '/docs/first');
            act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: {} })); });
            expect(go).not.toHaveBeenCalled();
            expect(routerPop).toHaveBeenCalledOnce();
            expect(screen.queryByRole('dialog')).toBeNull();
            expect(window.location.pathname).toBe('/docs/first');
            window.history.pushState({}, '', '/docs/third');
            expect(window.history.state).not.toHaveProperty('__connexEditorHistoryIndex');
        } finally {
            go.mockRestore();
            window.removeEventListener('popstate', routerPop);
            stopTracking?.();
            nativeReplace({}, '', '/');
        }
    });

    it('keeps native skip-link history synchronized after list, dirty editor, fragment, then go(-2)', async () => {
        vi.stubGlobal('navigation', undefined);
        const nativeReplace = window.history.replaceState.bind(window.history);
        nativeReplace({ __NA: true }, '', '/insights/reports');
        const stopTracking = trackEditorNavigationHistory();
        window.history.pushState({ __NA: true }, '', '/insights/reports/new');

        function HistoryRoute() {
            const [path, setPath] = useState(window.location.pathname);
            useEffect(() => {
                const onPop = () => setPath(window.location.pathname);
                window.addEventListener('popstate', onPop);
                return () => window.removeEventListener('popstate', onPop);
            }, []);
            return <NextIntlClientProvider locale="en" messages={{ ...common, ...reports }} timeZone="UTC">
                {path === '/insights/reports/new' ? <>
                    <a href="#app-main">Skip to main content</a>
                    <div id="app-main">
                        <ReportBuilderBoard pipelines={[]} owners={[]} ownersFailed={false} tags={[]} canReadGoals />
                    </div>
                </> : <h1>Reports list</h1>}
            </NextIntlClientProvider>;
        }

        try {
            render(<HistoryRoute />);
            fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Dirty skip-link draft' } });
            const entries = window.history.length;
            fireEvent.click(screen.getByRole('link', { name: 'Skip to main content' }));
            await waitFor(() => expect(window.location.hash).toBe('#app-main'));
            await waitFor(() => expect(window.history.length).toBe(entries + 1));
            expect(window.history.state ?? null).toBeNull();
            expect(unloadPrevented()).toBe(true);
            window.history.go(-2);
            await waitFor(() => expect(window.location.pathname).toBe('/insights/reports'));
            await screen.findByRole('heading', { name: 'Reports list' });
            expect(screen.queryByRole('textbox', { name: 'Name' })).toBeNull();
            expect(screen.queryByRole('dialog')).toBeNull();
        } finally {
            stopTracking?.();
            nativeReplace({}, '', '/');
        }
    });
});
