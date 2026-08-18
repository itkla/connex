'use client';

import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ChatBubbleLeftRightIcon } from '@heroicons/react/24/outline';

import {
    Drawer,
    DrawerContent,
    DrawerHeader,
    DrawerTitle,
    DrawerDescription,
    DrawerFooter,
    DrawerClose,
} from '@/components/ui/drawer';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { ACTIVITY_REFERENCE_COMMANDS } from '@/app/components/activity/notes/commands/slashCommandRegistry';
import RecordSelect from '@/app/components/records/RecordSelect';

import { updateActivity } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { ActivityTypePicker, normalizeType, type ActivityType } from '@/app/components/activity/activities/activityTypes';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    useContactTargetSearch,
    useDealTargetSearch,
} from '@/app/hooks/useRecordTargetSearch';
import { type Activity, type Contact, type Deal, type UpdateActivityPayload } from '@/app/lib/types';
import { toDatetimeLocalValue, toMysqlDateTime } from '@/app/lib/utils';

const inputClass =
    'w-full rounded-lg bg-muted px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';

const NONE_VALUE = 'none';

type ActivityDraft = {
    type: ActivityType;
    subject: string;
    notes: string;
    timestamp: string;
    personId: number | null;
    dealId: number | null;
};

type EditSession = {
    activityId: number;
    controller: AbortController;
    requestInit: RequestInit;
    workspaceId: number;
};

function toDraft(a: Activity): ActivityDraft {
    return {
        type: normalizeType(a.type),
        subject: a.subject ?? '',
        notes: a.notes ?? '',
        timestamp: toDatetimeLocalValue(a.timestamp),
        personId: a.personId ?? null,
        dealId: a.dealId ?? null,
    };
}

function createEditSession(activityId: number, workspaceId: number): EditSession {
    const controller = new AbortController();
    return {
        activityId,
        controller,
        requestInit: {
            signal: controller.signal,
            headers: { 'X-Workspace-Id': String(workspaceId) },
        },
        workspaceId,
    };
}

