package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.GoalMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.Permission;

/** Committed MySQL fixtures exercise departure, erasure, session freshness, and the bounded race. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DepartureReferencesIntegrationTest {
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserAccountCatalogOffboardingService catalogOffboarding;
    @Autowired private DocumentApprovalService approvalService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private DealDocumentMapper documentMapper;
    @Autowired private ApprovalPolicyMapper policyMapper;
    @Autowired private DocumentApprovalMapper approvalMapper;
    @Autowired private GoalMapper goalMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private TenantContext tenantContext;
    @MockitoSpyBean private DepartureApprovalGuard guard;
    @MockitoBean private AuditService auditService;
    @MockitoBean private SessionSecurityService sessionSecurityService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private Organization organization;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private User owner;
    private User target;
    private User survivor;
    private Deal deal;
    private DealDocument document;
    private ApprovalPolicy policy;
    private String fixturePrefix;

    @BeforeEach
    void setUp() {
        fixturePrefix = "departure-" + UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName(fixturePrefix);
        organization.setSlug(fixturePrefix);
        organizationMapper.insert(organization);
        workspace = workspace(fixturePrefix);
        otherWorkspace = workspace(fixturePrefix + "-other");
        jdbc.update("INSERT INTO app_user (username, display_name, email, password_hash, timezone) "
            + "VALUES (?, ?, ?, 'hash', 'UTC'), (?, ?, ?, 'hash', 'UTC'), (?, ?, ?, 'hash', 'UTC')",
            fixturePrefix + "-owner", "Owner", fixturePrefix + "-owner@example.com",
            fixturePrefix + "-target", "Target", fixturePrefix + "-target@example.com",
            fixturePrefix + "-survivor", "Survivor", fixturePrefix + "-survivor@example.com");
        owner = userMapper.getUserByUsername(fixturePrefix + "-owner");
        target = userMapper.getUserByUsername(fixturePrefix + "-target");
        survivor = userMapper.getUserByUsername(fixturePrefix + "-survivor");
        jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES "
            + "(?, ?, 'owner', 'active'), (?, ?, 'admin', 'active'), (?, ?, 'admin', 'active'), "
            + "(?, ?, 'member', 'active'), (?, ?, 'owner', 'active')",
            workspace.getId(), owner.getId(), workspace.getId(), target.getId(),
            workspace.getId(), survivor.getId(), otherWorkspace.getId(), target.getId(),
            otherWorkspace.getId(), owner.getId());
        authenticate(owner);
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName(fixturePrefix);
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Open");
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName(fixturePrefix);
        deal.setOwnerId(owner.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setValue(BigDecimal.TEN);
        deal.setCurrency("JPY");
        deal.setPosition(0);
        dealMapper.insert(deal);
        document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("pending_approval");
        document.setVersion(1);
        document.setTitle(fixturePrefix);
        document.setContent("{}");
        document.setCurrency("JPY");
        document.setCreatedBy(owner.getId());
        documentMapper.insert(document);
        policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspace.getId());
        policy.setName(fixturePrefix);
        policy.setActive(true);
        policy.setMode("sequential");
        policy.setSeparationOfDuties("off");
        policyMapper.insert(policy);
        jdbc.update("INSERT INTO approval_policy_step (workspace_id, policy_id, step_order, required_count) "
            + "VALUES (?, ?, 1, 1)", workspace.getId(), policy.getId());
        jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, id, 'user', ? FROM approval_policy_step WHERE policy_id = ?",
            target.getId(), policy.getId());
        jdbc.update("INSERT INTO report_goal (workspace_id, owner_id, metric, period_type, period_start, "
            + "target_value, currency, created_by) VALUES "
            + "(?, ?, 'revenue', 'month', '2026-10-01', 10, 'JPY', ?), "
            + "(?, NULL, 'revenue', 'month', '2026-10-01', 20, 'JPY', ?), "
            + "(?, ?, 'revenue', 'month', '2026-10-01', 30, 'JPY', ?), "
            + "(?, ?, 'revenue', 'month', '2026-10-01', 40, 'JPY', ?)",
            workspace.getId(), target.getId(), target.getId(), workspace.getId(), target.getId(),
            workspace.getId(), survivor.getId(), target.getId(),
            otherWorkspace.getId(), target.getId(), target.getId());
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        if (workspace != null) {
            jdbc.update("DELETE FROM report_goal WHERE workspace_id IN (?, ?)", workspace.getId(), otherWorkspace.getId());
            jdbc.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM approval_policy WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM notification WHERE workspace_id IN (?, ?)", workspace.getId(), otherWorkspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id IN (?, ?)", workspace.getId(), otherWorkspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id IN (?, ?)", workspace.getId(), otherWorkspace.getId());
        }
        if (fixturePrefix != null) {
            jdbc.update("DELETE FROM app_user WHERE username IN (?, ?, ?)",
                fixturePrefix + "-owner", fixturePrefix + "-target", fixturePrefix + "-survivor");
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "leave", "select", "decline"})
    void eachDepartureRefusesSoleRoutesThenCleansOnlyItsWorkspaceAfterReassignment(String path) {
        if ("decline".equals(path)) {
            jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(), target.getId());
        }
        assertThrows(ConflictException.class, () -> depart(path));
        assertNotNull(workspaceMapper.getMember(workspace.getId(), target.getId()));
        assertEquals(1, countPersonalGoals(workspace.getId()));
        reassignPolicy();
        DocumentApproval request = request("pending", null);
        assertThrows(ConflictException.class, () -> depart(path));
        reassignRequest(request);
        terminalEvidence(request);

        depart(path);

        assertNull(workspaceMapper.getMember(workspace.getId(), target.getId()));
        assertEquals(0, countPersonalGoals(workspace.getId()));
        assertEquals(2, goalMapper.getGoals(workspace.getId()).size());
        assertTrue(goalMapper.getGoals(workspace.getId()).stream()
            .allMatch(goal -> Integer.valueOf(target.getId()).equals(goal.getCreatedBy())));
        assertEquals(1, countPersonalGoals(otherWorkspace.getId()));
        assertReferencesAndEvidence(0);
        if ("select".equals(path)) {
            assertEquals(otherWorkspace.getId(), workspaceMapper.getLastActiveWorkspaceId(target.getId()));
        }
    }

    @Test
    void catalogErasureRefusesThenDeletesPersonalGoalsAndClearsSurvivingProvenance() {
        assertThrows(ConflictException.class, () -> catalogOffboarding.assertNoAuthoredContent(target.getId()));
        assertThrows(ConflictException.class, () -> catalogOffboarding.eraseReferences(target.getId()));
        reassignPolicy();
        DocumentApproval request = request("pending", null);
        assertThrows(ConflictException.class, () -> catalogOffboarding.eraseReferences(target.getId()));
        assertEquals(1, countPersonalGoals(workspace.getId()));
        reassignRequest(request);
        terminalEvidence(request);

        jdbc.update("DELETE FROM workspace_member WHERE user_id = ?", target.getId());
        authenticate(target);
        tenantContext.clear();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        catalogOffboarding.assertNoAuthoredContent(target.getId());
        catalogOffboarding.eraseReferences(target.getId());

        RequestContextHolder.resetRequestAttributes();
        authenticate(owner);
        assertEquals(0, countPersonalGoals(workspace.getId()));
        assertEquals(0, countPersonalGoals(otherWorkspace.getId()));
        assertEquals(2, goalMapper.getGoals(workspace.getId()).size());
        assertTrue(goalMapper.getGoals(workspace.getId()).stream().allMatch(goal -> goal.getCreatedBy() == null));
        assertReferencesAndEvidence(0);
        assertNotNull(userMapper.getUserById(target.getId()));
    }

    @Test
    void catalogGoalDiscoveryIncludesEitherReferenceOnceAndExcludesUnrelatedGoals() {
        jdbc.update("INSERT INTO report_goal (workspace_id, owner_id, metric, period_type, period_start, "
            + "target_value, currency, created_by) VALUES "
            + "(?, ?, 'revenue', 'month', '2026-11-01', 10, 'JPY', ?), "
            + "(?, ?, 'revenue', 'month', '2026-11-01', 20, 'JPY', ?)",
            workspace.getId(), target.getId(), owner.getId(), workspace.getId(), owner.getId(), owner.getId());
        List<Integer> expected = jdbc.queryForList("SELECT id FROM report_goal "
            + "WHERE owner_id = ? OR created_by = ? ORDER BY workspace_id, id", Integer.class,
            target.getId(), target.getId());

        var references = goalMapper.findDepartureReferencesAnywhere(target.getId());

        assertEquals(5, expected.size());
        assertEquals(expected, references.stream().map(goal -> goal.getId()).toList());
        assertEquals(4, references.stream().filter(goal -> goal.getWorkspaceId() == workspace.getId()).count());
        assertEquals(1, references.stream().filter(goal -> goal.getWorkspaceId() == otherWorkspace.getId()).count());
        assertEquals(List.of("owner_id", "workspace_id", "id"), jdbc.queryForList(
            "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'report_goal' AND index_name = 'idx_report_goal_departure_owner' "
                + "ORDER BY seq_in_index", String.class));
        assertEquals(List.of("created_by", "workspace_id", "id"), jdbc.queryForList(
            "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'report_goal' AND index_name = 'idx_report_goal_departure_creator' "
                + "ORDER BY seq_in_index", String.class));
    }

    @Test
    void terminalRequestsDoNotBlockAndTheirFrozenReferencesAreRemoved() {
        jdbc.update("UPDATE approval_policy SET active = FALSE WHERE id = ?", policy.getId());
        DocumentApproval terminal = request("active", null);
        jdbc.update("UPDATE document_approval SET status = 'cancelled', outcome_reason = 'cancelled_by_requester' "
            + "WHERE id = ?", terminal.getId());

        depart("remove");

        assertEquals(0, policyMapper.findDepartureReferences(workspace.getId(), target.getId()).size());
        assertEquals(0, approvalMapper.findDepartureReferences(workspace.getId(), target.getId()).size());
        assertEquals("cancelled", approvalMapper.getById(workspace.getId(), terminal.getId()).getStatus());
    }

    @Test
    void delegatedAwayDepartureKeepsHistoryAndUsesUnchangedPermissionLossReconciliation() {
        reassignPolicy();
        DocumentApproval request = request("active", null);
        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id, created_by_user_id) "
            + "SELECT workspace_id, approval_id, id, 'delegation', 0, 'user', ?, ?, ? "
            + "FROM document_approval_step WHERE approval_id = ?", survivor.getId(), target.getId(), target.getId(), request.getId());

        depart("remove");

        assertEquals(0, approvalMapper.findDepartureReferences(workspace.getId(), target.getId()).size());
        assertEquals(1, approvalMapper.getAssignmentsByApprovalIds(workspace.getId(), List.of(request.getId())).size());
        authenticate(owner);
        reconcile(request);
        assertEquals("unsatisfiable", approvalMapper.getById(workspace.getId(), request.getId()).getOutcomeReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "catalog"})
    void unrelatedOpenStepsAreNotLoadedAndCompleteCandidateRoutesKeepTheSameVerdicts(String scope) {
        DocumentApproval request = request("active", null);
        unrelatedRoutes(request);
        assertEquals(257, jdbc.queryForObject("SELECT COUNT(*) FROM document_approval_step "
            + "WHERE workspace_id = ? AND status IN ('pending', 'active')", Integer.class, workspace.getId()));
        assertEquals(257, jdbc.queryForObject("SELECT COUNT(*) FROM approval_policy_step "
            + "WHERE workspace_id = ?", Integer.class, workspace.getId()));
        assertEquals(1, departurePolicies(scope).size());
        assertEquals(1, departureRequests(scope).size());
        assertThrows(ConflictException.class, () -> departureVerdict(scope));

        jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, step_id, 'user', ? FROM approval_policy_step_approver "
            + "WHERE workspace_id = ? AND user_id = ?", survivor.getId(), workspace.getId(), target.getId());
        assertEquals(2, departurePolicies(scope).getFirst().getApprovers().size());
        assertThrows(ConflictException.class, () -> departureVerdict(scope));
        jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, step_id, 'user', ? FROM document_approval_step_approver "
            + "WHERE workspace_id = ? AND user_id = ?", survivor.getId(), workspace.getId(), target.getId());
        assertEquals(2, departureRequests(scope).getFirst().getApprovers().size());
        assertDoesNotThrow(() -> departureVerdict(scope));

        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id) "
            + "SELECT workspace_id, approval_id, id, 'reassignment', 1, 'user', ? "
            + "FROM document_approval_step WHERE approval_id = ? AND step_order = 1", target.getId(), request.getId());
        assertThrows(ConflictException.class, () -> departureVerdict(scope));
        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id) "
            + "SELECT workspace_id, approval_id, id, 'reassignment', 2, 'user', ? "
            + "FROM document_approval_step WHERE approval_id = ? AND step_order = 1", survivor.getId(), request.getId());
        assertEquals(2, departureRequests(scope).getFirst().getAssignments().size());
        assertDoesNotThrow(() -> departureVerdict(scope));

        jdbc.update("DELETE FROM approval_policy_step_approver WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), target.getId());
        jdbc.update("DELETE FROM document_approval_step_approver WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), target.getId());
        assertEquals(0, departurePolicies(scope).size());
        assertEquals(1, departureRequests(scope).size());
        jdbc.update("DELETE FROM document_approval_step_assignment WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), target.getId());
        assertEquals(0, departureRequests(scope).size());
        assertDoesNotThrow(() -> departureVerdict(scope));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reassignment", "escalation", "delegationTo", "delegationFrom"})
    void assignmentMentionsDiscoverCandidatesWithoutAFrozenUserReference(String kind) {
        reassignPolicy();
        DocumentApproval request = request("active", null);
        unrelatedRoutes(request);
        jdbc.update("UPDATE document_approval_step_approver SET user_id = ? "
            + "WHERE workspace_id = ? AND user_id = ?", survivor.getId(), workspace.getId(), target.getId());
        assertEquals(0, departureRequests("catalog").size());
        if ("escalation".equals(kind)) {
            jdbc.update("DELETE FROM document_approval_step_approver WHERE workspace_id = ? AND step_id = "
                + "(SELECT id FROM document_approval_step WHERE approval_id = ? AND step_order = 1)",
                workspace.getId(), request.getId());
        }
        String assignmentKind = kind.startsWith("delegation") ? "delegation" : kind;
        Integer delegator = switch (kind) {
            case "delegationTo" -> survivor.getId();
            case "delegationFrom" -> target.getId();
            default -> null;
        };
        int assignee = "delegationFrom".equals(kind) ? owner.getId() : target.getId();
        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id) "
            + "SELECT workspace_id, approval_id, id, ?, ?, 'user', ?, ? "
            + "FROM document_approval_step WHERE approval_id = ? AND step_order = 1", assignmentKind,
            "reassignment".equals(kind) ? 1 : 0, assignee, delegator, request.getId());

        assertEquals(1, departureRequests("workspace").size());
        assertEquals(1, departureRequests("catalog").size());
        assertEquals(1, departureRequests("catalog").getFirst().getAssignments().size());
        if ("delegationFrom".equals(kind)) {
            assertDoesNotThrow(() -> departureVerdict("workspace"));
            assertDoesNotThrow(() -> departureVerdict("catalog"));
        } else {
            assertThrows(ConflictException.class, () -> departureVerdict("workspace"));
            assertThrows(ConflictException.class, () -> departureVerdict("catalog"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"policy", "policyAnywhere", "request", "requestAnywhere"})
    void guardReadsFlushAPrimedRealSession(String query) {
        assertEquals(org.apache.ibatis.session.LocalCacheScope.SESSION,
            sqlSession.getConfiguration().getLocalCacheScope());
        DocumentApproval request = request("active", null);
        Supplier<List<Integer>> route = switch (query) {
            case "policy" -> () -> policyMapper.findActiveStepsForDeparture(workspace.getId(), target.getId()).stream()
                .flatMap(step -> step.getApprovers().stream()).map(approver -> approver.getUserId()).toList();
            case "policyAnywhere" -> () -> policyMapper.findActiveStepsForDepartureAnywhere(target.getId()).stream()
                .filter(step -> step.getPolicyId() == policy.getId())
                .flatMap(step -> step.getApprovers().stream()).map(approver -> approver.getUserId()).toList();
            case "request" -> () -> approvalMapper.findOpenStepsForDeparture(workspace.getId(), target.getId()).stream()
                .flatMap(step -> step.getApprovers().stream()).map(approver -> approver.getUserId()).toList();
            case "requestAnywhere" -> () -> approvalMapper.findOpenStepsForDepartureAnywhere(target.getId()).stream()
                .filter(step -> step.getApprovalId() == request.getId())
                .flatMap(step -> step.getApprovers().stream()).map(approver -> approver.getUserId()).toList();
            default -> throw new IllegalArgumentException(query);
        };
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(List.of(target.getId()), route.get());
            jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
                + "SELECT workspace_id, step_id, 'user', ? FROM approval_policy_step_approver "
                + "WHERE workspace_id = ? AND user_id = ?", survivor.getId(), workspace.getId(), target.getId());
            jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
                + "SELECT workspace_id, step_id, 'user', ? FROM document_approval_step_approver "
                + "WHERE workspace_id = ? AND user_id = ?", survivor.getId(), workspace.getId(), target.getId());
            assertEquals(List.of(target.getId(), survivor.getId()), route.get());
            jdbc.update("UPDATE approval_policy_step_approver SET user_id = ? WHERE workspace_id = ? AND user_id = ?",
                owner.getId(), workspace.getId(), target.getId());
            jdbc.update("UPDATE document_approval_step_approver SET user_id = ? WHERE workspace_id = ? AND user_id = ?",
                owner.getId(), workspace.getId(), target.getId());
            assertEquals(List.of(), route.get());
            status.setRollbackOnly();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "goalAnywhere", "policy", "policyAnywhere", "request", "requestAnywhere"})
    void cleanupDiscoveryFlushesAPrimedRealSession(String query) {
        request("active", null);
        Supplier<List<Integer>> references = switch (query) {
            case "goal" -> () -> goalMapper.findDepartureReferences(workspace.getId(), target.getId())
                .stream().map(goal -> goal.getId()).toList();
            case "goalAnywhere" -> () -> goalMapper.findDepartureReferencesAnywhere(target.getId())
                .stream().map(goal -> goal.getId()).toList();
            case "policy" -> () -> policyMapper.findDepartureReferences(workspace.getId(), target.getId())
                .stream().map(approver -> approver.getId()).toList();
            case "policyAnywhere" -> () -> policyMapper.findDepartureReferencesAnywhere(target.getId())
                .stream().map(approver -> approver.getId()).toList();
            case "request" -> () -> approvalMapper.findDepartureReferences(workspace.getId(), target.getId())
                .stream().map(approver -> approver.getId()).toList();
            case "requestAnywhere" -> () -> approvalMapper.findDepartureReferencesAnywhere(target.getId())
                .stream().map(approver -> approver.getId()).toList();
            default -> throw new IllegalArgumentException(query);
        };
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            int row = references.get().getFirst();
            switch (query) {
                case "goal", "goalAnywhere" -> jdbc.update(
                    "UPDATE report_goal SET owner_id = ?, created_by = ? WHERE id = ?",
                    owner.getId(), owner.getId(), row);
                case "policy", "policyAnywhere" -> jdbc.update(
                    "UPDATE approval_policy_step_approver SET user_id = ? WHERE id = ?", survivor.getId(), row);
                case "request", "requestAnywhere" -> jdbc.update(
                    "UPDATE document_approval_step_approver SET user_id = ? WHERE id = ?", survivor.getId(), row);
                default -> throw new IllegalArgumentException(query);
            }
            assertFalse(references.get().contains(row));
            status.setRollbackOnly();
        });
    }

    @Test
    void conditionalCleanupRequiresWorkspaceRowAndUserToStillMatch() {
        request("active", null);
        int goal = goalMapper.findDepartureReferences(workspace.getId(), target.getId()).getFirst().getId();
        int template = policyMapper.findDepartureReferences(workspace.getId(), target.getId()).getFirst().getId();
        int frozen = approvalMapper.findDepartureReferences(workspace.getId(), target.getId()).getFirst().getId();
        assertEquals(0, goalMapper.deleteDepartingOwner(otherWorkspace.getId(), goal, target.getId()));
        assertEquals(0, goalMapper.deleteDepartingOwner(workspace.getId(), goal, survivor.getId()));
        assertEquals(0, goalMapper.clearDepartingCreatorAnywhere(otherWorkspace.getId(), goal, target.getId()));
        assertEquals(0, goalMapper.clearDepartingCreatorAnywhere(workspace.getId(), goal, survivor.getId()));
        assertEquals(0, policyMapper.deleteDepartingApprover(otherWorkspace.getId(), template, target.getId()));
        assertEquals(0, policyMapper.deleteDepartingApprover(workspace.getId(), template, survivor.getId()));
        assertEquals(0, approvalMapper.deleteDepartingApprover(otherWorkspace.getId(), frozen, target.getId()));
        assertEquals(0, approvalMapper.deleteDepartingApprover(workspace.getId(), frozen, survivor.getId()));
        assertNotNull(goalMapper.getGoal(workspace.getId(), goal));
    }

    /** A request commits after the guard's snapshot, while departure still owns its existing locks. */
    @ParameterizedTest
    @ValueSource(strings = {"unsatisfiable", "expired", "escalate"})
    void concurrentRequestFollowsDeadlineRulesBeforeUnsatisfiability(String outcome) throws Exception {
        reassignPolicy();
        assertTrue(workspaceService.permissionsFor(workspace.getId(), target.getId())
            .contains(Permission.DOCUMENT_APPROVE));
        CountDownLatch guarded = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            guarded.countDown();
            assertTrue(committed.await(30, TimeUnit.SECONDS));
            return null;
        }).when(guard).assertCanDepart(eq(workspace.getId()), eq(target.getId()));
        DocumentApproval request;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var departure = executor.submit(() -> {
                authenticate(owner);
                try {
                    workspaceService.removeMember(workspace.getId(), owner.getId(), target.getId());
                } finally {
                    SecurityContextHolder.clearContext();
                    tenantContext.clear();
                }
            });
            try {
                assertTrue(guarded.await(20, TimeUnit.SECONDS));
                request = new TransactionTemplate(transactionManager).execute(status -> {
                    DocumentApproval concurrent = request("active", "unsatisfiable".equals(outcome) ? null
                        : "expired".equals(outcome) ? "expire" : "escalate");
                    jdbc.update("INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status) "
                        + "VALUES (?, ?, 2, 1, 'pending')", workspace.getId(), concurrent.getId());
                    jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
                        + "SELECT workspace_id, id, 'user', ? FROM document_approval_step WHERE approval_id = ? AND step_order = 2",
                        survivor.getId(), concurrent.getId());
                    return concurrent;
                });
                assertNotNull(request);
            } finally {
                committed.countDown();
            }
            departure.get(30, TimeUnit.SECONDS);
        }
        assertNull(workspaceMapper.getMember(workspace.getId(), target.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_approval_step_approver "
            + "WHERE workspace_id = ? AND user_id = ?", Integer.class, workspace.getId(), target.getId()));
        authenticate(owner);
        reconcile(request);
        if ("escalate".equals(outcome)) {
            assertEquals("pending", approvalMapper.getById(workspace.getId(), request.getId()).getStatus());
            DocumentApprovalStep step = approvalMapper.getStepsByApprovalIds(workspace.getId(), List.of(request.getId())).getFirst();
            assertNull(step.getDueAt());
            assertNotNull(step.getEscalatedAt());
            assertEquals("any_approver", approvalMapper.getAssignmentsByApprovalIds(workspace.getId(), List.of(request.getId()))
                .getFirst().getApproverKind());
            reconcile(request);
            authenticate(survivor);
            approvalService.decide(deal.getId(), document.getId(), "approved", null, step.getId());
            DocumentApprovalStep future = approvalMapper.getStepsByApprovalIds(
                workspace.getId(), List.of(request.getId())).getLast();
            approvalService.decide(deal.getId(), document.getId(), "approved", null, future.getId());
            assertEquals("approved", approvalMapper.getById(workspace.getId(), request.getId()).getStatus());
        } else {
            assertEquals(outcome, approvalMapper.getById(workspace.getId(), request.getId()).getOutcomeReason());
            assertEquals(outcome, approvalMapper.getStepsByApprovalIds(workspace.getId(), List.of(request.getId()))
                .getFirst().getStatus());
            assertEquals("cancelled", approvalMapper.getStepsByApprovalIds(workspace.getId(), List.of(request.getId()))
                .getLast().getStatus());
            assertEquals("draft", documentMapper.getById(workspace.getId(), document.getId()).getStatus());
        }
    }

    private void reconcile(DocumentApproval request) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            approvalService.reconcileApproval(workspace.getId(), request));
    }

    private void depart(String path) {
        authenticate("remove".equals(path) ? owner : target);
        switch (path) {
            case "remove" -> workspaceService.removeMember(workspace.getId(), owner.getId(), target.getId());
            case "leave" -> workspaceService.leaveWorkspace(workspace.getId(), target.getId());
            case "select" -> workspaceService.leaveWorkspaceAndSelectNext(workspace.getId(), target.getId());
            case "decline" -> workspaceService.declineMembership(workspace.getId(), target.getId());
            default -> throw new IllegalArgumentException(path);
        }
    }

    private void reassignPolicy() {
        jdbc.update("UPDATE approval_policy_step_approver SET user_id = ? WHERE workspace_id = ?",
            survivor.getId(), workspace.getId());
    }

    private void reassignRequest(DocumentApproval request) {
        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id, created_by_user_id) "
            + "SELECT workspace_id, approval_id, id, 'reassignment', 1, 'user', ?, ? "
            + "FROM document_approval_step WHERE approval_id = ?",
            survivor.getId(), owner.getId(), request.getId());
    }

    private void terminalEvidence(DocumentApproval request) {
        jdbc.update("INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status) "
            + "VALUES (?, ?, 2, 1, 'approved')", workspace.getId(), request.getId());
        jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, id, 'user', ? FROM document_approval_step WHERE approval_id = ? AND step_order = 2",
            target.getId(), request.getId());
        jdbc.update("INSERT INTO document_approval_decision (workspace_id, approval_id, step_id, decision, decided_by) "
            + "SELECT workspace_id, approval_id, id, 'approved', ? FROM document_approval_step "
            + "WHERE approval_id = ? AND step_order = 2", target.getId(), request.getId());
        jdbc.update("INSERT INTO approval_policy (workspace_id, name, active) VALUES (?, 'inactive', FALSE)", workspace.getId());
        jdbc.update("INSERT INTO approval_policy_step (workspace_id, policy_id, step_order, required_count) "
            + "SELECT workspace_id, id, 1, 1 FROM approval_policy WHERE workspace_id = ? AND active = FALSE", workspace.getId());
        jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT s.workspace_id, s.id, 'user', ? FROM approval_policy_step s JOIN approval_policy p ON p.id = s.policy_id "
            + "WHERE s.workspace_id = ? AND p.active = FALSE", target.getId(), workspace.getId());
    }

    private void assertReferencesAndEvidence(int remaining) {
        assertEquals(remaining, jdbc.queryForObject("SELECT COUNT(*) FROM approval_policy_step_approver "
            + "WHERE workspace_id = ? AND user_id = ?", Integer.class, workspace.getId(), target.getId()));
        assertEquals(remaining, jdbc.queryForObject("SELECT COUNT(*) FROM document_approval_step_approver "
            + "WHERE workspace_id = ? AND user_id = ?", Integer.class, workspace.getId(), target.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_approval_decision WHERE workspace_id = ? "
            + "AND decided_by = ?", Integer.class, workspace.getId(), target.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_approval_step_assignment WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
    }

    private List<ApprovalPolicyStep> departurePolicies(String scope) {
        return "workspace".equals(scope)
            ? policyMapper.findActiveStepsForDeparture(workspace.getId(), target.getId())
            : policyMapper.findActiveStepsForDepartureAnywhere(target.getId());
    }

    private List<DocumentApprovalStep> departureRequests(String scope) {
        return "workspace".equals(scope)
            ? approvalMapper.findOpenStepsForDeparture(workspace.getId(), target.getId())
            : approvalMapper.findOpenStepsForDepartureAnywhere(target.getId());
    }

    private void departureVerdict(String scope) {
        if ("workspace".equals(scope)) {
            guard.assertCanDepart(workspace.getId(), target.getId());
        } else {
            guard.assertCanErase(target.getId());
        }
    }

    /** Committed, set-based fixtures give unrelated steps several approvers and assignment facts. */
    private void unrelatedRoutes(DocumentApproval request) {
        jdbc.update("INSERT INTO approval_policy (workspace_id, name, active) VALUES (?, 'unrelated', TRUE)",
            workspace.getId());
        jdbc.update("INSERT INTO approval_policy_step (workspace_id, policy_id, step_order, required_count) "
            + "WITH RECURSIVE numbers AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < 256) "
            + "SELECT p.workspace_id, p.id, n, 1 FROM approval_policy p CROSS JOIN numbers "
            + "WHERE p.workspace_id = ? AND p.name = 'unrelated'", workspace.getId());
        jdbc.update("INSERT INTO approval_policy_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT s.workspace_id, s.id, 'user', u.user_id FROM approval_policy_step s "
            + "JOIN approval_policy p ON p.id = s.policy_id CROSS JOIN (SELECT ? AS user_id UNION ALL SELECT ?) u "
            + "WHERE s.workspace_id = ? AND p.name = 'unrelated'", owner.getId(), survivor.getId(), workspace.getId());
        jdbc.update("INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status) "
            + "WITH RECURSIVE numbers AS (SELECT 2 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < 257) "
            + "SELECT ?, ?, n, 1, 'pending' FROM numbers", workspace.getId(), request.getId());
        jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT s.workspace_id, s.id, 'user', u.user_id FROM document_approval_step s "
            + "CROSS JOIN (SELECT ? AS user_id UNION ALL SELECT ?) u "
            + "WHERE s.approval_id = ? AND s.step_order > 1", owner.getId(), survivor.getId(), request.getId());
        jdbc.update("INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
            + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id) "
            + "SELECT s.workspace_id, s.approval_id, s.id, h.kind, 0, 'user', ?, h.delegator "
            + "FROM document_approval_step s CROSS JOIN (SELECT 'reassignment' AS kind, NULL AS delegator "
            + "UNION ALL SELECT 'escalation', NULL UNION ALL SELECT 'delegation', ?) h "
            + "WHERE s.approval_id = ? AND s.step_order > 1", survivor.getId(), owner.getId(), request.getId());
    }

    private DocumentApproval request(String stepStatus, String expiry) {
        DocumentApproval request = new DocumentApproval();
        request.setWorkspaceId(workspace.getId());
        request.setDealId(deal.getId());
        request.setDocumentId(document.getId());
        request.setStatus("pending");
        request.setMode("sequential");
        request.setSeparationOfDuties("off");
        request.setRequestedBy(owner.getId());
        request.setPolicyBinding("none");
        approvalMapper.insert(request);
        jdbc.update("INSERT INTO document_approval_step (workspace_id, approval_id, step_order, required_count, status, "
            + "due_interval_hours, on_expiry, activated_at, due_at) VALUES (?, ?, 1, 1, ?, ?, ?, "
            + "UTC_TIMESTAMP() - INTERVAL 2 HOUR, ?)", workspace.getId(), request.getId(), stepStatus,
            expiry == null ? null : 1, expiry == null ? "expire" : expiry,
            expiry == null ? null : "2020-01-01 00:00:00");
        jdbc.update("INSERT INTO document_approval_step_approver (workspace_id, step_id, approver_kind, user_id) "
            + "SELECT workspace_id, id, 'user', ? FROM document_approval_step WHERE approval_id = ?",
            target.getId(), request.getId());
        return request;
    }

    private int countPersonalGoals(int workspaceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_goal WHERE workspace_id = ? AND owner_id = ?",
            Integer.class, workspaceId, target.getId());
    }

    private Workspace workspace(String slug) {
        Workspace created = new Workspace();
        created.setOrgId(organization.getId());
        created.setName(slug);
        created.setSlug(slug);
        workspaceMapper.insert(created);
        return created;
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), user.getId(),
            user.getId() == owner.getId() ? "owner" : "admin", null);
    }
}
