import type { MetadataRoute } from "next";
import { getTranslations } from "next-intl/server";

const BRAND_COLOR = "#73d200";

/**
 * The installable web app manifest. The mark is a full-bleed brand square, so the same file serves
 * as both the plain and the maskable icon: there is no corner detail for a platform mask to cut.
 * @returns the manifest for the reader's locale
 */
export default async function manifest(): Promise<MetadataRoute.Manifest> {
    const [t, common] = await Promise.all([
        getTranslations("AppMetadata"),
        getTranslations("CommonHome"),
    ]);

    return {
        name: t("title"),
        short_name: common("brand"),
        description: t("description"),
        start_url: "/",
        display: "standalone",
        background_color: BRAND_COLOR,
        theme_color: BRAND_COLOR,
        icons: [
            { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
            { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "maskable" },
            { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
            { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
    };
}
