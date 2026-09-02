"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import { ApiError, getVersion } from "@/app/lib/api";
import {
    resolveBuildIdentity,
    resolveBuildMetadata,
    type ReleaseProvenanceEvidence,
} from "@/app/lib/buildIdentity";
import type { ProductVersion } from "@/app/lib/types";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusPill, type DiagnosticTone } from "./StatusPill";

type VersionLoadState =
    | { kind: "loading" }
    | { kind: "loaded"; version: ProductVersion }
    | { kind: "unavailable"; referenceId: string | null };

function BuildRow({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 px-4 py-2.5">
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="break-all font-mono text-sm text-foreground">{value}</dd>
        </div>
    );
}

/** Running build versions, compared without treating version equality as release-provenance proof. */
export function BuildIdentitySection({
    releaseProvenance = null,
}: {
    releaseProvenance?: ReleaseProvenanceEvidence | null;
}) {
    const t = useTranslations("TenantDiagnostics");
    const [loadState, setLoadState] = useState<VersionLoadState>({ kind: "loading" });
    const [reloadToken, setReloadToken] = useState(0);

    useEffect(() => {
        let cancelled = false;
        void getVersion()
            .then((version) => {
                if (!cancelled) setLoadState({ kind: "loaded", version });
            })
            .catch((caught: unknown) => {
                if (!cancelled) {
                    setLoadState({
                        kind: "unavailable",
                        referenceId: caught instanceof ApiError ? caught.correlationId ?? null : null,
                    });
                }
            });
        return () => {
            cancelled = true;
        };
    }, [reloadToken]);

    const refresh = useCallback(() => {
        setLoadState({ kind: "loading" });
        setReloadToken((token) => token + 1);
    }, []);

    const loading = loadState.kind === "loading";
    const identity = resolveBuildIdentity(
        process.env.NEXT_PUBLIC_APP_VERSION,
        loadState.kind === "loaded" ? loadState.version : null,
        releaseProvenance,
    );

    let tone: DiagnosticTone;
    let status: string;
    let detail: string;
    switch (identity.kind) {
        case "matched":
            tone = "ok";
            status = t("buildIdentityMatched");
            detail = t("buildIdentityMatchedBody");
            break;
        case "version-agreement-unverified":
            tone = "neutral";
            status = t("buildIdentityVersionAgreementUnverified");
            detail = t("buildIdentityVersionAgreementUnverifiedBody");
            break;
        case "mismatched":
            tone = "warn";
            status = t("buildIdentityMismatched");
            detail = t("buildIdentityMismatchedBody");
            break;
        case "backend-unavailable":
            tone = "warn";
            status = t("buildIdentityBackendUnavailable");
            detail = t("buildIdentityBackendUnavailableBody");
            break;
        case "unversioned":
            tone = "neutral";
            status = t("buildIdentityUnversioned");
            detail = t("buildIdentityUnversionedBody");
            break;
        default: {
            const exhaustiveIdentity: never = identity;
            return exhaustiveIdentity;
        }
    }

    const backendVersion = identity.backendVersion;
    const unavailable = t("buildIdentityUnavailable");
    const notStamped = t("buildIdentityNotStamped");
    const backendVersionValue = backendVersion === null
        ? unavailable
        : backendVersion.version.trim() || notStamped;

    return (
        <SettingsSection
            title={t("buildIdentityTitle")}
            description={t("buildIdentityDescription")}
            action={
                <Button
                    type="button"
                    variant="outline"
                    size="inline"
                    onClick={refresh}
                    disabled={loading}
                >
                    {loading
                        ? t("buildIdentityRefreshing")
                        : loadState.kind === "unavailable"
                          ? t("retry")
                          : t("buildIdentityRefresh")}
                </Button>
            }
        >
            {loading ? (
                <div className="space-y-2" aria-busy="true" aria-live="polite">
                    <Skeleton className="h-12 w-full rounded-lg" />
                    <Skeleton className="h-36 w-full rounded-lg" />
                </div>
            ) : (
                <div className="space-y-3">
                    <div className="rounded-lg border border-border bg-card px-4 py-3">
                        <StatusPill tone={tone} label={status} />
                        <p className="mt-2 text-sm text-muted-foreground">{detail}</p>
                        {loadState.kind === "unavailable" && loadState.referenceId ? (
                            <p className="mt-1 font-mono text-xs text-muted-foreground">
                                {t("referenceId", { id: loadState.referenceId })}
                            </p>
                        ) : null}
                    </div>
                    <dl className="divide-y divide-border rounded-lg border border-border bg-card">
                        <BuildRow
                            label={t("buildIdentityFrontendVersion")}
                            value={identity.frontendVersion ?? notStamped}
                        />
                        <BuildRow
                            label={t("buildIdentityBackendVersion")}
                            value={backendVersionValue}
                        />
                        <BuildRow
                            label={t("buildIdentityBuildTime")}
                            value={resolveBuildMetadata(backendVersion?.buildTime ?? null) ?? unavailable}
                        />
                        <BuildRow
                            label={t("buildIdentityGitSha")}
                            value={resolveBuildMetadata(backendVersion?.gitSha ?? null) ?? unavailable}
                        />
                    </dl>
                </div>
            )}
        </SettingsSection>
    );
}
