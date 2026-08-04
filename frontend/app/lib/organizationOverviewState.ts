import type {
    OrganizationIdentity,
    OrganizationLayout,
    OrganizationLayoutAuthorityMember,
    OrganizationLayoutWorkspace,
} from "@/app/lib/types";

export type OrganizationOverviewState = {
    organization: OrganizationIdentity | null;
    authorityMemberships: OrganizationLayoutAuthorityMember[];
    workspaces: OrganizationLayoutWorkspace[];
    hasMoreAuthority: boolean;
    hasMoreWorkspaces: boolean;
    loadedOrgId: number | null;
    loadingMore: boolean;
    accessDenied: boolean;
    loadFailed: boolean;
    reloadKey: number;
};

export type OrganizationOverviewAction =
    | { type: "loadSucceeded"; orgId: number; generation: number; page: OrganizationLayout }
    | { type: "loadDenied"; orgId: number; generation: number }
    | { type: "loadFailed"; orgId: number; generation: number }
    | { type: "retry" }
    | { type: "loadMoreStarted"; orgId: number; generation: number }
    | { type: "loadMoreSucceeded"; orgId: number; generation: number; page: OrganizationLayout }
    | { type: "loadMoreDenied"; orgId: number; generation: number }
    | { type: "loadMoreFailed"; orgId: number; generation: number }
    | { type: "organizationUpdated"; orgId: number; organization: OrganizationIdentity };

export const INITIAL_ORGANIZATION_OVERVIEW_STATE: OrganizationOverviewState = {
    organization: null,
    authorityMemberships: [],
    workspaces: [],
    hasMoreAuthority: false,
    hasMoreWorkspaces: false,
    loadedOrgId: null,
    loadingMore: false,
    accessDenied: false,
    loadFailed: false,
    reloadKey: 0,
};

function appendUnique<T>(current: T[], arriving: T[], key: (entry: T) => number): T[] {
    const merged = new Map(current.map((entry) => [key(entry), entry]));
    for (const entry of arriving) merged.set(key(entry), entry);
    return Array.from(merged.values());
}

export function organizationOverviewReducer(
    state: OrganizationOverviewState,
    action: OrganizationOverviewAction,
): OrganizationOverviewState {
    switch (action.type) {
        case "loadSucceeded":
            if (state.reloadKey !== action.generation) return state;
            return {
                ...state,
                organization: action.page.organization,
                authorityMemberships: action.page.authorityMemberships,
                workspaces: action.page.workspaces,
                hasMoreAuthority: action.page.nextAuthorityMemberId !== null,
                hasMoreWorkspaces: action.page.nextWorkspaceId !== null,
                loadedOrgId: action.orgId,
                loadingMore: false,
                accessDenied: false,
                loadFailed: false,
            };
        case "loadDenied":
            if (state.reloadKey !== action.generation) return state;
            return {
                ...state,
                organization: null,
                authorityMemberships: [],
                workspaces: [],
                loadedOrgId: action.orgId,
                loadingMore: false,
                accessDenied: true,
                loadFailed: false,
            };
        case "loadFailed":
            if (state.reloadKey !== action.generation) return state;
            return {
                ...state,
                organization: null,
                authorityMemberships: [],
                workspaces: [],
                loadedOrgId: action.orgId,
                loadingMore: false,
                accessDenied: false,
                loadFailed: true,
            };
        case "retry":
            return {
                ...state,
                loadedOrgId: null,
                loadingMore: false,
                accessDenied: false,
                loadFailed: false,
                reloadKey: state.reloadKey + 1,
            };
        case "loadMoreStarted":
            return state.loadedOrgId === action.orgId && state.reloadKey === action.generation
                ? { ...state, loadingMore: true }
                : state;
        case "loadMoreSucceeded":
            if (state.loadedOrgId !== action.orgId || state.reloadKey !== action.generation) return state;
            return {
                ...state,
                authorityMemberships: appendUnique(
                    state.authorityMemberships,
                    action.page.authorityMemberships,
                    (member) => member.userId,
                ),
                workspaces: appendUnique(state.workspaces, action.page.workspaces, (workspace) => workspace.id),
                hasMoreAuthority: action.page.nextAuthorityMemberId !== null,
                hasMoreWorkspaces: action.page.nextWorkspaceId !== null,
                loadingMore: false,
            };
        case "loadMoreDenied":
            return state.loadedOrgId === action.orgId && state.reloadKey === action.generation
                ? { ...state, loadingMore: false, accessDenied: true }
                : state;
        case "loadMoreFailed":
            return state.loadedOrgId === action.orgId && state.reloadKey === action.generation
                ? { ...state, loadingMore: false }
                : state;
        case "organizationUpdated":
            return state.loadedOrgId === action.orgId
                ? { ...state, organization: action.organization }
                : state;
    }
}
