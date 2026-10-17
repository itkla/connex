package ooo.klae.connex.backend.services;

import static ooo.klae.connex.backend.services.ReferenceService.TYPE_ACTIVITY;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_COMPANY;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_DEAL;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_FILE;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_NOTE;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_PERSON;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_TASK;
import static ooo.klae.connex.backend.services.ReferenceService.TYPE_USER;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.web.util.HtmlUtils;

import io.micrometer.core.instrument.Metrics;
import ooo.klae.connex.backend.ai.masking.CanonicalText;
import ooo.klae.connex.backend.services.ReferenceService.MemberReference;

/** Reads internal Markdown destinations and member provenance without granting authorization. */
final class ReferenceContentReader {
    static final int MAX_INPUT_LENGTH = 262_144;
    static final int MAX_NESTING = 100;
    static final int MAX_TABLE_COLUMNS = 256;
    static final int MAX_TABLE_CELLS = 8_192;
    static final long MAX_BRACKET_WORK = 16_777_216;
    private static final int MAX_MEMBER_LABEL_CHARACTERS = 999;
    private static final String REFERENCE_SEPARATOR = "(?::|&colon;|\\\\:)";
    private static final String REFERENCE_TYPE_PATTERN =
        "(user|person|deal|company|note|file|task|activity)";
    private static final List<String> CENSUS_TYPES = List.of(TYPE_USER, TYPE_PERSON, TYPE_DEAL, TYPE_COMPANY,
            TYPE_NOTE, TYPE_FILE, TYPE_TASK, TYPE_ACTIVITY);
    private static final Pattern DESTINATION_CENSUS = Pattern.compile(
        "(?i)" + censusTypePattern()
            + "(?:\\\\)?+(?::|&colon;|&#0*+58;|&#x0*+3a;)"
            + "(?<id>(?:\\p{javaDigit}++|&#[0-9]++;|&#x[0-9a-f]++;)++)");
    private static final Pattern CENSUS_ENTITY = Pattern.compile(
        "&(?:#(?:[0-9]++|[xX][0-9a-fA-F]++)|[A-Za-z][A-Za-z0-9]*+);");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
        "(?im)^[ \\t]{0,3}+\\[((?:\\\\.|[^\\]\\\\])++)\\]:[ \\t]*+"
            + "(?:\\r?\\n[ \\t]++)?<?" + REFERENCE_TYPE_PATTERN
            + ":(\\d+)>?(?:[ \\t].*)?$");
    private static final Pattern MEMBER_CANONICAL_DESTINATION = Pattern.compile(
        "\\]\\(" + REFERENCE_TYPE_PATTERN + REFERENCE_SEPARATOR + "(\\d++)\\)");
    private static final Pattern MEMBER_REFERENCE_DEFINITION = Pattern.compile(
        "\\[((?:\\\\.|[^\\[\\]\\\\]){1,999}+)\\]:");
    private static final Pattern MEMBER_REFERENCE_DESTINATION = Pattern.compile(
        "(?i)<?user:(\\d+)>?(?:[ \\t].*)?");
    private static final Pattern MEMBER_DEFINITION_DESTINATION = Pattern.compile("(?i)<?user:(\\d+)>?");
    private static final String MEMBER_LINE_PREFIX = "[ ]{0,3}+(?:(?:>[ \t]?|[-+*][ \t]++|[0-9]{1,9}[.)][ \t]++)[ ]{0,3}+)*+";
    private static final Pattern MEMBER_LINE_CONTENT = Pattern.compile(MEMBER_LINE_PREFIX);
    private static final Pattern MEMBER_CONTINUATION_PREFIX = Pattern.compile("(?m)^(?!\\A)" + MEMBER_LINE_PREFIX);
    private static final Pattern MEMBER_CODE_BLOCK_START = Pattern.compile(
        "(?:#{1,6}(?:[ \t]|$)|`{3,}+[^`]*+$|~{3,}+"
            + "|<!--|<\\?|<![A-Za-z]|<!\\[CDATA\\["
            + "|<(?i:pre|script|style|textarea)(?:[ \t>]|$)"
            + "|</?(?i:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details"
            + "|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html"
            + "|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|search|section"
            + "|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?:[ \t>]|/>|$))");
    private static final String MEMBER_INLINE_SPACE = "[ \\t]*(?:(?:\\r\\n|[\\r\\n])[ \\t]*)?";
    private static final String MEMBER_ATTRIBUTE_SPACE = "(?:[ \\t]++|[ \\t]*(?:\\r\\n|[\\r\\n])[ \\t]*)";
    private static final String MEMBER_ATTRIBUTE_VALUE = "(?:[^ \\t\\r\\n\\\"'=<>`]++|'[^']*+'|\\\"[^\\\"]*+\\\")";
    private static final Pattern MEMBER_OPAQUE_INLINE = Pattern.compile(
        "(?:<[A-Za-z][A-Za-z0-9-]*+(?:" + MEMBER_ATTRIBUTE_SPACE
            + "[A-Za-z_:][A-Za-z0-9_.:-]*+(?:" + MEMBER_INLINE_SPACE + "=" + MEMBER_INLINE_SPACE
            + MEMBER_ATTRIBUTE_VALUE + ")?+)*+" + MEMBER_INLINE_SPACE + "/?>"
            + "|</[A-Za-z][A-Za-z0-9-]*+" + MEMBER_INLINE_SPACE + ">"
            + "|<[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\x00-\\x20]*+>"
            + "|<[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]++@"
            + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*+>)");

    private static final Parser PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .extensions(List.of(TablesExtension.builder().maxCells(MAX_TABLE_CELLS).build(),
                    FootnotesExtension.create()))
            .maxOpenBlockParsers(MAX_NESTING)
            .maxInlineNesting(MAX_NESTING)
            .build();
    private static final Parser PARSER_WITHOUT_TABLES = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .extensions(List.of(FootnotesExtension.create()))
            .maxOpenBlockParsers(MAX_NESTING)
            .maxInlineNesting(MAX_NESTING)
            .build();

    private ReferenceContentReader() {
    }

    enum Status {
        COMPLETE, INPUT_LIMIT, NESTING_LIMIT, TABLE_LIMIT, BRACKET_LIMIT, UNSUPPORTED_FOOTNOTE, PARSE_FAILURE
    }

    /** Source spans refer to the unchanged UTF-16 input, including escaped pipes in table cells. */
    record LinkOccurrence(String type, Optional<ReferenceTarget> target, List<SourceSpan> sourceSpans,
            List<SourceSpan> labelSpans, LabelReadings labels) {
        LinkOccurrence {
            sourceSpans = List.copyOf(sourceSpans);
            labelSpans = List.copyOf(labelSpans);
        }

        String authoredLabel() {
            return labels.first().authored();
        }

        String logicalLabel() {
            return labels.first().logical();
        }

        String decodedLabel() {
            return labels.first().decoded();
        }

        /** Both parses deduplicate by original syntax without evaluating lazy member labels. */
        @Override
        public boolean equals(Object other) {
            return other instanceof LinkOccurrence link && type.equals(link.type()) && target.equals(link.target())
                    && sourceSpans.equals(link.sourceSpans()) && labelSpans.equals(link.labelSpans());
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, target, sourceSpans, labelSpans);
        }
    }

    /** Compatibility and census candidates are additive; only AST destinations explain census hits. */
    record Reading(List<LinkOccurrence> links, List<ReferenceTarget> targets, Set<String> unparseableTypes,
            Lazy<MemberAnalysis> members, Set<ReferenceTarget> unexplainedTargets, Status status,
            boolean censusScanned) {
        Reading {
            links = List.copyOf(links);
            targets = List.copyOf(targets);
            unparseableTypes = Set.copyOf(unparseableTypes);
            unexplainedTargets = Set.copyOf(unexplainedTargets);
        }

        List<MemberReference> memberReferences() {
            return members.get().references().source();
        }

        List<MemberReference> maskingReferences() {
            return members.get().references().masking();
        }

        boolean memberTargetsComplete() {
            return members.get().complete();
        }

        boolean memberLabelsComputed() {
            return members.computed() || links.stream().anyMatch(link -> link.labels().computed());
        }

        boolean complete() {
            return status == Status.COMPLETE;
        }

        boolean isEmpty() {
            return targets.isEmpty() && unparseableTypes.isEmpty();
        }
    }

    record ReferenceTarget(String type, int id) {
    }

    private record MarkdownTargets(List<ReferenceTarget> targets, Set<String> unparseableTypes,
            boolean unparseableMemberLinks) {
    }

    private record CensusHit(ReferenceTarget target, int start, int end) {
    }

    private record DestinationCensus(List<CensusHit> hits, Set<ReferenceTarget> targets, Set<String> unparseableTypes) {
    }

    private record MemberAnalysis(MemberReadings references, boolean complete) {
    }

    private record LabelText(String authored, String logical, String decoded) {
    }

    /** Same source spans can render differently across table contexts; retain both lazy readings. */
    private static final class LabelReadings {
        private final List<Lazy<LabelText>> readings = new ArrayList<>();

        private LabelReadings(Supplier<LabelText> supplier) {
            readings.add(new Lazy<>(supplier));
        }

        private void add(LabelReadings additional) {
            readings.addAll(additional.readings);
        }

        private LabelText first() {
            return readings.getFirst().get();
        }

        private List<String> decoded() {
            return readings.stream().map(reading -> reading.get().decoded()).distinct().toList();
        }

        private boolean computed() {
            return readings.stream().anyMatch(Lazy::computed);
        }
    }

    /** Invocation-local memoization keeps member extraction off visibility and snippet paths. */
    private static final class Lazy<T> {
        private final Supplier<T> supplier;
        private T value;

        private Lazy(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        private synchronized T get() {
            if (value == null) {
                value = supplier.get();
            }
            return value;
        }

        private synchronized boolean computed() {
            return value != null;
        }
    }

    /**
     * Parses bounded original source with and without tables, without global entity decoding or
     * truncation. The census and fixed-number compatibility scans still cover the entire source
     * when parser admission fails. Shared target groups avoid label/id Cartesian expansion.
     */
    static Reading read(String content) {
        if (content == null || content.isEmpty() || content.length() <= MAX_INPUT_LENGTH && plainContent(content)) {
            return new Reading(List.of(), List.of(), Set.of(),
                    new Lazy<>(() -> new MemberAnalysis(new MemberReadings(List.of(), List.of()), true)),
                    Set.of(), Status.COMPLETE, false);
        }
        Map<LinkOccurrence, LinkOccurrence> links = new LinkedHashMap<>();
        DestinationCensus census = destinationCensus(content);
        MarkdownTargets compatibility = content.length() <= MAX_INPUT_LENGTH
                ? compatibilityTargets(content) : new MarkdownTargets(List.of(), Set.of(), false);
        var targets = new LinkedHashSet<>(compatibility.targets());
        var unparseable = new HashSet<>(compatibility.unparseableTypes());
        var parsedTargets = new LinkedHashSet<ReferenceTarget>();
        Status status = content.length() > MAX_INPUT_LENGTH ? Status.INPUT_LIMIT : tableBudgetStatus(content);
        if (status == Status.COMPLETE) {
            status = bracketBudgetStatus(content);
        }
        Status sourceFootnotes = footnoteSourceStatus(content);
        CensusCoverage coverage = new CensusCoverage(census.hits(), status == Status.COMPLETE ? content.length() : 0);
        if (status == Status.COMPLETE) {
            try {
                status = collectParsed(PARSER.parse(content), content, links, parsedTargets, unparseable, coverage);
                Status withoutTables = collectParsed(PARSER_WITHOUT_TABLES.parse(content), content, links,
                        parsedTargets, unparseable, coverage);
                status = mergeStatus(status, withoutTables);
            } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
                status = Status.PARSE_FAILURE;
            }
        }
        status = mergeStatus(status, sourceFootnotes);
        if (status == Status.UNSUPPORTED_FOOTNOTE && census.targets().isEmpty() && census.unparseableTypes().isEmpty()) {
            status = Status.COMPLETE;
        }
        targets.addAll(parsedTargets);
        targets.addAll(census.targets());
        unparseable.addAll(census.unparseableTypes());
        Set<ReferenceTarget> unexplained = coverage.unexplained();
        if (status != Status.COMPLETE) {
            Metrics.counter("connex.reference.content.incomplete", "reason", status.name().toLowerCase(Locale.ROOT)).increment();
        }
        boolean parsedCompletely = status == Status.COMPLETE;
        var members = new Lazy<>(() -> {
            var references = memberReferences(content, memberLabels(content), links.values());
            boolean complete = !compatibility.unparseableMemberLinks()
                    && !census.unparseableTypes().contains(TYPE_USER)
                    && unexplained.stream().noneMatch(target -> TYPE_USER.equals(target.type()))
                    && links.values().stream().noneMatch(link -> TYPE_USER.equals(link.type()) && (link.target().isEmpty()
                            || !link.labelSpans().isEmpty() && link.authoredLabel().isEmpty()));
            if (parsedCompletely && !complete) {
                Metrics.counter("connex.reference.content.incomplete", "reason", "member_provenance").increment();
            }
            return new MemberAnalysis(references, complete);
        });
        return new Reading(new ArrayList<>(links.values()), new ArrayList<>(targets), unparseable,
                members, unexplained, status, true);
    }

    private static boolean plainContent(String content) {
        return content.indexOf('[') < 0 && content.indexOf('<') < 0 && content.indexOf('&') < 0
                && content.indexOf('\\') < 0 && content.indexOf(':') < 0 && content.indexOf('：') < 0;
    }

    private static Status mergeStatus(Status current, Status additional) {
        return current == Status.COMPLETE || current == Status.UNSUPPORTED_FOOTNOTE && additional != Status.COMPLETE
                ? additional : current;
    }

    /**
     * Visits only literal Markdown destination anchors in the original source. Normalization
     * applies to each following destination, never to syntax; encoded brackets cannot create an
     * anchor. Candidate runs stop before another anchor, so work remains linear even in code.
     */
    private static DestinationCensus destinationCensus(String content) {
        List<CensusHit> hits = new ArrayList<>();
        Set<ReferenceTarget> targets = new LinkedHashSet<>();
        Set<String> unparseable = new HashSet<>();
        int bracket = content.indexOf(']');
        int angle = content.indexOf('<');
        int previousStart = -1;
        while (bracket >= 0 || angle >= 0) {
            int start = -1;
            if (angle >= 0 && (bracket < 0 || angle < bracket)) {
                start = angle + 1;
                angle = content.indexOf('<', angle + 1);
            } else {
                if (bracket + 1 < content.length()
                        && (content.charAt(bracket + 1) == '(' || content.charAt(bracket + 1) == ':')) {
                    start = censusDestinationStart(content, bracket + 2);
                }
                bracket = content.indexOf(']', bracket + 1);
            }
            if (start < 0 || start == previousStart || start == content.length()) {
                continue;
            }
            previousStart = start;
            int end = start;
            while (end < content.length() && !censusDestinationEnd(content.charAt(end))) {
                end++;
            }
            if (end == start) {
                continue;
            }
            CanonicalText.Projection projection = censusProjection(content.substring(start, end));
            Matcher destination = DESTINATION_CENSUS.matcher(projection.value());
            if (!destination.lookingAt()) {
                continue;
            }
            for (String type : CENSUS_TYPES) {
                if (destination.start(type) >= 0) {
                    var target = parseReferenceTarget(type, normalizeReferenceSyntax(destination.group("id")));
                    if (target.isPresent()) {
                        targets.add(target.get());
                        hits.add(new CensusHit(target.get(), start + projection.sourceStart(destination.start()),
                                start + projection.sourceEnd(destination.end())));
                    } else {
                        unparseable.add(type);
                    }
                    break;
                }
            }
        }
        return new DestinationCensus(hits, targets, unparseable);
    }

    /** Allows one physical continuation line, retaining literal list/blockquote prefixes. */
    private static int censusDestinationStart(String content, int start) {
        int offset = skipHorizontalSpace(content, start);
        if (offset < content.length() && (content.charAt(offset) == '\r' || content.charAt(offset) == '\n')) {
            offset = afterDefinitionLineEnd(content, offset);
            offset = skipHorizontalSpace(content, offset);
            boolean container = true;
            while (container && offset < content.length()) {
                char marker = content.charAt(offset);
                if (marker == '>') {
                    offset = skipHorizontalSpace(content, offset + 1);
                } else if ((marker == '-' || marker == '+' || marker == '*') && offset + 1 < content.length()
                        && (content.charAt(offset + 1) == ' ' || content.charAt(offset + 1) == '\t')) {
                    offset = skipHorizontalSpace(content, offset + 1);
                } else if (marker >= '0' && marker <= '9') {
                    int digits = offset;
                    while (digits < content.length() && digits - offset < 9
                            && content.charAt(digits) >= '0' && content.charAt(digits) <= '9') {
                        digits++;
                    }
                    if (digits + 1 < content.length() && (content.charAt(digits) == '.' || content.charAt(digits) == ')')
                            && (content.charAt(digits + 1) == ' ' || content.charAt(digits + 1) == '\t')) {
                        offset = skipHorizontalSpace(content, digits + 1);
                    } else {
                        container = false;
                    }
                } else {
                    container = false;
                }
            }
        }
        return offset < content.length() && content.charAt(offset) == '<' ? offset + 1 : offset;
    }

    private static int skipHorizontalSpace(String content, int start) {
        int offset = start;
        while (offset < content.length() && (content.charAt(offset) == ' ' || content.charAt(offset) == '\t')) {
            offset++;
        }
        return offset;
    }

    private static boolean censusDestinationEnd(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || "<>[]()\"'`|".indexOf(character) >= 0;
    }

    /** Backend entity decoding composes with the exact source-mapped fold used by AI screening. */
    private static CanonicalText.Projection censusProjection(String content) {
        Matcher entities = CENSUS_ENTITY.matcher(content);
        if (!entities.find()) {
            return CanonicalText.project(content);
        }
        StringBuilder decoded = new StringBuilder(content.length());
        int[] starts = new int[content.length()];
        int[] ends = new int[content.length()];
        int source = 0;
        do {
            while (source < entities.start()) {
                starts[decoded.length()] = source;
                ends[decoded.length()] = source + 1;
                decoded.append(content.charAt(source++));
            }
            String entity = entities.group();
            String value = entity.equalsIgnoreCase("&colon;") ? ":" : HtmlUtils.htmlUnescape(entity);
            int start = decoded.length();
            decoded.append(value);
            Arrays.fill(starts, start, decoded.length(), entities.start());
            Arrays.fill(ends, start, decoded.length(), entities.end());
            source = entities.end();
        } while (entities.find());
        while (source < content.length()) {
            starts[decoded.length()] = source;
            ends[decoded.length()] = source + 1;
            decoded.append(content.charAt(source++));
        }
        CanonicalText.Projection normalized = CanonicalText.project(decoded.toString());
        int[] projectedStarts = new int[normalized.value().length()];
        int[] projectedEnds = new int[normalized.value().length()];
        for (int offset = 0; offset < normalized.value().length(); offset++) {
            projectedStarts[offset] = starts[normalized.sourceStart(offset)];
            projectedEnds[offset] = ends[normalized.sourceEnd(offset + 1) - 1];
        }
        return new CanonicalText.Projection(normalized.value(), projectedStarts, projectedEnds);
    }

    /** Entity-encoded type letters must not hide renderer-recognized destinations from the census. */
    private static String censusTypePattern() {
        var pattern = new StringBuilder("(?:");
        for (String type : CENSUS_TYPES) {
            if (pattern.length() > 3) {
                pattern.append('|');
            }
            pattern.append("(?<").append(type).append('>');
            for (int index = 0; index < type.length(); index++) {
                char letter = type.charAt(index);
                char upper = Character.toUpperCase(letter);
                pattern.append("(?:").append(letter)
                        .append("|&#0*+").append((int) letter).append(';')
                        .append("|&#0*+").append((int) upper).append(';')
                        .append("|&#x0*+(?:").append(Integer.toHexString(letter))
                        .append('|').append(Integer.toHexString(upper)).append(");)");
            }
            pattern.append(')');
        }
        return pattern.append(')').toString();
    }

    /**
     * Matches individual census occurrences to actual destination source, never to another token
     * with the same ID. The offset index is bounded by parser admission and gives constant-time
     * coverage lookup; labels, reference keys and titles cannot explain a destination token.
     */
    private static final class CensusCoverage {
        private final List<CensusHit> hits;
        private final int[] hitAt;
        private final boolean[] explained;

        private CensusCoverage(List<CensusHit> hits, int inputLength) {
            this.hits = hits;
            hitAt = new int[inputLength];
            explained = new boolean[hits.size()];
            for (int index = 0; index < hits.size(); index++) {
                if (hits.get(index).start() < hitAt.length) {
                    hitAt[hits.get(index).start()] = index + 1;
                }
            }
        }

        private void explain(Node node, ReferenceTarget target, String content, TableSource tableSource) {
            var source = new SourceCursor(content, sourceSpans(node, tableSource));
            source.skipWhitespace();
            if (node instanceof LinkReferenceDefinition) {
                if (!source.take('[')) {
                    return;
                }
                while (source.current() >= 0 && source.current() != ']') {
                    if (source.current() == '\\') {
                        int line = source.span;
                        source.advance();
                        if (source.span == line) {
                            source.advance();
                        }
                    } else {
                        source.advance();
                    }
                }
                if (!source.take(']') || !source.take(':')) {
                    return;
                }
            } else if (source.take('<')) {
                explainAt(source, target);
                return;
            } else {
                Node last = node.getLastChild();
                List<SourceSpan> label = List.of();
                while (last != null && label.isEmpty()) {
                    label = sourceSpans(last, tableSource);
                    last = last.getPrevious();
                }
                if (label.isEmpty()) {
                    source.take('!');
                    if (!source.take('[')) {
                        return;
                    }
                } else {
                    SourceSpan end = label.getLast();
                    source.seek(end.getInputIndex() + end.getLength());
                }
                source.skipWhitespace();
                if (!source.take(']') || !source.take('(')) {
                    return;
                }
            }
            source.skipWhitespace();
            source.take('<');
            explainAt(source, target);
        }

        private void explainAt(SourceCursor source, ReferenceTarget target) {
            if (source.current() < 0 || source.index >= hitAt.length) {
                return;
            }
            int ordinal = hitAt[source.index] - 1;
            if (ordinal >= 0) {
                CensusHit hit = hits.get(ordinal);
                if (hit.target().equals(target) && hit.end() <= source.spanEnd()) {
                    explained[ordinal] = true;
                }
            }
        }

        private Set<ReferenceTarget> unexplained() {
            Set<ReferenceTarget> result = new HashSet<>();
            for (int index = 0; index < hits.size(); index++) {
                if (!explained[index]) {
                    result.add(hits.get(index).target());
                }
            }
            return result;
        }
    }

    /** Walks original node spans while skipping container prefixes between physical source lines. */
    private static final class SourceCursor {
        private final String content;
        private final List<SourceSpan> spans;
        private int span;
        private int index;

        private SourceCursor(String content, List<SourceSpan> spans) {
            this.content = content;
            this.spans = spans;
            index = spans.isEmpty() ? content.length() : spans.getFirst().getInputIndex();
            seek(index);
        }

        private void seek(int offset) {
            index = offset;
            while (span < spans.size() && index >= spanEnd()) {
                span++;
                if (span < spans.size()) {
                    index = Math.max(index, spans.get(span).getInputIndex());
                }
            }
        }

        private int spanEnd() {
            SourceSpan current = spans.get(span);
            return current.getInputIndex() + current.getLength();
        }

        private int current() {
            return span < spans.size() ? content.charAt(index) : -1;
        }

        private void advance() {
            if (current() >= 0) {
                seek(index + 1);
            }
        }

        private boolean take(char expected) {
            if (current() != expected) {
                return false;
            }
            advance();
            return true;
        }

        private void skipWhitespace() {
            while (current() == ' ' || current() == '\t' || current() == '\r' || current() == '\n') {
                advance();
            }
        }
    }

    /**
     * Bounds prospective table expansion before the extension allocates padded cells. Pipes in
     * code and HTML also count conservatively; separator rows and edge pipes consume this budget.
     * The AST subsequently enforces the exact column bound, including rows without edge pipes.
     */
    private static Status tableBudgetStatus(String content) {
        int pipes = 0;
        int width = 0;
        int cells = 0;
        for (int index = 0; index <= content.length(); index++) {
            if (index == content.length() || content.charAt(index) == '\n' || content.charAt(index) == '\r') {
                if (pipes == 0) {
                    width = 0;
                } else {
                    if (pipes > MAX_TABLE_COLUMNS + 1) {
                        return Status.TABLE_LIMIT;
                    }
                    width = Math.max(width, pipes + 1);
                    cells += width;
                    if (cells > MAX_TABLE_CELLS) {
                        return Status.TABLE_LIMIT;
                    }
                }
                pipes = 0;
                if (index < content.length() && content.charAt(index) == '\r'
                        && index + 1 < content.length() && content.charAt(index + 1) == '\n') {
                    index++;
                }
            } else if (content.charAt(index) == '\\' && index + 1 < content.length()
                    && content.charAt(index + 1) == '|') {
                index++;
            } else if (content.charAt(index) == '|') {
                pipes++;
            }
        }
        return Status.COMPLETE;
    }

    /**
     * Bounds the upstream scan of prior brackets on each successful link. Cumulative raw openers
     * upper-bound the active parser stack even across escapes, code and unmatched delimiters;
     * decrementing at raw closers would underestimate that stack. The conservative overcount can
     * refuse dense balanced-link or literal-bracket bodies before the parser's quadratic path.
     */
    private static Status bracketBudgetStatus(String content) {
        long openers = 0;
        long work = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '[') {
                openers++;
            } else if (content.charAt(index) == ']') {
                work += openers;
                if (work > MAX_BRACKET_WORK) {
                    return Status.BRACKET_LIMIT;
                }
            }
        }
        return Status.COMPLETE;
    }

    /**
     * Detects unsupported escaped footnote definitions before parsing. A missed footnote can
     * change paragraph/code boundaries, leaving no definition node to diagnose after the parse.
     * Literal source examples are conservatively refused too rather than trusted via that AST.
     */
    private static Status footnoteSourceStatus(String content) {
        boolean candidate = false;
        boolean complex = false;
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value == '\n' || value == '\r') {
                candidate = false;
            } else if (value == '\\') {
                complex |= candidate;
                if (index + 1 < content.length() && content.charAt(index + 1) != '\n' && content.charAt(index + 1) != '\r') {
                    index++;
                }
            } else if (value == '[' && index + 1 < content.length() && content.charAt(index + 1) == '^') {
                candidate = true;
                complex = false;
                index++;
            } else if (value == '[' && candidate) {
                complex = true;
            } else if (value == ']') {
                if (candidate && complex && index + 1 < content.length() && content.charAt(index + 1) == ':') {
                    return Status.UNSUPPORTED_FOOTNOTE;
                }
                candidate = false;
            }
        }
        return Status.COMPLETE;
    }

    private record WalkEntry(Node node, int blocks, int inlines, TableSource tableSource) {
    }

    /** Saturation is incomplete even if the resulting bounded AST still contains some links. */
    private static Status collectParsed(Node document, String content, Map<LinkOccurrence, LinkOccurrence> links,
            Set<ReferenceTarget> targets, Set<String> unparseable, CensusCoverage coverage) {
        Status status = Status.COMPLETE;
        Deque<WalkEntry> pending = new ArrayDeque<>();
        pending.push(new WalkEntry(document, 0, 0, null));
        while (!pending.isEmpty()) {
            WalkEntry entry = pending.pop();
            Node node = entry.node();
            int blocks = entry.blocks() + (node instanceof Block && !(node instanceof Document) ? 1 : 0);
            int inlines = node instanceof Block || node instanceof TableCell ? 0 : entry.inlines()
                    + (node instanceof Link || node instanceof Image || node instanceof FootnoteReference
                            || node.getFirstChild() != null ? 1 : 0);
            if (inlines >= MAX_NESTING || blocks >= MAX_NESTING) {
                status = mergeStatus(status, Status.NESTING_LIMIT);
            }
            TableSource tableSource = node instanceof TableCell
                    ? TableSource.from(content, node.getSourceSpans()) : entry.tableSource();
            if (node instanceof TableRow) {
                int columns = 0;
                for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext()) {
                    columns++;
                }
                if (columns > MAX_TABLE_COLUMNS) {
                    status = mergeStatus(status, Status.TABLE_LIMIT);
                }
            }
            if (node instanceof LinkReferenceDefinition definition) {
                String destination = definition.getDestination();
                String type = collectDestination(destination, targets, unparseable);
                if (type != null) {
                    parseReferenceTarget(type, destination.substring(destination.indexOf(':') + 1))
                            .ifPresent(target -> coverage.explain(node, target, content, tableSource));
                }
                if (definition.getLabel().startsWith("^")) {
                    status = mergeStatus(status, Status.UNSUPPORTED_FOOTNOTE);
                }
            } else if (node instanceof FootnoteDefinition definition) {
                if (!(node.getParent() instanceof Document) || definition.getLabel().indexOf('\\') >= 0
                        || definition.getLabel().indexOf('[') >= 0) {
                    status = mergeStatus(status, Status.UNSUPPORTED_FOOTNOTE);
                }
            } else if (node instanceof Link || node instanceof Image) {
                String destination = node instanceof Link link ? link.getDestination() : ((Image) node).getDestination();
                String type = collectDestination(destination, targets, unparseable);
                if (type != null) {
                    LinkOccurrence occurrence = linkOccurrence(node, type, destination, content, tableSource);
                    links.merge(occurrence, occurrence, (original, variant) -> {
                        original.labels().add(variant.labels());
                        return original;
                    });
                    occurrence.target().ifPresent(target -> coverage.explain(node, target, content, tableSource));
                }
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(new WalkEntry(child, blocks, inlines, tableSource));
            }
        }
        return status;
    }

    private static String collectDestination(String destination, Collection<ReferenceTarget> targets, Set<String> unparseable) {
        int colon = destination.indexOf(':');
        String type = colon < 0 ? null : canonicalReferenceType(destination.substring(0, colon));
        if (type != null) {
            Optional<ReferenceTarget> target = parseReferenceTarget(type, destination.substring(colon + 1));
            if (target.isPresent()) {
                targets.add(target.get());
            } else {
                unparseable.add(type);
            }
        }
        return type;
    }

    private static LinkOccurrence linkOccurrence(Node node, String type, String destination,
            String content, TableSource tableSource) {
        List<SourceSpan> source = sourceSpans(node, tableSource);
        List<SourceSpan> label = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            label.addAll(sourceSpans(child, tableSource));
        }
        if (source.isEmpty() || node.getFirstChild() != null && label.isEmpty()) {
            throw new IllegalArgumentException("Missing internal reference source spans");
        }
        for (List<SourceSpan> spans : List.of(source, label)) {
            for (SourceSpan span : spans) {
                if (span.getInputIndex() > content.length() - span.getLength()) {
                    throw new IllegalArgumentException("Invalid internal reference source span");
                }
            }
        }
        int colon = destination.indexOf(':');
        Optional<ReferenceTarget> target = parseReferenceTarget(type, destination.substring(colon + 1));
        return new LinkOccurrence(type, target, source, label,
                new LabelReadings(() -> labelText(node, content, label)));
    }

    private static LabelText labelText(Node node, String content, List<SourceSpan> label) {
        if (label.isEmpty()) {
            return new LabelText("", "", "");
        }
        int start = label.getFirst().getInputIndex();
        SourceSpan last = label.getLast();
        int end = last.getInputIndex() + last.getLength();
        if (!memberLabelWithinLimit(content, start, end)) {
            return new LabelText("", "", "");
        }
        var logical = new StringBuilder();
        int line = -1;
        for (SourceSpan span : label) {
            if (line >= 0 && line != span.getLineIndex()) {
                logical.append('\n');
            }
            logical.append(content, span.getInputIndex(), span.getInputIndex() + span.getLength());
            line = span.getLineIndex();
        }
        return new LabelText(content.substring(start, end), logical.toString(), decodedLabel(node));
    }

    private static String decodedLabel(Node link) {
        var label = new StringBuilder();
        Deque<Node> pending = new ArrayDeque<>();
        for (Node child = link.getLastChild(); child != null; child = child.getPrevious()) {
            pending.push(child);
        }
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node instanceof Text text) {
                label.append(text.getLiteral());
            } else if (node instanceof Code code) {
                label.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                label.append('\n');
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(child);
            }
        }
        return label.toString();
    }

    private static List<SourceSpan> sourceSpans(Node node, TableSource tableSource) {
        return tableSource == null ? node.getSourceSpans()
                : node.getSourceSpans().stream().map(tableSource::original).toList();
    }

    /**
     * The table extension removes a slash before each escaped pipe before inline parsing, but
     * retains the cell's original span. Map inline boundaries back before slicing authored source.
     */
    private record TableSource(int start, int[] boundaries) {
        private static TableSource from(String content, List<SourceSpan> spans) {
            if (spans.isEmpty()) {
                return null;
            }
            SourceSpan span = spans.getFirst();
            int[] boundaries = new int[span.getLength() + 1];
            int length = 0;
            int end = span.getInputIndex() + span.getLength();
            for (int index = span.getInputIndex(); index < end; length++) {
                boundaries[length] = index;
                index += content.charAt(index) == '\\' && index + 1 < end && content.charAt(index + 1) == '|' ? 2 : 1;
            }
            boundaries[length] = end;
            return new TableSource(span.getInputIndex(), Arrays.copyOf(boundaries, length + 1));
        }

        private SourceSpan original(SourceSpan span) {
            int from = span.getInputIndex() - start;
            int to = from + span.getLength();
            if (from < 0 || to >= boundaries.length) {
                throw new IllegalArgumentException("Invalid table source span");
            }
            return SourceSpan.of(span.getLineIndex(), span.getColumnIndex() + boundaries[from] - span.getInputIndex(),
                    boundaries[from], boundaries[to] - boundaries[from]);
        }
    }

    private record LabelRange(int start, int end) {
    }

    private record MemberReadings(List<MemberReference> source, List<MemberReference> masking) {
    }

    private record MemberLabels(List<MemberLabelSpan> spans, Set<Integer> definitionStarts) {
    }

    private record MaskingMemberLabel(String label, MemberTargets targets) {
    }

    /** Preserves compatibility ordering and group identity before adding rendered label spellings. */
    private static MemberReadings memberReferences(
            String content, MemberLabels labels, Collection<LinkOccurrence> links) {
        List<MemberReference> references = new ArrayList<>();
        Map<MemberTargets, Set<String>> collected = new HashMap<>();
        Map<LabelRange, Set<MemberTargets>> groups = new HashMap<>();
        Map<Integer, MemberTargets> singletons = new HashMap<>();
        List<MaskingMemberLabel> additional = new ArrayList<>();
        for (MemberLabelSpan span : labels.spans()) {
            groups.computeIfAbsent(new LabelRange(span.start(), span.end()), ignored -> new LinkedHashSet<>())
                    .add(span.targets());
            if (span.targets().ids.size() == 1) {
                singletons.putIfAbsent(span.targets().ids.iterator().next(), span.targets());
            }
            collectMemberReferences(references, collected, span.targets(),
                    normalizeReferenceSyntax(content.substring(span.start(), span.end())));
        }
        labels.spans().forEach(span -> collectMemberReferences(references, collected, span.targets(),
                content.substring(span.start(), span.end())));
        for (LinkOccurrence link : links) {
            if (!TYPE_USER.equals(link.type()) || link.target().isEmpty() || link.labelSpans().isEmpty()) {
                continue;
            }
            int id = link.target().get().id();
            SourceSpan last = link.labelSpans().getLast();
            LabelRange range = new LabelRange(link.labelSpans().getFirst().getInputIndex(),
                    last.getInputIndex() + last.getLength());
            MemberTargets group = groups.getOrDefault(range, Set.of()).stream()
                    .filter(candidate -> candidate.ids.contains(id)).findFirst()
                    .orElseGet(() -> singletons.computeIfAbsent(id, MemberTargets::new));
            boolean sourceLabel = !labels.definitionStarts().contains(link.sourceSpans().getFirst().getInputIndex());
            for (String label : List.of(normalizeReferenceSyntax(link.authoredLabel()), link.authoredLabel(),
                    link.logicalLabel())) {
                if (memberLabelWithinLimit(label)) {
                    if (sourceLabel) {
                        collectMemberReferences(references, collected, group, label);
                    } else {
                        additional.add(new MaskingMemberLabel(label, group));
                    }
                }
            }
            for (String decoded : link.labels().decoded()) {
                additional.add(new MaskingMemberLabel(decoded, group));
            }
        }
        List<MemberReference> source = List.copyOf(references);
        for (MaskingMemberLabel label : additional) {
            if (memberLabelWithinLimit(label.label())) {
                collectMemberReferences(references, collected, label.targets(), label.label());
            }
        }
        return new MemberReadings(source, List.copyOf(references));
    }

    private static MarkdownTargets compatibilityTargets(String content) {
        if (content == null || content.isBlank()) {
            return new MarkdownTargets(List.of(), Set.of(), false);
        }
        String normalized = normalizeReferenceSyntax(content);
        if (normalized.indexOf('[') < 0) {
            return new MarkdownTargets(List.of(), Set.of(), false);
        }
        List<ReferenceTarget> targets = new ArrayList<>();
        Set<String> unparseableTypes = new HashSet<>();
        collectInlineTargets(normalized, targets, unparseableTypes);
        boolean unparseableMemberLinks = unparseableTypes.contains(TYPE_USER);
        collectReferenceTargets(REFERENCE_DEFINITION.matcher(normalized), targets, unparseableTypes);
        Matcher definitions = MEMBER_REFERENCE_DEFINITION.matcher(content);
        while (definitions.find()) {
            if (!isEscaped(content, definitions.start())) {
                String destination = normalizeReferenceSyntax(memberDefinitionDestination(content, definitions.end()));
                if (destination.startsWith("<") && destination.endsWith(">")) {
                    destination = destination.substring(1, destination.length() - 1);
                }
                collectDestination(destination, targets, unparseableTypes);
            }
        }
        return new MarkdownTargets(targets, unparseableTypes, unparseableMemberLinks);
    }

    static String normalizeReferenceSyntax(String content) {
        return HtmlUtils.htmlUnescape(content.replace("&colon;", ":")).replace("\\:", ":");
    }

    private static void collectReferenceTargets(
            Matcher matcher, List<ReferenceTarget> targets, Set<String> unparseableTypes) {
        while (matcher.find()) {
            Optional<ReferenceTarget> target = parseReferenceTarget(matcher.group(2), matcher.group(3));
            if (target.isPresent()) {
                targets.add(target.get());
            } else {
                String type = canonicalReferenceType(matcher.group(2));
                if (type != null) {
                    unparseableTypes.add(type);
                }
            }
        }
    }

    private static void collectInlineTargets(
            String content, List<ReferenceTarget> targets, Set<String> unparseableTypes) {
        int[] labelEnds = matchingLabelEnds(content);
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) != '[' || isEscaped(content, index)) {
                continue;
            }
            int labelEnd = labelEnds[index];
            if (labelEnd < 0) {
                continue;
            }
            int destinationStart = skipWhitespace(content, labelEnd + 1);
            if (destinationStart >= content.length() || content.charAt(destinationStart) != '(') {
                index = labelEnd;
                continue;
            }
            destinationStart = skipWhitespace(content, destinationStart + 1);
            if (destinationStart < content.length() && content.charAt(destinationStart) == '<') {
                destinationStart++;
            }
            int typeEnd = destinationStart;
            while (typeEnd < content.length() && Character.isLetter(content.charAt(typeEnd))) {
                typeEnd++;
            }
            if (typeEnd == destinationStart || typeEnd >= content.length()
                    || content.charAt(typeEnd) != ':') {
                index = labelEnd;
                continue;
            }
            String type = canonicalReferenceType(content.substring(destinationStart, typeEnd));
            if (type == null) {
                index = labelEnd;
                continue;
            }
            int idStart = typeEnd + 1;
            int idEnd = idStart;
            while (idEnd < content.length() && Character.isDigit(content.charAt(idEnd))) {
                idEnd++;
            }
            if (idEnd == idStart) {
                unparseableTypes.add(type);
            } else {
                Optional<ReferenceTarget> target = parseReferenceTarget(
                    type, content.substring(idStart, idEnd));
                if (target.isPresent()) {
                    targets.add(target.get());
                } else {
                    unparseableTypes.add(type);
                }
            }
            index = labelEnd;
        }
    }

    /** Retains each label/group once without copying or hashing its shared target set per label. */
    private static void collectMemberReferences(
            List<MemberReference> references, Map<MemberTargets, Set<String>> collected,
            MemberTargets targets, String label) {
        if (!label.isBlank() && collected.computeIfAbsent(targets, ignored -> new HashSet<>()).add(label)) {
            references.add(new MemberReference(label, targets.readOnlyIds));
        }
    }

    private static MemberLabels memberLabels(String content) {
        if (content == null || content.indexOf('[') < 0 || content.isBlank()) {
            return new MemberLabels(List.of(), Set.of());
        }
        Map<String, MemberTargets> memberDefinitions = new HashMap<>();
        ReferenceDelimiters delimiters = new ReferenceDelimiters(content, true);
        Set<Integer> definitionLineStarts = memberDefinitionLineStarts(content);
        Map<Integer, Integer> definitionEnds = new HashMap<>();
        Matcher definitions = MEMBER_REFERENCE_DEFINITION.matcher(content);
        while (definitions.find()) {
            if (delimiters.escaped()[definitions.start()]
                    || !memberLabelWithinLimit(content, definitions.start(1), definitions.end(1))) {
                continue;
            }
            if (definitionLineStarts.contains(definitions.start())) {
                int end = referenceDefinitionEnd(content, definitions.end());
                if (end >= 0) {
                    definitionEnds.put(definitions.start(), end);
                }
            }
            memberReferenceId(memberDefinitionDestination(content, definitions.end()), true).ifPresent(id ->
                    memberReferenceKeys(definitions.group(1)).forEach(key ->
                            memberDefinitions.computeIfAbsent(key, ignored -> new MemberTargets()).ids.add(id)));
        }
        Map<Integer, MemberTargets> singletonTargets = new HashMap<>();
        memberDefinitions.replaceAll((key, targets) -> targets.ids.size() == 1
                ? singletonTargets.computeIfAbsent(targets.ids.iterator().next(), MemberTargets::new) : targets);
        Set<Integer> validDefinitionStarts = memberDefinitionStartsOutsideParagraphs(content, definitionEnds);
        List<List<MemberLabelSpan>> readings = new ArrayList<>();
        readings.add(canonicalMemberLabels(content, delimiters, false, singletonTargets));
        readings.add(canonicalMemberLabels(content, delimiters, true, singletonTargets));
        for (int mode = 0; mode < 3; mode++) {
            List<MemberLabelSpan> spans = new ArrayList<>();
            collectMemberLabels(content, memberDefinitions, singletonTargets, validDefinitionStarts, spans,
                    matchingLabelEnds(content, mode != 0, mode == 2, true, delimiters));
            readings.add(spans);
        }
        return new MemberLabels(mergeMemberLabelReadings(readings), Set.copyOf(validDefinitionStarts));
    }

    /**
     * Resolves each candidate's sequential delimiter steps through a backward suffix table. Code
     * spans and escapes use the same transition as structural readings, including literal slashes
     * inside code. The additional no-code reading remains conservative around ambiguous blocks.
     * Destinations are cached and the 999-code-point bound precedes every source-label extraction.
     */
    private static List<MemberLabelSpan> canonicalMemberLabels(
            String content, ReferenceDelimiters delimiters, boolean codeSpans,
            Map<Integer, MemberTargets> singletonTargets) {
        int[] nextClose = new int[content.length() + 1];
        nextClose[content.length()] = -1;
        for (int index = content.length() - 1; index >= 0; index--) {
            int end = delimiters.next(index, codeSpans, false);
            nextClose[index] = end > index + 1 ? nextClose[end]
                    : content.charAt(index) == ']' ? index : nextClose[index + 1];
        }
        int[] destinations = new int[content.length()];
        Matcher destination = MEMBER_CANONICAL_DESTINATION.matcher(content);
        List<MemberLabelSpan> labels = new ArrayList<>();
        for (int index = 0; index < content.length(); index = delimiters.next(index, false, false)) {
            if (content.charAt(index) != '[') {
                continue;
            }
            int start = index + 1;
            int end = nextClose[start];
            if (end <= start || !memberLabelWithinLimit(content, start, end)) {
                continue;
            }
            if (destinations[end] == 0) {
                destinations[end] = destination.region(end, content.length()).lookingAt()
                        && TYPE_USER.equals(destination.group(1))
                        ? parseReferenceTarget(TYPE_USER, destination.group(2)).map(ReferenceTarget::id).orElse(-1)
                        : -1;
            }
            if (destinations[end] > 0) {
                int id = destinations[end];
                labels.add(new MemberLabelSpan(start, end,
                        singletonTargets.computeIfAbsent(id, MemberTargets::new)));
            }
        }
        return labels;
    }

    /** Tests the shared 999-code-point bound for raw member provenance without copying the label. */
    static boolean memberLabelWithinLimit(String label) {
        return label != null && memberLabelWithinLimit(label, 0, label.length());
    }

    /** Rejects oversized spans before allocation; the UTF-16 gate bounds code-point counting too. */
    private static boolean memberLabelWithinLimit(String content, int start, int end) {
        return end - start <= MAX_MEMBER_LABEL_CHARACTERS * 2
                && content.codePointCount(start, end) <= MAX_MEMBER_LABEL_CHARACTERS;
    }

    /** Merges the fixed number of source-ordered readings in linear time. */
    private static List<MemberLabelSpan> mergeMemberLabelReadings(List<List<MemberLabelSpan>> readings) {
        int[] positions = new int[readings.size()];
        var merged = new java.util.LinkedHashSet<MemberLabelSpan>();
        while (true) {
            MemberLabelSpan first = null;
            int selected = -1;
            for (int index = 0; index < readings.size(); index++) {
                if (positions[index] < readings.get(index).size()) {
                    MemberLabelSpan next = readings.get(index).get(positions[index]);
                    if (first == null || next.start() < first.start()
                            || next.start() == first.start() && next.end() < first.end()) {
                        first = next;
                        selected = index;
                    }
                }
            }
            if (selected < 0) {
                return List.copyOf(merged);
            }
            merged.add(first);
            positions[selected]++;
        }
    }

    /**
     * Unions independent readings without consuming brackets. Only an adjacent plain label gives
     * its following bracket an unambiguous key role; nested, opaque and block readings keep shortcuts.
     */
    private static void collectMemberLabels(
            String content, Map<String, MemberTargets> memberDefinitions,
            Map<Integer, MemberTargets> singletonTargets, Set<Integer> validDefinitionStarts,
            List<MemberLabelSpan> labels, int[] labelEnds) {
        Set<Integer> referenceKeys = new HashSet<>();
        for (int index = 0; index < content.length(); index++) {
            int end = labelEnds[index];
            if (end < 0 || !memberLabelWithinLimit(content, index + 1, end)) {
                continue;
            }
            String label = content.substring(index + 1, end);
            int next = skipWhitespace(content, end + 1);
            if (next < content.length() && content.charAt(next) == '(') {
                int destinationStart = skipWhitespace(content, next + 1);
                int destinationEnd = destinationStart;
                while (destinationEnd < content.length()
                        && !Character.isWhitespace(content.charAt(destinationEnd))
                        && "()[]".indexOf(content.charAt(destinationEnd)) < 0) {
                    destinationEnd++;
                }
                int start = index + 1;
                memberReferenceId(content.substring(destinationStart, destinationEnd), false)
                        .ifPresent(id -> labels.add(new MemberLabelSpan(start, end,
                                singletonTargets.computeIfAbsent(id, MemberTargets::new))));
            }
            if (next == end + 1 && next < content.length() && content.charAt(next) == ':'
                    && validDefinitionStarts.contains(index)) {
                continue;
            }
            if (!referenceKeys.contains(index)) {
                for (MemberTargets targets : memberDefinitionTargets(memberDefinitions, label)) {
                    labels.add(new MemberLabelSpan(index + 1, end, targets));
                }
            }
            if (next == end + 1 && next < content.length()
                    && content.charAt(next) == '[' && labelEnds[next] >= 0
                    && memberLabelWithinLimit(content, next + 1, labelEnds[next])) {
                String explicit = content.substring(next + 1, labelEnds[next]);
                String key = explicit.isBlank() ? label : explicit;
                Set<MemberTargets> targets = memberDefinitionTargets(memberDefinitions, key);
                if (!targets.isEmpty()) {
                    for (MemberTargets target : targets) {
                        labels.add(new MemberLabelSpan(index + 1, end, target));
                    }
                    if (next == end + 1 && label.chars().noneMatch(value -> "[<`\r\n".indexOf(value) >= 0)) {
                        referenceKeys.add(next);
                    }
                }
            }
        }
    }

    /** Validates source syntax only for key suppression; member discovery never depends on title content. */
    private static int referenceDefinitionEnd(String content, int offset) {
        int start = skipDefinitionSpaces(content, offset);
        if (isDefinitionLineEnd(content, start) && start < content.length()) {
            start = skipDefinitionSpaces(content, memberLineContentStart(content, afterDefinitionLineEnd(content, start)));
        }
        int end = referenceDefinitionDestinationEnd(content, start);
        if (end < 0) {
            return -1;
        }
        int title = skipDefinitionSpaces(content, end);
        if (isDefinitionLineEnd(content, title)) {
            return title;
        }
        if (title == end) {
            return -1;
        }
        char opening = content.charAt(title);
        char closing = switch (opening) {
            case '\'', '"' -> opening;
            case '(' -> ')';
            default -> 0;
        };
        if (closing == 0) {
            return -1;
        }
        for (int index = title + 1; index < content.length(); index++) {
            char value = content.charAt(index);
            if (isReferenceEscape(content, index)) {
                index++;
            } else if (value == closing) {
                int lineEnd = skipDefinitionSpaces(content, index + 1);
                return isDefinitionLineEnd(content, lineEnd) ? lineEnd : -1;
            } else if (opening == '(' && value == '(') {
                return -1;
            } else if (isDefinitionLineEnd(content, index)) {
                int next = skipDefinitionSpaces(content, memberLineContentStart(content, afterDefinitionLineEnd(content, index)));
                if (isDefinitionLineEnd(content, next)) {
                    return -1;
                }
                index = next - 1;
            }
        }
        return -1;
    }

    private static int referenceDefinitionDestinationEnd(String content, int start) {
        if (isDefinitionLineEnd(content, start)) {
            return -1;
        }
        boolean angle = content.charAt(start) == '<';
        int depth = 0;
        int index = start + (angle ? 1 : 0);
        for (; index < content.length(); index++) {
            char value = content.charAt(index);
            if (isReferenceEscape(content, index)) {
                index++;
            } else if (angle) {
                if (value == '>') {
                    return index + 1;
                }
                if (value == '<' || isDefinitionLineEnd(content, index)) {
                    return -1;
                }
            } else if (value <= ' ' || value == 127) {
                break;
            } else if (value == '(') {
                depth++;
            } else if (value == ')' && --depth < 0) {
                return -1;
            }
        }
        return angle || depth != 0 || index == start ? -1 : index;
    }

    private static boolean isReferenceEscape(String content, int index) {
        if (content.charAt(index) != '\\' || index + 1 >= content.length()) {
            return false;
        }
        char value = content.charAt(index + 1);
        return value >= '!' && value <= '/' || value >= ':' && value <= '@'
                || value >= '[' && value <= '`' || value >= '{' && value <= '~';
    }

    private static int skipDefinitionSpaces(String content, int index) {
        while (index < content.length() && (content.charAt(index) == ' ' || content.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static boolean isDefinitionLineEnd(String content, int index) {
        return index == content.length() || content.charAt(index) == '\r' || content.charAt(index) == '\n';
    }

    private static int afterDefinitionLineEnd(String content, int index) {
        if (content.charAt(index++) == '\r' && index < content.length() && content.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    private static Optional<Integer> memberReferenceId(String source, boolean complete) {
        String value = complete ? normalizeReferenceSyntax(memberDefinitionDestination(source)).strip()
                : normalizeReferenceSyntax(source).stripLeading();
        Matcher destination = (complete ? MEMBER_DEFINITION_DESTINATION : MEMBER_REFERENCE_DESTINATION).matcher(value);
        return (complete ? destination.matches() : destination.lookingAt())
                ? parseReferenceTarget(TYPE_USER, destination.group(1)).map(ReferenceTarget::id)
                : Optional.empty();
    }

    /** Extracts the original destination so authored title content cannot suppress member detection. */
    private static String memberDefinitionDestination(String source) {
        return memberDefinitionDestination(source, 0);
    }

    /** Accepts container and continuation layouts conservatively without interpreting title text. */
    private static String memberDefinitionDestination(String source, int offset) {
        int start = offset;
        while (start < source.length()) {
            start = skipWhitespace(source, start);
            if (start == source.length()) {
                return "";
            }
            if (source.charAt(start) == '>') {
                start++;
                continue;
            }
            int markerEnd = start;
            while (markerEnd < source.length() && Character.isDigit(source.charAt(markerEnd))) {
                markerEnd++;
            }
            boolean bullet = "-+*".indexOf(source.charAt(start)) >= 0;
            boolean ordered = markerEnd > start && markerEnd < source.length()
                    && ".)".indexOf(source.charAt(markerEnd)) >= 0;
            int end = bullet ? start + 1 : ordered ? markerEnd + 1 : start;
            if (end > start && end < source.length() && Character.isWhitespace(source.charAt(end))) {
                start = end;
                continue;
            }
            break;
        }
        if (start == source.length()) {
            return "";
        }
        int end = start;
        if (source.charAt(start) == '<') {
            end = start + 1;
            while (end < source.length() && source.charAt(end) != '>') {
                if (Character.isWhitespace(source.charAt(end)) || "<[".indexOf(source.charAt(end)) >= 0) {
                    return "";
                }
                end++;
            }
            if (end == source.length()) {
                return "";
            }
            end++;
        } else {
            while (end < source.length() && !Character.isWhitespace(source.charAt(end))
                    && "<[".indexOf(source.charAt(end)) < 0) {
                end++;
            }
        }
        return source.substring(start, end);
    }

    private static Set<MemberTargets> memberDefinitionTargets(Map<String, MemberTargets> definitions, String label) {
        var targets = new java.util.LinkedHashSet<MemberTargets>();
        memberReferenceKeys(label).forEach(key -> {
            MemberTargets target = definitions.get(key);
            if (target != null) {
                targets.add(target);
            }
        });
        return targets;
    }

    /** Container-stripped keys supplement literal readings rather than discarding authored text. */
    private static Set<String> memberReferenceKeys(String label) {
        Set<String> keys = new HashSet<>();
        for (String reading : List.of(label, normalizeReferenceSyntax(label))) {
            keys.add(referenceLabelKey(reading));
            keys.add(referenceLabelKey(MEMBER_CONTINUATION_PREFIX.matcher(reading).replaceAll("")));
        }
        return keys;
    }

    /** Shares targets by definition key without multiplying spans or comparing whole id sets. */
    private static final class MemberTargets {
        private final Set<Integer> ids = new java.util.LinkedHashSet<>();
        private final Set<Integer> readOnlyIds = Collections.unmodifiableSet(ids);

        private MemberTargets() {
        }

        private MemberTargets(int memberId) {
            ids.add(memberId);
        }
    }

    private record MemberLabelSpan(int start, int end, MemberTargets targets) {
    }

    private static String referenceLabelKey(String label) {
        return label.replaceAll("(?U)\\s+", " ").strip()
                .toLowerCase(java.util.Locale.ROOT).toUpperCase(java.util.Locale.ROOT);
    }

    /** Only a logical line opening makes a colon a definition delimiter rather than prose. */
    private static Set<Integer> memberDefinitionLineStarts(String content) {
        Set<Integer> starts = new HashSet<>();
        for (int index = 0; index < content.length(); index++) {
            if (index == 0 || content.charAt(index - 1) == '\n' || content.charAt(index - 1) == '\r') {
                starts.add(memberLineContentStart(content, index));
            }
        }
        return starts;
    }

    /** Definitions and code-span pairing share the same paragraph interruption decisions. */
    private static Set<Integer> memberDefinitionStartsOutsideParagraphs(
            String content, Map<Integer, Integer> definitionEnds) {
        Set<Integer> starts = new HashSet<>();
        var paragraphs = new MemberParagraphState();
        int definitionEnd = -1;
        for (int lineStart = 0; lineStart < content.length();) {
            int lineEnd = memberLineEnd(content, lineStart);
            if (lineStart > definitionEnd) {
                MemberParagraphLine line = paragraphs.readLine(content, lineStart, lineEnd);
                Integer end = definitionEnds.get(line.bodyStart());
                if (end != null && line.definitionAllowed()) {
                    starts.add(line.bodyStart());
                    definitionEnd = end;
                    paragraphs.paragraphOpen = false;
                }
            }
            lineStart = lineEnd == content.length() ? lineEnd : afterDefinitionLineEnd(content, lineEnd);
        }
        return starts;
    }

    private static int memberLineEnd(String content, int start) {
        int end = start;
        while (end < content.length() && !isDefinitionLineEnd(content, end)) {
            end++;
        }
        return end;
    }

    private record MemberParagraphLine(int bodyStart, boolean definitionAllowed, boolean blockStart) {
    }

    private record MemberListContainer(int quoteDepth, char delimiter, int contentIndent) {
    }

    private record MemberListMarker(int end, int number, char delimiter) {
    }

    /**
     * Classifies paragraph interruption using CommonMark 0.31.2
     * <a href="https://spec.commonmark.org/0.31.2/#thematic-breaks">§§4.1–4.9</a> and
     * <a href="https://spec.commonmark.org/0.31.2/#block-quotes">§§5.1–5.2</a>.
     * Blank lines, ATX headings, fences, thematic breaks, block quotes, setext underlines and
     * HTML blocks of types 1–6 end a paragraph. A new list item must be nonempty and an ordered
     * item must start at 1; existing list siblings may start another item. Indented code and
     * HTML type 7 cannot interrupt. Unrecognized and lazy container continuations retain their
     * paragraph reading, so ambiguity cannot suppress a member shortcut. Container bodies can
     * immediately open another paragraph rather than leaving subsequent lazy text outside one.
     * <a href="https://spec.commonmark.org/0.31.2/#tabs">§2.2 tab expansion</a> retains indentation
     * columns not consumed by a container's single optional whitespace column.
     */
    private static final class MemberParagraphState {
        private final Map<Integer, MemberListContainer> lists = new HashMap<>();
        private final Deque<Integer> listIndents = new ArrayDeque<>();
        private boolean paragraphOpen;
        private int activeQuoteDepth;

        private MemberParagraphLine readLine(String content, int start, int end) {
            int body = skipMemberLineSpaces(content, start, end);
            int indent = memberColumn(content, start, body);
            int quoteDepth = 0;
            int parentIndent = listContentIndent(indent, quoteDepth);
            boolean newContainer = false;
            boolean boundary = false;
            MemberLineRule rule = memberLineRule(content, start, end);
            while (body < end && indent - parentIndent <= 3) {
                if (content.charAt(body) == '>') {
                    quoteDepth++;
                    if (quoteDepth > activeQuoteDepth) {
                        paragraphOpen = false;
                        newContainer = true;
                        discardListsFrom(indent);
                    }
                    int afterQuote = body + 1;
                    if (afterQuote < end && (content.charAt(afterQuote) == ' ' || content.charAt(afterQuote) == '\t')) {
                        afterQuote++;
                    }
                    int quoteIndent = indent + afterQuote - body;
                    int afterQuoteColumn = memberColumn(content, body + 1, afterQuote, indent + 1);
                    body = skipMemberLineSpaces(content, afterQuote, end);
                    indent = memberColumn(content, afterQuote, body, afterQuoteColumn);
                    parentIndent = Math.max(quoteIndent, listContentIndent(indent, quoteDepth));
                    continue;
                }
                if (rule.interruptsAt(body, paragraphOpen)
                        || MEMBER_CODE_BLOCK_START.matcher(content).region(body, end).lookingAt()) {
                    boundary = true;
                    break;
                }
                MemberListMarker marker = memberListMarker(content, body, end);
                if (marker == null) {
                    break;
                }
                int nonspace = skipMemberLineSpaces(content, marker.end(), end);
                var sibling = lists.get(indent);
                boolean sameList = sibling != null && sibling.quoteDepth() == quoteDepth
                        && sibling.delimiter() == marker.delimiter();
                if (paragraphOpen && !sameList && (nonspace == end
                        || ".)".indexOf(marker.delimiter()) >= 0 && marker.number() != 1)) {
                    break;
                }
                int markerColumn = indent + marker.end() - body;
                int nonspaceColumn = memberColumn(content, marker.end(), nonspace, markerColumn);
                int contentIndent = nonspaceColumn - markerColumn > 4
                        ? memberColumn(content, marker.end(), marker.end() + 1, markerColumn) : nonspaceColumn;
                discardListsFrom(indent);
                lists.put(indent, new MemberListContainer(quoteDepth, marker.delimiter(), contentIndent));
                listIndents.addLast(indent);
                parentIndent = contentIndent;
                paragraphOpen = false;
                newContainer = true;
                body = nonspace;
                indent = nonspaceColumn;
            }
            boolean definitionAllowed = !paragraphOpen && indent - parentIndent <= 3;
            boolean blank = body == end;
            boolean indentedCode = !paragraphOpen && indent - parentIndent >= 4;
            if (blank || boundary || !paragraphOpen) {
                while (!listIndents.isEmpty()) {
                    var container = lists.get(listIndents.getLast());
                    if (container.quoteDepth() <= quoteDepth && (blank || container.contentIndent() <= indent)) {
                        break;
                    }
                    lists.remove(listIndents.removeLast());
                }
            }
            paragraphOpen = !blank && !boundary && !indentedCode;
            activeQuoteDepth = blank || boundary || newContainer ? quoteDepth : Math.max(activeQuoteDepth, quoteDepth);
            return new MemberParagraphLine(body, definitionAllowed, blank || boundary || newContainer || indentedCode);
        }

        private int listContentIndent(int indent, int quoteDepth) {
            if (listIndents.isEmpty()) {
                return 0;
            }
            for (int candidate = Math.min(indent, listIndents.getLast()); candidate >= 0; candidate--) {
                var container = lists.get(candidate);
                if (container != null && container.contentIndent() <= indent
                        && (paragraphOpen || container.quoteDepth() <= quoteDepth)) {
                    return container.contentIndent();
                }
            }
            return 0;
        }

        private void discardListsFrom(int indent) {
            while (!listIndents.isEmpty() && listIndents.getLast() >= indent) {
                lists.remove(listIndents.removeLast());
            }
        }
    }

    private static int skipMemberLineSpaces(String content, int start, int end) {
        int cursor = start;
        while (cursor < end && (content.charAt(cursor) == ' ' || content.charAt(cursor) == '\t')) {
            cursor++;
        }
        return cursor;
    }

    /** Parses only the marker; paragraph state decides whether it can open an item. */
    private static MemberListMarker memberListMarker(String content, int start, int end) {
        if (start == end) {
            return null;
        }
        char first = content.charAt(start);
        int cursor = start;
        int number = 0;
        if ("-+*".indexOf(first) >= 0) {
            cursor++;
        } else {
            while (cursor < end && cursor - start < 9
                    && content.charAt(cursor) >= '0' && content.charAt(cursor) <= '9') {
                number = number * 10 + content.charAt(cursor++) - '0';
            }
            if (cursor == start || cursor == end || ".)".indexOf(content.charAt(cursor)) < 0) {
                return null;
            }
            cursor++;
        }
        if (cursor < end && content.charAt(cursor) != ' ' && content.charAt(cursor) != '\t') {
            return null;
        }
        return new MemberListMarker(cursor, number, content.charAt(cursor - 1));
    }

    private static int memberColumn(String content, int start, int end) {
        return memberColumn(content, start, end, 0);
    }

    private static int memberColumn(String content, int start, int end, int initialColumn) {
        int column = initialColumn;
        for (int index = start; index < end; index++) {
            column += content.charAt(index) == '\t' ? 4 - column % 4 : 1;
        }
        return column;
    }

    /** Prepares suffix coverage once so nested list markers cannot rescan the same thematic run. */
    private static MemberLineRule memberLineRule(String content, int start, int end) {
        int last = end - 1;
        while (last >= start && (content.charAt(last) == ' ' || content.charAt(last) == '\t')) {
            last--;
        }
        if (last < start || "=-*_".indexOf(content.charAt(last)) < 0) {
            return new MemberLineRule(end, -1, end, false);
        }
        char marker = content.charAt(last);
        int runStart = last;
        while (runStart > start && content.charAt(runStart - 1) == marker) {
            runStart--;
        }
        int cursor = last;
        int count = 0;
        int thirdLast = -1;
        while (cursor >= start) {
            char value = content.charAt(cursor);
            if (value == marker) {
                if (++count == 3) {
                    thirdLast = cursor;
                }
            } else if (value != ' ' && value != '\t') {
                break;
            }
            cursor--;
        }
        return new MemberLineRule(cursor + 1, marker == '=' ? -1 : thirdLast, runStart, marker == '=' || marker == '-');
    }

    private record MemberLineRule(int suffixStart, int thematicEnd, int setextStart, boolean setext) {
        private boolean interruptsAt(int start, boolean paragraphOpen) {
            return start >= suffixStart && (start <= thematicEnd || paragraphOpen && setext && start >= setextStart);
        }
    }

    private static int memberLineContentStart(String content, int lineStart) {
        Matcher prefix = MEMBER_LINE_CONTENT.matcher(content).region(lineStart, content.length());
        return prefix.lookingAt() ? prefix.end() : lineStart;
    }

    private static int[] matchingLabelEnds(String content) {
        return matchingLabelEnds(content, true);
    }

    private static int[] matchingLabelEnds(String content, boolean skipCodeSpans) {
        return matchingLabelEnds(content, skipCodeSpans, false);
    }

    private static int[] matchingLabelEnds(String content, boolean skipCodeSpans, boolean skipOpaqueInlines) {
        return matchingLabelEnds(content, skipCodeSpans, skipOpaqueInlines, false);
    }

    private static int[] matchingLabelEnds(
            String content, boolean skipCodeSpans, boolean skipOpaqueInlines, boolean boundCodeSpans) {
        return matchingLabelEnds(content, skipCodeSpans, skipOpaqueInlines, boundCodeSpans,
                new ReferenceDelimiters(content, boundCodeSpans));
    }

    private static int[] matchingLabelEnds(
            String content, boolean skipCodeSpans, boolean skipOpaqueInlines, boolean boundCodeSpans,
            ReferenceDelimiters delimiters) {
        int[] labelEnds = new int[content.length()];
        Arrays.fill(labelEnds, -1);
        Matcher opaque = MEMBER_OPAQUE_INLINE.matcher(content);
        Map<String, Integer> terminators = new HashMap<>();
        Deque<Integer> labelStarts = new ArrayDeque<>();
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            int next = delimiters.next(index, skipCodeSpans, boundCodeSpans);
            if (next > index + 1) {
                index = next - 1;
                continue;
            }
            if (skipOpaqueInlines && value == '<') {
                int opaqueEnd = memberOpaqueInlineEnd(content, index, opaque, terminators);
                if (opaqueEnd > index) {
                    index = opaqueEnd - 1;
                    continue;
                }
            }
            if (value == '[') {
                labelStarts.push(index);
            } else if (value == ']' && !labelStarts.isEmpty()) {
                labelEnds[labelStarts.pop()] = index;
            }
        }
        return labelEnds;
    }

    /** Recognizes opaque inline grammar without letting a missing terminator hide later labels. */
    private static int memberOpaqueInlineEnd(
            String content, int start, Matcher opaque, Map<String, Integer> terminators) {
        if (content.startsWith("<!-->", start)) {
            return start + 5;
        }
        if (content.startsWith("<!--->", start)) {
            return start + 6;
        }
        String closing = null;
        int openingLength = 0;
        if (content.startsWith("<!--", start)) {
            closing = "-->";
            openingLength = 4;
        } else if (content.startsWith("<?", start)) {
            closing = "?>";
            openingLength = 2;
        } else if (content.startsWith("<![CDATA[", start)) {
            closing = "]]>";
            openingLength = 9;
        } else if (content.startsWith("<!", start) && start + 2 < content.length()
                && (content.charAt(start + 2) >= 'A' && content.charAt(start + 2) <= 'Z'
                    || content.charAt(start + 2) >= 'a' && content.charAt(start + 2) <= 'z')) {
            closing = ">";
            openingLength = 2;
        }
        if (closing != null) {
            int from = start + openingLength;
            Integer end = terminators.get(closing);
            if (end == null || end >= 0 && end < from) {
                end = content.indexOf(closing, from);
                terminators.put(closing, end);
            }
            return end < 0 ? -1 : end + closing.length();
        }
        return opaque.region(start, content.length()).lookingAt() ? opaque.end() : -1;
    }

    /**
     * CommonMark 0.31.2 <a href="https://spec.commonmark.org/0.31.2/#backslash-escapes">§2.4</a>
     * and <a href="https://spec.commonmark.org/0.31.2/#code-spans">§6.1</a> sequential units.
     * A single backward table finds the next raw, maximal backtick run of each exact length.
     * Every suffix can be an opener after an escaped first tick, but only whole raw runs can
     * close code. The forward transition consumes escapes only outside code and jumps over the
     * entire literal span, so its internal backslashes never affect closing delimiters. Unmatched
     * runs stay literal. Canonical local entries and structural scans reuse these same units;
     * the no-code reading relaxes only code opacity. No opener rescans for a matching closer.
     */
    private static final class ReferenceDelimiters {
        private final String content;
        private final int[] runEnds;
        private final int[] codeEnds;
        private final int[] blockEnds;
        private final boolean[] escaped;

        private ReferenceDelimiters(String content, boolean boundCodeSpans) {
            this.content = content;
            runEnds = new int[content.length()];
            codeEnds = new int[content.length()];
            Arrays.fill(codeEnds, -1);
            Map<Integer, Integer> nextRunByLength = new HashMap<>();
            for (int end = content.length(); end > 0;) {
                if (content.charAt(end - 1) != '`') {
                    end--;
                    continue;
                }
                int start = end - 1;
                while (start > 0 && content.charAt(start - 1) == '`') {
                    start--;
                }
                for (int index = start; index < end; index++) {
                    int length = end - index;
                    int closing = nextRunByLength.getOrDefault(length, -1);
                    runEnds[index] = end;
                    codeEnds[index] = closing < 0 ? -1 : closing + length;
                }
                nextRunByLength.put(end - start, start);
                end = start;
            }
            blockEnds = new int[content.length()];
            boolean[] blockStarts = boundCodeSpans ? codeSpanBlockStarts(content) : new boolean[content.length()];
            int boundary = content.length();
            for (int index = content.length() - 1; index >= 0; index--) {
                blockEnds[index] = boundary;
                if (blockStarts[index]) {
                    boundary = index;
                }
            }
            escaped = new boolean[content.length()];
            for (int index = 0; index < content.length();) {
                int next = next(index, false, false);
                if (next > index + 1) {
                    escaped[index + 1] = true;
                }
                index = next;
            }
        }

        private boolean[] escaped() {
            return escaped;
        }

        /** Returns the next outside-code position; matched code content is consumed literally. */
        private int next(int index, boolean codeSpans, boolean boundCodeSpans) {
            if (isReferenceEscape(content, index)) {
                return index + 2;
            }
            if (codeSpans && content.charAt(index) == '`') {
                int end = codeEnds[index];
                return end >= 0 && (!boundCodeSpans || end <= blockEnds[index]) ? end : runEnds[index];
            }
            return index + 1;
        }
    }

    /** Backticks use the same paragraph and container boundaries as definition suppression. */
    private static boolean[] codeSpanBlockStarts(String content) {
        boolean[] starts = new boolean[content.length()];
        var paragraphs = new MemberParagraphState();
        for (int lineStart = 0; lineStart < content.length();) {
            int lineEnd = memberLineEnd(content, lineStart);
            starts[lineStart] = paragraphs.readLine(content, lineStart, lineEnd).blockStart();
            lineStart = lineEnd == content.length() ? lineEnd : afterDefinitionLineEnd(content, lineEnd);
        }
        return starts;
    }

    /** Scans pure setext underlines and same-marker thematic breaks once, without regex backtracking. */
    static boolean memberRuleLine(CharSequence content, int start, int end) {
        if (start == end) {
            return false;
        }
        char marker = content.charAt(start);
        if ("=-*_".indexOf(marker) < 0) {
            return false;
        }
        int count = 0;
        boolean separated = false;
        boolean setext = marker == '=' || marker == '-';
        for (int index = start; index < end; index++) {
            char value = content.charAt(index);
            if (value == marker) {
                count++;
                if (separated) {
                    setext = false;
                }
            } else if (value == ' ' || value == '\t') {
                separated = true;
            } else {
                return false;
            }
        }
        return setext || marker != '=' && count >= 3;
    }

    private static boolean isEscaped(String content, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && content.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int skipWhitespace(String content, int index) {
        int cursor = index;
        while (cursor < content.length() && Character.isWhitespace(content.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    static Optional<ReferenceTarget> parseReferenceTarget(String type, String value) {
        String canonicalType = canonicalReferenceType(type);
        if (canonicalType == null) {
            return Optional.empty();
        }
        if (value.isEmpty() || value.chars().anyMatch(character -> !Character.isDigit(character))) {
            return Optional.empty();
        }
        try {
            int id = Integer.parseInt(value);
            return id > 0 ? Optional.of(new ReferenceTarget(canonicalType, id)) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    static String canonicalReferenceType(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case TYPE_USER -> TYPE_USER;
            case TYPE_PERSON -> TYPE_PERSON;
            case TYPE_DEAL -> TYPE_DEAL;
            case TYPE_COMPANY -> TYPE_COMPANY;
            case TYPE_NOTE -> TYPE_NOTE;
            case TYPE_FILE -> TYPE_FILE;
            case TYPE_TASK -> TYPE_TASK;
            case TYPE_ACTIVITY -> TYPE_ACTIVITY;
            default -> null;
        };
    }

}
