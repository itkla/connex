import { getDateTimeFormatter } from '@/app/lib/intlFormatters';

export type TimeZoneOption = {
    value: string;
    label: string;
    searchText: string;
};

const ZONE_ALIASES: Record<string, string> = {
    'Asia/Tokyo': 'JST Japan 日本 東京',
    'Asia/Seoul': 'KST Korea 韓国 ソウル',
    'Asia/Shanghai': 'CST China 中国 北京 上海',
    'Asia/Kolkata': 'IST India インド',
    'Asia/Calcutta': 'IST India インド',
    'Asia/Singapore': 'SGT Singapore シンガポール',
    'Asia/Hong_Kong': 'HKT Hong Kong 香港',
    'America/New_York': 'EST EDT ET US Eastern 米国東部 ニューヨーク',
    'America/Chicago': 'CST CDT CT US Central 米国中部 シカゴ',
    'America/Denver': 'MST MDT MT US Mountain 米国山岳部 デンバー',
    'America/Los_Angeles': 'PST PDT PT US Pacific 米国太平洋 ロサンゼルス',
    'Pacific/Honolulu': 'HST Hawaii ハワイ ホノルル',
    'Europe/London': 'GMT BST UK Britain 英国 イギリス ロンドン',
    'Europe/Paris': 'CET CEST France フランス パリ',
    'Australia/Sydney': 'AEST AEDT Australia オーストラリア シドニー',
    UTC: 'GMT coordinated universal time 協定世界時 世界標準時',
};

function zoneFormatter(zone: string, locale: string, style: 'longGeneric' | 'longOffset' | 'shortOffset'): Intl.DateTimeFormat {
    return getDateTimeFormatter(locale, { timeZone: zone, timeZoneName: style });
}

function zoneName(zone: string, locale: string, style: 'longGeneric' | 'longOffset' | 'shortOffset', date: Date): string {
    return zoneFormatter(zone, locale, style)
        .formatToParts(date).find((part) => part.type === 'timeZoneName')?.value ?? zone;
}

/** Localized zone names and current UTC offsets, searchable across English, Japanese and common aliases. */
export function timeZoneOptions(zones: readonly string[], locale: string, date = new Date()): TimeZoneOption[] {
    return zones.map((value) => {
        try {
            const name = zoneName(value, locale, 'longGeneric', date);
            const rawOffset = zoneName(value, 'en', 'longOffset', date).replace('GMT', 'UTC');
            const offset = rawOffset === 'UTC' ? 'UTC+00:00' : rawOffset;
            const label = `${name} (${offset}) — ${value.replaceAll('_', ' ')}`;
            const canonical = zoneFormatter(value, 'en', 'longGeneric').resolvedOptions().timeZone;
            const searchText = [label, value, zoneName(value, 'en', 'shortOffset', date).replace('GMT', 'UTC'),
                zoneName(value, 'en', 'longGeneric', date),
                zoneName(value, 'ja', 'longGeneric', date), ZONE_ALIASES[value], ZONE_ALIASES[canonical]]
                .filter(Boolean).join(' ').normalize('NFKC').toLocaleLowerCase(locale);
            return { value, label, searchText };
        } catch {
            return { value, label: value, searchText: value.toLocaleLowerCase(locale) };
        }
    });
}

/** Matches every query word without requiring the stored IANA identifier. */
export function matchesTimeZone(option: TimeZoneOption, query: string, locale: string): boolean {
    return query.normalize('NFKC').toLocaleLowerCase(locale).trim().split(/\s+/)
        .every((word) => option.searchText.includes(word));
}
