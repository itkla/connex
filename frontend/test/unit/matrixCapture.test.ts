import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { chromium } from "@playwright/test";
import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";

const capture = vi.hoisted(() => ({
    directory: "",
    visible: vi.fn<() => Promise<void>>(),
    skeletons: vi.fn<() => Promise<void>>(),
    settled: vi.fn<() => Promise<void>>(),
    screenshot: vi.fn<(options: { path: string }) => Promise<Buffer>>(),
}));

vi.mock("../../playwright.matrix.config", () => ({
    MATRIX_ARTIFACT_DIR: capture.directory,
    MATRIX_BASE_URL: "http://localhost:3000",
}));

vi.mock("@playwright/test", () => {
    const locator = {
        count: async () => 1,
        first: () => locator,
        locator: () => locator,
    };
    return {
        chromium: {
            launch: async () => ({
                newPage: async () => ({
                    locator: () => locator,
                    evaluate: async () => undefined,
                    screenshot: capture.screenshot,
                }),
            }),
        },
        expect: Object.assign(() => ({
            toBeVisible: capture.visible,
            toHaveCount: capture.skeletons,
        }), {
            poll: () => ({ toBe: capture.settled }),
        }),
    };
});

capture.directory = mkdtempSync(path.join(tmpdir(), "connex-Q8b-capture-"));
const { record } = await import("../e2e/matrix/support/matrix");
const page = await (await chromium.launch()).newPage();
const entry = {
    routeId: "dashboard",
    path: "/dashboard",
    finalPath: "/dashboard",
    state: "success",
    axes: { viewport: "desktop", locale: "en", theme: "light" },
    faults: [],
    httpStatus: 200,
    landing: {
        requestedPath: "/dashboard",
        finalPath: "/dashboard",
        acceptedPaths: ["/dashboard"],
        inAuthenticatedShell: true,
        ok: true,
    },
    notes: "existing capture context",
} satisfies Parameters<typeof record>[1];
const screenshot = "shots/dashboard__desktop-en-light__success.png";

beforeEach(() => {
    rmSync(capture.directory, { recursive: true, force: true });
    capture.visible.mockReset().mockResolvedValue(undefined);
    capture.skeletons.mockReset().mockResolvedValue(undefined);
    capture.settled.mockReset().mockResolvedValue(undefined);
    capture.screenshot.mockReset().mockImplementation(async (options) => {
        const bytes = Buffer.from("captured page");
        writeFileSync(options.path, bytes);
        return bytes;
    });
});

afterAll(() => {
    rmSync(capture.directory, { recursive: true, force: true });
});

function manifest(): unknown {
    return JSON.parse(readFileSync(path.join(capture.directory, "manifest.jsonl"), "utf8"));
}

describe("matrix capture failure evidence", () => {
    it.each(["heading", "skeleton", "chart", "expanded chart"] as const)(
        "captures artifacts before propagating the %s readiness error",
        async (condition) => {
            const failure = new Error(`${condition} readiness failed`);
            if (condition === "heading") {
                capture.visible.mockResolvedValueOnce(undefined).mockRejectedValueOnce(failure);
            } else if (condition === "skeleton") {
                capture.skeletons.mockRejectedValueOnce(failure);
            } else {
                capture.settled.mockResolvedValueOnce(undefined);
                if (condition === "expanded chart") capture.settled.mockResolvedValueOnce(undefined);
                capture.settled.mockRejectedValueOnce(failure);
            }

            await expect(record(page, entry)).rejects.toBe(failure);

            expect(capture.screenshot).toHaveBeenCalledExactlyOnceWith({
                path: path.join(capture.directory, screenshot),
                fullPage: true,
                animations: "disabled",
            });
            expect(readFileSync(path.join(capture.directory, screenshot), "utf8")).toBe("captured page");
            expect(manifest()).toEqual({
                ...entry,
                state: "capture-failed",
                screenshot,
                readinessFailure: failure.message,
                notes: "existing capture context :: capture readiness failed for state success",
            });
        },
    );

    it("captures artifacts before propagating a caller's motion-readiness error", async () => {
        const failure = new Error("content sections never reached their settled opacity and geometry");
        const checkReadiness = vi.fn(async () => {
            throw failure;
        });

        await expect(record(page, entry, checkReadiness)).rejects.toBe(failure);

        expect(checkReadiness).toHaveBeenCalledTimes(1);
        expect(capture.visible).not.toHaveBeenCalled();
        expect(capture.screenshot).toHaveBeenCalledExactlyOnceWith({
            path: path.join(capture.directory, screenshot),
            fullPage: true,
            animations: "disabled",
        });
        expect(readFileSync(path.join(capture.directory, screenshot), "utf8")).toBe("captured page");
        expect(manifest()).toEqual({
            ...entry,
            state: "capture-failed",
            screenshot,
            readinessFailure: failure.message,
            notes: "existing capture context :: capture readiness failed for state success",
        });
    });

    it("checks caller readiness before capture preparation and records its diagnostic notes", async () => {
        const notes = "h1 opacity=1 sections=3 stranded=0";
        const checkReadiness = vi.fn(async () => {
            expect(capture.visible).not.toHaveBeenCalled();
            expect(capture.screenshot).not.toHaveBeenCalled();
            return notes;
        });

        await record(page, entry, checkReadiness);

        expect(checkReadiness).toHaveBeenCalledTimes(1);
        expect(capture.visible).toHaveBeenCalledTimes(2);
        expect(manifest()).toEqual({ ...entry, screenshot, notes });
        expect(capture.screenshot).toHaveBeenCalledTimes(1);
    });

    it("preserves a ready cell's successful state and evidence", async () => {
        await record(page, entry);

        expect(manifest()).toEqual({ ...entry, screenshot });
        expect(capture.screenshot).toHaveBeenCalledTimes(1);
    });
});
