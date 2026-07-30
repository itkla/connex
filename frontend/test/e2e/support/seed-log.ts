import type { Locale } from "@/i18n/config";

/** Plaintext behind the seeder's precomputed BCrypt hash; every seeded user shares it. */
export const SEED_PASSWORD = "seeder-password";

/** Zero-based index of the Japanese persona (佐藤 美咲) within each seeded workspace's users. */
export const SEED_JAPANESE_USER_INDEX = 3;

const SEED_USER_LABELS = ["Seeder Owner", "Seeder Admin", "Jordan Lee", "佐藤 美咲", "Mika Johnson"] as const;
const SEED_USER_ROLES = ["owner", "admin", "member", "member", "member"] as const;

const SUMMARY_PATTERN = /Seeder summary workspace=(\d+) slug=(\S+) rowCounts=\{([^}]*)\}/g;
const SLUG_PATTERN = /^seed-workspace-([0-9a-z]+)-(\d+)$/;

/** One deterministically generated seeder account and the credentials a spec signs in with. */
export type SeedUser = {
    username: string;
    email: string;
    displayName: string;
    role: string;
    locale: Locale;
    timezone: string;
};

/** One seeded tenant: its logical identity plus the accounts and row counts it was created with. */
export type SeedWorkspace = {
    ordinal: number;
    slug: string;
    key: string;
    rowCounts: Record<string, number>;
    users: SeedUser[];
    japaneseUser: SeedUser;
};

/** Everything a spec needs to act as a seeded identity, derived from one seeder invocation. */
export type SeedFixture = {
    password: string;
    workspaces: SeedWorkspace[];
};

function parseRowCounts(raw: string): Record<string, number> {
    const counts: Record<string, number> = {};
    for (const entry of raw.split(",")) {
        const [name, value] = entry.split("=").map((part) => part.trim());
        if (name && value && /^\d+$/.test(value)) {
            counts[name] = Number(value);
        }
    }
    return counts;
}

function users(key: string, ordinal: number): SeedUser[] {
    return SEED_USER_LABELS.map((displayName, index) => {
        const identity = `${key}-w${ordinal}-u${index + 1}`;
        const japanese = index === SEED_JAPANESE_USER_INDEX;
        return {
            username: `seed-${identity}`,
            email: `${identity}@users.seed.invalid`,
            displayName,
            role: SEED_USER_ROLES[index],
            locale: japanese ? "ja" : "en",
            timezone: japanese ? "Asia/Tokyo" : "UTC",
        };
    });
}

/**
 * Derives the seeded identities from a `bash gradlew seedData` console log.
 *
 * The seeder's usernames embed a SplitMix64-derived workspace key that cannot be recomputed from
 * the `-PseederSeed` value without mirroring the hash in TypeScript. The same key appears in the
 * workspace slug that `SeedDataRunner` logs, so reading the log keeps one derivation in Java and
 * leaves this side a parser that fails loudly instead of a second implementation that can silently
 * drift.
 *
 * @param log captured stdout of the seeder invocation
 * @returns the seeded workspaces in the order they were created
 * @throws when the log contains no parseable summary line
 */
export function parseSeedLog(log: string): SeedFixture {
    const workspaces: SeedWorkspace[] = [];
    for (const match of log.matchAll(SUMMARY_PATTERN)) {
        const slug = match[2];
        const slugParts = SLUG_PATTERN.exec(slug);
        if (!slugParts) {
            throw new Error(`Unrecognized seeder workspace slug: ${slug}`);
        }
        const key = slugParts[1];
        const ordinal = Number(match[1]);
        const seeded = users(key, ordinal);
        workspaces.push({
            ordinal,
            slug,
            key,
            rowCounts: parseRowCounts(match[3]),
            users: seeded,
            japaneseUser: seeded[SEED_JAPANESE_USER_INDEX],
        });
    }
    if (workspaces.length === 0) {
        throw new Error("No 'Seeder summary workspace=... slug=...' line found in the seeder log");
    }
    return { password: SEED_PASSWORD, workspaces };
}
