import { redirect } from "next/navigation";

import EmailPanel from "@/app/components/settings/EmailPanel";
import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";

export default async function EmailSettingsPage() {
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    if (capabilities.mailManaged) {
        redirect("/settings/members");
    }
    return <EmailPanel />;
}
