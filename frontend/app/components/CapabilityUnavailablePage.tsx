import { getTranslations } from "next-intl/server";

import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";

/** Fail-closed route state for an instance-capability lookup that could not be completed. */
export default async function CapabilityUnavailablePage() {
    const t = await getTranslations("CapabilityUnavailable");

    return (
        <PermissionsUnavailable
            title={t("title")}
            body={t("body")}
            action={<WorkspaceUnavailableRetry label={t("retry")} pendingLabel={t("retrying")} />}
        />
    );
}
