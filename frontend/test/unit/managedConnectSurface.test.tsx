import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import CaptureProviderCard from "@/app/components/account/connected-capture/CaptureProviderCard";

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
}));

function renderCard(overrides: {
    mode: "custom" | "managed";
    managedUnavailable: boolean;
}): string {
    return renderToStaticMarkup(createElement(CaptureProviderCard, {
        provider: "google" as const,
        providerIcon: null,
        mode: overrides.mode,
        managedUnavailable: overrides.managedUnavailable,
        connection: null,
        connectionEnabled: !overrides.managedUnavailable,
        captureEnabled: false,
        capture: null,
        captureLoading: false,
        captureLoadError: false,
        canManageWorkspacePolicy: false,
        busy: false,
        onConnect: () => undefined,
        onTogglePause: () => undefined,
        onConfigure: () => undefined,
        onWorkspacePolicy: () => undefined,
        onSync: () => undefined,
        onReviews: () => undefined,
        onPurge: () => undefined,
        onDisconnect: () => undefined,
        onRetryCapture: () => undefined,
    }));
}

describe("connected-account credential modes", () => {
    it("names the credential mode on every provider card", () => {
        expect(renderCard({ mode: "custom", managedUnavailable: false }))
            .toContain("AccountConnections.mode_custom");
        expect(renderCard({ mode: "managed", managedUnavailable: false }))
            .toContain("AccountConnections.mode_managed");
    });

    it("offers connecting when the managed application is usable", () => {
        const markup = renderCard({ mode: "managed", managedUnavailable: false });
        expect(markup).not.toContain("AccountConnections.managedUnavailableTitle");
        expect(markup).not.toContain("disabled=\"\"");
    });

    it("explains the specific managed gap instead of a dead connect button", () => {
        const markup = renderCard({ mode: "managed", managedUnavailable: true });
        expect(markup).toContain("AccountConnections.managedUnavailableTitle");
        expect(markup).toContain("AccountConnections.managedUnavailableBody");
        expect(markup).toContain("AccountConnections.managedUnavailableLink");
        expect(markup).toContain("docs/CONNECTED_ACCOUNTS_MANAGED_OAUTH.md");
        expect(markup).toContain("disabled=\"\"");
    });
});
