'use client';

import { useEffect, useState } from 'react';
import { motion, useReducedMotion } from 'motion/react';

import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import type { DealRisk, RelationshipTemperature } from '@/app/lib/types';
import { easeOut, instant } from '@/app/lib/motion';
import { cn } from '@/lib/utils';

/**
 * Keeps a restrained record identity available after the spacious header scrolls away.
 */
export default function RecordStickyContext({
    anchorId,
    name,
    temperature,
    risk,
}: {
    anchorId: string;
    name: string;
    temperature?: RelationshipTemperature | null;
    risk?: DealRisk | null;
}) {
    const [visible, setVisible] = useState(false);
    const reduce = useReducedMotion() ?? false;

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
            <motion.div
                initial={false}
                animate={{ opacity: visible ? 1 : 0, y: visible ? 0 : -8 }}
                transition={reduce ? instant : { duration: 0.15, ease: easeOut }}
                aria-hidden={!visible}
                inert={!visible}
                className={cn(
                    'pointer-events-none',
                    visible && 'pointer-events-auto',
                )}
            >
                <div className="flex items-center gap-3 rounded-xl border border-border bg-background/95 px-3 py-2 shadow-sm backdrop-blur">
                    <p className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground">{name}</p>
                    {temperature ? <TemperaturePill temp={temperature} /> : null}
                    {risk ? <DealRiskPill risk={risk} /> : null}
                </div>
            </motion.div>
        </div>
    );
}
