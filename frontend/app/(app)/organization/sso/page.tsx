import { headers } from "next/headers";
import { redirect } from "next/navigation";

import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import SsoPanel from "@/app/components/settings/SsoPanel";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

export default async function OrgSsoPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    if (!capabilitiesResult.data.sso) {
        redirect("/organization/members");
    }
    return <SsoPanel />;
}
