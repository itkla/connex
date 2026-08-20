"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { ArrowPathIcon } from "@heroicons/react/24/outline";

import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import ManagedConnectDialog from "@/app/components/account/connected-accounts/ManagedConnectDialog";
import CapturePolicyDialog from "@/app/components/account/connected-capture/CapturePolicyDialog";
import CaptureProviderCard from "@/app/components/account/connected-capture/CaptureProviderCard";
import CapturePurgeDialog, {
    type CaptureLifecycleMode,
} from "@/app/components/account/connected-capture/CapturePurgeDialog";
import CaptureReviewQueue from "@/app/components/account/connected-capture/CaptureReviewQueue";
import WorkspaceCapturePolicyDialog from "@/app/components/account/connected-capture/WorkspaceCapturePolicyDialog";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import {
    approveCapturedItem,
    beginProviderConnection,
    decideCaptureReview,
    deleteCapturedProviderData,
    disconnectProviderConnection,
    getCaptureOverview,
    getCaptureReviews,
    getProviderConnections,
    pauseProviderConnection,
    preflightPersonDuplicates,
    resumeProviderConnection,
    syncProviderCapture,
    updateProviderCapturePolicy,
    updateWorkspaceCapturePolicy,
} from "@/app/lib/api";
import {
    captureConnectionsHref,
    connectionsHrefWithoutOAuthCallback,
    isCaptureOperationActive,
    parseCaptureRouteState,
    providerCaptureEnabled,
} from "@/app/lib/connectedCapture";
import {
    connectedAccountMode,
    managedIdentityUnavailable,
} from "@/app/lib/managedConnect";
import { checkPermission, type PermissionsStatus } from "@/app/lib/permissionState";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type {
    CaptureOverview,
    CaptureReviewDecision,
    CaptureReviewItem,
    CaptureReviewPage,
    ConnectedAccountProvider,
    InstanceCapabilities,
    ProviderCaptureOverview,
    ProviderCapturePolicy,
    ProviderConnection,
    WorkspaceCapturePolicy,
} from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

const PROVIDERS: ConnectedAccountProvider[] = ["google", "microsoft"];

function GoogleMark() {
    return (
        <svg className="size-5" width={20} height={20} viewBox="0 0 48 48" aria-hidden="true">
            <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
            <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
            <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
            <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
        </svg>
    );
}

function MicrosoftMark() {
    return (
        <svg className="size-5" width={20} height={20} viewBox="0 0 23 23" aria-hidden="true">
            <path fill="#F25022" d="M1 1h10v10H1z" />
            <path fill="#7FBA00" d="M12 1h10v10H12z" />
            <path fill="#00A4EF" d="M1 12h10v10H1z" />
            <path fill="#FFB900" d="M12 12h10v10H12z" />
        </svg>
    );
}

function providerOverview(
    overview: CaptureOverview | null,
    provider: ConnectedAccountProvider,
): ProviderCaptureOverview | null {
    return overview?.providers.find((entry) => entry.provider === provider) ?? null;
}

function replaceProviderOverview(
    current: CaptureOverview | null,
    updated: ProviderCaptureOverview,
): CaptureOverview {
    const providers = current?.providers.filter((entry) => entry.provider !== updated.provider) ?? [];
    return { providers: [...providers, updated] };
}

type LifecycleTarget = {
    provider: ConnectedAccountProvider;
    mode: CaptureLifecycleMode;
};

function WorkspacePolicyUnavailable() {
    const t = useTranslations("PermissionsUnavailable");
    const router = useRouter();
    const [isRetrying, startTransition] = useTransition();

    return (
        <PermissionsUnavailable
            variant="inline"
            title={t("title")}
            body={t("sectionBody")}
            action={(
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => startTransition(() => router.refresh())}
                    disabled={isRetrying}
                >
                    <ArrowPathIcon
                        data-icon="inline-start"
                        className={isRetrying ? "animate-spin motion-reduce:animate-none" : undefined}
                    />
                    {isRetrying ? t("retrying") : t("retry")}
                </Button>
            )}
        />
    );
}

