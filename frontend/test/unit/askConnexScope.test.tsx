import { readFileSync } from "node:fs";
import { join } from "node:path";
import { NextIntlClientProvider } from "next-intl";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import {
    AskConnexScopeFields,
    AskConnexScopeSummary,
} from "@/app/components/ask-connex/AskConnexScopeEditor";
import {
    askConnexRequestScope,
    type AskConnexDeclaredScope,
} from "@/app/lib/askConnex";
import {
    ASK_CONNEX_SCOPE_MAX_STAGES,
    ASK_CONNEX_SCOPE_REASONS,
    EMPTY_ASK_CONNEX_SCOPE_DRAFT,
    NO_ASK_CONNEX_SCOPE_OPTIONS,
    askConnexScopeAccepted,
    askConnexScopeAllowsDeals,
    askConnexScopeBlocked,
    askConnexScopeChips,
    askConnexScopeDeclared,
    askConnexScopeDisclosures,
    askConnexScopeFilterCount,
    askConnexScopeOptionsFor,
    askConnexScopePeriodLabel,
    askConnexScopeProblem,
    askConnexScopeReason,
    askConnexScopeRefusal,
    askConnexScopeRequest,
    askConnexScopeRoutingKey,
    askConnexScopeSavedViewLabels,
    askConnexScopeStageLabels,
    clearedAskConnexScopeDraft,
    withAskConnexScopeRecordKinds,
    type AskConnexScopeDraft,
    type AskConnexScopeOptions,
    type AskConnexScopePreviewState,
} from "@/app/lib/askConnexScope";
import type {
    AiAssistantSkill,
    AiChatQueryScope,
    SavedView,
    SavedViewRecordType,
    Stage,
} from "@/app/lib/types";

const MESSAGES = JSON.parse(
    readFileSync(join(process.cwd(), "messages", "en", "common.json"), "utf8"),
) as Record<string, unknown>;

const JA_MESSAGES = JSON.parse(
    readFileSync(join(process.cwd(), "messages", "ja", "common.json"), "utf8"),
) as Record<string, unknown>;

function reasonCopy(catalog: Record<string, unknown>, reason: string): unknown {
    const ask = catalog.AskConnex as Record<string, unknown> | undefined;
    const scope = ask?.scope as Record<string, unknown> | undefined;
    const reasons = scope?.reasons as Record<string, unknown> | undefined;
    return reasons?.[reason];
}

function draft(overrides: Partial<AskConnexScopeDraft> = {}): AskConnexScopeDraft {
    return { ...EMPTY_ASK_CONNEX_SCOPE_DRAFT, ...overrides };
}

function options(overrides: Partial<AskConnexScopeOptions> = {}): AskConnexScopeOptions {
    return { ...NO_ASK_CONNEX_SCOPE_OPTIONS, ...overrides };
}

function stage(overrides: Partial<Stage> & { id: number; name: string }): Stage {
    return { pipeline: 1, position: 0, success: false, failure: false, ...overrides };
}

function savedView(overrides: Partial<SavedView> & { id: number; name: string; recordType: SavedViewRecordType }): SavedView {
    return {
        workspaceId: 1,
        visibility: "private",
        ownerUserId: 1,
        ownedByCurrentUser: true,
        config: {},
        position: 0,
        pinned: false,
        pinPosition: null,
        default: false,
        createdAt: "",
        updatedAt: "",
        ...overrides,
    };
}

function interpreted(overrides: Partial<AiChatQueryScope> = {}): AiChatQueryScope {
    return {
        declared: true,
        periodStart: null,
        periodEnd: null,
        periodDays: null,
        ownerMode: "all_team",
        owners: [],
        warmthBands: [],
        recordKinds: [],
        stages: [],
        dealStatuses: [],
        activityTypes: [],
        savedView: null,
        matchedRecordCount: null,
        matchedRecordCountTruncated: false,
        recordCap: 200,
        activityCap: 100,
        perRecordCap: 10,
        unavailable: [],
        ...overrides,
    };
}

function render(node: React.ReactNode, locale: "en" | "ja" = "en"): string {
    return renderToStaticMarkup(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? MESSAGES : JA_MESSAGES}>
            {node}
        </NextIntlClientProvider>,
    );
}

