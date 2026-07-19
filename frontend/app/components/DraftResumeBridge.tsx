'use client';

import { useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';

import { toastInfo } from '@/app/lib/toast';
import { clearDraft, listFreshDrafts } from '@/app/lib/formDrafts';
import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { ActivityDraftData } from '@/app/components/activity/activities/ActivityDialog';

const MAX_LABEL = 48;
/** Defer the toast past the mount/hydration tick so the sonner Toaster has subscribed before it fires. */
const DRAFT_TOAST_DELAY_MS = 300;

function shorten(value: string): string {
    const trimmed = value.trim().replace(/\s+/g, ' ');
    return trimmed.length > MAX_LABEL ? `${trimmed.slice(0, MAX_LABEL - 1)}…` : trimmed;
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
    const { activeWorkspaceId } = useWorkspace();
    const userId = context.user?.id ?? null;

    useEffect(() => {
        const drafts = listFreshDrafts({ userId, workspaceId: activeWorkspaceId }).filter((d) => {
            if (d.formType !== 'activity') return false;
            const data = d.data as ActivityDraftData;
            return Boolean(data.subject?.trim() || data.notes?.trim());
        });
        if (drafts.length === 0) return;
        const timer = window.setTimeout(() => {
            for (const stored of drafts) {
                const data = stored.data as ActivityDraftData;
                const label = data.subject?.trim() || data.notes?.trim() || '';
                toastInfo(t('activityMessageNamed', { label: shorten(label) }), {
                    id: stored.key,
                    duration: Infinity,
                    action: {
                        label: t('resume'),
                        onClick: () => {
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
                            clearDraft(stored.key);
                            toast.dismiss(stored.key);
                        },
                    },
                });
            }
        }, DRAFT_TOAST_DELAY_MS);
        return () => window.clearTimeout(timer);
    }, [openOverlay, t, userId, activeWorkspaceId]);

    return null;
}
