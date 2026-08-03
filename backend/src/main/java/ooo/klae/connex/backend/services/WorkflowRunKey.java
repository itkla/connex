package ooo.klae.connex.backend.services;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Strict parser for collision-safe canonical and legacy workflow run keys. */
public record WorkflowRunKey(String source, long id) {

    private static final Pattern FORMAT =
        Pattern.compile("^(canonical|legacy)-([1-9][0-9]*)$");

    public static WorkflowRunKey parse(String value) {
        Matcher matcher = value == null ? null : FORMAT.matcher(value);
        if (matcher == null || !matcher.matches()) {
            throw malformed();
        }
        try {
            return new WorkflowRunKey(
                matcher.group(1), Long.parseLong(matcher.group(2)));
        } catch (NumberFormatException exception) {
            throw malformed();
        }
    }

    private static BadRequestException malformed() {
        return new BadRequestException("Malformed workflow run key");
    }
}
