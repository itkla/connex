import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";
import { resolvePreLaunch } from "@/app/lib/landingMode";
import type { Metadata } from "next";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
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

/** Public session resolution selects CTAs; transport failures leave the whole page readable. */
export default async function Home() {
    const requestHeaders = await headers();
    const preLaunch = resolvePreLaunch({ host: requestHeaders.get("host") });
    const cookie = requestHeaders.get("cookie");
    const user = preLaunch ? null : await getPublicPageUserFromCookie(cookie);
    const t = await getTranslations("CommonHome");
    const ctaHref = user ? "/dashboard" : "/auth/register";
    const ctaLabel = user ? t("ctaDashboard") : t("heroCtaPrimary");

    return <LandingPage preLaunch={preLaunch} ctaHref={ctaHref} ctaLabel={ctaLabel} />;
}
