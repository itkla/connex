import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { pinsToRenderedWorkspace } from '@/app/lib/renderedWorkspace';

type FunctionBody = (ts.FunctionDeclaration | ts.FunctionExpression | ts.ArrowFunction | ts.MethodDeclaration) & { body: ts.ConciseBody };
type WriteHelper = { optionsIndex: number };
type Bindings = Map<ts.ParameterDeclaration, { expression: ts.Expression; bindings: Bindings; at: ts.CallExpression }>;
type Write = {
    call: ts.CallExpression;
    options: ts.Expression | undefined;
    bindings: Bindings;
    awaited: boolean;
    position: number;
    branches: Map<ts.Node, ts.Node>;
    repeated: boolean;
};

function visit(node: ts.Node, action: (node: ts.Node) => void): void {
    action(node);
    ts.forEachChild(node, (child) => visit(child, action));
}
function parse(file: string, source = readFileSync(file, 'utf8')): ts.SourceFile {
    return ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
}
function functionBody(node: ts.Node): node is FunctionBody {
    return (ts.isFunctionDeclaration(node) || ts.isFunctionExpression(node) || ts.isArrowFunction(node) || ts.isMethodDeclaration(node)) && node.body !== undefined;
}
function contains(parent: ts.Node, child: ts.Node): boolean {
    return child.pos >= parent.pos && child.end <= parent.end;
}
function functionName(fn: FunctionBody): string | null {
    if (fn.name && ts.isIdentifier(fn.name)) return fn.name.text;
    let parent: ts.Node = fn.parent;
    if (ts.isCallExpression(parent) && (ts.isIdentifier(parent.expression) && ['useCallback', 'useMemo'].includes(parent.expression.text)
        || ts.isPropertyAccessExpression(parent.expression) && ['useCallback', 'useMemo'].includes(parent.expression.name.text))) parent = parent.parent;
    return ts.isVariableDeclaration(parent) && ts.isIdentifier(parent.name) ? parent.name.text : null;
}
function importNames(source: ts.SourceFile, module: string): Map<string, string> {
    const names = new Map<string, string>();
    for (const node of source.statements) {
        if (!ts.isImportDeclaration(node) || !ts.isStringLiteral(node.moduleSpecifier) || node.moduleSpecifier.text !== module) continue;
        const bindings = node.importClause?.namedBindings;
        if (bindings && ts.isNamespaceImport(bindings)) names.set(bindings.name.text, '*');
        if (bindings && ts.isNamedImports(bindings)) {
            for (const specifier of bindings.elements) names.set(specifier.name.text, (specifier.propertyName ?? specifier.name).text);
        }
    }
    return names;
}
function importedName(expression: ts.Expression, names: Map<string, string>): string | undefined {
    if (ts.isIdentifier(expression)) return names.get(expression.text);
    if (ts.isPropertyAccessExpression(expression) && ts.isIdentifier(expression.expression)
        && names.get(expression.expression.text) === '*') return expression.name.text;
    return undefined;
}
function route(expression: ts.Expression | undefined, source: ts.SourceFile): string | null {
    if (!expression) return null;
    if (ts.isStringLiteralLike(expression)) return expression.text;
    if (ts.isTemplateExpression(expression)) {
        return expression.head.text + expression.templateSpans.map((span) => '1' + span.literal.text).join('');
    }
    if (ts.isIdentifier(expression)) {
        let result: string | null = null;
        visit(source, (node) => {
            if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.name.text === expression.text && node.initializer) {
                if (node.initializer !== expression && !ts.isIdentifier(node.initializer)) result = route(node.initializer, source);
            }
        });
        return result;
    }
    return null;
}
function mutatingOptions(expression: ts.Expression): boolean {
    return ts.isObjectLiteralExpression(expression) && expression.properties.some((property) =>
        ts.isPropertyAssignment(property) && property.name.getText() === 'method'
        && ts.isStringLiteralLike(property.initializer) && !['GET', 'HEAD', 'OPTIONS'].includes(property.initializer.text));
}
function discoverWrites(source: ts.SourceFile): Map<string, WriteHelper> {
    const functions: FunctionBody[] = [];
    for (const statement of source.statements) {
        if (functionBody(statement)) functions.push(statement);
        if (ts.isVariableStatement(statement)) {
            for (const variable of statement.declarationList.declarations) {
                if (variable.initializer && functionBody(variable.initializer)) functions.push(variable.initializer);
            }
        }
    }
    const mutators = new Set<string>();
    const writes = new Map<string, WriteHelper>();
    let grew = true;
    while (grew) {
        grew = false;
        for (const fn of functions) {
            const name = functionName(fn);
            if (!name || mutators.has(name)) continue;
            let mutates = false;
            visit(fn.body, (node) => {
                if (!ts.isCallExpression(node)) return;
                if (ts.isPropertyAccessExpression(node.expression) && node.expression.name.text === 'open'
                    && node.arguments[0] && ts.isStringLiteralLike(node.arguments[0])
                    && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(node.arguments[0].text)) {
                    const requestPath = route(node.arguments[1], source);
                    if (requestPath === null || pinsToRenderedWorkspace(requestPath)) mutates = true;
                }
                if (!ts.isIdentifier(node.expression)) return;
                const primitive = node.expression.text;
                const methodArgument = node.arguments.find((argument) => ts.isStringLiteralLike(argument) && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(argument.text));
                const direct = ['requestJson', 'requestMultipart', 'requestReportJson'].includes(primitive)
                    && (node.arguments.some(mutatingOptions) || methodArgument !== undefined);
                if (!direct && !mutators.has(primitive)) return;
                const requestPath = route(node.arguments[0], source);
                if (requestPath === null || pinsToRenderedWorkspace(requestPath)) mutates = true;
            });
            if (!mutates) continue;
            mutators.add(name);
            grew = true;
            const declaration = ts.isVariableDeclaration(fn.parent) && ts.isVariableDeclarationList(fn.parent.parent)
                && ts.isVariableStatement(fn.parent.parent.parent) ? fn.parent.parent.parent : fn;
            if (declaration.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword)) {
                writes.set(name, { optionsIndex: fn.parameters.findIndex((parameter) => parameter.type?.getText(source) === 'RequestInit') });
            }
        }
    }
    return writes;
}
const writeHelpers = discoverWrites(parse(path.join(process.cwd(), 'app/lib/api.ts')));

