/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { memo, useEffect, useLayoutEffect, useState, type PropsWithChildren } from 'react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ContentShell from '@/app/components/ContentShell';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { requestGuardedNavigation, useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import common from '@/messages/en/common.json';

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
const dirtyCommit = vi.hoisted(() => vi.fn<() => void>());
vi.mock('next/navigation', () => ({ usePathname: () => window.location.pathname, useRouter: () => router }));
vi.mock('@/app/components/GlobalSearch', () => ({ default: () => null }));
vi.mock('@/app/components/NavBreadcrumb', () => ({ default: () => null }));
vi.mock('@/app/components/MobileBottomBar', () => ({ default: () => null }));
vi.mock('@/app/components/account/PrivilegedMfaRouteGate', () => ({ default: ({ children }: PropsWithChildren) => children }));
vi.mock('@/app/hooks/useSidebarMode', () => ({ useSidebarMode: () => ({ mode: 'expanded', toggle: vi.fn() }) }));
vi.mock('@/app/components/ask-connex/AskConnexProvider', () => ({
    useAskConnex: () => ({ open: false, workspace: false, width: 420 }),
    useAskConnexMount: () => ({ registerDesktopRoot: vi.fn() }),
}));
vi.mock('motion/react', () => ({
    useReducedMotion: () => true,
    motion: { div: ({ children }: PropsWithChildren) => <div>{children}</div> },
}));

const nextState = { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: ['test-tree'] };

function DraftEditor({ href }: { href: string }) {
    const [revision, setRevision] = useState(0);
    return <DraftBody key={revision} href={href} onDiscard={() => setRevision((value) => value + 1)} />;
}

function DraftBody({ href, onDiscard }: { href: string; onDiscard: () => void }) {
    const [draft, setDraft] = useState('');
    const guard = useUnsavedChangesGuard({
        isDirty: draft.length > 0,
        onClose: () => router.push('/list'),
        navigation: { push: router.push, replace: router.replace, renderedHref: href, onDiscard },
    });
    useLayoutEffect(() => { if (draft) dirtyCommit(); }, [draft]);
    return <>
        <label>Draft<input value={draft} onChange={(event) => setDraft(event.target.value)} /></label>
        <button onClick={guard.requestClose}>Cancel editor</button>
        <ConfirmDiscardDialog {...guard.confirm} />
    </>;
}

const RetainedDraftEditor = memo(DraftEditor);

/** Models the installed Next router's null-state refusal; only explicit router calls can repair it. */
function NextRouterHarness() {
    const [href, setHref] = useState(window.location.href);
    useEffect(() => {
        router.push.mockImplementation((destination: string) => {
            window.history.pushState(nextState, '', destination);
            setHref(window.location.href);
        });
        router.replace.mockImplementation((destination: string) => {
            window.history.replaceState(nextState, '', destination);
            setHref(window.location.href);
        });
        const onPop = (event: PopStateEvent) => {
            const state: unknown = event.state;
            if (!state || typeof state !== 'object' || !('__NA' in state) || state.__NA !== true) return;
            setHref(window.location.href);
        };
        window.addEventListener('popstate', onPop);
        return () => window.removeEventListener('popstate', onPop);
    }, []);
    const url = new URL(href);
    return url.pathname === '/editor' ? <RetainedDraftEditor href={url.pathname + url.search} /> : <h1>Destination page</h1>;
}

function mount(path = '/editor?source=original') {
    window.history.replaceState(nextState, '', path);
    render(<NextIntlClientProvider locale="en" messages={common} timeZone="UTC">
        <ContentShell sidebar={null}><NextRouterHarness /></ContentShell>
    </NextIntlClientProvider>);
}

function dirty() {
    fireEvent.change(screen.getByRole('textbox', { name: 'Draft' }), { target: { value: 'Retained editor content' } });
}

function expectProtected() {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
}

async function discard() {
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
}

