import { cookies } from 'next/headers';
import Link from 'next/link';
import { notFound, redirect } from 'next/navigation';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import { loadRecord } from '@/app/lib/recordAccess';
import { CrumbLabel } from '@/app/hooks/useNavTrail';
import RecentRecordBridge from '@/app/components/actions/RecentRecordBridge';
import ActionRecordBridge from '@/app/components/actions/ActionRecordBridge';
import type { ReactNode } from 'react';
import { BuildingOffice2Icon, CalendarIcon, InformationCircleIcon } from '@heroicons/react/24/outline';
import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/solid';
import { getLocale, getTranslations } from 'next-intl/server';

import {
    getActivitiesForDeal,
    getAttachmentsFromCookie,
    getCompanyById,
    getContacts,
    getCurrentUserFromCookie,
    getEntityCustomFieldsFromCookie,
    getContextNotifications,
    getDealById,
    getDealLineItemsFromCookie,
    getDealDocumentsFromCookie,
    getEffectivePermissionsFromCookie,
    getDealCollaborators,
    getDealPeople,
    getDealRisk,
    getDealStageHistory,
    getNotesForDeal,
    getPipelineById,
    getStagesByPipelineId,
    getTagsForDeal,
    getTasksForDeal,
    getUserReferences,
} from '@/app/lib/api';
import {
    type Activity,
    type Contact,
    type DealPerson,
    type DealStageHistory,
    type Note,
    type Stage,
    type Tag,
    type Task,
    type User,
    type UserReference,
} from '@/app/lib/types';
import {
    formatCompactCurrency,
    formatCurrency,
    formatDate,
    formatDateTime,
    parseMysqlDateTime,
} from '@/app/lib/utils';

import Rise from '@/app/components/motion/Rise';
import { PageShell } from '@/app/components/PageShell';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import InfoRow from '@/app/components/me/InfoRow';
import Timeline from '@/app/components/me/Timeline';
import Attachments from '@/app/components/attachments/Attachments';
import SummaryTile from '@/app/components/SummaryTile';
import { EngagementSparkline, type EngagementPoint } from '@/app/components/records/companies/CompanyCard';
import DealActionsMenu from '@/app/components/records/deals/DealActionsMenu';
import DealActivityBreakdown from '@/app/components/records/deals/DealActivityBreakdown';
import EngineEvaluationPanel from '@/app/components/records/EngineEvaluationPanel';
import DealBriefPanel from '@/app/components/records/deals/DealBriefPanel';
import DealRationalePanel from '@/app/components/records/deals/DealRationalePanel';
import DealRiskPanel from '@/app/components/records/deals/DealRiskPanel';
import DealRiskPill from '@/app/components/records/deals/DealRiskPill';
import DealLifecycleProgress from '@/app/components/records/deals/DealLifecycleProgress';
import { dealOutcome, type DealOutcome } from '@/app/components/records/deals/dealOutcome';
import DealTaskList from '@/app/components/records/deals/DealTaskList';
import DealLineItems from '@/app/components/records/deals/DealLineItems';
import DealDocuments from '@/app/components/records/deals/DealDocuments';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import EntityNotificationBanner from '@/app/components/notifications/EntityNotificationBanner';
import CustomFieldRows from '@/app/components/records/CustomFieldRows';
import RecordDetailSection from '@/app/components/records/RecordDetailSection';
import RecordStickyContext from '@/app/components/records/RecordStickyContext';

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

type ResolvedDealPerson = { person: Contact; role: string | null };

type DealPageProps = {
    params: Promise<{ id: number }>;
};

