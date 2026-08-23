'use client';

import { useEffect, useState } from 'react';

import { usePermissionCheck } from '@/app/hooks/usePermissions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { getAiAssistantSkills } from '@/app/lib/api';
import type { AiAssistantSkill, AiChatPageContextKind } from '@/app/lib/types';

const NO_SKILLS: readonly AiAssistantSkill[] = [];

/**
 * One in-flight or settled directory read per workspace and context kind.
 *
 * A list surface renders one entry point per row, and each of them asks the same question of the
 * same directory. Sharing the read keeps that a single request instead of one per card, and every
 * entry belonging to another workspace is dropped when a new one is read, so a directory can never
 * outlive the workspace whose permissions produced it.
 */
const directoryReads = new Map<string, Promise<readonly AiAssistantSkill[]>>();

function directoryKey(workspaceId: number, context: AiChatPageContextKind | undefined): string {
    return `${workspaceId}:${context ?? 'all'}`;
}

function readDirectory(
    workspaceId: number,
    context: AiChatPageContextKind | undefined,
): Promise<readonly AiAssistantSkill[]> {
    const key = directoryKey(workspaceId, context);
    const existing = directoryReads.get(key);
    if (existing !== undefined) return existing;
    const read = getAiAssistantSkills(context).catch((error: unknown) => {
        directoryReads.delete(key);
        throw error;
    });
    for (const cached of [...directoryReads.keys()]) {
        if (!cached.startsWith(`${workspaceId}:`)) directoryReads.delete(cached);
    }
    directoryReads.set(key, read);
    return read;
}

/**
 * The declared capabilities this member can run here, read from the server's own directory.
 *
 * Every contextual entry point and every suggestion is built from this, so a surface offers exactly
 * the work the server would accept from it: a capability this build cannot execute, or one whose
 * permissions the member does not hold, is simply absent and the affordance that would have offered
 * it is absent with it.
 *
 * Fail-closed in both directions. Without the permission to use Ask Connex nothing is requested at
 * all, a directory that cannot be read stays empty rather than falling back to a list this client
 * made up, and a directory read for one workspace is never returned for another. Listing needs no
 * configured provider, so a workspace whose provider is not ready can still describe what Ask Connex
 * does — starting a request remains gated on its own.
 *
 * @param context the record kind to filter to, or undefined for the whole directory
 * @returns the runnable capabilities, in the catalog's own order
 */
export function useAskConnexSkills(context?: AiChatPageContextKind): readonly AiAssistantSkill[] {
    const permission = usePermissionCheck('AI_USE');
    const { activeWorkspaceId, switching } = useWorkspace();
    const [directory, setDirectory] = useState<{
        key: string;
        skills: readonly AiAssistantSkill[];
    } | null>(null);
    const enabled = permission === 'granted' && activeWorkspaceId !== null && !switching;
    const key = activeWorkspaceId === null ? null : directoryKey(activeWorkspaceId, context);

    useEffect(() => {
        if (!enabled || activeWorkspaceId === null || key === null) return;
        let active = true;
        readDirectory(activeWorkspaceId, context)
            .then((skills) => {
                if (active) setDirectory({ key, skills });
            })
            .catch(() => {
                if (active) setDirectory({ key, skills: NO_SKILLS });
            });
        return () => {
            active = false;
        };
    }, [activeWorkspaceId, context, enabled, key]);

    return enabled && directory?.key === key ? directory.skills : NO_SKILLS;
}
