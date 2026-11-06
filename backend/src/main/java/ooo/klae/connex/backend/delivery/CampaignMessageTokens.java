package ooo.klae.connex.backend.delivery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.exceptions.UnsupportedCampaignTokenException;

/** Personalization admitted by campaign authoring and send enrollment, matching dispatch syntax. */
public final class CampaignMessageTokens {
    private static final String UNSUBSCRIBE_URL = "{{unsubscribe_url}}";
    private static final Set<String> OPTIONAL_CONTENT = Set.of(
            "template", "noscript", "title", "datalist", "dialog", "head", "script", "meta", "base", "noframes", "noembed");

    private static final Pattern CSS_MARKUP = Pattern.compile("<(?:style|link)(?=[\\s/>])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_START = Pattern.compile("^\\s*\\[if\\b[^\\]]*\\]>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_END = Pattern.compile("<!\\[endif\\]\\s*$", Pattern.CASE_INSENSITIVE);

    private CampaignMessageTokens() {}

    /** Rejects unknown or incomplete placeholders beginning with {{ in every persisted content field. */
    public static void validate(CampaignMessageRevision revision) {
        validateField(revision.getSubject());
        validateField(revision.getBodyHtml());
        validateHtml(revision.getBodyHtml());
        validateField(revision.getBodyText());
    }

    private static void validateHtml(String html) {
        if (html == null) {
            return;
        }
        long parsedCharacters = html.length();
        long parseBudget = Math.max(1L, html.length()) * 8;
        boolean styledLayout = CSS_MARKUP.matcher(html).find();
        Deque<HtmlStep> steps = new ArrayDeque<>();
        steps.push(new HtmlStep(Jsoup.parse(html.replace(UNSUBSCRIBE_URL, "connex-unsubscribe-url")), HtmlExit.NONE, false));
        boolean trailingBrace = false;
        while (!steps.isEmpty()) {
            HtmlStep step = steps.pop();
            Node node = step.node();
            if (step.exit() != HtmlExit.NONE) {
                trailingBrace = step.exit() == HtmlExit.OPTIONAL && (trailingBrace || step.skippedBrace());
            } else if (node instanceof TextNode textNode) {
                String text = textNode.getWholeText();
                rejectDecodedPlaceholder(text);
                if (!text.isEmpty()) {
                    if (trailingBrace && text.startsWith("{")) {
                        throw new UnsupportedCampaignTokenException();
                    }
                    trailingBrace = text.endsWith("{");
                }
            } else if (node instanceof Comment comment) {
                String data = comment.getData();
                var opening = CONDITIONAL_START.matcher(data);
                if (opening.find()) {
                    String inner = data.substring(opening.end());
                    var closing = CONDITIONAL_END.matcher(inner);
                    if (closing.find()) {
                        inner = inner.substring(0, closing.start());
                    }
                    parsedCharacters += inner.length();
                    if (parsedCharacters > parseBudget) {
                        throw new UnsupportedCampaignTokenException();
                    }
                    steps.push(new HtmlStep(comment, HtmlExit.OPTIONAL, trailingBrace));
                    pushChildren(steps, Jsoup.parseBodyFragment(inner).body());
                }
            } else {
                if (node instanceof Element element) {
                    element.attributes().forEach(attribute -> rejectDecodedPlaceholder(attribute.getValue()));
                    if (styledLayout || !element.attr("style").isBlank() || element.hasAttr("hidden")
                            || OPTIONAL_CONTENT.contains(element.normalName())) {
                        steps.push(new HtmlStep(element, HtmlExit.OPTIONAL, trailingBrace));
                    } else if (element.isBlock() || "br".equals(element.normalName())) {
                        trailingBrace = false;
                        steps.push(new HtmlStep(element, HtmlExit.BOUNDARY, false));
                    }
                }
                pushChildren(steps, node);
            }
        }
    }

    private static void pushChildren(Deque<HtmlStep> steps, Node node) {
        for (int index = node.childNodeSize() - 1; index >= 0; index--) {
            steps.push(new HtmlStep(node.childNode(index), HtmlExit.NONE, false));
        }
    }

    private enum HtmlExit { NONE, BOUNDARY, OPTIONAL }

    private record HtmlStep(Node node, HtmlExit exit, boolean skippedBrace) {}

    private static void rejectDecodedPlaceholder(String content) {
        if (content.contains("{{")) {
            throw new UnsupportedCampaignTokenException();
        }
    }

    private static void validateField(String content) {
        if (content == null) {
            return;
        }
        int offset = 0;
        while (offset < content.length()) {
            int opening = content.indexOf("{{", offset);
            if (opening < 0) {
                return;
            }
            if (!content.startsWith(UNSUBSCRIBE_URL, opening)) {
                throw new UnsupportedCampaignTokenException();
            }
            offset = opening + UNSUBSCRIBE_URL.length();
        }
    }
}
