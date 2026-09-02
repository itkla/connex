import { act, createElement, type ComponentType, type ReactNode } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import DiagnosticsPanel from "@/app/components/diagnostics/DiagnosticsPanel";
import { BuildIdentitySection } from "@/app/components/diagnostics/BuildIdentitySection";
import { ApiError } from "@/app/lib/api";
import {
    resolveBuildIdentity,
    resolveBuildMetadata,
    type ReleaseProvenanceEvidence,
} from "@/app/lib/buildIdentity";
import type { ProductVersion } from "@/app/lib/types";
import englishMessages from "@/messages/en/workspace.json";
import japaneseMessages from "@/messages/ja/workspace.json";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const TestIntlProvider = NextIntlClientProvider as ComponentType<{
    children?: ReactNode;
    locale: "en" | "ja";
    messages: typeof englishMessages | typeof japaneseMessages;
    timeZone: string;
}>;

const BUILD_IDENTITY_COPY_KEYS = [
    "buildIdentityTitle",
    "buildIdentityDescription",
    "buildIdentityRefresh",
    "buildIdentityRefreshing",
    "buildIdentityMatched",
    "buildIdentityMatchedBody",
    "buildIdentityVersionAgreementUnverified",
    "buildIdentityVersionAgreementUnverifiedBody",
    "buildIdentityMismatched",
    "buildIdentityMismatchedBody",
    "buildIdentityBackendUnavailable",
    "buildIdentityBackendUnavailableBody",
    "buildIdentityUnversioned",
    "buildIdentityUnversionedBody",
    "buildIdentityFrontendVersion",
    "buildIdentityBackendVersion",
    "buildIdentityBuildTime",
    "buildIdentityGitSha",
    "buildIdentityUnavailable",
    "buildIdentityNotStamped",
] as const;

const {
    aggregateDiagnosticsMock,
    getVersionMock,
} = vi.hoisted(() => ({
    aggregateDiagnosticsMock: vi.fn(),
    getVersionMock: vi.fn(),
}));

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        getOrgDiagnostics: aggregateDiagnosticsMock,
        getVersion: getVersionMock,
        getWorkspaceDiagnostics: aggregateDiagnosticsMock,
    };
});

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspace: { id: 7, orgId: 11 } }),
}));

vi.mock("@/app/components/SectionBoundary", () => ({
    default: ({ children }: { children: import("react").ReactNode }) => children,
}));

vi.mock("@/app/components/diagnostics/JobRunsSection", () => ({
    JobRunsSection: () => null,
}));

vi.mock("@/app/components/diagnostics/MailDeliverabilitySection", () => ({
    MailDeliverabilitySection: () => null,
}));

vi.mock("@/app/components/diagnostics/ProfileCapabilitiesSection", () => ({
    ProfileCapabilitiesSection: () => null,
}));

vi.mock("@/app/components/diagnostics/ProviderReadinessSection", () => ({
    ProviderReadinessSection: () => null,
}));

vi.mock("@/app/components/diagnostics/SecretStoreSection", () => ({
    SecretStoreSection: () => null,
}));

function backendVersion(version: string): ProductVersion {
    return {
        version,
        buildTime: "2026-09-02T12:00:00Z",
        gitSha: "0123456789abcdef",
    };
}

function requiredElement(
    elements: readonly InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    label: string,
): InteractiveElement {
    const element = elements.find(predicate);
    if (!element) throw new Error(`${label} did not render`);
    return element;
}

async function renderBuildIdentity(
    frontendVersion: string | undefined,
    releaseProvenance: ReleaseProvenanceEvidence | null = null,
    locale: "en" | "ja" = "en",
) {
    if (frontendVersion === undefined) vi.stubEnv("NEXT_PUBLIC_APP_VERSION", "");
    else vi.stubEnv("NEXT_PUBLIC_APP_VERSION", frontendVersion);
    const interactive = installInteractiveDocument();
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(interactive.container);
    await act(async () => {
        root.render(createElement(
            TestIntlProvider,
            { locale, messages: locale === "en" ? englishMessages : japaneseMessages, timeZone: "UTC" },
            createElement(BuildIdentitySection, { releaseProvenance }),
        ));
    });
    return {
        ...interactive,
        unmount: async () => act(async () => root.unmount()),
    };
}

afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("build identity", () => {
    it.each([
        {
            cell: "equal versions with matching provenance",
            frontend: "1.4.0",
            backend: backendVersion("1.4.0"),
            provenance: { frontend: "release-set-857", backend: "release-set-857" },
            outcome: "matched",
        },
        {
            cell: "equal versions with no provenance",
            frontend: "1.4.0",
            backend: backendVersion("1.4.0"),
            provenance: null,
            outcome: "version-agreement-unverified",
        },
        {
            cell: "equal versions with frontend provenance only",
            frontend: "1.4.0",
            backend: backendVersion("1.4.0"),
            provenance: { frontend: "release-set-857", backend: null },
            outcome: "version-agreement-unverified",
        },
        {
            cell: "equal versions with backend provenance only",
            frontend: "1.4.0",
            backend: backendVersion("1.4.0"),
            provenance: { frontend: null, backend: "release-set-857" },
            outcome: "version-agreement-unverified",
        },
        {
            cell: "equal versions with conflicting provenance",
            frontend: "1.4.0",
            backend: backendVersion("1.4.0"),
            provenance: { frontend: "release-set-857-a", backend: "release-set-857-b" },
            outcome: "mismatched",
        },
        {
            cell: "different versions with matching provenance",
            frontend: "1.4.0",
            backend: backendVersion("1.4.1"),
            provenance: { frontend: "release-set-857", backend: "release-set-857" },
            outcome: "mismatched",
        },
        {
            cell: "different versions with no provenance",
            frontend: "1.4.0",
            backend: backendVersion("1.4.1"),
            provenance: null,
            outcome: "mismatched",
        },
        {
            cell: "source-build sentinel on the frontend",
            frontend: "source-ci",
            backend: backendVersion("1.4.0"),
            provenance: { frontend: "release-set-857", backend: "release-set-857" },
            outcome: "unversioned",
        },
        {
            cell: "source-build sentinel on the backend",
            frontend: "1.4.0",
            backend: backendVersion("source-ci"),
            provenance: { frontend: "release-set-857", backend: "release-set-857" },
            outcome: "unversioned",
        },
        {
            cell: "unreachable backend",
            frontend: "1.4.0",
            backend: null,
            provenance: { frontend: "release-set-857", backend: "release-set-857" },
            outcome: "backend-unavailable",
        },
    ] as const)("resolves $cell as $outcome", ({ frontend, backend, provenance, outcome }) => {
        expect(resolveBuildIdentity(frontend, backend, provenance)).toMatchObject({
            kind: outcome,
            frontendVersion: frontend,
        });
    });

    it("treats an absent frontend version as unversioned", () => {
        expect(resolveBuildIdentity(undefined, backendVersion("0.9.0-test"))).toMatchObject({
            kind: "unversioned",
            frontendVersion: null,
        });
    });

    it.each([
        ["dev", "dev"],
        ["0.0.0-dev", "ci"],
        ["ci", "0.9.0-test"],
        ["source-ci", "source-ci"],
        ["0.0.1-SNAPSHOT", "0.9.0-test"],
        ["unspecified", "0.9.0-test"],
        ["branch-build", "branch-build"],
        ["0.9.0-test", "0.0.0-dev"],
        ["0.9.0-test", "0.0.1-SNAPSHOT"],
        ["0.9.0-test", "unspecified"],
    ])("treats %s and %s as an unversioned development pair", (frontend, backend) => {
        expect(resolveBuildIdentity(frontend, backendVersion(backend))).toMatchObject({
            kind: "unversioned",
            frontendVersion: frontend,
        });
    });

    it.each([null, "", "  ", "unknown", " UNKNOWN "])(
        "treats %j as unavailable build metadata",
        (metadata) => {
            expect(resolveBuildMetadata(metadata)).toBeNull();
        },
    );

    it("preserves available build metadata", () => {
        expect(resolveBuildMetadata(" 2026-09-02T12:00:00Z ")).toBe("2026-09-02T12:00:00Z");
    });
});

