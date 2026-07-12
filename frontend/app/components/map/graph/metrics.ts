import type {
    Activity,
    Company,
    CompanyMetrics,
    Contact,
    Deal,
    Note,
    Task,
    User,
} from '@/app/lib/types';
import { parseMysqlDateTime, pickDominantCurrency } from '@/app/lib/utils';
import { isDealClosed } from '@/app/components/records/deals/dealOutcome';

// TODO: consolidate this with the other metric calculation functions used in the other components

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

export type MetricsLists = {
    contacts: Contact[];
    deals: Deal[];
    activities: Activity[];
    tasks: Task[];
    notes: Note[];
    users: User[];
};

export function computeCompanyMetrics(company: Company, lists: MetricsLists): CompanyMetrics {
    const { contacts, deals, activities, tasks, notes, users } = lists;

    const persons = contacts.filter((c) => c.companyId === company.id);
    const personIds = new Set(persons.map((p) => p.id));
    const companyDeals = deals.filter((d) => d.company === company.id);
    const dealIds = new Set(companyDeals.map((d) => d.id));

    const inScope = (personId?: number | null, dealId?: number | null) =>
        (personId != null && personIds.has(personId)) || (dealId != null && dealIds.has(dealId));

    const relActivities = activities.filter((a) => inScope(a.personId, a.dealId));
    const relTasks = tasks.filter((t) => inScope(t.personId, t.dealId));
    const relNotes = notes.filter((n) => inScope(n.person, n.deal));

    const userIds = new Set<number>();
    for (const a of relActivities) if (a.createdById != null) userIds.add(a.createdById);
    for (const n of relNotes) if (n.author != null) userIds.add(n.author);
    for (const t of relTasks) if (t.assignedToId != null) userIds.add(t.assignedToId);

    const now = Date.now();
    const firstWeekStart = now - 11 * WEEK_MS;
    const weeklyEngagement = Array.from({ length: 12 }, (_, i) => ({
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
    for (const a of relActivities) bucket(parseMysqlDateTime(a.timestamp), 'activities');
    for (const t of relTasks) bucket(parseMysqlDateTime(t.createdAt), 'tasks');
    for (const n of relNotes) bucket(parseMysqlDateTime(n.createdAt), 'notes');

    const currency = pickDominantCurrency(companyDeals);
    let pastRevenue = 0;
    let projectedRevenue = 0;
    for (const d of companyDeals) {
        if ((d.currency || 'USD') !== currency) continue;
        if (d.won === true) pastRevenue += d.actualValue ?? 0;
        else if (!isDealClosed(d)) projectedRevenue += d.value ?? 0;
    }

    return {
        persons,
        personCount: persons.length,
        relatedUsers: users.filter((u) => userIds.has(u.id)),
        relatedUserCount: userIds.size,
        pastRevenue,
        projectedRevenue,
        currency,
        numDeals: companyDeals.length,
        numTasks: relTasks.length,
        numActivities: relActivities.length,
        numNotes: relNotes.length,
        weeklyEngagement,
    };
}
