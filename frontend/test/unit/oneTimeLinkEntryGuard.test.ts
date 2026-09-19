import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";

const FRONTEND = process.cwd();
const APP = path.join(FRONTEND, "app");
const READER_MODULE = path.join(APP, "lib", "oneTimeLink.ts");
const READER_CALL = "takeOneTimeLinkToken";
const RELOAD_HOOK = "useReloadOnFragmentNavigation";
const SOURCE_ROOTS = ["app", "components", "lib"].map((dir) => path.join(FRONTEND, dir));
const CODE_EXTENSIONS = [".ts", ".tsx"];
const ROUTE_BOUNDARY_FILES = ["layout", "template", "error"];

/**
 * One-time-link entry routes (#1588) that must keep being discovered. The guard derives its entries
 * from the source rather than from this list; the list only stops a broken scan passing vacuously.
 */
const KNOWN_ENTRY_ROUTES = [
    "invite",
    "invite-link",
    "auth/reset-password",
    "auth/verify-email",
    "auth/confirm-email",
    "auth/confirm-passkey",
    "unsubscribe",
    "document-acceptance",
];

/** Components that fall back to `router.refresh()` unless the named prop supplies a local retry. */
const CONDITIONAL_REFRESHERS = new Map([
    [path.join(APP, "components", "WorkspaceUnavailableRetry.tsx"), "onRetry"],
]);

type Resolution =
    | { kind: "package" }
    | { kind: "asset" }
    | { kind: "code"; file: string }
    | { kind: "unresolved"; specifier: string };

const parsed = new Map<string, ts.SourceFile>();
const importsByFile = new Map<string, Resolution[]>();

/** Parses a source file once, with parent pointers so call sites can find their enclosing component. */
function parse(file: string): ts.SourceFile {
    const cached = parsed.get(file);
    if (cached) return cached;
    const source = ts.createSourceFile(
        file,
        readFileSync(file, "utf8"),
        ts.ScriptTarget.Latest,
        true,
        file.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
    );
    parsed.set(file, source);
    return source;
}

/** Walks every node of a parsed file depth-first. */
function visit(node: ts.Node, onNode: (node: ts.Node) => void) {
    onNode(node);
    ts.forEachChild(node, (child) => visit(child, onNode));
}

/** Lists the source-relative path of a node for violation messages. */
function location(file: string, node: ts.Node): string {
    const source = parse(file);
    const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
    return `${path.relative(FRONTEND, file)}:${line + 1}`;
}

/** Resolves an import specifier the way the `@/*` path alias and relative imports do. */
function resolveModule(from: string, specifier: string): Resolution {
    let base: string;
    if (specifier.startsWith("@/")) {
        base = path.join(FRONTEND, specifier.slice(2));
    } else if (specifier.startsWith(".")) {
        base = path.resolve(path.dirname(from), specifier);
    } else {
        return { kind: "package" };
    }
    const candidates = [
        base,
        ...CODE_EXTENSIONS.map((extension) => `${base}${extension}`),
        ...CODE_EXTENSIONS.map((extension) => path.join(base, `index${extension}`)),
    ];
    const file = candidates.find((candidate) => existsSync(candidate) && statSync(candidate).isFile());
    if (!file) return { kind: "unresolved", specifier };
    return CODE_EXTENSIONS.includes(path.extname(file)) ? { kind: "code", file } : { kind: "asset" };
}

/** Resolves every runtime import of a file: static imports, re-exports and dynamic `import()`. */
function runtimeImports(file: string): Resolution[] {
    const cached = importsByFile.get(file);
    if (cached) return cached;
    const specifiers: string[] = [];
    visit(parse(file), (node) => {
        if (ts.isImportDeclaration(node) && !node.importClause?.isTypeOnly && ts.isStringLiteral(node.moduleSpecifier)) {
            specifiers.push(node.moduleSpecifier.text);
        } else if (
            ts.isExportDeclaration(node)
            && !node.isTypeOnly
            && node.moduleSpecifier
            && ts.isStringLiteral(node.moduleSpecifier)
        ) {
            specifiers.push(node.moduleSpecifier.text);
        } else if (
            ts.isCallExpression(node)
            && node.expression.kind === ts.SyntaxKind.ImportKeyword
            && node.arguments.length > 0
            && ts.isStringLiteral(node.arguments[0])
        ) {
            specifiers.push(node.arguments[0].text);
        }
    });
    const resolved = specifiers.map((specifier) => resolveModule(file, specifier));
    importsByFile.set(file, resolved);
    return resolved;
}

