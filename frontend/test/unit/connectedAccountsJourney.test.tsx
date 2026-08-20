import { readFileSync } from "node:fs";
import path from "node:path";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { NowProvider } from "@/app/hooks/useNow";
import {
    CAPTURE_PANELS,
    capturePanelRequiresCapture,
    captureConnectionsHref,
    lastCaptureSuccessAt,
    parseCaptureRouteState,
    providerJourneyEnabled,
    providerJourneyState,
    rememberPendingAuthorization,
    takePendingAuthorization,
} from "@/app/lib/connectedCapture";
import type {
    CaptureStreamState,
    InstanceCapabilities,
    ProviderCaptureOverview,
    ProviderConnection,
    ProviderConnectionStatus,
} from "@/app/lib/types";

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
    useLocale: () => "en",
}));

function source(file: string): string {
    return readFileSync(path.join(process.cwd(), file), "utf8");
}

function messages(locale: "en" | "ja"): Record<string, Record<string, string>> {
    return JSON.parse(source(path.join("messages", locale, "account.json")));
}

function connection(overrides: Partial<ProviderConnection> = {}): ProviderConnection {
    return {
        provider: "google",
        status: "connected",
        providerAccountEmail: "hunter@example.com",
        hasCredential: true,
        createdAt: "2026-08-01T00:00:00Z",
        updatedAt: "2026-08-01T00:00:00Z",
        ...overrides,
    };
}

function stream(overrides: Partial<CaptureStreamState> = {}): CaptureStreamState {
    return {
        stream: "calendar",
        status: "idle",
        processedItems: 0,
        estimatedItems: null,
        lastAttemptAt: null,
        lastSuccessAt: null,
        nextAttemptAt: null,
        errorCode: null,
        ...overrides,
    };
}

function overview(streams: CaptureStreamState[]): ProviderCaptureOverview {
    return {
        provider: "google",
        userPolicy: {
            enabled: true,
            calendar: true,
            mailInbox: true,
            mailSent: false,
            backfillDays: 90,
            includeBodies: false,
            admissionMode: "review",
            reviewBeforeCapture: true,
            excludedPeople: [],
            excludedConversations: [],
            version: 1,
            updatedAt: null,
        },
        workspacePolicy: {
            allowed: true,
            calendar: true,
            mailInbox: true,
            mailSent: true,
            maxBackfillDays: 180,
            bodyCaptureAllowed: false,
            reviewRequired: true,
            excludePrivateEvents: true,
            excludeInternalOnly: false,
            excludedDomains: [],
            version: 1,
            updatedAt: null,
        },
        effectivePolicy: {
            enabled: true,
            calendar: true,
            mailInbox: true,
            mailSent: false,
            backfillDays: 90,
            includeBodies: false,
            admissionMode: "review",
            restrictionCodes: [],
        },
        streams,
        reviewCount: 0,
        pendingApprovalCount: 0,
        activationReady: true,
        disclosures: {
            scopes: [],
            admittedFields: [],
            materialExclusions: [],
            visibility: [],
            retention: [],
        },
        purge: { active: false, status: "idle", errorCode: null },
    };
}

const CAPABILITIES: InstanceCapabilities = {
    sso: false,
    socialLogin: { google: false, microsoft: false },
    connectedAccounts: { google: true, microsoft: false },
    connectedCapture: { google: false, microsoft: false },
    mailManaged: false,
    businessCardScanning: false,
    businessCardImport: false,
    campaignDelivery: false,
};

