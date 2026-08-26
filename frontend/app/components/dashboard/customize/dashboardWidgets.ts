import type {
    DashboardWidgetInstance,
    DashboardWidgetSpan,
    DashboardWidgetType,
} from '@/app/lib/types';
import { DEAL_RISK_LEVELS, riskDealsHref } from '@/app/components/records/deals/dealLinks';
import { radarFamilyHref } from '@/app/components/radar/radarLinks';

/**
 * Static metadata for a dashboard widget type. Titles and action labels are `next-intl` keys in
 * the `DashboardPage` namespace; `actionHref` is the read-mode "view all" link (omitted when the
 * widget has no drill-in). `defaultSpan`/`allowedSpans` drive the 1↔2 column width control.
 */
export type WidgetMeta = {
    titleKey: string;
    defaultSpan: DashboardWidgetSpan;
    allowedSpans: DashboardWidgetSpan[];
    actionHref?: string;
    actionLabelKey?: string;
};

/**
 * The widget catalog. Order here is the canonical order used to render the add-widget tray.
 */
export const WIDGET_META: Record<DashboardWidgetType, WidgetMeta> = {
    overview: { titleKey: 'overview', defaultSpan: 2, allowedSpans: [2] },
    pipeline: { titleKey: 'pipeline', defaultSpan: 1, allowedSpans: [1, 2] },
    tasks: { titleKey: 'tasks', defaultSpan: 1, allowedSpans: [1, 2] },
    atRiskDeals: {
        titleKey: 'atRiskDeals',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: riskDealsHref(DEAL_RISK_LEVELS),
        actionLabelKey: 'viewAtRiskDeals',
    },
    coolingRelationships: {
        titleKey: 'coolingRelationships',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: radarFamilyHref('relationship_decay'),
        actionLabelKey: 'viewInRadar',
    },
    recentMoves: {
        titleKey: 'recentlyMoved',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: '/records/contacts',
        actionLabelKey: 'viewAll',
    },
    introOpportunities: {
        titleKey: 'introductions',
        defaultSpan: 2,
        allowedSpans: [1, 2],
        actionHref: '/intelligence/introductions',
        actionLabelKey: 'viewAll',
    },
    recentFiles: {
        titleKey: 'files',
        defaultSpan: 2,
        allowedSpans: [1, 2],
        actionHref: '/library/files',
        actionLabelKey: 'viewAll',
    },
    recentActivity: {
        titleKey: 'recentActivity',
        defaultSpan: 2,
        allowedSpans: [1, 2],
        actionHref: '/activity/all',
        actionLabelKey: 'viewAll',
    },
    companyWarmth: {
        titleKey: 'companyWarmth',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: '/records/companies',
        actionLabelKey: 'viewAll',
    },
    warmthDistribution: { titleKey: 'warmthDistribution', defaultSpan: 1, allowedSpans: [1, 2] },
    closingSoon: {
        titleKey: 'closingSoon',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: '/records/deals',
        actionLabelKey: 'viewDeals',
    },
    recentNotes: {
        titleKey: 'recentNotes',
        defaultSpan: 1,
        allowedSpans: [1, 2],
        actionHref: '/activity/all',
        actionLabelKey: 'viewAll',
    },
    notifications: { titleKey: 'notifications', defaultSpan: 1, allowedSpans: [1, 2] },
    quickActions: { titleKey: 'quickActions', defaultSpan: 1, allowedSpans: [1, 2] },
    analyticsKpis: { titleKey: 'analyticsKpis', defaultSpan: 2, allowedSpans: [2] },
    revenueTrend: { titleKey: 'revenueTrend', defaultSpan: 1, allowedSpans: [1, 2] },
    winRate: { titleKey: 'winRate', defaultSpan: 1, allowedSpans: [1, 2] },
    pipelineValue: { titleKey: 'pipelineValue', defaultSpan: 1, allowedSpans: [1, 2] },
    stageFunnel: { titleKey: 'stageFunnel', defaultSpan: 1, allowedSpans: [1, 2] },
    activityVolume: { titleKey: 'activityVolume', defaultSpan: 1, allowedSpans: [1, 2] },
    teamLeaderboard: { titleKey: 'teamLeaderboard', defaultSpan: 1, allowedSpans: [1, 2] },
};

/** Widget types in catalog order. */
export const ALL_WIDGET_TYPES = Object.keys(WIDGET_META) as DashboardWidgetType[];

/**
 * The default dashboard, reproducing the original static layout: a full-width KPI overview, the
 * pipeline + tasks row, the at-risk / cooling / recently-moved row, then the full-width
 * introductions, files, and activity sections.
 */
const DEFAULT_WIDGETS: DashboardWidgetInstance[] = [
    { id: 'overview', type: 'overview', span: 2 },
    { id: 'pipeline', type: 'pipeline', span: 1 },
    { id: 'tasks', type: 'tasks', span: 1 },
    { id: 'atRiskDeals', type: 'atRiskDeals', span: 1 },
    { id: 'coolingRelationships', type: 'coolingRelationships', span: 1 },
    { id: 'recentMoves', type: 'recentMoves', span: 1 },
    { id: 'introOpportunities', type: 'introOpportunities', span: 2 },
    { id: 'recentFiles', type: 'recentFiles', span: 2 },
    { id: 'recentActivity', type: 'recentActivity', span: 2 },
];

/** A fresh, mutable copy of the default widget list. */
export function defaultWidgets(): DashboardWidgetInstance[] {
    return DEFAULT_WIDGETS.map((widget) => ({ ...widget }));
}

function isKnownType(type: unknown): type is DashboardWidgetType {
    return typeof type === 'string' && Object.prototype.hasOwnProperty.call(WIDGET_META, type);
}

function normalizeSpan(type: DashboardWidgetType, span: unknown): DashboardWidgetSpan {
    const meta = WIDGET_META[type];
    return meta.allowedSpans.includes(span as DashboardWidgetSpan)
        ? (span as DashboardWidgetSpan)
        : meta.defaultSpan;
}

/**
 * Validates an untrusted persisted layout into a render-ready widget list. Unknown or malformed
 * widget types are dropped, spans are clamped to what the widget allows, and ids are de-duplicated.
 * A missing/malformed layout falls back to the default; an explicit empty widget list is respected
 * (the user removed everything), so the two are not conflated.
 */
export function normalizeLayout(raw: unknown): DashboardWidgetInstance[] {
    const widgets = (raw as { widgets?: unknown } | null | undefined)?.widgets;
    if (!Array.isArray(widgets)) {
        return defaultWidgets();
    }
    const seen = new Set<string>();
    const result: DashboardWidgetInstance[] = [];
    for (const entry of widgets) {
        const type = (entry as { type?: unknown } | null)?.type;
        if (!isKnownType(type)) continue;
        const rawId = (entry as { id?: unknown }).id;
        let id = typeof rawId === 'string' && rawId.length > 0 ? rawId : `${type}-${result.length}`;
        let suffix = 0;
        while (seen.has(id)) {
            id = `${type}-${result.length}-${suffix}`;
            suffix += 1;
        }
        seen.add(id);
        result.push({ id, type, span: normalizeSpan(type, (entry as { span?: unknown }).span) });
    }
    return result;
}

/** A unique widget-instance id for a newly added widget. */
export function newWidgetId(type: DashboardWidgetType): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return `${type}-${crypto.randomUUID()}`;
    }
    return `${type}-${Date.now().toString(36)}`;
}
