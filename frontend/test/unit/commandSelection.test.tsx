/** @vitest-environment jsdom */
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { compile } from "tailwindcss";

import { Command, CommandItem, CommandList } from "@/components/ui/command";

beforeEach(() => {
    vi.stubGlobal("ResizeObserver", class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it("applies generated outline rules only to the selected cmdk row", async () => {
    render(<Command value="selected"><CommandList>
        <CommandItem value="selected">Selected</CommandItem>
        <CommandItem value="unselected">Unselected</CommandItem>
    </CommandList></Command>);
    const selected = screen.getByRole("option", { name: "Selected" });
    const unselected = screen.getByRole("option", { name: "Unselected" });
    await waitFor(() => expect(selected.getAttribute("data-selected")).toBe("true"));
    expect(unselected.getAttribute("data-selected")).toBe("false");

    const ringClasses = Array.from(unselected.classList).filter((name) => name.includes(":ring-"));
    const compiler = await compile("@theme { --color-brand-text: var(--brand-text); } @tailwind utilities;");
    const generated = compiler.build(ringClasses);
    const selectors = Array.from(generated.matchAll(/^(\.[^\n{]+)\s*\{/gm), (match) => match[1].trim());
    expect(selectors.length).toBe(ringClasses.length);
    expect(selectors.length).toBeGreaterThan(0);
    for (const selector of selectors) {
        expect(selected.matches(selector)).toBe(true);
        expect(unselected.matches(selector)).toBe(false);
    }
});
