import type { Metadata } from "next";
import { getLocale, getTranslations } from "next-intl/server";

import { LandingPage } from "@/app/components/landing/LandingPage";
import { openGraphLocales } from "@/app/lib/siteMetadata";
import { resolveLocale } from "@/i18n/config";

/**
 * Prelaunch landing metadata, matching what the product application states on a prelaunch host.
 * @returns the landing page's title, description, and social card
 */
export async function generateMetadata(): Promise<Metadata> {
    const [locale, t] = await Promise.all([getLocale(), getTranslations("CommonHome")]);
    const metaTitle = t("prelaunch.metaTitle");
    const metaDescription = t("prelaunch.metaDescription");

    return {
        title: { absolute: metaTitle },
        description: metaDescription,
        alternates: { canonical: "/" },
        openGraph: {
            type: "website",
            siteName: t("brand"),
            url: "/",
            title: metaTitle,
            description: metaDescription,
            ...openGraphLocales(resolveLocale(locale)),
        },
    };
}

/** This deployment exists to collect launch signups, so the body always renders prelaunch. */
export default async function Home() {
    const t = await getTranslations("CommonHome");
    return LandingPage({ t, preLaunch: true, ctaHref: "/", ctaLabel: t("heroCtaPrimary") });
}
