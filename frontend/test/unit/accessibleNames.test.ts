import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

const ROOT = process.cwd();
const SOURCE_ROOTS = [join(ROOT, "app"), join(ROOT, "components")];
const LINK_TAGS = new Set(["Link", "a"]);
const NAMING_ATTRIBUTES = new Set(["aria-label", "aria-labelledby", "title"]);
const MESSAGES_ROOT = join(ROOT, "messages");
const LOCALES = ["en", "ja"] as const;

/** The dialogs whose photo pickers are a bare file input behind a label that renders no text. */
const PHOTO_PICKERS = [
    {
        file: "app/components/records/contacts/NewContactDialog.tsx",
        catalog: "contacts",
        namespace: "ContactsNewContactDialog",
    },
    {
        file: "app/components/records/companies/NewCompanyDialog.tsx",
        catalog: "companies",
        namespace: "CompaniesNewDialog",
    },
] as const;

const RULE =
    "A link whose only content is an avatar must name itself: put aria-label (or aria-labelledby) on "
    + "the link. AvatarImage renders no <img> when a person has no photo, so alternative text on the "
    + "image cannot be the link's only name. alt=\"\" is for an avatar beside text the link already "
    + "exposes (WCAG 2.4.4).";

/** A link that renders an avatar and hands a screen reader nothing to announce. */
type UnnamedLink = { file: string; line: number };

type JsxElementNode = ts.JsxElement | ts.JsxSelfClosingElement;

function sourceFiles(directory: string): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const path = join(directory, entry.name);
        if (entry.isDirectory()) found.push(...sourceFiles(path));
        else if (entry.name.endsWith(".tsx")) found.push(path);
    }
    return found;
}

function parse(source: string, file: string): ts.SourceFile {
    return ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
}

function tagName(node: JsxElementNode): string {
    const opening = ts.isJsxElement(node) ? node.openingElement : node;
    return opening.tagName.getText();
}

function attributes(node: JsxElementNode): ts.JsxAttributes {
    return ts.isJsxElement(node) ? node.openingElement.attributes : node.attributes;
}

function attribute(node: JsxElementNode, name: string): ts.JsxAttribute | undefined {
    return attributes(node).properties.find(
        (property): property is ts.JsxAttribute =>
            ts.isJsxAttribute(property) && property.name.getText() === name,
    );
}

function isJsxElementNode(node: ts.Node): node is JsxElementNode {
    return ts.isJsxElement(node) || ts.isJsxSelfClosingElement(node);
}

function descendants(node: ts.Node, matches: (candidate: JsxElementNode) => boolean): JsxElementNode[] {
    const found: JsxElementNode[] = [];
    node.forEachChild(function visit(child) {
        if (isJsxElementNode(child) && matches(child)) found.push(child);
        child.forEachChild(visit);
    });
    return found;
}

function avatarImages(link: ts.JsxElement): JsxElementNode[] {
    return descendants(link, (candidate) => tagName(candidate) === "AvatarImage");
}

function namesItself(link: ts.JsxElement): boolean {
    return attributes(link).properties.some(
        (property) => ts.isJsxAttribute(property) && NAMING_ATTRIBUTES.has(property.name.getText()),
    );
}

function rendersTextBesideAvatar(link: ts.JsxElement): boolean {
    let found = false;
    const visit = (node: ts.Node): void => {
        if (isJsxElementNode(node) && tagName(node) === "Avatar") return;
        if (ts.isJsxAttributes(node)) return;
        if (ts.isJsxText(node) && node.text.trim().length > 0) found = true;
        if (ts.isJsxExpression(node) && node.expression !== undefined) found = true;
        node.forEachChild(visit);
    };
    link.children.forEach(visit);
    return found;
}

/** What one pass over a source file saw: the nameless links, and how many links it judged. */
type Scan = { findings: UnnamedLink[]; watched: number };

/**
 * Reads every link that renders an avatar and decides whether it exposes a name — its own label, or
 * text beside the avatar. Alternative text on the image does not count, because the image is not
 * rendered at all for a person without a photo.
 * @param source the file's text
 * @param file the path reported with each finding
 * @returns the nameless links in source order, and how many avatar-bearing links were judged
 */
export function scanAvatarLinks(source: string, file: string): Scan {
    const parsed = parse(source, file);
    const findings: UnnamedLink[] = [];
    let watched = 0;
    for (const link of descendants(parsed, (candidate) => LINK_TAGS.has(tagName(candidate)))) {
        if (!ts.isJsxElement(link)) continue;
        const images = avatarImages(link);
        if (images.length === 0) continue;
        watched += 1;
        if (namesItself(link)) continue;
        if (rendersTextBesideAvatar(link)) continue;
        findings.push({
            file,
            line: parsed.getLineAndCharacterOfPosition(link.getStart(parsed)).line + 1,
        });
    }
    return { findings, watched };
}

function scanProbe(source: string): UnnamedLink[] {
    return scanAvatarLinks(source, "Probe.tsx").findings;
}

