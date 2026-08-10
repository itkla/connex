"use client";

import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import WorkspaceSelectionUnavailable from "@/app/components/WorkspaceSelectionUnavailable";
import type { MyWorkspaces, Workspace } from "@/app/lib/types";
import {
    createWorkspace,
    readAuthoritativeWorkspaceSelection,
    switchWorkspace,
    WorkspaceSelectionUnavailableError,
} from "@/app/lib/api";
import {
    adoptWorkspaces,
    applyOrganizationIdentity,
    applyWorkspaceIdentity,
    preservePublishedOrganizationIdentities,
    preservePublishedWorkspaceIdentities,
    restoreOrganizationIdentity as restorePublishedOrganizationIdentity,
    restoreWorkspaceIdentity as restorePublishedWorkspaceIdentity,
    type PublishedOrganizationIdentity,
    type PublishedWorkspaceIdentity,
} from "@/app/lib/workspaceSnapshot";

type PublishActiveWorkspace = (id: number | null) => void;
type PublishWorkspace = (workspace: Workspace) => void;
type SelectionChangeRunner = <T>(
    operation: (
        publishActiveWorkspace: PublishActiveWorkspace,
        publishWorkspace: PublishWorkspace,
    ) => Promise<T>,
) => Promise<T>;

type WorkspaceContextValue = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
    activeWorkspace: Workspace | null;
    switching: boolean;
    runInWorkspace: (id: number, operation: (switched: boolean) => Promise<void>) => Promise<boolean>;
    runSelectionChange: SelectionChangeRunner;
    retrySelectionRecovery: () => Promise<void>;
    switchTo: (id: number) => Promise<void>;
    create: (name: string) => Promise<Workspace>;
    publishWorkspaceIdentity: (identity: PublishedWorkspaceIdentity) => void;
    restoreWorkspaceIdentity: (
        expected: PublishedWorkspaceIdentity,
        replacement: PublishedWorkspaceIdentity,
    ) => void;
    publishOrganizationIdentity: (identity: PublishedOrganizationIdentity) => void;
    restoreOrganizationIdentity: (
        expected: PublishedOrganizationIdentity,
        replacement: PublishedOrganizationIdentity,
    ) => void;
};

type WorkspaceSnapshotState = {
    workspaces: Workspace[];
    consumedWorkspaces: Workspace[];
    workspaceIdentities: PublishedWorkspaceIdentity[];
    organizationIdentities: PublishedOrganizationIdentity[];
};

function upsertWorkspaceIdentity(
    identities: PublishedWorkspaceIdentity[],
    identity: PublishedWorkspaceIdentity,
): PublishedWorkspaceIdentity[] {
    const current = identities.find(({ id }) => id === identity.id);
    if (current && current.identityVersion > identity.identityVersion) return identities;
    return [...identities.filter(({ id }) => id !== identity.id), identity];
}

function upsertOrganizationIdentity(
    identities: PublishedOrganizationIdentity[],
    identity: PublishedOrganizationIdentity,
): PublishedOrganizationIdentity[] {
    const current = identities.find(({ id }) => id === identity.id);
    if (current && current.identityVersion > identity.identityVersion) return identities;
    return [...identities.filter(({ id }) => id !== identity.id), identity];
}

function sameWorkspaceIdentity(
    left: PublishedWorkspaceIdentity,
    right: PublishedWorkspaceIdentity,
): boolean {
    return left.id === right.id
        && left.name === right.name
        && left.slug === right.slug
        && left.timezone === right.timezone
        && left.identityVersion === right.identityVersion;
}

