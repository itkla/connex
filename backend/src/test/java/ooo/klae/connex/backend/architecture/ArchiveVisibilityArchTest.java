package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.ArchiveDisposition;
import ooo.klae.connex.backend.tenant.ArchiveVisibilityRegistry.ArchiveStrategy;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Enforces the #854 archive contract: archiving replaced the hard delete for contacts and
 * companies, so a mapper that reads those tables must have exactly one reviewed disposition in
 * {@link ArchiveVisibilityRegistry} and must actually carry the SQL that disposition claims.
 *
 * <p>A new person- or company-reading mapper that forgets {@code archived_at IS NULL} therefore
 * fails the build with a checklist instead of silently resurrecting archived records into lists,
 * segments, campaigns, or the relationship graph.
 */
class ArchiveVisibilityArchTest {

    private static final Pattern RECORD_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?(?:person|company)[`\"]?(?:\\s|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ARCHIVED_NULL = Pattern.compile(
        "archived_at\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARCHIVED_NOT_NULL = Pattern.compile(
        "archived_at\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);

    @Test
    void everyRecordReaderHasOneReviewedArchiveDisposition() throws Exception {
        Map<String, String> readers = recordReaders();
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

    @Test
    void everyArchiveStrategyRetainsItsRequiredSqlEvidence() throws Exception {
        Map<String, String> readers = recordReaders();
        List<String> violations = new ArrayList<>();
        for (ArchiveDisposition disposition : ArchiveVisibilityRegistry.dispositions().values()) {
            String xml = readers.get(disposition.mapperNamespace());
            if (xml == null) {
                continue;
            }
            if (!hasEvidence(xml, disposition.strategy())) {
                violations.add(disposition.mapperNamespace() + " is missing SQL evidence for "
                    + disposition.strategy());
            }
        }
        assertTrue(violations.isEmpty(),
            "Archive dispositions must describe and prove existing mapper behavior honestly: "
                + violations);
    }

    /**
     * Pins the propagation mechanism itself. Every ordinary contact and company read inherits the
     * archive predicate from one shared fragment; if that fragment loses it, ~40 statements start
     * returning archived records at once and the per-file evidence scan above would still pass.
     */
    @Test
    void theSharedVisibilityFragmentsCarryTheArchivePredicate() throws Exception {
        assertTrue(visibleFragment("PersonMapper").contains("p.archived_at IS NULL"),
            "PersonMapper's `visible` fragment must exclude archived contacts; the whole "
                + "archive contract propagates through it.");
        assertTrue(visibleFragment("CompanyMapper").contains("c.archived_at IS NULL"),
            "CompanyMapper's `visible` fragment must exclude archived companies; the whole "
                + "archive contract propagates through it.");
    }

    /** No mapper may retain a hard DELETE against the two archive-backed record tables. */
    @Test
    void noMapperCanHardDeleteAContactOrCompany() throws Exception {
        Pattern hardDelete = Pattern.compile(
            "DELETE\\s+FROM\\s+[`\"]?(?:person|company)[`\"]?(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> reader : allMapperXml().entrySet()) {
            if (hardDelete.matcher(reader.getValue()).find()) {
                violations.add(reader.getKey());
            }
        }
        assertTrue(violations.isEmpty(),
            "Contacts and companies are archived, never deleted (#854); whole-tenant teardown "
                + "drives its DELETEs from TenantLifecycleRegistry identifiers instead: " + violations);
    }

    private boolean hasEvidence(String xml, ArchiveStrategy strategy) {
        return switch (strategy) {
            case EXCLUDE_ARCHIVED -> ARCHIVED_NULL.matcher(xml).find();
            case DETECT_ARCHIVED -> ARCHIVED_NOT_NULL.matcher(xml).find();
            case ARCHIVE_TOGGLE -> ARCHIVED_NULL.matcher(xml).find()
                && ARCHIVED_NOT_NULL.matcher(xml).find();
            case REACH_ARCHIVED -> true;
        };
    }

    private String visibleFragment(String mapper) throws IOException {
        String xml = mapperXml(mapper);
        int start = xml.indexOf("<sql id=\"visible\">");
        assertFalse(start < 0, mapper + " no longer declares a `visible` SQL fragment");
        int end = xml.indexOf("</sql>", start);
        assertFalse(end < 0, mapper + "'s `visible` fragment is unterminated");
        return xml.substring(start, end);
    }

    private Map<String, String> recordReaders() throws Exception {
        Map<String, String> readers = new HashMap<>();
        for (Map.Entry<String, String> entry : allMapperXml().entrySet()) {
            if (RECORD_READ.matcher(entry.getValue()).find()) {
                readers.put(entry.getKey(), entry.getValue());
            }
        }
        assertFalse(readers.isEmpty(),
            "No contact/company-reading mapper XML was discovered; the archive scan is misconfigured.");
        return readers;
    }

    private Map<String, String> allMapperXml() throws IOException {
        Map<String, String> byNamespace = new HashMap<>();
        Set<String> namespaces = new HashSet<>(TenantScopeInterceptor.SCOPED_NAMESPACES);
        namespaces.addAll(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES);
        for (String namespace : namespaces) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            byNamespace.put(namespace, mapperXml(mapper).replaceAll("(?s)<!--.*?-->", ""));
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
}
