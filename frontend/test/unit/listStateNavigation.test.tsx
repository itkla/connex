/** @vitest-environment jsdom */
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import {
    writeListQueryToUrl,
    writeListStateToUrl,
    writeOwnedParamsToUrl,
    writeSavedViewToUrl,
    writeWorkspaceSavedViewToUrl,
} from '@/app/hooks/listStateUrl';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useRecordPeek } from '@/app/hooks/useRecordPeek';

vi.mock('next/navigation', () => ({
    usePathname: () => '/records/deals',
    useSearchParams: () => new URLSearchParams(window.location.search),
}));
vi.mock('@/app/hooks/useActions', () => ({ useActions: () => ({ context: { user: { id: 1 } } }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1 }) }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useScopedViewPreference', () => ({ useScopedViewPreference: () => ['table', vi.fn()] }));

afterEach(cleanup);

const editorState = { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: { tree: 'report-editor' } };
const destination = '/insights/reports/new?mode=outline#app-main';
const writers: ReadonlyArray<[string, () => void]> = [
    ['query', () => writeListQueryToUrl('/records/deals', 'History destination')],
    ['server list state', () => writeListStateToUrl('/records/deals', { q: 'History destination', sort: null, dir: 'asc', page: 2, size: 25 }, 25)],
    ['owned params', () => writeOwnedParamsToUrl('/records/deals', { view: 'table' })],
    ['saved view', () => writeSavedViewToUrl('/records/deals', '1:7')],
    ['workspace saved view', () => writeWorkspaceSavedViewToUrl('/records/deals', '2:8')],
];

it.each(writers)('rejects an outgoing %s write after native Back reaches the editor', (_name, write) => {
    window.history.replaceState(editorState, '', destination);
    write();
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(destination);
    expect(window.history.state).toEqual(editorState);
});

it('canonicalizes a clean Deals visit to the selected table view without adding an entry', () => {
    window.history.replaceState(null, '', '/records/deals');
    const length = window.history.length;
    renderHook(() => useRecordsBrowser({ items: [{ id: 1 }], storageKey: 'deals:view', searchFields: () => [] }));
    expect(window.location.pathname + window.location.search).toBe('/records/deals?view=table');
    expect(window.history.length).toBe(length);
});

it('keeps the editor entry intact when outgoing view/filter effects run before the list unmounts', () => {
    window.history.replaceState(null, '', '/records/deals?q=History+destination');
    const { result } = renderHook(() => useRecordsBrowser({ items: [{ id: 1 }], storageKey: 'deals:view', searchFields: () => [] }));
    window.history.replaceState(editorState, '', destination);
    act(() => result.current.setFilterState({ status: ['open'] }));
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(destination);
    expect(window.history.state).toEqual(editorState);
});

it('ignores a late peek callback from the outgoing list', () => {
    window.history.replaceState(null, '', '/records/deals?view=table');
    const { result } = renderHook(() => useRecordPeek('deal', [41, 42]));
    window.history.replaceState(editorState, '', destination);
    act(() => result.current.open(42));
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(destination);
    expect(window.history.state).toEqual(editorState);
});
