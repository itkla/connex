'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowsRightLeftIcon, BuildingOffice2Icon, UsersIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { IntroSuggestion, TemperatureBand } from '@/app/lib/types';

import PartyAvatar from './PartyAvatar';
import WarmthBadge from './WarmthBadge';

function Party({
    id,
    name,
    title,
    company,
    imageUrl,
    warmth,
}: {
    id: number;
    name: string;
    title?: string | null;
    company?: string | null;
    imageUrl?: string | null;
    warmth?: TemperatureBand | null;
}) {
    const subtitle = [title, company].filter(Boolean).join(' · ');
    return (
        <div className="flex items-center gap-3">
            <PartyAvatar imageUrl={imageUrl} />
            <div className="min-w-0 flex-1">
                <Link
                    href={`/records/contacts/${id}`}
                    className="block truncate text-sm font-medium text-foreground transition-colors hover:text-brand-hover"
                >
                    {name}
                </Link>
                <p className="truncate text-xs text-muted-foreground">{subtitle || ' '}</p>
            </div>
            <WarmthBadge band={warmth} />
        </div>
    );
}

/**
 * A suggested reverse introduction (issue #43): the two contacts to connect, why, and the actions to
 * record the intro or dismiss the suggestion. The dual-party stack with a connective glyph reads
 * top-to-bottom as "introduce A to B".
 */
export default function IntroSuggestionCard({
    suggestion,
    onRecord,
    onDismiss,
}: {
    suggestion: IntroSuggestion;
    onRecord: () => void;
    onDismiss: () => void;
}) {
    const t = useTranslations('Introductions');
    const [acted, setActed] = useState(false);

    const act = (fn: () => void) => {
        if (acted) return;
        setActed(true);
        fn();
    };

    return (
        <article className="flex flex-col rounded-2xl border border-border bg-card p-4 transition-colors hover:border-foreground/15 sm:p-5">
            <div className="flex flex-col gap-3">
                <Party
                    id={suggestion.personAId}
                    name={suggestion.personAName}
                    title={suggestion.personATitle}
                    company={suggestion.personACompany}
                    imageUrl={suggestion.personAImageUrl}
                    warmth={suggestion.personAWarmth}
                />
                <div className="flex items-center gap-3" aria-hidden>
                    <span className="grid size-8 place-items-center rounded-full border border-border bg-muted/50 text-muted-foreground">
                        <ArrowsRightLeftIcon className="size-4" />
                    </span>
                    <span className="h-px flex-1 bg-border" />
                </div>
                <Party
                    id={suggestion.personBId}
                    name={suggestion.personBName}
                    title={suggestion.personBTitle}
                    company={suggestion.personBCompany}
                    imageUrl={suggestion.personBImageUrl}
                    warmth={suggestion.personBWarmth}
                />
            </div>

            <div className="mt-4 flex flex-wrap gap-1.5">
                {suggestion.reasons.includes('mutual_connections') && suggestion.mutualConnections > 0 ? (
                    <ReasonChip icon={<UsersIcon className="size-3.5" />}>
                        {t('reasonMutual', { count: suggestion.mutualConnections })}
                    </ReasonChip>
                ) : null}
                {suggestion.reasons.includes('shared_company') ? (
                    <ReasonChip icon={<BuildingOffice2Icon className="size-3.5" />}>
                        {suggestion.sharedCompany
                            ? t('reasonSharedCompanyNamed', { company: suggestion.sharedCompany })
                            : t('reasonSharedCompany')}
                    </ReasonChip>
                ) : null}
            </div>

            <div className="mt-4 flex items-center justify-end gap-2">
                <Button variant="ghost" size="sm" onClick={() => act(onDismiss)} disabled={acted}>
                    {t('dismiss')}
                </Button>
                <Button size="sm" onClick={() => act(onRecord)} disabled={acted}>
                    {t('record')}
                </Button>
            </div>
        </article>
    );
}

function ReasonChip({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
    return (
        <span className={cn(
            'inline-flex items-center gap-1.5 rounded-full bg-muted px-2.5 py-1 text-xs text-muted-foreground',
        )}>
            <span className="shrink-0 text-muted-foreground/80">{icon}</span>
            {children}
        </span>
    );
}
