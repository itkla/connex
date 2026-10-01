package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;

/** The framework refuses unsafe values before a proposal and preserves unrequested columns. */
class AiAssistantUpdateRecordFieldsWriteToolTest extends AbstractAiAssistantWriteToolTest {
    @Test
    void markerRefusalPrecedesFieldValidationAndPreservesOnlyLedgeredProse() {
        AiAssistantWriteToolService service = service();
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("company", 52);
        for (String marker : List.of("[redacted]", "[omitted by policy]")) {
            for (String tool : List.of("update_record_fields", "add_tag", "remove_tag", "assign_owner")) {
                String field = "update_record_fields".equals(tool) ? "website"
                        : "assign_owner".equals(tool) ? "owner" : "tag";
                var args = objectMapper.createObjectNode().put("handle", "r1").put(field, "prefix " + marker);
                AiAssistantLoopException error = assertThrows(AiAssistantLoopException.class,
                        () -> service.prepare(tool, args, resources, TURN.restrictionEpoch()));
                assertEquals("redacted_value", error.detailReason());
                assertTrue(error.recoverable());
            }
            AiChatResourceRegistry people = new AiChatResourceRegistry();
            people.register("person", 31);
            service.prepare("create_note", objectMapper.createObjectNode()
                    .put("handle", "r1").put("content", marker).put("title", marker),
                    people, TURN.restrictionEpoch());
            service.prepare("create_task", objectMapper.createObjectNode()
                    .put("handle", "r1").put("description", marker), people, TURN.restrictionEpoch());
            service.prepare("create_activity", objectMapper.createObjectNode()
                    .put("handle", "r1").put("type", "call").put("subject", marker)
                    .put("notes", marker).put("start", "tomorrow"), people, TURN.restrictionEpoch());
        }
        verifyNoInteractions(chatMapper);
    }

    @Test
    void maskedSeededWebsiteIsRefusedButNewHostsAndUnmaskedValuesAreAllowed() {
        for (AiPrivacyMode mode : List.of(AiPrivacyMode.MASKED, AiPrivacyMode.UNMASKED)) {
            MaskingContext masking = new MaskingContext(mode);
            masking.tokenFor(EntityKind.COMPANY, "other.example");
            AiChatResourceRegistry resources = new AiChatResourceRegistry(masking);
            resources.register("company", 52);
            var copied = objectMapper.createObjectNode().put("handle", "r1")
                    .put("website", "other.example");
            AiAssistantWriteToolService service = service();
            if (mode == AiPrivacyMode.MASKED) {
                AiAssistantLoopException error = assertThrows(AiAssistantLoopException.class,
                        () -> service.prepare("update_record_fields", copied, resources, TURN.restrictionEpoch()));
                assertEquals("identifier_from_another_record", error.detailReason());
                assertTrue(error.recoverable());
            } else {
                service.prepare("update_record_fields", copied, resources, TURN.restrictionEpoch());
            }
            service.prepare("update_record_fields", copied.put("website", "new.example"),
                    resources, TURN.restrictionEpoch());
        }
        verifyNoInteractions(chatMapper);
    }

    @Test
    void invalidKindFieldsAndNonCalendarValuesAreRecoverable() {
        AiAssistantWriteToolService service = service();
        for (List<String> test : List.of(
                List.of("person", "{}"),
                List.of("person", "{\"title\":\" \"}"),
                List.of("person", "{\"industry\":\"Software\"}"),
                List.of("company", "{\"website\":\"https://acme.example\"}"),
                List.of("company", "{\"website\":\"acme\"}"),
                List.of("deal", "{\"value\":\"-1\"}"),
                List.of("deal", "{\"value\":\"1.001\"}"),
                List.of("deal", "{\"value\":\"12345678901234\"}"),
                List.of("deal", "{\"expected_close_date\":\"2026-02-30\"}"))) {
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            resources.register(test.getFirst(), 31);
            var args = objectMapper.createObjectNode().put("handle", "r1");
            objectMapper.readTree(test.get(1)).properties().forEach(entry ->
                    args.set(entry.getKey(), entry.getValue()));
            AiAssistantLoopException error = assertThrows(AiAssistantLoopException.class,
                    () -> service.prepare("update_record_fields", args, resources, TURN.restrictionEpoch()));
            assertEquals("invalid_tool_arguments", error.detailReason());
            assertTrue(error.recoverable());
        }
        verifyNoInteractions(chatMapper);
    }

    @Test
    void sharedInPeopleAndCompaniesNeverGetAProposal() {
        when(personService.isOwnedByCurrentWorkspace(31)).thenReturn(false);
        when(companyService.isOwnedByCurrentWorkspace(31)).thenReturn(false);
        AiAssistantWriteToolService service = service();
        for (String kind : List.of("person", "company")) {
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            resources.register(kind, 31);
            var args = objectMapper.createObjectNode().put("handle", "r1")
                    .put("person".equals(kind) ? "title" : "industry", "Software");
            AiAssistantLoopException error = assertThrows(AiAssistantLoopException.class,
                    () -> service.prepare("update_record_fields", args, resources, TURN.restrictionEpoch()));
            assertEquals("unresolved_reference", error.detailReason());
        }
        verifyNoInteractions(chatMapper);
    }

