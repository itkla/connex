package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.ArchivePosture;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.Figure;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.FigureDefinition;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.OwnerBasis;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.PeriodBasis;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.RestrictionPosture;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.SharingPosture;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.StatementEvidence;
import ooo.klae.connex.backend.tenant.FigureReconciliationRegistry.ValueSource;

/**
 * Re-derives every revenue-figure reconciliation declaration from resolved MyBatis mapper SQL.
 *
 * <p>Each statement is loaded directly from its classpath mapper XML. Reusable
 * {@code <include>} fragments are recursively resolved before inspection, while XML comments and
 * every other non-text node are discarded so explanatory prose can never satisfy an evidence check.
 */
class FigureReconciliationArchTest {

    private static final int MIN_RATIONALE_LENGTH = 40;
    private static final String SPACE = " ";
    private static final String INCLUDE_MARK = String.valueOf((char) 1);
    private static final Pattern INCLUDE_REF = Pattern.compile(
        Pattern.quote(INCLUDE_MARK) + "([^" + Pattern.quote(INCLUDE_MARK) + "]*)"
            + Pattern.quote(INCLUDE_MARK));
    private static final Pattern DOCTYPE = Pattern.compile("(?s)<!DOCTYPE.*?>");
    private static final Pattern PERSON_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?person[`\"]?(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSON_RESTRICTION = Pattern.compile(
        "(?:suspended_at|provision_ceased_at)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_TABLE = Pattern.compile(
        "\\b[A-Za-z][A-Za-z0-9]*_share\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY_SHARE_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?company_share[`\"]?(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIPELINE_SHARE_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?pipeline_share[`\"]?(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSON_SHARE_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?person_share[`\"]?(?:\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_PREDICATE = Pattern.compile(
        "\\bd\\.owner_id\\s*(?:=|IN\\b|IS\\s+NULL)", Pattern.CASE_INSENSITIVE);
    private static final List<String> PERIOD_PARAMETERS = List.of(
        "#{days}",
        "#{startUtc}",
        "#{endUtc}",
        "#{query.startUtc}",
        "#{query.endUtc}",
        "#{query.startDate}",
        "#{query.endDateExclusive}");
    private static final Set<Figure> EXPECTED_FIGURES = Set.of(
        Figure.DEAL_BROWSER_METRICS,
        Figure.DEAL_BROWSER_METRICS_UNSCOPED,
        Figure.PIPELINE_BOARD,
        Figure.HOME_PIPELINE_CHART,
        Figure.REPORT_WON_REVENUE,
        Figure.REPORT_OPEN_PIPELINE_VALUE,
        Figure.REPORT_FORECAST_WEIGHTED);

    private final Map<String, MapperXml> mapperCache = new HashMap<>();

