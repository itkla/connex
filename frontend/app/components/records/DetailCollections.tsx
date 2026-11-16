'use client';

import { useLocale, useTimeZone, useTranslations } from 'next-intl';
import Link from 'next/link';
import type { ComponentProps } from 'react';

import { useNow } from '@/app/hooks/useNow';
import DealBriefPanel from '@/app/components/records/deals/DealBriefPanel';
import { buildDealCitationTitles } from '@/app/lib/dealBriefCitations';
import type { Contact } from '@/app/lib/types';
import { useDetailSections } from '@/app/hooks/useDetailSections';
import DetailSectionState from '@/app/components/records/DetailSectionState';
import ContactsGrid from '@/app/components/records/companies/ContactsGrid';
import PipelineCard from '@/app/components/records/PipelineCard';
import DealTaskList from '@/app/components/records/deals/DealTaskList';
import DealLineItems from '@/app/components/records/deals/DealLineItems';
import DealDocuments from '@/app/components/records/deals/DealDocuments';
import ContactConnections from '@/app/components/records/contacts/ContactConnections';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { EngagementSparkline } from '@/app/components/records/companies/CompanyCard';
import DealActivityBreakdown from '@/app/components/records/deals/DealActivityBreakdown';
import { formatShortDate, parseMysqlDateTime } from '@/app/lib/utils';

/** Company collections recover independently; neither owns the separately loaded chronology. */
export function CompanyPeopleSection(props: Omit<ComponentProps<typeof ContactsGrid>, 'contacts'>) {
    const result = useDetailSections()?.results.companyPeople;
    const t = useTranslations('CompaniesContactsGrid');
    return <DetailSectionState sectionKeys={['companyPeople']} title={t('contacts')}>
        {result?.kind === 'loaded' ? <ContactsGrid {...props} contacts={result.data} /> : null}
    </DetailSectionState>;
}

/** Renders only the company-deals request outcome. */
export function CompanyDealsSection() {
    const result = useDetailSections()?.results.companyDeals;
    const t = useTranslations('CompaniesDetail');
    return <DetailSectionState sectionKeys={['companyDeals']} title={t('deals')}>
        {result?.kind === 'loaded' ? <PipelineCard deals={result.data} render="active" /> : null}
    </DetailSectionState>;
}

/** Keeps task controls synchronized with isolated task recovery. */
export function DealTasksSection(props: Omit<ComponentProps<typeof DealTaskList>, 'tasks'>) {
    const result = useDetailSections()?.results.dealTasks;
    const t = useTranslations('DealsPage');
    return <DetailSectionState sectionKeys={['dealTasks']} title={t('openTasks')}>
        {result?.kind === 'loaded' ? <DealTaskList {...props} tasks={result.data} /> : null}
    </DetailSectionState>;
}

/** Never mounts the editor with invented line-item totals. */
export function DealLineItemsSection(props: Omit<ComponentProps<typeof DealLineItems>, 'initial' | 'embedded'>) {
    const result = useDetailSections()?.results.dealLineItems;
    const t = useTranslations('DealsLineItems');
    return <DetailSectionState sectionKeys={['dealLineItems']} title={t('title')}>
        {result?.kind === 'loaded' ? <DealLineItems {...props} initial={result.data} embedded /> : null}
    </DetailSectionState>;
}

/** Document recovery leaves the line-item and attachment requests untouched. */
export function DealDocumentsSection(props: Omit<ComponentProps<typeof DealDocuments>, 'initial'>) {
    const result = useDetailSections()?.results.dealDocuments;
    const t = useTranslations('DealsDocuments');
    return <DetailSectionState sectionKeys={['dealDocuments']} title={t('title')}>
        {result?.kind === 'loaded' ? <DealDocuments {...props} initial={result.data} /> : null}
    </DetailSectionState>;
}

