"use client";

import { useTranslations } from "next-intl";

import type { TenantDiagnostics } from "@/app/lib/types";
import { DiagnosticsSection } from "./DiagnosticsSection";
import { StatusPill } from "./StatusPill";

/**
 * Secret-store key health. Every value shown is metadata: key ids, status, counts, and the
 * reason a row failed verification. Plaintext, ciphertext, and wrapped key material are never
 * part of this payload.
 */
export function SecretStoreSection({
    data,
    loading,
    error,
}: {
    data: TenantDiagnostics | null;
    loading: boolean;
    error: string | null;
}) {
    const t = useTranslations("TenantDiagnostics");
    const store = data?.secretStore ?? null;

    return (
        <DiagnosticsSection
            title={t("secretStoreTitle")}
            description={t("secretStoreDescription")}
            loading={loading}
            error={error}
            isEmpty={!loading && !error && !store}
            emptyLabel={t("secretStoreEmpty")}
        >
            {store ? (
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-card px-4 py-3">
                        <div className="min-w-0">
                            <span className="text-sm text-foreground">{t("secretStoreHealth")}</span>
                            <p className="truncate font-mono text-xs text-muted-foreground">
                                {store.activeKeyId ?? t("secretStoreNoActiveKey")}
                            </p>
                        </div>
                        <StatusPill
                            tone={store.healthy ? "ok" : store.available ? "warn" : "bad"}
                            label={
                                store.healthy
                                    ? t("secretStoreHealthy")
                                    : store.available
                                      ? t("secretStoreDegraded")
                                      : t("secretStoreUnavailable")
                            }
                        />
                    </div>

                    <dl className="grid gap-x-6 gap-y-1 rounded-lg border border-border bg-card px-4 py-3 text-xs sm:grid-cols-3">
                        <Count label={t("secretsTotal")} value={store.totalSecrets} />
                        <Count label={t("secretsActive")} value={store.activeSecrets} />
                        <Count label={t("secretsStale")} value={store.staleSecrets} />
                        <Count label={t("secretsMissingKey")} value={store.missingKeySecrets} />
                        <Count label={t("secretsDisabledKey")} value={store.disabledKeySecrets} />
                        <Count label={t("secretsMismatched")} value={store.mismatchedSecrets} />
                    </dl>

                    {store.failures.length > 0 ? (
                        <ul className="divide-y divide-border rounded-lg border border-border bg-card">
                            {store.failures.map((failure) => (
                                <li
                                    key={failure.secretId}
                                    className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5"
                                >
                                    <span className="font-mono text-xs text-foreground">
                                        {failure.purpose ?? t("secretUnknownPurpose")}
                                    </span>
                                    <span className="font-mono text-xs text-muted-foreground">
                                        {failure.reason ?? failure.status}
                                    </span>
                                </li>
                            ))}
                        </ul>
                    ) : null}
                </div>
            ) : null}
        </DiagnosticsSection>
    );
}

function Count({ label, value }: { label: string; value: number }) {
    return (
        <div className="flex gap-1.5">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="font-medium text-foreground">{value}</dd>
        </div>
    );
}
