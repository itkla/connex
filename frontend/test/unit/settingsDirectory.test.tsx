import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import type { AnchorHTMLAttributes, PropsWithChildren } from "react";
import { describe, expect, it, vi } from "vitest";

import SettingsDirectory from "@/app/components/settings/SettingsDirectory";
import { SETTINGS_ENTRIES, type SettingsEntry } from "@/app/lib/settingsManifest";
import { resolveSettingsNavigation } from "@/app/lib/settingsNavigation";
import type { InstanceCapabilities } from "@/app/lib/types";

vi.mock("next/navigation", () => ({
    usePathname: () => "/settings",
}));

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

/**
 * Render-path gate over the settings directory (#1340 WS4.1).
 *
 * `settingsNavigation.test.ts` proves the navigation *model* equals the manifest; it cannot see what
 * the directory chooses to draw from that model. Two of those choices are load-bearing and only
 * observable here: a group with one destination is rendered by its group row alone, and a group with
 * several does not draw its landing destination a second time under a second name — the
 * one-name-per-destination failure #1340 exists to remove.
 */
const entries: readonly SettingsEntry[] = SETTINGS_ENTRIES;

const ALL_CAPABILITIES: InstanceCapabilities = {
    sso: true,
    socialLogin: { google: true, microsoft: true },
    connectedAccounts: { google: true, microsoft: true },
    connectedCapture: { google: true, microsoft: true },
    mailManaged: false,
    businessCardScanning: true,
    businessCardImport: true,
    campaignDelivery: true,
};

function englishCatalog(): Record<string, unknown> {
    const directory = path.join(process.cwd(), "messages", "en");
    const merged: Record<string, unknown> = {};
    for (const file of readdirSync(directory).sort()) {
        if (!file.endsWith(".json")) continue;
        Object.assign(merged, JSON.parse(readFileSync(path.join(directory, file), "utf8")) as object);
    }
    return merged;
}

function resolveMessage(catalog: Record<string, unknown>, key: string): string {
    let current: unknown = catalog;
    for (const segment of key.split(".")) {
        if (typeof current !== "object" || current === null) throw new Error(`unresolved ${key}`);
        current = (current as Record<string, unknown>)[segment];
    }
    if (typeof current !== "string") throw new Error(`unresolved ${key}`);
    return current;
}

const catalog = englishCatalog();

const model = resolveSettingsNavigation({
    viewer: {
        capabilities: ALL_CAPABILITIES,
        permissions: new Set(entries.flatMap((entry) => [...entry.access.permissions])),
        isOrgAdmin: true,
    },
    translate: (key) => resolveMessage(catalog, key),
    scopeNames: {
        personal: resolveMessage(catalog, "SettingsNav.scopePersonal"),
        workspace: resolveMessage(catalog, "SettingsNav.scopeWorkspace"),
        organization: resolveMessage(catalog, "SettingsNav.scopeOrganization"),
    },
    workspaceName: "Northstar",
    organizationName: "Klae",
});

const markup = renderToStaticMarkup(<SettingsDirectory scopes={model} />);

function occurrences(needle: string): number {
    return markup.split(needle).length - 1;
}

/** Serialized markup escapes the ampersand several group names carry. */
function rendered(text: string): string {
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

describe("the settings directory draws the navigation model", () => {
    it("renders every scope heading and every group row", () => {
        for (const scope of model) {
            expect(markup).toContain(rendered(scope.name));
            for (const group of scope.groups) {
                expect(markup, `${group.id} must reach the DOM`).toContain(rendered(group.title));
            }
        }
    });

    it("gives a single-destination group exactly one link, on its own row", () => {
        const single = model
            .flatMap((scope) => scope.groups)
            .filter((group) => group.destinations.length === 1);

        expect(single.length).toBeGreaterThan(0);
        for (const group of single) {
            expect(markup).toContain(rendered(group.title));
            expect(occurrences(`href="${group.href}"`), `${group.id} links its destination once`).toBe(1);
        }
    });

    it("never draws a group's landing destination a second time beneath it", () => {
        const several = model
            .flatMap((scope) => scope.groups)
            .filter((group) => group.destinations.length > 1);

        expect(several.length).toBeGreaterThan(3);
        for (const group of several) {
            expect(
                occurrences(`href="${group.href}"`),
                `${group.id} draws ${group.href} twice under two names`,
            ).toBe(1);
            for (const destination of group.destinations) {
                if (destination.href === group.href) continue;
                expect(markup).toContain(rendered(destination.title));
                expect(occurrences(`href="${destination.href}"`)).toBe(1);
            }
        }
    });

    it("marks no row as the current page when the reader is on the settings home", () => {
        expect(markup).not.toContain('aria-current="page"');
    });
});
