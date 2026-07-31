import { readFileSync } from "node:fs";
import { runFixturePath, tenantScopeForProject } from "../../../playwright.config";
import type { RunFixture, SeededRecord } from "./api";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function isSeededRecord(value: unknown): value is SeededRecord {
    return isRecord(value) && typeof value.id === "number" && typeof value.name === "string";
}

function isRunFixture(value: unknown): value is RunFixture {
    if (!isRecord(value)) {
        return false;
    }
    const { contacts, companies, deals, activities } = value;
    if (typeof value.username !== "string"
        || typeof value.password !== "string"
        || typeof value.email !== "string"
        || typeof value.workspaceId !== "number"
        || typeof value.companyName !== "string"
        || typeof value.ambiguityEmail !== "string"
        || typeof value.ambiguityEmailJa !== "string"
        || !isRecord(contacts)
        || !isRecord(companies)
        || !isRecord(deals)
        || !isRecord(activities)) {
        return false;
    }
    return [
        "peek",
        "edit",
        "activity",
        "search",
        "archive",
        "ambiguityPrimary",
        "ambiguitySecondary",
        "ambiguityPrimaryJa",
        "ambiguitySecondaryJa",
    ]
        .every((key) => isSeededRecord(contacts[key]))
        && isSeededRecord(companies.primary)
        && isSeededRecord(companies.archive)
        && isSeededRecord(deals.primary)
        && isSeededRecord(activities.evidence);
}

/** Loads and validates the fixture owned by the current browser project. */
export function runFixture(projectName: string): RunFixture {
    const fixturePath = runFixturePath(tenantScopeForProject(projectName));
    const fixture: unknown = JSON.parse(readFileSync(fixturePath, "utf8"));
    if (!isRunFixture(fixture)) {
        throw new Error(`Invalid E2E run fixture at ${fixturePath}`);
    }
    return fixture;
}
