import { act, type AnchorHTMLAttributes, type PropsWithChildren } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import AskConnexAnswerBlock from "@/app/components/ask-connex/AskConnexAnswerBlocks";
import AskConnexAnswerDocument, {
    AskConnexCheckedTrail,
    AskConnexCoverageDisclosure,
} from "@/app/components/ask-connex/AskConnexAnswerDocument";
import {
    AskConnexEvidenceDetail,
    AskConnexEvidencePeekBody,
    AskConnexEvidenceRow,
    AskConnexUnsupportedEvidence,
} from "@/app/components/ask-connex/AskConnexEvidence";
import {
    answerRows,
    evidenceCaveats,
    isStructuredBlockKind,
    isUnsupportedBlock,
    type AskConnexAnswerDocumentLabels,
} from "@/app/components/ask-connex/answerDocument";
import type {
    AiChatAnswerBlock,
    AiChatAnswerBlockKind,
    AiChatAnswerRow,
    AiChatCitation,
    AiChatCoverage,
} from "@/app/lib/types";
import { installInteractiveDocument } from "@/test/unit/helpers/interactiveDocument";

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

const labels: AskConnexAnswerDocumentLabels = {
    absoluteTime: (instant) => `abs(${instant})`,
    blockKind: (kind) => `kind:${kind}`,
    citationKind: (kind) => `citationKind:${kind}`,
    comparisonAgainst: "Compared with",
    comparisonValue: "Value",
    copyDraft: "Copy",
    copyDraftDone: "Copied",
    coverage: "Coverage",
    coverageStatus: (status) => `coverageStatus:${status}`,
    diffAfter: "After",
    diffBefore: "Before",
    dismiss: "Close",
    evidence: "Evidence",
    evidenceDetail: "Excerpt",
    exclusions: "Not included",
    exclusion: (exclusion) => `exclusion:${exclusion}`,
    freshness: "Freshness",
    freshnessCurrent: "Record updated",
    moreDetail: "More detail",
    openRecord: "Open record",
    period: (start, end) => `period ${start} to ${end}`,
    progressCount: (count) => `(${count} items)`,
    progressSource: (source) => `progressSource:${source}`,
    progressStatus: (status) => `progressStatus:${status}`,
    relativeTime: (instant) => `rel(${instant})`,
    sourceLimits: "Source limits",
    sources: "Sources checked",
    source: (source) => `source:${source}`,
    truncated: "Results were bounded",
    unsupported: "No source for this — read it as unconfirmed.",
    whatChecked: "What I checked",
};

function citation(overrides: Partial<AiChatCitation> = {}): AiChatCitation {
    return {
        handle: "h1",
        kind: "person",
        id: 42,
        label: "Aiko Tanaka",
        asOf: "2026-08-01T09:00:00Z",
        detail: "Met at the Osaka review",
        observed: true,
        ...overrides,
    };
}

function row(overrides: Partial<AiChatAnswerRow> = {}): AiChatAnswerRow {
    return {
        label: "Row label",
        value: "Row value",
        detail: "Row detail",
        at: null,
        evidence: [],
        ...overrides,
    };
}

function block(
    kind: AiChatAnswerBlockKind,
    overrides: Partial<AiChatAnswerBlock> = {},
): AiChatAnswerBlock {
    return {
        kind,
        title: null,
        body: null,
        items: [],
        rows: [],
        evidence: [],
        ...overrides,
    };
}

function coverage(overrides: Partial<AiChatCoverage> = {}): AiChatCoverage {
    return {
        status: "complete",
        asOf: null,
        periodStart: null,
        periodEnd: null,
        sources: [],
        exclusions: [],
        truncated: false,
        ...overrides,
    };
}

function renderBlock(target: AiChatAnswerBlock, caveats: string[] = []): string {
    return renderToStaticMarkup(
        <AskConnexAnswerBlock block={target} caveats={caveats} labels={labels} />,
    );
}

