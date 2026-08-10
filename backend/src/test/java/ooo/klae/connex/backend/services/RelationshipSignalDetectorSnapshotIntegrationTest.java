package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies detector reads remain one real MySQL snapshot across concurrent mutations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RelationshipSignalDetectorSnapshotIntegrationTest {
    @Autowired private RelationshipSignalDetectorService detector;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private ScoringService scoringService;
    @MockitoBean private DealRiskService dealRiskService;

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private Company company;
    private Pipeline pipeline;
    private Stage stage;
    private Deal deal;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Radar snapshot " + unique);
        organization.setSlug("radar-snapshot-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Radar snapshot " + unique);
        workspace.setSlug("radar-snapshot-" + unique);
        workspaceMapper.insert(workspace);

        owner = new User();
        owner.setUsername("radar_snapshot_" + unique);
        owner.setDisplayName("Radar snapshot " + unique);
        owner.setEmail("radar_snapshot_" + unique + "@example.com");
        owner.setPasswordHash("hash_" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");

        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Snapshot company " + unique);
        companyMapper.insert(company);

        pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Snapshot pipeline " + unique);
        pipelineMapper.insertPipeline(pipeline);

        stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Snapshot stage " + unique);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);

        deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(owner.getId());
        deal.setName("Before concurrent mutation");
        deal.setValue(BigDecimal.TEN);
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);

        DealRiskDto risk = new DealRiskDto(
            deal.getId(),
            BigDecimal.TEN,
            "USD",
            "high",
            80,
            List.of(new DealRiskFactor("stalled", "high", Map.of())),
            "2026-08-10 12:00:00");
        when(dealRiskService.assessWorkspaceNotificationStates(
                eq(workspace.getId()), anyMap(), anyMap()))
            .thenReturn(List.of(new DealRiskService.NotificationRiskState(
                risk, "a".repeat(64))));
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM activity WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person_edge WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void concurrentMutationBetweenDetectorReadsCannotCreateACompositeSignal() throws Exception {
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch releaseDetector = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object scores = invocation.callRealMethod();
            firstReadCompleted.countDown();
            if (!releaseDetector.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Detector snapshot read did not resume");
            }
            return scores;
        }).when(scoringService).scoreWorkspace(workspace.getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RelationshipSignalDetectorService.Detection> detection = executor.submit(() ->
                tenantWorkScope.inWorkspace(
                    workspace.getId(),
                    () -> detector.detectDealRisk(workspace.getId(), "snapshot")));
            assertTrue(firstReadCompleted.await(20, TimeUnit.SECONDS));
            tenantWorkScope.inWorkspace(workspace.getId(), () ->
                dealMapper.updateName(workspace.getId(), deal.getId(), "After concurrent mutation"));
            releaseDetector.countDown();

            assertEquals(
                "Before concurrent mutation",
                detection.get(20, TimeUnit.SECONDS).candidates().getFirst().getSubjectLabel());
            assertEquals(
                "After concurrent mutation",
                dealMapper.getDealById(workspace.getId(), deal.getId()).getName());
        } finally {
            releaseDetector.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentMutationBetweenWarmPathReadsCannotCreateACompositeSignal() throws Exception {
        Person bridge = new Person();
        bridge.setWorkspaceId(workspace.getId());
        bridge.setName("Warm bridge before mutation");
        personMapper.insert(bridge);
        Person target = new Person();
        target.setWorkspaceId(workspace.getId());
        target.setName("Warm target before mutation");
        personMapper.insert(target);

        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Snapshot engagement");
        activity.setPerson(bridge);
        activity.setCreatedBy(owner);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        activityMapper.insert(activity);

        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(bridge.getId(), target.getId()));
        edge.setTargetPersonId(Math.max(bridge.getId(), target.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);

        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch releaseDetector = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object scores = invocation.callRealMethod();
            firstReadCompleted.countDown();
            if (!releaseDetector.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Warm-path snapshot read did not resume");
            }
            return scores;
        }).when(scoringService).scoreContacts(workspace.getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RelationshipSignalDetectorService.Detection> detection = executor.submit(() ->
                tenantWorkScope.inWorkspace(
                    workspace.getId(),
                    () -> detector.detectWarmPaths(
                        workspace.getId(), "warm-snapshot", Instant.now())));
            assertTrue(firstReadCompleted.await(20, TimeUnit.SECONDS));
            jdbcTemplate.update(
                "UPDATE person SET name = ? WHERE workspace_id = ? AND id = ?",
                "Warm target after mutation",
                workspace.getId(),
                target.getId());
            releaseDetector.countDown();

            assertEquals(
                "Warm target before mutation",
                detection.get(20, TimeUnit.SECONDS).candidates().getFirst().getSubjectLabel());
            assertEquals(
                "Warm target after mutation",
                personMapper.getPersonById(workspace.getId(), target.getId()).getName());
        } finally {
            releaseDetector.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
