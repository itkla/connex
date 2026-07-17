'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowLongRightIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { warmthDotClass } from '@/app/lib/utils';
import type { TemperatureBand, WarmPath, WarmPathBridge } from '@/app/lib/types';

import IntroStrength from './IntroStrength';
import PartyAvatar from './PartyAvatar';

type PathTranslator = (key: string, values?: Record<string, string | number>) => string;

/** The labeled evidence tier behind a bridge, e.g. "Colleagues at Acme" or a dated overlap. */
export function evidenceLabel(bridge: WarmPathBridge, t: PathTranslator): string {
    if (bridge.evidenceType === 'connection') {
        return t('evidenceConnection');
    }
    const company = bridge.evidenceCompany ?? '';
    if (bridge.evidenceType === 'colleagues') {
        return company ? t('evidenceColleagues', { company }) : t('evidenceColleaguesUnnamed');
    }
    if (bridge.overlapStartYear && bridge.overlapEndYear) {
        return t('evidenceFormerYears', {
            company,
            start: bridge.overlapStartYear,
            end: bridge.overlapEndYear,
        });
    }
    return company ? t('evidenceFormer', { company }) : t('evidenceFormerUnnamed');
}

function Party({
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
 * One warm introduction path: the bridge contact the team is warm with, an arrow toward the
 * target worth reaching, the labeled evidence tier connecting them, and always-visible
 * ask/dismiss actions. Mirrors {@link IntroSuggestionRow}; the arrow is directional because the
 * path is — the bridge introduces you to the target.
 */
export default function WarmPathRow({
    path,
    onAsk,
    onDismiss,
}: {
    path: WarmPath;
    onAsk: () => void;
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

    const bridge = path.bridges[0];
    const morePaths = path.bridges.length - 1;

    return (
        <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card px-4 py-3.5 transition-colors hover:border-foreground/15 sm:flex-row sm:items-center sm:gap-4 sm:px-5">
            <IntroStrength score={path.score} className="shrink-0" />

            <div className="flex min-w-0 flex-1 items-center gap-3">
                <Party
                    id={bridge.personId}
                    name={bridge.name}
                    company={bridge.company}
                    imageUrl={bridge.imageUrl}
                    warmth={bridge.warmth}
                    warmthLabel={bridge.warmth ? tw(bridge.warmth) : ''}
                />
                <ArrowLongRightIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                <Party
                    id={path.targetId}
                    name={path.targetName}
                    company={path.targetCompany}
                    imageUrl={path.targetImageUrl}
                    warmth={path.targetWarmth}
                    warmthLabel={path.targetWarmth ? tw(path.targetWarmth) : ''}
                />
                <span className="hidden shrink-0 rounded-full border border-border px-2 py-0.5 text-[11px] font-medium text-muted-foreground lg:inline">
                    {path.reachType === 'rewarm' ? t('reachRewarm') : t('reachNew')}
                </span>
            </div>

            <span className="hidden max-w-[15rem] shrink-0 text-xs text-muted-foreground md:block">
                <span className="block truncate">{evidenceLabel(bridge, t)}</span>
                {morePaths > 0 ? (
                    <span className="block truncate">{t('morePaths', { count: morePaths })}</span>
                ) : null}
            </span>

            <div className="flex shrink-0 items-center gap-2">
                <Button variant="ghost" size="sm" onClick={() => act(onDismiss)} disabled={acted}>
                    {t('dismiss')}
                </Button>
                <Button size="sm" onClick={() => act(onAsk)} disabled={acted}>
                    {t('askIntro')}
                </Button>
            </div>
        </div>
    );
}
