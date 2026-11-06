import type { PropsWithChildren } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator } from "next-intl";
import { describe, expect, it, vi } from "vitest";
import CampaignRevisionSheet from "@/app/components/marketing/campaigns/CampaignRevisionSheet";
import type { CampaignMessageRevision } from "@/app/lib/types";
import en from "@/messages/en/campaigns.json";
import ja from "@/messages/ja/campaigns.json";

const state = vi.hoisted<{ locale: "en" | "ja" }>(() => ({ locale: "en" }));
vi.mock("next-intl", async (original) => {
    const actual = await original<typeof import("next-intl")>();
    return {
        ...actual,
        useTranslations: () => actual.createTranslator({
            locale: state.locale,
            messages: state.locale === "en" ? en : ja,
            namespace: "CampaignMessages",
        }),
    };
});
vi.mock("@/app/hooks/useIsMobile", () => ({ useIsMobile: () => false }));
vi.mock("@/components/ui/drawer", () => {
    const Part = ({ children }: PropsWithChildren) => <div>{children}</div>;
    return {
        Drawer: ({ children, open }: PropsWithChildren<{ open: boolean }>) => open ? <div>{children}</div> : null,
        DrawerContent: Part, DrawerHeader: Part, DrawerTitle: Part,
        DrawerDescription: Part, DrawerFooter: Part,
    };
});

const revision: CampaignMessageRevision = {
    version: 7, locale: "ja", subject: '保存済み <subject> & "quoted"\n次の行',
    bodyHtml: '</textarea><script>window.executed = true</script>\n<img src="https://preview.invalid/pixel">{{unsubscribe_url}}',
    bodyText: '  原文 & <text>\n{{unsubscribe_url}}  ', createdAt: "2026-11-05T00:00:00Z",
};

describe("saved campaign content inspection", () => {
    it.each(["en", "ja"] as const)("shows exact saved source as escaped read-only text in %s", (locale) => {
        state.locale = locale;
        const html = renderToStaticMarkup(
            <CampaignRevisionSheet revision={revision} isSms={false} onClose={() => {}} />,
        );
        expect(html).toContain('保存済み &lt;subject&gt; &amp; &quot;quoted&quot;\n次の行</textarea>');
        expect(html).toContain('&lt;/textarea&gt;&lt;script&gt;window.executed = true&lt;/script&gt;\n&lt;img');
        expect(html).toContain('  原文 &amp; &lt;text&gt;\n{{unsubscribe_url}}  ');
        expect(html.match(/readOnly=""/g)).toHaveLength(4);
        expect(html).not.toContain("<script");
        expect(html).not.toContain("<img");
        expect(html).not.toContain("<iframe");
        expect(html).not.toContain((locale === "en" ? en : ja).CampaignMessages.startFromRevision);
        const t = createTranslator({ locale, messages: locale === "en" ? en : ja, namespace: "CampaignMessages" });
        expect(t("supportedTokens")).toContain("{{unsubscribe_url}}");
        expect(t("unsupportedTokens")).toContain("{{unsubscribe_url}}");
    });

    it("also inspects SMS with absent email fields and no write action for viewers", () => {
        state.locale = "en";
        const html = renderToStaticMarkup(
            <CampaignRevisionSheet revision={{ ...revision, subject: "", bodyHtml: "" }} isSms onClose={() => {}} />,
        );
        expect(html).toContain('id="saved-revision-text"');
        expect(html).not.toContain('id="saved-revision-subject"');
        expect(html).not.toContain('id="saved-revision-html"');
    });
});
