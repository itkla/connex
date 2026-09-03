'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { Button } from '@/components/ui/button';
import { formatDateTime } from '@/app/lib/utils';
import type {
    Contact,
    ContactLifecycle,
    ContactLifecycleStage,
    ContactQualification,
    QualificationDimension,
} from '@/app/lib/types';
import { cn } from '@/lib/utils';
import { disqualificationReasonLabel } from '@/app/lib/contactLifecycle';
import ContactProvenanceDialog from './ContactProvenanceDialog';
import ContactQualificationDialog from './ContactQualificationDialog';
import ContactStageDialog from './ContactStageDialog';

type Props = {
    contact: Contact;
    lifecycle: ContactLifecycle;
    /** `null` when the qualification could not be read; the card says so rather than vanishing. */
    qualification: ContactQualification | null;
    referrer: Contact | null;
    /** Whether a deal is linked, the prerequisite for marking the contact converted. */
    hasLinkedDeal: boolean;
    canEdit: boolean;
    className?: string;
};

const DIMENSIONS: QualificationDimension[] = ['FIT', 'ENGAGEMENT'];

const STAGE_TONE: Record<ContactLifecycleStage, string> = {
    NEW: 'border-brand/30 bg-brand-light text-brand-dark',
    WORKING: 'border-brand/30 bg-brand-light text-brand-dark',
    NURTURING: 'border-border bg-muted text-muted-foreground',
    QUALIFIED: 'border-chart-won/30 bg-chart-won/10 text-chart-won',
    DISQUALIFIED: 'border-destructive/30 bg-destructive/10 text-destructive',
    CONVERTED: 'border-chart-won/30 bg-chart-won/10 text-chart-won',
    RECYCLED: 'border-border bg-muted text-muted-foreground',
};

/**
 * The contact's whole lead picture in one card (issue #559): where it came from, where it is, how
 * fast it was answered, and how it scores.
 *
 * <p>These were four stacked cards with four headers in a 256px rail, which is why the rail read as
 * a dumping ground — they are four facets of one subject, not four subjects. They are not tabbed
 * either: a stage badge, a response state, a source line and two scores are about eight lines in
 * total and fit together, while tabs would hide exactly the states someone opens the record to
 * discover — an overdue response, a blocked qualification.
 *
 * <p>The per-criterion answering that used to sit in the rail moves to a dialog. Up to fifty
 * label-plus-control rows do not belong in a 256px column at any level of disclosure; the rail
 * carries state, and the dialog carries the task.
 */
