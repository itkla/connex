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
 * The same-org invariant lives in hand-written {@code INSERT..SELECT} statements
 * in {@code ShareMapper.xml}; the workspace-predicate scan cannot see it (it only
 * checks {@code #{workspaceId}} is bound). This test asserts every share-grant
 * insert carries the org-equality join, so a future shareable entity type copied
 * without the ceiling fails the build instead of silently degrading cross-org
 * protection to the service layer alone.
 */
class OrgShareCeilingArchTest {

    private static final Pattern ORG_CEILING = Pattern.compile("tw\\.org_id\\s*=\\s*ow\\.org_id");

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
