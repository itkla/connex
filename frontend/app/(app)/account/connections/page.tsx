import { headers } from "next/headers";

import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";
import ConnectionsPanel from "@/app/components/account/ConnectionsPanel";

export default async function AccountConnectionsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilities = await getCapabilities(cookie ? { headers: { cookie } } : {})
        .catch(() => DEFAULT_CAPABILITIES);

    return <ConnectionsPanel capabilities={capabilities} />;
}
