import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
    ASK_CONNEX_JOBS_ALL,
    ASK_CONNEX_JOB_LIMIT,
    askConnexJobContext,
    askConnexJobs,
    canExplainAskConnexSignal,
    hasAskConnexJobs,
} from "@/app/lib/askConnexEntryPoints";
import {
    appendAskConnexPrompt,
    askConnexMentionToken,
    askConnexPromptContent,
    askConnexPromptFocusPending,
} from "@/app/lib/askConnex";
import type { AiAssistantSkill, AiChatPageContextKind } from "@/app/lib/types";

const MESSAGES_ROOT = join(process.cwd(), "messages");

function catalog(locale: string): Record<string, unknown> {
    return JSON.parse(readFileSync(join(MESSAGES_ROOT, locale, "common.json"), "utf8")) as Record<
        string,
        unknown
    >;
}

function lookup(tree: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((current, key) => {
        if (typeof current !== "object" || current === null) return undefined;
        return (current as Record<string, unknown>)[key];
    }, tree);
}

function skill(
    key: string,
    contextKinds: AiChatPageContextKind[],
    needsSubject = false,
): AiAssistantSkill {
    return {
        key,
        version: "1.0.0",
        nameKey: `askConnex.skills.${key}.name`,
        descriptionKey: `askConnex.skills.${key}.description`,
        contextKinds,
        needsSubject,
        authority: "read",
    };
}

/** The directory this build's server actually returns: the four capabilities it can execute. */
const SHIPPED_DIRECTORY: AiAssistantSkill[] = [
    skill("relationship_cooling_explanation_v1", ["person", "company"], true),
    skill("activity_digest_v1", ["person", "company", "deal"]),
    skill("relationship_brief_v1", ["person", "company"], true),
    skill("pipeline_attention_review_v1", ["company", "deal"]),
];

describe("what a surface may offer", () => {
    it("offers nothing at all when the member's directory is empty", () => {
        for (const kind of ["person", "company", "deal"] as const) {
            expect(askConnexJobs([], { kind, hasSubject: true })).toEqual([]);
            expect(hasAskConnexJobs([], { kind, hasSubject: true })).toBe(false);
        }
        expect(askConnexJobs([], { kind: null, hasSubject: false })).toEqual([]);
    });

    it("drops a job the moment its capability leaves the directory", () => {
        const withBrief = askConnexJobs(SHIPPED_DIRECTORY, { kind: "person", hasSubject: true });
        expect(withBrief.map((job) => job.id)).toContain("relationshipBrief");

        const withoutBrief = askConnexJobs(
            SHIPPED_DIRECTORY.filter((entry) => entry.key !== "relationship_brief_v1"),
            { kind: "person", hasSubject: true },
        );
        expect(withoutBrief.map((job) => job.id)).not.toContain("relationshipBrief");
    });

    it("withholds a capability that refuses without a record when there is none", () => {
        const anchored = askConnexJobs(SHIPPED_DIRECTORY, { kind: "person", hasSubject: true });
        expect(anchored.map((job) => job.id)).toContain("relationshipCooling");

        const unanchored = askConnexJobs(SHIPPED_DIRECTORY, { kind: "person", hasSubject: false });
        expect(unanchored.map((job) => job.id)).not.toContain("relationshipCooling");
        expect(unanchored.map((job) => job.id)).toContain("contactActivity");
    });

    it("withholds a capability that cannot anchor to this kind of record", () => {
        const onDeal = askConnexJobs(SHIPPED_DIRECTORY, { kind: "deal", hasSubject: true });

        expect(onDeal.map((job) => job.id)).not.toContain("relationshipBrief");
        expect(onDeal.map((job) => job.id)).toEqual(["dealActivity", "dealPipeline"]);
    });

    it("offers each record kind only the readings written for it", () => {
        const person = askConnexJobs(SHIPPED_DIRECTORY, { kind: "person", hasSubject: true });
        const company = askConnexJobs(SHIPPED_DIRECTORY, { kind: "company", hasSubject: true });

        expect(person.map((job) => job.id)).toEqual([
            "relationshipBrief",
            "relationshipCooling",
            "contactActivity",
        ]);
        expect(company.map((job) => job.id)).toEqual([
            "companyBrief",
            "companyCooling",
            "companyActivity",
            "companyPipeline",
        ]);
    });

    it("offers the workspace only the jobs that need no record", () => {
        const workspace = askConnexJobs(SHIPPED_DIRECTORY, { kind: null, hasSubject: false });

        expect(workspace.map((job) => job.id)).toEqual(["workspacePipeline", "workspaceActivity"]);
    });

    it("never offers more than the surface has room for", () => {
        const everything = ASK_CONNEX_JOBS_ALL.map((job) => skill(job.skillKey, ["person", "company", "deal"]));
        const offered = askConnexJobs(everything, { kind: "person", hasSubject: true });

        expect(offered.length).toBe(ASK_CONNEX_JOB_LIMIT);
        expect(askConnexJobs(everything, { kind: "person", hasSubject: true }, 2)).toHaveLength(2);
        expect(askConnexJobs(everything, { kind: "person", hasSubject: true }, 0)).toEqual([]);
    });

    it("keeps the same order every time it is asked", () => {
        const first = askConnexJobs(SHIPPED_DIRECTORY, { kind: "company", hasSubject: true });
        const second = askConnexJobs([...SHIPPED_DIRECTORY].reverse(), {
            kind: "company",
            hasSubject: true,
        });

        expect(second.map((job) => job.id)).toEqual(first.map((job) => job.id));
    });
});

