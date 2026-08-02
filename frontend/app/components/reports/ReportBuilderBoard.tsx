'use client';

import { type ReactNode, useId, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
    ArrowsPointingInIcon,
    ArrowsPointingOutIcon,
    PlusIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import SortableGrid, { type SortableGridMessages } from '@/app/components/layout/SortableGrid';
import ReportWidgetRenderer from '@/app/components/reports/ReportWidgetRenderer';
import {
    REPORT_CHART_TYPES,
    REPORT_DATA_SOURCES,
    REPORT_GROUPS,
    REPORT_MEASURES,
    blankReportConfig,
    cloneReportConfig,
    newReportWidget,
    reportGroupsForMeasure,
    reflowReportLayout,
    sampleReportWidgetData,
} from '@/app/components/reports/reportConfig';
import { createReport, updateReport } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    Pipeline,
    ReportCadence,
    ReportChartType,
    ReportConfig,
    ReportDataSource,
    ReportDefinition,
    ReportDefinitionInput,
    ReportFilters,
    ReportMeasure,
    ReportTemplate,
    ReportWidgetConfig,
    Tag,
    WorkspaceMember,
} from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';

const CADENCES: ReportCadence[] = ['weekly', 'monthly', 'quarterly', 'custom'];
const ATTAINMENT_CHARTS: ReadonlySet<ReportChartType> = new Set(['bar', 'kpi']);
const STATUSES = ['open', 'won', 'lost', 'todo', 'in_progress', 'done'] as const;
const WARMTH_BANDS = ['hot', 'warm', 'cool', 'cold'] as const;

function selectedFilters(filters: ReportFilters | null): ReportFilters {
    return filters ?? {
        pipelineIds: null,
        ownerIds: null,
        statuses: null,
        tagIds: null,
        warmthBands: null,
    };
}

function toggleValue<T>(values: T[] | null, value: T): T[] | null {
    const current = values ?? [];
    const next = current.includes(value) ? current.filter((item) => item !== value) : [...current, value];
    return next.length > 0 ? next : null;
}

