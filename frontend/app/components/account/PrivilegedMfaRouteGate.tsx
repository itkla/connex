"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowPathIcon } from "@heroicons/react/24/outline";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";

import AccessDenied from "@/app/components/AccessDenied";
import { PageShell } from "@/app/components/PageShell";
import { EmptyState } from "@/app/components/EmptyState";
import { Button } from "@/components/ui/button";
import { usePrivilegedMfaReadiness } from "@/app/hooks/usePrivilegedMfaConfinement";
import { usePrivilegedMfaStanding } from "@/app/hooks/usePrivilegedMfaStanding";
import { privilegedMfaConfinementDestination } from "@/app/lib/api";
import { settingsDestination } from "@/app/lib/settingsEntryPoints";

/** Keeps client navigation from mounting pages that known passkey confinement cannot use. */
export default function PrivilegedMfaRouteGate({ children }: { children: ReactNode }) {
    const pathname = usePathname();
    const standing = usePrivilegedMfaStanding();
    const readiness = usePrivilegedMfaReadiness();
    const t = useTranslations("AccountSecurity");
    if (pathname === settingsDestination("personal.security").href) return children;
    if (!standing.confined && readiness !== "ready") {
        return (
            <PageShell>
                <div role="status" aria-busy={readiness === "loading"}>
                    <EmptyState
                        tone="muted"
                        icon={ArrowPathIcon}
                        title={t(readiness === "failed" ? "accessCheckFailedTitle" : "accessCheckingTitle")}
                        body={t(readiness === "failed" ? "accessCheckFailedBody" : "accessCheckingBody")}
                        action={<div className="flex flex-wrap justify-center gap-2">
                            {readiness === "failed" && <Button onClick={() => void standing.refresh("check")}>{t("retry")}</Button>}
                            <Button variant="outline" asChild><Link href={settingsDestination("personal.security").href}>{t("accessSecurity")}</Link></Button>
                        </div>}
                    />
                </div>
            </PageShell>
        );
    }
    if (!standing.confined) return children;

    return (
        <PageShell>
            <AccessDenied
                title={t(standing.reason === "attestation" ? "approvalNeededTitle" : "enrollmentNeededTitle")}
                body={t(standing.reason === "attestation" ? "approvalNeededDescription" : "enrollmentNeededDescription")}
                actions={[{
                    href: privilegedMfaConfinementDestination() ?? settingsDestination("personal.security").href,
                    label: t("returnToSecurity"),
                }]}
            />
        </PageShell>
    );
}
