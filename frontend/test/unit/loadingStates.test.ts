import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { SHIPPED_APP_ROUTES } from "@/app/lib/routeManifest";

const APP_ROOT = path.join(process.cwd(), "app", "(app)");
const EXCEPTIONS_PATH = path.join(process.cwd(), "lint", "loading-skeleton-exceptions.json");

const EXCEPTION_KINDS = ["redirect-only", "resolver-redirect", "shares-parent-skeleton"] as const;

type ExceptionKind = (typeof EXCEPTION_KINDS)[number];

type SkeletonException = {
    route: string;
    kind: ExceptionKind;
    reason: string;
};

type SkeletonExceptionLedger = {
    highWaterMark: number;
    exceptions: SkeletonException[];
};

function isJsonObject(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isExceptionKind(value: unknown): value is ExceptionKind {
    return typeof value === "string" && (EXCEPTION_KINDS as readonly string[]).includes(value);
}

function readLedger(): SkeletonExceptionLedger {
    const parsed: unknown = JSON.parse(readFileSync(EXCEPTIONS_PATH, "utf8"));
    if (!isJsonObject(parsed)) throw new Error("loading-skeleton-exceptions.json is not an object");
    const { highWaterMark, exceptions } = parsed;
    if (typeof highWaterMark !== "number") throw new Error("highWaterMark must be a number");
    if (!Array.isArray(exceptions)) throw new Error("exceptions must be an array");
    const entries = exceptions.map((entry) => {
        if (!isJsonObject(entry)) throw new Error("every exception must be an object");
        const { route, kind, reason } = entry;
        if (typeof route !== "string") throw new Error("every exception needs a route");
        if (!isExceptionKind(kind)) throw new Error(`unknown exception kind for ${route}`);
        if (typeof reason !== "string") throw new Error(`every exception needs a reason: ${route}`);
        return { route, kind, reason } satisfies SkeletonException;
    });
    return { highWaterMark, exceptions: entries };
}

function routeDirectory(route: string): string {
    return path.join(APP_ROOT, ...route.split("/").filter((segment) => segment.length > 0));
}

function ownsSkeleton(route: string): boolean {
    return existsSync(path.join(routeDirectory(route), "loading.tsx"));
}

function readRouteFile(route: string, file: string): string {
    return readFileSync(path.join(routeDirectory(route), file), "utf8");
}

function ancestorRoutes(route: string): string[] {
    const segments = route.split("/").filter((segment) => segment.length > 0);
    const ancestors: string[] = [];
    for (let depth = segments.length - 1; depth > 0; depth -= 1) {
        ancestors.push(`/${segments.slice(0, depth).join("/")}`);
    }
    return ancestors;
}

/** The nearest ancestor directory (self excluded) holding a `layout.tsx`, as a route path. */
function nearestAncestorLayout(route: string): string | null {
    for (const ancestor of ancestorRoutes(route)) {
        if (existsSync(path.join(routeDirectory(ancestor), "layout.tsx"))) return ancestor;
    }
    return existsSync(path.join(APP_ROOT, "layout.tsx")) ? "/" : null;
}

function layoutSource(route: string): string {
    const file = route === "/"
        ? path.join(APP_ROOT, "layout.tsx")
        : path.join(routeDirectory(route), "layout.tsx");
    return readFileSync(file, "utf8");
}

/** Every `.tsx` source file under `app/`, so a shell rule can be checked everywhere the shell is used. */
function appSources(directory = path.join(process.cwd(), "app")): string[] {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) return appSources(full);
        return entry.name.endsWith(".tsx") ? [full] : [];
    });
}

/**
 * The `PageShell` opening tag a routed surface renders, normalized for whitespace. Comparing the whole
 * tag — not one prop — is what keeps a skeleton and its page from drifting apart on any wrapper concern.
 */
