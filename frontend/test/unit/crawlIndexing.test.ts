import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import robots from "@/app/robots";
import sitemap, { sitemapPaths } from "@/app/sitemap";
import { CRAWL_ALLOWED_PATHS } from "@/app/lib/crawlPolicy";
import { docsCategories } from "@/app/lib/docs/registry";
import { PROTECTED_PREFIXES } from "@/app/lib/protectedRoutes";

const request = vi.hoisted(() => ({ headers: new Headers() }));
vi.mock("next/headers", () => ({ headers: async () => request.headers }));

function requestFrom(host: string): void {
    request.headers = new Headers({ host, "x-forwarded-proto": "https" });
}

function disallowedPaths(rules: Awaited<ReturnType<typeof robots>>["rules"]): string[] {
    const rule = Array.isArray(rules) ? rules[0] : rules;
    const disallow = rule.disallow ?? [];
    return Array.isArray(disallow) ? disallow : [disallow];
}

beforeEach(() => {
    vi.stubEnv("CONNEX_LANDING_MODE", "product");
    vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", "");
    vi.stubEnv("CONNEX_NOINDEX_HOSTS", "");
    requestFrom("connexcrm.jp");
});

afterEach(() => {
    vi.unstubAllEnvs();
});

describe("robots", () => {
    it("closes every authenticated prefix route protection knows about", async () => {
        const disallow = disallowedPaths((await robots()).rules);
        for (const prefix of PROTECTED_PREFIXES) {
            expect(disallow).toContain(prefix);
        }
    });

    it("closes the sessionless routes that hold nothing to index", async () => {
        const disallow = disallowedPaths((await robots()).rules);
        expect(disallow).toEqual(
            expect.arrayContaining([
                "/auth/",
                "/invite",
                "/invite-link",
                "/sso/",
                "/onboarding",
                "/document-acceptance",
                "/unsubscribe",
                "/design-system",
                "/api/",
            ]),
        );
    });

    it("welcomes the public marketing, documentation, and legal routes", async () => {
        const rules = (await robots()).rules;
        const rule = Array.isArray(rules) ? rules[0] : rules;
        expect(rule.allow).toEqual([...CRAWL_ALLOWED_PATHS]);
    });

    it("points at the sitemap on the host that asked", async () => {
        expect((await robots()).sitemap).toBe("https://connexcrm.jp/sitemap.xml");
        requestFrom("www.connexcrm.jp");
        expect((await robots()).sitemap).toBe("https://www.connexcrm.jp/sitemap.xml");
    });

    it("refuses the whole site on a prelaunch host", async () => {
        vi.stubEnv("CONNEX_LANDING_PRELAUNCH_HOSTS", "connexcrm.jp");
        const result = await robots();
        expect(disallowedPaths(result.rules)).toEqual(["/"]);
        expect(result.sitemap).toBeUndefined();
    });

    it("refuses the whole site while the deployment is prelaunch", async () => {
        vi.stubEnv("CONNEX_LANDING_MODE", "prelaunch");
        expect(disallowedPaths((await robots()).rules)).toEqual(["/"]);
    });

    it("refuses the preview host even when the deployment serves the product", async () => {
        requestFrom("preview.connexcrm.jp");
        expect(disallowedPaths((await robots()).rules)).toEqual(["/"]);
    });

    it("refuses any host an operator adds to the noindex list", async () => {
        vi.stubEnv("CONNEX_NOINDEX_HOSTS", ".staging.connexcrm.jp");
        requestFrom("app.staging.connexcrm.jp");
        expect(disallowedPaths((await robots()).rules)).toEqual(["/"]);
    });
});

describe("sitemap", () => {
    it("lists the public routes and every documentation page the registry serves", () => {
        const articleCount = docsCategories.reduce((total, category) => total + category.articles.length, 0);
        expect(sitemapPaths()).toHaveLength(
            CRAWL_ALLOWED_PATHS.length + docsCategories.length + articleCount,
        );
        for (const category of docsCategories) {
            expect(sitemapPaths()).toContain(`/docs/${category.slug}`);
            for (const article of category.articles) {
                expect(sitemapPaths()).toContain(`/docs/${category.slug}/${article.slug}`);
            }
        }
    });

    it("carries no path the crawl policy closes", () => {
        const closed = sitemapPaths().filter((path) => path.startsWith("/auth") || path.startsWith("/settings"));
        expect(closed).toEqual([]);
    });

    it("addresses every entry to the host that asked", async () => {
        const entries = await sitemap();
        expect(entries).toHaveLength(sitemapPaths().length);
        expect(entries[0].url).toBe("https://connexcrm.jp/");
        expect(entries.every((entry) => entry.url.startsWith("https://connexcrm.jp/"))).toBe(true);
    });

    it("lists nothing on a host that must not be indexed", async () => {
        requestFrom("preview.connexcrm.jp");
        expect(await sitemap()).toEqual([]);
    });
});
