import type { MetadataRoute } from "next";
import { headers } from "next/headers";

import { requestOrigin } from "@/app/lib/requestHost";

/** Every route this deployment serves; anything else is a 404 here. */
export const PUBLIC_PATHS = ["/", "/privacy", "/legal", "/disclosure", "/tokushoho"] as const;

/**
 * `robots.txt` for the prelaunch site. It welcomes crawlers to the public routes and names the
 * sitemap on the host that asked, as the product application does on its public hosts.
 * @returns the crawl rules
 */
export default async function robots(): Promise<MetadataRoute.Robots> {
    const origin = requestOrigin(await headers());
    return {
        rules: { userAgent: "*", allow: [...PUBLIC_PATHS], disallow: ["/api/"] },
        ...(origin ? { sitemap: `${origin}/sitemap.xml` } : {}),
    };
}
