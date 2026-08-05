'use client';

import { useEffect, useMemo, useRef, useState, useTransition, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowPathIcon,
    ArrowsRightLeftIcon,
    ExclamationTriangleIcon,
    MapIcon,
    PlusIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { useLiveNow } from '@/app/hooks/useNow';
import { acceptWarmPath, dismissIntroSuggestion, dismissWarmPath, recordIntroduction } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatRelativeTime } from '@/app/lib/utils';
import type {
    Contact,
    IntroEmptyReason,
    IntroSuggestion,
    IntroductionRecord,
    WarmPath,
    WarmPathBridge,
    WarmPathReachType,
} from '@/app/lib/types';

import Rise from '@/app/components/motion/Rise';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import IntroLineageList from './IntroLineageList';
import IntroStats from './IntroStats';
import { tierFor } from './IntroStrength';
import IntroSuggestionCard from './IntroSuggestionCard';
import IntroSuggestionRow from './IntroSuggestionRow';
import LogIntroDialog from './LogIntroDialog';
import WarmPathRow from './WarmPathRow';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function pairKey(personAId: number, personBId: number): string {
    return `${Math.min(personAId, personBId)}-${Math.max(personAId, personBId)}`;
}

function SectionHeading({ title, count, asOf }: { title: string; count: number | null; asOf?: string | null }) {
    const t = useTranslations('Introductions');
    const locale = useLocale();
    const now = useLiveNow();
    return (
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
            <div className="flex items-center gap-2.5">
                <h2 className="text-sm font-semibold text-foreground">{title}</h2>
                <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground tabular-nums">
                    {count === null ? (
                        <>
                            <span aria-hidden>—</span>
                            <span className="sr-only">{t('statUnavailable')}</span>
                        </>
                    ) : (
                        count
                    )}
                </span>
            </div>
            {asOf ? (
                <span className="text-xs text-muted-foreground">
                    {t('freshness', { time: formatRelativeTime(asOf, locale, now) })}
                </span>
            ) : null}
        </div>
    );
}

function StatePanel({
    icon,
    title,
    hint,
    dashed = false,
    alert = false,
    action,
}: {
    icon: ReactNode;
    title: string;
    hint: string;
    dashed?: boolean;
    alert?: boolean;
    action?: ReactNode;
}) {
    return (
        <div
            className={cn(
                'flex flex-col items-center justify-center gap-2 rounded-2xl border border-border bg-card px-6 py-16 text-center',
                dashed && 'border-dashed',
            )}
        >
            {icon}
            <div role={alert ? 'alert' : undefined} className="space-y-2">
                <p className="text-sm font-medium text-foreground">{title}</p>
                <p className="mx-auto max-w-md text-sm text-muted-foreground">{hint}</p>
            </div>
            {action}
        </div>
    );
}

/**
 * Client orchestration for the Introductions page: a site-matching header, an overview strip, the
 * ranked queue of intros to make (a featured lead plus scannable rows, with optimistic record/dismiss)
 * and the lineage of intros made. Recording a pair here also creates the connection on the backend,
 * so a recorded pair simply leaves the queue and lands in the timeline. The queue is one animated list
 * so promoting the next-best suggestion to the lead is a continuous transition, not a remount.
 * Between the queue and the lineage sits the warm-paths feed (issue #614) — targets worth reaching
 * with the bridges who can introduce you, filterable by re-warm vs new reach; accepting a path
 * creates the follow-up task on the backend and removes the row optimistically.
 * When a fetch failed ({@code suggestionsFailed} / {@code lineageFailed}), the affected section
 * renders a distinct error state instead of masquerading as an empty workspace, and the
 * queue-derived counts show an unavailable marker instead of claiming zero.
 */
