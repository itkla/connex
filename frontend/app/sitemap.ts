import type { MetadataRoute } from "next";
import { headers } from "next/headers";

import { CRAWL_ALLOWED_PATHS, shouldBlockCrawlers } from "@/app/lib/crawlPolicy";
import { docsCategories } from "@/app/lib/docs/registry";
import { requestOrigin } from "@/app/lib/requestHost";

/**
 * Every public path the sitemap lists: the crawlable routes, then the documentation tree walked
 * exactly as `app/docs/[...slug]/page.tsx` walks it, so the sitemap cannot drift from what renders.
 * @returns absolute paths, in navigation order
 */
export function sitemapPaths(): string[] {
    const paths: string[] = [...CRAWL_ALLOWED_PATHS];
    for (const category of docsCategories) {
        paths.push(`/docs/${category.slug}`);
        for (const article of category.articles) {
            paths.push(`/docs/${category.slug}/${article.slug}`);
        }
    }
    return paths;
}

/**
 * `sitemap.xml`, answered per request. The origin comes from the request rather than from a baked
 * environment value because one process answers for several hostnames, and a sitemap that names a
 * different host than the one that served it is ignored.
 * @returns the sitemap entries, or none when this host must not be indexed
 */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
    const requestHeaders = await headers();
    const origin = requestOrigin(requestHeaders);

    if (!origin || shouldBlockCrawlers({ host: requestHeaders.get("host") })) {
        return [];
    }

    return sitemapPaths().map((path) => ({ url: new URL(path, origin).toString() }));
}
