'use client';

import { useEffect, useMemo, useState } from 'react';

import { getCompaniesByIds, getCompaniesPage } from '@/app/lib/api';
import type { Company } from '@/app/lib/types';

const SEARCH_SIZE = 50;
const SEARCH_DELAY_MS = 200;
const EMPTY_IDS: Array<number | null | undefined> = [];
const EMPTY_COMPANIES: Company[] = [];

function mergeCompanies(primary: Company[], secondary: Company[]): Company[] {
    const byId = new Map<number, Company>();
    for (const company of [...primary, ...secondary]) byId.set(company.id, company);
    return [...byId.values()].sort((left, right) => left.name.localeCompare(right.name));
}

/** Provides debounced, paged company options while preserving selected records by id. */
export function useCompanySearch(
    enabled: boolean,
    selectedIds: Array<number | null | undefined> = EMPTY_IDS,
    seeds: Company[] = EMPTY_COMPANIES,
) {
    const [query, setQuery] = useState('');
    const [companies, setCompanies] = useState<Company[]>(seeds);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<unknown>(null);
    const selectedKey = [...selectedIds]
        .filter((id): id is number => id != null && Number.isInteger(id) && id > 0)
        .sort((left, right) => left - right)
        .join(',');
    const stableSelectedIds = useMemo(
        () => selectedKey ? selectedKey.split(',').map(Number) : [],
        [selectedKey],
    );

    useEffect(() => {
        if (!enabled) return;
        let cancelled = false;
        const timer = window.setTimeout(() => {
            setLoading(true);
            setError(null);
            Promise.all([
                getCompaniesPage({ q: query.trim() || undefined, size: SEARCH_SIZE }),
                getCompaniesByIds(stableSelectedIds),
            ])
                .then(([page, selected]) => {
                    if (!cancelled) {
                        setCompanies((current) => mergeCompanies(
                            selected,
                            mergeCompanies(
                                page.items,
                                current.filter((company) => stableSelectedIds.includes(company.id)),
                            ),
                        ));
                    }
                })
                .catch((nextError: unknown) => {
                    if (!cancelled) {
                        setCompanies((current) => current.filter(
                            (company) => stableSelectedIds.includes(company.id),
                        ));
                        setError(nextError);
                    }
                })
                .finally(() => {
                    if (!cancelled) setLoading(false);
                });
        }, SEARCH_DELAY_MS);
        return () => {
            cancelled = true;
            window.clearTimeout(timer);
        };
    }, [enabled, query, stableSelectedIds]);

    return { companies, loading, error, onInputValueChange: setQuery };
}