    @Test
    void rawValidationRejectsUnknownKeysAndTypesAndAcceptsNullableNativeArguments() {
        AiAssistantUpdateRecordFieldsWriteTool tool =
                new AiAssistantUpdateRecordFieldsWriteTool(null, null, null);
        for (String raw : List.of(
                "{\"handle\":\"r1\",\"title\":42}",
                "{\"handle\":\"r1\",\"title\":true}",
                "{\"handle\":\"r1\",\"title\":[]}",
                "{\"handle\":\"r1\",\"title\":\"Director\",\"email\":null}",
                "{\"handle\":\"r1\",\"title\":\"Director\",\"expectedCloseDate\":null}",
                "{\"handle\":\"t1\",\"title\":\"Director\"}",
                "{\"handle\":\"r1\",\"title\":null}")) {
            var request = objectMapper.readTree(raw);
            AiAssistantLoopException error = assertThrows(AiAssistantLoopException.class,
                    () -> tool.validateFor("person", request));
            assertEquals("invalid_tool_arguments", error.detailReason());
        }
        var request = objectMapper.createObjectNode().put("handle", "r1").put("title", "Director")
                .putNull("website").putNull("industry").putNull("address")
                .putNull("value").putNull("expected_close_date");
        assertDoesNotThrow(() -> tool.validateFor("person", request));
    }

    @Test
    void dealFieldsUseBothSingleFieldDelegatesUnderTheTargetLock() throws Exception {
        Deal before = new Deal();
        before.setId(44);
        Deal after = new Deal();
        after.setId(44);
        after.setValue(new BigDecimal("1250.50"));
        after.setExpectedCloseDate("2026-10-15");
        when(dealService.getDealById(44)).thenReturn(before);
        when(dealService.lockDealForUpdate(44)).thenReturn(before);
        when(dealService.updateValue(44, new BigDecimal("1250.50"))).thenReturn(after);
        when(dealService.reschedule(44, "2026-10-15")).thenReturn(after);
        AiAssistantWriteToolService service = service();
        propose(service, "update_record_fields",
                "{\"handle\":\"r1\",\"value\":\"1250.50\",\"expected_close_date\":\"2026-10-15\"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        var order = inOrder(dealService);
        order.verify(dealService).lockDealForUpdate(44);
        order.verify(dealService).updateValue(44, new BigDecimal("1250.50"));
        order.verify(dealService).reschedule(44, "2026-10-15");
        verifyNoInteractions(duplicateDecisionLockService);
        var outcome = objectMapper.readTree(capturedExecutedResult()).path("outcome");
        assertEquals("1250.50", outcome.path("value").asString());
        assertEquals("2026-10-15", outcome.path("expectedCloseDate").asString());
    }

    @Test
    void companyOverlayPreservesOtherColumnsAndLocksMutexBeforeTarget() throws Exception {
        Company before = new Company();
        before.setId(52);
        before.setName("Acme");
        before.setWebsite("acme.example");
        before.setPhone("+81 03 1234 5678");
        before.setAddress("Tokyo");
        when(companyService.getCompanyById(52)).thenReturn(before);
        when(companyService.lockOwnedCompanyForUpdate(52)).thenReturn(before);
        when(companyService.updateCompany(eq(52), any())).thenAnswer(invocation -> {
            Company after = invocation.getArgument(1);
            after.setId(52);
            return after;
        });
        AiAssistantWriteToolService service = service();
        propose(service, "update_record_fields", "{\"handle\":\"r1\",\"industry\":\"Software\"}", "company", 52);
        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        var order = inOrder(duplicateDecisionLockService, companyService);
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(companyService).lockOwnedCompanyForUpdate(52);
        ArgumentCaptor<Company> overlay = ArgumentCaptor.forClass(Company.class);
        verify(companyService).updateCompany(eq(52), overlay.capture());
        assertEquals(before.getName(), overlay.getValue().getName());
        assertEquals(before.getWebsite(), overlay.getValue().getWebsite());
        assertEquals(before.getPhone(), overlay.getValue().getPhone());
        assertEquals(before.getAddress(), overlay.getValue().getAddress());
        assertEquals("Software", overlay.getValue().getIndustry());
        var model = new AiAssistantUpdateRecordFieldsWriteTool(null, null, null)
                .modelOutcome(objectMapper.readTree(capturedExecutedResult()).path("outcome"));
        assertEquals(List.of("industry"), model.get("updated"));
        assertFalse(model.containsValue("Software"));
    }

    @Test
    void personPatchKeepsTheCompanyAndStoredWrongKindFieldsAreRefused() throws Exception {
        Person before = new Person();
        before.setId(31);
        Company company = new Company();
        company.setId(52);
        before.setCompany(company);
        when(personService.getPersonById(31)).thenReturn(before);
        when(personService.lockProcessablePersonForUpdate(31)).thenReturn(before);
        when(personService.update(eq(31), any())).thenReturn(before);
        AiAssistantWriteToolService service = service();
        propose(service, "update_record_fields", "{\"handle\":\"r1\",\"title\":\"Director\"}", "person", 31);
        String valid = storedToolCall.getArgumentsJson();
        var wrongKind = objectMapper.readTree(valid).deepCopy();
        if (!(wrongKind.path("request") instanceof tools.jackson.databind.node.ObjectNode wrongRequest)) {
            throw new AssertionError("Missing request");
        }
        wrongRequest.remove("title");
        wrongRequest.put("industry", "Director");
        storedToolCall.setArgumentsJson(objectMapper.writeValueAsString(wrongKind));
        assertThrows(AiAssistantLoopException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        storedToolCall.setArgumentsJson(valid);
        service.approve(TURN.sessionId(), TOOL_CALL_ID);
        ArgumentCaptor<Person> patch = ArgumentCaptor.forClass(Person.class);
        verify(personService).update(eq(31), patch.capture());
        assertEquals(company, patch.getValue().getCompany());
        assertEquals("Director", patch.getValue().getTitle());
    }
}
