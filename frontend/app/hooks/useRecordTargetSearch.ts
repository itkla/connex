'use client';

import { useEffect, useMemo, useState } from 'react';
import type { ComboboxRootChangeEventDetails } from '@base-ui/react/combobox';

import {
    getContactById,
    getContactsPage,
    getDealById,
    getDealsPage,
    getUserOptions,
    getUserById,
} from '@/app/lib/api';
import { createServerPickerSearch, pickerQueryKey } from '@/lib/server-picker-search';
import type { Contact, Deal, Page, User } from '@/app/lib/types';

const SEARCH_SIZE = 50;
const SEARCH_DELAY_MS = 200;
const EMPTY_IDS: Array<number | null | undefined> = [];
const EMPTY_CONTACTS: Contact[] = [];
const EMPTY_DEALS: Deal[] = [];
const EMPTY_USERS: User[] = [];

type RecordTarget = { id: number; name?: string; displayName?: string; username?: string };

function targetLabel(target: RecordTarget): string {
    return target.name ?? target.displayName ?? target.username ?? '';
}

function mergeTargets<T extends RecordTarget>(...groups: T[][]): T[] {
    const byId = new Map<number, T>();
    for (const group of groups) {
        for (const target of group) byId.set(target.id, target);
    }
    return [...byId.values()].sort((left, right) => targetLabel(left).localeCompare(targetLabel(right)));
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
    const [result, setResult] = useState<{ query: string; idsKey: string; items: T[]; error: unknown } | null>(null);
    const normalizedQuery = pickerQueryKey(query);
    const idsKey = selectedKey(ids);
    const stableIds = useMemo(
        () => idsKey ? idsKey.split(',').map(Number) : [],
        [idsKey],
    );
    const stableIdSet = useMemo(() => new Set(stableIds), [stableIds]);
    const current = enabled && result?.idsKey === idsKey ? result : null;
    const serverSearch = createServerPickerSearch(normalizedQuery, current);

    useEffect(() => {
        if (!enabled) return;
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            const hydrated: T[] = [];
            Promise.all([
                loadPage(normalizedQuery, controller.signal),
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
                    setResult({ query: normalizedQuery, idsKey, items: page.items, error: null });
                    setTargets((current) => {
                        const retained = current.filter((target) => stableIdSet.has(target.id));
                        if (normalizedQuery) {
                            return mergeTargets(hydrated, retained, page.items);
                        }
                        return mergeTargets(hydrated, retained, seeds, page.items);
                    });
                })
                .catch((nextError: unknown) => {
                    if (controller.signal.aborted) return;
                    setTargets((current) => current.filter((target) => stableIdSet.has(target.id)));
                    setResult({ query: normalizedQuery, idsKey, items: [], error: nextError });
                });
        }, SEARCH_DELAY_MS);
        return () => {
            window.clearTimeout(timer);
            controller.abort();
        };
    }, [enabled, normalizedQuery, idsKey, stableIds, stableIdSet, seeds, loadPage, loadById]);

    return {
        query,
        targets,
        serverSearch,
        loading: enabled && serverSearch.pending,
        error: serverSearch.pending ? null : current?.error ?? null,
        onInputValueChange: (value: string, details?: ComboboxRootChangeEventDetails) => {
            if (!details || details.reason === 'input-change' || details.reason === 'clear-press') setQuery(value);
        },
    };
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

const loadMembersPage = async (query: string, signal: AbortSignal) => {
    const items = await getUserOptions(query, { signal });
    return { items, total: items.length };
};
const loadMemberById = (id: number, signal: AbortSignal) => getUserById(id, { signal });

/** Provides bounded active-member search while retaining the selected assignees or creators. */
export function useMemberTargetSearch(
    enabled: boolean,
    ids: Array<number | null | undefined> = EMPTY_IDS,
    seeds: User[] = EMPTY_USERS,
) {
    const result = useRecordTargetSearch(enabled, ids, seeds, loadMembersPage, loadMemberById);
    return { ...result, users: result.targets };
}
