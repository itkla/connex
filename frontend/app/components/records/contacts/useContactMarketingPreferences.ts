'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import {
    ApiError, createSuppression, deleteSuppression, getPersonConsent,
    getPersonMarketingStatus, getPersonSuppressions, setPersonConsent,
} from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import type { ContactChannelConsent, ContactMarketingStatus, ContactSuppression } from '@/app/lib/types';
import type { MarketingChannel } from './contactMarketingPreferences';

export type MarketingPreferenceAction =
    | { kind: 'consent'; channel: MarketingChannel; status: ContactChannelConsent['status']; expectedVersion: number }
    | { kind: 'restrict'; channel: MarketingChannel; address: string }
    | { kind: 'remove'; entry: ContactSuppression };

type Preferences = {
    consent: ContactChannelConsent[];
    status: ContactMarketingStatus;
    suppressions: ContactSuppression[];
    nextAfterId: number | null;
};

/** Loads privileged preferences only for the owning workspace and serializes explicit changes. */
export function useContactMarketingPreferences(personId: number, workspaceId: number, snapshot: string) {
    const router = useRouter();
    const t = useTranslations('ContactMarketingPreferences');
    const showError = useApiErrorToast('ContactMarketingPreferences');
    const [data, setData] = useState<Preferences | null>(null);
    const [error, setError] = useState<'denied' | 'failed' | null>(null);
    const [loading, setLoading] = useState(true);
    const [settledSnapshot, setSettledSnapshot] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [conflicted, setConflicted] = useState(false);
    const [revision, setRevision] = useState(0);
    const pending = useRef(false);
    const mounted = useRef(true);
    const readGeneration = useRef(0);
    const refreshing = settledSnapshot !== snapshot;

    const read = useCallback(async (signal?: AbortSignal): Promise<Preferences | null> => {
        const generation = ++readGeneration.current;
        const init = { ...workspacePinnedRequest(workspaceId), signal };
        try {
            const [consent, status, page] = await Promise.all([
                getPersonConsent(personId, init), getPersonMarketingStatus(personId, init), getPersonSuppressions(personId, 0, init),
            ]);
            return generation === readGeneration.current ? { consent, status, suppressions: page.items, nextAfterId: page.nextAfterId ?? null } : null;
        } catch (err) {
            if (generation !== readGeneration.current) return null;
            throw err;
        }
    }, [personId, workspaceId]);

    useEffect(() => {
        mounted.current = true;
        const controller = new AbortController();
        void read(controller.signal).then((loaded) => {
            if (loaded === null || controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return;
            setData(loaded);
            setError(null);
        }).catch((err: unknown) => {
            if (controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return;
            showError(err, 'loadFailedTitle');
            setData(null);
            setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
        }).finally(() => {
            if (!controller.signal.aborted) { setLoading(false); setSettledSnapshot(snapshot); }
        });
        return () => { mounted.current = false; controller.abort(); };
    }, [read, revision, showError, snapshot, workspaceId]);

    const apply = async (action: MarketingPreferenceAction): Promise<boolean> => {
        if (pending.current || data === null || error !== null || renderedWorkspaceId() !== workspaceId) return false;
        pending.current = true;
        readGeneration.current += 1;
        setBusy(true);
        setConflicted(false);
        const current = () => mounted.current && renderedWorkspaceId() === workspaceId;
        try {
            if (action.kind === 'consent') {
                await setPersonConsent(personId, {
                    channel: action.channel, purpose: 'marketing', status: action.status,
                    source: 'manual', evidenceRef: null, capturedAt: null, expectedVersion: action.expectedVersion,
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
                if (loaded !== null && current()) { setData(loaded); setError(null); }
            } catch (err) {
                if (current()) {
                    showError(err, 'loadFailedTitle');
                    setData(null);
                    setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
                }
            }
            return true;
        } catch (err) {
            if (current() && err instanceof ApiError && err.status === 409 && err.code === 'STALE_RECORD') {
                setConflicted(true);
                try {
                    const loaded = await read();
                    if (loaded !== null && current()) { setData(loaded); setError(null); }
                } catch (reloadError) {
                    if (current()) {
                        showError(reloadError, 'loadFailedTitle');
                        setData(null);
                        setError(reloadError instanceof ApiError && reloadError.status === 403 ? 'denied' : 'failed');
                    }
                }
                return true;
            }
            if (current()) {
                showError(err, 'saveFailed');
                setData(null);
                setError(err instanceof ApiError && err.status === 403 ? 'denied' : 'failed');
            }
            return false;
        } finally {
            pending.current = false;
            if (mounted.current) setBusy(false);
        }
    };

    const loadMore = async () => {
        if (pending.current || refreshing || data?.nextAfterId == null || renderedWorkspaceId() !== workspaceId) return;
        const generation = readGeneration.current;
        pending.current = true;
        setBusy(true);
        try {
            const page = await getPersonSuppressions(personId, data.nextAfterId, workspacePinnedRequest(workspaceId));
            if (generation === readGeneration.current && mounted.current && renderedWorkspaceId() === workspaceId) {
                setData((previous) => previous === null ? null : {
                    ...previous,
                    suppressions: [...new Map([...previous.suppressions, ...page.items].map((entry) => [entry.id, entry])).values()],
                    nextAfterId: page.nextAfterId ?? null,
                });
            }
        } catch (err) {
            if (generation === readGeneration.current && mounted.current && renderedWorkspaceId() === workspaceId) {
                showError(err, 'loadFailedTitle');
                if (err instanceof ApiError && (err.status === 403 || err.status === 401)) {
                    setData(null);
                    setError(err.status === 403 ? 'denied' : 'failed');
                }
            }
        } finally {
            pending.current = false;
            if (mounted.current) setBusy(false);
        }
    };

    return { data, error, loading, refreshing, busy, conflicted, apply, loadMore, retry: () => { setLoading(true); setRevision((value) => value + 1); } };
}
