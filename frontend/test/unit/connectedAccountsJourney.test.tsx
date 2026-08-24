import { readFileSync } from "node:fs";
import path from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import CaptureProviderCard from "@/app/components/account/connected-capture/CaptureProviderCard";
import CaptureDisclosures from "@/app/components/account/connected-capture/CaptureDisclosures";
import { NowProvider } from "@/app/hooks/useNow";
import { canChangeCaptureLifecycleDialogOpen } from "@/app/lib/captureLifecycleDialog";
import {
    CAPTURE_PANELS,
    providerCardAction,
    providerGlanceState,
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

type AccountMessageCatalog = {
    AccountConnections: Record<string, string>;
    AccountCaptureProvider: Record<string, string>;
    AccountCaptureLifecycle: Record<string, string>;
    AccountManageConnection: Record<string, string>;
    AccountCaptureDisclosure: { retention: Record<string, string> };
};

function messages(locale: "en" | "ja"): AccountMessageCatalog {
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
        retainedData: true,
        accountResetAvailable: false,
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
    it("reports disconnected when the provider has no connection", () => {
        expect(providerJourneyState(null, null)).toBe("disconnected");
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
            )).toBe(state);
        }
    });

    it("reads live capture work as syncing and a stalled stream as attention", () => {
        expect(providerJourneyState(
            connection(),
            overview([stream({ status: "backfilling" })]),
        )).toBe("syncing");
        expect(providerJourneyState(
            connection(),
            overview([stream({ status: "intervention_required" })]),
        )).toBe("attention");
    });

    it("ranks a stalled stream above a pause and a durable disconnect above both", () => {
        expect(providerJourneyState(
            connection({ status: "paused" }),
            overview([stream({ status: "intervention_required" })]),
        )).toBe("attention");
        expect(providerJourneyState(
            connection({ status: "disconnecting" }),
            overview([stream({ status: "intervention_required" })]),
        )).toBe("disconnecting");
    });
});

describe("the action a card offers", () => {
    /**
     * The regression this exists for: gating reconnect on capture-derived evidence left a failed or
     * revoked connection on a capture-disabled provider with no way back, because that provider's
     * overview is never fetched. Trouble is read from the connection, so the answer cannot depend
     * on whether the instance happens to capture.
     */
    it("offers reconnect for a broken connection even when capture is off", () => {
        for (const status of ["error", "revoked"] as const) {
            const broken = connection({ status });
            const state = providerJourneyState(broken, null);

            expect(state).toBe("attention");
            expect(providerCardAction(state, broken, false, null)).toBe("reconnect");
            expect(providerCardAction(state, broken, true, null)).toBe("reconnect");
        }
    });

    it("offers reconnect when the effective policy says the authorization is gone", () => {
        const stale = overview([stream()]);
        stale.effectivePolicy.restrictionCodes = ["connection_revoked"];

        expect(providerCardAction("connected", connection(), true, stale)).toBe("reconnect");
    });

    it("offers connect before there is a connection and nothing while one is ending", () => {
        expect(providerCardAction("disconnected", null, true, null)).toBe("connect");
        expect(providerCardAction(
            "disconnecting",
            connection({ status: "disconnecting" }),
            true,
            overview([stream()]),
        )).toBe("none");
    });

    it("offers sync only when there is something the policy admits to sync", () => {
        const admitting = overview([stream()]);
        expect(providerCardAction("connected", connection(), true, admitting)).toBe("sync");

        const admittingNothing = overview([stream()]);
        admittingNothing.effectivePolicy.enabled = false;
        expect(providerCardAction("connected", connection(), true, admittingNothing)).toBe("none");
        expect(providerCardAction("connected", connection(), false, null)).toBe("none");
    });

    it("offers no card action for a pause or a stall reconnecting cannot fix", () => {
        expect(providerCardAction(
            "paused",
            connection({ status: "paused" }),
            true,
            overview([stream({ status: "paused" })]),
        )).toBe("none");
        expect(providerCardAction(
            "attention",
            connection(),
            true,
            overview([stream({ status: "intervention_required" })]),
        )).toBe("none");
    });
});

