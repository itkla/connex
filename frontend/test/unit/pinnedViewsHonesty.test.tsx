import { beforeEach, describe, expect, it, vi } from 'vitest';

import { PinnedViewsProvider } from '@/app/hooks/usePinnedViews';

type RuntimeState = readonly unknown[] | {
    workspaceId: number | null;
    pins: readonly unknown[];
    status: string;
};

type StateUpdate = RuntimeState | ((current: RuntimeState) => RuntimeState);
type Effect = () => void | (() => void);

type HookRuntime = {
    stateSlots: RuntimeState[];
    refSlots: { current: number }[];
    effects: Effect[];
    stateCursor: number;
    refCursor: number;
    beginRender: () => void;
    reset: () => void;
    useState: (initializer: RuntimeState | (() => RuntimeState)) => [
        RuntimeState,
        (update: StateUpdate) => void,
    ];
    useRef: (initialValue: number) => { current: number };
    useEffect: (effect: Effect) => void;
};

const { hookRuntime, activeWorkspace, getSavedViewPins } = vi.hoisted(() => {
    const runtime: HookRuntime = {
        stateSlots: [],
        refSlots: [],
        effects: [],
        stateCursor: 0,
        refCursor: 0,
        beginRender() {
            runtime.stateCursor = 0;
            runtime.refCursor = 0;
            runtime.effects = [];
        },
        reset() {
            runtime.stateSlots = [];
            runtime.refSlots = [];
            runtime.beginRender();
        },
        useState(initializer) {
            const index = runtime.stateCursor++;
            if (runtime.stateSlots[index] === undefined) {
                runtime.stateSlots[index] = typeof initializer === 'function'
                    ? initializer()
                    : initializer;
            }
            return [
                runtime.stateSlots[index],
                (update) => {
                    const current = runtime.stateSlots[index];
                    runtime.stateSlots[index] = typeof update === 'function'
                        ? update(current)
                        : update;
                },
            ];
        },
        useRef(initialValue) {
            const index = runtime.refCursor++;
            runtime.refSlots[index] ??= { current: initialValue };
            return runtime.refSlots[index];
        },
        useEffect(effect) {
            runtime.effects.push(effect);
        },
    };

    return {
        hookRuntime: runtime,
        activeWorkspace: { current: 7 },
        getSavedViewPins: vi.fn<() => Promise<unknown[]>>(),
    };
});

vi.mock('react', () => ({
    createContext: () => ({ Provider: 'PinnedViewsContextProvider' }),
    useCallback: (callback: () => unknown) => callback,
    useContext: () => null,
    useEffect: hookRuntime.useEffect,
    useMemo: (factory: () => unknown) => factory(),
    useRef: hookRuntime.useRef,
    useState: hookRuntime.useState,
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

type PinnedViewsSnapshot = {
    pins: readonly unknown[];
    status: 'loading' | 'ready' | 'unavailable';
    reload: () => Promise<void>;
};

function snapshotFrom(rendered: unknown): PinnedViewsSnapshot {
    if (typeof rendered !== 'object' || rendered === null || !('props' in rendered)) {
        throw new Error('PinnedViewsProvider did not return a provider element');
    }
    const props = rendered.props;
    if (typeof props !== 'object' || props === null || !('value' in props)) {
        throw new Error('PinnedViewsProvider did not expose a context value');
    }
    const value = props.value;
    if (
        typeof value !== 'object'
        || value === null
        || !('pins' in value)
        || !Array.isArray(value.pins)
        || !('status' in value)
        || (value.status !== 'loading' && value.status !== 'ready' && value.status !== 'unavailable')
        || !('reload' in value)
        || typeof value.reload !== 'function'
    ) {
        throw new Error('PinnedViewsProvider exposed an invalid context value');
    }
    const reload = value.reload;
    return {
        pins: value.pins,
        status: value.status,
        reload: async () => {
            const result: unknown = reload();
            if (!(result instanceof Promise)) {
                throw new Error('PinnedViewsProvider reload did not return a promise');
            }
            await result;
        },
    };
}

function renderProvider(): PinnedViewsSnapshot {
    hookRuntime.beginRender();
    return snapshotFrom(PinnedViewsProvider({ children: null }));
}

function startLoadEffect(): void | (() => void) {
    const effect = hookRuntime.effects[0];
    if (!effect) throw new Error('PinnedViewsProvider did not register its load effect');
    return effect();
}

async function flushPromises(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
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
    hookRuntime.reset();
    activeWorkspace.current = 7;
    getSavedViewPins.mockReset();
});

describe('pinned-view load honesty', () => {
    it('surfaces a failed load and lets retry replace it with a ready result', async () => {
        getSavedViewPins.mockRejectedValueOnce(new Error('backend unavailable'));

        let snapshot = renderProvider();
        expect(snapshot.status).toBe('loading');
        startLoadEffect();
        await flushPromises();

        snapshot = renderProvider();
        expect(snapshot.status).toBe('unavailable');
        expect(snapshot.pins).toEqual([]);

        getSavedViewPins.mockResolvedValueOnce([pin(12, 7)]);
        await snapshot.reload();

        snapshot = renderProvider();
        expect(snapshot.status).toBe('ready');
        expect(snapshot.pins).toEqual([pin(12, 7)]);
    });

    it('ignores a late response from the previous workspace', async () => {
        const previousWorkspace = deferred<unknown[]>();
        const activeWorkspaceRead = deferred<unknown[]>();
        getSavedViewPins
            .mockReturnValueOnce(previousWorkspace.promise)
            .mockReturnValueOnce(activeWorkspaceRead.promise);

        renderProvider();
        const cancelPreviousLoad = startLoadEffect();

        activeWorkspace.current = 8;
        cancelPreviousLoad?.();
        let snapshot = renderProvider();
        expect(snapshot.status).toBe('loading');
        expect(snapshot.pins).toEqual([]);
        startLoadEffect();

        previousWorkspace.resolve([pin(21, 7)]);
        await flushPromises();
        snapshot = renderProvider();
        expect(snapshot.status).toBe('loading');
        expect(snapshot.pins).toEqual([]);

        activeWorkspaceRead.resolve([pin(22, 8)]);
        await flushPromises();
        snapshot = renderProvider();
        expect(snapshot.status).toBe('ready');
        expect(snapshot.pins).toEqual([pin(22, 8)]);
    });
});
