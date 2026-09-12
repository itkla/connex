/**
 * Which landing page a request should receive.
 *
 * `CONNEX_LANDING_MODE` sets the default for the deployment, and it is read from `process.env`, so
 * it is necessarily process-wide. One frontend process therefore cannot serve the launched product
 * page on one hostname and the prelaunch page on another — which is exactly what the apex and the
 * preview host need to do while the product is unreleased.
 *
 * Running a second frontend purely to hold a different environment would duplicate the release
 * runtime and the resident memory on a host whose filesystem headroom is already the constraint.
 * So the host decides instead: `CONNEX_LANDING_PRELAUNCH_HOSTS` lists the hostnames that must serve
 * prelaunch regardless of the deployment default.
 *
 * Matching ignores the port, is case-insensitive, and treats a leading dot as a suffix match, so
 * `.connexcrm.jp` covers the apex and every subdomain.
 */
export function resolvePreLaunch({
    host,
    mode = process.env.CONNEX_LANDING_MODE,
    preLaunchHosts = process.env.CONNEX_LANDING_PRELAUNCH_HOSTS,
}: {
    host: string | null;
    mode?: string;
    preLaunchHosts?: string;
}): boolean {
    const normalised = (host ?? "").trim().toLowerCase().replace(/:\d+$/, "");

    if (normalised && preLaunchHosts) {
        for (const raw of preLaunchHosts.split(",")) {
            const pattern = raw.trim().toLowerCase().replace(/:\d+$/, "");
            if (!pattern) continue;
            if (pattern.startsWith(".")) {
                if (normalised === pattern.slice(1) || normalised.endsWith(pattern)) return true;
                continue;
            }
            if (normalised === pattern) return true;
        }
    }

    return (mode ?? "prelaunch") === "prelaunch";
}
