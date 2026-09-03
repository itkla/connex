'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArchiveBoxIcon, ArrowUturnLeftIcon, PencilSquareIcon, PlusIcon } from '@heroicons/react/24/outline';

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
import {
    disqualificationReasonLabel,
    isCanonicalDisqualificationReasonCode,
} from '@/app/lib/contactLifecycle';
import { toastSuccess } from '@/app/lib/toast';
import type { DisqualificationReason } from '@/app/lib/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';

type Draft = {
    workspaceId: number;
    id: number | null;
    code: string;
    label: string;
    requiresNote: boolean;
    position: number;
    builtIn: boolean;
};

/** Workspace settings for the labels and rules used when a lead is disqualified (#559). */
export default function DisqualificationReasonsPanel() {
    const { activeWorkspaceId } = useWorkspace();
    if (activeWorkspaceId === null) return null;
    return <WorkspaceDisqualificationReasonsPanel key={activeWorkspaceId} workspaceId={activeWorkspaceId} />;
}

function WorkspaceDisqualificationReasonsPanel({ workspaceId }: { workspaceId: number }) {
    const t = useTranslations('WorkspaceDisqualification');
    const tl = useTranslations('ContactLifecycle');
    const showApiError = useApiErrorToast('WorkspaceDisqualification');
    const [reasons, setReasons] = useState<DisqualificationReason[]>([]);
    const [reasonsWorkspaceId, setReasonsWorkspaceId] = useState<number | null>(null);
    const [draft, setDraft] = useState<Draft | null>(null);
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

    const load = useCallback(async (workspaceId: number) => {
        try {
            const loaded = await getDisqualificationReasons(true);
            setReasons(loaded);
            setReasonsWorkspaceId(workspaceId);
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

    const labelFor = (reason: DisqualificationReason) => disqualificationReasonLabel(
        reason.code,
        reason.label,
        tl,
    );

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
                        <div className="rounded-2xl border border-brand/30 bg-brand-light/30 p-5">
                            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                                <div className="space-y-2">
                                    <Label htmlFor="reason-code">{t('codeField')}</Label>
                                    <Input
                                        id="reason-code"
                                        value={draft.code}
                                        maxLength={32}
                                        disabled={draft.id !== null || saving}
                                        aria-invalid={!draftCodeValid}
                                        aria-describedby={!draftCodeValid ? 'reason-code-error' : undefined}
                                        onChange={(event) => setDraft({ ...draft, code: event.target.value })}
                                        placeholder={t('codePlaceholder')}
                                    />
                                    {!draftCodeValid ? (
                                        <p id="reason-code-error" className="text-xs text-destructive">
                                            {t('codeInvalid')}
                                        </p>
                                    ) : null}
                                </div>
                                <div className="space-y-2">
                                    <Label htmlFor="reason-label">{t('labelField')}</Label>
                                    <Input
                                        id="reason-label"
                                        value={draft.label}
                                        maxLength={200}
                                        disabled={saving}
                                        onChange={(event) => setDraft({ ...draft, label: event.target.value })}
                                        placeholder={t('labelPlaceholder')}
                                    />
                                    {draft.builtIn ? (
                                        <p className="text-xs text-muted-foreground">{t('builtInLabelHint')}</p>
                                    ) : null}
                                </div>
                                <div className="space-y-2">
                                    <Label htmlFor="reason-position">{t('positionField')}</Label>
                                    <Input
                                        id="reason-position"
                                        type="number"
                                        min={0}
                                        value={draft.position}
                                        disabled={saving}
                                        onChange={(event) => setDraft({
                                            ...draft,
                                            position: Math.max(0, event.currentTarget.valueAsNumber || 0),
                                        })}
                                    />
                                </div>
                            </div>
                            <div className="mt-4 flex items-center justify-between gap-4">
                                <div className="flex items-center gap-3">
                                    <Switch
                                        id="reason-requires-note"
                                        checked={draft.requiresNote}
                                        disabled={saving}
                                        onCheckedChange={(checked) => setDraft({ ...draft, requiresNote: checked })}
                                    />
                                    <Label htmlFor="reason-requires-note">{t('requiresNoteField')}</Label>
                                </div>
                                <div className="flex gap-2">
                                    <Button variant="ghost" size="inline" disabled={saving} onClick={() => setDraft(null)}>
                                        {t('cancel')}
                                    </Button>
                                    <Button
                                        size="inline"
                                        disabled={saving || !draftCodeValid || !draftLabelValid}
                                        onClick={() => void save()}
                                    >
                                        {t('save')}
                                    </Button>
                                </div>
                            </div>
                        </div>
                    ) : null}

                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <ul className="divide-y divide-border">
                            {visible.map((reason) => (
                                <li key={reason.code} className="flex items-center gap-4 px-5 py-4">
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{labelFor(reason)}</p>
                                        <p className="mt-0.5 text-xs text-muted-foreground">
                                            {reason.requiresNote ? t('noteRequired') : t('noteOptional')}
                                            {' · '}{reason.code}
                                        </p>
                                    </div>
                                    {reason.archivedAt ? (
                                        <Button size="inline" variant="ghost" onClick={() => void restore(reason)}>
                                            <ArrowUturnLeftIcon className="size-4" />
                                            {t('restore')}
                                        </Button>
                                    ) : (
                                        <div className="flex gap-1">
                                            <Button size="inline" variant="ghost" onClick={() => edit(reason)}>
                                                <PencilSquareIcon className="size-4" />
                                                {t('edit')}
                                            </Button>
                                            <Button size="inline" variant="ghost" onClick={() => void archive(reason)}>
                                                <ArchiveBoxIcon className="size-4" />
                                                {t('archive')}
                                            </Button>
                                        </div>
                                    )}
                                </li>
                            ))}
                        </ul>
                    </div>

                    {archivedCount > 0 ? (
                        <div className="flex items-center gap-3">
                            <Switch id="show-archived-reasons" checked={showArchived} onCheckedChange={setShowArchived} />
                            <Label htmlFor="show-archived-reasons">{t('showArchived', { count: archivedCount })}</Label>
                        </div>
                    ) : null}
                </div>
            )}
        </SettingsSection>
    );
}
