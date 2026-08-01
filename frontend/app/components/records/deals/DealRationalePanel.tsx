'use client';

import { useEffect, useRef, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowPathIcon, LightBulbIcon } from '@heroicons/react/24/outline';

import { generateDealRationale } from '@/app/lib/api';
import { recoverAiResult } from '@/app/lib/aiRecovery';
import type { DealRationale, DealRiskFactorCode } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';

const FACTOR_CHIP_KEYS: Record<DealRiskFactorCode, string> = {
    close_overdue: 'factorChipCloseOverdue',
    closing_soon_quiet: 'factorChipClosingSoonQuiet',
    stalled: 'factorChipStalled',
    stakeholder_cold: 'factorChipStakeholderCold',
    no_stakeholders: 'factorChipNoStakeholders',
};

type RationaleState =
    | { status: 'loading' }
    | { status: 'hidden' }
    | { status: 'error' }
    | { status: 'rateLimited' }
    | { status: 'ready'; rationale: DealRationale };

type DealRationaleState = RationaleState & { dealId: number };

/**
 * AI-generated "before you act" narrative for an at-risk deal — the presentation-only companion of
 * {@link DealRiskPanel}, which stays the source of truth. Fetches on mount: deals that aren't at risk,
 * or organizations without a configured BYOP provider, get a fast unavailability response and the panel
 * renders nothing, so it only appears when there is a risk to explain. Generation is a slow masked LLM
 * call (server-cached), so arrival is a calm fade; a provider failure offers a quiet retry and never
 * blocks the deterministic signals shown above.
 */