export default function ReportBuilderBoard({
    initialReport,
    initialTemplate,
    pipelines,
    owners,
    ownersFailed,
    tags,
    canReadGoals,
}: {
    initialReport?: ReportDefinition;
    initialTemplate?: ReportTemplate;
    pipelines: Pipeline[];
    owners: WorkspaceMember[];
    ownersFailed: boolean;
    tags: Tag[];
    canReadGoals: boolean;
}) {
    const t = useTranslations('Reports');
    const router = useRouter();
    const reduceMotion = useReducedMotion() ?? false;
    const initialConfig = initialReport
        ? cloneReportConfig(initialReport.config)
        : initialTemplate
            ? cloneReportConfig(initialTemplate.config)
            : blankReportConfig();
    const localizedInitialConfig = initialTemplate && !initialReport
        ? {
            ...initialConfig,
            widgets: initialConfig.widgets.map((widget) => ({
                ...widget,
                title: t(`measure.${widget.measure}`),
            })),
        }
        : initialConfig;
    const [name, setName] = useState(
        initialReport?.name ?? (initialTemplate ? t(`templates.${initialTemplate.key}.name`) : ''),
    );
    const [description, setDescription] = useState(
        initialReport?.description ?? (initialTemplate ? t(`templates.${initialTemplate.key}.description`) : ''),
    );
    const [cadence, setCadence] = useState<ReportCadence>(
        initialReport?.cadence ?? initialTemplate?.cadence ?? 'monthly',
    );
    const [config, setConfig] = useState<ReportConfig>(localizedInitialConfig);
    const [saving, setSaving] = useState(false);

    const layoutById = useMemo(
        () => new Map(config.layout.map((item) => [item.widgetId, item])),
        [config.layout],
    );
    const filters = selectedFilters(config.filters);
    const ownerOptions = useMemo(() => {
        const options = owners.map((owner) => ({ value: owner.id, label: owner.displayName }));
        const availableIds = new Set(owners.map((owner) => owner.id));
        for (const ownerId of filters.ownerIds ?? []) {
            if (!availableIds.has(ownerId)) {
                options.push({ value: ownerId, label: t('builder.unavailableOwner', { id: ownerId }) });
                availableIds.add(ownerId);
            }
        }
        return options;
    }, [filters.ownerIds, owners, t]);
    const unavailableOwnerIds = useMemo(() => {
        const availableIds = new Set(owners.map((owner) => owner.id));
        return [...new Set((filters.ownerIds ?? []).filter((ownerId) => !availableIds.has(ownerId)))];
    }, [filters.ownerIds, owners]);
    const ownerFiltersUnresolved = unavailableOwnerIds.length > 0;
    const hasAttainment = config.widgets.some((widget) => widget.measure === 'attainment');
    const hasWorkspaceAttainment = config.widgets.some((widget) =>
        widget.measure === 'attainment' && (widget.groupBy ?? 'none') === 'none');
    const cadenceOptions = hasAttainment
        ? CADENCES.filter((value) => value === 'monthly' || value === 'quarterly')
        : CADENCES;
    const messages: SortableGridMessages = {
        instructions: t('builder.dragInstructions'),
        handleLabel: (widgetName) => t('builder.dragHandle', { name: widgetName }),
        lifted: (widgetName) => t('builder.dragLifted', { name: widgetName }),
        over: (widgetName, target) => t('builder.dragOver', { name: widgetName, target }),
        dropped: (widgetName, target) => t('builder.dragDropped', { name: widgetName, target }),
        cancelled: (widgetName) => t('builder.dragCancelled', { name: widgetName }),
    };

    const setWidgets = (widgets: ReportWidgetConfig[]) => {
        setConfig((current) => ({
            ...current,
            widgets,
            layout: reflowReportLayout(widgets, current.layout),
        }));
    };
    const updateWidget = (id: string, patch: Partial<ReportWidgetConfig>) => {
        setConfig((current) => {
            const widgets = current.widgets.map((widget) => widget.id === id ? { ...widget, ...patch } : widget);
            return {
                ...current,
                widgets,
                layout: reflowReportLayout(widgets, current.layout),
            };
        });
    };
    const updateDataSource = (widget: ReportWidgetConfig, dataSource: ReportDataSource) => {
        updateWidget(widget.id, {
            dataSource,
            measure: REPORT_MEASURES[dataSource][0],
            groupBy: REPORT_GROUPS[dataSource][0],
        });
    };
    const updateMeasure = (widget: ReportWidgetConfig, measure: ReportMeasure) => {
        const groups = reportGroupsForMeasure(widget.dataSource, measure);
        const currentGroup = widget.groupBy ?? 'none';
        const groupBy = groups.includes(currentGroup)
            ? currentGroup
            : measure === 'at_risk_revenue' ? 'risk' : groups[0];
        const chartType = measure === 'attainment' && !ATTAINMENT_CHARTS.has(widget.chartType)
            ? 'bar'
            : widget.chartType;
        if (measure === 'attainment' && cadence !== 'monthly' && cadence !== 'quarterly') {
            setCadence('monthly');
        }
        updateWidget(widget.id, { measure, groupBy, chartType });
        if (measure === 'attainment') {
            setConfig((current) => ({
                ...current,
                filters: {
                    ...selectedFilters(current.filters),
                    pipelineIds: null,
                    statuses: null,
                    tagIds: null,
                    ...(groupBy === 'none' ? { ownerIds: null } : {}),
                },
            }));
        }
    };
    const updateGroup = (widget: ReportWidgetConfig, groupBy: ReportWidgetConfig['groupBy']) => {
        updateWidget(widget.id, { groupBy });
        if (widget.measure === 'attainment' && groupBy === 'none') {
            setConfig((current) => ({
                ...current,
                filters: { ...selectedFilters(current.filters), ownerIds: null },
            }));
        }
    };
    const addWidget = () => {
        if (config.widgets.length >= 16) return;
        const widget = newReportWidget(config.widgets.length);
        setWidgets([...config.widgets, widget]);
    };
    const removeWidget = (id: string) => {
        if (config.widgets.length <= 1) return;
        setWidgets(config.widgets.filter((widget) => widget.id !== id));
    };
    const toggleWidth = (id: string) => {
        const nextLayout = config.layout.map((item) => item.widgetId === id
            ? { ...item, width: item.width >= 12 ? 6 : 12 }
            : item);
        setConfig((current) => ({
            ...current,
            layout: reflowReportLayout(current.widgets, nextLayout),
        }));
    };
    const updateFilters = (patch: Partial<ReportFilters>) => {
        setConfig((current) => ({
            ...current,
            filters: { ...selectedFilters(current.filters), ...patch },
        }));
    };
    const clearUnavailableOwners = () => {
        const availableIds = new Set(owners.map((owner) => owner.id));
        const ownerIds = (filters.ownerIds ?? []).filter((ownerId) => availableIds.has(ownerId));
        updateFilters({ ownerIds: ownerIds.length > 0 ? ownerIds : null });
    };

    const save = async () => {
        if (ownerFiltersUnresolved) {
            toastError(t(ownersFailed ? 'builder.ownersLoadBlocked' : 'builder.ownersUnavailable'));
            return;
        }
        const trimmedName = name.trim();
        if (!trimmedName) {
            toastError(t('builder.nameRequired'));
            return;
        }
        if (cadence === 'custom' && (!config.range?.start || !config.range.end)) {
            toastError(t('builder.rangeRequired'));
            return;
        }
        const payload: ReportDefinitionInput = {
            name: trimmedName,
            description: description.trim() || null,
            cadence,
            templateKey: initialReport?.templateKey ?? initialTemplate?.key ?? null,
            config,
        };
        setSaving(true);
        try {
            const saved = initialReport
                ? await updateReport(initialReport.id, payload)
                : await createReport(payload);
            toastSuccess(t(initialReport ? 'builder.updated' : 'builder.created'));
            router.push(`/overview/reports/${saved.id}`);
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto w-full max-w-[100rem]">
                <header className="flex flex-wrap items-end justify-between gap-5 border-b border-border pb-6">
                    <div>
                        <p className="mb-2 text-xs font-medium uppercase tracking-[0.12em] text-brand-dark">
                            {t(initialReport ? 'builder.editEyebrow' : 'builder.newEyebrow')}
                        </p>
                        <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
                            {t(initialReport ? 'builder.editTitle' : 'builder.newTitle')}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground">{t('builder.subtitle')}</p>
                    </div>
                    <div className="flex items-center gap-2">
                        <Button variant="outline" onClick={() => router.back()} disabled={saving}>
                            {t('common.cancel')}
                        </Button>
                        <Button variant="brand" onClick={save} disabled={saving || ownerFiltersUnresolved}>
                            {saving ? t('common.saving') : t('builder.save')}
                        </Button>
                    </div>
                </header>

                <div className="mt-8 grid grid-cols-1 gap-8 xl:grid-cols-[22rem_minmax(0,1fr)]">
                    <aside className="space-y-6 xl:sticky xl:top-6 xl:self-start">
                        <section className="rounded-2xl border border-border bg-card p-5">
                            <h2 className="text-sm font-semibold text-foreground">{t('builder.detailsTitle')}</h2>
                            <div className="mt-5 space-y-4">
                                <div className="space-y-2">
                                    <Label htmlFor="report-name">{t('builder.name')}</Label>
                                    <Input
                                        id="report-name"
                                        value={name}
                                        maxLength={128}
                                        onChange={(event) => setName(event.target.value)}
                                        placeholder={t('builder.namePlaceholder')}
                                    />
                                </div>
                                <div className="space-y-2">
                                    <Label htmlFor="report-description">{t('builder.description')}</Label>
                                    <Textarea
                                        id="report-description"
                                        value={description}
                                        maxLength={512}
                                        onChange={(event) => setDescription(event.target.value)}
                                        placeholder={t('builder.descriptionPlaceholder')}
                                        rows={3}
                                    />
                                </div>
                                <FieldSelect
                                    label={t('builder.cadence')}
                                    value={cadence}
                                    onChange={setCadence}
                                    options={cadenceOptions.map((value) => ({ value, label: t(`cadence.${value}`) }))}
                                />
                                <FieldSelect
                                    label={t('builder.bucket')}
                                    value={config.bucket}
                                    onChange={(value) => setConfig((current) => ({ ...current, bucket: value }))}
                                    options={(['day', 'week', 'month'] as const).map((value) => ({ value, label: t(`bucket.${value}`) }))}
                                />
                                {cadence === 'custom' ? (
                                    <div className="grid grid-cols-2 gap-3">
                                        <div className="space-y-2">
                                            <Label htmlFor="report-start">{t('builder.startDate')}</Label>
                                            <Input
                                                id="report-start"
                                                type="date"
                                                value={config.range?.start ?? ''}
                                                onChange={(event) => setConfig((current) => ({
                                                    ...current,
                                                    range: { start: event.target.value, end: current.range?.end ?? '' },
                                                }))}
                                            />
                                        </div>
                                        <div className="space-y-2">
                                            <Label htmlFor="report-end">{t('builder.endDate')}</Label>
                                            <Input
                                                id="report-end"
                                                type="date"
                                                value={config.range?.end ?? ''}
                                                onChange={(event) => setConfig((current) => ({
                                                    ...current,
                                                    range: { start: current.range?.start ?? '', end: event.target.value },
                                                }))}
                                            />
                                        </div>
                                    </div>
                                ) : null}
                            </div>
                        </section>

                        <section className="rounded-2xl border border-border bg-card p-5">
                            <h2 className="text-sm font-semibold text-foreground">{t('builder.filtersTitle')}</h2>
                            <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{t('builder.filtersSubtitle')}</p>
                            {hasAttainment ? (
                                <p className="mt-3 text-xs leading-relaxed text-muted-foreground">
                                    {t('builder.attainmentFilters')}
                                </p>
                            ) : null}
                            <div className="mt-5 space-y-5">
                                <FilterChecklist
                                    label={t('builder.pipelines')}
                                    options={pipelines.map((pipeline) => ({ value: pipeline.id, label: pipeline.name }))}
                                    values={filters.pipelineIds}
                                    onChange={(value) => updateFilters({ pipelineIds: toggleValue(filters.pipelineIds, value) })}
                                    disabled={hasAttainment}
                                />
                                <FilterChecklist
                                    label={t('builder.owners')}
                                    options={ownerOptions}
                                    values={filters.ownerIds}
                                    onChange={(value) => updateFilters({ ownerIds: toggleValue(filters.ownerIds, value) })}
                                    disabled={hasWorkspaceAttainment}
                                />
                                {ownersFailed || ownerFiltersUnresolved ? (
                                    <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                                        <p>{t(ownersFailed
                                            ? ownerFiltersUnresolved ? 'builder.ownersLoadBlocked' : 'builder.ownersLoadError'
                                            : 'builder.ownersUnavailable')}</p>
                                        <div className="mt-3 flex flex-wrap gap-2">
                                            {ownerFiltersUnresolved ? (
                                                <Button variant="outline" size="sm" onClick={clearUnavailableOwners}>
                                                    {t('builder.clearUnavailableOwners')}
                                                </Button>
                                            ) : null}
                                            {ownersFailed ? (
                                                <Button variant="outline" size="sm" onClick={() => router.refresh()}>
                                                    {t('common.retry')}
                                                </Button>
                                            ) : null}
                                        </div>
                                    </div>
                                ) : null}
                                <FilterChecklist
                                    label={t('builder.statuses')}
                                    options={STATUSES.map((value) => ({ value, label: t(`status.${value}`) }))}
                                    values={filters.statuses}
                                    onChange={(value) => updateFilters({ statuses: toggleValue(filters.statuses, value) })}
                                    disabled={hasAttainment}
                                />
                                <FilterChecklist
                                    label={t('builder.tags')}
                                    options={tags.map((tag) => ({ value: tag.id, label: tag.name }))}
                                    values={filters.tagIds}
                                    onChange={(value) => updateFilters({ tagIds: toggleValue(filters.tagIds, value) })}
                                    disabled={hasAttainment}
                                />
                                <FilterChecklist
                                    label={t('builder.warmth')}
                                    options={WARMTH_BANDS.map((value) => ({ value, label: t(`warmth.${value}`) }))}
                                    values={filters.warmthBands}
                                    onChange={(value) => updateFilters({ warmthBands: toggleValue(filters.warmthBands, value) })}
                                />
                            </div>
                        </section>
                    </aside>

                    <main>
                        <div className="mb-5 flex items-end justify-between gap-4">
                            <div>
                                <h2 className="text-xl font-bold tracking-tight text-foreground">{t('builder.canvasTitle')}</h2>
                                <p className="mt-1 text-sm text-muted-foreground">{t('builder.canvasSubtitle')}</p>
                            </div>
                            <Button variant="outline" onClick={addWidget} disabled={config.widgets.length >= 16}>
                                <PlusIcon />
                                {t('builder.addWidget')}
                            </Button>
                        </div>
                        <SortableGrid
                            items={config.widgets}
                            getLabel={(widget) => widget.title?.trim() || t(`measure.${widget.measure}`)}
                            onChange={setWidgets}
                            messages={messages}
                            reduceMotion={reduceMotion}
                            gridClassName="grid grid-cols-1 gap-5 lg:grid-cols-2"
                            itemClassName={(widget) => (layoutById.get(widget.id)?.width ?? 6) >= 12 ? 'lg:col-span-2' : undefined}
                            renderItem={(widget, { dragHandle, isDragging }) => (
                                <WidgetEditor
                                    widget={widget}
                                    dragHandle={dragHandle}
                                    isDragging={isDragging}
                                    fullWidth={(layoutById.get(widget.id)?.width ?? 6) >= 12}
                                    canRemove={config.widgets.length > 1}
                                    canReadGoals={canReadGoals}
                                    onDataSourceChange={(value) => updateDataSource(widget, value)}
                                    onMeasureChange={(value) => updateMeasure(widget, value)}
                                    onGroupChange={(value) => updateGroup(widget, value)}
                                    onChange={(patch) => updateWidget(widget.id, patch)}
                                    onToggleWidth={() => toggleWidth(widget.id)}
                                    onRemove={() => removeWidget(widget.id)}
                                />
                            )}
                            renderOverlay={(widget) => (
                                <div className="rounded-2xl border border-brand/40 bg-card p-5 shadow-xl">
                                    <p className="truncate text-sm font-semibold text-foreground">
                                        {widget.title?.trim() || t(`measure.${widget.measure}`)}
                                    </p>
                                </div>
                            )}
                        />
                    </main>
                </div>
            </div>
        </div>
    );
}

function WidgetEditor({
    widget,
    dragHandle,
    isDragging,
    fullWidth,
    canRemove,
    canReadGoals,
    onDataSourceChange,
    onMeasureChange,
    onGroupChange,
    onChange,
    onToggleWidth,
    onRemove,
}: {
    widget: ReportWidgetConfig;
    dragHandle: ReactNode;
    isDragging: boolean;
    fullWidth: boolean;
    canRemove: boolean;
    canReadGoals: boolean;
    onDataSourceChange: (source: ReportDataSource) => void;
    onMeasureChange: (measure: ReportMeasure) => void;
    onGroupChange: (group: ReportWidgetConfig['groupBy']) => void;
    onChange: (patch: Partial<ReportWidgetConfig>) => void;
    onToggleWidth: () => void;
    onRemove: () => void;
}) {
    const t = useTranslations('Reports');
    return (
        <section className={cn(
            'rounded-2xl border border-border bg-card p-5 transition-opacity duration-150 motion-reduce:transition-none',
            isDragging && 'opacity-40',
        )}>
            <div className="flex items-center gap-2">
                {dragHandle}
                <Input
                    value={widget.title ?? ''}
                    maxLength={160}
                    onChange={(event) => onChange({ title: event.target.value || null })}
                    placeholder={t(`measure.${widget.measure}`)}
                    aria-label={t('builder.widgetTitle')}
                    className="min-w-0 flex-1 border-transparent bg-transparent px-2 font-semibold shadow-none hover:border-input focus-visible:border-ring"
                />
                <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={onToggleWidth}
                    aria-label={t(fullWidth ? 'builder.halfWidth' : 'builder.fullWidth')}
                >
                    {fullWidth ? <ArrowsPointingInIcon /> : <ArrowsPointingOutIcon />}
                </Button>
                <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={onRemove}
                    disabled={!canRemove}
                    aria-label={t('builder.removeWidget')}
                    className="text-muted-foreground hover:text-destructive"
                >
                    <TrashIcon />
                </Button>
            </div>
            <div className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <FieldSelect
                    label={t('builder.dataSource')}
                    value={widget.dataSource}
                    onChange={onDataSourceChange}
                    options={REPORT_DATA_SOURCES.map((value) => ({ value, label: t(`source.${value}`) }))}
                />
                <FieldSelect
                    label={t('builder.measure')}
                    value={widget.measure}
                    onChange={onMeasureChange}
                    options={REPORT_MEASURES[widget.dataSource].flatMap((value) =>
                        canReadGoals || value !== 'attainment'
                            ? [{ value, label: t(`measure.${value}`) }]
                            : [],
                    )}
                />
                <FieldSelect
                    label={t('builder.groupBy')}
                    value={widget.groupBy ?? 'none'}
                    onChange={onGroupChange}
                    options={reportGroupsForMeasure(widget.dataSource, widget.measure)
                        .map((value) => ({ value, label: t(`group.${value}`) }))}
                />
                <FieldSelect
                    label={t('builder.chartType')}
                    value={widget.chartType}
                    onChange={(value) => onChange({ chartType: value })}
                    options={(widget.measure === 'attainment'
                        ? REPORT_CHART_TYPES.filter((value) => ATTAINMENT_CHARTS.has(value))
                        : REPORT_CHART_TYPES)
                        .map((value) => ({ value, label: t(`chart.${value}`) }))}
                />
            </div>
            <WidgetPreview widget={widget} />
        </section>
    );
}

function WidgetPreview({ widget }: { widget: ReportWidgetConfig }) {
    const t = useTranslations('Reports');
    const sample = useMemo(() => sampleReportWidgetData(widget), [widget]);
    return (
        <div className="mt-5 rounded-xl border border-border bg-muted/20 p-4">
            <div className="mb-3 flex items-center justify-between gap-2">
                <span className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('builder.samplePreview')}
                </span>
                <span className="text-xs text-muted-foreground">{t(`chart.${widget.chartType}`)}</span>
            </div>
            <div className="pointer-events-none select-none" inert>
                <ReportWidgetRenderer widget={sample} />
            </div>
        </div>
    );
}

