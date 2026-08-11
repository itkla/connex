'use client';

import { useEffect, useMemo, useState } from 'react';

import { getCommentIndicators } from '@/app/lib/api';
import type { RecordCommentTargetType } from '@/app/lib/types';

const BATCH_LIMIT = 100;
const EMPTY_COUNTS: Map<number, number> = new Map();

type IndicatorResult = {
    key: string;
    counts: Map<number, number>;
};

/**
 * Batch-fetches open comment-thread counts for the records currently on screen
 * (#906 slice 2). One request per id-set change, chunked at the server's
 * 100-id bound; failures resolve to an empty map so browsers render without
 * indicators rather than erroring. Returns targetId → open thread count, and
 * an empty map while a fresh id set is still loading.
 */
export function useCommentIndicators(
    targetType: RecordCommentTargetType,
    targetIds: number[],
): Map<number, number> {
    const [result, setResult] = useState<IndicatorResult>({ key: '', counts: EMPTY_COUNTS });
    const idsKey = useMemo(
        () => [...new Set(targetIds)].sort((a, b) => a - b).join(','),
        [targetIds],
    );

    useEffect(() => {
        if (idsKey.length === 0) return;
        let active = true;
        const ids = idsKey.split(',').map(Number);
        const chunks: number[][] = [];
        for (let start = 0; start < ids.length; start += BATCH_LIMIT) {
            chunks.push(ids.slice(start, start + BATCH_LIMIT));
        }
        Promise.all(
            chunks.map((chunk) =>
                getCommentIndicators(targetType, chunk).catch(() => []),
            ),
        ).then((results) => {
            if (!active) return;
            setResult({
                key: idsKey,
                counts: new Map(results.flat().map((row) => [row.targetId, row.openThreads])),
            });
        });
        return () => {
            active = false;
        };
    }, [targetType, idsKey]);

    return idsKey.length > 0 && result.key === idsKey ? result.counts : EMPTY_COUNTS;
}