describe("provider journey state machine", () => {
    it("reports disconnected until this browser hands off, then authorizing", () => {
        expect(providerJourneyState(null, null, false)).toBe("disconnected");
        expect(providerJourneyState(null, null, true)).toBe("authorizing");
    });

    it("maps every connection status to exactly one card state", () => {
        const expected: Record<ProviderConnectionStatus, string> = {
            connected: "connected",
            paused: "paused",
            error: "attention",
            revoked: "attention",
            disconnecting: "disconnecting",
            purge_failed: "disconnecting",
        };

        for (const [status, state] of Object.entries(expected)) {
            expect(providerJourneyState(
                connection({ status: status as ProviderConnectionStatus }),
                null,
                false,
            )).toBe(state);
        }
    });

    it("reads live capture work as syncing and a stalled stream as attention", () => {
        expect(providerJourneyState(
            connection(),
            overview([stream({ status: "backfilling" })]),
            false,
        )).toBe("syncing");
        expect(providerJourneyState(
            connection(),
            overview([stream({ status: "intervention_required" })]),
            false,
        )).toBe("attention");
    });

    it("ranks a stalled stream above a pause and a durable disconnect above both", () => {
        expect(providerJourneyState(
            connection({ status: "paused" }),
            overview([stream({ status: "intervention_required" })]),
            false,
        )).toBe("attention");
        expect(providerJourneyState(
            connection({ status: "disconnecting" }),
            overview([stream({ status: "intervention_required" })]),
            false,
        )).toBe("disconnecting");
    });

    it("ignores an in-flight handoff once a connection exists", () => {
        expect(providerJourneyState(connection(), null, true)).toBe("connected");
    });
});

describe("last sync", () => {
    /**
     * `ProviderConnection.lastSyncAt` survives from an earlier connection model and no backend
     * write path sets it, so reading it would report "never" on a provider that is syncing.
     */
    it("reads stream success rather than the connection's dead last-sync column", () => {
        const card = source("app/components/account/connected-capture/CaptureProviderCard.tsx");
        const drawer = source("app/components/account/connected-capture/ManageConnectionDrawer.tsx");

        expect(card).not.toContain("lastSyncAt");
        expect(drawer).not.toContain("lastSyncAt");
    });

    it("takes the newest stream success and ignores streams that never succeeded", () => {
        expect(lastCaptureSuccessAt(overview([
            stream({ stream: "calendar", lastSuccessAt: "2026-08-01T10:00:00Z" }),
            stream({ stream: "mail_inbox", lastSuccessAt: "2026-08-01T12:00:00Z" }),
            stream({ stream: "mail_sent", lastSuccessAt: null }),
        ]))).toBe("2026-08-01T12:00:00Z");
    });

    it("reports nothing when no stream has ever succeeded", () => {
        expect(lastCaptureSuccessAt(overview([stream()]))).toBeNull();
        expect(lastCaptureSuccessAt(null)).toBeNull();
    });
});

describe("two clicks to provider authorization", () => {
    /**
     * The acceptance criterion is a click budget, so the gate counts the interactive steps the
     * journey declares between a Connect action and the provider's authorization page. Step one is
     * the card's own control; step two is the confirm on the single expectation step. The third
     * assertion is what makes the second step terminal: the confirm performs the handoff itself
     * rather than advancing to another surface.
     */
    it("offers exactly one enabled control on a disconnected card", async () => {
        const { default: CaptureProviderCard } = await import(
            "@/app/components/account/connected-capture/CaptureProviderCard"
        );
        const markup = renderToStaticMarkup(createElement(NowProvider, {
            value: Date.parse("2026-08-19T12:00:00Z"),
            children: createElement(CaptureProviderCard, {
                provider: "google" as const,
                providerIcon: null,
                state: "disconnected" as const,
                managedUnavailable: false,
                connection: null,
                connectionEnabled: true,
                captureEnabled: false,
                capture: null,
                captureLoading: false,
                captureLoadError: false,
                pendingReviews: 0,
                authorizationErrorCode: null,
                busy: false,
                onConnect: () => undefined,
                onManage: () => undefined,
                onSync: () => undefined,
                onRetryCapture: () => undefined,
            }),
        }));

        const buttons = markup.match(/<button/g) ?? [];
        expect(buttons).toHaveLength(1);
        expect(markup).toContain("AccountConnections.connectProvider");
        expect(markup).not.toContain("disabled=\"\"");
    });

    it("routes the Connect action to one expectation step and never to the network", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");
        const connect = panel.slice(
            panel.indexOf("const connect = (provider"),
            panel.indexOf("/** The second click"),
        );

        expect(connect).toContain("setManagedTarget(provider)");
        expect(connect).toContain("setConsentTarget(provider)");
        expect(connect).not.toContain("beginProviderConnection");
        expect(connect).not.toContain("await");
    });

    it("makes the expectation step's confirm the authorization handoff itself", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");
        const startAuthorization = panel.slice(
            panel.indexOf("const startAuthorization = async"),
            panel.indexOf("const togglePause"),
        );

        expect(startAuthorization).toContain("beginProviderConnection(provider)");
        expect(startAuthorization).toContain("window.location.assign(url)");
        expect(panel).toContain("onConfirm={() => startAuthorization(consentTarget)}");
    });

    it("keeps the managed pairing dialog's own intro as its single expectation step", () => {
        const dialog = source(
            "app/components/account/connected-accounts/ManagedConnectDialog.tsx",
        );

        expect(dialog).toContain("{ kind: 'intro' }");
        expect(dialog).toContain("beginManagedPairing(provider)");
    });
});

