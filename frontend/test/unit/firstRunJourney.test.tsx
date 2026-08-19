import { readFileSync } from "node:fs";
import { resolve } from "node:path";

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
    resolveFirstRunEntry,
    warmthArrival,
} from "@/app/lib/firstRunJourney";
import type { FacetCount } from "@/app/lib/types";

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
        actions: [],
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

const WARMED: ActivationCounts = {
    ...EMPTY_COUNTS,
    contacts: 6,
    hasRelationshipTargets: true,
    hasInteractions: true,
};

function facets(entries: Record<string, number>): FacetCount[] {
    return Object.entries(entries).map(([key, count]) => ({ key, count }));
}

describe("resolveFirstRunEntry", () => {
    it("offers both doors to a workspace with nobody in it", () => {
        expect(resolveFirstRunEntry(EMPTY_COUNTS, false)).toEqual({
            doors: ["import", "create"],
            cardScanning: false,
        });
    });

    it("mentions the card scanner only on an instance that can read cards", () => {
        expect(resolveFirstRunEntry(EMPTY_COUNTS, true)?.cardScanning).toBe(true);
        expect(resolveFirstRunEntry(EMPTY_COUNTS, false)?.cardScanning).toBe(false);
    });

    it("guides nobody who cannot create the contacts it would ask for", () => {
        expect(resolveFirstRunEntry({ ...EMPTY_COUNTS, canImportContacts: false }, true)).toBeNull();
        expect(firstRunDoors(false)).toEqual([]);
    });

    it("retires once contacts exist", () => {
        expect(resolveFirstRunEntry({ ...EMPTY_COUNTS, contacts: 1 }, true)).toBeNull();
    });
});

describe("warmthArrival", () => {
    it("claims nothing when the warmth facet was not requested", () => {
        expect(warmthArrival(undefined)).toBeNull();
    });

    it("refuses an arrival when every contact is untouched", () => {
        expect(warmthArrival(facets({ __none__: 20 }))).toBeNull();
        expect(warmthArrival(facets({ __none__: 20, hot: 0, warm: 0, cool: 0, cold: 0 }))).toBeNull();
    });

    it("counts only the contacts a recorded interaction reached", () => {
        expect(warmthArrival(facets({ warm: 1, __none__: 19 }))).toEqual({
            hot: 0,
            warm: 1,
            cool: 0,
            cold: 0,
        });
    });

    it("keeps a genuinely cold reading, which is not the same as no history", () => {
        expect(warmthArrival(facets({ cold: 2, __none__: 3 }))).toEqual({
            hot: 0,
            warm: 0,
            cool: 0,
            cold: 2,
        });
    });

    it("ignores a band it does not recognize", () => {
        expect(warmthArrival(facets({ lukewarm: 4 }))).toBeNull();
    });
});