describe("what a source reports at a glance", () => {
    /**
     * Policy is not health. A calendar the workspace admits and the provider has stopped delivering
     * must not read "Active" on the surface a reader checks at a glance.
     */
    it("reports a stalled source as needing attention rather than active", () => {
        const stalled = overview([stream({ stream: "calendar", status: "intervention_required" })]);

        expect(providerGlanceState(stalled, "calendar")).toBe("attention");
    });

    it("reports live work and pauses from the streams", () => {
        expect(providerGlanceState(
            overview([stream({ stream: "calendar", status: "backfilling" })]),
            "calendar",
        )).toBe("working");
        expect(providerGlanceState(
            overview([stream({ stream: "calendar", status: "paused" })]),
            "calendar",
        )).toBe("paused");
    });

    it("aggregates both mail streams and reports trouble in either", () => {
        const oneStalled = overview([
            stream({ stream: "mail_inbox", status: "idle" }),
            stream({ stream: "mail_sent", status: "intervention_required" }),
        ]);
        oneStalled.effectivePolicy.mailSent = true;

        expect(providerGlanceState(oneStalled, "mail")).toBe("attention");
    });

    it("ignores a stall on a source the policy no longer admits", () => {
        const retired = overview([stream({ stream: "calendar", status: "intervention_required" })]);
        retired.effectivePolicy.calendar = false;

        expect(providerJourneyState(connection(), retired)).toBe("connected");
        expect(providerGlanceState(retired, "calendar")).toBe("off");
    });

    it("stays a policy answer for a source nobody admitted", () => {
        const noCalendar = overview([stream({ stream: "calendar", status: "intervention_required" })]);
        noCalendar.effectivePolicy.calendar = false;

        expect(providerGlanceState(noCalendar, "calendar")).toBe("off");
        expect(providerGlanceState(null, "mail")).toBe("off");
    });

    it("names every glance state in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            const catalog = messages(locale).AccountConnections;
            for (const state of ["active", "off", "working", "paused", "attention"]) {
                expect(catalog[`streamState_${state}`], `${locale}.${state}`).toBeTruthy();
            }
        }
    });
});

