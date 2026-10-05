import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";

const requestedPath = "/account/connections";
const destination = "/settings/personal/connected-accounts";

function summarize(overrides: Record<string, unknown>): string {
    const directory = mkdtempSync(path.join(tmpdir(), "connex-Q8b-matrix-"));
    const entry = {
        routeId: "account-connections",
        path: requestedPath,
        finalPath: destination,
        state: "success",
        axes: { viewport: "desktop", locale: "en", theme: "light" },
        faults: [],
        ...overrides,
    };
    try {
        writeFileSync(path.join(directory, "manifest.jsonl"), `${JSON.stringify(entry)}\n`);
        return execFileSync(process.execPath, ["test/e2e/matrix/summarize.mjs", directory], { encoding: "utf8" });
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
}

function landing(finalPath = destination, inAuthenticatedShell = true, ok = true) {
    return { requestedPath, finalPath, acceptedPaths: [destination], inAuthenticatedShell, ok };
}

describe("matrix summary landing evidence", () => {
    it("flags a failed capture even when the landing and page have no other faults", () => {
        const output = summarize({
            landing: landing(),
            state: "capture-failed",
            readinessFailure: "visible charts must render stable series",
        });
        expect(output).toContain("Cells needing triage: 1");
        expect(output).toContain("[capture-failed]");
        expect(output).toContain("capture readiness: visible charts must render stable series");
        expect(output).toContain("Cells that did not render as the route they requested: 0");
    });

    it("accepts a declared redirect verified inside the authenticated shell", () => {
        const output = summarize({ landing: landing() });
        expect(output).toContain("Cells that did not render as the route they requested: 0");
        expect(output).toContain("Cells without verified landing metadata: 0");
    });

    it.each(["/auth/login", "/records/contacts"])("rejects the undeclared destination %s", (finalPath) => {
        const output = summarize({ finalPath, landing: landing(finalPath) });
        expect(output).toContain("Cells that did not render as the route they requested: 1");
    });

    it("rejects the accepted path outside the authenticated shell", () => {
        expect(summarize({ landing: landing(destination, false) }))
            .toContain("Cells that did not render as the route they requested: 1");
    });

    it("keeps an explicit failed landing even when its paths match", () => {
        expect(summarize({ landing: landing(), state: "unexpected-landing" }))
            .toContain("Cells that did not render as the route they requested: 1");
        expect(summarize({ landing: landing(destination, true, false) }))
            .toContain("Cells that did not render as the route they requested: 1");
    });

    it("labels legacy entries unverified and conservatively flags changed paths", () => {
        const output = summarize({});
        expect(output).toContain("Cells without verified landing metadata: 1");
        expect(output).toContain("Cells that did not render as the route they requested: 1");
        expect(summarize({ finalPath: requestedPath })).toContain("Cells without verified landing metadata: 1");
    });
});
