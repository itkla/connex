import type { Activity, Deal, Note, Task } from '@/app/lib/types';
import { dealOutcome } from '@/app/components/records/deals/dealOutcome';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { computeCompanyMetrics } from './metrics';
import type { AppNode, DealSummary, Graph, GraphInput, RelationEdge } from './types';

export const UC_ID = 'uc';
const userNodeId = (id: number) => `user-${id}`;
export const companyNodeId = (id: number) => `company-${id}`;
export const contactNodeId = (id: number) => `contact-${id}`;

export const COLOR_UC = '#3b82f6'; // green, like default branding
export const COLOR_WON = '#22c55e'; // greenish
export const COLOR_LOST = '#ef4444'; // redish

export function collectActiveContactIds(
    activities: Activity[],
    tasks: Task[],
    notes: Note[],
): Set<number> {
    const s = new Set<number>();
    for (const a of activities) if (a.personId != null) s.add(a.personId);
    for (const t of tasks) if (t.personId != null) s.add(t.personId);
    for (const n of notes) if (n.person != null) s.add(n.person);
    return s;
}

function ccColorFor(summaries: DealSummary[]): string {
    const closed = summaries
        .filter((s) => s.outcome === 'won' || s.outcome === 'lost')
        .sort((a, b) => parseMysqlDateTime(b.closedAt) - parseMysqlDateTime(a.closedAt));
    const latest = closed[0]?.outcome;
    if (latest === 'won') return COLOR_WON;
    if (latest === 'lost') return COLOR_LOST;
    return COLOR_UC; // open-only / no resolved outcome -> uniform blue line
}

export function buildGraph(input: GraphInput): Graph {
    const { companies, contacts, deals, users, activities, tasks, notes, stageNames, ucLabel, contactWarmth, companyWarmth } = input;

    const metricsLists = { contacts, deals, activities, tasks, notes, users };
    const activeContactIds = collectActiveContactIds(activities, tasks, notes);

    const nodes: AppNode[] = [];
    const edges: RelationEdge[] = [];
    const companyPresent = new Set<number>();

    nodes.push({
        id: UC_ID,
        type: 'uc',
        position: { x: 0, y: 0 },
        data: { kind: 'uc', label: ucLabel },
    });

    for (const u of users) {
        nodes.push({
            id: userNodeId(u.id),
            type: 'user',
            position: { x: 0, y: 0 },
            data: { kind: 'user', user: u },
        });
        edges.push({
            id: `${UC_ID}--${userNodeId(u.id)}`,
            type: 'relation',
            source: UC_ID,
            target: userNodeId(u.id),
            data: { variant: 'uc-user', dashed: false },
        });
    }

    const dealsByCompany = new Map<number, Deal[]>();
    for (const d of deals) {
        if (d.company == null) continue;
        const arr = dealsByCompany.get(d.company);
        if (arr) arr.push(d);
        else dealsByCompany.set(d.company, [d]);
    }

    for (const c of companies) {
        companyPresent.add(c.id);
        const metrics = computeCompanyMetrics(c, metricsLists);
        nodes.push({
            id: companyNodeId(c.id),
            type: 'company',
            position: { x: 0, y: 0 },
            data: { kind: 'company', company: c, metrics, warmth: companyWarmth?.get(c.id), expanded: false },
        });

        const companyDeals = dealsByCompany.get(c.id) ?? [];
        const summaries: DealSummary[] = companyDeals.map((d) => {
            const stageName = d.stage != null ? stageNames.get(d.stage) : undefined;
            return {
                id: d.id,
                name: d.name,
                outcome: dealOutcome(d.won),
                value: d.value,
                currency: d.currency,
                stageName,
                closedAt: d.closedAt,
            };
        });

        const relData = {
            variant: 'rel-cc' as const,
            dashed: companyDeals.length === 0, // dashed = exists, no deals
            ucColor: COLOR_UC,
            ccColor: ccColorFor(summaries),
            deals: summaries,
        };

        const relatedUserIds = metrics.relatedUsers.map((u) => u.id);
        const sources = relatedUserIds.length > 0 ? relatedUserIds.map(userNodeId) : [UC_ID];
        for (const source of sources) {
            edges.push({
                id: `${source}--${companyNodeId(c.id)}`,
                type: 'relation',
                source,
                target: companyNodeId(c.id),
                data: relData,
            });
        }
    }

    for (const c of contacts) {
        nodes.push({
            id: contactNodeId(c.id),
            type: 'contact',
            position: { x: 0, y: 0 },
            data: { kind: 'contact', contact: c, hasActivity: activeContactIds.has(c.id), warmth: contactWarmth?.get(c.id), expanded: false },
        });

        const parent =
            c.companyId != null && companyPresent.has(c.companyId)
                ? companyNodeId(c.companyId)
                : UC_ID;

        edges.push({
            id: `${parent}--${contactNodeId(c.id)}`,
            type: 'relation',
            source: parent,
            target: contactNodeId(c.id),
            data: { variant: 'cc-co', dashed: false },
        });
    }

    return { nodes, edges };
}