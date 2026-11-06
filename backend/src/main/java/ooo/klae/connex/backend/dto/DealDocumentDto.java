package ooo.klae.connex.backend.dto;

/**
 * Client-facing generated document. {@code content} is the parsed immutable snapshot the client
 * renders (to PDF or preview); it is never accepted from the client.
 *
 * @param id               document id
 * @param dealId           parent deal
 * @param templateId       source template (nullable)
 * @param type             document type
 * @param locale           document locale
 * @param status           draft | pending_approval | approved | final | superseded
 * @param version          monotonic per deal
 * @param title            resolved title snapshot
 * @param currency         document currency
 * @param generatedAt      when generated
 * @param createdBy        generator user id
 * @param content          parsed immutable snapshot
 * @param requiresApproval whether an active approval policy currently blocks direct finalization
 * @param approvalConfigurationError localized refusal code for this draft, without hiding the snapshot
 * @param latestApproval   most recent approval request on this document (nullable)
 */
public record DealDocumentDto(
    int id,
    int dealId,
    Integer templateId,
    String type,
    String locale,
    String status,
    int version,
    String title,
    String currency,
    String generatedAt,
    Integer createdBy,
    DocumentContent content,
    boolean requiresApproval,
    DocumentApprovalDto latestApproval,
    String approvalConfigurationError
) {
    /** Retains the document snapshot construction contract for callers without a refusal. */
    public DealDocumentDto(int id, int dealId, Integer templateId, String type, String locale,
            String status, int version, String title, String currency, String generatedAt,
            Integer createdBy, DocumentContent content, boolean requiresApproval,
            DocumentApprovalDto latestApproval) {
        this(id, dealId, templateId, type, locale, status, version, title, currency, generatedAt,
            createdBy, content, requiresApproval, latestApproval, null);
    }
}
