package ooo.klae.connex.backend.delivery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;

import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.exceptions.UnsupportedCampaignTokenException;

/** Personalization admitted by campaign authoring and send enrollment, matching dispatch syntax. */
public final class CampaignMessageTokens {
    private static final String UNSUBSCRIBE_URL = "{{unsubscribe_url}}";
    private static final Set<String> OPTIONAL_CONTENT = Set.of(
            "template", "noscript", "title", "datalist", "dialog", "head", "script", "style", "link", "meta", "base", "noframes", "noembed");

    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?(?:\\*/|\\z)", Pattern.DOTALL);
    private static final Pattern CSS_RULE = Pattern.compile("(?:\\A|(?<=[{}]))([^{}]*+)\\{([^{}]*+)}", Pattern.DOTALL);
    private static final Pattern SIMPLE_SELECTOR = Pattern.compile("(?:[a-zA-Z][a-zA-Z0-9_-]*|[.#][a-zA-Z_][a-zA-Z0-9_-]*|\\*)");
    private static final Pattern HIDDEN_DISPLAY = Pattern.compile(
            "(?:\\A|;)\\s*+display\\s*+:\\s*+none\\s*+(?:!\\s*+important)?\\s*+(?=;|\\z)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_DISPLAY = Pattern.compile(
            "(?:\\A|;)\\s*+display\\s*+:\\s*+inline(?:-[a-z]+)?\\s*+(?:!\\s*+important)?\\s*+(?=;|\\z)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL_MARKUP = Pattern.compile(
            "<!--.*?(?:-->|\\z)|<\\s*/?\\s*([a-zA-Z][a-zA-Z0-9:-]*+)(?:\"[^\"]*\"|'[^']*'|[^'\"<>])*+>",
            Pattern.DOTALL);
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
        Element document = Jsoup.parse(html.replace(UNSUBSCRIBE_URL, "connex-unsubscribe-url"));
        CssRules styles = cssRules(document);
        Deque<HtmlStep> steps = new ArrayDeque<>();
        steps.push(new HtmlStep(document, HtmlExit.NONE, false));
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
                trailingBrace = validateConditional(comment.getData(), trailingBrace);
            } else {
                if (node instanceof Element element) {
                    element.attributes().forEach(attribute -> rejectDecodedPlaceholder(attribute.getValue()));
                    boolean optional = element.hasAttr("hidden") || OPTIONAL_CONTENT.contains(element.normalName())
                            || HIDDEN_DISPLAY.matcher(element.attr("style")).find() || matches(styles.hidden(), element)
                            || (!"br".equals(element.normalName()) && (INLINE_DISPLAY.matcher(element.attr("style")).find()
                                    || matches(styles.inline(), element)));
                    if (!optional && (element.isBlock() || "br".equals(element.normalName()))) {
                        trailingBrace = false;
                        steps.push(new HtmlStep(element, HtmlExit.BOUNDARY, false));
                    } else if (optional) {
                        steps.push(new HtmlStep(element, HtmlExit.OPTIONAL, trailingBrace));
                    }
                }
                pushChildren(steps, node);
            }
        }
    }

    private static boolean validateConditional(String data, boolean trailingBrace) {
        var opening = CONDITIONAL_START.matcher(data);
        if (!opening.find()) {
            return trailingBrace;
        }
        String inner = data.substring(opening.end());
        var closing = CONDITIONAL_END.matcher(inner);
        if (closing.find()) {
            inner = inner.substring(0, closing.start());
        }
        rejectDecodedPlaceholder(Parser.unescapeEntities(inner, false));
        String text = Parser.unescapeEntities(CONDITIONAL_MARKUP.matcher(inner).replaceAll(match -> {
            String name = match.group(1);
            return name != null && !OPTIONAL_CONTENT.contains(name.toLowerCase(Locale.ROOT))
                    && ("br".equalsIgnoreCase(name) || Tag.valueOf(name.toLowerCase(Locale.ROOT)).isBlock()) ? "\n" : "";
        }), false);
        rejectDecodedPlaceholder(text);
        if (trailingBrace && text.startsWith("{")) {
            throw new UnsupportedCampaignTokenException();
        }
        return trailingBrace || text.endsWith("{");
    }

    private static CssRules cssRules(Element document) {
        Set<String> hidden = new HashSet<>();
        Set<String> inline = new HashSet<>();
        for (Element style : document.getElementsByTag("style")) {
            var rules = CSS_RULE.matcher(CSS_COMMENT.matcher(style.data()).replaceAll(" "));
            while (rules.find()) {
                for (String selector : rules.group(1).split(",")) {
                    String name = selector.trim();
                    if (SIMPLE_SELECTOR.matcher(name).matches()) {
                        if (Character.isLetter(name.charAt(0))) {
                            name = name.toLowerCase(Locale.ROOT);
                        }
                        if (HIDDEN_DISPLAY.matcher(rules.group(2)).find()) {
                            hidden.add(name);
                        }
                        if (INLINE_DISPLAY.matcher(rules.group(2)).find()) {
                            inline.add(name);
                        }
                    }
                }
            }
        }
        return new CssRules(hidden, inline);
    }

    private static boolean matches(Set<String> selectors, Element element) {
        return selectors.contains("*") || selectors.contains(element.normalName())
                || (!element.id().isEmpty() && selectors.contains("#" + element.id()))
                || element.classNames().stream().anyMatch(name -> selectors.contains("." + name));
    }

    private record CssRules(Set<String> hidden, Set<String> inline) {}

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