const DIGEST: AiAssistantSkill = {
    key: "activity_digest_v1",
    version: "1.0.0",
    nameKey: "askConnex.skills.activityDigest.name",
    descriptionKey: "askConnex.skills.activityDigest.description",
    contextKinds: ["person", "company", "deal"],
    needsSubject: false,
    authority: "read",
};

describe("what the filter form declares", () => {
    it("declares nothing until a filter is actually set", () => {
        expect(askConnexScopeDeclared(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBe(false);
        expect(askConnexScopeRequest(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBeNull();
        expect(askConnexScopeFilterCount(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBe(0);
    });

    it("does not declare a period mode nobody filled in", () => {
        expect(askConnexScopeDeclared(draft({ periodMode: "days" }))).toBe(false);
        expect(askConnexScopeDeclared(draft({ periodMode: "range" }))).toBe(false);
        expect(askConnexScopeDeclared(draft({ periodMode: "days", periodDays: 30 }))).toBe(true);
    });

    it("sends a trailing window as days and a range as dates", () => {
        expect(askConnexScopeRequest(draft({ periodMode: "days", periodDays: 90 }))).toEqual({
            periodDays: 90,
        });
        expect(askConnexScopeRequest(draft({
            periodMode: "range",
            periodStart: "2026-01-01",
            periodEnd: "2026-03-31",
        }))).toEqual({ periodStart: "2026-01-01", periodEnd: "2026-03-31" });
    });

    it("keeps the mode the member is not in out of the request", () => {
        const request = askConnexScopeRequest(draft({
            periodMode: "days",
            periodDays: 7,
            periodStart: "2026-01-01",
            periodEnd: "2026-03-31",
        }));

        expect(request).toEqual({ periodDays: 7 });
    });

    it("omits an unset filter instead of sending it empty", () => {
        const request = askConnexScopeRequest(draft({ warmthBands: ["cool"] }));

        expect(request).toEqual({ warmthBands: ["cool"] });
        expect(request && "recordKinds" in request).toBe(false);
        expect(request && "ownerMode" in request).toBe(false);
    });

    it("names members only when the member scope is a chosen list", () => {
        expect(askConnexScopeRequest(draft({ ownerMode: "me", ownerMemberIds: [4] }))).toEqual({
            ownerMode: "me",
        });
        expect(askConnexScopeRequest(draft({ ownerMode: "members", ownerMemberIds: [4, 9] }))).toEqual({
            ownerMode: "members",
            ownerMemberIds: [4, 9],
        });
    });

    it("maps a whole selection into one request", () => {
        const request = askConnexScopeRequest(draft({
            periodMode: "days",
            periodDays: 30,
            ownerMode: "me",
            warmthBands: ["cool", "cold"],
            recordKinds: ["deal"],
            dealStatuses: ["open"],
            stageIds: [3, 5],
            savedViewId: 11,
        }));

        expect(request).toEqual({
            periodDays: 30,
            ownerMode: "me",
            warmthBands: ["cool", "cold"],
            recordKinds: ["deal"],
            dealStatuses: ["open"],
            stageIds: [3, 5],
            savedViewId: 11,
        });
    });

    it("counts each set filter once, whatever it names", () => {
        expect(askConnexScopeFilterCount(draft({
            periodMode: "days",
            periodDays: 30,
            ownerMode: "members",
            ownerMemberIds: [1, 2, 3],
            warmthBands: ["hot", "warm"],
        }))).toBe(3);
    });
});

describe("problems the form can settle itself", () => {
    it("refuses to send a backwards range and says so", () => {
        const backwards = draft({
            periodMode: "range",
            periodStart: "2026-05-01",
            periodEnd: "2026-04-01",
        });

        expect(askConnexScopeProblem(backwards)).toBe("periodOrder");
        expect(askConnexScopeRequest(backwards)).toBeNull();
    });

    it("refuses a window longer than a request may reach back", () => {
        expect(askConnexScopeProblem(draft({ periodMode: "days", periodDays: 400 }))).toBe("periodLength");
        expect(askConnexScopeProblem(draft({ periodMode: "days", periodDays: 0 }))).toBe("periodLength");
        expect(askConnexScopeProblem(draft({ periodMode: "days", periodDays: 365 }))).toBeNull();
    });

    it("refuses a chosen-members scope that names nobody", () => {
        expect(askConnexScopeProblem(draft({ ownerMode: "members" }))).toBe("membersMissing");
        expect(askConnexScopeProblem(draft({ ownerMode: "members", ownerMemberIds: [2] }))).toBeNull();
    });

    it("keeps a half-written filter from becoming a question about everything", () => {
        const nobody = draft({ ownerMode: "members" });
        const backwards = draft({
            periodMode: "range",
            periodStart: "2026-05-01",
            periodEnd: "2026-04-01",
        });

        for (const stuck of [nobody, backwards]) {
            expect(askConnexScopeDeclared(stuck)).toBe(true);
            expect(askConnexScopeRequest(stuck)).toBeNull();
            expect(askConnexScopeBlocked(stuck)).toBe(true);
        }
    });

    it("does not confuse a form with nothing in it for a form with a mistake in it", () => {
        expect(askConnexScopeRequest(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBeNull();
        expect(askConnexScopeBlocked(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBe(false);
        expect(askConnexScopeBlocked(draft({ ownerMode: "members", ownerMemberIds: [2] }))).toBe(false);
        expect(askConnexScopeBlocked(draft({ periodMode: "days", periodDays: 30 }))).toBe(false);
    });
});

describe("filters that depend on other filters", () => {
    it("offers deal-only filters while deals are still in scope", () => {
        expect(askConnexScopeAllowsDeals(EMPTY_ASK_CONNEX_SCOPE_DRAFT)).toBe(true);
        expect(askConnexScopeAllowsDeals(draft({ recordKinds: ["deal", "company"] }))).toBe(true);
        expect(askConnexScopeAllowsDeals(draft({ recordKinds: ["person"] }))).toBe(false);
    });

    it("takes the deal-only filters with it when deals leave the scope", () => {
        const narrowed = withAskConnexScopeRecordKinds(
            draft({ dealStatuses: ["open"], stageIds: [4] }),
            ["person"],
        );

        expect(narrowed.dealStatuses).toEqual([]);
        expect(narrowed.stageIds).toEqual([]);
        expect(askConnexScopeRequest(narrowed)).toEqual({ recordKinds: ["person"] });
    });

    it("keeps them when deals stay in the scope", () => {
        const kept = withAskConnexScopeRecordKinds(
            draft({ dealStatuses: ["open"], stageIds: [4] }),
            ["deal"],
        );

        expect(kept.dealStatuses).toEqual(["open"]);
        expect(kept.stageIds).toEqual([4]);
    });

    it("hides the deal-only fields once deals are out of scope", () => {
        const withDeals = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options()}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );
        const withoutDeals = render(
            <AskConnexScopeFields
                draft={draft({ recordKinds: ["person"] })}
                options={options()}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(withDeals).toContain("Deal status");
        expect(withoutDeals).not.toContain("Deal status");
    });

    it("offers stages only where a pipeline actually has them", () => {
        const withStages = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options({ stages: [stage({ id: 4, name: "Proposal", position: 2 })] })}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(withStages).toContain("Proposal");
        expect(render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options()}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        )).not.toContain("Stages");
    });

    it("states a fixable problem next to the field that caused it", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={draft({ periodMode: "range", periodStart: "2026-05-01", periodEnd: "2026-04-01" })}
                options={options()}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain("The first date has to come before the second one.");
        expect(markup).toContain('role="alert"');
    });
});

describe("what the interpreted scope says", () => {
    it("waits quietly until a filter is set", () => {
        expect(render(<AskConnexScopeSummary preview={{ status: "idle" }} skills={[]} />))
            .toContain("Choose a filter to see what it covers.");
    });

    it("states the count the server evaluated", () => {
        const preview: AskConnexScopePreviewState = {
            status: "ready",
            scope: interpreted({ matchedRecordCount: 47 }),
            skillKey: null,
            confirmationRecommended: true,
        };

        expect(render(<AskConnexScopeSummary preview={preview} skills={[]} />))
            .toContain("47 records match these filters.");
    });

    it("says so when the cohort is larger than the request will read", () => {
        const preview: AskConnexScopePreviewState = {
            status: "ready",
            scope: interpreted({
                matchedRecordCount: 200,
                matchedRecordCountTruncated: true,
                recordCap: 200,
            }),
            skillKey: null,
            confirmationRecommended: true,
        };

        expect(render(<AskConnexScopeSummary preview={preview} skills={[]} />))
            .toContain("Ask Connex will read the first 200 of them.");
    });

    it("names the capability that would run, from the server's own key", () => {
        const preview: AskConnexScopePreviewState = {
            status: "ready",
            scope: interpreted({ matchedRecordCount: 12 }),
            skillKey: "activity_digest_v1",
            confirmationRecommended: false,
        };

        expect(render(<AskConnexScopeSummary preview={preview} skills={[DIGEST]} />))
            .toContain("Activity digest");
    });

    it("discloses every filter the server could not apply", () => {
        const preview: AskConnexScopePreviewState = {
            status: "ready",
            scope: interpreted({ matchedRecordCount: 5, unavailable: ["period_capped"] }),
            skillKey: null,
            confirmationRecommended: false,
        };
        const markup = render(<AskConnexScopeSummary preview={preview} skills={[]} />);

        expect(markup).toContain("A period can reach back one year");
    });

    it("states a disclosed reason it has no explanation for in general terms rather than dropping it", () => {
        const scope = interpreted({
            unavailable: ["period_capped", "something_new", "period_capped", "something_else"],
        });

        expect(askConnexScopeDisclosures(scope)).toEqual(["period_capped", "other"]);
        expect(askConnexScopeDisclosures(null)).toEqual([]);
    });

    it("says out loud that a filter it cannot name was left unapplied", () => {
        const preview: AskConnexScopePreviewState = {
            status: "ready",
            scope: interpreted({ matchedRecordCount: 5, unavailable: ["a_reason_from_a_later_server"] }),
            skillKey: null,
            confirmationRecommended: false,
        };

        expect(render(<AskConnexScopeSummary preview={preview} skills={[]} />))
            .toContain("One of the filters you set couldn&#x27;t be applied");
        expect(render(<AskConnexScopeSummary preview={preview} skills={[]} />, "ja"))
            .toContain("この質問には適用できませんでした");
    });

    it("states a refusal in the member's words, never the server's", () => {
        const markup = render(
            <AskConnexScopeSummary
                preview={{ status: "refused", reason: "warmth_unsupported_for_deals" }}
                skills={[]}
            />,
        );

        expect(markup).toContain("Warmth describes relationships with people and companies");
        expect(markup).not.toContain("warmth_unsupported_for_deals");
        expect(markup).toContain('role="alert"');
    });

    it("explains an unrecognized refusal in general terms rather than echoing it", () => {
        const markup = render(
            <AskConnexScopeSummary preview={{ status: "refused", reason: null }} skills={[]} />,
        );

        expect(markup).toContain("Ask Connex can&#x27;t cover exactly this combination of filters.");
    });

    it("keeps a spent allowance, an unavailable feature, and a failure apart", () => {
        expect(render(<AskConnexScopeSummary preview={{ status: "throttled" }} skills={[]} />))
            .toContain("the count will catch up in a moment");
        expect(render(<AskConnexScopeSummary preview={{ status: "unavailable" }} skills={[]} />))
            .toContain("Ask Connex isn&#x27;t available in this workspace right now");
        expect(render(<AskConnexScopeSummary preview={{ status: "failed" }} skills={[]} />))
            .toContain("Couldn&#x27;t check what these filters cover.");
    });

    it("speaks Japanese wherever it speaks English", () => {
        const markup = render(
            <AskConnexScopeSummary
                preview={{ status: "refused", reason: "warmth_unsupported_for_deals" }}
                skills={[]}
            />,
            "ja",
        );

        expect(markup).toContain("温度感は人や会社との関係を表すもの");
    });
});

describe("reading the server's answer", () => {
    it("recognizes every reason it has copy for, and nothing else", () => {
        for (const reason of ASK_CONNEX_SCOPE_REASONS) {
            expect(askConnexScopeReason(reason)).toBe(reason);
        }
        expect(askConnexScopeReason("something_new")).toBeNull();
        expect(askConnexScopeReason("")).toBeNull();
    });

    it("takes the reason out of a refusal without letting the message through", () => {
        expect(askConnexScopeRefusal(
            "Assistant scope cannot be executed as declared: warmth_unsupported_for_deals",
        )).toBe("warmth_unsupported_for_deals");
        expect(askConnexScopeRefusal(
            "Assistant scope stage and status filters require a deal cohort: stage_scope_unsupported_for_cohort",
        )).toBe("stage_scope_unsupported_for_cohort");
        expect(askConnexScopeRefusal("Assistant scope period start must precede its end")).toBeNull();
        expect(askConnexScopeRefusal(null)).toBeNull();
        expect(askConnexScopeRefusal(undefined)).toBeNull();
    });

    it("explains every reason the contract can produce, in both languages", () => {
        for (const reason of [...ASK_CONNEX_SCOPE_REASONS, "unknown", "other"]) {
            expect(reasonCopy(MESSAGES, reason), `en copy for ${reason}`).toBeTypeOf("string");
            expect(reasonCopy(JA_MESSAGES, reason), `ja copy for ${reason}`).toBeTypeOf("string");
        }
    });
});

describe("the chips a declared scope leaves in the cockpit", () => {
    it("shows nothing for a request that declared no filters", () => {
        expect(askConnexScopeChips(null)).toEqual([]);
        expect(askConnexScopeChips(interpreted({ declared: false }))).toEqual([]);
    });

    it("states the period the server settled on, not the one that was asked for", () => {
        const chips = askConnexScopeChips(interpreted({
            periodStart: "2025-08-23",
            periodEnd: "2026-08-22",
            periodDays: 365,
        }));

        expect(chips).toEqual([
            { key: "period", kind: "period", values: ["2025-08-23", "2026-08-22"] },
        ]);
    });

    it("reads a period as one date range rather than a list of two days", () => {
        const english = askConnexScopePeriodLabel(["2026-07-25", "2026-08-23"], "en");

        expect(english).toContain("Jul 25");
        expect(english).toContain("Aug 23");
        expect(english).toContain("2026");
        expect(english).not.toContain("and");
        expect(english).not.toContain("2026-07-25");
        expect(askConnexScopePeriodLabel(["2026-07-25", "2026-08-23"], "ja")).toContain("2026");
    });

    it("states the chip's own name alone rather than half a span", () => {
        expect(askConnexScopePeriodLabel([], "en")).toBe("");
        expect(askConnexScopePeriodLabel(["2026-07-25"], "en")).toBe("");
        expect(askConnexScopePeriodLabel(["not a date", "2026-08-23"], "en")).toBe("");
        expect(askConnexScopePeriodLabel(["2026-08-23", "2026-07-25"], "en")).toBe("");
    });

    it("names owners, stages, and a saved view by the labels the server authorized", () => {
        const chips = askConnexScopeChips(interpreted({
            ownerMode: "members",
            owners: [{ id: 4, label: "Mina" }, { id: 9, label: "" }],
            stages: [{ id: 3, label: "Proposal" }],
            savedView: { id: 11, label: "My cooling accounts" },
        }));

        expect(chips.map((chip) => chip.kind)).toEqual(["owners", "stages", "savedView"]);
        expect(chips[0]?.values).toEqual(["Mina"]);
        expect(chips[2]?.values).toEqual(["My cooling accounts"]);
    });

    it("says whose records a scope restricted to the member covers", () => {
        const chips = askConnexScopeChips(interpreted({ ownerMode: "me", owners: [] }));

        expect(chips).toEqual([{ key: "owners", kind: "ownersMe", values: [] }]);
        expect(
            (((MESSAGES.AskConnex as Record<string, unknown>).scope as Record<string, unknown>)
                .chips as Record<string, unknown>).ownersMe,
        ).toBe("My records");
        expect(
            (((JA_MESSAGES.AskConnex as Record<string, unknown>).scope as Record<string, unknown>)
                .chips as Record<string, unknown>).ownersMe,
        ).toBeTypeOf("string");
    });

    it("carries the fixed vocabularies for their own copy to resolve", () => {
        const chips = askConnexScopeChips(interpreted({
            warmthBands: ["cool"],
            recordKinds: ["deal"],
            dealStatuses: ["open"],
        }));

        expect(chips).toEqual([
            { key: "warmth", kind: "warmth", values: ["cool"] },
            { key: "recordKinds", kind: "recordKinds", values: ["deal"] },
            { key: "dealStatuses", kind: "dealStatuses", values: ["open"] },
        ]);
    });
});

describe("agreeing to what a request will cover", () => {
    function measured(overrides: Partial<AskConnexDeclaredScope> = {}): AskConnexDeclaredScope {
        return {
            identity: "filters-a",
            confirmationRecommended: false,
            matched: { count: 3, truncated: false, recordCap: 200 },
            measuring: false,
            ...overrides,
        };
    }

    it("asks for nothing when neither the records nor the filters are broad", () => {
        expect(askConnexRequestScope(null, null).identity).toBeNull();
    });

    it("holds the request when the server asks for the breadth to be reviewed", () => {
        const scope = askConnexRequestScope(null, measured({
            confirmationRecommended: true,
            matched: { count: 47, truncated: false, recordCap: 200 },
        }));

        expect(scope.identity).not.toBeNull();
    });

    it("lets narrowing filters through without a confirmation of their own", () => {
        expect(askConnexRequestScope(null, measured()).identity).toBeNull();
    });

    it("holds a scope nothing has measured, however narrow it may turn out to be", () => {
        for (const unmeasured of [
            measured({ matched: null, measuring: true }),
            measured({ matched: null, measuring: false }),
        ]) {
            expect(askConnexRequestScope(null, unmeasured).identity).not.toBeNull();
        }
    });

    it("re-arms the confirmation when the filters change under an agreed breadth", () => {
        const first = askConnexRequestScope(null, measured({
            confirmationRecommended: true,
            matched: { count: 47, truncated: false, recordCap: 200 },
        }));
        const second = askConnexRequestScope(null, measured({
            identity: "filters-b",
            confirmationRecommended: true,
            matched: { count: 47, truncated: false, recordCap: 200 },
        }));

        expect(second.identity).not.toBe(first.identity);
    });

    it("re-arms it when the same filters are asked a different question, from a different page", () => {
        const asked = askConnexScopeRoutingKey("which accounts are cooling?", [
            { kind: "company", id: 4 },
        ]);

        expect(askConnexScopeRoutingKey("which accounts are cooling?", [
            { kind: "company", id: 4 },
        ])).toBe(asked);
        expect(askConnexScopeRoutingKey("which accounts are cooling?", [
            { kind: "company", id: 4 },
            { kind: "company", id: 9 },
        ])).not.toBe(asked);
        expect(askConnexScopeRoutingKey("who should I introduce?", [
            { kind: "company", id: 4 },
        ])).not.toBe(asked);
    });

    it("reads the same page context in either order as the same request", () => {
        expect(askConnexScopeRoutingKey("q", [{ kind: "deal", id: 2 }, { kind: "deal", id: 1 }]))
            .toBe(askConnexScopeRoutingKey("q", [{ kind: "deal", id: 1 }, { kind: "deal", id: 2 }]));
    });
});

describe("what the server echoes back once a scoped question is accepted", () => {
    const settled: AskConnexScopePreviewState = {
        status: "ready",
        scope: interpreted({ matchedRecordCount: 47, matchedRecordCountTruncated: true }),
        skillKey: "activity_digest_v1",
        confirmationRecommended: true,
    };

    it("takes the server's interpretation without erasing the count it never restates", () => {
        const merged = askConnexScopeAccepted(settled, interpreted({
            periodStart: "2026-07-25",
            periodEnd: "2026-08-23",
            unavailable: ["period_capped"],
        }));

        expect(merged.status).toBe("ready");
        if (merged.status !== "ready") return;
        expect(merged.scope.periodStart).toBe("2026-07-25");
        expect(merged.scope.unavailable).toEqual(["period_capped"]);
        expect(merged.scope.matchedRecordCount).toBe(47);
        expect(merged.scope.matchedRecordCountTruncated).toBe(true);
    });

    it("keeps the breadth armed, so the next question against it is reviewed too", () => {
        const merged = askConnexScopeAccepted(settled, interpreted());

        expect(merged.status).toBe("ready");
        if (merged.status !== "ready") return;
        expect(merged.confirmationRecommended).toBe(true);
        expect(merged.skillKey).toBe("activity_digest_v1");
        expect(askConnexRequestScope(null, {
            identity: "filters-a",
            confirmationRecommended: merged.confirmationRecommended,
            matched: {
                count: merged.scope.matchedRecordCount,
                truncated: merged.scope.matchedRecordCountTruncated,
                recordCap: merged.scope.recordCap,
            },
            measuring: false,
        }).identity).not.toBeNull();
    });

    it("still names the capability and the count when the editor is reopened", () => {
        const merged = askConnexScopeAccepted(settled, interpreted());

        expect(render(<AskConnexScopeSummary preview={merged} skills={[DIGEST]} />))
            .toContain("47 records match these filters.");
        expect(render(<AskConnexScopeSummary preview={merged} skills={[DIGEST]} />))
            .not.toContain("These filters are ready to use.");
    });

    it("promotes nothing when no count was ever settled", () => {
        for (const unsettled of [
            { status: "throttled" },
            { status: "failed" },
            { status: "unavailable" },
            { status: "loading" },
        ] satisfies AskConnexScopePreviewState[]) {
            expect(askConnexScopeAccepted(unsettled, interpreted())).toEqual(unsettled);
        }
    });
});

describe("clearing the filters", () => {
    it("clears every filter, including the deal-only ones the form is hiding", () => {
        const cleared = clearedAskConnexScopeDraft();

        expect(cleared).toEqual(EMPTY_ASK_CONNEX_SCOPE_DRAFT);
        expect(cleared.dealStatuses).toEqual([]);
        expect(cleared.stageIds).toEqual([]);
        expect(askConnexScopeDeclared(cleared)).toBe(false);
        expect(askConnexScopeFilterCount(cleared)).toBe(0);
        expect(askConnexScopeRequest(cleared)).toBeNull();
    });

    it("cannot be built by emptying the record types, which leaves the deal filters set", () => {
        const emptied = withAskConnexScopeRecordKinds(
            draft({ dealStatuses: ["open"], stageIds: [4] }),
            [],
        );

        expect(emptied.dealStatuses).toEqual(["open"]);
        expect(emptied.stageIds).toEqual([4]);
        expect(askConnexScopeDeclared(emptied)).toBe(true);
    });
});

describe("the names a workspace lends the form", () => {
    const workspaceOptions = options({
        stages: [stage({ id: 4, name: "Proposal" })],
    });

    it("offers a workspace's own names and nothing else", () => {
        const loaded = { workspaceId: 7, options: workspaceOptions };

        expect(askConnexScopeOptionsFor(loaded, 7)).toBe(workspaceOptions);
        expect(askConnexScopeOptionsFor(loaded, 9)).toEqual(NO_ASK_CONNEX_SCOPE_OPTIONS);
        expect(askConnexScopeOptionsFor(loaded, null)).toEqual(NO_ASK_CONNEX_SCOPE_OPTIONS);
        expect(askConnexScopeOptionsFor(null, 7)).toEqual(NO_ASK_CONNEX_SCOPE_OPTIONS);
    });

    it("shows nothing at all rather than the previous workspace's names", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={askConnexScopeOptionsFor({ workspaceId: 7, options: workspaceOptions }, 9)}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).not.toContain("Proposal");
    });
});

describe("naming a stage the member can tell apart", () => {
    it("carries the pipeline only where the same name occurs in more than one", () => {
        const labels = askConnexScopeStageLabels(
            [
                stage({ id: 1, name: "Proposal", pipeline: 10 }),
                stage({ id: 2, name: "Proposal", pipeline: 20 }),
                stage({ id: 3, name: "Signed", pipeline: 20 }),
            ],
            [
                { id: 10, name: "New business", createdAt: "", updatedAt: "" },
                { id: 20, name: "Renewals", createdAt: "", updatedAt: "" },
            ],
        );

        expect(labels.get(1)).toBe("New business · Proposal");
        expect(labels.get(2)).toBe("Renewals · Proposal");
        expect(labels.get(3)).toBe("Signed");
    });

    it("leaves an ambiguous name as it is rather than inventing a pipeline for it", () => {
        const labels = askConnexScopeStageLabels(
            [
                stage({ id: 1, name: "Proposal", pipeline: 10 }),
                stage({ id: 2, name: "Proposal", pipeline: 20 }),
            ],
            [],
        );

        expect(labels.get(1)).toBe("Proposal");
    });

    it("stops naming stages at the number the server accepts, and says why", () => {
        const stages = Array.from(
            { length: ASK_CONNEX_SCOPE_MAX_STAGES + 1 },
            (_, index) => stage({ id: index + 1, name: `Stage ${index + 1}`, position: index }),
        );
        const markup = render(
            <AskConnexScopeFields
                draft={draft({ stageIds: stages.slice(0, ASK_CONNEX_SCOPE_MAX_STAGES).map((each) => each.id) })}
                options={options({ stages })}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain(`You can name up to ${ASK_CONNEX_SCOPE_MAX_STAGES} stages.`);
        expect(markup).toContain('disabled=""');
    });

    it("leaves every stage choosable while there is room for another", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options({ stages: [stage({ id: 4, name: "Proposal" })] })}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).not.toContain("You can name up to");
        expect(markup).not.toContain('disabled=""');
    });
});

describe("naming a saved view the member can tell apart", () => {
    it("carries the record type only where the same name occurs under more than one", () => {
        const labels = askConnexScopeSavedViewLabels(
            [
                savedView({ id: 1, name: "My records", recordType: "person" }),
                savedView({ id: 2, name: "My records", recordType: "deal" }),
                savedView({ id: 3, name: "Cooling accounts", recordType: "company" }),
            ],
            (recordType) => ({ person: "Contacts", company: "Companies", deal: "Deals" })[recordType],
        );

        expect(labels.get(1)).toBe("Contacts · My records");
        expect(labels.get(2)).toBe("Deals · My records");
        expect(labels.get(3)).toBe("Cooling accounts");
    });

    it("offers two views of the same name as two buttons the member can tell apart", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options({
                    savedViews: [
                        savedView({ id: 1, name: "My records", recordType: "person" }),
                        savedView({ id: 2, name: "My records", recordType: "deal" }),
                    ],
                })}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain("Contacts · My records");
        expect(markup).toContain("Deals · My records");
    });
});

