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
 * @param path server-owned route template or {@code unknown}, normalized at construction
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
     * Maps the path into the closed route-template vocabulary before any reporter sees it.
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
