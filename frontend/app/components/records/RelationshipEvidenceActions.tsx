'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import {
    BoltIcon,
    CalendarDaysIcon,
    UserPlusIcon,
} from '@heroicons/react/24/outline';

import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { acceptWarmPath } from '@/app/lib/api';
import { toastSuccess } from '@/app/lib/toast';
import type { Contact, IntroPath } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

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
    /** The shortest intro path to this contact; the intro ask appears only when one exists. */
    introPath: IntroPath | null;
};

/** The bridge contact on an intro path: the step immediately before the target. */
export function introPathBridge(
    introPath: IntroPath | null,
): { personId: number; personName: string } | null {
    if (!introPath || !introPath.reachable || introPath.directlyKnown) return null;
    const steps = introPath.steps;
    if (steps.length < 2) return null;
    const bridge = steps[steps.length - 2];
    const name = bridge.personName?.trim();
    if (!name) return null;
    return { personId: bridge.personId, personName: name };
}

/** Composes the mention token the intro follow-up task carries for a contact. */
export function introMention(name: string, id: number): string {
    return `[${name}](person:${id})`;
}

/**
 * The action row under a contact's warmth evidence: log an interaction, schedule a follow-up dated
 * before the relationship is predicted to go cold, and — only when an intro path exists — ask for
 * the introduction. The two composer actions are handed back to the surface that owns the evidence
 * dialog, because a composer has to outlive the dialog it was launched from.
 */
export default function RelationshipEvidenceActions({
    context,
    onLogInteraction,
    onScheduleFollowUp,
    className,
}: {
    context: RelationshipEvidenceActionContext;
    onLogInteraction: () => void;
    onScheduleFollowUp: () => void;
    className?: string;
}) {
    const t = useTranslations('RelationshipEvidence');
    const tIntro = useTranslations('Introductions');
    const showApiError = useApiErrorToast('Introductions');
    const [asking, setAsking] = useState(false);
    const [asked, setAsked] = useState(false);

    const { contact, introPath } = context;
    const bridge = introPathBridge(introPath);

    const askForIntro = async () => {
        if (!bridge || asking || asked) return;
        setAsking(true);
        try {
            await acceptWarmPath({
                targetPersonId: contact.id,
                bridgePersonId: bridge.personId,
                taskDescription: tIntro('acceptTaskDescription', {
                    bridge: introMention(bridge.personName, bridge.personId),
                    target: introMention(contact.name, contact.id),
                }),
            });
            setAsked(true);
            toastSuccess(tIntro('acceptToast', { name: contact.name }));
        } catch (error: unknown) {
            showApiError(error, 'acceptFailed');
        } finally {
            setAsking(false);
        }
    };

    return (
        <div className={cn('flex flex-wrap items-center gap-2', className)}>
            <Button type="button" size="sm" variant="secondary" onClick={onLogInteraction}>
                <BoltIcon aria-hidden />
                {t('actionLogInteraction')}
            </Button>
            <Button type="button" size="sm" variant="secondary" onClick={onScheduleFollowUp}>
                <CalendarDaysIcon aria-hidden />
                {t('actionScheduleFollowUp')}
            </Button>
            {bridge ? (
                <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    disabled={asking || asked}
                    onClick={() => void askForIntro()}
                    title={t('actionAskIntroVia', { name: bridge.personName })}
                >
                    <UserPlusIcon aria-hidden />
                    {asked ? t('actionIntroAsked') : tIntro('askIntro')}
                </Button>
            ) : null}
        </div>
    );
}
