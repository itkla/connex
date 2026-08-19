'use client';

import { useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';

import RelationshipEvidencePanel from '@/app/components/records/RelationshipEvidencePanel';
import RelationshipEvidenceActions, {
    type RelationshipEvidenceActionContext,
} from '@/app/components/records/RelationshipEvidenceActions';
import { RecordActivityComposer, RecordTaskComposer } from '@/app/components/records/RecordComposers';
import WarmthPill from '@/app/components/records/WarmthPill';
import { useLiveNow } from '@/app/hooks/useNow';
import { followUpDueDate } from '@/app/lib/followUp';
import type { RelationshipEvidence } from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogTitle,
} from '@/components/ui/dialog';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card';
import { cn } from '@/lib/utils';

/**
 * Record-detail entry point that reuses {@link WarmthPill}: hover shows a short warmth
 * summary, click opens Relationship Evidence in a dialog that mirrors the page panel chrome.
 * Passing {@code actions} adds the footer's next steps for a contact record; a surface with no
 * contact anchor (a company) opens the same evidence without them.
 */
export default function WarmthEvidenceChip({
    evidence,
    actions,
}: {
    evidence: RelationshipEvidence;
    actions?: RelationshipEvidenceActionContext;
}) {
    const tTemp = useTranslations('Temperature');
    const tEvidence = useTranslations('RelationshipEvidence');
    const locale = useLocale();
    const now = useLiveNow();
    const reduceMotion = useReducedMotion();
    const [dialogOpen, setDialogOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);

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
                    delay={100}
                    closeDelay={100}
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
                            <WarmthPill temp={temperature} withTooltip={false} />
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
                    className="max-h-[min(90vh,44rem)] gap-0 overflow-hidden bg-card p-0 sm:max-w-2xl"
                >
                    <DialogTitle className="sr-only">{tEvidence('title')}</DialogTitle>
                    <DialogDescription className="sr-only">
                        {tEvidence('subtitle')}
                    </DialogDescription>
                    <div className="max-h-[min(90vh,44rem)] overflow-y-auto">
                        <RelationshipEvidencePanel
                            evidence={evidence}
                            variant="dialog"
                            actions={actions ? (
                                <RelationshipEvidenceActions
                                    context={actions}
                                    onLogInteraction={() => {
                                        setDialogOpen(false);
                                        setActivityOpen(true);
                                    }}
                                    onScheduleFollowUp={() => {
                                        setDialogOpen(false);
                                        setTaskOpen(true);
                                    }}
                                />
                            ) : undefined}
                        />
                    </div>
                </DialogContent>
            </Dialog>

            {actions ? (
                <>
                    <RecordActivityComposer
                        anchor={{
                            kind: 'person',
                            person: actions.contact,
                            companyId: actions.companyId,
                        }}
                        currentUserId={actions.currentUserId}
                        open={activityOpen}
                        onOpenChange={setActivityOpen}
                    />
                    <RecordTaskComposer
                        anchor={{
                            kind: 'person',
                            person: actions.contact,
                            companyId: actions.companyId,
                        }}
                        currentUserId={actions.currentUserId}
                        open={taskOpen}
                        onOpenChange={setTaskOpen}
                        defaultDueDate={followUpDueDate(actions.goesColdAt, now)}
                        defaultDescription={tEvidence('followUpDescription', {
                            name: actions.contact.name,
                        })}
                    />
                </>
            ) : null}
        </>
    );
}