describe("what the form says about names it could not read", () => {
    it("says the names are still coming rather than showing a workspace with none", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={NO_ASK_CONNEX_SCOPE_OPTIONS}
                optionsStatus="loading"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain("Loading the names this workspace offers");
        expect(markup).toContain('role="status"');
        expect(markup).not.toContain("Couldn&#x27;t load members, stages, and saved views.");
    });

    it("says a read failed, and offers it again, instead of settling into an empty workspace", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={NO_ASK_CONNEX_SCOPE_OPTIONS}
                optionsStatus="failed"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain("Couldn&#x27;t load members, stages, and saved views.");
        expect(markup).toContain("Try again");
        expect(markup).toContain('role="alert"');
    });

    it("says nothing at all once the names are there", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={NO_ASK_CONNEX_SCOPE_OPTIONS}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).not.toContain("Loading the names this workspace offers");
        expect(markup).not.toContain("Couldn&#x27;t load members, stages, and saved views.");
        expect(markup).not.toContain("Try again");
    });

    it("states an empty member list as empty, once it is known to be", () => {
        const membersMode = draft({ ownerMode: "members" });

        expect(render(
            <AskConnexScopeFields
                draft={membersMode}
                options={NO_ASK_CONNEX_SCOPE_OPTIONS}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        )).toContain("No members to choose from here.");
        expect(render(
            <AskConnexScopeFields
                draft={membersMode}
                options={NO_ASK_CONNEX_SCOPE_OPTIONS}
                optionsStatus="loading"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        )).not.toContain("No members to choose from here.");
    });
});

describe("what a screen reader hears in the filter form", () => {
    it("never names two nested groups the same thing", () => {
        const markup = render(
            <AskConnexScopeFields
                draft={EMPTY_ASK_CONNEX_SCOPE_DRAFT}
                options={options()}
                optionsStatus="ready"
                onChange={() => {}}
                onRetryOptions={() => {}}
            />,
        );

        expect(markup).toContain("<legend");
        expect(markup).toContain(">Period</legend>");
        expect(markup).toContain(">Owner</legend>");
        expect(markup).not.toContain('aria-label="Period"');
        expect(markup).not.toContain('aria-label="Owner"');
        expect(markup).toContain('aria-label="How to state the period"');
        expect(markup).toContain('aria-label="Whose records to cover"');
    });
});
