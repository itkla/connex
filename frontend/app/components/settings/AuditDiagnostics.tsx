"use client";

import { useTranslations } from "next-intl";

import AuditLogBrowser from "@/app/components/admin/AuditLogBrowser";
import DiagnosticsPanel from "@/app/components/diagnostics/DiagnosticsPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import {
    SectionRefusal,
    SettingsSectionRegion,
} from "@/app/components/settings/SettingsSectionRegion";
import { usePermissionCheck } from "@/app/hooks/usePermissions";
import { useSectionArrival } from "@/app/hooks/useSectionArrival";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { AUDIT_DIAGNOSTICS_SECTIONS } from "@/app/lib/auditDiagnosticsSections";
import type { PermissionCheck } from "@/app/lib/permissionState";
import type { AuditLogEntry } from "@/app/lib/types";

/**
 * What the server made of the audit read, kept apart because the three outcomes need three
 * different things said about them.
 *
 * `refused` is a settled answer and names who can change it. `unavailable` is not settled and
 * offers a retry. Neither may be presented as `loaded` with nothing in it: an empty security log
 * reads as "nothing happened", which is a claim about this workspace's history that a failed
 * request has no grounds to make.
 */
export type AuditRead =
    | { kind: "loaded"; entries: AuditLogEntry[] }
    | { kind: "refused" }
    | { kind: "unavailable" };

/** The refusal postures for this page's sections, in its own voice for the unresolved case. */
function RefusedSection({ check }: { check: Exclude<PermissionCheck, "granted"> }) {
    const t = useTranslations("SettingsAuditDiagnostics");
    return (
        <SectionRefusal
            check={check}
            retryTitle={t("accessCheckFailedTitle")}
            retryBody={t("accessCheckFailedBody")}
        />
    );
}

/**
 * Audit & diagnostics: the workspace's one destination for what happened here and whether the
 * machinery behind it is healthy (#1340 WS4.4).
 *
 * It consolidates the audit log, which lived under `/admin` and looked like an operator tool, with
 * the workspace diagnostics tab, which lived under Settings and looked like a different product.
 * They answer the same question at two depths, so they now sit on one page under the name the epic
 * gives them, each with its own deep link.
 *
 * The two sections are gated separately and always were: reading the log needs `AUDIT_READ` and
 * reading diagnostics needs `WORKSPACE_SETTINGS`. Either one reaches the destination — a workspace
 * that grants an auditor role exactly the first must not lose the audit log to a page named for it
 * — and whichever the reader lacks explains itself where it stands.
 *
 * The audit entries are read on the server, as they were on `/admin/logs`. A refusal and a failed
 * read are carried separately and neither becomes an empty log.
 *
 * @param audit - what the server's audit read produced
 * @param pageSize - the page size the browser continues paging with
 */
export default function AuditDiagnostics({
    audit,
    pageSize,
}: {
    audit: AuditRead;
    pageSize: number;
}) {
    const t = useTranslations("SettingsAuditDiagnostics");
    const tNav = useTranslations("SettingsNav");
    const tAudit = useTranslations("AdminAuditLog");
    const tSettings = useTranslations("WorkspaceSettings");
    const { activeWorkspace } = useWorkspace();
    const { register, arrived } = useSectionArrival(AUDIT_DIAGNOSTICS_SECTIONS);
    const diagnostics = usePermissionCheck("WORKSPACE_SETTINGS");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupAuditDiagnostics")}
                    description={t("description", { workspace: activeWorkspace?.name ?? "" })}
                />
            </Rise>

            <SettingsSectionRegion section="audit" arrived={arrived} register={register}>
                {audit.kind === "loaded" ? (
                    <AuditLogBrowser
                        initialEntries={audit.entries}
                        pageSize={pageSize}
                        presentation="section"
                    />
                ) : (
                    <Rise>
                        <SettingsSection
                            title={tAudit("heading")}
                            description={tAudit("subtitle")}
                        >
                            {audit.kind === "refused" ? (
                                <SettingsAvailabilityNotice
                                    variant="inline"
                                    state="ask-admin"
                                    title={tAudit("deniedTitle")}
                                    body={tAudit("deniedBody")}
                                />
                            ) : (
                                <SettingsAvailabilityNotice
                                    variant="inline"
                                    state="retry"
                                    title={t("auditFailedTitle")}
                                    body={t("auditFailedBody")}
                                />
                            )}
                        </SettingsSection>
                    </Rise>
                )}
            </SettingsSectionRegion>

            <SettingsSectionRegion section="diagnostics" arrived={arrived} register={register}>
                <Rise>
                    <SettingsSection
                        title={tSettings("tabDiagnostics")}
                        description={t("diagnosticsDescription")}
                    >
                        {diagnostics === "granted" ? (
                            <DiagnosticsPanel scope="workspace" />
                        ) : (
                            <RefusedSection check={diagnostics} />
                        )}
                    </SettingsSection>
                </Rise>
            </SettingsSectionRegion>
        </div>
    );
}
