"use client";

import { useTranslations } from "next-intl";

import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";

/**
 * Withholds workspace-scoped client content after selection recovery cannot establish which
 * cookie-backed membership is active. Its retry performs the provider's ordered authoritative
 * read instead of trusting an unordered server-component refresh payload, and it carries the same
 * sign-out escape as {@link WorkspaceUnavailablePage} because a rejected session repeats its
 * rejection for every retry.
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
            actions={[{ href: "/auth/logout", label: t("signOut"), variant: "ghost" }]}
        />
    );
}
