package ooo.klae.connex.backend.delivery;

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
        validateField(revision.getBodyText());
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