export default async function DealPage({ params }: DealPageProps) {
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;

    const [
        t,
        locale,
        dealAccess,
        currentUser,
        activities,
        notes,
        tasks,
        tags,
        peopleRefs,
        attachments,
        notificationPage,
        collaborators,
        customFields,
        risk,
        stageHistory,
    ] =
        await Promise.all([
            getTranslations('DealsPage'),
            getLocale(),
            loadRecord(() => getDealById(id, init)),
            getCurrentUserFromCookie(cookie),
            getActivitiesForDeal(id, init).catch(() => [] as Activity[]),
            getNotesForDeal(id, init).catch(() => [] as Note[]),
            getTasksForDeal(id, init).catch(() => [] as Task[]),
            getTagsForDeal(id, init).catch(() => [] as Tag[]),
            getDealPeople(id, init).catch(() => [] as DealPerson[]),
            getAttachmentsFromCookie("deal", id, cookie),
            getContextNotifications("deal", id, init).catch(() => ({
                items: [],
                total: 0,
                stateVersion: 0,
                asOf: "1970-01-01T00:00:00Z",
            })),
            getDealCollaborators(id, init).catch(() => [] as User[]),
            getEntityCustomFieldsFromCookie("deal", id, cookie),
            getDealRisk(id, init).catch(() => null),
            getDealStageHistory(id, init).catch(() => [] as DealStageHistory[]),
        ]);

    if (dealAccess.kind === 'forbidden') return <AccessDeniedPage />;
    if (dealAccess.kind === 'missing') notFound();
    const deal = dealAccess.record;
    if (!currentUser) redirect('/auth/login');

    const [lineItems, documents, effectivePermissions, company, dealContacts, pipeline, stages] = await Promise.all([
        getDealLineItemsFromCookie(deal.id, cookie)
            .catch(() => ({ items: [], totals: { currency: deal.currency ?? 'USD', subtotal: 0, tax: 0, oneTimeTotal: 0, recurringTotal: 0, grandTotal: 0 } })),
        getDealDocumentsFromCookie(deal.id, cookie).catch(() => []),
        getEffectivePermissionsFromCookie(cookie),
        deal.company != null
            ? getCompanyById(deal.company, init).catch(() => null)
            : Promise.resolve(null),
        getContacts({ dealId: id }, init).catch(() => [] as Contact[]),
        deal.pipeline != null
            ? getPipelineById(deal.pipeline, init).catch(() => null)
            : Promise.resolve(null),
        deal.pipeline != null
            ? getStagesByPipelineId(deal.pipeline, init).catch(() => [] as Stage[])
            : Promise.resolve([] as Stage[]),
    ]);
    const roleByPersonId = new Map(peopleRefs.map((ref) => [ref.person, ref.role]));
    const dealPeople: ResolvedDealPerson[] = dealContacts.map((person) => ({
        person,
        role: roleByPersonId.get(person.id) ?? null,
    }));

    const pipelines = pipeline ? [pipeline] : [];
    const stagesByPipeline: Record<number, Stage[]> = pipeline
        ? { [pipeline.id]: stages }
        : {};
    const currentStage = stages.find((s) => s.id === deal.stage) ?? null;
    const personSeeds = dealPeople.map(({ person }) => person);
    const dealSeeds = [deal];
    const knownUsers = new Map<number, UserReference>([
        [currentUser.id, currentUser],
        ...collaborators.map((user): [number, UserReference] => [user.id, user]),
    ]);
    const relatedUserIds = new Set<number>([
        ...activities.map((activity) => activity.createdById),
        ...tasks.map((task) => task.assignedToId),
        ...notes.map((note) => note.author),
        deal.ownerId,
    ].filter((userId): userId is number => typeof userId === 'number'));
    const missingUserIds = [...relatedUserIds].filter((userId) => !knownUsers.has(userId));
    const fetchedUsers = await getUserReferences(missingUserIds, init).catch(() => []);
    for (const user of fetchedUsers) knownUsers.set(user.id, user);
    const relatedUsers = [...knownUsers.values()];

    const outcome: DealOutcome = dealOutcome(deal.won);
    const closed = outcome !== 'open';
    const variance =
        closed && deal.value > 0 ? (deal.actualValue - deal.value) / deal.value : null;
    const currency = deal.currency || 'USD';
    const now = new Date().getTime();
    const firstWeekStart = now - 11 * WEEK_MS;
    const weeklyEngagement: EngagementPoint[] = Array.from({ length: 12 }, (_, i) => ({
        weekStart: firstWeekStart + i * WEEK_MS,
        count: 0,
        activities: 0,
        tasks: 0,
        notes: 0,
    }));
    const bucket = (ts: number, kind: 'activities' | 'tasks' | 'notes') => {
        if (!Number.isFinite(ts)) return;
        const idx = Math.floor((ts - firstWeekStart) / WEEK_MS);
        if (idx < 0 || idx >= weeklyEngagement.length) return;
        weeklyEngagement[idx][kind]++;
        weeklyEngagement[idx].count++;
    };
    for (const a of activities) bucket(parseMysqlDateTime(a.timestamp), 'activities');
    for (const t of tasks) bucket(parseMysqlDateTime(t.createdAt), 'tasks');
    for (const n of notes) bucket(parseMysqlDateTime(n.createdAt), 'notes');

    return (
        <PageShell tier="reading">
                <RecordStickyContext
                    anchorId="deal-record-identity"
                    name={deal.name}
                    risk={risk}
                />
                <Rise>
                    <CrumbLabel value={deal.name} />
                    <RecentRecordBridge type="deal" id={deal.id} label={deal.name} />
                    <ActionRecordBridge type="deal" id={deal.id} label={deal.name} />
                    <RecordDetailSection recordKind="deal" section="identity">
                        <header id="deal-record-identity" className="flex flex-wrap items-center justify-between gap-6">
                            <div className="flex flex-col gap-2 py-8">
                                <div className="flex flex-row flex-wrap items-center gap-3">
                                    <h1 className="text-4xl font-extrabold tracking-tight text-foreground">{deal.name}</h1>
                                    {tags.map((tag) => (
                                        <span
                                            key={tag.id}
                                            className="rounded-full px-2 py-0.5 text-xs font-medium text-white"
                                            style={{ backgroundColor: tag.color || 'var(--muted-foreground)' }}
                                        >
                                            {tag.name}
                                        </span>
                                    ))}
                                </div>
                                <h3 className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                    {company ? (
                                        <Link
                                            href={`/records/companies/${company.id}`}
                                            className="inline-flex items-center gap-1 rounded-md bg-muted px-2 py-1 transition-colors duration-200 hover:bg-brand-hover hover:text-brand-foreground"
                                        >
                                            <BuildingOffice2Icon className="size-3.5" />
                                            {company.name}
                                        </Link>
                                    ) : null}
                                    {pipeline ? (
                                        <span className="inline-flex items-center gap-2">
                                            {pipeline.name}
                                            {currentStage ? <> · {currentStage.name}</> : null}
                                            <StatusPill outcome={outcome} t={t} />
                                        </span>
                                    ) : (
                                        <StatusPill outcome={outcome} t={t} />
                                    )}
                                    {deal.expectedCloseDate ? (
                                        <span className="inline-flex items-center gap-1">
                                            <CalendarIcon className="size-3.5" />
                                            {t('closeBy', { date: formatDate(deal.expectedCloseDate, locale) })}
                                        </span>
                                    ) : null}
                                    <DealRiskPill risk={risk} />
                                </h3>
                            </div>

                            <div className="flex flex-col items-end gap-2">
                                <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                                    {closed ? t('actual') : t('projected')} · {currency}
                                </span>
                                <div className="text-3xl font-extrabold text-foreground">
                                    {formatCurrency(closed ? deal.actualValue : deal.value, currency, locale)}
                                </div>
                            </div>
                        </header>
                    </RecordDetailSection>

                    <RecordDetailSection recordKind="deal" section="actions" className="mt-4 flex justify-end">
                        <DealActionsMenu
                            deal={deal}
                            pipelines={pipelines}
                            stagesByPipeline={stagesByPipeline}
                            currentUserId={currentUser.id}
                            personSeeds={personSeeds}
                            dealSeeds={dealSeeds}
                            collaborators={collaborators}
                        />
                    </RecordDetailSection>
                    <RecordDetailSection recordKind="deal" section="notifications">
                        <EntityNotificationBanner
                            key={`${notificationPage.stateVersion}:${notificationPage.items.map((item) => item.id).join(',')}`}
                            initialNotifications={notificationPage.items}
                            contextType="deal"
                            contextId={id}
                            initialStateVersion={notificationPage.stateVersion}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.06}>
                    <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                        <RecordDetailSection recordKind="deal" section="profile">
                            <aside>
                                <SectionHeader title={t('details')} />
                                <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                    <InfoRow label={t('pipeline')} value={pipeline?.name ?? '—'} />
                                    <InfoRow label={t('stage')} value={currentStage?.name ?? '—'} />
                                    <InfoRow label={t('company')} value={company?.name ?? '—'} />
                                    <InfoRow label={t('currency')} value={deal.currency ?? '—'} />
                                    <InfoRow label={t('expectedClose')} value={formatDate(deal.expectedCloseDate, locale)} />
                                    <InfoRow label={t('closedAt')} value={closed ? formatDate(deal.closedAt, locale) : '—'} />
                                    <InfoRow label={t('created')} value={formatDate(deal.createdAt, locale)} />
                                    <InfoRow label={t('updated')} value={formatDateTime(deal.updatedAt, locale)} />
                                    <CustomFieldRows entityType="deal" entityId={deal.id} initialEntries={customFields} />
                                </dl>
                            </aside>
                        </RecordDetailSection>

                        <RecordDetailSection recordKind="deal" section="metrics" aria-label={t('performance')}>
                            <SectionLabelWithTooltip
                                title={t('pipelineProgress')}
                                tooltip={
                                    <div className="flex flex-col gap-2">
                                        <h2 className="text-sm font-medium">{t('pipelineProgress')}</h2>
                                        <p className="text-xs text-muted-foreground">
                                            {t('pipelineProgressTooltip')}
                                        </p>
                                    </div>
                                }
                            />
                            <DealLifecycleProgress
                                stages={stages}
                                currentStageId={deal.stage ?? null}
                                outcome={outcome}
                                createdAt={deal.createdAt}
                                expectedCloseDate={deal.expectedCloseDate}
                                closedAt={deal.closedAt}
                                closedReason={deal.closedReason}
                                references={deal.references}
                                stageHistory={stageHistory}
                            />

                            <div className="mt-6">
                                <SectionLabelWithTooltip
                                    title={t('performance')}
                                    tooltip={
                                        <div className="flex flex-col gap-2">
                                            <h2 className="text-sm font-medium">{t('performance')}</h2>
                                            <p className="text-xs text-muted-foreground">
                                                {t('performanceTooltip')}
                                            </p>
                                            <ul className="list-disc list-inside text-xs text-muted-foreground">
                                                <li>{t('performanceBulletProjected')}</li>
                                                <li>{t('performanceBulletActual')}</li>
                                                <li>{t('performanceBulletVariance')}</li>
                                            </ul>
                                        </div>
                                    }
                                />
                                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                                    <SummaryTile label={t('projectedValue')} value={formatCompactCurrency(deal.value, currency, locale)} />
                                    <SummaryTile
                                        label={t('actualValue')}
                                        value={closed ? formatCompactCurrency(deal.actualValue, currency, locale) : '—'}
                                    />
                                    <SummaryTile
                                        label={t('variance')}
                                        value={
                                            variance != null
                                                ? `${variance >= 0 ? '+' : ''}${(variance * 100).toFixed(1)}%`
                                                : '—'
                                        }
                                    />
                                    <SummaryTile
                                        label={closed ? t('closed') : t('expectedClose')}
                                        value={closed ? formatDate(deal.closedAt, locale) : formatDate(deal.expectedCloseDate, locale)}
                                    />
                                </div>
                            </div>
                        </RecordDetailSection>
                    </div>
                </Rise>

                <Rise delay={0.08}>
                    <RecordDetailSection recordKind="deal" section="activity" className="flex flex-col gap-6">
                        <div>
                            <SectionLabelWithTooltip
                                title={t('engagement')}
                                tooltip={
                                    <div className="flex flex-col gap-2">
                                        <h2 className="text-sm font-medium">{t('performance')}</h2>
                                        <p className="text-xs text-muted-foreground">
                                            {t('engagementTooltip')}
                                        </p>
                                    </div>
                                }
                            />
                            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                                <EngagementSparkline data={weeklyEngagement} />
                                <DealActivityBreakdown activities={activities} />
                            </div>
                        </div>

                        <DealTaskList dealId={deal.id} companyId={deal.company} tasks={tasks} deals={dealSeeds} />

                        <DealLineItems dealId={deal.id} dealCurrency={deal.currency ?? 'USD'} initial={lineItems} />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.1}>
                    <RecordDetailSection recordKind="deal" section="relationship" className="flex flex-col gap-6">
                        <DealRiskPanel risk={risk} />
                        <div className="flex flex-col gap-6 lg:flex-row lg:items-stretch lg:gap-4">
                            <DealBriefPanel
                                key={`deal-brief-${deal.id}`}
                                dealId={deal.id}
                                className="min-w-0 lg:flex-[2]"
                            />
                            <DealRationalePanel
                                key={`deal-rationale-${deal.id}`}
                                dealId={deal.id}
                                className="min-w-0 lg:flex-[1]"
                            />
                        </div>
                        <EngineEvaluationPanel
                            kind="deal"
                            id={deal.id}
                            riskExcluded={deal.riskExcluded ?? false}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.12}>
                    <RecordDetailSection recordKind="deal" section="related">
                        <SectionLabelWithTooltip
                            title={t('peopleOnThisDeal')}
                            tooltip={
                                <div className="flex flex-col gap-2">
                                    <h2 className="text-sm font-medium">{t('peopleOnThisDeal')}</h2>
                                    <p className="text-xs text-muted-foreground">
                                        {t('peopleOnThisDealTooltipShort')}
                                    </p>
                                    <p>
                                        {t('peopleOnThisDealTooltipLong')}
                                    </p>
                                </div>
                            }
                        />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            {dealPeople.length === 0 ? (
                                <p className="px-6 py-6 text-sm text-muted-foreground">
                                    {t('noPeopleAssociated')}
                                </p>
                            ) : (
                                <ul className="divide-y divide-border">
                                    {dealPeople.map(({ person, role }) => (
                                        <li key={person.id}>
                                            <Link
                                                href={`/records/contacts/${person.id}`}
                                                className="flex items-center gap-3 px-6 py-3 transition-colors hover:bg-muted/60"
                                            >
                                                <ContactAvatar contact={person} type="medium" />
                                                <div className="min-w-0 flex-1">
                                                    <p className="truncate text-sm font-medium text-foreground">
                                                        {person.name}
                                                    </p>
                                                    {person.title ? (
                                                        <p className="truncate text-xs text-muted-foreground">
                                                            {person.title}
                                                        </p>
                                                    ) : null}
                                                </div>
                                                {role ? (
                                                    <span className="rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark">
                                                        {role}
                                                    </span>
                                                ) : null}
                                            </Link>
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </div>
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.14}>
                    <RecordDetailSection recordKind="deal" section="files" className="flex flex-col gap-6">
                        <DealDocuments
                            dealId={deal.id}
                            initial={documents}
                            canApprove={effectivePermissions.includes('DOCUMENT_APPROVE')}
                            canDeleteDocuments={effectivePermissions.includes('DEAL_UPDATE')}
                            currentUserId={currentUser.id}
                        />
                        <Attachments
                            entityType="deal"
                            entityId={deal.id}
                            initialAttachments={attachments}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.16}>
                    <RecordDetailSection recordKind="deal" section="history">
                        <SectionLabelWithTooltip
                            title={t('timeline')}
                            tooltip={
                                <div className="flex flex-col gap-2">
                                    <h2 className="text-sm font-medium">{t('timeline')}</h2>
                                    <p className="text-xs text-muted-foreground">
                                        {t('timelineTooltip')}
                                    </p>
                                </div>
                            }
                        />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={relatedUsers}
                                persons={personSeeds}
                                deals={dealSeeds}
                                currentUserId={currentUser.id}
                                companyId={deal.company ?? null}
                            />
                        </div>
                    </RecordDetailSection>
                </Rise>
        </PageShell>
    );
}

function SectionLabelWithTooltip({ title, tooltip }: { title: string; tooltip: ReactNode }) {
    return (
        <div className="mb-3 flex h-8 items-center gap-1.5">
            <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                {title}
            </h2>
            <Tooltip>
                <TooltipTrigger asChild>
                    <InformationCircleIcon className="size-3 text-muted-foreground" />
                </TooltipTrigger>
                <TooltipContent>{tooltip}</TooltipContent>
            </Tooltip>
        </div>
    );
}

function StatusPill({ outcome, t }: { outcome: DealOutcome; t: (key: string) => string }) {
    if (outcome === 'won') {
        return (
            <span className="inline-flex items-center gap-1 rounded-full bg-brand-light px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-dark">
                <CheckCircleIcon className="size-3" /> {t('statusWon')}
            </span>
        );
    }
    if (outcome === 'lost') {
        return (
            <span className="inline-flex items-center gap-1 rounded-full bg-destructive/10 px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-destructive">
                <XCircleIcon className="size-3" /> {t('statusLost')}
            </span>
        );
    }
    return (
        <span className="rounded-full bg-brand px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-foreground">
            {t('statusOpen')}
        </span>
    );
}
