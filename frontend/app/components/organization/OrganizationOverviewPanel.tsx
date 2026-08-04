"use client";

import { useEffect, useReducer } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import OrganizationIdentityForm from "@/app/components/organization/OrganizationIdentityForm";
import OrganizationLayoutPanel from "@/app/components/organization/OrganizationLayoutPanel";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ApiError, getOrganizationLayout } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import type {
    OrganizationIdentity,
    OrganizationLayout,
    OrganizationLayoutAuthorityMember,
    OrganizationLayoutWorkspace,
} from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

function appendUnique<T>(current: T[], arriving: T[], key: (entry: T) => number): T[] {
    const merged = new Map(current.map((entry) => [key(entry), entry]));
    for (const entry of arriving) merged.set(key(entry), entry);
    return Array.from(merged.values());
}

type OverviewState = {
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

type OverviewAction =
    | { type: "loadSucceeded"; orgId: number; page: OrganizationLayout }
    | { type: "loadDenied"; orgId: number }
    | { type: "loadFailed"; orgId: number }
    | { type: "retry" }
    | { type: "loadMoreStarted" }
    | { type: "loadMoreSucceeded"; page: OrganizationLayout }
    | { type: "loadMoreDenied" }
    | { type: "loadMoreFailed" }
    | { type: "organizationUpdated"; organization: OrganizationIdentity };

const INITIAL_STATE: OverviewState = {
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

function overviewReducer(state: OverviewState, action: OverviewAction): OverviewState {
    switch (action.type) {
        case "loadSucceeded":
            return {
                ...state,
                organization: action.page.organization,
                authorityMemberships: action.page.authorityMemberships,
                workspaces: action.page.workspaces,
                hasMoreAuthority: action.page.nextAuthorityMemberId !== null,
                hasMoreWorkspaces: action.page.nextWorkspaceId !== null,
                loadedOrgId: action.orgId,
                accessDenied: false,
                loadFailed: false,
            };
        case "loadDenied":
            return { ...state, loadedOrgId: action.orgId, accessDenied: true, loadFailed: false };
        case "loadFailed":
            return { ...state, loadedOrgId: action.orgId, accessDenied: false, loadFailed: true };
        case "retry":
            return {
                ...state,
                loadedOrgId: null,
                accessDenied: false,
                loadFailed: false,
                reloadKey: state.reloadKey + 1,
            };
        case "loadMoreStarted":
            return { ...state, loadingMore: true };
        case "loadMoreSucceeded":
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
            return { ...state, loadingMore: false, accessDenied: true };
        case "loadMoreFailed":
            return { ...state, loadingMore: false };
        case "organizationUpdated":
            return { ...state, organization: action.organization };
    }
}

function OverviewLoading() {
    return (
        <div className="space-y-10">
            <div className="space-y-4">
                <Skeleton className="h-5 w-44" />
                <Skeleton className="h-56 rounded-2xl" />
            </div>
            <div className="space-y-4">
                <Skeleton className="h-5 w-56" />
                <Skeleton className="h-96 rounded-2xl" />
            </div>
        </div>
    );
}

export default function OrganizationOverviewPanel() {
    const t = useTranslations("OrgOverview");
    const router = useRouter();
    const {
        activeWorkspace,
        activeWorkspaceId,
        runInWorkspace,
        switching,
    } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;
    const hasOrgAccess = activeWorkspace?.orgRole != null;
    const [state, dispatch] = useReducer(overviewReducer, INITIAL_STATE);
    const loading = orgId !== null && state.loadedOrgId !== orgId;

    useEffect(() => {
        if (!orgId || !hasOrgAccess) return;
        let cancelled = false;
        (async () => {
            try {
                const page = await getOrganizationLayout(orgId, { limit: 24 });
                if (cancelled) return;
                dispatch({ type: "loadSucceeded", orgId, page });
            } catch (error) {
                if (cancelled) return;
                if (error instanceof ApiError && error.status === 403) {
                    dispatch({ type: "loadDenied", orgId });
                } else {
                    dispatch({ type: "loadFailed", orgId });
                    toastError(t("loadFailed"));
                }
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [hasOrgAccess, orgId, state.reloadKey, t]);

    async function loadMore() {
        if (!orgId || state.loadingMore || (!state.hasMoreAuthority && !state.hasMoreWorkspaces)) return;
        dispatch({ type: "loadMoreStarted" });
        try {
            const page = await getOrganizationLayout(orgId, {
                afterWorkspaceId: state.workspaces.at(-1)?.id,
                afterAuthorityMemberId: state.authorityMemberships.at(-1)?.userId,
                limit: 24,
            });
            dispatch({ type: "loadMoreSucceeded", page });
        } catch (error) {
            if (error instanceof ApiError && error.status === 403) {
                dispatch({ type: "loadMoreDenied" });
            } else {
                dispatch({ type: "loadMoreFailed" });
                toastError(t("loadMoreFailed"));
            }
        }
    }

    async function navigate(workspaceId: number, href: string) {
        try {
            const completed = await runInWorkspace(workspaceId, async () => {
                router.push(href);
                router.refresh();
            });
            if (!completed) toastError(t("switchInProgress"));
        } catch (error) {
            toastError(error instanceof Error ? error.message : t("navigationFailed"));
        }
    }

    if (!hasOrgAccess || state.accessDenied) return <NoAccessCard />;
    if (loading) return <OverviewLoading />;
    if (state.loadFailed) {
        return (
            <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
                <div className="space-y-1">
                    <p className="text-sm font-semibold text-foreground">{t("loadFailedTitle")}</p>
                    <p className="max-w-sm text-sm text-muted-foreground">{t("loadFailedBody")}</p>
                </div>
                <Button
                    variant="outline"
                    onClick={() => dispatch({ type: "retry" })}
                >
                    {t("retry")}
                </Button>
            </div>
        );
    }
    if (!state.organization) return null;

    return (
        <div className="space-y-10">
            <Rise>
                <SettingsSection title={t("identityTitle")} description={t("identityDescription")}>
                    <OrganizationIdentityForm
                        key={state.organization.id}
                        organization={state.organization}
                        onUpdated={(organization) => dispatch({ type: "organizationUpdated", organization })}
                    />
                </SettingsSection>
            </Rise>
            <Rise>
                <SettingsSection title={t("layoutTitle")} description={t("layoutDescription")}>
                    <OrganizationLayoutPanel
                        organization={state.organization}
                        authorityMemberships={state.authorityMemberships}
                        workspaces={state.workspaces}
                        activeWorkspaceId={activeWorkspaceId}
                        hasMore={state.hasMoreAuthority || state.hasMoreWorkspaces}
                        loadingMore={state.loadingMore}
                        switching={switching}
                        onLoadMore={() => void loadMore()}
                        onNavigate={(workspaceId, href) => void navigate(workspaceId, href)}
                    />
                </SettingsSection>
            </Rise>
        </div>
    );
}
