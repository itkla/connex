"use client";

import { useEffect, useState } from "react";
import { useMessages, useTranslations } from "next-intl";

import type { AuditLogEntry } from "@/app/lib/types";
import { ApiError, getOrgAudit } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { orgAuditActionKey, titleCaseAction } from "@/app/lib/orgAuditActionLabel";
import AuditLogBrowser from "@/app/components/admin/AuditLogBrowser";
import Rise from "@/app/components/motion/Rise";
import {
    SettingsPanelHeading,
    type SettingsPanelPresentation,
} from "@/app/components/settings/SettingsSection";
import { NoAccessCard, EmptyRow, ListCard } from "@/app/components/organization/OrgPrimitives";

const PAGE_SIZE = 30;

type AuditRead =
    | { kind: "loading" }
    | { kind: "loaded"; entries: AuditLogEntry[] }
    | { kind: "refused" }
    | { kind: "failed" };

/** Organization governance events, with the same loaded-row filters as the workspace audit log. */
export default function OrgAuditPanel({
    presentation = "page",
}: {
    presentation?: SettingsPanelPresentation;
} = {}) {
    const { activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId;
    return orgId == null ? null : <OrgAuditContent key={orgId} orgId={orgId} presentation={presentation} />;
}

function OrgAuditContent({ orgId, presentation }: { orgId: number; presentation: SettingsPanelPresentation }) {
    const t = useTranslations("OrgAudit");
    const messages = useMessages();
    const [read, setRead] = useState<AuditRead>({ kind: "loading" });

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const entries = await getOrgAudit(orgId, { limit: PAGE_SIZE, offset: 0 });
                if (!cancelled) setRead({ kind: "loaded", entries });
            } catch (error) {
                if (!cancelled) setRead({ kind: error instanceof ApiError && error.status === 403 ? "refused" : "failed" });
            }
        })();
        return () => { cancelled = true; };
    }, [orgId]);

    const actionLabel = (action: string) => {
        const key = orgAuditActionKey(messages, action);
        return key === null ? titleCaseAction(action) : t(key);
    };

    if (read.kind === "refused") return <NoAccessCard />;
    if (read.kind === "loaded") {
        return <AuditLogBrowser
            initialEntries={read.entries}
            pageSize={PAGE_SIZE}
            presentation={presentation}
            orgId={orgId}
            heading={t("title")}
            description={t("subtitle")}
            actionLabel={actionLabel}
        />;
    }

    return (
        <Rise className="space-y-4">
            <SettingsPanelHeading presentation={presentation} title={t("title")} description={t("subtitle")} />
            {read.kind === "failed" ? <EmptyRow>{t("loadError")}</EmptyRow> : (
                <ListCard>
                    {[0, 1, 2].map((i) => (
                        <li key={i} className="flex items-center justify-between gap-3 px-4 py-3">
                            <span className="h-4 w-48 animate-pulse rounded bg-muted" />
                            <span className="h-3 w-16 animate-pulse rounded bg-muted" />
                        </li>
                    ))}
                </ListCard>
            )}
        </Rise>
    );
}
