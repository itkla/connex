package ooo.klae.connex.backend.delivery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.exceptions.UnsupportedCampaignTokenException;

/** Personalization admitted by campaign authoring and send enrollment, matching dispatch syntax. */
public final class CampaignMessageTokens {
    private static final String UNSUBSCRIBE_URL = "{{unsubscribe_url}}";

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
        StringBuilder text = new StringBuilder();
        Jsoup.parse(html.replace(UNSUBSCRIBE_URL, "connex-unsubscribe-url")).forEachNode(node -> {
            if (node instanceof TextNode textNode) {
                text.append(textNode.getWholeText());
            }
            if (node instanceof Element element) {
                element.attributes().forEach(attribute -> rejectDecodedPlaceholder(attribute.getValue()));
            }
        });
        rejectDecodedPlaceholder(text.toString());
    }

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
