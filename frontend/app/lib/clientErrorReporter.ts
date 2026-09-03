import { reportClientError } from '@/app/lib/api';

const MAX_MESSAGE_LENGTH = 1000;
const MAX_STACK_LENGTH = 8000;
const MAX_PATH_LENGTH = 300;
const MAX_REPORTS_PER_PAGE_LOAD = 5;
const BEARER_PATH_PATTERNS = [
    /\/document-acceptance\/[^/?#\s]+/g,
    /\/unsubscribe\/[^/?#\s]+/g,
    /\/invite\/[^/?#\s]+/g,
    /\/invite-link\/[^/?#\s]+/g,
    /\/sso\/link\/[^/?#\s]+/g,
] as const;

const reportedKeys = new Set<string>();
let reportCount = 0;

/**
 * Builds the stable de-duplication key for an error, preferring the server digest
 * (identical server errors share one digest) over the client message.
 * @param error the boundary error, including the optional Next.js digest
 * @returns a key that identifies this error for the lifetime of the page load
 */
function dedupeKey(error: Error & { digest?: string }): string {
    return error.digest ?? `${error.name}:${error.message}`;
}

/** Removes a path-embedded credential before the path can reach persistent diagnostics. */
export function redactClientErrorPath(pathname: string): string {
    return redactBearerPaths(pathname);
}

function redactBearerPaths(value: string): string {
    return BEARER_PATH_PATTERNS.reduce(
        (redacted, pattern) => redacted.replace(pattern, (match) =>
            `${match.slice(0, match.lastIndexOf('/'))}/[token]`),
        value,
    );
}

/** Clones a boundary error with every path-embedded credential removed from console-safe fields. */
export function redactClientError(
    error: Error & { digest?: string },
): Error & { digest?: string } {
    const redacted: Error & { digest?: string } = new Error(redactBearerPaths(error.message));
    redacted.name = redactBearerPaths(error.name);
    redacted.stack = error.stack ? redactBearerPaths(error.stack) : undefined;
    redacted.digest = error.digest ? redactBearerPaths(error.digest) : undefined;
    return redacted;
}

/** Logs and persists one boundary error only after applying the shared credential redactor. */
export function reportBoundaryErrorWithConsole(
    error: Error & { digest?: string },
): void {
    const redacted = redactClientError(error);
    console.error(redacted);
    reportBoundaryError(redacted);
}

/**
 * Reports an error caught by a React error boundary to the backend error sink.
 * Fire-and-forget by design: never throws, de-duplicates repeated boundary
 * re-renders of the same error, caps the number of reports per page load, and
 * truncates every field client-side so a huge stack cannot trip the server's
 * request-size limit. Sends only the error itself plus a credential-redacted
 * pathname, with no query string or user input.
 * @param error the error forwarded by the boundary, including the server digest
 */
export function reportBoundaryError(error: Error & { digest?: string }): void {
    if (typeof window === 'undefined') {
        return;
    }
    const key = dedupeKey(error);
    if (reportedKeys.has(key) || reportCount >= MAX_REPORTS_PER_PAGE_LOAD) {
        return;
    }
    reportedKeys.add(key);
    reportCount += 1;
    const message = redactBearerPaths(
        error.message || error.name || 'Unknown client error',
    ).slice(0, MAX_MESSAGE_LENGTH);
    void reportClientError({
        digest: error.digest ? redactBearerPaths(error.digest).slice(0, 128) : null,
        message,
        stack: error.stack
            ? redactBearerPaths(error.stack).slice(0, MAX_STACK_LENGTH)
            : null,
        path: redactClientErrorPath(window.location.pathname).slice(0, MAX_PATH_LENGTH),
    }).catch(() => undefined);
}
