'use client';

import { useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';

import RelationMap from '@/app/components/map/RelationMap';
import ReplayControl, { type ReplayPhase } from '@/app/components/map/replay/ReplayControl';
import {
    augmentMasterGraph,
    employmentEdges,
    toComputedFrames,
    type ComputedFrame,
} from '@/app/components/map/graph/replay';
import { getMapReplay } from '@/app/lib/api';
import { toastError, toastInfo } from '@/app/lib/toast';
import { useReplayClock } from '@/app/hooks/useReplayClock';
import type { Graph, RelationEdge } from '@/app/components/map/graph/types';
import type { ReplayFrame } from '@/app/lib/types';

const DEFAULT_WEEKS = 26;
const DAY_MS = 24 * 60 * 60 * 1000;

function isoDate(ms: number): string {
    return new Date(ms).toISOString().slice(0, 10);
}

type ReplayData = { extraEdges: RelationEdge[]; computed: ComputedFrame[]; frames: ReplayFrame[] };

/**
 * The relationship map surface. The live map renders immediately; historical "time-travel" data is
 * fetched only on intent (the bottom-left control), then overlaid onto the existing layout so the map
 * never re-settles — nodes fade/recolour and employment edges appear in place. Collapsing the control
 * returns the map to its live state.
 */
export default function MapView({ graph, focusId }: { graph: Graph; focusId?: string }) {
    const t = useTranslations('Replay');
    const [phase, setPhase] = useState<ReplayPhase>('idle');
    const [weeks, setWeeks] = useState(DEFAULT_WEEKS);
    const [data, setData] = useState<ReplayData | null>(null);
    const clock = useReplayClock(data?.computed.length ?? 0, 1);

    const load = useCallback(
        async (w: number): Promise<ReplayData> => {
            const now = Date.now();
            const res = await getMapReplay({
                from: isoDate(now - w * 7 * DAY_MS),
                to: isoDate(now),
                granularity: 'weekly',
            });
            const extraEdges = employmentEdges(graph, res.frames);
            const computed = toComputedFrames(res.frames, augmentMasterGraph(graph, res.frames));
            return { extraEdges, computed, frames: res.frames };
        },
        [graph],
    );

    const enter = useCallback(async () => {
        setPhase('loading');
        try {
            const next = await load(weeks);
            if (next.computed.length < 2) {
                toastInfo(t('singleFrame'));
                setPhase('idle');
                return;
            }
            setData(next);
            clock.seek(0);
            setPhase('active');
        } catch {
            setPhase('idle');
            toastError(t('error'));
        }
    }, [load, weeks, clock, t]);

    const changeWeeks = useCallback(
        async (next: number) => {
            setWeeks(next);
            try {
                const loaded = await load(next);
                setData(loaded);
                clock.seek(0);
            } catch {
                toastError(t('error'));
            }
        },
        [load, clock, t],
    );

    const exit = useCallback(() => {
        clock.seek(0);
        setData(null);
        setPhase('idle');
    }, [clock]);

    const active = phase === 'active';

    return (
        <div className="relative h-full w-full">
            <RelationMap
                graph={graph}
                focusId={focusId}
                extraEdges={data?.extraEdges}
                replay={active && data ? { frames: data.computed, frameIndex: clock.frameIndex } : undefined}
            />
            <div className="absolute bottom-4 left-4 z-10">
                <ReplayControl
                    phase={phase}
                    frames={data?.frames ?? []}
                    frameIndex={clock.frameIndex}
                    playing={clock.playing}
                    weeks={weeks}
                    onEnter={enter}
                    onExit={exit}
                    onToggle={clock.toggle}
                    onSeek={clock.seek}
                    onWeeksChange={changeWeeks}
                />
            </div>
        </div>
    );
}
