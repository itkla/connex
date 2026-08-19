import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { loadActivationExtras } from "@/app/(app)/dashboard/page";
import SetupChecklist from "@/app/components/dashboard/activation/SetupChecklist";
import {
    buildActivationSteps,
    type ActivationCounts,
} from "@/app/lib/activation";
import type { InstanceCapabilities } from "@/app/lib/types";

vi.mock("next/navigation", () => ({
    redirect: vi.fn(),
    useRouter: () => ({ refresh: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

vi.mock("next-intl/server", () => ({
    getTranslations: (namespace: string) =>
        Promise.resolve((key: string) => `${namespace}.${key}`),
}));

vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({
        getAction: () => null,
        openOverlay: vi.fn(),
        pendingIds: new Set<string>(),
        run: vi.fn(),
    }),
}));

const DISABLED_CAPABILITIES = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: false, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
} satisfies InstanceCapabilities;

const SETUP_COUNTS = {
    contacts: 0,
    companies: 0,
    hasInteractions: false,
    hasRelationshipTargets: false,
    pipelines: 0,
    stages: 0,
} satisfies Pick<
    ActivationCounts,
    "contacts" | "companies" | "hasInteractions" | "hasRelationshipTargets" | "pipelines" | "stages"
>;

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubActivationReads(capabilities: InstanceCapabilities | null) {
    vi.stubGlobal("fetch", vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/workspaces/7/members")) return Promise.resolve(json([]));
        if (url.endsWith("/api/permissions/effective")) {
            return Promise.resolve(json([
                "PERSON_CREATE",
                "COMPANY_CREATE",
                "ACTIVITY_CREATE",
                "PIPELINE_MANAGE",
                "MEMBER_MANAGE",
                "TASK_CREATE",
            ]));
        }
        if (url.endsWith("/api/capabilities")) {
            return Promise.resolve(capabilities === null
                ? new Response("", { status: 503 })
                : json(capabilities));
        }
        return Promise.resolve(new Response("", { status: 404 }));
    }));
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("dashboard activation capability honesty", () => {
    it("keeps resolved setup steps and makes only the mailbox step retryable after lookup failure", async () => {
        stubActivationReads(null);

        const result = await loadActivationExtras("JSESSIONID=session; connex_workspace=7");
        if (!result.ok) throw new Error("Activation extras unexpectedly failed as a whole");
        const counts: ActivationCounts = { ...SETUP_COUNTS, ...result.data };
        const steps = buildActivationSteps(counts);
        const connectionStep = steps.find((step) => step.id === "connections");
        const html = renderToStaticMarkup(<SetupChecklist steps={steps} journey={null} />);

        expect(result.data.connectedAccountsAvailability).toBe("unavailable");
        expect(connectionStep?.availability).toBe("unavailable");
        expect(html).toContain("DashboardActivation.steps.contacts.title");
        expect(html).toContain("DashboardActivation.steps.connections.title");
        expect(html).toContain("CapabilityUnavailable.body");
        expect(html).toContain("CapabilityUnavailable.retry");
    });

    it("omits the mailbox step as disabled without rendering lookup-failure copy when providers resolve false", async () => {
        stubActivationReads(DISABLED_CAPABILITIES);

        const result = await loadActivationExtras("JSESSIONID=session; connex_workspace=7");
        if (!result.ok) throw new Error("Activation extras unexpectedly failed as a whole");
        const counts: ActivationCounts = { ...SETUP_COUNTS, ...result.data };
        const steps = buildActivationSteps(counts);
        const html = renderToStaticMarkup(<SetupChecklist steps={steps} journey={null} />);

        expect(result.data.connectedAccountsAvailability).toBe("disabled");
        expect(steps.some((step) => step.id === "connections")).toBe(false);
        expect(html).toContain("DashboardActivation.steps.contacts.title");
        expect(html).not.toContain("DashboardActivation.steps.connections.title");
        expect(html).not.toContain("CapabilityUnavailable.body");
    });
});
