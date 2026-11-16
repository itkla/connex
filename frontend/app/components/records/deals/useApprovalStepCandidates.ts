import { useEffect, useState } from 'react';

import { getDocumentApprovalApproverCandidates } from '@/app/lib/api';
import type { ApprovalStepCandidates } from '@/app/lib/types';

/** Identity of the active step whose advisory candidates are being read. */
export type ApprovalStepCandidatesRequest = {
    workspaceId: number;
    dealId: number;
    documentId: number;
    approvalId: number;
    stepId: number;
};

type Result = {
    key: string | null;
    epoch: number;
    status: 'loading' | 'ready' | 'unavailable';
    data: ApprovalStepCandidates | null;
};

/** Keeps advisory candidates isolated to the selected workspace, document, approval and step. */
export function useApprovalStepCandidates(
    request: ApprovalStepCandidatesRequest | null,
    attempt: number,
    onLoaded: (data: ApprovalStepCandidates, request: ApprovalStepCandidatesRequest) => void,
) {
    const workspaceId = request?.workspaceId;
    const dealId = request?.dealId;
    const documentId = request?.documentId;
    const approvalId = request?.approvalId;
    const stepId = request?.stepId;
    const key = request === null ? null : JSON.stringify([
        workspaceId, dealId, documentId, approvalId, stepId, attempt,
    ]);
    const [result, setResult] = useState<Result>({ key, epoch: 0, status: 'loading', data: null });
    const epoch = result.key === key ? result.epoch : result.epoch + 1;
    if (result.key !== key) {
        setResult({ key, epoch, status: 'loading', data: null });
    }

    useEffect(() => {
        if (key === null || workspaceId === undefined || dealId === undefined
            || documentId === undefined || approvalId === undefined || stepId === undefined) return;
        const controller = new AbortController();
        getDocumentApprovalApproverCandidates(dealId, documentId, stepId, { signal: controller.signal })
            .then((data) => {
                if (controller.signal.aborted) return;
                onLoaded(data, { workspaceId, dealId, documentId, approvalId, stepId });
                setResult((current) => current.key === key && current.epoch === epoch
                    ? { key, epoch, status: 'ready', data } : current);
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    setResult((current) => current.key === key && current.epoch === epoch
                        ? { key, epoch, status: 'unavailable', data: null } : current);
                }
            });
        return () => controller.abort();
    }, [key, epoch, workspaceId, dealId, documentId, approvalId, stepId, onLoaded]);

    if (key === null) return { key, status: 'hidden' as const, data: null };
    if (result.key !== key) return { key, status: 'loading' as const, data: null };
    return { key, status: result.status, data: result.data };
}
