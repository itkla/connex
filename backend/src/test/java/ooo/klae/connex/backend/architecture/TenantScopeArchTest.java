package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Read-path tenant-isolation backstop (#89). Asserts that every {@code <select>}
 * in a workspace-scoped mapper binds the {@code #{workspaceId}} parameter, so a
 * future read mapper that forgets the tenant predicate fails the build instead of
 * silently leaking across tenants. Pairs with the fail-closed
 * {@link TenantScopeInterceptor} (which only guards that a context is resolved, not
 * that the SQL filters) and the runtime {@code *_areIsolatedByWorkspace} tests.
 *
 * <p>Binding {@code #{workspaceId}} — not merely mentioning the {@code workspace_id}
 * column — is the signal: a statement that only selects {@code t.workspace_id}
 * without a predicate does not bind the parameter and is correctly flagged.
 *
 * <p>This is a heuristic backstop, not a proof: it confirms the parameter is bound
 * somewhere in the resolved statement, not that it filters the driving table on
 * every path (a future binding only inside an {@code <if>} branch or an unrelated
 * subquery could still pass). It is therefore layered with the runtime isolation
 * tests and the fail-closed interceptor, not relied on alone.
 */
class TenantScopeArchTest {

    private static final Pattern WORKSPACE_PARAM = Pattern.compile("#\\{\\s*(?:\\w+\\.)?workspaceId\\b");

    /**
     * Scoped-mapper selects that legitimately do not bind {@code #{workspaceId}}.
     * Each is provably tenant-safe without it: the notification inbox is
     * recipient-scoped across every membership by design (MULTITENANCY_PLAN §0.3 —
     * it binds {@code #{recipientId}}), the two scheduler helpers only enumerate
     * workspace ids for per-workspace background fan-out (they return no tenant rows),
     * and the org-scoped audit reads are org-filtered ({@code #{orgId}}) and gated by
     * org membership (MULTITENANCY_PLAN §0.6).
     */
    private static final Set<String> EXEMPT_SELECTS = Set.of(
        "ooo.klae.connex.backend.mappers.NotificationMapper.findPage",
        "ooo.klae.connex.backend.mappers.NotificationMapper.countPage",
        "ooo.klae.connex.backend.mappers.NotificationMapper.getUnreadCounts",
        "ooo.klae.connex.backend.mappers.NotificationMapper.findById",
        "ooo.klae.connex.backend.mappers.NotificationMapper.findWorkspaceIds",
        "ooo.klae.connex.backend.mappers.RuleMapper.workspaceIdsWithEnabledScheduleRules",
        "ooo.klae.connex.backend.mappers.AuditLogMapper.findRecentByOrg",
        "ooo.klae.connex.backend.mappers.AuditLogMapper.findOrgExport"
    );

    /**
     * Scoped-mapper writes that legitimately do not bind {@code #{workspaceId}}.
     * The audit-log insert runs during auth flows before a workspace is pinned and
     * carries a nullable {@code workspace_id} for system events (mirrors the matching
     * exemption in {@link TenantScopeInterceptor}). The notification mutations are
     * recipient-scoped across every membership by design (MULTITENANCY_PLAN §0.3 — they
     * bind {@code #{recipientId}}), exactly like the exempt notification selects above.
     */
    private static final Set<String> EXEMPT_WRITES = Set.of(
        "ooo.klae.connex.backend.mappers.AuditLogMapper.insert",
        "ooo.klae.connex.backend.mappers.NotificationMapper.markRead",
        "ooo.klae.connex.backend.mappers.NotificationMapper.markUnread",
        "ooo.klae.connex.backend.mappers.NotificationMapper.dismiss",
        "ooo.klae.connex.backend.mappers.NotificationMapper.restore",
        "ooo.klae.connex.backend.mappers.NotificationMapper.snooze",
        "ooo.klae.connex.backend.mappers.NotificationMapper.markAllRead"
    );

    /**
     * Write-path counterpart to {@link #every_select_in_scoped_mappers_binds_workspaceId()}:
     * asserts every {@code <insert>/<update>/<delete>} in a scoped mapper binds {@code #{workspaceId}}.
     * Same presence-not-placement heuristic as the class doc (a binding in an unrelated subquery would
     * still pass), so it is layered with the runtime cross-workspace write tests (e.g.
     * {@code CompanyMapperTest.addTag_fromAnotherWorkspace_doesNotAssociate}) and the fail-closed interceptor.
     */
    @Test
    void every_write_in_scoped_mappers_binds_workspaceId() throws Exception {
        List<String> violations = new ArrayList<>();
        int mappersWithWrites = 0;

        for (String namespace : TenantScopeInterceptor.SCOPED_NAMESPACES) {
            String simpleName = namespace.substring(namespace.lastIndexOf('.') + 1);
            String resource = "mappers/" + simpleName + ".xml";
            Document doc = loadMapper(resource);
            assertNotNull(doc, "Scoped mapper XML not found on the classpath: " + resource);

            Map<String, Element> fragments = sqlFragments(doc);
            int writesInMapper = 0;
            for (String tag : List.of("insert", "update", "delete")) {
                for (Element statement : elementsByTag(doc, tag)) {
                    writesInMapper++;
                    String statementId = namespace + "." + statement.getAttribute("id");
                    if (EXEMPT_WRITES.contains(statementId)) {
                        continue;
                    }
                    String sql = resolvedSql(statement, fragments);
                    if (!WORKSPACE_PARAM.matcher(sql).find()) {
                        violations.add(statementId);
                    }
                }
            }
            if (writesInMapper > 0) {
                mappersWithWrites++;
            }
        }

        assertTrue(mappersWithWrites >= 12,
            "Only " + mappersWithWrites + " of " + TenantScopeInterceptor.SCOPED_NAMESPACES.size()
                + " scoped mappers contributed any write statements — most have writes, so a near-zero "
                + "count means the per-mapper XML scan is misconfigured and this guard would pass vacuously.");
        assertTrue(violations.isEmpty(),
            "These <insert>/<update>/<delete> statements in workspace-scoped mappers do not bind "
                + "#{workspaceId} (add the tenant predicate/value, or exempt explicitly with rationale): "
                + violations);
    }

    @Test
    void every_select_in_scoped_mappers_binds_workspaceId() throws Exception {
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (String namespace : TenantScopeInterceptor.SCOPED_NAMESPACES) {
            String simpleName = namespace.substring(namespace.lastIndexOf('.') + 1);
            String resource = "mappers/" + simpleName + ".xml";
            Document doc = loadMapper(resource);
            assertNotNull(doc, "Scoped mapper XML not found on the classpath: " + resource);

            Map<String, Element> fragments = sqlFragments(doc);
            List<Element> selects = elementsByTag(doc, "select");
            assertFalse(selects.isEmpty(),
                "No <select> statements parsed from " + resource + " — the XML scan is misconfigured.");

            for (Element select : selects) {
                scanned++;
                String statementId = namespace + "." + select.getAttribute("id");
                if (EXEMPT_SELECTS.contains(statementId)) {
                    continue;
                }
                String sql = resolvedSql(select, fragments);
                if (!WORKSPACE_PARAM.matcher(sql).find()) {
                    violations.add(statementId);
                }
            }
        }

        assertTrue(scanned >= TenantScopeInterceptor.SCOPED_NAMESPACES.size(),
            "Only scanned " + scanned + " selects across scoped mappers — the scan looks misconfigured "
                + "and this guard would pass vacuously.");
        assertTrue(violations.isEmpty(),
            "These <select> statements in workspace-scoped mappers do not bind #{workspaceId} "
                + "(add the tenant predicate, or exempt explicitly with rationale): " + violations);
    }

    private Document loadMapper(String resource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return builder.parse(in);
        }
    }

    private Map<String, Element> sqlFragments(Document doc) {
        Map<String, Element> fragments = new HashMap<>();
        for (Element fragment : elementsByTag(doc, "sql")) {
            fragments.put(fragment.getAttribute("id"), fragment);
        }
        return fragments;
    }

    private List<Element> elementsByTag(Document doc, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    /**
     * Concatenates a statement's SQL text, recursing into dynamic tags and splicing
     * {@code <include refid="...">} fragments so the assertion sees the effective SQL.
     */
    private String resolvedSql(Node node, Map<String, Element> fragments) {
        StringBuilder sql = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sql.append(child.getNodeValue());
                case Node.ELEMENT_NODE -> {
                    Element element = (Element) child;
                    if ("include".equals(element.getTagName())) {
                        Element fragment = fragments.get(element.getAttribute("refid"));
                        if (fragment != null) {
                            sql.append(resolvedSql(fragment, fragments));
                        }
                    } else {
                        sql.append(resolvedSql(element, fragments));
                    }
                }
                default -> { }
            }
        }
        return sql.toString();
    }
}
