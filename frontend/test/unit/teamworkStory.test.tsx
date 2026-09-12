// @vitest-environment jsdom

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { TeamworkStory } from "@/app/components/landing/TeamworkStory";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const ids = ["deals", "quotes", "progress", "handover"];
const steps = ids.map((id) => ({ id, content: <p>{id}</p>, visual: <span>{id} illustration</span> }));
let container: HTMLDivElement;
let root: Root;
let eligible: boolean;
let scroll: number;
let mediaListeners: Set<() => void>;
let frames: Map<number, FrameRequestCallback>;
let nextFrame: number;
let intersect: (visible: boolean) => void;
let disconnect = vi.fn<() => void>();

beforeEach(() => {
    eligible = true;
    scroll = 0;
    nextFrame = 0;
    mediaListeners = new Set();
    frames = new Map();
    disconnect = vi.fn();
    vi.stubGlobal("innerHeight", 800);
    vi.stubGlobal("matchMedia", () => ({ get matches() { return eligible; }, addEventListener: (_: string, fn: () => void) => mediaListeners.add(fn), removeEventListener: (_: string, fn: () => void) => mediaListeners.delete(fn) }));
    vi.stubGlobal("IntersectionObserver", class {
        constructor(callback: (entries: { isIntersecting: boolean }[]) => void) { intersect = (visible) => callback([{ isIntersecting: visible }]); }
        observe() {}
        disconnect() { disconnect(); }
    });
    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => { frames.set(++nextFrame, callback); return nextFrame; });
    vi.spyOn(window, "cancelAnimationFrame").mockImplementation((id) => { frames.delete(id); });
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
        const pinned = this.hasAttribute("data-team-frame");
        const height = pinned ? 736 : 2800;
        const top = pinned ? Math.min(Math.max(64, 100 - scroll), 100 - scroll + 2800 - 736) : 100 - scroll;
        return { top, height, bottom: top + height, left: 0, right: 400, width: 400, x: 0, y: top, toJSON: () => ({}) };
    });
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

async function mount() { await act(async () => root.render(<TeamworkStory steps={steps} header={<h2>Teamwork</h2>} caption="Illustration" scrollLabel="Scroll to continue" />)); }
async function flushFrame() {
    await act(async () => {
        const callbacks = [...frames.values()];
        frames.clear();
        callbacks.forEach((callback) => callback(0));
    });
}
function activeScene() { return container.querySelector('[data-team-scene][data-active="true"]')?.getAttribute("data-team-scene"); }

it("advances the pinned chapter through forward, backward, and skipped scroll positions", async () => {
    await mount();
    await act(async () => intersect(true));
    await flushFrame();
    expect(activeScene()).toBe("deals");
    for (const [position, id] of [[1200, "progress"], [1800, "handover"], [600, "quotes"], [0, "deals"]] as const) {
        scroll = position;
        await act(async () => { window.dispatchEvent(new Event("scroll")); window.dispatchEvent(new Event("scroll")); });
        expect(frames.size).toBe(1);
        await flushFrame();
        expect(activeScene()).toBe(id);
        expect(container.querySelectorAll("[data-team-step]")).toHaveLength(4);
    }
});

it("only enables transitions for pointer scrolling and cancels pending work when leaving", async () => {
    await mount();
    const story = container.querySelector("[data-team-story]");
    await act(async () => { intersect(true); window.dispatchEvent(new WheelEvent("wheel")); });
    expect(story?.getAttribute("data-team-motion")).toBe("true");
    await act(async () => window.dispatchEvent(new KeyboardEvent("keydown", { key: "PageDown" })));
    expect(story?.getAttribute("data-team-motion")).toBe("false");
    expect(frames.size).toBe(1);
    await act(async () => intersect(false));
    expect(frames.size).toBe(0);
    await act(async () => window.dispatchEvent(new Event("scroll")));
    expect(frames.size).toBe(0);
});

it("restores inline content when viewport or motion preferences change and cleans up listeners", async () => {
    await mount();
    await act(async () => intersect(true));
    expect(container.querySelector("[data-team-story]")?.getAttribute("data-enhanced")).toBe("true");
    await act(async () => { eligible = false; mediaListeners.forEach((listener) => listener()); });
    expect(container.querySelector("[data-team-story]")?.getAttribute("data-enhanced")).toBe("false");
    expect(container.querySelectorAll("[data-team-step]")).toHaveLength(4);
    expect(frames.size).toBe(0);
    expect(disconnect).toHaveBeenCalled();
    await act(async () => root.render(null));
    expect(mediaListeners.size).toBe(0);
});

it("keeps all passages available when scroll observation is unavailable", async () => {
    vi.stubGlobal("IntersectionObserver", undefined);
    await mount();
    expect(container.querySelector("[data-team-story]")?.getAttribute("data-enhanced")).toBe("false");
    expect(container.querySelectorAll("[data-team-step]")).toHaveLength(4);
    expect(frames.size).toBe(0);
});
