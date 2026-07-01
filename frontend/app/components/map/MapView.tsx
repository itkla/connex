'use client';

import { useCallback } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { ClockIcon } from '@heroicons/react/24/outline';

import RelationMap from '@/app/components/map/RelationMap';
import ReplayView from '@/app/components/map/replay/ReplayView';
import type { Graph } from '@/app/components/map/graph/types';

const DEFAULT_WEEKS = 26;

/**
 * The relationship map surface: renders the live map by default with a "Replay" affordance, and the
 * time-travel replay experience when {@code ?replay=1} is set. Mode and range live in the URL so a
 * replay view is a shareable link.
 */
export default function MapView({ graph, focusId }: { graph: Graph; focusId?: string }) {
    const t = useTranslations('Replay');
    const searchParams = useSearchParams();
    const router = useRouter();
    const pathname = usePathname();
    const replayMode = searchParams.get('replay') === '1';

    const setParams = useCallback(
        (mutate: (params: URLSearchParams) => void) => {
            const params = new URLSearchParams(searchParams.toString());
            mutate(params);
            const qs = params.toString();
            router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
        },
        [pathname, router, searchParams],
    );

    const enterReplay = useCallback(
        () =>
            setParams((params) => {
                params.set('replay', '1');
                if (!params.get('weeks')) params.set('weeks', String(DEFAULT_WEEKS));
            }),
        [setParams],
    );

    const exitReplay = useCallback(
        () =>
            setParams((params) => {
                params.delete('replay');
                params.delete('weeks');
            }),
        [setParams],
    );

    const setWeeks = useCallback((weeks: number) => setParams((params) => params.set('weeks', String(weeks))), [setParams]);

    if (replayMode) {
        const weeks = Number(searchParams.get('weeks')) || DEFAULT_WEEKS;
        return (
            <ReplayView
                key={weeks}
                baseGraph={graph}
                focusId={focusId}
                weeks={weeks}
                onExit={exitReplay}
                onWeeksChange={setWeeks}
            />
        );
    }

    return (
        <div className="relative h-full w-full">
            <RelationMap graph={graph} focusId={focusId} />
            <button
                type="button"
                onClick={enterReplay}
                className="absolute left-3 top-3 z-10 flex items-center gap-1.5 rounded-lg border border-border bg-card/90 px-2.5 py-1.5 text-xs font-medium text-foreground shadow-md backdrop-blur transition-transform duration-150 ease-out hover:bg-card active:scale-95"
            >
                <ClockIcon className="size-4" />
                {t('enter')}
            </button>
        </div>
    );
}
