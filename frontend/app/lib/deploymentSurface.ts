/**
 * Whether this deployment serves the documentation under `/docs`.
 *
 * The product application always does, in both landing modes. The prelaunch Worker in `landing/`
 * vendors the shared landing and legal components but not the documentation, and replaces this
 * module with one that answers `false`, so those components drop links that would 404 there.
 */
export const DOCS_AVAILABLE: boolean = true;
