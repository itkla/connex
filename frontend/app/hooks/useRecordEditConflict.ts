"use client";

import { useRef, useState } from 'react';
import { isStaleRecordError } from '@/app/lib/recordEditConflict';

/** The current record is absent when fetching it failed; the original draft remains untouched. */
export type RecordEditConflict<T> = { base: T; current: T | null };

/** Loads a refused edit's latest record without replacing its draft; clearing invalidates pending loads. */
export function useRecordEditConflict<T extends { id: number }>(
    load: (id: number, init: RequestInit) => Promise<T>,
) {
    const [conflict, setConflict] = useState<RecordEditConflict<T> | null>(null);
    const generation = useRef(0);

    const captureConflict = async (error: unknown, base: T, init: RequestInit): Promise<boolean> => {
        if (!isStaleRecordError(error)) return false;
        const requestGeneration = ++generation.current;
        let current: T | null;
        try {
            current = await load(base.id, init);
        } catch {
            current = null;
        }
        if (generation.current === requestGeneration) setConflict({ base, current });
        return true;
    };

    return {
        conflict,
        captureConflict,
        clearConflict: () => {
            generation.current += 1;
            setConflict(null);
        },
    };
}
