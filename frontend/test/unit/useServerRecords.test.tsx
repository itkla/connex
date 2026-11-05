/** @vitest-environment jsdom */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useServerRecords } from '@/app/hooks/useServerRecords';
import type { Page, PageParams } from '@/app/lib/types';

const workspace = vi.hoisted(() => ({ activeWorkspaceId: 7 }));

vi.mock('next/navigation', () => ({
    usePathname: () => '/records/contacts',
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => workspace }));

type Record = { id: number; name: string };
const firstPage: Page<Record> = { items: [{ id: 1, name: 'First contact' }], total: 60 };

function deferredPage() {
    let resolve: (page: Page<Record>) => void = () => { throw new Error('Promise not initialized'); };
    let reject: (error: Error) => void = () => { throw new Error('Promise not initialized'); };
    const promise = new Promise<Page<Record>>((onResolve, onReject) => {
        resolve = onResolve;
        reject = onReject;
    });
    return { promise, resolve, reject };
}

beforeEach(() => { workspace.activeWorkspaceId = 7; });
afterEach(cleanup);

describe('useServerRecords failures and recovery', () => {
    it('distinguishes a rejected initial fetch from loading and retries the same request', async () => {
        const failure = new Error('Unavailable');
        const retry = deferredPage();
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockRejectedValueOnce(failure)
            .mockReturnValueOnce(retry.promise);
        const { result } = renderHook(() => useServerRecords(fetcher));

        expect(result.current.loading).toBe(true);
        expect(result.current.error).toBeNull();
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBe(failure);
        expect(result.current.items).toEqual([]);

        act(() => result.current.reload());
        expect(result.current.loading).toBe(true);
        expect(result.current.error).toBe(failure);
        expect(fetcher.mock.calls[1]).toEqual(fetcher.mock.calls[0]);
        await act(async () => retry.resolve(firstPage));

        expect(result.current.loading).toBe(false);
        expect(result.current.error).toBeNull();
        expect(result.current.items).toEqual(firstPage.items);
        expect(result.current.total).toBe(60);
    });

    it('keeps the loaded page and total during a pending request, a failure and its retry', async () => {
        const next = deferredPage();
        const failure = new Error('Page unavailable');
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockResolvedValueOnce(firstPage)
            .mockReturnValueOnce(next.promise)
            .mockResolvedValueOnce({ items: [{ id: 2, name: 'Next contact' }], total: 60 });
        const { result } = renderHook(() => useServerRecords(fetcher));
        await waitFor(() => expect(result.current.loading).toBe(false));

        act(() => result.current.setPage(2));
        expect(result.current.loading).toBe(true);
        expect(result.current.items).toEqual(firstPage.items);
        expect(result.current.total).toBe(60);
        await act(async () => next.reject(failure));

        expect(result.current.loading).toBe(false);
        expect(result.current.items).toEqual(firstPage.items);
        expect(result.current.total).toBe(60);
        expect(result.current.error).toBe(failure);
        await act(async () => result.current.reload());

        expect(fetcher.mock.calls[2][0].page).toBe(2);
        expect(result.current.items[0]?.id).toBe(2);
        expect(result.current.error).toBeNull();
    });

    it('clears a failure on a successful genuinely empty retry', async () => {
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockRejectedValueOnce(new Error('Unavailable'))
            .mockResolvedValueOnce({ items: [], total: 0 });
        const { result } = renderHook(() => useServerRecords(fetcher));
        await waitFor(() => expect(result.current.error).toBeInstanceOf(Error));
        await act(async () => result.current.reload());

        expect(result.current.error).toBeNull();
        expect(result.current.loading).toBe(false);
        expect(result.current.items).toEqual([]);
        expect(result.current.total).toBe(0);
    });

    it('keeps even a null rejection distinguishable from a successful empty list', async () => {
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>().mockRejectedValue(null);
        const { result } = renderHook(() => useServerRecords(fetcher));
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBeInstanceOf(Error);
    });

    it('ignores a late failure from a request superseded by a successful retry', async () => {
        const older = deferredPage();
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockReturnValueOnce(older.promise)
            .mockResolvedValueOnce(firstPage);
        const { result } = renderHook(() => useServerRecords(fetcher));
        await act(async () => result.current.reload());
        await act(async () => older.reject(new Error('Old failure')));

        expect(result.current.error).toBeNull();
        expect(result.current.items).toEqual(firstPage.items);
        expect(result.current.loading).toBe(false);
    });

    it('ignores a late success after a newer request has failed', async () => {
        const older = deferredPage();
        const failure = new Error('Current failure');
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockReturnValueOnce(older.promise)
            .mockRejectedValueOnce(failure);
        const { result } = renderHook(() => useServerRecords(fetcher));
        await act(async () => result.current.reload());
        await act(async () => older.resolve(firstPage));

        expect(result.current.error).toBe(failure);
        expect(result.current.items).toEqual([]);
        expect(result.current.loading).toBe(false);
    });

    it('discards the previous workspace rows before loading and failing in another workspace', async () => {
        const next = deferredPage();
        const fetcher = vi.fn<(params: PageParams) => Promise<Page<Record>>>()
            .mockResolvedValueOnce(firstPage)
            .mockReturnValueOnce(next.promise);
        const { result, rerender } = renderHook(() => useServerRecords(fetcher));
        await waitFor(() => expect(result.current.items).toEqual(firstPage.items));

        workspace.activeWorkspaceId = 8;
        rerender();
        expect(result.current.items).toEqual([]);
        expect(result.current.total).toBe(0);
        expect(result.current.loading).toBe(true);
        expect(fetcher).toHaveBeenCalledTimes(2);
        await act(async () => next.reject(new Error('New workspace unavailable')));

        expect(result.current.items).toEqual([]);
        expect(result.current.error).toBeInstanceOf(Error);
        expect(result.current.loading).toBe(false);
    });
});
