import { describe, expect, it } from "vitest";
import { SEED_PASSWORD, parseSeedLog } from "@/test/e2e/support/seed-log";

const LOG = [
    "2026-01-15T00:00:00.000Z  INFO 1 --- [main] o.k.c.b.seeder.SeedDataRunner            : "
    + "Starting deterministic seed profile=SMALL seed=853 workspaces=2 anchorDate=2026-01-15",
    "2026-01-15T00:00:12.000Z  INFO 1 --- [main] o.k.c.b.seeder.SeedDataRunner            : "
    + "Seeder summary workspace=1 slug=seed-workspace-2v9k1lqz4b8xy-1 rowCounts={companies=10, persons=50, deals=20}",
    "2026-01-15T00:00:24.000Z  INFO 1 --- [main] o.k.c.b.seeder.SeedDataRunner            : "
    + "Seeder summary workspace=2 slug=seed-workspace-7fghij0abcdef-2 rowCounts={companies=10, persons=50, deals=20}",
].join("\n");

describe("parseSeedLog", () => {
    it("derives every seeded workspace identity from the summary lines", () => {
        const fixture = parseSeedLog(LOG);

        expect(fixture.password).toBe(SEED_PASSWORD);
        expect(fixture.workspaces).toHaveLength(2);
        expect(fixture.workspaces[0].key).toBe("2v9k1lqz4b8xy");
        expect(fixture.workspaces[0].ordinal).toBe(1);
        expect(fixture.workspaces[0].rowCounts).toEqual({ companies: 10, persons: 50, deals: 20 });
        expect(fixture.workspaces[1].key).toBe("7fghij0abcdef");
    });

    it("reconstructs the seeder's username and email scheme", () => {
        const [workspace] = parseSeedLog(LOG).workspaces;

        expect(workspace.users.map((user) => user.username)).toEqual([
            "seed-2v9k1lqz4b8xy-w1-u1",
            "seed-2v9k1lqz4b8xy-w1-u2",
            "seed-2v9k1lqz4b8xy-w1-u3",
            "seed-2v9k1lqz4b8xy-w1-u4",
            "seed-2v9k1lqz4b8xy-w1-u5",
        ]);
        expect(workspace.users[0].email).toBe("2v9k1lqz4b8xy-w1-u1@users.seed.invalid");
        expect(workspace.users[0].role).toBe("owner");
    });

    it("exposes the Japanese persona with her locale and timezone", () => {
        const [workspace] = parseSeedLog(LOG).workspaces;

        expect(workspace.japaneseUser).toEqual({
            username: "seed-2v9k1lqz4b8xy-w1-u4",
            email: "2v9k1lqz4b8xy-w1-u4@users.seed.invalid",
            displayName: "佐藤 美咲",
            role: "member",
            locale: "ja",
            timezone: "Asia/Tokyo",
        });
    });

    it("fails loudly rather than returning an empty fixture", () => {
        expect(() => parseSeedLog("BUILD SUCCESSFUL in 42s")).toThrow(/No 'Seeder summary/);
        expect(() => parseSeedLog("Seeder summary workspace=1 slug=not-a-seed-slug rowCounts={}"))
            .toThrow(/Unrecognized seeder workspace slug/);
    });
});
