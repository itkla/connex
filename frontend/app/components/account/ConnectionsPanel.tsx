"use client";

import { useEffect, useRef, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import {
    ArrowPathIcon,
    EllipsisHorizontalIcon,
    LinkIcon,
    PauseIcon,
    PlayIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type { ConnectedAccountProvider, InstanceCapabilities, ProviderConnection, ProviderConnectionStatus } from "@/app/lib/types";
import {
    beginProviderConnection,
    disconnectProviderConnection,
    getProviderConnections,
    pauseProviderConnection,
    resumeProviderConnection,
} from "@/app/lib/api";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatRelativeTime } from "@/app/lib/utils";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const PROVIDERS: ConnectedAccountProvider[] = ["google", "microsoft"];

const STATUS_CLASS: Record<ProviderConnectionStatus, string> = {
    connected: "bg-brand text-brand-foreground ring-brand",
    paused: "bg-risk-medium/15 text-risk-medium ring-risk-medium/30",
    error: "bg-destructive/15 text-destructive ring-destructive/30",
    revoked: "bg-muted text-muted-foreground/70 ring-border",
};

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

type Props = {
    capabilities: InstanceCapabilities;
};

/**
 * Per-user connected accounts (mail/calendar providers). Connections are self-owned: the panel
 * lists the current user's Google/Microsoft links with status and lifecycle actions. Connecting
 * navigates the browser to the provider consent URL minted by the backend; the OAuth callback
 * redirects back here with `?connected=` or `?error=`, which this panel surfaces as toasts.
 */
export default function ConnectionsPanel({ capabilities }: Props) {
    const t = useTranslations("AccountConnections");
    const locale = useLocale();
    const router = useRouter();
    const searchParams = useSearchParams();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const [connections, setConnections] = useState<ProviderConnection[] | null>(null);
    const [error, setError] = useState(false);
    const [busy, setBusy] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const [disconnectTarget, setDisconnectTarget] = useState<ConnectedAccountProvider | null>(null);
    const [isDisconnecting, setIsDisconnecting] = useState(false);

    useEffect(() => {
        let cancelled = false;
        getProviderConnections()
            .then((all) => { if (!cancelled) { setConnections(all); setError(false); } })
            .catch(() => { if (!cancelled) { setConnections([]); setError(true); } });
        return () => { cancelled = true; };
    }, [reloadKey]);

    const callbackAnnounced = useRef(false);

    useEffect(() => {
        const connected = searchParams.get("connected");
        const callbackError = searchParams.get("error");
        if ((!connected && !callbackError) || callbackAnnounced.current) return;
        callbackAnnounced.current = true;
        const announce = () => {
            if (connected) {
                toastSuccess(t("connectedToast", { provider: t(`provider_${connected === "microsoft" ? "microsoft" : "google"}`) }));
            } else if (callbackError) {
                const known = ["state", "denied", "exchange", "no_offline_access"].includes(callbackError);
                toastError(t(known ? `error_${callbackError}` : "error_exchange"));
            }
        };
        setTimeout(announce, 50);
        router.replace("/account/connections");
    }, [searchParams, router, t]);

    const enabled = (provider: ConnectedAccountProvider) =>
        provider === "google" ? capabilities.connectedAccounts.google : capabilities.connectedAccounts.microsoft;

    const connectionOf = (provider: ConnectedAccountProvider) =>
        connections?.find((c) => c.provider === provider) ?? null;

    const connect = async (provider: ConnectedAccountProvider) => {
        setBusy(true);
        try {
            const { url } = await beginProviderConnection(provider);
            window.location.assign(url);
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : t("actionFailed"));
            }
            setBusy(false);
        }
    };

    const togglePause = async (connection: ProviderConnection) => {
        setBusy(true);
        try {
            const updated = connection.status === "paused"
                ? await resumeProviderConnection(connection.provider)
                : await pauseProviderConnection(connection.provider);
            setConnections((prev) => prev?.map((c) => (c.provider === updated.provider ? updated : c)) ?? [updated]);
            toastSuccess(updated.status === "paused" ? t("pausedToast") : t("resumedToast"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const confirmDisconnect = async () => {
        if (!disconnectTarget) return;
        setIsDisconnecting(true);
        try {
            await disconnectProviderConnection(disconnectTarget);
            setConnections((prev) => prev?.filter((c) => c.provider !== disconnectTarget) ?? []);
            toastSuccess(t("disconnectedToast"));
            setDisconnectTarget(null);
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : t("actionFailed"));
            }
        } finally {
            setIsDisconnecting(false);
        }
    };

    const anyProviderAvailable = PROVIDERS.some((provider) => enabled(provider) || connectionOf(provider) !== null);

    return (
        <Rise className="space-y-3">
            <div className="space-y-1">
                <SectionHeader title={t("title")} />
                <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
            </div>

            {connections === null ? (
                <div className="space-y-2 rounded-2xl border border-border bg-card p-4">
                    <Skeleton className="h-14 w-full" />
                    <Skeleton className="h-14 w-full" />
                </div>
            ) : error ? (
                <div className="rounded-2xl border border-border bg-card px-4 py-8 text-center">
                    <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                    <Button
                        variant="outline"
                        size="sm"
                        className="mt-3"
                        onClick={() => {
                            setConnections(null);
                            setReloadKey((k) => k + 1);
                        }}
                    >
                        {t("retry")}
                    </Button>
                </div>
            ) : !anyProviderAvailable ? (
                <div className="rounded-2xl border border-dashed border-border bg-card px-6 py-12 text-center">
                    <p className="text-sm font-medium text-foreground">{t("unavailableTitle")}</p>
                    <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t("unavailableBody")}</p>
                </div>
            ) : (
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {PROVIDERS.filter((provider) => enabled(provider) || connectionOf(provider) !== null).map((provider) => {
                        const connection = connectionOf(provider);
                        return (
                            <li key={provider} className="group flex items-center gap-3 px-4 py-3.5">
                                <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted ring-1 ring-border">
                                    {provider === "google" ? <GoogleMark /> : <MicrosoftMark />}
                                </div>
                                <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                        <p className="text-sm font-medium text-foreground">{t(`provider_${provider}`)}</p>
                                        {connection && (
                                            <span className={cn(
                                                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
                                                STATUS_CLASS[connection.status],
                                            )}>
                                                {t(`status_${connection.status}`)}
                                            </span>
                                        )}
                                    </div>
                                    <p className="truncate text-xs text-muted-foreground">
                                        {connection
                                            ? connection.providerAccountEmail
                                                ? t("connectedAs", { email: connection.providerAccountEmail })
                                                : t("connectedNoEmail")
                                            : t("notConnected")}
                                        {connection && (
                                            <span>
                                                {" · "}
                                                {connection.lastSyncAt
                                                    ? t("lastSync", { time: formatRelativeTime(connection.lastSyncAt, locale) })
                                                    : t("neverSynced")}
                                            </span>
                                        )}
                                    </p>
                                </div>
                                {connection ? (
                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <Button variant="ghost" size="icon-xs" aria-label={t("actions")} disabled={busy}>
                                                <EllipsisHorizontalIcon className="size-4" />
                                            </Button>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent align="end">
                                            {connection.status !== "revoked" && (
                                                <DropdownMenuItem onSelect={() => togglePause(connection)}>
                                                    {connection.status === "paused"
                                                        ? <><PlayIcon className="size-4" />{t("resume")}</>
                                                        : <><PauseIcon className="size-4" />{t("pause")}</>}
                                                </DropdownMenuItem>
                                            )}
                                            {enabled(provider) && (
                                                <DropdownMenuItem onSelect={() => connect(provider)}>
                                                    <ArrowPathIcon className="size-4" />{t("reconnect")}
                                                </DropdownMenuItem>
                                            )}
                                            <DropdownMenuSeparator />
                                            <DropdownMenuItem variant="destructive" onSelect={() => setDisconnectTarget(provider)}>
                                                <TrashIcon className="size-4" />{t("disconnect")}
                                            </DropdownMenuItem>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                ) : (
                                    <Button variant="outline" size="sm" onClick={() => connect(provider)} disabled={busy}>
                                        <LinkIcon className="size-4" />
                                        {t("connect")}
                                    </Button>
                                )}
                            </li>
                        );
                    })}
                </ul>
            )}

            <p className="max-w-prose px-6 text-xs text-muted-foreground">{t("privacyNote")}</p>

            <DeleteRecordDialog
                open={disconnectTarget !== null}
                onOpenChange={(next) => { if (!next) setDisconnectTarget(null); }}
                selectedIds={disconnectTarget ? new Set([disconnectTarget]) : new Set()}
                selectedItems={disconnectTarget ? [disconnectTarget] : []}
                entityLabel={t("entityLabel")}
                getDisplayName={(provider) => t(`provider_${provider}`)}
                isDeleting={isDisconnecting}
                confirmDelete={confirmDisconnect}
            />
        </Rise>
    );
}
