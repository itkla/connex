'use client';

import { useEffect, useMemo, useState } from 'react';

import {
    getContactById,
    getContactsPage,
    getDealById,
    getDealsPage,
} from '@/app/lib/api';
import type { Contact, Deal, Page } from '@/app/lib/types';

const SEARCH_SIZE = 50;
const SEARCH_DELAY_MS = 200;
const EMPTY_IDS: Array<number | null | undefined> = [];
const EMPTY_CONTACTS: Contact[] = [];
const EMPTY_DEALS: Deal[] = [];

type RecordTarget = { id: number; name: string };

function mergeTargets<T extends RecordTarget>(...groups: T[][]): T[] {
    const byId = new Map<number, T>();
    for (const group of groups) {
        for (const target of group) byId.set(target.id, target);
    }
    return [...byId.values()].sort((left, right) => left.name.localeCompare(right.name));
}

function selectedKey(ids: Array<number | null | undefined>): string {
    return [...new Set(ids.filter(
        (id): id is number => id != null && Number.isInteger(id) && id > 0,
    ))].sort((left, right) => left - right).join(',');
}

function useRecordTargetSearch<T extends RecordTarget>(
    enabled: boolean,
    ids: Array<number | null | undefined>,
    seeds: T[],
    loadPage: (query: string, signal: AbortSignal) => Promise<Page<T>>,
    loadById: (id: number, signal: AbortSignal) => Promise<T>,
) {
    const [query, setQuery] = useState('');
    const [targets, setTargets] = useState<T[]>(seeds);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<unknown>(null);
    const idsKey = selectedKey(ids);
    const stableIds = useMemo(
        () => idsKey ? idsKey.split(',').map(Number) : [],
        [idsKey],
    );

    useEffect(() => {
        if (!enabled) return;
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            setLoading(true);
            setError(null);
            const hydrated: T[] = [];
            Promise.all([
                loadPage(query.trim(), controller.signal),
                Promise.all(stableIds.map((id) =>
                    loadById(id, controller.signal).then(
                        (target) => {
                            hydrated.push(target);
                        },
                        () => undefined,
                    ),
                )),
            ])
                .then(([page]) => {
                    if (controller.signal.aborted) return;
                    setTargets((current) => mergeTargets(
                        hydrated,
                        current.filter((target) => stableIds.includes(target.id)),
                        seeds,
                        page.items,
                    ));
                })
                .catch((nextError: unknown) => {
                    if (controller.signal.aborted) return;
                    setTargets((current) => current.filter((target) => stableIds.includes(target.id)));
                    setError(nextError);
                })
                .finally(() => {
                    if (!controller.signal.aborted) setLoading(false);
                });
        }, SEARCH_DELAY_MS);
        return () => {
            window.clearTimeout(timer);
            controller.abort();
        };
    }, [enabled, query, stableIds, seeds, loadPage, loadById]);

    return { targets, loading, error, onInputValueChange: setQuery };
}

const loadContactsPage = (query: string, signal: AbortSignal) =>
    getContactsPage({ q: query || undefined, size: SEARCH_SIZE }, { signal });

const loadContactById = (id: number, signal: AbortSignal) =>
    getContactById(id, { signal });

const loadDealsPage = (query: string, signal: AbortSignal) =>
    getDealsPage({ q: query || undefined, size: SEARCH_SIZE }, { signal });

const loadDealById = (id: number, signal: AbortSignal) =>
    getDealById(id, { signal });

/** Provides open-only, debounced, server-paged contact targets while retaining selected records. */
export function useContactTargetSearch(
    enabled: boolean,
    ids: Array<number | null | undefined> = EMPTY_IDS,
    seeds: Contact[] = EMPTY_CONTACTS,
) {
    const result = useRecordTargetSearch(
        enabled,
        ids,
        seeds,
        loadContactsPage,
        loadContactById,
    );
    return { ...result, contacts: result.targets };
}

/** Provides open-only, debounced, server-paged deal targets while retaining selected records. */
export function useDealTargetSearch(
    enabled: boolean,
    ids: Array<number | null | undefined> = EMPTY_IDS,
    seeds: Deal[] = EMPTY_DEALS,
) {
    const result = useRecordTargetSearch(
        enabled,
        ids,
        seeds,
        loadDealsPage,
        loadDealById,
    );
    return { ...result, deals: result.targets };
}
