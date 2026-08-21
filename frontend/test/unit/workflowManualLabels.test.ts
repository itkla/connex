import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const LAUNCHER = "app/components/settings/workflows/manual-runs/WorkflowManualRunLauncher.tsx";

function launcherSource(): string {
    return readFileSync(path.resolve(process.cwd(), LAUNCHER), "utf8");
}

describe("manual workflow preparation labels", () => {
    const launcher = launcherSource();

    it("renders the prepared actor label instead of leaking a raw member id", () => {
        expect(launcher).toContain('preparation.actorLabel ?? t("manual.actorUnavailable")');
        expect(launcher).not.toContain('`#${preparation.actorUserId}`');
    });

    it("resolves exceptional rows from ready and skipped label samples", () => {
        expect(launcher).toContain("[...preparation.samples, ...preparation.skippedSamples]");
    });

    it("uses a non-identifying unavailable state when no sampled label remains", () => {
        expect(launcher).toContain('t("manual.recordUnavailable")');
        expect(launcher).not.toContain('t("manual.recordFallbackWithId"');
    });
});
