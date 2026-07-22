'use client';

import { useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';

import { toastInfo } from '@/app/lib/toast';
import {
    clearDraft,
    DRAFT_VERSIONS,
    getDraftKeyGeneration,
    listFreshDrafts,
    readDraft,
    subscribeDraftChanges,
    type StoredDraft,
} from '@/app/lib/formDrafts';
import { useActions } from '@/app/hooks/useActions';
import { DRAFT_DEBOUNCE_MS } from '@/app/hooks/useFormDraft';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { ActivityDraftData } from '@/app/components/activity/activities/ActivityDialog';
import type { NoteDraftData } from '@/app/components/activity/notes/NoteDialog';
import type { TaskDraftData } from '@/app/components/activity/tasks/TaskDialog';
import { ACTIVITY_TYPES } from '@/app/components/activity/activities/activityTypeMeta';
import { noteContentToVisibleText } from '@/app/lib/references';

const MAX_LABEL = 48;
/** Defer the toast past the mount/hydration tick so the sonner Toaster has subscribed before it fires. */
const DRAFT_TOAST_DELAY_MS = 300;

function shorten(value: string): string {
    const trimmed = value.trim().replace(/\s+/g, ' ');
    return trimmed.length > MAX_LABEL ? `${trimmed.slice(0, MAX_LABEL - 1)}…` : trimmed;
}

function isNullableId(value: unknown): value is number | null {
    return value === null || (typeof value === 'number' && Number.isSafeInteger(value) && value > 0);
}

function isDateInputValue(value: unknown): value is string {
    if (value === '') return true;
    if (typeof value !== 'string') return false;
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return false;
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    if (year < 1) return false;
    const date = new Date(0);
    date.setUTCHours(0, 0, 0, 0);
    date.setUTCFullYear(year, month - 1, day);
    return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function isActivityDraftData(value: unknown): value is ActivityDraftData {
    return (
        typeof value === 'object' &&
        value !== null &&
        'type' in value &&
        typeof value.type === 'string' &&
        ACTIVITY_TYPES.some((type) => type === value.type) &&
        'subject' in value &&
        typeof value.subject === 'string' &&
        'notes' in value &&
        typeof value.notes === 'string' &&
        'personId' in value &&
        isNullableId(value.personId) &&
        'dealId' in value &&
        isNullableId(value.dealId)
    );
}

function isTaskDraftData(value: unknown): value is TaskDraftData {
    return (
        typeof value === 'object' &&
        value !== null &&
        'description' in value &&
        typeof value.description === 'string' &&
        'dueDate' in value &&
        isDateInputValue(value.dueDate) &&
        'assigneeId' in value &&
        isNullableId(value.assigneeId) &&
        'personId' in value &&
        isNullableId(value.personId) &&
        'dealId' in value &&
        isNullableId(value.dealId)
    );
}

function isNoteDraftData(value: unknown): value is NoteDraftData {
    return (
        typeof value === 'object' &&
        value !== null &&
        'content' in value &&
        typeof value.content === 'string' &&
        'personId' in value &&
        isNullableId(value.personId) &&
        'dealId' in value &&
        isNullableId(value.dealId)
    );
}

type ResumeDraft =
    | { kind: 'activity'; keyGeneration: number; stored: StoredDraft<ActivityDraftData> }
    | { kind: 'note'; keyGeneration: number; stored: StoredDraft<NoteDraftData> }
    | { kind: 'task'; keyGeneration: number; stored: StoredDraft<TaskDraftData> };

function readCurrentActivityDraft(
    stored: StoredDraft<ActivityDraftData>,
    userId: number | null,
    workspaceId: number | null,
): StoredDraft<ActivityDraftData> | null {
    const current = readDraft(
        { userId, workspaceId, formType: 'activity', scope: stored.scope },
        { version: DRAFT_VERSIONS.activity },
    );
    if (!current || !isActivityDraftData(current.data) || (!current.data.subject.trim() && !current.data.notes.trim())) {
        return null;
    }
    return { ...current, data: current.data };
}

function readCurrentTaskDraft(
    stored: StoredDraft<TaskDraftData>,
    userId: number | null,
    workspaceId: number | null,
): StoredDraft<TaskDraftData> | null {
    const current = readDraft(
        { userId, workspaceId, formType: 'task', scope: stored.scope },
        { version: DRAFT_VERSIONS.task },
    );
    if (!current || !isTaskDraftData(current.data) || !current.data.description.trim()) return null;
    return { ...current, data: current.data };
}

function readCurrentNoteDraft(
    stored: StoredDraft<NoteDraftData>,
    userId: number | null,
    workspaceId: number | null,
): StoredDraft<NoteDraftData> | null {
    const current = readDraft(
        { userId, workspaceId, formType: 'note', scope: stored.scope },
        { version: DRAFT_VERSIONS.note },
    );
    if (!current || !isNoteDraftData(current.data) || !noteContentToVisibleText(current.data.content)) return null;
    return { ...current, data: current.data };
}

function sameActivityDraft(left: StoredDraft<ActivityDraftData>, right: StoredDraft<ActivityDraftData>): boolean {
    return (
        left.savedAt === right.savedAt &&
        left.data.type === right.data.type &&
        left.data.subject === right.data.subject &&
        left.data.notes === right.data.notes &&
        left.data.personId === right.data.personId &&
        left.data.dealId === right.data.dealId
    );
}

function sameTaskDraft(left: StoredDraft<TaskDraftData>, right: StoredDraft<TaskDraftData>): boolean {
    return (
        left.savedAt === right.savedAt &&
        left.data.description === right.data.description &&
        left.data.dueDate === right.data.dueDate &&
        left.data.assigneeId === right.data.assigneeId &&
        left.data.personId === right.data.personId &&
        left.data.dealId === right.data.dealId
    );
}

function sameNoteDraft(left: StoredDraft<NoteDraftData>, right: StoredDraft<NoteDraftData>): boolean {
    return (
        left.savedAt === right.savedAt &&
        left.data.content === right.data.content &&
        left.data.personId === right.data.personId &&
        left.data.dealId === right.data.dealId
    );
}

/**
 * Render-nothing shell bridge that surfaces the current user + workspace's unfinished composer draft on
 * load, offering to resume it (reopening the composer prefilled) or discard it. Mounted inside the
 * ActionProvider so it can drive {@link useActions} overlays; scoped to the active identity so a draft
 * never resumes into the wrong tenant.
 */
export default function DraftResumeBridge() {
    const t = useTranslations('DraftResume');
    const { openOverlay, context } = useActions();
    const { activeWorkspaceId, switching } = useWorkspace();
    const userId = context.user?.id ?? null;

    useEffect(() => {
        if (switching) return;
        const drafts: ResumeDraft[] = [];
        for (const stored of listFreshDrafts({ userId, workspaceId: activeWorkspaceId })) {
            if (stored.formType === 'activity') {
                if (
                    stored.scope !== 'global' ||
                    !isActivityDraftData(stored.data) ||
                    (!stored.data.subject.trim() && !stored.data.notes.trim())
                ) {
                    clearDraft(stored.key);
                    continue;
                }
                drafts.push({
                    kind: 'activity',
                    keyGeneration: getDraftKeyGeneration(stored.key),
                    stored: { ...stored, data: stored.data },
                });
            } else if (stored.formType === 'note') {
                if (
                    stored.scope !== 'global' ||
                    !isNoteDraftData(stored.data) ||
                    !noteContentToVisibleText(stored.data.content)
                ) {
                    clearDraft(stored.key);
                    continue;
                }
                drafts.push({
                    kind: 'note',
                    keyGeneration: getDraftKeyGeneration(stored.key),
                    stored: { ...stored, data: stored.data },
                });
            } else if (stored.formType === 'task') {
                if (stored.scope !== 'global' || !isTaskDraftData(stored.data) || !stored.data.description.trim()) {
                    clearDraft(stored.key);
                    continue;
                }
                drafts.push({
                    kind: 'task',
                    keyGeneration: getDraftKeyGeneration(stored.key),
                    stored: { ...stored, data: stored.data },
                });
            }
        }
        if (drafts.length === 0) return;
        let active = true;
        const refreshTimers = new Set<number>();
        const changedKeys = new Set<string>();
        const unsubscribe = subscribeDraftChanges((key) => {
            changedKeys.add(key);
            toast.dismiss(key);
        });

        function deferRefresh(refresh: () => void, delay: number) {
            const timer = window.setTimeout(() => {
                refreshTimers.delete(timer);
                if (active) refresh();
            }, delay);
            refreshTimers.add(timer);
        }

        function refreshActivityToast(stored: StoredDraft<ActivityDraftData>) {
            const keyGeneration = getDraftKeyGeneration(stored.key);
            const current = readCurrentActivityDraft(stored, userId, activeWorkspaceId);
            const delay =
                current && !sameActivityDraft(current, stored) ? DRAFT_TOAST_DELAY_MS : DRAFT_DEBOUNCE_MS;
            deferRefresh(() => {
                if (changedKeys.has(stored.key)) return;
                const latestGeneration = getDraftKeyGeneration(stored.key);
                if (latestGeneration !== keyGeneration) {
                    refreshActivityToast(stored);
                    return;
                }
                const latest = readCurrentActivityDraft(stored, userId, activeWorkspaceId);
                if (latest && !sameActivityDraft(latest, stored)) {
                    showActivityToast(latest, latestGeneration);
                }
            }, delay);
        }

        function showActivityToast(stored: StoredDraft<ActivityDraftData>, keyGeneration: number) {
            const label = stored.data.subject.trim() || stored.data.notes.trim();
            toastInfo(t('activityMessageNamed', { label: shorten(label) }), {
                id: stored.key,
                duration: Infinity,
                action: {
                    label: t('resumeActivity'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshActivityToast(stored);
                            return;
                        }
                        const current = readCurrentActivityDraft(stored, userId, activeWorkspaceId);
                        if (!current) {
                            toast.dismiss(stored.key);
                            return;
                        }
                        const currentData = current.data;
                        openOverlay({
                            kind: 'create-activity',
                            defaults: {
                                personId: currentData.personId ?? undefined,
                                dealId: currentData.dealId ?? undefined,
                            },
                            draft: {
                                type: currentData.type,
                                subject: currentData.subject,
                                notes: currentData.notes,
                            },
                            restoredDraftGeneration: keyGeneration,
                        });
                        toast.dismiss(stored.key);
                    },
                },
                cancel: {
                    label: t('discardActivity'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshActivityToast(stored);
                            return;
                        }
                        const current = readCurrentActivityDraft(stored, userId, activeWorkspaceId);
                        if (current && sameActivityDraft(current, stored)) clearDraft(stored.key);
                        toast.dismiss(stored.key);
                    },
                },
            });
        }

        function refreshNoteToast(stored: StoredDraft<NoteDraftData>) {
            const keyGeneration = getDraftKeyGeneration(stored.key);
            const current = readCurrentNoteDraft(stored, userId, activeWorkspaceId);
            const delay = current && !sameNoteDraft(current, stored) ? DRAFT_TOAST_DELAY_MS : DRAFT_DEBOUNCE_MS;
            deferRefresh(() => {
                if (changedKeys.has(stored.key)) return;
                const latestGeneration = getDraftKeyGeneration(stored.key);
                if (latestGeneration !== keyGeneration) {
                    refreshNoteToast(stored);
                    return;
                }
                const latest = readCurrentNoteDraft(stored, userId, activeWorkspaceId);
                if (latest && !sameNoteDraft(latest, stored)) {
                    showNoteToast(latest, latestGeneration);
                }
            }, delay);
        }

        function showNoteToast(stored: StoredDraft<NoteDraftData>, keyGeneration: number) {
            const label = shorten(noteContentToVisibleText(stored.data.content));
            toastInfo(t('noteMessageNamed', { label }), {
                id: stored.key,
                duration: Infinity,
                action: {
                    label: t('resumeNote'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshNoteToast(stored);
                            return;
                        }
                        const current = readCurrentNoteDraft(stored, userId, activeWorkspaceId);
                        if (!current) {
                            toast.dismiss(stored.key);
                            return;
                        }
                        openOverlay({
                            kind: 'create-note',
                            draft: current.data,
                            restoredDraftGeneration: keyGeneration,
                        });
                        toast.dismiss(stored.key);
                    },
                },
                cancel: {
                    label: t('discardNote'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshNoteToast(stored);
                            return;
                        }
                        const current = readCurrentNoteDraft(stored, userId, activeWorkspaceId);
                        if (current && sameNoteDraft(current, stored)) clearDraft(stored.key);
                        toast.dismiss(stored.key);
                    },
                },
            });
        }

        function refreshTaskToast(stored: StoredDraft<TaskDraftData>) {
            const keyGeneration = getDraftKeyGeneration(stored.key);
            const current = readCurrentTaskDraft(stored, userId, activeWorkspaceId);
            const delay = current && !sameTaskDraft(current, stored) ? DRAFT_TOAST_DELAY_MS : DRAFT_DEBOUNCE_MS;
            deferRefresh(() => {
                if (changedKeys.has(stored.key)) return;
                const latestGeneration = getDraftKeyGeneration(stored.key);
                if (latestGeneration !== keyGeneration) {
                    refreshTaskToast(stored);
                    return;
                }
                const latest = readCurrentTaskDraft(stored, userId, activeWorkspaceId);
                if (latest && !sameTaskDraft(latest, stored)) {
                    showTaskToast(latest, latestGeneration);
                }
            }, delay);
        }

        function showTaskToast(stored: StoredDraft<TaskDraftData>, keyGeneration: number) {
            const label = shorten(noteContentToVisibleText(stored.data.description));
            toastInfo(t('taskMessageNamed', { label }), {
                id: stored.key,
                duration: Infinity,
                action: {
                    label: t('resumeTask'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshTaskToast(stored);
                            return;
                        }
                        const current = readCurrentTaskDraft(stored, userId, activeWorkspaceId);
                        if (!current) {
                            toast.dismiss(stored.key);
                            return;
                        }
                        openOverlay({
                            kind: 'create-task',
                            draft: current.data,
                            restoredDraftGeneration: keyGeneration,
                        });
                        toast.dismiss(stored.key);
                    },
                },
                cancel: {
                    label: t('discardTask'),
                    onClick: () => {
                        if (!active) return;
                        if (keyGeneration !== getDraftKeyGeneration(stored.key)) {
                            refreshTaskToast(stored);
                            return;
                        }
                        const current = readCurrentTaskDraft(stored, userId, activeWorkspaceId);
                        if (current && sameTaskDraft(current, stored)) clearDraft(stored.key);
                        toast.dismiss(stored.key);
                    },
                },
            });
        }

        const timer = window.setTimeout(() => {
            for (const draft of drafts) {
                if (
                    changedKeys.has(draft.stored.key) ||
                    draft.keyGeneration !== getDraftKeyGeneration(draft.stored.key)
                ) {
                    continue;
                }
                if (draft.kind === 'activity') {
                    const current = readCurrentActivityDraft(draft.stored, userId, activeWorkspaceId);
                    if (current && sameActivityDraft(current, draft.stored)) {
                        showActivityToast(current, draft.keyGeneration);
                    }
                } else if (draft.kind === 'note') {
                    const current = readCurrentNoteDraft(draft.stored, userId, activeWorkspaceId);
                    if (current && sameNoteDraft(current, draft.stored)) {
                        showNoteToast(current, draft.keyGeneration);
                    }
                } else {
                    const current = readCurrentTaskDraft(draft.stored, userId, activeWorkspaceId);
                    if (current && sameTaskDraft(current, draft.stored)) {
                        showTaskToast(current, draft.keyGeneration);
                    }
                }
            }
        }, DRAFT_TOAST_DELAY_MS);
        return () => {
            active = false;
            unsubscribe();
            window.clearTimeout(timer);
            for (const refreshTimer of refreshTimers) window.clearTimeout(refreshTimer);
            for (const draft of drafts) toast.dismiss(draft.stored.key);
        };
    }, [openOverlay, t, userId, activeWorkspaceId, switching]);

    return null;
}
