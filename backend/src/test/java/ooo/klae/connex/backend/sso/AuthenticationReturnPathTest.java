package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ooo.klae.connex.backend.util.AuthenticationReturnPath;
import tools.jackson.databind.json.JsonMapper;

/** Uses the same malformed and normalized return-path vectors as the frontend. */
class AuthenticationReturnPathTest {
    record ReturnCase(String input, String expected) {
    }

    static Stream<Arguments> returnPaths() throws Exception {
        try (InputStream source = Objects.requireNonNull(
                AuthenticationReturnPathTest.class.getResourceAsStream("/auth-return-path-cases.json"))) {
            ReturnCase[] values = JsonMapper.builder().build().readValue(source, ReturnCase[].class);
            return Arrays.stream(values).map(value -> Arguments.of(value.input(), value.expected()));
        }
    }

    @ParameterizedTest(name = "return path case {index}")
    @MethodSource("returnPaths")
    void acceptsOnlyNormalizedRelativeDestinations(String input, String expected) {
        assertEquals(expected, AuthenticationReturnPath.validate(input));
    }
}
