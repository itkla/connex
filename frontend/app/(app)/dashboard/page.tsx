import { headers } from 'next/headers';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    FunnelIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';

import {
    getActivitiesFromCookie,
    getAttachmentFacets,
    getAttachmentsPage,
    getCompaniesFromCookie,
    getContactsFromCookie,
    getContactTemperaturesFromCookie,
    getCurrentUserFromCookie,
    getDealRisksFromCookie,
    getDealsFromCookie,
    getIntroSuggestionsFromCookie,
    getNotesFromCookie,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Attachment, AttachmentFacets, Page, User } from '@/app/lib/types';
import { startOfLocalDay, timeOf } from '@/app/lib/utils';

import AtRiskDeals, { type AtRiskItem } from '@/app/components/dashboard/AtRiskDeals';
import CoolingRelationships, { type CoolingItem } from '@/app/components/dashboard/CoolingRelationships';
import Greeting from '@/app/components/dashboard/Greeting';
import IntroOpportunities from '@/app/components/dashboard/IntroOpportunities';
import OverviewCard from '@/app/components/dashboard/OverviewCard';
import PipelineChart from '@/app/components/dashboard/PipelineChart';
import RecentFiles from '@/app/components/dashboard/RecentFiles';
import RecentMoves from '@/app/components/dashboard/RecentMoves';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import TaskSummary from '@/app/components/dashboard/TaskSummary';
import Timeline from '@/app/components/me/Timeline';

const DAY = 1000 * 60 * 60 * 24;

