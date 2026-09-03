import { isValidElement, type PropsWithChildren } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import CampaignDetailPage from "@/app/(app)/marketing/campaigns/[id]/page";
import CampaignDelivery from "@/app/components/marketing/campaigns/CampaignDelivery";
import CampaignDetail from "@/app/components/marketing/campaigns/CampaignDetail";
import CampaignExportPanel from "@/app/components/marketing/campaigns/CampaignExportPanel";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import type { CampaignAccess } from "@/app/lib/campaignAccess";
import type { Campaign, InstanceCapabilities, User } from "@/app/lib/types";

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=session; connex_workspace=7",
    })),
}));

vi.mock("next/navigation", () => ({
    notFound: vi.fn((message?: string): never => {
        throw new Error(message ?? "not-found");
    }),
    redirect: vi.fn((destination: string): never => {
        throw new Error(`redirect:${destination}`);
    }),
    useRouter: () => ({ refresh: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

vi.mock("@/app/components/overview/analytics/Panel", async () => {
    const React = await import("react");
    return {
        default: ({
            action,
            children,
            subtitle,
            title,
        }: PropsWithChildren<{ action?: React.ReactNode; subtitle?: string; title: string }>) =>
            React.createElement(
                "section",
                null,
                React.createElement("h2", null, title),
                subtitle ? React.createElement("p", null, subtitle) : null,
                action,
                children,
            ),
    };
});

vi.mock("@/app/components/marketing/campaigns/NewMessageDialog", () => ({
    default: () => null,
}));

const USER = {
    id: 9,
    username: "member",
    displayName: "Member",
    email: "member@connex.test",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    timezone: "UTC",
    locale: "en",
} satisfies User;

const CAMPAIGN = {
    id: 1,
    name: "Trial campaign",
    objective: null,
    type: "email",
    status: "draft",
    ownerUserId: null,
    budgetAmount: null,
    budgetCurrency: null,
    startAt: null,
    endAt: null,
    parentCampaignId: null,
    createdById: USER.id,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
} satisfies Campaign;

const DISABLED_CAPABILITIES = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: false, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
    privilegedMfaEnforced: true,
} satisfies InstanceCapabilities;

const READ_ONLY_ACCESS = {
    manage: false,
    send: false,
    consent: false,
} satisfies CampaignAccess;

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubCampaignReads(
    capabilities: InstanceCapabilities | null,
    audienceUnavailable = false,
) {
    vi.stubGlobal("fetch", vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) return Promise.resolve(json(USER));
        if (url.endsWith("/api/campaigns/1/audience/snapshots")) return Promise.resolve(json([]));
        if (url.endsWith("/api/campaigns/1/audience")) {
            return Promise.resolve(audienceUnavailable
                ? new Response("", { status: 503 })
                : new Response(null, { status: 204 }));
        }
        if (url.endsWith("/api/campaigns/1/messages")) return Promise.resolve(json([]));
        if (url.endsWith("/api/campaigns/1/sends")) return Promise.resolve(json([]));
        if (url.endsWith("/api/campaigns/1/exports")) return Promise.resolve(json([]));
        if (url.endsWith("/api/campaigns/1/engagement")) return Promise.resolve(new Response("", { status: 503 }));
        if (url.endsWith("/api/permissions/effective")) return Promise.resolve(json([]));
        if (url.endsWith("/api/capabilities")) {
            return Promise.resolve(capabilities === null
                ? new Response("", { status: 503 })
                : json(capabilities));
        }
        if (url.endsWith("/api/campaigns/1")) return Promise.resolve(json(CAMPAIGN));
        return Promise.resolve(new Response("", { status: 404 }));
    }));
}

function hasDeliveryAvailability(value: unknown): value is {
    deliveryAvailability: CapabilityAvailability;
} {
    return typeof value === "object"
        && value !== null
        && "deliveryAvailability" in value
        && (value.deliveryAvailability === "enabled"
            || value.deliveryAvailability === "disabled"
            || value.deliveryAvailability === "unavailable");
}

function deliveryHtml(deliveryAvailability: CapabilityAvailability): string {
    return renderToStaticMarkup(
        <CampaignDelivery
            campaignId={CAMPAIGN.id}
            initialMessages={[]}
            initialSends={[]}
            snapshots={[]}
            access={READ_ONLY_ACCESS}
            deliveryAvailability={deliveryAvailability}
        />,
    );
}

function exportHtml(deliveryAvailability: CapabilityAvailability): string {
    return renderToStaticMarkup(
        <CampaignExportPanel
            campaignId={CAMPAIGN.id}
            initialExports={[]}
            snapshots={[]}
            access={READ_ONLY_ACCESS}
            deliveryAvailability={deliveryAvailability}
        />,
    );
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe("campaign delivery capability honesty", () => {
    it("preserves an audience lookup failure instead of presenting an empty audience", async () => {
        stubCampaignReads(DISABLED_CAPABILITIES, true);

        const rendered = await CampaignDetailPage({ params: Promise.resolve({ id: "1" }) });

        expect(isValidElement(rendered)).toBe(true);
        if (!isValidElement(rendered)) {
            throw new Error("Campaign detail did not render");
        }
        expect(rendered.type).toBe(CampaignDetail);
        expect(rendered.props).toMatchObject({
            initialAudience: null,
            audienceUnavailable: true,
        });
    });

    it("keeps campaign authoring and history available while delivery controls show retryable lookup failure", async () => {
        stubCampaignReads(null);

        const rendered = await CampaignDetailPage({ params: Promise.resolve({ id: "1" }) });
        if (!isValidElement(rendered) || !hasDeliveryAvailability(rendered.props)) {
            throw new Error("Campaign detail did not render the expected capability contract");
        }
        const sends = deliveryHtml(rendered.props.deliveryAvailability);
        const exports = exportHtml(rendered.props.deliveryAvailability);

        expect(rendered.type).toBe(CampaignDetail);
        expect(rendered.props.deliveryAvailability).toBe("unavailable");
        expect(sends).toContain("CampaignMessages.title");
        expect(sends).toContain("CapabilityUnavailable.title");
        expect(sends).toContain("CapabilityUnavailable.retry");
        expect(exports).toContain("CapabilityUnavailable.title");
        expect(exports).toContain("CapabilityUnavailable.retry");
    });

    it("renders the resolved delivery-disabled fact without a lookup-failure state", async () => {
        stubCampaignReads(DISABLED_CAPABILITIES);

        const rendered = await CampaignDetailPage({ params: Promise.resolve({ id: "1" }) });
        if (!isValidElement(rendered) || !hasDeliveryAvailability(rendered.props)) {
            throw new Error("Campaign detail did not render the expected capability contract");
        }
        const sends = deliveryHtml(rendered.props.deliveryAvailability);
        const exports = exportHtml(rendered.props.deliveryAvailability);

        expect(rendered.props.deliveryAvailability).toBe("disabled");
        expect(sends).toContain("CampaignSends.deliveryUnavailable");
        expect(sends).not.toContain("CapabilityUnavailable.title");
        expect(exports).toContain("CampaignExports.exportUnavailable");
        expect(exports).not.toContain("CapabilityUnavailable.title");
    });
});
