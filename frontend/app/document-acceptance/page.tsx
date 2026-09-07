import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import DocumentAcceptanceEntry from "@/app/components/marketing/campaigns/DocumentAcceptanceEntry";
import { defaultLocale } from "@/i18n/config";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations({
        locale: defaultLocale,
        namespace: "DocumentAcceptance",
    });
    return {
        title: `${t("metaTitle")} | Connex`,
        robots: { index: false, follow: false },
    };
}

export default function DocumentAcceptancePage() {
    return <DocumentAcceptanceEntry />;
}
