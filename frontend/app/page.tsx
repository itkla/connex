import { headers } from "next/headers";
import { getLocale, getTranslations } from "next-intl/server";
import { resolvePreLaunch } from "@/app/lib/landingMode";
import { openGraphLocales } from "@/app/lib/siteMetadata";
import { resolveLocale } from "@/i18n/config";
import type { Metadata } from "next";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import { LandingPage } from "@/app/components/landing/LandingPage";

/**
 * Landing metadata. The host decides which page this address serves, so it also decides what the
 * page may claim: on a prelaunch host the product cannot be visited, and advertising an explorable
 * CRM there would be a promise the page does not keep.
 *
 * The X card is left unstated: with a 1200x630 share image shipped at the app root, Next resolves
 * it to `summary_large_image`, and stating a card here would fix it before that image is known.
 * @returns the landing page's title, description, and social card
 */
export async function generateMetadata(): Promise<Metadata> {
    const [locale, requestHeaders, t] = await Promise.all([
        getLocale(),
        headers(),
        getTranslations("CommonHome"),
    ]);
    const preLaunch = resolvePreLaunch({ host: requestHeaders.get("host") });
    const metaTitle = preLaunch ? t("prelaunch.metaTitle") : t("metaTitle");
    const metaDescription = preLaunch ? t("prelaunch.metaDescription") : t("metaDescription");

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

/** Public session resolution selects CTAs; transport failures leave the whole page readable. */
export default async function Home() {
    const requestHeaders = await headers();
    const preLaunch = resolvePreLaunch({ host: requestHeaders.get("host") });
    const cookie = requestHeaders.get("cookie");
    const user = preLaunch ? null : await getPublicPageUserFromCookie(cookie);
    const t = await getTranslations("CommonHome");
    const ctaHref = user ? "/dashboard" : "/auth/register";
    const ctaLabel = user ? t("ctaDashboard") : t("heroCtaPrimary");

    return LandingPage({ t, preLaunch, ctaHref, ctaLabel });
}
