'use client';

import { useEffect, useId, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';

import { ApiError, getAiAgentHoldingsPurge, purgeAiAgentHoldings } from '@/app/lib/api';
import { currentAccountBinding } from '@/app/lib/browserAccountStorage';
import { useAccountStorageGeneration } from '@/app/hooks/useAccountStorageGeneration';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import type { AiAgentHoldingsPurge, DataSubjectRequest } from '@/app/lib/types';

type Recovery = { idempotencyKey: string; operationId: string | null; schemaVersion: 1 | 2 };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function readRecovery(key: string): Recovery | null {
    try {
        const saved: unknown = JSON.parse(localStorage.getItem(key) ?? 'null');
        if (typeof saved !== 'object' || saved === null || !('idempotencyKey' in saved) || !('operationId' in saved)) return null;
        if (typeof saved.idempotencyKey !== 'string' || !UUID.test(saved.idempotencyKey)
            || (saved.operationId !== null && (typeof saved.operationId !== 'string' || !UUID.test(saved.operationId)))) return null;
        if ('schemaVersion' in saved && saved.schemaVersion !== 1 && saved.schemaVersion !== 2) return null;
        return { idempotencyKey: saved.idempotencyKey, operationId: saved.operationId,
            schemaVersion: 'schemaVersion' in saved && saved.schemaVersion === 2 ? 2 : 1 };
    } catch { return null; }
}

/** Explicit, recoverable organization-wide erasure acknowledgement within the DSR tracker. */
export default function MissionHoldingsPurgeDialog({ orgId, request, onClose, onStale }: {
    orgId: number;
    request: DataSubjectRequest;
    onClose: () => void;
    onStale: () => void;
}) {
    const legacy = useTranslations('OrgDataRequests.missionPurge');
    const assistant = useTranslations('OrgDataRequests.assistantPurge');
    const showApiError = useApiErrorToast('OrgDataRequests.missionPurge');
    const id = useId();
    const [acknowledged, setAcknowledged] = useState(false);
    const [cutoffAcknowledged, setCutoffAcknowledged] = useState(false);
    const [operation, setOperation] = useState<AiAgentHoldingsPurge | null>(null);
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);
    const [hasAcceptedOperation, setHasAcceptedOperation] = useState(false);
    const [stale, setStale] = useState(false);
    const [reload, setReload] = useState(0);
    const [recoveryVersion, setRecoveryVersion] = useState<1 | 2>(2);
    const t = operation?.schemaVersion === 1 || recoveryVersion === 1 ? legacy : assistant;
    const controllerRef = useRef<AbortController | null>(null);
    const recoveryRef = useRef<Recovery | null>(null);
    const storageKeyRef = useRef<string | null>(null);
    const submittingRef = useRef(false);
    const canPersist = useAccountStorageGeneration(() => {
        controllerRef.current?.abort();
        onClose();
    });

    useEffect(() => {
        const controller = new AbortController();
        controllerRef.current = controller;
        const account = currentAccountBinding().identity;
        const key = account === null ? null : `connex:mission-purge:${account}:${orgId}:${request.id}`;
        storageKeyRef.current = key;
        recoveryRef.current = key === null ? null : readRecovery(key);
        setRecoveryVersion(recoveryRef.current?.schemaVersion ?? 2);
        const operationId = recoveryRef.current?.operationId;
        setHasAcceptedOperation(operationId != null);
        if (operationId) {
            setBusy(true);
            void getAiAgentHoldingsPurge(orgId, request.id, operationId, { signal: controller.signal })
                .then((result) => { if (!controller.signal.aborted) { setOperation(result); setFailed(false); } })
                .catch(() => { if (!controller.signal.aborted) setFailed(true); })
                .finally(() => { if (!controller.signal.aborted) setBusy(false); });
        }
        return () => controller.abort();
    }, [orgId, reload, request.id]);

    useEffect(() => {
        if (!operation || operation.state === 'complete') return;
        const signal = controllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        const timer = window.setInterval(() => {
            void getAiAgentHoldingsPurge(orgId, request.id, operation.operationId, { signal })
                .then((result) => { if (!signal.aborted) { setOperation(result); setFailed(false); } })
                .catch(() => { if (!signal.aborted) setFailed(true); });
        }, 5_000);
        return () => window.clearInterval(timer);
    }, [operation, orgId, request.id]);

    function remember(recovery: Recovery) {
        recoveryRef.current = recovery;
        const key = storageKeyRef.current;
        if (!key || !canPersist()) return;
        try { localStorage.setItem(key, JSON.stringify(recovery)); } catch { return; }
    }

    async function submit() {
        const signal = controllerRef.current?.signal;
        if (!signal || signal.aborted || submittingRef.current || !acknowledged || !cutoffAcknowledged || stale || !canPersist()) return;
        submittingRef.current = true;
        setBusy(true);
        setFailed(false);
        const recovery = recoveryRef.current ?? { idempotencyKey: crypto.randomUUID(), operationId: null, schemaVersion: 2 as const };
        remember(recovery);
        try {
            const accepted = await purgeAiAgentHoldings(orgId, request.id, {
                idempotencyKey: recovery.idempotencyKey,
                snapshotToken: request.snapshotToken,
                scopeAcknowledgement: recovery.schemaVersion === 1
                    ? 'organization_mission_holdings_v1' : 'organization_assistant_holdings_v2',
                cutoffPolicyAcknowledgement: recovery.schemaVersion === 1
                    ? 'contents_at_each_workspace_first_invalidation_v1' : 'organization_generation_at_acceptance_v2',
            }, { signal });
            if (signal.aborted || !canPersist()) return;
            remember({ ...recovery, operationId: accepted.operationId });
            setOperation(accepted);
            setHasAcceptedOperation(true);
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && error.code?.toLowerCase() === 'dsr_snapshot_conflict') {
                setStale(true);
                onStale();
            } else showApiError(error, 'failed');
        } finally {
            if (!signal.aborted) { submittingRef.current = false; setBusy(false); }
        }
    }

    return (
        <Dialog open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
            <DialogContent aria-busy={busy}>
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>{operation ? t('accepted') : t('description')}</DialogDescription>
                </DialogHeader>
                {operation ? (
                    <div role="status" className="space-y-2 text-sm">
                        <p>{t(`states.${operation.state}`)}</p>
                        <p className="text-muted-foreground">{t('progress', {
                            completed: operation.completedWorkspaceCount, total: operation.workspaceCount,
                        })}</p>
                        <p className="text-muted-foreground">{t('workflowUnchanged')}</p>
                    </div>
                ) : (
                    <div className="space-y-4">
                        <div className="flex items-start gap-3">
                            <Checkbox id={`${id}-scope`} checked={acknowledged} disabled={busy || stale}
                                onCheckedChange={(value) => setAcknowledged(value === true)} />
                            <Label htmlFor={`${id}-scope`} className="text-sm leading-relaxed">{t('scope')}</Label>
                        </div>
                        <div className="flex items-start gap-3">
                            <Checkbox id={`${id}-cutoff`} checked={cutoffAcknowledged} disabled={busy || stale}
                                onCheckedChange={(value) => setCutoffAcknowledged(value === true)} />
                            <Label htmlFor={`${id}-cutoff`} className="text-sm leading-relaxed">{t('cutoff')}</Label>
                        </div>
                        <p className="text-sm text-muted-foreground">{t('irreversible')}</p>
                    </div>
                )}
                {stale ? <p role="alert" className="text-sm text-destructive">{t('stale')}</p> : null}
                {failed ? <p role="alert" className="text-sm text-destructive">{t('failed')}</p> : null}
                <DialogFooter>
                    <Button type="button" variant="outline" onClick={onClose} disabled={busy}>{t('close')}</Button>
                    {failed ? <Button type="button" variant="outline" onClick={() => setReload((value) => value + 1)}>{t('retry')}</Button> : null}
                    {!operation && !hasAcceptedOperation ? (
                        <Button type="button" variant="destructive" disabled={busy || stale || !acknowledged || !cutoffAcknowledged}
                            onClick={() => void submit()}>{busy ? t('starting') : t('confirm')}</Button>
                    ) : null}
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
