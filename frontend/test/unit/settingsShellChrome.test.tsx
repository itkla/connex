import { renderToStaticMarkup } from "react-dom/server";
import type { AnchorHTMLAttributes, PropsWithChildren } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import WorkspaceSettingsChrome from "@/app/components/settings/WorkspaceSettingsChrome";
import { PermissionsProvider } from "@/app/hooks/usePermissions";
import { SETTINGS_GROUPS } from "@/app/lib/settingsManifest";

const { pathnameState } = vi.hoisted(() => ({ pathnameState: { value: "/settings/members" } }));

vi.mock("next/navigation", () => ({
    usePathname: () => pathnameState.value,
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

/**
 * Gate over the seam that lets the unified Settings home share the `/settings` segment with the
 * workspace-settings destinations that still live under it (#1340 WS4.1).
 *
 * The epic's acceptance is that no settings surface uses a horizontally scrolling peer-tab strip.
 * The home renders inside the same layout as those destinations, so the only thing standing between
 * it and the shipped strip is this component stepping aside. That is worth a probe, not a comment:
 * the first test asserts the strip is gone at the home, and the second asserts it is still there
 * everywhere else, so neither claim can pass by the chrome simply never rendering.
 */
function chrome() {
    return renderToStaticMarkup(
        <PermissionsProvider permissions={["WORKSPACE_SETTINGS"]} status="resolved">
            <WorkspaceSettingsChrome
                title="Settings"
                description="Manage this workspace."

            />
        </PermissionsProvider>,
    );
}

describe("the settings home does not inherit the workspace tab strip", () => {
    beforeEach(() => {
        pathnameState.value = "/settings/members";
    });

    it("renders no header and no tab strip at the settings home", () => {
        pathnameState.value = "/settings";

        expect(chrome()).toBe("");
    });

    it("still renders the header and the tab strip on a workspace settings destination", () => {
        const html = chrome();

        expect(html).toContain("/settings/general");
        expect(html).toContain("/settings/data");
        expect(html).toContain("Manage this workspace.");
    });

    it.each(SETTINGS_GROUPS.map((group) => group.route))(
        "renders neither at the canonical destination %s",
        (route) => {
            pathnameState.value = route;

            expect(
                chrome(),
                "a scope-group page is named for the job it owns; a second header saying Settings and the strip it replaced must not stack above it",
            ).toBe("");
        },
    );
});