describe("build identity section", () => {
    it.each([
        {
            locale: "en",
            messages: englishMessages,
            implementationWords: /\b(?:frontend|backend|artifact provenance|release evidence|release set|build|component|deploy|source commit|stamped)\b/i,
        },
        {
            locale: "ja",
            messages: japaneseMessages,
            implementationWords: /フロントエンド|バックエンド|成果物の出所|リリースエビデンス|リリースセット|ビルド|構成要素|デプロイ|ソースコミット/,
        },
    ] as const)("keeps the full Build identity copy register plain in $locale", ({ messages, implementationWords }) => {
        const copy = BUILD_IDENTITY_COPY_KEYS
            .map((key) => messages.TenantDiagnostics[key])
            .join("\n");

        expect(copy).not.toMatch(implementationWords);
    });

    it.each([
        {
            locale: "en",
            heading: "Release confirmed",
            body: "The app you see in your browser and the server show the same version, and we confirmed that they were released together.",
        },
        {
            locale: "ja",
            heading: "リリースを確認できました",
            body: "ブラウザーに表示されているアプリとサーバーには同じバージョンが表示されており、一緒にリリースされたものであることを確認できました。",
        },
    ] as const)("renders matched heading and body copy in $locale", async ({ locale, heading, body }) => {
        getVersionMock.mockResolvedValueOnce(backendVersion("1.4.0"));

        const rendered = await renderBuildIdentity(
            "1.4.0",
            { frontend: "release-set-857", backend: "release-set-857" },
            locale,
        );

        expect(rendered.container.textContent).toContain(heading);
        expect(rendered.container.textContent).toContain(body);
        expect(rendered.container.textContent).not.toContain(
            "Versions match, but the release could not be confirmed",
        );
        await rendered.unmount();
    });

    it("renders the from-source stamp and metadata as an unversioned build", async () => {
        getVersionMock.mockResolvedValueOnce(backendVersion("source-ci"));

        const rendered = await renderBuildIdentity("source-ci");

        expect(rendered.container.textContent).toContain("Release version not recorded");
        expect(rendered.container.textContent).not.toContain("Release confirmed");
        expect(rendered.container.textContent).toContain("source-ci");
        expect(rendered.container.textContent).toContain("2026-09-02T12:00:00Z");
        expect(rendered.container.textContent).toContain("0123456789abcdef");
        await rendered.unmount();
    });

    it.each([
        {
            locale: "en",
            heading: "Versions match, but the release could not be confirmed",
            body: "The app you see in your browser and the server show the same version, but we could not confirm that they were released together.",
        },
        {
            locale: "ja",
            heading: "バージョンは一致していますが、リリースを確認できません",
            body: "ブラウザーに表示されているアプリとサーバーには同じバージョンが表示されていますが、一緒にリリースされたものか確認できませんでした。",
        },
    ] as const)("renders version-agreement-unverified heading and body copy in $locale", async ({ locale, heading, body }) => {
        getVersionMock.mockResolvedValueOnce(backendVersion("1.4.0"));

        const rendered = await renderBuildIdentity("1.4.0", null, locale);
        const status = requiredElement(
            rendered.elements,
            (element) => element.tagName === "SPAN"
                && element.textContent.includes(heading),
            "Unverified version agreement status",
        );

        expect(status.getAttribute("class")).toContain("bg-muted");
        expect(rendered.container.textContent).toContain(heading);
        expect(rendered.container.textContent).toContain(body);
        expect(rendered.container.textContent).not.toContain("Release confirmed");
        expect(rendered.container.textContent.match(/1\.4\.0/g)).toHaveLength(2);
        expect(rendered.container.textContent).toContain("2026-09-02T12:00:00Z");
        expect(rendered.container.textContent).toContain("0123456789abcdef");
        await rendered.unmount();
    });

    it("renders mismatched release values with warning tone and mismatch copy", async () => {
        getVersionMock.mockResolvedValueOnce(backendVersion("1.4.1"));

        const rendered = await renderBuildIdentity("1.4.0");
        const status = requiredElement(
            rendered.elements,
            (element) => element.tagName === "SPAN"
                && element.textContent.includes("The app and server do not match"),
            "Mismatched release status",
        );

        expect(status.getAttribute("class")).toContain("bg-amber-500/10");
        expect(rendered.container.textContent).toContain(
            "The app you see in your browser and the server show different versions, or we confirmed that they were not released together. Ask the person who manages this installation to update them together.",
        );
        expect(rendered.container.textContent).toContain("1.4.0");
        expect(rendered.container.textContent).toContain("1.4.1");
        await rendered.unmount();
    });

    it("renders an unavailable response with a reference and retries into a loaded state", async () => {
        getVersionMock
            .mockRejectedValueOnce(new ApiError(
                "backend unavailable",
                503,
                undefined,
                undefined,
                "request-857",
            ))
            .mockResolvedValueOnce(backendVersion("1.4.0"));

        const rendered = await renderBuildIdentity("1.4.0");
        const retry = requiredElement(
            rendered.elements,
            (element) => element.tagName === "BUTTON" && element.textContent === "Try again",
            "Build identity retry",
        );

        expect(rendered.container.textContent).toContain("The server did not answer");
        expect(rendered.container.textContent).toContain("Reference ID request-857");
        expect(rendered.container.textContent).toContain("Unavailable");
        await act(async () => {
            rendered.dispatch("click", retry);
        });
        expect(getVersionMock).toHaveBeenCalledTimes(2);
        expect(rendered.container.textContent).toContain(
            "Versions match, but the release could not be confirmed",
        );
        expect(rendered.container.textContent).not.toContain("The server did not answer");
        await rendered.unmount();
    });

    it.each([
        ["en", "Unavailable"],
        ["ja", "取得できません"],
    ] as const)("renders unknown metadata as unavailable in %s", async (locale, unavailable) => {
        getVersionMock.mockResolvedValueOnce({
            version: "1.4.0",
            buildTime: "unknown",
            gitSha: " UNKNOWN ",
        });

        const rendered = await renderBuildIdentity("1.4.0", null, locale);

        expect(rendered.container.textContent.match(new RegExp(unavailable, "g"))).toHaveLength(2);
        expect(rendered.container.textContent.toLowerCase()).not.toContain("unknown");
        await rendered.unmount();
    });

    it("keeps build identity available when the aggregate diagnostics request fails", async () => {
        aggregateDiagnosticsMock.mockRejectedValueOnce(new Error("aggregate unavailable"));
        getVersionMock.mockResolvedValue(backendVersion("1.4.0"));
        vi.stubEnv("NEXT_PUBLIC_APP_VERSION", "1.4.0");
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);

        await act(async () => {
            root.render(createElement(
                TestIntlProvider,
                { locale: "en", messages: englishMessages, timeZone: "UTC" },
                createElement(DiagnosticsPanel, { scope: "workspace" }),
            ));
        });

        expect(interactive.container.textContent).toContain(
            "The diagnostics report didn't load.",
        );
        expect(interactive.container.textContent).toContain(
            "Versions match, but the release could not be confirmed",
        );
        expect(interactive.container.textContent).toContain("1.4.0");
        await act(async () => root.unmount());
    });
});
