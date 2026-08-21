import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import {
    parsePipelineEditId,
    pipelineEditHref,
} from "@/app/components/records/pipelines/pipelineLinks";

describe("pipeline edit deep links", () => {
    it("preserves the exact pipeline identity in the browser-owned edit parameter", () => {
        expect(pipelineEditHref(47)).toBe("/records/pipelines?edit=47");
    });

    it("accepts only a plain positive safe integer identity", () => {
        expect(parsePipelineEditId("47")).toBe(47);
        expect(parsePipelineEditId(null)).toBeNull();
        expect(parsePipelineEditId("0")).toBeNull();
        expect(parsePipelineEditId("-1")).toBeNull();
        expect(parsePipelineEditId("4.7")).toBeNull();
        expect(parsePipelineEditId(" 47 ")).toBeNull();
        expect(parsePipelineEditId("99999999999999999999")).toBeNull();
    });

    it("routes every terminal sheet close through deep-link cleanup", () => {
        const browser = readFileSync(
            path.resolve(process.cwd(), "app/components/records/pipelines/PipelinesBrowser.tsx"),
            "utf8",
        );

        expect(browser).not.toContain("setEditSheetOpen(false)");
        expect(browser).toContain("changeEditSheetOpen(false)");
        expect(browser).toContain("clearPipelineEditDeepLink();");
    });
});
