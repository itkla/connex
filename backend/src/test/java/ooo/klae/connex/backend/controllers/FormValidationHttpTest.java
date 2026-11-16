package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the real RegisterDto and ActivityDto validation at the JSON request boundary. */
class FormValidationHttpTest {
    private final AuthService auth = mock(AuthService.class);
    private final ActivityService activities = mock(ActivityService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
            new AuthController(auth, mock(PasswordResetService.class), mock(ClientIpResolver.class),
                mock(SessionSecurityService.class), mock(OneTimeLinkFlowService.class), mock(OneTimeLinkFlowCookie.class)),
            new ActivityController(activities, mock(WorkspaceService.class), mock(MemberScopeResolver.class)))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), new TenantContext()))
            .build();
    }

    static Stream<Arguments> locales() {
        return Stream.of(
            Arguments.of(Locale.ENGLISH, "Please enter a value", "Keep the subject to 255 characters or fewer"),
            Arguments.of(Locale.JAPANESE, "入力してください", "件名は255文字以内で入力してください"));
    }

    @ParameterizedTest
    @MethodSource("locales")
    void emptyUsernameAlwaysChoosesRequiredAcrossTwentyRequests(Locale locale, String required, String subjectSize)
            throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(post("/api/auth/register").locale(locale).contentType(MediaType.APPLICATION_JSON)
                    .content(registration("", "valid@example.test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").value(required))
                .andExpect(jsonPath("$.fieldErrorCodes.username").value("NotBlank"));
        }
        verifyNoInteractions(auth);
    }

    static Stream<Arguments> registrationErrors() {
        return Stream.of(
            Arguments.of(Locale.ENGLISH, "a!", "valid@example.test", "username", "Size", "Use 3–64 characters for your username"),
            Arguments.of(Locale.JAPANESE, "a!", "valid@example.test", "username", "Size", "ユーザー名は3〜64文字で入力してください"),
            Arguments.of(Locale.ENGLISH, "abc!", "valid@example.test", "username", "Pattern", "Use only letters, numbers, dots, underscores, and hyphens for your username"),
            Arguments.of(Locale.JAPANESE, "abc!", "valid@example.test", "username", "Pattern", "ユーザー名は半角英数字、ピリオド、アンダースコア、ハイフンで入力してください"),
            Arguments.of(Locale.ENGLISH, "valid", "bad-email", "email", "Email", "Please enter a valid email address"),
            Arguments.of(Locale.JAPANESE, "valid", "bad-email", "email", "Email", "正しいメールアドレスを入力してください"),
            Arguments.of(Locale.ENGLISH, "valid", "a".repeat(256), "email", "Size", "Use 255 characters or fewer"),
            Arguments.of(Locale.JAPANESE, "valid", "a".repeat(256), "email", "Size", "255文字以内で入力してください"));
    }

    @ParameterizedTest
    @MethodSource("registrationErrors")
    void registrationUsesCatalogMessagesAndConstraintPriority(Locale locale, String username, String email,
            String field, String code, String message) throws Exception {
        mvc.perform(post("/api/auth/register").locale(locale).contentType(MediaType.APPLICATION_JSON)
                .content(registration(username, email)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors." + field).value(message))
            .andExpect(jsonPath("$.fieldErrorCodes." + field).value(code));
        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @MethodSource("locales")
    void activitySubjectSizeUsesCatalogCopy(Locale locale, String required, String subjectSize) throws Exception {
        mvc.perform(post("/api/activities").locale(locale).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "call", "subject", "a".repeat(256)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.subject").value(subjectSize))
            .andExpect(jsonPath("$.fieldErrorCodes.subject").value("Size"));
        verifyNoInteractions(activities);
    }

    private String registration(String username, String email) {
        return json.writeValueAsString(Map.of("username", username, "email", email,
            "displayName", "Form validation", "password", "Aa12345#"));
    }
}
