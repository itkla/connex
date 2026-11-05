'use client';

import { useEffect, useMemo, useState } from 'react';

import { getCompaniesByIds, getCompaniesPage } from '@/app/lib/api';
import { createServerPickerSearch } from '@/lib/server-picker-search';
import type { Company } from '@/app/lib/types';
import type { ComboboxRootChangeEventDetails } from '@base-ui/react/combobox';

const SEARCH_SIZE = 50;
const SEARCH_DELAY_MS = 200;
const EMPTY_IDS: Array<number | null | undefined> = [];
const EMPTY_COMPANIES: Company[] = [];

type SearchResult = {
    query: string;
    selectedKey: string;
    options: Company[];
    companies: Company[];
    error: unknown;
};

function mergeCompanies(primary: Company[], secondary: Company[]): Company[] {
    const byId = new Map<number, Company>();
    for (const company of [...primary, ...secondary]) byId.set(company.id, company);
    return [...byId.values()].sort((left, right) => left.name.localeCompare(right.name));
}

/** Separates current-query matches from hydrated selections throughout debounce, requests and failures. */
export function useCompanySearch(
    enabled: boolean,
    selectedIds: Array<number | null | undefined> = EMPTY_IDS,
    seeds: Company[] = EMPTY_COMPANIES,
) {
    const [query, setQuery] = useState('');
    const [result, setResult] = useState<SearchResult | null>(null);
    const selectedKey = [...selectedIds]
        .filter((id): id is number => id != null && Number.isInteger(id) && id > 0)
        .sort((left, right) => left - right)
        .join(',');
    const stableSelectedIds = useMemo(
        () => selectedKey ? selectedKey.split(',').map(Number) : [],
        [selectedKey],
    );
    const normalizedQuery = query.normalize('NFKC').trim();
    const current = enabled && result?.query === normalizedQuery && result.selectedKey === selectedKey
        ? result : null;

    useEffect(() => {
        if (!enabled) return;
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            Promise.all([
                getCompaniesPage({ q: normalizedQuery || undefined, size: SEARCH_SIZE }, { signal: controller.signal }),
                getCompaniesByIds(stableSelectedIds, { signal: controller.signal }),
            ])
                .then(([page, selected]) => {
                    if (controller.signal.aborted) return;
                    setResult({
                        query: normalizedQuery,
                        selectedKey,
                        options: page.items,
                        companies: mergeCompanies(selected, page.items),
                        error: null,
                    });
                })
                .catch((error: unknown) => {
                    if (controller.signal.aborted) return;
                    setResult((previous) => ({
                        query: normalizedQuery,
                        selectedKey,
                        options: [],
                        companies: previous?.companies.filter((company) => stableSelectedIds.includes(company.id)) ?? [],
                        error,
                    }));
                });
        }, SEARCH_DELAY_MS);
        return () => {
            controller.abort();
            window.clearTimeout(timer);
        };
    }, [enabled, normalizedQuery, selectedKey, stableSelectedIds]);

    return {
        companies: mergeCompanies(result?.companies ?? [], seeds),
        options: current?.options ?? EMPTY_COMPANIES,
        serverSearch: createServerPickerSearch(normalizedQuery, current ? { query: current.query, items: current.options } : null),
        loading: enabled && current === null,
        error: current?.error ?? null,
        onInputValueChange: (value: string, details?: ComboboxRootChangeEventDetails) => {
            if (!details || details.reason === 'input-change' || details.reason === 'clear-press') setQuery(value);
        },
    };
}
