import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import SettingsAvailabilityNotice from "@/app/components/settings/SettingsAvailabilityNotice";
import type { SettingsAvailabilityState } from "@/app/lib/settingsManifest";

const { locale } = vi.hoisted(() => ({ locale: { current: "en" } }));

vi.mock("next-intl", () => ({
    useTranslations: (namespace: string) => (key: string) => message(locale.current, `${namespace}.${key}`),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
}));

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionDivProps = { children?: ReactNode; className?: string };
    return {
        motion: {
            div: ({ children, className }: MotionDivProps) =>
                React.createElement("div", { className }, children),
        },
        useReducedMotion: () => true,
    };
});

/**
 * Gate over the shared in-place availability notice (#1340 PR 2).
 *
 * The epic's contract is four postures, each of which a capability-managed destination can state
 * about itself where it stands. This suite renders all four against the shipped catalogs in both
 * locales — a mocked translator that echoed its key would prove only that a key was passed — and
 * holds the two properties the postures differ on: which of them offers an action, and that none of
 * them shares another's words.
 */
const STATES: readonly SettingsAvailabilityState[] = ["managed", "not-enabled", "ask-admin", "retry"];

const LOCALES = ["en", "ja"] as const;

function catalog(language: string): Record<string, unknown> {
    const directory = path.join(process.cwd(), "messages", language);
    const merged: Record<string, unknown> = {};
    for (const file of readdirSync(directory).sort()) {
        if (!file.endsWith(".json")) continue;
        Object.assign(merged, JSON.parse(readFileSync(path.join(directory, file), "utf8")) as object);
    }
    return merged;
}

function message(language: string, key: string): string {
    let current: unknown = catalog(language);
    for (const segment of key.split(".")) {
        if (typeof current !== "object" || current === null) throw new Error(`unresolved ${key}`);
        current = (current as Record<string, unknown>)[segment];
    }
    if (typeof current !== "string") throw new Error(`unresolved ${key}`);
    return current;
}

function render(
    state: SettingsAvailabilityState,
    language: (typeof LOCALES)[number] = "en",
    props: { variant?: "page" | "inline"; title?: string; body?: string } = {},
): string {
    locale.current = language;
    return renderToStaticMarkup(<SettingsAvailabilityNotice state={state} {...props} />);
}

/** Serialized markup escapes the apostrophes and ampersands the copy carries. */
function escaped(text: string): string {
    return text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#x27;");
}

const EXPECTED_COPY: Record<SettingsAvailabilityState, { title: string; body: string }> = {
    managed: { title: "SettingsAvailability.managedTitle", body: "SettingsAvailability.managedBody" },
    "not-enabled": {
        title: "SettingsAvailability.notEnabledTitle",
        body: "SettingsAvailability.notEnabledBody",
    },
    "ask-admin": {
        title: "SettingsAvailability.askAdminTitle",
        body: "SettingsAvailability.askAdminBody",
    },
    retry: { title: "CapabilityUnavailable.title", body: "CapabilityUnavailable.body" },
};

describe("the availability notice states every posture the manifest names", () => {
    it("covers the whole state union, so a fifth posture cannot ship unrendered", () => {
        const covered: Record<SettingsAvailabilityState, true> = {
            managed: true,
            "not-enabled": true,
            "ask-admin": true,
            retry: true,
        };

        expect(Object.keys(covered).sort()).toEqual([...STATES].sort());
    });

    it.each(LOCALES)("renders each posture's shipped title and body in %s", (language) => {
        for (const state of STATES) {
            const markup = render(state, language);
            expect(markup, `${state} must render its title`).toContain(
                escaped(message(language, EXPECTED_COPY[state].title)),
            );
            expect(markup, `${state} must render its body`).toContain(
                escaped(message(language, EXPECTED_COPY[state].body)),
            );
        }
    });

    it("gives each posture words of its own", () => {
        const titles = STATES.map((state) => message("en", EXPECTED_COPY[state].title));

        expect(new Set(titles).size).toBe(STATES.length);
    });

    it("differs between the locales, so a locale-blind pass cannot satisfy this suite", () => {
        const english = STATES.map((state) => render(state, "en"));
        const japanese = STATES.map((state) => render(state, "ja"));

        expect(english).not.toEqual(japanese);
        expect(japanese.every((markup) => /[^\x00-\x7F]/.test(markup))).toBe(true);
    });

    it("speaks the epic's own lines, so the postures are recognizable as the ones it named", () => {
        expect(message("en", "SettingsAvailability.managedTitle")).toBe("Set up for everyone here");
        expect(message("en", "SettingsAvailability.notEnabledTitle")).toBe("This isn't turned on");
        expect(message("en", "SettingsAvailability.askAdminTitle")).toBe("Ask a workspace administrator");
    });
});

describe("the availability notice offers an action only where one can help", () => {
    it("gives the unresolved posture a retry control", () => {
        const markup = render("retry");

        expect(markup).toContain("<button");
        expect(markup).toContain(escaped(message("en", "CapabilityUnavailable.retry")));
    });

    it("gives the settled postures no control at all", () => {
        for (const state of ["managed", "not-enabled", "ask-admin"] as const) {
            expect(render(state), `${state} is settled; a button that cannot change it is a dead end`)
                .not.toContain("<button");
        }
    });

    it("keeps the retry control in the inline variant, where the page around it still renders", () => {
        const markup = render("retry", "en", { variant: "inline" });

        expect(markup).toContain("<button");
        expect(markup).toContain(escaped(message("en", "CapabilityUnavailable.body")));
    });
});

describe("the availability notice adapts to where it renders", () => {
    it("draws the page variant as a full route-level state and the inline one as a card", () => {
        const page = render("managed");
        const inline = render("managed", "en", { variant: "inline" });

        expect(page).toContain("min-h-[60vh]");
        expect(inline).not.toContain("min-h-[60vh]");
        expect(inline).toContain("rounded-2xl border border-border bg-card");
    });

    it("marks each posture with an icon of its own", () => {
        const shapes = STATES.map((state) => {
            const paths = render(state).match(/<path[^>]*d="([^"]+)"/g) ?? [];
            return paths.join("");
        });

        expect(shapes.every((shape) => shape.length > 0)).toBe(true);
        expect(new Set(shapes).size).toBe(STATES.length);
    });

    it("prefers a destination's own words over the posture's general ones", () => {
        const markup = render("not-enabled", "en", {
            variant: "inline",
            title: "Connected accounts aren't available",
            body: "This Connex instance has no connected-account providers configured.",
        });

        expect(markup).toContain(escaped("Connected accounts aren't available"));
        expect(markup).not.toContain(escaped(message("en", "SettingsAvailability.notEnabledTitle")));
    });
});
