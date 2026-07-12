package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Organization-ceiling backstop for the sharing control plane (#97, #313 §0.5).
 * The same-org invariant lives in hand-written SQL — the {@code INSERT..SELECT}
 * grants in {@code ShareMapper.xml} (write path) and the {@code EXISTS} share
 * branches of the owned-or-shared visibility predicates in the entity mappers
 * (read path). The workspace-predicate scan cannot see either (it only checks
 * {@code #{workspaceId}} is bound). These tests assert both paths carry the
 * org-equality join, so a future shareable entity type copied without the
 * ceiling fails the build instead of silently degrading cross-org protection to
 * the service layer alone.
 */
class OrgShareCeilingArchTest {

    private static final Pattern ORG_CEILING = Pattern.compile("tw\\.org_id\\s*=\\s*ow\\.org_id");
    private static final Pattern READ_CEILING = Pattern.compile("ows\\.org_id\\s*=\\s*vws\\.org_id");

    /**
     * Every entity mapper whose visibility predicate reads a {@code *_share} table
     * must pair each such read with the read-path org ceiling. Keyed by mapper
     * resource to the share table it references.
     */
    private static final java.util.Map<String, Pattern> SHARE_READERS = java.util.Map.of(
        "mappers/CompanyMapper.xml", Pattern.compile("FROM company_share"),
        "mappers/DealMapper.xml", Pattern.compile("FROM (?:company|pipeline)_share"),
        "mappers/PersonMapper.xml", Pattern.compile("FROM (?:person|company)_share"),
        "mappers/PipelineMapper.xml", Pattern.compile("FROM pipeline_share")
    );

    @Test
    void every_share_read_predicate_carries_the_same_org_ceiling() throws Exception {
        List<String> violations = new ArrayList<>();
        for (var entry : SHARE_READERS.entrySet()) {
            String xml = loadMapperText(entry.getKey());
            int shareReads = count(entry.getValue(), xml);
            int ceilings = count(READ_CEILING, xml);
            if (shareReads < 1) {
                violations.add(entry.getKey() + " references its share table 0 times — the scan is misconfigured");
            } else if (ceilings != shareReads) {
                violations.add(entry.getKey() + " has " + shareReads + " share-table reads but " + ceilings
                    + " org ceilings (ows.org_id = vws.org_id); every share read must be same-org gated");
            }
        }
        assertTrue(violations.isEmpty(),
            "Read-path share visibility is missing the same-organization ceiling: " + violations);
    }

    private int count(Pattern pattern, String text) {
        int n = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private String loadMapperText(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the classpath");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void every_share_grant_enforces_the_same_org_ceiling() throws Exception {
        Document doc = loadShareMapper();
        List<String> violations = new ArrayList<>();
        int grants = 0;

        NodeList inserts = doc.getElementsByTagName("insert");
        for (int i = 0; i < inserts.getLength(); i++) {
            Element insert = (Element) inserts.item(i);
            String id = insert.getAttribute("id");
            if (!id.startsWith("share")) {
                continue;
            }
            grants++;
            String sql = insert.getTextContent();
            if (!ORG_CEILING.matcher(sql).find()) {
                violations.add(id);
            }
        }

        assertTrue(grants >= 3,
            "Only " + grants + " share-grant inserts found in ShareMapper.xml — the scan looks "
                + "misconfigured and this guard would pass vacuously.");
        assertTrue(violations.isEmpty(),
            "These share-grant statements are missing the same-organization ceiling "
                + "(JOIN workspace tw ... tw.org_id = ow.org_id): " + violations);
    }

    private Document loadShareMapper() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mappers/ShareMapper.xml")) {
            assertNotNull(in, "ShareMapper.xml not found on the classpath");
            return builder.parse(in);
        }
    }
}
