import { act, createElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/app/lib/api';
import { useRecordEditConflict } from '@/app/hooks/useRecordEditConflict';
import { installInteractiveDocument } from '@/test/unit/helpers/interactiveDocument';

type Record = { id: number; version: number; value: number };
const base: Record = { id: 7, version: 1, value: 100 };
const current: Record = { id: 7, version: 2, value: 99999 };

afterEach(() => vi.unstubAllGlobals());

async function mount(load: (id: number, init: RequestInit) => Promise<Record>) {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    let hook: ReturnType<typeof useRecordEditConflict<Record>> | undefined;
    function Harness() {
        hook = useRecordEditConflict(load);
        return null;
    }
    await act(async () => root.render(createElement(Harness)));
    return {
        read() {
            if (!hook) throw new Error('Expected the conflict hook to mount');
            return hook;
        },
        close: async () => { await act(async () => root.unmount()); },
    };
}

describe('record edit conflict loading', () => {
    it('fetches in the captured workspace and keeps the original snapshot on keep editing', async () => {
        const load = vi.fn(async () => current);
        const mounted = await mount(load);
        const init = { headers: { 'X-Workspace-Id': '17' } };
        try {
            await act(async () => {
                expect(await mounted.read().captureConflict(new ApiError('Changed', 409, 'STALE_RECORD'), base, init)).toBe(true);
            });
            expect(load).toHaveBeenCalledWith(base.id, init);
            expect(mounted.read().conflict).toEqual({ base, current });
            await act(async () => mounted.read().clearConflict());
            expect(mounted.read().conflict).toBeNull();
            expect(base).toEqual({ id: 7, version: 1, value: 100 });
            expect(load).toHaveBeenCalledTimes(1);
        } finally {
            await mounted.close();
        }
    });

    it('keeps a reload failure distinct from other 409s without losing the base', async () => {
        const load = vi.fn(async (): Promise<Record> => { throw new TypeError('Offline'); });
        const mounted = await mount(load);
        try {
            expect(await mounted.read().captureConflict(new ApiError('Line items', 409), base, {})).toBe(false);
            expect(load).not.toHaveBeenCalled();
            await act(async () => {
                await mounted.read().captureConflict(new ApiError('Changed', 409, 'STALE_RECORD'), base, {});
            });
            expect(mounted.read().conflict).toEqual({ base, current: null });
        } finally {
            await mounted.close();
        }
    });

    it('does not reopen an old conflict after the form closes during the current-record fetch', async () => {
        let resolveCurrent: ((record: Record) => void) | undefined;
        const load = () => new Promise<Record>((resolve) => { resolveCurrent = resolve; });
        const mounted = await mount(load);
        try {
            await act(async () => {
                const pending = mounted.read().captureConflict(new ApiError('Changed', 409, 'STALE_RECORD'), base, {});
                mounted.read().clearConflict();
                if (!resolveCurrent) throw new Error('Expected a pending current-record fetch');
                resolveCurrent(current);
                await pending;
            });
            expect(mounted.read().conflict).toBeNull();
        } finally {
            await mounted.close();
        }
    });
});
