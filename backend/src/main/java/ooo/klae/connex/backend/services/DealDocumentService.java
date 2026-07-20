package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemsResponse;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Generates and manages commercial documents on a deal. A generated document is an immutable,
 * versioned snapshot: merge tokens are resolved and the deal's line items + totals frozen at
 * generation time, so a document never changes when the deal, catalog, or template later change.
 * Only status transitions after creation. Mutations require {@link Permission#DEAL_UPDATE}.
 *
 * <p>The status endpoint only accepts {@code draft|final|superseded} targets; the approval states
 * ({@code pending_approval}, {@code approved}) are owned by {@code DocumentApprovalService}, and
 * {@code draft → final} is refused while an active approval policy matches the document — that
 * pairing keeps the approval gate non-bypassable through UI or API.
 */
@Service
@RequiredArgsConstructor
public class DealDocumentService {
    private final DealDocumentMapper documentMapper;
    private final DocumentApprovalMapper approvalMapper;
    private final DocumentApprovalService approvalService;
    private final DocumentTemplateService templateService;
    private final ApprovalPolicyService policyService;
    private final DealMapper dealMapper;
    private final CompanyMapper companyMapper;
    private final UserMapper userMapper;
    private final DealLineItemService lineItemService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private static final Set<String> CLIENT_TARGET_STATUSES = Set.of("draft", "final", "superseded");
    private static final int MAX_VERSION_ATTEMPTS = 5;
    private static final int MAX_BODY_DEPTH = 50;

    /** Documents on a deal, newest version first. */
    public List<DealDocumentDto> getForDeal(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        List<ApprovalPolicy> policies = policyService.activePolicies(workspaceId);
        Map<Integer, DocumentApproval> latest = approvalMapper.getByDealId(workspaceId, dealId).stream()
            .collect(Collectors.toMap(DocumentApproval::getDocumentId, Function.identity(), (a, b) -> a));
        return documentMapper.getByDealId(workspaceId, dealId).stream()
            .map(document -> toDto(document, policies, latest.get(document.getId())))
            .toList();
    }

    /** A single document on a deal. */
    public DealDocumentDto getOne(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        return enrich(workspaceId, requireDocument(workspaceId, dealId, documentId));
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
            resolveBody(template.getBody(), tokens),
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
        return enrich(workspaceId, requireDocument(workspaceId, dealId, document.getId()));
    }

    /**
     * Transitions a document's status on the client's behalf. draft → final|superseded,
     * approved → final|superseded, pending_approval → superseded (withdrawing the pending
     * request), final → superseded. Finalizing a draft is refused while an active approval
     * policy matches; the approval flow is the only path to {@code final} for such documents.
     */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DealDocumentDto updateStatus(int dealId, int documentId, String status) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        if (status == null || !CLIENT_TARGET_STATUSES.contains(status)) {
            throw new BadRequestException("status must be one of: draft, final, superseded");
        }
        List<ApprovalPolicy> policies = policyService.activePolicies(workspaceId);
        if (document.getStatus().equals(status)) {
            return enrichWith(workspaceId, document, policies);
        }
        if (!isAllowedTransition(document.getStatus(), status)) {
            throw new BadRequestException("Cannot change document status from " + document.getStatus() + " to " + status);
        }
        if ("final".equals(status) && "draft".equals(document.getStatus())) {
            requireNoMatchingPolicy(policies, document);
        }
        if ("pending_approval".equals(document.getStatus())) {
            approvalService.cancelPendingOnSupersede(workspaceId, deal, document);
        }
        documentMapper.updateStatus(workspaceId, documentId, status);
        auditService.record("deal_document.status", "deal", dealId, deal.getName(),
            "Document v" + document.getVersion() + " status " + document.getStatus() + " → " + status, null);
        return enrichWith(workspaceId, requireDocument(workspaceId, dealId, documentId), policies);
    }

    /** Deletes a draft document; finalized documents cannot be deleted. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public void delete(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
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
            case "pending_approval" -> to.equals("superseded");
            case "approved" -> to.equals("final") || to.equals("superseded");
            case "final" -> to.equals("superseded");
            default -> false;
        };
    }

    private void requireNoMatchingPolicy(List<ApprovalPolicy> policies, DealDocument document) {
        ApprovalPolicy match = policyService.firstMatch(policies, document, parseContent(document));
        if (match != null) {
            throw new BadRequestException(
                "This document requires approval under policy \"" + match.getName() + "\" before it can be finalized");
        }
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

    /**
     * Resolves a template body (ProseMirror/Tiptap JSON) into a frozen snapshot tree: {@code {{tokens}}}
     * inside text runs are substituted and inline {@code mergeToken} nodes become plain, properly-escaped
     * text. Rebuilding the tree through Jackson (rather than string-replacing the serialized JSON) keeps a
     * token value that contains quotes or braces from corrupting the document or injecting structure.
     */
    private JsonNode resolveBody(String templateBody, Map<String, String> tokens) {
        if (templateBody == null || templateBody.isBlank()) return null;
        JsonNode root;
        try {
            root = objectMapper.readTree(templateBody);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Document template body is not valid document content");
        }
        return resolveNode(root, tokens, 0);
    }

    private JsonNode resolveNode(JsonNode node, Map<String, String> tokens, int depth) {
        if (depth > MAX_BODY_DEPTH) {
            throw new BadRequestException("Document template body is nested too deeply");
        }
        if (node == null || !node.isObject()) {
            return node;
        }
        JsonNode typeNode = node.get("type");
        String type = typeNode != null && typeNode.isString() ? typeNode.asString() : null;
        ObjectNode copy = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if ("text".equals(type) && "text".equals(key) && value.isString()) {
                copy.put("text", resolve(value.asString(), tokens));
            } else if ("content".equals(key) && value.isArray()) {
                copy.set("content", resolveContent(value, tokens, depth));
            } else {
                copy.set(key, value);
            }
        }
        return copy;
    }

    private ArrayNode resolveContent(JsonNode content, Map<String, String> tokens, int depth) {
        ArrayNode out = objectMapper.createArrayNode();
        for (JsonNode child : content) {
            JsonNode typeNode = child.isObject() ? child.get("type") : null;
            String type = typeNode != null && typeNode.isString() ? typeNode.asString() : null;
            if ("mergeToken".equals(type)) {
                JsonNode key = child.path("attrs").path("token");
                String value = key.isString() ? tokens.getOrDefault(key.asString(), "") : "";
                if (!value.isEmpty()) {
                    ObjectNode text = objectMapper.createObjectNode();
                    text.put("type", "text");
                    text.put("text", value);
                    out.add(text);
                }
            } else {
                out.add(resolveNode(child, tokens, depth + 1));
            }
        }
        return out;
    }

    private DealDocumentDto enrich(int workspaceId, DealDocument document) {
        return enrichWith(workspaceId, document, policyService.activePolicies(workspaceId));
    }

    private DealDocumentDto enrichWith(int workspaceId, DealDocument document, List<ApprovalPolicy> policies) {
        List<DocumentApproval> approvals = approvalMapper.getByDocumentId(workspaceId, document.getId());
        return toDto(document, policies, approvals.isEmpty() ? null : approvals.getFirst());
    }

    private DealDocumentDto toDto(DealDocument d, List<ApprovalPolicy> policies, DocumentApproval latestApproval) {
        DocumentContent content = parseContent(d);
        boolean requiresApproval = !"final".equals(d.getStatus()) && !"superseded".equals(d.getStatus())
            && !"approved".equals(d.getStatus())
            && policyService.firstMatch(policies, d, content) != null;
        return new DealDocumentDto(d.getId(), d.getDealId(), d.getTemplateId(), d.getType(), d.getLocale(),
            d.getStatus(), d.getVersion(), d.getTitle(), d.getCurrency(), d.getGeneratedAt(), content,
            requiresApproval, DocumentApprovalDto.from(latestApproval));
    }

    private DocumentContent parseContent(DealDocument d) {
        return d.getContent() == null ? null : objectMapper.readValue(d.getContent(), DocumentContent.class);
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

    private DealDocument lockDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.lockById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }
}
