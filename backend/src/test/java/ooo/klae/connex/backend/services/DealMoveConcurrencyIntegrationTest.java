package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealLineItem;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Verifies concurrent deal moves reconcile outcome from ascending exact-row locks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DealMoveConcurrencyIntegrationTest {

    @Autowired private DealService dealService;
    @Autowired private DealMapper dealMapper;
    @Autowired private DealLineItemMapper dealLineItemMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private DealMapper dealMapperSpy;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private final ThreadLocal<String> activeMove = new ThreadLocal<>();
    private Organization organization;
    private Workspace workspace;
    private User owner;
    private Pipeline pipeline;
    private Stage wonStage;
    private Stage lostStage;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Deal move " + unique);
        organization.setSlug("deal-move-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Deal move " + unique);
        workspace.setSlug("deal-move-" + unique);
        workspaceMapper.insert(workspace);

        owner = new User();
        owner.setUsername("deal-move-owner-" + unique);
        owner.setDisplayName("Deal move owner " + unique);
        owner.setEmail("deal-move-owner-" + unique + "@example.com");
        owner.setPasswordHash("hash-" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");

        pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Deal move pipeline " + unique);
        pipelineMapper.insertPipeline(pipeline);

        wonStage = stage("Won " + unique, 0, true, false);
        lostStage = stage("Lost " + unique, 1, false, true);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        activeMove.remove();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
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
    void concurrentLossThenWinDerivesRealizedValueFromLockedOutcome() throws Exception {
        Deal sourceSibling = deal("Source sibling", wonStage, 0, true, BigDecimal.ONE, "manual");
        Deal moving = deal(
            "Moving", wonStage, 1, true, new BigDecimal("5000000.00"), "line_items");
        Deal targetSibling = deal("Target sibling", lostStage, 0, false, BigDecimal.ZERO, "manual");
        lineItem(moving, new BigDecimal("5000000.00"));

        CountDownLatch winningReadCompleted = new CountDownLatch(1);
        CountDownLatch releaseWinningMove = new CountDownLatch(1);
        AtomicBoolean pauseWinningRead = new AtomicBoolean(true);
        Map<String, List<Integer>> lockOrder = new ConcurrentHashMap<>();
        DealMapper realDealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            Deal found = realDealMapper.getDealById(workspace.getId(), moving.getId());
            if ("winning".equals(activeMove.get()) && pauseWinningRead.compareAndSet(true, false)) {
                winningReadCompleted.countDown();
                assertTrue(releaseWinningMove.await(20, TimeUnit.SECONDS));
            }
            return found;
        }).when(dealMapperSpy).getDealById(workspace.getId(), moving.getId());
        doAnswer(invocation -> {
            String move = activeMove.get();
            int lockedDealId = invocation.getArgument(1);
            if (move != null) {
                lockOrder.computeIfAbsent(move, ignored -> new CopyOnWriteArrayList<>())
                    .add(lockedDealId);
            }
            return realDealMapper.getDealByIdForUpdate(workspace.getId(), lockedDealId);
        }).when(dealMapperSpy).getDealByIdForUpdate(eq(workspace.getId()), anyInt());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Deal> winning = executor.submit(
                () -> moveAs("winning", wonStage.getId(), moving.getId()));
            assertTrue(winningReadCompleted.await(10, TimeUnit.SECONDS));
            Future<Deal> losing = executor.submit(
                () -> moveAs("losing", lostStage.getId(), moving.getId()));
            losing.get(20, TimeUnit.SECONDS);

            Deal afterLoss = realDealMapper.getDealById(workspace.getId(), moving.getId());
            assertEquals(Boolean.FALSE, afterLoss.getWon());
            assertEquals(new BigDecimal("0.00"), afterLoss.getActualValue());

            releaseWinningMove.countDown();
            winning.get(20, TimeUnit.SECONDS);
        } finally {
            releaseWinningMove.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Deal afterWin = realDealMapper.getDealById(workspace.getId(), moving.getId());
        assertEquals(Boolean.TRUE, afterWin.getWon());
        assertEquals(new BigDecimal("5000000.00"), afterWin.getActualValue());
        assertEquals(
            List.of(sourceSibling.getId(), moving.getId(), targetSibling.getId()).stream()
                .sorted()
                .toList(),
            lockOrder.get("losing"));
        assertEquals(
            List.of(sourceSibling.getId(), moving.getId()).stream().sorted().toList(),
            lockOrder.get("winning"));
    }

    private Deal moveAs(String move, int stageId, int dealId) {
        activeMove.set(move);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        tenantContext.set(
            workspace.getId(), organization.getId(), owner.getId(), "owner", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, owner.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return dealService.move(dealId, stageId, 0);
        } finally {
            activeMove.remove();
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
    }

    private Stage stage(String name, int position, boolean success, boolean failure) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName(name);
        stage.setPosition(position);
        stage.setSuccess(success);
        stage.setFailure(failure);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal deal(
            String name, Stage stage, int position, Boolean won,
            BigDecimal actualValue, String valueSource) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(owner.getId());
        deal.setName(name + " " + UUID.randomUUID());
        deal.setValue(new BigDecimal("5000000.00"));
        deal.setActualValue(actualValue);
        deal.setValueSource(valueSource);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setPosition(position);
        deal.setWon(won);
        deal.setClosedAt(won == null ? null : "2026-08-02 12:00:00");
        dealMapper.insert(deal);
        return deal;
    }

    private void lineItem(Deal deal, BigDecimal total) {
        DealLineItem item = new DealLineItem();
        item.setWorkspaceId(workspace.getId());
        item.setDealId(deal.getId());
        item.setName("Line item " + UUID.randomUUID());
        item.setUnitPrice(total);
        item.setQuantity(BigDecimal.ONE);
        item.setBillingFrequency("one_time");
        item.setPosition(0);
        item.setCurrency(deal.getCurrency());
        item.setLineSubtotal(total);
        item.setLineTax(BigDecimal.ZERO);
        item.setLineTotal(total);
        dealLineItemMapper.insert(item);
    }
}
