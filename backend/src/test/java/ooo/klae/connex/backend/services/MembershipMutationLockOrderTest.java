package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.dto.WorkflowInterventionOwnerRequest;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Proves the audited membership batch retains every parent before any aggregate lock. */
class MembershipMutationLockOrderTest {
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final WorkspaceMapper memberships = dependency(WorkspaceMapper.class);
    private final UserMapper accounts = dependency(UserMapper.class);
    private final OrganizationMapper organizations = dependency(OrganizationMapper.class);
    private final RuntimeException aggregateReached = new RuntimeException("aggregate reached");
    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() throws Exception {
        workspaceService = spy(construct(WorkspaceService.class));
        dependencies.put(WorkspaceService.class, workspaceService);
        doReturn(9).when(workspaceService).getCurrentUserId();
        doReturn(7).when(workspaceService).getCurrentWorkspaceId();
        when(accounts.lockByIdForShare(9)).thenReturn(9);
        when(memberships.lockWorkspaceForShare(7)).thenReturn(7);
        when(memberships.getOrgId(7)).thenReturn(3);
        when(organizations.lockByIdForShare(3)).thenReturn(3);
        when(memberships.lockActiveMembership(org.mockito.ArgumentMatchers.eq(7), anyInt()))
            .thenAnswer(call -> call.getArgument(1));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void batchIsSortedAndDeduplicatedBetweenRootsAndOrganization() {
        workspaceService.lockAssignmentMembers(7, List.of(41, 17, 41));

        InOrder order = inOrder(accounts, memberships, organizations);
        order.verify(accounts).lockByIdForShare(9);
        order.verify(memberships).lockWorkspaceForShare(7);
        order.verify(memberships).lockActiveMembership(7, 17);
        order.verify(memberships).lockActiveMembership(7, 41);
        order.verify(organizations).lockByIdForShare(3);
        verify(memberships).lockActiveMembership(7, 41);
    }

    @Test
    void leafMembershipAcquisitionDoesNotIntroduceAuditRoots() {
        workspaceService.lockAndRequireMember(7, 41);

        verify(memberships).lockActiveMembership(7, 41);
        verify(memberships, never()).lockWorkspaceForShare(anyInt());
        verifyNoInteractions(accounts, organizations);
    }

    @Test
    void executionRetainsSystemAndAttributionAccountsThenTheSingleMemberRoleBatch() {
        RoleMapper roles = dependency(RoleMapper.class);
        when(accounts.lockByIdForShare(1)).thenReturn(1);
        when(accounts.isAccountDeletionReservedForShare(9)).thenReturn(false);
        when(memberships.lockActiveWorkspaceForShare(7)).thenReturn(3);
        WorkspaceMember actor = membership(9);
        actor.setRoleId(23);
        when(memberships.lockAuthorizationMembership(7, 9)).thenReturn(actor);
        when(memberships.lockAuthorizationMembershipWithoutWaiting(7, 17)).thenReturn(membership(17));
        when(memberships.lockAuthorizationMembershipWithoutWaiting(7, 41)).thenReturn(membership(41));
        when(roles.lockRole(7, 23)).thenReturn(23);
        when(roles.lockPermissions(7, 23)).thenReturn(List.of("PERSON_UPDATE"));

        org.junit.jupiter.api.Assertions.assertEquals(Set.of(Permission.PERSON_UPDATE),
            workspaceService.lockExecutionPermissions(7, 1, 9, Set.of(41, 9, 17)));

        InOrder order = inOrder(accounts, memberships, roles, organizations);
        order.verify(accounts).lockByIdForShare(1);
        order.verify(accounts).lockByIdForShare(9);
        order.verify(memberships, org.mockito.Mockito.atLeastOnce()).lockActiveWorkspaceForShare(7);
        order.verify(memberships).lockAuthorizationMembership(7, 9);
        order.verify(memberships).lockAuthorizationMembershipWithoutWaiting(7, 17);
        order.verify(memberships).lockAuthorizationMembershipWithoutWaiting(7, 41);
        order.verify(roles).lockRole(7, 23);
        order.verify(roles).lockPermissions(7, 23);
        order.verify(organizations).lockByIdForShare(3);
        verify(memberships, never()).lockAuthorizationMembershipWithoutWaiting(7, 9);
    }

    private static WorkspaceMember membership(int userId) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(7);
        member.setUserId(userId);
        member.setRole("member");
        member.setStatus("active");
        return member;
    }

