import { describe, expect, it } from 'vitest';

import { apportionShares } from '@/app/lib/qualificationShares';

describe('apportionShares', () => {
    it('always totals exactly 100 where naive rounding would not', () => {
        const shares = apportionShares([1, 1, 1]);
        expect(shares.reduce((sum, share) => sum + share, 0)).toBe(100);
        expect(shares).toEqual([34, 33, 33]);
    });

    it('totals 100 across awkward splits', () => {
        for (const weights of [[1, 1, 1, 1, 1, 1, 1], [7, 11, 13], [1, 2, 3, 94], [3, 3, 3, 3, 3, 3]]) {
            expect(apportionShares(weights).reduce((sum, share) => sum + share, 0)).toBe(100);
        }
    });

    it('gives the whole dimension to a single criterion', () => {
        expect(apportionShares([42])).toEqual([100]);
    });

    it('reports zero rather than dividing by zero when nothing is weighted', () => {
        expect(apportionShares([])).toEqual([]);
        expect(apportionShares([0, 0])).toEqual([0, 0]);
    });

    it('is stable for equal remainders so shares do not swap between renders', () => {
        expect(apportionShares([1, 1, 1])).toEqual(apportionShares([1, 1, 1]));
    });

    it('keeps proportions intact when weights differ', () => {
        expect(apportionShares([30, 10])).toEqual([75, 25]);
    });
});
