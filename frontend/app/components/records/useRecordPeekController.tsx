'use client';

import { useCallback, useEffect, useMemo } from 'react';

import { useRecordPeek, type PeekType } from '@/app/hooks/useRecordPeek';
import { useRecordListKeys } from '@/app/hooks/useRecordListKeys';
import RecordPeekDrawer from '@/app/components/records/RecordPeekDrawer';
import type { RecordReturnSelectionSnapshot } from '@/app/lib/recordReturnPath';

/**
 * Ties the Peek deep-link param and keyboard row navigation to a browser's visible ordered rows.
 * Returns the row-click opener, the active-row id for the J/K highlight, and the ready-to-mount
 * drawer element. Each records browser wires this with its own visible id list; pass
 * `keysEnabled: false` for display modes without a visible row highlight (grid, kanban) — deep
 * links and the drawer's own prev/next still work there.
 *
 * @param browserType the record type this browser peeks
 * @param items the currently visible rows, in display order
 * @param keysEnabled whether the J/K/Space keyboard navigation is active
 * @param returnSelection the scoped list selection to restore after opening full detail
 */
export function useRecordPeekController<T extends { id: number }>(
    browserType: PeekType,
    items: T[],
    keysEnabled = true,
    returnSelection?: RecordReturnSelectionSnapshot,
) {
    const orderedIds = useMemo(() => items.map((item) => item.id), [items]);
    const peek = useRecordPeek(browserType, orderedIds);
    const { goTo } = peek;
    const onPrev = useCallback(() => goTo(-1), [goTo]);
    const onNext = useCallback(() => goTo(1), [goTo]);
    const { activeId, setActiveId } = useRecordListKeys({
        orderedIds,
        peekOpen: peek.target !== null,
        enabled: keysEnabled,
        onOpen: peek.open,
        onPrev,
        onNext,
    });

    const targetId = peek.target?.id ?? null;
    useEffect(() => {
        if (targetId !== null) setActiveId(targetId);
    }, [targetId, setActiveId]);
    const closePeek = useCallback(() => {
        const closingId = peek.target?.id;
        peek.close();
        if (closingId == null) return;
        window.requestAnimationFrame(() => {
            document.querySelector<HTMLElement>(
                `[data-record-row-id="${closingId}"]`,
            )?.focus();
        });
    }, [peek]);

    const drawer = (
        <RecordPeekDrawer
            target={peek.target}
            browserType={browserType}
            onClose={closePeek}
            onPrev={onPrev}
            onNext={onNext}
            hasPrev={peek.hasPrev}
            hasNext={peek.hasNext}
            position={peek.position}
            returnSelection={returnSelection}
        />
    );

    return { openPeek: peek.open, activeId: targetId ?? activeId, drawer };
}
