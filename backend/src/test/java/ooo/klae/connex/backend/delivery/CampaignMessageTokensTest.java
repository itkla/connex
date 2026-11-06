package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "<p>&#123;&#123;first_name&#125;&#125;</p>",
            "<p>&#x7b;&#x7b;first_name&#x7d;&#x7d;</p>",
            "<p>&lbrace;&lcub;first_name&rcub;&rbrace;</p>",
            "<p>{<span>{first_name}</span>}</p>",
            "<p>{<!-- hidden -->{first_name}}</p>",
            "<a title='&#123;&#123;first_name&#125;&#125;'>Hello</a>",
            "<img alt='&lbrace;&lbrace;first_name&rbrace;&rbrace;'>",
            "<a href='https://example.invalid/&#123;&#123;first_name&#125;&#125;'>Hello</a>",
            "<p>&#123;&#123;unsubscribe_url&#125;&#125;</p>",
            "<p>{<span>{unsubscribe_url}</span>}</p>",
            "<a title='&#123;&#123;unsubscribe_url&#125;&#125;'>Hello</a>"})
    void refusesTokensVisibleAfterHtmlDecodingAndAcrossInlineElements(String html) {
        CampaignMessageRevision revision = revision(null);
        revision.setBodyHtml(html);
        assertThrows(UnsupportedCampaignTokenException.class, () -> CampaignMessageTokens.validate(revision));
        assertEquals(html, revision.getBodyHtml());
    }

    @Test
    void validatesHtmlWithoutRewritingSourceOrDoubleDecodingLiteralText() {
        String html = "<p title='Company &amp; team'>配信停止 <a href='{{unsubscribe_url}}'>こちら</a></p>"
                + "<p>&amp;#123;&amp;#123;first_name&amp;#125;&amp;#125;</p>";
        CampaignMessageRevision revision = revision(null);
        revision.setSubject("Literal &#123;&#123;first_name&#125;&#125;");
        revision.setBodyText("Literal &#123;&#123;first_name&#125;&#125;");
        revision.setBodyHtml(html);
        assertDoesNotThrow(() -> CampaignMessageTokens.validate(revision));
        assertEquals(html, revision.getBodyHtml());
    }
}
