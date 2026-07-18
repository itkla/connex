'use client';

import { useMemo } from 'react';

import { useRecordPeek, type PeekType } from '@/app/hooks/useRecordPeek';
import { useRecordListKeys } from '@/app/hooks/useRecordListKeys';
import RecordPeekDrawer from '@/app/components/records/RecordPeekDrawer';

/**
 * Ties the Peek deep-link param and keyboard row navigation to a browser's visible ordered rows.
 * Returns the row-click opener, the active-row id for the J/K highlight, and the ready-to-mount
 * drawer element. Each records browser wires this with its own visible id list.
 *
 * @param browserType the record type this browser peeks
 * @param items the currently visible rows, in display order
 */
export function useRecordPeekController<T extends { id: number }>(browserType: PeekType, items: T[]) {
    const orderedIds = useMemo(() => items.map((item) => item.id), [items]);
    const peek = useRecordPeek(browserType, orderedIds);
    const { activeId, setActiveId } = useRecordListKeys({
        orderedIds,
        peekOpen: peek.target !== null,
        onOpen: peek.open,
        onPrev: () => peek.goTo(-1),
        onNext: () => peek.goTo(1),
    });

    const openPeek = (id: number) => {
        setActiveId(id);
        peek.open(id);
    };

    const drawer = (
        <RecordPeekDrawer
            target={peek.target}
            browserType={browserType}
            onClose={peek.close}
            onPrev={() => peek.goTo(-1)}
            onNext={() => peek.goTo(1)}
            hasPrev={peek.hasPrev}
            hasNext={peek.hasNext}
            position={peek.position}
        />
    );

    return { openPeek, activeId: peek.target?.id ?? activeId, drawer };
}
