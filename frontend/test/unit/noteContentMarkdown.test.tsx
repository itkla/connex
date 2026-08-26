import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import NoteContent from "@/app/components/activity/notes/NoteContent";
import type { NoteReference } from "@/app/lib/types";

function render(content: string, references: NoteReference[] = [], block = true): string {
    return renderToStaticMarkup(
        <NextIntlClientProvider
            locale="en"
            messages={{
                ActivityNotesEditor: {
                    taskChecked: "Completed checklist item",
                    taskUnchecked: "Incomplete checklist item",
                    unavailableReference: "Unavailable reference",
                    calloutInfo: "Information callout",
                    calloutSuccess: "Success callout",
                    calloutWarning: "Warning callout",
                    calloutDanger: "Danger callout",
                },
            }}
        >
            <NoteContent content={content} references={references} block={block} />
        </NextIntlClientProvider>,
    );
}

describe("NoteContent Markdown rendering", () => {
    it("renders GFM structure without raw HTML", () => {
        const html = render([
            "## Status",
            "",
            "**Ready** and ~~stale~~",
            "",
            "- [x] Approved",
            "",
            "| Owner | State |",
            "| --- | --- |",
            "| Mina | Ready |",
            "",
            "<script>alert('unsafe')</script>",
        ].join("\n"));

        expect(html).toContain("<h2>Status</h2>");
        expect(html).toContain("<strong>Ready</strong>");
        expect(html).toContain("<del>stale</del>");
        expect(html).toContain('type="checkbox"');
        expect(html).toContain('aria-label="Completed checklist item"');
        expect(html).toContain('class="contains-task-list"');
        expect(html).toContain('class="task-list-item"');
        expect(html).toContain("<table>");
        expect(html).not.toContain("<script");
        expect(html).not.toContain("alert");
    });

    it("renders authorized references and masks unresolved targets", () => {
        const references: NoteReference[] = [{ type: "company", id: 7, label: "Server Acme" }];
        const html = render(
            "[Forged](company:7) [Malformed](company:7.0) [Private](note:9) [Missing member](user:11)",
            references,
        );

        expect(html).toContain('href="/records/companies/7"');
        expect(html).toContain("Server Acme");
        expect(html).not.toContain("Forged");
        expect(html).toContain("Malformed");
        expect(html.match(/records\/companies\/7/g)).toHaveLength(1);
        expect(html.match(/Unavailable reference/g)).toHaveLength(2);
        expect(html).not.toContain("Private");
        expect(html).not.toContain("Missing member");
        expect(html).not.toContain("note:9");
        expect(html).not.toContain("/activity/notes/9");
        expect(html).not.toContain("/users/11");
    });

    it("resolves references inside rich structure without nesting interactive links", () => {
        const references: NoteReference[] = [{ type: "company", id: 7, label: "Server Acme" }];
        const html = render([
            "**[Forged](company:7)**",
            "",
            "| Account |",
            "| --- |",
            "| [Forged again](company:7) |",
        ].join("\n"), references);

        expect(html).toContain("<strong>");
        expect(html).toContain("<table>");
        expect(html.match(/href="\/records\/companies\/7"/g)).toHaveLength(2);
        expect(html.match(/<a /g)).toHaveLength(2);
        expect(html).not.toContain("Forged");
        expect(html.match(/Server Acme/g)).toHaveLength(2);
    });

    it("masks unresolved reference definitions without exposing their frozen labels", () => {
        const html = render("[Private account][hidden]\n\n[hidden]: note:9");

        expect(html).toContain("Unavailable reference");
        expect(html).not.toContain("Private account");
        expect(html).not.toContain("note:9");
    });

    it("rejects executable links and unsafe images", () => {
        const html = render([
            "[Unsafe](javascript:alert(1))",
            "",
            "![Unsafe](data:image/svg+xml;base64,AAAA)",
            "",
            "![Insecure](http://example.com/image.png)",
            "",
            "[Protocol relative](//example.com/private)",
            "",
            "![Protocol relative](//example.com/image.png)",
            "",
            "![No description](https://example.com/image.png)",
        ].join("\n"));

        expect(html).not.toContain("javascript:");
        expect(html).not.toContain("data:image");
        expect(html).not.toContain("http://example.com");
        expect(html).not.toContain('href="//example.com/private"');
        expect(html).not.toContain('src="//example.com/image.png"');
        expect(html).toContain('src="https://example.com/image.png"');
        expect(html).toContain('alt="No description"');
    });

    it("preserves callout and toggle semantics without exposing markers", () => {
        const html = render([
            "> [!warn]",
            ">",
            "> Check this first.",
            "",
            "> [!toggle]",
            ">",
            "> Details",
            ">",
            "> Hidden context",
        ].join("\n"));

        expect(html).toContain('data-callout="warn"');
        expect(html).toContain("Warning callout");
        expect(html).toContain("note-callout-symbol");
        expect(html).toContain("Check this first.");
        expect(html).toContain("<details");
        expect(html).toContain("<summary");
        expect(html).toContain("Details");
        expect(html).not.toContain("[!warn]");
        expect(html).not.toContain("[!toggle]");
    });

    it("keeps compact consumers inline", () => {
        const html = render("## **Important**\n\n- First\n- Second", [], false);
        expect(html.startsWith("<span>")).toBe(true);
        expect(html).toContain("<strong>");
        expect(html).not.toContain("<h2>");
        expect(html).not.toContain("<ul>");
        expect(html).not.toContain("<li>");
    });
});
