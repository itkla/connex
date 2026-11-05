'use client';

import { useMemo } from 'react';
import { useLocale } from 'next-intl';
import { matchesTimeZone, timeZoneOptions } from '@/app/lib/timeZoneSearch';

/** Keeps persisted IANA values while sharing localized labels and alias filtering across zone pickers. */
export function useTimeZoneSearch(zones: readonly string[]) {
    const locale = useLocale();
    const options = useMemo(() => new Map(timeZoneOptions(zones, locale)
        .map((option) => [option.value, option])), [zones, locale]);
    return {
        itemToStringLabel: (value: string) => options.get(value)?.label ?? value,
        filter: (value: string, query: string) => {
            const option = options.get(value);
            return option !== undefined && matchesTimeZone(option, query, locale);
        },
    };
}
