import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import UnsubscribeEntry from "@/app/components/marketing/campaigns/UnsubscribeEntry";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Unsubscribe");
    return { title: `${t("title")} — Connex`, robots: { index: false, follow: false } };
}

export default function UnsubscribePage() {
    return <UnsubscribeEntry />;
}
