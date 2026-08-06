'use client';

import { useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';

import RelationshipEvidencePanel from '@/app/components/records/RelationshipEvidencePanel';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import { useLiveNow } from '@/app/hooks/useNow';
import type { RelationshipEvidence } from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card';
import { cn } from '@/lib/utils';

/**
 * Contact-detail entry point that reuses {@link TemperaturePill}: hover shows a short warmth
 * summary, click opens Relationship Evidence in a dialog instead of embedding it on the page.
 */
export default function ContactTemperatureChip({
    evidence,
}: {
    evidence: RelationshipEvidence;
}) {
    const tTemp = useTranslations('Temperature');
    const tEvidence = useTranslations('RelationshipEvidence');
    const locale = useLocale();
    const now = useLiveNow();
    const reduceMotion = useReducedMotion();
    const [dialogOpen, setDialogOpen] = useState(false);

    const temperature = evidence.temperature;
    const hasHistory = Boolean(temperature.lastTouchAt);
    const lastTouch = temperature.lastTouchAt
        ? formatRelativeTime(temperature.lastTouchAt, locale, now)
        : null;
    const bandLabel = hasHistory ? tTemp(temperature.band) : tTemp('noHistory');

    return (
        <>
            <HoverCard>
                <HoverCardTrigger
                    render={
                        <button
                            type="button"
                            onClick={() => setDialogOpen(true)}
                            aria-haspopup="dialog"
                            aria-expanded={dialogOpen}
                            aria-label={tEvidence('openEvidenceNamed', { band: bandLabel })}
                            className={cn(
                                'rounded-full transition-[transform,box-shadow] duration-150 ease-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40',
                                !reduceMotion && 'active:scale-[0.97]',
                            )}
                        >
                            <TemperaturePill temp={temperature} withTooltip={false} />
                        </button>
                    }
                />
                <HoverCardContent className="w-64">
                    <div className="flex flex-col gap-1.5">
                        <p className="text-sm font-medium text-foreground">
                            {!hasHistory
                                ? tTemp('noHistory')
                                : tTemp('tooltip', {
                                      score: temperature.score,
                                      lastTouch: lastTouch ?? '',
                                  })}
                        </p>
                        {hasHistory
                            && temperature.daysUntilCold != null
                            && temperature.goesColdAt ? (
                            <p className="text-xs text-muted-foreground">
                                {tTemp('goesCold', {
                                    when: formatRelativeTime(temperature.goesColdAt, locale, now),
                                })}
                            </p>
                        ) : null}
                        {!hasHistory ? (
                            <p className="text-xs text-muted-foreground">{tTemp('noHistoryTooltip')}</p>
                        ) : null}
                        <p className="text-xs text-muted-foreground">{tEvidence('chipHint')}</p>
                    </div>
                </HoverCardContent>
            </HoverCard>

            <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
                <DialogContent
                    size="xl"
                    className="max-h-[min(90vh,44rem)] gap-4 overflow-hidden p-0 sm:max-w-2xl"
                >
                    <DialogHeader className="px-6 pt-6 pr-14">
                        <DialogTitle>{tEvidence('title')}</DialogTitle>
                        <DialogDescription>{tEvidence('subtitle')}</DialogDescription>
                    </DialogHeader>
                    <div className="max-h-[min(70vh,34rem)] overflow-y-auto px-6 pb-6">
                        <RelationshipEvidencePanel
                            evidence={evidence}
                            className="mt-0"
                            hideChromeHeader
                        />
                    </div>
                </DialogContent>
            </Dialog>
        </>
    );
}
