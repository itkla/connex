'use client';

import { useState } from 'react';
import dynamic from 'next/dynamic';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';

import RelationshipEvidencePanel from '@/app/components/records/RelationshipEvidencePanel';
import RelationshipEvidenceActions from '@/app/components/records/RelationshipEvidenceActions';
import WarmthPill from '@/app/components/records/WarmthPill';
import { useLiveNow } from '@/app/hooks/useNow';
import { followUpDueDate } from '@/app/lib/followUp';
import type { Contact, RelationshipEvidence } from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card';
import { cn } from '@/lib/utils';

const RecordActivityComposer = dynamic(() => import(
    '@/app/components/records/RecordComposers'
).then((composers) => composers.RecordActivityComposer));

const RecordTaskComposer = dynamic(() => import(
    '@/app/components/records/RecordComposers'
).then((composers) => composers.RecordTaskComposer));

/**
 * Everything the warmth-evidence footer needs to turn a reading into a next step. Passed as one
 * object so a server surface can hand it across the boundary and so the footer is all-or-nothing:
 * a surface without a contact anchor renders no actions rather than half of them.
 */
export type RelationshipEvidenceActionContext = {
    contact: Contact;
    companyId: number | null;
    currentUserId: number;
    /** The predicted cold date, so a follow-up can be scheduled ahead of it. */
    goesColdAt: string | null;
};

/** The composer an evidence action hands off to once the evidence surface has finished closing. */
type PendingComposer = 'activity' | 'task';

/**
 * Record-detail entry point that reuses {@link WarmthPill}: hover shows a short warmth
 * summary, click opens Relationship Evidence in a dialog that mirrors the page panel chrome —
 * a centered dialog on desktop, a bottom sheet on mobile.
 * Passing {@code actions} adds the footer's next steps for a contact record; a surface with no
 * contact anchor (a company) opens the same evidence without them.
 *
 * A footer action hands off rather than stacking: the pending composer is held until the evidence
 * surface reports it has finished closing, so two overlays never transition at once.
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
    const [pending, setPending] = useState<PendingComposer | null>(null);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [composersMounted, setComposersMounted] = useState(false);

    const temperature = evidence.temperature;
    const hasHistory = Boolean(temperature.lastTouchAt);
    const lastTouch = temperature.lastTouchAt
        ? formatRelativeTime(temperature.lastTouchAt, locale, now)
        : null;
    const bandLabel = hasHistory ? tTemp(temperature.band) : tTemp('noHistory');

    const handOff = (composer: PendingComposer) => {
        setPending(composer);
        setDialogOpen(false);
    };

    const openPendingComposer = () => {
        if (pending === null) return;
        setComposersMounted(true);
        if (pending === 'activity') setActivityOpen(true);
        else setTaskOpen(true);
        setPending(null);
    };

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

            <ResponsiveDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                onCloseComplete={openPendingComposer}
            >
                <ResponsiveDialogContent
                    scrollable={false}
                    className="max-h-[min(90vh,44rem)] gap-0 overflow-hidden bg-card p-0 sm:max-w-2xl"
                >
                    <ResponsiveDialogTitle className="sr-only">
                        {tEvidence('title')}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription className="sr-only">
                        {tEvidence('subtitle')}
                    </ResponsiveDialogDescription>
                    <div className="max-h-[min(90vh,44rem)] overflow-y-auto">
                        <RelationshipEvidencePanel
                            evidence={evidence}
                            variant="dialog"
                            actions={actions ? (
                                <RelationshipEvidenceActions
                                    onLogInteraction={() => handOff('activity')}
                                    onScheduleFollowUp={() => handOff('task')}
                                />
                            ) : undefined}
                        />
                    </div>
                </ResponsiveDialogContent>
            </ResponsiveDialog>

            {actions && composersMounted ? (
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