function pageShellTag(source: string): string | null {
    const match = /<PageShell(\s[^>]*)?>/.exec(source);
    if (!match) return null;
    return `<PageShell${(match[1] ?? "").replace(/\s+/g, " ").trimEnd()}>`;
}

const SKELETON_PRIMITIVE = "@/components/ui/skeleton";

/** Every module specifier a source file imports from. */
function importSources(source: string): string[] {
    return [...source.matchAll(/from\s+["']([^"']+)["']/g)].map((match) => match[1]);
}

type SharedSkeletonConsumer = {
    /** Repo-relative path of the module that must render the shared skeleton. */
    file: string;
    /** The specifier that module imports it by — alias from a route, relative from a sibling. */
    specifier: string;
};

type SharedSkeleton = {
    /** Repo-relative path of the skeleton component itself. */
    component: string;
    consumers: SharedSkeletonConsumer[];
};

/**
 * The shared skeletons and every module that must render them.
 *
 * A route's `loading.tsx` and the client component it stands in for both draw the first load: the
 * route paints while the server resolves, the component paints while its own fetch resolves. When
 * they hold separate copies of the markup the reader watches one set of bones swap for another, so
 * each pair here imports a single module instead. Re-forking one of these is the regression this
 * guards.
 */
const SHARED_SKELETONS: SharedSkeleton[] = [
    {
        component: "app/components/organization/OrganizationOverviewSkeleton.tsx",
        consumers: [
            {
                file: "app/(app)/organization/overview/loading.tsx",
                specifier: "@/app/components/organization/OrganizationOverviewSkeleton",
            },
            {
                file: "app/components/organization/OrganizationOverviewPanel.tsx",
                specifier: "@/app/components/organization/OrganizationOverviewSkeleton",
            },
        ],
    },
    {
        component: "app/components/settings/QualificationCriteriaSkeleton.tsx",
        consumers: [
            {
                file: "app/(app)/settings/qualification/loading.tsx",
                specifier: "@/app/components/settings/QualificationCriteriaSkeleton",
            },
            {
                file: "app/components/settings/QualificationCriteriaPanel.tsx",
                specifier: "@/app/components/settings/QualificationCriteriaSkeleton",
            },
        ],
    },
    {
        component: "app/components/settings/workflows/operations/WorkflowRunDetailSkeleton.tsx",
        consumers: [
            {
                file: "app/(app)/workflows/[workflowId]/runs/[runKey]/loading.tsx",
                specifier: "@/app/components/settings/workflows/operations/WorkflowRunDetailSkeleton",
            },
            {
                file: "app/components/settings/workflows/operations/WorkflowRunOperationsDetail.tsx",
                specifier: "@/app/components/settings/workflows/operations/WorkflowRunDetailSkeleton",
            },
        ],
    },
    {
        component: "app/components/settings/workflows/recipes/WorkflowRecipeDetailSkeleton.tsx",
        consumers: [
            {
                file: "app/(app)/workflows/recipes/[recipeKey]/loading.tsx",
                specifier: "@/app/components/settings/workflows/recipes/WorkflowRecipeDetailSkeleton",
            },
            {
                file: "app/components/settings/workflows/recipes/WorkflowRecipeGallery.tsx",
                specifier: "@/app/components/settings/workflows/recipes/WorkflowRecipeDetailSkeleton",
            },
        ],
    },
    {
        component: "app/components/diagnostics/DiagnosticsPanelSkeleton.tsx",
        consumers: [
            {
                file: "app/(app)/organization/diagnostics/loading.tsx",
                specifier: "@/app/components/diagnostics/DiagnosticsPanelSkeleton",
            },
            {
                file: "app/(app)/settings/diagnostics/loading.tsx",
                specifier: "@/app/components/diagnostics/DiagnosticsPanelSkeleton",
            },
            {
                file: "app/components/diagnostics/DiagnosticsPanel.tsx",
                specifier: "./DiagnosticsPanelSkeleton",
            },
        ],
    },
];

