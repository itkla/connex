package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.ArchiveDisposition;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.ArchiveStrategy;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.ExemptionReason;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.StatementExemption;
import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry;
import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry.RestrictionStrategy;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Enforces the #854 archive contract: archiving replaced the hard delete for contacts and companies,
 * so a statement that could never observe a deleted record must not be able to observe an archived
 * one.
 *
 * <p>The check runs at the granularity a leak actually has — one MyBatis statement, with its
 * {@code <include>} fragments resolved exactly as MyBatis resolves them, and one table alias within
 * it. A statement that projects or filters on an archived contact's or company's identifying columns
 * (name, email, phone, title, website, industry, address, picture) without
 * {@code archived_at IS NULL} on that alias fails the build. Adding the predicate to one statement in
 * a file no longer covers the other forty, and no namespace-wide disposition can wave a statement
 * through: only a {@link StatementExemption} naming that statement and those aliases can, and each
 * exemption's stated reason is re-derived from the statement's own SQL rather than believed.
 */
class ArchiveVisibilityArchTest {

    private static final String SPACE = " ";
    private static final String INCLUDE_MARK = String.valueOf((char) 1);
    private static final Pattern INCLUDE_REF = Pattern.compile(
        Pattern.quote(INCLUDE_MARK) + "([^" + Pattern.quote(INCLUDE_MARK) + "]*)"
            + Pattern.quote(INCLUDE_MARK));
    private static final Pattern DOCTYPE = Pattern.compile("(?s)<!DOCTYPE.*?>");
    private static final Pattern RECORD_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?(?:person|company)[`\"]?(?:\\s|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ARCHIVED_NULL = Pattern.compile(
        "archived_at\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARCHIVED_NOT_NULL = Pattern.compile(
        "archived_at\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCKS_ROW = Pattern.compile(
        "FOR\\s+UPDATE", Pattern.CASE_INSENSITIVE);

    /**
     * Binds an alias to {@code person} or {@code company} in a FROM/JOIN position. The word boundary
     * after the table name is what keeps {@code person_share}, {@code person_employment}, and
     * {@code company_identity} out; the negative lookahead keeps SQL keywords from being read as an
     * alias when the table is used unaliased.
     */
    private static final Pattern RECORD_ALIAS = Pattern.compile(
        "\\b(?:FROM|JOIN)\\s+[`\"]?(person|company)[`\"]?\\b"
            + "(?:\\s+(?:AS\\s+)?"
            + "(?!ON\\b|WHERE\\b|SET\\b|USING\\b|LEFT\\b|RIGHT\\b|INNER\\b|JOIN\\b|GROUP\\b"
            + "|ORDER\\b|UNION\\b|LIMIT\\b|HAVING\\b|AND\\b|OR\\b|SELECT\\b)"
            + "([A-Za-z_][A-Za-z0-9_]*))?",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> PERSON_IDENTIFYING_COLUMNS =
        Set.of("name", "email", "phone", "title", "image_url");
    private static final Set<String> COMPANY_IDENTIFYING_COLUMNS =
        Set.of("name", "website", "industry", "phone", "address", "logo_url");
    private static final Set<String> OBJECT_STORAGE_COLUMNS = Set.of("image_url", "logo_url");
    private static final List<String> REPORT_PERIOD_BOUNDS =
        List.of("query.startUtc", "query.endUtc", "query.startDate", "query.endDateExclusive");

    @Test
    void everyRecordReaderHasOneReviewedArchiveDisposition() throws Exception {
        Map<String, MapperXml> readers = recordReaders();
        Map<String, ArchiveDisposition> dispositions = ArchiveVisibilityRegistry.dispositions();

        List<String> violations = new ArrayList<>();
        for (String namespace : readers.keySet()) {
            if (!dispositions.containsKey(namespace)) {
                violations.add(namespace + ": missing archive-visibility disposition — add an "
                    + "ArchiveVisibilityRegistry entry stating whether it excludes archived "
                    + "contacts/companies or deliberately reaches them, and why.");
            }
        }
        for (String namespace : dispositions.keySet()) {
            if (!readers.containsKey(namespace)) {
                violations.add("stale archive disposition " + namespace);
            }
        }
        assertTrue(violations.isEmpty(),
            "Archive-visibility checklist failures: " + violations);
    }

    /**
     * The core guard. Every statement is checked on its own resolved SQL, so a mapper cannot pass
     * because some other statement in the same file happens to carry the predicate.
     */
    @Test
    void everyArchivedProjectionCarriesThePredicateOrANamedExemption() throws Exception {
        Map<String, Set<String>> exemptedAliases = exemptedAliasesByStatement();

        List<String> violations = new ArrayList<>();
        for (MapperXml mapper : allMapperXml().values()) {
            for (Statement statement : mapper.statements()) {
                Set<String> waived = exemptedAliases.getOrDefault(statement.key(), Set.of());
                for (String alias : unguardedProjections(statement)) {
                    if (!waived.contains(alias)) {
                        violations.add(statement.key() + " projects archived rows through `" + alias
                            + "` — add `" + alias + ".archived_at IS NULL` to that statement, or add "
                            + "an ArchiveVisibilityRegistry statement exemption naming it.");
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Archived contacts and companies must not reach a caller (#854): " + violations);
    }

    /**
     * Keeps every exemption earned. An exemption must name a real statement, must still be needed,
     * and must cover exactly the archived reads that statement performs — so a new archived alias
     * added to an already-exempted statement is a red build rather than a free ride on the old
     * waiver, and a statement that has since been fixed loses its waiver instead of keeping a
     * standing licence to regress.
     */
    @Test
    void everyStatementExemptionStaysTrueOfItsStatement() throws Exception {
        Map<String, MapperXml> mappers = allMapperXml();
        Map<String, ArchiveDisposition> dispositions = ArchiveVisibilityRegistry.dispositions();

        List<String> violations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StatementExemption exemption : ArchiveVisibilityRegistry.exemptions()) {
            if (!seen.add(exemption.key())) {
                violations.add(exemption.key() + ": duplicate exemption");
                continue;
            }
            if (!dispositions.containsKey(exemption.mapperNamespace())) {
                violations.add(exemption.key() + ": exempts a mapper with no archive disposition");
                continue;
            }
            MapperXml mapper = mappers.get(exemption.mapperNamespace());
            Statement statement = mapper == null ? null : mapper.statement(exemption.statementId());
            if (statement == null) {
                violations.add(exemption.key() + ": no such statement — the exemption is stale");
                continue;
            }
            Set<String> unguarded = unguardedProjections(statement);
            if (!unguarded.equals(exemption.aliases())) {
                violations.add(exemption.key() + ": exempts " + new TreeSet<>(exemption.aliases())
                    + " but the statement reads archived rows through " + new TreeSet<>(unguarded)
                    + (unguarded.isEmpty()
                        ? " — the statement carries the predicate now, so drop the exemption"
                        : " — review the new read and update or remove the exemption"));
            }
        }
        assertTrue(violations.isEmpty(),
            "Archive statement exemptions must stay minimal and true: " + violations);
    }

    /**
     * Makes each exemption's stated reason falsifiable. The reason is not prose to be taken on
     * trust: every constant carries a claim about the statement, and the claim is re-derived here
     * from the statement's own SQL or from an independent registry.
     */
    @Test
    void everyStatementExemptionReasonHoldsInTheStatementItself() throws Exception {
        Map<String, MapperXml> mappers = allMapperXml();

        List<String> violations = new ArrayList<>();
        for (StatementExemption exemption : ArchiveVisibilityRegistry.exemptions()) {
            MapperXml mapper = mappers.get(exemption.mapperNamespace());
            Statement statement = mapper == null ? null : mapper.statement(exemption.statementId());
            if (statement == null) {
                continue;
            }
            String failure = reasonFailure(exemption, statement);
            if (failure != null) {
                violations.add(exemption.key() + " claims " + exemption.reason() + " but " + failure);
            }
        }
        assertTrue(violations.isEmpty(),
            "An archive exemption's reason must be true of the statement it exempts: " + violations);
    }

    /** Each namespace disposition must still describe the SQL that is actually there. */
    @Test
    void everyArchiveStrategyRetainsItsRequiredSqlEvidence() throws Exception {
        Map<String, MapperXml> mappers = allMapperXml();
        Map<String, List<StatementExemption>> exemptions = exemptionsByNamespace();

        List<String> violations = new ArrayList<>();
        for (ArchiveDisposition disposition : ArchiveVisibilityRegistry.dispositions().values()) {
            MapperXml mapper = mappers.get(disposition.mapperNamespace());
            if (mapper == null) {
                continue;
            }
            String evidence = evidenceFailure(
                disposition.strategy(), mapper,
                exemptions.getOrDefault(disposition.mapperNamespace(), List.of()));
            if (evidence != null) {
                violations.add(disposition.mapperNamespace() + " claims "
                    + disposition.strategy() + " but " + evidence);
            }
        }
        assertTrue(violations.isEmpty(),
            "Archive dispositions must describe and prove existing mapper behavior honestly: "
                + violations);
    }

    /**
     * Pins the propagation mechanisms themselves. Every ordinary contact and company read inherits
     * the archive predicate from one shared fragment; if a fragment loses it, dozens of statements
     * start returning archived records at once.
     */
    @Test
    void theSharedVisibilityFragmentsCarryTheArchivePredicate() throws Exception {
        assertTrue(fragment("PersonMapper", "visible").contains("p.archived_at IS NULL"),
            "PersonMapper's `visible` fragment must exclude archived contacts; the whole "
                + "archive contract propagates through it.");
        assertTrue(fragment("CompanyMapper", "visible").contains("c.archived_at IS NULL"),
            "CompanyMapper's `visible` fragment must exclude archived companies; the whole "
                + "archive contract propagates through it.");
        assertTrue(fragment("PersonMapper", "companyJoin").contains("c.archived_at IS NULL"),
            "PersonMapper's `companyJoin` fragment must drop an archived employer; every contact "
                + "row, peek, and detail page projects its company through it.");
        assertTrue(fragment("AttachmentMapper", "tenantAttachJoins").contains("p.archived_at IS NULL"),
            "AttachmentMapper's owner-label joins must exclude archived contacts; the file browser "
                + "labels every file through them.");
        assertTrue(fragment("AttachmentMapper", "tenantAttachJoins").contains("c.archived_at IS NULL"),
            "AttachmentMapper's owner-label joins must exclude archived companies; the file browser "
                + "labels every file through them.");
    }

    /** No mapper may retain a hard DELETE against the two archive-backed record tables. */
    @Test
    void noMapperCanHardDeleteAContactOrCompany() throws Exception {
        Pattern hardDelete = Pattern.compile(
            "DELETE\\s+FROM\\s+[`\"]?(?:person|company)[`\"]?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, MapperXml> reader : allMapperXml().entrySet()) {
            if (hardDelete.matcher(reader.getValue().sql()).find()) {
                violations.add(reader.getKey());
            }
        }
        assertTrue(violations.isEmpty(),
            "Contacts and companies are archived, never deleted (#854); whole-tenant teardown "
                + "drives its DELETEs from TenantLifecycleRegistry identifiers instead: " + violations);
    }

    private String reasonFailure(StatementExemption exemption, Statement statement) {
        return switch (exemption.reason()) {
            case RECORD_LIFECYCLE_LOCK -> LOCKS_ROW.matcher(statement.sql()).find()
                ? null : "the statement takes no row lock";
            case LAWFUL_DISCLOSURE -> disclosesRestrictedRecords(exemption.mapperNamespace())
                ? null
                : "its mapper is not enrolled in ProcessingRestrictionRegistry as a disclosure path";
            case OBJECT_STORAGE_KEYS_ONLY -> columnsFailure(
                exemption, statement, OBJECT_STORAGE_COLUMNS, "stored object keys");
            case CLOSED_PERIOD_ANALYTICS -> REPORT_PERIOD_BOUNDS.stream()
                .anyMatch(bound -> statement.sql().contains("#{" + bound + "}"))
                ? null : "the statement is bounded by no report period, so it is a live worklist";
        };
    }

    private String columnsFailure(
            StatementExemption exemption, Statement statement, Set<String> allowed, String label) {
        Set<String> read = new TreeSet<>();
        for (String alias : exemption.aliases()) {
            read.addAll(identifyingColumnsRead(statement, alias));
        }
        read.removeAll(allowed);
        return read.isEmpty() ? null : "it also reads " + read + " beyond the " + label;
    }

    private boolean disclosesRestrictedRecords(String namespace) {
        var enrollment = ProcessingRestrictionRegistry.enrollments().get(namespace);
        return enrollment != null
            && enrollment.strategy() == RestrictionStrategy.INCLUDE_RESTRICTED_FOR_DISCLOSURE;
    }

    private String evidenceFailure(
            ArchiveStrategy strategy, MapperXml mapper, List<StatementExemption> exemptions) {
        return switch (strategy) {
            case EXCLUDE_ARCHIVED -> ARCHIVED_NULL.matcher(mapper.sql()).find()
                ? null : "it never tests `archived_at IS NULL`";
            case DETECT_ARCHIVED -> ARCHIVED_NOT_NULL.matcher(mapper.sql()).find()
                ? null : "it never tests `archived_at IS NOT NULL`";
            case ARCHIVE_TOGGLE -> ARCHIVED_NULL.matcher(mapper.sql()).find()
                && ARCHIVED_NOT_NULL.matcher(mapper.sql()).find()
                ? null : "it does not serve both the active and the archived set";
            case NO_RECORD_PROJECTION -> projectingStatements(mapper).isEmpty()
                ? null : "these statements do project record columns: " + projectingStatements(mapper);
            case REACH_ARCHIVED -> exemptions.isEmpty()
                ? "no statement of it actually reaches an archived record, so the strategy grants "
                    + "nothing and the mapper should be reclassified"
                : null;
        };
    }

    private List<String> projectingStatements(MapperXml mapper) {
        List<String> projecting = new ArrayList<>();
        for (Statement statement : mapper.statements()) {
            for (Map.Entry<String, String> alias : recordAliases(statement.sql()).entrySet()) {
                if (!identifyingColumnsRead(statement, alias.getKey()).isEmpty()) {
                    projecting.add(statement.statementId());
                    break;
                }
            }
        }
        return projecting;
    }

    /** Record aliases the statement reads identifying columns through without the archive predicate. */
    private Set<String> unguardedProjections(Statement statement) {
        Set<String> unguarded = new LinkedHashSet<>();
        for (Map.Entry<String, String> alias : recordAliases(statement.sql()).entrySet()) {
            if (identifyingColumnsRead(statement, alias.getKey()).isEmpty()) {
                continue;
            }
            if (!hasArchivePredicate(statement, alias.getKey(), alias.getValue())) {
                unguarded.add(alias.getKey());
            }
        }
        return unguarded;
    }

    private boolean hasArchivePredicate(Statement statement, String alias, String table) {
        if (Pattern.compile("\\b" + Pattern.quote(alias) + "\\.archived_at\\s+IS\\s+(?:NOT\\s+)?NULL",
                Pattern.CASE_INSENSITIVE).matcher(statement.sql()).find()) {
            return true;
        }
        return alias.equals(table)
            && Pattern.compile("(?<![\\w.])archived_at\\s+IS\\s+(?:NOT\\s+)?NULL",
                Pattern.CASE_INSENSITIVE).matcher(statement.sql()).find();
    }

    /** Identifying columns of the aliased record table that the statement projects or filters on. */
    private Set<String> identifyingColumnsRead(Statement statement, String alias) {
        Map<String, String> aliases = recordAliases(statement.sql());
        String table = aliases.get(alias);
        if (table == null) {
            return Set.of();
        }
        Set<String> columns = "person".equals(table)
            ? PERSON_IDENTIFYING_COLUMNS : COMPANY_IDENTIFYING_COLUMNS;
        boolean unaliasedAndAlone = alias.equals(table) && aliases.size() == 1;
        Set<String> read = new LinkedHashSet<>();
        for (String column : columns) {
            if (Pattern.compile("\\b" + Pattern.quote(alias) + "\\." + column + "\\b",
                    Pattern.CASE_INSENSITIVE).matcher(statement.sql()).find()) {
                read.add(column);
            } else if (unaliasedAndAlone
                && Pattern.compile("(?<![\\w.])" + column + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(statement.sql()).find()) {
                read.add(column);
            }
        }
        return read;
    }

    /** Maps each alias bound in a FROM/JOIN position to the record table it stands for. */
    private Map<String, String> recordAliases(String sql) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = RECORD_ALIAS.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            String alias = matcher.group(2) == null ? table : matcher.group(2);
            aliases.putIfAbsent(alias, table);
        }
        return aliases;
    }

    private Map<String, Set<String>> exemptedAliasesByStatement() {
        Map<String, Set<String>> byStatement = new HashMap<>();
        for (StatementExemption exemption : ArchiveVisibilityRegistry.exemptions()) {
            byStatement.computeIfAbsent(exemption.key(), key -> new HashSet<>())
                .addAll(exemption.aliases());
        }
        return byStatement;
    }

    private Map<String, List<StatementExemption>> exemptionsByNamespace() {
        Map<String, List<StatementExemption>> byNamespace = new HashMap<>();
        for (StatementExemption exemption : ArchiveVisibilityRegistry.exemptions()) {
            byNamespace.computeIfAbsent(exemption.mapperNamespace(), key -> new ArrayList<>())
                .add(exemption);
        }
        return byNamespace;
    }

    private String fragment(String mapper, String fragmentId) throws Exception {
        MapperXml parsed = parse("ooo.klae.connex.backend.mappers." + mapper, mapperXml(mapper));
        String body = parsed.fragments().get(fragmentId);
        assertFalse(body == null, mapper + " no longer declares the `" + fragmentId + "` fragment");
        return body;
    }

    private Map<String, MapperXml> recordReaders() throws Exception {
        Map<String, MapperXml> readers = new HashMap<>();
        for (Map.Entry<String, MapperXml> entry : allMapperXml().entrySet()) {
            if (RECORD_READ.matcher(entry.getValue().sql()).find()) {
                readers.put(entry.getKey(), entry.getValue());
            }
        }
        assertFalse(readers.isEmpty(),
            "No contact/company-reading mapper XML was discovered; the archive scan is misconfigured.");
        return readers;
    }

    private Map<String, MapperXml> allMapperXml() throws Exception {
        Map<String, MapperXml> byNamespace = new HashMap<>();
        Set<String> namespaces = new HashSet<>(TenantScopeInterceptor.SCOPED_NAMESPACES);
        namespaces.addAll(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES);
        for (String namespace : namespaces) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            byNamespace.put(namespace, parse(namespace, mapperXml(mapper)));
        }
        return byNamespace;
    }

    private String mapperXml(String mapper) throws IOException {
        String resource = "mappers/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertFalse(input == null, "Mapper XML not found on the classpath: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Splits one mapper file into its reusable fragments and its statements, then resolves every
     * {@code <include>} the way MyBatis does, so each statement is checked against the SQL it really
     * runs. Comments are dropped with the rest of the non-text nodes, so prose about the predicate
     * can never stand in as evidence for it.
     */
    private MapperXml parse(String namespace, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
            new InputSource(new ByteArrayInputStream(new byte[0])));
        String withoutDoctype = DOCTYPE.matcher(xml).replaceFirst("");
        Document document = builder.parse(
            new InputSource(new ByteArrayInputStream(withoutDoctype.getBytes(StandardCharsets.UTF_8))));

        Map<String, String> fragments = new LinkedHashMap<>();
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
            } else if (Set.of("select", "insert", "update", "delete").contains(element.getTagName())) {
                statementElements.add(element);
            }
        }

        Map<String, String> resolvedFragments = new LinkedHashMap<>();
        for (Map.Entry<String, String> fragment : fragments.entrySet()) {
            resolvedFragments.put(fragment.getKey(), resolve(fragment.getValue(), fragments, 0));
        }
        List<Statement> statements = new ArrayList<>();
        StringBuilder whole = new StringBuilder();
        for (Element element : statementElements) {
            String sql = resolve(collectSql(element), fragments, 0);
            statements.add(new Statement(namespace, element.getAttribute("id"), sql));
            whole.append(sql).append('\n');
        }
        for (String fragment : resolvedFragments.values()) {
            whole.append(fragment).append('\n');
        }
        return new MapperXml(namespace, resolvedFragments, statements, whole.toString());
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

    /** One mapper file: its resolved reusable fragments, its statements, and all of that SQL. */
    private record MapperXml(
            String namespace,
            Map<String, String> fragments,
            List<Statement> statements,
            String sql) {

        Statement statement(String statementId) {
            return statements.stream()
                .filter(statement -> statement.statementId().equals(statementId))
                .findFirst()
                .orElse(null);
        }
    }

    /** One MyBatis statement with its includes resolved. */
    private record Statement(String namespace, String statementId, String sql) {

        String key() {
            return namespace + "#" + statementId;
        }
    }
}