export default function IntroductionsBoard({
    initialSuggestions,
    suggestionsFailed = false,
    suggestionsEmptyReason,
    initialPaths,
    pathsFailed = false,
    pathsEmptyReason,
    asOf,
    initialLineage,
    initialLineageTotal,
    lineageFailed = false,
    contacts,
}: {
    initialSuggestions: IntroSuggestion[];
    suggestionsFailed?: boolean;
    suggestionsEmptyReason?: IntroEmptyReason | null;
    initialPaths: WarmPath[];
    pathsFailed?: boolean;
    pathsEmptyReason?: IntroEmptyReason | null;
    asOf?: string | null;
    initialLineage: IntroductionRecord[];
    initialLineageTotal: number;
    lineageFailed?: boolean;
    contacts: Contact[];
}) {
    const t = useTranslations('Introductions');
    const reduce = useReducedMotion() ?? false;
    const router = useRouter();
    const [retrying, startRetry] = useTransition();
    const retryRequested = useRef(false);
    const [retryStatus, setRetryStatus] = useState('');
    const [suggestions, setSuggestions] = useState(initialSuggestions);

    useEffect(() => {
        if (!retrying && retryRequested.current) {
            retryRequested.current = false;
            setRetryStatus(t('retryFailed'));
            toastError(t('retryFailed'));
        }
    }, [retrying, t]);

    const retry = () => {
        if (retrying) return;
        retryRequested.current = true;
        setRetryStatus(t('retrying'));
        startRetry(() => router.refresh());
    };
    const [lineage, setLineage] = useState(initialLineage);
    const [madeTotal, setMadeTotal] = useState(initialLineageTotal);
    const [paths, setPaths] = useState(() => initialPaths.filter((p) => p.bridges.length > 0));
    const [pathFilter, setPathFilter] = useState<'all' | WarmPathReachType>('all');

    const emptyCopy = (reason?: IntroEmptyReason | null) => {
        switch (reason) {
            case 'insufficient_candidates':
                return {
                    title: t('emptyInsufficientCandidatesTitle'),
                    hint: t('emptyInsufficientCandidatesHint'),
                };
            case 'missing_relationship_evidence':
                return {
                    title: t('emptyMissingEvidenceTitle'),
                    hint: t('emptyMissingEvidenceHint'),
                };
            case 'policy_exclusion':
                return {
                    title: t('emptyPolicyExclusionTitle'),
                    hint: t('emptyPolicyExclusionHint'),
                };
            case 'insufficient_path_strength':
                return {
                    title: t('emptyInsufficientStrengthTitle'),
                    hint: t('emptyInsufficientStrengthHint'),
                };
            case 'unavailable_data':
                return {
                    title: t('emptyUnavailableTitle'),
                    hint: t('emptyUnavailableHint'),
                };
            default:
                return {
                    title: t('emptyQueueClearedTitle'),
                    hint: t('emptyQueueClearedHint'),
                };
        }
    };

    const suggestionsEmptyCopy = emptyCopy(suggestionsEmptyReason);
    const pathsEmptyCopy = emptyCopy(pathsEmptyReason);
    const visiblePathsEmptyCopy = paths.length === 0
        ? pathsEmptyCopy
        : { title: t('pathsEmptyTitle'), hint: t('pathsEmptyHint') };

    const strongCount = useMemo(
        () => suggestions.filter((s) => tierFor(s.score).tier === 'strong').length,
        [suggestions],
    );

    const indexOfSuggestion = (suggestion: IntroSuggestion) => {
        const key = pairKey(suggestion.personAId, suggestion.personBId);
        return suggestions.findIndex((s) => pairKey(s.personAId, s.personBId) === key);
    };

    const removeSuggestion = (personAId: number, personBId: number) => {
        const key = pairKey(personAId, personBId);
        setSuggestions((current) => current.filter((s) => pairKey(s.personAId, s.personBId) !== key));
    };

    const restoreSuggestion = (suggestion: IntroSuggestion, index: number) => {
        const key = pairKey(suggestion.personAId, suggestion.personBId);
        setSuggestions((current) => {
            if (current.some((s) => pairKey(s.personAId, s.personBId) === key)) return current;
            const next = [...current];
            next.splice(Math.min(Math.max(index, 0), next.length), 0, suggestion);
            return next;
        });
    };

    const addToLineage = (created: IntroductionRecord) => {
        const isNew = !lineage.some((i) => i.id === created.id);
        setLineage((current) => [created, ...current.filter((i) => i.id !== created.id)]);
        if (isNew) setMadeTotal((count) => count + 1);
    };

    const record = async (suggestion: IntroSuggestion) => {
        const index = indexOfSuggestion(suggestion);
        removeSuggestion(suggestion.personAId, suggestion.personBId);
        try {
            const created = await recordIntroduction({
                personAId: suggestion.personAId,
                personBId: suggestion.personBId,
            });
            addToLineage(created);
            toastSuccess(t('recordedToast', { a: suggestion.personAName, b: suggestion.personBName }));
        } catch (err) {
            restoreSuggestion(suggestion, index);
            toastError(err instanceof Error ? err.message : t('recordFailed'));
        }
    };

    const dismiss = async (suggestion: IntroSuggestion) => {
        const index = indexOfSuggestion(suggestion);
        removeSuggestion(suggestion.personAId, suggestion.personBId);
        try {
            await dismissIntroSuggestion({
                personAId: suggestion.personAId,
                personBId: suggestion.personBId,
            });
        } catch (err) {
            restoreSuggestion(suggestion, index);
            toastError(err instanceof Error ? err.message : t('dismissFailed'));
        }
    };

    const visiblePaths = useMemo(
        () => (pathFilter === 'all' ? paths : paths.filter((p) => p.reachType === pathFilter)),
        [paths, pathFilter],
    );

    const mention = (name: string, id: number) => `[${name}](person:${id})`;

    const removePathRow = (targetId: number) => {
        setPaths((current) => current.filter((p) => p.targetId !== targetId));
    };

    const restorePathRow = (path: WarmPath, index: number) => {
        setPaths((current) => {
            if (current.some((p) => p.targetId === path.targetId)) return current;
            const next = [...current];
            next.splice(Math.min(Math.max(index, 0), next.length), 0, path);
            return next;
        });
    };

    const askIntro = async (path: WarmPath, bridge: WarmPathBridge) => {
        const index = paths.findIndex((p) => p.targetId === path.targetId);
        removePathRow(path.targetId);
        try {
            await acceptWarmPath({
                targetPersonId: path.targetId,
                bridgePersonId: bridge.personId,
                taskDescription: t('acceptTaskDescription', {
                    bridge: mention(bridge.name, bridge.personId),
                    target: mention(path.targetName, path.targetId),
                }),
            });
            toastSuccess(t('acceptToast', { name: path.targetName }));
        } catch (err) {
            restorePathRow(path, index);
            toastError(err instanceof Error ? err.message : t('acceptFailed'));
            throw err;
        }
    };

    const dismissTarget = async (path: WarmPath) => {
        const index = paths.findIndex((p) => p.targetId === path.targetId);
        removePathRow(path.targetId);
        try {
            await dismissWarmPath({ targetPersonId: path.targetId });
        } catch (err) {
            restorePathRow(path, index);
            toastError(err instanceof Error ? err.message : t('dismissFailed'));
            throw err;
        }
    };

    const restoreAvenue = (path: WarmPath, rowIndex: number, bridge: WarmPathBridge, bridgeIndex: number) => {
        setPaths((current) => {
            const existing = current.find((p) => p.targetId === path.targetId);
            if (!existing) {
                const next = [...current];
                next.splice(Math.min(Math.max(rowIndex, 0), next.length), 0, path);
                return next;
            }
            if (existing.bridges.some((b) => b.personId === bridge.personId)) return current;
            const at = Math.min(Math.max(bridgeIndex, 0), existing.bridges.length);
            return current.map((p) => p.targetId === path.targetId
                ? { ...p, bridges: [...p.bridges.slice(0, at), bridge, ...p.bridges.slice(at)] }
                : p);
        });
    };

    const dismissAvenue = async (path: WarmPath, bridge: WarmPathBridge) => {
        const rowIndex = paths.findIndex((p) => p.targetId === path.targetId);
        const bridgeIndex = path.bridges.findIndex((b) => b.personId === bridge.personId);
        setPaths((current) => current
            .map((p) => p.targetId === path.targetId
                ? { ...p, bridges: p.bridges.filter((b) => b.personId !== bridge.personId) }
                : p)
            .filter((p) => p.bridges.length > 0));
        try {
            await dismissWarmPath({ targetPersonId: path.targetId, bridgePersonId: bridge.personId });
        } catch (err) {
            restoreAvenue(path, rowIndex, bridge, bridgeIndex);
            toastError(err instanceof Error ? err.message : t('dismissFailed'));
            throw err;
        }
    };

    const logManual = async (personAId: number, personBId: number, note?: string) => {
        try {
            const created = await recordIntroduction({ personAId, personBId, note });
            addToLineage(created);
            removeSuggestion(personAId, personBId);
            toastSuccess(t('recordedManualToast'));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('recordFailed'));
            throw err;
        }
    };

    const enter = (index: number) =>
        reduce
            ? { initial: false as const, animate: { opacity: 1 } }
            : {
                  initial: { opacity: 0, y: 12 },
                  animate: { opacity: 1, y: 0 },
                  exit: { opacity: 0, scale: 0.97 },
                  transition: {
                      duration: 0.22,
                      ease: EASE_OUT,
                      delay: Math.min(index, 6) * 0.04,
                  },
              };

    return (
        <PageShell tier="wide">
            <Rise>
                <PageHeader
                    title={t('pageTitle')}
                    description={t('pageSubtitle')}
                    actions={
                        <LogIntroDialog
                            contacts={contacts}
                            onRecord={logManual}
                            trigger={
                                <Button variant="outline">
                                    <PlusIcon className="size-4" />
                                    {t('logIntro')}
                                </Button>
                            }
                        />
                    }
                />
            </Rise>

            <Rise delay={0.06}>
                <IntroStats
                    opportunities={suggestions.length}
                    strong={strongCount}
                    made={madeTotal}
                    queueUnavailable={suggestionsFailed}
                    lineageUnavailable={lineageFailed}
                />
            </Rise>

            <Rise delay={0.12}>
                <section className="space-y-3">
                <SectionHeading
                    title={t('toMake')}
                    count={suggestionsFailed ? null : suggestions.length}
                    asOf={suggestionsFailed ? null : asOf}
                />
                {suggestionsFailed ? (
                    <StatePanel
                        icon={<ExclamationTriangleIcon className="size-6 text-destructive" aria-hidden />}
                        title={t('toMakeErrorTitle')}
                        hint={t('toMakeErrorHint')}
                        alert
                        action={
                            <>
                                <Button
                                    variant="outline"
                                    size="sm"
                                    className="mt-2"
                                    aria-disabled={retrying}
                                    onClick={retry}
                                >
                                    <ArrowPathIcon className={cn('size-4', retrying && 'animate-spin')} aria-hidden />
                                    {t('retry')}
                                </Button>
                                <span aria-live="polite" className="sr-only">
                                    {retryStatus}
                                </span>
                            </>
                        }
                    />
                ) : suggestions.length === 0 ? (
                    <StatePanel
                        icon={<ArrowsRightLeftIcon className="size-6 text-muted-foreground" aria-hidden />}
                        title={suggestionsEmptyCopy.title}
                        hint={suggestionsEmptyCopy.hint}
                        dashed
                    />
                ) : (
                    <motion.div layout={!reduce} role="list" className="space-y-3">
                        <AnimatePresence mode="popLayout" initial={false}>
                            {suggestions.map((suggestion, index) => (
                                <motion.div
                                    key={pairKey(suggestion.personAId, suggestion.personBId)}
                                    role="listitem"
                                    layout={!reduce}
                                    {...enter(index)}
                                >
                                    {index === 0 ? (
                                        <IntroSuggestionCard
                                            suggestion={suggestion}
                                            onRecord={() => void record(suggestion)}
                                            onDismiss={() => void dismiss(suggestion)}
                                        />
                                    ) : (
                                        <IntroSuggestionRow
                                            suggestion={suggestion}
                                            onRecord={() => void record(suggestion)}
                                            onDismiss={() => void dismiss(suggestion)}
                                        />
                                    )}
                                </motion.div>
                            ))}
                        </AnimatePresence>
                    </motion.div>
                )}
                </section>
            </Rise>

            <Rise delay={0.18}>
                <section className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <SectionHeading
                            title={t('pathsTitle')}
                            count={pathsFailed ? null : visiblePaths.length}
                            asOf={pathsFailed ? null : asOf}
                        />
                        {!pathsFailed && paths.length > 0 ? (
                            <div role="group" aria-label={t('pathsFilterLabel')} className="flex items-center gap-1">
                                {(['all', 'rewarm', 'reach'] as const).map((filter) => (
                                    <button
                                        key={filter}
                                        type="button"
                                        aria-pressed={pathFilter === filter}
                                        onClick={() => setPathFilter(filter)}
                                        className={cn(
                                            'rounded-full border px-2.5 py-1 text-xs font-medium transition-colors',
                                            pathFilter === filter
                                                ? 'border-foreground/20 bg-muted text-foreground'
                                                : 'border-transparent text-muted-foreground hover:text-foreground',
                                        )}
                                    >
                                        {filter === 'all'
                                            ? t('pathsFilterAll')
                                            : filter === 'rewarm'
                                              ? t('reachRewarm')
                                              : t('reachNew')}
                                    </button>
                                ))}
                            </div>
                        ) : null}
                    </div>
                    {pathsFailed ? (
                        <StatePanel
                            icon={<ExclamationTriangleIcon className="size-6 text-destructive" aria-hidden />}
                            title={t('pathsErrorTitle')}
                            hint={t('pathsErrorHint')}
                            alert
                        />
                    ) : visiblePaths.length === 0 ? (
                        <StatePanel
                            icon={<MapIcon className="size-6 text-muted-foreground" aria-hidden />}
                            title={visiblePathsEmptyCopy.title}
                            hint={visiblePathsEmptyCopy.hint}
                            dashed
                        />
                    ) : (
                        <motion.div layout={!reduce} role="list" className="space-y-3">
                            <AnimatePresence mode="popLayout" initial={false}>
                                {visiblePaths.map((path, index) => (
                                    <motion.div
                                        key={path.targetId}
                                        role="listitem"
                                        layout={!reduce}
                                        {...enter(index)}
                                    >
                                        <WarmPathRow
                                            path={path}
                                            onAsk={(bridge) => askIntro(path, bridge)}
                                            onDismissAvenue={(bridge) => dismissAvenue(path, bridge)}
                                            onDismissTarget={() => dismissTarget(path)}
                                        />
                                    </motion.div>
                                ))}
                            </AnimatePresence>
                        </motion.div>
                    )}
                </section>
            </Rise>

            <Rise delay={0.24}>
                <section className="space-y-3">
                    <SectionHeading title={t('lineageTitle')} count={lineageFailed ? null : madeTotal} />
                    {lineageFailed ? (
                        <StatePanel
                            icon={<ExclamationTriangleIcon className="size-6 text-destructive" aria-hidden />}
                            title={t('lineageErrorTitle')}
                            hint={t('lineageErrorHint')}
                            alert
                        />
                    ) : (
                        <IntroLineageList items={lineage} />
                    )}
                </section>
            </Rise>
        </PageShell>
    );
}
