import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import SettingsHome from "@/app/components/settings/SettingsHome";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("SettingsHome");
    return {
        title: t("title"),
        description: t("description"),
    };
}

/**
 * The unified Settings home (#1340 WS4.1). Resolves the instance capabilities the navigation gates
 * on server-side; the viewer's effective permissions and active workspace already travel down the
 * app shell's providers, so the navigation needs no second lookup for either.
 */
export default async function SettingsPage() {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    return <SettingsHome capabilities={capabilitiesResult.ok ? capabilitiesResult.data : null} />;
}
