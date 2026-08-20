import { headers } from "next/headers";

import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import SsoPanel from "@/app/components/settings/SsoPanel";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

/**
 * Single sign-on settings, or the reason this instance has none.
 *
 * The organization layout still owns who may be here at all; this page answers only what the
 * capability is doing. An instance built without single sign-on says so in place instead of
 * forwarding the reader to Administrators, which is the teleport #1340 forbids.
 */
export default async function OrgSsoPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    if (!capabilitiesResult.data.sso) {
        return <SettingsAvailabilityNotice state="not-enabled" />;
    }
    return <SsoPanel />;
}
