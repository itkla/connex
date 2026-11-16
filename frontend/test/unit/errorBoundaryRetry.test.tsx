/** @vitest-environment jsdom */
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { act, type ComponentType } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { ErrorBoundaryHandler, type ErrorInfo } from "next/dist/client/components/error-boundary";
import ts from "typescript";
import { afterEach, describe, expect, it, vi } from "vitest";

import RootError from "@/app/error";
import GlobalError from "@/app/global-error";
import type { SegmentErrorProps } from "@/app/components/ErrorState";
import enErrors from "@/messages/en/errors.json";

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
}));

vi.mock("@/app/lib/clientErrorReporter", () => ({
    reportBoundaryErrorWithConsole: vi.fn(),
}));

afterEach(() => {
    document.body.replaceChildren();
    vi.clearAllMocks();
});

const FRONTEND = process.cwd();
const APP = path.join(FRONTEND, "app");
const APP_SHELL = path.join(APP, "(app)");
const ROOT_BOUNDARY = path.join(APP, "error.tsx");
const GLOBAL_BOUNDARY = path.join(APP, "global-error.tsx");
const ERROR_STATE = path.join(APP, "components", "ErrorState.tsx");
const BOUNDARY_FILE = /^(global-)?error\.tsx?$/;

/** Lists every segment and global error boundary under a directory. */
function errorBoundaries(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) return errorBoundaries(full);
        return BOUNDARY_FILE.test(entry.name) ? [full] : [];
    });
}

/** Narrows a dynamically imported module to one whose default export is a component. */
function isBoundaryModule(value: unknown): value is { default: ComponentType<SegmentErrorProps> } {
    return typeof value === "object"
        && value !== null
        && "default" in value
        && typeof value.default === "function";
}

/** Imports a boundary file and returns its default-exported component. */
async function loadBoundary(file: string): Promise<ComponentType<SegmentErrorProps>> {
    const loaded: unknown = await import(file);
    if (!isBoundaryModule(loaded)) {
        throw new Error(`${path.relative(FRONTEND, file)} does not default-export a boundary component`);
    }
    return loaded.default;
}

/** Renders a boundary with both recovery callbacks stubbed, and returns the mounted root. */
async function mountBoundary(
    Boundary: ComponentType<SegmentErrorProps>,
    props: SegmentErrorProps,
): Promise<Root> {
    const container = document.createElement("div");
    document.body.appendChild(container);
    const root = createRoot(container, { onCaughtError: vi.fn() });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={enErrors} timeZone="UTC" onError={() => {}}>
                <Boundary {...props} />
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
    });
    return root;
}

/** Clicks the boundary's recovery control by its rendered label. */
async function clickRecovery(label: string) {
    const button = [...document.body.querySelectorAll("button")]
        .find((candidate) => candidate.textContent?.includes(label));
    expect(button, `no recovery control labelled "${label}" rendered`).toBeDefined();
    await act(async () => {
        button?.click();
        await Promise.resolve();
    });
}

/** Boundary props whose recovery callbacks record which one the rendered control invoked. */
function props() {
    return {
        error: Object.assign(new Error("boom"), { digest: "abc123" }),
        reset: vi.fn<() => void>(),
        retry: vi.fn<() => void>(),
    };
}

const APP_SHELL_BOUNDARIES = errorBoundaries(APP_SHELL);

/**
 * The recovery split #1781 depends on, proved by clicking "Try again" on every boundary the app ships.
 *
 * Next's `retry` refreshes the router before resetting, so it is the only callback that re-fetches a
 * failed segment; `reset` re-renders the same client state and leaves a server-side failure in
 * place. Before #1781 every boundary read a prop Next no longer passes and silently fell back to
 * `reset`, so each app-shell boundary is mounted here rather than one standing in for the rest.
 *
 * The two root boundaries deliberately stay on `reset`: they are the only boundaries above a
 * one-time-link entry route, where a refresh can re-publish a stripped bearer.
 * `test/unit/oneTimeLinkEntryGuard.test.ts` holds that line from the entry side.
 */
