/**
 * This deployment does not serve `/docs`.
 *
 * The product application's `app/lib/deploymentSurface.ts` answers `true`. Keeping the same export
 * lets the shared landing and legal components be copied from `frontend/` unchanged while omitting
 * links to routes that do not exist here.
 */
export const DOCS_AVAILABLE: boolean = false;
