import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { ALL_WIDGET_TYPES, WIDGET_META } from "@/app/components/dashboard/customize/dashboardWidgets";

/**
 * Every widget title must resolve in the DashboardPage namespace, in both locales.
 *
 * A missing key does not fail any build gate: next-intl logs MISSING_MESSAGE during server
 * rendering and degrades to the raw key on screen, so a catalog sweep that removes a key a
 * widget still declares ships as production jank rather than a red check — which is exactly
 * how `DashboardPage.overview` broke when the D13 navigation rework pruned the catalogs.
 */
describe("dashboard widget titles", () => {
    const catalogs = ["en", "ja"].map((locale) => {
        const file = path.join(process.cwd(), "messages", locale, "dashboard.json");
        const parsed = JSON.parse(readFileSync(file, "utf-8")) as {
            DashboardPage: Record<string, string>;
        };
        return { locale, page: parsed.DashboardPage };
    });

    it.each(catalogs)("resolve for every declared widget in $locale", ({ page }) => {
        const missing = ALL_WIDGET_TYPES
            .map((type) => WIDGET_META[type].titleKey)
            .filter((titleKey) => typeof page[titleKey] !== "string" || page[titleKey].length === 0);
        expect(missing).toEqual([]);
    });
});
