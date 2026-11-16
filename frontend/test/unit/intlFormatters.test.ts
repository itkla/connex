import { describe, expect, it, vi } from 'vitest';
import { getDateTimeFormatter, getNumberFormatter, getRelativeTimeFormatter } from '@/app/lib/intlFormatters';

describe('shared Intl formatters', () => {
    it('reuses equivalent options regardless of property order', () => {
        expect(getDateTimeFormatter('en-US', { timeZone: 'UTC', dateStyle: 'medium' }))
            .toBe(getDateTimeFormatter('en-US', { dateStyle: 'medium', timeZone: 'UTC', hour: undefined }));
        expect(getNumberFormatter('en-US', { style: 'currency', currency: 'JPY' }))
            .toBe(getNumberFormatter('en-US', { currency: 'JPY', style: 'currency' }));
        expect(getRelativeTimeFormatter('ja', { numeric: 'auto', style: 'short' }))
            .toBe(getRelativeTimeFormatter('ja', { style: 'short', numeric: 'auto' }));
    });

    it('keeps profile zones, locales and options separate', () => {
        const utc = getDateTimeFormatter('en-US', { timeZone: 'UTC', dateStyle: 'medium', timeStyle: 'short' });
        const tokyo = getDateTimeFormatter('en-US', { timeZone: 'Asia/Tokyo', dateStyle: 'medium', timeStyle: 'short' });
        expect(tokyo).not.toBe(utc);
        expect(tokyo.format(Date.parse('2026-11-04T23:30:00Z'))).toBe('Nov 5, 2026, 8:30 AM');
        expect(utc.format(Date.parse('2026-11-04T23:30:00Z'))).toBe('Nov 4, 2026, 11:30 PM');
        expect(getDateTimeFormatter('ja', { timeZone: 'UTC', dateStyle: 'medium' })).not.toBe(utc);
    });

    it('preserves each zone when users or workspaces switch and reuse an options object', () => {
        const timestamp = Date.parse('2026-11-04T23:30:00Z');
        const options: Intl.DateTimeFormatOptions = { timeZone: 'Asia/Tokyo', dateStyle: 'medium', timeStyle: 'short' };
        const tokyo = getDateTimeFormatter('en-US', options);
        options.timeZone = 'America/Los_Angeles';
        const losAngeles = getDateTimeFormatter('en-US', options);
        options.timeZone = 'UTC';
        const utc = getDateTimeFormatter('en-US', options);
        const japanese = getDateTimeFormatter('ja', options);
        expect(losAngeles).not.toBe(tokyo);
        expect(utc).not.toBe(tokyo);
        expect(japanese).not.toBe(utc);
        expect(losAngeles.format(timestamp)).toBe('Nov 4, 2026, 3:30 PM');
        expect(utc.format(timestamp)).toBe('Nov 4, 2026, 11:30 PM');
        expect(japanese.resolvedOptions().locale).toBe('ja');
        expect(japanese.resolvedOptions().timeZone).toBe('UTC');
        options.timeZone = 'Asia/Tokyo';
        expect(getDateTimeFormatter('en-US', options)).toBe(tokyo);
        expect(tokyo.format(timestamp)).toBe('Nov 5, 2026, 8:30 AM');
        expect(losAngeles.format(timestamp)).toBe('Nov 4, 2026, 3:30 PM');
    });

    it('constructs one formatter for a long list of identical cells', () => {
        const NativeDateTimeFormat = Intl.DateTimeFormat;
        const constructor = vi.spyOn(Intl, 'DateTimeFormat').mockImplementation(function (locale, options) {
            return new NativeDateTimeFormat(locale, options);
        });
        try {
            for (let row = 0; row < 100; row++) {
                getDateTimeFormatter('en-GB', { timeZone: 'Asia/Tokyo', weekday: 'long', hour: '2-digit' }).format(0);
            }
            expect(constructor).toHaveBeenCalledTimes(1);
        } finally {
            constructor.mockRestore();
        }
    });

    it('bounds cached locale variants instead of retaining them forever', () => {
        const first = getDateTimeFormatter('en-x-cache0', { timeZone: 'UTC' });
        for (let variant = 1; variant <= 512; variant++) {
            getDateTimeFormatter(`en-x-cache${variant}`, { timeZone: 'UTC' });
        }
        expect(getDateTimeFormatter('en-x-cache0', { timeZone: 'UTC' })).not.toBe(first);
    });
});
