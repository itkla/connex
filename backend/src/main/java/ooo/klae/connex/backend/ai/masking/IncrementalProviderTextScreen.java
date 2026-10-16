package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Incremental counterpart of {@link MaskingContext#containsUnexposedTokenInProviderText}.
 * Projection checkpoints contain parser state, never field history. Open record labels advance
 * an alternate downstream checkpoint, so completing a link retracts its target without replay.
 * Refusal is sampled after the callback because a completed link can remove a speculative match.
 * An open placeholder or nested Unicode escape exceeding 16,384 units fails closed.
 */
public final class IncrementalProviderTextScreen {
    /**
     * Exceeds the 16,000-character durable partial limit while bounding one unresolved candidate.
     * Ordinary field length never consumes this bound.
     */
    public static final int MAX_CANDIDATE_CHARACTERS = 16_384;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final List<String> TARGETS = List.of("(person:", "(company:", "(deal:", "(record:");
    private final Projection raw;
    private final Projection decoded;
    private final Sink escapes;
    private final ArrayList<EscapeUnit> unicode = new ArrayList<>();
    private boolean overlong;
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

    /**
     * Appends new text and returns the complete-field oracle's decision, or a permanent refusal
     * when an unresolved candidate exceeds {@link #MAX_CANDIDATE_CHARACTERS}.
     */
    public boolean append(String delta) {
        Objects.requireNonNull(delta, "delta");
        inputCharacters += delta.length();
        for (int index = 0; index < delta.length() && !overlong; index++) {
            cost.beginCharacter();
            char value = delta.charAt(index);
            raw.normalizer.append(new Unit(value, true));
            appendEscape(value);
            while (unicode.size() >= 6) {
                int start = unicode.size() - 6;
                if (unicode.get(start).value() != '\\' || unicode.get(start + 1).value() != 'u') {
                    break;
                }
                int code = 0;
                for (int offset = start + 2; offset < start + 6; offset++) {
                    int digit = Character.digit(unicode.get(offset).value(), 16);
                    if (digit < 0) {
                        code = -1;
                        break;
                    }
                    code = code * 16 + digit;
                }
                if (code < 0) {
                    break;
                }
                escapes.restore(unicode.get(start).before());
                unicode.subList(start, unicode.size()).clear();
                appendEscape((char) code);
            }
            trimEscapes();
            overlong = unicode.size() > MAX_CANDIDATE_CHARACTERS || cost.overlong;
        }
        return overlong || raw.refused() || decoded.refused();
    }

    private void appendEscape(char value) {
        unicode.add(new EscapeUnit(value, escapes.top));
        escapes.append(new Unit(value, true));
    }

    /** Only incomplete, potentially nested Unicode escape prefixes can participate in reductions. */
    private void trimEscapes() {
        int end = unicode.size();
        int start = end - 1;
        while (start >= 0 && unicode.get(start).value() != '\\') {
            start--;
        }
        if (start < 0 || end - start > 5 || end - start > 1 && unicode.get(start + 1).value() != 'u') {
            unicode.clear();
            return;
        }
        for (int index = start + 2; index < end; index++) {
            if (Character.digit(unicode.get(index).value(), 16) < 0) {
                unicode.clear();
                return;
            }
        }
    }

    private record EscapeUnit(char value, Frame before) {
    }

    /** Counts actual projection/recognizer character visits for deterministic complexity tests. */
    long scannedCharacters() {
        return cost.characters;
    }

    /** Counts newly supplied characters, excluding projection work and rollbacks. */
    public long inputCharacters() {
        return inputCharacters;
    }

    /** Counts reachable semantic snapshots, excluding the current character's temporary memo tables. */
    int retainedSnapshots() {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Frame, Boolean>());
        var pending = new java.util.ArrayDeque<Frame>();
        if (raw.normalizer.top != null) {
            pending.add(raw.normalizer.top);
        }
        if (escapes.top != null) {
            pending.add(escapes.top);
        }
        for (EscapeUnit unit : unicode) {
            if (unit.before() != null) {
                pending.add(unit.before());
            }
        }
        while (!pending.isEmpty()) {
            Frame frame = pending.removeLast();
            if (!seen.add(frame)) {
                continue;
            }
            if (frame.output != null) {
                pending.add(frame.output);
            }
            if (frame.state instanceof LinkState link && link.label() != null) {
                pending.add(link.label());
            } else if (frame.state instanceof ControlState control && control.before() != null) {
                pending.add(control.before());
            } else if (frame.state instanceof UnicodeState state) {
                for (Scalar scalar : new Scalar[] {state.current(), state.preceding()}) {
                    if (scalar != null && scalar.start() != null) {
                        pending.add(scalar.start());
                    }
                }
            }
        }
        return seen.size();
    }

    private static final class Cost {
        private long characters;
        private boolean overlong;
        private final Map<Frame, Frame> frames = new HashMap<>();
        private final Map<Transition, Frame> transitions = new HashMap<>();

        void beginCharacter() {
            frames.clear();
            transitions.clear();
        }

        Frame intern(Frame frame) {
            return frames.computeIfAbsent(frame, key -> key);
        }
    }

    private record Unit(char value, boolean literal) {
        Unit transformed() {
            return literal ? new Unit(value, false) : this;
        }
    }

    private record Transition(Sink sink, Frame before, Unit unit) {
    }

    /** Immutable semantic snapshots have no same-stage history or source-position identity. */
    private abstract static class Sink {
        final Sink target;
        final Cost cost;
        Frame top;

        Sink(Sink target, Cost cost) {
            this.target = target;
            this.cost = cost;
        }

        final void append(Unit unit) {
            Transition transition = new Transition(this, top, unit);
            Frame cached = cost.transitions.get(transition);
            if (cached != null) {
                restore(cached);
                return;
            }
            cost.characters++;
            Object state = project(unit);
            top = cost.intern(new Frame(state, target == null ? null : target.top));
            cost.transitions.put(transition, top);
        }

        abstract Object project(Unit unit);

        final void restore(Frame checkpoint) {
            top = checkpoint;
            if (target != null) {
                target.restore(top == null ? null : top.output);
            }
        }

        void poisonLast() {
            cost.characters++;
            if (target != null) {
                target.poisonLast();
                top = cost.intern(new Frame(top == null ? null : top.state, target.top));
            }
        }
    }

    private static final class Frame {
        private final Object state;
        private final Frame output;
        private final int hash;

        Frame(Object state, Frame output) {
            this.state = state;
            this.output = output;
            hash = 31 * Objects.hashCode(state) + Objects.hashCode(output);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Frame frame && hash == frame.hash
                    && Objects.equals(state, frame.state) && Objects.equals(output, frame.output);
        }
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
            if (convergence.state().removed()) {
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
            UnicodeState old = top == null ? new UnicodeState(null, null) : (UnicodeState) top.state;
            Scalar prior = old.current();
            Frame start = target.top;
            if (prior != null && Character.isLowSurrogate(unit.value())
                    && prior.source().length() == 1 && Character.isHighSurrogate(prior.source().charAt(0))) {
                source = prior.source() + unit.value();
                target.restore(prior.start());
                start = target.top;
                prior = old.preceding();
            }
            String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC);
            boolean literal = source.equals(normalized) && unit.literal();
            if (prior != null) {
                Scalar preceding = prior;
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
            return new UnicodeState(new Scalar(source, normalized, start), prior);
        }

        private void emit(String text, boolean literal) {
            for (int index = 0; index < text.length(); index++) {
                target.append(new Unit(text.charAt(index), literal));
            }
        }
    }

    private record Scalar(String source, String normalized, Frame start) {
    }

    private record UnicodeState(Scalar current, Scalar preceding) {
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
            ControlState prior = top == null ? new ControlState(false, null) : (ControlState) top.state;
            Frame before = target.top;
            if (unit.value() == escaped && prior.backslash()) {
                target.restore(prior.before());
                target.append(new Unit(replacement, true));
            } else {
                target.append(unit);
            }
            return new ControlState(unit.value() == '\\', unit.value() == '\\' ? before : null);
        }
    }

    private record ControlState(boolean backslash, Frame before) {
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
     * A record-link pass advances both the literal output and a label-only semantic checkpoint.
     * Interning equal states shares nested alternatives; neither path retains the label text.
     */
    private static final class LinkProjection extends Sink {
        private static final LinkState EMPTY = new LinkState(false, null, false, null, false, false);

        LinkProjection(Sink target, Cost cost) {
            super(target, cost);
        }

        LinkState state() {
            return top == null ? EMPTY : (LinkState) top.state;
        }

        @Override
        Object project(Unit unit) {
            LinkState prior = state();
            LinkState next = prior;
            char value = unit.value();
            if (prior.target()) {
                if (value == ')') {
                    target.restore(prior.label());
                    target.poisonLast();
                    return new LinkState(false, null, false, null, false, true);
                }
            } else if (prior.prefix() != null) {
                String prefix = prior.prefix() + value;
                if (TARGETS.contains(prefix)) {
                    next = new LinkState(true, prior.label(), prior.nonempty(), "", true, prior.removed());
                } else if (TARGETS.stream().anyMatch(candidate -> candidate.startsWith(prefix))) {
                    next = new LinkState(true, prior.label(), prior.nonempty(), prefix, false, prior.removed());
                } else {
                    next = value == '[' ? new LinkState(true, target.top, false, null, false, prior.removed())
                            : new LinkState(false, null, false, null, false, prior.removed());
                }
            } else if (value == '[' && !prior.open()) {
                next = new LinkState(true, target.top, false, null, false, prior.removed());
            } else if (value == ']' && prior.open()) {
                next = new LinkState(true, prior.label(), prior.nonempty(), "", false, prior.removed());
            } else if (prior.open()) {
                Frame literal = target.top;
                target.restore(prior.label());
                target.append(prior.nonempty() ? unit : unit.transformed());
                Frame label = target.top;
                target.restore(literal);
                next = new LinkState(true, label, true, null, false, prior.removed());
            }
            target.append(unit);
            return next;
        }

        @Override
        void poisonLast() {
            cost.characters++;
            LinkState prior = state();
            Frame label = prior.label();
            if (prior.open() && prior.prefix() == null && !prior.target() && prior.nonempty()) {
                Frame literal = target.top;
                target.restore(label);
                target.poisonLast();
                label = target.top;
                target.restore(literal);
            }
            target.poisonLast();
            top = cost.intern(new Frame(new LinkState(prior.open(), label, prior.nonempty(), prior.prefix(),
                    prior.target(), prior.removed()), target.top));
        }
    }

    private record LinkState(boolean open, Frame label, boolean nonempty, String prefix, boolean target, boolean removed) {
    }

    /** The exact ASCII placeholder grammar, retaining only bounded recognition state. */
    private static final class TokenRecognizer extends Sink {
        private static final TokenState EMPTY = new TokenState(0, '\0', 0, true, false, true, false, false, 0);
        private final MaskingContext context;

        TokenRecognizer(MaskingContext context, Cost cost) {
            super(null, cost);
            this.context = context;
        }

        TokenState state() {
            return top == null ? EMPTY : (TokenState) top.state;
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
            boolean completed = false;
            int length = prior.phase() == 0 ? 0 : prior.length() + 1;
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
                completed = true;
                refused |= !literal || kind < 'A' || kind > 'Z' || number < 0
                        || context.demaskableValueForToken("{{" + kind + number + "}}") == null;
                phase = 0;
            } else if (value == '{') {
                phase = prior.lastBrace() ? 2 : 1;
                literal = unit.literal() && (phase == 1 || prior.lastLiteral());
                length = phase == 1 ? 1 : 2;
            } else {
                phase = 0;
            }
            if (phase == 0) {
                length = 0;
                kind = '\0';
                number = 0;
                literal = true;
            }
            cost.overlong |= length > MAX_CANDIDATE_CHARACTERS;
            return new TokenState(phase, kind, number, literal, value == '{', value != '{' || unit.literal(),
                    refused, completed, length);
        }

        @Override
        void poisonLast() {
            cost.characters++;
            TokenState state = state();
            top = cost.intern(new Frame(new TokenState(state.phase(), state.kind(), state.number(),
                    state.phase() == 0, state.lastBrace(), false,
                    state.refused() || state.completed(), state.completed(), state.length()), null));
        }

    }

    private record TokenState(int phase, char kind, int number, boolean literal, boolean lastBrace,
            boolean lastLiteral, boolean refused, boolean completed, int length) {
    }
}
