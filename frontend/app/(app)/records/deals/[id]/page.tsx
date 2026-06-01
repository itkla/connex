import { cookies } from 'next/headers';
import Link from 'next/link';
import { notFound, redirect } from 'next/navigation';
import { ArrowLeftIcon, BuildingOffice2Icon, CalendarIcon, EllipsisVerticalIcon, PencilIcon, TrashIcon, InformationCircleIcon } from '@heroicons/react/24/outline';
import { CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/solid';
import { getLocale, getTranslations } from 'next-intl/server';

import {
    getActivitiesForDeal,
    getCompanies,
    getCompanyById,
    getContactById,
    getContacts,
    getCurrentUserFromCookie,
    getDealById,
    getDealPeople,
    getDeals,
    getNotesForDeal,
    getPipelines,
    getStagesByPipelineId,
    getTagsForDeal,
    getTasksForDeal,
    getUsers,
    deleteTask,
    updateTask,
} from '@/app/lib/api';
import {
    type Activity,
    type Company,
    type Contact,
    type Deal,
    type Note,
    type Pipeline,
    type Stage,
    type Tag,
    type Task,
    type User,
} from '@/app/lib/types';
import {
    formatCompactCurrency,
    formatCurrency,
    formatDate,
    formatDateTime,
    parseMysqlDateTime,
} from '@/app/lib/utils';

import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import InfoRow from '@/app/components/me/InfoRow';
import Timeline from '@/app/components/me/Timeline';
import SummaryTile from '@/app/components/SummaryTile';
import { EngagementSparkline, type EngagementPoint } from '@/app/components/records/companies/CompanyCard';
import DealActionsMenu from '@/app/components/records/deals/DealActionsMenu';
import DealActivityBreakdown from '@/app/components/records/deals/DealActivityBreakdown';
import DealLifecycleProgress from '@/app/components/records/deals/DealLifecycleProgress';
import { dealOutcome, type DealOutcome } from '@/app/components/records/deals/dealOutcome';
// import { Button } from '@/components/ui/button';
// import { DropdownMenuItem, DropdownMenuContent, DropdownMenuTrigger, DropdownMenu, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
// import { toast } from 'sonner';
import DealTaskList from '@/app/components/records/deals/DealTaskList';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

type DealPersonRef = { person: number; role: string | null };
type ResolvedDealPerson = { person: Contact; role: string | null };

export default async function DealPage({ params }: { params: { id: number } }) {
    const { id } = await params;
    const cookie = (await cookies()).toString();
    const init = { headers: { cookie } } as const;
    const t = await getTranslations('DealsPage');
    const locale = await getLocale();

    const [deal, currentUser, activities, notes, tasks, tags, peopleRaw, allPipelines, allCompanies, allPersons, allDeals, allUsers] =
        await Promise.all([
            getDealById(id, init).catch(() => null),
            getCurrentUserFromCookie(cookie),
            getActivitiesForDeal(id, init).catch(() => [] as Activity[]),
            getNotesForDeal(id, init).catch(() => [] as Note[]),
            getTasksForDeal(id, init).catch(() => [] as Task[]),
            getTagsForDeal(id, init).catch(() => [] as Tag[]),
            getDealPeople(id, init).catch(() => []) as Promise<unknown>,
            getPipelines(init).catch(() => [] as Pipeline[]),
            getCompanies(init).catch(() => [] as Company[]),
            getContacts({}, init).catch(() => [] as Contact[]),
            getDeals(init).catch(() => [] as Deal[]),
            getUsers(init).catch(() => [] as User[]),
        ]);

    if (!deal) notFound();
    if (!currentUser) redirect('/auth/login');

    const peopleRefs = peopleRaw as DealPersonRef[];

    const [company, dealPeople, allStages] = await Promise.all([
        deal.company != null
            ? getCompanyById(deal.company, init).catch(() => null)
            : Promise.resolve(null),
        Promise.all(
            peopleRefs.map(async (ref): Promise<ResolvedDealPerson | null> => {
                const contact = await getContactById(ref.person, init).catch(() => null);
                return contact ? { person: contact, role: ref.role } : null;
            }),
        ).then((entries) => entries.filter((e): e is ResolvedDealPerson => e !== null)),
        Promise.all(
            allPipelines.map(async (p): Promise<[number, Stage[]]> => {
                const s = await getStagesByPipelineId(p.id, init).catch(() => [] as Stage[]);
                return [p.id, s];
            }),
        ),
    ]);

    const stagesByPipeline: Record<number, Stage[]> = Object.fromEntries(allStages);
    const stages = deal.pipeline != null ? stagesByPipeline[deal.pipeline] ?? [] : [];

    const pipeline = allPipelines.find((p) => p.id === deal.pipeline) ?? null;
    const currentStage = stages.find((s) => s.id === deal.stage) ?? null;

    const outcome: DealOutcome = dealOutcome(currentStage);
    const closed = outcome !== 'open';
    const variance =
        closed && deal.value > 0 ? (deal.actualValue - deal.value) / deal.value : null;
    const currency = deal.currency || 'USD';
    const openTasks = tasks.filter((t) => !t.completed);

    const now = Date.now();
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
        <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
            <div className="flex flex-row justify-between">
                <Link
                    href="/records/deals"
                    className="inline-flex w-fit items-center gap-2 text-base text-brand hover:text-brand-hover"
                >
                    <ArrowLeftIcon className="h-4 w-4" />
                    <span>{t('allDeals')}</span>
                </Link>
            </div>

            <header className="mt-8 flex flex-wrap items-center justify-between gap-6">
                <div className="flex flex-col gap-2 py-8">
                    <div className="flex flex-row flex-wrap items-center gap-3">
                        <h1 className="text-4xl font-extrabold tracking-tight text-black">{deal.name}</h1>
                        {tags.map((tag) => (
                            <span
                                key={tag.id}
                                className="rounded-full px-2 py-0.5 text-xs font-medium text-white"
                                style={{ backgroundColor: tag.color || '#737373' }}
                            >
                                {tag.name}
                            </span>
                        ))}
                    </div>
                    <h3 className="flex flex-wrap items-center gap-2 text-sm text-neutral-500">
                        {company ? (
                            <Link
                                href={`/records/companies/${company.id}`}
                                className="inline-flex items-center gap-1 rounded-md bg-neutral-100 px-2 py-1 transition-colors duration-200 hover:bg-brand-hover hover:text-white"
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
                    </h3>
                </div>

                <div className="flex flex-col items-end gap-2">
                    <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                        {closed ? t('actual') : t('projected')} · {currency}
                    </span>
                    <div className="text-3xl font-extrabold text-neutral-900">
                        {formatCurrency(closed ? deal.actualValue : deal.value, currency, locale)}
                    </div>
                </div>
            </header>

            <div className="mt-4 flex justify-end">
                <DealActionsMenu
                    deal={deal}
                    companies={allCompanies}
                    pipelines={allPipelines}
                    stagesByPipeline={stagesByPipeline}
                    currentUserId={currentUser.id}
                    persons={allPersons}
                    deals={allDeals}
                />
            </div>

            <div className="mt-8 mb-3 flex h-8 items-center gap-1.5">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                    {t('pipelineProgress')}
                </h2>
                {/* TODO: make the tooltips show translation lines, but that's something for after */}
                <Tooltip>
                    <TooltipTrigger asChild>
                        <InformationCircleIcon className="size-3 text-neutral-500" />
                    </TooltipTrigger>
                    <TooltipContent>
                        <div className="flex flex-col gap-2">
                            <h2 className="text-sm font-medium">{t('pipelineProgress')}</h2>
                            <p className="text-xs text-neutral-400">
                                {t('pipelineProgressTooltip')}
                            </p>
                        </div>
                    </TooltipContent>
                </Tooltip>
            </div>
            <DealLifecycleProgress
                stages={stages}
                currentStageId={deal.stage ?? null}
                outcome={outcome}
                createdAt={deal.createdAt}
                expectedCloseDate={deal.expectedCloseDate}
                closedAt={deal.closedAt}
            />

            <div className="mt-6 mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                    {t('performance')}
                </h2>
                <Tooltip>
                    <TooltipTrigger asChild>
                        <InformationCircleIcon className="size-3 text-neutral-500" />
                    </TooltipTrigger>
                    <TooltipContent>
                        <div className="flex flex-col gap-2">
                            <h2 className="text-sm font-medium">{t('performance')}</h2>
                            <p className="text-xs text-neutral-400">
                                {t('performanceTooltip')}
                            </p>
                            <ul className="list-disc list-inside text-xs text-neutral-400">
                                <li>{t('performanceBulletProjected')}</li>
                                <li>{t('performanceBulletActual')}</li>
                                <li>{t('performanceBulletVariance')}</li>
                            </ul>
                        </div>
                    </TooltipContent>
                </Tooltip>
            </div>
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

            <div className="mt-6 mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                    {t('engagement')}
                </h2>
                <Tooltip>
                    <TooltipTrigger asChild>
                        <InformationCircleIcon className="size-3 text-neutral-500" />
                    </TooltipTrigger>
                    <TooltipContent>
                        <div className="flex flex-col gap-2">
                            <h2 className="text-sm font-medium">{t('performance')}</h2>
                            <p className="text-xs text-neutral-400">
                                {t('engagementTooltip')}
                            </p>
                        </div>
                    </TooltipContent>
                </Tooltip>
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <EngagementSparkline data={weeklyEngagement} />
                <DealActivityBreakdown activities={activities} />
            </div>

            <div className="mt-12 grid grid-cols-1 gap-8 md:min-h-0 md:flex-1 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                <aside>
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                            {t('details')}
                        </h2>
                    </div>
                    <dl className="divide-y divide-neutral-200 overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                        <InfoRow label={t('pipeline')} value={pipeline?.name ?? '—'} />
                        <InfoRow label={t('stage')} value={currentStage?.name ?? '—'} />
                        <InfoRow label={t('company')} value={company?.name ?? '—'} />
                        <InfoRow label={t('currency')} value={deal.currency ?? '—'} />
                        <InfoRow label={t('expectedClose')} value={formatDate(deal.expectedCloseDate, locale)} />
                        <InfoRow label={t('closedAt')} value={closed ? formatDate(deal.closedAt, locale) : '—'} />
                        <InfoRow label={t('created')} value={formatDate(deal.createdAt, locale)} />
                        <InfoRow label={t('updated')} value={formatDateTime(deal.updatedAt, locale)} />
                    </dl>
                </aside>

                <section className="md:flex md:min-h-0 md:flex-col">
                    <div className="mb-3 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                            {t('peopleOnThisDeal')}
                            {/* <InformationCircleIcon className="size-2" /> */}
                        </h2>
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <InformationCircleIcon className="size-3 text-neutral-500" />
                            </TooltipTrigger>
                            <TooltipContent>
                                <div className="flex flex-col gap-2">
                                    <h2 className="text-sm font-medium">{t('peopleOnThisDeal')}</h2>
                                    <p className="text-xs text-neutral-400">
                                        {t('peopleOnThisDealTooltipShort')}
                                    </p>
                                    <p>
                                        {t('peopleOnThisDealTooltipLong')}
                                    </p>
                                </div>
                            </TooltipContent>
                        </Tooltip>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                        {dealPeople.length === 0 ? (
                            <p className="px-6 py-6 text-sm text-neutral-500">
                                {t('noPeopleAssociated')}
                            </p>
                        ) : (
                            <ul className="divide-y divide-neutral-200">
                                {dealPeople.map(({ person, role }) => (
                                    <li key={person.id}>
                                        <Link
                                            href={`/records/contacts/${person.id}`}
                                            className="flex items-center gap-3 px-6 py-3 transition-colors hover:bg-neutral-200/60"
                                        >
                                            <ContactAvatar contact={person} type="medium" />
                                            <div className="min-w-0 flex-1">
                                                <p className="truncate text-sm font-medium text-neutral-900">
                                                    {person.name}
                                                </p>
                                                {person.title ? (
                                                    <p className="truncate text-xs text-neutral-500">
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

                    <DealTaskList dealId={deal.id} companyId={deal.company} tasks={tasks} deals={allDeals} />

                    <div className="mb-3 mt-6 flex h-8 items-center">
                        <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                            {t('timeline')}
                        </h2>
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <InformationCircleIcon className="size-3 text-neutral-500" />
                            </TooltipTrigger>
                            <TooltipContent>
                                <div className="flex flex-col gap-2">
                                    <h2 className="text-sm font-medium">{t('timeline')}</h2>
                                    <p className="text-xs text-neutral-400">
                                        {t('timelineTooltip')}
                                    </p>
                                </div>
                            </TooltipContent>
                        </Tooltip>
                    </div>
                    <div className="overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 md:flex md:min-h-0 md:flex-1 md:flex-col">
                        <div className="md:min-h-0 md:flex-1 md:overflow-y-auto md:[-webkit-mask-image:linear-gradient(to_bottom,transparent_0,black_24px)] md:[mask-image:linear-gradient(to_bottom,transparent_0,black_24px)]">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={allUsers}
                                persons={allPersons}
                                deals={allDeals}
                                currentUserId={currentUser.id}
                                companyId={deal.company ?? null}
                            />
                        </div>
                    </div>
                </section>
            </div>
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
            <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-red-700">
                <XCircleIcon className="size-3" /> {t('statusLost')}
            </span>
        );
    }
    if (outcome === 'open') {
        return (
            <span className="rounded-full bg-brand px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-white">
                {t('statusOpen')}
            </span>
        );
    }
    return (
        <span className="rounded-full bg-neutral-200 px-2.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-neutral-600">
            {t('statusClosed')}
        </span>
    );
}