describe("answer-document block classification", () => {
    it("treats only the row-bearing kinds as structured", () => {
        const structured: AiChatAnswerBlockKind[] = [
            "metric",
            "comparison",
            "timeline",
            "diff",
            "extraction",
        ];
        const prose: AiChatAnswerBlockKind[] = [
            "answer",
            "fact",
            "inference",
            "recommendation",
            "list",
            "draft",
            "limitation",
        ];
        for (const kind of structured) expect(isStructuredBlockKind(kind)).toBe(true);
        for (const kind of prose) expect(isStructuredBlockKind(kind)).toBe(false);
    });

    it("marks every kind that asserts workspace data as unsupported when nothing backs it", () => {
        const asserting: AiChatAnswerBlockKind[] = [
            "answer",
            "fact",
            "metric",
            "comparison",
            "timeline",
            "extraction",
            "diff",
        ];
        for (const kind of asserting) expect(isUnsupportedBlock(block(kind))).toBe(true);
    });

    it("never marks a kind that claims nothing of its own", () => {
        const claiming: AiChatAnswerBlockKind[] = [
            "inference",
            "recommendation",
            "list",
            "draft",
            "limitation",
        ];
        for (const kind of claiming) expect(isUnsupportedBlock(block(kind))).toBe(false);
    });

    it("accepts row-level citations as support for the whole block", () => {
        const supported = block("metric", {
            rows: [row(), row({ evidence: [citation()] })],
        });
        expect(isUnsupportedBlock(supported)).toBe(false);
        expect(isUnsupportedBlock(block("fact", { evidence: [citation()] }))).toBe(false);
    });

    it("reads a row field the payload left out exactly as it reads a declared empty one", () => {
        const declared = block("metric", { rows: [row({ value: null, detail: null, at: null })] });
        const omitted = block("metric", { rows: [row()] });
        const only = omitted.rows[0];
        if (only === undefined) throw new Error("expected one row");
        for (const field of ["value", "detail", "at"]) Reflect.deleteProperty(only, field);

        expect(renderBlock(omitted)).toBe(renderBlock(declared));
        expect(renderBlock(omitted)).toContain("—");
    });

    it("tolerates a payload that omitted the rows field entirely", () => {
        const legacy = block("metric", { body: "12 open deals" });
        Reflect.deleteProperty(legacy, "rows");
        expect(answerRows(legacy)).toEqual([]);
        expect(() => renderBlock(legacy)).not.toThrow();
        expect(renderBlock(legacy)).toContain("12 open deals");
    });
});

describe("epistemic block rendering", () => {
    it("renders the answer block as the lede with no kind marker", () => {
        const html = renderBlock(block("answer", {
            body: "Three deals are cooling.",
            evidence: [citation()],
        }));
        expect(html).toContain("Three deals are cooling.");
        expect(html).not.toContain("kind:answer");
    });

    it("marks a fact with its word and its own icon", () => {
        const html = renderBlock(block("fact", {
            body: "Aiko replied on 1 August.",
            evidence: [citation()],
        }));
        expect(html).toContain("kind:fact");
        expect(html).toContain("Aiko replied on 1 August.");
        expect(html).toContain("Aiko Tanaka");
        expect(html).not.toContain(labels.unsupported);
    });

    it("recesses an inference into its own surface rather than tinting it", () => {
        const html = renderBlock(block("inference", { body: "The deal is probably stalling." }));
        expect(html).toContain("kind:inference");
        expect(html).toContain("bg-muted/70");
        expect(html).not.toContain("chart-");
        expect(html).not.toContain("warmth-");
    });

    it("renders a recommendation as an ordered list of proposed steps", () => {
        const html = renderBlock(block("recommendation", {
            body: "Re-engage this week.",
            items: ["Send a follow-up", "Book a review"],
        }));
        expect(html).toContain("kind:recommendation");
        expect(html).toContain("<ol");
        expect(html).toContain("list-decimal");
        expect(html).toContain("Send a follow-up");
    });

    it("renders a limitation inside a dashed outline", () => {
        const html = renderBlock(block("limitation", { body: "Calendar is not connected." }));
        expect(html).toContain("kind:limitation");
        expect(html).toContain("border-dashed");
        expect(html).toContain("Calendar is not connected.");
    });

    it("distinguishes fact, inference, and recommendation by more than one channel", () => {
        const fact = renderBlock(block("fact", { body: "b", evidence: [citation()] }));
        const inference = renderBlock(block("inference", { body: "b" }));
        const recommendation = renderBlock(block("recommendation", { body: "b", items: ["x"] }));
        const paths = (html: string) => html.match(/<path[^>]*d="([^"]+)"/g) ?? [];
        expect(paths(fact)).not.toEqual(paths(inference));
        expect(paths(inference)).not.toEqual(paths(recommendation));
        expect(fact).toContain("kind:fact");
        expect(inference).toContain("kind:inference");
        expect(recommendation).toContain("kind:recommendation");
    });
});

