package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.exceptions.UnsupportedCampaignTokenException;

class CampaignMessageTokensTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Plain text {example}", "{{unsubscribe_url}}", "{{unsubscribe_url}}{{unsubscribe_url}}",
            "<a href=\"{{unsubscribe_url}}\">配信停止</a>", "<style>@media print{p{color:blue}}</style>", "}}",
            "{{unsubscribe_url}}}", "<style>@media print{p{--url:{{unsubscribe_url}}}}</style>"})
    void acceptsOnlyLiteralContentAndTheExactSupportedToken(String content) {
        CampaignMessageRevision revision = revision(content);
        assertDoesNotThrow(() -> CampaignMessageTokens.validate(revision));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{first_name}}", "{{company.name}}", "{{ unsubscribe_url }}", "{{UNSUBSCRIBE_URL}}",
            "{{", "{{unsubscribe_url", "{{{unsubscribe_url}}}",
            "{{unsubscribe_url}} {{first_name}}", "{{unsubscribe_url}} {{"})
    void refusesUnknownAndMalformedTokensInEachContentField(String content) {
        for (int field = 0; field < 3; field++) {
            CampaignMessageRevision revision = revision(null);
            switch (field) {
                case 0 -> revision.setSubject(content);
                case 1 -> revision.setBodyHtml(content);
                case 2 -> revision.setBodyText(content);
                default -> throw new IllegalStateException();
            }
            UnsupportedCampaignTokenException error = assertThrows(UnsupportedCampaignTokenException.class,
                    () -> CampaignMessageTokens.validate(revision));
            assertEquals("CAMPAIGN_UNSUPPORTED_TOKEN", error.getCode());
        }
    }

    private static CampaignMessageRevision revision(String content) {
        CampaignMessageRevision revision = new CampaignMessageRevision();
        revision.setSubject(content);
        revision.setBodyHtml(content);
        revision.setBodyText(content);
        return revision;
    }
}
