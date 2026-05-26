import { Fragment, type CSSProperties } from 'react';
import { CheckIcon, XMarkIcon } from '@heroicons/react/24/solid';

import { type Stage } from '@/app/lib/types';
import { formatDate, parseMysqlDateTime } from '@/app/lib/utils';
import { classifyStage, type DealOutcome } from '@/app/components/records/deals/dealOutcome';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';

const DAY_MS = 24 * 60 * 60 * 1000;

type ScheduleTone = 'early' | 'late' | 'neutral';

export default function DealLifecycleProgress({
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
    const sorted = [...stages].sort((a, b) => a.position - b.position);
    const filtered = sorted.filter((s) => {
        const c = classifyStage(s.name);
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
    const candidates = hasTime ? [nowMs] : [];
    if (hasTime && Number.isFinite(expectedMs)) candidates.push(expectedMs);
    if (hasTime && Number.isFinite(closedMs)) candidates.push(closedMs);
    const barEnd = candidates.length ? Math.max(...candidates) : 0;
    const span = hasTime ? Math.max(barEnd - startMs, DAY_MS) : 0;
    const pct = (t: number) =>
        hasTime ? Math.max(0, Math.min(100, ((t - startMs) / span) * 100)) : 0;

    const expectedPct = Number.isFinite(expectedMs) ? pct(expectedMs) : null;
    const closedPct = Number.isFinite(closedMs) ? pct(closedMs) : null;
    const nowPct = pct(nowMs);

    const lastStagePct = expectedPct ?? 100;
    const stagePct = (i: number) =>
        filtered.length <= 1 ? lastStagePct / 2 : (i / (filtered.length - 1)) * lastStagePct;

    const currentStagePct = currentIdx >= 0 ? stagePct(currentIdx) : null;
    const progressEnd = isClosed
        ? closedPct ?? lastStagePct
        : currentStagePct ?? 0;
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
    if (!isClosed && currentStagePct != null && hasTime && Number.isFinite(expectedMs)) {
        const expectedAtCurrentStage = startMs + (currentStagePct / 100) * span;
        scheduleDays = Math.round((nowMs - expectedAtCurrentStage) / DAY_MS);
    }

    const scheduleStatus = ((): { text: string; tone: ScheduleTone } | null => {
        if (!hasTime) return null;
        if (isClosed) {
            if (closedVsExpected == null) return null;
            if (closedVsExpected === 0) return { text: 'On time', tone: 'neutral' };
            return closedVsExpected > 0
                ? { text: `${closedVsExpected}d late`, tone: 'late' }
                : { text: `${-closedVsExpected}d early`, tone: 'early' };
        }
        if (scheduleDays != null) {
            if (scheduleDays === 0) return { text: 'On track', tone: 'neutral' };
            return scheduleDays > 0
                ? { text: `${scheduleDays}d behind`, tone: 'late' }
                : { text: `${-scheduleDays}d ahead`, tone: 'early' };
        }
        if (daysToExpected != null) {
            return daysToExpected >= 0
                ? { text: `${daysToExpected}d left`, tone: 'neutral' }
                : { text: `${-daysToExpected}d overdue`, tone: 'late' };
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

    const rightAnchorPct = isClosed && closedPct != null ? closedPct : lastStagePct;

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

                {hasTime && !isClosed ? (
                    // <div
                    //     title={`Today · day ${daysOpen}`}
                    //     aria-label={`Today · day ${daysOpen}`}
                    //     className="absolute top-4 z-0 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-brand shadow-sm sm:size-3.5"
                    //     style={{ left: `${nowPct}%` }}
                    // />
                    <Tooltip>
                        <TooltipTrigger asChild>
                            {/* <span className="text-xs text-neutral-500">Today · day {daysOpen}</span> */}
                            <div
                                className="absolute top-4 z-0 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-brand shadow-sm sm:size-3.5"
                                style={{ left: `${nowPct}%` }}
                            />
                        </TooltipTrigger>
                        <TooltipContent>
                            <p>Today · day {daysOpen} of {daysToExpected}</p>
                        </TooltipContent>
                    </Tooltip>
                ) : null}
                {isClosed && closedPct != null ? (
                    <div
                        title={`Closed ${formatDate(closedAt)}`}
                        aria-label={`Closed ${formatDate(closedAt)}`}
                        className={`absolute top-4 z-0 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow-sm sm:size-3.5 ${isWon ? 'bg-green-500' : isLost ? 'bg-red-500' : 'bg-neutral-700'}`}
                        style={{ left: `${closedPct}%` }}
                    />
                ) : null}

                {filtered.map((stage, i) => {
                    const c = classifyStage(stage.name);
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
                <div className="flex min-w-0 flex-col items-start">
                    <span className="font-medium text-neutral-700">Created</span>
                    <span>{formatDate(createdAt)}</span>
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
                        <div
                            className="flex min-w-0 flex-col items-end"
                            style={{ marginRight: `${Math.max(0, 100 - rightAnchorPct)}%` }}
                        >
                            <span
                                className={`font-medium ${isClosed ? closedNameClass : 'text-neutral-700'}`}
                            >
                                {isClosed ? 'Closed' : 'Expected close'}
                            </span>
                            <span>
                                {formatDate(isClosed && closedAt ? closedAt : expectedCloseDate)}
                            </span>

                        </div>
                    </>
                ) : null}
            </div>
        </div>
    );
}