    /** The registry is closed, internally unique, sufficiently reasoned, and points to real SQL. */
    @Test
    void registryContainsExactlyTheReviewedFiguresAndRealEvidence() throws Exception {
        Map<Figure, FigureDefinition> definitions = FigureReconciliationRegistry.definitions();
        List<String> violations = new ArrayList<>();

        if (!definitions.keySet().equals(EXPECTED_FIGURES)) {
            violations.add("figure=<registry> statement=<registry> declaredPosture=EXACT_FIGURE_SET"
                + " expectedEvidence=" + EXPECTED_FIGURES
                + " actualSql=" + definitions.keySet());
        }

        Set<Figure> declaredFigures = new HashSet<>();
        for (Map.Entry<Figure, FigureDefinition> entry : definitions.entrySet()) {
            FigureDefinition definition = entry.getValue();
            StatementEvidence firstEvidence = definition.evidence().getFirst();
            Statement firstStatement = statement(firstEvidence);
            String firstSql = firstStatement == null ? "<missing>" : firstStatement.sql();

            if (entry.getKey() != definition.figure()) {
                violations.add(failure(definition, firstEvidence, "REGISTRY_KEY",
                    "map key and definition figure must match", firstSql));
            }
            if (!declaredFigures.add(definition.figure())) {
                violations.add(failure(definition, firstEvidence, "UNIQUE_FIGURE",
                    "each figure must be declared exactly once", firstSql));
            }
            if (definition.rationale().length() < MIN_RATIONALE_LENGTH) {
                violations.add(failure(definition, firstEvidence, "RATIONALE_LENGTH",
                    "rationale length must be at least " + MIN_RATIONALE_LENGTH, firstSql));
            }

            Set<String> evidenceKeys = new HashSet<>();
            for (StatementEvidence evidence : definition.evidence()) {
                if (!evidenceKeys.add(evidence.key())) {
                    violations.add(failure(definition, evidence, "UNIQUE_EVIDENCE",
                        "a figure must not repeat a mapper statement", firstSql));
                    continue;
                }
                MapperXml mapper = mapper(evidence.mapperNamespace());
                if (mapper == null) {
                    violations.add(failure(definition, evidence, "STATEMENT_EXISTS",
                        "classpath mapper XML must exist", "<missing mapper XML>"));
                    continue;
                }
                if (!mapper.namespace().equals(evidence.mapperNamespace())) {
                    violations.add(failure(definition, evidence, "MAPPER_NAMESPACE",
                        "mapper namespace " + evidence.mapperNamespace(), mapper.sql()));
                }
                long matches = mapper.statements().stream()
                    .filter(candidate -> candidate.statementId().equals(evidence.statementId()))
                    .count();
                if (matches != 1) {
                    violations.add(failure(definition, evidence, "STATEMENT_EXISTS_ONCE",
                        "exactly one statement with the declared id", mapper.sql()));
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Figure reconciliation registry completeness failures: " + violations);
    }

    /** Every posture stays earned by the comment-free, include-resolved mapper SQL. */
    @Test
    void everyFigureDeclarationMatchesResolvedMapperSql() throws Exception {
        List<String> violations = new ArrayList<>();
        for (FigureDefinition definition : FigureReconciliationRegistry.definitions().values()) {
            for (StatementEvidence evidence : definition.evidence()) {
                Statement statement = statement(evidence);
                if (statement == null) {
                    violations.add(failure(definition, evidence, "STATEMENT_EXISTS",
                        "resolved mapper statement", "<missing>"));
                    continue;
                }
                String sql = normalize(statement.sql());
                String measureSql = measureSql(definition, statement);
                verifyValueSource(definition, evidence, sql, measureSql, violations);
                verifyNoLineItemSource(definition, evidence, sql, violations);
                verifyArchivePosture(definition, evidence, sql, violations);
                verifyRestrictionPosture(definition, evidence, statement, sql, violations);
                verifySharingPosture(definition, evidence, sql, violations);
                verifyPeriodBasis(
                    definition, evidence, statement, sql, measureSql, violations);
                verifyOwnerBasis(definition, evidence, sql, violations);
            }
        }
        assertTrue(violations.isEmpty(),
            "Revenue figure declarations drifted from mapper SQL: " + violations);
    }

    private void verifyValueSource(
            FigureDefinition definition,
            StatementEvidence evidence,
            String sql,
            String measureSql,
            List<String> violations) {
        ValueSource posture = definition.valueSource();
        switch (posture) {
            case CANONICAL_DEAL_VALUE -> {
                requireContainsInEvidence(definition, evidence, posture,
                    "d.value", measureSql, sql, violations);
                requireAbsent(definition, evidence, posture,
                    "GREATEST(d.value, 0)", sql, violations);
            }
            case ACTUAL_DEAL_VALUE -> {
                requireContainsInEvidence(definition, evidence, posture,
                    "d.actual_value", measureSql, sql, violations);
                requireAbsent(definition, evidence, posture,
                    "GREATEST(d.value, 0)", sql, violations);
            }
            case CANONICAL_AND_ACTUAL_DEAL_VALUES -> {
                requireContainsInEvidence(definition, evidence, posture,
                    "d.value", measureSql, sql, violations);
                requireContainsInEvidence(definition, evidence, posture,
                    "d.actual_value", measureSql, sql, violations);
                requireAbsent(definition, evidence, posture,
                    "GREATEST(d.value, 0)", sql, violations);
                if (definition.figure() == Figure.DEAL_BROWSER_METRICS
                        || definition.figure() == Figure.DEAL_BROWSER_METRICS_UNSCOPED) {
                    requireContains(definition, evidence, posture,
                        "CASE WHEN d.won IS NULL THEN d.value END", sql, violations);
                    requireContains(definition, evidence, posture,
                        "CASE WHEN d.won IS NOT NULL THEN d.actual_value END", sql, violations);
                }
            }
            case CLAMPED_CANONICAL_VALUE_TIMES_WIN_RATE -> {
                requireContainsInEvidence(definition, evidence, posture,
                    "GREATEST(d.value, 0)", measureSql, sql, violations);
                requireContainsInEvidence(definition, evidence, posture,
                    "GREATEST(d.value, 0) * COALESCE(", measureSql, sql, violations);
                requireContainsInEvidence(definition, evidence, posture,
                    ".win_rate", measureSql, sql, violations);
            }
        }
    }

    private void verifyNoLineItemSource(
            FigureDefinition definition,
            StatementEvidence evidence,
            String sql,
            List<String> violations) {
        requireAbsent(definition, evidence, "REVENUE_SOURCE",
            "deal_line_item", sql, violations);
    }

    private void verifyArchivePosture(
            FigureDefinition definition,
            StatementEvidence evidence,
            String sql,
            List<String> violations) {
        ArchivePosture posture = definition.archivePosture();
        switch (posture) {
            case ARCHIVED_COMPANY_AS_UNASSIGNED -> {
                requireContains(definition, evidence, posture,
                    "LEFT JOIN company c", sql, violations);
                requireContains(definition, evidence, posture,
                    "c.archived_at IS NULL", sql, violations);
                requireContains(definition, evidence, posture,
                    "c.id IS NULL", sql, violations);
            }
            case RETAIN_ARCHIVED_COMPANY_LABEL -> {
                requireContains(definition, evidence, posture,
                    "LEFT JOIN company c", sql, violations);
                requireContains(definition, evidence, posture,
                    "c.workspace_id = #{query.workspaceId}", sql, violations);
                requireContains(definition, evidence, posture, "c.name", sql, violations);
                requireAbsent(definition, evidence, posture,
                    "c.archived_at IS NULL", sql, violations);
            }
            case NO_ARCHIVE_PREDICATE -> requireAbsent(definition, evidence, posture,
                "c.archived_at IS NULL", sql, violations);
        }
    }

    private void verifyRestrictionPosture(
            FigureDefinition definition,
            StatementEvidence evidence,
            Statement statement,
            String sql,
            List<String> violations) {
        RestrictionPosture posture = definition.restrictionPosture();
        switch (posture) {
            case CONTACT_FILTER_EXCLUDES_UNAVAILABLE_PERSONS -> {
                String contactFilterSql = statement.conditionals().stream()
                    .filter(conditional -> conditional.condition().contains("personIds != null"))
                    .map(ConditionalSql::sql)
                    .findFirst()
                    .orElse("");
                requireContains(definition, evidence, posture,
                    "JOIN person filtered_person", contactFilterSql, violations);
                requireContains(definition, evidence, posture,
                    "filtered_person.archived_at IS NULL", contactFilterSql, violations);
                requireContains(definition, evidence, posture,
                    "filtered_person.suspended_at IS NULL", contactFilterSql, violations);
                requireContains(definition, evidence, posture,
                    "filtered_person.provision_ceased_at IS NULL", contactFilterSql, violations);
            }
            case NO_PERSON_RESTRICTION_PREDICATE -> {
                if (PERSON_READ.matcher(sql).find()) {
                    violations.add(failure(definition, evidence, posture,
                        "no FROM/JOIN person evidence", sql));
                }
                if (PERSON_RESTRICTION.matcher(sql).find()) {
                    violations.add(failure(definition, evidence, posture,
                        "no suspended_at or provision_ceased_at predicate", sql));
                }
            }
        }
    }

    private void verifySharingPosture(
            FigureDefinition definition,
            StatementEvidence evidence,
            String sql,
            List<String> violations) {
        SharingPosture posture = definition.sharingPosture();
        switch (posture) {
            case COMPANY_PERSON_AND_PIPELINE_SHARE_TRAVERSAL -> {
                requirePattern(definition, evidence, posture, COMPANY_SHARE_READ,
                    "a FROM/JOIN company_share table reference", sql, violations);
                requirePattern(definition, evidence, posture, PERSON_SHARE_READ,
                    "a FROM/JOIN person_share table reference", sql, violations);
                requirePattern(definition, evidence, posture, PIPELINE_SHARE_READ,
                    "a FROM/JOIN pipeline_share table reference", sql, violations);
            }
            case NO_SHARE_TRAVERSAL -> {
                if (SHARE_TABLE.matcher(sql).find()) {
                    violations.add(failure(definition, evidence, posture,
                        "no _share table reference", sql));
                }
            }
        }
    }

    private void verifyPeriodBasis(
            FigureDefinition definition,
            StatementEvidence evidence,
            Statement statement,
            String sql,
            String measureSql,
            List<String> violations) {
        PeriodBasis posture = definition.periodBasis();
        switch (posture) {
            case UNBOUNDED -> {
                for (String parameter : PERIOD_PARAMETERS) {
                    requireAbsent(definition, evidence, posture, parameter, sql, violations);
                }
            }
            case WON_CLOSED_AT_BOUNDED_OPEN_UNBOUNDED -> {
                requireContains(definition, evidence, posture,
                    "CASE WHEN d.won = TRUE", sql, violations);
                requireContains(definition, evidence, posture,
                    "d.closed_at", sql, violations);
                requireContains(definition, evidence, posture,
                    "THEN d.actual_value END", sql, violations);
                requireContains(definition, evidence, posture,
                    "CASE WHEN d.won IS NULL THEN d.value END", sql, violations);
                if ("dealPipelineValue".equals(evidence.statementId())) {
                    requireContains(definition, evidence, posture,
                        "DATE_SUB(NOW(), INTERVAL #{days} DAY)", sql, violations);
                    requireContains(definition, evidence, posture,
                        "d.closed_at <= NOW()", sql, violations);
                } else {
                    requireContains(definition, evidence, posture,
                        "d.closed_at >= #{startUtc}", sql, violations);
                    requireContains(definition, evidence, posture,
                        "d.closed_at < #{endUtc}", sql, violations);
                }
            }
            case CLOSED_AT_UTC_HALF_OPEN -> {
                requireContainsInEvidence(definition, evidence, posture,
                    "d.closed_at", measureSql, sql, violations);
                requireContains(definition, evidence, posture,
                    "#{query.startUtc}", sql, violations);
                requireContains(definition, evidence, posture,
                    "#{query.endUtc}", sql, violations);
                requireContainsInEvidence(definition, evidence, posture,
                    "d.won = TRUE", measureSql, sql, violations);
            }
            case EXPECTED_CLOSE_DATE_HALF_OPEN -> {
                requireExpectedCloseWindow(
                    definition, evidence, posture, measureSql, sql, violations);
                requireContainsInEvidence(definition, evidence, posture,
                    "d.won IS NULL", measureSql, sql, violations);
            }
            case EXPECTED_CLOSE_DATE_HALF_OPEN_WITH_ALL_HISTORY -> {
                requireExpectedCloseWindow(
                    definition, evidence, posture, sql, sql, violations);
                requireContains(definition, evidence, posture,
                    "d.won IS NULL", sql, violations);
                requireContains(definition, evidence, posture,
                    "legacy_stage_history", sql, violations);
                requireContains(definition, evidence, posture,
                    "reached_stage_history", sql, violations);
                requireContains(definition, evidence, posture,
                    "workspace_history", sql, violations);
                requireOccurrenceCount(definition, evidence, posture,
                    "#{query.startDate}", 1, sql, violations);
                requireOccurrenceCount(definition, evidence, posture,
                    "#{query.endDateExclusive}", 1, sql, violations);
                verifyForecastHistoryIsUnbounded(
                    definition, evidence, posture, sql, violations);
                for (ConditionalSql conditional : statement.conditionals()) {
                    if (conditional.sql().contains("#{query.startDate}")
                            || conditional.sql().contains("#{query.endDateExclusive}")) {
                        violations.add(failure(definition, evidence, posture,
                            "unconditional expected-close cohort bounds", sql));
                    }
                }
            }
        }
    }

    private void requireExpectedCloseWindow(
            FigureDefinition definition,
            StatementEvidence evidence,
            PeriodBasis posture,
            String evidenceSql,
            String actualSql,
            List<String> violations) {
        requireContainsInEvidence(definition, evidence, posture,
            "d.expected_close_date >= #{query.startDate}", evidenceSql, actualSql, violations);
        requireContainsInEvidence(definition, evidence, posture,
            "d.expected_close_date < #{query.endDateExclusive}", evidenceSql, actualSql, violations);
    }

    private void verifyForecastHistoryIsUnbounded(
            FigureDefinition definition,
            StatementEvidence evidence,
            PeriodBasis posture,
            String sql,
            List<String> violations) {
        String historyStartToken = "LEFT JOIN ( SELECT closed_stage_deal.pipeline_id";
        String cohortStartToken = "WHERE d.workspace_id = #{query.workspaceId} AND d.won IS NULL";
        int historyStart = sql.indexOf(historyStartToken);
        int cohortStart = sql.lastIndexOf(cohortStartToken);
        if (historyStart < 0 || cohortStart <= historyStart) {
            violations.add(failure(definition, evidence, posture,
                "separable legacy-stage, reached-stage, and workspace-history SQL", sql));
            return;
        }
        String historySql = sql.substring(historyStart, cohortStart);
        for (String temporalEvidence : List.of(
                "closed_at", "created_at", "achieved_at", "expected_close_date", "DATE_SUB(", "NOW()",
                "CURRENT_DATE", "#{query.startDate}", "#{query.endDateExclusive}",
                "#{query.startUtc}", "#{query.endUtc}")) {
            requireAbsentInEvidence(definition, evidence, posture,
                temporalEvidence, historySql, sql, violations);
        }
    }

    private void verifyOwnerBasis(
            FigureDefinition definition,
            StatementEvidence evidence,
            String sql,
            List<String> violations) {
        OwnerBasis posture = definition.ownerBasis();
        switch (posture) {
            case MEMBER_SCOPE_ON_CURRENT_OWNER -> {
                requireContains(definition, evidence, posture,
                    "#{memberScope.userId}", sql, violations);
                if (!OWNER_PREDICATE.matcher(sql).find()) {
                    violations.add(failure(definition, evidence, posture,
                        "a d.owner_id member-scope predicate", sql));
                }
            }
            case NO_MEMBER_SCOPE -> requireAbsent(definition, evidence, posture,
                "#{memberScope.", sql, violations);
            case CURRENT_OWNER_GROUPING_AND_OPTIONAL_FILTER -> {
                requireContains(definition, evidence, posture,
                    "CAST(d.owner_id AS CHAR)", sql, violations);
                requireContains(definition, evidence, posture,
                    "d.owner_id IN", sql, violations);
                requireAbsent(definition, evidence, posture,
                    "#{memberScope.", sql, violations);
            }
        }
    }

    private void requireContains(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String expected,
            String sql,
            List<String> violations) {
        if (!sql.contains(expected)) {
            violations.add(failure(definition, evidence, posture,
                "SQL containing `" + expected + "`", sql));
        }
    }

    private void requireAbsent(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String forbidden,
            String sql,
            List<String> violations) {
        if (sql.contains(forbidden)) {
            violations.add(failure(definition, evidence, posture,
                "SQL without `" + forbidden + "`", sql));
        }
    }

    private void requireContainsInEvidence(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String expected,
            String evidenceSql,
            String actualSql,
            List<String> violations) {
        if (!evidenceSql.contains(expected)) {
            violations.add(failure(definition, evidence, posture,
                "selected SQL containing `" + expected + "`", actualSql));
        }
    }

    private void requireAbsentInEvidence(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String forbidden,
            String evidenceSql,
            String actualSql,
            List<String> violations) {
        if (evidenceSql.contains(forbidden)) {
            violations.add(failure(definition, evidence, posture,
                "selected SQL without `" + forbidden + "`", actualSql));
        }
    }

    private void requirePattern(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            Pattern expectedPattern,
            String expected,
            String sql,
            List<String> violations) {
        if (!expectedPattern.matcher(sql).find()) {
            violations.add(failure(definition, evidence, posture, expected, sql));
        }
    }

    private void requireOccurrenceCount(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String token,
            int expectedCount,
            String sql,
            List<String> violations) {
        int count = 0;
        int offset = 0;
        while ((offset = sql.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        if (count != expectedCount) {
            violations.add(failure(definition, evidence, posture,
                "exactly " + expectedCount + " occurrence(s) of `" + token + "`", sql));
        }
    }

    private String failure(
            FigureDefinition definition,
            StatementEvidence evidence,
            Object posture,
            String expected,
            String sql) {
        return "figure=" + definition.figure()
            + " statement=" + evidence.key()
            + " declaredPosture=" + posture
            + " expectedEvidence=" + expected
            + " actualSql=" + normalize(sql);
    }

    private Statement statement(StatementEvidence evidence) throws Exception {
        MapperXml mapper = mapper(evidence.mapperNamespace());
        if (mapper == null) {
            return null;
        }
        return mapper.statements().stream()
            .filter(candidate -> candidate.statementId().equals(evidence.statementId()))
            .findFirst()
            .orElse(null);
    }

    private String measureSql(FigureDefinition definition, Statement statement) {
        String measure = switch (definition.figure()) {
            case REPORT_WON_REVENUE -> "won_revenue";
            case REPORT_OPEN_PIPELINE_VALUE -> "open_pipeline_value";
            case REPORT_FORECAST_WEIGHTED -> null;
            default -> null;
        };
        if (definition.figure() == Figure.REPORT_FORECAST_WEIGHTED) {
            return weightedForecastSql(statement.sql());
        }
        if (measure == null) {
            return normalize(statement.sql());
        }
        StringBuilder selected = new StringBuilder();
        for (ConditionalSql conditional : statement.conditionals()) {
            if (selectsMeasure(conditional.condition(), measure)) {
                selected.append(conditional.sql()).append(SPACE);
            }
        }
        return normalize(selected.toString());
    }

    private boolean selectsMeasure(String condition, String measure) {
        Pattern equality = Pattern.compile(
            "\\bquery\\.measure\\s*==\\s*'" + Pattern.quote(measure) + "'");
        return equality.matcher(condition).find();
    }

    private String weightedForecastSql(String sql) {
        String normalized = normalize(sql);
        String startToken = "AS best_value,";
        String endToken = "AS weighted_value";
        int start = normalized.indexOf(startToken);
        int end = normalized.indexOf(endToken, Math.max(start, 0));
        if (start < 0 || end <= start) {
            return "";
        }
        return normalized.substring(start + startToken.length(), end);
    }

    private MapperXml mapper(String namespace) throws Exception {
        if (mapperCache.containsKey(namespace)) {
            return mapperCache.get(namespace);
        }
        String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
        String xml = mapperXml(mapper);
        MapperXml parsed = xml == null ? null : parse(xml);
        mapperCache.put(namespace, parsed);
        return parsed;
    }

    private String mapperXml(String mapper) throws IOException {
        String resource = "mappers/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits one mapper into reusable fragments and statements, then resolves every include before
     * returning the SQL. Only text and CDATA nodes contribute evidence, so comments are dropped.
     */
    private MapperXml parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
            new InputSource(new ByteArrayInputStream(new byte[0])));
        String withoutDoctype = DOCTYPE.matcher(xml).replaceFirst("");
        Document document = builder.parse(
            new InputSource(new ByteArrayInputStream(withoutDoctype.getBytes(StandardCharsets.UTF_8))));

        Map<String, String> fragments = new LinkedHashMap<>();
        Map<String, Element> fragmentElements = new LinkedHashMap<>();
        List<Element> statementElements = new ArrayList<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            if ("sql".equals(element.getTagName())) {
                fragments.put(element.getAttribute("id"), collectSql(element));
                fragmentElements.put(element.getAttribute("id"), element);
            } else if (Set.of("select", "insert", "update", "delete")
                    .contains(element.getTagName())) {
                statementElements.add(element);
            }
        }

        List<Statement> statements = new ArrayList<>();
        StringBuilder whole = new StringBuilder();
        for (Element element : statementElements) {
            String sql = resolve(collectSql(element), fragments, 0);
            List<ConditionalSql> conditionals = new ArrayList<>();
            collectConditionals(element, fragmentElements, fragments, conditionals, 0);
            statements.add(new Statement(
                element.getAttribute("id"), sql, List.copyOf(conditionals)));
            whole.append(sql).append('\n');
        }
        return new MapperXml(
            document.getDocumentElement().getAttribute("namespace"), statements, whole.toString());
    }

    private String collectSql(Element element) {
        StringBuilder sql = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                sql.append(node.getNodeValue());
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if ("include".equals(child.getTagName())) {
                    sql.append(INCLUDE_MARK).append(child.getAttribute("refid")).append(INCLUDE_MARK);
                } else {
                    sql.append(' ').append(collectSql(child)).append(' ');
                }
            }
        }
        return sql.toString();
    }

    private String resolve(String sql, Map<String, String> fragments, int depth) {
        if (depth > 16 || !sql.contains(INCLUDE_MARK)) {
            return sql;
        }
        Matcher matcher = INCLUDE_REF.matcher(sql);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String body = fragments.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(
                resolved, Matcher.quoteReplacement(SPACE + resolve(body, fragments, depth + 1) + SPACE));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private void collectConditionals(
            Element element,
            Map<String, Element> fragmentElements,
            Map<String, String> fragments,
            List<ConditionalSql> conditionals,
            int depth) {
        if (depth > 16) {
            return;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if ("include".equals(child.getTagName())) {
                Element fragment = fragmentElements.get(child.getAttribute("refid"));
                if (fragment != null) {
                    collectConditionals(
                        fragment, fragmentElements, fragments, conditionals, depth + 1);
                }
                continue;
            }
            if (child.hasAttribute("test") || "otherwise".equals(child.getTagName())) {
                conditionals.add(new ConditionalSql(
                    child.hasAttribute("test") ? child.getAttribute("test") : "<otherwise>",
                    normalize(resolve(collectSql(child), fragments, 0))));
            }
            collectConditionals(child, fragmentElements, fragments, conditionals, depth);
        }
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** One mapper file with its declared namespace, resolved statements, and normalized evidence. */
    private record MapperXml(String namespace, List<Statement> statements, String sql) {
    }

    /** One MyBatis statement with all reusable fragments resolved. */
    private record Statement(String statementId, String sql, List<ConditionalSql> conditionals) {
    }

    /** One dynamic SQL branch selected by its executable MyBatis test expression. */
    private record ConditionalSql(String condition, String sql) {
    }
}
