import { describe, expect, it } from "vitest";

import {
    activationGaps,
    buildActivationSteps,
    isActivated,
    selectFirstInsight,
    type ActivationCandidates,
    type ActivationCounts,
} from "@/app/lib/activation";
import type {
    Company,
    Contact,
    Deal,
    DealRisk,
    IntroSuggestion,
    RelationshipTemperature,
} from "@/app/lib/types";

const EMPTY_COUNTS: ActivationCounts = {
    contacts: 0,
    companies: 0,
    hasInteractions: false,
    hasRelationshipTargets: false,
    pipelines: 0,
    stages: 0,
    members: 1,
    connectedAccounts: 0,
    connectedCaptureReady: 0,
    connectedCaptureAvailable: false,
    connectedAccountsAvailable: false,
    canImportContacts: true,
    canImportCompanies: true,
    canCreateActivities: true,
    canManagePipelines: true,
    canManageMembers: true,
    canCreateTasks: true,
};

const FULL_COUNTS: ActivationCounts = {
    contacts: 12,
    companies: 4,
    hasInteractions: true,
    hasRelationshipTargets: true,
    pipelines: 1,
    stages: 5,
    members: 2,
    connectedAccounts: 0,
    connectedCaptureReady: 0,
    connectedCaptureAvailable: false,
    connectedAccountsAvailable: false,
    canImportContacts: true,
    canImportCompanies: true,
    canCreateActivities: true,
    canManagePipelines: true,
    canManageMembers: true,
    canCreateTasks: true,
};

const NO_CANDIDATES: ActivationCandidates = {
    dealRisks: [],
    coolingContacts: [],
    introSuggestions: [],
};

function deal(overrides: Partial<Deal> = {}): Deal {
    return {
        id: 1,
        name: "Renewal",
        value: 1000,
        actualValue: 0,
        currency: "JPY",
        pipeline: 1,
        stage: 2,
        position: 0,
        company: 7,
        createdAt: "2026-01-01 00:00:00",
        updatedAt: "2026-01-01 00:00:00",
        ...overrides,
    };
}

function company(overrides: Partial<Company> = {}): Company {
    return {
        id: 7,
        name: "Kaisha",
        website: "",
        industry: "",
        phone: "",
        address: "",
        logoUrl: "",
        createdAt: "2026-01-01 00:00:00",
        updatedAt: "2026-01-01 00:00:00",
        ...overrides,
    };
}

function contact(overrides: Partial<Contact> = {}): Contact {
    return {
        id: 3,
        name: "Sato Rin",
        email: "rin@example.jp",
        phone: "",
        title: "Head of Ops",
        imageUrl: "",
        createdAt: "2026-01-01 00:00:00",
        updatedAt: "2026-01-01 00:00:00",
        ...overrides,
    };
}

function risk(overrides: Partial<DealRisk> = {}): DealRisk {
    return {
        dealId: 1,
        level: "high",
        score: 80,
        factors: [{ code: "stalled", severity: "high", params: { days: 40 } }],
        assessedAt: "2026-07-01 00:00:00",
        value: 1000,
        currency: "JPY",
        ...overrides,
    };
}

function temperature(overrides: Partial<RelationshipTemperature> = {}): RelationshipTemperature {
    return {
        id: 3,
        score: 40,
        band: "cool",
        trend: "cooling",
        lastTouchAt: "2026-06-01 00:00:00",
        daysSinceTouch: 55,
        touchCount: 4,
        goesColdAt: "2026-08-01 00:00:00",
        daysUntilCold: 6,
        ...overrides,
    };
}

function suggestion(overrides: Partial<IntroSuggestion> = {}): IntroSuggestion {
    return {
        personAId: 1,
        personAName: "Aoki Mei",
        personBId: 2,
        personBName: "Kubo Taro",
        score: 10,
        reasons: ["shared_company"],
        mutualConnections: 0,
        sharedCompany: "Kaisha",
        asOf: "2026-07-26T00:00:00Z",
        supportingPersonIds: [],
        supportingEdgeIds: [],
        ...overrides,
    };
}

