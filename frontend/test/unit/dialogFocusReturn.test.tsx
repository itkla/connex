/** @vitest-environment jsdom */
import { StrictMode, useState } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogDescription, ResponsiveDialogTitle } from "@/components/ui/responsive-dialog";

const viewport = vi.hoisted(() => ({ mobile: false }));
vi.mock("@/app/hooks/useIsMobile", () => ({ useIsMobile: () => viewport.mobile }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));

beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
    Object.defineProperty(window, "matchMedia", { configurable: true, value: (media: string) => ({
        media, matches: false, addEventListener() {}, removeEventListener() {},
    }) });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function Harness({ drawer, openerState = "available", onOpenAutoFocus, onCloseAutoFocus }: {
    drawer: boolean;
    openerState?: "available" | "removed" | "disabled" | "hidden" | "unfocusable";
    onOpenAutoFocus?: (event: Event) => void;
    onCloseAutoFocus?: (event: Event) => void;
}) {
    const [open, setOpen] = useState(false);
    const Root = drawer ? ResponsiveDialog : Dialog;
    const Content = drawer ? ResponsiveDialogContent : DialogContent;
    const Title = drawer ? ResponsiveDialogTitle : DialogTitle;
    const Description = drawer ? ResponsiveDialogDescription : DialogDescription;
    return <>
        <main id="app-main" tabIndex={-1}><button>Another action</button></main>
        {openerState !== "removed" && (openerState === "unfocusable"
            ? <span>Launch</span>
            : <button disabled={openerState === "disabled"} hidden={openerState === "hidden"}
                onClick={() => setOpen(true)}>Launch</button>)}
        <Root open={open} onOpenChange={setOpen}>
            <Content onOpenAutoFocus={onOpenAutoFocus} onCloseAutoFocus={onCloseAutoFocus}>
                <Title>Create</Title><Description>Create a record</Description>
                <input aria-label="Record name" autoFocus />
                <button onClick={() => setOpen(false)}>Finish</button>
            </Content>
        </Root>
    </>;
}

for (const drawer of [false, true]) {
    describe(drawer ? "ResponsiveDialog drawer focus return" : "DialogContent focus return", () => {
        beforeEach(() => { viewport.mobile = drawer; });
        async function open() {
            const opener = screen.getByRole("button", { name: "Launch" });
            opener.focus();
            fireEvent.click(opener);
            await waitFor(() => expect(screen.getByRole("dialog").contains(document.activeElement)).toBe(true));
            return opener;
        }
        async function close() {
            fireEvent.click(screen.getByRole("button", { name: "Finish" }));
            await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
        }
        it("returns to the state opener across repeated open/close cycles", async () => {
            render(<Harness drawer={drawer} />);
            for (let cycle = 0; cycle < 2; cycle++) {
                const opener = await open();
                await close();
                await waitFor(() => expect(document.activeElement).toBe(opener));
            }
        });
        it("preserves the opener through StrictMode layout-effect replay", async () => {
            render(<StrictMode><Harness drawer={drawer} /></StrictMode>);
            const opener = await open();
            await close();
            await waitFor(() => expect(document.activeElement).toBe(opener));
        });
        it.each(["removed", "disabled", "hidden", "unfocusable"] as const)("falls back to main when the opener is %s", async (openerState) => {
            const view = render(<Harness drawer={drawer} />);
            await open();
            view.rerender(<Harness drawer={drawer} openerState={openerState} />);
            await close();
            await waitFor(() => expect(document.activeElement).toBe(document.getElementById("app-main")));
        });
        it("honors caller close preventDefault and its chosen focus target", async () => {
            const override = vi.fn((event: Event) => {
                event.preventDefault();
                screen.getByRole("button", { name: "Another action" }).focus();
            });
            render(<Harness drawer={drawer} onCloseAutoFocus={override} />);
            await open();
            await close();
            await waitFor(() => expect(override).toHaveBeenCalledOnce());
            expect(document.activeElement?.textContent).toBe("Another action");
        });
        it("captures the opener before a caller's open autofocus override", async () => {
            render(<Harness drawer={drawer} onOpenAutoFocus={(event) => {
                event.preventDefault();
                screen.getByRole("button", { name: "Finish" }).focus();
            }} />);
            const opener = await open();
            await close();
            await waitFor(() => expect(document.activeElement).toBe(opener));
        });
    });
}
