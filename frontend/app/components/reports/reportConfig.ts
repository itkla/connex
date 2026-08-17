import type {
    ReportChartType,
    ReportConfig,
    ReportDataSource,
    ReportGroupBy,
    ReportLayoutItem,
    ReportMeasure,
    ReportTemplate,
    ReportWidgetConfig,
    ReportWidgetData,
} from '@/app/lib/types';

export type ReportTemplateGroupId = 'pipeline' | 'relationships' | 'other';

/**
 * Curates the built-in report templates into intent-based clusters so the landing
 * page can present them as "start a report" outcomes rather than a flat card wall.
 * Any template key the backend introduces that is not listed here falls into the
 * `other` group so new templates always remain reachable.
 */
const REPORT_TEMPLATE_GROUPS: { id: Exclude<ReportTemplateGroupId, 'other'>; keys: string[] }[] = [
    {
        id: 'pipeline',
        keys: ['lead-lifecycle', 'sales-performance', 'pipeline-health', 'forecasting', 'quota-attainment', 'commercial-documents'],
    },
    {
        id: 'relationships',
        keys: ['relationship-coverage', 'relationship-health', 'network-warm-intros', 'employment-moves', 'activity-team'],
    },
];

export function groupReportTemplates(
    templates: ReportTemplate[],
): { id: ReportTemplateGroupId; templates: ReportTemplate[] }[] {
    const byKey = new Map(templates.map((template) => [template.key, template]));
    const claimed = new Set<string>();
    const groups: { id: ReportTemplateGroupId; templates: ReportTemplate[] }[] = [];
    for (const group of REPORT_TEMPLATE_GROUPS) {
        const members = group.keys
            .map((key) => byKey.get(key))
            .filter((template): template is ReportTemplate => template !== undefined);
        members.forEach((template) => claimed.add(template.key));
        if (members.length > 0) groups.push({ id: group.id, templates: members });
    }
    const rest = templates.filter((template) => !claimed.has(template.key));
    if (rest.length > 0) groups.push({ id: 'other', templates: rest });
    return groups;
}

/**
 * The distinct measures a template presents, in first-seen order, so a template
 * card can show what evidence the report contains before it is created.
 */
export function reportTemplateMeasures(template: ReportTemplate): ReportMeasure[] {
    const seen = new Set<ReportMeasure>();
    const measures: ReportMeasure[] = [];
    for (const widget of template.config.widgets) {
        if (!seen.has(widget.measure)) {
            seen.add(widget.measure);
            measures.push(widget.measure);
        }
    }
    return measures;
}

export const REPORT_DATA_SOURCES: ReportDataSource[] = [
    'deals',
    'people',
    'companies',
    'activities',
    'tasks',
    'relationships',
    'documents',
    'leads',
];

export const REPORT_CHART_TYPES: ReportChartType[] = ['bar', 'line-area', 'donut', 'funnel', 'table', 'kpi'];

export const CURRENT_STATE_REPORT_MEASURES: ReadonlySet<ReportMeasure> = new Set([
    'warm_intro_opportunity_value',
    'warm_intro_reachable_account_count',
    'reverse_intro_weighted_opportunities',
]);

export const REPORT_MEASURES: Record<ReportDataSource, ReportMeasure[]> = {
    deals: [
        'count',
        'new_pipeline_value',
        'won_revenue',
        'attainment',
        'win_rate',
        'avg_cycle_days',
        'open_pipeline_value',
        'open_deal_count',
        'forecast_best',
        'forecast_weighted',
        'forecast_worst',
        'at_risk_revenue',
        'single_threaded_deal_count',
        'single_threaded_deal_value',
        'effective_discount_percent',
        'open_discount_percent',
    ],
    people: ['count', 'employment_departure_count', 'employment_arrival_count'],
    companies: [
        'count',
        'coverage_gap_count',
        'coverage_gap_open_pipeline_value',
        'warm_intro_opportunity_value',
        'warm_intro_reachable_account_count',
    ],
    activities: ['count'],
    tasks: ['count'],
    relationships: ['count', 'company_count', 'reverse_intro_weighted_opportunities'],
    documents: [
        'quote_count',
        'quote_issue_rate',
        'document_to_win_rate',
        'approval_decision_count',
        'approval_cycle_days',
    ],
    leads: [
        'lead_count',
        'qualified_count',
        'converted_count',
        'disqualified_count',
        'qualification_rate',
        'conversion_rate',
        'time_to_convert_days',
        'first_response_hours',
        'first_response_breach_rate',
    ],
};

export const REPORT_GROUPS: Record<ReportDataSource, ReportGroupBy[]> = {
    deals: ['none', 'date', 'pipeline', 'stage', 'owner', 'status', 'company', 'deal'],
    people: ['none', 'company'],
    companies: ['none', 'industry', 'company'],
    activities: ['none', 'date', 'activity_type', 'owner'],
    tasks: ['none', 'date', 'status', 'owner'],
    relationships: ['none', 'warmth_band', 'trend'],
    documents: ['none', 'date', 'owner', 'company'],
    leads: ['none', 'date', 'owner', 'lead_source'],
};

