'use client';

import { useEffect, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowPathIcon, LightBulbIcon } from '@heroicons/react/24/outline';

import { getDealRationale } from '@/app/lib/api';
import type { DealRationale } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

type RationaleState =
    | { status: 'loading' }
    | { status: 'hidden' }
    | { status: 'error' }
    | { status: 'ready'; rationale: DealRationale };

/**
 * AI-generated "before you act" narrative for an at-risk deal — the presentation-only companion of
 * {@link DealRiskPanel}, which stays the source of truth. Fetches on mount: deals that aren't at risk,
 * or organizations without a configured BYOP provider, get a fast unavailability response and the panel
 * renders nothing, so it only appears when there is a risk to explain. Generation is a slow masked LLM
 * call (server-cached), so arrival is a calm fade; a provider failure offers a quiet retry and never
 * blocks the deterministic signals shown above.
 */
export default function DealRationalePanel({ dealId }: { dealId: number }) {
    const t = useTranslations('DealRationale');
    const locale = useLocale();
    const [state, setState] = useState<RationaleState>({ status: 'loading' });

    const [reloadKey, setReloadKey] = useState(0);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const rationale = await getDealRationale(dealId);
                if (cancelled) return;
                if (rationale.available && rationale.rationale) {
                    setState({ status: 'ready', rationale });
                } else if (rationale.reason === 'provider_error') {
                    setState({ status: 'error' });
                } else {
                    setState({ status: 'hidden' });
                }
            } catch {
                if (!cancelled) setState({ status: 'error' });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [dealId, reloadKey]);

    const retry = () => {
        setState({ status: 'loading' });
        setReloadKey((key) => key + 1);
    };

    if (state.status === 'hidden') return null;

    return (
        <section aria-label={t('panelTitle')} className="mt-8 grid gap-3">
            <h2 className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                <LightBulbIcon className="size-3.5" aria-hidden />
                {t('panelTitle')}
            </h2>
            {state.status === 'loading' ? (
                <div className="grid gap-2 rounded-lg border px-4 py-3" aria-busy>
                    <Skeleton className="h-3.5 w-full" />
                    <Skeleton className="h-3.5 w-11/12" />
                    <Skeleton className="h-3.5 w-4/5" />
                    <Skeleton className="h-3.5 w-2/3" />
                </div>
            ) : state.status === 'error' ? (
                <div className="flex items-center justify-between gap-3 rounded-lg border px-4 py-3">
                    <p className="text-sm text-muted-foreground">{t('error')}</p>
                    <Button variant="ghost" size="sm" onClick={retry} className="shrink-0">
                        <ArrowPathIcon className="size-4" aria-hidden />
                        {t('retry')}
                    </Button>
                </div>
            ) : (
                <div className="grid gap-3 rounded-lg border px-4 py-3 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-1 motion-safe:duration-300">
                    <div className="max-w-[70ch] whitespace-pre-wrap text-sm leading-relaxed text-foreground">
                        {state.rationale.rationale}
                    </div>
                    <p className="text-xs text-muted-foreground">
                        {t('attribution', {
                            time: state.rationale.generatedAt
                                ? new Intl.DateTimeFormat(locale, { timeStyle: 'short' }).format(
                                      new Date(state.rationale.generatedAt),
                                  )
                                : '',
                        })}
                        {state.rationale.warnings > 0 ? <> · {t('integrityWarning')}</> : null}
                    </p>
                </div>
            )}
        </section>
    );
}