/** Collects every local code module reachable from the roots, plus any local import it cannot resolve. */
function reachable(roots: string[]): { modules: Set<string>; unresolved: string[] } {
    const modules = new Set<string>();
    const unresolved: string[] = [];
    const pending = [...roots];
    while (pending.length > 0) {
        const file = pending.pop();
        if (file === undefined || modules.has(file)) continue;
        modules.add(file);
        for (const resolution of runtimeImports(file)) {
            if (resolution.kind === "code") pending.push(resolution.file);
            if (resolution.kind === "unresolved") {
                unresolved.push(`${path.relative(FRONTEND, file)} -> ${resolution.specifier}`);
            }
        }
    }
    return { modules, unresolved };
}

/** Lists every file under a directory whose name satisfies the predicate. */
function filesUnder(dir: string, accept: (name: string) => boolean): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) return filesUnder(full, accept);
        return accept(entry.name) ? [full] : [];
    });
}

/** Maps a route page to its URL path, dropping route groups such as `(app)`. */
function routeOf(page: string): string {
    return path.relative(APP, path.dirname(page))
        .split(path.sep)
        .filter((segment) => segment !== "" && !/^\(.*\)$/.test(segment))
        .join("/");
}

/** Lists the layouts, templates and error boundaries that wrap a page, plus the global error boundary. */
function boundaryChain(page: string): string[] {
    const chain: string[] = [];
    for (let dir = path.dirname(page); dir.startsWith(APP); dir = path.dirname(dir)) {
        for (const name of ROUTE_BOUNDARY_FILES) {
            for (const extension of CODE_EXTENSIONS) {
                const candidate = path.join(dir, `${name}${extension}`);
                if (existsSync(candidate)) chain.push(candidate);
            }
        }
        if (dir === APP) break;
    }
    const globalError = path.join(APP, "global-error.tsx");
    if (existsSync(globalError)) chain.push(globalError);
    return chain;
}

/** Every route page whose runtime import graph reaches the one-time-link bearer reader. */
function entryPages(): string[] {
    return filesUnder(APP, (name) => name === "page.tsx")
        .filter((page) => reachable([page]).modules.has(READER_MODULE));
}

/**
 * Every source module, outside the reader itself, that references the bearer reader. Each one reads
 * the bearer once on mount, so it must reload on a same-tab fragment navigation or a second emailed
 * link would linger in the URL while the page kept the previous flow's state.
 */
function readerModules(): string[] {
    return SOURCE_ROOTS
        .filter((root) => existsSync(root))
        .flatMap((root) => filesUnder(root, (name) => CODE_EXTENSIONS.includes(path.extname(name))))
        .filter((file) => file !== READER_MODULE && readFileSync(file, "utf8").includes(READER_CALL));
}

/** Finds the outermost function around a node: the component whose render owns the call. */
function outermostFunction(node: ts.Node): ts.Node | null {
    let outermost: ts.Node | null = null;
    for (let current = node.parent; current !== undefined; current = current.parent) {
        if (
            ts.isFunctionDeclaration(current)
            || ts.isFunctionExpression(current)
            || ts.isArrowFunction(current)
            || ts.isMethodDeclaration(current)
        ) {
            outermost = current;
        }
    }
    return outermost;
}

/** Reports whether a subtree calls the named identifier. */
function callsIdentifier(root: ts.Node, name: string): boolean {
    let found = false;
    visit(root, (node) => {
        if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === name) {
            found = true;
        }
    });
    return found;
}

/** Reports whether a file imports anything from `next/navigation`, the source of `router.refresh`. */
function usesNextRouter(file: string): boolean {
    return parse(file).statements.some((statement) =>
        ts.isImportDeclaration(statement)
        && ts.isStringLiteral(statement.moduleSpecifier)
        && statement.moduleSpecifier.text === "next/navigation");
}

/** Local names a file binds to a conditional refresher, mapped to the prop that disarms it. */
function conditionalRefresherBindings(file: string): Map<string, string> {
    const bindings = new Map<string, string>();
    for (const statement of parse(file).statements) {
        if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue;
        const resolution = resolveModule(file, statement.moduleSpecifier.text);
        if (resolution.kind !== "code") continue;
        const requiredProp = CONDITIONAL_REFRESHERS.get(resolution.file);
        const clause = statement.importClause;
        if (requiredProp === undefined || clause === undefined) continue;
        if (clause.name) bindings.set(clause.name.text, requiredProp);
        if (clause.namedBindings && ts.isNamedImports(clause.namedBindings)) {
            for (const element of clause.namedBindings.elements) bindings.set(element.name.text, requiredProp);
        }
    }
    return bindings;
}

