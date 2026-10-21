package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;

/** Real operation boundaries preserve per-item commits, failures, lock order and skipped work. */
@ExtendWith(MockitoExtension.class)
class PersonOperationScopeTest {
    @Mock private PersonMapper personMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private IdentityIntakeService identityIntakeService;
    @Mock private ProviderCaptureMapper providerCaptureMapper;
    @Mock private AuditService auditService;
    @Mock private NotificationChangePublisher notificationChanges;
    @InjectMocks private PersonService target;
    private RecordVisibilityOperationFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new RecordVisibilityOperationFixture(7);
        org.mockito.Mockito.reset(fixture.control);
        ReflectionTestUtils.setField(target, "recordVisibilityScopeControlAccess", fixture.scopes);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void aThousandArchivesShareOneControlReadAcrossTheirIndependentCommits() {
        prepareArchives();
        var result = bulk().archivePersons(IntStream.rangeClosed(1, 1000).boxed().toList());
        assertEquals(1000, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertEquals(1000, fixture.transactions.beginCount());
        verify(fixture.control, times(1)).findOrganizationWorkspacesForShare(7);
        var order = inOrder(fixture.control, duplicateDecisionLockService, personMapper);
        order.verify(fixture.control).findOrganizationWorkspacesForShare(7);
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(personMapper).lockById(7, 1);
    }

    @Test
    void oneFailedArchiveRollsBackWithoutPreventingTheNextItem() {
        prepareArchives();
        when(personMapper.archive(7, 2)).thenReturn(0);
        var result = bulk().archivePersons(List.of(1, 2, 3));
        assertEquals(2, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(3, fixture.transactions.beginCount());
        verify(fixture.control, times(1)).findOrganizationWorkspacesForShare(7);
        verify(personMapper).archive(7, 3);
    }

    @Test
    void emptyAndUnownedBatchesDoNotResolveVisibility() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        assertEquals(0, bulk().archivePersons(List.of()).getSucceeded());
        assertEquals(1, bulk().archivePersons(List.of(99)).getFailed());
        verifyNoInteractions(fixture.control, duplicateDecisionLockService);
    }

    @Test
    void snapshotInvariantFailureEscapesThePerItemHandlerBeforeAnyLock() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(personMapper.existsOwned(7, 1)).thenReturn(true);
        fixture.context.set(8, 90, 17, "member", null);
        assertThrows(TenantScopeSnapshotException.class, () -> bulk().archivePersons(List.of(1, 2)));
        verifyNoInteractions(duplicateDecisionLockService);
    }

    @Test
    void anEmptyScoreSelectionDoesNotResolveVisibility() {
        var scoring = mock(ScoringService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(scoring, "recordVisibilityScopeControlAccess", fixture.scopes);
        assertEquals(List.of(), scoring.scoreContacts(7, java.util.Set.of()));
        verifyNoInteractions(fixture.control);
    }

    @Test
    void anEntirelyRestrictedAudienceNeverLoadsAddressesOrScope() {
        CampaignMapper campaigns = mock(CampaignMapper.class);
        when(campaigns.restrictedPersonIds(7, List.of(1, 2))).thenReturn(List.of(1, 2));
        var eligibility = new AudienceEligibilityService(fixture.scopes, campaigns, personMapper);
        var result = eligibility.classify(7, List.of(1, 2), "email", "marketing");
        assertEquals(List.of(), result.includedIds());
        assertEquals(java.util.Set.of(1, 2), result.restricted());
        verifyNoInteractions(fixture.control, personMapper);
    }

    @Test
    void addressBatchesReuseOneScopeAndPreserveCandidateOrder() {
        prepareScope();
        var campaigns = mock(CampaignMapper.class);
        List<Integer> ids = IntStream.rangeClosed(1, 1001).boxed().toList();
        when(personMapper.getByIds(eq(7), eq("[7,17]"), anyList())).thenAnswer(call -> {
            List<Integer> batch = call.getArgument(2);
            return batch.stream().map(id -> {
                Person person = person(id);
                person.setEmail("person-" + id + "@example.com");
                return person;
            }).toList();
        });
        var eligibility = new AudienceEligibilityService(fixture.scopes, campaigns, personMapper);
        var result = eligibility.classify(7, ids, "email", "transactional");
        assertEquals(ids, result.includedIds());
        verify(fixture.control, times(1)).findOrganizationWorkspacesForShare(7);
        verify(personMapper, times(3)).getByIds(eq(7), eq("[7,17]"), anyList());
    }

    @Test
    void rulesRefuseOneFailedScopeLookupBeforeAnyClaim() {
        var rules = mock(ooo.klae.connex.backend.mappers.RuleMapper.class);
        var claims = mock(WorkflowRuntimeClaimService.class);
        var scope = org.mockito.Mockito.spy(fixture.scopes);
        var engine = ruleEngine(rules, claims, scope);
        when(rules.getEnabledByTrigger(7, "entity_change")).thenReturn(List.of(rule(1), rule(2)));
        var failure = new IllegalStateException("Control unavailable");
        when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);

        org.junit.jupiter.api.Assertions.assertSame(failure,
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> engine.onEntityChange(7, "person", 41, "person.updated")));

