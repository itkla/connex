"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import AcceptInviteLink from "@/app/components/invite/AcceptInviteLink";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import { ApiError, exchangeInviteLinkToken, getInviteLinkPreview, me } from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import { useOneTimeLinkEntry } from "@/app/hooks/useOneTimeLinkEntry";
import type { InviteLinkPreview, WorkspaceRole } from "@/app/lib/types";

type InviteLinkPageState =
    | { status: "loading" }
    | { status: "invalid" }
    | { status: "unavailable" }
    | { status: "ready"; preview: InviteLinkPreview };

function roleKey(role: WorkspaceRole): "roleOwner" | "roleAdmin" | "roleMember" {
    return role === "owner" ? "roleOwner" : role === "admin" ? "roleAdmin" : "roleMember";
}

/**
 * Exchanges a shareable invite fragment and renders its token-free acceptance state. A bearer whose
 * exchange failed without a definitive rejection stays in memory only, so retrying re-sends it
 * without restoring the fragment to the address bar or refreshing the router.
 */
export default function InviteLinkPage() {
    const t = useTranslations("InviteLinkAccept");
    const tUnavailable = useTranslations("WorkspaceUnavailable");
    const [state, setState] = useState<InviteLinkPageState>({ status: "loading" });
    const [attempt, setAttempt] = useState(0);
    const retainedBearer = useRef<string | null>(null);

    useOneTimeLinkEntry();

    useEffect(() => {
        let active = true;
        const token = takeOneTimeLinkToken() ?? retainedBearer.current;
        retainedBearer.current = null;

        const establish = async () => {
            if (token) {
                await exchangeInviteLinkToken(token);
                window.location.replace("/invite-link");
                return;
            }

            await me();
            const preview = await getInviteLinkPreview();
            if (active) {
                setState(preview.valid ? { status: "ready", preview } : { status: "invalid" });
            }
        };

        establish().catch((error: unknown) => {
            const rejected = error instanceof ApiError && error.status === 400;
            if (token && !rejected) {
                retainedBearer.current = token;
            }
            if (!active) return;
            if (error instanceof ApiError && error.status === 401) {
                window.location.replace("/auth/login?redirect=%2Finvite-link");
                return;
            }
            setState(rejected ? { status: "invalid" } : { status: "unavailable" });
        });

        return () => {
            active = false;
        };
    }, [attempt]);

    const retry = async () => {
        setState({ status: "loading" });
        setAttempt((current) => current + 1);
    };

    if (state.status === "unavailable") {
        return (
            <PermissionsUnavailable
                title={tUnavailable("title")}
                body={tUnavailable("body")}
                action={(
                    <WorkspaceUnavailableRetry
                        label={tUnavailable("retry")}
                        pendingLabel={tUnavailable("retrying")}
                        onRetry={retry}
                    />
                )}
            />
        );
    }

    return (
        <div className="grid min-h-dvh place-items-center bg-background px-6 py-12">
            <div className="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-sm">
                {state.status === "loading" ? (
                    <div className="flex items-center justify-center gap-3 text-sm text-muted-foreground">
                        <Loader2Icon className="size-4 animate-spin" />
                        {t("accepting")}
                    </div>
                ) : state.status === "invalid" ? (
                    <div className="text-center">
                        <h1 className="text-xl font-semibold tracking-tight text-foreground">
                            {t("invalidTitle")}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground">{t("invalidBody")}</p>
                        <Link
                            href="/dashboard"
                            className="mt-6 inline-block text-sm font-medium text-brand-dark hover:underline"
                        >
                            {t("backToApp")}
                        </Link>
                    </div>
                ) : state.status === "ready" ? (
                    <div className="text-center">
                        <span
                            aria-hidden
                            className="mx-auto grid size-12 place-items-center rounded-xl bg-brand-light text-lg font-semibold text-brand-dark"
                        >
                            {state.preview.workspaceName.trim().charAt(0).toUpperCase()}
                        </span>
                        <h1 className="mt-4 text-xl font-semibold tracking-tight text-foreground text-balance">
                            {t("heading", { workspace: state.preview.workspaceName })}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground">
                            {t("roleLine", { role: t(roleKey(state.preview.role)) })}
                        </p>
                        <div className="mt-6">
                            <AcceptInviteLink flowId={state.preview.flowId} />
                        </div>
                    </div>
                ) : null}
            </div>
        </div>
    );
}
