package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DealLineItem;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DealLineItemDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealLineItemTotalsDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises monetary admission, approval reproducibility, and parent-lock currency isolation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DealMoneySecurityIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DealLineItemMapper lineItemMapper;
    @Autowired private DocumentTemplateMapper templateMapper;
    @Autowired private ApprovalPolicyMapper policyMapper;
    @Autowired private DealLineItemService lineItemService;
    @Autowired private DealService dealService;
    @Autowired private DealDocumentMapper documentMapper;
    @Autowired private DealValueService valueService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @MockitoSpyBean private DealMapper dealMapper;
    @MockitoSpyBean private ApprovalPolicyService policyService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private NotificationChangePublisher notificationChanges;

    private MockMvc mockMvc;
    private Organization organization;
    private Workspace workspace;
    private User actor;
    private User otherActor;
    private Pipeline pipeline;
    private Stage stage;
    private final ThreadLocal<String> operation = new ThreadLocal<>();

    @BeforeEach
    void setUp() {
        clearContext();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Money security " + suffix);
        organization.setSlug("money-security-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Money security " + suffix);
        workspace.setSlug("money-security-" + suffix);
        workspaceMapper.insert(workspace);
        actor = AuthenticatedSessions.account(userMapper, "money-actor");
        otherActor = AuthenticatedSessions.account(userMapper, "money-other");
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), otherActor.getId(), "member");
        pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName("Pipeline");
        pipelineMapper.insertPipeline(pipeline);
        stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Open");
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
    }

    @AfterEach
    void cleanUp() {
        clearContext();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM deal WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM approval_policy WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM document_template WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM stage WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM pipeline WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actor != null) jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        if (otherActor != null) jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", otherActor.getId());
        if (organization != null) jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void importRejectsUnboundedAndOverflowingValuesInPreviewAndCommit() {
        List<String> invalid = List.of("1E100000000", "1E-100000000", "1E2147483647",
            "1E-2147483647", "10000000000000", "9999999999999.995",
            "-9999999999999.995", "0." + "0".repeat(130) + "1", "0".repeat(128) + "1");
        ImportRequest request = importRequest(invalid.getFirst());
        request.setRows(invalid.stream()
            .map(value -> Map.of("Deal", "Import " + UUID.randomUUID(), "Value", value)).toList());
        assertTimeout(Duration.ofSeconds(10), () -> {
            JsonNode preview = json(mockMvc.perform(auth(post("/api/imports/deals/preview"), actor)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalid").value(invalid.size()))
                .andExpect(jsonPath("$.toCreate").value(0))
                .andReturn().getResponse().getContentAsString());
            request.setDuplicateReviewProof(preview.path("duplicateReviewProof").asString());
            mockMvc.perform(auth(post("/api/imports/deals"), actor)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.failed.length()").value(invalid.size()));
        });
        assertEquals(0, dealCount());
    }

    @Test
    void importRoundsHalfUpAndAcceptsLargestRoundedColumnValue() throws Exception {
        for (String value : List.of("1.005", "9,999,999,999,999.994", "1E-20")) {
            ImportRequest request = importRequest(value);
            JsonNode preview = json(mockMvc.perform(auth(post("/api/imports/deals/preview"), actor)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invalid").value(0))
                .andReturn().getResponse().getContentAsString());
            request.setDuplicateReviewProof(preview.path("duplicateReviewProof").asString());
            mockMvc.perform(auth(post("/api/imports/deals"), actor)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
            BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT value FROM deal WHERE workspace_id = ? AND name = ?", BigDecimal.class,
                workspace.getId(), request.getRows().getFirst().get("Deal"));
            String expected = value.equals("1.005") ? "1.01" : value.equals("1E-20") ? "0.00" : "9999999999999.99";
            assertEquals(new BigDecimal(expected), stored);
        }
    }

    @Test
    void excessivePriceIsRejectedAndPersistedOperandsKeepTheApprovalGate() throws Exception {
        Deal deal = deal("USD", false);
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspace.getId());
        policy.setName("Twenty percent");
        policy.setActive(true);
        policy.setMinDiscountPercent(new BigDecimal("20"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("strict");
        policyMapper.insert(policy);
        String path = "/api/deals/" + deal.getId();
        mockMvc.perform(auth(post(path + "/line-items"), actor).content(lineJson("1.004")))
            .andExpect(status().isBadRequest());
        assertEquals(0, lineItemMapper.countByDealId(workspace.getId(), deal.getId()));
        JsonNode created = json(mockMvc.perform(auth(post(path + "/line-items"), actor)
                .content(lineJson("1.00")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        DealLineItem stored = lineItemMapper.getByDealId(workspace.getId(), deal.getId()).getFirst();
        mockMvc.perform(auth(put(path + "/line-items/" + stored.getId()), actor).content(lineJson("1.004")))
            .andExpect(status().isBadRequest());
        assertEquals(new BigDecimal("800.00"), stored.getLineSubtotal());
        DealLineItemRequest echo = new DealLineItemRequest();
        echo.setQuantity(stored.getQuantity());
        echo.setUnitPrice(stored.getUnitPrice());
        echo.setDiscountType(stored.getDiscountType());
        echo.setDiscountValue(stored.getDiscountValue());
        echo.setTaxRate(stored.getTaxRate());
        mockMvc.perform(auth(put(path + "/line-items/" + created.path("items").get(0).path("id").asInt()), actor)
                .content(objectMapper.writeValueAsString(echo)))
            .andExpect(status().isOk());
        assertEquals(stored.getLineTotal(), lineItemMapper.getById(workspace.getId(), stored.getId()).getLineTotal());
        DocumentTemplate template = new DocumentTemplate();
        template.setWorkspaceId(workspace.getId());
        template.setName("Quote");
        template.setType("quote");
        template.setLocale("en");
        template.setTitle("Quote");
        templateMapper.insert(template);
        JsonNode document = json(mockMvc.perform(auth(post(path + "/documents"), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requiresApproval").value(true))
            .andReturn().getResponse().getContentAsString());
        mockMvc.perform(auth(put(path + "/documents/" + document.path("id").asInt() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void serviceRejectsEveryUnpersistableOperandBeforeCalculatingOrWriting() {
        Deal deal = deal("USD", false);
        authenticateService(actor);
        for (String field : List.of("unitPrice", "quantity", "discountValue", "taxRate")) {
            for (String value : List.of("1.0001", "1E1000", "1E-1000", "1E2147483647")) {
                DealLineItemRequest request = new DealLineItemRequest();
                request.setName("Service");
                request.setUnitPrice(BigDecimal.ONE);
                request.setQuantity(BigDecimal.ONE);
                BigDecimal operand = new BigDecimal(value);
                switch (field) {
                    case "unitPrice" -> request.setUnitPrice(operand);
                    case "quantity" -> request.setQuantity(operand);
                    case "discountValue" -> {
                        request.setDiscountType("amount");
                        request.setDiscountValue(operand);
                    }
                    case "taxRate" -> request.setTaxRate(operand);
                    default -> throw new IllegalStateException(field);
                }
                assertThrows(BadRequestException.class, () -> lineItemService.create(deal.getId(), request), field);
            }
        }
        assertEquals(0, lineItemMapper.countByDealId(workspace.getId(), deal.getId()));
    }

    @Test
    void reopenSerializesCurrencyChangeAtTheParentLock() throws Exception {
        Deal deal = deal("USD", true);
        CountDownLatch reopenRead = new CountDownLatch(1);
        CountDownLatch releaseReopen = new CountDownLatch(1);
        CountDownLatch currencyLockAttempt = new CountDownLatch(1);
        CountDownLatch currencyLockAcquired = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        DealMapper realMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            Deal found = realMapper.getDealById(workspace.getId(), deal.getId());
            if ("reopen".equals(operation.get()) && pause.compareAndSet(true, false)) {
                reopenRead.countDown();
                assertTrue(releaseReopen.await(30, TimeUnit.SECONDS));
            }
            return found;
        }).when(dealMapper).getDealById(workspace.getId(), deal.getId());
        doAnswer(invocation -> {
            if ("currency".equals(operation.get())) currencyLockAttempt.countDown();
            Deal found = realMapper.getDealByIdForUpdate(workspace.getId(), deal.getId());
            if ("currency".equals(operation.get())) currencyLockAcquired.countDown();
            if ("reopen".equals(operation.get()) && pause.compareAndSet(true, false)) {
                reopenRead.countDown();
                assertTrue(releaseReopen.await(30, TimeUnit.SECONDS));
            }
            return found;
        }).when(dealMapper).getDealByIdForUpdate(workspace.getId(), deal.getId());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var reopening = executor.submit(() -> {
                operation.set("reopen");
                try {
                    mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/reopen"), actor))
                        .andExpect(status().isOk());
                    return null;
                } finally {
                    clearContext();
                }
            });
            assertTrue(reopenRead.await(15, TimeUnit.SECONDS));
            var changingCurrency = executor.submit(() -> {
                operation.set("currency");
                try {
                    mockMvc.perform(auth(put("/api/deals/" + deal.getId()), otherActor)
                            .content(objectMapper.writeValueAsString(Map.of("name", "Changed concurrently",
                                "value", BigDecimal.ZERO, "currency", "JPY", "pipeline", pipeline.getId(),
                                "stage", stage.getId()))))
                        .andExpect(status().isOk());
                    mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/line-items"), otherActor)
                            .content("{\"name\":\"Service\",\"unitPrice\":100000,\"quantity\":1,\"taxRate\":0}"))
                        .andExpect(status().isOk());
                    return null;
                } finally {
                    clearContext();
                }
            });
            assertTrue(currencyLockAttempt.await(15, TimeUnit.SECONDS));
            assertFalse(currencyLockAcquired.await(5, TimeUnit.SECONDS), "Currency writer must wait on the parent deal");
            releaseReopen.countDown();
            reopening.get(30, TimeUnit.SECONDS);
            changingCurrency.get(30, TimeUnit.SECONDS);
        } finally {
            releaseReopen.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
        Deal stored = realMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("JPY", stored.getCurrency());
        assertEquals("Changed concurrently", stored.getName());
        assertEquals(stored.getCurrency(), lineItemMapper.getByDealId(workspace.getId(), deal.getId()).getFirst().getCurrency());
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isOk());
        Deal won = realMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("JPY", won.getCurrency());
        assertEquals(new BigDecimal("100000.00"), won.getActualValue());
    }

    @Test
    void mismatchedCurrencyCannotBeReconciledReopenedOrBooked() throws Exception {
        Deal deal = deal("USD", false);
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/line-items"), actor)
                .content("{\"name\":\"Service\",\"unitPrice\":100000,\"quantity\":1,\"taxRate\":0}"))
            .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE deal_line_item SET currency = 'JPY' WHERE workspace_id = ? AND deal_id = ?",
            workspace.getId(), deal.getId());
        Deal before = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertThrows(ConflictException.class, () -> valueService.canonicalValue(workspace.getId(), before));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(ConflictException.class, () -> transaction.execute(status -> {
            Deal locked = dealMapper.getDealByIdForUpdate(workspace.getId(), deal.getId());
            return valueService.reconcileLineItems(workspace.getId(), locked);
        }));
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/reopen"), actor))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isConflict());
        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertNull(after.getWon());
        assertEquals(before.getActualValue(), after.getActualValue());
        assertEquals(before.getValue(), after.getValue());
        assertEquals("USD", after.getCurrency());
    }

    @Test
    void twoForeignLinesCanBeDeletedOneAtATime() throws Exception {
        Deal deal = deal("USD", false);
        int first = addLine(deal, "10");
        int second = addLine(deal, "20");
        makeLinesForeign(deal);
        String path = "/api/deals/" + deal.getId();

        mockMvc.perform(auth(get(path + "/line-items"), actor))
            .andExpect(status().isOk()).andExpect(this::assertUnavailableTotals);
        mockMvc.perform(auth(delete(path + "/line-items/" + first), actor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(this::assertUnavailableTotals);
        assertEquals(1, lineItemMapper.countCurrencyMismatches(workspace.getId(), deal.getId(), "USD"));
        Deal partial = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(new BigDecimal("30.00"), partial.getValue());
        assertEquals(BigDecimal.ZERO.setScale(2), partial.getActualValue());
        assertThrows(ConflictException.class, () -> valueService.canonicalValue(workspace.getId(), partial));
        mockMvc.perform(auth(post(path + "/reopen"), actor)).andExpect(status().isConflict());
        mockMvc.perform(auth(post(path + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isConflict());

        mockMvc.perform(auth(delete(path + "/line-items/" + second), actor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.totals.grandTotal").value(0));
        assertFalse(lineItemMapper.hasCurrencyMismatch(workspace.getId(), deal.getId(), "USD"));
        Deal repaired = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("manual", repaired.getValueSource());
        assertEquals(new BigDecimal("30.00"), valueService.canonicalValue(workspace.getId(), repaired));
        addLine(deal, "7");
        mockMvc.perform(auth(post(path + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isOk());
        assertEquals(new BigDecimal("7.00"), dealMapper.getDealById(workspace.getId(), deal.getId()).getActualValue());
    }

    @Test
    void twoForeignLinesCanBeRewrittenOnlyWhenEachMutationReducesMismatches() throws Exception {
        Deal deal = deal("USD", false);
        int first = addLine(deal, "10");
        int second = addLine(deal, "20");
        makeLinesForeign(deal);
        String path = "/api/deals/" + deal.getId();

        mockMvc.perform(auth(put(path + "/line-items/" + first), actor)
                .content("{\"unitPrice\":11,\"quantity\":1}"))
            .andExpect(status().isOk()).andExpect(this::assertUnavailableTotals);
        assertEquals("USD", lineItemMapper.getById(workspace.getId(), first).getCurrency());
        assertEquals(1, lineItemMapper.countCurrencyMismatches(workspace.getId(), deal.getId(), "USD"));
        assertEquals(new BigDecimal("30.00"), dealMapper.getDealById(workspace.getId(), deal.getId()).getValue());

        mockMvc.perform(auth(put(path + "/line-items/" + first), actor)
                .content("{\"unitPrice\":99,\"quantity\":1}"))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(delete(path + "/line-items/" + first), actor))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(post(path + "/line-items"), actor)
                .content("{\"name\":\"Extra\",\"unitPrice\":1,\"quantity\":1}"))
            .andExpect(status().isConflict());
        assertEquals(2, lineItemMapper.countByDealId(workspace.getId(), deal.getId()));
        assertEquals(new BigDecimal("11.00"), lineItemMapper.getById(workspace.getId(), first).getUnitPrice());

        mockMvc.perform(auth(put(path + "/line-items/" + second), actor)
                .content("{\"unitPrice\":21,\"quantity\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totals.grandTotal").value(32));
        assertFalse(lineItemMapper.hasCurrencyMismatch(workspace.getId(), deal.getId(), "USD"));
        Deal repaired = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("line_items", repaired.getValueSource());
        assertEquals(new BigDecimal("32.00"), repaired.getValue());
        mockMvc.perform(auth(post(path + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isOk());
        assertEquals(new BigDecimal("32.00"), dealMapper.getDealById(workspace.getId(), deal.getId()).getActualValue());
    }

    @Test
    void mismatchedCurrencyCanBeClosedLostAndZeroesActualValue() throws Exception {
        Deal deal = deal("USD", false);
        addLine(deal, "10");
        String path = "/api/deals/" + deal.getId();
        mockMvc.perform(auth(post(path + "/close"), actor).content("{\"won\":true}"))
            .andExpect(status().isOk());
        assertEquals(new BigDecimal("10.00"), dealMapper.getDealById(workspace.getId(), deal.getId()).getActualValue());
        makeLinesForeign(deal);

        mockMvc.perform(auth(post(path + "/close"), actor).content("{\"won\":false,\"actualValue\":999}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.actualValue").value(0));

        Deal lost = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, lost.getWon());
        assertEquals(BigDecimal.ZERO.setScale(2), lost.getActualValue());
        assertEquals(new BigDecimal("10.00"), lost.getValue());
        assertTrue(lineItemMapper.hasCurrencyMismatch(workspace.getId(), deal.getId(), "USD"));
    }

    @Test
    void mismatchedCurrencyCannotGenerateADocumentSnapshot() throws Exception {
        Deal deal = deal("USD", false);
        int itemId = addLine(deal, "10");
        makeLinesForeign(deal);
        DocumentTemplate template = new DocumentTemplate();
        template.setWorkspaceId(workspace.getId());
        template.setName("Quote");
        template.setType("quote");
        template.setLocale("en");
        template.setTitle("Quote");
        templateMapper.insert(template);
        String path = "/api/deals/" + deal.getId();
        String request = objectMapper.writeValueAsString(Map.of("templateId", template.getId()));

        mockMvc.perform(auth(post(path + "/documents"), actor).content(request))
            .andExpect(status().isConflict());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_document WHERE workspace_id = ? AND deal_id = ?",
            Integer.class, workspace.getId(), deal.getId()));

        mockMvc.perform(auth(put(path + "/line-items/" + itemId), actor)
                .content("{\"unitPrice\":10,\"quantity\":1}"))
            .andExpect(status().isOk());
        mockMvc.perform(auth(post(path + "/documents"), actor).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.totals.grandTotal").value(10));
    }

    @Test
    void closeUsesCommittedLineTotalsAfterWaitingForParentLock() throws Exception {
        Deal deal = deal("USD", false);
        CountDownLatch lineWritten = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch closeLockAttempt = new CountDownLatch(1);
        CountDownLatch closeLockAcquired = new CountDownLatch(1);
        DealMapper realMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            if ("close".equals(operation.get())) {
                assertEquals(0, lineItemMapper.countByDealId(workspace.getId(), deal.getId()));
                closeLockAttempt.countDown();
            }
            Deal found = realMapper.getDealByIdForUpdate(workspace.getId(), deal.getId());
            if ("close".equals(operation.get())) closeLockAcquired.countDown();
            return found;
        }).when(dealMapper).getDealByIdForUpdate(workspace.getId(), deal.getId());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writingLine = executor.submit(() -> {
                authenticateService(otherActor);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        DealLineItemRequest request = new DealLineItemRequest();
                        request.setName("Committed service");
                        request.setUnitPrice(new BigDecimal("42"));
                        request.setQuantity(BigDecimal.ONE);
                        lineItemService.create(deal.getId(), request);
                        lineWritten.countDown();
                        awaitLatch(releaseWriter);
                    });
                    return null;
                } finally {
                    clearContext();
                }
            });
            assertTrue(lineWritten.await(15, TimeUnit.SECONDS));
            var closing = executor.submit(() -> {
                operation.set("close");
                try {
                    mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/close"), actor)
                            .content("{\"won\":true}"))
                        .andExpect(status().isOk());
                    return null;
                } finally {
                    clearContext();
                }
            });
            assertTrue(closeLockAttempt.await(15, TimeUnit.SECONDS));
            assertFalse(closeLockAcquired.await(5, TimeUnit.SECONDS), "Close must wait on the parent deal");
            releaseWriter.countDown();
            writingLine.get(30, TimeUnit.SECONDS);
            closing.get(30, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
        Deal closed = realMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, closed.getWon());
        assertEquals(new BigDecimal("42.00"), closed.getActualValue());
    }

    @Test
    void generationUsesLockedCurrencyAndTotalsAfterConcurrentCurrencyChange() throws Exception {
        Deal deal = deal("USD", false);
        DocumentTemplate template = quoteTemplate();
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspace.getId());
        policy.setName("JPY minimum");
        policy.setActive(true);
        policy.setCurrency("JPY");
        policy.setMinTotal(new BigDecimal("100000"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("strict");
        policyMapper.insert(policy);
        CountDownLatch firstParentRead = new CountDownLatch(1);
        CountDownLatch resumeGeneration = new CountDownLatch(1);
        CountDownLatch lineWritten = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch generationLockAttempt = new CountDownLatch(1);
        CountDownLatch generationLockAcquired = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        DealMapper realMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        doAnswer(invocation -> {
            Deal found = realMapper.getDealById(workspace.getId(), deal.getId());
            if ("generate".equals(operation.get()) && firstRead.compareAndSet(true, false)) {
                assertEquals("USD", found.getCurrency());
                firstParentRead.countDown();
                awaitLatch(resumeGeneration);
            }
            return found;
        }).when(dealMapper).getDealById(workspace.getId(), deal.getId());
        doAnswer(invocation -> {
            if ("generate".equals(operation.get())) generationLockAttempt.countDown();
            Deal found = realMapper.getDealByIdForUpdate(workspace.getId(), deal.getId());
            if ("generate".equals(operation.get())) generationLockAcquired.countDown();
            return found;
        }).when(dealMapper).getDealByIdForUpdate(workspace.getId(), deal.getId());
        var executor = Executors.newFixedThreadPool(2);
        String path = "/api/deals/" + deal.getId() + "/documents";
        try {
            var generating = executor.submit(() -> {
                operation.set("generate");
                try {
                    return json(mockMvc.perform(auth(post(path), actor)
                            .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                } finally {
                    clearContext();
                }
            });
            assertTrue(firstParentRead.await(15, TimeUnit.SECONDS));
            var writing = executor.submit(() -> {
                authenticateService(otherActor);
                try {
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    transaction.executeWithoutResult(status -> {
                        Deal changed = realMapper.getDealById(workspace.getId(), deal.getId());
                        changed.setCurrency("JPY");
                        dealService.update(deal.getId(), changed);
                        DealLineItemRequest request = new DealLineItemRequest();
                        request.setName("JPY service");
                        request.setUnitPrice(new BigDecimal("100000"));
                        request.setQuantity(BigDecimal.ONE);
                        lineItemService.create(deal.getId(), request);
                        lineWritten.countDown();
                        awaitLatch(releaseWriter);
                    });
                    return null;
                } finally {
                    clearContext();
                }
            });
            assertTrue(lineWritten.await(15, TimeUnit.SECONDS));
            resumeGeneration.countDown();
            assertTrue(generationLockAttempt.await(15, TimeUnit.SECONDS));
            assertFalse(generationLockAcquired.await(5, TimeUnit.SECONDS),
                "Generation must wait at the parent deal's locked statement");
            releaseWriter.countDown();
            writing.get(30, TimeUnit.SECONDS);
            JsonNode document = generating.get(30, TimeUnit.SECONDS);
            assertEquals("JPY", document.path("currency").asString());
            assertEquals("JPY", document.path("content").path("deal").path("currency").asString());
            assertEquals("JPY", document.path("content").path("totals").path("currency").asString());
            assertEquals("JPY", document.path("content").path("lineItems").get(0).path("currency").asString());
            assertEquals(100000, document.path("content").path("totals").path("grandTotal").asInt());
            assertTrue(document.path("requiresApproval").asBoolean());
            mockMvc.perform(auth(put(path + "/" + document.path("id").asInt() + "/status"), actor)
                    .content("{\"status\":\"final\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("JPY minimum")));
        } finally {
            resumeGeneration.countDown();
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void generationRefusesBlankStoredCurrenciesInsteadOfDefaultingTheDocumentToUsd() throws Exception {
        Deal deal = deal("USD", false);
        int itemId = addLine(deal, "10");
        Deal stored = dealMapper.getDealById(workspace.getId(), deal.getId());
        stored.setCurrency("");
        dealMapper.update(stored);
        DealLineItem line = lineItemMapper.getById(workspace.getId(), itemId);
        line.setCurrency("");
        lineItemMapper.update(line);
        DocumentTemplate template = quoteTemplate();

        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/documents"), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isConflict());
        assertTrue(documentMapper.getByDealId(workspace.getId(), deal.getId()).isEmpty());
    }

    @Test
    void generationRejectsHistoricalArithmeticSkewWithoutRewritingTheLine() throws Exception {
        Deal deal = deal("USD", false);
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/line-items"), actor)
                .content(lineJson("1.00")))
            .andExpect(status().isOk());
        DealLineItem line = lineItemMapper.getByDealId(workspace.getId(), deal.getId()).getFirst();
        line.setLineSubtotal(new BigDecimal("803.20"));
        line.setLineTotal(new BigDecimal("803.20"));
        lineItemMapper.update(line);
        DocumentTemplate template = quoteTemplate();

        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/documents"), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("Line item " + line.getId() + " (Service)")));

        assertTrue(documentMapper.getByDealId(workspace.getId(), deal.getId()).isEmpty());
        assertEquals(new BigDecimal("803.20"),
            lineItemMapper.getById(workspace.getId(), line.getId()).getLineSubtotal());
    }

    @Test
    void finalizationRejectsHistoricalArithmeticSkewInDraftAndApprovedSnapshots() throws Exception {
        Deal deal = deal("USD", false);
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/line-items"), actor)
                .content(lineJson("1.00")))
            .andExpect(status().isOk());
        DocumentTemplate template = quoteTemplate();
        String path = "/api/deals/" + deal.getId() + "/documents";
        mockMvc.perform(auth(post(path), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isOk());
        DealDocument historical = documentMapper.getByDealId(workspace.getId(), deal.getId()).getFirst();
        DocumentContent content = objectMapper.readValue(historical.getContent(), DocumentContent.class);
        var line = content.lineItems().getFirst();
        line.setLineSubtotal(new BigDecimal("803.20"));
        line.setLineTotal(new BigDecimal("803.20"));
        BigDecimal historicalTotal = new BigDecimal("803.20");
        DocumentContent historicalContent = new DocumentContent(
            content.generatedAt(), content.workspace(), content.company(), content.owner(),
            content.deal(), content.sections(), content.body(), content.lineItems(),
            new DealLineItemTotalsDto("USD", historicalTotal, BigDecimal.ZERO,
                historicalTotal, BigDecimal.ZERO, historicalTotal));
        historical.setId(0);
        historical.setVersion(2);
        historical.setContent(objectMapper.writeValueAsString(historicalContent));
        documentMapper.insert(historical);

        for (String source : List.of("draft", "approved")) {
            documentMapper.updateStatus(workspace.getId(), historical.getId(), source);
            mockMvc.perform(auth(put(path + "/" + historical.getId() + "/status"), actor)
                    .content("{\"status\":\"final\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Line item " + line.getId() + " (Service)")));
            DealDocument unchanged = documentMapper.getById(workspace.getId(), historical.getId());
            assertEquals(source, unchanged.getStatus());
            assertEquals(json(historical.getContent()), json(unchanged.getContent()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "JPY"})
    void generatesAndFinalizesAnEmptyDocumentInTheLockedDealCurrency(String currency) throws Exception {
        Deal deal = deal(currency, false);
        DocumentTemplate template = quoteTemplate();
        String path = "/api/deals/" + deal.getId() + "/documents";

        mockMvc.perform(auth(post(path), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.currency").value(currency))
            .andExpect(jsonPath("$.content.lineItems").isEmpty())
            .andExpect(jsonPath("$.content.totals.currency").value(currency))
            .andExpect(jsonPath("$.content.totals.grandTotal").value(0));
        DealDocument generated = documentMapper.getByDealId(workspace.getId(), deal.getId()).getFirst();
        DocumentContent content = objectMapper.readValue(generated.getContent(), DocumentContent.class);
        DealLineItemTotalsDto totals = content.totals();
        assertEquals(currency, totals.currency());
        for (BigDecimal amount : List.of(totals.subtotal(), totals.tax(), totals.oneTimeTotal(),
                totals.recurringTotal(), totals.grandTotal())) {
            assertEquals(0, amount.signum());
        }

        mockMvc.perform(auth(put(path + "/" + generated.getId() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("final"))
            .andExpect(jsonPath("$.content.totals.currency").value(currency));
        DealDocument finalized = documentMapper.getById(workspace.getId(), generated.getId());
        assertEquals("final", finalized.getStatus());
        assertEquals(json(generated.getContent()), json(finalized.getContent()));
        verify(ruleTriggers).publish(workspace.getId(), "document", generated.getId(), "document.finalized");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "approved"})
    void finalizesHistoricalEmptyNullCurrencyTotalsOnlyWhenEveryAmountIsZero(String source) throws Exception {
        Deal deal = deal("JPY", false);
        BigDecimal zero = new BigDecimal("0.00");
        DocumentContent content = new DocumentContent(
            "2026-09-01T00:00:00", new DocumentContent.PartyRef(workspace.getName(), null), null, null,
            new DocumentContent.DealRef(deal.getName(), "USD"),
            new DocumentContent.Sections("Quote", null, null, null), null, List.of(),
            new DealLineItemTotalsDto(null, zero, zero, zero, zero, zero));
        DealDocument historical = frozenDocument(deal, source, 1, "USD", content);
        String path = "/api/deals/" + deal.getId() + "/documents/";

        mockMvc.perform(auth(put(path + historical.getId() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("final"))
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.content.lineItems").isEmpty());
        DealDocument finalized = documentMapper.getById(workspace.getId(), historical.getId());
        assertEquals("final", finalized.getStatus());
        assertEquals(json(historical.getContent()), json(finalized.getContent()));
        assertNull(objectMapper.readValue(finalized.getContent(), DocumentContent.class).totals().currency());
        assertEquals("JPY", dealMapper.getDealById(workspace.getId(), deal.getId()).getCurrency());

        int version = 2;
        for (int field = 0; field < 5; field++) {
            for (BigDecimal invalid : new BigDecimal[] {null, BigDecimal.ONE}) {
                BigDecimal[] amounts = {zero, zero, zero, zero, zero};
                amounts[field] = invalid;
                DocumentContent inconsistent = new DocumentContent(
                    content.generatedAt(), content.workspace(), content.company(), content.owner(),
                    content.deal(), content.sections(), content.body(), content.lineItems(),
                    new DealLineItemTotalsDto(null, amounts[0], amounts[1], amounts[2], amounts[3], amounts[4]));
                DealDocument rejected = frozenDocument(deal, source, version++, "USD", inconsistent);
                clearInvocations(policyService);

                mockMvc.perform(auth(put(path + rejected.getId() + "/status"), actor)
                        .content("{\"status\":\"final\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", containsString("Document totals")));
                verify(policyService, never()).firstMatch(
                    anyList(), any(DealDocument.class), any(DocumentContent.class));
                DealDocument unchanged = documentMapper.getById(workspace.getId(), rejected.getId());
                assertEquals(source, unchanged.getStatus());
                assertEquals(json(rejected.getContent()), json(unchanged.getContent()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "approved"})
    void finalizationRejectsHistoricalCurrencySkewBeforeApproval(String source) throws Exception {
        Deal deal = deal("JPY", false);
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspace.getId());
        policy.setName("JPY minimum");
        policy.setActive(true);
        policy.setCurrency("JPY");
        policy.setMinTotal(new BigDecimal("100000"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("strict");
        policyMapper.insert(policy);
        DealDocument historical = frozenDocument(deal, source, 1, "USD", "USD", "JPY", "JPY");
        String path = "/api/deals/" + deal.getId() + "/documents/";
        clearInvocations(policyService);

        mockMvc.perform(auth(put(path + historical.getId() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("Line item 1 currency JPY")))
            .andExpect(jsonPath("$.message", containsString("document currency USD")));
        verify(policyService, never()).firstMatch(anyList(), any(DealDocument.class), any(DocumentContent.class));
        DealDocument unchanged = documentMapper.getById(workspace.getId(), historical.getId());
        assertEquals(source, unchanged.getStatus());
        assertEquals("USD", unchanged.getCurrency());
        assertEquals(json(historical.getContent()), json(unchanged.getContent()));

        DealDocument consistent = frozenDocument(deal, source, 2, "USD", "USD", "USD", "USD");
        mockMvc.perform(auth(put(path + consistent.getId() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("final"))
            .andExpect(jsonPath("$.currency").value("USD"));
        DealDocument finalized = documentMapper.getById(workspace.getId(), consistent.getId());
        assertEquals("final", finalized.getStatus());
        assertEquals(json(consistent.getContent()), json(finalized.getContent()));
        assertEquals("JPY", dealMapper.getDealById(workspace.getId(), deal.getId()).getCurrency());

        DealDocument requiresApproval = frozenDocument(deal, source, 3, "JPY", "JPY", "JPY", "JPY");
        clearInvocations(policyService);
        var request = mockMvc.perform(auth(put(path + requiresApproval.getId() + "/status"), actor)
            .content("{\"status\":\"final\"}"));
        if ("draft".equals(source)) {
            request.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("JPY minimum")));
            verify(policyService).firstMatch(anyList(), any(DealDocument.class), any(DocumentContent.class));
            assertEquals("draft", documentMapper.getById(workspace.getId(), requiresApproval.getId()).getStatus());
        } else {
            request.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("final"));
            verify(policyService, never()).firstMatch(anyList(), any(DealDocument.class), any(DocumentContent.class));
            assertEquals("final", documentMapper.getById(workspace.getId(), requiresApproval.getId()).getStatus());
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
        "USD, JPY, USD, USD, Frozen deal",
        "USD, USD, JPY, USD, Line item 1",
        "USD, USD, USD, JPY, Document totals",
        "USD, NULL, USD, USD, Frozen deal",
        "USD, USD, NULL, USD, Line item 1",
        "USD, USD, USD, NULL, Document totals",
        "'', USD, USD, USD, Document currency"
    }, nullValues = "NULL")
    void finalizationRequiresEveryFrozenCurrency(String documentCurrency, String dealCurrency,
            String lineCurrency, String totalsCurrency, String mismatch) throws Exception {
        Deal deal = deal("USD", false);
        DealDocument historical = frozenDocument(
            deal, "draft", 1, documentCurrency, dealCurrency, lineCurrency, totalsCurrency);
        clearInvocations(policyService);

        mockMvc.perform(auth(put("/api/deals/" + deal.getId() + "/documents/" + historical.getId() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString(mismatch)));
        verify(policyService, never()).firstMatch(anyList(), any(DealDocument.class), any(DocumentContent.class));
        DealDocument unchanged = documentMapper.getById(workspace.getId(), historical.getId());
        assertEquals("draft", unchanged.getStatus());
        assertEquals(documentCurrency, unchanged.getCurrency());
        assertEquals(json(historical.getContent()), json(unchanged.getContent()));
    }

    @Test
    void discountApprovalUsesEffectiveRoundedDiscountAtTheSubcentBoundary() throws Exception {
        Deal deal = deal("USD", false);
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setWorkspaceId(workspace.getId());
        policy.setName("Any positive effective discount");
        policy.setActive(true);
        policy.setMinDiscountPercent(new BigDecimal("0.001"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("strict");
        policyMapper.insert(policy);
        String path = "/api/deals/" + deal.getId();
        mockMvc.perform(auth(post(path + "/line-items"), actor)
                .content("{\"name\":\"Subcent\",\"unitPrice\":0.01,\"quantity\":1,"
                    + "\"discountType\":\"percent\",\"discountValue\":20}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].lineSubtotal").value(0.01));
        DocumentTemplate template = quoteTemplate();
        JsonNode document = json(mockMvc.perform(auth(post(path + "/documents"), actor)
                .content(objectMapper.writeValueAsString(Map.of("templateId", template.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiresApproval").value(false))
            .andReturn().getResponse().getContentAsString());
        mockMvc.perform(auth(put(path + "/documents/" + document.path("id").asInt() + "/status"), actor)
                .content("{\"status\":\"final\"}"))
            .andExpect(status().isOk());
    }

    private DealDocument frozenDocument(Deal deal, String source, int version, String documentCurrency,
            String dealCurrency, String lineCurrency, String totalsCurrency) {
        BigDecimal amount = new BigDecimal("100000.00");
        DealLineItemDto line = new DealLineItemDto();
        line.setId(1);
        line.setName("Service");
        line.setCurrency(lineCurrency);
        line.setUnitPrice(amount);
        line.setQuantity(BigDecimal.ONE);
        line.setDiscountType("percent");
        line.setDiscountValue(BigDecimal.ZERO);
        line.setTaxRate(BigDecimal.ZERO);
        line.setBillingFrequency("one_time");
        line.setLineSubtotal(amount);
        line.setLineTax(BigDecimal.ZERO);
        line.setLineTotal(amount);
        DocumentContent content = new DocumentContent(
            "2026-09-01T00:00:00", new DocumentContent.PartyRef(workspace.getName(), null), null, null,
            new DocumentContent.DealRef(deal.getName(), dealCurrency),
            new DocumentContent.Sections("Quote", null, null, null), null, List.of(line),
            new DealLineItemTotalsDto(totalsCurrency, amount, BigDecimal.ZERO, amount, BigDecimal.ZERO, amount));
        return frozenDocument(deal, source, version, documentCurrency, content);
    }

    private DealDocument frozenDocument(Deal deal, String source, int version, String documentCurrency,
            DocumentContent content) {
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus(source);
        document.setVersion(version);
        document.setTitle("Quote");
        document.setContent(objectMapper.writeValueAsString(content));
        document.setCurrency(documentCurrency);
        document.setCreatedBy(actor.getId());
        documentMapper.insert(document);
        return document;
    }

    private void assertUnavailableTotals(MvcResult result) throws Exception {
        assertTrue(json(result.getResponse().getContentAsString()).path("totals").isNull(),
            "Unavailable totals must be an explicit JSON null, not an omitted property");
    }

    private DocumentTemplate quoteTemplate() {
        DocumentTemplate template = new DocumentTemplate();
        template.setWorkspaceId(workspace.getId());
        template.setName("Quote");
        template.setType("quote");
        template.setLocale("en");
        template.setTitle("Quote");
        templateMapper.insert(template);
        return template;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int addLine(Deal deal, String price) throws Exception {
        mockMvc.perform(auth(post("/api/deals/" + deal.getId() + "/line-items"), actor)
                .content(objectMapper.writeValueAsString(Map.of(
                    "name", "Service", "unitPrice", new BigDecimal(price), "quantity", BigDecimal.ONE))))
            .andExpect(status().isOk());
        return lineItemMapper.getByDealId(workspace.getId(), deal.getId()).getLast().getId();
    }

    private void makeLinesForeign(Deal deal) {
        jdbcTemplate.update("UPDATE deal_line_item SET currency = 'JPY' WHERE workspace_id = ? AND deal_id = ?",
            workspace.getId(), deal.getId());
    }

    private ImportRequest importRequest(String value) {
        return new ImportRequest(List.of(Map.of("Deal", "Import " + UUID.randomUUID(), "Value", value)),
            List.of(new ColumnMapping("Deal", "name", null, null, null),
                new ColumnMapping("Value", "value", null, null, null)), "skip", null);
    }

    private Deal deal(String currency, boolean closed) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(actor.getId());
        deal.setName("Deal " + UUID.randomUUID());
        deal.setCurrency(currency);
        deal.setValue(BigDecimal.ZERO);
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        if (closed) {
            deal.setWon(false);
            deal.setClosedAt("2026-09-01 00:00:00");
        }
        dealMapper.insert(deal);
        return deal;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("X-Workspace-Id", workspace.getId())
            .session(AuthenticatedSessions.stampedSession(user))
            .with(authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())))
            .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON);
    }

    private void authenticateService(User user) {
        clearContext();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), user.getId(), "member", null);
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        operation.remove();
    }

    private int dealCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deal WHERE workspace_id = ?",
            Integer.class, workspace.getId());
        return count == null ? 0 : count;
    }

    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    private String lineJson(String price) {
        return "{\"name\":\"Service\",\"unitPrice\":" + price
            + ",\"quantity\":1000,\"discountType\":\"percent\",\"discountValue\":20,\"taxRate\":0}";
    }
}