describe("buildActivationSteps", () => {
    it("marks every step undone for a brand-new workspace", () => {
        const steps = buildActivationSteps(EMPTY_COUNTS);
        expect(steps.every((step) => !step.done)).toBe(true);
        expect(isActivated(steps)).toBe(false);
    });

    it("derives completion from counts rather than a stored flag", () => {
        const steps = buildActivationSteps(FULL_COUNTS);
        const byId = new Map(steps.map((step) => [step.id, step]));
        expect(byId.get("contacts")?.done).toBe(true);
        expect(byId.get("contacts")?.count).toBe(12);
        expect(byId.get("companies")?.done).toBe(true);
        expect(byId.get("interactions")?.done).toBe(true);
        expect(byId.get("pipeline")?.done).toBe(true);
        expect(byId.get("team")?.done).toBe(true);
        expect(isActivated(steps)).toBe(true);
    });

    it("leaves the interaction step without a count because none can be counted exactly", () => {
        const steps = buildActivationSteps(FULL_COUNTS);
        const interaction = steps.find((step) => step.id === "interactions");
        expect(interaction?.count).toBeNull();
        expect(interaction?.requireRelationshipTarget).toBe(true);
    });

    it("hides the interaction action until a contact or deal can receive the evidence", () => {
        const steps = buildActivationSteps({
            ...EMPTY_COUNTS,
            canImportContacts: false,
        });
        expect(steps.some((step) => step.id === "interactions")).toBe(false);
    });

    it("treats a pipeline with no stages as unfinished", () => {
        const steps = buildActivationSteps({ ...FULL_COUNTS, stages: 0 });
        expect(steps.find((step) => step.id === "pipeline")?.done).toBe(false);
        expect(isActivated(steps)).toBe(false);
    });

    it("counts a single-member workspace as not yet shared", () => {
        const steps = buildActivationSteps({ ...FULL_COUNTS, members: 1 });
        expect(steps.find((step) => step.id === "team")?.done).toBe(false);
        expect(isActivated(steps)).toBe(true);
    });

    it("hides the team step when the member cannot invite teammates", () => {
        const steps = buildActivationSteps({
            ...FULL_COUNTS,
            members: 1,
            canManageMembers: false,
        });
        expect(steps.some((step) => step.id === "team")).toBe(false);
    });

    it("hides unfinished setup actions the member cannot perform", () => {
        const steps = buildActivationSteps({
            ...EMPTY_COUNTS,
            canImportContacts: false,
            canImportCompanies: false,
            canCreateActivities: false,
            canManagePipelines: false,
            canManageMembers: false,
        });
        expect(steps).toEqual([]);
    });

    it("treats permission-inaccessible pipeline setup as non-blocking", () => {
        const steps = buildActivationSteps({
            ...FULL_COUNTS,
            pipelines: 0,
            stages: 0,
            canManagePipelines: false,
        });
        expect(steps.some((step) => step.id === "pipeline")).toBe(false);
        expect(isActivated(steps)).toBe(true);
    });

    it("keeps completed steps visible without their mutation permission", () => {
        const steps = buildActivationSteps({
            ...FULL_COUNTS,
            canImportContacts: false,
            canImportCompanies: false,
            canCreateActivities: false,
            canManagePipelines: false,
            canManageMembers: false,
        });
        expect(steps.map((step) => step.id)).toEqual([
            "contacts",
            "companies",
            "interactions",
            "pipeline",
            "team",
        ]);
        expect(steps.every((step) => step.done)).toBe(true);
    });

    it("hides the mailbox step when the instance offers no provider", () => {
        expect(buildActivationSteps(FULL_COUNTS).some((step) => step.id === "connections")).toBe(false);
    });

    it("shows the mailbox step when a provider is configured", () => {
        const steps = buildActivationSteps({
            ...FULL_COUNTS,
            connectedAccountsAvailable: true,
            connectedAccounts: 1,
        });
        expect(steps.find((step) => step.id === "connections")?.done).toBe(true);
    });

    it("requires capture readiness instead of OAuth custody when capture is available", () => {
        const connectedOnly = buildActivationSteps({
            ...FULL_COUNTS,
            connectedAccountsAvailable: true,
            connectedAccounts: 1,
            connectedCaptureAvailable: true,
            connectedCaptureReady: 0,
        });
        expect(connectedOnly.find((step) => step.id === "connections")?.done).toBe(false);

        const captureReady = buildActivationSteps({
            ...FULL_COUNTS,
            connectedAccountsAvailable: true,
            connectedAccounts: 1,
            connectedCaptureAvailable: true,
            connectedCaptureReady: 1,
        });
        expect(captureReady.find((step) => step.id === "connections")?.done).toBe(true);
    });
});

