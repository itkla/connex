package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Incremental counterpart of {@link MaskingContext#containsUnexposedTokenInProviderText}.
 * Each projection keeps rollback checkpoints, rather than rescanning an open placeholder or
 * link. Candidates may contain arbitrarily many spaces or digits. Refusal is sampled only after
 * the entire callback: a completed record link can remove a match from its target.
 * The caller bounds aggregate input before append and releases this linear rollback history when
 * partial publication stops; final complete-result validation remains independent of this screen.
 */
public final class IncrementalProviderTextScreen {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final List<String> TARGETS = List.of("(person:", "(company:", "(deal:", "(record:");
    private final Projection raw;
    private final Projection decoded;
    private final Sink escapes;
    private final StringBuilder unicode = new StringBuilder();
    private final Cost cost = new Cost();
    private long inputCharacters;

    /** Creates an independent field screen against the current run's bindings and exposure. */
    public IncrementalProviderTextScreen(MaskingContext context) {
        Objects.requireNonNull(context, "context");
        raw = new Projection(context, cost);
        decoded = new Projection(context, cost);
        Sink replacement = new BackslashRemoval(decoded.normalizer);
        for (String pair : List.of("f\f", "b\b", "t\t", "r\r", "n\n")) {
            replacement = new ControlReplacement(pair.charAt(0), pair.charAt(1), replacement, cost);
        }
        escapes = replacement;
    }

    /** Appends new field text and returns the complete-field oracle's current refusal decision. */
    public boolean append(String delta) {
        Objects.requireNonNull(delta, "delta");
        inputCharacters += delta.length();
        for (int index = 0; index < delta.length(); index++) {
            char value = delta.charAt(index);
            raw.normalizer.append(new Unit(value, true));
            unicode.append(value);
            escapes.append(new Unit(value, true));
            while (unicode.length() >= 6) {
                int start = unicode.length() - 6;
                if (unicode.charAt(start) != '\\' || unicode.charAt(start + 1) != 'u') {
                    break;
                }
                int code = 0;
                for (int offset = start + 2; offset < start + 6; offset++) {
                    int digit = Character.digit(unicode.charAt(offset), 16);
                    if (digit < 0) {
                        code = -1;
                        break;
                    }
                    code = code * 16 + digit;
                }
                if (code < 0) {
                    break;
                }
                unicode.setLength(start);
                escapes.pop(6);
                unicode.append((char) code);
                escapes.append(new Unit((char) code, true));
            }
        }
        return raw.refused() || decoded.refused();
    }

    /** Counts actual projection/recognizer character visits for deterministic complexity tests. */
    long scannedCharacters() {
        return cost.characters;
    }

    /** Counts newly supplied characters, excluding projection work and rollbacks. */
    public long inputCharacters() {
        return inputCharacters;
    }

    private static final class Cost {
        private long characters;
    }

    private record Unit(char value, boolean literal) {
        Unit transformed() {
            return literal ? new Unit(value, false) : this;
        }
    }

    /** Persistent checkpoints restore speculative rewrites without replaying a retained prefix. */
    private abstract static class Sink {
        final Sink target;
        final Cost cost;
        Frame top;

        Sink(Sink target, Cost cost) {
            this.target = target;
            this.cost = cost;
        }

        final void append(Unit unit) {
            cost.characters++;
            Object state = project(unit);
            top = new Frame(unit, top, state, target == null ? null : target.top);
        }

        abstract Object project(Unit unit);

        final void restore(Frame checkpoint) {
            top = checkpoint;
            if (target != null) {
                target.restore(top == null ? null : top.output());
            }
        }

        final void pop(int count) {
            Frame checkpoint = top;
            for (int index = 0; index < count; index++) {
                checkpoint = checkpoint.previous();
            }
            restore(checkpoint);
        }

        final Frame outputBeforeLast() {
            return top == null || top.previous() == null ? null : top.previous().output();
        }
    }

    private record Frame(Unit input, Frame previous, Object state, Frame output) {
    }

    private static final class Projection {
        private final TokenRecognizer tokens;
        private final LinkProjection convergence;
        private final UnicodeProjection normalizer;

        Projection(MaskingContext context, Cost cost) {
            tokens = new TokenRecognizer(context, cost);
            convergence = new LinkProjection(tokens, cost);
            Sink links = convergence;
            for (int pass = 0; pass < 16; pass++) {
                links = new LinkProjection(links, cost);
            }
            normalizer = new UnicodeProjection(links, cost);
        }

        boolean refused() {
            if (convergence.state().removed() != 0) {
                throw new MaskingLeakException("Outbound AI text transformation did not converge");
            }
            return tokens.state().refused();
        }
    }

    /**
     * NFKC composition cannot create ASCII grammar characters across code points: combining
     * sequences either remain non-ASCII or interrupt the ASCII grammar. Per-code-point NFKC
     * therefore recognizes the same language. Adjacent grapheme probes preserve the extra source
     * coverage at ASCII braces, including prepends and extensions of an already completed token.
     * Surrogate pairs replace their speculative high-surrogate projection on arrival.
     */
    private static final class UnicodeProjection extends Sink {
        UnicodeProjection(Sink target, Cost cost) {
            super(target, cost);
        }

        @Override
        Object project(Unit unit) {
            String source = String.valueOf(unit.value());
            Frame prior = top;
            Frame start = target.top;
            if (prior != null && Character.isLowSurrogate(unit.value())
                    && Character.isHighSurrogate(prior.input().value())) {
                source = String.valueOf(prior.input().value()) + unit.value();
                target.restore(outputBeforeLast());
                start = target.top;
                prior = prior.previous();
            }
            String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC);
            boolean literal = source.equals(normalized) && unit.literal();
            if (prior != null) {
                UnicodeState preceding = (UnicodeState) prior.state();
                if (GRAPHEME.matcher(preceding.source() + source).matches()) {
                    if (preceding.source().equals("}")) {
                        target.restore(preceding.start());
                        emit(preceding.normalized(), false);
                        start = target.top;
                    }
                    if (source.equals("{")) {
                        literal = false;
                    }
                }
            }
            emit(normalized, literal);
            return new UnicodeState(source, normalized, start);
        }

        private void emit(String text, boolean literal) {
            for (int index = 0; index < text.length(); index++) {
                target.append(new Unit(text.charAt(index), literal));
            }
        }
    }

    private record UnicodeState(String source, String normalized, Frame start) {
    }

    /** One ordered String.replace pass, with checkpoints for nested Unicode reductions. */
    private static final class ControlReplacement extends Sink {
        private final char escaped;
        private final char replacement;

        ControlReplacement(char escaped, char replacement, Sink target, Cost cost) {
            super(target, cost);
            this.escaped = escaped;
            this.replacement = replacement;
        }

        @Override
        Object project(Unit unit) {
            if (unit.value() == escaped && top != null && top.input().value() == '\\') {
                target.restore(outputBeforeLast());
                target.append(new Unit(replacement, true));
            } else {
                target.append(unit);
            }
            return null;
        }
    }

    private static final class BackslashRemoval extends Sink {
        BackslashRemoval(Sink target) {
            super(target, target.cost);
        }

        @Override
        Object project(Unit unit) {
            if (unit.value() != '\\') {
                target.append(unit);
            }
            return null;
        }
    }

    /**
     * A single ConversationText link pass. Completed links on this pass are disjoint; replaying
     * only their labels visits each source character a bounded number of times over 17 passes.
     * Checkpoints retract speculative target text and its refusal state without rescanning it.
     */
    private static final class LinkProjection extends Sink {
        private static final LinkState EMPTY = new LinkState(null, null, null, false, 0);

        LinkProjection(Sink target, Cost cost) {
            super(target, cost);
        }

        LinkState state() {
            return top == null ? EMPTY : (LinkState) top.state();
        }

        @Override
        Object project(Unit unit) {
            LinkState prior = state();
            LinkState next = prior;
            char value = unit.value();
            if (prior.target()) {
                if (value == ')') {
                    target.restore(prior.start().output());
                    var reversed = new ArrayList<Unit>();
                    for (Frame label = prior.end(); label.previous() != prior.start().input(); label = label.previous()) {
                        reversed.add(label.input());
                    }
                    if (reversed.isEmpty() && target.top != null) {
                        Unit previous = target.top.input();
                        target.pop(1);
                        target.append(previous.transformed());
                    }
                    for (int index = reversed.size() - 1; index >= 0; index--) {
                        Unit label = reversed.get(index);
                        target.append(index == 0 || index == reversed.size() - 1 ? label.transformed() : label);
                    }
                    return new LinkState(null, null, null, false, prior.removed() + 1);
                }
            } else if (prior.prefix() != null) {
                String prefix = prior.prefix() + value;
                if (TARGETS.contains(prefix)) {
                    next = new LinkState(prior.start(), prior.end(), "", true, prior.removed());
                } else if (TARGETS.stream().anyMatch(candidate -> candidate.startsWith(prefix))) {
                    next = new LinkState(prior.start(), prior.end(), prefix, false, prior.removed());
                } else {
                    next = value == '[' ? new LinkState(new LinkStart(top, target.top), null, null, false, prior.removed())
                            : new LinkState(null, null, null, false, prior.removed());
                }
            } else if (value == '[' && prior.start() == null) {
                next = new LinkState(new LinkStart(top, target.top), null, null, false, prior.removed());
            } else if (value == ']' && prior.start() != null) {
                next = new LinkState(prior.start(), top, "", false, prior.removed());
            }
            target.append(unit);
            return next;
        }
    }

    private record LinkStart(Frame input, Frame output) {
    }

    private record LinkState(LinkStart start, Frame end, String prefix, boolean target, int removed) {
    }

    /** The exact ASCII placeholder grammar, with constant state even for unbounded digit runs. */
    private static final class TokenRecognizer extends Sink {
        private static final TokenState EMPTY = new TokenState(0, '\0', 0, true, false, true, false);
        private final MaskingContext context;

        TokenRecognizer(MaskingContext context, Cost cost) {
            super(null, cost);
            this.context = context;
        }

        TokenState state() {
            return top == null ? EMPTY : (TokenState) top.state();
        }

        @Override
        Object project(Unit unit) {
            TokenState prior = state();
            char value = unit.value();
            int phase = prior.phase();
            char kind = prior.kind();
            int number = prior.number();
            boolean literal = prior.literal() && unit.literal();
            boolean refused = prior.refused();
            boolean whitespace = value == ' ' || value >= '\t' && value <= '\r';
            if (phase == 2 && whitespace || phase == 5 && whitespace) {
                phase = prior.phase();
            } else if (phase == 2 && (value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z')) {
                kind = value;
                phase = 3;
            } else if (phase == 3 && value >= '1' && value <= '9') {
                number = value - '0';
                phase = 4;
            } else if (phase == 4 && value >= '0' && value <= '9') {
                number = number < 0 || number > (Integer.MAX_VALUE - (value - '0')) / 10
                        ? -1 : number * 10 + value - '0';
            } else if (phase == 4 && whitespace) {
                phase = 5;
            } else if ((phase == 4 || phase == 5) && value == '}') {
                phase = 6;
            } else if (phase == 6 && value == '}') {
                refused |= !literal || kind < 'A' || kind > 'Z' || number < 0
                        || context.demaskableValueForToken("{{" + kind + number + "}}") == null;
                phase = 0;
            } else if (value == '{') {
                phase = prior.lastBrace() ? 2 : 1;
                literal = unit.literal() && (phase == 1 || prior.lastLiteral());
            } else {
                phase = 0;
            }
            return new TokenState(phase, kind, number, literal, value == '{', unit.literal(), refused);
        }
    }

    private record TokenState(int phase, char kind, int number, boolean literal, boolean lastBrace, boolean lastLiteral, boolean refused) {
    }
}