export default function DealRationalePanel({ dealId, className }: { dealId: number; className?: string }) {
    const t = useTranslations('DealRationale');
    const locale = useLocale();
    const [storedState, setStoredState] = useState<DealRationaleState>({ dealId, status: 'loading' });
    const state: RationaleState = storedState.dealId === dealId ? storedState : { status: 'loading' };

    const [reloadKey, setReloadKey] = useState(0);
    const refreshNext = useRef(false);

    useEffect(() => {
        let cancelled = false;
        const refresh = refreshNext.current;
        refreshNext.current = false;
        (async () => {
            try {
                const rationale = await generateDealRationale(dealId, refresh);
                if (cancelled) return;
                if (rationale.available && (rationale.narrative || rationale.rationale)) {
                    setStoredState({ dealId, status: 'ready', rationale });
                } else if (rationale.reason === 'provider_error') {
                    setStoredState({ dealId, status: 'error' });
                } else if (rationale.reason === 'rate_limited') {
                    setStoredState({ dealId, status: 'rateLimited' });
                } else {
                    setStoredState({ dealId, status: 'hidden' });
                }
            } catch {
                if (cancelled) return;
                const recovered = await recoverAiResult(
                    () => generateDealRationale(dealId, false),
                    (rationale) => rationale.available && Boolean(rationale.narrative || rationale.rationale),
                    () => cancelled,
                );
                if (cancelled) return;
                setStoredState(recovered
                    ? { dealId, status: 'ready', rationale: recovered }
                    : { dealId, status: 'error' });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [dealId, reloadKey]);

    const retry = () => {
        setStoredState({ dealId, status: 'loading' });
        setReloadKey((key) => key + 1);
    };

    const regenerate = () => {
        refreshNext.current = true;
        setStoredState({ dealId, status: 'loading' });
        setReloadKey((key) => key + 1);
    };

    if (state.status === 'hidden') return null;

    return (
        <section aria-label={t('panelTitle')} className={cn('flex flex-col gap-3', className)}>
            <h2 className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                <LightBulbIcon className="size-3.5" aria-hidden />
                {t('panelTitle')}
            </h2>
            {state.status === 'loading' ? (
                <div className="grid flex-1 gap-2 rounded-lg border px-4 py-3" aria-busy>
                    <Skeleton className="h-3.5 w-full" />
                    <Skeleton className="h-3.5 w-11/12" />
                    <Skeleton className="h-3.5 w-4/5" />
                    <Skeleton className="h-3.5 w-2/3" />
                </div>
            ) : state.status === 'error' ? (
                <div className="flex flex-1 items-center justify-between gap-3 rounded-lg border px-4 py-3">
                    <p className="text-sm text-muted-foreground">{t('error')}</p>
                    <Button variant="ghost" size="sm" onClick={retry} className="shrink-0">
                        <ArrowPathIcon className="size-4" aria-hidden />
                        {t('retry')}
                    </Button>
                </div>
            ) : state.status === 'rateLimited' ? (
                <div className="flex flex-1 items-center justify-between gap-3 rounded-lg border px-4 py-3">
                    <p className="text-sm text-muted-foreground">{t('rateLimited')}</p>
                    <Button variant="ghost" size="sm" onClick={retry} className="shrink-0">
                        <ArrowPathIcon className="size-4" aria-hidden />
                        {t('retry')}
                    </Button>
                </div>
            ) : (
                <div className="flex flex-1 flex-col gap-3 rounded-lg border px-4 py-3 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-1 motion-safe:duration-300">
                    <div className="grid max-w-[70ch] flex-1 gap-3">
                        <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground">
                            {state.rationale.narrative ?? state.rationale.rationale}
                        </p>
                        {state.rationale.narrativeFactorCodes
                        && state.rationale.narrativeFactorCodes.length > 0 ? (
                            <div className="flex flex-wrap gap-1">
                                {state.rationale.narrativeFactorCodes.map((code) => (
                                    <Badge
                                        key={code}
                                        variant="outline"
                                        className="font-normal text-muted-foreground"
                                    >
                                        {t(FACTOR_CHIP_KEYS[code])}
                                    </Badge>
                                ))}
                            </div>
                        ) : null}
                        {state.rationale.recommendedActions
                        && state.rationale.recommendedActions.length > 0 ? (
                            <ul className="grid gap-2">
                                {state.rationale.recommendedActions.map((action, index) => (
                                    <li
                                        key={index}
                                        className="grid gap-1 text-sm leading-relaxed text-muted-foreground"
                                    >
                                        <div className="flex gap-2">
                                            <span
                                                aria-hidden
                                                className="mt-[0.5rem] size-1 shrink-0 rounded-full bg-muted-foreground/50"
                                            />
                                            <span>{action.text}</span>
                                        </div>
                                        {action.factorCodes.length > 0 ? (
                                            <div className="flex flex-wrap gap-1 pl-4">
                                                {action.factorCodes.map((code) => (
                                                    <Badge
                                                        key={code}
                                                        variant="outline"
                                                        className="font-normal text-muted-foreground"
                                                    >
                                                        {t(FACTOR_CHIP_KEYS[code])}
                                                    </Badge>
                                                ))}
                                            </div>
                                        ) : null}
                                    </li>
                                ))}
                            </ul>
                        ) : state.rationale.actions && state.rationale.actions.length > 0 ? (
                            <ul className="grid gap-1.5">
                                {state.rationale.actions.map((action, index) => (
                                    <li
                                        key={index}
                                        className="flex gap-2 text-sm leading-relaxed text-muted-foreground"
                                    >
                                        <span
                                            aria-hidden
                                            className="mt-[0.5rem] size-1 shrink-0 rounded-full bg-muted-foreground/50"
                                        />
                                        <span>{action}</span>
                                    </li>
                                ))}
                            </ul>
                        ) : null}
                    </div>
                    <div className="flex items-center justify-between gap-3">
                        <p className="text-xs text-muted-foreground">
                            {t('attribution', {
                                time: formatDateTime(state.rationale.generatedAt ?? undefined, locale),
                            })}
                            {state.rationale.warnings > 0 ? <> · {t('integrityWarning')}</> : null}
                        </p>
                        <Button
                            variant="ghost"
                            size="sm"
                            onClick={regenerate}
                            className="shrink-0 text-muted-foreground"
                        >
                            <ArrowPathIcon className="size-4" aria-hidden />
                            {t('regenerate')}
                        </Button>
                    </div>
                </div>
            )}
        </section>
    );
}
