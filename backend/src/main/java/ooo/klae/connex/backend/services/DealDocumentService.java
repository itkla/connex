package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Generates and manages commercial documents on a deal. A generated document is an immutable,
 * versioned snapshot: merge tokens are resolved and the deal's line items + totals frozen at
 * generation time, so a document never changes when the deal, catalog, or template later change.
 * Only status transitions after creation. Mutations require {@link Permission#DEAL_UPDATE}.
 */
@Service
@RequiredArgsConstructor
public class DealDocumentService {
    private final DealDocumentMapper documentMapper;
    private final DocumentTemplateService templateService;
    private final DealMapper dealMapper;
    private final CompanyMapper companyMapper;
    private final UserMapper userMapper;
    private final DealLineItemService lineItemService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private static final Set<String> STATUSES = Set.of("draft", "final", "superseded");
    private static final int MAX_VERSION_ATTEMPTS = 5;

    /** Documents on a deal, newest version first. */
    public List<DealDocumentDto> getForDeal(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        return documentMapper.getByDealId(workspaceId, dealId).stream().map(this::toDto).toList();
    }

    /** A single document on a deal. */
    public DealDocumentDto getOne(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        return toDto(requireDocument(workspaceId, dealId, documentId));
    }

    /** Generates a new immutable document version on a deal from a template. */
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealDocumentDto generate(int dealId, int templateId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DocumentTemplate template = templateService.require(workspaceId, templateId);
        String currency = deal.getCurrency() == null || deal.getCurrency().isBlank() ? "USD" : deal.getCurrency();

        Company company = deal.getCompanyId() == null ? null : companyMapper.getCompanyById(workspaceId, deal.getCompanyId());
        User owner = deal.getOwnerId() == null ? null : userMapper.getUserById(deal.getOwnerId());
        Workspace workspace = workspaceService.getCurrentWorkspace();
        DealLineItemsResponse lines = lineItemService.getForDeal(dealId);

        Map<String, String> tokens = new HashMap<>();
        tokens.put("workspace.name", workspace == null ? "" : nz(workspace.getName()));
        tokens.put("company.name", company == null ? "" : nz(company.getName()));
        tokens.put("company.address", company == null ? "" : nz(company.getAddress()));
        tokens.put("deal.name", nz(deal.getName()));
        tokens.put("deal.currency", currency);
        tokens.put("owner.name", owner == null ? "" : nz(owner.getDisplayName()));
        tokens.put("date", LocalDateTime.now().toLocalDate().toString());
        tokens.put("total", lines.totals() == null || lines.totals().grandTotal() == null
            ? "" : currency + " " + lines.totals().grandTotal().toPlainString());

        DocumentContent.Sections sections = new DocumentContent.Sections(
            resolve(template.getTitle(), tokens),
            resolve(template.getIntro(), tokens),
            resolve(template.getTerms(), tokens),
            resolve(template.getFooter(), tokens));
        String resolvedTitle = sections.title() == null || sections.title().isBlank()
            ? template.getName() : sections.title();

        DocumentContent content = new DocumentContent(
            LocalDateTime.now().toString(),
            new DocumentContent.PartyRef(tokens.get("workspace.name"), null),
            company == null ? null : new DocumentContent.PartyRef(nz(company.getName()), company.getAddress()),
            owner == null ? null : new DocumentContent.PartyRef(nz(owner.getDisplayName()), null),
            new DocumentContent.DealRef(nz(deal.getName()), currency),
            sections,
            lines.items(),
            lines.totals());

        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspaceId);
        document.setDealId(dealId);
        document.setTemplateId(templateId);
        document.setType(template.getType());
        document.setLocale(template.getLocale());
        document.setStatus("draft");
        document.setTitle(resolvedTitle);
        document.setContent(objectMapper.writeValueAsString(content));
        document.setCurrency(currency);
        document.setCreatedBy(workspaceService.getCurrentUserId());
        insertNextVersion(workspaceId, dealId, document);

        auditService.record("deal_document.generate", "deal", dealId, deal.getName(),
            "Generated " + template.getType() + " document v" + document.getVersion() + " on " + deal.getName(), null);
        return toDto(requireDocument(workspaceId, dealId, document.getId()));
    }

    /** Transitions a document's status. draft → final|superseded; final → superseded. */
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealDocumentDto updateStatus(int dealId, int documentId, String status) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = requireDocument(workspaceId, dealId, documentId);
        if (status == null || !STATUSES.contains(status)) {
            throw new BadRequestException("status must be one of: draft, final, superseded");
        }
        if (document.getStatus().equals(status)) {
            return toDto(document);
        }
        if (!isAllowedTransition(document.getStatus(), status)) {
            throw new BadRequestException("Cannot change document status from " + document.getStatus() + " to " + status);
        }
        documentMapper.updateStatus(workspaceId, documentId, status);
        auditService.record("deal_document.status", "deal", dealId, deal.getName(),
            "Document v" + document.getVersion() + " status " + document.getStatus() + " → " + status, null);
        return toDto(requireDocument(workspaceId, dealId, documentId));
    }

    /** Deletes a draft document; finalized documents cannot be deleted. */
    @RequirePermission(Permission.DEAL_UPDATE)
    public void delete(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = requireDocument(workspaceId, dealId, documentId);
        if (!"draft".equals(document.getStatus())) {
            throw new BadRequestException("Only draft documents can be deleted");
        }
        documentMapper.delete(workspaceId, documentId);
        auditService.record("deal_document.delete", "deal", dealId, deal.getName(),
            "Deleted draft document v" + document.getVersion() + " on " + deal.getName(), null);
    }

    private boolean isAllowedTransition(String from, String to) {
        return switch (from) {
            case "draft" -> to.equals("final") || to.equals("superseded");
            case "final" -> to.equals("superseded");
            default -> false;
        };
    }

    /**
     * Inserts the document at the next per-deal version, retrying on the unique-version constraint so
     * two concurrent generations on the same deal can never persist a duplicate or gapped version.
     */
    private void insertNextVersion(int workspaceId, int dealId, DealDocument document) {
        for (int attempt = 0; attempt < MAX_VERSION_ATTEMPTS; attempt++) {
            Integer max = documentMapper.maxVersion(workspaceId, dealId);
            document.setVersion(max == null ? 1 : max + 1);
            try {
                documentMapper.insert(document);
                return;
            } catch (DuplicateKeyException contended) {
                if (attempt == MAX_VERSION_ATTEMPTS - 1) {
                    throw contended;
                }
            }
        }
    }

    private String resolve(String template, Map<String, String> tokens) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private DealDocumentDto toDto(DealDocument d) {
        DocumentContent content = d.getContent() == null ? null
            : objectMapper.readValue(d.getContent(), DocumentContent.class);
        return new DealDocumentDto(d.getId(), d.getDealId(), d.getTemplateId(), d.getType(), d.getLocale(),
            d.getStatus(), d.getVersion(), d.getTitle(), d.getCurrency(), d.getGeneratedAt(), content);
    }

    private String nz(String value) {
        return value == null ? "" : value;
    }

    private Deal requireDeal(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return deal;
    }

    private DealDocument requireDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.getById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }
}
