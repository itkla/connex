'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { CalendarDaysIcon } from '@heroicons/react/24/outline';

import ScheduleDialog from '@/app/components/reports/ScheduleDialog';
import {
    ApiError,
    deleteReportSchedule,
    getActiveWorkspaceMembers,
    getReportSchedule,
    saveReportSchedule,
} from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    ReportSchedule,
    ReportScheduleRequest,
    WorkspaceMember,
} from '@/app/lib/types';
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

type LoadOutcome<T> = { ok: true; data: T } | { ok: false };

/** Loads and manages the schedule for one report without exposing mutation controls to read-only users. */
export default function ScheduleManager({
    reportId,
    reportName,
    canManage,
    defaultTimezone,
}: {
    reportId: number;
    reportName: string;
    canManage: boolean;
    defaultTimezone: string;
}) {
    const t = useTranslations('Reports');
    const router = useRouter();
    const [open, setOpen] = useState(false);
    const [loading, setLoading] = useState(false);
    const [loadFailed, setLoadFailed] = useState(false);
    const [membersFailed, setMembersFailed] = useState(false);
    const [schedule, setSchedule] = useState<ReportSchedule | null>(null);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const loadRequestRef = useRef(0);

    const load = async () => {
        const requestId = loadRequestRef.current + 1;
        loadRequestRef.current = requestId;
        setLoading(true);
        setLoadFailed(false);
        setMembersFailed(false);

        const scheduleRequest = resolveOutcome(getReportSchedule(reportId));
        const membersRequest: Promise<LoadOutcome<WorkspaceMember[]>> = canManage
            ? resolveOutcome(getActiveWorkspaceMembers().then((data) =>
                data.filter((member) => member.status === 'active')))
            : Promise.resolve({ ok: true, data: [] });

        const [scheduleOutcome, membersOutcome] = await Promise.all([scheduleRequest, membersRequest]);
        if (loadRequestRef.current !== requestId) return;
        if (!scheduleOutcome.ok) {
            setLoadFailed(true);
        } else {
            setSchedule(scheduleOutcome.data);
        }
        if (!membersOutcome.ok) {
            setMembersFailed(true);
            setMembers([]);
        } else {
            setMembers(membersOutcome.data);
        }
        setLoading(false);
    };

    const openDialog = () => {
        setOpen(true);
        if (!loading) void load();
    };

    const save = async (payload: ReportScheduleRequest) => {
        const scheduleExisted = schedule !== null;
        try {
            const saved = await saveReportSchedule(reportId, payload, scheduleExisted);
            setSchedule(saved);
            toastSuccess(scheduleExisted ? t('schedule.updated') : t('schedule.created'));
            router.refresh();
        } catch (error) {
            if (error instanceof ApiError && (error.status === 404 || error.status === 409)) {
                toastError(t('schedule.conflict'));
                await load();
                router.refresh();
            } else {
                toastError(error instanceof ApiError && error.status === 400
                    ? t('schedule.validation.recipientAccess')
                    : error instanceof Error ? error.message : t('common.requestFailed'));
            }
            throw error;
        }
    };

    const requestDelete = () => {
        if (!canManage || !schedule) return;
        setOpen(false);
        setDeleteOpen(true);
    };

    const confirmDelete = async () => {
        if (!canManage || !schedule) {
            setDeleteOpen(false);
            return;
        }
        setDeleting(true);
        try {
            await deleteReportSchedule(reportId);
            setSchedule(null);
            setDeleteOpen(false);
            toastSuccess(t('schedule.deleted'));
            router.refresh();
        } catch (error) {
            if (error instanceof ApiError && error.status === 404) {
                setDeleteOpen(false);
                toastError(t('schedule.conflict'));
                await load();
                router.refresh();
            } else {
                toastError(error instanceof Error ? error.message : t('common.requestFailed'));
            }
        } finally {
            setDeleting(false);
        }
    };

    return (
        <>
            <Button variant="outline" onClick={openDialog}>
                <CalendarDaysIcon />
                {t('schedule.action')}
            </Button>

            <ScheduleDialog
                open={open}
                schedule={schedule}
                members={members}
                canManage={canManage}
                loading={loading}
                loadFailed={loadFailed}
                membersFailed={membersFailed}
                defaultTimezone={defaultTimezone}
                onOpenChange={setOpen}
                onRetry={() => void load()}
                onSubmit={save}
                onRequestDelete={requestDelete}
            />

            <Dialog
                open={deleteOpen && canManage && schedule !== null}
                onOpenChange={(next) => !next && !deleting && setDeleteOpen(false)}
            >
                <DialogContent showCloseButton={false}>
                    <DialogHeader>
                        <DialogTitle>{t('schedule.deleteTitle')}</DialogTitle>
                        <DialogDescription>{t('schedule.deleteBody', { name: reportName })}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={deleting}>{t('common.cancel')}</Button>
                        </DialogClose>
                        <Button variant="destructive" onClick={confirmDelete} disabled={deleting}>
                            {deleting ? t('common.deleting') : t('schedule.delete')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </>
    );
}

async function resolveOutcome<T>(request: Promise<T>): Promise<LoadOutcome<T>> {
    try {
        return { ok: true, data: await request };
    } catch {
        return { ok: false };
    }
}
