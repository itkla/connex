package ooo.klae.connex.backend.password;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.json.JsonMapper;

/** The canonical printable-character policy shared with the browser's checked policy copy. */
public final class PasswordPolicy {
    /** A portable Java/JavaScript expression and its safe field explanation. */
    public record Rule(String key, String pattern, String message) {}
    /** Shared browser limits and ordered character rules. */
    public record Definition(int minLength, int maxLength, int maxBytes, List<Rule> rules) {}

    private record CompiledRule(Pattern pattern, String message) {}

    private static final Definition DEFINITION = load();
    private static final List<CompiledRule> RULES = DEFINITION.rules().stream()
        .map(rule -> new CompiledRule(Pattern.compile(rule.pattern()), rule.message()))
        .toList();

    private PasswordPolicy() {}

    /** Returns the first missing character requirement, without including password content. */
    public static String characterError(String password) {
        return RULES.stream()
            .filter(rule -> !rule.pattern().matcher(password).find())
            .map(CompiledRule::message)
            .findFirst().orElse(null);
    }

    private static Definition load() {
        try (var input = Objects.requireNonNull(
                PasswordPolicy.class.getResourceAsStream("/password-policy.json"))) {
            return JsonMapper.builder().build().readValue(input, Definition.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Password policy could not be loaded", exception);
        }
    }
}
