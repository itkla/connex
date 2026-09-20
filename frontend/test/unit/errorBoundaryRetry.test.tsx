/** @vitest-environment jsdom */
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { act, type ComponentType } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { ErrorBoundaryHandler, type ErrorInfo } from "next/dist/client/components/error-boundary";
import ts from "typescript";
import { afterEach, describe, expect, it, vi } from "vitest";

import AppError from "@/app/(app)/error";
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
    expect(button).toBeDefined();
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

describe("segment error boundaries", () => {
    it("re-fetches the failed segment from an app-shell boundary", async () => {
        const boundary = props();
        const root = await mountBoundary(AppError, boundary);

        await clickRecovery(enErrors.ErrorState.retry);

        expect(boundary.retry).toHaveBeenCalledTimes(1);
        expect(boundary.reset).not.toHaveBeenCalled();

        await act(async () => root.unmount());
    });

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
});

const FRONTEND = process.cwd();
const APP = path.join(FRONTEND, "app");
const APP_SHELL = path.join(APP, "(app)");
const ROOT_BOUNDARIES = ["error.tsx", "global-error.tsx"].map((name) => path.join(APP, name));
const ERROR_STATE = path.join(APP, "components", "ErrorState.tsx");

/** Parses a source file the way both source-level checks below read it. */
function sourceOf(file: string): ts.SourceFile {
    return ts.createSourceFile(
        file,
        readFileSync(file, "utf8"),
        ts.ScriptTarget.Latest,
        true,
        ts.ScriptKind.TSX,
    );
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

/** Lists every segment error boundary under a directory. */
function errorBoundaries(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) return errorBoundaries(full);
        return entry.name === "error.tsx" ? [full] : [];
    });
}

/** The prop names a boundary's default-exported component destructures from Next's error props. */
function recoveryProps(file: string): string[] {
    const source = sourceOf(file);
    const exported = source.statements.find((statement): statement is ts.FunctionDeclaration =>
        ts.isFunctionDeclaration(statement)
        && (statement.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.DefaultKeyword) ?? false));
    const parameter = exported?.parameters[0]?.name;
    if (parameter === undefined || !ts.isObjectBindingPattern(parameter)) return [];
    return parameter.elements.map((element) => (element.propertyName ?? element.name).getText(source));
}

/**
 * The split the three mounted cases above prove, asserted across every boundary that carries it.
 * Only `app/(app)/error.tsx` is mounted here, so without this the other sixteen app-shell
 * boundaries could drift back to `reset` — the dead "Try again" of #1781 Part 2 — with a green
 * suite, and nothing else looks at them: the one-time-link guard only scans boundaries that sit
 * above an entry route, and every entry route lives outside the `(app)` group.
 */
describe("segment error boundary recovery ledger", () => {
    it("recovers every app-shell boundary with Next's retry prop", () => {
        const boundaries = errorBoundaries(APP_SHELL);
        const violations = boundaries
            .filter((file) => !recoveryProps(file).includes("retry"))
            .map((file) => `${path.relative(FRONTEND, file)} does not recover with Next's retry prop`);

        expect(boundaries.length).toBeGreaterThanOrEqual(17);
        expect(violations).toEqual([]);
    });

    it("keeps the boundaries above a one-time-link entry route on reset", () => {
        const ledger = ROOT_BOUNDARIES.map((file) => ({
            boundary: path.relative(FRONTEND, file),
            recovers: recoveryProps(file).filter((name) => name === "reset" || name === "retry"),
        }));

        expect(ledger).toEqual([
            { boundary: "app/error.tsx", recovers: ["reset"] },
            { boundary: "app/global-error.tsx", recovers: ["reset"] },
        ]);
    });
});
