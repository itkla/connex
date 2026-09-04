package ooo.klae.connex.backend.dto;

/**
 * One browser Content Security Policy violation reduced to bounded, non-identifying metadata.
 *
 * @param disposition whether the reporting policy was enforced or report-only
 * @param directive the effective directive that refused the resource
 * @param blockedHost the blocked origin, or the CSP keyword the browser reported instead
 * @param documentPath the path of the reporting document with query and fragment removed
 * @param sourceHost the origin of the script that triggered the violation, when reported
 * @param lineNumber the reported source line, or {@code -1} when absent
 * @param statusCode the reported document status code, or {@code -1} when absent
 * @param format the wire format the report arrived in
 */
public record CspViolationRecord(
        String disposition,
        String directive,
        String blockedHost,
        String documentPath,
        String sourceHost,
        int lineNumber,
        int statusCode,
        String format) {
}
