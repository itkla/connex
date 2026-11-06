import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { SsoLinkForm } from "@/app/sso/link/SsoLinkForm";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("SsoLink");
    return { title: t("title"), robots: { index: false, follow: false } };
}

export default async function SsoLinkPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string | string[] }>;
}) {
    const { redirect } = await searchParams;
    return <SsoLinkForm redirectUrl={typeof redirect === "string" ? redirect : null} />;
}
