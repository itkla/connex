import { DealTasksSection, DealLineItemsSection, DealDocumentsSection, DealEngagementSection, DealBriefSection } from '@/app/components/records/DetailCollections';
import { cookies } from 'next/headers';
import Link from 'next/link';
import { notFound, redirect } from 'next/navigation';
import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import WorkspaceUnavailablePage from '@/app/components/WorkspaceUnavailablePage';
import { loadRecord } from '@/app/lib/recordAccess';
import { loadDetailSection } from '@/app/lib/loadDetailSection';
import { DetailSectionsProvider } from '@/app/hooks/useDetailSections';
import { CrumbLabel } from '@/app/hooks/useNavTrail';
import RecentRecordBridge from '@/app/components/actions/RecentRecordBridge';
import ActionRecordBridge from '@/app/components/actions/ActionRecordBridge';
import type { ReactNode } from 'react';
import { BuildingOffice2Icon, CalendarIcon, InformationCircleIcon } from '@heroicons/react/24/outline';
import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/solid';
import { getTimeZone, getLocale, getTranslations } from 'next-intl/server';

import {
    getActivitiesForDeal,
    getAttachmentsFromCookie,
    getCommentThreads,
    getCompanyById,
    getContacts,
    getContactTemperatures,
    getCurrentUserResultFromCookie,
    getMyWorkspacesFromCookie,
    getEntityCustomFieldsFromCookie,
    getContextNotifications,
    getDealById,
    getDealLineItems,
    getDealDocuments,
    getEffectivePermissionsFromCookie,
    getDealCollaborators,
    getDealPeople,
    getDealRisk,
    getDealStageHistory,
    getRelationshipMapAvailability,
    getNotesForDeal,
    getPipelineById,
    getStagesByPipelineId,
    getTagsForDeal,
    getTasksForDeal,
    getUserReferences,
} from '@/app/lib/api';
import {
    type Contact,
    type DealPerson,
    type DealStageHistory,
    type RelationshipTemperature,
    type Stage,
    type Tag,
    type User,
    type UserReference,
} from '@/app/lib/types';
import {
    formatCompactCurrency,
    formatCurrency,
    formatDate,
    formatDateTime,
} from '@/app/lib/utils';
import { requestNow } from '@/app/lib/requestClock';
import { resolveWorkspaceTimezone } from '@/app/lib/workspaceSnapshot';
import { formatDealClosedAt } from '@/app/components/records/deals/dealDates';

import Rise from '@/app/components/motion/Rise';
import { PageShell } from '@/app/components/PageShell';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import WarmthPill from '@/app/components/records/WarmthPill';
import InfoRow from '@/app/components/me/InfoRow';
import Timeline from '@/app/components/me/Timeline';
import { commentsFromThreads, TIMELINE_COMMENT_LIMIT } from '@/app/components/me/timelineEntries';
import Attachments from '@/app/components/attachments/Attachments';
import CommentsSection from '@/app/components/records/comments/CommentsSection';
import SummaryTile from '@/app/components/SummaryTile';
import AskConnexRecordEntry from '@/app/components/ask-connex/AskConnexRecordEntry';
import DealActionsMenu from '@/app/components/records/deals/DealActionsMenu';
import EngineEvaluationPanel from '@/app/components/records/EngineEvaluationPanel';
import DealRationalePanel from '@/app/components/records/deals/DealRationalePanel';
import DealRiskPanel from '@/app/components/records/deals/DealRiskPanel';
import DealLifecycleProgress from '@/app/components/records/deals/DealLifecycleProgress';
import { dealOutcome, type DealOutcome } from '@/app/components/records/deals/dealOutcome';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import EntityNotificationBanner from '@/app/components/notifications/EntityNotificationBanner';
import CustomFieldRows from '@/app/components/records/CustomFieldRows';
import RecordDetailSection from '@/app/components/records/RecordDetailSection';
import RecordStickyContext from '@/app/components/records/RecordStickyContext';