export default function ContactLeadPanel({
    contact,
    lifecycle,
    qualification,
    referrer,
    hasLinkedDeal,
    canEdit,
    className,
}: Props) {
    const t = useTranslations('ContactLead');
    const tl = useTranslations('ContactLifecycle');
    const tp = useTranslations('ContactProvenance');
    const tq = useTranslations('ContactQualification');
    const locale = useLocale();
    const [assessing, setAssessing] = useState(false);
    const [changingStage, setChangingStage] = useState(false);
    const [editingSource, setEditingSource] = useState(false);

    const stage = lifecycle.stage ?? null;

    const response = useMemo(() => {
        const dueAt = contact.firstResponseDueAt ?? null;
        if (!dueAt) return null;
        if (contact.firstRespondedAt) {
            return {
                tone: 'border-border bg-muted text-muted-foreground',
                label: t('response.answered'),
                detail: formatDateTime(contact.firstRespondedAt, locale),
            };
        }
        if (contact.firstResponseBreachedAt) {
            return {
                tone: 'border-destructive/30 bg-destructive/10 text-destructive',
                label: t('response.overdue'),
                detail: formatDateTime(dueAt, locale),
            };
        }
        return {
            tone: 'border-border bg-muted text-muted-foreground',
            label: t('response.due'),
            detail: formatDateTime(dueAt, locale),
        };
    }, [contact, locale, t]);

    const blocking = [
        ...new Set(qualification?.scores.flatMap((score) => score.unmetRequiredLabels) ?? []),
    ];

    return (
        <div className={cn('mt-6', className)}>
            <SectionHeader title={t('title')} />
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="flex flex-wrap items-center gap-2 px-6 py-4 xl:px-4">
                    <span
                        className={cn(
                            'inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium uppercase tracking-wider',
                            stage === null
                                ? 'border-dashed border-border bg-transparent text-muted-foreground'
                                : STAGE_TONE[stage],
                        )}
                    >
                        {stage === null ? tl('stage.none') : tl(`stage.${stage}`)}
                    </span>
                    {response ? (
                        <span
                            className={cn(
                                'inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium',
                                response.tone,
                            )}
                            title={response.detail}
                        >
                            {response.label}
                        </span>
                    ) : null}
                </div>

                {qualification && qualification.criteria.length > 0 ? (
                    <div className="grid grid-cols-2 gap-px border-t border-border bg-border">
                        {DIMENSIONS.map((dimension) => {
                            const score = qualification.scores.find(
                                (candidate) => candidate.dimension === dimension,
                            );
                            return (
                                <div key={dimension} className="bg-card px-6 py-3 xl:px-4">
                                    <p className="text-lg font-semibold tabular-nums text-foreground">
                                        {score?.percent === null || score?.percent === undefined
                                            ? '—'
                                            : `${score.percent}%`}
                                    </p>
                                    <p className="text-xs text-muted-foreground">
                                        {tq(`dimension.${dimension}`)}
                                    </p>
                                </div>
                            );
                        })}
                    </div>
                ) : null}

                <dl className="divide-y divide-border border-t border-border text-sm">
                    <div className="px-6 py-3 xl:px-4">
                        <dt className="text-xs text-muted-foreground">{tp('title')}</dt>
                        <dd className="mt-0.5 text-foreground">
                            {contact.leadSource ? tp(`source.${contact.leadSource}`) : tp('source.none')}
                            {contact.leadSourceDetail ? (
                                <span className="text-muted-foreground"> · {contact.leadSourceDetail}</span>
                            ) : null}
                            {referrer && contact.referrerPersonId ? (
                                <>
                                    {' · '}
                                    <Link
                                        href={`/records/contacts/${contact.referrerPersonId}`}
                                        className="underline underline-offset-2 hover:text-foreground"
                                    >
                                        {referrer.name}
                                    </Link>
                                </>
                            ) : null}
                        </dd>
                    </div>

                    {lifecycle.changedAt ? (
                        <div className="px-6 py-3 xl:px-4">
                            <dt className="text-xs text-muted-foreground">{t('stageChanged')}</dt>
                            <dd className="mt-0.5 text-foreground">
                                {formatDateTime(lifecycle.changedAt, locale)}
                            </dd>
                        </div>
                    ) : null}

                    {lifecycle.disqualifiedReason ? (
                        <div className="px-6 py-3 xl:px-4">
                            <dt className="text-xs text-muted-foreground">{t('reason')}</dt>
                            <dd className="mt-0.5 text-foreground">
                                {disqualificationReasonLabel(
                                    lifecycle.disqualifiedReason,
                                    lifecycle.reasonLabel,
                                    tl,
                                )}
                            </dd>
                        </div>
                    ) : null}

                    {blocking.length > 0 ? (
                        <div className="px-6 py-3 xl:px-4">
                            <dt className="text-xs text-muted-foreground">{t('blocking')}</dt>
                            <dd className="mt-0.5 text-foreground">{blocking.join(' · ')}</dd>
                        </div>
                    ) : null}

                    {qualification === null ? (
                        <div className="px-6 py-3 text-xs text-muted-foreground xl:px-4">
                            {tq('loadFailed')}
                        </div>
                    ) : null}
                </dl>

                {canEdit ? (
                    <div className="flex flex-wrap gap-2 border-t border-border px-6 py-3 xl:px-4">
                        <Button variant="outline" size="sm" onClick={() => setChangingStage(true)}>
                            {stage === null ? tl('startAction') : tl('changeAction')}
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => setEditingSource(true)}>
                            {contact.leadSource ? tp('correctAction') : tp('captureAction')}
                        </Button>
                        {qualification && qualification.criteria.length > 0 ? (
                            <Button variant="ghost" size="sm" onClick={() => setAssessing(true)}>
                                {t('assess')}
                            </Button>
                        ) : null}
                    </div>
                ) : null}
            </div>

            <ContactStageDialog
                contactId={contact.id}
                lifecycle={lifecycle}
                hasLinkedDeal={hasLinkedDeal}
                open={changingStage}
                onOpenChange={setChangingStage}
            />

            <ContactProvenanceDialog
                contactId={contact.id}
                ownerWorkspaceId={contact.workspaceId}
                leadSource={contact.leadSource ?? null}
                leadSourceDetail={contact.leadSourceDetail ?? null}
                referrerPersonId={contact.referrerPersonId ?? null}
                referrer={referrer}
                open={editingSource}
                onOpenChange={setEditingSource}
            />

            {qualification ? (
                <ContactQualificationDialog
                    contactId={contact.id}
                    qualification={qualification}
                    open={assessing}
                    onOpenChange={setAssessing}
                    canEdit={canEdit}
                />
            ) : null}
        </div>
    );
}
