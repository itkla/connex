package ooo.klae.connex.backend.dto;

/**
 * Redacted mail transport test outcome with content-free advisory DNS metadata.
 */
public record MailDiagnosticTestDto(
        String correlationId,
        Sender sender,
        Transport transport,
        Dns dns) {

    /** Redacted effective sender identity. */
    public record Sender(String address, String displayName) {
    }

    /** Effective transport mode and stable delivery outcome. */
    public record Transport(
            String mode,
            String host,
            Integer port,
            String outcome,
            String errorCode) {
    }

    /** Advisory DNS posture that never changes the transport outcome. */
    public record Dns(
            boolean advisory,
            String domain,
            DnsRecord spf,
            DnsRecord dkim,
            DnsRecord dmarc) {
    }

    /** Content-free result of one bounded TXT lookup. */
    public record DnsRecord(String queryName, String status) {
    }
}