describe("structured block rendering", () => {
    it("renders metrics as value tiles instead of prose", () => {
        const html = renderBlock(block("metric", {
            rows: [
                row({ label: "Open deals", value: "12", detail: "+3 vs last month" }),
                row({ label: "At risk", value: "4", detail: null }),
            ],
            evidence: [citation()],
        }));
        expect(html).toContain("Open deals");
        expect(html).toContain(">12<");
        expect(html).toContain("+3 vs last month");
        expect(html).toContain("tabular-nums");
        expect(html).toContain("At risk");
        expect(html).not.toContain("| Open deals |");
    });

    it("renders a comparison with both sides and screen-reader side captions", () => {
        const html = renderBlock(block("comparison", {
            rows: [row({ label: "Win rate", value: "38%", detail: "31%", evidence: [citation()] })],
        }));
        expect(html).toContain("Win rate");
        expect(html).toContain("38%");
        expect(html).toContain("31%");
        expect(html).toContain("Value");
        expect(html).toContain("Compared with");
        expect(html).toContain("sr-only");
        expect(html).toContain("<dl");
    });

    it("renders a timeline with machine-readable, relative, and absolute freshness", () => {
        const html = renderBlock(block("timeline", {
            rows: [
                row({ label: "Call logged", value: "30 minutes", at: "2026-08-10T02:00:00Z" }),
                row({ label: "Email sent", value: null, at: "2026-08-01T02:00:00Z" }),
            ],
            evidence: [citation()],
        }));
        expect(html).toContain("<ol");
        expect(html.toLowerCase()).toContain('datetime="2026-08-10t02:00:00z"');
        expect(html).toContain("rel(2026-08-10T02:00:00Z)");
        expect(html).toContain("abs(2026-08-10T02:00:00Z)");
        expect(html.indexOf("Call logged")).toBeLessThan(html.indexOf("Email sent"));
    });

    it("drops a timeline timestamp that is model prose rather than echoing it", () => {
        const html = renderBlock(block("timeline", {
            rows: [row({ label: "Call logged", value: "Held", at: "shortly after the diagnosis" })],
            evidence: [citation()],
        }));
        expect(html).toContain("Call logged");
        expect(html).not.toContain("diagnosis");
        expect(html).not.toContain("<time");
    });

    it("renders a diff with words and strike-through, not colour alone", () => {
        const html = renderBlock(block("diff", {
            rows: [row({ label: "Stage", value: "Proposal", detail: "Negotiation" })],
        }));
        expect(html).toContain("Before");
        expect(html).toContain("After");
        expect(html).toContain("line-through");
        expect(html).toContain("Proposal");
        expect(html).toContain("Negotiation");
    });

    it("renders extracted fields as definition pairs", () => {
        const html = renderBlock(block("extraction", {
            rows: [row({ label: "Renewal date", value: "2026-11-30", detail: null })],
        }));
        expect(html).toContain("<dl");
        expect(html).toContain("<dt");
        expect(html).toContain("Renewal date");
        expect(html).toContain("2026-11-30");
    });

    it("shows a placeholder rather than an empty cell for a value the server could not establish", () => {
        const html = renderBlock(block("metric", { rows: [row({ value: null, detail: null })] }));
        expect(html).toContain("—");
    });

    it("renders a list block as a real list", () => {
        const html = renderBlock(block("list", { items: ["Acme", "Globex"] }));
        expect(html).toContain("<ul");
        expect(html).toContain("list-disc");
        expect(html).toContain("Acme");
        expect(html).toContain("Globex");
    });

    it("renders a draft with a copy affordance", () => {
        const html = renderBlock(block("draft", {
            title: "Follow-up email",
            body: "Hello Aiko,\n\nThanks for the review.",
        }));
        expect(html).toContain("Follow-up email");
        expect(html).toContain("Thanks for the review.");
        expect(html).toContain("Copy");
        expect(html).toContain("<button");
    });
});

