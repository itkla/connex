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
 * @param content          parsed immutable snapshot
 * @param requiresApproval whether an active approval policy currently blocks direct finalization
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
    DocumentContent content,
    boolean requiresApproval,
    DocumentApprovalDto latestApproval
) {}
