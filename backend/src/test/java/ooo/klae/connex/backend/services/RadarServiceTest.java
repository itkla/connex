package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RelationshipSignalFamilyState;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.RadarTaskRequestDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

class RadarServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private RelationshipSignalMapper signalMapper;
    private WorkspaceService workspaceService;
    private WorkspaceMapper workspaceMapper;
    private UserMapper userMapper;
    private PersonMapper personMapper;
    private PersonEdgeReadService personEdgeReadService;
    private TaskService taskService;
    private WarmPathService warmPathService;
    private RadarService service;

    @BeforeEach
    void setUp() {
        signalMapper = mock(RelationshipSignalMapper.class);
        workspaceService = mock(WorkspaceService.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        userMapper = mock(UserMapper.class);
        personMapper = mock(PersonMapper.class);
        personEdgeReadService = mock(PersonEdgeReadService.class);
        taskService = mock(TaskService.class);
        warmPathService = mock(WarmPathService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceService.getCurrentUserId()).thenReturn(USER_ID);
        when(signalMapper.findFamilyStates(WORKSPACE_ID)).thenReturn(List.of(availableFamily()));
        when(userMapper.lockByIdForShare(USER_ID)).thenReturn(USER_ID);
        when(userMapper.isAccountDeletionReserved(USER_ID)).thenReturn(false);
        memberLock();
        when(personMapper.getProcessablePersonIds(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> {
                List<Integer> requested = invocation.getArgument(1);
                return requested.stream()
                    .filter(id -> personMapper.getPersonById(WORKSPACE_ID, id) != null)
                    .toList();
            });
        service = new RadarService(
            signalMapper,
            mock(RelationshipSignalReconciliationService.class),
            workspaceService,
            workspaceMapper,
            userMapper,
            personMapper,
            personEdgeReadService,
            mock(CompanyMapper.class),
            mock(DealMapper.class),
            taskService,
            warmPathService,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void followUsesOptimisticActorStateAndReturnsTheNewVersion() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        currentPerson(signal.getSubjectId(), "Visible");
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);
        when(signalMapper.insertState(
                WORKSPACE_ID, signal.getId(), USER_ID, "followed", null, null))
            .thenAnswer(invocation -> {
                signal.setDisposition("followed");
                signal.setStateVersion(1L);
                return 1;
            });
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));

        var followed = service.follow(signal.getId(), "1:0");

        assertEquals("followed", followed.state());
        assertEquals("1:1", followed.version());
        assertThrows(ConflictException.class, () -> service.follow(signal.getId(), "1:0"));
    }

    @Test
    void snoozeAndDismissRequireCurrentVersionsAndSourceFingerprint() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        signal.setDisposition("followed");
        signal.setStateVersion(3L);
        currentPerson(signal.getSubjectId(), "Visible");
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);
        when(signalMapper.updateState(
                WORKSPACE_ID,
                signal.getId(),
                USER_ID,
                "snoozed",
                LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                null,
                3))
            .thenAnswer(invocation -> {
                signal.setDisposition("snoozed");
                signal.setSnoozeUntil(invocation.getArgument(4));
                signal.setStateVersion(4L);
                return 1;
            });
        when(signalMapper.updateState(
                WORKSPACE_ID,
                signal.getId(),
                USER_ID,
                "dismissed",
                null,
                signal.getSourceStateHash(),
                4))
            .thenAnswer(invocation -> {
                signal.setDisposition("dismissed");
                signal.setDismissedSourceHash(signal.getSourceStateHash());
                signal.setSnoozeUntil(null);
                signal.setStateVersion(5L);
                return 1;
            });
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));

        assertEquals("snoozed", service.snooze(
            signal.getId(), "1:3", NOW.plusSeconds(3600)).state());
        var dismissed = service.dismiss(signal.getId(), "1:4");

        assertEquals("dismissed", dismissed.state());
        assertEquals("1:5", dismissed.version());
        assertEquals(signal.getSourceStateHash(), signal.getDismissedSourceHash());
    }

    @Test
    void staleEvidenceRefusesTaskBeforeAnyTaskMutation() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        signal.setEvidenceAsOf(LocalDateTime.ofInstant(NOW.minusSeconds(16 * 60), ZoneOffset.UTC));
        currentPerson(signal.getSubjectId(), "Visible");
        memberLock();
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);

        assertThrows(ConflictException.class, () -> service.createTask(
            signal.getId(),
            "1:0",
            new RadarTaskRequestDto("Call", null, null, null, null, null)));
        verify(taskService, never()).create(any());
        verify(warmPathService, never()).acceptPath(anyInt(), anyInt(), any());
    }

    @Test
    void hiddenSubjectAndHiddenRequiredBridgeNeverRenderAsEmptyEvidence() {
        RelationshipSignal subjectHidden = signal("relationship_decay", evidenceForSubject());
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID))
            .thenReturn(List.of(subjectHidden));
        assertTrue(service.get(List.of(), List.of(), null).items().isEmpty());
        when(signalMapper.getActiveForActor(
            WORKSPACE_ID, subjectHidden.getId(), USER_ID)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.context(subjectHidden.getId()));

        RelationshipSignal bridgeHidden = signal("warm_path", evidenceForBridge(22));
        currentPerson(bridgeHidden.getSubjectId(), "Target");
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID))
            .thenReturn(List.of(bridgeHidden));
        assertTrue(service.get(List.of(), List.of(), null).items().isEmpty());
    }

    @Test
    void standardTaskDelegatesWithSubjectLinkAndBindsIdempotently() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        currentPerson(signal.getSubjectId(), "Visible");
        memberLock();
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);
        when(signalMapper.insertState(
                WORKSPACE_ID, signal.getId(), USER_ID, "active", null, null))
            .thenReturn(1);
        Task created = new Task();
        created.setId(66);
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> {
            Task requested = invocation.getArgument(0);
            assertEquals(signal.getSubjectId(), requested.getPerson().getId());
            return created;
        });
        when(signalMapper.attachTask(
                WORKSPACE_ID,
                signal.getId(),
                USER_ID,
                66,
                signal.getSourceStateHash(),
                1))
            .thenAnswer(invocation -> {
                signal.setDisposition("active");
                signal.setStateVersion(2L);
                signal.setTaskId(66);
                return 1;
            });
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));

        var updated = service.createTask(
            signal.getId(),
            "1:0",
            new RadarTaskRequestDto("Call", "2026-08-10", USER_ID, signal.getSubjectId(), null, null));

        assertEquals(66, updated.taskId());
        verify(taskService).create(any(Task.class));
    }

    @Test
    void delegatedTaskLocksAllMembershipRootsInUserOrderBeforeSignalAndTaskCreation() {
        int assigneeId = 5;
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        currentPerson(signal.getSubjectId(), "Visible");
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, assigneeId))
            .thenReturn(activeMember(assigneeId));
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);
        when(signalMapper.insertState(
                WORKSPACE_ID, signal.getId(), USER_ID, "active", null, null))
            .thenReturn(1);
        Task created = new Task();
        created.setId(67);
        when(taskService.create(any(Task.class))).thenReturn(created);
        when(signalMapper.attachTask(
                WORKSPACE_ID,
                signal.getId(),
                USER_ID,
                67,
                signal.getSourceStateHash(),
                1))
            .thenAnswer(invocation -> {
                signal.setDisposition("active");
                signal.setStateVersion(2L);
                signal.setTaskId(67);
                return 1;
            });
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));

        service.createTask(
            signal.getId(),
            "1:0",
            new RadarTaskRequestDto(
                "Call", "2026-08-10", assigneeId, signal.getSubjectId(), null, null));

        InOrder order = inOrder(workspaceMapper, signalMapper, taskService);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, assigneeId);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, USER_ID);
        order.verify(signalMapper).getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID);
        order.verify(taskService).create(any(Task.class));
    }

    @Test
    void warmPathDelegatesAcceptanceAndImmediatelyResolvesTheCanonicalSignal() {
        RelationshipSignal signal = signal("warm_path", evidenceForBridge(22));
        currentPerson(signal.getSubjectId(), "Target");
        currentPerson(22, "Bridge");
        memberLock();
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);
        when(signalMapper.insertState(
                WORKSPACE_ID, signal.getId(), USER_ID, "active", null, null))
            .thenReturn(1);
        Task created = new Task();
        created.setId(77);
        when(warmPathService.acceptPath(
            signal.getSubjectId(), 22, "Ask for an intro")).thenReturn(created);
        when(signalMapper.attachTask(
                WORKSPACE_ID,
                signal.getId(),
                USER_ID,
                77,
                signal.getSourceStateHash(),
                1))
            .thenAnswer(invocation -> {
                signal.setDisposition("active");
                signal.setStateVersion(2L);
                signal.setTaskId(77);
                return 1;
            });
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));

        var updated = service.createTask(
            signal.getId(),
            "1:0",
            new RadarTaskRequestDto("Ask for an intro", null, USER_ID, signal.getSubjectId(), null, 22));

        assertEquals(77, updated.taskId());
        verify(warmPathService).acceptPath(signal.getSubjectId(), 22, "Ask for an intro");
        verify(signalMapper).resolveByIds(
            WORKSPACE_ID,
            List.of(signal.getId()),
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void canonicalReconciliationVersionInvalidatesAnActorAction() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        signal.setVersion(2);
        currentPerson(signal.getSubjectId(), "Visible");
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);

        assertThrows(
            ConflictException.class,
            () -> service.follow(signal.getId(), "1:0"));
        verify(signalMapper, never()).insertState(
            anyInt(), anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    void actorMutationRefusesAccountDeletionAndInactiveMembershipFences() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        when(userMapper.isAccountDeletionReserved(USER_ID)).thenReturn(true);

        assertThrows(
            ResourceNotFoundException.class,
            () -> service.follow(signal.getId(), "1:0"));
        verify(signalMapper, never()).getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID);

        when(userMapper.isAccountDeletionReserved(USER_ID)).thenReturn(false);
        WorkspaceMember inactive = new WorkspaceMember();
        inactive.setWorkspaceId(WORKSPACE_ID);
        inactive.setUserId(USER_ID);
        inactive.setStatus("inactive");
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, USER_ID))
            .thenReturn(inactive);

        assertThrows(
            ResourceNotFoundException.class,
            () -> service.follow(signal.getId(), "1:0"));
    }

    @Test
    void processingSuspensionBetweenGenerationAndReadRefusesEvidenceAndActions() {
        RelationshipSignal signal = signal("relationship_decay", evidenceForSubject());
        currentPerson(signal.getSubjectId(), "Suspended");
        doAnswer(invocation -> List.<Integer>of()).when(personMapper)
            .getProcessablePersonIds(WORKSPACE_ID, List.of(signal.getSubjectId()));
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));
        when(signalMapper.getActiveForActorForUpdate(
            WORKSPACE_ID, signal.getId(), USER_ID)).thenReturn(signal);

        assertTrue(service.get(List.of(), List.of(), null).items().isEmpty());
        assertThrows(
            ResourceNotFoundException.class,
            () -> service.follow(signal.getId(), "1:0"));
    }

    @Test
    void verifiedWarmPathEvidenceRequiresItsCurrentVisibleEdge() {
        RelationshipSignal signal = signal("warm_path", evidenceForBridgeEdge(22, 31));
        currentPerson(signal.getSubjectId(), "Target");
        currentPerson(22, "Bridge");
        when(signalMapper.findActiveForActor(WORKSPACE_ID, USER_ID)).thenReturn(List.of(signal));
        when(personEdgeReadService.getVisibleEdgeIds(WORKSPACE_ID, List.of(31)))
            .thenReturn(Set.of(31));

        var visible = service.get(List.of(), List.of(), null);
        when(personEdgeReadService.getVisibleEdgeIds(WORKSPACE_ID, List.of(31)))
            .thenReturn(Set.of());
        var hidden = service.get(List.of(), List.of(), null);

        assertTrue(visible.items().getFirst().evidence().getFirst().references().stream()
            .anyMatch(reference -> "person_edge".equals(reference.type())
                && reference.id() == 31));
        assertTrue(hidden.items().isEmpty());
    }

    @Test
    void taskMutationRequiresTaskCreateAtTheServiceBoundary() throws Exception {
        RequirePermission permission = RadarService.class
            .getMethod(
                "createTask", long.class, String.class, RadarTaskRequestDto.class)
            .getAnnotation(RequirePermission.class);

        assertEquals(Permission.TASK_CREATE, permission.value());
    }

    private RelationshipSignal signal(String family, String evidence) {
        RelationshipSignal signal = new RelationshipSignal();
        signal.setId("warm_path".equals(family) ? 2 : 1);
        signal.setWorkspaceId(WORKSPACE_ID);
        signal.setFamily(family);
        signal.setSubjectType("person");
        signal.setSubjectId(18);
        signal.setSubjectLabel("Persisted");
        signal.setPriority("warm_path".equals(family) ? "opportunity" : "cooling");
        signal.setPriorityRank("warm_path".equals(family) ? 3 : 2);
        signal.setRankValue(40);
        signal.setEvidenceJson(evidence);
        signal.setRankExplanationJson(
            "{\"rule\":\"priority_then_source_strength_then_subject\",\"factors\":[]}");
        signal.setEvidenceAsOf(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        signal.setSourceStateHash("a".repeat(64));
        signal.setVersion(1);
        return signal;
    }

    private static String evidenceForSubject() {
        return "[{\"type\":\"relationship_temperature\",\"parameters\":{},"
            + "\"references\":[{\"type\":\"person\",\"id\":18}]}]";
    }

    private static String evidenceForBridge(int bridgeId) {
        return "[{\"type\":\"warm_path\",\"parameters\":{\"bridgePersonId\":"
            + bridgeId + ",\"bridgeName\":\"Persisted bridge\"},\"references\":["
            + "{\"type\":\"person\",\"id\":18},{\"type\":\"person\",\"id\":"
            + bridgeId + "}]}]";
    }

    private static String evidenceForBridgeEdge(int bridgeId, int edgeId) {
        return "[{\"type\":\"warm_path\",\"parameters\":{\"bridgePersonId\":"
            + bridgeId + ",\"bridgeName\":\"Persisted bridge\"},\"references\":["
            + "{\"type\":\"person\",\"id\":18},{\"type\":\"person\",\"id\":"
            + bridgeId + "},{\"type\":\"person_edge\",\"id\":" + edgeId + "}]}]";
    }

    private void currentPerson(int id, String name) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        when(personMapper.getPersonById(WORKSPACE_ID, id)).thenReturn(person);
    }

    private void memberLock() {
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, USER_ID))
            .thenReturn(activeMember(USER_ID));
    }

    private static WorkspaceMember activeMember(int userId) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(userId);
        member.setStatus("active");
        return member;
    }

    private static RelationshipSignalFamilyState availableFamily() {
        RelationshipSignalFamilyState state = new RelationshipSignalFamilyState();
        state.setWorkspaceId(WORKSPACE_ID);
        state.setFamily("relationship_decay");
        state.setStatus("available");
        state.setLastAttemptAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        state.setLastSuccessAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        state.setEvidenceAsOf(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return state;
    }
}