export default async function Dashboard() {
    const t = await getTranslations('DashboardPage');

    // TODO: move this somewhere else, or use the user object from layout.tsx
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const emptyFacets: AttachmentFacets = { sources: [], kinds: [], tags: [], orphaned: 0, total: 0, totalSize: 0 };
    const [companies, contacts, deals, pipelines, tasks, activities, notes, users, recentFiles, fileFacets, contactTemps, recentMoves, introSuggestions, dealRisks] =
        await Promise.all([
            getCompaniesFromCookie(cookie),
            getContactsFromCookie(cookie),
            getDealsFromCookie(cookie),
            getPipelinesFromCookie(cookie),
            getTasksFromCookie(cookie),
            getActivitiesFromCookie(cookie),
            getNotesFromCookie(cookie),
            getUsers(init).catch(() => [] as User[]),
            getAttachmentsPage({ size: 6, sort: 'newest' }, init).catch(
                () => ({ items: [], total: 0 }) as Page<Attachment>,
            ),
            getAttachmentFacets(init).catch(() => emptyFacets),
            getContactTemperaturesFromCookie(cookie),
            getRecentMovesFromCookie(cookie),
            getIntroSuggestionsFromCookie(cookie, 4),
            getDealRisksFromCookie(cookie),
        ]);

    const companyById = new Map(companies.map((company) => [company.id, company]));
    const dealById = new Map(deals.map((deal) => [deal.id, deal]));
    const atRiskDeals: AtRiskItem[] = dealRisks
        .map((risk) => ({ risk, deal: dealById.get(risk.dealId) }))
        .filter((entry): entry is { risk: (typeof dealRisks)[number]; deal: (typeof deals)[number] } => entry.deal != null)
        .slice(0, 6)
        .map(({ risk, deal }) => ({
            risk,
            deal,
            company: deal.company != null ? companyById.get(deal.company) : undefined,
        }));

    const tempByContactId = new Map(contactTemps.map((temp) => [temp.id, temp]));
    const coolingContacts: CoolingItem[] = contacts
        .map((contact) => ({ contact, temp: tempByContactId.get(contact.id) }))
        .filter((item): item is CoolingItem => item.temp != null && item.temp.trend === 'cooling')
        .sort((a, b) => (b.temp.daysSinceTouch ?? 0) - (a.temp.daysSinceTouch ?? 0))
        .slice(0, 6);

    // TODO: move this to it's own separate component so it can be reused elsewhere
    const now = new Date().getTime();
    const todayStart = startOfLocalDay(now);
    const overdueTasks = tasks.filter((t) => {
        if (t.completed) return false;
        const due = timeOf(t.dueDate);
        return due > 0 && due < todayStart;
    }).length;
    // TODO: use the function from @/app/components/dashboard/TaskSummary.tsx, or put it into the utils file and call it from both places idk just want to get this done rn
    const closingSoon = deals.filter((d) => {
        if (d.closedAt) return false;
        const t = timeOf(d.expectedCloseDate);
        return t >= todayStart && t - todayStart <= 7 * DAY;
    }).length;
    const dueSoon = tasks.filter((tk) => {
        if (tk.completed) return false;
        const due = timeOf(tk.dueDate);
        return due >= todayStart && due - todayStart <= 7 * DAY;
    }).length;
    const upcomingActivities = activities.filter((a) => {
        const ts = timeOf(a.timestamp);
        return ts > now && ts - now <= 7 * DAY;
    }).length;

    return (
        <div className="min-h-screen bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise>
                    <Greeting
                        user={user}
                        overdueTasks={overdueTasks}
                        dueSoon={dueSoon}
                        closingSoon={closingSoon}
                        upcomingActivities={upcomingActivities}
                    />
                </Rise>

                <section>
                    <SectionHeader title={t('overview')} />
                    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                        <OverviewCard
                            index={0}
                            label={t('companies')}
                            value={companies.length}
                            icon={BuildingOffice2Icon}
                            href="/records/companies"
                            description={t('companiesDescription')}
                        />
                        <OverviewCard
                            index={1}
                            label={t('contacts')}
                            value={contacts.length}
                            icon={UsersIcon}
                            href="/records/contacts"
                        />
                        <OverviewCard
                            index={2}
                            label={t('deals')}
                            value={deals.length}
                            icon={BriefcaseIcon}
                            href="/records/deals"
                            description={t('dealsDescription')}
                        />
                        <OverviewCard
                            index={3}
                            label={t('pipelines')}
                            value={pipelines.length}
                            icon={FunnelIcon}
                            href="/records/pipelines"
                        />
                    </div>
                </section>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                    <Rise delay={0.24} className="flex flex-col">
                        <SectionHeader title={t('pipeline')} />
                        <PipelineChart deals={deals} />
                    </Rise>
                    <Rise delay={0.3} className="flex flex-col">
                        <SectionHeader title={t('tasks')} />
                        <TaskSummary tasks={tasks} />
                    </Rise>
                </div>

                <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                    <Rise delay={0.31} className="flex flex-col">
                        <SectionHeader
                            title={t('atRiskDeals')}
                            action={
                                <Link
                                    href="/records/deals"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewDeals')}
                                </Link>
                            }
                        />
                        <AtRiskDeals items={atRiskDeals} />
                    </Rise>
                    <Rise delay={0.33} className="flex flex-col">
                        <SectionHeader
                            title={t('coolingRelationships')}
                            action={
                                <Link
                                    href="/overview/map"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewMap')}
                                </Link>
                            }
                        />
                        <CoolingRelationships items={coolingContacts} currentUserId={user.id} />
                    </Rise>
                    <Rise delay={0.36} className="flex flex-col">
                        <SectionHeader
                            title={t('recentlyMoved')}
                            action={
                                <Link
                                    href="/records/contacts"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewAll')}
                                </Link>
                            }
                        />
                        <RecentMoves moves={recentMoves} />
                    </Rise>
                </div>

                <Rise delay={0.38}>
                    <section>
                        <SectionHeader
                            title={t('introductions')}
                            action={
                                <Link
                                    href="/overview/introductions"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewAll')}
                                </Link>
                            }
                        />
                        <IntroOpportunities items={introSuggestions} />
                    </section>
                </Rise>

                <Rise delay={0.39}>
                    <section>
                        <SectionHeader
                            title={t('files')}
                            action={
                                <Link
                                    href="/library/files"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewAll')}
                                </Link>
                            }
                        />
                        <RecentFiles
                            files={recentFiles.items}
                            total={fileFacets.total}
                            totalSize={fileFacets.totalSize}
                        />
                    </section>
                </Rise>

                <Rise delay={0.42}>
                    <section>
                        <SectionHeader
                            title={t('recentActivity')}
                            action={
                                <Link
                                    href="/activity/all"
                                    className="text-xs text-brand hover:text-brand-hover"
                                >
                                    {t('viewAll')}
                                </Link>
                            }
                        />
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <Timeline
                                tasks={tasks}
                                activities={activities}
                                notes={notes}
                                users={users}
                                persons={contacts}
                                deals={deals}
                                currentUserId={user.id}
                                limit={8}
                            />
                        </div>
                    </section>
                </Rise>
            </div>
        </div>
    );
}