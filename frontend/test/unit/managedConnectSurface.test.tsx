import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import CaptureProviderCard from "@/app/components/account/connected-capture/CaptureProviderCard";
import { NowProvider } from "@/app/hooks/useNow";

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => `${namespace}.${key}`,
    useLocale: () => "en",
}));

function renderCard(overrides: { managedUnavailable: boolean }): string {
    return renderToStaticMarkup(
        <NowProvider value={Date.parse("2026-08-19T12:00:00Z")}>
            <CaptureProviderCard
                provider="google"
                providerIcon={null}
                state="disconnected"
                managedUnavailable={overrides.managedUnavailable}
                connection={null}
                connectionEnabled={!overrides.managedUnavailable}
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
}

describe("connected-account credential modes", () => {
    /**
     * #1340 rules the credential mode an operator chose to be an operator concern: the card says
     * what the reader must do, not how authorization is arranged. The previous gate here required
     * the opposite and is superseded by that ruling.
     */
    it("names no credential mode on the provider card", () => {
        const markup = renderCard({ managedUnavailable: false });

        expect(markup).not.toContain("AccountConnections.mode_custom");
        expect(markup).not.toContain("AccountConnections.mode_managed");
    });

    it("offers connecting when the managed application is usable", () => {
        const markup = renderCard({ managedUnavailable: false });

        expect(markup).not.toContain("AccountConnections.managedUnavailableTitle");
        expect(markup).not.toContain("disabled=\"\"");
    });

    it("explains the specific managed gap instead of a dead connect button", () => {
        const markup = renderCard({ managedUnavailable: true });

        expect(markup).toContain("AccountConnections.managedUnavailableTitle");
        expect(markup).toContain("AccountConnections.managedUnavailableBody");
        expect(markup).toContain("AccountConnections.managedUnavailableLink");
        expect(markup).toContain("docs/CONNECTED_ACCOUNTS_MANAGED_OAUTH.md");
        expect(markup).toContain("disabled=\"\"");
    });
});
