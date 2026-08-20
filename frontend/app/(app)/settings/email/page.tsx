import { headers } from "next/headers";

import CapabilityUnavailablePage from "@/app/components/CapabilityUnavailablePage";
import EmailPanel from "@/app/components/settings/EmailPanel";
import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

/**
 * Workspace email settings, or the reason there is nothing here to set.
 *
 * A managed-mail instance sends its own mail, so this page has no settings to offer — but it says
 * so where its own name is, rather than forwarding the reader to Members. #1340 forbids the
 * teleport: a reader who followed a link, a search result, or a bookmark to email settings gets an
 * answer about email settings.
 */
export default async function EmailSettingsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    if (!capabilitiesResult.ok) {
        return <CapabilityUnavailablePage />;
    }
    if (capabilitiesResult.data.mailManaged) {
        return <SettingsAvailabilityNotice state="managed" />;
    }
    return <EmailPanel />;
}