describe("explaining a Radar signal", () => {
    it("offers an explanation only where a capability reads the same ground", () => {
        expect(canExplainAskConnexSignal(SHIPPED_DIRECTORY, "relationship_decay", "person")).toBe(true);
        expect(canExplainAskConnexSignal(SHIPPED_DIRECTORY, "relationship_decay", "company")).toBe(true);
        expect(canExplainAskConnexSignal(SHIPPED_DIRECTORY, "relationship_decay", "deal")).toBe(false);
        expect(canExplainAskConnexSignal(SHIPPED_DIRECTORY, "deal_risk", "deal")).toBe(false);
        expect(canExplainAskConnexSignal(SHIPPED_DIRECTORY, "warm_path", "person")).toBe(false);
        expect(canExplainAskConnexSignal([], "relationship_decay", "person")).toBe(false);
    });

    it("offers a deal-risk explanation once that capability is in the directory", () => {
        const directory = [...SHIPPED_DIRECTORY, skill("deal_risk_review_v1", ["deal"])];

        expect(canExplainAskConnexSignal(directory, "deal_risk", "deal")).toBe(true);
        expect(canExplainAskConnexSignal(directory, "deal_risk", "person")).toBe(false);
    });
});

describe("what counts as a subject to offer a job about", () => {
    it("treats the record a surface is about as its subject", () => {
        expect(askConnexJobContext("person")).toEqual({ kind: "person", hasSubject: true });
        expect(askConnexJobContext("deal")).toEqual({ kind: "deal", hasSubject: true });
    });

    it("treats a surface with no record as having no subject at all", () => {
        expect(askConnexJobContext(null)).toEqual({ kind: null, hasSubject: false });
    });

    it("offers no single-record job against a surface carrying only a selection", () => {
        const offered = askConnexJobs(SHIPPED_DIRECTORY, askConnexJobContext(null));

        expect(offered.map((job) => job.id)).not.toContain("relationshipBrief");
        expect(offered.map((job) => job.id)).not.toContain("companyBrief");
        expect(offered.every((job) => job.contexts.length === 0)).toBe(true);
    });

    it("still offers the workspace jobs that need no subject", () => {
        expect(askConnexJobs(SHIPPED_DIRECTORY, askConnexJobContext(null)).map((job) => job.id))
            .toEqual(["workspacePipeline", "workspaceActivity"]);
    });
});

describe("offering a job without destroying a half-written question", () => {
    it("takes an empty composer whole", () => {
        expect(appendAskConnexPrompt("", "Brief me on this relationship."))
            .toBe("Brief me on this relationship.");
        expect(appendAskConnexPrompt("   ", "Brief me on this relationship."))
            .toBe("Brief me on this relationship.");
    });

    it("keeps what was already typed and joins the job to it", () => {
        expect(appendAskConnexPrompt("What changed last week?", "Brief me on this relationship."))
            .toBe("What changed last week?\n\nBrief me on this relationship.");
    });

    it("changes nothing when there is no job to add", () => {
        expect(appendAskConnexPrompt("What changed last week?", "")).toBe("What changed last week?");
    });
});

