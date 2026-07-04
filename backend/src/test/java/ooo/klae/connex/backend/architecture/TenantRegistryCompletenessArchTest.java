package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Document;

import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Registry-completeness backstop for tenant scoping (#97, #313 Phase 2). The
 * fail-closed {@link TenantScopeInterceptor} and the workspace-predicate scan in
 * {@link TenantScopeArchTest} only protect mappers listed in
 * {@code SCOPED_NAMESPACES} — a mapper missing from the registry silently
 * bypasses both (exactly how {@code PersonEdgeMapper} once did). This test
 * closes that hole structurally: every mapper XML on the classpath must be
 * classified as either workspace-scoped or explicitly control-plane, the two
 * sets must not overlap, and neither set may reference a mapper that no longer
 * exists. Adding a mapper without classifying it fails the build.
 */
class TenantRegistryCompletenessArchTest {

    @Test
    void every_mapper_namespace_is_classified_as_scoped_or_control_plane() throws Exception {
        Set<String> namespaces = mapperNamespacesOnClasspath();
        assertTrue(namespaces.size() >= 30,
            "Only found " + namespaces.size() + " mapper XMLs on the classpath — the resource scan "
                + "looks misconfigured and this guard would pass vacuously.");

        List<String> unclassified = new ArrayList<>();
        for (String namespace : namespaces) {
            boolean scoped = TenantScopeInterceptor.SCOPED_NAMESPACES.contains(namespace);
            boolean controlPlane = TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.contains(namespace);
            if (!scoped && !controlPlane) {
                unclassified.add(namespace);
            }
        }
        assertTrue(unclassified.isEmpty(),
            "These mapper namespaces are in neither SCOPED_NAMESPACES nor CONTROL_PLANE_NAMESPACES, "
                + "so the fail-closed interceptor and the workspace-predicate arch scan ignore them. "
                + "Classify each in TenantScopeInterceptor (scoped if it touches tenant data): "
                + unclassified);
    }

    @Test
    void registries_are_disjoint_and_reference_existing_mappers() throws Exception {
        Set<String> overlap = new HashSet<>(TenantScopeInterceptor.SCOPED_NAMESPACES);
        overlap.retainAll(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES);
        assertTrue(overlap.isEmpty(),
            "A namespace must be scoped or control-plane, never both: " + overlap);

        Set<String> namespaces = mapperNamespacesOnClasspath();
        List<String> stale = new ArrayList<>();
        for (String namespace : TenantScopeInterceptor.SCOPED_NAMESPACES) {
            if (!namespaces.contains(namespace)) {
                stale.add(namespace);
            }
        }
        for (String namespace : TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES) {
            if (!namespaces.contains(namespace)) {
                stale.add(namespace);
            }
        }
        assertTrue(stale.isEmpty(),
            "These registry entries have no matching mapper XML on the classpath (remove or rename): "
                + stale);
    }

    private Set<String> mapperNamespacesOnClasspath() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] xmls = resolver.getResources("classpath:mappers/*.xml");
        Set<String> namespaces = new HashSet<>();
        for (Resource xml : xmls) {
            Document doc = parse(xml);
            String namespace = doc.getDocumentElement().getAttribute("namespace");
            assertNotNull(namespace, "Mapper XML without a namespace attribute: " + xml.getFilename());
            assertTrue(!namespace.isBlank(), "Blank mapper namespace in " + xml.getFilename());
            namespaces.add(namespace);
        }
        return namespaces;
    }

    private Document parse(Resource xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = xml.getInputStream()) {
            return builder.parse(in);
        }
    }
}
