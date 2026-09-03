// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

const report = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock("@/app/lib/api", () => ({
    reportClientError: report,
}));

import {
    redactClientErrorPath,
    reportBoundaryError,
    reportBoundaryErrorWithConsole,
} from "@/app/lib/clientErrorReporter";

function hasDigest(value: unknown): value is Error & { digest: string } {
    return value instanceof Error && "digest" in value && typeof value.digest === "string";
}

afterEach(() => {
    report.mockClear();
    vi.restoreAllMocks();
});

describe("client error path redaction", () => {
    it.each([
        [`/document-acceptance/w12-${"a".repeat(64)}`, "/document-acceptance/[token]"],
        [`/unsubscribe/${"b".repeat(64)}`, "/unsubscribe/[token]"],
    ])("redacts %s before reporting", (pathname, expected) => {
        window.history.replaceState({}, "", pathname);

        reportBoundaryError(new Error(`Boundary at ${pathname}`));

        expect(report).toHaveBeenCalledWith(expect.objectContaining({ path: expected }));
        expect(JSON.stringify(report.mock.calls)).not.toContain(pathname);
    });

    it.each([
        ["/invite/future-path-bearer", "/invite/[token]"],
        ["/invite-link/future-path-bearer", "/invite-link/[token]"],
        ["/sso/link/future-path-bearer", "/sso/link/[token]"],
    ])("redacts future credential path %s", (pathname, expected) => {
        expect(redactClientErrorPath(pathname)).toBe(expected);
    });

    it("redacts the boundary error before console and persistent reporting", () => {
        const pathname = `/document-acceptance/w12-${"c".repeat(64)}`;
        const error = Object.assign(new Error(`Boundary at ${pathname}`), {
            digest: `digest-${pathname}`,
        });
        error.name = `Route error at ${pathname}`;
        error.stack = `Error: Boundary at ${pathname}\n    at ${pathname}:1:1`;
        window.history.replaceState({}, "", pathname);
        const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);

        reportBoundaryErrorWithConsole(error);

        expect(consoleError).toHaveBeenCalledTimes(1);
        const logged = consoleError.mock.calls[0]?.[0];
        if (!hasDigest(logged)) throw new Error("Console output omitted the redacted error digest");
        expect(logged.name).not.toContain(pathname);
        expect(logged.message).not.toContain(pathname);
        expect(logged.stack).not.toContain(pathname);
        expect(logged.digest).not.toContain(pathname);
        expect(logged.message).toContain("/document-acceptance/[token]");
        expect(logged.stack).toContain("/document-acceptance/[token]");
        expect(JSON.stringify(report.mock.calls)).not.toContain(pathname);
        expect(JSON.stringify(report.mock.calls)).toContain("/document-acceptance/[token]");
    });

    it("leaves ordinary paths untouched", () => {
        expect(redactClientErrorPath("/records/deals/42")).toBe("/records/deals/42");
    });
});
