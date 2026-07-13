import type {
    ReportChartType,
    ReportConfig,
    ReportDataSource,
    ReportGroupBy,
    ReportLayoutItem,
    ReportMeasure,
    ReportWidgetConfig,
} from '@/app/lib/types';

export const REPORT_DATA_SOURCES: ReportDataSource[] = [
    'deals',
    'people',
    'companies',
    'activities',
    'tasks',
    'relationships',
];

export const REPORT_CHART_TYPES: ReportChartType[] = ['bar', 'line-area', 'donut', 'funnel', 'table', 'kpi'];

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
    ],
    people: ['count'],
    companies: ['count', 'coverage_gap_count', 'coverage_gap_open_pipeline_value'],
    activities: ['count'],
    tasks: ['count'],
    relationships: ['count', 'company_count'],
};

export const REPORT_GROUPS: Record<ReportDataSource, ReportGroupBy[]> = {
    deals: ['none', 'date', 'pipeline', 'stage', 'owner', 'status', 'company', 'deal'],
    people: ['none', 'company'],
    companies: ['none', 'industry', 'company'],
    activities: ['none', 'date', 'activity_type', 'owner'],
    tasks: ['none', 'date', 'status', 'owner'],
    relationships: ['none', 'warmth_band', 'trend'],
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
    if (measure === 'forecast_best' || measure === 'forecast_weighted' || measure === 'forecast_worst') {
        return ['none', 'date', 'pipeline', 'stage'];
    }
    if (measure === 'attainment') return ['none', 'owner'];
    if (dataSource === 'companies') return ['none', 'industry'];
    if (dataSource === 'deals') return REPORT_GROUPS.deals.filter((group) => group !== 'deal');
    return REPORT_GROUPS[dataSource];
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