function scan(source: ts.SourceFile, helpers = writeHelpers): string[] {
    const imported = importNames(source, '@/app/lib/api');
    const pinImports = importNames(source, '@/app/lib/renderedWorkspace');
    const functions: FunctionBody[] = [];
    const calls: ts.CallExpression[] = [];
    const variables: ts.VariableDeclaration[] = [];
    visit(source, (node) => {
        if (functionBody(node)) functions.push(node);
        if (ts.isCallExpression(node)) calls.push(node);
        if (ts.isVariableDeclaration(node)) variables.push(node);
    });
    function owner(node: ts.Node): FunctionBody | ts.SourceFile {
        let current = node.parent;
        while (current && !functionBody(current) && !ts.isSourceFile(current)) current = current.parent;
        return functionBody(current) ? current : source;
    }
    function declaration(identifier: ts.Identifier): ts.VariableDeclaration | ts.ParameterDeclaration | undefined {
        let scope = owner(identifier);
        for (;;) {
            const parameter = functionBody(scope) ? scope.parameters.find((item) => ts.isIdentifier(item.name) && item.name.text === identifier.text) : undefined;
            if (parameter) return parameter;
            const variable = variables.find((item) => ts.isIdentifier(item.name) && item.name.text === identifier.text && owner(item) === scope);
            if (variable) return variable;
            if (ts.isSourceFile(scope)) return undefined;
            scope = owner(scope);
        }
    }
    function localFunction(call: ts.CallExpression): FunctionBody | undefined {
        if (!ts.isIdentifier(call.expression)) return undefined;
        const name = call.expression.text;
        return functions.find((fn) => functionName(fn) === name && contains(owner(fn), call));
    }
    function pinned(expression: ts.Expression | undefined, bindings: Bindings, seen = new Set<ts.Node>()): boolean {
        if (!expression || seen.has(expression)) return false;
        const next = new Set(seen).add(expression);
        if (ts.isCallExpression(expression) && importedName(expression.expression, pinImports) === 'workspacePinnedRequest') {
            const scope = owner(expression);
            let firstAwait = Number.POSITIVE_INFINITY;
            visit(scope, (node) => {
                if (ts.isAwaitExpression(node) && owner(node) === scope) firstAwait = Math.min(firstAwait, node.getStart(source));
            });
            const workspace = expression.arguments[0];
            const captured = workspace && ts.isIdentifier(workspace) ? declaration(workspace) : undefined;
            if (repeated(expression, scope) && (captured === undefined || repeated(captured, scope))) return false;
            return expression.getStart(source) < firstAwait
                || captured !== undefined && ts.isVariableDeclaration(captured)
                && ts.isVariableDeclarationList(captured.parent)
                && (captured.parent.flags & ts.NodeFlags.Const) !== 0
                && captured.getStart(source) < firstAwait;
        }
        if (ts.isIdentifier(expression)) {
            const declared = declaration(expression);
            if (!declared || next.has(declared)) return false;
            next.add(declared);
            if (ts.isVariableDeclaration(declared)) return pinned(declared.initializer, bindings, next);
            const supplied = bindings.get(declared);
            if (supplied) return pinned(supplied.expression, supplied.bindings, next);
            const fn = owner(declared);
            if (!functionBody(fn)) return false;
            const index = fn.parameters.indexOf(declared);
            const callers = calls.filter((call) => localFunction(call) === fn);
            return callers.length > 0 && callers.every((call) => pinned(call.arguments[index], bindings, next));
        }
        if (ts.isBinaryExpression(expression) && expression.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken) {
            const left = ts.isIdentifier(expression.left) ? declaration(expression.left) : undefined;
            if (!pinned(expression.right, bindings, next)) return false;
            if (!left || !ts.isParameter(left)) return pinned(expression.left, bindings, next);
            const supplied = bindings.get(left);
            if (supplied) return supplied.expression.kind === ts.SyntaxKind.UndefinedKeyword
                || ts.isIdentifier(supplied.expression) && supplied.expression.text === 'undefined'
                || pinned(supplied.expression, supplied.bindings, next);
            const fn = owner(left);
            const index = functionBody(fn) ? fn.parameters.indexOf(left) : -1;
            const callers = calls.filter((call) => localFunction(call) === fn);
            return callers.every((call) => call.arguments[index] === undefined
                || ts.isIdentifier(call.arguments[index]) && call.arguments[index].getText(source) === 'undefined'
                || pinned(call.arguments[index], bindings, next));
        }
        if (ts.isParenthesizedExpression(expression)) return pinned(expression.expression, bindings, next);
        if (ts.isObjectLiteralExpression(expression)) return expression.properties.some((property) => ts.isSpreadAssignment(property) && pinned(property.expression, bindings, next));
        return false;
    }
    function branches(node: ts.Node, stop: ts.Node): Map<ts.Node, ts.Node> {
        const result = new Map<ts.Node, ts.Node>();
        let child = node;
        for (let parent = node.parent; parent && parent !== stop; child = parent, parent = parent.parent) {
            if (ts.isIfStatement(parent) && (parent.thenStatement === child || parent.elseStatement === child)) result.set(parent, child);
            if (ts.isConditionalExpression(parent) && (parent.whenTrue === child || parent.whenFalse === child)) result.set(parent, child);
            if (ts.isCaseClause(parent) || ts.isDefaultClause(parent)) result.set(parent.parent, parent);
            if (ts.isBinaryExpression(parent) && [ts.SyntaxKind.QuestionQuestionToken, ts.SyntaxKind.BarBarToken].includes(parent.operatorToken.kind)) {
                result.set(parent, child);
            }
        }
        return result;
    }
    function awaited(node: ts.Node, stop: ts.Node): boolean {
        for (let current: ts.Node | undefined = node.parent; current && current !== stop; current = current.parent) {
            if (ts.isAwaitExpression(current)) return true;
        }
        return false;
    }
    function repeated(node: ts.Node, stop: ts.Node): boolean {
        for (let current: ts.Node | undefined = node.parent; current && current !== stop; current = current.parent) {
            if (ts.isForOfStatement(current) || ts.isForInStatement(current) || ts.isForStatement(current) || ts.isWhileStatement(current) || ts.isDoStatement(current)) return true;
        }
        return false;
    }
    function events(fn: FunctionBody, bindings: Bindings = new Map(), stack = new Set<FunctionBody>()): Write[] {
        if (stack.has(fn)) return [];
        const next = new Set(stack).add(fn);
        const found: Write[] = [];
        function truth(expression: ts.Expression, seen = new Set<ts.Node>()): boolean | null {
            if (seen.has(expression)) return null;
            seen.add(expression);
            if (ts.isPrefixUnaryExpression(expression) && expression.operator === ts.SyntaxKind.ExclamationToken) {
                const value = truth(expression.operand, seen);
                return value === null ? null : !value;
            }
            if (!ts.isIdentifier(expression)) return null;
            const declared = declaration(expression);
            if (declared && ts.isVariableDeclaration(declared) && declared.initializer) return truth(declared.initializer, seen);
            const supplied = declared && ts.isParameter(declared) ? bindings.get(declared) : undefined;
            if (!supplied) return null;
            const suppliedText = supplied.expression.getText(source);
            for (let parent: ts.Node | undefined = supplied.at.parent; parent && !functionBody(parent); parent = parent.parent) {
                if (!ts.isIfStatement(parent) || !contains(parent.thenStatement, supplied.at)) continue;
                function guaranteesValue(condition: ts.Expression): boolean {
                    if (ts.isParenthesizedExpression(condition)) return guaranteesValue(condition.expression);
                    if (!ts.isBinaryExpression(condition)) return false;
                    if (condition.operatorToken.kind === ts.SyntaxKind.BarBarToken) return guaranteesValue(condition.left) && guaranteesValue(condition.right);
                    if (condition.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken) return guaranteesValue(condition.left) || guaranteesValue(condition.right);
                    return condition.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken
                        && ts.isStringLiteralLike(condition.right)
                        && ts.isPropertyAccessExpression(condition.left) && condition.left.questionDotToken !== undefined
                        && condition.left.expression.getText(source) === suppliedText;
                }
                if (guaranteesValue(parent.expression)) return true;
            }
            return null;
        }
        function walk(node: ts.Node): void {
            if (ts.isIfStatement(node)) {
                const decision = truth(node.expression);
                if (decision !== null) {
                    if (decision) walk(node.thenStatement);
                    else if (node.elseStatement) walk(node.elseStatement);
                    return;
                }
            }
            if (node !== fn.body && functionBody(node)) return;
            if (ts.isCallExpression(node)) {
                const apiName = importedName(node.expression, imported);
                const helper = apiName ? helpers.get(apiName) : undefined;
                if (helper) {
                    found.push({ call: node, options: helper.optionsIndex < 0 ? undefined : node.arguments[helper.optionsIndex], bindings, awaited: awaited(node, fn), position: node.getStart(source), branches: branches(node, fn), repeated: repeated(node, fn) });
                } else {
                    const target = localFunction(node);
                    if (target) {
                        const supplied = new Map(bindings);
                        target.parameters.forEach((parameter, index) => {
                            const expression = node.arguments[index];
                            if (expression) supplied.set(parameter, { expression, bindings, at: node });
                        });
                        for (const event of events(target, supplied, next)) {
                            found.push({ ...event, position: node.getStart(source), awaited: awaited(node, fn) && event.awaited, repeated: event.repeated || repeated(node, fn), branches: new Map([...event.branches, ...branches(node, fn)]) });
                        }
                    }
                }
                if (ts.isPropertyAccessExpression(node.expression) && ['map', 'forEach', 'then', 'catch', 'finally'].includes(node.expression.name.text)) {
                    for (const argument of node.arguments) {
                        if (functionBody(argument)) {
                            for (const event of events(argument, bindings, next)) {
                                found.push({ ...event, position: node.getStart(source), branches: new Map([...event.branches, ...branches(node, fn)]) });
                            }
                        }
                    }
                }
            }
            ts.forEachChild(node, walk);
        }
        walk(fn.body);
        return found.sort((left, right) => left.position - right.position);
    }
    function exits(node: ts.Node): boolean {
        if (ts.isReturnStatement(node) || ts.isThrowStatement(node)) return true;
        if (ts.isBlock(node)) {
            const last = node.statements.at(-1);
            return last !== undefined && exits(last);
        }
        return ts.isIfStatement(node) && node.elseStatement !== undefined
            && exits(node.thenStatement) && exits(node.elseStatement);
    }
    const violations = new Set<string>();
    for (const fn of functions) {
        const writes = events(fn);
        const chained = new Set<Write>();
        for (let index = 0; index < writes.length; index += 1) {
            const earlier = writes[index];
            if (!earlier.awaited) continue;
            if (earlier.repeated) chained.add(earlier);
            for (const later of writes.slice(index + 1)) {
                if ([...earlier.branches].some(([branch, arm]) => later.branches.has(branch) && later.branches.get(branch) !== arm)) continue;
                if ([...earlier.branches].some(([branch, arm]) => ts.isIfStatement(branch) && exits(arm) && later.position >= branch.end)) continue;
                chained.add(earlier);
                chained.add(later);
            }
        }
        for (const write of chained) {
            if (pinned(write.options, write.bindings)) continue;
            const line = source.getLineAndCharacterOfPosition(write.call.getStart(source)).line + 1;
            violations.add(source.fileName + ':' + line + ' ' + write.call.expression.getText(source));
        }
    }
    return [...violations].sort();
}
function filesUnder(directory: string): string[] {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const file = path.join(directory, entry.name);
        return entry.isDirectory() ? filesUnder(file) : /\.tsx?$/.test(entry.name) ? [file] : [];
    });
}
const EXCEPTIONS: Record<string, string> = {
    'app/components/dashboard/customize/DashboardGrid.tsx:87 resetDashboardLayout': 'The queue drains separate user actions; each SaveJob carries its composed workspaceId, rather than recapturing a live selection per iteration.',
    'app/components/dashboard/customize/DashboardGrid.tsx:90 saveDashboardLayout': 'The queue drains separate user actions; each SaveJob carries its composed workspaceId, rather than recapturing a live selection per iteration.',
};
const found = [...filesUnder('app/components'), ...filesUnder('app/(app)')].flatMap((file) => scan(parse(file)));
const imports = "import { createContact, createContact as makeContact, uploadContactPicture, updateContact, login } from '@/app/lib/api'; import * as api from '@/app/lib/api'; import { workspacePinnedRequest, renderedWorkspaceId } from '@/app/lib/renderedWorkspace'; import * as workspace from '@/app/lib/renderedWorkspace';";
function probe(body: string): string[] { return scan(parse('Probe.tsx', imports + body)); }

