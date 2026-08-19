'use client';

import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { CheckCircleIcon as CheckCircleSolidIcon } from '@heroicons/react/24/solid';
import { CheckCircleIcon, QuestionMarkCircleIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { useActions } from '@/app/hooks/useActions';
import type { ActivationStep } from '@/app/lib/activation';
import type { FirstRunJourney } from '@/app/lib/firstRunJourney';
import FirstRunDoors from '@/app/components/FirstRunDoors';
import WorkspaceUnavailableRetry from '@/app/components/WorkspaceUnavailableRetry';
import { cn } from '@/lib/utils';

const IMPORT_CONTACTS_ACTION = 'utility.import-contacts';
const CREATE_PERSON_ACTION = 'create.person';

function StepDoors({ journey }: { journey: FirstRunJourney }) {
    const { run, pendingIds, getAction } = useActions();
    const doors = journey.doors.filter((door) => (door === 'importCsv'
        ? getAction(IMPORT_CONTACTS_ACTION) != null
        : getAction(CREATE_PERSON_ACTION) != null));

    return (
        <FirstRunDoors
            doors={doors}
            importPending={pendingIds.has(IMPORT_CONTACTS_ACTION)}
            onImport={() => {
                void run(IMPORT_CONTACTS_ACTION, { source: 'empty-state' });
            }}
            onNew={() => {
                void run(CREATE_PERSON_ACTION, { source: 'empty-state' });
            }}
        />
    );
}

function StepAction({ step, emphasis }: { step: ActivationStep; emphasis: boolean }) {
    const t = useTranslations('DashboardActivation');
    const tCapability = useTranslations('CapabilityUnavailable');
    const { run, pendingIds, getAction, openOverlay } = useActions();
    const label = t(`steps.${step.id}.cta`);
    const variant = emphasis ? 'brand' : 'outline';

    if (step.availability === 'unavailable') {
        return (
            <WorkspaceUnavailableRetry
                label={tCapability('retry')}
                pendingLabel={tCapability('retrying')}
                variant="outline"
                size="sm"
                className="w-full sm:w-auto sm:shrink-0"
            />
        );
    }

    if (step.href) {
        return (
            <Button asChild size="sm" variant={variant} className="w-full sm:w-auto sm:shrink-0">
                <Link href={step.href}>{label}</Link>
            </Button>
        );
    }

    if (!step.actionId || !getAction(step.actionId)) return null;

    const actionId = step.actionId;
    const pending = pendingIds.has(actionId);
    const handleAction = () => {
        if (step.requireRelationshipTarget) {
            openOverlay({ kind: 'create-activity', requireRelationshipTarget: true });
            return;
        }
        void run(actionId, { source: 'empty-state' });
    };

    return (
        <Button
            type="button"
            size="sm"
            variant={variant}
            disabled={pending}
            className="w-full sm:w-auto sm:shrink-0"
            onClick={handleAction}
        >
            {pending ? <Loader2Icon className="size-3.5 animate-spin" aria-hidden /> : null}
            {label}
        </Button>
    );
}

/**
 * The workspace setup checklist. Every step's completion is recomputed from the workspace's own
 * counts on each render rather than stored, and a completed step shows the count that completed it
 * instead of a generic tick, so the list can always be checked against the records themselves.
 * While the workspace is still on the first leg of its first-run journey, the contacts step offers
 * that journey's doors in place of its single call to action.
 */
export default function SetupChecklist({
    steps,
    journey,
}: {
    steps: ActivationStep[];
    journey: FirstRunJourney | null;
}) {
    const t = useTranslations('DashboardActivation');
    const tCapability = useTranslations('CapabilityUnavailable');
    const doneCount = steps.filter((step) => step.done).length;
    const firstOutstanding = steps.find((step) => !step.done && step.required)
        ?? steps.find((step) => !step.done);
    const contactDoors = journey?.leg === 'contacts' ? journey : null;

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-3.5">
                <h3 className="text-sm font-medium text-foreground">{t('checklistTitle')}</h3>
                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                    {t('checklistProgress', { done: doneCount, total: steps.length })}
                </span>
            </div>
            <ul className="flex-1 divide-y divide-border">
                {steps.map((step) => (
                    <li key={step.id} className="flex flex-col gap-3 px-5 py-3.5 sm:flex-row sm:items-start">
                        <div className="flex min-w-0 flex-1 items-start gap-3">
                            {step.availability === 'unavailable' ? (
                                <QuestionMarkCircleIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
                            ) : step.done ? (
                                <CheckCircleSolidIcon className="mt-0.5 size-5 shrink-0 text-brand-dark" aria-hidden />
                            ) : (
                                <CheckCircleIcon className="mt-0.5 size-5 shrink-0 text-muted-foreground/40" aria-hidden />
                            )}
                            <div className="min-w-0 flex-1">
                                <p
                                    className={cn(
                                        'text-sm font-medium',
                                        step.done ? 'text-muted-foreground' : 'text-foreground',
                                    )}
                                >
                                    {step.done ? <span className="sr-only">{t('doneLabel')} </span> : null}
                                    {t(`steps.${step.id}.title`)}
                                    {!step.done && !step.required ? (
                                        <span className="ml-2 text-xs font-normal text-muted-foreground">
                                            {t('optionalLabel')}
                                        </span>
                                    ) : null}
                                </p>
                                <p className="mt-0.5 text-xs text-muted-foreground">
                                    {step.availability === 'unavailable'
                                        ? tCapability('body')
                                        : step.done
                                        ? t(`steps.${step.id}.done`, { count: step.count ?? 0 })
                                        : step.id === 'contacts' && contactDoors?.cardScanning
                                        ? t('steps.contacts.bodyScanning')
                                        : t(`steps.${step.id}.body`)}
                                </p>
                            </div>
                        </div>
                        {step.done ? null : step.id === 'contacts' && contactDoors ? (
                            <StepDoors journey={contactDoors} />
                        ) : (
                            <StepAction step={step} emphasis={step.id === firstOutstanding?.id} />
                        )}
                    </li>
                ))}
            </ul>
        </div>
    );
}