describe("per-claim evidence honesty", () => {
    it("marks an unsourced fact as unconfirmed instead of rendering it silently", () => {
        const html = renderBlock(block("fact", { body: "Revenue doubled." }));
        expect(html).toContain(labels.unsupported);
    });

    it("marks an unsourced metric, comparison, timeline, extraction, and change", () => {
        for (const kind of ["metric", "comparison", "timeline", "extraction", "diff"] as const) {
            const html = renderBlock(block(kind, { rows: [row()] }));
            expect(html).toContain(labels.unsupported);
        }
    });

    it("marks the whole answer when a reply with no blocks cited nothing", () => {
        const html = renderBlock(block("answer", { body: "Revenue doubled." }));
        expect(html).toContain("Revenue doubled.");
        expect(html).toContain(labels.unsupported);
        expect(renderBlock(block("answer", {
            body: "Revenue doubled.",
            evidence: [citation()],
        }))).not.toContain(labels.unsupported);
    });

    it("leaves an evidenced block unmarked", () => {
        const html = renderBlock(block("timeline", {
            rows: [row({ at: "2026-08-01T00:00:00Z", evidence: [citation()] })],
        }));
        expect(html).not.toContain(labels.unsupported);
    });

    it("never marks an inference or a recommendation as unsupported", () => {
        expect(renderBlock(block("inference", { body: "b" }))).not.toContain(labels.unsupported);
        expect(renderBlock(block("recommendation", { body: "b" }))).not.toContain(labels.unsupported);
    });

    it("renders the unsupported marker with an icon and a dashed outline", () => {
        const html = renderToStaticMarkup(<AskConnexUnsupportedEvidence labels={labels} />);
        expect(html).toContain("border-dashed");
        expect(html).toContain("<svg");
        expect(html).toContain(labels.unsupported);
    });
});

describe("evidence escalation surfaces", () => {
    it("renders every source marker as a keyboard-operable button naming its record", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidenceRow
                evidence={[citation(), citation({ handle: "h2", id: 7, kind: "deal", label: "Renewal" })]}
                caveats={[]}
                labels={labels}
            />,
        );
        expect(html).toContain('type="button"');
        expect(html).toContain("focus-visible:ring");
        expect(html).toContain("Aiko Tanaka");
        expect(html).toContain("Renewal");
        expect(html).toContain('aria-label="Evidence"');
    });

    it("renders nothing when a block carries no citations", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidenceRow evidence={[]} caveats={[]} labels={labels} />,
        );
        expect(html).toBe("");
    });

    it("falls back to the record kind when no display name was projected", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidenceRow
                evidence={[citation({ label: null })]}
                caveats={[]}
                labels={labels}
            />,
        );
        expect(html).toContain("citationKind:person");
    });

    it("offers both the deeper inspector and the record from the peek", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidencePeekBody
                citation={citation()}
                labels={labels}
                onEscalate={() => {}}
                onNavigate={() => {}}
            />,
        );
        expect(html).toContain("Aiko Tanaka");
        expect(html).toContain("citationKind:person");
        expect(html).toContain("Met at the Osaka review");
        expect(html).toContain("rel(2026-08-01T09:00:00Z)");
        expect(html).toContain("More detail");
        expect(html).toContain("Open record");
        expect(html).toContain('href="/records/contacts/42"');
    });

    it("omits the excerpt and freshness the server did not project", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidencePeekBody
                citation={citation({ asOf: null, detail: null })}
                labels={labels}
                onEscalate={() => {}}
                onNavigate={() => {}}
            />,
        );
        expect(html).not.toContain("Freshness");
        expect(html).toContain("Open record");
    });

    it("shows excerpt, freshness, and the answer's source limits in the inspector", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidenceDetail
                citation={citation()}
                caveats={["Results were bounded", "exclusion:restricted_records"]}
                labels={labels}
            />,
        );
        expect(html).toContain("Excerpt");
        expect(html).toContain("Met at the Osaka review");
        expect(html).toContain("Freshness");
        expect(html).toContain("rel(2026-08-01T09:00:00Z)");
        expect(html).toContain("abs(2026-08-01T09:00:00Z)");
        expect(html).toContain("Source limits");
        expect(html).toContain("exclusion:restricted_records");
    });

    it("says the freshness is unknown rather than hiding it", () => {
        const html = renderToStaticMarkup(
            <AskConnexEvidenceDetail citation={citation({ asOf: null })} caveats={[]} labels={labels} />,
        );
        expect(html).toContain("Freshness");
        expect(html).toContain("—");
        expect(html).not.toContain("Source limits");
    });

    it("files a snapshot under the answer's freshness and a live read under the record's", () => {
        const snapshot = renderToStaticMarkup(
            <AskConnexEvidenceDetail citation={citation()} caveats={[]} labels={labels} />,
        );
        const live = renderToStaticMarkup(
            <AskConnexEvidenceDetail
                citation={citation({ observed: false })}
                caveats={[]}
                labels={labels}
            />,
        );

        expect(snapshot).toContain("Freshness");
        expect(snapshot).not.toContain("Record updated");
        expect(live).toContain("Record updated");
    });

    it("derives inspector caveats from the answer's own coverage", () => {
        expect(evidenceCaveats(coverage(), labels)).toEqual([]);
        expect(evidenceCaveats(
            coverage({
                truncated: true,
                exclusions: ["restricted_records", "unsupported_context", "tool_failure"],
            }),
            labels,
        )).toEqual([
            "Results were bounded",
            "exclusion:restricted_records",
            "exclusion:tool_failure",
        ]);
    });
});

