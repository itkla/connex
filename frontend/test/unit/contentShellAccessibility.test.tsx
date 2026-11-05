/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { PropsWithChildren, ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const navigation = vi.hoisted(() => ({ pathname: "/dashboard" }));
vi.mock("next/navigation", () => ({ usePathname: () => navigation.pathname }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/app/components/GlobalSearch", () => ({ default: () => <input aria-label="Search" /> }));
vi.mock("@/app/components/account/PrivilegedMfaRouteGate", () => ({
    default: ({ children }: PropsWithChildren) => <>{children}</>,
}));
vi.mock("@/app/components/NavBreadcrumb", () => ({ default: () => null }));
vi.mock("@/app/components/MobileBottomBar", () => ({
    default: ({ onOpenMore }: { onOpenMore: () => void }) => <button onClick={onOpenMore}>More</button>,
}));
vi.mock("@/app/hooks/useSidebarMode", () => ({ useSidebarMode: () => ({ mode: "expanded", toggle: vi.fn() }) }));
vi.mock("@/app/components/ask-connex/AskConnexProvider", () => ({
    useAskConnex: () => ({ open: false, workspace: false, width: 420 }),
    useAskConnexMount: () => ({ registerDesktopRoot: vi.fn() }),
}));
vi.mock("motion/react", () => ({
    useReducedMotion: () => true,
    motion: {
        div: ({ children, inert, className }: PropsWithChildren<{ inert?: boolean; className?: string }>) => (
            <div inert={inert} className={className}>{children}</div>
        ),
    },
}));

import ContentShell from "@/app/components/ContentShell";

let desktop = false;
const mediaQueries = new Set<EventTarget>();

class TestMediaQueryList extends EventTarget {
    readonly media: string;
    readonly onchange = null;
    readonly addListener = vi.fn();
    readonly removeListener = vi.fn();

    constructor(query: string) {
        super();
        this.media = query;
    }

    get matches() { return desktop; }
}

function shell(children: ReactNode = <button>Page action</button>) {
    return <ContentShell sidebar={<a href="#contacts">Contacts</a>}>{children}</ContentShell>;
}

beforeEach(() => {
    desktop = false;
    navigation.pathname = "/dashboard";
    vi.stubGlobal("matchMedia", (query: string): MediaQueryList => {
        const mediaQuery = new TestMediaQueryList(query);
        mediaQueries.add(mediaQuery);
        return mediaQuery;
    });
});

afterEach(() => {
    cleanup();
    mediaQueries.clear();
    vi.unstubAllGlobals();
});

describe("shell keyboard access", () => {
    it("makes the closed mobile drawer inert and restores the opener after Escape", () => {
        const { container } = render(shell());
        const sidebar = container.querySelector("#app-sidebar");
        const main = screen.getByRole("main");
        expect(sidebar?.hasAttribute("inert")).toBe(true);
        const trigger = screen.getByRole("button", { name: "showSidebar" });
        trigger.focus();
        fireEvent.click(trigger);
        expect(sidebar?.hasAttribute("inert")).toBe(false);
        expect(document.activeElement).toBe(sidebar);
        expect(main.closest("[inert]")).not.toBeNull();
        fireEvent.keyDown(document, { key: "Escape" });
        expect(sidebar?.hasAttribute("inert")).toBe(true);
        expect(document.activeElement).toBe(trigger);
        expect(main.closest("[inert]")).toBeNull();
    });

    it("restores the toolbar trigger when the bottom-bar opener unmounts", () => {
        render(shell());
        const more = screen.getByRole("button", { name: "More" });
        more.focus();
        fireEvent.click(more);
        expect(more.isConnected).toBe(false);
        fireEvent.keyDown(document, { key: "Escape" });
        expect(document.activeElement).toBe(screen.getByRole("button", { name: "showSidebar" }));
    });

    it("enables the desktop sidebar on a breakpoint change", () => {
        const { container } = render(shell());
        act(() => {
            desktop = true;
            for (const query of mediaQueries) query.dispatchEvent(Object.assign(new Event("change"), { matches: true }));
        });
        expect(container.querySelector("#app-sidebar")?.hasAttribute("inert")).toBe(false);
    });

    it("offers the skip link first and focuses main on skip", () => {
        const { container } = render(shell());
        const skip = screen.getByRole("link", { name: "skipToContent" });
        expect(container.querySelector("a,button,input")).toBe(skip);
        fireEvent.click(skip);
        expect(document.activeElement).toBe(screen.getByRole("main"));
    });

    it.each([false, true])("keeps destination autofocus when navigating with the mobile drawer open: %s", (mobileOpen) => {
        desktop = !mobileOpen;
        const { container, rerender } = render(shell());
        if (mobileOpen) fireEvent.click(screen.getByRole("button", { name: "showSidebar" }));
        navigation.pathname = "/library/documents/new";
        rerender(shell(<input id="tpl-name" aria-label="Document template name" autoFocus />));
        expect(document.activeElement).toBe(screen.getByRole("textbox", { name: "Document template name" }));
        expect(container.querySelector("#app-sidebar")?.hasAttribute("inert")).toBe(mobileOpen);
    });

    it.each([false, true])("focuses main without scrolling when the previous control was removed: %s", (removeFocusedControl) => {
        const { rerender } = render(shell());
        const previousControl = screen.getByRole("button", { name: "Page action" });
        if (removeFocusedControl) previousControl.focus();
        const main = screen.getByRole("main");
        const focus = vi.spyOn(main, "focus");
        navigation.pathname = "/records/contacts";
        rerender(shell(<p>Destination</p>));
        expect(previousControl.isConnected).toBe(false);
        expect(document.activeElement).toBe(main);
        expect(focus).toHaveBeenCalledExactlyOnceWith({ preventScroll: true });
    });

});
