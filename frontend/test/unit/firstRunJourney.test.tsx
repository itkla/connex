import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import ActivationPanel from "@/app/components/dashboard/activation/ActivationPanel";
import SetupChecklist from "@/app/components/dashboard/activation/SetupChecklist";
import RecordsRenderView from "@/app/components/records/RecordsRenderView";
import FirstRunDoors from "@/app/components/FirstRunDoors";
import { EmptyState } from "@/app/components/EmptyState";
import { buildActivationSteps, type ActivationCounts, type ActivationInsight } from "@/app/lib/activation";
import {
    firstRunDoors,
    resolveFirstRunJourney,
    warmthReadings,
} from "@/app/lib/firstRunJourney";
import type { WarmthSummary } from "@/app/lib/types";

vi.mock("next/navigation", () => ({
    redirect: vi.fn(),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
    useLocale: () => "en",
}));

vi.mock("motion/react", async () => {
    const react = await import("react");
    const passthrough = new Proxy(
        {},
        {
            get: (_target, tag: string) => (props: Record<string, unknown>) =>
                react.createElement(tag, stripMotionProps(props)),
        },
    );
    return {
        motion: passthrough,
        useReducedMotion: () => true,
        AnimatePresence: ({ children }: { children?: React.ReactNode }) => children ?? null,
    };
});

vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({
        getAction: () => ({ id: "stub" }),
        openOverlay: vi.fn(),
        pendingIds: new Set<string>(),
        run: vi.fn(),
    }),
}));

const MOTION_ONLY_PROPS = new Set([
    "initial",
    "animate",
    "exit",
    "transition",
    "whileHover",
    "whileTap",
    "layout",
]);

function stripMotionProps(props: Record<string, unknown>): Record<string, unknown> {
    return Object.fromEntries(
        Object.entries(props).filter(([key]) => !MOTION_ONLY_PROPS.has(key)),
    );
}

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
    connectedAccountsAvailability: "disabled",
    canImportContacts: true,
    canImportCompanies: true,
    canCreateActivities: true,
    canManagePipelines: true,
    canManageMembers: true,
    canCreateTasks: true,
};

const PEOPLE_IN: ActivationCounts = {
    ...EMPTY_COUNTS,
    contacts: 6,
    hasRelationshipTargets: true,
};

const WARMED: ActivationCounts = { ...PEOPLE_IN, hasInteractions: true };

const EMPTY_WARMTH: WarmthSummary = {
    contacts: { hot: 0, warm: 0, cool: 0, cold: 0 },
    companies: { hot: 0, warm: 0, cool: 0, cold: 0 },
    contactTrends: { rising: 0, steady: 0, cooling: 0 },
    contactDecay: { soon: 0, mid: 0, later: 0 },
};

const READ_WARMTH: WarmthSummary = {
    ...EMPTY_WARMTH,
    contacts: { hot: 1, warm: 2, cool: 0, cold: 0 },
};

describe("resolveFirstRunJourney", () => {
    it("puts a workspace with nobody in it on the contacts leg with both doors", () => {
        expect(resolveFirstRunJourney(EMPTY_COUNTS, false)).toEqual({
            leg: "contacts",
            doors: ["importCsv", "newContact"],
            cardScanning: false,
        });
    });

    it("offers the card scanner only on an instance that can read cards", () => {
        expect(resolveFirstRunJourney(EMPTY_COUNTS, true)?.cardScanning).toBe(true);
        expect(resolveFirstRunJourney(EMPTY_COUNTS, false)?.cardScanning).toBe(false);
    });

    it("guides nobody who cannot create the contacts it would ask for", () => {
        expect(resolveFirstRunJourney({ ...EMPTY_COUNTS, canImportContacts: false }, true)).toBeNull();
        expect(firstRunDoors(false)).toEqual([]);
    });

    it("moves to the evidence leg once people are in", () => {
        expect(resolveFirstRunJourney(PEOPLE_IN, false)).toEqual({
            leg: "evidence",
            doors: [],
            cardScanning: false,
        });
    });

    it("guides nobody who cannot log the interaction it would ask for", () => {
        expect(resolveFirstRunJourney({ ...PEOPLE_IN, canCreateActivities: false }, false)).toBeNull();
        expect(resolveFirstRunJourney({ ...PEOPLE_IN, hasRelationshipTargets: false }, false)).toBeNull();
    });

    it("arrives at warmth once an interaction is recorded", () => {
        expect(resolveFirstRunJourney(WARMED, false)?.leg).toBe("warmth");
    });
});

describe("warmthReadings", () => {
    it("refuses a reading with no recorded interaction behind it", () => {
        expect(warmthReadings(READ_WARMTH, false)).toBeNull();
    });

    it("refuses a reading that covers nobody", () => {
        expect(warmthReadings(EMPTY_WARMTH, true)).toBeNull();
    });

    it("returns the contact bands once recorded interactions back them", () => {
        expect(warmthReadings(READ_WARMTH, true)).toEqual({ hot: 1, warm: 2, cool: 0, cold: 0 });
    });
});