/**
 * Router-state triggers in one module that would re-publish a stripped bearer. Next's app router
 * seeds `canonicalUrl` from `location.href` before `takeOneTimeLinkToken()` strips the fragment and
 * never learns about that strip, so any later router action on the entry (a refresh, a server
 * action, or a retry control that falls back to a refresh) makes `HistoryUpdater` write the stale
 * canonical URL, bearer included, back into the address bar and the current history entry.
 */
function routerTriggers(file: string): string[] {
    const violations: string[] = [];
    const refreshes = usesNextRouter(file) && !CONDITIONAL_REFRESHERS.has(file);
    const refresherBindings = conditionalRefresherBindings(file);
    visit(parse(file), (node) => {
        if (
            ts.isExpressionStatement(node)
            && ts.isStringLiteral(node.expression)
            && node.expression.text === "use server"
        ) {
            violations.push(`${location(file, node)} declares a server action`);
        }
        if (refreshes && ts.isPropertyAccessExpression(node) && node.name.text === "refresh") {
            violations.push(`${location(file, node)} refreshes the router`);
        }
        if (
            refreshes
            && ts.isBindingElement(node)
            && (node.propertyName ?? node.name).getText(parse(file)) === "refresh"
        ) {
            violations.push(`${location(file, node)} binds the router's refresh`);
        }
        if ((ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) && ts.isIdentifier(node.tagName)) {
            const requiredProp = refresherBindings.get(node.tagName.text);
            const disarmed = requiredProp === undefined || node.attributes.properties.some((attribute) =>
                ts.isJsxAttribute(attribute) && attribute.name.getText(parse(file)) === requiredProp);
            if (!disarmed) {
                violations.push(`${location(file, node)} renders a router-refreshing retry without ${requiredProp}`);
            }
        }
    });
    return violations;
}

/**
 * Reads of Next's `retry` error-boundary prop. Next 16.3 passes `retry` (not `unstable_retry`) and
 * its implementation calls `router.refresh()` before resetting, which re-publishes a stripped bearer
 * on any entry page that throws during render.
 */
function routerRetries(file: string): string[] {
    const violations: string[] = [];
    visit(parse(file), (node) => {
        if (ts.isBindingElement(node) && (node.propertyName ?? node.name).getText(parse(file)) === "retry") {
            violations.push(`${location(file, node)} reads the router-refreshing retry prop`);
        }
        if (ts.isPropertyAccessExpression(node) && node.name.text === "retry") {
            violations.push(`${location(file, node)} reads the router-refreshing retry prop`);
        }
    });
    return violations;
}

describe("one-time-link entry guard", () => {
    const pages = entryPages();

    it("discovers every entry page that reads a one-time-link bearer", () => {
        expect(pages.map(routeOf)).toEqual(expect.arrayContaining(KNOWN_ENTRY_ROUTES));
    });

    it("reloads every bearer-reading component on a same-tab fragment navigation", () => {
        const readers = readerModules();
        const violations = readers.flatMap((file) => {
            const calls: ts.CallExpression[] = [];
            visit(parse(file), (node) => {
                if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === READER_CALL) {
                    calls.push(node);
                }
            });
            return calls
                .filter((call) => {
                    const component = outermostFunction(call);
                    return component === null || !callsIdentifier(component, RELOAD_HOOK);
                })
                .map((call) => `${location(file, call)} reads the bearer without ${RELOAD_HOOK}()`);
        });

        expect(readers.length).toBeGreaterThanOrEqual(KNOWN_ENTRY_ROUTES.length);
        expect(violations).toEqual([]);
    });

    it("dispatches no router action that would restore the stripped bearer from canonicalUrl", () => {
        const violations = pages.flatMap((page) => {
            const { modules, unresolved } = reachable([page, ...boundaryChain(page)]);
            return [
                ...unresolved.map((edge) => `${routeOf(page)}: unresolved import ${edge}`),
                ...[...modules].flatMap((file) => routerTriggers(file).map((found) => `${routeOf(page)}: ${found}`)),
            ];
        });

        expect(violations).toEqual([]);
    });

    it("keeps error boundaries above an entry page off the router-refreshing retry", () => {
        const boundaries = new Set(pages.flatMap((page) => boundaryChain(page)
            .filter((file) => /^(global-)?error\.tsx?$/.test(path.basename(file)))));
        const violations = [...boundaries].flatMap(routerRetries);

        expect(boundaries.size).toBeGreaterThan(0);
        expect(violations).toEqual([]);
    });
});
