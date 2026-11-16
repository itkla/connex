import { describe, expect, it } from 'vitest';
import { ApiError } from '@/app/lib/api';
import { changedRecordFields, isStaleRecordError } from '@/app/lib/recordEditConflict';
import { changedDealFields } from '@/app/components/records/deals/dealEditConflict';
import type { Deal } from '@/app/lib/types';

const base: Deal = {
    id: 7, version: 1, name: 'Renewal', value: 100, actualValue: 0, currency: 'JPY',
    pipeline: 2, stage: 3, position: 0, company: null, createdAt: '', updatedAt: '',
};

describe('record edit conflicts', () => {
    it('distinguishes stale edits from line-item and generic conflicts', () => {
        expect(isStaleRecordError(new ApiError('Changed', 409, 'STALE_RECORD'))).toBe(true);
        expect(isStaleRecordError(new ApiError('Line items', 409))).toBe(false);
        expect(isStaleRecordError(new ApiError('Unknown', 409, 'OTHER_CONFLICT'))).toBe(false);
        expect(isStaleRecordError(new ApiError('Invalid', 400, 'STALE_RECORD'))).toBe(false);
    });

    it('names external changes against the base rather than the user draft', () => {
        const draft = { ...base, name: 'My unsaved name' };
        const current = { ...base, version: 2, value: 99999 };
        expect(changedDealFields(base, current)).toEqual(['value']);
        expect(draft.name).toBe('My unsaved name');
        expect(draft.version).toBe(1);
        expect(base.value).toBe(100);
    });

    it('includes stage, outcome, value source and owner changes from targeted writers', () => {
        expect(changedDealFields(base, { ...base, stage: 9, won: false, valueSource: 'line_items', ownerId: 4 }))
            .toEqual(['valueSource', 'stage', 'ownerId', 'won']);
    });

    it('ignores timestamp and token changes and normalizes absent nullable fields', () => {
        expect(changedDealFields(base, { ...base, version: 5, updatedAt: 'later', won: null })).toEqual([]);
        expect(changedRecordFields({ value: 3 }, { value: 4 }, ['value'])).toEqual(['value']);
    });
});
