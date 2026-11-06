'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react';

import * as api from '@/app/lib/api';
import type * as Types from '@/app/lib/types';
import type { DetailSectionResult } from '@/app/lib/detailSectionResult';
import { isSessionExpired, redirectToSignIn } from '@/app/lib/sessionExpiry';
import { renderedWorkspaceId } from '@/app/lib/renderedWorkspace';
import { useWorkspace } from '@/app/hooks/useWorkspace';

/** Payload types shared by the initial server reads and isolated client recovery. */
export type DetailSectionData = {
    companyPeople: Types.Contact[];
    companyDeals: Types.Deal[];
    dealActivities: Types.Activity[];
    dealNotes: Types.Note[];
    dealTasks: Types.Task[];
    dealDocuments: Types.DealDocument[];
    dealLineItems: Types.DealLineItemsResponse;
    contactEmployment: Types.PersonEmployment[];
    contactConnections: Types.PersonConnection[];
    contactIntroPath: Types.IntroPath;
    contactHistory: Types.ContactLifecycleHistoryEntry[];
    campaignMessages: Types.CampaignMessage[];
    campaignSends: Types.CampaignSend[];
    campaignExports: Types.CampaignAudienceExport[];
};
export type DetailSectionKey = keyof DetailSectionData;
export type DetailSectionResults = { [K in DetailSectionKey]?: DetailSectionResult<DetailSectionData[K]> };

const loaders: { [K in DetailSectionKey]: (id: number, init: RequestInit) => Promise<DetailSectionData[K]> } = {
    companyPeople: (id, init) => api.getCompanyPeople(id, init),
    companyDeals: (id, init) => api.getCompanyDeals(id, init),
    dealActivities: (id, init) => api.getActivitiesForDeal(id, init),
    dealNotes: (id, init) => api.getNotesForDeal(id, {}, init),
    dealTasks: (id, init) => api.getTasksForDeal(id, init),
    dealDocuments: (id, init) => api.getDealDocuments(id, init),
    dealLineItems: (id, init) => api.getDealLineItems(id, init),
    contactEmployment: (id, init) => api.getContactEmployment(id, init),
    contactConnections: (id, init) => api.getContactConnections(id, init),
    contactIntroPath: (id, init) => api.getContactIntroPath(id, init),
    contactHistory: (id, init) => api.getContactLifecycleHistory(id, init),
    campaignMessages: (id, init) => api.getCampaignMessages(id, init),
    campaignSends: (id, init) => api.getCampaignSends(id, init),
    campaignExports: (id, init) => api.getCampaignExports(id, init),
};

type DetailSectionsContextValue = {
    results: DetailSectionResults;
    pending: ReadonlySet<DetailSectionKey>;
    retry: (keys: readonly DetailSectionKey[]) => Promise<boolean>;
};
const DetailSectionsContext = createContext<DetailSectionsContextValue | null>(null);

/** Holds only this record's section outcomes; recovery fetches failed collections without refreshing the route. */
export function DetailSectionsProvider({ recordId, workspaceId: requestedWorkspaceId, initial, children }: {
    recordId: number;
    workspaceId?: number;
    initial: DetailSectionResults;
    children: ReactNode;
}) {
    const workspace = useWorkspace();
    const [workspaceId] = useState(requestedWorkspaceId ?? workspace.activeWorkspaceId);
    const [snapshot, setSnapshot] = useState({ initial, results: initial });
    if (snapshot.initial !== initial) setSnapshot({ initial, results: initial });
    const results = snapshot.initial === initial ? snapshot.results : initial;
    const [pending, setPending] = useState<ReadonlySet<DetailSectionKey>>(() => new Set());
    const requests = useRef(new Map<DetailSectionKey, AbortController>());
    const alive = useRef(true);
    useEffect(() => {
        alive.current = true;
        const active = requests.current;
        return () => {
            alive.current = false;
            for (const controller of active.values()) controller.abort();
        };
    }, [initial]);

    const retry = useCallback(async (keys: readonly DetailSectionKey[]) => {
        const failed = [...new Set(keys)].filter((key) => results[key]?.kind === 'failed');
        if (workspaceId == null || renderedWorkspaceId() !== workspaceId || failed.length === 0) return false;
        const recovered = await Promise.all(failed.map(async (key) => {
            if (requests.current.has(key)) return false;
            const controller = new AbortController();
            requests.current.set(key, controller);
            setPending((previous) => new Set([...previous, key]));
            try {
                const data = await loaders[key](recordId, {
                    headers: { 'X-Workspace-Id': String(workspaceId) }, signal: controller.signal,
                });
                if (!alive.current || controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return false;
                setSnapshot((previous) => previous.initial !== initial ? previous : {
                    ...previous, results: { ...previous.results, [key]: { kind: 'loaded', data } },
                });
                return true;
            } catch (error) {
                if (!alive.current || controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return false;
                if (isSessionExpired(error)) {
                    redirectToSignIn();
                    return false;
                }
                const failure = error instanceof api.ApiError
                    ? { kind: 'failed' as const, status: error.status, reference: error.correlationId }
                    : { kind: 'failed' as const };
                setSnapshot((previous) => previous.initial !== initial ? previous : {
                    ...previous, results: { ...previous.results, [key]: failure },
                });
                return false;
            } finally {
                requests.current.delete(key);
                if (alive.current) setPending((previous) => new Set([...previous].filter((value) => value !== key)));
            }
        }));
        return recovered.every(Boolean);
    }, [initial, recordId, results, workspaceId]);

    const value = useMemo(() => ({ results, pending, retry }), [results, pending, retry]);

    if (workspace.switching || workspace.activeWorkspaceId !== workspaceId) return null;
    return <DetailSectionsContext.Provider value={value}>{children}</DetailSectionsContext.Provider>;
}

/** Reads the current page's outcomes; shared components outside detail pages keep their ordinary props. */
export function useDetailSections() {
    return useContext(DetailSectionsContext);
}

/** Keeps a collection editor's local mutations while adopting a recovered server collection. */
export function useDetailSectionData<K extends DetailSectionKey>(key: K, initial: DetailSectionData[K]): [DetailSectionData[K], Dispatch<SetStateAction<DetailSectionData[K]>>] {
    const result = useDetailSections()?.results[key];
    const [seen, setSeen] = useState(result);
    const [data, setData] = useState(result?.kind === 'loaded' ? result.data : initial);
    if (result !== seen) {
        setSeen(result);
        if (result?.kind === 'loaded') setData(result.data);
    }
    return [data, setData];
}
