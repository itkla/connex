'use client';

import { PlusIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { DisqualificationReasonArchiveControls } from '@/app/components/settings/DisqualificationReasonArchiveControls';
import {
    DisqualificationReasonEditor,
    type DisqualificationReasonDraft,
} from '@/app/components/settings/DisqualificationReasonEditor';
import { DisqualificationReasonList } from '@/app/components/settings/DisqualificationReasonList';
import QualificationCriteriaSkeleton from '@/app/components/settings/QualificationCriteriaSkeleton';
import { SettingsSection } from '@/app/components/settings/SettingsSection';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    archiveDisqualificationReason,
    createDisqualificationReason,
    getDisqualificationReasons,
    restoreDisqualificationReason,
    updateDisqualificationReason,
} from '@/app/lib/api';
import { isCanonicalDisqualificationReasonCode } from '@/app/lib/contactLifecycle';
import { toastSuccess } from '@/app/lib/toast';
import type { DisqualificationReason } from '@/app/lib/types';
import { Button } from '@/components/ui/button';

/** Workspace settings for the labels and rules used when a lead is disqualified (#559). */
export default function DisqualificationReasonsPanel() {
    const { activeWorkspaceId } = useWorkspace();
    if (activeWorkspaceId === null) return null;
    return <WorkspaceDisqualificationReasonsPanel key={activeWorkspaceId} workspaceId={activeWorkspaceId} />;
}

function WorkspaceDisqualificationReasonsPanel({ workspaceId }: { workspaceId: number }) {
    const t = useTranslations('WorkspaceDisqualification');
    const showApiError = useApiErrorToast('WorkspaceDisqualification');
    const [reasons, setReasons] = useState<DisqualificationReason[]>([]);
    const [reasonsWorkspaceId, setReasonsWorkspaceId] = useState<number | null>(null);
    const [draft, setDraft] = useState<DisqualificationReasonDraft | null>(null);
    const [loading, setLoading] = useState(true);
    const [unavailable, setUnavailable] = useState(false);
    const [saving, setSaving] = useState(false);
    const [showArchived, setShowArchived] = useState(false);
    const mounted = useRef(true);

    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
        };
    }, []);

    const load = useCallback(async (targetWorkspaceId: number) => {
        try {
            const loaded = await getDisqualificationReasons(true);
            setReasons(loaded);
            setReasonsWorkspaceId(targetWorkspaceId);
            setUnavailable(false);
        } catch {
            setUnavailable(true);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        let cancelled = false;
        getDisqualificationReasons(true)
            .then((loaded) => {
                if (cancelled) return;
                setReasons(loaded);
                setReasonsWorkspaceId(workspaceId);
                setUnavailable(false);
                setLoading(false);
            })
            .catch(() => {
                if (cancelled) return;
                setReasonsWorkspaceId(workspaceId);
                setUnavailable(true);
                setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [workspaceId]);

    const visible = useMemo(
        () => reasonsWorkspaceId === workspaceId
            ? reasons.filter((reason) => showArchived || reason.archivedAt === null)
            : [],
        [reasons, reasonsWorkspaceId, showArchived, workspaceId],
    );
    const archivedCount = reasonsWorkspaceId === workspaceId
        ? reasons.filter((reason) => reason.archivedAt !== null).length
        : 0;
    const draftCodeValid = draft !== null
        && isCanonicalDisqualificationReasonCode(draft.code);
    const draftLabelValid = draft !== null
        && (draft.builtIn || draft.label.trim() !== '');

    const edit = (reason: DisqualificationReason) => {
        setDraft({
            workspaceId,
            id: reason.id,
            code: reason.code,
            label: reason.label ?? '',
            requiresNote: reason.requiresNote,
            position: reason.position,
            builtIn: reason.builtIn,
        });
    };

    const save = async () => {
        if (!draft
            || draft.workspaceId !== workspaceId
            || saving
            || !isCanonicalDisqualificationReasonCode(draft.code)
            || (!draft.builtIn && !draft.label.trim())) return;
        setSaving(true);
        try {
            const payload = {
                code: draft.code,
                label: draft.label.trim() || null,
                requiresNote: draft.requiresNote,
                position: draft.position,
            };
            if (draft.id === null) {
                await createDisqualificationReason(payload);
            } else {
                await updateDisqualificationReason(draft.id, payload);
            }
            if (!mounted.current) return;
            setDraft(null);
            await load(workspaceId);
            toastSuccess(t('saved'));
        } catch (error) {
            showApiError(error, 'saveFailed');
        } finally {
            setSaving(false);
        }
    };

    const archive = async (reason: DisqualificationReason) => {
        try {
            await archiveDisqualificationReason(reason.id);
            if (!mounted.current) return;
            await load(workspaceId);
            toastSuccess(t('archived'));
        } catch (error) {
            showApiError(error, 'archiveFailed');
        }
    };

    const restore = async (reason: DisqualificationReason) => {
        try {
            await restoreDisqualificationReason(reason.id);
            if (!mounted.current) return;
            await load(workspaceId);
            toastSuccess(t('restored'));
        } catch (error) {
            showApiError(error, 'restoreFailed');
        }
    };

    if (loading || reasonsWorkspaceId !== workspaceId) {
        return (
            <SettingsSection title={t('title')} description={t('description')}>
                <QualificationCriteriaSkeleton />
            </SettingsSection>
        );
    }

    return (
        <SettingsSection
            title={t('title')}
            description={t('description')}
            action={!unavailable && draft === null ? (
                <Button
                    size="inline"
                    variant="outline"
                    onClick={() => setDraft({
                        workspaceId,
                        id: null,
                        code: '',
                        label: '',
                        requiresNote: false,
                        position: reasons.length,
                        builtIn: false,
                    })}
                >
                    <PlusIcon className="size-4" />
                    {t('add')}
                </Button>
            ) : undefined}
        >
            {unavailable ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-5">
                    <p className="text-sm font-medium text-foreground">{t('loadFailed')}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{t('loadFailedBody')}</p>
                    <Button
                        size="inline"
                        variant="outline"
                        className="mt-4"
                        onClick={() => void load(workspaceId)}
                    >
                        {t('retry')}
                    </Button>
                </div>
            ) : (
                <div className="space-y-4">
                    {draft ? (
                        <DisqualificationReasonEditor
                            draft={draft}
                            saving={saving}
                            codeValid={draftCodeValid}
                            labelValid={draftLabelValid}
                            onChange={setDraft}
                            onCancel={() => setDraft(null)}
                            onSave={() => void save()}
                        />
                    ) : null}
                    <DisqualificationReasonList
                        reasons={visible}
                        onEdit={edit}
                        onArchive={(reason) => void archive(reason)}
                        onRestore={(reason) => void restore(reason)}
                    />
                    <DisqualificationReasonArchiveControls
                        archivedCount={archivedCount}
                        showArchived={showArchived}
                        onShowArchivedChange={setShowArchived}
                    />
                </div>
            )}
        </SettingsSection>
    );
}
