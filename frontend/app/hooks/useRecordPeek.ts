'use client';

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';

import type { RecordType } from '@/app/lib/actions/types';
import { useWorkspace } from '@/app/hooks/useWorkspace';

/** The record types the Peek drawer can open. The contacts browser uses the `person` type. */
export type PeekType = Extract<RecordType, 'company' | 'person' | 'deal'>;

export type PeekTarget = { type: PeekType; id: number };

/** The URL search param carrying the Peek deep link; preserved by {@link useRecordsBrowser}. */
export const PEEK_PARAM = 'peek';

const PEEK_TYPES: readonly PeekType[] = ['company', 'person', 'deal'];

function parsePeek(value: string | null): PeekTarget | null {
    if (!value) return null;
    const [rawType, rawId] = value.split(':');
    const type = PEEK_TYPES.find((candidate) => candidate === rawType);
    const id = Number(rawId);
    if (!type || !Number.isInteger(id) || id <= 0) return null;
    return { type, id };
}

function liveTarget(browserType: PeekType): PeekTarget | null {
    const parsed = parsePeek(new URLSearchParams(window.location.search).get(PEEK_PARAM));
    return parsed && parsed.type === browserType ? parsed : null;
}

/**
 * Reads and writes the `?peek=<type>:<id>` deep-link param and drives previous/next over a
 * caller-supplied ordered id list (the visible, sorted, filtered result set). Writes go through
 * `window.history.replaceState` — a shallow URL update the Next router syncs into
 * `useSearchParams` without a server round-trip — and prev/next reads the freshly written URL so
 * rapid steps never race the router sync. A param whose type doesn't match {@link browserType}
 * never opens a drawer and is removed from the URL. An open peek closes on workspace switch.
 *
 * @param browserType the record type this browser peeks (company | person | deal)
 * @param orderedIds the ids of the currently visible rows, in display order
 */
export function useRecordPeek(browserType: PeekType, orderedIds: number[]) {
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const { activeWorkspaceId } = useWorkspace();

    const raw = searchParams.get(PEEK_PARAM);
    const parsed = useMemo(() => parsePeek(raw), [raw]);
    const target = parsed && parsed.type === browserType ? parsed : null;

    const write = useCallback(
        (next: PeekTarget | null) => {
            const params = new URLSearchParams(window.location.search);
            if (next) {
                params.set(PEEK_PARAM, `${next.type}:${next.id}`);
            } else {
                params.delete(PEEK_PARAM);
            }
            const query = params.toString();
            if (query === window.location.search.replace(/^\?/, '')) return;
            window.history.replaceState(null, '', query ? `${pathname}?${query}` : pathname);
        },
        [pathname],
    );

    const open = useCallback((id: number) => write({ type: browserType, id }), [browserType, write]);
    const close = useCallback(() => write(null), [write]);

    const goTo = useCallback(
        (delta: -1 | 1) => {
            const current = liveTarget(browserType);
            if (!current) return;
            const index = orderedIds.indexOf(current.id);
            if (index < 0) return;
            const nextIndex = index + delta;
            if (nextIndex < 0 || nextIndex >= orderedIds.length) return;
            write({ type: browserType, id: orderedIds[nextIndex] });
        },
        [orderedIds, browserType, write],
    );

    useEffect(() => {
        const current = parsePeek(new URLSearchParams(window.location.search).get(PEEK_PARAM));
        if (current && current.type !== browserType) write(null);
    }, [searchParams, browserType, write]);

    const workspaceRef = useRef(activeWorkspaceId);
    useEffect(() => {
        if (workspaceRef.current === activeWorkspaceId) return;
        workspaceRef.current = activeWorkspaceId;
        if (target) close();
    }, [activeWorkspaceId, target, close]);

    const index = target ? orderedIds.indexOf(target.id) : -1;
    const position = useMemo(
        () => (index >= 0 ? { index: index + 1, total: orderedIds.length } : null),
        [index, orderedIds.length],
    );

    return {
        target,
        open,
        close,
        goTo,
        hasPrev: index > 0,
        hasNext: index >= 0 && index < orderedIds.length - 1,
        position,
    };
}