/** Employment history keeps its existing successful-empty presentation. */
export function ContactEmploymentSection() {
    const result = useDetailSections()?.results.contactEmployment;
    const t = useTranslations('ContactsPage');
    const locale = useLocale();
    const zone = useTimeZone();
    return <DetailSectionState sectionKeys={['contactEmployment']} title={t('employmentHistory')}>
        {result?.kind === 'loaded' && result.data.length > 0 ? <div>
            <SectionHeader title={t('employmentHistory')} />
            <ol className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                {result.data.map((stint) => <li key={stint.id} className="px-6 py-4 xl:px-4">
                    <div className="flex items-center justify-between gap-2">
                        {stint.companyId ? <Link href={`/records/companies/${stint.companyId}`} className="truncate text-sm font-medium text-foreground transition-colors hover:text-brand-text">
                            {stint.companyName ?? t('unknownCompany')}
                        </Link> : <span className="truncate text-sm font-medium text-foreground">{stint.companyName ?? t('unknownCompany')}</span>}
                        {stint.current ? <span className="shrink-0 rounded-full bg-brand-light px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-brand-text">{t('current')}</span> : null}
                    </div>
                    {stint.title ? <p className="mt-0.5 text-xs text-muted-foreground">{stint.title}</p> : null}
                    <p className="mt-1 text-xs tabular-nums text-muted-foreground">
                        {formatShortDate(stint.startedAt ?? undefined, locale, zone)} – {stint.endedAt ? formatShortDate(stint.endedAt, locale, zone) : t('present')}
                    </p>
                </li>)}
            </ol>
        </div> : null}
    </DetailSectionState>;
}

/** Connections and paths own separate recovery controls inside their shared panel. */
export function ContactConnectionsSection(props: Pick<ComponentProps<typeof ContactConnections>, 'contactId' | 'contactName'>) {
    const results = useDetailSections()?.results;
    return <ContactConnections {...props}
        initialConnections={results?.contactConnections?.kind === 'loaded' ? results.contactConnections.data : []}
        initialIntroPath={results?.contactIntroPath?.kind === 'loaded' ? results.contactIntroPath.data : null}
    />;
}

/** Avoids claiming an absence of interactions when lifecycle history was unavailable. */
export function ContactHistoryQuietState() {
    const result = useDetailSections()?.results.contactHistory;
    const t = useTranslations('ContactsPage');
    return result?.kind === 'loaded' && result.data.length === 0 ? <>{t('noRecordedInteractions')}</> : null;
}

/** Engagement totals are derived afresh from the same recovered collections as the timeline. */
export function DealEngagementSection({ now }: { now?: number }) {
    const results = useDetailSections()?.results;
    const t = useTranslations('DealsPage');
    const activities = results?.dealActivities;
    const notes = results?.dealNotes;
    const tasks = results?.dealTasks;
    const complete = activities?.kind === 'loaded' && notes?.kind === 'loaded' && tasks?.kind === 'loaded';
    const week = 7 * 24 * 60 * 60 * 1000;
    const sharedNow = useNow();
    const start = (now ?? sharedNow) - 11 * week;
    const points = Array.from({ length: 12 }, (_, index) => ({ weekStart: start + index * week, count: 0, activities: 0, tasks: 0, notes: 0 }));
    if (complete) {
        const bucket = (date: string | undefined, kind: 'activities' | 'tasks' | 'notes') => {
            const index = Math.floor((parseMysqlDateTime(date) - start) / week);
            if (index < 0 || index >= points.length || !Number.isFinite(index)) return;
            points[index][kind]++;
            points[index].count++;
        };
        for (const item of activities.data) bucket(item.timestamp, 'activities');
        for (const item of tasks.data) bucket(item.createdAt, 'tasks');
        for (const item of notes.data) bucket(item.createdAt, 'notes');
    }
    return <DetailSectionState sectionKeys={['dealActivities', 'dealNotes', 'dealTasks']} title={t('engagement')}>
        {complete ? <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <EngagementSparkline data={points} /><DealActivityBreakdown activities={activities.data} />
        </div> : null}
    </DetailSectionState>;
}

/** Citation labels follow recovered history without regenerating the brief or reloading unrelated records. */
export function DealBriefSection({ dealId, dealName, people, className }: {
    dealId: number; dealName: string; people: { person: Contact }[]; className?: string;
}) {
    const results = useDetailSections()?.results;
    const titles = buildDealCitationTitles(dealId, dealName, people,
        results?.dealActivities?.kind === 'loaded' ? results.dealActivities.data : [],
        results?.dealTasks?.kind === 'loaded' ? results.dealTasks.data : [],
        results?.dealNotes?.kind === 'loaded' ? results.dealNotes.data : []);
    return <DealBriefPanel dealId={dealId} className={className} citationTitles={titles} />;
}