describe('workspace write-chain inventory', () => {
    it('derives write helpers and their request-options positions from api.ts', () => {
        expect(writeHelpers.get('createContact')).toEqual({ optionsIndex: 1 });
        expect(writeHelpers.get('uploadContactPicture')).toEqual({ optionsIndex: 2 });
        expect(writeHelpers.has('getContactById')).toBe(false);
        expect(writeHelpers.has('login')).toBe(false);
        expect(writeHelpers.has('decideCaptureReview')).toBe(true);
        expect(writeHelpers.has('uploadAiChatAttachment')).toBe(true);
        expect(writeHelpers.size).toBeGreaterThan(100);
        const fresh = discoverWrites(parse('api.ts', 'function requestJson() {} function postJson(path, body, init) { return requestJson(path, { ...init, method: "POST" }); } export function freshWrite(payload, init: RequestInit = {}) { return postJson("/api/fresh", payload, init); } export const freshArrow = (payload, init: RequestInit = {}) => postJson("/api/fresh-arrow", payload, init);'));
        expect(fresh.get('freshWrite')).toEqual({ optionsIndex: 1 });
        expect(fresh.get('freshArrow')).toEqual({ optionsIndex: 1 });
    });
    it.each([
        'async function save() { await createContact({}); await uploadContactPicture(1, file); }',
        'async function save() { await api.createContact({}); await api.uploadContactPicture(1, file); }',
        'async function chain(init?: RequestInit) { const origin = init ?? workspacePinnedRequest(renderedWorkspaceId()); await createContact({}, origin); await uploadContactPicture(1, file, origin); } function save() { return chain({ signal }); }',
        'const one = useCallback(async () => { await createContact({}); }, []); const save = useCallback(async () => { for (const item of items) await one(); }, []);',
        'async function save(){ for(const item of items){ const origin=workspacePinnedRequest(renderedWorkspaceId()); await createContact(item,origin); } }',
        'async function save(init?: RequestInit) { await createContact({}, init); await uploadContactPicture(1, file, init); }',
        'async function save() { await createContact({}); files.map(file => uploadContactPicture(1, file)); }',
        'async function save() { for (const item of items) { await createContact(item); } }',
        'async function save() { await createContact({}); const origin = workspacePinnedRequest(renderedWorkspaceId()); await uploadContactPicture(1, file, origin); }',
        'async function follow(init?: RequestInit) { await uploadContactPicture(1, file, init); } async function save() { await createContact({}); await follow(); }',
    ])('rejects an unpinned chain: %s', (body) => { expect(probe(body).length).toBeGreaterThan(0); });
    it.each([
        'async function save() { const origin = workspacePinnedRequest(renderedWorkspaceId()); await createContact({}, origin); await uploadContactPicture(1, file, origin); }',
        'async function save() { const origin = workspacePinnedRequest(renderedWorkspaceId(), { signal }); const forwarded = origin; await createContact({}, forwarded); await uploadContactPicture(1, file, { ...forwarded, cache: "no-store" }); }',
        'async function chain(origin: RequestInit) { await createContact({}, origin); await uploadContactPicture(1, file, origin); } function save() { const origin = workspacePinnedRequest(renderedWorkspaceId()); return chain(origin); }',
        'async function save(init?: RequestInit) { const origin = init ?? workspacePinnedRequest(renderedWorkspaceId()); await createContact({}, origin); await uploadContactPicture(1, file, origin); }',
        'async function save() { if (existing) await updateContact(1, {}); else await createContact({}); }',
        'async function save() { const value = existing ? await updateContact(1, {}) : await createContact({}); }',
        'async function save() { await login({}); await createContact({}); }',
        'async function save() { if (existing) { await updateContact(1, {}); return; } await createContact({}); }',
        'async function save() { const workspace = renderedWorkspaceId(); await createContact({}, workspacePinnedRequest(workspace)); await uploadContactPicture(1, file, workspacePinnedRequest(workspace)); }',
        'async function save() { files.map(file => uploadContactPicture(1, file)); }',
        'async function save() { const origin = workspace.workspacePinnedRequest(workspace.renderedWorkspaceId()); await api.createContact({}, origin); await api.uploadContactPicture(1, file, origin); }',
        'async function save() { const origin = workspacePinnedRequest(renderedWorkspaceId()); await makeContact({}, origin); await uploadContactPicture(1, file, origin); }',
    ])('accepts a pinned chain or separate writes: %s', (body) => { expect(probe(body)).toEqual([]); });
    it('has no unpinned component/page chain outside the shrinking ledger', () => {
        expect(found.filter((entry) => !(entry in EXCEPTIONS))).toEqual([]);
    });
    it('keeps the ledger shrinking, with no stale or unexplained exception', () => {
        expect(Object.keys(EXCEPTIONS).filter((entry) => !found.includes(entry) || !EXCEPTIONS[entry].trim())).toEqual([]);
        expect(Object.keys(EXCEPTIONS).length).toBeLessThanOrEqual(2);
    });
});
