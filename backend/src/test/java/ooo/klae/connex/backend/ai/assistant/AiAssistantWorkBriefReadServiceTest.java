package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantWorkBriefReadServiceTest {
    private TaskMapper taskMapper;
    private ActivityMapper activityMapper;
    private AiAssistantWorkBriefReadService service;

    @Test
    void incompleteCommitmentProseDoesNotDiscardOtherCommitments() throws Exception {
        when(taskMapper.getAiAssistantWorkCommitments(anyInt(), anyInt(), any(), any(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new AiAssistantWorkCommitment(1, "`[Unknown Former Alias](user:42)`", null, true, null, null),
                        new AiAssistantWorkCommitment(2, "Keep this commitment", null, false, null, null)));
        var context = new MaskingContext();
        var result = service.workCommitments(11, 7, 10, new AiChatResourceRegistry(context));
        var data = JsonMapper.builder().build().valueToTree(result.data());
        org.junit.jupiter.api.Assertions.assertEquals(2, result.data().get("returnedCommitments"));
        org.junit.jupiter.api.Assertions.assertEquals("(unavailable reference)",
                data.path("commitments").path(0).path("description").asString());
        org.junit.jupiter.api.Assertions.assertEquals("Keep this commitment",
                data.path("commitments").path(1).path("description").asString());
        assertFalse(data.toString().contains("Unknown Former Alias"));
        assertFalse(context.identifierDictionary().contains("Unknown Former Alias"));
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        activityMapper = mock(ActivityMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess workspaceScope = mock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceScope.getForWorkspace(7)).thenReturn(new WorkspaceScope(1, List.of(7), "[7]"));
        Person person = new Person();
        person.setId(2);
        person.setName("Johnathan Smith");
        Deal deal = new Deal();
        deal.setId(3);
        deal.setName("Renewal Agreement");
        when(personMapper.getByIds(eq(7), anyList())).thenReturn(List.of(person));
        when(dealMapper.getByIds(eq(7), anyList())).thenReturn(List.of(deal));
        ReferenceService references = mock(ReferenceService.class);
        when(references.hydrateTasks(anyInt(), anyList())).thenAnswer(call -> call.getArgument(1));
        service = new AiAssistantWorkBriefReadService(
                taskMapper, activityMapper, personMapper, mock(CompanyMapper.class), dealMapper,
                mock(ScoringService.class), workspaceService, workspaceScope,
                Clock.fixed(Instant.parse("2026-08-23T04:00:00Z"), ZoneOffset.UTC), references, ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered());
    }

    @Test
    void commitmentsSeedAllLinkedRecordsBeforeTruncatingEarlierDescriptions() throws Exception {
        when(taskMapper.getAiAssistantWorkCommitments(anyInt(), anyInt(), any(), any(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new AiAssistantWorkCommitment(1, "x".repeat(498) + "Johnathan Smith", null, false, null, null),
                        new AiAssistantWorkCommitment(2, "x".repeat(498) + "Renewal Agreement", null, false, null, null),
                        new AiAssistantWorkCommitment(3, "Follow up", null, false, 2, 3)));
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);

        AiAssistantToolResult result = service.workCommitments(11, 7, 10, resources);

        assertNoFragments(result, context, resources);
    }

    @Test
    void meetingsSeedAllLinkedRecordsBeforeTruncatingEarlierSubjects() throws Exception {
        when(activityMapper.getAiAssistantUpcomingMeetings(
                anyInt(), anyInt(), any(), any(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new AiAssistantUpcomingMeeting(1, "meeting", "x".repeat(498) + "Johnathan Smith", null, null, null),
                        new AiAssistantUpcomingMeeting(2, "call", "x".repeat(498) + "Renewal Agreement", null, null, null),
                        new AiAssistantUpcomingMeeting(3, "demo", "Follow up", null, 2, 3)));
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);

        AiAssistantToolResult result = service.upcomingMeetings(11, 7, 10, resources);

        assertNoFragments(result, context, resources);
    }

    private static void assertNoFragments(
            AiAssistantToolResult result, MaskingContext context, AiChatResourceRegistry resources) throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        String providerInput = mapper.writeValueAsString(new AiAssistantPromptAssembler(
                mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "work_brief", result)),
                        context, resources,
                        AiAssistantToolCatalog.ALL).getMessages());
        assertFalse(providerInput.contains("Johnathan"));
        assertFalse(providerInput.contains("Renewal Agre"));
        assertTrue(providerInput.contains("{{P"));
        assertTrue(providerInput.contains("{{D"));
    }
    @Test
    void commitmentMemberReferencesAreCandidatesBeforeDescriptionTruncation() throws Exception {
        when(taskMapper.getAiAssistantWorkCommitments(anyInt(), anyInt(), any(), any(), anyList(), anyInt()))
                .thenReturn(List.of(new AiAssistantWorkCommitment(1,
                        "x".repeat(490) + " [Former Member](user:42) and [Pending Alias](user:43)", null, true, null, null)));
        MaskingContext context = new MaskingContext();
        var resources = new AiChatResourceRegistry(context);
        var result = service.workCommitments(11, 7, 10, resources);
        assertTrue(context.identifierDictionary().containsAll(List.of("Former Member", "Pending Alias")));
        String projected = new ObjectMapper().writeValueAsString(result.data());
        assertFalse(projected.contains("Former"));
        assertFalse(projected.contains("Pending"));
        org.junit.jupiter.api.Assertions.assertEquals(0, context.tokenBindings().size());
    }

    @Test
    void emptyCommitmentsAndMeetingsDoNotReadRecordVisibilityScopes() {
        try (var operation = new ooo.klae.connex.backend.support.RecordVisibilityOperationFixture(7)) {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", operation.scopes);
            service.workCommitments(11, 7, 10, new AiChatResourceRegistry());
            service.upcomingMeetings(11, 7, 10, new AiChatResourceRegistry());
            org.mockito.Mockito.verifyNoInteractions(operation.control);
        }
    }

}
