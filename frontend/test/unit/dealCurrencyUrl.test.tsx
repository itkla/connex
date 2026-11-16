/** @vitest-environment jsdom */
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { writeOwnedParamsToUrl } from '@/app/hooks/listStateUrl';

vi.mock('next/navigation', () => ({
    usePathname: () => '/records/deals',
    useSearchParams: () => new URLSearchParams(window.location.search),
}));
vi.mock('@/app/hooks/useActions', () => ({ useActions: () => ({ context: { user: { id: 1 } } }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 1 }) }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useScopedViewPreference', () => ({
    useScopedViewPreference: () => ['table', vi.fn()],
}));

afterEach(cleanup);
const ownedUrlKeys = ['currency'];

describe('Deals currency URL ownership', () => {
    it('preserves currency while changing facets and never imports it as a facet', () => {
        window.history.replaceState(null, '', '/records/deals?currency=JPY&status=open');
        const { result, rerender } = renderHook(() => useRecordsBrowser({
            items: [{ id: 1 }], storageKey: 'deals:view', searchFields: () => [], ownedUrlKeys,
        }));
        expect(result.current.filterState).toEqual({ status: ['open'] });
        act(() => result.current.setFilterState({ status: ['closed'] }));
        expect(new URLSearchParams(window.location.search).get('currency')).toBe('JPY');
        act(() => writeOwnedParamsToUrl('/records/deals', { currency: 'USD', page: undefined }));
        rerender();
        expect(new URLSearchParams(window.location.search).get('currency')).toBe('USD');
        act(() => result.current.setFilterState({}));
        expect(new URLSearchParams(window.location.search).get('currency')).toBe('USD');
    });
});
