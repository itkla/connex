'use client';

import Link from 'next/link';
import { type ComponentType, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowPathIcon,
    ArrowRightIcon,
    ArrowTrendingUpIcon,
    BriefcaseIcon,
    ChartBarIcon,
    DocumentDuplicateIcon,
    EllipsisHorizontalIcon,
    FlagIcon,
    HeartIcon,
    PencilSquareIcon,
    PresentationChartLineIcon,
    ShareIcon,
    ShieldExclamationIcon,
    TrashIcon,
    UserGroupIcon,
} from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import { PageShell } from '@/app/components/PageShell';
import AskConnexComposer from '@/app/components/reports/AskConnexComposer';
import {
    cloneReportConfig,
    groupReportTemplates,
    reportTemplateMeasures,
} from '@/app/components/reports/reportConfig';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { createReport, deleteReport } from '@/app/lib/api';
import { canDeleteOwnedRecord } from '@/app/lib/deletionPolicy';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ReportDefinition, ReportTemplate } from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

const TEMPLATE_ICONS: Record<string, ComponentType<{ className?: string }>> = {
    'sales-performance': ChartBarIcon,
    'pipeline-health': PresentationChartLineIcon,
    'forecasting': ArrowTrendingUpIcon,
    'quota-attainment': FlagIcon,
    'relationship-coverage': HeartIcon,
    'relationship-health': ShieldExclamationIcon,
    'network-warm-intros': ShareIcon,
    'employment-moves': BriefcaseIcon,
    'activity-team': UserGroupIcon,
};

const NAME_COPY_BUDGET = 120;