describe("the checklist's contacts step", () => {
    it("offers the entry's doors instead of its single call to action", () => {
        const html = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(EMPTY_COUNTS)}
                entry={resolveFirstRunEntry(EMPTY_COUNTS, false)}
            />,
        );

        expect(html).toContain("DashboardActivation.steps.contacts.cta");
        expect(html).toContain("FirstRunJourney.newContact");
    });

    it("promises card scanning only on an instance that can scan", () => {
        const scanning = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(EMPTY_COUNTS)}
                entry={resolveFirstRunEntry(EMPTY_COUNTS, true)}
            />,
        );
        const withoutScanning = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(EMPTY_COUNTS)}
                entry={resolveFirstRunEntry(EMPTY_COUNTS, false)}
            />,
        );

        expect(scanning).toContain("DashboardActivation.steps.contacts.bodyScanning");
        expect(withoutScanning).not.toContain("DashboardActivation.steps.contacts.bodyScanning");
        expect(withoutScanning).toContain("DashboardActivation.steps.contacts.body");
    });

    it("keeps its plain call to action and drops the create door when there is no entry", () => {
        const html = renderToStaticMarkup(
            <SetupChecklist steps={buildActivationSteps(EMPTY_COUNTS)} entry={null} />,
        );

        expect(html).toContain("DashboardActivation.steps.contacts.cta");
        expect(html).not.toContain("FirstRunJourney.newContact");
    });

    it("drops the doors once the step is done rather than repeating the invitation", () => {
        const html = renderToStaticMarkup(
            <SetupChecklist
                steps={buildActivationSteps(WARMED)}
                entry={resolveFirstRunEntry(WARMED, true)}
            />,
        );

        expect(html).not.toContain("FirstRunJourney.newContact");
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

    function panel(
        overrides: {
            insight?: ActivationInsight | null;
            bands?: FacetCount[] | undefined;
            gaps?: Parameters<typeof ActivationPanel>[0]["gaps"];
        } = {},
    ): string {
        return renderToStaticMarkup(
            <ActivationPanel
                steps={null}
                entry={null}
                insight={overrides.insight ?? null}
                warmthReadings={warmthArrival(overrides.bands)}
                gaps={overrides.gaps ?? ["noSignal"]}
                canCreateFollowUp
            />,
        );
    }

    it("shows the first warmth reading instead of claiming there is no signal", () => {
        const html = panel({ bands: facets({ warm: 1 }) });

        expect(html).toContain("FirstRunJourney.warmth.title");
        expect(html).toContain("FirstRunJourney.warmth.cta");
        expect(html).toContain("/records/contacts?sort=warmth&amp;dir=desc");
        expect(html).not.toContain("DashboardActivation.missing.noSignalTitle");
    });

    it("claims no arrival for an import whose contacts nobody has interacted with", () => {
        const html = panel({ bands: facets({ __none__: 20 }) });

        expect(html).not.toContain("FirstRunJourney.warmth.title");
        expect(html).toContain("DashboardActivation.missing.noSignalTitle");
    });

    it("names only the touched contacts, never the ones with no history", () => {
        const html = panel({ bands: facets({ warm: 1, __none__: 19 }) });

        expect(html).toContain("Temperature.warm");
        expect(html).not.toContain("Temperature.cold");
        expect(html).not.toContain("19");
    });

    it("lets a triage-worthy signal outrank the arrival", () => {
        const html = panel({ insight, bands: facets({ warm: 1 }), gaps: [] });

        expect(html).toContain("DashboardActivation.insight.coolingContact.headline");
        expect(html).not.toContain("FirstRunJourney.warmth.title");
    });

    it("says the signals could not load rather than inventing an arrival", () => {
        const html = panel({ bands: facets({ warm: 1 }), gaps: ["unavailable"] });

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
                    doors={["import", "create"]}
                    importLabel="Actions.utility.importContacts"
                    createLabel="ContactsBrowser.emptyCta"
                    onImport={() => {}}
                    onCreate={() => {}}
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
        expect(html).toContain("Actions.utility.importContacts");
        expect(html).not.toContain("RecordsRenderView.clearFilters");
    });

    it("offers to clear the filter rather than the journey when a filter emptied the list", () => {
        const html = render(true);

        expect(html).toContain("RecordsRenderView.clearFilters");
        expect(html).not.toContain("Actions.utility.importContacts");
        expect(html).not.toContain("ContactsBrowser.emptyJourneyBody");
    });
});

describe("the doors survive a hard load", () => {
    const BROWSERS = [
        ["contacts", "app/components/records/contacts/ContactsBrowser.tsx"],
        ["companies", "app/components/records/companies/CompaniesBrowser.tsx"],
    ] as const;

    it.each(BROWSERS)(
        "recomputes the %s doors when the action registry seeds after mount",
        (_entity, file) => {
            const source = readFileSync(resolve(process.cwd(), file), "utf8");
            const memo = /const firstRunEntryDoors = useMemo\([\s\S]*?\n {8}\[([^\]]*)\],\n {4}\);/.exec(source);

            expect(memo, `${file} no longer resolves its first-run doors in a useMemo`).not.toBeNull();
            expect(
                memo?.[1].split(",").map((dependency) => dependency.trim()),
                `${file} memoizes the first-run doors without the action registry: the registry is `
                + "seeded in an effect, so a hard load would resolve against an empty registry, drop "
                + "the import door, and never recompute it.",
            ).toContain("actions");
        },
    );
});

describe("the doors themselves", () => {
    function doors(list: Parameters<typeof FirstRunDoors>[0]["doors"]): string {
        return renderToStaticMarkup(
            <FirstRunDoors
                doors={list}
                importLabel="Actions.utility.importContacts"
                createLabel="ContactsBrowser.emptyCta"
                onImport={() => {}}
                onCreate={() => {}}
            />,
        );
    }

    it("renders nothing at all for a member who cannot create records", () => {
        expect(doors(firstRunDoors(false))).toBe("");
    });

    it("names each door as the action behind it is named everywhere else", () => {
        const html = doors(firstRunDoors(true));

        expect(html).toContain("Actions.utility.importContacts");
        expect(html).toContain("ContactsBrowser.emptyCta");
    });

    it("keeps the create door when the import action has not been registered yet", () => {
        const html = doors(["create"]);

        expect(html).toContain("ContactsBrowser.emptyCta");
        expect(html).not.toContain("Actions.utility.importContacts");
    });
});