        verify(scope, times(1)).getForWorkspace(7);
        verify(fixture.control, times(1)).findOrganizationWorkspacesForShare(7);
        verifyNoInteractions(claims, personMapper);
    }

    @Test
    void skippedRuleTypesAndEventsDoNotResolveAScope() {
        var rules = mock(ooo.klae.connex.backend.mappers.RuleMapper.class);
        var claims = mock(WorkflowRuntimeClaimService.class);
        var engine = ruleEngine(rules, claims, fixture.scopes);
        var wrongType = rule(1);
        wrongType.setRecordType("company");
        var wrongEvent = rule(2);
        wrongEvent.setTriggerConfig("{\"events\":[\"person.created\"]}");
        when(rules.getEnabledByTrigger(7, "entity_change")).thenReturn(List.of(wrongType, wrongEvent));

        engine.onEntityChange(7, "person", 41, "person.updated");

        verifyNoInteractions(fixture.control, claims, personMapper);
    }

    private RuleEngineService ruleEngine(ooo.klae.connex.backend.mappers.RuleMapper rules,
            WorkflowRuntimeClaimService claims, RecordVisibilityScopeControlAccess scopes) {
        var engine = mock(RuleEngineService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(engine, "ruleMapper", rules);
        ReflectionTestUtils.setField(engine, "workflowRuntimeClaimService", claims);
        ReflectionTestUtils.setField(engine, "recordVisibilityScopeControlAccess", scopes);
        ReflectionTestUtils.setField(engine, "personMapper", personMapper);
        ReflectionTestUtils.setField(engine, "objectMapper", new tools.jackson.databind.ObjectMapper());
        return engine;
    }

    private static ooo.klae.connex.backend.beans.Rule rule(int id) {
        var rule = new ooo.klae.connex.backend.beans.Rule();
        rule.setId(id);
        rule.setWorkspaceId(7);
        rule.setRecordType("person");
        rule.setTriggerConfig("{\"events\":[\"person.updated\"]}");
        return rule;
    }

    @Test
    void visiblePersonLocksConsumeTheCapturedScopeWithoutAnotherLookup() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        Person person = person(1);
        when(personMapper.getVisiblePersonByIdForUpdate(7, "[7,17]", 1)).thenReturn(person);
        when(personMapper.getVisiblePersonByIdForShare(7, "[7,17]", 1)).thenReturn(person);

        assertEquals(person, target.lockProcessablePersonForUpdate(1, "[7,17]"));
        assertEquals(person, target.lockProcessablePersonForShare(1, "[7,17]"));
        verifyNoInteractions(fixture.control);
    }

    private void prepareArchives() {
        prepareScope();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(personMapper.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(personMapper.lockById(eq(7), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(personMapper.archive(eq(7), anyInt())).thenReturn(1);
        when(personMapper.getPersonById(eq(7), eq("[7,17]"), anyInt())).thenAnswer(call -> person(call.getArgument(2)));
        when(personMapper.getOwnedArchivedPersonById(eq(7), eq("[7,17]"), anyInt())).thenAnswer(call -> person(call.getArgument(2)));
    }

    private void prepareScope() {
        var anchor = new ooo.klae.connex.backend.beans.Workspace();
        anchor.setId(7);
        anchor.setOrgId(90);
        anchor.setName("Anchor");
        var sibling = new ooo.klae.connex.backend.beans.Workspace();
        sibling.setId(17);
        sibling.setOrgId(90);
        sibling.setName("Sibling");
        when(fixture.control.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(anchor, sibling));
    }

    private BulkOperationService bulk() {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(fixture.transactions, new AnnotationTransactionAttributeSource()));
        return new BulkOperationService(workspaceService, (PersonService) proxy.getProxy(), mock(CompanyService.class),
            mock(DealService.class), personMapper, mock(CompanyMapper.class), mock(DealMapper.class),
            mock(TagMapper.class), mock(PipelineMapper.class), fixture.scopes);
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        person.setWorkspaceId(7);
        person.setName("Person " + id);
        return person;
    }
}