describe("landing in the composer after a job is written into it", () => {
    it("takes focus while a request is outstanding and the surface is on screen", () => {
        expect(askConnexPromptFocusPending(true, 1)).toBe(true);
        expect(askConnexPromptFocusPending(true, 4)).toBe(true);
    });

    it("takes no focus from a surface that opens with nothing outstanding", () => {
        expect(askConnexPromptFocusPending(true, 0)).toBe(false);
    });

    it("waits until the surface is actually on screen", () => {
        expect(askConnexPromptFocusPending(false, 1)).toBe(false);
    });
});

describe("the message an entry point hands to the composer", () => {
    it("carries records the page does not already have as reference chips", () => {
        const content = askConnexPromptContent("Brief me on this relationship.", [
            { kind: "person", id: 42, label: "Aiko Tanaka" },
        ]);

        expect(content).toBe("Brief me on this relationship. [Aiko Tanaka](person:42)");
    });

    it("carries a record named inside the sentence where the sentence needs it", () => {
        const token = askConnexMentionToken({ kind: "company", id: 7, label: "Acme" });

        expect(token).toBe("[Acme](company:7)");
        expect(`Why is ${token} cooling?`).toBe("Why is [Acme](company:7) cooling?");
    });

    it("keeps a bracketed record name from breaking the reference it sits in", () => {
        const token = askConnexMentionToken({ kind: "person", id: 3, label: "Sato [KK]" });

        expect(token).toBe("[Sato KK](person:3)");
    });

    it("drops a record with no usable name rather than emitting an empty reference", () => {
        expect(askConnexMentionToken({ kind: "person", id: 3, label: "  " })).toBe("");
        expect(askConnexPromptContent("Ask.", [{ kind: "person", id: 3, label: "[]" }])).toBe("Ask.");
    });

    it("names the same record once however many times it was offered", () => {
        const content = askConnexPromptContent("Compare these.", [
            { kind: "person", id: 1, label: "A" },
            { kind: "person", id: 1, label: "A" },
            { kind: "company", id: 1, label: "B" },
        ]);

        expect(content).toBe("Compare these. [A](person:1) [B](company:1)");
    });
});

describe("the copy every offer needs", () => {
    it("states every job in both languages", () => {
        for (const locale of ["en", "ja"]) {
            const messages = catalog(locale);
            for (const job of ASK_CONNEX_JOBS_ALL) {
                expect(
                    lookup(messages, `AskConnex.jobs.${job.id}.label`),
                    `${locale} label for ${job.id}`,
                ).toBeTypeOf("string");
                expect(
                    lookup(messages, `AskConnex.jobs.${job.id}.prompt`),
                    `${locale} prompt for ${job.id}`,
                ).toBeTypeOf("string");
            }
        }
    });

    it("names every declared capability in both languages, under the server's own keys", () => {
        const declared = [
            "relationshipCoolingExplanation",
            "activityDigest",
            "relationshipBrief",
            "pipelineAttentionReview",
            "relationshipChangeSummary",
            "introductionPathExplanation",
            "meetingPreparation",
            "meetingFollowUpExtraction",
            "followUpDraft",
            "dealRiskReview",
            "stakeholderGapAnalysis",
            "companyReview",
            "dailyWorkBrief",
            "commitmentExtraction",
            "dataQualityReview",
            "naturalLanguageReport",
        ];
        for (const locale of ["en", "ja"]) {
            const messages = catalog(locale);
            for (const key of declared) {
                expect(
                    lookup(messages, `askConnex.skills.${key}.name`),
                    `${locale} name for ${key}`,
                ).toBeTypeOf("string");
                expect(
                    lookup(messages, `askConnex.skills.${key}.description`),
                    `${locale} description for ${key}`,
                ).toBeTypeOf("string");
            }
        }
    });

    it("explains every Radar signal family it can enter, in both languages", () => {
        for (const locale of ["en", "ja"]) {
            const messages = catalog(locale);
            for (const family of ["relationship_decay", "deal_risk", "warm_path"]) {
                expect(lookup(messages, `AskConnex.signals.${family}.label`)).toBeTypeOf("string");
                const prompt = lookup(messages, `AskConnex.signals.${family}.prompt`);
                expect(prompt).toBeTypeOf("string");
                expect(String(prompt)).toContain("{subject}");
            }
        }
    });

    it("names the record entry point on every record kind, in both languages", () => {
        for (const locale of ["en", "ja"]) {
            const messages = catalog(locale);
            for (const kind of ["person", "company", "deal"]) {
                expect(lookup(messages, `AskConnex.entryPoint.recordMenu.${kind}`)).toBeTypeOf("string");
            }
        }
    });
});
