package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import tools.jackson.databind.json.JsonMapper;

/** Exercises JSON binding and actual DTO validation on every existing new-password endpoint. */
class PasswordPolicyHttpTest {
    private final AuthService auth = mock(AuthService.class);
    private final PasswordResetService resets = mock(PasswordResetService.class);
    private final OneTimeLinkFlowService flows = mock(OneTimeLinkFlowService.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final SessionSecurityService sessions = mock(SessionSecurityService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ClientIpResolver ips = mock(ClientIpResolver.class);
        mvc = MockMvcBuilders.standaloneSetup(
            new AuthController(auth, resets, ips, sessions, flows, mock(OneTimeLinkFlowCookie.class)),
            new UserController(mock(UserService.class), auth, workspaces, sessions, ips))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext()))
            .build();
    }

    static Stream<String> symbols() {
        return Stream.concat(IntStream.rangeClosed(32, 126)
            .filter(c -> !Character.isLetterOrDigit(c))
            .mapToObj(c -> Character.toString((char) c)), Stream.of("£", "€", "。", "★", "　"));
    }

    static Stream<Arguments> accepted() {
        return symbols().flatMap(symbol -> Stream.of("/api/auth/register", "/api/auth/reset-password", "/api/users")
            .map(endpoint -> Arguments.of(endpoint, "Aa12345" + symbol)));
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
            Arguments.of("abcdef1#", "Password must contain at least one uppercase letter (A-Z)"),
            Arguments.of("ABCDEF1#", "Password must contain at least one lowercase letter (a-z)"),
            Arguments.of("Abcdefg#", "Password must contain at least one number (0-9)"),
            Arguments.of("Abcdefg1", "Password must contain at least one symbol or space"),
            Arguments.of("Aa1#abc", "Password must be between 8 and 72 characters"),
            Arguments.of("Aa1#" + "a".repeat(69), "Password must be between 8 and 72 characters"),
            Arguments.of("Aa12345\n", "Password must contain only printable characters"),
            Arguments.of("Aa12345\t", "Password must contain only printable characters"))
            .flatMap(args -> Stream.of("/api/auth/register", "/api/auth/reset-password", "/api/users")
                .map(endpoint -> Arguments.of(endpoint, args.get()[0], args.get()[1])));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void acceptsEachPrintableSymbolWithoutChangingThePassword(String endpoint, String password) throws Exception {
        if (endpoint.equals("/api/users")) {
            User current = new User();
            current.setId(7);
            when(auth.getCurrentUser()).thenReturn(current);
            when(auth.register(any(RegisterDto.class), any())).thenReturn(current);
        }
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(endpoint, password)))
            .andExpect(status().isOk());
        if (endpoint.equals("/api/auth/reset-password")) {
            verify(resets).screenForReset(password);
            verify(flows).consumePasswordReset(any(), any(), any());
        } else if (endpoint.equals("/api/auth/register")) {
            verify(auth).registerSelfService(org.mockito.ArgumentMatchers.argThat(dto -> dto.getPassword().equals(password)), any());
        } else {
            verify(auth).register(org.mockito.ArgumentMatchers.argThat(dto -> dto.getPassword().equals(password)), any());
            verify(workspaces).requirePermission(ooo.klae.connex.backend.tenant.Permission.MEMBER_MANAGE);
            verify(sessions).requireRecentAuthentication(eq(7));
        }
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void namesTheMissingRequirementBeforeAnyAuthWork(String endpoint, String password, String message) throws Exception {
        String field = endpoint.equals("/api/auth/reset-password") ? "newPassword" : "password";
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(endpoint, password)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verifyNoInteractions(auth, resets, flows, workspaces, sessions);
    }

    private String body(String endpoint, String password) {
        return json.writeValueAsString(endpoint.equals("/api/auth/reset-password")
            ? Map.of("newPassword", password)
            : Map.of("username", "password_probe", "email", "password@example.com",
                "displayName", "Password Probe", "password", password));
    }
}
