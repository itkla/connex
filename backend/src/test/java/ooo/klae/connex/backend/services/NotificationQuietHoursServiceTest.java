package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.NotificationQuietHoursDto;
import ooo.klae.connex.backend.dto.NotificationQuietHoursRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class NotificationQuietHoursServiceTest {
    @Mock private NotificationQuietHoursMapper quietHoursMapper;
    @Mock private AuthService authService;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private NotificationQuietHoursService service;
    private TenantContext tenantContext;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(42);
        user.setTimezone("America/New_York");
        tenantContext = new TenantContext();
        tenantContext.set(7, 8, 42, "member", "cnx_tenant");
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        lenient().when(authService.getCurrentPrincipal()).thenReturn(user);
        lenient().when(quietHoursMapper.findByUserId(42)).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return null;
        });
        lenient().doAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return null;
        }).when(quietHoursMapper).upsert(org.mockito.ArgumentMatchers.any());
        NotificationQuietHoursEvaluator evaluator = new NotificationQuietHoursEvaluator();
        TestTransactionManager transactionManager = new TestTransactionManager();
        NotificationQuietHoursControlAccess controlAccess = new NotificationQuietHoursControlAccess(
            quietHoursMapper, evaluator, tenantWorkScope, tenantContext, transactionManager);
        service = new NotificationQuietHoursService(
            controlAccess,
            authService,
            evaluator,
            Clock.fixed(Instant.parse("2026-07-21T03:00:00Z"), ZoneOffset.UTC)
        );
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void absentRowReturnsDisabledProfileDefaults() {
        NotificationQuietHoursDto result = service.getCurrent();

        assertFalse(result.enabled());
        assertEquals("America/New_York", result.timezone());
        assertEquals("22:00", result.start());
        assertEquals("07:00", result.end());
        assertEquals(List.of(DayOfWeek.values()), result.days());
        assertFalse(result.activeNow());
        assertEquals("security_only", result.bypassPolicy());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void updateDerivesUserAndPersistsOrderedDayMask() {
        NotificationQuietHoursRequest request = new NotificationQuietHoursRequest(
            true,
            "America/New_York",
            "22:00",
            "07:00",
            List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        );

        NotificationQuietHoursDto result = service.updateCurrent(request);

        ArgumentCaptor<NotificationQuietHours> saved = ArgumentCaptor.forClass(NotificationQuietHours.class);
        verify(quietHoursMapper).upsert(saved.capture());
        assertEquals(42, saved.getValue().getUserId());
        assertEquals(17, saved.getValue().getDaysMask());
        assertTrue(result.activeNow());
        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), result.days());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void disabledEmptyDaysNormalizeToAllDays() {
        NotificationQuietHoursRequest request = new NotificationQuietHoursRequest(
            false, "UTC", "22:00", "07:00", List.of());

        NotificationQuietHoursDto result = service.updateCurrent(request);

        assertEquals(List.of(DayOfWeek.values()), result.days());
    }

    @Test
    void rejectsEnabledEmptyDuplicateDaysEqualTimesAndInvalidTimezone() {
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(true, "UTC", "22:00", "07:00", List.of())));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "UTC", "22:00", "07:00",
                List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY))));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "UTC", "22:00", "22:00", List.of())));
        assertThrows(BadRequestException.class, () -> service.updateCurrent(
            new NotificationQuietHoursRequest(false, "+09:00", "22:00", "07:00", List.of())));
    }

    @Test
    void deliveryEvaluationRunsUnroutedAndRestoresTenantCatalog() {
        NotificationQuietHoursEvaluator.Evaluation result = service.evaluateForUser(
            42, Instant.parse("2026-07-21T03:00:00Z"));

        assertFalse(result.active());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void routedControlAccessSuspendsAndRestoresAnActiveTenantTransaction() {
        TestTransactionManager transactionManager = new TestTransactionManager();
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        NotificationQuietHoursControlAccess controlAccess = new NotificationQuietHoursControlAccess(
            quietHoursMapper,
            new NotificationQuietHoursEvaluator(),
            tenantWorkScope,
            tenantContext,
            transactionManager);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return null;
        }).when(quietHoursMapper).findByUserId(42);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_tenant", tenantContext.getCatalog());

            NotificationQuietHoursEvaluator.Evaluation result = controlAccess.evaluateForUser(
                42, Instant.parse("2026-07-21T03:00:00Z"));

            assertFalse(result.active());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_tenant", tenantContext.getCatalog());
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();

        @Override
        protected Object doGetTransaction() {
            TestTransaction transaction = current.get();
            return transaction == null ? new TestTransaction() : transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TestTransaction active = (TestTransaction) transaction;
            active.active = true;
            current.set(active);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            current.remove();
            return transaction;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current.set((TestTransaction) suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            TestTransaction completed = (TestTransaction) transaction;
            completed.active = false;
            current.remove();
        }
    }

    private static final class TestTransaction {
        private boolean active;
    }
}
