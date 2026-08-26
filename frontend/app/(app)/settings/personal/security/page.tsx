import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import PersonalSecurity from "@/app/components/settings/PersonalSecurity";

export async function generateMetadata(): Promise<Metadata> {
    const [tAccount, t] = await Promise.all([
        getTranslations("Account"),
        getTranslations("SettingsPersonalSecurity"),
    ]);
    return {
        title: tAccount("tabSecurity"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical personal Security destination (#1340 WS4.1).
 *
 * Nothing is read here. Passkeys are enumerated in the browser because enrolling one is a WebAuthn
 * ceremony that has to happen there anyway, and a server-rendered list would be stale the moment the
 * first one completes.
 */
export default function PersonalSecurityPage() {
    return <PersonalSecurity />;
}
