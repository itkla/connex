import { describe, expect, it } from 'vitest';
import { activityListIds } from '@/app/hooks/useActivityListFilters';

describe('activity and task record context', () => {
    it('retains the originating contact, company and deal alongside list filters', () => {
        const params = new URLSearchParams('companyId=7&contactId=9&dealId=11&persons=10,9');
        expect([...activityListIds(params, 'companies', 'companyId')]).toEqual(['7']);
        expect([...activityListIds(params, 'persons', 'contactId')]).toEqual(['10', '9']);
        expect([...activityListIds(params, 'deals', 'dealId')]).toEqual(['11']);
    });
    it('bounds restored selections and rejects malformed identifiers', () => {
        const values = ['-1', 'NaN', '0', '1.5', ...Array.from({ length: 80 }, (_, i) => String(i + 1))];
        const ids = activityListIds(new URLSearchParams({ persons: values.join(',') }), 'persons');
        expect(ids.size).toBe(50);
        expect([...ids][0]).toBe('1');
        expect(ids.has('-1')).toBe(false);
    });
});