describe("coverage disclosure", () => {
    it("keeps status, truncation, and freshness visible without expanding", () => {
        const html = renderToStaticMarkup(
            <AskConnexCoverageDisclosure
                coverage={coverage({
                    status: "partial",
                    truncated: true,
                    asOf: "2026-08-10T00:00:00Z",
                    sources: ["records", "activities"],
                    exclusions: ["private_data"],
                })}
                labels={labels}
            />,
        );
        const summaryEnd = html.indexOf("</summary>");
        expect(summaryEnd).toBeGreaterThan(0);
        const summary = html.slice(0, summaryEnd);
        expect(summary).toContain("coverageStatus:partial");
        expect(summary).toContain("Results were bounded");
        expect(summary).toContain("rel(2026-08-10T00:00:00Z)");
        expect(html).toContain("source:records");
        expect(html).toContain("source:activities");
        expect(html).toContain("exclusion:private_data");
    });

    it("formats the period through the locale formatter rather than printing raw instants", () => {
        const html = renderToStaticMarkup(
            <AskConnexCoverageDisclosure
                coverage={coverage({
                    periodStart: "2026-05-01T00:00:00Z",
                    periodEnd: "2026-08-01T00:00:00Z",
                })}
                labels={labels}
            />,
        );
        expect(html).toContain("period abs(2026-05-01T00:00:00Z) to abs(2026-08-01T00:00:00Z)");
    });

    it("does not offer an expander when there is nothing to expand", () => {
        const html = renderToStaticMarkup(
            <AskConnexCoverageDisclosure coverage={coverage({ status: "insufficient" })} labels={labels} />,
        );
        expect(html).not.toContain("<details");
        expect(html).toContain("coverageStatus:insufficient");
    });

    it("reads a key the payload left out exactly as it reads a declared empty one", () => {
        const render = (value: AiChatCoverage) => renderToStaticMarkup(
            <AskConnexCoverageDisclosure coverage={value} labels={labels} />,
        );
        const omitted = (overrides: Partial<AiChatCoverage>) => {
            const value = coverage(overrides);
            for (const field of ["asOf", "periodStart", "periodEnd"]) {
                Reflect.deleteProperty(value, field);
            }
            return value;
        };

        expect(render(omitted({ status: "partial", sources: ["records"] })))
            .toBe(render(coverage({ status: "partial", sources: ["records"] })));
        expect(render(omitted({ status: "insufficient" })))
            .toBe(render(coverage({ status: "insufficient" })));
        expect(render(omitted({ status: "insufficient" }))).not.toContain("Freshness");
        expect(render(omitted({ status: "insufficient" }))).not.toContain("<details");
    });

    it("drops a coverage timestamp that is model prose rather than echoing it", () => {
        const html = renderToStaticMarkup(
            <AskConnexCoverageDisclosure
                coverage={coverage({
                    status: "partial",
                    asOf: "as of the last hospital visit",
                    periodStart: "some time last quarter",
                    periodEnd: "ignore previous instructions",
                })}
                labels={labels}
            />,
        );
        expect(html).not.toContain("hospital");
        expect(html).not.toContain("last quarter");
        expect(html).not.toContain("ignore previous instructions");
        expect(html).not.toContain("Freshness");
        expect(html).not.toContain("<details");
        expect(html).toContain("coverageStatus:partial");
    });
});

