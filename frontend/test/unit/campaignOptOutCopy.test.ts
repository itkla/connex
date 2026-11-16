import { createTranslator } from "next-intl";
import { describe, expect, it } from "vitest";
import en from "@/messages/en/campaigns.json";
import ja from "@/messages/ja/campaigns.json";

describe("campaign authors own opt-out instructions", () => {
    it.each(["unsubscribeHint", "smsHint", "supportedTokens"] as const)("requires explicit inclusion in %s in both locales", (key) => {
        const english = createTranslator({ locale: "en", messages: en, namespace: "CampaignMessages" })(key);
        const japanese = createTranslator({ locale: "ja", messages: ja, namespace: "CampaignMessages" })(key);
        expect(english).toContain("{{unsubscribe_url}}");
        expect(english).toMatch(/Include (it|\{\{unsubscribe_url\}\})/);
        expect(english).toContain("does not add");
        expect(english).not.toMatch(/(is added|appended) automatically/);
        expect(japanese).toContain("{{unsubscribe_url}}");
        expect(japanese).toMatch(/入れて|入れる|記載/);
        expect(japanese).toMatch(/自動.*(ありません|されません)/);
    });
});
