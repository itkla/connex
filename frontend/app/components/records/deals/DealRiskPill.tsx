'use client';

import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { riskSurfaceClasses, riskTextClass } from '@/app/lib/utils';
import type { DealRisk } from '@/app/lib/types';

import { useRiskText } from './dealRisk';

/**
 * Compact risk indicator for a deal — the sibling of {@link TemperaturePill}. Renders nothing when
 * the deal is not at risk; on hover its tooltip lists the contributing factors.
 */
export default function DealRiskPill({ risk }: { risk?: DealRisk | null }) {
    const { levelLabel, factorText } = useRiskText();

    const level = risk?.level;
    if (!risk || level == null || level === 'none' || risk.factors.length === 0) return null;

    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <span
                    className={cn(
                        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset select-none',
                        riskSurfaceClasses(level),
                    )}
                >
                    <ExclamationTriangleIcon className={cn('size-3.5 shrink-0', riskTextClass(level))} aria-hidden />
                    {levelLabel(level)}
                </span>
            </TooltipTrigger>
            <TooltipContent>
                <ul className="grid gap-1">
                    {risk.factors.map((factor, index) => (
                        <li key={`${factor.code}-${index}`}>{factorText(factor)}</li>
                    ))}
                </ul>
            </TooltipContent>
        </Tooltip>
    );
}
