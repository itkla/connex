package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import ooo.klae.connex.backend.password.PasswordPolicy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the published password length to what the BCrypt encoder can store: 72 bytes, which the
 * ASCII test candidates make 72 characters.
 */
class PasswordLengthValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final String LENGTH_MESSAGE = "Password must be between 8 and 72 characters";

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void sharedBrowserLimitsMatchBothApiBoundariesAndTheEncoder() throws Exception {
        try (var input = PasswordPolicy.class.getResourceAsStream("/password-policy.json")) {
            PasswordPolicy.Definition policy = JsonMapper.builder().build()
                .readValue(java.util.Objects.requireNonNull(input), PasswordPolicy.Definition.class);
            for (var field : List.of(RegisterDto.class.getDeclaredField("password"),
                    ResetPasswordRequest.class.getDeclaredField("newPassword"))) {
                Size size = field.getAnnotation(Size.class);
                assertEquals(size.min(), policy.minLength());
                assertEquals(size.max(), policy.maxLength());
            }
            assertEquals(72, policy.maxBytes());
        }
    }

    @Test
    void registrationAcceptsSeventyTwoCharactersAndRejectsSeventyThree() {
        assertTrue(VALIDATOR.validate(registration(password(72))).isEmpty());

        List<String> violations = VALIDATOR.validate(registration(password(73))).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();

        assertEquals(List.of("password: " + LENGTH_MESSAGE), violations);
    }

    @Test
    void resetAcceptsSeventyTwoCharactersAndRejectsSeventyThree() {
        assertTrue(VALIDATOR.validate(reset(password(72))).isEmpty());

        List<String> violations = VALIDATOR.validate(reset(password(73))).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();

        assertEquals(List.of("newPassword: " + LENGTH_MESSAGE), violations);
    }

    @Test
    void registrationStillRejectsShortPasswordsWithTheSameMessage() {
        List<String> messages = VALIDATOR.validate(registration("Aa1!aaa")).stream()
                .map(ConstraintViolation::getMessage)
                .toList();

        assertTrue(messages.contains(LENGTH_MESSAGE));
    }

    private static RegisterDto registration(String password) {
        RegisterDto dto = new RegisterDto();
        dto.setUsername("length_probe");
        dto.setDisplayName("Length Probe");
        dto.setEmail("length.probe@example.com");
        dto.setPassword(password);
        return dto;
    }

    private static ResetPasswordRequest reset(String password) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setNewPassword(password);
        return request;
    }

    private static String password(int length) {
        return "Aa1!" + "a".repeat(length - 4);
    }
}
