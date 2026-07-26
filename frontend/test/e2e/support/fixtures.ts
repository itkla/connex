import { readFileSync } from "node:fs";
import { RUN_FIXTURE_PATH } from "../../../playwright.config";
import type { RunFixture } from "./api";

/** Loads the tenant fixture the setup project provisioned for this run. */
export function runFixture(): RunFixture {
    return JSON.parse(readFileSync(RUN_FIXTURE_PATH, "utf8")) as RunFixture;
}
