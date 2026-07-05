import { redirect } from "next/navigation";

import SsoPanel from "@/app/components/settings/SsoPanel";
import { getSsoInstanceEnabled } from "@/app/lib/api";

export default async function OrgSsoPage() {
    const ssoEnabled = await getSsoInstanceEnabled()
        .then((r) => r.enabled)
        .catch(() => false);
    if (!ssoEnabled) {
        redirect("/organization/members");
    }
    return <SsoPanel />;
}
