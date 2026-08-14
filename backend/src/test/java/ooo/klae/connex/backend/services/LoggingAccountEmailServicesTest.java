package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.beans.User;

class LoggingAccountEmailServicesTest {

    private static final String RAW_TOKEN = "raw-token-that-must-never-be-logged";
    private static final String NEW_EMAIL = "pending@example.com";

    @Test
    void passwordResetFallbackLogsNoBearer() {
        assertSafeFallbackLog(
            LoggingPasswordResetEmailService.class,
            () -> new LoggingPasswordResetEmailService().sendResetEmail(user(), RAW_TOKEN),
            "Password reset requested but no email delivery is configured");
    }

    @Test
    void emailChangeFallbackLogsNoBearerOrPendingAddress() {
        assertSafeFallbackLog(
            LoggingEmailChangeEmailService.class,
            () -> new LoggingEmailChangeEmailService().sendVerificationEmail(user(), NEW_EMAIL, RAW_TOKEN),
            "Email change requested but no email delivery is configured");
    }

    @Test
    void registrationVerificationFallbackLogsNoBearer() {
        assertSafeFallbackLog(
            LoggingRegistrationVerificationEmailService.class,
            () -> new LoggingRegistrationVerificationEmailService().sendVerificationEmail(user(), RAW_TOKEN),
            "Registration verification requested but no email delivery is configured");
    }

    private void assertSafeFallbackLog(Class<?> serviceType, Runnable delivery, String expectedMessage) {
        Logger logger = (Logger) LoggerFactory.getLogger(serviceType);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            delivery.run();

            assertEquals(1, appender.list.size());
            String message = appender.list.getFirst().getFormattedMessage();
            assertTrue(message.contains(expectedMessage));
            assertFalse(message.contains(RAW_TOKEN));
            assertFalse(message.contains(NEW_EMAIL));
            assertFalse(message.contains("42"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private User user() {
        User user = new User();
        user.setId(42);
        return user;
    }
}