beforeEach(() => {
    dirtyCommit.mockReset();
    router.push.mockReset();
    router.replace.mockReset();
    vi.stubGlobal('navigation', undefined);
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: true, addEventListener() {}, removeEventListener() {} }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('editor navigation invariants', () => {
    it.each([
        { direction: 'Back', native: false, index: 0 },
        { direction: 'Back', native: true, index: 0 },
        { direction: 'Forward', native: false, index: 2 },
        { direction: 'Forward', native: true, index: 2 },
    ])('leaves clean $direction traversal to Next without touching history (Navigation API: $native)', ({ native, index }) => {
        vi.stubGlobal('navigation', native ? {
            get currentEntry() {
                return window.location.pathname === '/editor'
                    ? { key: 'editor-entry', index: 1 }
                    : { key: 'record-list-entry', index };
            },
        } : undefined);
        mount('/editor');
        const destination = '/records/deals?view=table&q=Saved+selection';
        const state = { ...nextState, connexRecordReturn: 'saved-selection-navigation' };
        window.history.replaceState(state, '', destination);
        const pushState = vi.spyOn(window.History.prototype, 'pushState');
        const replaceState = vi.spyOn(window.History.prototype, 'replaceState');
        const go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
        const event = new PopStateEvent('popstate', { state });
        const stop = vi.spyOn(event, 'stopImmediatePropagation');
        act(() => { window.dispatchEvent(event); });
        expect(stop).not.toHaveBeenCalled();
        expect(go).not.toHaveBeenCalled();
        expect(pushState).not.toHaveBeenCalled();
        expect(replaceState).not.toHaveBeenCalled();
        expect(router.push).not.toHaveBeenCalled();
        expect(router.replace).not.toHaveBeenCalled();
        expect(window.history.state).toEqual(state);
        expect(window.location.pathname + window.location.search).toBe(destination);
        expect(screen.getByRole('heading', { name: 'Destination page' })).toBeTruthy();
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('finishes a guarded traversal if the editor becomes clean while restoring its position', () => {
        vi.stubGlobal('navigation', {
            get currentEntry() {
                return window.location.pathname === '/editor'
                    ? { key: 'editor-entry', index: 1 }
                    : { key: 'list-entry', index: 0 };
            },
        });
        mount('/editor');
        dirty();
        const go = vi.spyOn(window.history, 'go').mockImplementation(() => {});
        window.history.replaceState(nextState, '', '/list');
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: nextState })); });
        expect(go).toHaveBeenNthCalledWith(1, 1);
        window.history.replaceState(nextState, '', '/editor');
        fireEvent.change(screen.getByRole('textbox', { name: 'Draft' }), { target: { value: '' } });
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: nextState })); });
        expect(go).toHaveBeenNthCalledWith(2, -1);
        expect(router.replace).not.toHaveBeenCalled();
        window.history.replaceState(null, '', '/list');
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: null })); });
        expect(router.replace).toHaveBeenCalledExactlyOnceWith('/list');
        expect(screen.getByRole('heading', { name: 'Destination page' })).toBeTruthy();
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it.each(['popstate', 'shell', 'beforeunload'])('protects committed edits before passive effects at the %s boundary', async (boundary) => {
        mount('/editor');
        let unload: Event | undefined;
        dirtyCommit.mockImplementationOnce(() => {
            if (boundary === 'popstate') {
                window.history.replaceState(nextState, '', '/list');
                window.dispatchEvent(new PopStateEvent('popstate', { state: nextState }));
            } else if (boundary === 'shell') {
                requestGuardedNavigation(() => router.push('/list'));
            } else {
                unload = new Event('beforeunload', { cancelable: true });
                window.dispatchEvent(unload);
            }
        });
        dirty();
        expect(window.location.pathname).toBe('/editor');
        if (boundary === 'beforeunload') {
            expect(unload?.defaultPrevented).toBe(true);
        } else {
            await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
            fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
        }
        expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', 'Retained editor content');
        expectProtected();
    });

    it.each([false, true])('preserves the known Forward destination and its filters after Keep editing (fragment: %s)', async (fragment) => {
        const destination = '/records/deals?pipeline=7&status=open';
        vi.stubGlobal('navigation', {
            get currentEntry() {
                return window.location.pathname === '/editor'
                    ? window.location.hash
                        ? { key: 'editor-fragment-entry', index: 1 }
                        : { key: 'editor-entry', index: 0 }
                    : { key: 'filtered-deals-entry', index: fragment ? 2 : 1 };
            },
        });
        mount('/editor');
        if (fragment) {
            fireEvent.click(screen.getByRole('link', { name: common.CommonContentShell.skipToContent }));
            await waitFor(() => expect(window.location.hash).toBe('#app-main'));
        }
        act(() => router.push(destination));
        window.history.go(fragment ? -2 : -1);
        await screen.findByRole('textbox', { name: 'Draft' });
        dirty();
        if (fragment) {
            router.replace.mockClear();
            window.history.forward();
            await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/editor#app-main'));
        }
        const length = window.history.length;
        window.history.forward();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
        expect(window.location.pathname).toBe('/editor');
        expect(window.location.hash).toBe(fragment ? '#app-main' : '');
        expect(window.history.length).toBe(length);
        expectProtected();
        window.history.forward();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname + window.location.search).toBe(destination);
    });

    it('keeps edits with the unknown-distance fallback and guards a fresh Forward after Keep editing', async () => {
        mount('/editor');
        act(() => router.push('/destination'));
        window.history.back();
        await screen.findByRole('textbox', { name: 'Draft' });
        dirty();
        window.history.forward();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname).toBe('/editor');
        fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
        expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', 'Retained editor content');
        expectProtected();
        fireEvent.click(screen.getByRole('button', { name: 'Cancel editor' }));
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname).toBe('/list');
        window.history.back();
        await screen.findByRole('textbox', { name: 'Draft' });
        dirty();
        window.history.forward();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname).toBe('/editor');
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname).toBe('/list');
    });

    it.each(['no-op', 'query-only', 'hash-only', 'pending-async'])('disposes discarded content after accepted %s navigation and protects fresh edits', async (kind) => {
        mount();
        dirty();
        const pending = Promise.withResolvers<void>();
        act(() => requestGuardedNavigation(() => {
            if (kind === 'query-only') router.replace('/editor?source=changed');
            if (kind === 'hash-only') router.replace('/editor?source=original#app-main');
            if (kind === 'pending-async') return pending.promise;
        }));
        await discard();
        expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', '');
        const clean = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(clean);
        expect(clean.defaultPrevented).toBe(false);
        dirty();
        expectProtected();
        await act(async () => { pending.resolve(); });
        expectProtected();
        fireEvent.click(screen.getByRole('button', { name: 'Cancel editor' }));
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname).toBe('/list');
    });

    it.each([null, {}, { __NA: true }, { __N: true }, 0, 'legacy'])('keeps URL and dirty editor synchronized for every popstate shape (%j)', async (state) => {
        mount();
        dirty();
        const source = window.location.href;
        const input = screen.getByRole('textbox', { name: 'Draft' });
        window.history.replaceState(state, '', '/list#app-main');
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state })); });
        expect(window.location.href).toBe(source);
        expect(window.history.state).toEqual(nextState);
        expect(input).toHaveProperty('isConnected', true);
        expect(input).toHaveProperty('value', 'Retained editor content');
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
        expectProtected();
        window.history.replaceState(state, '', '/list#app-main');
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state })); });
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname + window.location.hash).toBe('/list#app-main');
    });

    it('binds restoration to the rendered route even if the editor renders after the address bar moves', async () => {
        mount();
        const source = window.location.href;
        window.history.replaceState(null, '', '/list#app-main');
        dirty();
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: null })); });
        expect(window.location.href).toBe(source);
        expect(window.history.state).toEqual(nextState);
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname).toBe('/list');
    });

    it.each(['keep', 'discard'])('traverses to a native null-state fragment entry and can %s safely', async (choice) => {
        mount('/list');
        const skip = screen.getByRole('link', { name: common.CommonContentShell.skipToContent });
        fireEvent.click(skip);
        await waitFor(() => expect(window.location.hash).toBe('#app-main'));
        expect(window.history.state ?? null).toBeNull();
        act(() => router.push('/editor'));
        dirty();
        window.history.back();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname).toBe('/editor');
        if (choice === 'keep') {
            fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
            expectProtected();
            expect(window.location.pathname).toBe('/editor');
            expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', 'Retained editor content');
        } else {
            await discard();
            await screen.findByRole('heading', { name: 'Destination page' });
            expect(window.location.pathname + window.location.hash).toBe('/list#app-main');
        }
    });

    it('traverses from a native fragment entry, then explicitly routes to the accepted destination', async () => {
        mount('/list');
        act(() => router.push('/editor'));
        dirty();
        fireEvent.click(screen.getByRole('link', { name: common.CommonContentShell.skipToContent }));
        await waitFor(() => expect(window.location.hash).toBe('#app-main'));
        expectProtected();
        window.history.go(-2);
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname).toBe('/editor');
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname).toBe('/list');
    });

    it('reconciles a known traversal to a null-state fragment before disposing the discarded guard instance', async () => {
        vi.stubGlobal('navigation', {
            get currentEntry() {
                return window.location.pathname === '/editor'
                    ? { key: 'editor-entry', index: 2 }
                    : window.location.hash
                        ? { key: 'list-fragment', index: 1 }
                        : { key: 'list-entry', index: 0 };
            },
        });
        mount('/list');
        fireEvent.click(screen.getByRole('link', { name: common.CommonContentShell.skipToContent }));
        await waitFor(() => expect(window.location.hash).toBe('#app-main'));
        expect(window.history.state ?? null).toBeNull();
        act(() => router.push('/editor'));
        dirty();
        window.history.back();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname).toBe('/editor');
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
        expect(window.location.pathname + window.location.hash).toBe('/list#app-main');
    });

    it.each(['/editor?source=other', '/editor?source=original#app-main'])('keeps retained editor protection after native query/hash traversal to %s', async (destination) => {
        mount();
        dirty();
        window.history.replaceState(null, '', destination);
        act(() => { window.dispatchEvent(new PopStateEvent('popstate', { state: null })); });
        if (destination.includes('source=other')) {
            await discard();
            expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', '');
            dirty();
        }
        else expect(screen.queryByRole('dialog')).toBeNull();
        expect(window.location.pathname + window.location.search + window.location.hash).toBe(destination);
        expectProtected();
        fireEvent.click(screen.getByRole('button', { name: 'Cancel editor' }));
        await discard();
        await screen.findByRole('heading', { name: 'Destination page' });
    });
});
