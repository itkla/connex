import { describe, expect, it } from "vitest";

import { filterByNameQuery } from "@/app/lib/filterByNameQuery";

describe("filterByNameQuery", () => {
    const items = [
        { id: 1, name: "Acme Corp" },
        { id: 2, name: "Blizzard Entertainment" },
        { id: 3, name: "blizzard soft" },
    ];

    it("returns all items when the query is blank", () => {
        expect(filterByNameQuery(items, "  ")).toEqual(items);
        expect(filterByNameQuery(items, "")).toEqual(items);
    });

    it("filters by case-insensitive name contains", () => {
        expect(filterByNameQuery(items, "blizzard")).toEqual([
            { id: 2, name: "Blizzard Entertainment" },
            { id: 3, name: "blizzard soft" },
        ]);
    });
});
