'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import {
    ArrowLeftIcon,
    FlagIcon,
    PencilSquareIcon,
    PlusIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import GoalDialog from '@/app/components/reports/GoalDialog';
import { createGoal, deleteGoal, updateGoal } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ReportGoal, ReportGoalInput, WorkspaceMember } from '@/app/lib/types';
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

type GoalsBoardProps = {
    initialGoals: ReportGoal[];
    owners: WorkspaceMember[];
    canManage: boolean;
    goalsFailed: boolean;
    ownersFailed: boolean;
};

/** Workspace report-goal list with permission-aware mutation controls. */
export default function GoalsBoard({
    initialGoals,
    owners,
    canManage,
    goalsFailed,
    ownersFailed,
}: GoalsBoardProps) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const router = useRouter();
    const [goals, setGoals] = useState(initialGoals);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<ReportGoal | null>(null);
    const [deleting, setDeleting] = useState<ReportGoal | null>(null);
    const [deleteBusy, setDeleteBusy] = useState(false);
    const managementAvailable = canManage && !goalsFailed && !ownersFailed;

    const openCreate = () => {
        setEditing(null);
        setDialogOpen(true);
    };

    const openEdit = (goal: ReportGoal) => {
        setEditing(goal);
        setDialogOpen(true);
    };

    const saveGoal = async (payload: ReportGoalInput) => {
        try {
            if (editing) {
                const saved = await updateGoal(editing.id, payload);
                setGoals((current) => current.map((goal) => goal.id === saved.id ? saved : goal));
                toastSuccess(t('goals.updated'));
            } else {
                const saved = await createGoal(payload);
                setGoals((current) => [saved, ...current]);
                toastSuccess(t('goals.created'));
            }
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
            throw error;
        }
    };

    const confirmDelete = async () => {
        if (!deleting) return;
        setDeleteBusy(true);
        try {
            await deleteGoal(deleting.id);
            setGoals((current) => current.filter((goal) => goal.id !== deleting.id));
            toastSuccess(t('goals.deleted'));
            setDeleting(null);
            router.refresh();
        } catch (error) {
            toastError(error instanceof Error ? error.message : t('common.requestFailed'));
        } finally {
            setDeleteBusy(false);
        }
    };

    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <header className="flex flex-wrap items-end justify-between gap-5">
                    <div>
                        <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
                            <Link href="/overview/reports">
                                <ArrowLeftIcon />
                                {t('goals.backToReports')}
                            </Link>
                        </Button>
                        <p className="mb-2 text-xs font-medium uppercase tracking-[0.12em] text-brand-dark">
                            {t('goals.eyebrow')}
                        </p>
                        <h1 className="text-4xl font-extrabold tracking-tight text-foreground">{t('goals.title')}</h1>
                        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">
                            {t('goals.subtitle')}
                        </p>
                    </div>
                    {managementAvailable ? (
                        <Button variant="brand" onClick={openCreate}>
                            <PlusIcon />
                            {t('goals.createGoal')}
                        </Button>
                    ) : null}
                </header>

                {!canManage ? (
                    <div className="rounded-xl border border-border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
                        {t('goals.readOnly')}
                    </div>
                ) : null}

                {ownersFailed && !goalsFailed ? (
                    <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                        {t('goals.ownersLoadError')}
                    </div>
                ) : null}

                {goalsFailed ? (
                    <section className="rounded-2xl border border-destructive/30 bg-card px-6 py-16 text-center" role="alert">
                        <FlagIcon className="mx-auto size-7 text-destructive" />
                        <h2 className="mt-4 text-base font-semibold text-foreground">{t('goals.loadErrorTitle')}</h2>
                        <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t('goals.loadErrorBody')}</p>
                        <Button variant="outline" className="mt-5" onClick={() => router.refresh()}>
                            {t('common.retry')}
                        </Button>
                    </section>
                ) : goals.length === 0 ? (
                    <section className="rounded-2xl border border-dashed border-border bg-card/40 px-6 py-16 text-center">
                        <FlagIcon className="mx-auto size-7 text-muted-foreground" />
                        <h2 className="mt-4 text-base font-semibold text-foreground">{t('goals.emptyTitle')}</h2>
                        <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t('goals.emptyBody')}</p>
                        {managementAvailable ? (
                            <Button variant="outline" className="mt-5" onClick={openCreate}>
                                <PlusIcon />
                                {t('goals.createGoal')}
                            </Button>
                        ) : null}
                    </section>
                ) : (
                    <section className="overflow-hidden rounded-2xl border border-border bg-card" aria-labelledby="goals-table-title">
                        <div className="border-b border-border px-5 py-4">
                            <h2 id="goals-table-title" className="font-semibold text-foreground">{t('goals.tableTitle')}</h2>
                            <p className="mt-1 text-sm text-muted-foreground">{t('goals.count', { count: goals.length })}</p>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-max text-left text-sm">
                                <thead className="border-b border-border bg-muted/30 text-xs uppercase tracking-[0.12em] text-muted-foreground">
                                    <tr>
                                        <th className="px-5 py-3 font-medium">{t('goals.owner')}</th>
                                        <th className="px-5 py-3 font-medium">{t('goals.scope')}</th>
                                        <th className="px-5 py-3 font-medium">{t('goals.metric')}</th>
                                        <th className="px-5 py-3 font-medium">{t('goals.period')}</th>
                                        <th className="px-5 py-3 text-right font-medium">{t('goals.target')}</th>
                                        <th className="px-5 py-3 font-medium">{t('goals.currency')}</th>
                                        {managementAvailable ? <th className="px-5 py-3 text-right font-medium">{t('goals.actions')}</th> : null}
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {goals.map((goal) => (
                                        <tr key={goal.id} className="hover:bg-muted/30">
                                            <td className="px-5 py-4">
                                                <p className="font-medium text-foreground">{goal.ownerLabel ?? '—'}</p>
                                            </td>
                                            <td className="px-5 py-4 text-muted-foreground">
                                                {goal.ownerId == null ? t('goals.workspaceScope') : t('goals.ownerScope')}
                                            </td>
                                            <td className="px-5 py-4 text-foreground">{t(`measure.${goal.metric}`)}</td>
                                            <td className="px-5 py-4 text-foreground">
                                                {formatGoalPeriod(goal, locale, (quarter, year) =>
                                                    t('goals.quarterLabel', { quarter, year }))}
                                            </td>
                                            <td className="px-5 py-4 text-right font-semibold tabular-nums text-foreground">
                                                {formatTargetValue(goal.targetValue, locale)}
                                            </td>
                                            <td className="px-5 py-4 font-medium text-foreground">{goal.currency}</td>
                                            {managementAvailable ? (
                                                <td className="px-5 py-4">
                                                    <div className="flex justify-end gap-1">
                                                        <Button
                                                            variant="ghost"
                                                            size="icon-sm"
                                                            aria-label={t('goals.editNamed', { scope: goal.ownerLabel ?? t('goals.workspaceWide') })}
                                                            onClick={() => openEdit(goal)}
                                                        >
                                                            <PencilSquareIcon />
                                                        </Button>
                                                        <Button
                                                            variant="ghost"
                                                            size="icon-sm"
                                                            aria-label={t('goals.deleteNamed', { scope: goal.ownerLabel ?? t('goals.workspaceWide') })}
                                                            onClick={() => setDeleting(goal)}
                                                        >
                                                            <TrashIcon />
                                                        </Button>
                                                    </div>
                                                </td>
                                            ) : null}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </section>
                )}
            </div>

            {managementAvailable ? (
                <GoalDialog
                    open={dialogOpen}
                    editing={editing}
                    owners={owners}
                    onOpenChange={setDialogOpen}
                    onSubmit={saveGoal}
                />
            ) : null}

            <Dialog open={deleting !== null} onOpenChange={(open) => !open && setDeleting(null)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('goals.deleteTitle')}</DialogTitle>
                        <DialogDescription>
                            {t('goals.deleteBody', { scope: deleting?.ownerLabel ?? t('goals.workspaceWide') })}
                        </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={deleteBusy}>{t('common.cancel')}</Button>
                        </DialogClose>
                        <Button variant="destructive" onClick={confirmDelete} disabled={deleteBusy}>
                            {deleteBusy ? t('common.deleting') : t('common.delete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}

function formatTargetValue(value: number, locale: string): string {
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
}

function formatGoalPeriod(
    goal: ReportGoal,
    locale: string,
    quarterLabel: (quarter: number, year: number) => string,
): string {
    const date = new Date(`${goal.periodStart}T00:00:00Z`);
    if (goal.periodType === 'quarter') {
        const quarter = Math.floor(date.getUTCMonth() / 3) + 1;
        return quarterLabel(quarter, date.getUTCFullYear());
    }
    return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: 'long',
        timeZone: 'UTC',
    }).format(date);
}