const ledger = readLedger();
const excepted = new Map(ledger.exceptions.map((entry) => [entry.route, entry]));
const skeletonRoutes = SHIPPED_APP_ROUTES.filter((route) => ownsSkeleton(route));

describe("route loading skeletons", () => {
    it("gives every shipped route its own loading.tsx or a documented exception", () => {
        const uncovered = SHIPPED_APP_ROUTES.filter((route) => !ownsSkeleton(route) && !excepted.has(route));

        expect(
            uncovered,
            `${uncovered.length} route(s) arrive with no skeleton. Add a loading.tsx that mirrors the destination `
            + "layout, or record the exception and its reason in frontend/lint/loading-skeleton-exceptions.json.",
        ).toEqual([]);
    });

    it("holds no exception for a route that now has its own skeleton", () => {
        const stale = ledger.exceptions.filter((entry) => ownsSkeleton(entry.route)).map((entry) => entry.route);

        expect(stale, "the exception list only shrinks: delete entries whose route grew a loading.tsx").toEqual([]);
    });

    it("names only shipped routes, once each, in order", () => {
        const routes = ledger.exceptions.map((entry) => entry.route);
        const shipped = new Set<string>(SHIPPED_APP_ROUTES);

        expect(routes).toEqual([...new Set(routes)].sort());
        expect(routes.filter((route) => !shipped.has(route))).toEqual([]);
    });

    it("never grows past the committed high-water mark", () => {
        expect(
            ledger.exceptions.length,
            "raise highWaterMark only in the commit that ships routes which genuinely need no skeleton",
        ).toBeLessThanOrEqual(ledger.highWaterMark);
    });

    it("explains every exception", () => {
        const unexplained = ledger.exceptions
            .filter((entry) => entry.reason.trim().length < 40)
            .map((entry) => entry.route);

        expect(unexplained, "each exception states why the route needs no skeleton of its own").toEqual([]);
    });

    it("proves each redirect exception actually redirects", () => {
        const redirects = ledger.exceptions.filter(
            (entry) => entry.kind === "redirect-only" || entry.kind === "resolver-redirect",
        );
        const notRedirecting = redirects
            .filter((entry) => !/\b(?:permanentRedirect|redirect)\(/.test(readRouteFile(entry.route, "page.tsx")))
            .map((entry) => entry.route);

        expect(redirects.length).toBeGreaterThan(0);
        expect(notRedirecting, "a redirect exception must render nothing and redirect").toEqual([]);
    });

    it("proves each shared-skeleton exception has an ancestor skeleton to share", () => {
        const shared = ledger.exceptions.filter((entry) => entry.kind === "shares-parent-skeleton");
        const orphaned = shared
            .filter((entry) => !ancestorRoutes(entry.route).some((ancestor) => ownsSkeleton(ancestor)))
            .map((entry) => entry.route);

        expect(orphaned, "a shared-skeleton exception needs an ancestor loading.tsx that covers it").toEqual([]);
    });
});

describe("loading skeletons mirror rather than decorate", () => {
    it("covers most of the shipped surface", () => {
        expect(skeletonRoutes.length).toBeGreaterThan(60);
    });

    it("draws bones, never a spinner or a progress word", () => {
        const decorated = skeletonRoutes.filter((route) => {
            const source = readRouteFile(route, "loading.tsx");
            return /animate-spin|Spinner|Loader\b|LoaderIcon|role="status"/.test(source);
        });

        expect(
            decorated,
            "first loads mirror the destination layout; a spinner or progress text is not a loading strategy",
        ).toEqual([]);
    });

    it("says nothing, because a skeleton has no copy to localize", () => {
        const talkative = skeletonRoutes.filter((route) =>
            /useTranslations|getTranslations/.test(readRouteFile(route, "loading.tsx")));

        expect(talkative, "a skeleton that needs words is showing words instead of shape").toEqual([]);
    });

    it("builds every skeleton out of the shared Skeleton primitive", () => {
        const handRolled = skeletonRoutes.filter((route) => {
            const sources = importSources(readRouteFile(route, "loading.tsx"));
            return !sources.includes(SKELETON_PRIMITIVE) && !sources.some((source) => source.endsWith("Skeleton"));
        });

        expect(handRolled, "reuse @/components/ui/skeleton or a shared *Skeleton component").toEqual([]);
    });

    it("never repeats a PageShell its layout already owns", () => {
        const doubled = skeletonRoutes.filter((route) => {
            const layoutRoute = nearestAncestorLayout(route);
            if (layoutRoute === null) return false;
            if (!/<PageShell/.test(layoutSource(layoutRoute))) return false;
            return /<PageShell/.test(readRouteFile(route, "loading.tsx"));
        });

        expect(doubled, "a layout that owns the shell leaves its children's skeletons shell-free").toEqual([]);
    });

    it("keeps a route and the component it stands in for on one set of bones", () => {
        const missing: string[] = [];
        const forked: string[] = [];

        for (const shared of SHARED_SKELETONS) {
            if (!existsSync(path.join(process.cwd(), shared.component))) {
                missing.push(shared.component);
                continue;
            }
            for (const consumer of shared.consumers) {
                const consumerPath = path.join(process.cwd(), consumer.file);
                if (!existsSync(consumerPath)) {
                    missing.push(consumer.file);
                    continue;
                }
                if (!importSources(readFileSync(consumerPath, "utf8")).includes(consumer.specifier)) {
                    forked.push(`${consumer.file} no longer renders ${shared.component}`);
                }
            }
        }

        expect(missing, "the shared-skeleton ledger names a module that no longer exists").toEqual([]);
        expect(
            forked,
            "a consumer dropped the shared skeleton; re-forking it makes the reader watch bones swap for bones",
        ).toEqual([]);
    });

    it("pairs every shared skeleton with more than one consumer", () => {
        const lonely = SHARED_SKELETONS
            .filter((shared) => shared.consumers.length < 2)
            .map((shared) => shared.component);

        expect(lonely, "a skeleton with one consumer is not shared; inline it or record its second reader").toEqual([]);
    });

    it("lets no consumer of a shared skeleton define a second one locally", () => {
        const localCopies: string[] = [];

        for (const shared of SHARED_SKELETONS) {
            for (const consumer of shared.consumers) {
                const consumerPath = path.join(process.cwd(), consumer.file);
                if (!existsSync(consumerPath)) continue;
                const source = readFileSync(consumerPath, "utf8");
                if (/\nfunction \w*(?:Detail|Overview|Panel|Criteria)Skeleton\(/.test(source)) {
                    localCopies.push(consumer.file);
                }
            }
        }

        expect(localCopies, "delete the local skeleton and render the shared one instead").toEqual([]);
    });

    it("agrees with its page about the page wrapper", () => {
        const disagreements = skeletonRoutes
            .filter((route) => existsSync(path.join(routeDirectory(route), "page.tsx")))
            .map((route) => ({
                route,
                page: pageShellTag(readRouteFile(route, "page.tsx")),
                skeleton: pageShellTag(readRouteFile(route, "loading.tsx")),
            }))
            .filter((entry) => entry.page !== null && entry.page !== entry.skeleton);

        expect(
            disagreements,
            "a skeleton and its page must not disagree about the wrapper, or the page jumps when data arrives",
        ).toEqual([]);
    });

    it("caps no page at the shell", () => {
        const capped = appSources()
            .filter((file) => /<PageShell\s[^>]*max-w-/.test(readFileSync(file, "utf8")))
            .map((file) => path.relative(process.cwd(), file));

        expect(
            capped,
            "pages span the full content area; a readable measure belongs on the text block, never on the shell",
        ).toEqual([]);
    });
});