export default function EditActivitySheet({
    activity,
    open,
    onOpenChange,
    persons,
    deals,
    originWorkspaceId,
}: {
    activity: Activity;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    persons: Contact[];
    deals: Deal[];
    originWorkspaceId: number | null;
}) {
    const router = useRouter();
    const t = useTranslations('ActivityEditActivitySheet');
    const showApiError = useApiErrorToast('ActivityEditActivitySheet');
    const { activeWorkspaceId, switching } = useWorkspace();
    const [draft, setDraft] = useState<ActivityDraft>(() => toDraft(activity));
    const [session, setSession] = useState<EditSession | null>(() =>
        open && !switching && originWorkspaceId !== null && activeWorkspaceId === originWorkspaceId
            ? createEditSession(activity.id, originWorkspaceId)
            : null,
    );
    const [savingSession, setSavingSession] = useState<EditSession | null>(null);
    const sessionRef = useRef(session);
    const latestActivityRef = useRef(activity);
    const personSearch = useContactTargetSearch(open, [activity.personId], persons);
    const dealSearch = useDealTargetSearch(open, [activity.dealId], deals);

    useLayoutEffect(() => {
        latestActivityRef.current = activity;
    }, [activity]);

    const closeSession = useCallback(() => {
        sessionRef.current?.controller.abort();
        sessionRef.current = null;
        setSession(null);
        setSavingSession(null);
    }, []);

    const closeSheet = useCallback(() => {
        closeSession();
        setDraft(toDraft(latestActivityRef.current));
        onOpenChange(false);
    }, [closeSession, onOpenChange]);

    const finishSaving = useCallback((current: EditSession) => {
        if (sessionRef.current !== current || current.controller.signal.aborted) return;
        setSavingSession((candidate) => candidate === current ? null : candidate);
    }, []);

    useLayoutEffect(() => {
        const current = sessionRef.current;
        if (!open) {
            if (current !== null) closeSession();
            return;
        }
        if (
            switching
            || originWorkspaceId === null
            || activeWorkspaceId !== originWorkspaceId
            || (current !== null && (
                current.workspaceId !== originWorkspaceId
                || current.activityId !== activity.id
            ))
        ) {
            closeSheet();
            return;
        }
        if (current !== null) return;
        const next = createEditSession(activity.id, originWorkspaceId);
        sessionRef.current = next;
        setSession(next);
        setDraft(toDraft(latestActivityRef.current));
    }, [activeWorkspaceId, activity.id, closeSession, closeSheet, open, originWorkspaceId, switching]);

    useLayoutEffect(() => () => {
        sessionRef.current?.controller.abort();
        sessionRef.current = null;
    }, []);

    const handleOpenChange = (next: boolean) => {
        if (next) {
            onOpenChange(true);
            return;
        }
        closeSheet();
    };

    const saveUpdates = async () => {
        const current = sessionRef.current;
        if (
            current === null
            || current.controller.signal.aborted
            || switching
            || originWorkspaceId === null
            || activeWorkspaceId !== originWorkspaceId
            || current.workspaceId !== originWorkspaceId
            || current.activityId !== activity.id
        ) {
            closeSheet();
            return;
        }
        if (!draft.subject.trim()) {
            toastError(t('subjectRequired'));
            return;
        }
        setSavingSession(current);
        try {
            const payload: UpdateActivityPayload = {
                type: draft.type,
                subject: draft.subject.trim(),
                createdById: activity.createdById,
                notes: draft.notes.trim() || undefined,
                timestamp: draft.timestamp ? toMysqlDateTime(draft.timestamp) : undefined,
                personId: draft.personId,
                dealId: draft.dealId,
            };
            const updatedActivity = await updateActivity(current.activityId, payload, current.requestInit);
            if (sessionRef.current !== current || current.controller.signal.aborted) return;
            latestActivityRef.current = updatedActivity;
            toastSuccess(t('activityUpdated'));
            closeSheet();
            router.refresh();
        } catch (err) {
            if (sessionRef.current !== current || current.controller.signal.aborted) return;
            showApiError(err, 'updateFailed');
        } finally {
            finishSaving(current);
        }
    };

    const activeSession =
        open
        && !switching
        && session !== null
        && originWorkspaceId !== null
        && activeWorkspaceId === originWorkspaceId
        && session.workspaceId === originWorkspaceId
        && session.activityId === activity.id
        && !session.controller.signal.aborted
            ? session
            : null;
    const isSaving = activeSession !== null && savingSession === activeSession;

    return (
        <Drawer open={open} onOpenChange={handleOpenChange} swipeDirection="right">
            <DrawerContent className="flex w-full flex-col sm:max-w-lg">
                <DrawerHeader className="border-b pr-12">
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ChatBubbleLeftRightIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <DrawerTitle>{t('title')}</DrawerTitle>
                            <DrawerDescription>{t('description')}</DrawerDescription>
                        </div>
                    </div>
                </DrawerHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="grid gap-4 pt-6">
                        <div className="grid gap-1.5">
                            <Label>{t('typeLabel')}</Label>
                            <ActivityTypePicker
                                value={draft.type}
                                onChange={(value) => setDraft((d) => ({ ...d, type: value }))}
                                getLabel={(ty) => t(`type${ty}` as 'typeCall')}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-subject">{t('subjectLabel')}</Label>
                            <input
                                id="activity-subject"
                                type="text"
                                value={draft.subject}
                                onChange={(e) => setDraft((d) => ({ ...d, subject: e.target.value }))}
                                className={inputClass}
                                required
                                autoFocus
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-timestamp">{t('timestampLabel')}</Label>
                            <input
                                id="activity-timestamp"
                                type="datetime-local"
                                value={draft.timestamp}
                                onChange={(e) => setDraft((d) => ({ ...d, timestamp: e.target.value }))}
                                className={inputClass}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-person">{t('personLabel')}</Label>
                            <RecordSelect
                                value={draft.personId != null ? draft.personId.toString() : NONE_VALUE}
                                onValueChange={(value) =>
                                    setDraft((d) => ({ ...d, personId: value === NONE_VALUE ? null : Number(value) }))
                                }
                                id="activity-person"
                                placeholder={t('selectPersonPlaceholder')}
                                className={inputClass}
                                noneOption={{ value: NONE_VALUE, label: t('noPerson') }}
                                options={personSearch.contacts.map((person) => ({
                                    id: person.id,
                                    label: person.name,
                                    imageUrl: person.imageUrl,
                                }))}
                                onInputValueChange={personSearch.onInputValueChange}
                                emptyLabel={
                                    personSearch.loading
                                        ? t('searching')
                                        : personSearch.error
                                            ? t('personSearchFailed')
                                            : t('noPersonFound')
                                }
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-deal">{t('dealLabel')}</Label>
                            <RecordSelect
                                value={draft.dealId != null ? draft.dealId.toString() : NONE_VALUE}
                                onValueChange={(value) =>
                                    setDraft((d) => ({ ...d, dealId: value === NONE_VALUE ? null : Number(value) }))
                                }
                                id="activity-deal"
                                placeholder={t('selectDealPlaceholder')}
                                className={inputClass}
                                noneOption={{ value: NONE_VALUE, label: t('noDeal') }}
                                options={dealSearch.deals.map((deal) => ({
                                    id: deal.id,
                                    label: deal.name,
                                }))}
                                onInputValueChange={dealSearch.onInputValueChange}
                                emptyLabel={
                                    dealSearch.loading
                                        ? t('searching')
                                        : dealSearch.error
                                            ? t('dealSearchFailed')
                                            : t('noDealFound')
                                }
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-notes">{t('notesLabel')}</Label>
                            {activeSession && (
                                <MentionEditor
                                    id="activity-notes"
                                    value={draft.notes}
                                    onChange={(value) => setDraft((d) => ({ ...d, notes: value }))}
                                    commands={ACTIVITY_REFERENCE_COMMANDS}
                                    requestInit={activeSession.requestInit}
                                    ariaLabel={t('notesLabel')}
                                    className={`${inputClass} min-h-24`}
                                />
                            )}
                        </div>
                    </div>
                </div>

                <DrawerFooter className="border-t">
                    <DrawerClose render={<Button variant="outline" disabled={isSaving} />}>
                        {t('cancel')}
                    </DrawerClose>
                    <Button
                        onClick={saveUpdates}
                        variant="brand"
                        disabled={isSaving || activeSession === null}
                        className="transition-transform active:scale-[0.98]"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </DrawerFooter>
            </DrawerContent>
        </Drawer>
    );
}