describe("selectFirstInsight", () => {
    it("returns null when there are no candidates", () => {
        expect(selectFirstInsight(NO_CANDIDATES)).toBeNull();
    });

    it("refuses a deal risk that carries no factors", () => {
        expect(
            selectFirstInsight({
                ...NO_CANDIDATES,
                dealRisks: [{ deal: deal(), company: company(), risk: risk({ factors: [] }) }],
            }),
        ).toBeNull();
    });

    it("refuses a deal risk scored at level none", () => {
        expect(
            selectFirstInsight({
                ...NO_CANDIDATES,
                dealRisks: [{ deal: deal(), company: company(), risk: risk({ level: "none" }) }],
            }),
        ).toBeNull();
    });

    it("refuses a temperature with no recorded touch", () => {
        expect(
            selectFirstInsight({
                ...NO_CANDIDATES,
                coolingContacts: [
                    {
                        contact: contact(),
                        temperature: temperature({
                            lastTouchAt: null,
                            touchCount: 0,
                            goesColdAt: null,
                        }),
                    },
                ],
            }),
        ).toBeNull();
    });

    it("refuses an introduction with neither a mutual connection nor a shared company", () => {
        expect(
            selectFirstInsight({
                ...NO_CANDIDATES,
                introSuggestions: [suggestion({ mutualConnections: 0, sharedCompany: null })],
            }),
        ).toBeNull();
    });

    it("cites every risk factor as evidence and prefers deal risk over the rest", () => {
        const insight = selectFirstInsight({
            dealRisks: [{ deal: deal(), company: company(), risk: risk() }],
            coolingContacts: [{ contact: contact(), temperature: temperature() }],
            introSuggestions: [suggestion()],
        });
        expect(insight?.kind).toBe("dealRisk");
        expect(insight?.evidence).toEqual([
            { kind: "riskFactor", factor: { code: "stalled", severity: "high", params: { days: 40 } } },
        ]);
        expect(insight?.href).toBe("/records/deals/1");
        expect(insight?.record).toEqual({ type: "deal", id: 1, label: "Renewal" });
    });

    it("cites the stored touch history behind a cooling relationship", () => {
        const insight = selectFirstInsight({
            ...NO_CANDIDATES,
            coolingContacts: [{ contact: contact(), temperature: temperature() }],
        });
        expect(insight?.kind).toBe("coolingContact");
        expect(insight?.evidence).toEqual([
            { kind: "lastTouch", at: "2026-06-01 00:00:00" },
            { kind: "touchCount", count: 4 },
            { kind: "goesCold", at: "2026-08-01 00:00:00" },
        ]);
        expect(insight?.record).toEqual({ type: "person", id: 3, label: "Sato Rin" });
    });

    it("falls back to an introduction path that a plain import can already justify", () => {
        const insight = selectFirstInsight({
            ...NO_CANDIDATES,
            introSuggestions: [suggestion({ mutualConnections: 2 })],
        });
        expect(insight?.kind).toBe("introPath");
        expect(insight?.evidence).toEqual([
            { kind: "mutualConnections", count: 2 },
            { kind: "sharedCompany", company: "Kaisha" },
        ]);
        expect(insight?.record).toBeNull();
    });

    it("never returns an insight without evidence", () => {
        const candidates: ActivationCandidates = {
            dealRisks: [{ deal: deal(), company: null, risk: risk({ factors: [] }) }],
            coolingContacts: [
                {
                    contact: contact(),
                    temperature: temperature({ lastTouchAt: null, touchCount: 0, goesColdAt: null }),
                },
            ],
            introSuggestions: [suggestion({ mutualConnections: 0, sharedCompany: null })],
        };
        const insight = selectFirstInsight(candidates);
        expect(insight).toBeNull();
    });
});

describe("activationGaps", () => {
    it("names the missing inputs for an empty workspace", () => {
        expect(activationGaps(EMPTY_COUNTS, false)).toEqual(["contacts", "interactions"]);
    });

    it("names only the interaction gap once contacts exist", () => {
        expect(activationGaps({ ...EMPTY_COUNTS, contacts: 9 }, false)).toEqual(["interactions"]);
    });

    it("says the data is in but nothing rises to a signal", () => {
        expect(activationGaps(FULL_COUNTS, false)).toEqual(["noSignal"]);
    });

    it("reports no gaps once a signal exists", () => {
        expect(activationGaps(EMPTY_COUNTS, true)).toEqual([]);
    });

    it("reports unavailable instead of inventing a gap when signal loading fails", () => {
        expect(activationGaps(FULL_COUNTS, false, false)).toEqual(["unavailable"]);
    });

    it("reports unavailable when checklist inputs fail even if one signal loaded", () => {
        expect(activationGaps(FULL_COUNTS, true, false)).toEqual(["unavailable"]);
    });
});
