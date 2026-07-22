'use client';

import { useEffect, useRef, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowPathIcon, SparklesIcon } from '@heroicons/react/24/outline';

import { generateDealBrief } from '@/app/lib/api';
import { recoverAiResult } from '@/app/lib/aiRecovery';
import type { DealBrief } from '@/app/lib/types';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

type BriefState =
    | { status: 'loading' }
    | { status: 'hidden' }
    | { status: 'error' }
    | { status: 'ready'; brief: DealBrief };

type DealBriefState = BriefState & { dealId: number };

/**
 * AI-generated "before you call" brief on the deal-detail page — the sibling of
 * {@link DealRiskPanel}. Fetches on mount: organizations without a configured BYOP provider get a
 * fast unavailability response and the panel renders nothing, so the page stays clean while AI is
 * off by default. Generation is slow (a masked LLM call, server-cached), so arrival is a calm
 * fade; a provider failure offers a quiet retry and never blocks the deterministic signals.
 */
export default function DealBriefPanel({ dealId, className }: { dealId: number; className?: string }) {
    const t = useTranslations('DealBrief');
    const locale = useLocale();
    const [storedState, setStoredState] = useState<DealBriefState>({ dealId, status: 'loading' });
    const state: BriefState = storedState.dealId === dealId ? storedState : { status: 'loading' };

    const [reloadKey, setReloadKey] = useState(0);
    const refreshNext = useRef(false);

    useEffect(() => {
        let cancelled = false;
        const refresh = refreshNext.current;
        refreshNext.current = false;
        (async () => {
            try {
                const brief = await generateDealBrief(dealId, refresh);
                if (cancelled) return;
                if (brief.available && ((brief.sections && brief.sections.length > 0) || brief.brief)) {
                    setStoredState({ dealId, status: 'ready', brief });
                } else if (brief.reason === 'provider_error') {
                    setStoredState({ dealId, status: 'error' });
                } else {
                    setStoredState({ dealId, status: 'hidden' });
                }
            } catch {
                if (cancelled) return;
                const recovered = await recoverAiResult(
                    () => generateDealBrief(dealId, false),
                    (brief) => brief.available && ((brief.sections?.length ?? 0) > 0 || Boolean(brief.brief)),
                    () => cancelled,
                );
                if (cancelled) return;
                setStoredState(recovered
                    ? { dealId, status: 'ready', brief: recovered }
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
                <SparklesIcon className="size-3.5" aria-hidden />
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
            ) : (
                <div className="flex flex-1 flex-col gap-3 rounded-lg border px-4 py-3 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-1 motion-safe:duration-300">
                    {state.brief.sections && state.brief.sections.length > 0 ? (
                        <div className="grid max-w-[70ch] flex-1 gap-4">
                            {state.brief.sections.map((section, index) => (
                                <div key={index} className="grid gap-1">
                                    <h3 className="text-sm font-medium text-foreground">{section.title}</h3>
                                    <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground">
                                        {section.body}
                                    </p>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="max-w-[70ch] flex-1 whitespace-pre-wrap text-sm leading-relaxed text-foreground">
                            {state.brief.brief}
                        </div>
                    )}
                    <div className="flex items-center justify-between gap-3">
                        <p className="text-xs text-muted-foreground">
                            {t('attribution', {
                                time: state.brief.generatedAt
                                    ? new Intl.DateTimeFormat(locale, { timeStyle: 'short' }).format(
                                          new Date(state.brief.generatedAt),
                                      )
                                    : '',
                            })}
                            {state.brief.warnings > 0 ? <> · {t('integrityWarning')}</> : null}
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
