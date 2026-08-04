import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const SOURCE = readFileSync(
    path.resolve(process.cwd(), "app/components/activity/notes/editor/SelectionToolbar.tsx"),
    "utf8",
);

describe("SelectionToolbar lifecycle", () => {
    it("keeps BubbleMenu options stable across transaction-driven renders", () => {
        expect(SOURCE).toContain('const MENU_OPTIONS: NonNullable<BubbleMenuProps["options"]>');
        expect(SOURCE).toContain(
            'const shouldShowSelectionToolbar: NonNullable<BubbleMenuProps["shouldShow"]>',
        );
        expect(SOURCE).toContain("options={MENU_OPTIONS}");
        expect(SOURCE).toContain("shouldShow={shouldShowSelectionToolbar}");
        expect(SOURCE).not.toMatch(/shouldShow=\{\(\{/);
    });
});
