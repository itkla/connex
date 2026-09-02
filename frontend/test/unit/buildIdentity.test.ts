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
}>;

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
            { locale, messages: locale === "en" ? englishMessages : japaneseMessages },
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
            heading: "Release versions match",
            body: "The frontend and backend carry matching verified release provenance.",
        },
        {
            locale: "ja",
            heading: "リリースバージョンが一致しています",
            body: "フロントエンドとバックエンドで、検証済みリリースの出所が一致しています。",
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
            "Versions agree — artifact provenance not verified",
        );
        await rendered.unmount();
    });

    it("renders the from-source stamp and metadata as an unversioned build", async () => {
        getVersionMock.mockResolvedValueOnce(backendVersion("source-ci"));

        const rendered = await renderBuildIdentity("source-ci");

        expect(rendered.container.textContent).toContain("Development or source build");
        expect(rendered.container.textContent).not.toContain("Release versions match");
        expect(rendered.container.textContent).toContain("source-ci");
        expect(rendered.container.textContent).toContain("2026-09-02T12:00:00Z");
        expect(rendered.container.textContent).toContain("0123456789abcdef");
        await rendered.unmount();
    });

    it.each([
        {
            locale: "en",
            heading: "Versions agree — artifact provenance not verified",
            body: "The frontend and backend report the same version, but they do not expose the release evidence needed to confirm a matched release set.",
        },
        {
            locale: "ja",
            heading: "バージョンは一致していますが、成果物の出所を確認できません",
            body: "フロントエンドとバックエンドは同じバージョンを示していますが、同じリリースセットだと確認するための情報は公開されていません。",
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
        expect(rendered.container.textContent).not.toContain("Release versions match");
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
                && element.textContent.includes("Release builds do not match"),
            "Mismatched release status",
        );

        expect(status.getAttribute("class")).toContain("bg-amber-500/10");
        expect(rendered.container.textContent).toContain(
            "The frontend and backend report different release versions or release evidence. Deploy them together from one release set.",
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

        expect(rendered.container.textContent).toContain("Backend version unavailable");
        expect(rendered.container.textContent).toContain("Reference ID request-857");
        expect(rendered.container.textContent).toContain("Unavailable");
        await act(async () => {
            rendered.dispatch("click", retry);
        });
        expect(getVersionMock).toHaveBeenCalledTimes(2);
        expect(rendered.container.textContent).toContain(
            "Versions agree — artifact provenance not verified",
        );
        expect(rendered.container.textContent).not.toContain("Backend version unavailable");
        await rendered.unmount();
    });

    it.each([
        ["en", "Unavailable"],
        ["ja", "取得不可"],
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
                { locale: "en", messages: englishMessages },
                createElement(DiagnosticsPanel, { scope: "workspace" }),
            ));
        });

        expect(interactive.container.textContent).toContain(
            "The diagnostics report didn't load.",
        );
        expect(interactive.container.textContent).toContain(
            "Versions agree — artifact provenance not verified",
        );
        expect(interactive.container.textContent).toContain("1.4.0");
        await act(async () => root.unmount());
    });
});
