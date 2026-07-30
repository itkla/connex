import { existsSync, readFileSync } from "node:fs";
import { SEED_LOG_PATH } from "../../../playwright.config";
import { parseSeedLog, type SeedFixture } from "./seed-log";

export {
    SEED_JAPANESE_USER_INDEX,
    SEED_PASSWORD,
    type SeedFixture,
    type SeedUser,
    type SeedWorkspace,
} from "./seed-log";

/** Whether the deterministic volume seeder ran against the stack under test. */
export function seedFixtureAvailable(): boolean {
    return existsSync(SEED_LOG_PATH);
}

/**
 * Loads the identities the deterministic volume seeder created for the stack under test.
 *
 * @returns the seeded workspaces and the shared seeded password
 * @throws when the seeder has not run for this stack
 */
export function seedFixture(): SeedFixture {
    if (!seedFixtureAvailable()) {
        throw new Error(
            `No seeder log at ${SEED_LOG_PATH}. Seed a disposable schema before booting the backend: `
            + "from backend/, `bash gradlew seedData -PseederProfile=small -PseederSeed=853 "
            + "-PseederWorkspaces=1 -PseederAnchorDate=2026-01-15 | tee "
            + "../frontend/test/e2e/.artifacts/seeder.log` (see docs/FRONTEND_TESTING.md).",
        );
    }
    return parseSeedLog(readFileSync(SEED_LOG_PATH, "utf8"));
}
