'use client';

import { useTranslations } from 'next-intl';
import {
    BoltIcon,
    CalendarDaysIcon,
    UserPlusIcon,
} from '@heroicons/react/24/outline';

import { useContactIntroAsk } from '@/app/components/records/contacts/introAsk';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/**
 * The action row under a contact's warmth evidence: log an interaction, schedule a follow-up dated
 * before the relationship is predicted to go cold, and — only when the Introductions board offers a
 * path — ask for the introduction. The two composer actions are handed back to the surface that
 * owns the evidence dialog, because a composer has to outlive the dialog it was launched from; the
 * intro ask is the record's one shared ask, so making it here settles it everywhere on the page.
 */
export default function RelationshipEvidenceActions({
    onLogInteraction,
    onScheduleFollowUp,
    className,
}: {
    onLogInteraction: () => void;
    onScheduleFollowUp: () => void;
    className?: string;
}) {
    const t = useTranslations('RelationshipEvidence');
    const tIntro = useTranslations('Introductions');
    const { bridge, asking, asked, ask } = useContactIntroAsk();

    return (
        <div className={cn('flex flex-wrap items-center gap-2', className)}>
            <Button type="button" size="dialog" variant="secondary" onClick={onLogInteraction}>
                <BoltIcon aria-hidden />
                {t('actionLogInteraction')}
            </Button>
            <Button type="button" size="dialog" variant="secondary" onClick={onScheduleFollowUp}>
                <CalendarDaysIcon aria-hidden />
                {t('actionScheduleFollowUp')}
            </Button>
            {bridge ? (
                <Button
                    type="button"
                    size="dialog"
                    variant="ghost"
                    disabled={asking || asked}
                    onClick={ask}
                    title={tIntro('askIntroVia', { name: bridge.personName })}
                >
                    <UserPlusIcon aria-hidden />
                    {asked ? tIntro('introAsked') : tIntro('askIntro')}
                </Button>
            ) : null}
        </div>
    );
}
