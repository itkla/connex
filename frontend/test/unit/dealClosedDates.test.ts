import { describe, expect, it } from 'vitest';

import { dealClosedAtFromInput, dealClosedAtInput, formatDealClosedAt } from '@/app/components/records/deals/dealDates';

describe('deal close dates in the reporting timezone', () => {
    it.each(['2025-02-28 15:00:00', '2025-02-28T15:00:00Z', '2025-03-01T00:00:00+09:00'])(
        'keeps imported March 1 in Tokyo for %s regardless of the host timezone', (value) => {
            const previous = process.env.TZ;
            process.env.TZ = 'Pacific/Honolulu';
            try {
                expect(formatDealClosedAt(value, 'en', 'Asia/Tokyo')).toBe('March 1, 2025');
                expect(formatDealClosedAt(value, 'ja', 'Asia/Tokyo')).toBe('2025年3月1日');
                expect(dealClosedAtInput(value, 'Asia/Tokyo')).toBe('2025-03-01T00:00');
            } finally {
                if (previous === undefined) delete process.env.TZ;
                else process.env.TZ = previous;
            }
        },
    );

    it.each(['Asia/Tokyo', 'America/Los_Angeles', 'Pacific/Kiritimati', 'UTC'])(
        'preserves a bare imported close day in %s', (zone) => {
            expect(formatDealClosedAt('2025-03-01', 'en', zone)).toBe('March 1, 2025');
            expect(dealClosedAtInput('2025-03-01', zone)).toBe('2025-03-01T00:00');
        },
    );

    it('applies the reporting calendar west of UTC too', () => {
        expect(formatDealClosedAt('2025-03-01 04:30:00', 'en', 'America/New_York')).toBe('February 28, 2025');
    });

    it('supports legacy fixed-offset reporting zones', () => {
        expect(formatDealClosedAt('2025-02-28 15:00:00', 'en', 'UTC+9')).toBe('March 1, 2025');
        expect(dealClosedAtInput('2025-02-28 15:00:00', 'UTC+9')).toBe('2025-03-01T00:00');
    });

    it.each([null, undefined, '', 'invalid'])('does not invent a date for %s', (value) => {
        expect(formatDealClosedAt(value, 'en', 'Asia/Tokyo')).toBe('—');
        expect(dealClosedAtInput(value, 'Asia/Tokyo')).toBe('');
    });

    it('preserves legacy second offsets across midnight through the shared helpers', () => {
        const zone = 'UTC+00:44:30';
        expect(formatDealClosedAt('2025-03-01 23:30:00', 'en', zone)).toBe('March 2, 2025');
        expect(dealClosedAtInput('2025-03-01 23:30:00', zone)).toBe('2025-03-02T00:14');
        expect(dealClosedAtFromInput('2025-03-02T00:14', zone)).toBe('2025-03-01 23:29:30');
        expect(dealClosedAtFromInput('2025-03-02T00:14', zone, '2025-03-01 23:30:00')).toBe('2025-03-01 23:30:00');
    });

    it('saves edited reporting wall time as UTC rather than browser local time', () => {
        expect(dealClosedAtFromInput('2025-03-02T00:00', 'Asia/Tokyo')).toBe('2025-03-01 15:00:00');
        expect(dealClosedAtFromInput('2025-03-02T00:00', 'UTC+9')).toBe('2025-03-01 15:00:00');
    });

    it.each([
        ['1960-03-02T00:00', 'Africa/Monrovia', '1960-03-02 00:44:30'],
        ['1880-03-02T00:00', 'America/New_York', '1880-03-02 04:56:02'],
    ])('preserves historical offset seconds for %s in %s', (value, timezone, expected) => {
        expect(dealClosedAtFromInput(value, timezone)).toBe(expected);
        expect(dealClosedAtInput(expected, timezone)).toBe(value);
    });

    it('preserves seconds when the displayed minute is unchanged', () => {
        expect(dealClosedAtFromInput('2025-03-01T00:00', 'Asia/Tokyo', '2025-02-28 15:00:42'))
            .toBe('2025-02-28 15:00:42');
    });

    it('rejects a missing civil time during a daylight-saving gap', () => {
        expect(dealClosedAtFromInput('2025-03-09T02:30', 'America/New_York')).toBeNull();
    });

    it('keeps the original occurrence of a repeated hour and uses the earlier occurrence for a new value', () => {
        expect(dealClosedAtFromInput('2025-11-02T01:30', 'America/New_York', '2025-11-02 06:30:00'))
            .toBe('2025-11-02 06:30:00');
        expect(dealClosedAtFromInput('2025-11-02T01:30', 'America/New_York'))
            .toBe('2025-11-02 05:30:00');
    });

    it.each(['2025-02-30T00:00', '2025-03-01', 'invalid', ''])('rejects invalid editor input %s', (value) => {
        expect(dealClosedAtFromInput(value, 'Asia/Tokyo')).toBeNull();
    });
});
