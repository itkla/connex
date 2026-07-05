'use client';

import { useEffect, useMemo, useRef, useState, useTransition, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ArrowsRightLeftIcon, ExclamationTriangleIcon, PlusIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { dismissIntroSuggestion, recordIntroduction } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Contact, IntroSuggestion, IntroductionRecord } from '@/app/lib/types';

import Rise from '@/app/components/motion/Rise';
import IntroLineageList from './IntroLineageList';
import IntroStats from './IntroStats';
import { tierFor } from './IntroStrength';
import IntroSuggestionCard from './IntroSuggestionCard';
import IntroSuggestionRow from './IntroSuggestionRow';
import LogIntroDialog from './LogIntroDialog';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function pairKey(personAId: number, personBId: number): string {
    return `${Math.min(personAId, personBId)}-${Math.max(personAId, personBId)}`;
}

function SectionHeading({ title, count }: { title: string; count: number | null }) {
    const t = useTranslations('Introductions');
    return (
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
 * When a fetch failed ({@code suggestionsFailed} / {@code lineageFailed}), the affected section
 * renders a distinct error state instead of masquerading as an empty workspace, and the
 * queue-derived counts show an unavailable marker instead of claiming zero.
 */
export default function IntroductionsBoard({
    initialSuggestions,
    suggestionsFailed = false,
    initialLineage,
    initialLineageTotal,
    lineageFailed = false,
    contacts,
}: {
    initialSuggestions: IntroSuggestion[];
    suggestionsFailed?: boolean;
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
        <div className="mx-auto w-full max-w-7xl space-y-6 px-2 pb-12">
            <Rise>
                <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                    <div>
                        <h1 className="text-3xl font-extrabold tracking-tight text-foreground md:text-4xl">
                            {t('pageTitle')}
                        </h1>
                        <p className="mt-1.5 max-w-2xl text-sm text-muted-foreground">{t('pageSubtitle')}</p>
                    </div>
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
                </header>
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
                <SectionHeading title={t('toMake')} count={suggestionsFailed ? null : suggestions.length} />
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
                        title={t('toMakeEmptyTitle')}
                        hint={t('toMakeEmptyHint')}
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
        </div>
    );
}
