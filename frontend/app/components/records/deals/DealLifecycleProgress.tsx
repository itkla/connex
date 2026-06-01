import { Fragment, type CSSProperties } from 'react';
import { CheckIcon, XMarkIcon } from '@heroicons/react/24/solid';
import { getLocale, getTranslations } from 'next-intl/server';

import { type Stage } from '@/app/lib/types';
import { formatDate, parseMysqlDateTime } from '@/app/lib/utils';
import { classifyStage, type DealOutcome } from '@/app/components/records/deals/dealOutcome';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

const DAY_MS = 24 * 60 * 60 * 1000;

type ScheduleTone = 'early' | 'late' | 'neutral';

export default async function DealLifecycleProgress({
    stages,
    currentStageId,
    outcome,
    createdAt,
    expectedCloseDate,
    closedAt,
}: {
    stages: Stage[];
    currentStageId: number | null;
    outcome: DealOutcome;
    createdAt: string;
    expectedCloseDate?: string;
    closedAt?: string;
}) {
    const t = await getTranslations('DealsLifecycleProgress');
    const locale = await getLocale();
    const sorted = [...stages].sort((a, b) => a.position - b.position);
    const filtered = sorted.filter((s) => {
        const c = classifyStage(s);
        if (c === 'won') return outcome !== 'lost';
        if (c === 'lost') return outcome === 'lost';
        return true;
    });
    const currentIdx = filtered.findIndex((s) => s.id === currentStageId);

    const startMs = parseMysqlDateTime(createdAt);
    const expectedMs = expectedCloseDate ? parseMysqlDateTime(expectedCloseDate) : NaN;
    const closedMs = closedAt ? parseMysqlDateTime(closedAt) : NaN;
    const nowMs = Date.now();

    const isClosed = outcome !== 'open';
    const isWon = outcome === 'won';
    const isLost = outcome === 'lost';

    const hasTime = Number.isFinite(startMs);
    const hasPlan = hasTime && Number.isFinite(expectedMs) && expectedMs > startMs;
    const plannedSpan = hasPlan ? expectedMs - startMs : 0;
    const planDays = hasPlan ? Math.max(1, Math.round(plannedSpan / DAY_MS)) : 0;
    const timePct = (t: number) =>
        hasPlan ? Math.max(0, Math.min(100, ((t - startMs) / plannedSpan) * 100)) : 0;
    const nowPct = timePct(nowMs);
    const trackLeft = (p: number) => `calc(1rem + ${p / 100} * (100% - 2rem))`;

    const stagePct = (i: number) =>
        filtered.length <= 1 ? 50 : (i / (filtered.length - 1)) * 100;

    const currentStagePct = currentIdx >= 0 ? stagePct(currentIdx) : null;
    const progressEnd = currentStagePct ?? (isClosed ? 100 : 0);
    const progressColor = isWon ? 'bg-green-500' : isLost ? 'bg-red-500' : 'bg-brand';

    const daysOpen = hasTime ? Math.round((nowMs - startMs) / DAY_MS) : 0;
    const daysToExpected = Number.isFinite(expectedMs)
        ? Math.round((expectedMs - nowMs) / DAY_MS)
        : null;
    const closedVsExpected =
        isClosed && Number.isFinite(closedMs) && Number.isFinite(expectedMs)
            ? Math.round((closedMs - expectedMs) / DAY_MS)
            : null;

    let scheduleDays: number | null = null;
    if (!isClosed && currentStagePct != null && hasPlan) {
        const expectedAtCurrentStage = startMs + (currentStagePct / 100) * plannedSpan;
        scheduleDays = Math.round((nowMs - expectedAtCurrentStage) / DAY_MS);
    }

    const scheduleStatus = ((): { text: string; tone: ScheduleTone } | null => {
        if (!hasTime) return null;
        if (isClosed) {
            if (isLost) return null;
            if (closedVsExpected == null) return null;
            if (closedVsExpected === 0) return { text: t('onTime'), tone: 'neutral' };
            return closedVsExpected > 0
                ? { text: t('daysLate', { days: closedVsExpected }), tone: 'late' }
                : { text: t('daysEarly', { days: -closedVsExpected }), tone: 'early' };
        }
        if (scheduleDays != null) {
            if (scheduleDays === 0) return { text: t('onTrack'), tone: 'neutral' };
            return scheduleDays > 0
                ? { text: t('daysBehind', { days: scheduleDays }), tone: 'late' }
                : { text: t('daysAhead', { days: -scheduleDays }), tone: 'early' };
        }
        if (daysToExpected != null) {
            return daysToExpected >= 0
                ? { text: t('daysLeft', { days: daysToExpected }), tone: 'neutral' }
                : { text: t('daysOverdue', { days: -daysToExpected }), tone: 'late' };
        }
        return null;
    })();

    const scheduleClass =
        scheduleStatus?.tone === 'early'
            ? 'bg-green-100 text-green-700'
            : scheduleStatus?.tone === 'late'
                ? 'bg-red-100 text-red-700'
                : 'bg-neutral-200 text-neutral-700';

    if (filtered.length === 0 && !hasTime) return null;

    const closedNameClass = isWon
        ? 'text-green-700'
        : isLost
            ? 'text-red-700'
            : 'text-neutral-700';

    return (
        <div className="rounded-2xl bg-neutral-100 p-4 ring-1 ring-black/5 sm:p-6">
            <div className="relative h-14 sm:h-16">
                <div
                    aria-hidden="true"
                    className="pointer-events-none absolute inset-x-0 top-4 h-px -translate-y-1/2 bg-neutral-300"
                />
                <div
                    aria-hidden="true"
                    className={`pointer-events-none absolute left-0 top-4 h-px -translate-y-1/2 ${progressColor}`}
                    style={{ width: `${progressEnd}%` }}
                />

                {!isClosed && hasPlan ? (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <div
                                className="absolute top-4 z-0 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-brand shadow-sm sm:size-3.5"
                                style={{ left: trackLeft(nowPct) }}
                            />
                        </TooltipTrigger>
                        <TooltipContent>
                            <p>{t('todayDay', { daysOpen, totalDays: planDays })}</p>
                        </TooltipContent>
                    </Tooltip>
                ) : null}

                {filtered.map((stage, i) => {
                    const c = classifyStage(stage);
                    const isWonStage = c === 'won';
                    const isLostStage = c === 'lost';
                    const wonAchieved = isWonStage && outcome === 'won';
                    const lostAchieved = isLostStage && outcome === 'lost';
                    const isPast =
                        currentIdx >= 0 &&
                        (i < currentIdx || (outcome !== 'open' && i === currentIdx));
                    const isCurrent = outcome === 'open' && i === currentIdx;
                    const left = stagePct(i);
                    const isFirst = i === 0 && filtered.length > 1;
                    const isLast = i === filtered.length - 1 && filtered.length > 1;

                    // TODO: move these to a css file so they can be applied elsewhere.

                    const circleClass = wonAchieved
                        ? 'bg-green-500 text-white ring-4 ring-green-100'
                        : lostAchieved
                            ? 'bg-red-500 text-white ring-4 ring-red-100'
                            : isCurrent
                                ? 'bg-brand text-white ring-4 ring-brand-light'
                                : isPast
                                    ? 'bg-brand text-white'
                                    : isWonStage
                                        ? 'bg-white text-green-600 ring-1 ring-green-300'
                                        : isLostStage
                                            ? 'bg-white text-red-600 ring-1 ring-red-300'
                                            : 'bg-white text-neutral-500 ring-1 ring-neutral-300';

                    const labelClass =
                        isCurrent || wonAchieved || lostAchieved
                            ? 'font-medium text-neutral-900'
                            : 'text-neutral-500';

                    const labelStyle: CSSProperties = isLast
                        ? { right: `${100 - left}%` }
                        : isFirst
                            ? { left: '0%' }
                            : { left: `${left}%`, transform: 'translateX(-50%)' };
                    const labelAlign = isLast
                        ? 'text-right'
                        : isFirst
                            ? 'text-left'
                            : 'text-center';

                    const circleStyle: CSSProperties = isLast
                        ? { right: `${100 - left}%` }
                        : isFirst
                            ? { left: '0%' }
                            : { left: `${left}%` };
                    const circleTransform = isFirst || isLast ? '' : '-translate-x-1/2';

                    return (
                        <Fragment key={stage.id}>
                            <div
                                className={`absolute top-0 z-10 flex size-8 ${circleTransform} items-center justify-center rounded-full ${circleClass}`}
                                style={circleStyle}
                            >
                                {wonAchieved ? (
                                    <CheckIcon className="size-4" />
                                ) : lostAchieved ? (
                                    <XMarkIcon className="size-4" />
                                ) : isPast ? (
                                    <CheckIcon className="size-4" />
                                ) : isWonStage ? (
                                    <CheckIcon className="size-4" />
                                ) : isLostStage ? (
                                    <XMarkIcon className="size-4" />
                                ) : (
                                    <span className="text-xs font-medium">{i + 1}</span>
                                )}
                            </div>
                            <span
                                className={`absolute top-9 max-w-[56px] truncate text-[10px] sm:top-10 sm:max-w-[88px] sm:text-xs ${labelAlign} ${labelClass}`}
                                style={labelStyle}
                            >
                                {stage.name}
                            </span>
                        </Fragment>
                    );
                })}
            </div>

            <div className="mt-4 flex items-start justify-between gap-3 text-[10px] leading-tight text-neutral-500 sm:text-[11px]">
                <div className="flex shrink-0 flex-col items-start whitespace-nowrap">
                    <span className="font-medium text-neutral-700">{t('created')}</span>
                    <span>{formatDate(createdAt, locale)}</span>
                </div>
                {expectedCloseDate || (isClosed && closedAt) ? (
                    <>
                        <div>
                            {scheduleStatus ? (
                                <span
                                    className={`mt-1 whitespace-nowrap rounded-full px-2 py-0.5 text-[9px] font-semibold uppercase tracking-[0.08em] sm:text-[10px] ${scheduleClass}`}
                                >
                                    {scheduleStatus.text}
                                </span>
                            ) : null}
                        </div>
                        <div className="flex shrink-0 flex-col items-end whitespace-nowrap">
                            <span
                                className={`font-medium ${isClosed ? closedNameClass : 'text-neutral-700'}`}
                            >
                                {isClosed ? t('closed') : t('expectedClose')}
                            </span>
                            <span>
                                {formatDate(isClosed && closedAt ? closedAt : expectedCloseDate, locale)}
                            </span>

                        </div>
                    </>
                ) : null}
            </div>
        </div>
    );
}