describe("authorization return path", () => {
    it("remembers and consumes the provider a handoff left for", () => {
        const store = new Map<string, string>();
        vi.stubGlobal("window", {
            sessionStorage: {
                getItem: (key: string) => store.get(key) ?? null,
                setItem: (key: string, value: string) => void store.set(key, value),
                removeItem: (key: string) => void store.delete(key),
            },
        });

        rememberPendingAuthorization("microsoft");
        expect(takePendingAuthorization()).toBe("microsoft");
        expect(takePendingAuthorization()).toBeNull();

        vi.unstubAllGlobals();
    });

    it("survives a browser that refuses session storage", () => {
        vi.stubGlobal("window", {
            sessionStorage: {
                getItem: () => {
                    throw new Error("denied");
                },
                setItem: () => {
                    throw new Error("denied");
                },
                removeItem: () => undefined,
            },
        });

        expect(() => rememberPendingAuthorization("google")).not.toThrow();
        expect(takePendingAuthorization()).toBeNull();

        vi.unstubAllGlobals();
    });

    /**
     * The provider's failure return carries only `?error=<code>`, never which provider failed, so
     * the remembered handoff is the only thing that can put the reader back on the right card.
     */
    it("resumes on the provider's own card after a success and after a failure", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");

        expect(panel).toContain("const handedOffTo = takePendingAuthorization()");
        expect(panel).toContain("captureConnectionsHref(withoutCallback, { provider: resumed })");
        expect(panel).toContain("setAuthorizationError({ provider: handedOffTo, code })");
    });

    it("keeps a recoverable failure on the card with one next action", () => {
        const card = source("app/components/account/connected-capture/CaptureProviderCard.tsx");

        expect(card).toContain("authorizationErrorCode");
        expect(card).toContain("AccountConnections.tryAgain".replace("AccountConnections.", "'"));
        expect(card).toContain("role=\"alert\"");
    });

    it("returns to the connections route rather than another settings destination", () => {
        expect(captureConnectionsHref(new URLSearchParams(), { provider: "google" }))
            .toBe("/account/connections?provider=google");
    });
});

