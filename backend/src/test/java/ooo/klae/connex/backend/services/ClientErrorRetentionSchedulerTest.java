package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

class ClientErrorRetentionSchedulerTest {

    @Test
    void retentionCannotBeDisabledAndRunsAtStartupThenHourly() throws Exception {
        ConditionalOnProperty condition =
            ClientErrorRetentionScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("connex.maintenance", condition.prefix());
        assertArrayEquals(new String[] {"mode"}, condition.name());
        assertEquals("off", condition.havingValue());
        assertTrue(condition.matchIfMissing());
        Method startup = ClientErrorRetentionScheduler.class.getMethod("purgeOnStartup");
        assertArrayEquals(
            new Class<?>[] { ApplicationReadyEvent.class },
            startup.getAnnotation(EventListener.class).value());
        Method scheduled = ClientErrorRetentionScheduler.class.getMethod("purgeExpired");
        Scheduled cadence = scheduled.getAnnotation(Scheduled.class);
        assertEquals(1, cadence.fixedDelay());
        assertEquals(1, cadence.initialDelay());
        assertEquals(TimeUnit.HOURS, cadence.timeUnit());
    }

    @Test
    void normalApplicationContextRunsStartupPurge() {
        ClientErrorService service = mock(ClientErrorService.class);
        new ApplicationContextRunner()
            .withBean(ClientErrorService.class, () -> service)
            .withUserConfiguration(ClientErrorRetentionScheduler.class)
            .withPropertyValues("connex.maintenance.mode=off")
            .run(context -> {
                assertTrue(context.containsBean("clientErrorRetentionScheduler"));
                context.publishEvent(mock(ApplicationReadyEvent.class));
                verify(service).purgeExpired();
            });
    }

    @Test
    void seederContextDoesNotCreateRetentionScheduler() {
        new ApplicationContextRunner()
            .withBean(ClientErrorService.class, () -> mock(ClientErrorService.class))
            .withUserConfiguration(ClientErrorRetentionScheduler.class)
            .withPropertyValues("connex.maintenance.mode=seeder")
            .run(context -> assertFalse(
                context.containsBean("clientErrorRetentionScheduler")));
    }

    @Test
    void startupAndScheduledInvocationsBothPurgeThroughTheService() {
        ClientErrorService service = mock(ClientErrorService.class);
        ClientErrorRetentionScheduler scheduler = new ClientErrorRetentionScheduler(service);

        scheduler.purgeOnStartup();
        scheduler.purgeExpired();

        verify(service, times(2)).purgeExpired();
    }

    @Test
    void startupPurgeFailsClosedWhileScheduledFailuresRemainRetryable() {
        ClientErrorService service = mock(ClientErrorService.class);
        ClientErrorRetentionScheduler scheduler = new ClientErrorRetentionScheduler(service);
        doThrow(new IllegalStateException("database unavailable")).when(service).purgeExpired();

        assertThrows(IllegalStateException.class, scheduler::purgeOnStartup);
        assertDoesNotThrow(scheduler::purgeExpired);
    }
}
