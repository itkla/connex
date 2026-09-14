import { PROTECTED_PREFIXES } from "@/app/lib/protectedRoutes";
import { hostMatchesPatterns } from "@/app/lib/requestHost";

/**
 * Sessionless prefixes that a crawler gains nothing from fetching: the onboarding hand-off redirects
 * before it renders, the design-system gallery answers 404 outside development, and `/api/` serves no
 * pages. Sessionless pages that do render — authentication, invitation acceptance, SSO linking, and the
 * one-time email links — are deliberately absent. Each of them carries a `noindex` directive, and a
 * crawler only honours `noindex` on a page it is allowed to fetch; closing them here would leave their
 * URLs indexable without a snippet, which is the duplicate the directive exists to prevent.
 */
const CLOSED_PREFIXES = ["/onboarding", "/design-system", "/api/"] as const;

/**
 * Hosts that publish the identical application but must never appear in an index. The preview
 * deployment serves the same build on the public internet, so it is blocked by default rather than by
 * configuration; `CONNEX_NOINDEX_HOSTS` adds to this list and cannot shorten it.
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
    ...CLOSED_PREFIXES,
];

/**
 * Whether a host must refuse crawlers outright. A preview host duplicates the primary host's pages
 * verbatim, which is worse than being absent from an index. Landing mode plays no part: the prelaunch
 * page is the product's public face and carries its own accurate title and description, so keeping a
 * prelaunch host out of search is an operator decision expressed through `CONNEX_NOINDEX_HOSTS`.
 * @param host raw `Host` header value
 * @param noIndexHosts additional hosts that must never be indexed
 * @returns whether `robots.txt` should disallow the whole site for this host
 */
export function shouldBlockCrawlers({
    host,
    noIndexHosts = process.env.CONNEX_NOINDEX_HOSTS,
}: {
    host: string | null;
    noIndexHosts?: string;
}): boolean {
    return hostMatchesPatterns(host, ALWAYS_UNINDEXED_HOSTS) || hostMatchesPatterns(host, noIndexHosts);
}
