"use client";

import { useEffect, useReducer, useRef } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import OrganizationIdentityForm from "@/app/components/organization/OrganizationIdentityForm";
import OrganizationLifecyclePanel from "@/app/components/organization/OrganizationLifecyclePanel";
import OrganizationLayoutPanel from "@/app/components/organization/OrganizationLayoutPanel";
import OrganizationOverviewSkeleton from "@/app/components/organization/OrganizationOverviewSkeleton";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import Rise from "@/app/components/motion/Rise";
import SectionBoundary from "@/app/components/SectionBoundary";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { ApiError, getOrganizationLayout } from "@/app/lib/api";
import {
    INITIAL_ORGANIZATION_OVERVIEW_STATE,
    organizationOverviewReducer,
} from "@/app/lib/organizationOverviewState";
import { toastError } from "@/app/lib/toast";

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
    const orgRole = activeWorkspace?.orgRole ?? null;
    const hasOrgAccess = orgRole !== null;
    const loadMoreRequest = useRef<AbortController | null>(null);
    const [state, dispatch] = useReducer(
        organizationOverviewReducer,
        INITIAL_ORGANIZATION_OVERVIEW_STATE,
    );
    const loading = orgId !== null && state.loadedOrgId !== orgId;

    useEffect(() => {
        if (!orgId || !hasOrgAccess) return;
        let cancelled = false;
        const generation = state.reloadKey;
        (async () => {
            try {
                const page = await getOrganizationLayout(orgId, { limit: 24 });
                if (cancelled) return;
                dispatch({ type: "loadSucceeded", orgId, generation, page });
            } catch (error) {
                if (cancelled) return;
                if (error instanceof ApiError && error.status === 403) {
                    dispatch({ type: "loadDenied", orgId, generation });
                } else {
                    dispatch({ type: "loadFailed", orgId, generation });
                    toastError(t("loadFailed"));
                }
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [hasOrgAccess, orgId, state.reloadKey, t]);

    useEffect(() => () => {
        loadMoreRequest.current?.abort();
        loadMoreRequest.current = null;
    }, [orgId, state.reloadKey]);

    async function loadMore() {
        if (!orgId
            || state.loadingMore
            || loadMoreRequest.current !== null
            || (!state.hasMoreAuthority && !state.hasMoreWorkspaces)) return;
        const requestedOrgId = orgId;
        const generation = state.reloadKey;
        const controller = new AbortController();
        loadMoreRequest.current = controller;
        dispatch({ type: "loadMoreStarted", orgId: requestedOrgId, generation });
        try {
            const page = await getOrganizationLayout(requestedOrgId, {
                afterWorkspaceId: state.workspaces.at(-1)?.id,
                afterAuthorityMemberId: state.authorityMemberships.at(-1)?.userId,
                limit: 24,
            }, { signal: controller.signal });
            dispatch({ type: "loadMoreSucceeded", orgId: requestedOrgId, generation, page });
        } catch (error) {
            if (error instanceof Error && error.name === "AbortError") {
                return;
            }
            if (error instanceof ApiError && error.status === 403) {
                dispatch({ type: "loadMoreDenied", orgId: requestedOrgId, generation });
            } else {
                dispatch({ type: "loadMoreFailed", orgId: requestedOrgId, generation });
                toastError(t("loadMoreFailed"));
            }
        } finally {
            if (loadMoreRequest.current === controller) loadMoreRequest.current = null;
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

    if (orgRole === null || state.accessDenied) return <NoAccessCard />;
    if (loading) return <OrganizationOverviewSkeleton />;
    if (state.loadFailed) {
        return (
            <SectionBoundary
                resetKey={`${orgId}:${state.reloadKey}`}
                title={t("loadFailedTitle")}
                body={t("loadFailedBody")}
            >
                <SectionUnavailable
                    title={t("loadFailedTitle")}
                    body={t("loadFailedBody")}
                    onReset={() => dispatch({ type: "retry" })}
                />
            </SectionBoundary>
        );
    }
    if (!state.organization) return null;

    return (
        <SectionBoundary
            resetKey={orgId}
            title={t("loadFailedTitle")}
            body={t("loadFailedBody")}
        >
            <div className="space-y-10">
                <Rise>
                    <SettingsSection title={t("identityTitle")} description={t("identityDescription")}>
                        <OrganizationIdentityForm
                            key={state.organization.id}
                            organization={state.organization}
                            onUpdated={(organization) => dispatch({
                                type: "organizationUpdated",
                                orgId: organization.id,
                                organization,
                            })}
                            onReconcile={() => dispatch({ type: "retry" })}
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
                <Rise>
                    <SettingsSection
                        title={t("lifecycleTitle")}
                        description={t("lifecycleDescription")}
                    >
                        <OrganizationLifecyclePanel
                            organization={state.organization}
                            workspaces={state.workspaces}
                            orgRole={orgRole}
                            hasMore={state.hasMoreAuthority || state.hasMoreWorkspaces}
                            loadingMore={state.loadingMore}
                            onLoadMore={() => void loadMore()}
                        />
                    </SettingsSection>
                </Rise>
            </div>
        </SectionBoundary>
    );
}
