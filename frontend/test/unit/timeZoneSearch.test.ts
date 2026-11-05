import { describe, expect, it } from 'vitest';
import { matchesTimeZone, timeZoneOptions } from '@/app/lib/timeZoneSearch';

const date = new Date('2026-01-15T12:00:00Z');
const zones = ['Asia/Tokyo', 'America/Los_Angeles', 'America/New_York', 'UTC'];

describe('friendly time-zone search', () => {
    it.each(['en', 'ja'])('finds labels, offsets, IDs and aliases in %s', (locale) => {
        const options = timeZoneOptions(zones, locale, date);
        for (const query of ['Japan', 'JST', '日本', '東京', 'UTC+9', 'UTC+09:00', 'Asia/Tokyo', 'ｊｓｔ']) {
            expect(options.filter((option) => matchesTimeZone(option, query, locale)).map((option) => option.value), query)
                .toEqual(['Asia/Tokyo']);
        }
        expect(options.filter((option) => matchesTimeZone(option, 'Pacific PST', locale)).map((option) => option.value))
            .toEqual(['America/Los_Angeles']);
        expect(options[0].label).toContain('(UTC+09:00)');
        expect(options[0].label).toContain(locale === 'ja' ? '日本' : 'Japan');
        expect(options.filter((option) => matchesTimeZone(option, 'nothing matches', locale))).toEqual([]);
        expect(options.filter((option) => matchesTimeZone(option, '', locale))).toHaveLength(4);
    });

    it('keeps saved aliases searchable and handles an unavailable zone without crashing', () => {
        const options = timeZoneOptions(['US/Pacific', 'Unavailable/Zone'], 'en', date);
        expect(matchesTimeZone(options[0], 'PST', 'en')).toBe(true);
        expect(options[1]).toEqual({ value: 'Unavailable/Zone', label: 'Unavailable/Zone', searchText: 'unavailable/zone' });
    });

    it('shows the offset at the supplied instant including daylight saving time', () => {
        expect(timeZoneOptions(['America/Los_Angeles'], 'en', date)[0].label).toContain('(UTC-08:00)');
        expect(timeZoneOptions(['America/Los_Angeles'], 'en', new Date('2026-07-15T12:00:00Z'))[0].label).toContain('(UTC-07:00)');
    });
});