describe("last sync", () => {
    it("reads stream success rather than duplicating connection metadata", () => {
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

describe("a broken connection keeps a way back on the card", () => {
    function renderBroken(captureEnabled: boolean): string {
        const broken = connection({ status: "error" });
        return renderToStaticMarkup(
            <NowProvider value={Date.parse("2026-08-19T12:00:00Z")}>
                <CaptureProviderCard
                    provider="google"
                    providerIcon={null}
                    state={providerJourneyState(broken, null)}
                    managedUnavailable={false}
                    connection={broken}
                    connectionEnabled
                    captureEnabled={captureEnabled}
                    capture={null}
                    captureLoading={false}
                    captureLoadError={false}
                    pendingReviews={0}
                    authorizationErrorCode={null}
                    busy={false}
                    onConnect={() => undefined}
                    onPurge={() => undefined}
                    onReset={() => undefined}
                    onManage={() => undefined}
                    onReviews={() => undefined}
                    onSync={() => undefined}
                    onRetryCapture={() => undefined}
                />
            </NowProvider>,
        );
    }

    /**
     * The rendered half of the reconnect regression: an errored connection on a capture-disabled
     * provider used to render `Manage` alone, because the reconnect gate read an overview that
     * instance never fetches.
     */
    it("renders reconnect beside manage whether or not the instance captures", () => {
        for (const captureEnabled of [false, true]) {
            const markup = renderBroken(captureEnabled);

            expect(markup, `captureEnabled=${captureEnabled}`)
                .toContain("AccountConnections.reconnect");
            expect(markup).toContain("AccountConnections.manage");
            expect(markup).toContain("AccountConnections.status_error");
            expect((markup.match(/<button/g) ?? []).length).toBeGreaterThanOrEqual(2);
        }
    });

    it("leaves the reconnect action enabled so it can actually be taken", () => {
        expect(renderBroken(false)).not.toContain("disabled=\"\"");
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
    it("offers exactly one enabled control on a disconnected card", () => {
        const markup = renderToStaticMarkup(
            <NowProvider value={Date.parse("2026-08-19T12:00:00Z")}>
                <CaptureProviderCard
                    provider="google"
                    providerIcon={null}
                    state="disconnected"
                    managedUnavailable={false}
                    connection={null}
                    connectionEnabled
                    captureEnabled={false}
                    capture={null}
                    captureLoading={false}
                    captureLoadError={false}
                    pendingReviews={0}
                    authorizationErrorCode={null}
                    busy={false}
                    onConnect={() => undefined}
                    onPurge={() => undefined}
                    onReset={() => undefined}
                    onManage={() => undefined}
                    onReviews={() => undefined}
                    onSync={() => undefined}
                    onRetryCapture={() => undefined}
                />
            </NowProvider>,
        );

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

describe("retained evidence after disconnect", () => {
    it("keeps both scopes of erasure actionable on a disconnected card", () => {
        const retained = overview([]);
        retained.accountResetAvailable = true;
        const markup = renderToStaticMarkup(
            <NowProvider value={Date.parse("2026-08-19T12:00:00Z")}>
                <CaptureProviderCard
                    provider="google"
                    providerIcon={null}
                    state="disconnected"
                    managedUnavailable={false}
                    connection={null}
                    connectionEnabled
                    captureEnabled={false}
                    capture={retained}
                    captureLoading={false}
                    captureLoadError={false}
                    pendingReviews={0}
                    authorizationErrorCode={null}
                    busy={false}
                    onConnect={() => undefined}
                    onPurge={() => undefined}
                    onReset={() => undefined}
                    onManage={() => undefined}
                    onReviews={() => undefined}
                    onSync={() => undefined}
                    onRetryCapture={() => undefined}
                />
            </NowProvider>,
        );

        expect(markup).toContain("AccountCaptureProvider.purge");
        expect(markup).toContain("AccountConnections.retainedDataNote");
        expect(markup).toContain("AccountConnections.resetProviderAccount");
        expect(markup).toContain("AccountConnections.accountResetNote");
    });

    it("offers an explicit reset retry after a purge failure", () => {
        const failed = overview([]);
        failed.purge = { active: false, status: "purge_failed", errorCode: "purge_failed" };
        const markup = renderToStaticMarkup(
            <NowProvider value={Date.parse("2026-08-19T12:00:00Z")}>
                <CaptureProviderCard
                    provider="google"
                    providerIcon={null}
                    state="disconnecting"
                    managedUnavailable={false}
                    connection={connection({ status: "purge_failed" })}
                    connectionEnabled
                    captureEnabled={false}
                    capture={failed}
                    captureLoading={false}
                    captureLoadError={false}
                    pendingReviews={0}
                    authorizationErrorCode={null}
                    busy={false}
                    onConnect={() => undefined}
                    onPurge={() => undefined}
                    onReset={() => undefined}
                    onManage={() => undefined}
                    onReviews={() => undefined}
                    onSync={() => undefined}
                    onRetryCapture={() => undefined}
                />
            </NowProvider>,
        );

        expect(markup).toContain("AccountConnections.retryReset");
    });

    it("discovers retained providers even when capture is disabled", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");

        expect(panel).toContain("overviewOf(provider)?.retainedData === true");
        expect(panel).toContain("overviewOf(provider)?.accountResetAvailable === true");
        expect(panel).toContain("setCaptureReloadKey((current) => current + 1)");
        expect(panel).toContain("setConnectionsReloadKey((current) => current + 1)");
        expect(panel).not.toContain("if (!anyCaptureEnabled) return;");
        expect(panel).not.toContain("error instanceof Error ? error.message");
    });

    it("keeps a destructive lifecycle dialog open while its request is running", () => {
        expect(canChangeCaptureLifecycleDialogOpen(true, false)).toBe(false);
        expect(canChangeCaptureLifecycleDialogOpen(true, true)).toBe(true);
        expect(canChangeCaptureLifecycleDialogOpen(false, false)).toBe(true);
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

    it("routes a retained-identity conflict to the destructive reset instead of retrying", () => {
        const card = source("app/components/account/connected-capture/CaptureProviderCard.tsx");
        const panel = source("app/components/account/ConnectionsPanel.tsx");

        expect(card).toContain("retained_data_reset_required");
        expect(card).toContain("onReset");
        expect(card).toContain("eraseRetainedData");
        expect(panel).toContain("resetRetainedProviderData(target.provider)");
        expect(panel).toContain('mode: "reset"');
    });

    it("returns to the connections route rather than another settings destination", () => {
        expect(captureConnectionsHref(new URLSearchParams(), { provider: "google" }))
            .toBe("/settings/personal/connected-accounts?provider=google");
    });
});

describe("disconnect is not erasure", () => {
    const RETAINED = ["disconnectRetained", "purgeRetained", "resetRetained"] as const;

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

    it("keeps ordinary disconnect non-destructive and acknowledgement-free", () => {
        const dialog = source("app/components/account/connected-capture/CapturePurgeDialog.tsx");

        expect(dialog).not.toContain("captureEnabled");
        expect(dialog).toContain("disconnectRetentionTitle");
        expect(dialog).toContain("disconnectRetentionDescription");
        expect(dialog).toContain("const destructive = mode !== 'disconnect'");
        expect(dialog).toContain("destructive ? (");
    });

    it("gives the all-workspace reset its own scope and acknowledgement", () => {
        const dialog = source("app/components/account/connected-capture/CapturePurgeDialog.tsx");

        expect(dialog).toContain("allWorkspacesTitle");
        expect(dialog).toContain("allWorkspacesDescription");
        expect(dialog).toContain("resetAcknowledge");
        expect(dialog).toContain("confirmReset");
        expect(dialog).toContain("disabled={busy || (destructive && !acknowledged)}");
    });

    it("passes no capture switch into the confirmation from the panel", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");
        const lifecycle = panel.slice(panel.indexOf("<CapturePurgeDialog"));

        expect(lifecycle.slice(0, lifecycle.indexOf("/>"))).not.toContain("captureEnabled");
    });

    it("states retention in the drawer note before the disconnect confirmation", () => {
        for (const locale of ["en", "ja"] as const) {
            const note = messages(locale).AccountManageConnection.disconnectNote;

            expect(note, `${locale}`).toMatch(locale === "en" ? /stays in Connex/ : /Connex に残ります/);
            expect(note).toMatch(locale === "en" ? /stay exactly as they are/ : /そのまま残ります/);
        }
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

describe("capture disclosure codes", () => {
    it("maps the retained-data contract and safely falls back for future codes", () => {
        const markup = renderToStaticMarkup(
            <CaptureDisclosures disclosures={{
                scopes: [],
                admittedFields: ["future_field"],
                materialExclusions: [],
                visibility: [],
                retention: [
                    "retained_on_disconnect",
                    "erased_on_request",
                    "future_retention_rule",
                ],
            }} />,
        );

        expect(markup).toContain("AccountCaptureDisclosure.retention.retainedOnDisconnect");
        expect(markup).toContain("AccountCaptureDisclosure.retention.erasedOnRequest");
        expect(markup).toContain("AccountCaptureDisclosure.retention.unknown");
        expect(markup).toContain("AccountCaptureDisclosure.admittedField.unknown");
    });

    it("defines every retained-data disclosure in both locale catalogs", () => {
        for (const locale of ["en", "ja"] as const) {
            const retention = messages(locale).AccountCaptureDisclosure.retention;
            expect(retention.retainedOnDisconnect).toBeTruthy();
            expect(retention.erasedOnRequest).toBeTruthy();
            expect(retention.unknown).toBeTruthy();
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
        })).toBe("/settings/personal/connected-accounts?provider=google&panel=reviews");
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
        const page = source("app/(app)/settings/personal/connected-accounts/page.tsx");

        expect(page).toContain("providerUnavailable");
        expect(page).toContain("panelUnavailable");
        expect(page).toContain("{ panel: null, reviewId: null, page: 1 }");
    });

    it("round-trips the manage panel through the query string", () => {
        const href = captureConnectionsHref(new URLSearchParams(), {
            provider: "microsoft",
            panel: "manage",
        });

        expect(href).toBe("/settings/personal/connected-accounts?provider=microsoft&panel=manage");
        expect(parseCaptureRouteState(new URLSearchParams("provider=microsoft&panel=manage")).panel)
            .toBe("manage");
    });
});

describe("active lifecycle polling", () => {
    it("reschedules from the reload generation and refreshes both lifecycle views", () => {
        const panel = source("app/components/account/ConnectionsPanel.tsx");
        const effect = panel.slice(
            panel.indexOf("if (!activeCaptureOperation) return;"),
            panel.indexOf(
                "useEffect(() => {",
                panel.indexOf("if (!activeCaptureOperation) return;") + 1,
            ),
        );

        expect(effect).toContain("setCaptureReloadKey((current) => current + 1)");
        expect(effect).toContain("setConnectionsReloadKey((current) => current + 1)");
        expect(effect).toContain("[activeCaptureOperation, captureReloadKey]");
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
