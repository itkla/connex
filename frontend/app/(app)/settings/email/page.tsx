import { redirect } from "next/navigation";

import EmailPanel from "@/app/components/settings/EmailPanel";
import { getMailManaged } from "@/app/lib/api";

export default async function EmailSettingsPage() {
    const mailManaged = await getMailManaged()
        .then((r) => r.managed)
        .catch(() => false);
    if (mailManaged) {
        redirect("/settings/members");
    }
    return <EmailPanel />;
}
