package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Bounded generated-document projection shared by the cross-deal document index and the
 * global-search document group.
 *
 * <p>Excludes the immutable resolved content snapshot the full {@link DealDocumentDto} carries, and
 * carries the parent deal's name and owner so a list row can name its deal and be scoped by
 * ownership without a second round trip. A generated document has no owner of its own — ownership
 * is the parent deal's — so {@code dealOwnerId} is the ownership column and {@code createdBy} is
 * the member who generated the version.
 *
 * @param id the document id
 * @param dealId the parent deal id
 * @param dealName the parent deal name
 * @param dealOwnerId the parent deal's owner member id, or null when the deal is unassigned
 * @param type the document type
 * @param status the document status
 * @param version the monotonic per-deal version
 * @param title the resolved title snapshot
 * @param currency the document currency
 * @param createdBy the member who generated the document, or null when that member has left
 * @param generatedAt when the document was generated
 * @param createdAt when the document row was created
 * @param updatedAt when the document last changed
 */
public record GeneratedDocumentSummaryDto(
        int id,
        int dealId,
        String dealName,
        Integer dealOwnerId,
        String type,
        String status,
        int version,
        String title,
        String currency,
        Integer createdBy,
        LocalDateTime generatedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
