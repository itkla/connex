package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry;
import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry.RestrictionEnrollment;
import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry.RestrictionStrategy;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Enforces the APPI four-fold tax on tenant persistence. Plane classification
 * is enforced by {@link TablePlaneArchTest}; mapper/interceptor enrollment is
 * enforced by {@link TenantRegistryCompletenessArchTest} and
 * {@link TenantScopeArchTest}. This test enforces restriction-sweep assessment
 * and linkage to the lifecycle declaration that drives export, teardown, and
 * residual verification.
 *
 * <p>A new table or person-reading mapper must fail with a checklist naming
 * missing (c) restriction-sweep enrollment and (d) teardown declaration so the
 * compliance obligations cannot be merged independently.
 */
class AppiComplianceArchTest {

    private static final Pattern PERSON_READ = Pattern.compile(
        "(?:FROM|JOIN)\\s+[`\"]?person[`\"]?(?:\\s|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SUSPENDED_NULL = Pattern.compile(
        "suspended_at\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern CEASED_NULL = Pattern.compile(
        "provision_ceased_at\\s+IS\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUSPENDED_NOT_NULL = Pattern.compile(
        "suspended_at\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern CEASED_NOT_NULL = Pattern.compile(
        "provision_ceased_at\\s+IS\\s+NOT\\s+NULL", Pattern.CASE_INSENSITIVE);

    @Test
    void everyPersonReaderHasOneReviewedRestrictionDisposition() throws Exception {
        Map<String, String> personReaders = personReaders();
        Map<String, RestrictionEnrollment> enrollments =
            ProcessingRestrictionRegistry.enrollments();
        Map<String, String> allowlist =
            ProcessingRestrictionRegistry.personReaderAllowlist();
        Set<String> overlap = new HashSet<>(enrollments.keySet());
        overlap.retainAll(allowlist.keySet());

        List<String> violations = new ArrayList<>();
        if (!overlap.isEmpty()) {
            violations.add("namespaces appear in both enrollment and allowlist: " + overlap);
        }
        for (Map.Entry<String, String> reader : personReaders.entrySet()) {
            int dispositions = (enrollments.containsKey(reader.getKey()) ? 1 : 0)
                + (allowlist.containsKey(reader.getKey()) ? 1 : 0);
            if (dispositions != 1) {
                violations.add(reader.getKey() + " has " + dispositions
                    + " restriction dispositions");
            }
        }
        for (String namespace : enrollments.keySet()) {
            if (!personReaders.containsKey(namespace)) {
                violations.add("stale restriction enrollment " + namespace);
            }
        }
        for (Map.Entry<String, String> exception : allowlist.entrySet()) {
            if (!personReaders.containsKey(exception.getKey())) {
                violations.add("stale person-reader allowlist entry " + exception.getKey());
            }
            if (exception.getValue() == null || exception.getValue().isBlank()) {
                violations.add("blank allowlist rationale " + exception.getKey());
            }
        }
        assertEquals(11, enrollments.size(),
            "The historical APPI restriction sweep must retain all 11 reviewed namespaces.");
        assertTrue(violations.isEmpty(),
            "Person-reading mappers must be either strategy-enrolled or explicitly allowlisted "
                + "with one-line justification: " + violations);
    }

    @Test
    void everyRestrictionStrategyRetainsItsRequiredSqlEvidence() throws Exception {
        Map<String, String> personReaders = personReaders();
        List<String> violations = new ArrayList<>();
        for (RestrictionEnrollment enrollment
                : ProcessingRestrictionRegistry.enrollments().values()) {
            String xml = personReaders.get(enrollment.mapperNamespace());
            if (xml == null) {
                violations.add(enrollment.mapperNamespace() + " no longer reads person");
                continue;
            }
            if (!hasEvidence(xml, enrollment.strategy())) {
                violations.add(enrollment.mapperNamespace() + " is missing SQL evidence for "
                    + enrollment.strategy());
            }
            if (enrollment.strategy() == RestrictionStrategy.PROJECT_RESTRICTION_STATE
                    && !enrollment.rationale().contains("#869")) {
                violations.add(enrollment.mapperNamespace()
                    + " projected-state debt must reference #869");
            }
        }
        String employmentRationale = ProcessingRestrictionRegistry.personReaderAllowlist().get(
            "ooo.klae.connex.backend.mappers.PersonEmploymentMapper");
        assertTrue(employmentRationale != null && employmentRationale.contains("#869"),
            "PersonEmploymentMapper's reviewed exception must reference tracking issue #869.");
        assertTrue(violations.isEmpty(),
            "Restriction enrollment must describe and prove existing mapper behavior honestly: "
                + violations);
    }

    @Test
    void everyOrgDataTableIsLifecycleDeclared() {
        Set<String> missing = new java.util.TreeSet<>(TablePlaneRegistry.ORG_DATA_TABLES);
        missing.removeAll(TenantLifecycleRegistry.declarations().keySet());
        Set<String> stale = new java.util.TreeSet<>(
            TenantLifecycleRegistry.declarations().keySet());
        stale.removeAll(TablePlaneRegistry.ORG_DATA_TABLES);
        List<String> checklist = new ArrayList<>();
        for (String table : missing) {
            checklist.add(table + ": missing (c) restriction-sweep enrollment, "
                + "(d) teardown declaration — see AppiComplianceArchTest Javadoc.");
        }
        assertTrue(missing.isEmpty() && stale.isEmpty(),
            "APPI lifecycle checklist failures: " + checklist + "; stale=" + stale);
    }

    private boolean hasEvidence(String xml, RestrictionStrategy strategy) {
        return switch (strategy) {
            case EXCLUDE_SUSPENDED -> SUSPENDED_NULL.matcher(xml).find();
            case EXCLUDE_PROVISION_CEASED -> CEASED_NULL.matcher(xml).find();
            case DETECT_RESTRICTED -> SUSPENDED_NOT_NULL.matcher(xml).find()
                && CEASED_NOT_NULL.matcher(xml).find();
            case INCLUDE_RESTRICTED_FOR_DISCLOSURE, PROJECT_RESTRICTION_STATE ->
                containsBothRestrictionColumns(xml);
        };
    }

    private boolean containsBothRestrictionColumns(String xml) {
        return xml.toLowerCase().contains("suspended_at")
            && xml.toLowerCase().contains("provision_ceased_at");
    }

    private Map<String, String> personReaders() throws Exception {
        Map<String, String> readers = new HashMap<>();
        Set<String> namespaces = new HashSet<>(TenantScopeInterceptor.SCOPED_NAMESPACES);
        namespaces.addAll(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES);
        for (String namespace : namespaces) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            String xml = mapperXml(mapper).replaceAll("(?s)<!--.*?-->", "");
            if (PERSON_READ.matcher(xml).find()) {
                readers.put(namespace, xml);
            }
        }
        assertFalse(readers.isEmpty(),
            "No person-reading mapper XML was discovered; the APPI scan is misconfigured.");
        return readers;
    }

    private String mapperXml(String mapper) throws IOException {
        String resource = "mappers/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertFalse(input == null, "Mapper XML not found on the classpath: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
