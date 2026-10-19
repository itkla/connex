package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DataSubjectDisclosureDeliveryTest {
    @Test
    void expiryCancelsTheCapturedTransportAndRejectsFurtherDelivery() throws Exception {
        CountDownLatch aborted = new CountDownLatch(1);
        try (var delivery = new DataSubjectDisclosureDelivery(500, mock(DataSource.class));
                var transport = DataSubjectDisclosureDelivery.withTransport(aborted::countDown);
                var deadline = delivery.start()) {
            assertTrue(aborted.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, deadline::check);
        }
    }

    @Test
    void disarmingAndRestoringBindingsCannotCancelALaterRequest() throws Exception {
        AtomicInteger outerAborts = new AtomicInteger();
        CountDownLatch innerAborted = new CountDownLatch(1);
        try (var delivery = new DataSubjectDisclosureDelivery(500, mock(DataSource.class));
                var outer = DataSubjectDisclosureDelivery.withTransport(outerAborts::incrementAndGet)) {
            try (var cancelled = delivery.start()) {
                cancelled.check();
            }
            try (var inner = DataSubjectDisclosureDelivery.withTransport(innerAborted::countDown);
                    var deadline = delivery.start()) {
                assertTrue(innerAborted.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, deadline::check);
            }
            assertEquals(0, outerAborts.get());
        }
    }

    @Test
    void passTwoInstallsTheRemainingBudgetForMyBatisStatements() {
        DataSource dataSource = mock(DataSource.class);
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        try (var delivery = new DataSubjectDisclosureDelivery(10_000, dataSource);
                var deadline = delivery.start()) {
            assertTrue(holder.hasTimeout());
            assertTrue(holder.getTimeToLiveInMillis() > 0);
            assertTrue(holder.getTimeToLiveInMillis() <= 10_000);
            deadline.check();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
    }

    @Test
    void embeddedServerRefusesAnUnsupportedTransportBeforeOpeningTheResponse() {
        try (var delivery = new DataSubjectDisclosureDelivery(1_000, mock(DataSource.class))) {
            delivery.requireTransport();
            assertThrows(IllegalStateException.class, delivery::start);
        }
    }

    @Test
    void deliveryCannotExtendAnExistingTransactionDeadline() {
        DataSource dataSource = mock(DataSource.class);
        ConnectionHolder holder = new ConnectionHolder(mock(Connection.class));
        holder.setTimeoutInMillis(1_000);
        long previousRemaining = holder.getTimeToLiveInMillis();
        TransactionSynchronizationManager.bindResource(dataSource, holder);
        try (var delivery = new DataSubjectDisclosureDelivery(10_000, dataSource);
                var deadline = delivery.start()) {
            assertTrue(holder.getTimeToLiveInMillis() <= previousRemaining);
            deadline.check();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
    }

    @Test
    void nonPositiveOrOverflowingDeadlinesFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> new DataSubjectDisclosureDelivery(0, mock(DataSource.class)));
        assertThrows(ArithmeticException.class,
            () -> new DataSubjectDisclosureDelivery(Long.MAX_VALUE, mock(DataSource.class)));
    }
}
