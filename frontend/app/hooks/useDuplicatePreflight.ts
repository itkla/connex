'use client';

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

import {
    preflightCompanyDuplicates,
    preflightDealDuplicates,
    preflightPersonDuplicates,
} from '@/app/lib/api';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { DuplicatePreflightResponse } from '@/app/lib/types';

export type DuplicatePreflightKind = 'company' | 'deal' | 'person';

export type DuplicatePreflightValues = {
    name: string;
    email?: string;
    phone?: string;
    website?: string;
    companyId?: number | null;
};

export type DuplicatePreflightStatus = 'idle' | 'checking' | 'ready' | 'error';

export type DuplicatePreflightDecision = {
    allowed: boolean;
    duplicateReviewToken: string | null;
    reviewSignature: string | null;
    response: DuplicatePreflightResponse | null;
};

type CompletedCheck = {
    requestKey: string;
    status: 'checking' | 'ready' | 'error';
    response: DuplicatePreflightResponse | null;
};

const DEBOUNCE_MS = 400;

function candidateList(value?: string): string[] {
    const trimmed = value?.trim();
    return trimmed ? [trimmed] : [];
}

function eligible(kind: DuplicatePreflightKind, values: DuplicatePreflightValues): boolean {
    const nameReady = values.name.trim().length > 0;
    if (kind === 'deal') return nameReady;
    const phoneReady = (values.phone?.replaceAll(/\D/g, '').length ?? 0) >= 7;
    if (kind === 'person') {
        return nameReady || values.email?.includes('@') === true || phoneReady;
    }
    return nameReady || (values.website?.trim().length ?? 0) > 0 || phoneReady;
}

function fingerprint(
    workspaceKey: string,
    active: boolean,
    kind: DuplicatePreflightKind,
    values: DuplicatePreflightValues,
): string {
    return JSON.stringify([
        workspaceKey,
        active,
        kind,
        values.name.trim(),
        values.email?.trim() ?? '',
        values.phone?.trim() ?? '',
        values.website?.trim() ?? '',
        values.companyId ?? null,
    ]);
}

/** Produces the acknowledgement identity for one exact duplicate-review response. */
export function duplicatePreflightResponseSignature(
    response: DuplicatePreflightResponse,
): string {
    return JSON.stringify([
        response.reviewToken,
        response.truncated,
        response.candidates.map((candidate) => [
            candidate.recordType,
            candidate.recordId,
            candidate.name,
            candidate.companyName,
            candidate.title,
            candidate.website,
            candidate.industry,
            candidate.ownedByActiveWorkspace,
            candidate.strength,
            candidate.matches.map((match) => [
                match.kind,
                match.normalizedValue,
                match.strength,
            ]),
        ]),
    ]);
}

/**
 * Debounces the canonical server duplicate check and binds acknowledgement to the exact checked values.
 */
