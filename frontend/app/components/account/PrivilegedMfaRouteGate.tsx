"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";

import AccessDenied from "@/app/components/AccessDenied";
import { PageShell } from "@/app/components/PageShell";
import { usePrivilegedMfaStanding } from "@/app/hooks/usePrivilegedMfaStanding";
import { privilegedMfaConfinementDestination } from "@/app/lib/api";
import { settingsDestination } from "@/app/lib/settingsEntryPoints";

/** Keeps client navigation from mounting pages that known passkey confinement cannot use. */
export default function PrivilegedMfaRouteGate({ children }: { children: ReactNode }) {
    const pathname = usePathname();
    const standing = usePrivilegedMfaStanding();
    const t = useTranslations("AccountSecurity");
    if (!standing.confined || pathname === settingsDestination("personal.security").href) return children;

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
