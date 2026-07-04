import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';
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
    getDashboardLayoutFromCookie,
    getDealRisksFromCookie,
    getDealsFromCookie,
    getIntroSuggestionsFromCookie,
    getNotesFromCookie,
    getPipelinesFromCookie,
    getRecentMovesFromCookie,
    getTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Attachment, AttachmentFacets, DashboardWidgetType, Page, User } from '@/app/lib/types';
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
import TaskSummary from '@/app/components/dashboard/TaskSummary';
import Timeline from '@/app/components/me/Timeline';
import DashboardGrid from '@/app/components/dashboard/customize/DashboardGrid';
import { normalizeLayout } from '@/app/components/dashboard/customize/dashboardWidgets';

const DAY = 1000 * 60 * 60 * 24;

export default async function Dashboard() {
    const t = await getTranslations('DashboardPage');

    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const emptyFacets: AttachmentFacets = { sources: [], kinds: [], tags: [], orphaned: 0, total: 0, totalSize: 0 };
    const [companies, contacts, deals, pipelines, tasks, activities, notes, users, recentFiles, fileFacets, contactTemps, recentMoves, introSuggestions, dealRisks, layoutResponse] =
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
            getDashboardLayoutFromCookie(cookie),
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

    const now = new Date().getTime();
    const todayStart = startOfLocalDay(now);
    const overdueTasks = tasks.filter((task) => {
        if (task.completed) return false;
        const due = timeOf(task.dueDate);
        return due > 0 && due < todayStart;
    }).length;
    const closingSoon = deals.filter((deal) => {
        if (deal.closedAt) return false;
        const close = timeOf(deal.expectedCloseDate);
        return close >= todayStart && close - todayStart <= 7 * DAY;
    }).length;
    const dueSoon = tasks.filter((task) => {
        if (task.completed) return false;
        const due = timeOf(task.dueDate);
        return due >= todayStart && due - todayStart <= 7 * DAY;
    }).length;
    const upcomingActivities = activities.filter((activity) => {
        const ts = timeOf(activity.timestamp);
        return ts > now && ts - now <= 7 * DAY;
    }).length;

    const widgetNodes: Record<DashboardWidgetType, ReactNode> = {
        overview: (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <OverviewCard index={0} label={t('companies')} value={companies.length} icon={BuildingOffice2Icon} href="/records/companies" description={t('companiesDescription')} />
                <OverviewCard index={1} label={t('contacts')} value={contacts.length} icon={UsersIcon} href="/records/contacts" />
                <OverviewCard index={2} label={t('deals')} value={deals.length} icon={BriefcaseIcon} href="/records/deals" description={t('dealsDescription')} />
                <OverviewCard index={3} label={t('pipelines')} value={pipelines.length} icon={FunnelIcon} href="/records/pipelines" />
            </div>
        ),
        pipeline: <PipelineChart deals={deals} />,
        tasks: <TaskSummary tasks={tasks} />,
        atRiskDeals: <AtRiskDeals items={atRiskDeals} />,
        coolingRelationships: <CoolingRelationships items={coolingContacts} currentUserId={user.id} />,
        recentMoves: <RecentMoves moves={recentMoves} />,
        introOpportunities: <IntroOpportunities items={introSuggestions} />,
        recentFiles: <RecentFiles files={recentFiles.items} total={fileFacets.total} totalSize={fileFacets.totalSize} />,
        recentActivity: (
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <Timeline tasks={tasks} activities={activities} notes={notes} users={users} persons={contacts} deals={deals} currentUserId={user.id} limit={8} />
            </div>
        ),
    };

    const initialWidgets = normalizeLayout(layoutResponse.response?.layout);

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-8">
                <Rise>
                    <Greeting
                        user={user}
                        overdueTasks={overdueTasks}
                        dueSoon={dueSoon}
                        closingSoon={closingSoon}
                        upcomingActivities={upcomingActivities}
                    />
                </Rise>
                <Rise delay={0.18}>
                    <DashboardGrid
                        initialWidgets={initialWidgets}
                        nodes={widgetNodes}
                        layoutErrored={layoutResponse.errored}
                    />
                </Rise>
            </div>
        </div>
    );
}
