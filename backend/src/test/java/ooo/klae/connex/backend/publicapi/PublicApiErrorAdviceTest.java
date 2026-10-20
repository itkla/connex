package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionExecution;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.config.RequestTransactionCommitListener;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.observability.CorrelationIds;

class PublicApiErrorAdviceTest {
    @BeforeEach
    void bindRequestCorrelationId() {
        MDC.put(CorrelationIds.MDC_KEY, "locked-mfa-request-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequestCorrelationId() {
        MDC.remove(CorrelationIds.MDC_KEY);
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockedConfinementKeepsTheFiltersUnauthorizedEnvelope(boolean attestation) throws Exception {
        ForbiddenException refusal = attestation ? new PrivilegedMfaAttestationRequiredException()
            : new PrivilegedMfaEnrollmentRequiredException();
        var handler = new ExceptionHandlerMethodResolver(PublicApiErrorAdvice.class).resolveMethodByThrowable(refusal);
        assertNotNull(handler);
        assertEquals("privilegedMfaRequired", handler.getName());
        var response = new PublicApiErrorAdvice().privilegedMfaRequired(refusal);
        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(refusal.getCode().toLowerCase(java.util.Locale.ROOT), response.getBody().error().code());
        assertEquals(refusal.getMessage(), response.getBody().error().message());
        assertEquals(CorrelationIds.current(), response.getBody().error().requestId());
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.support.LockContentionTestCases#retryable")
    void lockContentionReturnsGeneric409WithoutLoggingSql(Exception failure) {
        var method = new ExceptionHandlerMethodResolver(PublicApiErrorAdvice.class)
            .resolveMethodByThrowable(failure);
        assertNotNull(method);
        assertEquals("unexpectedFailure", method.getName());

        var response = new PublicApiErrorAdvice().unexpectedFailure(failure);
        List<ILoggingEvent> events = captureAdviceLogs(
            () -> new PublicApiErrorAdvice().unexpectedFailure(failure));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(PublicApiErrorAdvice.envelope("conflict",
            "The request conflicts with another operation; try again"), response.getBody());
        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.getFirst().getLevel());
        assertNull(events.getFirst().getThrowableProxy());
        assertFalse(events.getFirst().getFormattedMessage().contains("private"));
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.support.LockContentionTestCases#unclassifiedTransactions")
    void unclassifiedTransactionFailureKeepsTheExact500EnvelopeAndLogging(Exception failure) {
        var response = new PublicApiErrorAdvice().unexpectedFailure(failure);
        List<ILoggingEvent> events = captureAdviceLogs(
            () -> new PublicApiErrorAdvice().unexpectedFailure(failure));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(PublicApiErrorAdvice.envelope("internal_error", "An unexpected error occurred"),
            response.getBody());
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.getFirst().getLevel());
        assertNull(events.getFirst().getThrowableProxy());
        assertEquals("Public API request failed: exception=" + failure.getClass().getName(),
            events.getFirst().getFormattedMessage());
    }

    private static List<ILoggingEvent> captureAdviceLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PublicApiErrorAdvice.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.support.LockContentionTestCases#retryable")
    void contentionAfterAnEarlierCommitKeepsTheExact500EnvelopeAndLogging(Exception failure) {
        TransactionExecution committed = mock(TransactionExecution.class);
        when(committed.isNewTransaction()).thenReturn(true);
        new RequestTransactionCommitListener().afterCommit(committed, null);

        var response = new PublicApiErrorAdvice().unexpectedFailure(failure);
        List<ILoggingEvent> events = captureAdviceLogs(
            () -> new PublicApiErrorAdvice().unexpectedFailure(failure));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(PublicApiErrorAdvice.envelope("internal_error", "An unexpected error occurred"),
            response.getBody());
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.getFirst().getLevel());
        assertNull(events.getFirst().getThrowableProxy());
        assertEquals("Public API request failed: exception=" + failure.getClass().getName(),
            events.getFirst().getFormattedMessage());
    }
}