/**
 * Manages self-owned provider connections and the active workspace's explicit capture policy.
 *
 * @param capabilities the instance's connected-capture switches
 * @param capabilitiesAvailability whether any provider capability is enabled, disabled, or unresolved
 * @param effectivePermissions the viewer's effective permission keys, empty when the lookup failed
 * @param permissionsStatus whether that lookup succeeded, so a refusal can say which one it is
 */
export default function ConnectionsPanel({
    capabilities,
    capabilitiesAvailability,
    effectivePermissions,
    permissionsStatus,
}: {
    capabilities: InstanceCapabilities;
    capabilitiesAvailability: CapabilityAvailability;
    effectivePermissions: string[];
    permissionsStatus: PermissionsStatus;
}) {
    const t = useTranslations("AccountConnections");
    const tPolicy = useTranslations("AccountCapturePolicy");
    const tWorkspacePolicy = useTranslations("AccountWorkspaceCapturePolicy");
    const tCapture = useTranslations("AccountCaptureProvider");
    const tReviews = useTranslations("AccountCaptureReviews");
    const tLifecycle = useTranslations("AccountCaptureLifecycle");
    const router = useRouter();
    const searchParams = useSearchParams();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const currentSearchParams = useMemo(
        () => new URLSearchParams(searchParams.toString()),
        [searchParams],
    );
    const routeState = useMemo(
        () => parseCaptureRouteState(currentSearchParams),
        [currentSearchParams],
    );
    const anyCaptureEnabled = PROVIDERS.some((provider) =>
        providerCaptureEnabled(capabilities, provider));
    const workspacePolicyCheck = useMemo(
        () => checkPermission(permissionsStatus, new Set(effectivePermissions), "WORKSPACE_SETTINGS"),
        [effectivePermissions, permissionsStatus],
    );
    const canManageWorkspacePolicy = workspacePolicyCheck === "granted";
    const canCreatePeople = effectivePermissions.includes("PERSON_CREATE");

    const [connections, setConnections] = useState<ProviderConnection[] | null>(null);
    const [connectionsError, setConnectionsError] = useState(false);
    const [connectionsReloadKey, setConnectionsReloadKey] = useState(0);
    const [captureOverview, setCaptureOverview] = useState<CaptureOverview | null>(
        anyCaptureEnabled ? null : { providers: [] },
    );
    const [captureLoading, setCaptureLoading] = useState(anyCaptureEnabled);
    const [captureError, setCaptureError] = useState(false);
    const [captureReloadKey, setCaptureReloadKey] = useState(0);
    const [reviewPage, setReviewPage] = useState<CaptureReviewPage | null>(null);
    const [reviewsError, setReviewsError] = useState(false);
    const [reviewsReloadKey, setReviewsReloadKey] = useState(0);
    const [busyProvider, setBusyProvider] = useState<ConnectedAccountProvider | null>(null);
    const [lifecycleTarget, setLifecycleTarget] = useState<LifecycleTarget | null>(null);
    const [managedTarget, setManagedTarget] = useState<ConnectedAccountProvider | null>(null);
    const callbackAnnounced = useRef(false);

    const replaceRouteState = useCallback((next: Partial<typeof routeState>) => {
        router.replace(captureConnectionsHref(currentSearchParams, next), { scroll: false });
    }, [currentSearchParams, router]);

    useEffect(() => {
        let cancelled = false;
        const controller = new AbortController();
        getProviderConnections({ signal: controller.signal })
            .then((all) => {
                if (!cancelled) {
                    setConnections(all);
                    setConnectionsError(false);
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setConnections([]);
                    setConnectionsError(true);
                }
            });
        return () => {
            cancelled = true;
            controller.abort();
        };
    }, [connectionsReloadKey]);

    useEffect(() => {
        if (!anyCaptureEnabled) return;
        let cancelled = false;
        const controller = new AbortController();
        getCaptureOverview({ signal: controller.signal })
            .then((overview) => {
                if (!cancelled) {
                    setCaptureOverview(overview);
                    setCaptureError(false);
                }
            })
            .catch(() => {
                if (!cancelled) setCaptureError(true);
            })
            .finally(() => {
                if (!cancelled) setCaptureLoading(false);
            });
        return () => {
            cancelled = true;
            controller.abort();
        };
    }, [anyCaptureEnabled, captureReloadKey]);

    const activeCaptureOperation = captureOverview?.providers.some(
        (provider) =>
            provider.streams.some((stream) => isCaptureOperationActive(stream.status))
            || provider.purge.active,
    ) ?? false;

    useEffect(() => {
        if (!anyCaptureEnabled || !activeCaptureOperation) return;
        const timeout = window.setTimeout(
            () => setCaptureReloadKey((current) => current + 1),
            4000,
        );
        return () => window.clearTimeout(timeout);
    }, [activeCaptureOperation, anyCaptureEnabled, captureOverview]);

    useEffect(() => {
        const connected = searchParams.get("connected");
        const callbackError = searchParams.get("error");
        if ((!connected && !callbackError) || callbackAnnounced.current) return;
        callbackAnnounced.current = true;
        const timeout = window.setTimeout(() => {
            if (connected === "google" || connected === "microsoft") {
                toastSuccess(t("connectedToast", { provider: t(`provider_${connected}`) }));
                setConnectionsReloadKey((current) => current + 1);
                if (providerCaptureEnabled(capabilities, connected)) {
                    setCaptureReloadKey((current) => current + 1);
                }
            } else if (callbackError) {
                const known = ["state", "denied", "exchange", "no_offline_access"].includes(callbackError);
                toastError(t(known ? `error_${callbackError}` : "error_exchange"));
            }
            router.replace(connectionsHrefWithoutOAuthCallback(currentSearchParams), {
                scroll: false,
            });
        }, 50);
        return () => window.clearTimeout(timeout);
    }, [capabilities, currentSearchParams, router, searchParams, t]);

    useEffect(() => {
        if (
            routeState.panel !== "reviews"
            || !routeState.provider
            || !providerCaptureEnabled(capabilities, routeState.provider)
        ) {
            return;
        }
        let cancelled = false;
        const controller = new AbortController();
        getCaptureReviews(routeState.provider, routeState.page, 20, {
            signal: controller.signal,
        })
            .then((page) => {
                if (!cancelled) {
                    setReviewPage(page);
                    setReviewsError(false);
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setReviewPage(null);
                    setReviewsError(true);
                }
            })
        return () => {
            cancelled = true;
            controller.abort();
        };
    }, [
        capabilities,
        reviewsReloadKey,
        routeState.page,
        routeState.panel,
        routeState.provider,
    ]);

    const connectionEnabled = (provider: ConnectedAccountProvider) =>
        capabilities.connectedAccounts[provider];
    const connectionOf = (provider: ConnectedAccountProvider) =>
        connections?.find((connection) => connection.provider === provider) ?? null;
    const overviewOf = (provider: ConnectedAccountProvider) =>
        providerOverview(captureOverview, provider);

    const runProviderMutation = async <T,>(
        provider: ConnectedAccountProvider,
        request: () => Promise<T>,
        success: (result: T) => void,
        successMessage?: string,
    ): Promise<boolean> => {
        setBusyProvider(provider);
        try {
            const result = await request();
            success(result);
            if (successMessage) toastSuccess(successMessage);
            return true;
        } catch (error) {
            if (!handlePasskeyStepUpError(error)) {
                toastError(error instanceof Error ? error.message : t("actionFailed"));
            }
            return false;
        } finally {
            setBusyProvider(null);
        }
    };

    const handleManagedConnected = useCallback(() => {
        setConnectionsReloadKey((current) => current + 1);
        setCaptureReloadKey((current) => current + 1);
    }, []);

    const connect = async (provider: ConnectedAccountProvider) => {
        if (connectedAccountMode(capabilities, provider) === "managed") {
            setManagedTarget(provider);
            return;
        }
        setBusyProvider(provider);
        try {
            const { url } = await beginProviderConnection(provider);
            window.location.assign(url);
        } catch (error) {
            if (!handlePasskeyStepUpError(error)) {
                toastError(error instanceof Error ? error.message : t("actionFailed"));
            }
            setBusyProvider(null);
        }
    };

    const togglePause = async (connection: ProviderConnection) => {
        await runProviderMutation(
            connection.provider,
            () => connection.status === "paused"
                ? resumeProviderConnection(connection.provider)
                : pauseProviderConnection(connection.provider),
            (updated) => setConnections((current) =>
                current?.map((entry) =>
                    entry.provider === updated.provider ? updated : entry) ?? [updated]),
            connection.status === "paused" ? t("resumedToast") : t("pausedToast"),
        );
    };

    const savePolicy = async (
        provider: ConnectedAccountProvider,
        policy: ProviderCapturePolicy,
    ) => runProviderMutation(
        provider,
        () => updateProviderCapturePolicy(provider, policy),
        (updated) => setCaptureOverview((current) => replaceProviderOverview(current, updated)),
        tPolicy("saved"),
    );

    const saveWorkspacePolicy = async (
        provider: ConnectedAccountProvider,
        policy: WorkspaceCapturePolicy,
    ) => runProviderMutation(
        provider,
        () => updateWorkspaceCapturePolicy(provider, policy),
        (updated) => setCaptureOverview((current) => replaceProviderOverview(current, updated)),
        tWorkspacePolicy("saved"),
    );

    const sync = async (provider: ConnectedAccountProvider) => {
        await runProviderMutation(
            provider,
            () => syncProviderCapture(provider),
            (updated) => setCaptureOverview((current) => replaceProviderOverview(current, updated)),
            tCapture("syncStarted"),
        );
    };

    const decideReview = async (
        review: CaptureReviewItem,
        decision: CaptureReviewDecision,
    ) => runProviderMutation(
        review.provider,
        () => decideCaptureReview(review.provider, review.id, decision),
        (updated) => {
            setCaptureOverview((current) => replaceProviderOverview(current, updated));
            setReviewPage(null);
            setReviewsReloadKey((current) => current + 1);
        },
        tReviews("resolved"),
    );

    const approveReview = async (review: CaptureReviewItem) => runProviderMutation(
        review.provider,
        () => approveCapturedItem(
            review.provider,
            review.interactionId,
            review.interactionVersion,
        ),
        (updated) => {
            setCaptureOverview((current) => replaceProviderOverview(current, updated));
            setReviewPage(null);
            setReviewsReloadKey((current) => current + 1);
        },
        tReviews("approved"),
    );

    const confirmLifecycle = async (target: LifecycleTarget): Promise<boolean> => {
        if (target.mode === "purge") {
            return runProviderMutation(
                target.provider,
                () => deleteCapturedProviderData(target.provider),
                () => setCaptureReloadKey((current) => current + 1),
                tLifecycle("purgeStarted"),
            );
        }
        return runProviderMutation(
            target.provider,
            () => disconnectProviderConnection(target.provider),
            () => {
                setConnections((current) =>
                    current?.filter((entry) => entry.provider !== target.provider) ?? []);
                setCaptureOverview((current) => ({
                    providers: current?.providers.filter(
                        (entry) => entry.provider !== target.provider,
                    ) ?? [],
                }));
            },
            t("disconnectedToast"),
        );
    };

    const routeOverview = routeState.provider ? overviewOf(routeState.provider) : null;
    const selectedReview = routeState.reviewId
        ? reviewPage?.items.find((review) => review.id === routeState.reviewId) ?? null
        : null;
    const purgeTarget = routeState.panel === "purge" && routeState.provider
        ? { provider: routeState.provider, mode: "purge" as const }
        : null;
    const activeLifecycleTarget = lifecycleTarget ?? purgeTarget;
    const providersToShow = PROVIDERS.filter(
        (provider) =>
            connectionEnabled(provider)
            || providerCaptureEnabled(capabilities, provider)
            || connectedAccountMode(capabilities, provider) === "managed"
            || connectionOf(provider) != null,
    );

    return (
        <div className="space-y-4">
            <div className="space-y-1">
                <SectionHeader title={t("title")} />
                <p className="max-w-2xl px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {capabilitiesAvailability === "unavailable" ? (
                <SettingsAvailabilityNotice variant="inline" state="retry" />
            ) : null}

            {connections === null ? (
                <div className="grid gap-3">
                    <Skeleton className="h-28 w-full rounded-2xl" />
                    <Skeleton className="h-28 w-full rounded-2xl" />
                </div>
            ) : connectionsError ? (
                <div className="rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground" role="alert">{t("loadFailed")}</p>
                    <Button
                        variant="outline"
                        size="sm"
                        className="mt-3"
                        onClick={() => {
                            setConnections(null);
                            setConnectionsReloadKey((current) => current + 1);
                        }}
                    >
                        {t("retry")}
                    </Button>
                </div>
            ) : providersToShow.length === 0 && capabilitiesAvailability === "disabled" ? (
                <SettingsAvailabilityNotice
                    variant="inline"
                    state="not-enabled"
                    title={t("unavailableTitle")}
                    body={t("unavailableBody")}
                />
            ) : providersToShow.length > 0 ? (
                <div className="grid gap-3">
                    {providersToShow.map((provider) => {
                        const connection = connectionOf(provider);
                        const captureEnabled = providerCaptureEnabled(capabilities, provider);
                        const capture = overviewOf(provider);
                        return (
                            <CaptureProviderCard
                                key={provider}
                                provider={provider}
                                providerIcon={provider === "google" ? <GoogleMark /> : <MicrosoftMark />}
                                mode={connectedAccountMode(capabilities, provider)}
                                managedUnavailable={managedIdentityUnavailable(capabilities, provider)}
                                connection={connection}
                                connectionEnabled={
                                    connectionEnabled(provider) || captureEnabled
                                }
                                captureEnabled={captureEnabled}
                                capture={capture}
                                captureLoading={captureEnabled && captureLoading}
                                captureLoadError={captureEnabled && captureError}
                                canManageWorkspacePolicy={canManageWorkspacePolicy}
                                busy={busyProvider === provider}
                                onConnect={() => connect(provider)}
                                onTogglePause={() => {
                                    if (connection) togglePause(connection);
                                }}
                                onConfigure={() => replaceRouteState({
                                    provider,
                                    panel: "policy",
                                    reviewId: null,
                                    page: 1,
                                })}
                                onWorkspacePolicy={() => replaceRouteState({
                                    provider,
                                    panel: "workspace-policy",
                                    reviewId: null,
                                    page: 1,
                                })}
                                onSync={() => sync(provider)}
                                onReviews={() => {
                                    setReviewPage(null);
                                    setReviewsError(false);
                                    replaceRouteState({
                                        provider,
                                        panel: "reviews",
                                        reviewId: null,
                                        page: 1,
                                    });
                                }}
                                onPurge={() => replaceRouteState({
                                    provider,
                                    panel: "purge",
                                    reviewId: null,
                                    page: 1,
                                })}
                                onDisconnect={() => setLifecycleTarget({
                                    provider,
                                    mode: "disconnect",
                                })}
                                onRetryCapture={() =>
                                    setCaptureReloadKey((current) => current + 1)}
                            />
                        );
                    })}
                </div>
            ) : null}

            {routeState.panel === "workspace-policy" && workspacePolicyCheck === "unavailable" ? (
                <WorkspacePolicyUnavailable />
            ) : null}

            <p className="max-w-2xl px-6 text-xs text-muted-foreground">{t("privacyNote")}</p>

            {routeOverview && routeState.panel === "policy" ? (
                <CapturePolicyDialog
                    key={`${routeOverview.provider}-${routeOverview.userPolicy.version}`}
                    overview={routeOverview}
                    open
                    saving={busyProvider === routeOverview.provider}
                    onOpenChange={(open) => {
                        if (!open) replaceRouteState({ panel: null, reviewId: null, page: 1 });
                    }}
                    onSave={(policy) => savePolicy(routeOverview.provider, policy)}
                />
            ) : null}

            {routeOverview
                && routeState.panel === "workspace-policy"
                && canManageWorkspacePolicy ? (
                    <WorkspaceCapturePolicyDialog
                        key={`${routeOverview.provider}-${routeOverview.workspacePolicy.version}`}
                        overview={routeOverview}
                        open
                        saving={busyProvider === routeOverview.provider}
                        onOpenChange={(open) => {
                            if (!open) replaceRouteState({ panel: null, reviewId: null, page: 1 });
                        }}
                        onSave={(policy) =>
                            saveWorkspacePolicy(routeOverview.provider, policy)}
                    />
                ) : null}

            {routeOverview && routeState.panel === "reviews" ? (
                <CaptureReviewQueue
                    overview={routeOverview}
                    page={reviewPage}
                    selected={selectedReview}
                    open
                    loading={!reviewPage && !reviewsError}
                    error={reviewsError}
                    busy={busyProvider === routeOverview.provider}
                    canCreatePeople={canCreatePeople}
                    onOpenChange={(open) => {
                        if (!open) {
                            setReviewPage(null);
                            setReviewsError(false);
                            replaceRouteState({ panel: null, reviewId: null, page: 1 });
                        }
                    }}
                    onPageChange={(page) => {
                        setReviewPage(null);
                        setReviewsError(false);
                        replaceRouteState({ page, reviewId: null });
                    }}
                    onSelect={(review) => replaceRouteState({
                        reviewId: review?.id ?? null,
                    })}
                    onRetry={() => {
                        setReviewPage(null);
                        setReviewsError(false);
                        setReviewsReloadKey((current) => current + 1);
                    }}
                    onDecide={decideReview}
                    onApprove={approveReview}
                    onPreflight={(request) => preflightPersonDuplicates(request)}
                />
            ) : null}

            {managedTarget ? (
                <ManagedConnectDialog
                    key={managedTarget}
                    provider={managedTarget}
                    providerName={t(`provider_${managedTarget}`)}
                    open
                    onOpenChange={(open) => {
                        if (!open) setManagedTarget(null);
                    }}
                    onConnected={handleManagedConnected}
                />
            ) : null}

            {activeLifecycleTarget ? (
                <CapturePurgeDialog
                    key={`${activeLifecycleTarget.provider}-${activeLifecycleTarget.mode}`}
                    mode={activeLifecycleTarget.mode}
                    providerName={t(`provider_${activeLifecycleTarget.provider}`)}
                    captureEnabled={providerCaptureEnabled(
                        capabilities,
                        activeLifecycleTarget.provider,
                    )}
                    open
                    busy={busyProvider === activeLifecycleTarget.provider}
                    onOpenChange={(open) => {
                        if (open) return;
                        if (activeLifecycleTarget.mode === "purge") {
                            replaceRouteState({ panel: null, reviewId: null, page: 1 });
                        }
                        setLifecycleTarget(null);
                    }}
                    onConfirm={() => confirmLifecycle(activeLifecycleTarget)}
                />
            ) : null}
        </div>
    );
}