    @ParameterizedTest
    @ValueSource(strings = {"deal", "person", "company", "intervention", "collaborators", "createTask", "updateTask"})
    void allAuditedPathsRetainRootsBeforeTheirFirstAggregate(String path) throws Exception {
        exercise(path, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deal", "person", "company", "intervention", "collaborators"})
    void clearingAnAssignmentStillRetainsEveryAuditParent(String path) throws Exception {
        exercise(path, true);
        verify(memberships, never()).lockActiveMembership(anyInt(), anyInt());
    }

    private void exercise(String path, boolean empty) throws Exception {
        Integer owner = empty ? null : 41;
        Object aggregate;
        Runnable write;
        switch (path) {
            case "person" -> {
                PersonMapper mapper = dependency(PersonMapper.class);
                when(mapper.existsOwned(7, 29)).thenReturn(true);
                when(mapper.getPersonById(7, 29)).thenReturn(new Person());
                when(mapper.getOwnedPersonByIdForUpdate(7, 29)).thenThrow(aggregateReached);
                PersonService service = construct(PersonService.class);
                aggregate = mapper;
                write = () -> service.updateOwner(29, owner);
            }
            case "company" -> {
                CompanyMapper mapper = dependency(CompanyMapper.class);
                when(mapper.existsOwned(7, 29)).thenReturn(true);
                when(mapper.getCompanyById(7, 29)).thenReturn(new Company());
                when(mapper.getOwnedCompanyByIdForUpdate(7, 29)).thenThrow(aggregateReached);
                CompanyService service = construct(CompanyService.class);
                aggregate = mapper;
                write = () -> service.updateOwner(29, owner);
            }
            case "deal", "collaborators" -> {
                DealMapper mapper = dependency(DealMapper.class);
                when(mapper.getDealById(7, 29)).thenReturn(new Deal());
                DealService service;
                if ("deal".equals(path)) {
                    when(mapper.getDealByPrimaryKeyForUpdate(7, 29)).thenThrow(aggregateReached);
                    service = construct(DealService.class);
                    write = () -> service.updateOwner(29, owner);
                } else {
                    when(mapper.getDealByIdForUpdate(7, 29)).thenThrow(aggregateReached);
                    dependencies.put(TransactionTemplate.class,
                        new TransactionTemplate(mock(PlatformTransactionManager.class)));
                    service = construct(DealService.class);
                    write = () -> service.replaceCollaborators(29, empty ? List.of() : List.of(41, 17, 41));
                }
                aggregate = mapper;
            }
            case "intervention" -> {
                WorkflowOperationsMapper mapper = dependency(WorkflowOperationsMapper.class);
                when(mapper.getInterventionForUpdate(7, 29L)).thenThrow(aggregateReached);
                WorkflowInterventionService service = construct(WorkflowInterventionService.class);
                aggregate = mapper;
                write = () -> service.updateOwner(29L, new WorkflowInterventionOwnerRequest(owner, 0));
            }
            case "createTask", "updateTask" -> {
                TaskMapper mapper = dependency(TaskMapper.class);
                doThrow(aggregateReached).when(mapper).lockTaskBoard(7);
                TaskService service = construct(TaskService.class);
                Task task = new Task();
                User assignee = new User();
                assignee.setId(41);
                task.setAssignedTo(assignee);
                aggregate = mapper;
                write = "createTask".equals(path) ? () -> service.create(task) : () -> service.update(29, task);
            }
            default -> throw new IllegalArgumentException(path);
        }
        assertSame(aggregateReached, assertThrows(RuntimeException.class, write::run));
        InOrder order = inOrder(accounts, memberships, organizations, aggregate);
        order.verify(accounts).lockByIdForShare(9);
        order.verify(memberships).lockWorkspaceForShare(7);
        if (!empty) {
            if ("collaborators".equals(path)) {
                order.verify(memberships).lockActiveMembership(7, 17);
            }
            order.verify(memberships).lockActiveMembership(7, 41);
        }
        order.verify(organizations).lockByIdForShare(3);
        switch (path) {
            case "person" -> order.verify((PersonMapper) aggregate).getOwnedPersonByIdForUpdate(7, 29);
            case "company" -> order.verify((CompanyMapper) aggregate).getOwnedCompanyByIdForUpdate(7, 29);
            case "deal" -> order.verify((DealMapper) aggregate).getDealByPrimaryKeyForUpdate(7, 29);
            case "collaborators" -> order.verify((DealMapper) aggregate).getDealByIdForUpdate(7, 29);
            case "intervention" -> order.verify((WorkflowOperationsMapper) aggregate).getInterventionForUpdate(7, 29L);
            default -> order.verify((TaskMapper) aggregate).lockTaskBoard(7);
        }
        if (!empty) {
            verify(memberships).lockActiveMembership(7, 41);
        }
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, key -> mock(type)));
    }

    private <T> T construct(Class<T> type) throws Exception {
        var constructor = type.getConstructors()[0];
        Object[] arguments = java.util.Arrays.stream(constructor.getParameterTypes())
            .map(this::dependency).toArray();
        return type.cast(constructor.newInstance(arguments));
    }
}
