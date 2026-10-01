package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.DraftDocument;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.DealDocumentService;
import ooo.klae.connex.backend.services.DocumentTemplateService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Proposes a draft document on an owned deal from exactly one active workspace template name.
 *
 * <p>The framework pins the resolved template, checks target freshness and owner scope, and holds
 * the deal for update before generation. The delegate creates the draft and its audit row; this
 * tool neither finalizes nor sends it and records no inverse. Template names are workspace labels,
 * screened on the card; the rendered document title can contain merged record data and is never
 * projected to the model.
 *
 * <p>An inactive template is unresolved at preparation and on the card. If it is already inactive
 * when approval resolves the name, approval uses the existing 404 unavailable-template refusal.
 * After the framework's locks, this tool re-reads the pinned template and refuses deactivation
 * with the 409 pin-drift conflict before generation. Templates are not locked, so a deactivation
 * racing the generate call itself is not excluded.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantDraftDocumentWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "draft_document";
    private static final String TEMPLATE = "template";
    private final DealDocumentService documentService;
    private final DocumentTemplateService templateService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.CONFIRM;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return DraftDocument.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("deal");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("deal.document");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.DEAL_UPDATE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.RECORD_UPDATE);
    }

    @Override
    public boolean requiresOwnedTarget() {
        return true;
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        if (!(request instanceof DraftDocument draft)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        DocumentTemplate template = requestedTemplate(draft.template(), templateService.getAll());
        if (template == null) {
            throw new ResourceNotFoundException("Document template is unavailable or ambiguous");
        }
        return new Resolution(TEMPLATE, template.getId(), template.getName());
    }

    @Override
    public Outcome apply(Execution execution) {
        Resolution resolution = execution.resolution();
        if (resolution == null) {
            throw new ConflictException("Prepared document template is unavailable");
        }
        DocumentTemplate template = templateService.getById(resolution.id());
        if (!template.isActive()) {
            throw new ConflictException("Assistant proposal target changed");
        }
        DealDocumentDto document = documentService.generate(
                execution.row().target().id(), resolution.id());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "document");
        outcome.put("type", document.type());
        outcome.put("title", document.title());
        outcome.put("version", document.version());
        return new Outcome(outcome, null,
                new ReadBack("templateId", resolution.id(), document.templateId()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> requiredRequestText() {
        return Set.of(TEMPLATE);
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.TEMPLATES, ReviewInput.DOCUMENTS);
    }

    @Override
    public Diff diff(Review review) {
        DocumentTemplate template = reviewedTemplate(review);
        if (template == null) {
            return new Diff("document", null, false, null, DiffState.UNRESOLVED);
        }
        Integer version = review.documentVersions().get(template.getId());
        return SpecialCareTextScreen.screen(template.getName()).excluded() ? null
                : new Diff("document", version == null ? null : version.toString(), false,
                        template.getName(), DiffState.CHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        DocumentTemplate template = review.detailsReadable() ? reviewedTemplate(review) : null;
        return template == null ? "Draft a deal document" : "Draft document from: " + template.getName();
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Document drafted";
    }

    /** Only server scalars reach the model; the member-only title may contain merged record data. */
    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "type");
        JsonNode version = storedOutcome.get("version");
        if (version != null && version.isIntegralNumber()) {
            result.put("version", version.asInt());
        }
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("type", "title");
    }

    private static DocumentTemplate reviewedTemplate(Review review) {
        DocumentTemplate matched = requestedTemplate(review.requestText(TEMPLATE), review.templates());
        if (matched == null || review.pinnedPrincipalIds() == null) {
            return matched;
        }
        return review.pinnedPrincipalIds().isEmpty()
                && Integer.valueOf(matched.getId()).equals(review.pinnedResolutionId())
                ? matched : null;
    }

    private static DocumentTemplate requestedTemplate(String requested, List<DocumentTemplate> templates) {
        if (requested == null) {
            return null;
        }
        List<DocumentTemplate> matches = templates.stream()
                .filter(DocumentTemplate::isActive)
                .filter(template -> template.getName() != null
                        && template.getName().equalsIgnoreCase(requested))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
}
