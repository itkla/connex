package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sun.net.httpserver.HttpServer;

import ooo.klae.connex.backend.ai.AiBudgetControlAccess;
import ooo.klae.connex.backend.ai.AiBudgetControlOperations;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiMediaAdmissionService;
import ooo.klae.connex.backend.ai.AiOrganizationBudgetCoordinator;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptBudget;
import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleAdapter;
import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleClient;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiOrganizationBudgetMapperTest extends AbstractMapperTest {
    private static final String SCRATCH_CATALOG =
            "connex_ai_budget_it_" + UUID.randomUUID().toString().replace("-", "");
    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean catalogCreated;

    @MockitoSpyBean private AiOrganizationBudgetMapper budgetMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private AiBudgetControlOperations operations;
    @Autowired private AiBudgetControlAccess controlAccess;
    @Autowired private AiOrganizationBudgetCoordinator coordinator;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @BeforeAll
    static void createBudgetCatalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
                "CONNEX_DB_URL",
                "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
                "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping AI budget mapper test");
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        String prefix = base.substring(0, base.lastIndexOf('/') + 1);
        bootstrapUrl = prefix + "mysql" + query;
        scratchUrl = prefix + SCRATCH_CATALOG + query;
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            catalogCreated = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                    "Cannot create AI budget scratch catalog: " + exception.getMessage());
        }
    }

    /** Isolates the global empty-table drill and pins the default inherited by REQUIRES_NEW. */
    @DynamicPropertySource
    static void isolateBudgetCatalog(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> Objects.requireNonNull(scratchUrl));
        registry.add("spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    }

    @AfterAll
    static void dropBudgetCatalog() throws SQLException {
        if (catalogCreated) {
            try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
            }
        }
    }

    @Test
    void sharedOrganizationBudgetAndReservationsRoundTrip() {
        Organization organization = new Organization();
        organization.setName("Budget Organization " + unique());
        organization.setSlug("budget-org-" + unique());
        organizationMapper.insert(organization);
        int orgId = organization.getId();
        LocalDate day = LocalDate.of(2026, 8, 10);
        budgetMapper.upsert(orgId, 1_000);
        budgetMapper.ensureUsage(orgId, day);
        budgetMapper.insertReservation(
                "2cf6d5a4-e640-4c67-9908-726933adaad2",
                orgId,
                day,
                300,
                LocalDateTime.of(2026, 8, 10, 1, 0));

        assertEquals(1_000, budgetMapper.getForUpdate(orgId).getDailyTokenLimit());
        assertNotNull(budgetMapper.getUsageForUpdate(orgId, day));
        assertEquals(300, budgetMapper.sumReservedTokens(orgId, day));

        budgetMapper.addConsumedTokens(orgId, day, 125);

        assertEquals(125, budgetMapper.getConsumedTokens(orgId, day));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredReservationsRemainBudgetedUntilTheScheduledSweep() {
        int orgId = newOrganization();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate day = now.toLocalDate();
        String expiredId = UUID.randomUUID().toString();
        String activeId = UUID.randomUUID().toString();
        budgetMapper.upsert(orgId, 100);
        budgetMapper.insertReservation(expiredId, orgId, day, 60, now.minusMinutes(1));
        budgetMapper.insertReservation(activeId, orgId, day, 40, now.plusMinutes(10));
        try {
            assertThrows(AiBudgetExhaustedException.class, () -> operations.reserve(
                    orgId, day, 1, UUID.randomUUID().toString(), now, now.plusMinutes(10)));

            coordinator.sweepExpiredReservations();

            assertEquals(40, budgetMapper.sumReservedTokens(orgId, day));
            assertEquals(0, budgetMapper.getConsumedTokens(orgId, day));
            assertNull(budgetMapper.getReservation(expiredId));
            assertNotNull(budgetMapper.getReservationForUpdate(activeId));
        } finally {
            budgetMapper.deleteReservation(expiredId);
            budgetMapper.deleteReservation(activeId);
        }
    }

    /** Holds competing organization admissions at the actual reservation insertion boundary. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void differentOrganizationsReserveConcurrentlyAtRepeatableReadWithoutGapLockDeadlock()
            throws Exception {
        assertEquals(SCRATCH_CATALOG, jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        jdbcTemplate.execute("TRUNCATE TABLE organization_ai_budget_reservation");
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_ai_budget_reservation", Long.class));
        int firstOrg = newOrganization();
        int secondOrg = newOrganization();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate day = now.toLocalDate();
        for (int orgId : new int[] {firstOrg, secondOrg}) {
            budgetMapper.upsert(orgId, 100);
            budgetMapper.ensureUsage(orgId, day);
            assertEquals(0, budgetMapper.sumReservedTokens(orgId, day));
        }
        CyclicBarrier insertionsReady = new CyclicBarrier(2);
        AiOrganizationBudgetMapper realMapper =
                sqlSessionTemplate.getMapper(AiOrganizationBudgetMapper.class);
        doAnswer(call -> {
            assertRepeatableRead();
            insertionsReady.await(10, TimeUnit.SECONDS);
            return realMapper.insertReservation(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4));
        }).when(budgetMapper).insertReservation(
                anyString(), anyInt(), any(LocalDate.class), anyLong(), any(LocalDateTime.class));
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> operations.reserve(
                    firstOrg, day, 60, firstId, now, now.plusMinutes(10)));
            var second = executor.submit(() -> operations.reserve(
                    secondOrg, day, 60, secondId, now, now.plusMinutes(10)));

            assertTrue(first.get(20, TimeUnit.SECONDS).metered());
            assertTrue(second.get(20, TimeUnit.SECONDS).metered());
            for (int orgId : new int[] {firstOrg, secondOrg}) {
                assertEquals(60, budgetMapper.sumReservedTokens(orgId, day));
                assertEquals(0, budgetMapper.getConsumedTokens(orgId, day));
                assertThrows(AiBudgetExhaustedException.class, () -> operations.reserve(
                        orgId, day, 41, UUID.randomUUID().toString(), now, now.plusMinutes(10)));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            budgetMapper.deleteReservation(firstId);
            budgetMapper.deleteReservation(secondId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancellingStreamsAfterOutputConsumesTheCeilingAndExhaustsTheDailyLedger() throws Exception {
        int orgId = newOrganization();
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        budgetMapper.upsert(orgId, 10_000);
        AiOrganizationBudgetMapper realMapper =
                sqlSessionTemplate.getMapper(AiOrganizationBudgetMapper.class);
        AtomicReference<String> reservationId = new AtomicReference<>();
        doAnswer(call -> {
            reservationId.set(call.getArgument(0));
            return realMapper.insertReservation(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
        }).when(budgetMapper).insertReservation(anyString(), org.mockito.ArgumentMatchers.eq(orgId),
                any(LocalDate.class), anyLong(), any(LocalDateTime.class));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverExecutor = Executors.newSingleThreadExecutor();
        server.setExecutor(serverExecutor);
        List<CountDownLatch> finishResponses = List.of(new CountDownLatch(1), new CountDownLatch(1));
        AtomicInteger modelRequests = new AtomicInteger();
        AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                int requestIndex = modelRequests.getAndIncrement();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"content\":"
                        + "\"Measured partial output\"},\"finish_reason\":null}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                assertTrue(finishResponses.get(requestIndex).await(30, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fixtureFailure.set(exception);
            } catch (Exception | AssertionError exception) {
                fixtureFailure.set(exception);
            } finally {
                exchange.close();
            }
        });
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        AiProperties properties = new AiProperties();
        AiProperties.ModelOverride model = new AiProperties.ModelOverride();
        model.setProvider("openai_compatible");
        model.setModelId("test-model");
        model.setEndpoint(endpoint);
        model.setStreaming(true);
        model.setContextWindowTokens(AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS);
        model.setMaxOutputTokens(4_096);
        properties.setModelOverrides(List.of(model));
        AiEndpointAddressValidator validator = mock(AiEndpointAddressValidator.class);
        when(validator.resolveFetchable("127.0.0.1", true))
                .thenReturn(InetAddress.getByName("127.0.0.1"));
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(properties, validator);
        AiProvider provider = new OpenAiCompatibleAdapter(client, new ObjectMapper(), properties);
        ResolvedAiProvider resolved = new ResolvedAiProvider(
                "openai_compatible", null, "test-model", endpoint, null, null, null,
                true, false, AiPrivacyMode.UNMASKED, AiCredentials.of(Map.of()));
        AiInvocationService invocationService = invocationService(orgId, provider, resolved);

        long reservedCeiling = 0;
        var executor = Executors.newSingleThreadExecutor();
        try {
            server.start();
            for (int turn = 1; turn <= 2; turn++) {
                CountDownLatch outputReceived = new CountDownLatch(1);
                AtomicReference<Runnable> cancellation = new AtomicReference<>();
                AtomicReference<String> output = new AtomicReference<>();
                AiInvocation invocation = streamingInvocation(new AiProviderStreamObserver() {
                    @Override
                    public void onTransportOpen(Runnable abort) {
                        cancellation.set(abort);
                    }

                    @Override
                    public void onContentDelta(String text) {
                        output.set(text);
                        outputReceived.countDown();
                    }
                });
                var generation = executor.submit(() -> invocationService.complete(invocation));
                assertTrue(outputReceived.await(10, TimeUnit.SECONDS));
                assertEquals("Measured partial output", output.get());
                String id = Objects.requireNonNull(reservationId.get());
                assertEquals("dispatched", Objects.requireNonNull(budgetMapper.getReservation(id)).getState());
                long reserved = budgetMapper.sumReservedTokens(orgId, day);
                assertTrue(reserved >= invocation.maxTokens());
                if (turn == 1) {
                    reservedCeiling = reserved;
                    budgetMapper.upsert(orgId, reservedCeiling * 2);
                }
                assertEquals(reservedCeiling, reserved);

                Objects.requireNonNull(cancellation.get(), "transport cancellation").run();

                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> generation.get(10, TimeUnit.SECONDS));
                assertInstanceOf(AiProviderException.class, failure.getCause());
                assertEquals(reservedCeiling * turn, budgetMapper.getConsumedTokens(orgId, day));
                assertEquals(0, budgetMapper.sumReservedTokens(orgId, day));
                var settled = Objects.requireNonNull(budgetMapper.getReservation(id));
                assertEquals("settled", settled.getState());
                assertEquals(Long.valueOf(reservedCeiling), settled.getConsumedTokens());
                finishResponses.get(turn - 1).countDown();
            }
            assertThrows(AiBudgetExhaustedException.class, () -> invocationService.complete(
                    streamingInvocation(text -> {})));
            assertEquals(2, modelRequests.get());
        } finally {
            finishResponses.forEach(CountDownLatch::countDown);
            ReflectionTestUtils.invokeMethod(client, "shutdown");
            executor.shutdownNow();
            server.stop(0);
            serverExecutor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(serverExecutor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(fixtureFailure.get());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedCancellationSettlementsRemainChargedAfterExpiryAndConcurrentLateSettlement()
            throws Exception {
        int orgId = newOrganization();
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        budgetMapper.upsert(orgId, 10_000);
        AiOrganizationBudgetMapper realMapper =
                sqlSessionTemplate.getMapper(AiOrganizationBudgetMapper.class);
        AtomicReference<String> reservationId = new AtomicReference<>();
        doAnswer(call -> {
            reservationId.set(call.getArgument(0));
            return realMapper.insertReservation(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
        }).when(budgetMapper).insertReservation(anyString(), org.mockito.ArgumentMatchers.eq(orgId),
                any(LocalDate.class), anyLong(), any(LocalDateTime.class));
        AtomicInteger settlementWrites = new AtomicInteger();
        doAnswer(call -> {
            assertRepeatableRead();
            if (settlementWrites.incrementAndGet() <= 2) {
                throw new IllegalStateException("Settlement write unavailable");
            }
            return realMapper.markReservationSettled(call.getArgument(0), call.getArgument(1));
        }).when(budgetMapper).markReservationSettled(anyString(), anyLong());
        CountDownLatch outputReceived = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AiProvider provider = mock(AiProvider.class);
        AiInvocationService invocationService = invocationService(orgId, provider);
        when(provider.completeStreaming(any(AiCompletionRequest.class), any())).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            AiProviderStreamObserver observer = call.getArgument(1);
            return request.providerAttemptExecutor().executeStream(() -> {
                request.providerAttemptExecutor().beforeSend();
                observer.onContentDelta("Partial answer");
                try {
                    assertTrue(cancelled.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AiProviderException("Streaming interrupted");
                }
                throw new AiProviderException("Streaming cancelled");
            });
        });
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch expiryLocked = new CountDownLatch(1);
        CountDownLatch releaseExpiry = new CountDownLatch(1);
        CountDownLatch lateSettlementContending = new CountDownLatch(1);
        try {
            var generation = executor.submit(() -> invocationService.complete(
                    streamingInvocation(text -> outputReceived.countDown())));
            assertTrue(outputReceived.await(10, TimeUnit.SECONDS));
            String id = Objects.requireNonNull(reservationId.get());
            long ceiling = budgetMapper.sumReservedTokens(orgId, day);
            assertTrue(ceiling > 0);
            budgetMapper.upsert(orgId, ceiling);

            cancelled.countDown();
            assertThrows(ExecutionException.class, () -> generation.get(10, TimeUnit.SECONDS));

            assertEquals(2, settlementWrites.get());
            assertEquals(0, budgetMapper.getConsumedTokens(orgId, day));
            assertEquals(ceiling, budgetMapper.sumReservedTokens(orgId, day));
            assertEquals("dispatched", Objects.requireNonNull(budgetMapper.getReservation(id)).getState());
            doAnswer(call -> {
                var row = realMapper.getReservationForUpdate(id);
                assertRepeatableRead();
                if (expiryLocked.getCount() != 0) {
                    expiryLocked.countDown();
                    assertTrue(releaseExpiry.await(10, TimeUnit.SECONDS));
                }
                return row;
            }).when(budgetMapper).getReservationForUpdate(id);
            doAnswer(call -> {
                if (expiryLocked.getCount() == 0 && releaseExpiry.getCount() != 0) {
                    lateSettlementContending.countDown();
                }
                return realMapper.getForUpdate(orgId);
            }).when(budgetMapper).getForUpdate(orgId);
            Clock expiredClock = Clock.offset(clock, Duration.ofMinutes(11));
            AiOrganizationBudgetCoordinator recovery = new AiOrganizationBudgetCoordinator(
                    operations, controlAccess, expiredClock);
            var sweep = executor.submit(recovery::sweepExpiredReservations);
            assertTrue(expiryLocked.await(10, TimeUnit.SECONDS));
            var lateSettlement = executor.submit(() -> operations.settle(id, 1));
            assertTrue(lateSettlementContending.await(10, TimeUnit.SECONDS));
            assertFalse(lateSettlement.isDone());

            releaseExpiry.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            lateSettlement.get(10, TimeUnit.SECONDS);
            recovery.sweepExpiredReservations();

            assertEquals(ceiling, budgetMapper.getConsumedTokens(orgId, day));
            assertEquals(0, budgetMapper.sumReservedTokens(orgId, day));
            var settled = Objects.requireNonNull(budgetMapper.getReservation(id));
            assertEquals("settled", settled.getState());
            assertEquals(Long.valueOf(ceiling), settled.getConsumedTokens());
            assertEquals(3, settlementWrites.get());
            assertThrows(AiBudgetExhaustedException.class, () -> invocationService.complete(
                    streamingInvocation(text -> {})));
        } finally {
            cancelled.countDown();
            releaseExpiry.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertRepeatableRead() {
        ConnectionCallback<Void> isolationCheck = connection -> {
            assertFalse(connection.getAutoCommit());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            return null;
        };
        jdbcTemplate.execute(isolationCheck);
    }

    private AiInvocationService invocationService(int orgId, AiProvider provider) {
        ResolvedAiProvider resolved = new ResolvedAiProvider(
                "openai_compatible", null, "test-model", "https://provider.example.test/v1",
                null, null, null, false, false, AiPrivacyMode.UNMASKED,
                AiCredentials.of(Map.of()));
        when(provider.supportsStreaming(resolved.target())).thenReturn(true);
        when(provider.contextWindowTokens(resolved.target()))
                .thenReturn(AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS);
        when(provider.maxOutputTokens(resolved.target())).thenReturn(4_096);
        return invocationService(orgId, provider, resolved);
    }

    private AiInvocationService invocationService(
            int orgId, AiProvider provider, ResolvedAiProvider resolved) {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspace.getId());
        when(workspaceService.getCurrentOrgId()).thenReturn(orgId);
        when(workspaceService.getCurrentUserId()).thenReturn(1);
        AiProviderConfigService providerConfig = mock(AiProviderConfigService.class);
        when(providerConfig.resolveForOrg(orgId, 1)).thenReturn(resolved);
        AiProviderRouter router = mock(AiProviderRouter.class);
        when(router.adapterFor(resolved.provider())).thenReturn(provider);
        return new AiInvocationService(
                mock(AiFeatureGate.class), mock(AiInvocationAdmissionService.class),
                mock(AiMediaAdmissionService.class), providerConfig, router, new AiRestrictionEpoch(),
                workspaceService, mock(AuditService.class), new ObjectMapper(), coordinator, clock);
    }

    private static AiInvocation streamingInvocation(AiProviderStreamObserver observer) {
        return new AiInvocation(
                AiFeature.ASSISTANT_CHAT, new MaskingContext(AiPrivacyMode.UNMASKED),
                PromptAssembly.builder().system("Respond concisely").userTurn("Summarize").build(),
                64, 0.1).withStreamObserver(observer);
    }

    private int newOrganization() {
        Organization organization = new Organization();
        organization.setName("Budget Organization " + unique());
        organization.setSlug("budget-org-" + unique());
        organizationMapper.insert(organization);
        return organization.getId();
    }
}