function FieldSelect<T extends string>({
    label,
    value,
    options,
    onChange,
}: {
    label: string;
    value: T;
    options: { value: T; label: string }[];
    onChange: (value: T) => void;
}) {
    const id = useId();
    return (
        <div className="space-y-2">
            <Label htmlFor={id}>{label}</Label>
            <Select
                value={value}
                onValueChange={(next) => {
                    const option = options.find((candidate) => candidate.value === next);
                    if (option) onChange(option.value);
                }}
            >
                <SelectTrigger id={id} className="w-full">
                    <SelectValue />
                </SelectTrigger>
                <SelectContent>
                    {options.map((option) => (
                        <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
                    ))}
                </SelectContent>
            </Select>
        </div>
    );
}

function FilterChecklist<T extends string | number>({
    label,
    options,
    values,
    onChange,
    disabled = false,
}: {
    label: string;
    options: { value: T; label: string }[];
    values: T[] | null;
    onChange: (value: T) => void;
    disabled?: boolean;
}) {
    if (options.length === 0) return null;
    return (
        <fieldset>
            <legend className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">{label}</legend>
            <div className="mt-2 max-h-32 space-y-1 overflow-y-auto rounded-xl border border-border p-2">
                {options.map((option) => (
                    <label
                        key={String(option.value)}
                        className={cn(
                            'flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm',
                            disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:bg-muted',
                        )}
                    >
                        <Checkbox
                            checked={values?.includes(option.value) ?? false}
                            onCheckedChange={() => onChange(option.value)}
                            disabled={disabled}
                        />
                        <span className="truncate">{option.label}</span>
                    </label>
                ))}
            </div>
        </fieldset>
    );
}
