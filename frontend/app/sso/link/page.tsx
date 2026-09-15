import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { SsoLinkForm } from "@/app/sso/link/SsoLinkForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("SsoLink");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default function SsoLinkPage() {
    return <SsoLinkForm />;
}