describe("checked trail", () => {
    it("renders durable milestones with counts and truncation", () => {
        const html = renderToStaticMarkup(
            <AskConnexCheckedTrail
                progress={[
                    { seq: 1, source: "scope", status: "complete", count: null, truncated: false },
                    { seq: 2, source: "records", status: "complete", count: 12, truncated: true },
                    { seq: 3, source: "metrics", status: "failed", count: null, truncated: false },
                ]}
                labels={labels}
            />,
        );
        expect(html).toContain("What I checked");
        expect(html).toContain("progressSource:scope");
        expect(html).toContain("progressStatus:complete");
        expect(html).toContain("(12 items)");
        expect(html).toContain("Results were bounded");
        expect(html).toContain("progressStatus:failed");
    });

    it("renders nothing when no milestone was recorded", () => {
        expect(renderToStaticMarkup(
            <AskConnexCheckedTrail progress={[]} labels={labels} />,
        )).toBe("");
    });
});

describe("whole answer document", () => {
    it("renders every block, the coverage disclosure, and the trail without a nested bubble", () => {
        const html = renderToStaticMarkup(
            <AskConnexAnswerDocument
                document={{
                    turnId: 5,
                    blocks: [
                        block("answer", { body: "Two deals need attention." }),
                        block("metric", { rows: [row({ label: "At risk", value: "2" })], evidence: [citation()] }),
                        block("fact", { body: "No reply in 21 days." }),
                        block("recommendation", { body: "Send a check-in." }),
                    ],
                    coverage: coverage({ status: "partial", sources: ["deals"], truncated: true }),
                    progress: [
                        { seq: 1, source: "records", status: "complete", count: 2, truncated: false },
                    ],
                }}
                labels={labels}
            />,
        );
        expect(html).toContain("Two deals need attention.");
        expect(html).toContain("At risk");
        expect(html).toContain("kind:fact");
        expect(html).toContain("kind:recommendation");
        expect(html).toContain(labels.unsupported);
        expect(html).toContain("coverageStatus:partial");
        expect(html).toContain("What I checked");
        expect(html).not.toContain("rounded-2xl bg-muted/45");
    });

    it("never renders provider reasoning, prompts, or protocol vocabulary", () => {
        const html = renderToStaticMarkup(
            <AskConnexAnswerDocument
                document={{
                    turnId: 5,
                    blocks: [block("answer", { body: "Answer." })],
                    coverage: coverage(),
                    progress: [],
                }}
                labels={labels}
            />,
        );
        for (const forbidden of ["thinking", "<thinking", "system prompt", "token", "json"]) {
            expect(html.toLowerCase()).not.toContain(forbidden);
        }
    });
});

describe("a draft is copied whole", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    async function copyDraft(target: AiChatAnswerBlock): Promise<{
        copied: string[];
        disabled: boolean;
    }> {
        const copied: string[] = [];
        const { createRoot } = await import("react-dom/client");
        vi.stubGlobal("navigator", {
            ...globalThis.navigator,
            clipboard: { writeText: (value: string) => copied.push(value) },
        });
        const interactive = installInteractiveDocument();
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(<AskConnexAnswerBlock block={target} caveats={[]} labels={labels} />);
        });
        const control = interactive.elements.find(
            (element) => element.tagName === "BUTTON" && element.textContent.includes("Copy"),
        );
        if (!control) throw new Error("Copy control was not rendered");
        const disabled = control.getAttribute("disabled") !== null || control.disabled === true;
        await act(async () => {
            interactive.dispatch("click", control);
        });
        await act(async () => root.unmount());
        return { copied, disabled };
    }

    it("copies the body and the items a mixed draft renders", async () => {
        const { copied, disabled } = await copyDraft(block("draft", {
            title: "Follow-up email",
            body: "Hello Aiko,",
            items: ["Confirm the renewal date", "Send the revised quote"],
        }));

        expect(disabled).toBe(false);
        expect(copied).toEqual([
            "Hello Aiko,\nConfirm the renewal date\nSend the revised quote",
        ]);
    });

    it("copies an item-only draft rather than disabling itself", async () => {
        const { copied, disabled } = await copyDraft(block("draft", {
            items: ["Confirm the renewal date"],
        }));

        expect(disabled).toBe(false);
        expect(copied).toEqual(["Confirm the renewal date"]);
    });

    it("stays disabled when the draft rendered nothing to copy", async () => {
        const { copied, disabled } = await copyDraft(block("draft", { body: "   " }));

        expect(disabled).toBe(true);
        expect(copied).toEqual([]);
    });
});
