'use client';

import { ArrowLeftIcon } from '@heroicons/react/24/outline';
import { useEffect, useState } from 'react';

import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import RecordReturnLink from '@/app/components/records/RecordReturnLink';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import type { DealRisk, RelationshipTemperature } from '@/app/lib/types';
import { cn } from '@/lib/utils';

/**
 * Keeps a restrained record identity and return path available after the spacious header scrolls away.
 */
export default function RecordStickyContext({
    anchorId,
    backHref,
    backLabel,
    name,
    temperature,
    risk,
}: {
    anchorId: string;
    backHref: string;
    backLabel: string;
    name: string;
    temperature?: RelationshipTemperature | null;
    risk?: DealRisk | null;
}) {
    const [visible, setVisible] = useState(false);

    useEffect(() => {
        const anchor = document.getElementById(anchorId);
        const root = document.querySelector<HTMLElement>('[data-app-main]');
        if (!anchor || !root) return;
        const observer = new IntersectionObserver(
            ([entry]) => setVisible(!entry.isIntersecting && entry.boundingClientRect.top < 0),
            { root, threshold: 0 },
        );
        observer.observe(anchor);
        return () => observer.disconnect();
    }, [anchorId]);

    return (
        <div
            className="sticky top-0 z-20 h-0"
            data-record-sticky-context
            data-visible={visible}
        >
            <div
                aria-hidden={!visible}
                inert={!visible}
                className={cn(
                    'pointer-events-none -translate-y-2 opacity-0 transition-[opacity,transform] duration-150 ease-[cubic-bezier(0.23,1,0.32,1)] motion-reduce:translate-y-0 motion-reduce:transition-opacity',
                    visible && 'pointer-events-auto translate-y-0 opacity-100',
                )}
            >
                <div className="flex items-center gap-3 rounded-xl border border-border bg-background/95 px-3 py-2 shadow-sm backdrop-blur">
                    <RecordReturnLink
                        href={backHref}
                        ariaLabel={backLabel}
                        className="grid size-8 shrink-0 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    >
                        <ArrowLeftIcon className="size-4" aria-hidden />
                    </RecordReturnLink>
                    <p className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{name}</p>
                    {temperature ? <TemperaturePill temp={temperature} /> : null}
                    {risk ? <DealRiskPill risk={risk} /> : null}
                </div>
            </div>
        </div>
    );
}
