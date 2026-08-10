package ooo.klae.connex.backend.observability;

/**
 * Bounded application error report for the deployment-local reporter sink.
 *
 * @param source whether the report originated on the server or client
 * @param correlationId request correlation identifier
 * @param workspaceId resolved workspace identifier, or null
 * @param userId resolved user identifier, or null
 * @param message bounded error summary
 * @param detail bounded diagnostic detail
 * @param path request path without query parameters, redacted at construction
 */
public record ReportedError(
        Source source,
        String correlationId,
        Integer workspaceId,
        Integer userId,
        String message,
        String detail,
        String path) {

    /**
     * Redacts credential-bearing path segments so no reporter implementation and no caller can
     * publish a request path that carries an invite, unsubscribe or managed-object token.
     */
    public ReportedError {
        path = RequestPathRedactor.redact(path);
    }

    /**
     * Error origin.
     */
    public enum Source {
        SERVER,
        CLIENT
    }
}
