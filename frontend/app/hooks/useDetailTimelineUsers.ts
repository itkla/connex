'use client';

import { useEffect, useRef, useState } from 'react';

import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useDetailSections, type DetailSectionResults } from '@/app/hooks/useDetailSections';
import { getUserReferences } from '@/app/lib/api';
import { renderedWorkspaceId } from '@/app/lib/renderedWorkspace';
import type { UserReference } from '@/app/lib/types';

type UserHydration = {
    initial: DetailSectionResults;
    seeds: UserReference[];
    workspaceId: number;
    users: Map<number, UserReference>;
};

function recoveredAuthors(kind: 'company' | 'deal' | 'contact' | undefined, initial: DetailSectionResults, results: DetailSectionResults) {
    const ids = kind === 'deal' ? [
        ...(results.dealActivities?.kind === 'loaded' && results.dealActivities !== initial.dealActivities
            ? results.dealActivities.data.map((entry) => entry.createdById) : []),
        ...(results.dealTasks?.kind === 'loaded' && results.dealTasks !== initial.dealTasks
            ? results.dealTasks.data.map((entry) => entry.assignedToId) : []),
        ...(results.dealNotes?.kind === 'loaded' && results.dealNotes !== initial.dealNotes
            ? results.dealNotes.data.map((entry) => entry.author) : []),
    ] : kind === 'contact' && results.contactHistory?.kind === 'loaded' && results.contactHistory !== initial.contactHistory
        ? results.contactHistory.data.map((entry) => entry.changedById) : [];
    return [...new Set(ids.filter((id): id is number => typeof id === 'number' && Number.isSafeInteger(id) && id > 0))]
        .sort((left, right) => left - right).join(',');
}

/** Hydrates only missing authors introduced by recovered detail collections, within their original workspace and server snapshot. */
export function useDetailTimelineUsers(kind: 'company' | 'deal' | 'contact' | undefined, seeds: UserReference[], workspaceId: number | null) {
    const sections = useDetailSections();
    const initial = sections?.initial;
    const authors = sections ? recoveredAuthors(kind, sections.initial, sections.results) : '';
    const lookup = useRef<UserHydration | null>(null);
    const [hydrated, setHydrated] = useState<UserHydration | null>(null);
    const showApiError = useApiErrorToast();

    useEffect(() => {
        if (!initial || workspaceId === null || renderedWorkspaceId() !== workspaceId) return;
        let current = lookup.current;
        if (current?.initial !== initial || current.seeds !== seeds || current.workspaceId !== workspaceId) {
            current = { initial, seeds, workspaceId, users: new Map(seeds.map((user) => [user.id, user])) };
            lookup.current = current;
        }
        const target = current;
        const missing = authors ? authors.split(',').map(Number).filter((id) => !target.users.has(id)) : [];
        if (missing.length === 0) return;
        const controller = new AbortController();
        getUserReferences(missing, { headers: { 'X-Workspace-Id': String(workspaceId) }, signal: controller.signal })
            .then((users) => {
                if (controller.signal.aborted || renderedWorkspaceId() !== workspaceId) return;
                for (const user of users) {
                    if (missing.includes(user.id)) target.users.set(user.id, user);
                }
                setHydrated({ ...target, users: new Map(target.users) });
            })
            .catch((error: unknown) => {
                if (!controller.signal.aborted && renderedWorkspaceId() === workspaceId) showApiError(error);
            });
        return () => controller.abort();
    }, [authors, initial, seeds, workspaceId, showApiError]);

    return hydrated?.initial === initial && hydrated?.seeds === seeds && hydrated?.workspaceId === workspaceId
        ? [...hydrated.users.values()] : seeds;
}
