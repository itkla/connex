package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import ooo.klae.connex.backend.tenant.Permission;

/** Pins scope reuse through the import's existing outcome cache and participant deduplication. */
class ImportDealVisibilityScopeTest {
    @Test
    void outcomeCacheAndParticipantLoopUseTheSuppliedScopeWithoutAnotherBoundaryLookup() {
        ImportService service = mock(ImportService.class, org.mockito.Answers.CALLS_REAL_METHODS);
        DealMapper mapper = mock(DealMapper.class);
        DealOutcomeWriter outcomes = mock(DealOutcomeWriter.class);
        RecordVisibilityScopeControlAccess scopes = mock(RecordVisibilityScopeControlAccess.class);
        RecordVisibilityScope scope = new RecordVisibilityScope(90, List.of(7, 8, 9));
        ReflectionTestUtils.setField(service, "dealMapper", mapper);
        ReflectionTestUtils.setField(service, "dealOutcomeWriter", outcomes);
        ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", scopes);
        when(outcomes.stageOutcome(7, 13, scope)).thenReturn("normal");
        Map<Integer, String> cache = new HashMap<>();

        for (int iteration = 0; iteration < 4; iteration++) {
            assertEquals("normal", ReflectionTestUtils.invokeMethod(service, "outcomeFor", 7, 13, cache, scope));
        }
        assertEquals("normal", ReflectionTestUtils.invokeMethod(service, "outcomeFor", 7, null, cache, scope));
        when(mapper.addPersonIfAbsent(7, "[7,8,9]", 41, 11)).thenReturn(1);
        boolean changed = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "linkDealPeople",
            7, 41, List.of("first", "alias", "second", "missing"),
            Map.of("first", 11, "alias", 11, "second", 12), scope));

        assertTrue(changed);
        verify(outcomes).stageOutcome(7, 13, scope);
        verify(mapper).addPersonIfAbsent(7, "[7,8,9]", 41, 11);
        verify(mapper).addPersonIfAbsent(7, "[7,8,9]", 41, 12);
        verifyNoInteractions(scopes);
    }
    @ParameterizedTest
    @ValueSource(strings = {"overwrite", "skip"})
    void commitResolvesOnceAcrossCreatedUpdatedAndSkippedRowsWithRepeatedStages(String action) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            RecordVisibilityScopeControlAccess scopes = spy(fixture.scopes);
            WorkspaceService workspace = mock(WorkspaceService.class);
            DuplicateDecisionLockService locks = mock(DuplicateDecisionLockService.class);
            AuthService auth = mock(AuthService.class);
            DealMapper deals = mock(DealMapper.class);
            IdentityMapper identities = mock(IdentityMapper.class);
            PipelineMapper pipelines = mock(PipelineMapper.class);
            DealStageHistoryService history = mock(DealStageHistoryService.class);
            DealValueService values = mock(DealValueService.class);
            DealOutcomeWriter outcomes = new DealOutcomeWriter(deals, values);
            DuplicatePreflightService preflight = mock(DuplicatePreflightService.class);
            AuditService audit = mock(AuditService.class);
            ImportService service = new ImportService(
                workspace, locks, auth, mock(PersonMapper.class), mock(CompanyMapper.class), identities,
                mock(CompanyService.class), deals, mock(DealLineItemMapper.class), values, outcomes,
                mock(TagMapper.class), mock(TagService.class), pipelines, scopes, mock(EmploymentService.class),
                history, mock(CustomFieldDefinitionMapper.class), mock(CustomFieldDefinitionService.class),
                mock(RecordCreationTemplateMapper.class), mock(CustomFieldValueService.class), audit,
                mock(IdentityIntakeService.class), new MatchingService(
                    PhoneNumberUtil.getInstance(), PublicSuffixMatcherLoader.getDefault()), preflight);
            when(workspace.getCurrentWorkspaceId()).thenReturn(7);
            when(workspace.getCurrentUserId()).thenReturn(17);
            User actor = new User();
            actor.setId(17);
            when(auth.getCurrentUser()).thenReturn(actor);
            var admission = mock(DuplicatePreflightService.ImportCommitAdmission.class);
            var session = mock(DuplicatePreflightService.ImportCommitSession.class);
            when(preflight.claimImportCommit(eq("review-proof"), anyString())).thenReturn(admission);
            when(preflight.beginImportCommit(eq(List.of()), eq(List.of()), anyString(), same(admission)))
                .thenReturn(session);
            when(session.responses()).thenReturn(List.of());
            Pipeline primary = pipeline(10, "Primary");
            Pipeline secondary = pipeline(20, "Secondary");
            when(pipelines.getAllPipelines(7, "[7,17]")).thenReturn(List.of(primary, secondary));
            when(pipelines.getStagesByPipelineId(7, "[7,17]", 10)).thenReturn(List.of(stage(13, "Review")));
            when(pipelines.getStagesByPipelineId(7, "[7,17]", 20)).thenReturn(List.of(stage(24, "Review")));
            when(deals.getStageOutcome(7, "[7,17]", 13)).thenReturn("normal");
            when(deals.getStageOutcome(7, "[7,17]", 24)).thenReturn("normal");
            when(identities.findCurrentPersonIdentityMatches(7, "email", List.of("first@example.com", "second@example.com")))
                .thenReturn(List.of(person(11, "first@example.com"), person(12, "second@example.com")));
            when(deals.addPersonIfAbsent(eq(7), eq("[7,17]"), anyInt(), anyInt())).thenReturn(1);
            List<Deal> existing = new ArrayList<>();
            for (int id : List.of(41, 42, 43)) {
                Deal deal = new Deal();
                deal.setId(id);
                deal.setWorkspaceId(7);
                deal.setName("Existing " + id);
                deal.setCurrency("USD");
                deal.setValue(BigDecimal.ZERO);
                deal.setPipelineId(10);
                deal.setStageId(11);
                existing.add(deal);
                when(deals.getDealByIdForUpdate(7, id)).thenReturn(deal);
            }
            when(deals.getDealsForDedup(7)).thenReturn(existing);
            when(deals.insertBatch(anyList())).thenAnswer(invocation -> {
                List<Deal> inserted = invocation.getArgument(0);
                for (int index = 0; index < inserted.size(); index++) {
                    inserted.get(index).setId(51 + index);
                }
                return inserted.size();
            });
            ImportRequest request = new ImportRequest(
                List.of(row("Created 1", "Primary"), row("Existing 41", "Primary"),
                    row("Created 2", "Primary"), row("Existing 42", "Primary"),
                    row("Created 3", "Secondary"), row("Existing 43", "Secondary")),
                List.of(mapping("Name", "name"), mapping("Pipeline", "pipeline"),
                    mapping("Stage", "stage"), mapping("People", "people")), action, null);
            request.setDuplicateReviewProof("review-proof");

            ImportResult result = service.commitDeals(request);

            boolean skipping = "skip".equals(action);
            assertEquals(3, result.getCreated());
            assertEquals(skipping ? 0 : 3, result.getUpdated());
            assertEquals(skipping ? 3 : 0, result.getSkipped());
            assertEquals(List.of(), result.getFailed());
            verify(scopes).getForWorkspace(7);
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            verify(deals).getStageOutcome(7, "[7,17]", 13);
            verify(deals).getStageOutcome(7, "[7,17]", 24);
            verify(pipelines).getAllPipelines(7, "[7,17]");
            verify(pipelines).getStagesByPipelineId(7, "[7,17]", 10);
            verify(pipelines).getStagesByPipelineId(7, "[7,17]", 20);
            verify(deals).insertBatch(argThat(inserted ->
                inserted.stream().map(Deal::getName).toList().equals(List.of("Created 1", "Created 2", "Created 3"))));
            RecordVisibilityScope expected = new RecordVisibilityScope(90, List.of(7, 17));
            for (int id : List.of(51, 52, 53)) {
                int stageId = id == 53 ? 24 : 13;
                verify(history).recordInitial(7, id, stageId, null, expected);
            }
            for (int id : List.of(41, 42, 43)) {
                int stageId = id == 43 ? 24 : 13;
                if (skipping) {
                    verify(deals, never()).getDealByIdForUpdate(7, id);
                    verify(deals, never()).update(existing.get(id - 41));
                } else {
                    verify(deals).getDealByIdForUpdate(7, id);
                    verify(deals).update(existing.get(id - 41));
                    assertEquals(stageId, existing.get(id - 41).getStageId());
                    verify(history).recordTransition(7, id, stageId, null, null, expected);
                }
            }
            for (int id : skipping ? List.of(51, 52, 53) : List.of(41, 42, 43, 51, 52, 53)) {
                verify(deals).addPersonIfAbsent(7, "[7,17]", id, 11);
                verify(deals).addPersonIfAbsent(7, "[7,17]", id, 12);
            }
            verify(deals, times(skipping ? 6 : 12)).addPersonIfAbsent(eq(7), eq("[7,17]"), anyInt(), anyInt());
            verify(audit).record(eq("import.deal"), eq("deal"), eq(null), eq("CSV import"), anyString(),
                eq(Map.of("created", 3, "updated", skipping ? 0 : 3, "skipped", skipping ? 3 : 0, "failed", 0)));
            var order = inOrder(preflight, scopes, fixture.control, workspace, locks, deals);
            order.verify(preflight).claimImportCommit(eq("review-proof"), anyString());
            order.verify(scopes).getForWorkspace(7);
            order.verify(fixture.control).findOrganizationWorkspacesForShare(7);
            order.verify(workspace).lockAndRequirePermissions(7, Map.of(17, Set.of(Permission.DEAL_CREATE)));
            order.verify(locks).lockCurrentOrganization();
        }
    }

    private static Map<String, String> row(String name, String pipeline) {
        return Map.of("Name", name, "Pipeline", pipeline, "Stage", "Review",
            "People", "first@example.com;second@example.com;first@example.com");
    }

    private static ColumnMapping mapping(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private static Pipeline pipeline(int id, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        pipeline.setName(name);
        return pipeline;
    }

    private static Stage stage(int id, String name) {
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        return stage;
    }

    private static IdentityMatchRow person(int id, String email) {
        IdentityMatchRow person = new IdentityMatchRow();
        person.setRecordId(id);
        person.setNormalizedValue(email);
        return person;
    }

}
