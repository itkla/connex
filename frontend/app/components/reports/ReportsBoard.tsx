'use client';

import Link from 'next/link';
import { type ComponentType, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowRightIcon,
    ArrowTrendingUpIcon,
    ChartBarIcon,
    FlagIcon,
    EllipsisHorizontalIcon,
    HeartIcon,
    PresentationChartLineIcon,
    ShieldExclamationIcon,
    ShareIcon,
    SparklesIcon,
    TrashIcon,
    UserGroupIcon,
} from '@heroicons/react/24/outline';

import type { ReportDefinition, ReportTemplate } from '@/app/lib/types';
import { deleteReport } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
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
    'activity-team': UserGroupIcon,
};

export default function ReportsBoard({
    templates,
    initialReports,
    effectivePermissions,
}: {
    templates: ReportTemplate[];
    initialReports: ReportDefinition[];
    effectivePermissions: string[];
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const router = useRouter();
    const [reports, setReports] = useState(initialReports);
    const [deleting, setDeleting] = useState<ReportDefinition | null>(null);
    const [busy, setBusy] = useState(false);
    const canReadGoals = effectivePermissions.includes('GOAL_READ');
    const visibleTemplates = canReadGoals
        ? templates
        : templates.filter((template) => template.key !== 'quota-attainment');
    const visibleReports = canReadGoals
        ? reports
        : reports.filter((report) => report.config.widgets.every((widget) => widget.measure !== 'attainment'));

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

    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
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
                        <Button asChild variant="brand">
                            <Link href="/overview/reports/new">
                                <SparklesIcon />
                                {t('landing.newReport')}
                            </Link>
                        </Button>
                    </div>
                </header>

                <section aria-labelledby="report-templates-title">
                    <div className="mb-4 flex items-end justify-between gap-4">
                        <div>
                            <h2 id="report-templates-title" className="text-xl font-bold tracking-tight text-foreground">
                                {t('landing.templatesTitle')}
                            </h2>
                            <p className="mt-1 text-sm text-muted-foreground">{t('landing.templatesSubtitle')}</p>
                        </div>
                        <Button asChild variant="ghost" size="sm">
                            <Link href="/overview/reports/new">{t('landing.startBlank')}</Link>
                        </Button>
                    </div>
                    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
                        {visibleTemplates.map((template) => {
                            const Icon = TEMPLATE_ICONS[template.key] ?? PresentationChartLineIcon;
                            return (
                                <Link
                                    key={template.key}
                                    href={`/overview/reports/new?template=${encodeURIComponent(template.key)}`}
                                    className="group flex min-h-56 flex-col rounded-2xl border border-border bg-card p-5 hover:border-brand/40 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                                >
                                    <div className="flex size-10 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                                        <Icon className="size-5" />
                                    </div>
                                    <h3 className="mt-6 text-lg font-semibold text-foreground">
                                        {t(`templates.${template.key}.name`)}
                                    </h3>
                                    <p className="mt-2 flex-1 text-sm leading-relaxed text-muted-foreground">
                                        {t(`templates.${template.key}.description`)}
                                    </p>
                                    <span className="mt-5 inline-flex items-center gap-1.5 text-sm font-medium text-brand-dark">
                                        {t('landing.useTemplate')}
                                        <ArrowRightIcon className="size-4" />
                                    </span>
                                </Link>
                            );
                        })}
                    </div>
                </section>

                <section aria-labelledby="saved-reports-title">
                    <div className="mb-4">
                        <h2 id="saved-reports-title" className="text-xl font-bold tracking-tight text-foreground">
                            {t('landing.savedTitle')}
                        </h2>
                        <p className="mt-1 text-sm text-muted-foreground">{t('landing.savedSubtitle')}</p>
                    </div>
                    {visibleReports.length === 0 ? (
                        <div className="rounded-2xl border border-dashed border-border bg-card/40 px-6 py-16 text-center">
                            <PresentationChartLineIcon className="mx-auto size-7 text-muted-foreground" />
                            <h3 className="mt-4 text-base font-semibold text-foreground">{t('landing.emptyTitle')}</h3>
                            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t('landing.emptyBody')}</p>
                        </div>
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <ul className="divide-y divide-border">
                                {visibleReports.map((report) => (
                                    <li key={report.id} className="group flex items-center gap-4 px-5 py-4 hover:bg-muted/50">
                                        <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-muted text-muted-foreground">
                                            <PresentationChartLineIcon className="size-5" />
                                        </div>
                                        <Link href={`/overview/reports/${report.id}`} className="min-w-0 flex-1">
                                            <p className="truncate font-medium text-foreground group-hover:text-brand-dark">{report.name}</p>
                                            <p className="mt-1 truncate text-sm text-muted-foreground">
                                                {report.description || t('landing.noDescription')}
                                            </p>
                                        </Link>
                                        <div className="hidden shrink-0 text-right sm:block">
                                            <p className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                                {t(`cadence.${report.cadence}`)}
                                            </p>
                                            <p className="mt-1 text-xs text-muted-foreground">
                                                {t('landing.updated', {
                                                    date: new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(report.updatedAt)),
                                                })}
                                            </p>
                                        </div>
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <Button variant="ghost" size="icon-sm" aria-label={t('landing.actions', { name: report.name })}>
                                                    <EllipsisHorizontalIcon />
                                                </Button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end">
                                                <DropdownMenuItem asChild>
                                                    <Link href={`/overview/reports/${report.id}/edit`}>{t('common.edit')}</Link>
                                                </DropdownMenuItem>
                                                <DropdownMenuSeparator />
                                                <DropdownMenuItem variant="destructive" onSelect={() => setDeleting(report)}>
                                                    <TrashIcon />
                                                    {t('common.delete')}
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}
                </section>
            </div>

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
        </div>
    );
}
