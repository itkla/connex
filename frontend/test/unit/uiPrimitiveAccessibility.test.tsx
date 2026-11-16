/** @vitest-environment jsdom */
import { readFileSync } from "node:fs";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { StageOrderControls } from "@/app/components/records/pipelines/StageOrderControls";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Switch } from "@/components/ui/switch";
import { DialogStatusCover, fieldInputClass } from "@/components/ui/dialog-status-cover";
import SearchField from "@/app/components/filters/SearchField";
import MentionEditor from "@/app/components/activity/notes/MentionEditor";

vi.mock("@/components/PixelCard", () => ({ default: ({ colors }: { colors: string }) => <div data-testid="palette" data-colors={colors} /> }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
afterEach(cleanup);

describe("forced-colors focus contracts", () => {
    it("retains outlines on every shared keyboard control and a system border on brand buttons", () => {
        render(<>
            <Button variant="brand">Save</Button><Input aria-label="Name" />
            <Select><SelectTrigger aria-label="Stage"><SelectValue /></SelectTrigger></Select>
            <Tabs defaultValue="one"><TabsList><TabsTrigger value="one">One</TabsTrigger></TabsList></Tabs>
            <SegmentedControl value="one" onChange={() => {}} ariaLabel="View" options={[{ value: "one", label: "List" }]} />
            <Checkbox aria-label="Complete" /><Switch aria-label="Enabled" />
        </>);
        for (const control of [
            screen.getByRole("button", { name: "Save" }), screen.getByRole("textbox"), screen.getByRole("combobox"),
            screen.getByRole("tab"), screen.getByRole("button", { name: "List" }), screen.getByRole("checkbox"), screen.getByRole("switch"),
        ]) {
            expect(control.classList.contains("outline-hidden")).toBe(true);
            expect(control.classList.contains("forced-colors:focus-visible:outline-2")).toBe(true);
            expect(control.classList.contains("forced-colors:focus-visible:outline-solid")).toBe(true);
            expect(control.classList.contains("forced-colors:focus-visible:outline-[Highlight]")).toBe(true);
        }
        expect(screen.getByRole("button", { name: "Save" }).classList.contains("forced-colors:border-[ButtonText]")).toBe(true);
    });
});

describe("coarse-pointer hit areas", () => {
    it("leaves dense controls inside their own bounds by default", () => {
        const move = vi.fn();
        render(<TooltipProvider>
            <StageOrderControls id="middle" index={1} count={3} disabled={false} onMove={move} />
            <Checkbox aria-label="Select row" />
            <Button size="icon-toolbar">Toolbar</Button>
            <Button size="icon-inline">Inline</Button>
        </TooltipProvider>);
        for (const control of [...screen.getAllByRole("button"), screen.getByRole("checkbox")]) {
            expect(control.classList.contains("touch-target")).toBe(false);
        }
        fireEvent.click(screen.getByRole("button", { name: "moveUp" }));
        fireEvent.click(screen.getByRole("button", { name: "moveDown" }));
        expect(move.mock.calls).toEqual([[0, "up"], [2, "down"]]);
    });

    it("expands opted-in controls without resizing their boxes", () => {
        render(<><Checkbox expandHitArea aria-label="Complete" className="size-[18px]" />
            {(["icon-toolbar", "icon-inline", "icon-sm", "icon-xs"] as const).map((size) => <Button key={size} size={size} expandHitArea>{size}</Button>)}
        </>);
        const checkbox = screen.getByRole("checkbox");
        expect(checkbox.classList.contains("size-[18px]")).toBe(true);
        for (const control of [checkbox, ...screen.getAllByRole("button")]) {
            expect(control.classList.contains("relative")).toBe(true);
            expect(control.classList.contains("touch-target")).toBe(true);
        }
        const css = readFileSync("app/globals.css", "utf8");
        expect(css).toMatch(/@media \(pointer: coarse\)\s*\{\s*\.touch-target::after\s*\{/);
        expect(css).toContain("width: max(100%, 44px)");
        expect(css).toContain("height: max(100%, 44px)");
    });
});

describe("mobile editing text", () => {
    it("uses 16px below md and 14px from md in dialog fields, search, and notes", () => {
        const { container } = render(<>
            <input aria-label="Subject" className={fieldInputClass} />
            <SearchField value="" onChange={() => {}} onClear={() => {}} placeholder="Search" searchAria="Search" clearAria="Clear" />
            <MentionEditor value="" onChange={() => {}} />
        </>);
        const editor = container.querySelector('[contenteditable="true"]');
        if (!editor) throw new Error("Missing note editor");
        for (const control of [...screen.getAllByRole("textbox"), editor]) {
            expect(control.classList.contains("text-base")).toBe(true);
            expect(control.classList.contains("md:text-sm")).toBe(true);
        }
        const layout = readFileSync("app/layout.tsx", "utf8");
        expect(layout).not.toMatch(/maximumScale|maximum-scale/);
    });
});

it("retains the previous dialog-cover palette while idle", () => {
    const view = render(<DialogStatusCover status="success" />);
    const success = screen.getByTestId("palette").getAttribute("data-colors");
    view.rerender(<DialogStatusCover status="idle" />);
    expect(screen.getByTestId("palette").getAttribute("data-colors")).toBe(success);
    view.rerender(<DialogStatusCover status="error" />);
    const error = screen.getByTestId("palette").getAttribute("data-colors");
    expect(error).not.toBe(success);
    view.rerender(<DialogStatusCover status="idle" />);
    expect(screen.getByTestId("palette").getAttribute("data-colors")).toBe(error);
});
