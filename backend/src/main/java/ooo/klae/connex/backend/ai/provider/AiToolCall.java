package ooo.klae.connex.backend.ai.provider;

import java.util.regex.Pattern;

/** Opaque provider function call whose arguments remain masked until validation succeeds. */
public record AiToolCall(String id, String name, String arguments) {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_ID_CHARS = 256;

    public AiToolCall {
        if (id == null || id.isBlank() || id.length() > MAX_ID_CHARS
                || id.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("AI tool call id is invalid");
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("AI tool call name is invalid");
        }
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("AI tool call arguments are invalid");
        }
    }

    @Override
    public String toString() {
        return "AiToolCall[id=<redacted>, name=" + name + ", arguments=<redacted>]";
    }
}
