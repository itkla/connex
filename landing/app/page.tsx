import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { LandingPage } from "@/app/components/landing/LandingPage";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("CommonHome");
    return {
        title: t("metaTitle"),
        description: t("metaDescription"),
        alternates: { canonical: "/" },
        openGraph: {
            title: t("metaTitle"),
            description: t("metaDescription"),
            type: "website",
        },
        twitter: { card: "summary_large_image", title: t("metaTitle"), description: t("metaDescription") },
    };
}

/** This deployment exists to collect launch signups, so the body always renders prelaunch. */
export default async function Home() {
    const t = await getTranslations("CommonHome");
    return <LandingPage preLaunch ctaHref="/" ctaLabel={t("heroCtaPrimary")} />;
}
