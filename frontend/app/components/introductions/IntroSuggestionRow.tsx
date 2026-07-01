'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowsRightLeftIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { warmthDotClass } from '@/app/lib/utils';
import type { IntroSuggestion, TemperatureBand } from '@/app/lib/types';

import IntroStrength from './IntroStrength';
import PartyAvatar from './PartyAvatar';

type ReasonTranslator = (key: string, values?: Record<string, string | number>) => string;

function reasonLabel(suggestion: IntroSuggestion, t: ReasonTranslator): string {
    if (suggestion.reasons.includes('shared_company') && suggestion.sharedCompany) {
        return suggestion.sharedCompany;
    }
    if (suggestion.reasons.includes('mutual_connections') && suggestion.mutualConnections > 0) {
        return t('reasonMutual', { count: suggestion.mutualConnections });
    }
    if (suggestion.reasons.includes('shared_company')) {
        return t('reasonSharedCompany');
    }
    return '';
}

function RowParty({
    id,
    name,
    company,
    imageUrl,
    warmth,
    warmthLabel,
}: {
    id: number;
    name: string;
    company?: string | null;
    imageUrl?: string | null;
    warmth?: TemperatureBand | null;
    warmthLabel: string;
}) {
    return (
        <span className="flex min-w-0 items-center gap-2.5">
            <PartyAvatar imageUrl={imageUrl} />
            <span className="min-w-0">
                <span className="flex items-center gap-1.5">
                    <Link
                        href={`/records/contacts/${id}`}
                        className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                    >
                        {name}
                    </Link>
                    {warmth ? (
                        <span
                            className={cn('size-2 shrink-0 rounded-full', warmthDotClass(warmth))}
                            title={warmthLabel}
                            aria-label={warmthLabel}
                            role="img"
                        />
                    ) : null}
                </span>
                {company ? (
                    <span className="block truncate text-xs text-muted-foreground">{company}</span>
                ) : null}
            </span>
        </span>
    );
}

/**
 * A compact ranked reverse-introduction suggestion, rendered as a standalone card beneath the
 * featured lead: strength signal, the pair to connect with their companies, the leading reason, and
 * always-visible record/dismiss actions so the page's core action is never hidden.
 */
export default function IntroSuggestionRow({
    suggestion,
    onRecord,
    onDismiss,
}: {
    suggestion: IntroSuggestion;
    onRecord: () => void;
    onDismiss: () => void;
}) {
    const t = useTranslations('Introductions');
    const tw = useTranslations('Temperature');
    const [acted, setActed] = useState(false);

    const act = (fn: () => void) => {
        if (acted) return;
        setActed(true);
        fn();
    };

    const reason = reasonLabel(suggestion, t);

    return (
        <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card px-4 py-3.5 transition-colors hover:border-foreground/15 sm:flex-row sm:items-center sm:gap-4 sm:px-5">
            <IntroStrength score={suggestion.score} className="shrink-0" />

            <div className="flex min-w-0 flex-1 items-center gap-3">
                <RowParty
                    id={suggestion.personAId}
                    name={suggestion.personAName}
                    company={suggestion.personACompany}
                    imageUrl={suggestion.personAImageUrl}
                    warmth={suggestion.personAWarmth}
                    warmthLabel={suggestion.personAWarmth ? tw(suggestion.personAWarmth) : ''}
                />
                <ArrowsRightLeftIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                <RowParty
                    id={suggestion.personBId}
                    name={suggestion.personBName}
                    company={suggestion.personBCompany}
                    imageUrl={suggestion.personBImageUrl}
                    warmth={suggestion.personBWarmth}
                    warmthLabel={suggestion.personBWarmth ? tw(suggestion.personBWarmth) : ''}
                />
            </div>

            {reason ? (
                <span className="hidden max-w-[13rem] shrink-0 truncate text-xs text-muted-foreground md:block">
                    {reason}
                </span>
            ) : null}

            <div className="flex shrink-0 items-center gap-2">
                <Button variant="ghost" size="sm" onClick={() => act(onDismiss)} disabled={acted}>
                    {t('dismiss')}
                </Button>
                <Button size="sm" onClick={() => act(onRecord)} disabled={acted}>
                    {t('record')}
                </Button>
            </div>
        </div>
    );
}
