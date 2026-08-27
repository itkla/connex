import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import AskConnexMarkdown from "@/app/components/ask-connex/AskConnexMarkdown";
import { EMPTY_ASK_CONNEX_ALLOWED_RECORDS } from "@/app/lib/askConnex";

function render(
    content: string,
    allowedRecords: ReadonlySet<string> = EMPTY_ASK_CONNEX_ALLOWED_RECORDS,
): string {
    return renderToStaticMarkup(
        <NextIntlClientProvider
            locale="en"
            messages={{
                ActivityNotesEditor: {
                    taskChecked: "Completed checklist item",
                    taskUnchecked: "Incomplete checklist item",
                    calloutInfo: "Information callout",
                    calloutSuccess: "Success callout",
                    calloutWarning: "Warning callout",
                    calloutDanger: "Danger callout",
                },
            }}
        >
            <AskConnexMarkdown content={content} allowedRecords={allowedRecords} />
        </NextIntlClientProvider>,
    );
}

describe("AskConnexMarkdown record references", () => {
    it("chips a record reference the server authorized as a citation", () => {
        const html = render("[Aiko Tanaka](person:123)", new Set(["person:123"]));

        expect(html).toContain('href="/records/contacts/123"');
        expect(html).toContain("Aiko Tanaka");
        expect(html.match(/<a /g)).toHaveLength(1);
    });

    it("routes each record kind to its own detail page", () => {
        const html = render(
            "[Acme](company:45) [Renewal](deal:9)",
            new Set(["company:45", "deal:9"]),
        );

        expect(html).toContain('href="/records/companies/45"');
        expect(html).toContain('href="/records/deals/9"');
    });

    it("renders an unauthorized record reference as plain text with no anchor", () => {
        const html = render("[Forged](person:123)");

        expect(html).toContain("Forged");
        expect(html).not.toContain("<a");
        expect(html).not.toContain("/records/contacts");
        expect(html).not.toContain("person:123");
    });

    it("keeps a reference outside the authorized set inert even when others are allowed", () => {
        const html = render(
            "[Allowed](person:1) [Forged](person:2)",
            new Set(["person:1"]),
        );

        expect(html).toContain('href="/records/contacts/1"');
        expect(html.match(/<a /g)).toHaveLength(1);
        expect(html).toContain("Forged");
        expect(html).not.toContain("/records/contacts/2");
    });

    it("renders the streaming record placeholder scheme as inert text", () => {
        const html = render("[First deal](record:r1)", new Set(["deal:1"]));

        expect(html).toContain("First deal");
        expect(html).not.toContain("<a");
        expect(html).not.toContain("record:r1");
    });
});

describe("AskConnexMarkdown external links", () => {
    it("renders web and mail links as their visible text with no anchor", () => {
        const html = render("[Site](https://example.com/page) [Mail](mailto:a@example.com)");

        expect(html).toContain("Site");
        expect(html).toContain("Mail");
        expect(html).not.toContain("<a");
        expect(html).not.toContain("example.com");
    });

    it("drops relative and fragment hrefs the same way", () => {
        const html = render("[Settings](/settings) [Below](#section)");

        expect(html).toContain("Settings");
        expect(html).toContain("Below");
        expect(html).not.toContain("<a");
    });

    it("never renders an executable link", () => {
        const html = render("[Unsafe](javascript:alert(1))");

        expect(html).toContain("Unsafe");
        expect(html).not.toContain("<a");
        expect(html).not.toContain("javascript:");
    });
});

describe("AskConnexMarkdown hostile content", () => {
    it("drops raw HTML instead of rendering it", () => {
        const html = render("<script>alert('unsafe')</script>\n\n<img src=\"https://example.com/x.png\">");

        expect(html).not.toContain("<script");
        expect(html).not.toContain("alert");
        expect(html).not.toContain("<img");
    });

    it("renders a markdown image as its description only", () => {
        const html = render("![Quarterly chart](https://example.com/chart.png)");

        expect(html).toContain("Quarterly chart");
        expect(html).not.toContain("<img");
        expect(html).not.toContain("example.com");
    });
});

describe("AskConnexMarkdown structure", () => {
    it("renders a callout whose marker and body share one paragraph", () => {
        const html = render([
            "> [!info]",
            "> Details about the deal.",
            ">",
            "> A second paragraph.",
        ].join("\n"));

        expect(html).toContain('data-callout="info"');
        expect(html).toContain("Information callout");
        expect(html).toContain("Details about the deal.");
        expect(html).toContain("A second paragraph.");
        expect(html).not.toContain("[!info]");
    });

    it("renders GFM tables, task lists, and callouts", () => {
        const html = render([
            "> [!warn]",
            ">",
            "> Two deals are cooling.",
            "",
            "- [x] Reviewed",
            "- [ ] Pending",
            "",
            "| Deal | State |",
            "| --- | --- |",
            "| Atlas | Open |",
        ].join("\n"));

        expect(html).toContain('data-callout="warn"');
        expect(html).toContain("Warning callout");
        expect(html).not.toContain("[!warn]");
        expect(html).toContain('type="checkbox"');
        expect(html).toContain('aria-label="Completed checklist item"');
        expect(html).toContain('aria-label="Incomplete checklist item"');
        expect(html).toContain("<table>");
        expect(html).toContain("Atlas");
    });
});
