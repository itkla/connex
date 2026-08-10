package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

class ClientErrorRetentionSchedulerTest {

    @Test
    void retentionCannotBeDisabledAndRunsAtStartupThenHourly() throws Exception {
        assertNull(ClientErrorRetentionScheduler.class.getAnnotation(ConditionalOnProperty.class));
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
    void startupAndScheduledInvocationsBothPurgeThroughTheService() {
        ClientErrorService service = org.mockito.Mockito.mock(ClientErrorService.class);
        ClientErrorRetentionScheduler scheduler = new ClientErrorRetentionScheduler(service);

        scheduler.purgeOnStartup();
        scheduler.purgeExpired();

        verify(service, times(2)).purgeExpired();
    }

    @Test
    void startupPurgeFailsClosedWhileScheduledFailuresRemainRetryable() {
        ClientErrorService service = org.mockito.Mockito.mock(ClientErrorService.class);
        ClientErrorRetentionScheduler scheduler = new ClientErrorRetentionScheduler(service);
        doThrow(new IllegalStateException("database unavailable")).when(service).purgeExpired();

        assertThrows(IllegalStateException.class, scheduler::purgeOnStartup);
        assertDoesNotThrow(scheduler::purgeExpired);
    }
}
