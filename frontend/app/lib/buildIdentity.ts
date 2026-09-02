import type { ProductVersion } from "@/app/lib/types";

const DEVELOPMENT_VERSIONS = new Set([
    "0.0.0-dev",
    "0.0.1-snapshot",
]);

const RELEASE_VERSION = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?$/;

/** The honest comparison state for the browser and backend build versions. */
export type BuildIdentity =
    | {
        kind: "matched";
        frontendVersion: string;
        backendVersion: ProductVersion;
    }
    | {
        kind: "version-agreement-unverified";
        frontendVersion: string;
        backendVersion: ProductVersion;
    }
    | {
        kind: "mismatched";
        frontendVersion: string;
        backendVersion: ProductVersion;
    }
    | {
        kind: "backend-unavailable";
        frontendVersion: string | null;
        backendVersion: null;
    }
    | {
        kind: "unversioned";
        frontendVersion: string | null;
        backendVersion: ProductVersion;
    };

/** Authenticated release-workflow evidence exposed independently by both running artifacts. */
export type ReleaseProvenanceEvidence = Readonly<{
    frontend: string | null;
    backend: string | null;
}>;

function normalizedVersion(version: string | null | undefined): string | null {
    const normalized = version?.trim() ?? "";
    return normalized.length > 0 ? normalized : null;
}

function isReleaseVersion(version: string): boolean {
    return !DEVELOPMENT_VERSIONS.has(version.toLowerCase()) && RELEASE_VERSION.test(version);
}

/** Returns displayable build metadata, excluding empty and explicit unknown values. */
export function resolveBuildMetadata(value: string | null): string | null {
    const normalized = value?.trim() ?? "";
    if (normalized.length === 0 || normalized.toLowerCase() === "unknown") return null;
    return normalized;
}

/**
 * Resolves whether the browser and backend identify the same proven release artifact set.
 *
 * An unreachable backend takes precedence because no comparison can be made. Only the strict
 * semantic versions accepted by the release workflow count as release stamps; Docker defaults,
 * local development values, and source-build labels never count even when both components agree.
 * Equal release-looking versions remain unverified unless both artifacts expose non-empty
 * release-provenance values through a separately defined runtime contract. Equal values establish
 * a match; two present but unequal values establish a mismatch.
 */
export function resolveBuildIdentity(
    frontendVersion: string | null | undefined,
    backendVersion: ProductVersion | null,
    releaseProvenance: ReleaseProvenanceEvidence | null = null,
): BuildIdentity {
    const normalizedFrontendVersion = normalizedVersion(frontendVersion);
    if (backendVersion === null) {
        return {
            kind: "backend-unavailable",
            frontendVersion: normalizedFrontendVersion,
            backendVersion: null,
        };
    }

    const normalizedBackendVersion = normalizedVersion(backendVersion.version);
    if (
        normalizedFrontendVersion === null
        || normalizedBackendVersion === null
        || !isReleaseVersion(normalizedFrontendVersion)
        || !isReleaseVersion(normalizedBackendVersion)
    ) {
        return {
            kind: "unversioned",
            frontendVersion: normalizedFrontendVersion,
            backendVersion,
        };
    }

    if (normalizedFrontendVersion === normalizedBackendVersion) {
        const frontendProvenance = resolveBuildMetadata(releaseProvenance?.frontend ?? null);
        const backendProvenance = resolveBuildMetadata(releaseProvenance?.backend ?? null);
        if (frontendProvenance === null || backendProvenance === null) {
            return {
                kind: "version-agreement-unverified",
                frontendVersion: normalizedFrontendVersion,
                backendVersion,
            };
        }
        if (frontendProvenance !== backendProvenance) {
            return {
                kind: "mismatched",
                frontendVersion: normalizedFrontendVersion,
                backendVersion,
            };
        }
        return {
            kind: "matched",
            frontendVersion: normalizedFrontendVersion,
            backendVersion,
        };
    }

    return {
        kind: "mismatched",
        frontendVersion: normalizedFrontendVersion,
        backendVersion,
    };
}
