import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import { NarrationTrail } from "@/app/components/ask-connex/AskConnexDrawer";
import {
    askConnexMessageNarration,
    type AskConnexTurnSegment,
} from "@/app/lib/askConnex";

const LABEL = "Work in progress";

const TRAIL: AskConnexTurnSegment[] = [
    { seq: 1, text: "Let me check this contact." },
    { seq: 2, text: "Got it — **Aiko Tanaka** at Acme. Now their open deals." },
];

function render(segments: readonly AskConnexTurnSegment[]): string {
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
            <NarrationTrail segments={segments} label={LABEL} />
        </NextIntlClientProvider>,
    );
}

describe("the narration trail", () => {
    it("shows every segment in the order the assistant worked", () => {
        const html = render(TRAIL);
        expect(html).toContain("Let me check this contact.");
        expect(html).toContain("Now their open deals.");
        expect(html.indexOf("Let me check this contact."))
            .toBeLessThan(html.indexOf("Now their open deals."));
    });

    it("names the trail so it is not an unlabelled list beside the answer", () => {
        expect(render(TRAIL)).toContain(`aria-label="${LABEL}"`);
    });

    it("renders the assistant's prose as Markdown, like the answer it leads to", () => {
        expect(render(TRAIL)).toContain("<strong>Aiko Tanaka</strong>");
    });

    it("renders nothing at all when the assistant narrated no work", () => {
        expect(render([])).toBe("");
    });

    it("never renders a record chip, live or settled, whatever the text looks like", () => {
        const linkShaped = [
            "Checking [Aiko Tanaka](person:123).",
            "Reading [Acme](company:45) and [Renewal](deal:9).",
            "Pulling [the first result](record:r1).",
        ];
        for (const text of linkShaped) {
            const live = render([{ seq: 1, text }]);
            const settled = render(askConnexMessageNarration({ narration: [{ seq: 1, text }] }));
            for (const html of [live, settled]) {
                expect(html).not.toContain("<a");
                expect(html).not.toContain("/records/");
                expect(html).not.toContain("person:123");
                expect(html).not.toContain("company:45");
                expect(html).not.toContain("record:r1");
            }
            expect(live).toBe(settled);
        }
        expect(render([{ seq: 1, text: "Checking [Aiko Tanaka](person:123)." }]))
            .toContain("Aiko Tanaka");
    });

    it("replays a settled answer's persisted narration through the same trail", () => {
        const html = render(askConnexMessageNarration({
            narration: [
                { seq: 2, text: "Then the deals." },
                { seq: 1, text: "First the contact." },
            ],
        }));
        expect(html.indexOf("First the contact.")).toBeLessThan(html.indexOf("Then the deals."));
    });
});