describe("the checklist's contacts step", () => {
    it("offers the journey's doors instead of its single call to action", () => {
        const journey = resolveFirstRunJourney(EMPTY_COUNTS, false);
        const html = renderToStaticMarkup(
            <SetupChecklist steps={buildActivationSteps(EMPTY_COUNTS)} journey={journey} />,
        );

        expect(html).toContain("FirstRunJourney.doors.importCsv");
        expect(html).toContain("FirstRunJourney.doors.newContact");
        expect(html).not.toContain("DashboardActivation.steps.contacts.cta");
    });

    it("promises card scanning only on an instance that can scan", () => {
        const scanning = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(EMPTY_COUNTS)}
                journey={resolveFirstRunJourney(EMPTY_COUNTS, true)}
            />,
        );
        const withoutScanning = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(EMPTY_COUNTS)}
                journey={resolveFirstRunJourney(EMPTY_COUNTS, false)}
            />,
        );

        expect(scanning).toContain("DashboardActivation.steps.contacts.bodyScanning");
        expect(withoutScanning).not.toContain("DashboardActivation.steps.contacts.bodyScanning");
        expect(withoutScanning).toContain("DashboardActivation.steps.contacts.body");
    });

    it("keeps its plain call to action when there is no journey to guide", () => {
        const html = renderToStaticMarkup(
            <SetupChecklist steps={buildActivationSteps(EMPTY_COUNTS)} journey={null} />,
        );

        expect(html).toContain("DashboardActivation.steps.contacts.cta");
        expect(html).not.toContain("FirstRunJourney.doors.importCsv");
    });

    it("drops the doors once the step is done rather than repeating the invitation", () => {
        const html = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(WARMED)}
                journey={resolveFirstRunJourney(WARMED, true)}
            />,
        );

        expect(html).not.toContain("FirstRunJourney.doors.importCsv");
        expect(html).toContain("DashboardActivation.steps.contacts.done");
    });
});

describe("the activation panel's ending", () => {
    const insight: ActivationInsight = {
        kind: "coolingContact",
        title: "Sato Rin",
        subtitle: null,
        href: "/records/contacts/3",
        record: { type: "person", id: 3, label: "Sato Rin" },
        risk: null,
        temperature: null,
        evidence: [{ kind: "touchCount", count: 4 }],
    };

    it("shows the first warmth reading instead of claiming there is no signal", () => {
        const html = renderToStaticMarkup(
            <ActivationPanel
                steps={null}
                journey={null}
                insight={null}
                warmthReadings={{ hot: 1, warm: 2, cool: 0, cold: 0 }}
                gaps={["noSignal"]}
                canCreateFollowUp
            />,
        );

        expect(html).toContain("FirstRunJourney.warmth.title");
        expect(html).toContain("FirstRunJourney.warmth.cta");
        expect(html).toContain("/records/contacts");
        expect(html).not.toContain("DashboardActivation.missing.noSignalTitle");
    });

    it("names only the bands somebody is actually in", () => {
        const html = renderToStaticMarkup(
            <ActivationPanel
                steps={null}
                journey={null}
                insight={null}
                warmthReadings={{ hot: 1, warm: 0, cool: 0, cold: 0 }}
                gaps={["noSignal"]}
                canCreateFollowUp
            />,
        );

        expect(html).toContain("Temperature.hot");
        expect(html).not.toContain("Temperature.cold");
    });

    it("lets a triage-worthy signal outrank the arrival", () => {
        const html = renderToStaticMarkup(
            <ActivationPanel
                steps={null}
                journey={null}
                insight={insight}
                warmthReadings={{ hot: 1, warm: 2, cool: 0, cold: 0 }}
                gaps={[]}
                canCreateFollowUp
            />,
        );

        expect(html).toContain("DashboardActivation.insight.coolingContact.headline");
        expect(html).not.toContain("FirstRunJourney.warmth.title");
    });

    it("says the signals could not load rather than inventing an arrival", () => {
        const html = renderToStaticMarkup(
            <ActivationPanel
                steps={null}
                journey={null}
                insight={null}
                warmthReadings={{ hot: 1, warm: 2, cool: 0, cold: 0 }}
                gaps={["unavailable"]}
                canCreateFollowUp
            />,
        );

        expect(html).toContain("DashboardActivation.missing.unavailableTitle");
        expect(html).not.toContain("FirstRunJourney.warmth.title");
    });
});

describe("first-run and filtered-empty stay distinct", () => {
    const journeyEmptyState = (
        <EmptyState
            icon={() => null}
            title="ContactsBrowser.emptyTitle"
            body="ContactsBrowser.emptyJourneyBody"
            action={
                <FirstRunDoors
                    doors={["importCsv", "newContact"]}
                    onImport={() => {}}
                    onNew={() => {}}
                />
            }
        />
    );

    function render(filtersActive: boolean): string {
        return renderToStaticMarkup(
            <RecordsRenderView
                data={[]}
                columns={[]}
                renderCard={() => null}
                displayMode="table"
                selectedIds={new Set()}
                onSelectedIdsChange={() => {}}
                entityLabel="contact"
                emptyState={journeyEmptyState}
                filtersActive={filtersActive}
                onClearFilters={() => {}}
            />,
        );
    }

    it("teaches the journey on a genuine first run", () => {
        const html = render(false);

        expect(html).toContain("ContactsBrowser.emptyJourneyBody");
        expect(html).toContain("FirstRunJourney.doors.importCsv");
        expect(html).not.toContain("RecordsRenderView.clearFilters");
    });

    it("offers to clear the filter rather than the journey when a filter emptied the list", () => {
        const html = render(true);

        expect(html).toContain("RecordsRenderView.clearFilters");
        expect(html).not.toContain("FirstRunJourney.doors.importCsv");
        expect(html).not.toContain("ContactsBrowser.emptyJourneyBody");
    });
});
