import { redirect } from "next/navigation";

import SsoPanel from "@/app/components/settings/SsoPanel";
import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";

export default async function OrgSsoPage() {
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    if (!capabilities.sso) {
        redirect("/organization/members");
    }
    return <SsoPanel />;
}
