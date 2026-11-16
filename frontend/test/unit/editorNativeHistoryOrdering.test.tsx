/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useCallback, useLayoutEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { NextIntlClientProvider } from 'next-intl';
import { afterAll, beforeAll, expect, it, vi } from 'vitest';

import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import common from '@/messages/en/common.json';

type Listener = { callback: EventListenerOrEventListenerObject; capture: boolean };
const listeners: Listener[] = [];
const nextRestore = vi.fn();
const nextState = { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: ['native-order'] };

/** Models Chromium's native Window dispatch, which visits target listeners in registration order. */
function nativePopState() {
    const event = new PopStateEvent('popstate', { state: window.history.state });
    Object.defineProperties(event, {
        eventPhase: { value: Event.AT_TARGET },
        target: { value: window },
        currentTarget: { value: window },
    });
    let stopped = false;
    event.stopImmediatePropagation = () => { stopped = true; };
    for (const listener of [...listeners]) {
        if (stopped) break;
        if (!listeners.includes(listener)) continue;
        if (typeof listener.callback === 'function') listener.callback.call(window, event);
        else listener.callback.handleEvent(event);
    }
}

function Editor({ href, push, replace }: { href: string; push: (href: string) => void; replace: (href: string) => void }) {
    const [draft, setDraft] = useState('');
    const [revision, setRevision] = useState(0);
    const guard = useUnsavedChangesGuard({
        isDirty: draft.length > 0,
        onClose: () => push('/list'),
        navigation: { push, replace, renderedHref: href, onDiscard: () => { setDraft(''); setRevision((value) => value + 1); } },
    });
    return <>
        <input key={revision} aria-label="Draft" value={draft} onChange={(event) => setDraft(event.target.value)} />
        <ConfirmDiscardDialog {...guard.confirm} />
    </>;
}

function NextFirstRouter({ source, sourceState }: { source: string; sourceState: typeof nextState | null }) {
    const [href, setHref] = useState('/list');
    const push = useCallback((destination: string) => {
        window.history.pushState(sourceState, '', destination);
        setHref(destination);
    }, [sourceState]);
    const replace = useCallback((destination: string) => {
        window.history.replaceState(nextState, '', destination);
        setHref(destination);
    }, []);
    useLayoutEffect(() => {
        const onPopState = (event: PopStateEvent) => {
            const state: unknown = event.state;
            if (!state || typeof state !== 'object' || !('__NA' in state) || state.__NA !== true) return;
            nextRestore();
            flushSync(() => setHref(window.location.pathname + window.location.search + window.location.hash));
        };
        window.addEventListener('popstate', onPopState);
        return () => window.removeEventListener('popstate', onPopState);
    }, []);
    const route = new URL(href, window.location.origin);
    return route.pathname === '/editor'
        ? <Editor href={route.pathname + route.search} push={push} replace={replace} />
        : <><h1>Destination page</h1><button onClick={() => push(source)}>Open editor</button></>;
}

beforeAll(async () => {
    const add = window.addEventListener.bind(window);
    const remove = window.removeEventListener.bind(window);
    vi.spyOn(window, 'addEventListener').mockImplementation((type, callback, options) => {
        if (type !== 'popstate') return add(type, callback, options);
        const capture = typeof options === 'boolean' ? options : options?.capture ?? false;
        if (!listeners.some((item) => item.callback === callback && item.capture === capture)) listeners.push({ callback, capture });
    });
    vi.spyOn(window, 'removeEventListener').mockImplementation((type, callback, options) => {
        if (type !== 'popstate') return remove(type, callback, options);
        const capture = typeof options === 'boolean' ? options : options?.capture ?? false;
        const index = listeners.findIndex((item) => item.callback === callback && item.capture === capture);
        if (index >= 0) listeners.splice(index, 1);
    });
    await import('@/instrumentation-client');
});
afterAll(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('protects native Back, Forward and fragment jumps before an earlier Next listener can unmount the editor', async () => {
    const cases = [
        { source: '/editor', destination: '/list', native: false, destinationIndex: 0, sourceState: nextState },
        { source: '/editor', destination: '/list', native: true, destinationIndex: 0, sourceState: nextState },
        { source: '/editor#app-main', destination: '/list', native: false, destinationIndex: 0, sourceState: null },
        { source: '/editor', destination: '/records/deals?view=table&q=History+destination', native: true, destinationIndex: 2, sourceState: nextState },
    ];
    for (const scenario of cases) {
        nextRestore.mockClear();
        window.history.replaceState(nextState, '', '/list');
        vi.stubGlobal('navigation', scenario.native ? {
            get currentEntry() {
                return window.location.pathname === '/editor'
                    ? { key: 'editor', index: 1 }
                    : { key: 'destination', index: scenario.destinationIndex };
            },
        } : undefined);
        const go = vi.spyOn(window.history, 'go').mockImplementation((delta) => {
            const restoring = delta === 1 - scenario.destinationIndex;
            window.history.replaceState(restoring ? scenario.sourceState : nextState, '', restoring ? scenario.source : scenario.destination);
            queueMicrotask(nativePopState);
        });
        const view = render(<NextIntlClientProvider locale="en" messages={common} timeZone="UTC">
            <NextFirstRouter source={scenario.source} sourceState={scenario.sourceState} />
        </NextIntlClientProvider>);
        fireEvent.click(screen.getByRole('button', { name: 'Open editor' }));
        fireEvent.change(screen.getByRole('textbox', { name: 'Draft' }), { target: { value: 'Dirty editor' } });
        await act(async () => {
            window.history.replaceState(nextState, '', scenario.destination);
            nativePopState();
        });
        expect(nextRestore).not.toHaveBeenCalled();
        await screen.findByRole('dialog', { name: 'Discard unsaved changes?' });
        expect(window.location.pathname + window.location.search + window.location.hash).toBe(scenario.source);
        fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
        expect(screen.getByRole('textbox', { name: 'Draft' })).toHaveProperty('value', 'Dirty editor');
        const leave = new Event('beforeunload', { cancelable: true });
        window.dispatchEvent(leave);
        expect(leave.defaultPrevented).toBe(true);
        await act(async () => {
            window.history.replaceState(nextState, '', scenario.destination);
            nativePopState();
        });
        const discard = await screen.findByRole('button', { name: 'Discard changes' });
        await act(async () => { fireEvent.click(discard); });
        await screen.findByRole('heading', { name: 'Destination page' });
        await waitFor(() => expect(window.location.pathname + window.location.search).toBe(scenario.destination));
        expect(screen.queryByRole('textbox', { name: 'Draft' })).toBeNull();
        expect(nextRestore).not.toHaveBeenCalled();
        view.unmount();
        go.mockRestore();
    }
});
