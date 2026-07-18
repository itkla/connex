'use client';

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { useWorkspace } from '@/app/hooks/useWorkspace';

/** The record types the Peek drawer can open. The contacts browser uses the `person` type. */
export type PeekType = 'company' | 'person' | 'deal';

export type PeekTarget = { type: PeekType; id: number };

const PEEK_TYPES: readonly PeekType[] = ['company', 'person', 'deal'];

function parsePeek(value: string | null): PeekTarget | null {
    if (!value) return null;
    const [rawType, rawId] = value.split(':');
    const type = PEEK_TYPES.find((candidate) => candidate === rawType);
    const id = Number(rawId);
    if (!type || !Number.isInteger(id) || id <= 0) return null;
    return { type, id };
}

/**
 * Reads and writes the `?peek=<type>:<id>` deep-link param without a full navigation, and drives
 * previous/next over a caller-supplied ordered id list (the visible, sorted, filtered result set).
 * The param is validated against {@link browserType} so a stale/foreign value is cleared rather
 * than opening the wrong record type. An open peek closes on workspace switch.
 *
 * @param browserType the record type this browser peeks (company | person | deal)
 * @param orderedIds the ids of the currently visible rows, in display order
 */
export function useRecordPeek(browserType: PeekType, orderedIds: number[]) {
    const router = useRouter();
    const pathname = usePathname();
    const searchParams = useSearchParams();
    const { activeWorkspaceId } = useWorkspace();

    const raw = searchParams.get('peek');
    const parsed = useMemo(() => parsePeek(raw), [raw]);
    const target = parsed && parsed.type === browserType ? parsed : null;

    const write = useCallback(
        (next: PeekTarget | null) => {
            const params = new URLSearchParams(searchParams.toString());
            if (next) {
                params.set('peek', `${next.type}:${next.id}`);
            } else {
                params.delete('peek');
            }
            const query = params.toString();
            if (query === searchParams.toString()) return;
            router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
        },
        [pathname, router, searchParams],
    );

    const open = useCallback((id: number) => write({ type: browserType, id }), [browserType, write]);
    const close = useCallback(() => write(null), [write]);

    const index = target ? orderedIds.indexOf(target.id) : -1;

    const goTo = useCallback(
        (delta: -1 | 1) => {
            if (index < 0) return;
            const nextIndex = index + delta;
            if (nextIndex < 0 || nextIndex >= orderedIds.length) return;
            write({ type: browserType, id: orderedIds[nextIndex] });
        },
        [index, orderedIds, browserType, write],
    );

    const hasPrev = index > 0;
    const hasNext = index >= 0 && index < orderedIds.length - 1;

    const workspaceRef = useRef(activeWorkspaceId);
    useEffect(() => {
        if (workspaceRef.current === activeWorkspaceId) return;
        workspaceRef.current = activeWorkspaceId;
        if (target) close();
    }, [activeWorkspaceId, target, close]);

    return {
        target,
        invalid: parsed !== null && parsed.type !== browserType,
        open,
        close,
        goTo,
        hasPrev,
        hasNext,
        position: index >= 0 ? { index: index + 1, total: orderedIds.length } : null,
    };
}
