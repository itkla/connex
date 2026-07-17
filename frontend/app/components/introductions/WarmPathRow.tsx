'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowLongRightIcon, ChevronDownIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { warmthDotClass } from '@/app/lib/utils';
import type { TemperatureBand, WarmPath, WarmPathBridge } from '@/app/lib/types';

import IntroStrength from './IntroStrength';
import PartyAvatar from './PartyAvatar';

type PathTranslator = (key: string, values?: Record<string, string | number>) => string;

/** The labeled evidence tier behind a bridge, e.g. "Colleagues at Acme" or a dated overlap. */
function evidenceLabel(bridge: WarmPathBridge, t: PathTranslator): string {
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
 * One warm introduction path: the selected bridge contact, an arrow toward the target worth
 * reaching, the labeled evidence tier connecting them, and always-visible ask/dismiss actions.
 * When several bridges exist, a switcher chip cycles the active avenue — evidence and both
 * actions follow the selection — and Dismiss offers "not via this bridge" (per avenue) versus
 * "not interested in this contact" (whole target). Mirrors {@link IntroSuggestionRow}.
 */
export default function WarmPathRow({
    path,
    onAsk,
    onDismissAvenue,
    onDismissTarget,
}: {
    path: WarmPath;
    onAsk: (bridge: WarmPathBridge) => Promise<void>;
    onDismissAvenue: (bridge: WarmPathBridge) => Promise<void>;
    onDismissTarget: () => Promise<void>;
}) {
    const t = useTranslations('Introductions');
    const tw = useTranslations('Temperature');
    const [busy, setBusy] = useState(false);
    const [bridgeIndex, setBridgeIndex] = useState(0);

    const bridge = path.bridges[Math.min(bridgeIndex, path.bridges.length - 1)];
    const otherBridges = path.bridges.length - 1;

    const run = (action: () => Promise<void>) => {
        if (busy) return;
        setBusy(true);
        action()
            .catch(() => undefined)
            .finally(() => setBusy(false));
    };

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
                {otherBridges > 0 ? (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                aria-label={t('chooseBridgeAria', { count: path.bridges.length })}
                                className="flex shrink-0 items-center gap-1 rounded-full border border-border px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:border-foreground/20 hover:text-foreground aria-expanded:border-foreground/20 aria-expanded:text-foreground"
                            >
                                {t('morePaths', { count: otherBridges })}
                                <ChevronDownIcon className="size-3" aria-hidden />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" className="w-64">
                            {path.bridges.map((candidate, index) => (
                                <DropdownMenuItem
                                    key={candidate.personId}
                                    onClick={() => setBridgeIndex(index)}
                                    className={cn(index === bridgeIndex && 'bg-muted')}
                                >
                                    <span className="min-w-0">
                                        <span className="block truncate text-sm font-medium text-foreground">
                                            {candidate.name}
                                        </span>
                                        <span className="block truncate text-xs text-muted-foreground">
                                            {evidenceLabel(candidate, t)}
                                        </span>
                                    </span>
                                </DropdownMenuItem>
                            ))}
                        </DropdownMenuContent>
                    </DropdownMenu>
                ) : null}
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

            <span className="hidden max-w-[15rem] shrink-0 truncate text-xs text-muted-foreground md:block">
                {evidenceLabel(bridge, t)}
            </span>

            <div className="flex shrink-0 items-center gap-2">
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="sm" disabled={busy}>
                            {t('dismiss')}
                            <ChevronDownIcon className="size-3" aria-hidden />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-60">
                        <DropdownMenuItem onClick={() => run(() => onDismissAvenue(bridge))}>
                            {t('dismissNotVia', { name: bridge.name })}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => run(onDismissTarget)}>
                            {t('dismissTargetItem', { name: path.targetName })}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
                <Button size="sm" onClick={() => run(() => onAsk(bridge))} disabled={busy}>
                    {t('askIntro')}
                </Button>
            </div>
        </div>
    );
}
