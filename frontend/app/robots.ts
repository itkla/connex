import type { MetadataRoute } from "next";
import { headers } from "next/headers";

import { CRAWL_ALLOWED_PATHS, CRAWL_DISALLOWED_PATHS, shouldBlockCrawlers } from "@/app/lib/crawlPolicy";
import { requestOrigin } from "@/app/lib/requestHost";

/**
 * `robots.txt`, answered per request. Reading the `Host` header makes this a dynamic route handler,
 * which is what lets one process serve a welcoming `robots.txt` on its public hosts — prelaunch
 * included — and a blanket refusal on the preview host and any host listed in `CONNEX_NOINDEX_HOSTS`.
 * @returns the crawl rules for the host that asked
 */
export default async function robots(): Promise<MetadataRoute.Robots> {
    const requestHeaders = await headers();
    const origin = requestOrigin(requestHeaders);

    if (shouldBlockCrawlers({ host: requestHeaders.get("host") })) {
        return { rules: { userAgent: "*", disallow: "/" } };
    }

    return {
        rules: {
            userAgent: "*",
            allow: [...CRAWL_ALLOWED_PATHS],
            disallow: [...CRAWL_DISALLOWED_PATHS],
        },
        ...(origin ? { sitemap: `${origin}/sitemap.xml` } : {}),
    };
}
