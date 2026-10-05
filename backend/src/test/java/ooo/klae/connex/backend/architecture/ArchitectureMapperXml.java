package ooo.klae.connex.backend.architecture;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Shared mapper parsing mechanics; callers retain their own SQL evidence and classification rules. */
final class ArchitectureMapperXml {
    private static final String INCLUDE_MARK = String.valueOf((char) 1);
    private static final Pattern INCLUDE_REF = Pattern.compile(
        Pattern.quote(INCLUDE_MARK) + "([^" + Pattern.quote(INCLUDE_MARK) + "]*)"
            + Pattern.quote(INCLUDE_MARK));
    private static final Pattern DOCTYPE = Pattern.compile("(?s)<!DOCTYPE.*?>");

    private ArchitectureMapperXml() {
    }

    static Parsed parse(String xml) throws Exception {
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
        List<Element> statements = new ArrayList<>();
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
            } else if (Set.of("select", "insert", "update", "delete").contains(element.getTagName())) {
                statements.add(element);
            }
        }
        return new Parsed(document.getDocumentElement().getAttribute("namespace"),
            java.util.Collections.unmodifiableMap(fragments), Map.copyOf(fragmentElements), List.copyOf(statements));
    }

    static String collectSql(Element element) {
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

    static String resolve(String sql, Map<String, String> fragments, int depth) {
        if (depth > 16 || !sql.contains(INCLUDE_MARK)) {
            return sql;
        }
        Matcher matcher = INCLUDE_REF.matcher(sql);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String body = fragments.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(
                resolved, Matcher.quoteReplacement(" " + resolve(body, fragments, depth + 1) + " "));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    record Parsed(
            String namespace,
            Map<String, String> fragments,
            Map<String, Element> fragmentElements,
            List<Element> statements) {
    }
}
