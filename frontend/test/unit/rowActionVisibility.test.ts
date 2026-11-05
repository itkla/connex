import { compile } from "tailwindcss";
import { describe, expect, it } from "vitest";

import { rowActionTrigger } from "@/app/components/settings/SettingsListPrimitives";

describe("row actions on touch and hybrid devices", () => {
    it.each(["hover:none", "any-pointer:coarse"])("emits a visible trigger for %s", async (query) => {
        const compiler = await compile("@tailwind utilities;");
        const styles = compiler.build(rowActionTrigger.split(/\s+/)).replace(/\s+/g, "");
        expect(styles).toMatch(new RegExp(`@media\\(${query}\\)\\{[^{}]+\\{opacity:(?:1|100%);`));
    });
});
