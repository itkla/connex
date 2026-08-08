"use client";

import { useTranslations } from "next-intl";

import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";

/**
 * Withholds workspace-scoped client content after selection recovery cannot establish which
 * cookie-backed membership is active. Its retry performs the provider's ordered authoritative
 * read instead of trusting an unordered server-component refresh payload.
 */
export default function WorkspaceSelectionUnavailable({
    onRetry,
}: {
    onRetry: () => Promise<void>;
}) {
    const t = useTranslations("WorkspaceUnavailable");

    return (
        <PermissionsUnavailable
            title={t("title")}
            body={t("body")}
            action={(
                <WorkspaceUnavailableRetry
                    label={t("retry")}
                    pendingLabel={t("retrying")}
                    onRetry={onRetry}
                />
            )}
        />
    );
}
