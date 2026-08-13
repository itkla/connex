package ooo.klae.connex.backend.ai.assistant;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Incrementally decodes only the canonical root terminal-answer text JSON path. */
final class AiAssistantTextDeltaProjector {
    enum Shape {
        JSON_REACT,
        NATIVE_FINAL
    }

    private final Shape shape;
    private final Consumer<String> consumer;
    private final StringBuilder raw = new StringBuilder();
    private String projected = "";
    private boolean nonTerminal;
    private boolean closed;

    AiAssistantTextDeltaProjector(Shape shape, Consumer<String> consumer) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    void accept(String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return;
        }
        raw.append(fragment);
        project();
    }

    String finish() {
        project();
        if (nonTerminal || !closed) {
            throw new AiAssistantLoopException("malformed_output", "malformed_output");
        }
        return projected;
    }

    boolean hasProjectedText() {
        return !projected.isEmpty();
    }

    private void project() {
        if (nonTerminal) {
            return;
        }
        Projection projection = new Scanner(raw, shape).scan();
        if (projection.invalid()) {
            nonTerminal = true;
            return;
        }
        int safeLength = projection.text().length();
        if (!projection.textClosed() && safeLength > 0
                && Character.isHighSurrogate(projection.text().charAt(safeLength - 1))) {
            safeLength--;
        }
        if (safeLength < projected.length()
                || !projection.text().startsWith(projected)) {
            throw malformedOutput();
        }
        if (projection.terminalConfirmed() && safeLength > projected.length()) {
            String delta = projection.text().substring(projected.length(), safeLength);
            projected = projection.text().substring(0, safeLength);
            consumer.accept(delta);
        }
        closed = projection.complete()
                && projection.terminalConfirmed()
                && projection.textClosed()
                && safeLength == projection.text().length();
    }

    private static AiAssistantLoopException malformedOutput() {
        return new AiAssistantLoopException("malformed_output", "malformed_output");
    }

    private record Projection(
            String text,
            boolean textClosed,
            boolean terminalConfirmed,
            boolean complete,
            boolean invalid) {
    }

    private static final class Scanner {
        private final CharSequence input;
        private final Shape shape;
        private int index;
        private String text = "";
        private boolean textSeen;
        private boolean textClosed;
        private boolean toolSeen;
        private boolean toolNull;
        private boolean finalSeen;
        private boolean finalObject;

        private Scanner(CharSequence input, Shape shape) {
            this.input = input;
            this.shape = shape;
        }

        private Projection scan() {
            boolean complete = false;
            boolean invalid = false;
            try {
                expect('{');
                if (shape == Shape.JSON_REACT) {
                    readReactRoot();
                } else {
                    readFinalObjectBody();
                    finalSeen = true;
                    finalObject = true;
                }
                skipWhitespace();
                if (index != input.length()) {
                    throw Invalid.INSTANCE;
                }
                complete = true;
            } catch (Incomplete exception) {
                complete = false;
            } catch (Invalid exception) {
                invalid = true;
            }
            boolean terminalConfirmed = textSeen && finalSeen && finalObject
                    && (shape == Shape.NATIVE_FINAL || toolSeen && toolNull);
            return new Projection(text, textClosed, terminalConfirmed, complete, invalid);
        }

        private void readReactRoot() {
            Set<String> fields = new HashSet<>();
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return;
                }
                if (!first) {
                    expect(',');
                }
                String field = readString(false);
                if (!fields.add(field)) {
                    throw Invalid.INSTANCE;
                }
                expect(':');
                switch (field) {
                    case "tool" -> readTool();
                    case "final" -> readFinal();
                    default -> throw Invalid.INSTANCE;
                }
                first = false;
            }
        }

        private void readTool() {
            toolSeen = true;
            skipWhitespace();
            if (peekLiteral("null")) {
                expectLiteral("null");
                toolNull = true;
                return;
            }
            skipValue();
        }

        private void readFinal() {
            finalSeen = true;
            skipWhitespace();
            if (peekLiteral("null")) {
                expectLiteral("null");
                return;
            }
            expect('{');
            finalObject = true;
            readFinalObjectBody();
        }

        private void readFinalObjectBody() {
            Set<String> fields = new HashSet<>();
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return;
                }
                if (!first) {
                    expect(',');
                }
                String field = readString(false);
                if (!fields.add(field)
                        || !Set.of("text", "citations", "suggestions", "title").contains(field)) {
                    throw Invalid.INSTANCE;
                }
                expect(':');
                if ("text".equals(field)) {
                    textSeen = true;
                    text = readString(true);
                    textClosed = true;
                } else {
                    skipValue();
                }
                first = false;
            }
        }

        private void skipValue() {
            skipWhitespace();
            if (index >= input.length()) {
                throw Incomplete.INSTANCE;
            }
            switch (input.charAt(index)) {
                case '"' -> readString(false);
                case '{' -> skipObject();
                case '[' -> skipArray();
                case 't' -> expectLiteral("true");
                case 'f' -> expectLiteral("false");
                case 'n' -> expectLiteral("null");
                default -> skipNumber();
            }
        }

        private void skipObject() {
            expect('{');
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return;
                }
                if (!first) {
                    expect(',');
                }
                readString(false);
                expect(':');
                skipValue();
                first = false;
            }
        }

        private void skipArray() {
            expect('[');
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return;
                }
                if (!first) {
                    expect(',');
                }
                skipValue();
                first = false;
            }
        }

        private void skipNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (peek('0')) {
                index++;
            } else {
                requireDigits();
            }
            if (peek('.')) {
                index++;
                requireDigits();
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                requireDigits();
            }
            if (index == start) {
                throw Invalid.INSTANCE;
            }
        }

        private void requireDigits() {
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (index == start) {
                if (index == input.length()) {
                    throw Incomplete.INSTANCE;
                }
                throw Invalid.INSTANCE;
            }
        }

        private String readString(boolean target) {
            skipWhitespace();
            expectRaw('"');
            StringBuilder decoded = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') {
                    return decoded.toString();
                }
                if (current != '\\') {
                    if (current < 0x20) {
                        throw Invalid.INSTANCE;
                    }
                    decoded.append(current);
                    continue;
                }
                if (index >= input.length()) {
                    retainTarget(target, decoded);
                    throw Incomplete.INSTANCE;
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> decoded.append(escaped);
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> decoded.append(readUnicode(target, decoded));
                    default -> throw Invalid.INSTANCE;
                }
            }
            retainTarget(target, decoded);
            throw Incomplete.INSTANCE;
        }

        private char readUnicode(boolean target, StringBuilder decoded) {
            if (index + 4 > input.length()) {
                retainTarget(target, decoded);
                throw Incomplete.INSTANCE;
            }
            int value = 0;
            for (int digit = 0; digit < 4; digit++) {
                int hex = Character.digit(input.charAt(index + digit), 16);
                if (hex < 0) {
                    throw Invalid.INSTANCE;
                }
                value = value * 16 + hex;
            }
            index += 4;
            return (char) value;
        }

        private void retainTarget(boolean target, StringBuilder decoded) {
            if (target) {
                text = decoded.toString();
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            expectRaw(expected);
        }

        private void expectRaw(char expected) {
            if (index >= input.length()) {
                throw Incomplete.INSTANCE;
            }
            if (input.charAt(index) != expected) {
                throw Invalid.INSTANCE;
            }
            index++;
        }

        private void expectLiteral(String expected) {
            skipWhitespace();
            if (input.length() - index < expected.length()) {
                throw Incomplete.INSTANCE;
            }
            if (!expected.contentEquals(input.subSequence(index, index + expected.length()))) {
                throw Invalid.INSTANCE;
            }
            index += expected.length();
        }

        private boolean peekLiteral(String expected) {
            return input.length() - index >= expected.length()
                    && expected.contentEquals(input.subSequence(index, index + expected.length()));
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char current = input.charAt(index);
                if (current != ' ' && current != '\t' && current != '\r' && current != '\n') {
                    return;
                }
                index++;
            }
        }
    }

    private static final class Incomplete extends RuntimeException {
        private static final Incomplete INSTANCE = new Incomplete();

        private Incomplete() {
            super(null, null, false, false);
        }
    }

    private static final class Invalid extends RuntimeException {
        private static final Invalid INSTANCE = new Invalid();

        private Invalid() {
            super(null, null, false, false);
        }
    }
}