function sameOrganizationIdentity(
    left: PublishedOrganizationIdentity,
    right: PublishedOrganizationIdentity,
): boolean {
    return left.id === right.id
        && left.name === right.name
        && left.identityVersion === right.identityVersion;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

/**
 * Publishes the viewer's workspaces and which one is active to client components.
 *
 * The workspace list is kept in step with the app shell's server-side read rather than frozen at
 * mount. Next preserves this provider across client-side navigation and merges a `router.refresh()`
 * payload without discarding client state, so a list seeded once from props never hears about a
 * later server render. That is what strands `activeWorkspace.role` after the viewer changes their
 * own membership: the members list correctly reads "Member" while every gate derived from the
 * workspace snapshot — the role selects, invite and domain administration, the member-removal menu
 * — keeps offering owner chrome whose mutations the backend then refuses.
 *
 * Adopting the payload during render rather than from an effect is deliberate: an effect would
 * commit one frame of exactly that stale chrome before correcting it, which is the defect in
 * miniature. {@link adoptWorkspaces} settles what a payload may overwrite.
 * Identity values published by a mutation remain protected until a server payload carries the
 * matching or a newer identity version. A failed optimistic mutation releases that protection
 * before refreshing, allowing the authoritative server identity to replace the rollback value.
 *
 * Which workspace is active is never adopted from a refreshed prop. A server render that began
 * before {@link WorkspaceContextValue.switchTo} set the cookie can resolve after it, and the payload
 * carries no generation to order two in-flight renders. Only one selection-changing operation at a
 * time is therefore permitted. Within a successful operation, the response cookie is
 * applied before that result is published to provider state, so serialization prevents another
 * selection response from inverting those two decisions.
 *
 * If a successful selection response's body fails, the shared request pipeline re-reads a
 * cookie-matching authoritative snapshot before this operation releases the serialization lock.
 * The endpoint's normal success path then publishes that recovered decision. If the re-read is
 * unavailable or inconsistent, the stale active ID is cleared and workspace-scoped children are
 * withheld until an explicit ordered retry succeeds. Refreshed props remain list-only input; they
 * never repair active selection because their RSC payloads still carry no ordering generation.
 * An explicit recovery snapshot replaces the held membership list without marking itself as the
 * last consumed RSC payload, so the props already mounted cannot immediately overwrite it.
 *
 * @param initialWorkspaces - the viewer's workspaces as of the current server render
 * @param initialActiveId - the workspace the session cookie selects, or null when none is
 * @param children - the tree that reads them
 */
export function WorkspaceProvider({
    initialWorkspaces,
    initialActiveId,
    children,
}: {
    initialWorkspaces: Workspace[];
    initialActiveId: number | null;
    children: React.ReactNode;
}) {
    const router = useRouter();
    const [workspaceSnapshot, setWorkspaceSnapshot] = useState<WorkspaceSnapshotState>({
        workspaces: initialWorkspaces,
        consumedWorkspaces: initialWorkspaces,
        workspaceIdentities: [],
        organizationIdentities: [],
    });
    const [activeWorkspaceId, setActiveWorkspaceId] = useState<number | null>(initialActiveId);
    const [switching, setSwitching] = useState(false);
    const [selectionUnavailable, setSelectionUnavailable] = useState(false);
    const activeWorkspaceIdRef = useRef(initialActiveId);
    const switchingRef = useRef(false);
    const selectionUnavailableRef = useRef(false);

    if (workspaceSnapshot.consumedWorkspaces !== initialWorkspaces) {
        const adopted = adoptWorkspaces(
            workspaceSnapshot.workspaces,
            workspaceSnapshot.consumedWorkspaces,
            initialWorkspaces,
        );
        const workspaceIdentityResult = preservePublishedWorkspaceIdentities(
            adopted,
            workspaceSnapshot.workspaceIdentities,
        );
        const organizationIdentityResult = preservePublishedOrganizationIdentities(
            workspaceIdentityResult.workspaces,
            workspaceSnapshot.organizationIdentities,
        );
        setWorkspaceSnapshot({
            workspaces: organizationIdentityResult.workspaces,
            consumedWorkspaces: initialWorkspaces,
            workspaceIdentities: workspaceIdentityResult.pending,
            organizationIdentities: organizationIdentityResult.pending,
        });
    }
    const workspaces = workspaceSnapshot.workspaces;

    const publishActiveWorkspace = useCallback((id: number | null) => {
        activeWorkspaceIdRef.current = id;
        setActiveWorkspaceId(id);
    }, []);

    const publishWorkspace = useCallback((workspace: Workspace) => {
        setWorkspaceSnapshot((previous) => ({
            ...previous,
            workspaces: previous.workspaces.some(({ id }) => id === workspace.id)
                ? previous.workspaces.map((held) => held.id === workspace.id ? workspace : held)
                : [...previous.workspaces, workspace],
        }));
    }, []);

    const publishAuthoritativeWorkspaceSelection = useCallback((snapshot: MyWorkspaces) => {
        setWorkspaceSnapshot((previous) => {
            const workspaceIdentityResult = preservePublishedWorkspaceIdentities(
                snapshot.workspaces,
                previous.workspaceIdentities,
            );
            const organizationIdentityResult = preservePublishedOrganizationIdentities(
                workspaceIdentityResult.workspaces,
                previous.organizationIdentities,
            );
            return {
                workspaces: organizationIdentityResult.workspaces,
                consumedWorkspaces: previous.consumedWorkspaces,
                workspaceIdentities: workspaceIdentityResult.pending,
                organizationIdentities: organizationIdentityResult.pending,
            };
        });
        publishActiveWorkspace(snapshot.activeWorkspaceId);
        selectionUnavailableRef.current = false;
        setSelectionUnavailable(false);
    }, [publishActiveWorkspace]);

    const publishWorkspaceIdentity = useCallback((identity: PublishedWorkspaceIdentity) => {
        setWorkspaceSnapshot((previous) => ({
            ...previous,
            workspaces: applyWorkspaceIdentity(previous.workspaces, identity),
            workspaceIdentities: upsertWorkspaceIdentity(previous.workspaceIdentities, identity),
        }));
    }, []);

    const restoreWorkspaceIdentity = useCallback((
        expected: PublishedWorkspaceIdentity,
        replacement: PublishedWorkspaceIdentity,
    ) => {
        setWorkspaceSnapshot((previous) => {
            const restored = restorePublishedWorkspaceIdentity(previous.workspaces, expected, replacement);
            const workspaceIdentities = previous.workspaceIdentities.filter(
                (identity) => !sameWorkspaceIdentity(identity, expected),
            );
            if (restored === previous.workspaces
                && workspaceIdentities.length === previous.workspaceIdentities.length) return previous;
            return {
                ...previous,
                workspaces: restored,
                workspaceIdentities,
            };
        });
    }, []);

    const publishOrganizationIdentity = useCallback((identity: PublishedOrganizationIdentity) => {
        setWorkspaceSnapshot((previous) => ({
            ...previous,
            workspaces: applyOrganizationIdentity(previous.workspaces, identity),
            organizationIdentities: upsertOrganizationIdentity(
                previous.organizationIdentities,
                identity,
            ),
        }));
    }, []);

    const restoreOrganizationIdentity = useCallback((
        expected: PublishedOrganizationIdentity,
        replacement: PublishedOrganizationIdentity,
    ) => {
        setWorkspaceSnapshot((previous) => {
            const restored = restorePublishedOrganizationIdentity(
                previous.workspaces,
                expected,
                replacement,
            );
            const organizationIdentities = previous.organizationIdentities.filter(
                (identity) => !sameOrganizationIdentity(identity, expected),
            );
            if (restored === previous.workspaces
                && organizationIdentities.length === previous.organizationIdentities.length) return previous;
            return {
                ...previous,
                workspaces: restored,
                organizationIdentities,
            };
        });
    }, []);

    const executeSelectionChange = useCallback(async <T,>(
        operation: (
            publishActiveWorkspace: PublishActiveWorkspace,
            publishWorkspace: PublishWorkspace,
        ) => Promise<T>,
        allowUnavailable: boolean,
    ) => {
        if (switchingRef.current) throw new Error("A workspace operation is already in progress");
        if (selectionUnavailableRef.current && !allowUnavailable) {
            throw new Error("Workspace selection is unavailable");
        }
        switchingRef.current = true;
        setSwitching(true);
        try {
            return await operation(publishActiveWorkspace, publishWorkspace);
        } catch (error) {
            if (error instanceof WorkspaceSelectionUnavailableError) {
                selectionUnavailableRef.current = true;
                publishActiveWorkspace(null);
                setSelectionUnavailable(true);
            }
            throw error;
        } finally {
            switchingRef.current = false;
            setSwitching(false);
        }
    }, [publishActiveWorkspace, publishWorkspace]);

    const runSelectionChange = useCallback(<T,>(
        operation: (
            publishActiveWorkspace: PublishActiveWorkspace,
            publishWorkspace: PublishWorkspace,
        ) => Promise<T>,
    ) => executeSelectionChange(operation, false), [executeSelectionChange]);

    const retrySelectionRecovery = useCallback(async () => {
        try {
            const snapshot = await executeSelectionChange(
                () => readAuthoritativeWorkspaceSelection(),
                true,
            );
            publishAuthoritativeWorkspaceSelection(snapshot);
            router.refresh();
        } catch {
            selectionUnavailableRef.current = true;
            setSelectionUnavailable(true);
        }
    }, [executeSelectionChange, publishAuthoritativeWorkspaceSelection, router]);

    const runInWorkspace = useCallback(async (
        id: number,
        operation: (switched: boolean) => Promise<void>,
    ) => {
        if (switchingRef.current) return false;
        return runSelectionChange(async (publishActiveWorkspace) => {
            const switched = id !== activeWorkspaceIdRef.current;
            if (switched) {
                await switchWorkspace(id);
                publishActiveWorkspace(id);
            }
            await operation(switched);
            return true;
        });
    }, [runSelectionChange]);

    const switchTo = useCallback(
        async (id: number) => {
            await runInWorkspace(id, async () => {
                router.replace("/dashboard");
                router.refresh();
            });
        },
        [router, runInWorkspace],
    );

    const create = useCallback(
        (name: string) => runSelectionChange(async (publishActiveWorkspace, publishWorkspace) => {
            const workspace = await createWorkspace(name);
            publishWorkspace(workspace);
            publishActiveWorkspace(workspace.id);
            router.replace("/dashboard");
            router.refresh();
            return workspace;
        }),
        [router, runSelectionChange],
    );

    const activeWorkspace = useMemo(
        () => workspaces.find((w) => w.id === activeWorkspaceId) ?? null,
        [workspaces, activeWorkspaceId],
    );

    const value = useMemo(
        () => ({
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            runSelectionChange,
            retrySelectionRecovery,
            switchTo,
            create,
            publishWorkspaceIdentity,
            restoreWorkspaceIdentity,
            publishOrganizationIdentity,
            restoreOrganizationIdentity,
        }),
        [
            workspaces,
            activeWorkspaceId,
            activeWorkspace,
            switching,
            runInWorkspace,
            runSelectionChange,
            retrySelectionRecovery,
            switchTo,
            create,
            publishWorkspaceIdentity,
            restoreWorkspaceIdentity,
            publishOrganizationIdentity,
            restoreOrganizationIdentity,
        ],
    );

    if (selectionUnavailable) {
        return <WorkspaceSelectionUnavailable onRetry={retrySelectionRecovery} />;
    }

    return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace() {
    const value = useContext(WorkspaceContext);
    if (!value) throw new Error("useWorkspace must be used within WorkspaceProvider");
    return value;
}