const SCANNED = SOURCE_ROOTS.flatMap(sourceFiles).reduce<Scan>(
    (total, path) => {
        const scan = scanAvatarLinks(readFileSync(path, "utf8"), relative(ROOT, path));
        return {
            findings: [...total.findings, ...scan.findings],
            watched: total.watched + scan.watched,
        };
    },
    { findings: [], watched: 0 },
);

describe("avatar link names", () => {
    it("leaves no link nameless behind an avatar", () => {
        expect(SCANNED.findings.map((finding) => `${finding.file}:${finding.line}`), RULE).toEqual([]);
    });

    it("still watches the links that render an avatar today", () => {
        expect(SCANNED.watched).toBeGreaterThanOrEqual(6);
    });

    it("catches an avatar-only link whose image has no alternative text", () => {
        const source = `export const Row = () => (
            <Link href={\`/records/contacts/\${person.id}\`}>
                <Avatar>
                    <AvatarImage src={person.imageUrl} />
                    <AvatarFallback>{initial}</AvatarFallback>
                </Avatar>
            </Link>
        );`;

        expect(scanProbe(source)).toHaveLength(1);
    });

    it("catches an avatar-only link whose image is marked decorative", () => {
        const source = `export const Row = () => (
            <Link href="/records/contacts/1">
                <Avatar>
                    <AvatarImage src={person.imageUrl} alt="" />
                    <AvatarFallback>{initial}</AvatarFallback>
                </Avatar>
            </Link>
        );`;

        expect(scanProbe(source)).toHaveLength(1);
    });

    it("catches an avatar-only link that relies on alternative text the image may never render", () => {
        const source = `export const Row = () => (
            <Link href="/records/contacts/1" className="nodrag">
                <Avatar>
                    <AvatarImage src={person.imageUrl} alt={person.name} />
                    <AvatarFallback>{initial}</AvatarFallback>
                </Avatar>
            </Link>
        );`;

        expect(scanProbe(source)).toHaveLength(1);
    });

    it("does not mistake an attribute beside the avatar for visible text", () => {
        const source = `export const Row = () => (
            <Link href="/records/contacts/1">
                <Avatar>
                    <AvatarImage src={person.imageUrl} alt={person.name} />
                </Avatar>
                <span className={badgeClass} />
            </Link>
        );`;

        expect(scanProbe(source)).toHaveLength(1);
    });

    it("accepts a decorative avatar beside text the link already exposes", () => {
        const source = `export const Row = () => (
            <Link href="/records/contacts/1" onClick={() => open(person)}>
                <Avatar>
                    <AvatarImage src={person.imageUrl} alt="" />
                    <AvatarFallback>{initial}</AvatarFallback>
                </Avatar>
                <p>{person.name}</p>
            </Link>
        );`;

        expect(scanProbe(source)).toEqual([]);
    });

    it("accepts an avatar-only link that labels itself", () => {
        const source = `export const Row = () => (
            <Link href="/records/contacts/1" aria-label={person.name}>
                <Avatar>
                    <AvatarImage src={person.imageUrl} alt="" />
                </Avatar>
            </Link>
        );`;

        expect(scanProbe(source)).toEqual([]);
    });
});

function fileInputs(source: string, file: string): JsxElementNode[] {
    return descendants(parse(source, file), (candidate) => {
        if (tagName(candidate) !== "input") return false;
        const type = attribute(candidate, "type");
        return type !== undefined
            && type.initializer !== undefined
            && ts.isStringLiteral(type.initializer)
            && type.initializer.text === "file";
    });
}

function translationKey(input: JsxElementNode): string | null {
    const label = attribute(input, "aria-label");
    if (label === undefined || label.initializer === undefined) return null;
    if (!ts.isJsxExpression(label.initializer)) return null;
    const expression = label.initializer.expression;
    if (expression === undefined || !ts.isCallExpression(expression)) return null;
    const [argument] = expression.arguments;
    return argument !== undefined && ts.isStringLiteralLike(argument) ? argument.text : null;
}

function message(locale: (typeof LOCALES)[number], catalog: string, namespace: string, key: string): unknown {
    const parsed: unknown = JSON.parse(
        readFileSync(join(MESSAGES_ROOT, locale, `${catalog}.json`), "utf8"),
    );
    if (typeof parsed !== "object" || parsed === null) return undefined;
    const section: unknown = (parsed as Record<string, unknown>)[namespace];
    if (typeof section !== "object" || section === null) return undefined;
    return (section as Record<string, unknown>)[key];
}

describe("photo picker names", () => {
    it.each(PHOTO_PICKERS)("names every file input in $file", ({ file, catalog, namespace }) => {
        const inputs = fileInputs(readFileSync(join(ROOT, file), "utf8"), file);

        expect(inputs.length).toBeGreaterThan(0);
        for (const input of inputs) {
            const key = translationKey(input);
            expect(key, `${file} has a file input with no translated aria-label`).not.toBeNull();
            for (const locale of LOCALES) {
                expect(message(locale, catalog, namespace, key ?? "")).toBeTruthy();
            }
        }
    });
});