describe("disconnect is not erasure", () => {
    const RETAINED = ["disconnectRetained", "purgeRetained"] as const;

    it("states what survives a disconnect in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            const lifecycle = messages(locale).AccountCaptureLifecycle;
            for (const key of RETAINED) {
                expect(lifecycle[key], `${locale}.${key}`).toBeTruthy();
            }
        }

        expect(messages("en").AccountCaptureLifecycle.disconnectRetained)
            .toContain("stay exactly as they are");
        expect(messages("ja").AccountCaptureLifecycle.disconnectRetained)
            .toContain("そのまま残ります");
    });

    it("shows the retained-records line on every disconnect confirmation", () => {
        const dialog = source("app/components/account/connected-capture/CapturePurgeDialog.tsx");

        expect(dialog).toContain("disconnectRetained");
        expect(dialog).toContain("purgeRetained");
    });

    it("keeps erasure behind its own disclosure, separately named from disconnect", () => {
        const drawer = source("app/components/account/connected-capture/ManageConnectionDrawer.tsx");
        const ending = drawer.slice(drawer.indexOf("sectionEnding"));

        expect(ending.indexOf("disconnect")).toBeLessThan(ending.indexOf("Collapsible"));
        expect(ending).toContain("CollapsibleContent");
        expect(ending.indexOf("CollapsibleContent")).toBeLessThan(ending.indexOf("tCapture('purge')"));
    });

    it("names the two operations differently in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            const catalog = messages(locale);
            expect(catalog.AccountConnections.disconnect)
                .not.toBe(catalog.AccountCaptureProvider.purge);
        }
    });
});

describe("reviews absorbed into the journey", () => {
    it("still resolves the deep link the reviews route redirects to", () => {
        const state = parseCaptureRouteState(
            new URLSearchParams("provider=google&panel=reviews&page=2"),
        );

        expect(state).toEqual({ provider: "google", panel: "reviews", reviewId: null, page: 2 });
    });

    it("keeps the redirect target the manifest records for the reviews stub", () => {
        expect(captureConnectionsHref(new URLSearchParams(), {
            provider: "google",
            panel: "reviews",
        })).toBe("/account/connections?provider=google&panel=reviews");
    });

    it("gives the queue a home in the drawer even when nothing is waiting", () => {
        const drawer = source("app/components/account/connected-capture/ManageConnectionDrawer.tsx");

        expect(drawer).toContain("tReviews('drawerLabel')");
        expect(drawer).toContain("count={pendingReviews}");
    });

    it("returns a closed review queue to the connection it belongs to", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");

        expect(panel).toContain("closePanel(routeOverview.provider)");
        expect(panel).toContain("panel: provider && connectionOf(provider) ? \"manage\" : null");
    });
});

describe("manage drawer route state", () => {
    it("is the only panel that survives a provider whose capture is off", () => {
        const surviving = CAPTURE_PANELS.filter((panel) => !capturePanelRequiresCapture(panel));

        expect(surviving).toEqual(["manage"]);
    });

    it("treats a connection-only provider as having a journey", () => {
        expect(providerJourneyEnabled(CAPABILITIES, "google")).toBe(true);
        expect(providerJourneyEnabled(CAPABILITIES, "microsoft")).toBe(false);
    });

    it("keeps the provider when only the panel is unreachable", () => {
        const page = source("app/(app)/account/connections/page.tsx");

        expect(page).toContain("providerUnavailable");
        expect(page).toContain("panelUnavailable");
        expect(page).toContain("{ panel: null, reviewId: null, page: 1 }");
    });

    it("round-trips the manage panel through the query string", () => {
        const href = captureConnectionsHref(new URLSearchParams(), {
            provider: "microsoft",
            panel: "manage",
        });

        expect(href).toBe("/account/connections?provider=microsoft&panel=manage");
        expect(parseCaptureRouteState(new URLSearchParams("provider=microsoft&panel=manage")).panel)
            .toBe("manage");
    });
});

describe("workspace defaults are visible and edit-gated", () => {
    it("renders the effective policy for every reader and the control for none but administrators", () => {
        const drawer = source("app/components/account/connected-capture/ManageConnectionDrawer.tsx");
        const section = drawer.slice(drawer.indexOf("sectionWorkspaceDefaults"));

        expect(section.indexOf("workspaceCaptureLabel"))
            .toBeLessThan(section.indexOf("canManageWorkspacePolicy"));
        expect(section).toContain("workspaceDefaultsAskAdmin");
    });
});
