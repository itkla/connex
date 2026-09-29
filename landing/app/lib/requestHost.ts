/**
 * Normalises a `Host` header into a comparable hostname: trimmed, lower-cased, port removed.
 * @param host raw `Host` header value
 * @returns the bare hostname, or an empty string when the header is absent
 */
export function normaliseHostname(host: string | null | undefined): string {
    return (host ?? "").trim().toLowerCase().replace(/:\d+$/, "");
}

/**
 * Matches a request host against a comma-separated pattern list. A pattern with a leading dot is a
 * suffix match covering the bare domain and every subdomain; any other pattern is an exact match.
 * @param host raw `Host` header value
 * @param patterns comma-separated hostname patterns
 * @returns whether the host matches one of the patterns
 */
export function hostMatchesPatterns(
    host: string | null | undefined,
    patterns: string | null | undefined,
): boolean {
    const normalised = normaliseHostname(host);
    if (!normalised || !patterns) return false;

    for (const raw of patterns.split(",")) {
        const pattern = normaliseHostname(raw);
        if (!pattern) continue;
        if (pattern.startsWith(".")) {
            if (normalised === pattern.slice(1) || normalised.endsWith(pattern)) return true;
            continue;
        }
        if (normalised === pattern) return true;
    }

    return false;
}

/**
 * Derives the origin the browser actually addressed, from the `Host` header and the proxy's
 * forwarded scheme. One frontend process serves several hostnames, so the origin is a property of
 * the request rather than of the deployment.
 * @param host raw `Host` header value
 * @param forwardedProtocol raw `x-forwarded-proto` header value
 * @param fallbackProtocol protocol to use when no forwarded scheme is trustworthy
 * @returns the browser-facing origin, or null when the `Host` header is absent or unusable
 */
export function browserFacingOrigin({
    host,
    forwardedProtocol,
    fallbackProtocol,
}: {
    host: string | null | undefined;
    forwardedProtocol: string | null | undefined;
    fallbackProtocol: string;
}): string | null {
    if (!host) return null;

    const protocol = forwardedProtocol === "http" || forwardedProtocol === "https"
        ? `${forwardedProtocol}:`
        : fallbackProtocol;

    try {
        const url = new URL(`${protocol}//${host}`);
        if (!url.username && !url.password && url.pathname === "/" && !url.search && !url.hash) {
            return url.origin;
        }
    } catch {}

    return null;
}

/**
 * Derives the browser-facing origin from a request's headers.
 * @param requestHeaders incoming request headers
 * @returns the origin, or null when the request carries no usable `Host` header
 */
export function requestOrigin(requestHeaders: Headers): string | null {
    return browserFacingOrigin({
        host: requestHeaders.get("host"),
        forwardedProtocol: requestHeaders.get("x-forwarded-proto"),
        fallbackProtocol: "https:",
    });
}
