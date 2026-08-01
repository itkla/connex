'use client';

import { useEffect, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { LightBulbIcon } from '@heroicons/react/24/outline';

import { generateIntroRationale } from '@/app/lib/api';
import type { IntroRationale } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';

type RationaleState =
    | { status: 'loading' }
    | { status: 'hidden' }
    | { status: 'rateLimited' }
    | { status: 'ready'; rationale: IntroRationale };

type IntroRationaleState = RationaleState & { pairKey: string };

/**
 * AI-generated one-line "why introduce them" rationale for the lead reverse-introduction suggestion —
 * the presentation-only companion of the deterministic reason chips, which stay the source of truth
 * and the fallback. Fetches on mount for the pair; pairs that aren't a current suggestion or
 * organizations without a configured BYOP provider get a fast unavailability response and this line
 * renders nothing, so the chips below still explain the match. Generation is a slow masked LLM call
 * (server-cached), so arrival is a calm fade; a failure never blocks the deterministic signals.
 */
export default function IntroRationaleLine({
    personAId,
    personBId,
}: {
    personAId: number;
    personBId: number;
}) {
    const t = useTranslations('IntroRationale');
    const locale = useLocale();
    const pairKey = `${Math.min(personAId, personBId)}:${Math.max(personAId, personBId)}`;
    const [storedState, setStoredState] = useState<IntroRationaleState>({ pairKey, status: 'loading' });
    const state: RationaleState = storedState.pairKey === pairKey ? storedState : { status: 'loading' };

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const rationale = await generateIntroRationale(personAId, personBId);
                if (cancelled) return;
                if (rationale.available && rationale.rationale) {
                    setStoredState({ pairKey, status: 'ready', rationale });
                } else if (rationale.reason === 'rate_limited') {
                    setStoredState({ pairKey, status: 'rateLimited' });
                } else {
                    setStoredState({ pairKey, status: 'hidden' });
                }
            } catch {
                if (!cancelled) setStoredState({ pairKey, status: 'hidden' });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [pairKey, personAId, personBId]);

    if (state.status === 'hidden') return null;

    if (state.status === 'loading') {
        return <Skeleton className="mt-4 h-4 w-3/4" aria-busy />;
    }

    if (state.status === 'rateLimited') {
        return (
            <p className="mt-4 flex max-w-[70ch] items-start gap-1.5 text-sm leading-relaxed text-muted-foreground">
                <LightBulbIcon className="mt-0.5 size-3.5 shrink-0" aria-label={t('label')} />
                <span>{t('rateLimited')}</span>
            </p>
        );
    }

    return (
        <p className="mt-4 flex max-w-[70ch] items-start gap-1.5 text-sm leading-relaxed text-muted-foreground motion-safe:animate-in motion-safe:fade-in motion-safe:duration-300">
            <LightBulbIcon className="mt-0.5 size-3.5 shrink-0" aria-label={t('label')} />
            <span>
                {state.rationale.rationale}
                <span className="text-muted-foreground/70">
                    {' · '}
                    {t('attribution', {
                        time: formatDateTime(state.rationale.generatedAt ?? undefined, locale),
                    })}
                </span>
                {state.rationale.warnings > 0 ? (
                    <span className="text-muted-foreground/70"> · {t('integrityWarning')}</span>
                ) : null}
            </span>
        </p>
    );
}
