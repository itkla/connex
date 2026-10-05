import { act, useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PinnedViewsProvider, usePinnedViews } from '@/app/hooks/usePinnedViews';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

const { activeWorkspace, getSavedViewPins } = vi.hoisted(() => ({
    activeWorkspace: { current: 7 },
    getSavedViewPins: vi.fn<() => Promise<unknown[]>>(),
}));

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspaceId: activeWorkspace.current }),
}));

vi.mock('@/app/lib/api', () => ({
    getSavedViewPins,
}));

vi.mock('@/app/lib/saved-view-events', () => ({
    subscribeToSavedViewMutations: () => () => undefined,
}));

type PinnedViewsSnapshot = ReturnType<typeof usePinnedViews>;

function PinnedViewsProbe({ onValue }: { onValue: (value: PinnedViewsSnapshot) => void }) {
    const value = usePinnedViews();
    useEffect(() => {
        onValue(value);
    });
    return null;
}

const mountedRoots: Array<{ unmount: () => void }> = [];

async function mountProvider() {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    mountedRoots.push(root);
    let latest: PinnedViewsSnapshot | null = null;
    const tree = () => (
        <PinnedViewsProvider>
            <PinnedViewsProbe onValue={(value) => { latest = value; }} />
        </PinnedViewsProvider>
    );
    await act(async () => root.render(tree()));
    return {
        current(): PinnedViewsSnapshot {
            if (latest === null) throw new Error('PinnedViewsProvider did not expose a context value');
            return latest;
        },
        async rerender(): Promise<void> {
            await act(async () => root.render(tree()));
        },
    };
}

function deferred<T>(): {
    promise: Promise<T>;
    resolve: (value: T) => void;
} {
    let resolver: ((value: T) => void) | null = null;
    const promise = new Promise<T>((resolve) => {
        resolver = resolve;
    });
    return {
        promise,
        resolve(value) {
            if (resolver === null) throw new Error('Deferred promise was not initialized');
            resolver(value);
        },
    };
}

function pin(id: number, workspaceId: number): { id: number; workspaceId: number } {
    return { id, workspaceId };
}

beforeEach(() => {
    activeWorkspace.current = 7;
    getSavedViewPins.mockReset();
});

afterEach(async () => {
    for (const root of mountedRoots.splice(0)) await act(async () => root.unmount());
    vi.unstubAllGlobals();
});

describe('pinned-view load honesty', () => {
    it('surfaces a failed load and lets retry replace it with a ready result', async () => {
        const failedRead = deferred<unknown[]>();
        getSavedViewPins.mockReturnValueOnce(failedRead.promise.then(() => {
            throw new Error('backend unavailable');
        }));

        const mounted = await mountProvider();
        expect(mounted.current().status).toBe('loading');
        expect(getSavedViewPins).toHaveBeenCalledTimes(1);

        await act(async () => failedRead.resolve([]));
        expect(mounted.current().status).toBe('unavailable');
        expect(mounted.current().pins).toEqual([]);

        getSavedViewPins.mockResolvedValueOnce([pin(12, 7)]);
        await act(async () => mounted.current().reload());

        expect(mounted.current().status).toBe('ready');
        expect(mounted.current().pins).toEqual([pin(12, 7)]);
    });

    it('ignores a late response from the previous workspace', async () => {
        const previousWorkspace = deferred<unknown[]>();
        const activeWorkspaceRead = deferred<unknown[]>();
        getSavedViewPins
            .mockReturnValueOnce(previousWorkspace.promise)
            .mockReturnValueOnce(activeWorkspaceRead.promise);

        const mounted = await mountProvider();
        expect(getSavedViewPins).toHaveBeenCalledTimes(1);

        activeWorkspace.current = 8;
        await mounted.rerender();
        expect(getSavedViewPins, 'a workspace switch must start its own read without being asked').toHaveBeenCalledTimes(2);
        expect(mounted.current().status).toBe('loading');
        expect(mounted.current().pins).toEqual([]);

        await act(async () => previousWorkspace.resolve([pin(21, 7)]));
        expect(mounted.current().status).toBe('loading');
        expect(mounted.current().pins).toEqual([]);

        await act(async () => activeWorkspaceRead.resolve([pin(22, 8)]));
        expect(mounted.current().status).toBe('ready');
        expect(mounted.current().pins).toEqual([pin(22, 8)]);
    });

    it('keeps the current workspace ready when the previous workspace answers last', async () => {
        const previousWorkspace = deferred<unknown[]>();
        const activeWorkspaceRead = deferred<unknown[]>();
        getSavedViewPins
            .mockReturnValueOnce(previousWorkspace.promise)
            .mockReturnValueOnce(activeWorkspaceRead.promise);

        const mounted = await mountProvider();
        activeWorkspace.current = 8;
        await mounted.rerender();
        expect(getSavedViewPins, 'a workspace switch must start its own read without being asked').toHaveBeenCalledTimes(2);

        await act(async () => activeWorkspaceRead.resolve([pin(22, 8)]));
        expect(mounted.current().status).toBe('ready');
        expect(mounted.current().pins).toEqual([pin(22, 8)]);

        await act(async () => previousWorkspace.resolve([pin(21, 7)]));
        expect(mounted.current().status).toBe('ready');
        expect(mounted.current().pins).toEqual([pin(22, 8)]);
    });
});
