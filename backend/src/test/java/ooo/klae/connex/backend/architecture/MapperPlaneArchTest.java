package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Pins the statement-level crossings of the control-plane wall (#440
 * increment 3). {@code TablePlaneArchTest} guarantees no FOREIGN KEY crosses
 * the wall; this test inventories the SQL that still does — every JOIN or
 * subquery in a workspace-scoped mapper that reads a control-plane table (and
 * vice versa). These crossings work today because both planes share one
 * catalog, but each one must become service-side hydration before Phase 4
 * (#313) can place org data in its own catalog, where the joined table simply
 * does not exist. The baseline below is that rewrite work-list: adding a NEW
 * crossing grows the Phase 4 bill and fails this test until it is deliberately
 * baselined; shrinking the baseline as hydration lands is the goal.
 *
 * <p>{@code AuditLogMapper} and {@code RoleMapper} appear because they are
 * workspace-scope-ENFORCED mappers whose tables live wholly on the control
 * plane — the documented exception, not an accident (see
 * {@link TablePlaneRegistry}'s class doc on the two orthogonal registries).
 */
class MapperPlaneArchTest {

    private static final Pattern TABLE_TOKEN = Pattern.compile(
        "(?:FROM|JOIN|INTO|UPDATE|DELETE\\s+FROM)\\s+[`\"]?([A-Za-z_]\\w*)",
        Pattern.CASE_INSENSITIVE);

    /**
     * The current, reviewed inventory of cross-plane table references per
     * mapper. Every entry is Phase 4 rewrite work (or a documented exception).
     */
    private static final Map<String, Set<String>> BASELINE_CROSSINGS = Map.ofEntries(
        Map.entry("AiOutputCacheMapper", Set.of("workspace")),
        Map.entry("AttachmentMapper", Set.of("app_user")),
        Map.entry("AuditLogMapper", Set.of("app_user", "audit_log")),
        Map.entry("CompanyMapper", Set.of("workspace")),
        Map.entry("DealMapper", Set.of("app_user", "workspace", "workspace_member")),
        Map.entry("DuplicateReviewMapper", Set.of("workspace")),
        Map.entry("IntroductionMapper", Set.of("workspace")),
        Map.entry("NoteMapper", Set.of("app_user", "workspace")),
        Map.entry("NotificationMapper", Set.of("app_user", "notification_recipient_state", "workspace", "workspace_member")),
        Map.entry("PersonMapper", Set.of("workspace")),
        Map.entry("PipelineMapper", Set.of("workspace")),
        Map.entry("ReportMapper", Set.of("workspace")),
        Map.entry("RoleMapper", Set.of("workspace_role", "workspace_role_permission")),
        Map.entry("SegmentMapper", Set.of("workspace")),
        Map.entry("ShareMapper", Set.of("workspace")));

    @Test
    void crossPlaneStatementReferencesStayWithinTheBaseline() throws IOException {
        List<String> newCrossings = new ArrayList<>();
        int scanned = 0;
        for (String namespace : TenantScopeInterceptor.SCOPED_NAMESPACES) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            Set<String> crossings = crossPlaneReferences(mapper, TablePlaneRegistry.CONTROL_PLANE_TABLES);
            scanned++;
            recordNew(mapper, crossings, newCrossings);
        }
        for (String namespace : TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            Set<String> crossings = crossPlaneReferences(mapper, TablePlaneRegistry.ORG_DATA_TABLES);
            scanned++;
            recordNew(mapper, crossings, newCrossings);
        }
        assertTrue(scanned > 40, "Only scanned " + scanned + " mappers — the scan looks misconfigured.");
        assertTrue(newCrossings.isEmpty(),
            "New cross-plane table references in mapper SQL. Each one is a query that breaks when the "
                + "planes split into separate catalogs (Phase 4, #313) — hydrate in the service layer "
                + "instead, or add to the baseline with a reviewed rationale: " + newCrossings);
    }

    @Test
    void controlCatalogRoutingRegistryIsExactAndPhysicallyControlOnly() throws IOException {
        Set<String> expectedNamespaces = Stream.concat(
            TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.stream(),
            Stream.of(
                "ooo.klae.connex.backend.mappers.AuditLogMapper",
                "ooo.klae.connex.backend.mappers.RoleMapper"))
            .collect(Collectors.toUnmodifiableSet());
        assertEquals(
            expectedNamespaces,
            ControlCatalogRoutingInterceptor.CONTROL_CATALOG_NAMESPACES);
        Set<String> expectedStatements = Set.of(
            "ooo.klae.connex.backend.mappers.NotificationMapper.getStateVersion",
            "ooo.klae.connex.backend.mappers.NotificationMapper.bumpStateVersions",
            "ooo.klae.connex.backend.mappers.NotificationMapper.lockRecipientMemberships",
            "ooo.klae.connex.backend.mappers.NotificationMapper.findWorkspaceRecipientIds");
        assertEquals(
            expectedStatements,
            ControlCatalogRoutingInterceptor.CONTROL_CATALOG_STATEMENTS);

        List<String> tenantReferences = new ArrayList<>();
        for (String namespace : ControlCatalogRoutingInterceptor.CONTROL_CATALOG_NAMESPACES) {
            String mapper = namespace.substring(namespace.lastIndexOf('.') + 1);
            for (String table : crossPlaneReferences(mapper, TablePlaneRegistry.ORG_DATA_TABLES)) {
                tenantReferences.add(mapper + " -> " + table);
            }
        }
        for (String statementId : ControlCatalogRoutingInterceptor.CONTROL_CATALOG_STATEMENTS) {
            int statementSeparator = statementId.lastIndexOf('.');
            String mapper = statementId.substring(
                statementId.lastIndexOf('.', statementSeparator - 1) + 1,
                statementSeparator);
            String statement = statementId.substring(statementSeparator + 1);
            String statementXml = statementXml(mapper, statement);
            assertFalse(
                statementXml.contains("<include"),
                "Explicitly routed control statement must not contain unexpanded SQL fragments: "
                    + statementId);
            for (String table : tableReferences(
                    statementXml,
                    TablePlaneRegistry.ORG_DATA_TABLES)) {
                tenantReferences.add(mapper + "." + statement + " -> " + table);
            }
        }
        assertTrue(tenantReferences.isEmpty(),
            "Control-catalog routing may contain only physically control-plane SQL: "
                + tenantReferences);
    }

    private void recordNew(String mapper, Set<String> crossings, List<String> newCrossings) {
        Set<String> baseline = BASELINE_CROSSINGS.getOrDefault(mapper, Set.of());
        for (String table : crossings) {
            if (!baseline.contains(table)) {
                newCrossings.add(mapper + " -> " + table);
            }
        }
    }

    private Set<String> crossPlaneReferences(String mapper, Set<String> otherPlane) throws IOException {
        String xml = mapperXml(mapper).replaceAll("(?s)<!--.*?-->", "");
        return tableReferences(xml, otherPlane);
    }

    private Set<String> tableReferences(String xml, Set<String> tables) {
        Set<String> crossings = new java.util.TreeSet<>();
        Matcher matcher = TABLE_TOKEN.matcher(xml);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (tables.contains(table)) {
                crossings.add(table);
            }
        }
        return crossings;
    }

    private String statementXml(String mapper, String statement) throws IOException {
        String xml = mapperXml(mapper).replaceAll("(?s)<!--.*?-->", "");
        Pattern pattern = Pattern.compile(
            "(?s)<(select|insert|update|delete)\\b"
                + "(?=[^>]*\\bid\\s*=\\s*\"" + Pattern.quote(statement) + "\")"
                + "[^>]*>.*?</\\1>");
        Matcher matcher = pattern.matcher(xml);
        assertTrue(
            matcher.find(),
            "Mapped statement not found: " + mapper + "." + statement);
        return matcher.group();
    }

    private String mapperXml(String mapper) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mappers/" + mapper + ".xml")) {
            assertFalse(in == null, "Mapper XML not found on the classpath: mappers/" + mapper + ".xml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
