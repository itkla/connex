'use client';

import { useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';

import { toastInfo } from '@/app/lib/toast';
import { clearDraft, listFreshDrafts, type StoredDraft } from '@/app/lib/formDrafts';
import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { ActivityDraftData } from '@/app/components/activity/activities/ActivityDialog';
import { ACTIVITY_TYPES } from '@/app/components/activity/activities/activityTypeMeta';

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
        const drafts: StoredDraft<ActivityDraftData>[] = [];
        for (const stored of listFreshDrafts({ userId, workspaceId: activeWorkspaceId })) {
            if (stored.formType !== 'activity') continue;
            if (!isActivityDraftData(stored.data) || (!stored.data.subject.trim() && !stored.data.notes.trim())) {
                clearDraft(stored.key);
                continue;
            }
            drafts.push({ ...stored, data: stored.data });
        }
        if (drafts.length === 0) return;
        let active = true;
        const timer = window.setTimeout(() => {
            for (const stored of drafts) {
                const data = stored.data;
                const label = data.subject?.trim() || data.notes?.trim() || '';
                toastInfo(t('activityMessageNamed', { label: shorten(label) }), {
                    id: stored.key,
                    duration: Infinity,
                    action: {
                        label: t('resume'),
                        onClick: () => {
                            if (!active) return;
                            openOverlay({
                                kind: 'create-activity',
                                defaults: { personId: data.personId ?? undefined, dealId: data.dealId ?? undefined },
                                draft: { type: data.type, subject: data.subject, notes: data.notes },
                            });
                            toast.dismiss(stored.key);
                        },
                    },
                    cancel: {
                        label: t('discard'),
                        onClick: () => {
                            if (!active) return;
                            clearDraft(stored.key);
                            toast.dismiss(stored.key);
                        },
                    },
                });
            }
        }, DRAFT_TOAST_DELAY_MS);
        return () => {
            active = false;
            window.clearTimeout(timer);
            for (const stored of drafts) toast.dismiss(stored.key);
        };
    }, [openOverlay, t, userId, activeWorkspaceId, switching]);

    return null;
}
