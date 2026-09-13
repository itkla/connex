import { resolvePreLaunch } from "@/app/lib/landingMode";
import { PROTECTED_PREFIXES } from "@/app/lib/protectedRoutes";
import { hostMatchesPatterns } from "@/app/lib/requestHost";

/**
 * Sessionless routes that answer without a workspace but hold nothing a search engine should carry:
 * authentication, invitation acceptance, the onboarding hand-off, one-time email links, and the
 * internal design-system gallery. `/auth/` matters most — route protection appends
 * `?redirect=<path>` to the login page, so every crawled authenticated path would otherwise mint an
 * indexable login duplicate.
 */
const UNINDEXED_PREFIXES = [
    "/auth/",
    "/invite",
    "/invite-link",
    "/sso/",
    "/onboarding",
    "/document-acceptance",
    "/unsubscribe",
    "/design-system",
    "/api/",
] as const;

/**
 * Hosts that publish the identical application but must never appear in an index, no matter what
 * the deployment's landing mode says. The preview deployment serves the same build on the public
 * internet, so it is blocked by default rather than by configuration; `CONNEX_NOINDEX_HOSTS` adds
 * to this list and cannot shorten it.
 */
const ALWAYS_UNINDEXED_HOSTS = "preview.connexcrm.jp";

/** Public routes a crawler is welcome to fetch: the landing page, the documentation, the legal set. */
export const CRAWL_ALLOWED_PATHS = [
    "/",
    "/docs",
    "/privacy",
    "/legal",
    "/disclosure",
    "/tokushoho",
] as const;

/**
 * Path prefixes closed to crawlers. The authenticated application contributes its prefixes through
 * {@link PROTECTED_PREFIXES}, so a new authenticated area cannot be added to route protection while
 * staying open in `robots.txt`.
 */
export const CRAWL_DISALLOWED_PATHS: readonly string[] = [
    ...PROTECTED_PREFIXES,
    ...UNINDEXED_PREFIXES,
];

/**
 * Whether a host must refuse crawlers outright. A prelaunch host advertises a product that cannot
 * be visited, and a preview host duplicates the primary host's pages verbatim; both are worse than
 * absent from an index.
 * @param host raw `Host` header value
 * @param mode deployment landing mode
 * @param preLaunchHosts hosts forced to serve the prelaunch page
 * @param noIndexHosts additional hosts that must never be indexed
 * @returns whether `robots.txt` should disallow the whole site for this host
 */
export function shouldBlockCrawlers({
    host,
    mode,
    preLaunchHosts,
    noIndexHosts = process.env.CONNEX_NOINDEX_HOSTS,
}: {
    host: string | null;
    mode?: string;
    preLaunchHosts?: string;
    noIndexHosts?: string;
}): boolean {
    if (hostMatchesPatterns(host, ALWAYS_UNINDEXED_HOSTS)) return true;
    if (hostMatchesPatterns(host, noIndexHosts)) return true;
    return resolvePreLaunch({ host, mode, preLaunchHosts });
}