export function useDuplicatePreflight(
    kind: DuplicatePreflightKind,
    values: DuplicatePreflightValues,
    active = true,
    requestInit?: RequestInit,
) {
    const { activeWorkspaceId } = useWorkspace();
    const explicitWorkspaceId = new Headers(requestInit?.headers).get('X-Workspace-Id');
    const workspaceKey = explicitWorkspaceId ?? activeWorkspaceId?.toString() ?? '';
    const requestFingerprint = fingerprint(workspaceKey, active, kind, values);
    const requestKey = requestFingerprint;
    const requestEligible = active
        && workspaceKey !== ''
        && eligible(kind, values);
    const [completed, setCompleted] = useState<CompletedCheck | null>(null);
    const [retryGeneration, setRetryGeneration] = useState(0);
    const [acknowledgedResponse, setAcknowledgedResponse] = useState<string | null>(null);
    const requestSequenceRef = useRef(0);
    const currentRequestKeyRef = useRef(requestKey);

    useLayoutEffect(() => {
        if (currentRequestKeyRef.current === requestKey) return;
        currentRequestKeyRef.current = requestKey;
        requestSequenceRef.current += 1;
        setAcknowledgedResponse(null);
    }, [requestKey]);

    const runCheck = useCallback(async (dealReviewToken?: string) => {
        if (!requestEligible) return null;
        const sequence = requestSequenceRef.current + 1;
        requestSequenceRef.current = sequence;
        setCompleted({ requestKey, status: 'checking', response: null });
        try {
            let response: DuplicatePreflightResponse;
            if (kind === 'person') {
                response = await preflightPersonDuplicates({
                    name: values.name,
                    emails: candidateList(values.email),
                    phones: candidateList(values.phone),
                }, requestInit);
            } else if (kind === 'company') {
                response = await preflightCompanyDuplicates({
                    name: values.name,
                    websites: candidateList(values.website),
                    phones: candidateList(values.phone),
                }, requestInit);
            } else {
                response = await preflightDealDuplicates({
                    name: values.name,
                    companyId: values.companyId,
                    reviewToken: dealReviewToken,
                }, requestInit);
            }
            if (requestSequenceRef.current === sequence
                    && currentRequestKeyRef.current === requestKey) {
                setCompleted({ requestKey, status: 'ready', response });
                return response;
            }
            return null;
        } catch {
            if (requestSequenceRef.current === sequence
                    && currentRequestKeyRef.current === requestKey) {
                setCompleted({ requestKey, status: 'error', response: null });
            }
            return null;
        }
    }, [
        kind,
        requestEligible,
        requestKey,
        requestInit,
        values.companyId,
        values.email,
        values.name,
        values.phone,
        values.website,
    ]);

    useEffect(() => {
        if (!requestEligible) return;
        const timer = window.setTimeout(() => {
            void runCheck();
        }, DEBOUNCE_MS);
        return () => {
            window.clearTimeout(timer);
        };
    }, [requestEligible, retryGeneration, runCheck]);

    const current = completed?.requestKey === requestKey ? completed : null;
    const status: DuplicatePreflightStatus = !requestEligible
        ? 'idle'
        : current?.status ?? 'checking';
    const response = current?.response ?? null;
    const hasCandidates = (response?.candidates.length ?? 0) > 0 || response?.truncated === true;
    const acknowledgementKey = response
        ? `${requestKey}:${duplicatePreflightResponseSignature(response)}`
        : null;
    const acknowledged = acknowledgementKey != null
        && acknowledgedResponse === acknowledgementKey;
    const blocked = status === 'checking'
        || status === 'error'
        || status === 'ready' && (
            response?.truncated === true
            || hasCandidates && !acknowledged
        );
    const setAcknowledged = useCallback((checked: boolean) => {
        setAcknowledgedResponse(checked ? acknowledgementKey : null);
    }, [acknowledgementKey]);
    const retry = useCallback(() => {
        setRetryGeneration((generation) => generation + 1);
    }, []);
    const reviewNow = useCallback(async (): Promise<DuplicatePreflightDecision> => {
        if (!requestEligible) {
            return {
                allowed: true,
                duplicateReviewToken: null,
                reviewSignature: null,
                response: null,
            };
        }
        const checked = await runCheck(
            kind === 'deal' ? response?.reviewToken : undefined,
        );
        if (!checked || checked.truncated) {
            return {
                allowed: false,
                duplicateReviewToken: null,
                reviewSignature: checked
                    ? duplicatePreflightResponseSignature(checked)
                    : null,
                response: checked,
            };
        }
        const reviewSignature = duplicatePreflightResponseSignature(checked);
        if (checked.candidates.length === 0) {
            return {
                allowed: true,
                duplicateReviewToken: kind === 'deal' ? checked.reviewToken : null,
                reviewSignature,
                response: checked,
            };
        }
        const checkedAcknowledgementKey =
            `${requestKey}:${reviewSignature}`;
        const duplicateAcknowledged =
            acknowledgedResponse === checkedAcknowledgementKey;
        return {
            allowed: duplicateAcknowledged,
            duplicateReviewToken: duplicateAcknowledged
                ? checked.reviewToken
                : null,
            reviewSignature,
            response: checked,
        };
    }, [acknowledgedResponse, kind, requestEligible, requestKey, response?.reviewToken, runCheck]);
    const checkNow = useCallback(async () => {
        const decision = await reviewNow();
        return decision.allowed;
    }, [reviewNow]);

    return {
        status,
        response,
        blocked,
        acknowledged,
        setAcknowledged,
        retry,
        checkNow,
        reviewNow,
    };
}