describe("segment error boundary recovery", () => {
    it("discovers every app-shell boundary", () => {
        expect(APP_SHELL_BOUNDARIES.length).toBeGreaterThanOrEqual(15);
    });

    it.each(APP_SHELL_BOUNDARIES.map((file) => [path.relative(FRONTEND, file), file]))(
        "%s re-fetches the failed segment with Next's retry",
        async (_name, file) => {
            const Boundary = await loadBoundary(file);
            const boundary = props();
            const root = await mountBoundary(Boundary, boundary);

            await clickRecovery(enErrors.ErrorState.retry);

            expect(boundary.retry).toHaveBeenCalledTimes(1);
            expect(boundary.reset).not.toHaveBeenCalled();

            await act(async () => root.unmount());
        },
    );

    it("keeps the root boundary off the router-refreshing retry", async () => {
        const boundary = props();
        const root = await mountBoundary(RootError, boundary);

        await clickRecovery(enErrors.ErrorState.retry);

        expect(boundary.reset).toHaveBeenCalledTimes(1);
        expect(boundary.retry).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });

    it("keeps the global boundary off the router-refreshing retry", async () => {
        const boundary = props();
        const root = await mountBoundary(GlobalError, boundary);

        await clickRecovery("Try again");

        expect(boundary.reset).toHaveBeenCalledTimes(1);
        expect(boundary.retry).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });

    it("leaves no boundary outside the app shell and the two root boundaries unclassified", () => {
        const unclassified = errorBoundaries(APP)
            .filter((file) => !APP_SHELL_BOUNDARIES.includes(file))
            .filter((file) => file !== ROOT_BOUNDARY && file !== GLOBAL_BOUNDARY)
            .map((file) => path.relative(FRONTEND, file));

        expect(
            unclassified,
            "a new boundary must decide between retry and reset: retry only where no one-time-link entry route sits beneath it",
        ).toEqual([]);
    });
});

/** Parses a source file for the declaration check below. */
function sourceOf(file: string): ts.SourceFile {
    return ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
}

/** The members a named object-type alias declares, in declaration order. */
function typeMembers(file: string, alias: string): string[] {
    const source = sourceOf(file);
    const declaration = source.statements.find((statement): statement is ts.TypeAliasDeclaration =>
        ts.isTypeAliasDeclaration(statement) && statement.name.text === alias);
    if (declaration === undefined || !ts.isTypeLiteralNode(declaration.type)) return [];
    return declaration.type.members.flatMap((member) =>
        (ts.isPropertySignature(member) && member.name ? [member.name.getText(source)] : []));
}

/** A child that fails during render, so Next's boundary renders its fallback instead. */
function Throwing(): never {
    throw new Error("boom");
}

/**
 * Next's boundary props are a hand-written assertion in `SegmentErrorProps`: nothing in the route
 * type-check covers an `error.tsx`, so a rename on Next's side — it already renamed this prop once,
 * from `unstable_retry` to `retry` — would leave every boundary destructuring a prop Next no longer
 * passes and turn "Try again" into a click-time `TypeError`. Mounting Next's own
 * `ErrorBoundaryHandler` over a throwing child pins the real contract against the installed Next.
 */
describe("Next's error-boundary props", () => {
    it("passes exactly the recovery callbacks SegmentErrorProps declares", async () => {
        const captured: ErrorInfo[] = [];
        const container = document.createElement("div");
        document.body.appendChild(container);
        const root = createRoot(container, { onCaughtError: vi.fn() });

        await act(async () => {
            root.render(
                <ErrorBoundaryHandler
                    pathname="/dashboard"
                    errorComponent={(info: ErrorInfo) => {
                        captured.push(info);
                        return null;
                    }}
                >
                    <Throwing />
                </ErrorBoundaryHandler>,
            );
            await Promise.resolve();
        });

        expect(captured.length, "Next's boundary never rendered the probe").toBeGreaterThan(0);
        const declared = typeMembers(ERROR_STATE, "SegmentErrorProps").sort();
        expect(declared).toEqual(["error", "reset", "retry"]);
        for (const info of captured) {
            expect(Object.keys(info).sort()).toEqual(declared);
            expect(typeof info.reset).toBe("function");
            expect(typeof info.retry).toBe("function");
        }

        await act(async () => root.unmount());
    });
});
