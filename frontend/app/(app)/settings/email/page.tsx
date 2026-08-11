import { headers } from "next/headers";
import { redirect } from "next/navigation";

import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import EmailPanel from "@/app/components/settings/EmailPanel";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

export default async function EmailSettingsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    if (capabilitiesResult.data.mailManaged) {
        redirect("/settings/members");
    }
    return <EmailPanel />;
}