export function reportGroupsForMeasure(
    dataSource: ReportDataSource,
    measure: ReportMeasure,
): ReportGroupBy[] {
    if (measure === 'at_risk_revenue') return ['none', 'risk'];
    if (measure === 'single_threaded_deal_count' || measure === 'single_threaded_deal_value') {
        return ['none', 'company', 'deal'];
    }
    if (measure === 'coverage_gap_count' || measure === 'coverage_gap_open_pipeline_value') {
        return ['none', 'company'];
    }
    if (measure === 'warm_intro_opportunity_value') return ['none', 'company', 'connector'];
    if (measure === 'warm_intro_reachable_account_count') return ['none', 'connector'];
    if (measure === 'reverse_intro_weighted_opportunities') return ['none', 'pair'];
    if (measure === 'employment_departure_count' || measure === 'employment_arrival_count') {
        return ['none', 'date', 'company', 'person'];
    }
    if (measure === 'forecast_best' || measure === 'forecast_weighted' || measure === 'forecast_worst') {
        return ['none', 'date', 'pipeline', 'stage'];
    }
    if (measure === 'effective_discount_percent' || measure === 'open_discount_percent') {
        return ['none', 'date', 'pipeline', 'stage', 'owner', 'company'];
    }
    if (measure === 'attainment') return ['none', 'owner'];
    if (dataSource === 'leads') return REPORT_GROUPS.leads;
    if (dataSource === 'documents') return REPORT_GROUPS.documents;
    if (dataSource === 'companies') return ['none', 'industry'];
    if (dataSource === 'deals') return REPORT_GROUPS.deals.filter((group) => group !== 'deal');
    return REPORT_GROUPS[dataSource];
}

const SAMPLE_LABELS = ['A', 'B', 'C', 'D', 'E'];
const SAMPLE_VALUES = [82, 64, 47, 33, 21];
const SAMPLE_PRIOR = [70, 58, 51, 30, 18];

/**
 * Builds representative sample figures for the builder's live shape preview, so an
 * author can see how a chosen presentation renders before the report is generated.
 * The values are illustrative only; the real report always recomputes from workspace
 * data, so the preview is clearly labelled as a sample by its host component.
 */
export function sampleReportWidgetData(widget: ReportWidgetConfig): ReportWidgetData {
    const singleValue = widget.chartType === 'kpi' && (widget.groupBy ?? 'none') === 'none';
    const count = singleValue ? 1 : widget.chartType === 'donut' || widget.chartType === 'funnel' ? 4 : 5;
    const points = SAMPLE_VALUES.slice(0, count).map((value, index) => ({
        key: `sample-${index}`,
        label: SAMPLE_LABELS[index] ?? `${index + 1}`,
        value,
        priorValue: SAMPLE_PRIOR[index] ?? null,
        sourceId: `sample-${index}`,
    }));
    return {
        widgetId: widget.id,
        title: widget.title ?? '',
        chartType: widget.chartType,
        dataSource: widget.dataSource,
        measure: widget.measure,
        groupBy: widget.groupBy,
        unit: null,
        total: singleValue ? 128 : SAMPLE_VALUES.slice(0, count).reduce((sum, value) => sum + value, 0),
        priorTotal: singleValue ? 112 : null,
        changePercent: singleValue ? 14.3 : null,
        points,
    };
}

export function newReportWidget(index: number): ReportWidgetConfig {
    const suffix = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : Date.now().toString(36);
    return {
        id: `widget-${index}-${suffix}`,
        title: null,
        dataSource: 'deals',
        measure: 'count',
        groupBy: 'date',
        chartType: 'bar',
    };
}

export function blankReportConfig(): ReportConfig {
    const widget = newReportWidget(0);
    return {
        widgets: [widget],
        filters: {
            pipelineIds: null,
            ownerIds: null,
            statuses: null,
            tagIds: null,
            warmthBands: null,
        },
        range: null,
        bucket: 'week',
        layout: [{ widgetId: widget.id, x: 0, y: 0, width: 12, height: 4 }],
    };
}

export function cloneReportConfig(config: ReportConfig): ReportConfig {
    return {
        widgets: config.widgets.map((widget) => ({ ...widget })),
        filters: config.filters
            ? {
                pipelineIds: config.filters.pipelineIds ? [...config.filters.pipelineIds] : null,
                ownerIds: config.filters.ownerIds ? [...config.filters.ownerIds] : null,
                statuses: config.filters.statuses ? [...config.filters.statuses] : null,
                tagIds: config.filters.tagIds ? [...config.filters.tagIds] : null,
                warmthBands: config.filters.warmthBands ? [...config.filters.warmthBands] : null,
            }
            : null,
        range: config.range ? { ...config.range } : null,
        bucket: config.bucket,
        layout: config.layout.map((item) => ({ ...item })),
    };
}

export function reflowReportLayout(
    widgets: ReportWidgetConfig[],
    existing: ReportLayoutItem[],
): ReportLayoutItem[] {
    const widthById = new Map(existing.map((item) => [item.widgetId, item.width >= 12 ? 12 : 6]));
    let row = 0;
    let column = 0;
    return widgets.map((widget) => {
        const width = widthById.get(widget.id) ?? 6;
        if (column + width > 12) {
            row += 1;
            column = 0;
        }
        const item = { widgetId: widget.id, x: column, y: row * 4, width, height: 4 };
        column += width;
        if (column >= 12) {
            row += 1;
            column = 0;
        }
        return item;
    });
}
