import type { MetadataRoute } from "next";
import { headers } from "next/headers";

import { PUBLIC_PATHS } from "@/app/robots";
import { requestOrigin } from "@/app/lib/requestHost";

/**
 * `sitemap.xml` for the prelaunch site, built from the request origin so the listed URLs name the
 * host that served it.
 * @returns one entry per public route
 */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
    const origin = requestOrigin(await headers());
    if (!origin) return [];
    return PUBLIC_PATHS.map((path) => ({ url: new URL(path, origin).toString() }));
}
