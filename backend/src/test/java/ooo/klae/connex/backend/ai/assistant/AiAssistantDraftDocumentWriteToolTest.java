package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.DiffState;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Review;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Target;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.DraftDocument;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/** Pins document resolution, ownership, approval and disclosure at the write framework boundary. */
class AiAssistantDraftDocumentWriteToolTest extends AbstractAiAssistantWriteToolTest {
    static final String ARGUMENTS = "{\"handle\":\"r1\",\"template\":\"quote\"}";

    @Test
    void pinsOneCaseInsensitiveTemplateAndGeneratesOnlyAfterApproval() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite prepared = propose(service, "draft_document", ARGUMENTS, "deal", 44);
        JsonNode stored = objectMapper.readTree(prepared.argumentsJson());
        assertEquals(6, stored.path("resolution").path("id").asInt());
        assertEquals("template", stored.path("resolution").path("field").asString());
        assertEquals(0, stored.path("principals").size());
        verifyNoInteractions(documentService);

        assertEquals("executed", service.approve(TURN.sessionId(), TOOL_CALL_ID).status());

        verify(documentService).generate(44, 6);
        JsonNode result = objectMapper.readTree(capturedExecutedResult());
        assertEquals("{\"status\":\"executed\",\"recordType\":\"document\",\"type\":\"quote\","
                + "\"title\":\"Acme renewal quote\",\"version\":1}",
                objectMapper.writeValueAsString(result.path("outcome")));
        assertFalse(result.has("undo"));
        assertFalse(result.has("verification"));
        assertFalse(draftDocumentTool().inverseAvailable());
    }

    @Test
    void aTemplateNameSwapRefusesApprovalEvenWhileTheDealIsUnchanged() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        when(templateService.getAll()).thenReturn(List.of(template(6, "Retired"), template(9, "Quote")));

        ConflictException refused = assertThrows(ConflictException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        verifyNoInteractions(documentService);
    }

    @Test
    void missingOrAmbiguousTemplatesRefuseRecoverablyBeforeAProposal() throws Exception {
        when(dealService.getDealById(44)).thenReturn(deal(5));
        AiAssistantWriteToolService service = service();
        for (List<DocumentTemplate> templates : List.of(
                List.<DocumentTemplate>of(), List.of(template(6, "Quote"), template(9, "QUOTE")))) {
            when(templateService.getAll()).thenReturn(templates);
            AiAssistantLoopException refused = assertThrows(AiAssistantLoopException.class,
                    () -> propose(service, "draft_document", ARGUMENTS, "deal", 44));
            assertEquals("unresolved_reference", refused.detailReason());
            assertTrue(refused.recoverable());
        }
        verifyNoInteractions(documentService);
    }

    @Test
    void anInitiallyInactiveTemplateRefusesRecoverablyBeforeAProposal() {
        when(dealService.getDealById(44)).thenReturn(deal(5));
        DocumentTemplate inactive = template(6, "Quote");
        inactive.setActive(false);
        when(templateService.getAll()).thenReturn(List.of(inactive));
        AiAssistantWriteToolService service = service();

        AiAssistantLoopException refused = assertThrows(AiAssistantLoopException.class,
                () -> propose(service, "draft_document", ARGUMENTS, "deal", 44));

        assertEquals("unresolved_reference", refused.detailReason());
        assertTrue(refused.recoverable());
        verifyNoInteractions(documentService);
    }

    @Test
    void aTemplateDeactivatedBeforeApprovalRefusesAsUnavailableAndGeneratesNothing() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        DocumentTemplate inactive = template(6, "Quote");
        inactive.setActive(false);
        when(templateService.getAll()).thenReturn(List.of(inactive));
        when(templateService.getById(6)).thenReturn(inactive);

        ResourceNotFoundException refused = assertThrows(ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));

        assertEquals("Document template is unavailable or ambiguous", refused.getMessage());
        verifyNoInteractions(documentService);
    }

    @Test
    void aTemplateDeactivatedAfterResolutionRefusesWithPinDriftConflictAfterLocking() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        DocumentTemplate pinned = template(6, "Quote");
        when(templateService.getById(6)).thenReturn(pinned);
        Deal target = deal(5);
        target.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(dealService.lockDealForUpdate(44)).thenAnswer(invocation -> {
            pinned.setActive(false);
            return target;
        });

        ConflictException refused = assertThrows(ConflictException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));

        assertEquals("Assistant proposal target changed", refused.getMessage());
        InOrder order = inOrder(dealService, templateService);
        order.verify(dealService).lockDealForUpdate(44);
        order.verify(templateService).getById(6);
        verifyNoInteractions(documentService);
    }

    @Test
    void aDealUnavailableToTheOwnedGetterRefusesBeforeTemplateResolution() {
        when(dealService.getDealById(44)).thenThrow(new ResourceNotFoundException("Deal not found"));
        AiAssistantWriteToolService service = service();

        AiAssistantLoopException refused = assertThrows(AiAssistantLoopException.class,
                () -> propose(service, "draft_document", ARGUMENTS, "deal", 44));

        assertEquals("unresolved_reference", refused.detailReason());
        assertTrue(refused.recoverable());
        verifyNoInteractions(templateService, documentService);
    }

    @Test
    void revokedDealUpdateCannotGenerate() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        when(authority.effectiveFor(TURN.userId())).thenReturn(Set.of(Permission.AI_USE));
        assertThrows(ForbiddenException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verifyNoInteractions(documentService);
    }

    @Test
    void aDealWrittenAfterTheProposalCannotGenerate() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        Deal changed = deal(5);
        changed.setUpdatedAt("2026-03-06 14:59:01.000000");
        when(dealService.lockDealForUpdate(44)).thenReturn(changed);
        assertThrows(ConflictException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verifyNoInteractions(documentService);
    }

    @Test
    void theFrameworkRechecksTargetAccessAfterLocking() throws Exception {
        stubDraft();
        AiAssistantWriteToolService service = service();
        propose(service, "draft_document", ARGUMENTS, "deal", 44);
        when(dealService.getDealById(44)).thenThrow(new ResourceNotFoundException("Deal not found"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verify(dealService).lockDealForUpdate(44);
        verifyNoInteractions(documentService);
    }

    @Test
    void theCardNamesOnlyThePinnedTemplateAndWithholdsSpecialCareNames() throws Exception {
        AiAssistantDraftDocumentWriteTool tool = draftDocumentTool();
        Review ready = review("Quote", 6, List.of(template(6, "Quote")));
        assertEquals("document", tool.diff(ready).field());
        assertNull(tool.diff(ready).currentValue());
        assertEquals("Quote", tool.diff(ready).proposedValue());
        assertEquals(DiffState.CHANGED, tool.diff(ready).state());
        assertEquals("Draft document from: Quote", tool.requestSummary(ready));
        Review drifted = review("Quote", 6, List.of(template(9, "Quote")));
        assertEquals(DiffState.UNRESOLVED, tool.diff(drifted).state());
        assertEquals("Draft a deal document", tool.requestSummary(drifted));
        assertNull(tool.diff(review("Diagnosis pending", 6, List.of(template(6, "Diagnosis pending")))));
        verifyNoInteractions(templateService);
    }

    @Test
    void theModelOutcomeNeverReceivesTheRenderedTitleOrTemplateIdentifier() throws Exception {
        JsonNode stored = objectMapper.readTree("{\"recordType\":\"document\",\"type\":\"quote\","
                + "\"title\":\"Diagnosis pending for Acme\",\"version\":1,\"templateId\":6}");
        assertEquals("{\"recordType\":\"document\",\"type\":\"quote\",\"version\":1}",
                objectMapper.writeValueAsString(draftDocumentTool().modelOutcome(stored)));
    }

    @Test
    void resolutionNeverWritesAndRequiresOneExactNameIgnoringCase() {
        when(templateService.getAll()).thenReturn(List.of(template(6, " Quote ")));
        AiAssistantDraftDocumentWriteTool tool = draftDocumentTool();
        assertEquals(6, tool.resolve(new Target("deal", 44), new DraftDocument("r1", " QUOTE ")).id());
        assertThrows(ResourceNotFoundException.class,
                () -> tool.resolve(new Target("deal", 44), new DraftDocument("r1", "Quote")));
        verify(documentService, never()).generate(44, 6);
    }

    private Review review(String name, int pin, List<DocumentTemplate> templates) {
        return new Review("deal", 44, true, null,
                objectMapper.createObjectNode().put("template", name), null,
                List.of(), List.of(), List.of(), List.of(), Set.of(Permission.DEAL_UPDATE),
                pin, List.of(), templates);
    }

    private void stubDraft() {
        Deal target = deal(5);
        target.setUpdatedAt("2026-03-06 14:00:00.000000");
        when(dealService.getDealById(44)).thenReturn(target);
        when(dealService.lockDealForUpdate(44)).thenReturn(target);
        when(templateService.getAll()).thenReturn(List.of(template(6, "Quote")));
        when(templateService.getById(6)).thenReturn(template(6, "Quote"));
        when(documentService.generate(44, 6)).thenReturn(document(6));
    }

    static DocumentTemplate template(int id, String name) {
        DocumentTemplate template = new DocumentTemplate();
        template.setId(id);
        template.setName(name);
        return template;
    }

    static DealDocumentDto document(Integer templateId) {
        return new DealDocumentDto(74, 44, templateId, "quote", "en", "draft", 1,
                "Acme renewal quote", "USD", "2026-03-06 15:00:00", TURN.userId(), null, false, null);
    }
}
