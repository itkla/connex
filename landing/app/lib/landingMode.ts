/**
 * This deployment exists only to serve the prelaunch page, so every host is a prelaunch host.
 *
 * The product application resolves the mode per request from `CONNEX_LANDING_MODE` and
 * `CONNEX_LANDING_PRELAUNCH_HOSTS`. Keeping the same export here lets the shared legal and not-found
 * pages be copied from `frontend/` unchanged.
 * @param _request the request host, which does not affect the result here
 * @returns always `true`
 */
export function resolvePreLaunch(_request: { host: string | null }): boolean {
    return true;
}