export default function ReportsBoard({
    templates,
    initialReports,
    effectivePermissions,
    currentUserId,
    composerAvailable,
}: {
    templates: ReportTemplate[];
    initialReports: ReportDefinition[];
    effectivePermissions: string[];
    currentUserId: number;
    composerAvailable: boolean;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const router = useRouter();
    const { activeWorkspace } = useWorkspace();
    const canDeleteReports = effectivePermissions.includes('REPORT_DELETE');
    const canCreateReports = effectivePermissions.includes('REPORT_CREATE');
    const [reports, setReports] = useState(initialReports);
    const [deleting, setDeleting] = useState<ReportDefinition | null>(null);
    const [busy, setBusy] = useState(false);
    const [creatingKey, setCreatingKey] = useState<string | null>(null);
    const [duplicatingId, setDuplicatingId] = useState<number | null>(null);
    const canReadGoals = effectivePermissions.includes('GOAL_READ');
    const visibleTemplates = canReadGoals
        ? templates
        : templates.filter((template) => template.key !== 'quota-attainment');
    const visibleReports = canReadGoals
        ? reports
        : reports.filter((report) => report.config.widgets.every((widget) => widget.measure !== 'attainment'));
    const templateGroups = groupReportTemplates(visibleTemplates);
    const hasReports = visibleReports.length > 0;

    const createFromTemplate = async (template: ReportTemplate) => {
        if (creatingKey) return;
        setCreatingKey(template.key);
        const config = cloneReportConfig(template.config);
        try {
            const created = await createReport({
                name: t(`templates.${template.key}.name`),
                description: t(`templates.${template.key}.description`),
                cadence: template.cadence,
                templateKey: template.key,
                config: {
                    ...config,
                    widgets: config.widgets.map((widget) => ({ ...widget, title: t(`measure.${widget.measure}`) })),
                },
            });
            router.push(`/overview/reports/${created.id}`);
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
            setCreatingKey(null);
        }
    };

    const duplicateReport = async (report: ReportDefinition) => {
        if (duplicatingId) return;
        setDuplicatingId(report.id);
        const base = report.name.length > NAME_COPY_BUDGET ? report.name.slice(0, NAME_COPY_BUDGET) : report.name;
        try {
            const created = await createReport({
                name: t('landing.copyName', { name: base }),
                description: report.description,
                cadence: report.cadence,
                templateKey: report.templateKey,
                config: cloneReportConfig(report.config),
            });
            setReports((current) => [created, ...current]);
            toastSuccess(t('landing.duplicated', { name: report.name }));
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setDuplicatingId(null);
        }
    };

    const confirmDelete = async () => {
        if (!deleting) return;
        setBusy(true);
        try {
            await deleteReport(deleting.id);
            setReports((current) => current.filter((report) => report.id !== deleting.id));
            toastSuccess(t('landing.deleted', { name: deleting.name }));
            setDeleting(null);
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setBusy(false);
        }
    };

    const startSection = (
        <section aria-labelledby="start-title">
            <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
                <div className="max-w-2xl">
                    <h2 id="start-title" className="text-xl font-bold tracking-tight text-foreground">
                        {hasReports ? t('landing.startTitle') : t('landing.firstTitle')}
                    </h2>
                    <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                        {hasReports ? t('landing.startSubtitle') : t('landing.firstSubtitle')}
                    </p>
                </div>
                {canCreateReports ? (
                    <Button asChild variant="outline">
                        <Link href="/overview/reports/new">
                            <PencilSquareIcon />
                            {t('landing.blankBuilder')}
                        </Link>
                    </Button>
                ) : null}
            </div>
            <div className="space-y-8">
                {templateGroups.map((group) => (
                    <div key={group.id}>
                        <div className="mb-3 flex items-center gap-3">
                            <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                {t(`landing.groups.${group.id}`)}
                            </h3>
                            <div className="h-px flex-1 bg-border" />
                        </div>
                        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
                            {group.templates.map((template) => {
                                const Icon = TEMPLATE_ICONS[template.key] ?? PresentationChartLineIcon;
                                const measures = reportTemplateMeasures(template);
                                const shown = measures.slice(0, 3).map((measure) => t(`measure.${measure}`));
                                const extra = measures.length - shown.length;
                                const creating = creatingKey === template.key;
                                return (
                                    <div
                                        key={template.key}
                                        className="flex h-full flex-col rounded-2xl border border-border bg-card p-5 transition-[border-color,box-shadow] duration-200 ease-out hover:border-brand/40 hover:shadow-[0_14px_34px_-16px_rgba(0,0,0,0.22)] dark:hover:shadow-[0_14px_34px_-16px_rgba(0,0,0,0.6)]"
                                    >
                                        <div className="flex size-10 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                                            <Icon className="size-5" />
                                        </div>
                                        <h4 className="mt-4 text-base font-semibold text-foreground">
                                            {t(`templates.${template.key}.name`)}
                                        </h4>
                                        <p className="mt-1.5 flex-1 text-sm leading-relaxed text-muted-foreground">
                                            {t(`templates.${template.key}.description`)}
                                        </p>
                                        {shown.length > 0 ? (
                                            <p className="mt-4 text-xs leading-relaxed text-muted-foreground">
                                                <span className="font-medium text-foreground/70">{t('landing.includes')} </span>
                                                {new Intl.ListFormat(locale, { type: 'unit' }).format(shown)}
                                                {extra > 0 ? ` ${t('landing.moreMeasures', { count: extra })}` : ''}
                                            </p>
                                        ) : null}
                                        <Button
                                            variant="outline"
                                            className="mt-5 w-full"
                                            onClick={() => createFromTemplate(template)}
                                            disabled={creatingKey !== null}
                                        >
                                            {creating ? (
                                                <>
                                                    <ArrowPathIcon className="animate-spin motion-reduce:animate-none" />
                                                    {t('landing.creating')}
                                                </>
                                            ) : (
                                                <>
                                                    {t('landing.createReport')}
                                                    <ArrowRightIcon />
                                                </>
                                            )}
                                        </Button>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                ))}
            </div>
        </section>
    );
    const progressiveStartSection = composerAvailable ? (
        <details className="group rounded-2xl border border-border bg-card p-5 sm:p-6">
            <summary className="cursor-pointer list-none font-semibold text-foreground marker:hidden">
                <span className="flex items-center justify-between gap-4">
                    {t('landing.otherWays')}
                    <ArrowRightIcon className="size-4 transition-transform group-open:rotate-90 motion-reduce:transition-none" />
                </span>
            </summary>
            <div className="mt-8 border-t border-border pt-8">{startSection}</div>
        </details>
    ) : startSection;

    return (
        <>
            <PageShell tier="wide">
                <Rise>
                    <header className="flex flex-wrap items-end justify-between gap-5">
                        <div>
                            <p className="mb-2 text-xs font-medium uppercase tracking-[0.12em] text-brand-dark">
                                {t('landing.eyebrow')}
                            </p>
                            <h1 className="text-4xl font-extrabold tracking-tight text-foreground">{t('landing.title')}</h1>
                            <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
                                {t('landing.subtitle')}
                            </p>
                        </div>
                        <div className="flex flex-wrap items-center gap-2">
                            {canReadGoals ? (
                                <Button asChild variant="outline">
                                    <Link href="/overview/reports/goals">
                                        <FlagIcon />
                                        {t('landing.manageGoals')}
                                    </Link>
                                </Button>
                            ) : null}
                            {!composerAvailable && canCreateReports ? (
                                <Button asChild variant="brand">
                                    <Link href="/overview/reports/new">
                                        <PencilSquareIcon />
                                        {t('landing.newReport')}
                                    </Link>
                                </Button>
                            ) : null}
                        </div>
                    </header>
                </Rise>

                {composerAvailable && canCreateReports ? (
                    <Rise delay={0.06}>
                        <AskConnexComposer />
                    </Rise>
                ) : null}

                {hasReports ? (
                    <>
                        <Rise delay={composerAvailable ? 0.12 : 0.06}>
                            <section aria-labelledby="your-reports-title">
                                <div className="mb-4">
                                    <h2 id="your-reports-title" className="text-xl font-bold tracking-tight text-foreground">
                                        {t('landing.yourReportsTitle')}
                                    </h2>
                                    <p className="mt-1 text-sm text-muted-foreground">{t('landing.yourReportsSubtitle')}</p>
                                </div>
                                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                    <ul className="divide-y divide-border">
                                        {visibleReports.map((report) => {
                                            const Icon = (report.templateKey && TEMPLATE_ICONS[report.templateKey])
                                                || PresentationChartLineIcon;
                                            return (
                                                <li
                                                    key={report.id}
                                                    className="group flex items-center gap-4 px-5 py-4 transition-colors hover:bg-muted/40"
                                                >
                                                    <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-brand-light/60 text-brand-dark">
                                                        <Icon className="size-5" />
                                                    </div>
                                                    <Link href={`/overview/reports/${report.id}`} className="min-w-0 flex-1">
                                                        <div className="flex min-w-0 items-center gap-2">
                                                            <p className="min-w-0 truncate font-medium text-foreground group-hover:text-brand-dark">
                                                                {report.name}
                                                            </p>
                                                            <Badge variant="secondary" className="shrink-0 font-normal">
                                                                {t(`cadence.${report.cadence}`)}
                                                            </Badge>
                                                        </div>
                                                        <p className="mt-0.5 truncate text-sm text-muted-foreground">
                                                            {report.description || t('landing.noDescription')}
                                                        </p>
                                                    </Link>
                                                    <p className="hidden shrink-0 text-xs text-muted-foreground sm:block">
                                                        {t('landing.updated', {
                                                            date: new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(report.updatedAt)),
                                                        })}
                                                    </p>
                                                    <DropdownMenu>
                                                        <DropdownMenuTrigger asChild>
                                                            <Button variant="ghost" size="icon-sm" aria-label={t('landing.actions', { name: report.name })}>
                                                                <EllipsisHorizontalIcon />
                                                            </Button>
                                                        </DropdownMenuTrigger>
                                                        <DropdownMenuContent align="end">
                                                            <DropdownMenuItem asChild>
                                                                <Link href={`/overview/reports/${report.id}`}>{t('landing.open')}</Link>
                                                            </DropdownMenuItem>
                                                            <DropdownMenuItem asChild>
                                                                <Link href={`/overview/reports/${report.id}/edit`}>{t('common.edit')}</Link>
                                                            </DropdownMenuItem>
                                                            <DropdownMenuItem
                                                                onSelect={() => duplicateReport(report)}
                                                                disabled={duplicatingId !== null}
                                                            >
                                                                <DocumentDuplicateIcon />
                                                                {t('landing.duplicate')}
                                                            </DropdownMenuItem>
                                                            {canDeleteReports
                                                                && canDeleteOwnedRecord(report.createdBy, currentUserId, activeWorkspace?.role) ? (
                                                                <>
                                                                    <DropdownMenuSeparator />
                                                                    <DropdownMenuItem variant="destructive" onSelect={() => setDeleting(report)}>
                                                                        <TrashIcon />
                                                                        {t('common.delete')}
                                                                    </DropdownMenuItem>
                                                                </>
                                                            ) : null}
                                                        </DropdownMenuContent>
                                                    </DropdownMenu>
                                                </li>
                                            );
                                        })}
                                    </ul>
                                </div>
                            </section>
                        </Rise>
                        <Rise delay={composerAvailable ? 0.18 : 0.12}>
                            {progressiveStartSection}
                        </Rise>
                    </>
                ) : (
                    <Rise delay={composerAvailable ? 0.12 : 0.06}>
                        {progressiveStartSection}
                    </Rise>
                )}
            </PageShell>

            <Dialog open={deleting !== null} onOpenChange={(open) => !open && setDeleting(null)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('landing.deleteTitle')}</DialogTitle>
                        <DialogDescription>{t('landing.deleteBody', { name: deleting?.name ?? '' })}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={busy}>{t('common.cancel')}</Button>
                        </DialogClose>
                        <Button variant="destructive" onClick={confirmDelete} disabled={busy}>
                            {busy ? t('common.deleting') : t('common.delete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </>
    );
}
