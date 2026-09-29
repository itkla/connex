package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.PasswordTooLongException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuthService;

/**
 * The bootstrap runner provisions the first owner only when it is enabled, the instance has no
 * login-capable users, and the credentials are all present — and it must never propagate an
 * exception (a failed bootstrap cannot be allowed to abort startup). (#81 Phase 2)
 */
class BootstrapRunnerTest {

    private UserMapper userMapper;
    private AuthService authService;
    private BootstrapRunner runner;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        authService = mock(AuthService.class);
        runner = new BootstrapRunner(userMapper, authService);
        config(true, "owner", "owner@example.com", "Aa1!aaaa", "Owner", "UTC");
    }

    private void config(boolean enabled, String username, String email,
            String password, String displayName, String timezone) {
        ReflectionTestUtils.setField(runner, "enabled", enabled);
        ReflectionTestUtils.setField(runner, "username", username);
        ReflectionTestUtils.setField(runner, "email", email);
        ReflectionTestUtils.setField(runner, "password", password);
        ReflectionTestUtils.setField(runner, "displayName", displayName);
        ReflectionTestUtils.setField(runner, "timezone", timezone);
    }

    @Test
    void provisionsOwner_whenEnabledAndInstanceEmpty() {
        when(userMapper.countUsers()).thenReturn(0);
        User created = new User();
        created.setUsername("owner");
        when(authService.provisionBootstrapOwner(any())).thenReturn(created);

        runner.onApplicationEvent(null);

        ArgumentCaptor<RegisterDto> captor = ArgumentCaptor.forClass(RegisterDto.class);
        verify(authService).provisionBootstrapOwner(captor.capture());
        RegisterDto dto = captor.getValue();
        assertEquals("owner", dto.getUsername());
        assertEquals("owner@example.com", dto.getEmail());
        assertEquals("Owner", dto.getDisplayName());
    }

    @Test
    void skips_whenDisabled() {
        config(false, "owner", "owner@example.com", "Aa1!aaaa", "Owner", "UTC");

        runner.onApplicationEvent(null);

        verify(userMapper, never()).countUsers();
        verify(authService, never()).provisionBootstrapOwner(any());
    }

    @Test
    void skips_whenUsersAlreadyExist() {
        when(userMapper.countUsers()).thenReturn(3);

        runner.onApplicationEvent(null);

        verify(authService, never()).provisionBootstrapOwner(any());
    }

    @Test
    void skips_whenCredentialsIncomplete() {
        when(userMapper.countUsers()).thenReturn(0);
        config(true, "owner", "owner@example.com", "   ", "Owner", "UTC");

        runner.onApplicationEvent(null);

        verify(authService, never()).provisionBootstrapOwner(any());
    }

    @Test
    void overlongPasswordLogsTheActionableLimitButNeverThePassword() {
        String overlong = "Aa1!" + "\u3042".repeat(24);
        config(true, "owner", "owner@example.com", overlong, "Owner", "UTC");
        when(userMapper.countUsers()).thenReturn(0);
        when(authService.provisionBootstrapOwner(any()))
                .thenThrow(new PasswordTooLongException("password"));
        Logger logger = assertInstanceOf(Logger.class, LoggerFactory.getLogger(BootstrapRunner.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner.onApplicationEvent(null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> errors = appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("connex.bootstrap.password"));
        assertTrue(errors.get(0).contains(PasswordTooLongException.MESSAGE));
        assertFalse(errors.get(0).contains(overlong));
    }

    @Test
    void doesNotPropagate_whenProvisioningFails() {
        when(userMapper.countUsers()).thenReturn(0);
        when(authService.provisionBootstrapOwner(any())).thenThrow(new RuntimeException("db down"));

        runner.onApplicationEvent(null);

        verify(authService).provisionBootstrapOwner(any());
    }
}
