'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
    ApiError, createSuppression, deleteSuppression, getPersonConsent,
    getPersonMarketingStatus, getSuppressions, setPersonConsent,
} from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import type { ContactChannelConsent, ContactMarketingStatus, SuppressionEntry } from '@/app/lib/types';
import type { MarketingChannel } from './contactMarketingPreferences';

export type MarketingPreferenceAction =
    | { kind: 'consent'; channel: MarketingChannel; status: ContactChannelConsent['status'] }
    | { kind: 'restrict'; channel: MarketingChannel; address: string }
    | { kind: 'remove'; entry: SuppressionEntry };

type Preferences = {
    consent: ContactChannelConsent[];
    status: ContactMarketingStatus;
    suppressions: SuppressionEntry[];
};

/** Loads privileged preferences only for the owning workspace and serializes explicit changes. */
export function useContactMarketingPreferences(personId: number, workspaceId: number) {
    const router = useRouter();
    const t = useTranslations('ContactMarketingPreferences');
    const showError = useApiErrorToast('ContactMarketingPreferences');
    const [data, setData] = useState<Preferences | null>(null);
    const [error, setError] = useState<'denied' | 'failed' | null>(null);
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [revision, setRevision] = useState(0);
    const pending = useRef(false);
    const mounted = useRef(true);

    const read = useCallback(async (signal?: AbortSignal) => {
        const init = { ...workspacePinnedRequest(workspaceId), signal };
        const [consent, status, suppressions] = await Promise.all([
            getPersonConsent(personId, init), getPersonMarketingStatus(personId, init), getSuppressions(init),
        ]);
        return { consent, status, suppressions };
    }, [personId, workspaceId]);

    useEffect(() => {
        mounted.current = true;
        const controller = new AbortController();
        void read(controller.signal).then((loaded) => {
            if (controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return;
            setData(loaded);
            setError(null);
        }).catch((err: unknown) => {
            if (controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return;
            showError(err, 'loadFailedTitle');
            setData(null);
            setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false);
        });
        return () => { mounted.current = false; controller.abort(); };
    }, [read, revision, showError, workspaceId]);

    const apply = async (action: MarketingPreferenceAction): Promise<boolean> => {
        if (pending.current || data === null || error !== null || renderedWorkspaceId() !== workspaceId) return false;
        pending.current = true;
        setBusy(true);
        const current = () => mounted.current && renderedWorkspaceId() === workspaceId;
        try {
            if (action.kind === 'consent') {
                await setPersonConsent(personId, {
                    channel: action.channel, purpose: 'marketing', status: action.status,
                    source: 'manual', evidenceRef: null, capturedAt: null,
                });
            } else if (action.kind === 'restrict') {
                await createSuppression({
                    scope: 'workspace', channel: action.channel, address: action.address,
                    personId, reason: 'do_not_contact',
                });
            } else {
                await deleteSuppression(action.entry.id);
            }
            if (!current()) return false;
            toastSuccess(t('saved'));
            router.refresh();
            try {
                const loaded = await read();
                if (current()) { setData(loaded); setError(null); }
            } catch (err) {
                if (current()) {
                    showError(err, 'loadFailedTitle');
                    setData(null);
                    setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
                }
            }
            return true;
        } catch (err) {
            if (current()) {
                showError(err, 'saveFailed');
                setData(null);
                setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
            }
            return false;
        } finally {
            pending.current = false;
            if (current()) setBusy(false);
        }
    };

    return { data, error, loading, busy, apply, retry: () => { setLoading(true); setRevision((value) => value + 1); } };
}