type ResolvedDealPerson = { person: Contact; role: string | null };

type DealPageProps = {
    params: Promise<{ id: number }>;
};

export default async function DealPage({ params }: DealPageProps) {
    const timeZone = await getTimeZone();
    const { id: rawId } = await params;
    const id = Number(rawId);
    if (!Number.isInteger(id) || id < 1) notFound();
    const cookieStore = await cookies();
    const cookie = cookieStore.toString();
    const detailWorkspaceId = Number(cookieStore.get("connex_workspace")?.value) || undefined;
    const init = { headers: { cookie } } as const;
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);
    if (!currentUserResult.ok) return <WorkspaceUnavailablePage />;
    const currentUser = currentUserResult.data;
    if (!currentUser) redirect('/auth/login');
    const workspaceSnapshot = await getMyWorkspacesFromCookie(cookie);
    const timezone = resolveWorkspaceTimezone(workspaceSnapshot, currentUser.timezone);

    const [
        t,
        locale,
        dealAccess,
        activitiesResult,
        notesResult,
        tasksResult,
        tags,
        peopleRefs,
        attachments,
        notificationPage,
        collaborators,
        customFields,
        risk,
        stageHistory,
        mapAvailability,
    ] =
        await Promise.all([
            getTranslations('DealsPage'),
            getLocale(),
            loadRecord(() => getDealById(id, init)),
            loadDetailSection(() => getActivitiesForDeal(id, init)),
            loadDetailSection(() => getNotesForDeal(id, {}, init)),
            loadDetailSection(() => getTasksForDeal(id, init)),
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
            getRelationshipMapAvailability(init),
        ]);

    if (dealAccess.kind === 'forbidden') return <AccessDeniedPage />;
    if (dealAccess.kind === 'missing') notFound();
    const deal = dealAccess.record;

    const [lineItemsResult, documentsResult, effectivePermissions, company, dealContacts, pipeline, stages, commentThreads] = await Promise.all([
        loadDetailSection(() => getDealLineItems(deal.id, init)),
        loadDetailSection(() => getDealDocuments(deal.id, init)),
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
        getCommentThreads('deal', id, { limit: TIMELINE_COMMENT_LIMIT }, init).catch(() => []),
    ]);
    const activities = activitiesResult.kind === 'loaded' ? activitiesResult.data : [];
    const notes = notesResult.kind === 'loaded' ? notesResult.data : [];
    const tasks = tasksResult.kind === 'loaded' ? tasksResult.data : [];
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
    const stakeholderWarmth = await getContactTemperatures(
        personSeeds.map((person) => person.id),
        init,
    ).then(
        (temperatures) => ({
            read: true,
            byPerson: new Map(temperatures.map((temperature) => [temperature.id, temperature])),
        }),
        () => ({ read: false, byPerson: new Map<number, RelationshipTemperature>() }),
    );


    return (
        <DetailSectionsProvider key={`${detailWorkspaceId}:${id}`} recordId={id} workspaceId={detailWorkspaceId} initial={{ dealActivities: activitiesResult, dealNotes: notesResult, dealTasks: tasksResult, dealDocuments: documentsResult, dealLineItems: lineItemsResult }}>
        <PageShell>
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
                        <header
                            id="deal-record-identity"
                            className="flex flex-col gap-6 py-4 sm:flex-row sm:items-end sm:justify-between"
                        >
                            <div className="flex min-w-0 flex-col gap-3">
                                <div className="flex flex-row flex-wrap items-center gap-3">
                                    <h1 className="text-balance text-4xl font-extrabold tracking-tight text-foreground">
                                        {deal.name}
                                    </h1>
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
                                            {t('closeBy', { date: formatDate(deal.expectedCloseDate, locale, timeZone) })}
                                        </span>
                                    ) : null}
                                </h3>
                            </div>

                            <div className="flex flex-col items-end gap-1">
                                <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                                    {closed ? t('actual') : t('projected')} · {currency}
                                </span>
                                <div className="text-3xl font-extrabold tracking-tight text-foreground tabular-nums">
                                    {formatCurrency(closed ? deal.actualValue : deal.value, currency, locale)}
                                </div>
                            </div>
                        </header>
                    </RecordDetailSection>

                    <RecordDetailSection recordKind="deal" section="actions" className="mt-4 flex flex-wrap justify-end gap-2">
                        <AskConnexRecordEntry kind="deal" />
                        <DealActionsMenu
                            timezone={timezone}
                            mapAvailability={mapAvailability}
                            deal={deal}
                            pipelines={pipelines}
                            stagesByPipeline={stagesByPipeline}
                            currentUserId={currentUser.id}
                            personSeeds={personSeeds}
                            dealSeeds={dealSeeds}
                            collaborators={collaborators}
                        />
                    </RecordDetailSection>

                    <RecordDetailSection recordKind="deal" section="notifications" className="mt-6">
                        <EntityNotificationBanner
                            key={`${notificationPage.stateVersion}:${notificationPage.items.map((item) => item.id).join(',')}`}
                            initialNotifications={notificationPage.items}
                            contextType="deal"
                            contextId={id}
                            initialStateVersion={notificationPage.stateVersion}
                        />
                    </RecordDetailSection>
                </Rise>

                <div className="grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)] xl:items-start">
                        <aside className="flex flex-col gap-6">
                            <RecordDetailSection recordKind="deal" section="profile" className="flex flex-col gap-6">
                                <div>
                                    <SectionHeader title={t('details')} />
                                    <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                        <InfoRow label={t('pipeline')} value={pipeline?.name ?? '—'} />
                                        <InfoRow label={t('stage')} value={currentStage?.name ?? '—'} />
                                        <InfoRow label={t('company')} value={company?.name ?? '—'} />
                                        <InfoRow label={t('currency')} value={deal.currency ?? '—'} />
                                        <InfoRow label={t('expectedClose')} value={formatDate(deal.expectedCloseDate, locale, timeZone)} />
                                        <InfoRow label={t('closedAt')} value={closed ? formatDealClosedAt(deal.closedAt, locale, timezone) : '—'} />
                                        <InfoRow label={t('created')} value={formatDate(deal.createdAt, locale, timeZone)} />
                                        <InfoRow label={t('updated')} value={formatDateTime(deal.updatedAt, locale, timeZone)} />
                                        <CustomFieldRows entityType="deal" entityId={deal.id} initialEntries={customFields} />
                                    </dl>
                                </div>
                                <DealRiskPanel risk={risk} />
                            </RecordDetailSection>

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
                                            {dealPeople.map(({ person, role }) => {
                                                return (
                                                <li key={person.id}>
                                                    <Link
                                                        href={`/records/contacts/${person.id}`}
                                                        className="flex items-center gap-3 px-6 py-3 transition-colors hover:bg-muted/60 xl:px-4"
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
                                                            <div className="mt-1">
                                                                {stakeholderWarmth.read ? (
                                                                    <WarmthPill
                                                                        temp={stakeholderWarmth.byPerson.get(person.id)}
                                                                    />
                                                                ) : (
                                                                    <span className="text-xs text-muted-foreground">
                                                                        {t('warmthUnavailable')}
                                                                    </span>
                                                                )}
                                                            </div>
                                                        </div>
                                                        {role ? (
                                                            <span className="max-w-[6rem] truncate rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-text">
                                                                {role}
                                                            </span>
                                                        ) : null}
                                                    </Link>
                                                </li>
                                                );
                                            })}
                                        </ul>
                                    )}
                                </div>
                            </RecordDetailSection>

                            <EngineEvaluationPanel
                                kind="deal"
                                id={deal.id}
                                riskExcluded={deal.riskExcluded ?? false}
                                className="mt-0"
                            />
                        </aside>

                        <Rise delay={0.06} className="flex min-w-0 flex-col gap-8">
                            <RecordDetailSection
                                recordKind="deal"
                                section="metrics"
                                aria-label={t('performance')}
                                className="flex flex-col gap-8"
                            >
                                <div>
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
                                        timezone={timezone}
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
                                </div>

                                <div>
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
                                    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
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
                                            value={closed ? formatDealClosedAt(deal.closedAt, locale, timezone) : formatDate(deal.expectedCloseDate, locale, timeZone)}
                                        />
                                    </div>
                                </div>
                            </RecordDetailSection>

                            <RecordDetailSection recordKind="deal" section="relationship" className="flex flex-col gap-6">
                                <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)] xl:items-stretch">
                                    <DealBriefSection
                                        key={`deal-brief-${deal.id}`}
                                        dealId={deal.id}
                                        className="min-w-0"
                                        dealName={deal.name}
                                        people={dealPeople}
                                    />
                                    <DealRationalePanel
                                        key={`deal-rationale-${deal.id}`}
                                        dealId={deal.id}
                                        className="min-w-0"
                                    />
                                </div>
                            </RecordDetailSection>

                            <RecordDetailSection recordKind="deal" section="activity" className="flex flex-col gap-8">
                                <DealTasksSection dealId={deal.id} companyId={deal.company} deals={dealSeeds} />

                                <DealLineItemsSection dealId={deal.id} dealWorkspaceId={deal.workspaceId ?? null} dealCurrency={deal.currency ?? 'USD'} />
                            </RecordDetailSection>
                        </Rise>
                    </div>

                <Rise delay={0.1}>
                    <RecordDetailSection recordKind="deal" section="comments">
                        <CommentsSection
                            key={`deal-${deal.id}`}
                            targetType="deal"
                            targetId={deal.id}
                            currentUser={{ id: currentUser.id, displayName: currentUser.displayName, profilePictureUrl: currentUser.profilePictureUrl }}
                            canComment={effectivePermissions.includes('COMMENT_CREATE')}
                            canModerate={effectivePermissions.includes('COMMENT_MODERATE')}
                        />
                    </RecordDetailSection>
                </Rise>

                <Rise delay={0.12}>
                    <RecordDetailSection
                        recordKind="deal"
                        section="files"
                        className="grid grid-cols-1 gap-8 xl:grid-cols-2 xl:items-start"
                    >
                        <DealDocumentsSection dealId={deal.id}
                            canApprove={effectivePermissions.includes('DOCUMENT_APPROVE')}
                            canManageApprovals={effectivePermissions.includes('DOCUMENT_MANAGE')}
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
                    <RecordDetailSection recordKind="deal" section="history" className="flex flex-col gap-8">
                            <div>
                                <SectionLabelWithTooltip
                                    title={t('engagement')}
                                    tooltip={
                                        <div className="flex flex-col gap-2">
                                            <h2 className="text-sm font-medium">{t('engagement')}</h2>
                                            <p className="text-xs text-muted-foreground">
                                                {t('engagementTooltip')}
                                            </p>
                                        </div>
                                    }
                                />
                                <DealEngagementSection now={requestNow()} />
                            </div>

                            <div>
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
                                        detailKind="deal"
                                        tasks={tasks}
                                        activities={activities}
                                        notes={notes}
                                        noteTarget={{ type: "deal", id: deal.id }}
                                        users={relatedUsers}
                                        persons={personSeeds}
                                        deals={dealSeeds}
                                        comments={commentsFromThreads(commentThreads)}
                                        currentUserId={currentUser.id}
                                        companyId={deal.company ?? null}
                                    />
                                </div>
                            </div>

                    </RecordDetailSection>
                </Rise>
        </PageShell>
        </DetailSectionsProvider>
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
            <span className="inline-flex items-center gap-1 rounded-full bg-brand-light px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-text">
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
