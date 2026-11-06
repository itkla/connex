/** @vitest-environment jsdom */
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useActivityListFilters } from '@/app/hooks/useActivityListFilters';

vi.mock('next/navigation', () => ({
    usePathname: () => window.location.pathname,
    useSearchParams: () => new URLSearchParams(window.location.search),
}));
afterEach(cleanup);

describe('activity list navigation', () => {
    it.each(['/activity/tasks', '/activity/all'])('clears record context when navigating to %s without filters', (path) => {
        window.history.replaceState(null, '', `${path}?contactId=9&companyId=7&dealId=11&members=5`);
        const { result, rerender } = renderHook(useActivityListFilters);
        expect(result.current.params.persons).toEqual([9]);
        act(() => window.history.pushState(null, '', path));
        rerender();
        expect(result.current.params).toEqual({ persons: [], companies: [], deals: [], members: [] });
        expect(result.current.hasRecordContext).toBe(false);
        expect(window.location.search).toBe('');
    });

    it('restores a different record on navigation and retains shallow edits by each owner', () => {
        window.history.replaceState(null, '', '/activity/tasks?contactId=9');
        const { result, rerender } = renderHook(useActivityListFilters);
        act(() => window.history.pushState(null, '', '/activity/tasks?contactId=20&q=proposal&queue=allOpen'));
        rerender();
        expect(result.current.params.persons).toEqual([20]);
        act(() => {
            result.current.setCompanyFilter(new Set(['7']));
            result.current.setMemberFilter(new Set(['5']));
            result.current.setPersonFilter((current) => new Set([...current, '21']));
        });
        rerender();
        expect(result.current.params).toEqual({ persons: [20, 21], companies: [7], deals: [], members: [5] });
        expect(new URLSearchParams(window.location.search).get('q')).toBe('proposal');
        expect(new URLSearchParams(window.location.search).get('queue')).toBe('allOpen');
        act(() => {
            result.current.setCompanyFilter(new Set());
            result.current.setPersonFilter(new Set());
            result.current.setMemberFilter(new Set());
        });
        rerender();
        expect(result.current.hasRecordContext).toBe(false);
        expect(new URLSearchParams(window.location.search).get('persons')).toBeNull();
    });
});
