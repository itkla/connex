// @vitest-environment jsdom

import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FujiMotion } from "@/app/components/landing/FujiMotion";
import { TooltipProvider } from "@/components/ui/tooltip";

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;
let reducedMotion: boolean;
let pageHidden: boolean;
let sectionTop: number;
let motionListeners: Set<() => void>;
let notifyIntersection: (visible: boolean) => void;
const disconnect = vi.fn<() => void>();
let frames: Map<number, FrameRequestCallback>;
let nextFrame: number;

function Example() {
    return (
        <TooltipProvider>
            <section>
                <FujiMotion pauseLabel="Pause clouds" resumeLabel="Resume clouds">
                    <svg aria-hidden="true"><g data-fuji-depth="0.08" /><g data-fuji-depth="0.28" /></svg>
                </FujiMotion>
            </section>
        </TooltipProvider>
    );
}

function scene() {
    const element = container.querySelector<HTMLDivElement>("[data-fuji-scene]");
    if (!element) throw new Error("Missing Fuji scene");
    return element;
}

function control() {
    const element = container.querySelector<HTMLButtonElement>("button");
    if (!element) throw new Error("Missing cloud motion control");
    return element;
}

function offsets() {
    return Array.from(container.querySelectorAll<SVGGElement>("g[data-fuji-depth]"), (layer) => layer.style.transform);
}

async function mount() {
    await act(async () => root.render(<Example />));
    const section = container.querySelector("section");
    if (!section) throw new Error("Missing hero section");
    vi.spyOn(section, "getBoundingClientRect").mockImplementation(() => ({ top: sectionTop, bottom: sectionTop + 800, left: 0, right: 1200, x: 0, y: sectionTop, width: 1200, height: 800, toJSON: () => ({}) }));
}

function flushFrames() {
    const pending = Array.from(frames.values());
    frames.clear();
    pending.forEach((callback) => callback(0));
}

beforeEach(() => {
    reducedMotion = false;
    pageHidden = false;
    sectionTop = 0;
    motionListeners = new Set();
    frames = new Map();
    nextFrame = 0;
    notifyIntersection = () => undefined;
    disconnect.mockReset();
    vi.stubGlobal("matchMedia", (query: string) => ({
        get matches() { return reducedMotion; },
        media: query,
        addEventListener: (_: string, listener: () => void) => motionListeners.add(listener),
        removeEventListener: (_: string, listener: () => void) => motionListeners.delete(listener),
    }));
    Object.defineProperty(document, "hidden", { configurable: true, get: () => pageHidden });
    vi.stubGlobal("IntersectionObserver", class {
        constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
            notifyIntersection = (visible) => callback([{ isIntersecting: visible }]);
        }
        observe() {}
        disconnect() { disconnect(); }
    });
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
        frames.set(++nextFrame, callback);
        return nextFrame;
    });
    vi.stubGlobal("cancelAnimationFrame", (frame: number) => frames.delete(frame));
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

describe("Fuji cloud motion", () => {
    it("renders still scenery and hides the motion control before JavaScript", () => {
        const document = new DOMParser().parseFromString(renderToStaticMarkup(<Example />), "text/html");
        expect(document.querySelector("[data-fuji-scene]")?.getAttribute("data-fuji-motion")).toBe("still");
        expect(document.querySelector("button")?.closest("[hidden]")).not.toBeNull();
        expect(document.querySelectorAll("g[data-fuji-depth]")).toHaveLength(2);
    });

    it("coalesces scroll updates and preserves a user pause after leaving and returning", async () => {
        await mount();
        expect(scene().dataset.fujiMotion).toBe("paused");
        await act(async () => notifyIntersection(true));
        expect(scene().dataset.fujiMotion).toBe("running");
        sectionTop = -125;
        window.dispatchEvent(new Event("scroll"));
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(1);
        flushFrames();
        expect(frames.size).toBe(0);
        expect(offsets()).toEqual(["translate3d(0, 10px, 0)", "translate3d(0, 35px, 0)"]);

        await act(async () => control().click());
        expect(scene().dataset.fujiMotion).toBe("paused");
        expect(control().getAttribute("aria-label")).toBe("Resume clouds");
        sectionTop = -250;
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(0);
        await act(async () => notifyIntersection(false));
        await act(async () => notifyIntersection(true));
        expect(scene().dataset.fujiMotion).toBe("paused");
        expect(offsets()[0]).toBe("translate3d(0, 10px, 0)");
        await act(async () => control().click());
        expect(scene().dataset.fujiMotion).toBe("running");
        expect(offsets()[0]).toBe("translate3d(0, 20px, 0)");
    });

    it("freezes while offscreen or in a hidden tab and cancels pending work", async () => {
        await mount();
        await act(async () => notifyIntersection(true));
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(1);
        await act(async () => notifyIntersection(false));
        expect(frames.size).toBe(0);
        expect(scene().dataset.fujiMotion).toBe("paused");
        await act(async () => {
            pageHidden = true;
            document.dispatchEvent(new Event("visibilitychange"));
            notifyIntersection(true);
        });
        expect(scene().dataset.fujiMotion).toBe("paused");
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(0);
        await act(async () => {
            pageHidden = false;
            document.dispatchEvent(new Event("visibilitychange"));
        });
        expect(scene().dataset.fujiMotion).toBe("running");
    });

    it("clears parallax on a live reduced-motion change and restores no paused motion", async () => {
        await mount();
        sectionTop = -100;
        await act(async () => notifyIntersection(true));
        await act(async () => control().click());
        await act(async () => {
            reducedMotion = true;
            motionListeners.forEach((listener) => listener());
        });
        expect(scene().dataset.fujiMotion).toBe("still");
        expect(offsets()).toEqual(["", ""]);
        expect(control().closest("[hidden]")).not.toBeNull();
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(0);
        await act(async () => {
            reducedMotion = false;
            motionListeners.forEach((listener) => listener());
        });
        expect(scene().dataset.fujiMotion).toBe("paused");
        expect(control().getAttribute("aria-label")).toBe("Resume clouds");
        expect(offsets()).toEqual(["", ""]);
    });

    it("starts still for reduced motion and releases observers, listeners, and animation frames", async () => {
        reducedMotion = true;
        const removeWindowListener = vi.spyOn(window, "removeEventListener");
        const removeDocumentListener = vi.spyOn(document, "removeEventListener");
        await mount();
        await act(async () => notifyIntersection(true));
        expect(scene().dataset.fujiMotion).toBe("still");
        expect(offsets()).toEqual(["", ""]);
        await act(async () => {
            reducedMotion = false;
            motionListeners.forEach((listener) => listener());
        });
        window.dispatchEvent(new Event("scroll"));
        expect(frames.size).toBe(1);
        await act(async () => root.render(null));
        expect(frames.size).toBe(0);
        expect(motionListeners.size).toBe(0);
        expect(disconnect).toHaveBeenCalledOnce();
        expect(removeWindowListener).toHaveBeenCalledWith("scroll", expect.any(Function));
        expect(removeDocumentListener).toHaveBeenCalledWith("visibilitychange", expect.any(Function));
    });
});
