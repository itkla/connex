'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowsRightLeftIcon } from '@heroicons/react/24/outline';

import type { IntroSuggestion } from '@/app/lib/types';
import PartyAvatar from '@/app/components/introductions/PartyAvatar';

type Translator = (key: string, values?: Record<string, string | number>) => string;

function reasonLabel(suggestion: IntroSuggestion, t: Translator): string {
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

/**
 * Dashboard widget: the top reverse-introduction opportunities, linking through to the full
 * Introductions page where they can be recorded or dismissed.
 */
export default function IntroOpportunities({ items }: { items: IntroSuggestion[] }) {
    const t = useTranslations('Introductions');

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {items.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('dashboardEmpty')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {items.map((suggestion) => (
                        <li key={`${suggestion.personAId}-${suggestion.personBId}`}>
                            <Link
                                href="/overview/introductions"
                                className="flex items-center gap-2.5 px-4 py-2.5 transition-colors hover:bg-muted/50"
                            >
                                <PartyAvatar imageUrl={suggestion.personAImageUrl} />
                                <span className="truncate text-sm font-medium text-foreground">
                                    {suggestion.personAName}
                                </span>
                                <ArrowsRightLeftIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                                <PartyAvatar imageUrl={suggestion.personBImageUrl} />
                                <span className="truncate text-sm font-medium text-foreground">
                                    {suggestion.personBName}
                                </span>
                                <span className="ml-auto hidden shrink-0 truncate pl-2 text-xs text-muted-foreground sm:block">
                                    {reasonLabel(suggestion, t)}
                                </span>
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
